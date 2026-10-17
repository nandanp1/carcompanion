package com.carcompanion.app.navigation

import com.carcompanion.app.model.NavigationState

/** Retains only supported route candidates; notification presence never refreshes old instructions. */
class NavigationNotificationTracker {
    private data class Candidate(val notification: NavigationNotification, val state: NavigationState, val diagnostic: String)
    private val candidates = linkedMapOf<String, Candidate>()
    private var selectedKey: String? = null

    fun posted(notification: NavigationNotification, nowElapsedMs: Long): NavigationParseResult {
        val parsed = MapsNotificationParser.parse(notification, nowElapsedMs)
        if (parsed.state == null) candidates.remove(notification.key)
        else {
            candidates[notification.key] = Candidate(notification,
                parsed.state.copy(routeConfirmedAtElapsedMs = nowElapsedMs), parsed.diagnostic)
            selectedKey = notification.key
            boundCandidates()
        }
        select()
        return parsed
    }

    /** A successful complete snapshot also detects removals missed while the listener was reconnecting. */
    fun reconcile(notifications: List<NavigationNotification>, nowElapsedMs: Long) {
        val refreshed = linkedMapOf<String, Candidate>()
        for (notification in notifications) {
            val parsed = MapsNotificationParser.parse(notification, nowElapsedMs)
            val state = parsed.state ?: continue
            val previous = candidates[notification.key]
            val instructionTime = if (previous?.notification == notification) previous.state.updatedAtElapsedMs else nowElapsedMs
            refreshed[notification.key] = Candidate(notification, state.copy(
                updatedAtElapsedMs = instructionTime, routeConfirmedAtElapsedMs = nowElapsedMs,
            ), parsed.diagnostic)
        }
        candidates.clear()
        candidates.putAll(refreshed)
        boundCandidates()
        select()
    }

    fun removed(key: String) { candidates.remove(key); select() }
    fun clear() { candidates.clear(); selectedKey = null }
    fun state(nowElapsedMs: Long): NavigationState = candidates[selectedKey]?.state?.fresh(nowElapsedMs) ?: NavigationState()
    fun diagnostic(nowElapsedMs: Long): String {
        val state = state(nowElapsedMs)
        return when {
            !state.active -> "No confirmed active Maps navigation notification"
            nowElapsedMs - state.updatedAtElapsedMs > 30_000L -> "Route notification remains present; stale instruction fields are unavailable"
            else -> candidates[selectedKey]?.diagnostic ?: "Navigation unavailable"
        }
    }

    private fun select() {
        if (selectedKey !in candidates) selectedKey = candidates.keys.firstOrNull()
    }
    private fun boundCandidates() {
        while (candidates.size > 8) candidates.remove(candidates.keys.first { it != selectedKey })
    }
}
