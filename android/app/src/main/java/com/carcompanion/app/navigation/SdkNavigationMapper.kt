package com.carcompanion.app.navigation

import com.carcompanion.app.model.Maneuver
import com.carcompanion.app.model.NavigationState
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as GoogleManeuver
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

object SdkNavigationMapper {
    fun map(info: NavInfo, now: Long): NavigationState = when (info.navState) {
        NavState.ENROUTE -> {
            val step = info.currentStep
            val turn = maneuver(step?.maneuver ?: GoogleManeuver.UNKNOWN)
            NavigationState(true, turn, distance(info.distanceToCurrentStepMeters),
                step?.fullRoadName.orEmpty().take(90), duration(info.timeToFinalDestinationSeconds), now,
                "Google Navigation SDK", turn != Maneuver.UNKNOWN)
        }
        NavState.REROUTING -> NavigationState(active = true, road = "Rerouting…",
            updatedAtElapsedMs = now, source = "Google Navigation SDK")
        else -> NavigationState(source = "Google Navigation SDK")
    }

    fun distance(meters: Int?): String {
        if (meters == null || meters < 0) return ""
        val feet = meters * 3.28084
        return if (feet < 1_000) "${(feet / 10).roundToInt() * 10} ft"
        else String.format(Locale.US, "%.1f mi", meters / 1609.344)
    }
    fun duration(seconds: Int?): String {
        if (seconds == null || seconds < 0) return ""
        val minutes = ceil(seconds / 60.0).toInt()
        return if (minutes < 60) "$minutes min" else "${minutes / 60} hr ${minutes % 60} min"
    }
    fun maneuver(value: Int): Maneuver = when (value) {
        GoogleManeuver.STRAIGHT, GoogleManeuver.NAME_CHANGE -> Maneuver.STRAIGHT
        GoogleManeuver.TURN_LEFT, GoogleManeuver.TURN_SHARP_LEFT,
        GoogleManeuver.ON_RAMP_LEFT, GoogleManeuver.ON_RAMP_SHARP_LEFT,
        GoogleManeuver.OFF_RAMP_LEFT, GoogleManeuver.OFF_RAMP_SHARP_LEFT -> Maneuver.LEFT
        GoogleManeuver.TURN_RIGHT, GoogleManeuver.TURN_SHARP_RIGHT,
        GoogleManeuver.ON_RAMP_RIGHT, GoogleManeuver.ON_RAMP_SHARP_RIGHT,
        GoogleManeuver.OFF_RAMP_RIGHT, GoogleManeuver.OFF_RAMP_SHARP_RIGHT -> Maneuver.RIGHT
        GoogleManeuver.TURN_SLIGHT_LEFT, GoogleManeuver.TURN_KEEP_LEFT, GoogleManeuver.FORK_LEFT,
        GoogleManeuver.MERGE_LEFT, GoogleManeuver.ON_RAMP_KEEP_LEFT, GoogleManeuver.ON_RAMP_SLIGHT_LEFT,
        GoogleManeuver.OFF_RAMP_KEEP_LEFT, GoogleManeuver.OFF_RAMP_SLIGHT_LEFT -> Maneuver.SLIGHT_LEFT
        GoogleManeuver.TURN_SLIGHT_RIGHT, GoogleManeuver.TURN_KEEP_RIGHT, GoogleManeuver.FORK_RIGHT,
        GoogleManeuver.MERGE_RIGHT, GoogleManeuver.ON_RAMP_KEEP_RIGHT, GoogleManeuver.ON_RAMP_SLIGHT_RIGHT,
        GoogleManeuver.OFF_RAMP_KEEP_RIGHT, GoogleManeuver.OFF_RAMP_SLIGHT_RIGHT -> Maneuver.SLIGHT_RIGHT
        GoogleManeuver.TURN_U_TURN_CLOCKWISE, GoogleManeuver.TURN_U_TURN_COUNTERCLOCKWISE,
        GoogleManeuver.ON_RAMP_U_TURN_CLOCKWISE, GoogleManeuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE,
        GoogleManeuver.OFF_RAMP_U_TURN_CLOCKWISE, GoogleManeuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE -> Maneuver.U_TURN
        in GoogleManeuver.ROUNDABOUT_CLOCKWISE..GoogleManeuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE -> Maneuver.ROUNDABOUT
        GoogleManeuver.DESTINATION, GoogleManeuver.DESTINATION_LEFT, GoogleManeuver.DESTINATION_RIGHT -> Maneuver.ARRIVE
        else -> Maneuver.UNKNOWN
    }
}
