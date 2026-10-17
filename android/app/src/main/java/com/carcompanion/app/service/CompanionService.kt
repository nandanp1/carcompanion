package com.carcompanion.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.carcompanion.app.MainActivity
import com.carcompanion.app.R
import com.carcompanion.app.ble.BleClient
import com.carcompanion.app.location.*
import com.carcompanion.app.media.SpotifyMediaMonitor
import com.carcompanion.app.model.*
import com.carcompanion.app.navigation.MapsNotificationListener
import com.carcompanion.app.navigation.MockNavigationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Started from visible onboarding. BLE and GPS remain alive while Android Auto owns its audio. */
class CompanionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private lateinit var ble: BleClient
    private lateinit var gps: GpsSpeedMonitor
    private lateinit var media: SpotifyMediaMonitor
    private var initialized = false
    private var demoEnabled = false
    private var locationEnabled = false
    private var provider: SpeedLimitProvider = UnknownSpeedLimitProvider()
    private var limit = SpeedLimitState()
    private var limitReceivedElapsedMs = 0L
    private val artworkQueue = Channel<Artwork?>(Channel.CONFLATED)
    private val stateSendMutex = Mutex()
    private val mockNavigation = MockNavigationProvider()
    private var lastLookupElapsedMs = Long.MIN_VALUE
    private var lookupJob: Job? = null
    private var demoTick = 0
    private var demoArtworkJob: Job? = null
    private var demoGeneration = 0
    private var demoArtworkId: Long? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Driving display connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Car Companion connected and GPS speed updated"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean("service_enabled", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action != ACTION_RESUME_BLE && intent?.action != ACTION_SETTINGS) locationEnabled = true
        if (!enterForeground()) return START_NOT_STICKY
        if (!initialized) initialize()
        else if (locationEnabled && !demoEnabled) gps.start()
        prefs.edit().putBoolean("service_enabled", true).apply()
        intent?.getStringExtra(EXTRA_ADDRESS)?.takeIf { it.isNotBlank() }?.let { address ->
            prefs.edit().putString("device_address", address).apply()
            runCatching { ble.connect(address) }.onFailure { AppStore.log("Device address: ${it.message}") }
        }
        when (intent?.action) {
            ACTION_SETTINGS -> applySettings()
            ACTION_DEMO -> setDemo(intent.getBooleanExtra(EXTRA_ENABLED, false))
        }
        // Do not restart a location FGS from a stopped/background process without a visible action.
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean {
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CompanionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_companion_notification).setContentTitle("Car Companion")
            .setContentText("Driving display active · tap to manage connection")
            .setContentIntent(activity).setOngoing(true).setSilent(true)
            .addAction(0, "Stop", stop).build()
        val hasLocation = locationEnabled && granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasBluetooth = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        val type = (if (hasBluetooth) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) or
            (if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        if (type == 0) {
            AppStore.log("Grant Bluetooth or precise location permission before starting")
            stopSelf(); return false
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (failure: RuntimeException) {
            AppStore.log("Start from the visible app after granting permissions: ${failure.javaClass.simpleName}")
            stopSelf(); false
        }
    }

    private fun initialize() {
        initialized = true
        ble = BleClient(this) { command -> scope.launch { if (!demoEnabled) media.command(command) } }
        gps = GpsSpeedMonitor(this)
        media = SpotifyMediaMonitor(this, scope) { prefs.getInt("jpeg_quality", 75) }
        AppStore.diagnostic { it.copy(serviceRunning = true) }
        val page = runCatching { DisplayPage.valueOf(prefs.getString("last_page", "DRIVING")!!) }.getOrDefault(DisplayPage.DRIVING)
        AppStore.selectPage(page)
        applySettings()
        if (locationEnabled) gps.start()
        else AppStore.diagnostic { it.copy(gpsStatus = "Open the app to resume GPS after background reconnection") }
        media.start()
        prefs.getString("device_address", null)?.let { address ->
            runCatching { ble.connect(address) }.onFailure { AppStore.log("Saved device unavailable") }
        }
        scope.launch {
            ble.connection.collect { info ->
                AppStore.update { it.copy(connection = ConnectionState(info.connected, info.status, info.mtu, info.firmwareVersion)) }
                if (info.connected) artworkQueue.trySend(AppStore.artwork.value)
            }
        }
        scope.launch {
            AppStore.state.collect {
                if (ble.connection.value.connected) try { publishLatestState() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { AppStore.log("State pending reconnect: ${failure.javaClass.simpleName}") }
            }
        }
        scope.launch { AppStore.artwork.collect { artworkQueue.trySend(it) } }
        scope.launch {
            for (art in artworkQueue) {
                if (art == null || !ble.connection.value.connected || AppStore.state.value.music.artworkId != art.contentId) continue
                try {
                    // Metadata first prevents a previous album cover being labeled as the next song.
                    publishLatestState()
                    if (AppStore.state.value.music.artworkId != art.contentId) continue
                    ble.sendArtwork(art.jpeg, art.contentId)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { AppStore.log("Artwork pending reconnect: ${failure.javaClass.simpleName}") }
            }
        }
        scope.launch {
            MapsNotificationListener.updates.collect { navigation ->
                if (!demoEnabled) AppStore.navigation(navigation.fresh(SystemClock.elapsedRealtime()))
            }
        }
        scope.launch { MapsNotificationListener.listenerConnected.collect { if (!demoEnabled) media.refresh() } }
        scope.launch {
            gps.fixes.collect { fix ->
                val now = SystemClock.elapsedRealtime()
                if (fix != null && !demoEnabled && (lastLookupElapsedMs == Long.MIN_VALUE || now - lastLookupElapsedMs >= 10_000)) {
                    lastLookupElapsedMs = now
                    lookupJob?.cancel()
                    lookupJob = launch {
                        try {
                            limit = provider.lookup(fix)
                            limitReceivedElapsedMs = SystemClock.elapsedRealtime()
                            AppStore.diagnostic { it.copy(speedLimitStatus = "${limit.source} · ${limit.displayMph?.let { value -> "$value mph" } ?: "unavailable"}") }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            limit = SpeedLimitState(source = "${provider.name} unavailable")
                            AppStore.diagnostic { it.copy(speedLimitStatus = "${provider.name}: ${failure.javaClass.simpleName}") }
                        }
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (demoEnabled) updateDemo(now) else {
                    gps.checkPermission()
                    media.tick()
                    val agedLimit = limit.copy(ageMs = if (limit.ageMs == Long.MAX_VALUE) Long.MAX_VALUE
                        else (limit.ageMs + (now - limitReceivedElapsedMs)).coerceAtLeast(0))
                    val driving = SpeedPolicy.resolve(gps.samples.value, now, agedLimit)
                    AppStore.update { it.copy(driving = driving, timeEpochSeconds = System.currentTimeMillis() / 1000) }
                    AppStore.navigation(MapsNotificationListener.updates.value.fresh(now))
                    if (driving.speedMph == null && gps.samples.value != null && driving.speedAgeMs >= SpeedPolicy.STALE_AFTER_MS) {
                        AppStore.diagnostic { it.copy(gpsStatus = "GPS is stale · speed unavailable") }
                    }
                }
                delay(1_000)
            }
        }
    }

    private suspend fun publishLatestState() = stateSendMutex.withLock {
        // Sample after acquiring the lock so an older artwork task cannot regress current metadata.
        ble.sendState(AppStore.state.value)
    }

    private fun applySettings() {
        ble.setBrightness(prefs.getInt("brightness", 70))
        media.settingsChanged()
        lookupJob?.cancel()
        limit = SpeedLimitState()
        lastLookupElapsedMs = Long.MIN_VALUE
        provider = when (prefs.getString("speed_limit_provider", "unknown")) {
            "http", "road_api" -> runCatching {
                RoadMatchedSpeedLimitProvider(prefs.getString("speed_limit_endpoint", "").orEmpty(), prefs.getString("speed_limit_token", "").orEmpty())
            }.getOrElse { AppStore.log("Configure a valid HTTPS speed-limit endpoint"); UnknownSpeedLimitProvider() }
            "mock" -> if (demoEnabled) MockSpeedLimitProvider() else UnknownSpeedLimitProvider()
            else -> UnknownSpeedLimitProvider()
        }
        AppStore.diagnostic { it.copy(speedLimitStatus = provider.name) }
    }

    private fun setDemo(enabled: Boolean) {
        demoArtworkJob?.cancel()
        val generation = ++demoGeneration
        demoArtworkId = null
        demoEnabled = enabled
        // Clear synthetic/live values atomically with the mode bit before any BLE observer resumes.
        AppStore.update { it.copy(demo = enabled, driving = DrivingState(), music = MusicState()) }
        if (enabled) {
            gps.stop(); media.stop(); lookupJob?.cancel()
            AppStore.artwork(null)
            demoTick = 0
            updateDemo(SystemClock.elapsedRealtime())
            demoArtworkJob = scope.launch {
                val art = DemoArtworkFactory.encode(this@CompanionService, prefs.getInt("jpeg_quality", 75))
                // Cancellation and a generation guard prevent a delayed demo image replacing live media.
                if (!demoEnabled || generation != demoGeneration) return@launch
                demoArtworkId = art?.contentId
                AppStore.update { it.copy(music = it.music.copy(artworkId = demoArtworkId)) }
                AppStore.artwork(art)
                AppStore.log(if (art == null) "Demo artwork unavailable" else "DEMO JPEG ready · ${art.jpeg.size} bytes")
            }
            AppStore.diagnostic { it.copy(gpsStatus = "DEMO · simulated speed", mediaStatus = "DEMO · simulated media", navigationStatus = "DEMO · synthetic navigation") }
        } else {
            AppStore.artwork(null)
            AppStore.navigation(MapsNotificationListener.updates.value.fresh(SystemClock.elapsedRealtime()))
            AppStore.update { it.copy(driving = DrivingState(), music = MusicState()) }
            if (locationEnabled) gps.start()
            media.start()
        }
        applySettings()
    }

    private fun updateDemo(now: Long) {
        val phase = demoTick++
        if (phase % 12 == 0) mockNavigation.advance(now, phase / 12)
        val nav = mockNavigation.updates.value
        AppStore.navigation(if (nav.active) nav.copy(updatedAtElapsedMs = now) else nav)
        AppStore.update { it.copy(
            driving = DrivingState(speedMph = 32f + (phase % 9), speedAgeMs = 0, horizontalAccuracyM = 3f,
                speedLimit = SpeedLimitState(35, 1f, 0, "DEMO ONLY")),
            music = MusicState("A drive by the coast", "Car Companion demo", "Synthetic preview", 180_000,
                (phase * 1000L) % 180_000L, true, artworkId = demoArtworkId, trackId = "demo"),
            timeEpochSeconds = System.currentTimeMillis() / 1000,
        ) }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (initialized) { ble.close(); gps.stop(); media.stop() }
        scope.cancel()
        AppStore.update { it.copy(connection = ConnectionState(), driving = DrivingState(), music = MusicState(), demo = false) }
        AppStore.artwork(null)
        AppStore.navigation(NavigationState())
        AppStore.diagnostic { it.copy(serviceRunning = false, gpsStatus = "Location service stopped", mediaStatus = "Media monitoring stopped") }
        super.onDestroy()
    }

    companion object {
        const val PREFS = "car_companion"
        const val ACTION_STOP = "com.carcompanion.STOP"
        const val ACTION_SETTINGS = "com.carcompanion.SETTINGS"
        const val ACTION_DEMO = "com.carcompanion.DEMO"
        const val ACTION_RESUME_BLE = "com.carcompanion.RESUME_BLE"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_ENABLED = "enabled"
        private const val CHANNEL = "car_companion_connection"
        private const val NOTIFICATION_ID = 986
        fun start(context: Context, address: String? = null) = send(context, Intent(context, CompanionService::class.java).putExtra(EXTRA_ADDRESS, address))
        fun resumeBle(context: Context, address: String) = send(context,
            Intent(context, CompanionService::class.java).setAction(ACTION_RESUME_BLE).putExtra(EXTRA_ADDRESS, address))
        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("service_enabled", false).apply()
            context.stopService(Intent(context, CompanionService::class.java))
        }
        fun updateSettings(context: Context) {
            if (AppStore.diagnostics.value.serviceRunning) send(context, Intent(context, CompanionService::class.java).setAction(ACTION_SETTINGS))
        }
        fun demo(context: Context, enabled: Boolean) = send(context,
            Intent(context, CompanionService::class.java).setAction(ACTION_DEMO).putExtra(EXTRA_ENABLED, enabled))
        private fun send(context: Context, intent: Intent) {
            try { ContextCompat.startForegroundService(context, intent) }
            catch (failure: RuntimeException) { AppStore.log("Open Car Companion to start: ${failure.javaClass.simpleName}") }
        }
    }
}
