package com.carcompanion.app.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationRemovalDebouncerTest {
    private fun notification(key: String) = NavigationNotification(MapsNotificationParser.MAPS_PACKAGE,
        key, true, "navigation", "350 ft · Keep right to stay on Sample Rd", "")

    @Test fun sequentialReplacementDuringEmptySnapshotGapNeverPublishesInactive() = runTest {
        val tracker = NavigationNotificationTracker()
        tracker.posted(notification("old"), 0)
        var snapshots = 0
        val removal = NavigationRemovalDebouncer(this) {
            snapshots++
            tracker.reconcile(emptyList(), testScheduler.currentTime)
        }
        removal.removed(); runCurrent()
        advanceTimeBy(100)
        // The system snapshot could be empty here; the pending removal defers reading it.
        assertTrue(removal.isPending)
        assertTrue(tracker.state(100).active)
        tracker.posted(notification("new"), 100)
        removal.cancel()
        advanceTimeBy(200); runCurrent()
        assertEquals(0, snapshots)
        assertTrue(tracker.state(300).active)
    }

    @Test fun confirmedRemovalEndsRouteAfterBoundedGrace() = runTest {
        val tracker = NavigationNotificationTracker()
        tracker.posted(notification("route"), 0)
        val removal = NavigationRemovalDebouncer(this) {
            tracker.reconcile(emptyList(), testScheduler.currentTime)
        }
        removal.removed(); runCurrent()
        advanceTimeBy(249)
        assertTrue(tracker.state(249).active)
        advanceTimeBy(1); runCurrent()
        assertFalse(tracker.state(250).active)
        assertFalse(removal.isPending)
    }

    @Test fun snapshotFailureAfterRemovalRetainsConfirmationUntilExpiryOrReplacement() = runTest {
        val tracker = NavigationNotificationTracker()
        tracker.posted(notification("old"), 0)
        val removal = NavigationRemovalDebouncer(this) {
            // Listener catches Binder failures without replacing the last successful snapshot.
            runCatching { throw IllegalStateException("Binder unavailable") }
        }
        removal.removed(); runCurrent()
        advanceTimeBy(250); runCurrent()
        assertTrue(tracker.state(250).active)
        assertFalse(tracker.state(30_001).active)
        tracker.posted(notification("new"), 400)
        assertTrue(tracker.state(400).active)
    }

    @Test fun furtherRemovalsCannotExtendGraceAndDisconnectCancelsPendingWork() = runTest {
        var reconciliations = 0
        val removal = NavigationRemovalDebouncer(this) { reconciliations++ }
        removal.removed(); runCurrent()
        advanceTimeBy(200)
        removal.removed()
        advanceTimeBy(50); runCurrent()
        assertEquals(1, reconciliations)
        removal.removed(); runCurrent()
        removal.cancel()
        advanceTimeBy(300); runCurrent()
        assertEquals(1, reconciliations)
    }
}
