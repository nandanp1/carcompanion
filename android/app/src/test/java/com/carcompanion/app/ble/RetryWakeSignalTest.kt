package com.carcompanion.app.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryWakeSignalTest {
    @Test fun deviceAppearanceInterruptsLongBackoffImmediately() = runTest {
        val signal = RetryWakeSignal()
        var retries = 0
        launch { signal.await(30_000); retries++ }
        runCurrent()
        advanceTimeBy(2_000)
        signal.wake()
        runCurrent()
        assertEquals(1, retries)
        assertEquals(2_000L, currentTime)
    }

    @Test fun bluetoothOnBeforeWaitIsNotLostAndDuplicateEventsCoalesce() = runTest {
        val signal = RetryWakeSignal()
        repeat(5) { signal.wake() }
        signal.await(30_000)
        assertEquals(0L, currentTime)
        signal.await(1_000)
        assertEquals(1_000L, currentTime)
    }

    @Test fun cancellationStopsPendingRecoveryAndNewSessionClearsOldEvents() = runTest {
        val signal = RetryWakeSignal()
        var retries = 0
        val waiting = launch { signal.await(30_000); retries++ }
        runCurrent()
        waiting.cancel()
        runCurrent()
        signal.wake()
        signal.clear()
        signal.await(1_000)
        assertEquals(0, retries)
        assertEquals(1_000L, currentTime)
    }

    @Test fun absentDeviceStillRetriesAfterBoundedBackoff() = runTest {
        val signal = RetryWakeSignal()
        signal.await(30_000)
        assertEquals(30_000L, currentTime)
    }
}
