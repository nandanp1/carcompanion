package com.carcompanion.app.navigation

import com.carcompanion.app.model.Maneuver
import com.carcompanion.app.model.NavigationState

/** Public notification text only. Fixtures are synthetic supported formats, not a Maps API contract. */
data class NavigationNotification(
    val packageName: String,
    val key: String,
    val ongoing: Boolean,
    val category: String?,
    val title: String,
    val text: String,
    val bigText: String = "",
    val subText: String = "",
)

data class NavigationParseResult(val state: NavigationState?, val diagnostic: String)

object MapsNotificationParser {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private val distance = Regex("(?<![\\p{L}\\d.])(?:\\d+(?:[.,]\\d+)?)\\s*(?:mi|mile(?:s)?|ft|feet|km|m|meters?|metres?)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val duration = Regex("(?:\\d+\\s*h(?:r|ours?)?\\s*)?\\d+\\s*min(?:utes?)?\\b", RegexOption.IGNORE_CASE)
    private val arrival = Regex("\\b\\d{1,2}:\\d{2}\\s*(?:AM|PM)\\b", RegexOption.IGNORE_CASE)
    private val instruction = Regex("^(?:In\\s+[^,]+,\\s*)?(Turn (?:slight(?:ly)? )?(?:left|right)|Keep (?:left|right)|Continue (?:straight|on)|Head (?:north|south|east|west)(?:east|west)?|Make a U.turn|At the roundabout|Enter the roundabout)(?:\\s+|,|$)", RegexOption.IGNORE_CASE)

    fun parse(input: NavigationNotification, nowElapsedMs: Long): NavigationParseResult {
        if (input.packageName != MAPS_PACKAGE) return NavigationParseResult(null, "Ignored package")
        val lines = listOf(input.title, input.text, input.bigText).flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }
        val explicit = lines.firstOrNull { instruction.containsMatchIn(it) }
        val distanceText = lines.firstNotNullOfOrNull { distance.find(it)?.value }.orEmpty()
        val categoryNavigation = input.category == "navigation"
        val evidence = input.ongoing && (categoryNavigation || (explicit != null && distanceText.isNotEmpty()))
        if (!evidence) return NavigationParseResult(null, "No reliable active-route evidence in public notification fields")
        val eta = listOf(input.subText, input.text, input.bigText).firstNotNullOfOrNull {
            duration.find(it)?.value ?: arrival.find(it)?.value
        }.orEmpty()
        if (explicit == null) return NavigationParseResult(
            NavigationState(active = true, distanceText = distanceText, etaText = eta,
                updatedAtElapsedMs = nowElapsedMs, source = "Google Maps notification", reliable = false),
            "Route category detected; turn/road unavailable in supported public text format",
        )
        val normalized = instruction.find(explicit)!!.groupValues[1].lowercase()
        val maneuver = when {
            "roundabout" in normalized -> Maneuver.ROUNDABOUT
            Regex("u.turn").containsMatchIn(normalized) -> Maneuver.U_TURN
            "slight left" in normalized || "slightly left" in normalized || "keep left" in normalized -> Maneuver.SLIGHT_LEFT
            "slight right" in normalized || "slightly right" in normalized || "keep right" in normalized -> Maneuver.SLIGHT_RIGHT
            "left" in normalized -> Maneuver.LEFT
            "right" in normalized -> Maneuver.RIGHT
            "continue" in normalized || "head" in normalized -> Maneuver.STRAIGHT
            else -> Maneuver.UNKNOWN
        }
        // Road names are extracted only from an explicit instruction suffix; never from a destination.
        val road = Regex("\\b(?:onto|on|toward)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(explicit)?.groupValues?.get(1)?.trim()?.take(90).orEmpty()
        return NavigationParseResult(
            NavigationState(true, maneuver, distanceText, road, eta, nowElapsedMs,
                "Google Maps notification (experimental English parser)", maneuver != Maneuver.UNKNOWN),
            "Recognized explicit English instruction; unavailable fields remain empty",
        )
    }
}
