package com.carcompanion.app.navigation

import com.carcompanion.app.model.Maneuver
import com.carcompanion.app.model.NavigationState
import org.junit.Assert.*
import org.junit.Test

class NavigationNotificationTrackerTest {
    private fun notification(key: String = "route", title: String = "350 ft · Keep right to stay on Sample Rd") =
        NavigationNotification(MapsNotificationParser.MAPS_PACKAGE, key, true, "navigation", title, "")

    @Test fun ongoingUnchangedSnapshotKeepsRouteButClearsOldInstruction() {
        val tracker = NavigationNotificationTracker()
        val input = notification()
        tracker.posted(input, 1_000)
        tracker.reconcile(listOf(input), 35_000)
        val state = tracker.state(35_000)
        assertTrue(state.active)
        assertFalse(state.reliable)
        assertEquals(Maneuver.UNKNOWN, state.maneuver)
        assertEquals("", state.distanceText)
        assertEquals("", state.road)
        assertEquals("", state.etaText)
        assertEquals(1_000L, state.updatedAtElapsedMs)
        assertEquals(35_000L, state.routeConfirmedAtElapsedMs)
        assertTrue(tracker.diagnostic(35_000).contains("stale instruction"))
    }

    @Test fun changedSnapshotProvidesFreshInstruction() {
        val tracker = NavigationNotificationTracker()
        tracker.posted(notification(), 1_000)
        tracker.reconcile(listOf(notification(title = "200 ft · Turn left onto Sample Road")), 35_000)
        val state = tracker.state(35_000)
        assertTrue(state.active)
        assertTrue(state.reliable)
        assertEquals(Maneuver.LEFT, state.maneuver)
        assertEquals(35_000L, state.updatedAtElapsedMs)
    }

    @Test fun postedReplacementSupersedesOldKeyWhileBothTemporarilyExist() {
        val tracker = NavigationNotificationTracker()
        tracker.posted(notification("old"), 1_000)
        tracker.posted(notification("new", "200 ft · Turn left onto Sample Road"), 2_000)
        assertEquals(Maneuver.LEFT, tracker.state(2_000).maneuver)
        tracker.reconcile(listOf(notification("old"), notification("new", "200 ft · Turn left onto Sample Road")), 3_000)
        assertEquals(Maneuver.LEFT, tracker.state(3_000).maneuver)
    }

    @Test fun realPostedUpdateRefreshesInstructionEvenWhenTextUnchanged() {
        val tracker = NavigationNotificationTracker()
        tracker.posted(notification(), 1_000)
        tracker.posted(notification(), 35_000)
        assertTrue(tracker.state(35_000).reliable)
        assertEquals(35_000L, tracker.state(35_000).updatedAtElapsedMs)
    }

    @Test fun categoryOnlyRouteCanBeConfirmedWithoutFabricatingInstruction() {
        val tracker = NavigationNotificationTracker()
        val input = notification(title = "Navigation is running")
        tracker.posted(input, 1_000)
        tracker.reconcile(listOf(input), 60_000)
        assertTrue(tracker.state(60_000).active)
        assertFalse(tracker.state(60_000).reliable)
        assertEquals(Maneuver.UNKNOWN, tracker.state(60_000).maneuver)
    }

    @Test fun unavailableSnapshotDoesNotEndRouteUntilPresenceExpires() {
        val tracker = NavigationNotificationTracker()
        tracker.posted(notification(), 1_000)
        // On a failed Binder call, no successful reconciliation is supplied.
        assertTrue(tracker.state(30_000).active)
        assertFalse(tracker.state(31_001).active)
    }

    @Test fun completeSnapshotDetectsRouteRemovalOrLossOfOngoingFlag() {
        val tracker = NavigationNotificationTracker()
        tracker.posted(notification(), 1_000)
        tracker.reconcile(emptyList(), 2_000)
        assertFalse(tracker.state(2_000).active)
        tracker.posted(notification(), 3_000)
        tracker.reconcile(listOf(notification().copy(ongoing = false)), 4_000)
        assertFalse(tracker.state(4_000).active)
    }

    @Test fun removalReconciledWithReplacementKeepsActiveRoute() {
        val tracker = NavigationNotificationTracker()
        tracker.posted(notification("old"), 1_000)
        tracker.removed("old")
        tracker.reconcile(listOf(notification("new")), 2_000)
        assertTrue(tracker.state(2_000).active)
        tracker.removed("old")
        assertTrue(tracker.state(2_000).active)
        tracker.removed("new")
        assertFalse(tracker.state(2_000).active)
    }

    @Test fun initialListenerSnapshotRecoversRouteAndDisconnectClearsIt() {
        val tracker = NavigationNotificationTracker()
        tracker.reconcile(listOf(notification()), 1_000)
        assertTrue(tracker.state(1_000).active)
        tracker.clear()
        assertFalse(tracker.state(1_000).active)
    }

    @Test fun unsupportedSnapshotDoesNotCreateRoute() {
        val tracker = NavigationNotificationTracker()
        tracker.reconcile(listOf(notification().copy(packageName = "another.app"),
            notification().copy(category = null, title = "Write a review")), 1_000)
        assertFalse(tracker.state(1_000).active)
    }

    @Test fun structuredAndMockStatesRetainUpdateBasedExpiryUnlessPresenceExplicit() {
        val state = NavigationState(active = true, maneuver = Maneuver.RIGHT,
            updatedAtElapsedMs = 1_000, reliable = true)
        assertTrue(state.fresh(31_000).active)
        assertFalse(state.fresh(31_001).active)
        val confirmed = state.copy(routeConfirmedAtElapsedMs = 35_000).fresh(35_000)
        assertTrue(confirmed.active)
        assertFalse(confirmed.reliable)
    }
}
