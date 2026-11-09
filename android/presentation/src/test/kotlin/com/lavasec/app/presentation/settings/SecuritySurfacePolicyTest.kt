package com.lavasec.app.presentation.settings

import com.lavasec.app.foundation.security.ProtectedSurface
import com.lavasec.app.presentation.ProductDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the destination coverage of each protected surface against the pinned iOS
 * `SettingsRoute.securityPolicy` mapping. This is the pure policy half of the Security surface;
 * the Compose switch rendering is covered by SecuritySettingsScreenTest.
 */
class SecuritySurfacePolicyTest {
    @Test
    fun `app settings protects the settings family the pinned iOS policy gates`() {
        val protected = setOf(
            ProductDestination.Settings,
            ProductDestination.Account,
            ProductDestination.Upgrade,
            ProductDestination.Customization,
            ProductDestination.DnsResolver,
            ProductDestination.PrivacyData,
        )
        for (destination in ProductDestination.entries) {
            if (destination in protected) {
                assertTrue(
                    "appSettings must protect $destination",
                    ProtectedSurface.APP_SETTINGS.protects(destination),
                )
            } else {
                assertFalse(
                    "appSettings must not protect $destination",
                    ProtectedSurface.APP_SETTINGS.protects(destination),
                )
            }
        }
    }

    @Test
    fun `read only settings and the other families stay outside app settings`() {
        assertFalse(ProtectedSurface.APP_SETTINGS.protects(ProductDestination.Security))
        assertFalse(ProtectedSurface.APP_SETTINGS.protects(ProductDestination.BugReport))
        assertFalse(ProtectedSurface.APP_SETTINGS.protects(ProductDestination.LegalNotices))
        assertFalse(ProtectedSurface.APP_SETTINGS.protects(ProductDestination.Guard))
        assertFalse(ProtectedSurface.APP_SETTINGS.protects(ProductDestination.Filters))
        assertFalse(ProtectedSurface.APP_SETTINGS.protects(ProductDestination.Activity))
    }

    @Test
    fun `activity keeps its diagnostics family and protection commands stay destinationless`() {
        assertTrue(ProtectedSurface.ACTIVITY.protects(ProductDestination.NetworkActivity))
        assertTrue(ProtectedSurface.ACTIVITY.protects(ProductDestination.NerdStats))
        assertFalse(ProtectedSurface.PROTECTION_CONTROL.protects(ProductDestination.Settings))
        assertFalse(ProtectedSurface.PROTECTION_PAUSE.protects(ProductDestination.Guard))
    }

    @Test
    fun `app unlock blocks the whole app rather than a destination`() {
        for (destination in ProductDestination.entries) {
            assertFalse(
                "appUnlock must not be destination-scoped ($destination)",
                ProtectedSurface.APP_UNLOCK.protects(destination),
            )
        }
    }
}
