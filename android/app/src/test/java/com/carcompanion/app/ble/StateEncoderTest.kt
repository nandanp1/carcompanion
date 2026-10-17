package com.carcompanion.app.ble

import com.carcompanion.app.model.*
import org.junit.Assert.*
import org.junit.Test

class StateEncoderTest {
    @Test fun missingSpeedLimitAndArtworkRemainExplicitNull() {
        val state = DisplayState()
        assertTrue(StateEncoder.driving(state).isNull("speed_mph"))
        assertTrue(StateEncoder.driving(state).has("limit_mph"))
        assertTrue(StateEncoder.driving(state).isNull("limit_mph"))
        assertTrue(StateEncoder.music(state).has("art_crc"))
        assertTrue(StateEncoder.music(state).isNull("art_crc"))
    }
    @Test fun staleSpeedAndUncertainLimitsSuppressed() {
        val state = DisplayState(driving = DrivingState(speedMph = 42f, speedAgeMs = 3000,
            speedLimit = SpeedLimitState(55, .79f, 0, "provider")))
        assertTrue(StateEncoder.driving(state).isNull("speed_mph"))
        assertTrue(StateEncoder.driving(state).isNull("limit_mph"))
    }
    @Test fun mockLimitsCannotEnterLiveMode() {
        val state = DisplayState(driving = DrivingState(speedLimit = SpeedLimitState(35, 1f, 0, "mock")))
        assertTrue(StateEncoder.driving(state).isNull("limit_mph"))
        assertEquals(35, StateEncoder.driving(state.copy(demo = true)).getInt("limit_mph"))
    }
    @Test fun unknownNavigationHasNoInventedInstruction() {
        val payload = StateEncoder.navigation(DisplayState(navigation = NavigationState(active = true)))
        assertEquals("unknown", payload.getString("maneuver"))
        assertTrue(payload.isNull("distance"))
        assertTrue(payload.isNull("road"))
        assertFalse(payload.getBoolean("reliable"))
    }
    @Test fun utf8TextRespectsWireBytes() {
        val payload = StateEncoder.music(DisplayState(music = MusicState(title = "夜".repeat(100))))
        assertTrue(payload.getString("title").toByteArray().size <= 128)
    }
    @Test fun nonFiniteInputsRemainUnavailable() {
        val payload = StateEncoder.driving(DisplayState(driving = DrivingState(42f, 0, horizontalAccuracyM = Float.NaN,
            speedLimit = SpeedLimitState(35, Float.NaN, 0, "provider"))))
        assertTrue(payload.isNull("speed_mph"))
        assertTrue(payload.isNull("accuracy_m"))
        assertEquals(0.0, payload.getDouble("limit_confidence"), 0.0)
    }
    @Test fun demoOnlyProviderCannotLeakIntoLiveMode() {
        val payload = StateEncoder.driving(DisplayState(driving = DrivingState(speedLimit = SpeedLimitState(35, 1f, 0, "DEMO ONLY"))))
        assertTrue(payload.isNull("limit_mph"))
    }
    @Test fun escapedMetadataFitsReassemblyBound() {
        val escaped = "\"".repeat(128)
        val json = StateEncoder.music(DisplayState(music = MusicState(escaped, escaped, escaped, trackId = escaped)))
        assertTrue(json.toString().toByteArray().size <= 1024)
    }
}
