package com.lavasec.app.foundation.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Android capability mapping: every retained key is classified, the
 * iOS-only capabilities are marked unavailable, and no caller can read one as
 * available through the map.
 */
class ProductPreferenceCapabilityTest {
    @Test
    fun `capability classification is exhaustive over the key set`() {
        val classified = AndroidCapabilityMap.classifiedKeys
        assertEquals(ProductPreferenceKey.entries.toSet(), classified)
        for (key in ProductPreferenceKey.entries) {
            assertEquals(key.capability, AndroidCapabilityMap.capabilityFor(key))
            assertEquals(key.capability, AndroidCapabilityMap.capabilityFor(key.wire))
        }
        // The mapping covers every key exactly once, with no key left unclassified:
        // the wire lookup succeeds for every canonical key.
        assertEquals(ProductPreferenceKey.entries.size, AndroidCapabilityMap.classifiedKeys.size)
        for (key in ProductPreferenceKey.entries) {
            assertTrue(AndroidCapabilityMap.capabilityFor(key.wire) != null)
        }
    }

    @Test
    fun `an unknown wire key has no capability rather than a default`() {
        assertNull(AndroidCapabilityMap.capabilityFor("dnsPatchSetup"))
        assertNull(AndroidCapabilityMap.capabilityFor("biometrics"))
        assertNull(AndroidCapabilityMap.capabilityFor(""))
    }

    @Test
    fun `ios-only platform capabilities are never presentable as working`() {
        val expectedUnavailable = setOf(
            ProductPreferenceKey.LIVE_ACTIVITIES,
            ProductPreferenceKey.LIVE_ACTIVITY_PAUSE_MINUTES,
            ProductPreferenceKey.MATCH_ICON,
        )

        assertEquals(
            expectedUnavailable,
            AndroidCapabilityMap.unavailablePerPlatform.toSet(),
        )
        for (key in expectedUnavailable) {
            assertEquals(AndroidCapability.UNAVAILABLE_PER_PLATFORM, key.capability)
            assertFalse(key.capability.isPresentableAsWorking)
        }
    }

    @Test
    fun `degraded and available capabilities are presentable but distinguished`() {
        assertTrue(AndroidCapability.AVAILABLE.isPresentableAsWorking)
        assertTrue(AndroidCapability.EQUIVALENT_DEGRADED.isPresentableAsWorking)
        assertFalse(AndroidCapability.EQUIVALENT_DEGRADED == AndroidCapability.AVAILABLE)

        // Android implements the intent, but with a reduced mechanism.
        assertEquals(
            AndroidCapability.EQUIVALENT_DEGRADED,
            ProductPreferenceKey.LOOK.capability,
        )
        assertEquals(
            AndroidCapability.EQUIVALENT_DEGRADED,
            ProductPreferenceKey.NOTIFICATIONS_FILTER_CHANGES.capability,
        )
        assertEquals(
            AndroidCapability.EQUIVALENT_DEGRADED,
            ProductPreferenceKey.TEXT_SIZE.capability,
        )
    }
}
