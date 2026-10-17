package com.carcompanion.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carcompanion.app.BuildConfig
import com.carcompanion.app.model.*
import com.carcompanion.app.service.AutoReconnectSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Ink = Color(0xff080b0d)
private val Panel = Color(0xff11171b)
private val Line = Color(0xff273139)
private val Muted = Color(0xff82919a)
private val Blue = Color(0xff9acdf1)
private val Green = Color(0xff87c99d)

@Composable
fun CarCompanionApp(
    onPair: () -> Unit, onStart: () -> Unit, onStop: () -> Unit,
    onNotificationAccess: () -> Unit, onPermissions: () -> Unit,
    onSettingsChanged: () -> Unit, onDemo: (Boolean) -> Unit, onAutoReconnect: (Boolean) -> Unit,
) {
    val state by AppStore.state.collectAsStateWithLifecycle()
    val diagnostics by AppStore.diagnostics.collectAsStateWithLifecycle()
    val artwork by AppStore.artwork.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, secondary = Green, background = Ink, surface = Panel, onSurface = Color.White)) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("986  /  DRIVER’S DISPLAY", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(7.dp))
                        Text("Car Companion", fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp)
                    }
                    Box(Modifier.size(8.dp).background(if (state.connection.connected) Green else Muted, CircleShape))
                }
                Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    listOf("Overview", "Setup", "Diagnostics").forEachIndexed { index, title ->
                        Column(Modifier.clickable { tab = index }.padding(vertical = 8.dp)) {
                            Text(title, color = if (tab == index) Color.White else Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(10.dp))
                            Box(Modifier.height(2.dp).width(if (index == 2) 68.dp else 53.dp).background(if (tab == index) Blue else Color.Transparent))
                        }
                    }
                }
                HorizontalDivider(color = Line)
                Crossfade(targetState = tab, label = "setup sections") { active ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        when (active) {
                            0 -> Overview(state, diagnostics, artwork, onPair, onStart, onStop, onDemo)
                            1 -> Setup(state, diagnostics, onPair, onPermissions, onNotificationAccess, onSettingsChanged, onAutoReconnect)
                            else -> DiagnosticsView(state, diagnostics)
                        }
                        Text("CAR COMPANION  /  ${BuildConfig.VERSION_NAME}", color = Muted.copy(alpha = .6f), fontSize = 9.sp, letterSpacing = 2.sp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Overview(state: DisplayState, diagnostics: Diagnostics, artwork: Artwork?, onPair: () -> Unit,
                     onStart: () -> Unit, onStop: () -> Unit, onDemo: (Boolean) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("car_companion", Context.MODE_PRIVATE) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Label(if (state.demo) "SIMULATED DISPLAY" else "LIVE DISPLAY")
        Text(if (state.connection.connected) "●  CONNECTED" else "○  ${state.connection.status.uppercase()}", color = if (state.connection.connected) Green else Muted, fontSize = 10.sp)
    }
    DisplayPreview(state, artwork, Modifier.fillMaxWidth().aspectRatio(1f)) { page ->
        AppStore.selectPage(page)
        preferences.edit().putString("last_page", page.name).apply()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(DisplayPage.NAVIGATION to "Navigation", DisplayPage.DRIVING to "Driving", DisplayPage.MUSIC to "Music").forEach { (page, title) ->
            Surface(modifier = Modifier.weight(1f).clickable {
                AppStore.selectPage(page); preferences.edit().putString("last_page", page.name).apply()
            }, color = if (state.page == page) Line else Panel, shape = RoundedCornerShape(8.dp)) {
                Text(title, Modifier.padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp,
                    color = if (state.page == page) Color.White else Muted)
            }
        }
    }
    Text("Swipe the display to change views. Navigation takes the lead when a route starts.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
    CardBlock {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (diagnostics.serviceRunning) "Ready for the road" else "Your drive, in focus", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(if (diagnostics.serviceRunning) "Companion service is running" else "Connect your display to get started", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Text("↗", color = Blue, fontSize = 27.sp)
        }
        Spacer(Modifier.height(18.dp))
        if (diagnostics.serviceRunning) {
            if (diagnostics.locationNeedsForegroundStart) {
                Text("The display can reconnect automatically. Resume GPS here to send your current speed.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Resume GPS") }
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop companion") }
        }
        else Button(onClick = { if (preferences.getString("device_address", null) == null) onPair() else onStart() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (preferences.getString("device_address", null) == null) "Connect a display" else "Start companion")
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Explore demo mode", fontSize = 14.sp)
            Text("Sample data, clearly marked on screen", color = Muted, fontSize = 11.sp)
        }
        Switch(checked = state.demo, onCheckedChange = onDemo)
    }
}

@Composable
fun DisplayPreview(state: DisplayState, artwork: Artwork?, modifier: Modifier = Modifier, select: (DisplayPage) -> Unit = {}) {
    var drag by remember { mutableFloatStateOf(0f) }
    val pages = listOf(DisplayPage.NAVIGATION, DisplayPage.DRIVING, DisplayPage.MUSIC)
    val bitmap = remember(artwork?.contentId) { artwork?.jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    // Metadata can arrive before its JPEG. Never show the previous track's cover in any view.
    val currentArtwork = bitmap?.takeIf { state.music.artworkId == artwork?.contentId }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(30.dp)).background(Color.Black).border(1.dp, Line, RoundedCornerShape(30.dp))
        .pointerInput(state.page) {
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (kotlin.math.abs(drag) > size.width * .18f) {
                    val index = pages.indexOf(state.page)
                    select(pages[(index + if (drag < 0) 1 else -1).coerceIn(0, 2)])
                }
            }) { change, amount -> change.consume(); drag += amount }
        }) {
        val scale = maxWidth.value / 360f
        Column(Modifier.fillMaxSize().padding((25 * scale).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (state.demo) "DEMO" else "986", color = if (state.demo) Color(0xffffc977) else Muted, fontSize = (11 * scale).sp, letterSpacing = 2.sp)
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((state.timeEpochSeconds.takeIf { it > 0 } ?: System.currentTimeMillis() / 1000) * 1000)), color = Color.White, fontSize = (13 * scale).sp, fontFamily = FontFamily.Monospace)
            }
            Crossfade(state.page, label = "display page", modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    DisplayPage.DRIVING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                            val radius = size.minDimension * .48f
                            for (i in 0..40) {
                                val angle = Math.toRadians((140 + i * 6.5).toDouble())
                                val inside = radius - if (i % 5 == 0) 9 * density else 4 * density
                                drawLine(if (i % 5 == 0) Color(0xff50606a) else Color(0xff26323a),
                                    Offset(center.x + cos(angle).toFloat() * inside, center.y + sin(angle).toFloat() * inside),
                                    Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius), 1.3f * density)
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.driving.speedMph?.takeIf { state.driving.speedAgeMs < 3000 }?.roundToInt()?.toString() ?: "—", fontSize = (91 * scale).sp, fontWeight = FontWeight.Light, letterSpacing = (-5).sp, lineHeight = (96 * scale).sp)
                            Text(if (state.driving.speedMph == null) "GPS UNAVAILABLE" else "MILES PER HOUR", color = Muted, fontSize = (9 * scale).sp, letterSpacing = 2.sp)
                            Spacer(Modifier.height((18 * scale).dp))
                            SpeedLimit(state.driving.speedLimit.displayMph)
                        }
                    }
                    DisplayPage.NAVIGATION -> Column(Modifier.fillMaxSize().padding(top = (12 * scale).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (!state.navigation.active) "⌁" else when (state.navigation.maneuver) {
                            Maneuver.LEFT -> "←"; Maneuver.RIGHT -> "→"; Maneuver.STRAIGHT -> "↑"; Maneuver.SLIGHT_LEFT -> "↖"; Maneuver.SLIGHT_RIGHT -> "↗"; Maneuver.U_TURN -> "↶"; Maneuver.ROUNDABOUT -> "⟳"; Maneuver.ARRIVE -> "⚑"; else -> "—"
                        }, color = Blue, fontSize = (75 * scale).sp, lineHeight = (86 * scale).sp)
                        Text(state.navigation.distanceText.ifBlank { if (state.navigation.active) "Instruction unavailable" else "No active route" }, fontSize = (25 * scale).sp, fontWeight = FontWeight.Medium)
                        Text(state.navigation.road.ifBlank { if (state.navigation.active) "Check Google Maps" else "Start navigation in Google Maps" }, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = (13 * scale).sp, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.driving.speedMph?.takeIf { state.driving.speedAgeMs < 3000 }?.roundToInt() ?: "—"} mph", fontSize = (17 * scale).sp)
                            Text(state.navigation.etaText, color = Muted, fontSize = (11 * scale).sp)
                            SpeedLimit(state.driving.speedLimit.displayMph)
                        }
                    }
                    DisplayPage.MUSIC -> Column(Modifier.fillMaxSize().padding(top = (14 * scale).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val artSize = (137 * scale).dp
                        if (currentArtwork != null) Image(currentArtwork, "Album artwork", Modifier.size(artSize).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        else Box(Modifier.size(artSize).background(Panel, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("♫", fontSize = 52.sp, color = Green) }
                        MusicRow(state.music.title.ifBlank { "Nothing playing" }, 17 * scale, Color.White, FontWeight.Medium, Modifier.padding(top = (14 * scale).dp))
                        MusicRow(state.music.artist.ifBlank { "Play something on Spotify" }, 12 * scale, Muted)
                        if (state.music.album.isNotBlank()) MusicRow(state.music.album, 10 * scale, Muted)
                        Spacer(Modifier.weight(1f))
                        LinearProgressIndicator(progress = { if (state.music.durationMs > 0) (state.music.positionMs.toFloat() / state.music.durationMs).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Green, trackColor = Line)
                        Text(if (state.music.playing) "PLAYING" else "PAUSED", color = Green, fontSize = 9.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (state.page != DisplayPage.MUSIC) {
                Spacer(Modifier.height((10 * scale).dp))
                NowPlayingStrip(state.music, currentArtwork, scale)
                Spacer(Modifier.height((12.5f * scale).dp))
            }
        }
    }
}

private val metadataLineBreaks = Regex("[\\r\\n\\t\\u0085\\u2028\\u2029]+")

@Composable
private fun NowPlayingStrip(music: MusicState, artwork: ImageBitmap?, scale: Float) {
    // Map the firmware footer (x104, y378, width280, height52) onto the 360dp preview grid.
    val coverSize = (39 * scale).dp
    Row(Modifier.width((210 * scale).dp).offset(x = (3 * scale).dp).height(coverSize),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((12 * scale).dp)) {
        if (artwork != null) {
            Image(artwork, "Current album artwork", Modifier.size(coverSize).clip(RoundedCornerShape((4 * scale).dp)),
                contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((2 * scale).dp)) {
            val alignment = if (artwork == null) TextAlign.Center else TextAlign.Start
            MusicRow(music.title.ifBlank { "Spotify · not playing" }, 12 * scale,
                if (music.playing) Green else Color.White, FontWeight.Medium, alignment = alignment)
            if (music.artist.isNotBlank()) MusicRow(music.artist, 12 * scale, Muted, alignment = alignment)
        }
    }
}

@Composable
private fun MusicRow(text: String, size: Float, color: Color, weight: FontWeight = FontWeight.Normal,
                     modifier: Modifier = Modifier, alignment: TextAlign = TextAlign.Center) {
    val singleLine = remember(text) { text.replace(metadataLineBreaks, " ").trim() }
    Text(singleLine, modifier.fillMaxWidth(), color = color, fontSize = size.sp, lineHeight = (size * 1.3f).sp,
        fontWeight = weight, minLines = 1, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        textAlign = alignment)
}

@Composable private fun SpeedLimit(limit: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(limit?.toString() ?: "—", Modifier.border(1.dp, Color(0xffbac4ca), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("LIMIT", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun Setup(state: DisplayState, diagnostics: Diagnostics, onPair: () -> Unit, onPermissions: () -> Unit,
                  onNotificationAccess: () -> Unit, changed: () -> Unit, onAutoReconnect: (Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("car_companion", Context.MODE_PRIVATE) }
    var brightness by remember { mutableFloatStateOf(prefs.getInt("brightness", 70).toFloat()) }
    var quality by remember { mutableFloatStateOf(prefs.getInt("jpeg_quality", 75).toFloat()) }
    var provider by remember { mutableStateOf(prefs.getString("speed_limit_provider", "unknown") ?: "unknown") }
    var endpoint by remember { mutableStateOf(prefs.getString("speed_limit_endpoint", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("speed_limit_token", "") ?: "") }
    var autoReconnect by remember { mutableStateOf(AutoReconnectSettings.isEnabled(context)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "auto_reconnect" || key == "service_enabled") autoReconnect = AutoReconnectSettings.isEnabled(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Label("01  /  CONNECT YOUR DISPLAY")
    CardBlock {
        Text("Waveshare companion", fontSize = 18.sp)
        Text(state.connection.status, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        prefs.getString("device_address", null)?.let { Text(it, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 5.dp)) }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onPair) { Text("Find nearby display") }
        Text("Power on the display, then select it in the Android device chooser and confirm Bluetooth pairing.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Automatic reconnect", fontSize = 14.sp)
                Text("Connect when the car display powers on", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Switch(checked = autoReconnect, onCheckedChange = { enabled -> autoReconnect = enabled; onAutoReconnect(enabled) })
        }
        Text("Start companion once to enable it. Stop companion pauses automatic connections until you start it again.", color = Muted, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
    }
    Label("02  /  PHONE INTEGRATIONS")
    CardBlock {
        StatusRow("Nearby devices & GPS", diagnostics.gpsStatus)
        TextButton(onClick = onPermissions) { Text("Review permissions") }
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 8.dp))
        StatusRow("Spotify & Google Maps", if (diagnostics.notificationAccess) "Notification access enabled" else "Notification access required")
        TextButton(onClick = onNotificationAccess) { Text("Open notification access") }
        Text("Only Google Maps navigation notifications and active Spotify media sessions are used. Audio continues through your Alpine head unit.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
    }
    Label("03  /  DISPLAY PREFERENCES")
    CardBlock {
        StatusRow("Brightness", "${brightness.roundToInt()}%")
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 5f..100f, onValueChangeFinished = {
            prefs.edit().putInt("brightness", brightness.roundToInt()).apply(); changed()
        })
        StatusRow("Artwork quality", "${quality.roundToInt()} / 100")
        Slider(value = quality, onValueChange = { quality = it }, valueRange = 35f..95f, onValueChangeFinished = {
            prefs.edit().putInt("jpeg_quality", quality.roundToInt()).apply(); changed()
        })
        Text("240 × 240 JPEG · automatically sized to fit the display’s memory budget.", color = Muted, fontSize = 12.sp)
    }
    Label("04  /  REPORTED SPEED LIMIT")
    CardBlock {
        listOf("unknown" to "Unavailable — show a dash", "http" to "Road-matched HTTPS provider", "mock" to "Mock provider — demo only").forEach { (value, title) ->
            Row(Modifier.fillMaxWidth().clickable { provider = value }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = provider == value, onClick = { provider = value }); Text(title, fontSize = 12.sp)
            }
        }
        if (provider == "http") {
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(token, { token = it }, label = { Text("Bearer token, if required") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Text("Uses the documented provider adapter contract. The configured service receives your current coordinates. Coverage and licensing depend on that provider.", color = Muted, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
        }
        TextButton(onClick = {
            prefs.edit().putString("speed_limit_provider", provider).putString("speed_limit_endpoint", endpoint.trim()).putString("speed_limit_token", token.trim()).apply(); changed()
        }) { Text("Save provider") }
        Text("Uncertain, missing or stale limits are always shown as —.", color = Muted, fontSize = 12.sp)
    }
}

@Composable private fun DiagnosticsView(state: DisplayState, diagnostics: Diagnostics) {
    Label("CONNECTION & DATA HEALTH")
    CardBlock {
        StatusRow("Bluetooth", state.connection.status)
        StatusRow("Negotiated MTU", "${state.connection.mtu} bytes")
        StatusRow("Firmware", state.connection.firmwareVersion)
        StatusRow("Protocol", "1.0")
        StatusRow("GPS", diagnostics.gpsStatus)
        StatusRow("Spotify", diagnostics.mediaStatus)
        StatusRow("Google Maps", diagnostics.navigationStatus)
        StatusRow("Speed limit", diagnostics.speedLimitStatus)
        StatusRow("Limit confidence / age", "${(state.driving.speedLimit.confidence * 100).roundToInt()}% / ${if (state.driving.speedLimit.ageMs == Long.MAX_VALUE) "—" else "${state.driving.speedLimit.ageMs / 1000}s"}")
    }
    Label("MAPS NOTIFICATION INSPECTOR")
    Text("Google Maps has no public active-navigation API. These are the fields actually exposed by notifications on this phone; missing directions stay unavailable.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
    if (diagnostics.notifications.isEmpty()) CardBlock { Text("Start a Google Maps route after enabling notification access to inspect its fields.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp) }
    diagnostics.notifications.take(6).forEach { notification ->
        CardBlock {
            Text(notification.title.ifBlank { "(No title)" }, fontSize = 14.sp)
            Text("Category: ${notification.category.ifBlank { "(Not supplied)" }}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Text(notification.text.ifBlank { "(No text)" }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (notification.bigText.isNotBlank()) {
                Text("Expanded: ${notification.bigText}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            if (notification.subText.isNotBlank()) {
                Text("Subtitle: ${notification.subText}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Text(notification.result, color = Blue, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Text("Fields: ${notification.extraKeys.joinToString()}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
    Label("RECENT EVENTS")
    CardBlock {
        if (diagnostics.logs.isEmpty()) Text("No events yet", color = Muted, fontSize = 12.sp)
        diagnostics.logs.take(25).forEach { Text(it, color = Muted, fontSize = 11.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 4.dp)) }
    }
}

@Composable private fun Label(text: String) { Text(text, color = Muted, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium) }
@Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).border(1.dp, Line.copy(alpha = .65f), RoundedCornerShape(14.dp)).padding(18.dp), content = content)
}
@Composable private fun StatusRow(title: String, value: String) {
    Column(Modifier.padding(vertical = 7.dp)) {
        Text(title, color = Color.White, fontSize = 13.sp)
        Text(value, color = Muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
    }
}
