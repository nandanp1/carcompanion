package com.carcompanion.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.carcompanion.app.model.AppStore
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GpsSpeedMonitor(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val current = MutableStateFlow<SpeedSample?>(null)
    val samples: StateFlow<SpeedSample?> = current.asStateFlow()
    private val currentFix = MutableStateFlow<RoadFix?>(null)
    val fixes: StateFlow<RoadFix?> = currentFix.asStateFlow()
    private var running = false
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
        override fun onLocationAvailability(availability: LocationAvailability) {
            if (running && !availability.isLocationAvailable) AppStore.diagnostic { it.copy(gpsStatus = "Waiting for a fresh location fix") }
        }
    }

    /** Recheck while running: revocation must clear an otherwise plausible cached speed immediately. */
    fun checkPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return true
        if (running || current.value != null) stop()
        AppStore.diagnostic { it.copy(gpsStatus = "Precise location permission is required for speed") }
        return false
    }

    fun start() {
        if (!checkPermission() || running) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L).setMaxUpdateDelayMillis(0L).setWaitForAccurateLocation(true).build()
        try {
            running = true
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener {
                    running = false
                    AppStore.diagnostic { d -> d.copy(gpsStatus = "Location unavailable: ${it.javaClass.simpleName}") }
                }
            AppStore.diagnostic { it.copy(gpsStatus = "Waiting for GPS speed and accuracy") }
        } catch (_: SecurityException) {
            running = false
            AppStore.diagnostic { it.copy(gpsStatus = "Location permission was revoked") }
        }
    }

    @Suppress("DEPRECATION")
    private fun onLocation(location: Location) {
        // Fused Location can already have posted a callback when stop() removes its subscription.
        if (!running) return
        val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
        val observed = location.elapsedRealtimeNanos / 1_000_000L
        if (observed < (current.value?.observedElapsedMs ?: 0)) return
        val sample = SpeedSample(
            location.speed.takeIf { location.hasSpeed() }, location.accuracy.takeIf { location.hasAccuracy() },
            location.speedAccuracyMetersPerSecond.takeIf { location.hasSpeedAccuracy() }, observed, mock,
        )
        current.value = sample
        currentFix.value = if (!mock && location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy in 0f..50f &&
            SystemClock.elapsedRealtime() - observed in 0 until SpeedPolicy.STALE_AFTER_MS) {
            RoadFix(location.latitude, location.longitude, location.bearing.takeIf { location.hasBearing() },
                location.accuracy, observed, location.time)
        } else null
        val resolved = SpeedPolicy.resolve(sample, SystemClock.elapsedRealtime())
        AppStore.diagnostic { it.copy(gpsStatus = when {
            mock -> "Mock location rejected; use the explicit demo mode"
            resolved.speedMph == null -> "Speed unavailable: fix, speed, or accuracy is insufficient"
            else -> "Live GPS speed · ±${"%.1f".format(sample.speedAccuracyMetersPerSecond)} m/s"
        }) }
    }

    fun stop() {
        runCatching { client.removeLocationUpdates(callback) }
        running = false
        current.value = null
        currentFix.value = null
    }
}
