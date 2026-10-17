package com.carcompanion.app.service

import com.carcompanion.app.model.Artwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestArtworkSenderTest {
    private fun art(id: Long) = Artwork(id, byteArrayOf(id.toByte()))

    @Test fun identicalCrcDoesNotRestartMetadataOrInFlightTransfer() = runTest {
        val metadataWritten = CompletableDeferred<Unit>()
        var metadataWrites = 0
        var transfers = 0
        var cancellations = 0
        val sender = LatestArtworkSender({ true }, {
            metadataWrites++
            metadataWritten.await()
        }, {
            transfers++
            try { awaitCancellation() } finally { cancellations++ }
        }, { throw AssertionError(it) })
        backgroundScope.launch { sender.run() }
        sender.offer(art(1)); runCurrent()
        sender.offer(art(1)); runCurrent()
        assertEquals(1, metadataWrites)
        metadataWritten.complete(Unit); runCurrent()
        sender.offer(art(1)); runCurrent()
        assertEquals(1, transfers)
        assertEquals(0, cancellations)
    }

    @Test fun disconnectAllowsSameCrcWhileOldCancellationIsStillFinishing() = runTest {
        val finishedWrite = CompletableDeferred<Unit>()
        var transfers = 0
        val sender = LatestArtworkSender({ true }, {}, {
            transfers++
            val firstAttempt = transfers == 1
            try { awaitCancellation() }
            finally { if (firstAttempt) withContext(NonCancellable) { finishedWrite.await() } }
        }, { throw AssertionError(it) })
        backgroundScope.launch { sender.run() }
        sender.offer(art(1)); runCurrent()
        sender.offer(null); runCurrent()
        sender.offer(art(1)); runCurrent()
        assertEquals(1, transfers)
        finishedWrite.complete(Unit); runCurrent()
        assertEquals(2, transfers)
        // Cleanup from the canceled attempt must not clear its replacement's duplicate guard.
        sender.offer(art(1)); runCurrent()
        assertEquals(2, transfers)
    }

    @Test fun completedImageCanBeOfferedAgainForReconnect() = runTest {
        var transfers = 0
        val sender = LatestArtworkSender({ true }, {}, { transfers++ }, { throw AssertionError(it) })
        backgroundScope.launch { sender.run() }
        sender.offer(art(1)); runCurrent()
        sender.offer(art(1)); runCurrent()
        assertEquals(2, transfers)
    }

    @Test fun newTrackCancelsTransferAndSendsItsMetadataBeforeArtwork() = runTest {
        val events = mutableListOf<String>()
        val sender = LatestArtworkSender({ true }, { events += "metadata" }, { image ->
            events += "transfer ${image.contentId}"
            try { awaitCancellation() } finally { events += "cancel ${image.contentId}" }
        }, { throw AssertionError(it) })
        val worker = backgroundScope.launch { sender.run() }
        sender.offer(art(1)); runCurrent()
        sender.offer(art(2)); runCurrent()
        assertEquals(listOf("metadata", "transfer 1", "cancel 1", "metadata", "transfer 2"), events)
        worker.cancel()
    }

    @Test fun clearingArtworkCancelsCurrentTransferWithoutWaitingForReplacementImage() = runTest {
        var cancelled = false
        var transfers = 0
        val sender = LatestArtworkSender({ true }, {}, {
            transfers++
            try { awaitCancellation() } finally { cancelled = true }
        }, { throw AssertionError(it) })
        backgroundScope.launch { sender.run() }
        sender.offer(art(1)); runCurrent()
        sender.offer(null); runCurrent()
        assertTrue(cancelled)
        assertEquals(1, transfers)
    }

    @Test fun replacementWaitsForInFlightWriteCleanupAndSkipsIntermediatePendingImages() = runTest {
        val finishedWrite = CompletableDeferred<Unit>()
        val transferred = mutableListOf<Long>()
        var currentId = 1L
        val sender = LatestArtworkSender({ it.contentId == currentId }, {}, { image ->
            transferred += image.contentId
            if (image.contentId == 1L) try { awaitCancellation() }
            finally { withContext(NonCancellable) { finishedWrite.await() } }
        }, { throw AssertionError(it) })
        backgroundScope.launch { sender.run() }
        sender.offer(art(1)); runCurrent()
        currentId = 2L; sender.offer(art(2)); runCurrent()
        currentId = 3L; sender.offer(art(3))
        currentId = 4L; sender.offer(art(4)); runCurrent()
        assertEquals(listOf(1L), transferred)
        finishedWrite.complete(Unit); runCurrent()
        assertEquals(listOf(1L, 4L), transferred)
    }

    @Test fun staleTrackIsRejectedAfterMetadataWriteCompletes() = runTest {
        val metadataWritten = CompletableDeferred<Unit>()
        var currentId = 1L
        var transferred = false
        val sender = LatestArtworkSender({ it.contentId == currentId }, { metadataWritten.await() },
            { transferred = true }, { throw AssertionError(it) })
        backgroundScope.launch { sender.run() }
        sender.offer(art(1)); runCurrent()
        currentId = 2L
        metadataWritten.complete(Unit); runCurrent()
        assertFalse(transferred)
    }

    @Test fun failureDoesNotKillCollectorAndReconnectCanOfferSameImageAgain() = runTest {
        var attempts = 0
        var failures = 0
        val sender = LatestArtworkSender({ true }, {}, {
            attempts++
            if (attempts == 1) throw IllegalStateException("Disconnected")
        }, { failures++ })
        backgroundScope.launch { sender.run() }
        val image = art(1)
        sender.offer(image); runCurrent()
        sender.offer(image); runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, failures)
    }
}
