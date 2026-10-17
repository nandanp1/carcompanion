package com.carcompanion.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale

class BluetoothAddressTest {
    @Test fun associationMacAddressBecomesAndroidCompatible() {
        assertEquals("AA:BB:CC:01:02:03", BluetoothAddress.canonicalize("aa:bb:cc:01:02:03"))
    }

    @Test fun savedLegacyAddressAndNewAssociationHaveSameIdentity() {
        val restored = BluetoothAddress.canonicalize("aa:bb:cc:01:02:03")
        val associated = BluetoothAddress.canonicalize("AA:BB:CC:01:02:03")
        assertEquals(restored, associated)
        assertEquals(restored, BluetoothAddress.canonicalize(restored))
    }

    @Test fun mixedCaseAndLeadingZeroOctetsArePreserved() {
        assertEquals("00:0A:AB:CD:EF:01", BluetoothAddress.canonicalize("00:0a:aB:Cd:eF:01"))
    }

    @Test fun deviceLocaleDoesNotChangeCanonicalAddress() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddress.canonicalize("aa:bb:cc:dd:ee:ff"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun malformedAddressesRemainRejected() {
        listOf("", "AA:BB:CC:01:02", "AA:BB:CC:01:02:03:04", "AA-BB-CC-01-02-03",
            "AABBCC010203", "AA:BB:CC:01:02:0G", "AA:BB:CC:01:02:3", "AA:BB:CC:01:02:0003",
            " AA:BB:CC:01:02:03", "AA:BB:CC:01:02:03\n", "ＡA:BB:CC:01:02:03",
            "AA:BB:CC:01:02:ß").forEach { invalid ->
            assertThrows("Must reject '$invalid'", IllegalArgumentException::class.java) {
                BluetoothAddress.canonicalize(invalid)
            }
        }
    }
}
