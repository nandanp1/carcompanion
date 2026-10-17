package com.carcompanion.app.service

import org.junit.Assert.*
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun upgradeMigratesOnlyPreviouslyStartedUsersToAutomaticReconnect() {
        assertTrue(ReconnectPolicy.enabled(null, serviceEnabled = true))
        assertFalse(ReconnectPolicy.enabled(null, serviceEnabled = false))
        assertFalse(ReconnectPolicy.enabled(false, serviceEnabled = true))
        assertTrue(ReconnectPolicy.enabled(true, serviceEnabled = false))
    }

    @Test fun stoppedServiceCannotResumeEvenWhenAutomaticSettingRemainsEnabled() {
        assertFalse(ReconnectPolicy.canResume(true, false, "AA:BB:CC:01:02:03"))
        assertFalse(ReconnectPolicy.allowStart(ReconnectPolicy.Start.PRESENCE, false, false))
        assertFalse(ReconnectPolicy.allowStart(ReconnectPolicy.Start.RESTART, false, false))
        assertTrue(ReconnectPolicy.allowStart(ReconnectPolicy.Start.USER, false, false))
    }

    @Test fun recoveryRequiresEnabledServiceAndSelectedDevice() {
        assertTrue(ReconnectPolicy.canResume(true, true, "AA:BB:CC:01:02:03"))
        assertFalse(ReconnectPolicy.canResume(false, true, "AA:BB:CC:01:02:03"))
        assertFalse(ReconnectPolicy.canResume(true, true, null))
        for (source in listOf(ReconnectPolicy.Start.PRESENCE, ReconnectPolicy.Start.RESTART)) {
            assertTrue(ReconnectPolicy.allowStart(source, true, true))
            assertTrue(ReconnectPolicy.connectOnStart(source, true))
            assertFalse(ReconnectPolicy.connectOnStart(source, false))
        }
    }

    @Test fun coldPresenceAndStickyRestartCannotStartLocation() {
        for (source in listOf(ReconnectPolicy.Start.PRESENCE, ReconnectPolicy.Start.RESTART, ReconnectPolicy.Start.SETTINGS)) {
            assertFalse(ReconnectPolicy.useLocation(source, alreadyEnabled = false))
            assertTrue(ReconnectPolicy.useLocation(source, alreadyEnabled = true))
        }
        assertTrue(ReconnectPolicy.useLocation(ReconnectPolicy.Start.USER, alreadyEnabled = false))
    }

    @Test fun settingsCannotWakeDisconnectedDisplayOrRestartStoppedService() {
        assertFalse(ReconnectPolicy.allowStart(ReconnectPolicy.Start.SETTINGS, false, false))
        assertTrue(ReconnectPolicy.allowStart(ReconnectPolicy.Start.SETTINGS, false, true))
        assertFalse(ReconnectPolicy.connectOnStart(ReconnectPolicy.Start.SETTINGS, false))
        assertFalse(ReconnectPolicy.connectOnStart(ReconnectPolicy.Start.SETTINGS, true))
        assertTrue(ReconnectPolicy.connectOnStart(ReconnectPolicy.Start.USER, false))
    }

    @Test fun queuedPresenceAfterDisablingAutoReconnectPreservesExistingManualConnection() {
        assertEquals(ReconnectPolicy.Admission.IGNORE, ReconnectPolicy.admit(
            ReconnectPolicy.Start.PRESENCE, armed = false, serviceEnabled = true, initialized = true))
        assertEquals(ReconnectPolicy.Admission.STOP, ReconnectPolicy.admit(
            ReconnectPolicy.Start.PRESENCE, armed = false, serviceEnabled = false, initialized = false))
        assertEquals(ReconnectPolicy.Admission.START, ReconnectPolicy.admit(
            ReconnectPolicy.Start.USER, armed = false, serviceEnabled = false, initialized = false))
    }
}
