package com.carcompanion.app.service

import android.content.Context
import com.carcompanion.app.ble.BluetoothAddress

object AutoReconnectSettings {
    private const val AUTO_RECONNECT = "auto_reconnect"
    private fun preferences(context: Context) = context.getSharedPreferences(CompanionService.PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean {
        val prefs = preferences(context)
        val saved = if (prefs.contains(AUTO_RECONNECT)) prefs.getBoolean(AUTO_RECONNECT, false) else null
        return ReconnectPolicy.enabled(saved, prefs.getBoolean("service_enabled", false))
    }

    fun isArmed(context: Context): Boolean = ReconnectPolicy.canResume(
        isEnabled(context), preferences(context).getBoolean("service_enabled", false), selectedAddress(context),
    )

    fun selectedAddress(context: Context): String? = preferences(context).getString("device_address", null)?.let {
        runCatching { BluetoothAddress.canonicalize(it) }.getOrNull()
    }

    internal fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(AUTO_RECONNECT, enabled).apply()
    }

    internal fun arm(context: Context) {
        val prefs = preferences(context)
        prefs.edit().apply {
            if (!prefs.contains(AUTO_RECONNECT)) putBoolean(AUTO_RECONNECT, true)
            putBoolean("service_enabled", true)
        }.apply()
    }

    internal fun disarm(context: Context) {
        preferences(context).edit().putBoolean("service_enabled", false).apply()
    }
}

/** Pure decisions shared by presence wakeups, sticky restarts, and visible user starts. */
internal object ReconnectPolicy {
    enum class Start { USER, PRESENCE, RESTART, SETTINGS }
    enum class Admission { START, IGNORE, STOP }
    fun enabled(saved: Boolean?, serviceEnabled: Boolean): Boolean = saved ?: serviceEnabled
    fun canResume(enabled: Boolean, serviceEnabled: Boolean, address: String?): Boolean =
        enabled && serviceEnabled && address != null
    fun allowStart(source: Start, armed: Boolean, serviceEnabled: Boolean): Boolean = when (source) {
        Start.USER -> true
        Start.SETTINGS -> serviceEnabled
        Start.PRESENCE, Start.RESTART -> armed
    }
    fun admit(source: Start, armed: Boolean, serviceEnabled: Boolean, initialized: Boolean): Admission = when {
        allowStart(source, armed, serviceEnabled) -> Admission.START
        initialized -> Admission.IGNORE
        else -> Admission.STOP
    }
    fun useLocation(source: Start, alreadyEnabled: Boolean): Boolean = alreadyEnabled || source == Start.USER
    fun connectOnStart(source: Start, armed: Boolean): Boolean = when (source) {
        Start.USER -> true
        Start.PRESENCE, Start.RESTART -> armed
        Start.SETTINGS -> false
    }
}
