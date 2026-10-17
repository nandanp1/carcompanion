package com.carcompanion.app.location

import com.carcompanion.app.model.DrivingState
import com.carcompanion.app.model.SpeedLimitState

/** elapsedRealtime timestamps avoid wall-clock jumps. The speed itself comes from Android location. */
data class SpeedSample(
    val metersPerSecond: Float?,
    val accuracyMeters: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val observedElapsedMs: Long,
    val mock: Boolean = false,
)

object SpeedPolicy {
    const val STALE_AFTER_MS = 3_000L
    const val METERS_PER_SECOND_TO_MPH = 2.2369362921f
    fun resolve(sample: SpeedSample?, nowElapsedMs: Long, limit: SpeedLimitState = SpeedLimitState()): DrivingState {
        if (sample == null) return DrivingState(speedLimit = limit)
        val age = nowElapsedMs - sample.observedElapsedMs
        val speed = sample.metersPerSecond?.takeIf {
            age in 0 until STALE_AFTER_MS && it.isFinite() && it in 0f..134.112f && !sample.mock &&
                sample.accuracyMeters != null && sample.accuracyMeters.isFinite() && sample.accuracyMeters in 0f..50f &&
                sample.speedAccuracyMetersPerSecond != null && sample.speedAccuracyMetersPerSecond.isFinite() &&
                sample.speedAccuracyMetersPerSecond in 0f..5f
        }
        return DrivingState(speed?.times(METERS_PER_SECOND_TO_MPH), age.coerceAtLeast(0), sample.speedAccuracyMetersPerSecond, sample.accuracyMeters, limit)
    }
}
