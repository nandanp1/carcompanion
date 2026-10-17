package com.carcompanion.app.model

/** The UI preview and BLE encoder consume this same model. Null means unavailable, never zero. */
data class DisplayState(
    val driving: DrivingState = DrivingState(),
    val navigation: NavigationState = NavigationState(),
    val music: MusicState = MusicState(),
    val connection: ConnectionState = ConnectionState(),
    val page: DisplayPage = DisplayPage.DRIVING,
    val timeEpochSeconds: Long = 0,
    val demo: Boolean = false,
)

enum class DisplayPage { DRIVING, MUSIC, NAVIGATION }
enum class Maneuver { UNKNOWN, STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, U_TURN, ROUNDABOUT, ARRIVE }

data class ConnectionState(
    val connected: Boolean = false,
    val status: String = "Disconnected",
    val mtu: Int = 23,
    val firmwareVersion: String = "Unknown",
)

data class DrivingState(
    val speedMph: Float? = null,
    val speedAgeMs: Long = Long.MAX_VALUE,
    val speedAccuracyMps: Float? = null,
    val horizontalAccuracyM: Float? = null,
    val speedLimit: SpeedLimitState = SpeedLimitState(),
)

data class SpeedLimitState(
    val mph: Int? = null,
    val confidence: Float = 0f,
    val ageMs: Long = Long.MAX_VALUE,
    val source: String = "Unavailable",
    val roadId: String? = null,
) {
    val displayMph: Int? get() = mph?.takeIf { it in 1..200 && confidence in 0.8f..1f && ageMs in 0 until 30_000L }
}

data class NavigationState(
    val active: Boolean = false,
    val maneuver: Maneuver = Maneuver.UNKNOWN,
    val distanceText: String = "",
    val road: String = "",
    val etaText: String = "",
    val updatedAtElapsedMs: Long = 0,
    val source: String = "No navigation",
    val reliable: Boolean = false,
) {
    fun fresh(nowElapsedMs: Long): NavigationState =
        if (active && nowElapsedMs - updatedAtElapsedMs > 30_000L) NavigationState(source = "$source (stale)") else this
}

data class MusicState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val artworkId: Long? = null,
    val trackId: String = "",
)

/** Compressed payload stays phone-side; only its content hash is part of DisplayState. */
data class Artwork(val contentId: Long, val jpeg: ByteArray)

data class NotificationDiagnostic(
    val observedAtEpochMs: Long,
    val category: String,
    val title: String,
    val text: String,
    val extraKeys: List<String>,
    val result: String,
    val bigText: String = "",
    val subText: String = "",
)

data class Diagnostics(
    val serviceRunning: Boolean = false,
    val notificationAccess: Boolean = false,
    val mediaStatus: String = "Enable notification access to read Spotify",
    val navigationStatus: String = "Waiting for Google Maps notifications",
    val gpsStatus: String = "Location service stopped",
    val speedLimitStatus: String = "Unknown provider; speed limit is unavailable",
    val notifications: List<NotificationDiagnostic> = emptyList(),
    val logs: List<String> = emptyList(),
)
