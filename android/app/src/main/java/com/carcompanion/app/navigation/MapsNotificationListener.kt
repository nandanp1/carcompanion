package com.carcompanion.app.navigation

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.carcompanion.app.model.AppStore
import com.carcompanion.app.model.NavigationState
import com.carcompanion.app.model.NotificationDiagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MapsNotificationListener : NotificationListenerService() {
    private var routeKey: String? = null

    override fun onListenerConnected() {
        connected.value = true
        AppStore.diagnostic { it.copy(notificationAccess = true) }
        activeNotifications?.filter { it.packageName == MapsNotificationParser.MAPS_PACKAGE }?.forEach(::inspect)
    }

    override fun onListenerDisconnected() {
        connected.value = false
        current.value = NavigationState()
        AppStore.diagnostic { it.copy(notificationAccess = false, navigationStatus = "Notification listener disconnected") }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = inspect(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == routeKey) {
            routeKey = null
            current.value = NavigationState()
            AppStore.diagnostic { it.copy(navigationStatus = "Navigation notification removed") }
        }
    }

    private fun inspect(sbn: StatusBarNotification) {
        if (sbn.packageName != MapsNotificationParser.MAPS_PACKAGE) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val e = n.extras
        fun field(key: String) = e.getCharSequence(key)?.toString()?.take(512).orEmpty()
        val snapshot = NavigationNotification(
            sbn.packageName, sbn.key, sbn.isOngoing, n.category,
            field(Notification.EXTRA_TITLE), field(Notification.EXTRA_TEXT),
            field(Notification.EXTRA_BIG_TEXT), field(Notification.EXTRA_SUB_TEXT),
        )
        val result = MapsNotificationParser.parse(snapshot, SystemClock.elapsedRealtime())
        AppStore.notification(NotificationDiagnostic(
            System.currentTimeMillis(), n.category.orEmpty(), snapshot.title, snapshot.text,
            e.keySet().sorted().take(40), result.diagnostic,
            bigText = snapshot.bigText.take(512), subText = snapshot.subText.take(512),
        ))
        AppStore.diagnostic { it.copy(navigationStatus = result.diagnostic) }
        if (result.state != null) {
            routeKey = sbn.key
            current.value = result.state
        } else if (sbn.key == routeKey) {
            // An updated former route notification no longer meets our active-route criteria.
            routeKey = null
            current.value = NavigationState()
        }
    }

    companion object : NavigationProvider {
        private val current = MutableStateFlow(NavigationState())
        override val updates: StateFlow<NavigationState> = current.asStateFlow()
        private val connected = MutableStateFlow(false)
        val listenerConnected: StateFlow<Boolean> = connected.asStateFlow()
    }
}
