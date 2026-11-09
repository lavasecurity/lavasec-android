package com.lavasec.app.foundation.config

import com.lavasec.app.foundation.filter.FilterProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigurationTest {
    @Test
    fun `defaults use balanced filtering with Device DNS and Mullvad DoH fallback`() {
        val configuration = AppConfiguration.default()

        assertEquals(ProtectionLevel.BALANCED, configuration.protectionLevel)
        assertEquals("filter-balanced", configuration.activeFilterId)
        assertTrue(configuration.resolver.useDeviceDns)
        assertEquals("mullvad-doh", configuration.resolver.encryptedFallbackId)
        assertTrue(configuration.keepCounts)
        assertTrue(configuration.keepHistory)
        assertFalse(
            AppConfiguration::class.java.declaredFields.any { it.name == "runtimeProtectionIntent" },
        )
    }

    @Test
    fun `seeded filter identity has one non-circular owner`() {
        assertEquals("filter-essential", ProtectionLevel.CORE.seededFilterId)
        assertEquals("filter-balanced", ProtectionLevel.BALANCED.seededFilterId)
        assertEquals("filter-comprehensive", ProtectionLevel.EXTRA.seededFilterId)

        for (level in ProtectionLevel.entries) {
            assertEquals(level.seededFilterId, FilterProfile.stableId(level))
        }
    }

    @Test
    fun `configuration rejects blank active filter identity but accepts any saved filter id`() {
        assertThrowsIllegalArgument {
            AppConfiguration.default().copy(activeFilterId = " ")
        }
        // The active filter is an opaque id: switching filters is independent of the onboarding
        // protection level, so a seeded id that differs from the level is a legal state now
        // (the filter library is the authoritative record's truth, not the level pair).
        val seededMismatch = AppConfiguration.default().copy(
            protectionLevel = ProtectionLevel.CORE,
            activeFilterId = ProtectionLevel.EXTRA.seededFilterId,
        )
        assertEquals(ProtectionLevel.EXTRA.seededFilterId, seededMismatch.activeFilterId)

        val custom = AppConfiguration.default().copy(
            protectionLevel = ProtectionLevel.CORE,
            activeFilterId = "custom-profile",
        )
        assertEquals("custom-profile", custom.activeFilterId)
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            assertFalse("expected IllegalArgumentException", true)
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
