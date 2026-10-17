package com.carcompanion.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSelectionTest {
    @Test fun routeReturnsToPreviousManualPage() {
        AppStore.navigation(NavigationState())
        AppStore.selectPage(DisplayPage.MUSIC)
        AppStore.navigation(NavigationState(active = true))
        assertEquals(DisplayPage.NAVIGATION, AppStore.state.value.page)
        AppStore.navigation(NavigationState())
        assertEquals(DisplayPage.MUSIC, AppStore.state.value.page)
    }
    @Test fun manualChoiceDuringRouteSurvivesSubsequentNavigationUpdates() {
        AppStore.navigation(NavigationState())
        AppStore.selectPage(DisplayPage.DRIVING)
        AppStore.navigation(NavigationState(active = true))
        AppStore.selectPage(DisplayPage.MUSIC)
        AppStore.navigation(NavigationState(active = true, road = "A new instruction"))
        assertEquals(DisplayPage.MUSIC, AppStore.state.value.page)
        AppStore.navigation(NavigationState())
        assertEquals(DisplayPage.DRIVING, AppStore.state.value.page)
    }
}
