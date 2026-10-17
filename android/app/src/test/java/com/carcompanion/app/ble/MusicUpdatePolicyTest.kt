package com.carcompanion.app.ble

import com.carcompanion.app.model.MusicState
import org.junit.Assert.*
import org.junit.Test

class MusicUpdatePolicyTest {
    private val old = MusicState(title = "Synthetic track", artist = "Test artist", album = "Test album",
        durationMs = 180_000, positionMs = 10_000, playing = true, artworkId = 123, trackId = "test-track")

    @Test fun tinyMtuLeavesBandwidthBetweenProgressUpdates() {
        assertFalse(MusicUpdatePolicy.shouldSend(old.copy(positionMs = 14_999), old, 4_999, 23))
        assertTrue(MusicUpdatePolicy.shouldSend(old.copy(positionMs = 15_000), old, 5_000, 23))
    }

    @Test fun negotiatedLargerMtuKeepsOneSecondProgress() {
        assertFalse(MusicUpdatePolicy.shouldSend(old.copy(positionMs = 10_999), old, 999, 247))
        assertTrue(MusicUpdatePolicy.shouldSend(old.copy(positionMs = 11_000), old, 1_000, 247))
        assertFalse(MusicUpdatePolicy.shouldSend(old.copy(positionMs = 12_999), old, 2_999, 64))
        assertTrue(MusicUpdatePolicy.shouldSend(old.copy(positionMs = 13_000), old, 3_000, 64))
    }

    @Test fun allSignificantMetadataChangesBypassProgressCadence() {
        val changes = listOf(old.copy(title = "New title"), old.copy(artist = "New artist"),
            old.copy(album = "New album"), old.copy(trackId = "new-track"), old.copy(artworkId = 456),
            old.copy(artworkId = null), old.copy(durationMs = 200_000), old.copy(playing = false))
        changes.forEach { assertTrue(MusicUpdatePolicy.shouldSend(it, old, 0, 23)) }
    }

    @Test fun firstConnectionAndReconnectSendImmediatelyButIdenticalSnapshotDoesNot() {
        assertTrue(MusicUpdatePolicy.shouldSend(old, null, 0, 23))
        assertFalse(MusicUpdatePolicy.shouldSend(old, old, 60_000, 23))
    }

    @Test fun forwardAndBackwardSeeksSendImmediately() {
        assertTrue(MusicUpdatePolicy.shouldSend(old.copy(positionMs = 40_000), old, 100, 23))
        assertTrue(MusicUpdatePolicy.shouldSend(old.copy(positionMs = 2_000), old, 100, 23))
    }
}
