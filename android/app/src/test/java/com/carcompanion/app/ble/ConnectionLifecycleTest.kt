package com.carcompanion.app.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionLifecycleTest {
    @Test fun rapidReconnectWaitsForOldSessionCleanup() = runTest {
        val lifecycle = ConnectionLifecycle(this)
        val events = mutableListOf<String>()
        lifecycle.replace {
            events += "old connected"
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { delay(100); events += "old closed" } }
        }
        runCurrent()
        lifecycle.replace { events += "intermediate must not start" }
        runCurrent() // Intermediate waits while old cleanup is in flight.
        lifecycle.replace { events += "new connected" }
        advanceUntilIdle()
        assertEquals(listOf("old connected", "old closed", "new connected"), events)
    }

    @Test fun explicitStopCancelsActiveSessionAndReleasesOwnership() = runTest {
        val lifecycle = ConnectionLifecycle(this)
        val closed = CompletableDeferred<Unit>()
        lifecycle.replace { try { awaitCancellation() } finally { closed.complete(Unit) } }
        runCurrent()
        assertTrue(lifecycle.isActive)
        lifecycle.cancel()
        advanceUntilIdle()
        assertTrue(closed.isCompleted)
        assertFalse(lifecycle.isActive)
        var reconnected = false
        lifecycle.replace { reconnected = true }
        advanceUntilIdle()
        assertTrue(reconnected)
    }
}
