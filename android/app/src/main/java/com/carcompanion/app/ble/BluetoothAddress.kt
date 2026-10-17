package com.carcompanion.app.ble

import java.util.Locale

/** CDM's MacAddress string is lowercase; BluetoothAdapter requires uppercase hex. */
object BluetoothAddress {
    private val format = Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")

    fun canonicalize(address: String): String {
        require(format.matches(address)) { "Invalid Bluetooth address" }
        return address.uppercase(Locale.ROOT)
    }
}
