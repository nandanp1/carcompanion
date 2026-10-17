package com.carcompanion.app.service

import com.carcompanion.app.model.DisplayState
import com.carcompanion.app.model.MusicState

/** Track/artwork/playback changes bypass periodic driving updates; progress uses the normal tick. */
internal data class MusicDeliveryKey(val music: MusicState, val demo: Boolean, val connected: Boolean) {
    companion object {
        fun from(state: DisplayState) = MusicDeliveryKey(
            state.music.copy(positionMs = 0), state.demo, state.connection.connected,
        )
    }
}
