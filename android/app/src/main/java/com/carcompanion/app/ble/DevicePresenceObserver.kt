@file:Suppress("DEPRECATION")
package com.carcompanion.app.ble

import android.annotation.SuppressLint
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.carcompanion.app.model.AppStore
import com.carcompanion.app.service.AutoReconnectSettings

/** Keeps system presence observation aligned with the one display selected by the user. */
object DevicePresenceObserver {
    const val ASSOCIATION_ID_KEY = "device_association_id"
    private const val PREFS = "car_companion"

    @SuppressLint("MissingPermission")
    fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT < 31 || !supported(context)) return
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            val associations = associations(manager)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val selected = PresenceSelection.resolve(associations, AutoReconnectSettings.selectedAddress(context),
                prefs.getInt(ASSOCIATION_ID_KEY, -1))
            val armed = AutoReconnectSettings.isArmed(context)
            prefs.edit().apply {
                if (selected?.id != null) putInt(ASSOCIATION_ID_KEY, selected.id)
                else remove(ASSOCIATION_ID_KEY)
            }.apply()
            // Associations belong to this app. Old selections must not keep waking its process.
            associations.filter {
                !armed || if (Build.VERSION.SDK_INT >= 36) it != selected
                else !PresenceSelection.sameAddress(it.address, selected?.address)
            }.distinctBy { if (Build.VERSION.SDK_INT >= 36) it.id else it.address.uppercase() }.forEach { association ->
                try { observe(manager, association, enabled = false) }
                catch (failure: Exception) { AppStore.log("Presence cleanup unavailable: ${failure.javaClass.simpleName}") }
            }
            if (!armed) return
            if (selected == null) {
                AppStore.log("Associate the selected display to enable presence reconnection")
                return
            }
            observe(manager, selected, enabled = true)
            AppStore.log("Automatic display presence observation enabled")
        } catch (failure: Exception) {
            AppStore.log("Presence observation unavailable: ${failure.javaClass.simpleName}")
        }
    }

    fun matches(context: Context, associationId: Int): Boolean {
        if (Build.VERSION.SDK_INT < 33 || associationId < 0 || !supported(context) || !AutoReconnectSettings.isArmed(context)) return false
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            PresenceSelection.matchesAssociation(associations(manager), AutoReconnectSettings.selectedAddress(context), associationId)
        } catch (failure: Exception) {
            AppStore.log("Presence identity unavailable: ${failure.javaClass.simpleName}")
            false
        }
    }

    fun matches(context: Context, address: String): Boolean = AutoReconnectSettings.isArmed(context) &&
        PresenceSelection.sameAddress(AutoReconnectSettings.selectedAddress(context), address)

    private fun supported(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    @RequiresApi(31)
    private fun associations(manager: CompanionDeviceManager): List<PresenceAssociation> =
        if (Build.VERSION.SDK_INT >= 33) manager.myAssociations.mapNotNull { info ->
            info.deviceMacAddress?.toString()?.let { PresenceAssociation(info.id, it) }
        } else manager.associations.map { PresenceAssociation(null, it) }

    @RequiresApi(31)
    @SuppressLint("MissingPermission")
    private fun observe(manager: CompanionDeviceManager, association: PresenceAssociation, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 36) {
            val request = ObservingDevicePresenceRequest.Builder().setAssociationId(requireNotNull(association.id)).build()
            if (enabled) manager.startObservingDevicePresence(request) else manager.stopObservingDevicePresence(request)
        } else {
            val address = BluetoothAddress.canonicalize(association.address)
            if (enabled) manager.startObservingDevicePresence(address) else manager.stopObservingDevicePresence(address)
        }
    }
}

internal data class PresenceAssociation(val id: Int?, val address: String)

/** Pure identity policy: a stale association ID must never select a different saved display. */
internal object PresenceSelection {
    fun sameAddress(first: String?, second: String?): Boolean {
        if (first == null || second == null) return false
        return runCatching { BluetoothAddress.canonicalize(first) == BluetoothAddress.canonicalize(second) }.getOrDefault(false)
    }

    fun resolve(associations: List<PresenceAssociation>, selectedAddress: String?, preferredId: Int?): PresenceAssociation? {
        val matching = associations.filter { sameAddress(selectedAddress, it.address) }
        return matching.firstOrNull { preferredId != null && preferredId >= 0 && it.id == preferredId } ?: matching.firstOrNull()
    }

    fun matchesAssociation(associations: List<PresenceAssociation>, selectedAddress: String?, eventId: Int): Boolean =
        eventId >= 0 && associations.any { it.id == eventId && sameAddress(selectedAddress, it.address) }
}
