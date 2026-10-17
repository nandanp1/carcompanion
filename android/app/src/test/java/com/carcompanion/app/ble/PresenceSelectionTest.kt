package com.carcompanion.app.ble

import org.junit.Assert.*
import org.junit.Test

class PresenceSelectionTest {
    private val selected = "AA:BB:CC:01:02:03"
    private val other = "AA:BB:CC:04:05:06"

    @Test fun existingSavedAddressMigratesToSystemAssociationId() {
        val records = listOf(PresenceAssociation(11, other), PresenceAssociation(12, selected.lowercase()))
        assertEquals(12, PresenceSelection.resolve(records, selected, null)?.id)
    }

    @Test fun staleIdCannotRedirectWakeToAnotherDisplay() {
        val records = listOf(PresenceAssociation(11, other), PresenceAssociation(12, selected))
        assertEquals(12, PresenceSelection.resolve(records, selected, 11)?.id)
        assertNull(PresenceSelection.resolve(listOf(records.first()), selected, 11))
    }

    @Test fun duplicateAddressAssociationsPreferSavedId() {
        val records = listOf(PresenceAssociation(11, selected), PresenceAssociation(12, selected))
        assertEquals(12, PresenceSelection.resolve(records, selected, 12)?.id)
    }

    @Test fun legacyAddressObservationMayReportEitherIdForSameDeviceButNeverAnotherDevice() {
        val records = listOf(PresenceAssociation(11, selected), PresenceAssociation(12, selected), PresenceAssociation(13, other))
        assertTrue(PresenceSelection.matchesAssociation(records, selected, 11))
        assertTrue(PresenceSelection.matchesAssociation(records, selected, 12))
        assertFalse(PresenceSelection.matchesAssociation(records, selected, 13))
        assertFalse(PresenceSelection.matchesAssociation(records, selected, 99))
        assertFalse(PresenceSelection.matchesAssociation(records, selected, -1))
    }

    @Test fun removedAssociationFallsBackOnlyToSameDisplay() {
        val records = listOf(PresenceAssociation(11, other), PresenceAssociation(12, selected))
        assertEquals(12, PresenceSelection.resolve(records, selected, 99)?.id)
        assertNull(PresenceSelection.resolve(records, "AA:BB:CC:07:08:09", 99))
    }

    @Test fun legacyAddressMatchingIsCanonicalAndRejectsMissingInvalidOrDifferentDevices() {
        assertTrue(PresenceSelection.sameAddress(selected, selected.lowercase()))
        for (address in listOf(null, "", "invalid", other)) assertFalse(PresenceSelection.sameAddress(selected, address))
        assertFalse(PresenceSelection.sameAddress(null, selected))
        assertEquals(PresenceAssociation(null, selected), PresenceSelection.resolve(listOf(PresenceAssociation(null, selected)), selected, -1))
    }
}
