package com.carcompanion.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.carcompanion.app.ble.DeviceAssociation
import com.carcompanion.app.model.AppStore
import com.carcompanion.app.navigation.MapsNotificationListener
import com.carcompanion.app.service.CompanionService
import com.carcompanion.app.ui.CarCompanionApp

class MainActivity : ComponentActivity() {
    private lateinit var association: DeviceAssociation
    private var afterPermissions: (() -> Unit)? = null
    private val pairing = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) association.onResult(result.data)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val continuation = afterPermissions
        afterPermissions = null
        if (hasBluetoothPermissions()) continuation?.invoke()
        else AppStore.log("Nearby devices permission is required to connect")
        refreshNotificationAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(8, 11, 13)))
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        association = DeviceAssociation(this, { pairing.launch(IntentSenderRequest.Builder(it).build()) }) {
            CompanionService.start(this, it)
        }
        refreshNotificationAccess()
        setContent {
            CarCompanionApp(
                onPair = { requestPermissionsThen { association.associate() } },
                onStart = { requestPermissionsThen { CompanionService.start(this) } },
                onStop = { CompanionService.stop(this) },
                onNotificationAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onPermissions = { requestPermissionsThen {} },
                onSettingsChanged = { CompanionService.updateSettings(this) },
                onDemo = { enabled -> if (enabled) requestPermissionsThen { CompanionService.demo(this, true) } else CompanionService.demo(this, false) },
            )
        }
    }
    override fun onResume() { super.onResume(); refreshNotificationAccess() }
    private fun refreshNotificationAccess() {
        val expected = ComponentName(this, MapsNotificationListener::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)?.contains(expected) == true
        AppStore.diagnostic { it.copy(notificationAccess = enabled) }
    }
    private fun hasBluetoothPermissions(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun requestPermissionsThen(action: () -> Unit) {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (required.isEmpty()) action()
        else { afterPermissions = action; permissions.launch(required.toTypedArray()) }
    }
}
