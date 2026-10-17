package com.carcompanion.app.location

import com.carcompanion.app.model.SpeedLimitState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Explicitly road-matched results only. The service supplies one fix at most every 10 seconds. */
data class RoadFix(
    val latitude: Double,
    val longitude: Double,
    val bearingDegrees: Float?,
    val accuracyMeters: Float,
    val observedElapsedMs: Long,
    val observedEpochMs: Long,
)

interface SpeedLimitProvider {
    val name: String
    suspend fun lookup(fix: RoadFix): SpeedLimitState
}

class UnknownSpeedLimitProvider : SpeedLimitProvider {
    override val name = "Unavailable"
    override suspend fun lookup(fix: RoadFix) = SpeedLimitState()
}

class MockSpeedLimitProvider : SpeedLimitProvider {
    override val name = "DEMO ONLY"
    override suspend fun lookup(fix: RoadFix) = SpeedLimitState(35, 1f, 0, name, "synthetic-road")
}

/** A future on-device camera module must submit a validated observation, never an assumed default. */
interface SpeedSignRecognitionSource {
    suspend fun latestObservation(fix: RoadFix): SpeedLimitState?
}
class CameraSpeedLimitProvider(private val source: SpeedSignRecognitionSource) : SpeedLimitProvider {
    override val name = "Camera observation"
    override suspend fun lookup(fix: RoadFix): SpeedLimitState =
        source.latestObservation(fix)?.takeIf { it.displayMph != null } ?: SpeedLimitState(source = name)
}

/**
 * User-operated road matching endpoint, not a bundled licensed road database.
 * GET ?lat=..&lon=..&bearing=..&accuracy_m=.. returns:
 * {"roadMatched":true,"mph":35,"confidence":0.96,"observedAtEpochMs":..,"roadId":"..."}
 * See docs/speed-limits.md. No request is made until the user chooses this provider.
 */
class RoadMatchedSpeedLimitProvider(
    private val endpoint: String,
    private val bearerToken: String = "",
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : SpeedLimitProvider {
    override val name = "Configured road API"
    init {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "Speed-limit endpoint must be HTTPS without embedded credentials"
        }
    }
    override suspend fun lookup(fix: RoadFix): SpeedLimitState = withContext(Dispatchers.IO) {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            fix.longitude !in -180.0..180.0 || !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..50f) return@withContext SpeedLimitState(source = name)
        val query = buildMap {
            put("lat", fix.latitude.toString()); put("lon", fix.longitude.toString())
            put("accuracy_m", fix.accuracyMeters.toString())
            fix.bearingDegrees?.let { put("bearing", it.toString()) }
        }.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val url = URI(endpoint + if ('?' in endpoint) "&$query" else "?$query").toURL()
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (bearerToken.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext SpeedLimitState(source = "$name unavailable")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (output.size() <= 8192) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 8193 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 8_192) return@withContext SpeedLimitState(source = "$name invalid response")
            parseRoadResponse(JSONObject(String(bytes, Charsets.UTF_8)), nowEpochMs(), name)
        } finally { connection.disconnect() }
    }
}

internal fun parseRoadResponse(json: JSONObject, nowEpochMs: Long, source: String): SpeedLimitState {
    if (json.opt("roadMatched") != true) return SpeedLimitState(source = source)
    val mph = (json.opt("mph") as? Number)?.toDouble() ?: return SpeedLimitState(source = source)
    val confidence = (json.opt("confidence") as? Number)?.toDouble() ?: return SpeedLimitState(source = source)
    val observed = (json.opt("observedAtEpochMs") as? Number)?.toLong() ?: return SpeedLimitState(source = source)
    val age = nowEpochMs - observed
    if (!mph.isFinite() || mph % 1.0 != 0.0 || mph !in 1.0..200.0 || !confidence.isFinite() || confidence !in 0.8..1.0 || age !in 0 until 30_000L) {
        return SpeedLimitState(source = source)
    }
    return SpeedLimitState(mph.toInt(), confidence.toFloat(), age, source, json.optString("roadId").take(128).ifBlank { null })
}
