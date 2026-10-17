package com.carcompanion.app.navigation

import com.carcompanion.app.model.Maneuver
import com.carcompanion.app.model.NavigationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Implement this for an authorized navigation SDK that owns its route. No private Maps/Auto API. */
interface NavigationProvider {
    val updates: StateFlow<NavigationState>
}

/** In-process injection seam for an app-owned SDK integration. Not an exported IPC endpoint. */
class StructuredNavigationProvider : NavigationProvider {
    private val current = MutableStateFlow(NavigationState())
    override val updates: StateFlow<NavigationState> = current.asStateFlow()
    fun submit(state: NavigationState) {
        require(state.distanceText.length <= 24 && state.road.length <= 90 && state.etaText.length <= 32)
        require(state.updatedAtElapsedMs >= 0)
        current.value = state
    }
    fun endRoute() { current.value = NavigationState() }
}

class MockNavigationProvider : NavigationProvider {
    private val current = MutableStateFlow(NavigationState())
    override val updates: StateFlow<NavigationState> = current.asStateFlow()
    fun advance(nowElapsedMs: Long, step: Int) {
        current.value = when (step % 4) {
            0 -> NavigationState(true, Maneuver.RIGHT, "0.3 mi", "Coast Road", "12 min", nowElapsedMs, "DEMO", true)
            1 -> NavigationState(true, Maneuver.LEFT, "500 ft", "Ocean Avenue", "10 min", nowElapsedMs, "DEMO", true)
            2 -> NavigationState(true, Maneuver.STRAIGHT, "1.2 mi", "Scenic Drive", "8 min", nowElapsedMs, "DEMO", true)
            else -> NavigationState(source = "DEMO complete")
        }
    }
}
