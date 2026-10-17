package com.carcompanion.app.navigation

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.carcompanion.app.model.AppStore
import com.carcompanion.app.model.NavigationState
import com.carcompanion.app.model.NotificationDiagnostic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MapsNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracker = NavigationNotificationTracker()
    private val pendingRemoval = NavigationRemovalDebouncer(scope) { refreshActiveNotifications() }
    private var refreshJob: Job? = null
    private var lastSnapshotFailure: String? = null

    override fun onListenerConnected() {
        connected.value = true
        AppStore.diagnostic { it.copy(notificationAccess = true) }
        pendingRemoval.cancel()
        tracker.clear()
        refreshActiveNotifications(captureDiagnostics = true)
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                delay(5_000)
                if (AppStore.diagnostics.value.serviceRunning) refreshActiveNotifications()
            }
        }
    }

    override fun onListenerDisconnected() {
        refreshJob?.cancel()
        pendingRemoval.cancel()
        tracker.clear()
        connected.value = false
        current.value = NavigationState()
        AppStore.diagnostic { it.copy(notificationAccess = false, navigationStatus = "Notification listener disconnected") }
    }

    override fun onDestroy() {
        onListenerDisconnected()
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!connected.value) return
        val snapshot = snapshot(sbn) ?: return
        val result = tracker.posted(snapshot, SystemClock.elapsedRealtime())
        if (result.state != null) pendingRemoval.cancel()
        capture(sbn, snapshot, result.diagnostic)
        publish()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!connected.value || sbn.packageName != MapsNotificationParser.MAPS_PACKAGE) return
        // Keep the prior confirmation until an authoritative snapshot succeeds. Maps may post a
        // replacement next; a failed Binder call must not turn that gap into a false route end.
        pendingRemoval.removed()
    }

    private fun refreshActiveNotifications(captureDiagnostics: Boolean = false) {
        if (!connected.value || pendingRemoval.isPending) return
        try {
            // Android permits this only between onListenerConnected and onListenerDisconnected.
            val notifications = activeNotifications ?: error("Notification snapshot unavailable")
            val snapshots = notifications.mapNotNull { sbn ->
                snapshot(sbn)?.also { snapshot ->
                    if (captureDiagnostics) capture(sbn, snapshot,
                        MapsNotificationParser.parse(snapshot, SystemClock.elapsedRealtime()).diagnostic)
                }
            }
            tracker.reconcile(snapshots, SystemClock.elapsedRealtime())
            lastSnapshotFailure = null
            publish()
        } catch (failure: Exception) {
            val kind = failure.javaClass.simpleName
            if (kind != lastSnapshotFailure) AppStore.log("Maps notification snapshot unavailable: $kind")
            lastSnapshotFailure = kind
            // Do not invent an end event for a transient Binder failure. The last confirmation expires.
            current.value = tracker.state(SystemClock.elapsedRealtime())
            AppStore.diagnostic { it.copy(navigationStatus = "Maps presence check unavailable; waiting for notification access") }
        }
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        current.value = tracker.state(now)
        AppStore.diagnostic { it.copy(navigationStatus = tracker.diagnostic(now)) }
    }

    private fun snapshot(sbn: StatusBarNotification): NavigationNotification? {
        if (sbn.packageName != MapsNotificationParser.MAPS_PACKAGE) return null
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        fun field(key: String) = runCatching { n.extras.getCharSequence(key)?.toString()?.take(512).orEmpty() }.getOrDefault("")
        return NavigationNotification(sbn.packageName, sbn.key, sbn.isOngoing, n.category,
            field(Notification.EXTRA_TITLE), field(Notification.EXTRA_TEXT),
            field(Notification.EXTRA_BIG_TEXT), field(Notification.EXTRA_SUB_TEXT))
    }

    private fun capture(sbn: StatusBarNotification, snapshot: NavigationNotification, diagnostic: String) {
        AppStore.notification(NotificationDiagnostic(System.currentTimeMillis(), snapshot.category.orEmpty(),
            snapshot.title, snapshot.text, sbn.notification.extras.keySet().sorted().take(40).map { it.take(96) },
            diagnostic, bigText = snapshot.bigText, subText = snapshot.subText))
    }

    companion object : NavigationProvider {
        private val current = MutableStateFlow(NavigationState())
        override val updates: StateFlow<NavigationState> = current.asStateFlow()
        private val connected = MutableStateFlow(false)
        val listenerConnected: StateFlow<Boolean> = connected.asStateFlow()
    }
}
