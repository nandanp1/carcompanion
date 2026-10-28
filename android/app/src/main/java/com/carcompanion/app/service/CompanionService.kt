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
import com.carcompanion.app.ble.BluetoothAddress
import com.carcompanion.app.ble.DevicePresenceObserver
import com.carcompanion.app.location.*
import com.carcompanion.app.media.SpotifyMediaMonitor
import com.carcompanion.app.model.*
import com.carcompanion.app.navigation.MapsNotificationListener
import com.carcompanion.app.navigation.MockNavigationProvider
import com.carcompanion.app.navigation.GoogleNavigationSession
import com.carcompanion.app.navigation.SharedMapsDestination
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps BLE, GPS and app-owned navigation running after a visible user start. */
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
    private val artworkSender = LatestArtworkSender(
        isCurrent = { art -> ble.connection.value.connected && AppStore.state.value.music.artworkId == art.contentId },
        publishMetadata = { ble.sendMusic { AppStore.state.value } },
        transfer = { art -> ble.sendArtwork(art.jpeg, art.contentId) },
        onFailure = { AppStore.log("Artwork pending reconnect: ${it.javaClass.simpleName}") },
    )
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
            AutoReconnectSettings.disarm(this)
            DevicePresenceObserver.refresh(this)
            stopSelf(); return START_NOT_STICKY
        }
        val source = when {
            intent == null -> ReconnectPolicy.Start.RESTART
            intent.action == ACTION_RESUME_BLE -> ReconnectPolicy.Start.PRESENCE
            intent.action == ACTION_SETTINGS -> ReconnectPolicy.Start.SETTINGS
            else -> ReconnectPolicy.Start.USER
        }
        val admission = ReconnectPolicy.admit(source, AutoReconnectSettings.isArmed(this),
            prefs.getBoolean("service_enabled", false), initialized)
        if (admission != ReconnectPolicy.Admission.START) {
            // A presence intent queued before auto reconnect was disabled must not stop a live
            // manual connection. Cold unarmed starts still shut down without entering foreground.
            if (admission == ReconnectPolicy.Admission.STOP) stopSelf()
            return START_NOT_STICKY
        }
        val requestedAddress = intent?.getStringExtra(EXTRA_ADDRESS)?.let {
            runCatching { BluetoothAddress.canonicalize(it) }.getOrElse {
                AppStore.log("Invalid saved device address")
                null
            }
        }
        if (source == ReconnectPolicy.Start.PRESENCE && requestedAddress != AutoReconnectSettings.selectedAddress(this)) {
            AppStore.log("Ignored presence for a different display")
            if (!initialized) stopSelf()
            return if (initialized && AutoReconnectSettings.isArmed(this)) START_STICKY else START_NOT_STICKY
        }
        locationEnabled = ReconnectPolicy.useLocation(source, locationEnabled)
        if (!enterForeground()) return START_NOT_STICKY
        // A sticky null-Intent restart and a CDM wake restore BLE only, never a new location FGS.
        locationEnabled = locationEnabled && granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (source == ReconnectPolicy.Start.USER) AutoReconnectSettings.arm(this)
        requestedAddress?.let { prefs.edit().putString("device_address", it).apply() }
        if (!initialized) initialize()
        ble.setAutoReconnect(AutoReconnectSettings.isEnabled(this))
        DevicePresenceObserver.refresh(this)
        if (ReconnectPolicy.connectOnStart(source, AutoReconnectSettings.isArmed(this))) {
            AutoReconnectSettings.selectedAddress(this)?.let { address ->
                runCatching { ble.connect(address) }.onFailure { AppStore.log("Device address: ${it.message}") }
            }
        }
        refreshGps()
        when (intent?.action) {
            ACTION_NAVIGATE -> {
                if (demoEnabled) setDemo(false)
                val destination = SharedMapsDestination(
                    intent.getStringExtra("destination_label").orEmpty().take(200),
                    intent.getDoubleExtra("destination_lat", Double.NaN).takeIf { it.isFinite() && it in -90.0..90.0 },
                    intent.getDoubleExtra("destination_lng", Double.NaN).takeIf { it.isFinite() && it in -180.0..180.0 },
                    intent.getStringExtra("destination_place_id"),
                )
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) GoogleNavigationSession.start(this, destination)
                else AppStore.diagnostic { it.copy(navigationStatus = "Allow precise location, then share the destination again.") }
            }
            ACTION_SETTINGS -> applySettings()
            ACTION_DEMO -> setDemo(intent.getBooleanExtra(EXTRA_ENABLED, false))
        }
        return if (AutoReconnectSettings.isArmed(this)) START_STICKY else START_NOT_STICKY
    }

    private fun notification(): android.app.Notification {
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CompanionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val connected = initialized && ble.connection.value.connected
        val description = when {
            connected -> "Display connected · tap to manage"
            AutoReconnectSettings.isEnabled(this) -> "Waiting for your display · reconnects automatically"
            else -> "Display disconnected · tap to connect"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_companion_notification).setContentTitle("Car Companion")
            .setContentText(description)
            .setContentIntent(activity).setOngoing(true).setSilent(true)
            .addAction(0, "Stop", stop).build()
    }

    private fun enterForeground(): Boolean {
        val hasLocation = locationEnabled && granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasBluetooth = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        val type = (if (hasBluetooth) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) or
            (if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        if (type == 0) {
            AppStore.log("Grant Bluetooth or precise location permission before starting")
            stopSelf(); return false
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
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
        media.start()
        scope.launch {
            ble.connection.collect { info ->
                AppStore.update { it.copy(connection = ConnectionState(info.connected, info.status, info.mtu, info.firmwareVersion)) }
                // Disconnect also cancels the old transfer before a replacement GATT session starts.
                artworkSender.offer(if (info.connected) AppStore.artwork.value else null)
                refreshGps()
                runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
            }
        }
        scope.launch {
            AppStore.state.collect {
                if (ble.connection.value.connected) try { publishLatestState() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { AppStore.log("State pending reconnect: ${failure.javaClass.simpleName}") }
            }
        }
        scope.launch {
            AppStore.state.map { MusicDeliveryKey.from(it) }.distinctUntilChanged().collect {
                if (ble.connection.value.connected) try { ble.sendMusic { AppStore.state.value } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { AppStore.log("Music pending reconnect: ${failure.javaClass.simpleName}") }
            }
        }
        scope.launch { AppStore.artwork.collect { artworkSender.offer(it) } }
        scope.launch { artworkSender.run() }
        scope.launch {
            GoogleNavigationSession.updates.collect { navigation ->
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
                    AppStore.navigation(GoogleNavigationSession.updates.value.fresh(now))
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
        ble.sendState { AppStore.state.value }
    }

    private fun applySettings() {
        ble.setAutoReconnect(AutoReconnectSettings.isEnabled(this))
        DevicePresenceObserver.refresh(this)
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

    private fun refreshGps() {
        if (!initialized) return
        AppStore.diagnostic { it.copy(locationNeedsForegroundStart = !locationEnabled) }
        if (demoEnabled) return
        if (locationEnabled && ble.connection.value.connected) gps.start()
        else {
            gps.stop()
            AppStore.update { it.copy(driving = DrivingState()) }
            AppStore.diagnostic { it.copy(gpsStatus = if (locationEnabled) "Display unavailable · GPS paused"
                else "Tap Resume GPS in the app after background reconnection") }
        }
    }

    private fun setDemo(enabled: Boolean) {
        demoArtworkJob?.cancel()
        val generation = ++demoGeneration
        demoArtworkId = null
        demoEnabled = enabled
        // Clear synthetic/live values atomically with the mode bit before any BLE observer resumes.
        AppStore.update { it.copy(demo = enabled, driving = DrivingState(), music = MusicState()) }
        if (enabled) {
            GoogleNavigationSession.stop("Demo mode · real navigation stopped")
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
            AppStore.navigation(GoogleNavigationSession.updates.value.fresh(SystemClock.elapsedRealtime()))
            AppStore.update { it.copy(driving = DrivingState(), music = MusicState()) }
            refreshGps()
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
        GoogleNavigationSession.stop()
        if (initialized) { ble.close(); gps.stop(); media.stop() }
        scope.cancel()
        AppStore.update { it.copy(connection = ConnectionState(), driving = DrivingState(), music = MusicState(), demo = false) }
        AppStore.artwork(null)
        AppStore.navigation(NavigationState())
        AppStore.diagnostic { it.copy(serviceRunning = false, locationNeedsForegroundStart = false,
            gpsStatus = "Location service stopped", mediaStatus = "Media monitoring stopped") }
        super.onDestroy()
    }

    companion object {
        const val PREFS = "car_companion"
        const val ACTION_NAVIGATE = "com.carcompanion.NAVIGATE"
        const val ACTION_STOP = "com.carcompanion.STOP"
        const val ACTION_SETTINGS = "com.carcompanion.SETTINGS"
        const val ACTION_DEMO = "com.carcompanion.DEMO"
        const val ACTION_RESUME_BLE = "com.carcompanion.RESUME_BLE"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_ENABLED = "enabled"
        private const val CHANNEL = "car_companion_connection"
        private const val NOTIFICATION_ID = 986
        fun navigate(context: Context, destination: SharedMapsDestination) = send(context,
            Intent(context, CompanionService::class.java).setAction(ACTION_NAVIGATE)
                .putExtra("destination_label", destination.label)
                .putExtra("destination_lat", destination.latitude ?: Double.NaN)
                .putExtra("destination_lng", destination.longitude ?: Double.NaN)
                .putExtra("destination_place_id", destination.placeId))
        fun start(context: Context, address: String? = null) = send(context, Intent(context, CompanionService::class.java).putExtra(EXTRA_ADDRESS, address))
        fun resumeBle(context: Context, address: String) = send(context,
            Intent(context, CompanionService::class.java).setAction(ACTION_RESUME_BLE).putExtra(EXTRA_ADDRESS, address))
        /** Called only while the activity is visible; repairs observation after an app update. */
        fun restoreWhenVisible(context: Context) {
            DevicePresenceObserver.refresh(context)
            if (!AutoReconnectSettings.isArmed(context)) return
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            val diagnostics = AppStore.diagnostics.value
            if (!diagnostics.serviceRunning || diagnostics.locationNeedsForegroundStart) start(context)
        }
        fun stop(context: Context) {
            AutoReconnectSettings.disarm(context)
            DevicePresenceObserver.refresh(context)
            context.stopService(Intent(context, CompanionService::class.java))
        }
        fun setAutoReconnect(context: Context, enabled: Boolean) {
            AutoReconnectSettings.setEnabled(context, enabled)
            if (enabled) start(context)
            else {
                DevicePresenceObserver.refresh(context)
                updateSettings(context)
            }
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
