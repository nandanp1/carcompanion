package com.carcompanion.app.location

import com.carcompanion.app.model.SpeedLimitState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeedLimitProviderTest {
    private val now = 100_000L
    private val fix = RoadFix(37.0, -122.0, 90f, 4f, 1000, now)
    private fun response() = JSONObject().put("roadMatched", true).put("mph", 35)
        .put("confidence", 0.95).put("observedAtEpochMs", now - 500).put("roadId", "test-road")

    @Test fun validRoadMatchPreservesSourceConfidenceAndAge() {
        val state = parseRoadResponse(response(), now, "test API")
        assertEquals(35, state.displayMph)
        assertEquals(500, state.ageMs)
        assertEquals("test-road", state.roadId)
        assertEquals("test API", state.source)
        assertEquals(.95f, state.confidence, .001f)
    }
    @Test fun unmatchedOrMissingDataNeverGetsDefaultLimit() {
        assertNull(parseRoadResponse(response().put("roadMatched", false), now, "API").mph)
        assertNull(parseRoadResponse(JSONObject(), now, "API").mph)
        assertNull(parseRoadResponse(response().put("mph", JSONObject.NULL), now, "API").mph)
        assertNull(parseRoadResponse(response().put("mph", "35"), now, "API").mph)
    }
    @Test fun uncertainOldFutureOrFractionalLimitRejected() {
        assertNull(parseRoadResponse(response().put("confidence", .79), now, "API").mph)
        assertNull(parseRoadResponse(response().put("confidence", 1.1), now, "API").mph)
        assertNull(parseRoadResponse(response().put("observedAtEpochMs", now - 30_000), now, "API").mph)
        assertNull(parseRoadResponse(response().put("observedAtEpochMs", now + 1), now, "API").mph)
        assertNull(parseRoadResponse(response().put("mph", 35.5), now, "API").mph)
    }
    @Test fun unknownAndCameraProvidersHaveFunctionalUnavailablePath() = runBlocking {
        assertNull(UnknownSpeedLimitProvider().lookup(fix).displayMph)
        val camera = CameraSpeedLimitProvider(object : SpeedSignRecognitionSource {
            override suspend fun latestObservation(fix: RoadFix) = SpeedLimitState(35, .4f, 0)
        })
        assertNull(camera.lookup(fix).displayMph)
        assertEquals(35, MockSpeedLimitProvider().lookup(fix).displayMph)
    }
    @Test fun endpointsMustUseHttpsWithoutEmbeddedCredentials() {
        for (url in listOf("http://example.com", "https://user:secret@example.com", "https://example.com#fragment", "invalid")) {
            assertThrows(IllegalArgumentException::class.java) { RoadMatchedSpeedLimitProvider(url) }
        }
    }
    @Test fun displayContractRejectsInvalidConfidenceAndNegativeAge() {
        assertNull(SpeedLimitState(35, 1.1f, 0).displayMph)
        assertNull(SpeedLimitState(35, Float.NaN, 0).displayMph)
        assertNull(SpeedLimitState(35, .9f, -1).displayMph)
    }
}
