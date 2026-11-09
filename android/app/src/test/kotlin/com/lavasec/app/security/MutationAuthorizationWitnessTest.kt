package com.lavasec.app.security

import com.lavasec.app.foundation.security.ProtectedSurface
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MutationAuthorizationWitnessTest {
    @Test fun `capture needs both native unlock and requested surface grants`() = runTest {
        val owner = security(setOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.FILTERS))
        try {
            assertNull(MutationAuthorizationWitness.capture(owner, ProtectedSurface.FILTERS))
            authorize(owner, ProtectedSurface.APP_UNLOCK)
            assertNull(MutationAuthorizationWitness.capture(owner, ProtectedSurface.FILTERS))
            authorize(owner, ProtectedSurface.FILTERS)
            assertTrue(checkNotNull(MutationAuthorizationWitness.capture(owner, ProtectedSurface.FILTERS)).isCurrent())
        } finally { owner.close() }
    }

    @Test fun `regrant cannot revive a mutation captured in the revoked turn`() = runTest {
        val owner = security(setOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.APP_SETTINGS))
        try {
            authorize(owner, ProtectedSurface.APP_UNLOCK); authorize(owner, ProtectedSurface.APP_SETTINGS)
            val old = checkNotNull(MutationAuthorizationWitness.capture(owner, ProtectedSurface.APP_SETTINGS))
            owner.resetAuthenticationTurn()
            assertFalse(old.isCurrent())
            authorize(owner, ProtectedSurface.APP_UNLOCK); authorize(owner, ProtectedSurface.APP_SETTINGS)
            assertTrue(owner.state.value.isAuthorized(ProtectedSurface.APP_SETTINGS))
            assertFalse(old.isCurrent())
            assertTrue(checkNotNull(MutationAuthorizationWitness.capture(owner, ProtectedSurface.APP_SETTINGS)).isCurrent())
        } finally { owner.close() }
    }

    @Test fun `security off navigation turn keeps unchanged native authority`() = runTest {
        val owner = security(emptySet(), passcode = null)
        try {
            val witness = checkNotNull(MutationAuthorizationWitness.capture(owner, ProtectedSurface.APP_SETTINGS))
            val revision = owner.state.value.authenticationRevision
            owner.resetAuthenticationTurn()
            assertTrue(owner.state.value.authenticationRevision > revision)
            assertTrue(witness.isCurrent())
            assertTrue(owner.setPasscode("1234"))
            assertTrue(witness.isCurrent())
            assertTrue(owner.setProtectedSurface(ProtectedSurface.APP_SETTINGS, true))
            assertFalse(witness.isCurrent())
        } finally { owner.close() }
    }

    @Test fun `an unrelated policy change does not invent protection for an unprotected mutation`() = runTest {
        val owner = security(setOf(ProtectedSurface.ACTIVITY))
        try {
            val witness = checkNotNull(MutationAuthorizationWitness.capture(owner, ProtectedSurface.FILTERS))
            assertTrue(owner.setProtectedSurface(ProtectedSurface.APP_SETTINGS, true))
            assertTrue(witness.isCurrent())
        } finally { owner.close() }
    }

    @Test fun `required policy change invalidates captured authority even when it becomes unprotected`() = runTest {
        val owner = security(setOf(ProtectedSurface.FILTERS))
        try {
            authorize(owner, ProtectedSurface.FILTERS)
            val witness = checkNotNull(MutationAuthorizationWitness.capture(owner, ProtectedSurface.FILTERS))
            assertTrue(owner.setProtectedSurface(ProtectedSurface.FILTERS, false))
            assertTrue(owner.state.value.isAuthorized(ProtectedSurface.FILTERS))
            assertFalse(witness.isCurrent())
            assertNotNull(MutationAuthorizationWitness.capture(owner, ProtectedSurface.FILTERS))
        } finally { owner.close() }
    }

    private suspend fun authorize(owner: SecurityOwner, surface: ProtectedSurface) {
        assertTrue(owner.requireAuthentication(surface) { request ->
            assertTrue(owner.completeAuthentication(request.id, "1234"))
        })
    }

    private fun TestScope.security(surfaces: Set<ProtectedSurface>, passcode: String? = "1234"): SecurityOwner {
        val verifier = object : PasscodeVerifierPort {
            var savedCode = passcode
            override fun availability() = PasscodeVerifierAvailability.Available
            override fun credentialState() = if (savedCode == null) PasscodeCredentialState.Absent else PasscodeCredentialState.Available
            override fun verify(code: String) = if (code == savedCode) PasscodeVerification.Verified else PasscodeVerification.Wrong
            override fun configure(code: String): Boolean { savedCode = code; return true }
            override fun remove(): Boolean { savedCode = null; return true }
        }
        val preferences = object : SecurityPreferencesPort {
            var saved = SecurityPreferences(enabled = passcode != null, protectedSurfaces = surfaces)
            override fun load() = saved
            override fun save(preferences: SecurityPreferences) { saved = preferences }
        }
        return SecurityOwner(verifier, preferences, backgroundScope)
    }
}
