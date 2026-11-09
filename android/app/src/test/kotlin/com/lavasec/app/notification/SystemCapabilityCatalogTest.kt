package com.lavasec.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCapabilityCatalogTest {
    @Test
    fun `iOS-only capabilities are unavailable with no fabricated Android equivalent`() {
        val iosOnly = SystemCapability.entries.filter { it.iosOnly }
        assertEquals(
            setOf(
                SystemCapability.DNS_PATCH,
                SystemCapability.FOCUS_FILTER,
                SystemCapability.APP_INTENT,
                SystemCapability.ALTERNATE_ICON,
                SystemCapability.AUTO_SWITCH,
            ),
            iosOnly.toSet(),
        )
        for (capability in iosOnly) {
            assertEquals(CapabilityDisposition.UNAVAILABLE, capability.disposition)
            assertNull(capability.androidEquivalent)
            assertFalse(SystemCapabilityPolicy.isAvailable(capability, debugBuild = false))
        }
    }

    @Test
    fun `the Live Activity equivalent is advertised on Android`() {
        assertEquals(CapabilityDisposition.EQUIVALENT, SystemCapability.LIVE_ACTIVITY.disposition)
        assertFalse(SystemCapability.LIVE_ACTIVITY.iosOnly)
        assertTrue(SystemCapability.LIVE_ACTIVITY.androidEquivalent!!.contains("notification"))
        assertTrue(SystemCapabilityPolicy.isAvailable(SystemCapability.LIVE_ACTIVITY, debugBuild = false))
    }

    @Test
    fun `deferred surfaces stay unavailable and debug-only surfaces follow the build`() {
        assertFalse(SystemCapabilityPolicy.isAvailable(SystemCapability.WIDGET, debugBuild = false))
        assertEquals(CapabilityDisposition.DEFERRED, SystemCapability.WIDGET.disposition)
        assertEquals(CapabilityDisposition.DEFERRED, SystemCapability.SHORTCUT.disposition)
        assertFalse(SystemCapabilityPolicy.isAvailable(SystemCapability.SHORTCUT, debugBuild = false))
        assertFalse(SystemCapabilityPolicy.isAvailable(SystemCapability.DEVICE_QA, debugBuild = false))
        assertTrue(SystemCapabilityPolicy.isAvailable(SystemCapability.DEVICE_QA, debugBuild = true))
        assertFalse(SystemCapabilityPolicy.isAvailable(SystemCapability.DESIGN_GALLERY, debugBuild = false))
        assertTrue(SystemCapabilityPolicy.isAvailable(SystemCapability.DESIGN_GALLERY, debugBuild = true))
    }

    @Test
    fun `the route gate refuses unavailable and deferred routes and gates QA on the build`() {
        assertTrue(SystemCapabilityPolicy.routeAllowed("Guard", debugBuild = false))
        assertFalse(SystemCapabilityPolicy.routeAllowed("DNSPatch", debugBuild = false))
        assertFalse(SystemCapabilityPolicy.routeAllowed("AutoSwitch", debugBuild = false))
        assertFalse(SystemCapabilityPolicy.routeAllowed("DeviceQA", debugBuild = false))
        assertFalse(SystemCapabilityPolicy.routeAllowed("phoneQA", debugBuild = false))
        assertTrue(SystemCapabilityPolicy.routeAllowed("DeviceQA", debugBuild = true))
        assertTrue(SystemCapabilityPolicy.routeAllowed("Components", debugBuild = true))
        assertEquals(SystemCapability.DNS_PATCH, SystemCapabilityPolicy.capabilityForRoute("DNSPatch"))
        assertNull(SystemCapabilityPolicy.capabilityForRoute("Guard"))
    }

    @Test
    fun `the advertised audit excludes QA and unusable capabilities`() {
        val advertised = SystemCapabilityPolicy.advertisedCapabilities(debugBuild = false)
        assertTrue(advertised.contains(SystemCapability.NOTIFICATION_ACTIONS))
        assertTrue(advertised.contains(SystemCapability.DEEP_LINKS))
        assertFalse(advertised.contains(SystemCapability.DEVICE_QA))
        assertFalse(advertised.contains(SystemCapability.DNS_PATCH))
        assertTrue(
            SystemCapabilityPolicy.advertisedCapabilities(debugBuild = true)
                .contains(SystemCapability.DEVICE_QA),
        )
    }
}
