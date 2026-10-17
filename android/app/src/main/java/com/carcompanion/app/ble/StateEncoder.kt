package com.carcompanion.app.ble

import com.carcompanion.app.model.DisplayState
import com.carcompanion.app.model.Maneuver
import org.json.JSONObject

object StateEncoder {
    private fun String.bounded(max: Int): String {
        var result = map { if (it.isISOControl()) ' ' else it }.joinToString("")
        while (result.toByteArray(Charsets.UTF_8).size > max) result = result.dropLast(1)
        return result
    }
    private fun nullable(value: Any?): Any = value ?: JSONObject.NULL
    fun driving(state: DisplayState): JSONObject = with(state.driving) {
        val simulatedLimit = speedLimit.source.contains("mock", true) || speedLimit.source.contains("demo", true)
        val limit = speedLimit.displayMph?.takeIf { state.demo || !simulatedLimit }
        val accuracy = horizontalAccuracyM?.takeIf { it.isFinite() && it in 0f..50f }
        val confidence = speedLimit.confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        JSONObject().put("speed_mph", nullable(speedMph?.takeIf { it.isFinite() && it in 0f..300f && speedAgeMs in 0..2999 && accuracy != null }))
            .put("gps_age_ms", speedAgeMs.coerceIn(0, 0xffffffffL))
            .put("accuracy_m", nullable(accuracy))
            .put("limit_mph", nullable(limit)).put("limit_confidence", confidence)
            .put("limit_age_ms", if (limit == null) JSONObject.NULL else speedLimit.ageMs.coerceAtLeast(0))
            .put("limit_source", speedLimit.source.bounded(96))
    }
    fun navigation(state: DisplayState): JSONObject = with(state.navigation) {
        val direction = when (maneuver) {
            Maneuver.U_TURN -> "uturn"
            else -> maneuver.name.lowercase()
        }
        JSONObject().put("active", active).put("maneuver", direction)
            .put("distance", nullable(distanceText.bounded(96).ifBlank { null }))
            .put("road", nullable(road.bounded(96).ifBlank { null }))
            .put("eta", nullable(etaText.bounded(32).ifBlank { null }))
            .put("reliable", reliable).put("source", source.bounded(96))
    }
    fun music(state: DisplayState): JSONObject = with(state.music) {
        val json = JSONObject().put("track_id", trackId.bounded(128)).put("title", title.bounded(128))
            .put("artist", artist.bounded(128)).put("album", album.bounded(128))
            .put("duration_ms", durationMs.coerceIn(0, 86400000))
            .put("position_ms", positionMs.coerceIn(0, if (durationMs > 0) durationMs.coerceAtMost(86400000) else 86400000))
            .put("playing", playing).put("art_crc", nullable(artworkId))
        // Escaped quotes/backslashes can double JSON byte size even with valid UTF-8 field bounds.
        if (json.toString().toByteArray().size > 1024) {
            listOf("track_id", "title", "artist", "album").forEach { json.put(it, json.getString(it).bounded(96)) }
        }
        json
    }
}
