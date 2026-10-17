package com.carcompanion.app.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrioritySendQueueTest {
    @Test fun newTrackPreemptsQueuedTelemetryAndArtworkAfterCurrentPacket() = runTest {
        val queue = PrioritySendQueue()
        val packetResponse = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        launch { queue.run(PrioritySendQueue.Priority.ARTWORK) { sent += "art in flight"; packetResponse.await() } }
        runCurrent()
        launch { queue.run(PrioritySendQueue.Priority.ARTWORK) { sent += "next art chunk" } }
        launch { queue.run(PrioritySendQueue.Priority.DRIVING) { sent += "telemetry" } }
        launch { queue.run(PrioritySendQueue.Priority.METADATA) { sent += "new track" } }
        runCurrent()
        assertEquals(listOf("art in flight"), sent)
        packetResponse.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("art in flight", "new track", "telemetry", "next art chunk"), sent)
    }

    @Test fun canceledQueuedTrackCannotOverwriteItsReplacement() = runTest {
        val queue = PrioritySendQueue()
        val ready = CompletableDeferred<Unit>()
        launch { queue.run(PrioritySendQueue.Priority.DRIVING) { ready.await() } }
        runCurrent()
        val obsolete = launch { queue.run(PrioritySendQueue.Priority.METADATA) { fail("Obsolete metadata must not run") } }
        runCurrent()
        obsolete.cancel()
        var replacementSent = false
        launch { queue.run(PrioritySendQueue.Priority.METADATA) { replacementSent = true } }
        ready.complete(Unit)
        advanceUntilIdle()
        assertTrue(replacementSent)
    }

    @Test fun equalPriorityPreservesRequestOrderAndFailureReleasesQueue() = runTest {
        val queue = PrioritySendQueue()
        val ready = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        launch { runCatching { queue.run(PrioritySendQueue.Priority.METADATA) { ready.await(); error("write failed") } } }
        runCurrent()
        repeat(3) { index -> launch { queue.run(PrioritySendQueue.Priority.METADATA) { sent += index } } }
        ready.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), sent)
    }

    @Test fun canceledArtworkKeepsAttOwnershipUntilItsCallbackArrives() = runTest {
        val gate = AttOperationGate()
        val oldWriteResponse = CompletableDeferred<Unit>()
        val newWriteResponse = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val obsolete = launch {
            gate.run { events += "old write started"; oldWriteResponse.await(); events += "old acknowledged" }
            fail("Canceled transfer must not send another packet")
        }
        runCurrent()
        obsolete.cancel()
        val replacement = launch {
            gate.run { events += "new write started"; newWriteResponse.await(); events += "new acknowledged" }
        }
        runCurrent()
        assertEquals(listOf("old write started"), events)
        oldWriteResponse.complete(Unit)
        runCurrent()
        assertEquals(listOf("old write started", "old acknowledged", "new write started"), events)
        assertFalse(replacement.isCompleted)
        newWriteResponse.complete(Unit)
        advanceUntilIdle()
        assertTrue(obsolete.isCancelled)
        assertEquals("new acknowledged", events.last())
    }

    @Test fun cancellationWhileWaitingNeverStartsAnAttOperation() = runTest {
        val gate = AttOperationGate()
        val response = CompletableDeferred<Unit>()
        launch { gate.run { response.await() } }
        runCurrent()
        val canceled = launch { gate.run { fail("Canceled queued ATT operation started") } }
        runCurrent()
        canceled.cancel()
        response.complete(Unit)
        advanceUntilIdle()
        var resumed = false
        gate.run { resumed = true }
        assertTrue(resumed)
    }
}
