package com.carcompanion.app.navigation

import android.service.notification.NotificationListenerService
import com.carcompanion.app.model.AppStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Kept under its original component name to preserve existing Spotify access grants.
 * No notification content is inspected. Navigation comes exclusively from the SDK.
 */
class MapsNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected.value = true
        AppStore.diagnostic { it.copy(notificationAccess = true) }
    }
    override fun onListenerDisconnected() {
        connected.value = false
        AppStore.diagnostic { it.copy(notificationAccess = false) }
    }
    override fun onDestroy() { onListenerDisconnected(); super.onDestroy() }
    companion object {
        private val connected = MutableStateFlow(false)
        val listenerConnected = connected.asStateFlow()
    }
}
