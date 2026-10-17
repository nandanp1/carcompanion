package com.carcompanion.app.location

import com.carcompanion.app.model.SpeedLimitState
import org.junit.Assert.*
import org.junit.Test

class SpeedPolicyTest {
    private val sample = SpeedSample(10f, 5f, 0.4f, 1000)
    @Test fun convertsAndroidMetersPerSecondToMph() {
        assertEquals(22.36936f, SpeedPolicy.resolve(sample, 1100).speedMph!!, 0.0001f)
    }
    @Test fun staleGpsDisplaysUnavailableAtBoundary() {
        assertNotNull(SpeedPolicy.resolve(sample, 3999).speedMph)
        assertNull(SpeedPolicy.resolve(sample, 4000).speedMph)
    }
    @Test fun missingGpsNeverMeansZero() { assertNull(SpeedPolicy.resolve(null, 1000).speedMph) }
    @Test fun actualStationaryGpsRemainsZero() {
        assertEquals(0f, SpeedPolicy.resolve(sample.copy(metersPerSecond = 0f), 1000).speedMph!!, 0f)
    }
    @Test fun missingOrPoorAccuracyRejectsSpeed() {
        assertNull(SpeedPolicy.resolve(sample.copy(accuracyMeters = null), 1000).speedMph)
        assertNull(SpeedPolicy.resolve(sample.copy(accuracyMeters = 51f), 1000).speedMph)
        assertNull(SpeedPolicy.resolve(sample.copy(speedAccuracyMetersPerSecond = 6f), 1000).speedMph)
        assertNull(SpeedPolicy.resolve(sample.copy(speedAccuracyMetersPerSecond = null), 1000).speedMph)
    }
    @Test fun malformedAndMockSpeedsRejected() {
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 140f)) {
            assertNull(SpeedPolicy.resolve(sample.copy(metersPerSecond = speed), 1000).speedMph)
        }
        assertNull(SpeedPolicy.resolve(sample.copy(mock = true), 1000).speedMph)
        assertNull(SpeedPolicy.resolve(sample, 999).speedMph)
    }
    @Test fun unknownLimitRemainsUnavailable() { assertNull(SpeedLimitState().displayMph) }
    @Test fun lowConfidenceOrOldLimitSuppressed() {
        assertNull(SpeedLimitState(35, .79f, 0).displayMph)
        assertNull(SpeedLimitState(35, .9f, 30_000).displayMph)
        assertEquals(35, SpeedLimitState(35, .8f, 29_999).displayMph)
    }
}
