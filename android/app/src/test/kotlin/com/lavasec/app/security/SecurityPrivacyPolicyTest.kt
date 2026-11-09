package com.lavasec.app.security

import com.lavasec.app.foundation.security.ProtectedSurface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Global display consent is independent of scoped access and temporary read failures. */
class SecurityPrivacyPolicyTest {
    private fun owner(
        availability: SecurityAvailability = SecurityAvailability.Available,
        enabled: Boolean = true,
        protectedSurfaces: Set<ProtectedSurface> = emptySet(),
        recoveryRequired: Boolean = false,
    ) = SecurityOwnerState(
        availability = availability,
        enabled = enabled,
        protectedSurfaces = protectedSurfaces,
        recoveryRequired = recoveryRequired,
    )

    @Test fun `no owner state stays covered`() {
        assertTrue(SecurityPrivacyPolicy.requiresBackgroundCover(null))
    }

    @Test fun `unavailable or recovery state stays covered`() {
        assertTrue(SecurityPrivacyPolicy.requiresBackgroundCover(owner(availability = SecurityAvailability.Unavailable)))
        assertTrue(SecurityPrivacyPolicy.requiresBackgroundCover(owner(recoveryRequired = true)))
    }

    @Test fun `a credential alone does not opt in`() {
        assertFalse(SecurityPrivacyPolicy.requiresBackgroundCover(owner(enabled = true, protectedSurfaces = emptySet())))
        assertFalse(SecurityPrivacyPolicy.requiresBackgroundCover(owner(enabled = false, protectedSurfaces = emptySet())))
    }

    @Test fun `only Open Lava opts into the global cover`() {
        assertTrue(SecurityPrivacyPolicy.requiresBackgroundCover(owner(protectedSurfaces = setOf(ProtectedSurface.APP_UNLOCK))))
        assertFalse(SecurityPrivacyPolicy.requiresBackgroundCover(owner(protectedSurfaces = setOf(ProtectedSurface.FILTERS))))
        assertFalse(
            SecurityPrivacyPolicy.requiresBackgroundCover(
                owner(protectedSurfaces = setOf(ProtectedSurface.ACTIVITY, ProtectedSurface.APP_SETTINGS)),
            ),
        )
    }
}
