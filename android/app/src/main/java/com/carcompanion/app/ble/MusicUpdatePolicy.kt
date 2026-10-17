package com.carcompanion.app.ble

import com.carcompanion.app.model.MusicState

/** The display interpolates progress; tiny MTUs must leave bandwidth for GPS and images. */
internal object MusicUpdatePolicy {
    fun shouldSend(current: MusicState, previous: MusicState?, elapsedMs: Long, mtu: Int): Boolean {
        if (previous == null) return true
        if (current == previous) return false
        if (current.copy(positionMs = 0) != previous.copy(positionMs = 0)) return true
        // A deliberate seek must not wait for the regular progress refresh.
        val advance = current.positionMs - previous.positionMs
        if (advance < -1_000 || advance > elapsedMs.coerceAtLeast(0) + 5_000) return true
        val interval = when {
            mtu < 64 -> 5_000L
            mtu < 185 -> 3_000L
            else -> 1_000L
        }
        return elapsedMs >= interval
    }
}
