package com.carcompanion.app.ble

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Presence and Bluetooth-on events interrupt backoff without starting another GATT owner. */
internal class RetryWakeSignal {
    private val pending = Channel<Unit>(Channel.CONFLATED)
    fun wake() { pending.trySend(Unit) }
    fun clear() { while (pending.tryReceive().isSuccess) { /* consume stale wakeups */ } }
    suspend fun await(delayMs: Long) { withTimeoutOrNull(delayMs) { pending.receive() } }
}
