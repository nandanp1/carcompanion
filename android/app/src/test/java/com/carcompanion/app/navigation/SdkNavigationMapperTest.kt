package com.carcompanion.app.navigation

import com.carcompanion.app.model.Maneuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as GoogleManeuver
import org.junit.Assert.*
import org.junit.Test

class SdkNavigationMapperTest {
    @Test fun mapsStructuredTurnWithoutReadingRoadWords() {
        val info = NavInfo.builder().setNavState(NavState.ENROUTE)
            .setCurrentStep(StepInfo.builder().setManeuver(GoogleManeuver.TURN_RIGHT).setFullRoadName("Left Lane").setStepNumber(4).build())
            .setDistanceToCurrentStepMeters(100).setTimeToFinalDestinationSeconds(601).build()
        val state = SdkNavigationMapper.map(info, 1_000)
        assertEquals(Maneuver.RIGHT, state.maneuver)
        assertEquals("Left Lane", state.road)
        assertEquals(5L, state.stepId)
        assertEquals("330 ft", state.distanceText)
        assertEquals("11 min", state.etaText)
        assertTrue(state.active)
        assertTrue(state.reliable)
        assertFalse(state.fresh(31_001).active)
    }
    @Test fun reroutingClearsTurnAndDistance() {
        val state = SdkNavigationMapper.map(NavInfo.builder().setNavState(NavState.REROUTING).build(), 1_000)
        assertTrue(state.active)
        assertEquals(Maneuver.UNKNOWN, state.maneuver)
        assertEquals("", state.distanceText)
        assertEquals("Rerouting…", state.road)
    }
    @Test fun stoppedOrUnknownClearsRoute() {
        listOf(NavState.STOPPED, NavState.UNKNOWN).forEach {
            assertFalse(SdkNavigationMapper.map(NavInfo.builder().setNavState(it).build(), 1_000).active)
        }
    }
    @Test fun unsupportedManeuversStayUnknown() {
        assertEquals(Maneuver.UNKNOWN, SdkNavigationMapper.maneuver(9999))
        assertEquals(Maneuver.UNKNOWN, SdkNavigationMapper.maneuver(GoogleManeuver.FERRY_BOAT))
        assertEquals(Maneuver.ROUNDABOUT, SdkNavigationMapper.maneuver(GoogleManeuver.ROUNDABOUT_EXIT_CLOCKWISE))
        assertEquals(Maneuver.U_TURN, SdkNavigationMapper.maneuver(GoogleManeuver.TURN_U_TURN_COUNTERCLOCKWISE))
    }
    @Test fun missingMeasurementsAreNotZero() {
        assertEquals("", SdkNavigationMapper.distance(null))
        assertEquals("", SdkNavigationMapper.duration(-1))
        assertEquals("1.0 mi", SdkNavigationMapper.distance(1609))
    }
}
