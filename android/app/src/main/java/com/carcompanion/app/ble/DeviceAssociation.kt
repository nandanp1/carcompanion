@file:Suppress("DEPRECATION")
package com.carcompanion.app.ble

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.*
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import com.carcompanion.app.model.AppStore
import com.carcompanion.app.service.AutoReconnectSettings
import com.carcompanion.app.service.CompanionService

/** The system chooser performs discovery; this app never scans continually. */
class DeviceAssociation(private val activity: Activity, private val launch: (IntentSender) -> Unit, private val selected: (String) -> Unit) {
    private val manager = activity.getSystemService(CompanionDeviceManager::class.java)
    private var delivered: String? = null

    fun associate() {
        delivered = null
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            AppStore.log("System companion-device association is unavailable on this phone")
            return
        }
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE)).build()).build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        try {
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) = launch(chooserLauncher)
                override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
                override fun onAssociationCreated(info: AssociationInfo) {
                    if (Build.VERSION.SDK_INT >= 33) info.deviceMacAddress?.toString()?.let { accept(it, info.id) }
                }
                override fun onFailure(error: CharSequence?) { AppStore.log("Association: ${error ?: "No display found"}") }
            }, Handler(Looper.getMainLooper()))
        } catch (error: Exception) { AppStore.log("Association unavailable: ${error.message}") }
    }

    fun onResult(data: Intent?) {
        if (data == null) return
        if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)?.let {
                it.deviceMacAddress?.toString()?.let { address -> accept(address, it.id) }
                return
            }
        }
        when (val device = data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is ScanResult -> accept(device.device.address)
            is BluetoothDevice -> accept(device.address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun accept(rawAddress: String, associationId: Int? = null) {
        val address = runCatching { BluetoothAddress.canonicalize(rawAddress) }.getOrElse {
            AppStore.log("Association returned an invalid Bluetooth address")
            return
        }
        val prefs = activity.getSharedPreferences("car_companion", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("device_address", address)
            if (associationId != null) putInt(DevicePresenceObserver.ASSOCIATION_ID_KEY, associationId)
            else if (!PresenceSelection.sameAddress(prefs.getString("device_address", null), address))
                remove(DevicePresenceObserver.ASSOCIATION_ID_KEY)
        }.apply()
        DevicePresenceObserver.refresh(activity)
        if (delivered == address) return
        delivered = address
        selected(address)
    }
}

/** Presence restores BLE only. GPS is started from a visible user action under Android rules. */
@RequiresApi(31)
class DisplayPresenceService : CompanionDeviceService() {
    override fun onDeviceAppeared(address: String) {
        if (DevicePresenceObserver.matches(this, address)) resume()
    }
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= 33 && DevicePresenceObserver.matches(this, associationInfo.id)) resume()
    }
    override fun onDeviceDisappeared(address: String) {
        if (DevicePresenceObserver.matches(this, address)) disappeared()
    }
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= 33 && DevicePresenceObserver.matches(this, associationInfo.id)) disappeared()
    }
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36 || !DevicePresenceObserver.matches(this, event.associationId)) return
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> resume()
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED -> disappeared()
        }
    }
    private fun resume() {
        // Respect a deliberate Stop action; association itself is not permission to restart.
        if (AutoReconnectSettings.isArmed(this)) AutoReconnectSettings.selectedAddress(this)?.let { CompanionService.resumeBle(this, it) }
    }
    private fun disappeared() {
        // Scan presence and GATT connection are distinct; the live connection owns disconnects.
        AppStore.log("Selected display left companion presence; automatic reconnection remains enabled")
    }
}
