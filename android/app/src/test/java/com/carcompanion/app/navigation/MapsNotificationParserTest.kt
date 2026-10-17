package com.carcompanion.app.navigation

import com.carcompanion.app.model.Maneuver
import org.junit.Assert.*
import org.junit.Test

class MapsNotificationParserTest {
    private fun notification(title: String = "500 ft", text: String = "Turn right onto Ocean Avenue", category: String? = "navigation", ongoing: Boolean = true) =
        NavigationNotification(MapsNotificationParser.MAPS_PACKAGE, "key", ongoing, category, title, text)

    @Test fun explicitInstructionExposesOnlySupportedFields() {
        val result = MapsNotificationParser.parse(notification(), 1000)
        assertTrue(result.state!!.active)
        assertTrue(result.state.reliable)
        assertEquals(Maneuver.RIGHT, result.state.maneuver)
        assertEquals("Ocean Avenue", result.state.road)
        assertEquals("500 ft", result.state.distanceText)
        assertEquals("", result.state.etaText)
    }
    @Test fun distanceAndStreetWithoutTextualTurnDoNotInventArrowOrRoad() {
        val state = MapsNotificationParser.parse(notification(text = "Ocean Avenue"), 1000).state!!
        assertEquals(Maneuver.UNKNOWN, state.maneuver)
        assertFalse(state.reliable)
        assertEquals("", state.road)
    }
    @Test fun nonNavigationMapsNotificationDoesNotStartRoute() {
        assertNull(MapsNotificationParser.parse(notification("Restaurant review", "Write a review", null), 1000).state)
    }
    @Test fun nonOngoingNotificationDoesNotStartRoute() {
        assertNull(MapsNotificationParser.parse(notification(ongoing = false), 1000).state)
    }
    @Test fun wrongPackageIgnored() {
        assertNull(MapsNotificationParser.parse(notification().copy(packageName = "other.app"), 1000).state)
    }
    @Test fun noCategoryRequiresBothInstructionAndDistance() {
        assertNotNull(MapsNotificationParser.parse(notification(category = null), 1000).state)
        assertNull(MapsNotificationParser.parse(notification(title = "Drive safely", category = null), 1000).state)
    }
    @Test fun localizedUnknownFormatNeverInventsInstruction() {
        val state = MapsNotificationParser.parse(notification(text = "Gira a la derecha hacia Calle Mayor"), 1000).state!!
        assertEquals(Maneuver.UNKNOWN, state.maneuver)
        assertFalse(state.reliable)
    }
    @Test fun turnWordsInsideRoadNamesDoNotChangeManeuver() {
        assertEquals(Maneuver.RIGHT, MapsNotificationParser.parse(notification(text = "Turn right onto Left Bank Road"), 1000).state!!.maneuver)
        assertEquals(Maneuver.LEFT, MapsNotificationParser.parse(notification(text = "Turn left onto Roundabout Way"), 1000).state!!.maneuver)
        assertEquals(Maneuver.STRAIGHT, MapsNotificationParser.parse(notification(text = "Continue on Right Street"), 1000).state!!.maneuver)
    }
    @Test fun prefixedDistanceRecognizedWithoutInferringExtraData() {
        val state = MapsNotificationParser.parse(notification(text = "In 500 ft, Turn slight left onto Ocean Avenue"), 1000).state!!
        assertEquals(Maneuver.SLIGHT_LEFT, state.maneuver)
        assertEquals("Ocean Avenue", state.road)
    }
    @Test fun staleNotificationReturnsInactive() {
        val state = MapsNotificationParser.parse(notification(), 1000).state!!
        assertFalse(state.fresh(31_001).active)
        assertEquals(Maneuver.UNKNOWN, state.fresh(31_001).maneuver)
    }
}
