package com.carcompanion.app.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

/** Bounded message scheduling: urgent metadata runs before queued telemetry or artwork. */
internal class PrioritySendQueue {
    enum class Priority { METADATA, DRIVING, ARTWORK }
    private class Ticket(val priority: Priority, val ready: CompletableDeferred<Unit> = CompletableDeferred()) {
        var granted = false
    }
    private val lock = Any()
    private val waiting = ArrayDeque<Ticket>()
    private var busy = false

    suspend fun <T> run(priority: Priority, action: suspend () -> T): T {
        val ticket = Ticket(priority)
        synchronized(lock) {
            check(waiting.size < 32) { "BLE send queue is full" }
            if (!busy) {
                busy = true
                ticket.granted = true
                ticket.ready.complete(Unit)
            } else waiting.addLast(ticket)
        }
        try {
            ticket.ready.await()
            currentCoroutineContext().ensureActive()
            return action()
        } finally {
            synchronized(lock) {
                if (ticket.granted) {
                    val next = waiting.minByOrNull { it.priority.ordinal }
                    if (next == null) busy = false
                    else {
                        waiting.remove(next)
                        next.granted = true
                        next.ready.complete(Unit)
                    }
                } else waiting.remove(ticket)
            }
        }
    }
}

/** Cancellation cannot release an ATT operation until its callback or bounded timeout. */
internal class AttOperationGate {
    private val mutex = Mutex()
    suspend fun <T> run(action: suspend () -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val result = withContext(NonCancellable) { action() }
        currentCoroutineContext().ensureActive()
        result
    }
}
