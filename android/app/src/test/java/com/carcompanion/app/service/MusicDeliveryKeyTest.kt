package com.carcompanion.app.service

import com.carcompanion.app.model.ConnectionState
import com.carcompanion.app.model.DisplayState
import com.carcompanion.app.model.DrivingState
import com.carcompanion.app.model.MusicState
import org.junit.Assert.*
import org.junit.Test

class MusicDeliveryKeyTest {
    private val state = DisplayState(music = MusicState(title = "First", trackId = "one", playing = true))

    @Test fun progressAndGpsTicksDoNotQueueUrgentMetadata() {
        val tick = state.copy(music = state.music.copy(positionMs = 1_000),
            driving = DrivingState(speedMph = 20f), timeEpochSeconds = 1_000)
        assertEquals(MusicDeliveryKey.from(state), MusicDeliveryKey.from(tick))
    }

    @Test fun trackArtworkAndPlayPauseChangesAreUrgent() {
        for (music in listOf(state.music.copy(title = "Second", trackId = "two"),
            state.music.copy(artworkId = 42), state.music.copy(playing = false))) {
            assertNotEquals(MusicDeliveryKey.from(state), MusicDeliveryKey.from(state.copy(music = music)))
        }
    }

    @Test fun reconnectAndDemoTransitionResendCurrentMetadata() {
        assertNotEquals(MusicDeliveryKey.from(state),
            MusicDeliveryKey.from(state.copy(connection = ConnectionState(connected = true))))
        assertNotEquals(MusicDeliveryKey.from(state), MusicDeliveryKey.from(state.copy(demo = true)))
    }
}
