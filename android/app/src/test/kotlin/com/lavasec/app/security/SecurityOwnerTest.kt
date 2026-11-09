package com.lavasec.app.security

import com.lavasec.app.foundation.biometrics.BiometricAvailability
import com.lavasec.app.foundation.biometrics.BiometricKind
import com.lavasec.app.foundation.biometrics.BiometricPromptResult
import com.lavasec.app.foundation.security.LockTimeoutOption
import com.lavasec.app.foundation.security.ProtectedSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory passcode verifier with the exact iOS verification semantics. */
private class FakePasscodeVerifier(
    private var available: Boolean = true,
) : PasscodeVerifierPort {
    var configured: String? = null
    var configureFails: Boolean = false
    var verifyUnavailable: Boolean = false
    var removeFails: Boolean = false
    var onConfigure: (() -> Unit)? = null
    var onRemove: (() -> Unit)? = null
    var onVerify: (() -> Unit)? = null

    override fun availability(): PasscodeVerifierAvailability =
        if (available) PasscodeVerifierAvailability.Available else PasscodeVerifierAvailability.Unavailable

    override fun configure(code: String): Boolean {
        onConfigure?.invoke()
        if (configureFails || !available) return false
        configured = code
        return true
    }

    override fun verify(code: String): PasscodeVerification {
        onVerify?.invoke()
        if (verifyUnavailable) return PasscodeVerification.Unavailable
        // Mirrors the Keystore verifier: a missing blob is an unavailable
        // verifier, not a wrong passcode.
        val stored = configured ?: return PasscodeVerification.Unavailable
        return if (stored == code) PasscodeVerification.Verified else PasscodeVerification.Wrong
    }

    override fun remove(): Boolean {
        onRemove?.invoke()
        if (removeFails) return false
        configured = null
        verifyUnavailable = false
        return true
    }

    override fun credentialState(): PasscodeCredentialState = when {
        !available || verifyUnavailable -> PasscodeCredentialState.Unavailable
        configured != null -> PasscodeCredentialState.Available
        else -> PasscodeCredentialState.Absent
    }

    fun makeUnavailable() {
        available = false
    }
}

/** In-memory biometric authenticator with the exact fail-closed port semantics. */
private class FakeBiometricAuthenticator(
    var availabilityState: BiometricAvailability = BiometricAvailability.Available(BiometricKind.FINGERPRINT),
    var lockedOut: Boolean = false,
) : BiometricAuthenticatorPort {
    var result: BiometricPromptResult = BiometricPromptResult.Success
    var authenticateCount = 0
    var cancelCount = 0
    var resetCount = 0
    var resetSucceeds = false
    val authenticateStarted = CompletableDeferred<Unit>()
    var release: CompletableDeferred<Unit>? = null

    override fun availability(): BiometricAvailability = availabilityState

    override fun isLockedOut(): Boolean = lockedOut

    override fun resetEnrollmentBoundKey(): Boolean {
        resetCount++
        if (!resetSucceeds) return false
        availabilityState = BiometricAvailability.Available(BiometricKind.FINGERPRINT)
        return true
    }

    override suspend fun authenticate(purpose: String): BiometricPromptResult {
        authenticateCount++
        authenticateStarted.complete(Unit)
        release?.await()
        return result
    }

    override fun cancel() {
        cancelCount++
    }
}

private class FakeSecurityPreferences : SecurityPreferencesPort {
    var stored = SecurityPreferences()
    var failLoad = false
    var beforeSave: ((SecurityPreferences) -> Unit)? = null

    override fun load(): SecurityPreferences {
        check(!failLoad)
        return stored
    }

    override fun save(preferences: SecurityPreferences) {
        beforeSave?.invoke(preferences)
        stored = preferences
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityOwnerTest {
    private var now = 0L

    @Test
    fun `native credential results distinguish verification refusal from legacy admission`() = runTest {
        val verifier = FakePasscodeVerifier()
        val prefs = FakeSecurityPreferences()
        val owner = owner(verifier, prefs)
        assertEquals(SecurityCredentialMutationResult.COMPLETED, owner.setPasscodeResult("1234"))
        val retained = prefs.stored
        assertTrue(owner.changePasscode("wrong", "5678")) // Existing Boolean admission contract.
        assertEquals(SecurityCredentialMutationResult.WRONG_PASSCODE, owner.changePasscodeResult("wrong", "5678"))
        assertEquals(SecurityCredentialMutationResult.WRONG_PASSCODE, owner.removePasscodeResult("wrong"))
        assertEquals(retained, prefs.stored)
        assertTrue(owner.state.value.enabled)
        assertEquals(SecurityFailure.WRONG_PASSCODE, owner.state.value.failure)
        assertEquals(PasscodeVerification.Verified, verifier.verify("1234"))
        assertEquals(PasscodeVerification.Wrong, verifier.verify("5678"))
        assertEquals(SecurityCredentialMutationResult.COMPLETED, owner.changePasscodeResult("1234", "5678"))
        assertEquals(PasscodeVerification.Verified, verifier.verify("5678"))
        assertEquals(SecurityCredentialMutationResult.COMPLETED, owner.removePasscodeResult("5678"))
        assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
        assertFalse(owner.state.value.enabled)
    }

    @Test
    fun `native credential results never complete failed writes deletion or publication`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configureFails = true }
        val prefs = FakeSecurityPreferences()
        val owner = owner(verifier, prefs)
        assertEquals(SecurityCredentialMutationResult.SECURE_STORAGE, owner.setPasscodeResult("1234"))
        assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
        verifier.configureFails = false
        assertEquals(SecurityCredentialMutationResult.COMPLETED, owner.setPasscodeResult("1234"))
        verifier.removeFails = true
        assertEquals(SecurityCredentialMutationResult.SECURE_STORAGE, owner.removePasscodeResult("1234"))
        assertTrue(owner.state.value.enabled)
        assertEquals(PasscodeVerification.Verified, verifier.verify("1234"))
        verifier.removeFails = false
        verifier.onRemove = { prefs.beforeSave = { error("final publication unavailable") } }
        assertEquals(SecurityCredentialMutationResult.SECURE_STORAGE, owner.removePasscodeResult("1234"))
        assertEquals(SecurityAvailability.Unavailable, owner.state.value.availability)
        assertTrue(prefs.stored.credentialChangePending)
    }

    @Test
    fun `retired native credential operation cannot report completion to its destroyed handle`() = runTest {
        val verifier = FakePasscodeVerifier()
        val root = owner(verifier)
        val native = root.openSession(backgroundScope)
        verifier.onConfigure = { native.close() }
        assertEquals(SecurityCredentialMutationResult.REFUSED, native.setPasscodeResult("1234"))
        // The already-authorized synchronous write still publishes durable facts
        // to the live owner, but cannot manufacture a result for the retired UI.
        assertTrue(root.state.value.enabled)
        assertEquals(PasscodeVerification.Verified, verifier.verify("1234"))
        assertEquals(SecurityCredentialMutationResult.REFUSED, native.removePasscodeResult("1234"))
        verifier.onConfigure = null
        assertEquals(SecurityCredentialMutationResult.COMPLETED, root.removePasscodeResult("1234"))
    }

    @Test
    fun `setup change remove and unlock follow the exact lock lifecycle`() = runTest {
        val verifier = FakePasscodeVerifier()
        val owner = owner(verifier)

        assertEquals(SecurityUiStateLike.Disabled, owner.state.value.classify())

        // Setup enables the lock and starts the session now.
        assertTrue(owner.setPasscode("1234"))
        assertEquals(true, owner.state.value.enabled)
        assertEquals(now, owner.state.value.lastUnlockEpochMillis)
        assertNull(owner.state.value.failure)

        // A wrong current passcode never changes the stored verifier.
        now = 1_000L
        assertTrue(owner.changePasscode("wrong", "9999"))
        assertEquals(SecurityFailure.WRONG_PASSCODE, owner.state.value.failure)
        assertNull(owner.state.value.lastChangeEpochMillis)
        assertTrue(verifier.verify("1234") == PasscodeVerification.Verified)

        // The correct current passcode admits the change and stamps the
        // durable close signal the Security screen watches.
        now = 2_000L
        assertTrue(owner.changePasscode("1234", "5678"))
        assertEquals(now, owner.state.value.lastChangeEpochMillis)
        assertNull(owner.state.value.failure)
        assertEquals(true, verifier.verify("5678") == PasscodeVerification.Verified)

        // Unlock with the new code refreshes the session; wrong code reports it.
        assertTrue(owner.unlock("5678"))
        assertEquals(now, owner.state.value.lastUnlockEpochMillis)
        assertTrue(owner.unlock("nope"))
        assertEquals(SecurityFailure.WRONG_PASSCODE, owner.state.value.failure)

        // Remove verifies the current passcode and returns to disabled.
        assertTrue(owner.removePasscode("5678"))
        assertEquals(false, owner.state.value.enabled)
        assertEquals(null, owner.state.value.lastUnlockEpochMillis)
    }

    @Test
    fun `setting a passcode does not select protection surfaces`() = runTest {
        val prefs = FakeSecurityPreferences()
        val owner = owner(FakePasscodeVerifier(), prefs)
        assertTrue(owner.setPasscode("1234"))
        assertEquals(emptySet<ProtectedSurface>(), owner.state.value.protectedSurfaces)
        assertEquals(emptySet<ProtectedSurface>(), prefs.stored.protectedSurfaces)
    }

    @Test
    fun `lock timeout and protected surfaces persist through the ports`() = runTest {
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(protectedSurfaces = setOf(ProtectedSurface.ACTIVITY, ProtectedSurface.FILTERS))
        }
        val owner = owner(FakePasscodeVerifier().apply { configured = "1234" }, prefs)

        assertTrue(owner.selectLockTimeout(LockTimeoutOption.FIVE_MINUTES))
        assertTrue(owner.setProtectedSurface(ProtectedSurface.ACTIVITY, false))

        assertEquals(5L, prefs.stored.lockTimeoutMinutes)
        assertEquals(setOf(ProtectedSurface.FILTERS), prefs.stored.protectedSurfaces)
        assertEquals(5L, owner.state.value.lockTimeoutMinutes)
        assertEquals(setOf(ProtectedSurface.FILTERS), owner.state.value.protectedSurfaces)
    }

    @Test
    fun `a lost verifier fails to the honest recovery state and resets cleanly`() = runTest {
        val prefs = FakeSecurityPreferences()
        prefs.stored = prefs.stored.copy(enabled = true)
        // A record exists but its key is unreadable (restore or hardware reset).
        val owner = owner(FakePasscodeVerifier().apply { verifyUnavailable = true }, prefs)

        assertEquals(true, owner.state.value.recoveryRequired)

        // Verification against a lost verifier keeps the honest recovery path.
        assertTrue(owner.unlock("1234"))
        assertEquals(true, owner.state.value.recoveryRequired)
        assertEquals(SecurityFailure.SECURE_STORAGE, owner.state.value.failure)

        // Reset drops the broken verifier and returns to disabled; a fresh
        // setup then works normally.
        assertTrue(owner.resetAfterRecoveryFailure())
        assertEquals(false, owner.state.value.recoveryRequired)
        assertEquals(false, owner.state.value.enabled)
        assertTrue(owner.setPasscode("4321"))
        assertEquals(true, owner.state.value.enabled)
    }

    @Test
    fun `an unavailable keystore degrades to the honest unavailable state`() = runTest {
        val verifier = FakePasscodeVerifier(available = false)
        val owner = owner(verifier)

        assertEquals(true, owner.state.value.availability == SecurityAvailability.Unavailable)
        // Every mutation is admitted but changes nothing.
        assertTrue(owner.setPasscode("1234"))
        assertEquals(false, owner.state.value.enabled)
    }

    @Test
    fun `secure storage failure during setup reports the typed failure`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configureFails = true }
        val owner = owner(verifier)

        assertTrue(owner.setPasscode("1234"))
        assertEquals(SecurityFailure.SECURE_STORAGE, owner.state.value.failure)
        assertEquals(false, owner.state.value.enabled)
    }

    @Test
    fun `a closed owner refuses every mutation`() = runTest {
        val owner = owner(FakePasscodeVerifier())
        owner.close()

        assertFalse(owner.setPasscode("1234"))
        assertFalse(owner.unlock("1234"))
        assertFalse(owner.selectLockTimeout(LockTimeoutOption.ONE_MINUTE))
        assertFalse(owner.resetAfterRecoveryFailure())
    }

    @Test
    fun `confirmed absence clears choices but unreadable storage preserves them`() = runTest {
        val choices = setOf(ProtectedSurface.ACTIVITY)
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = choices)
        }
        val verifier = FakePasscodeVerifier().apply { verifyUnavailable = true }
        val unreadable = owner(verifier, prefs)
        assertTrue(unreadable.state.value.recoveryRequired)
        assertEquals(choices, prefs.stored.protectedSurfaces)
        assertEquals(ProtectedSurface.ALL, unreadable.state.value.protectedSurfaces)
        unreadable.close()
        verifier.verifyUnavailable = false
        val absent = owner(verifier, prefs)
        assertFalse(absent.state.value.enabled)
        assertFalse(absent.state.value.recoveryRequired)
        assertEquals(emptySet<ProtectedSurface>(), prefs.stored.protectedSurfaces)
    }

    @Test
    fun `setup and recovery cannot replace a valid passcode`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val owner = owner(verifier)
        owner.setPasscode("9999")
        assertEquals(SecurityFailure.OPERATION_FAILED, owner.state.value.failure)
        owner.resetAfterRecoveryFailure()
        assertEquals(SecurityFailure.OPERATION_FAILED, owner.state.value.failure)
        assertEquals(PasscodeVerification.Verified, verifier.verify("1234"))
    }

    @Test
    fun `failed conservative publication prevents any credential mutation`() = runTest {
        val prefs = FakeSecurityPreferences()
        val verifier = FakePasscodeVerifier()
        val owner = owner(verifier, prefs)
        prefs.beforeSave = { error("disk unavailable") }
        var called = false
        verifier.onConfigure = { called = true }
        owner.setPasscode("1234")
        assertFalse(called)
        assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
        assertEquals(SecurityAvailability.Unavailable, owner.state.value.availability)
        assertNull(owner.state.value.lastUnlockEpochMillis)
    }

    @Test
    fun `credential change is fenced on disk and recovery never converts gates to choices`() = runTest {
        val choices = setOf(ProtectedSurface.ACTIVITY)
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = choices)
        }
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val owner = owner(verifier, prefs)
        verifier.onConfigure = {
            assertTrue(prefs.stored.credentialChangePending)
            assertEquals(choices, prefs.stored.protectedSurfaces)
            assertEquals(SecurityAvailability.Unavailable, owner.state.value.availability)
            prefs.beforeSave = { error("disk unavailable after credential write") }
        }
        owner.changePasscode("1234", "5678")
        assertEquals(PasscodeVerification.Verified, verifier.verify("5678"))
        assertEquals(SecurityAvailability.Unavailable, owner.state.value.availability)
        assertNull(owner.state.value.lastChangeEpochMillis)
        assertTrue(prefs.stored.credentialChangePending)
        owner.close()
        prefs.beforeSave = null
        val reopened = owner(verifier, prefs)
        assertEquals(SecurityAvailability.Available, reopened.state.value.availability)
        assertEquals(choices, reopened.state.value.protectedSurfaces)
        assertFalse(prefs.stored.credentialChangePending)
        assertNull(reopened.state.value.lastUnlockEpochMillis)
    }

    @Test
    fun `failed deletion keeps passcode and selected gates instead of reporting disabled`() = runTest {
        val choices = setOf(ProtectedSurface.ACTIVITY)
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = choices)
        }
        val verifier = FakePasscodeVerifier().apply { configured = "1234"; removeFails = true }
        val owner = owner(verifier, prefs)
        owner.removePasscode("1234")
        assertTrue(owner.state.value.enabled)
        assertEquals(SecurityFailure.SECURE_STORAGE, owner.state.value.failure)
        assertEquals(choices, prefs.stored.protectedSurfaces)
        verifier.removeFails = false
        owner.removePasscode("1234")
        assertFalse(owner.state.value.enabled)
        assertEquals(emptySet<ProtectedSurface>(), prefs.stored.protectedSurfaces)
    }

    @Test
    fun `unreadable preferences refuse access and do not touch verifier`() = runTest {
        val prefs = FakeSecurityPreferences().apply { failLoad = true }
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val owner = owner(verifier, prefs)
        assertEquals(SecurityAvailability.Unavailable, owner.state.value.availability)
        owner.setPasscode("5678")
        owner.unlock("1234")
        owner.resetAfterRecoveryFailure()
        assertEquals(SecurityAvailability.Unavailable, owner.state.value.availability)
        assertEquals(PasscodeVerification.Verified, verifier.verify("1234"))
    }

    @Test
    fun `failed final removal publication stays unavailable until classified reopen`() = runTest {
        val prefs = FakeSecurityPreferences()
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val owner = owner(verifier, prefs)
        verifier.onRemove = { prefs.beforeSave = { error("disk unavailable") } }
        owner.removePasscode("1234")
        assertEquals(SecurityAvailability.Unavailable, owner.state.value.availability)
        assertTrue(prefs.stored.credentialChangePending)
        owner.close()
        prefs.beforeSave = null
        val reopened = owner(verifier, prefs)
        assertEquals(SecurityAvailability.Available, reopened.state.value.availability)
        assertFalse(reopened.state.value.enabled)
        assertEquals(emptySet<ProtectedSurface>(), reopened.state.value.protectedSurfaces)
    }

    @Test
    fun `retirement during a synchronous credential write still publishes durable facts to siblings`() = runTest {
        val verifier = FakePasscodeVerifier()
        val root = owner(verifier)
        val native = root.openSession(backgroundScope)
        val rn = root.openSession(backgroundScope)
        verifier.onConfigure = { native.close() }
        assertFalse(native.setPasscode("1234"))
        // The retired caller cannot claim completion, but the disk change must remain visible.
        assertTrue(rn.state.value.enabled)
        assertFalse(rn.state.value.inFlight)
        assertEquals(PasscodeVerification.Verified, verifier.verify("1234"))
        rn.close()
        root.close()
    }

    @Test
    fun `native and RN handles share current credential state but retire independently`() = runTest {
        val verifier = FakePasscodeVerifier()
        val root = owner(verifier)
        val native = root.openSession(backgroundScope)
        val rn = root.openSession(backgroundScope)
        native.setPasscode("1234")
        assertTrue(rn.state.value.enabled)
        assertEquals(native.state.value, rn.state.value)
        rn.setPasscode("9999")
        assertEquals(PasscodeVerification.Verified, verifier.verify("1234"))
        native.close()
        assertFalse(native.removePasscode("1234"))
        rn.changePasscode("1234", "5678")
        assertEquals(PasscodeVerification.Verified, verifier.verify("5678"))
        val replacement = root.openSession(backgroundScope)
        assertEquals(rn.state.value, replacement.state.value)
        rn.close()
        replacement.removePasscode("5678")
        assertFalse(root.state.value.enabled)
        replacement.close()
        root.close()
    }

    @Test
    fun `grants are per surface per turn and never restored from timestamps`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val root = owner(verifier, prefs)
        val sibling = root.openSession(backgroundScope)
        root.unlock("1234", ProtectedSurface.ACTIVITY)
        assertTrue(sibling.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
        assertFalse(sibling.state.value.isAuthorized(ProtectedSurface.FILTERS))
        sibling.resetAuthenticationTurn()
        assertFalse(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
        root.unlock("1234", ProtectedSurface.FILTERS)
        assertTrue(root.state.value.isAuthorized(ProtectedSurface.FILTERS))
        val reopened = owner(verifier, prefs)
        assertFalse(reopened.state.value.isAuthorized(ProtectedSurface.FILTERS))
    }

    @Test
    fun `navigation during verification rejects the late grant in every handle`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val root = owner(verifier, prefs)
        val sibling = root.openSession(backgroundScope)
        verifier.onVerify = { sibling.resetAuthenticationTurn() }
        root.unlock("1234", ProtectedSurface.ACTIVITY)
        assertFalse(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
        assertEquals(root.state.value, sibling.state.value)
    }

    @Test
    fun `a queued old-page unlock cannot grant a newer navigation turn`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val root = owner(verifier, prefs)
        val renderedRevision = root.state.value.authenticationRevision
        root.resetAuthenticationTurn()
        root.unlock("1234", ProtectedSurface.ACTIVITY, renderedRevision)
        assertFalse(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
    }

    @Test
    fun `retirement during verification cannot publish a grant to a sibling`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val root = owner(verifier, prefs)
        val retired = root.openSession(backgroundScope)
        verifier.onVerify = { retired.close() }
        retired.unlock("1234", ProtectedSurface.ACTIVITY)
        assertFalse(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
    }

    @Test
    fun `request retries wrong codes and returns verified completion not admission`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" })
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        val result = async { root.requireAuthentication(null) { shown.complete(it); awaitCancellation() } }
        val request = shown.await()
        assertFalse(root.completeAuthentication(request.id, "wrong"))
        assertFalse(result.isCompleted)
        assertFalse(root.state.value.credentialsAuthenticated)
        assertTrue(root.completeAuthentication(request.id, "1234"))
        assertTrue(result.await())
        assertTrue(root.state.value.credentialsAuthenticated)
        assertFalse(root.completeAuthentication(request.id, "1234"))
    }

    @Test
    fun `credentials purpose always verifies configured passcode even with no selected surfaces`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" })
        assertTrue(root.requireAuthentication(ProtectedSurface.ACTIVITY) { error("unselected") })
        assertFalse(root.requireAuthentication(null) { /* native dismissal */ })
        assertFalse(root.state.value.credentialsAuthenticated)
    }

    @Test
    fun `request grants only its purpose and fresh request bypasses cached grant`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" }, FakeSecurityPreferences().apply {
            stored = SecurityPreferences(protectedSurfaces = ProtectedSurface.ALL)
        })
        suspend fun verify(surface: ProtectedSurface?) {
            val shown = CompletableDeferred<SecurityAuthenticationRequest>()
            val result = async { root.requireAuthentication(surface) { shown.complete(it); awaitCancellation() } }
            assertTrue(root.completeAuthentication(shown.await().id, "1234"))
            assertTrue(result.await())
        }
        verify(ProtectedSurface.ACTIVITY)
        assertTrue(root.requireAuthentication(ProtectedSurface.ACTIVITY) { error("cached") })
        assertFalse(root.state.value.isAuthorized(ProtectedSurface.FILTERS))
        assertFalse(root.state.value.credentialsAuthenticated)
        assertFalse(root.requireAuthentication(ProtectedSurface.ACTIVITY, fresh = true) { /* cancel */ })
        verify(null)
        assertTrue(root.requireAuthentication(null) { error("cached credentials") })
        root.resetAuthenticationTurn()
        assertFalse(root.state.value.credentialsAuthenticated)
        assertFalse(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
    }

    @Test
    fun `navigation dismisses pending request and rejects its late completion`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" })
        val sibling = root.openSession(backgroundScope)
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        var dismissed = false
        val result = async { root.requireAuthentication(null) {
            shown.complete(it)
            try { awaitCancellation() } finally { dismissed = true }
        } }
        val request = shown.await()
        sibling.resetAuthenticationTurn()
        assertFalse(result.await())
        assertTrue(dismissed)
        assertFalse(root.completeAuthentication(request.id, "1234"))
        assertFalse(root.state.value.credentialsAuthenticated)
    }

    @Test
    fun `turn changes during verification cannot authorize a request`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val root = owner(verifier)
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        val result = async { root.requireAuthentication(null) { shown.complete(it); awaitCancellation() } }
        verifier.onVerify = { root.resetAuthenticationTurn() }
        assertFalse(root.completeAuthentication(shown.await().id, "1234"))
        assertFalse(result.await())
        assertFalse(root.state.value.credentialsAuthenticated)
    }

    @Test
    fun `retired owner dismisses request without authorizing siblings`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" })
        val retired = root.openSession(backgroundScope)
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        val result = async { retired.requireAuthentication(null) { shown.complete(it); awaitCancellation() } }
        val request = shown.await()
        retired.close()
        assertFalse(result.await())
        assertFalse(root.completeAuthentication(request.id, "1234"))
        assertFalse(root.state.value.credentialsAuthenticated)
    }

    @Test
    fun `caller cancellation releases prompt and old id cannot complete replacement`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" })
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        val result = async { root.requireAuthentication(null) { shown.complete(it); awaitCancellation() } }
        val old = shown.await()
        result.cancel()
        result.join()
        val replacement = CompletableDeferred<SecurityAuthenticationRequest>()
        val next = async { root.requireAuthentication(null) { replacement.complete(it); awaitCancellation() } }
        val current = replacement.await()
        assertFalse(root.completeAuthentication(old.id, "1234"))
        assertFalse(next.isCompleted)
        assertTrue(root.completeAuthentication(current.id, "1234"))
        assertTrue(next.await())
    }

    @Test
    fun `simultaneous native and RN prompts cannot steal each others result`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" })
        val sibling = root.openSession(backgroundScope)
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        val result = async { root.requireAuthentication(null) { shown.complete(it); awaitCancellation() } }
        val request = shown.await()
        assertFalse(sibling.requireAuthentication(null) { error("overlapping prompt") })
        assertFalse(sibling.completeAuthentication(request.id, "1234"))
        assertFalse(result.isCompleted)
        root.cancelAuthentication(request.id)
        assertFalse(result.await())
    }

    @Test
    fun `unavailable verifier cancels request rather than returning authentication success`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val root = owner(verifier)
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        val result = async { root.requireAuthentication(null) { shown.complete(it); awaitCancellation() } }
        val request = shown.await()
        verifier.verifyUnavailable = true
        assertFalse(root.completeAuthentication(request.id, "1234"))
        assertFalse(result.await())
        assertFalse(root.state.value.credentialsAuthenticated)
        assertFalse(root.requireAuthentication(null) { error("unavailable") })
    }

    @Test
    fun `dismissal during verification synchronously invalidates correct late result`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val root = owner(verifier)
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        val result = async { root.requireAuthentication(null) { shown.complete(it); awaitCancellation() } }
        val request = shown.await()
        verifier.onVerify = { root.cancelAuthentication(request.id) }
        assertFalse(root.completeAuthentication(request.id, "1234"))
        assertFalse(result.await())
        assertFalse(root.state.value.credentialsAuthenticated)
    }

    @Test
    fun `parent lifecycle cancellation retires outstanding prompt and cached admission`() = runTest {
        val parent = SupervisorJob()
        val root = SecurityOwner(FakePasscodeVerifier().apply { configured = "1234" },
            FakeSecurityPreferences(), CoroutineScope(coroutineContext + parent))
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        val result = async { root.requireAuthentication(null) { shown.complete(it); awaitCancellation() } }
        shown.await()
        parent.cancel()
        assertFalse(result.await())
        assertFalse(root.requireAuthentication(ProtectedSurface.ACTIVITY) { error("retired") })
        root.close()
    }

    @Test
    fun `authentication releases mutation admission before publishing the successful grant`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" })
        val grantInFlight = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            root.state.collect { if (it.credentialsAuthenticated) grantInFlight += it.inFlight }
        }
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        val result = async { root.requireAuthentication(null) { shown.complete(it); awaitCancellation() } }
        assertTrue(root.completeAuthentication(shown.await().id, "1234"))
        assertTrue(result.await())
        assertTrue(grantInFlight.isNotEmpty())
        assertTrue(grantInFlight.none { it })
        observer.cancel()
        assertTrue(root.setProtectedSurface(ProtectedSurface.PROTECTION_CONTROL, true))
        assertTrue(ProtectedSurface.PROTECTION_CONTROL in root.state.value.protectedSurfaces)
    }

    @Test
    fun `a usable enabled biometric authenticates before the passcode`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator()
        val root = owner(verifier, prefs, biometric)
        assertTrue(root.setBiometrics(true))
        assertTrue(root.state.value.biometricEnabled)

        var presentedPasscode = false
        val result = async {
            root.requireAuthentication(ProtectedSurface.ACTIVITY) {
                presentedPasscode = true
                awaitCancellation()
            }
        }
        assertTrue(result.await())
        assertEquals(1, biometric.authenticateCount)
        assertFalse(presentedPasscode)
        assertTrue(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
        root.close()
    }

    @Test
    fun `an absent or changed enrollment is refused and falls back to the passcode`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator(availabilityState = BiometricAvailability.NotEnrolled)
        val root = owner(verifier, prefs, biometric)
        // Enabling is refused while nothing is enrolled; the durable preference stays off.
        assertTrue(root.setBiometrics(true))
        assertFalse(root.state.value.biometricEnabled)

        suspend fun expectPasscodeFallback() {
            val shown = CompletableDeferred<SecurityAuthenticationRequest>()
            var presented = false
            val result = async {
                root.requireAuthentication(ProtectedSurface.ACTIVITY) {
                    shown.complete(it)
                    presented = true
                    awaitCancellation()
                }
            }
            val request = shown.await()
            assertTrue(presented)
            assertEquals(0, biometric.authenticateCount)
            assertTrue(root.completeAuthentication(request.id, "1234"))
            assertTrue(result.await())
            root.resetAuthenticationTurn()
        }
        expectPasscodeFallback()
        // An invalidated key (biometric re-enrollment changed the key) is likewise never offered.
        biometric.availabilityState = BiometricAvailability.KeyInvalidated
        expectPasscodeFallback()
        root.close()
    }

    @Test
    fun `a biometric lockout falls back to the passcode and is recorded`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator().apply {
            result = BiometricPromptResult.Lockout(permanent = false)
        }
        val root = owner(verifier, prefs, biometric)
        assertTrue(root.setBiometrics(true))

        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        var presented = false
        val result = async {
            root.requireAuthentication(ProtectedSurface.ACTIVITY) {
                shown.complete(it)
                presented = true
                awaitCancellation()
            }
        }
        val request = shown.await()
        assertTrue(presented)
        assertEquals(1, biometric.authenticateCount)
        assertTrue(root.state.value.biometricLockout)
        assertTrue(root.completeAuthentication(request.id, "1234"))
        assertTrue(result.await())
        root.close()
    }

    @Test
    fun `an invalidated enrollment key is regenerated on a passcode-authorized re-enable`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator().apply {
            availabilityState = BiometricAvailability.KeyInvalidated
            resetSucceeds = true
        }
        val root = owner(verifier, prefs, biometric)

        // Re-enabling regenerates the enrollment-bound key and advances availability, so the
        // preference becomes effective instead of staying stuck on KeyInvalidated. The reset runs
        // only because the caller proved a fresh passcode authorization.
        assertTrue(root.setBiometrics(true, enrollmentKeyResetAuthorized = true))
        assertEquals(1, biometric.resetCount)
        assertEquals(true, root.state.value.biometricEnabled)
        assertEquals(BiometricAvailability.Available(BiometricKind.FINGERPRINT), root.state.value.biometricAvailability)
        root.close()
    }

    @Test
    fun `an invalidated enrollment key is not regenerated without authorization`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator().apply {
            availabilityState = BiometricAvailability.KeyInvalidated
            resetSucceeds = true
        }
        val root = owner(verifier, prefs, biometric)

        // An unauthenticated enable must never delete/regenerate the enrollment-bound key: the
        // biometric stays ineffective and the invalidation (the passcode fallback) remains.
        assertTrue(root.setBiometrics(true))
        assertEquals(0, biometric.resetCount)
        assertFalse(root.state.value.biometricEnabled)
        assertEquals(BiometricAvailability.KeyInvalidated, root.state.value.biometricAvailability)
        root.close()
    }

    @Test
    fun `an invalidated key that cannot be regenerated keeps biometrics disabled`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator().apply {
            availabilityState = BiometricAvailability.KeyInvalidated
            resetSucceeds = false
        }
        val root = owner(verifier, prefs, biometric)

        assertTrue(root.setBiometrics(true, enrollmentKeyResetAuthorized = true))
        assertEquals(1, biometric.resetCount)
        assertFalse(root.state.value.biometricEnabled)
        assertEquals(BiometricAvailability.KeyInvalidated, root.state.value.biometricAvailability)
        root.close()
    }

    @Test
    fun `a user cancellation denies without presenting the passcode`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator().apply { result = BiometricPromptResult.Cancelled }
        val root = owner(verifier, prefs, biometric)
        assertTrue(root.setBiometrics(true))

        val result = async {
            root.requireAuthentication(ProtectedSurface.ACTIVITY) { error("passcode must not be presented") }
        }
        assertFalse(result.await())
        assertEquals(1, biometric.authenticateCount)
        assertFalse(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
        root.close()
    }

    @Test
    fun `a temporary biometric lockout is re-read and expires`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator().apply { result = BiometricPromptResult.Lockout(permanent = false) }
        val root = owner(verifier, prefs, biometric)
        assertTrue(root.setBiometrics(true))

        // First prompt: lockout -> passcode fallback; the lockout is cached.
        assertFalse(root.requireAuthentication(ProtectedSurface.ACTIVITY) { request -> root.cancelAuthentication(request.id) })
        assertEquals(1, biometric.authenticateCount)
        assertTrue(root.state.value.biometricLockout)

        // The platform lockout expires; the next attempt must re-read it, clear the cache and prompt.
        biometric.lockedOut = false
        biometric.result = BiometricPromptResult.Success
        assertTrue(root.requireAuthentication(ProtectedSurface.ACTIVITY) { error("biometric should have succeeded") })
        assertEquals(2, biometric.authenticateCount)
        assertFalse(root.state.value.biometricLockout)
        root.close()
    }

    @Test
    fun `refreshBiometricFacts reflects an enrollment change`() = runTest {
        val biometric = FakeBiometricAuthenticator(
            availabilityState = BiometricAvailability.NotEnrolled,
        )
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" }, FakeSecurityPreferences(), biometric)
        assertEquals(BiometricAvailability.NotEnrolled, root.state.value.biometricAvailability)
        biometric.availabilityState = BiometricAvailability.Available(BiometricKind.FINGERPRINT)
        root.refreshBiometricFacts()
        assertEquals(BiometricAvailability.Available(BiometricKind.FINGERPRINT), root.state.value.biometricAvailability)
        root.close()
    }

    @Test
    fun `a biometric cancel reports CANCELLED through the outcome form`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator().apply { result = BiometricPromptResult.Cancelled }
        val root = owner(verifier, prefs, biometric)
        assertTrue(root.setBiometrics(true))

        // The bridge needs the cancellation distinct from an ordinary denial so it suppresses the
        // prompt instead of showing a failure.
        val outcome = async {
            root.requireAuthenticationOutcome(ProtectedSurface.ACTIVITY) { error("passcode must not be presented") }
        }
        assertEquals(SecurityAuthorization.CANCELLED, outcome.await())
        root.close()
    }

    @Test
    fun `a turn change during a biometric prompt rejects the late success`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator().apply { release = CompletableDeferred() }
        val root = owner(verifier, prefs, biometric)
        assertTrue(root.setBiometrics(true))

        var presented = false
        val result = async {
            root.requireAuthentication(ProtectedSurface.ACTIVITY) {
                presented = true
                awaitCancellation()
            }
        }
        biometric.authenticateStarted.await()
        root.resetAuthenticationTurn()
        biometric.release!!.complete(Unit)
        assertFalse(result.await())
        assertFalse(presented)
        assertTrue(biometric.cancelCount >= 1)
        assertFalse(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
        root.close()
    }

    @Test
    fun `a retired owner cannot grant from a late biometric success`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator().apply { release = CompletableDeferred() }
        val root = owner(verifier, prefs, biometric)
        assertTrue(root.setBiometrics(true))
        val retired = root.openSession(backgroundScope)

        var presented = false
        val result = async {
            retired.requireAuthentication(ProtectedSurface.ACTIVITY) {
                presented = true
                awaitCancellation()
            }
        }
        biometric.authenticateStarted.await()
        retired.close()
        biometric.release!!.complete(Unit)
        assertFalse(result.await())
        assertFalse(presented)
        assertFalse(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
        root.close()
    }

    @Test
    fun `disabling biometrics keeps the passcode gate`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        }
        val biometric = FakeBiometricAuthenticator()
        val root = owner(verifier, prefs, biometric)
        assertTrue(root.setBiometrics(true))
        assertTrue(root.setBiometrics(false))
        assertFalse(root.state.value.biometricEnabled)
        assertTrue(root.state.value.enabled)
        val shown = CompletableDeferred<SecurityAuthenticationRequest>()
        var presented = false
        val result = async {
            root.requireAuthentication(ProtectedSurface.ACTIVITY) {
                shown.complete(it)
                presented = true
                awaitCancellation()
            }
        }
        val request = shown.await()
        assertTrue(presented)
        assertEquals(0, biometric.authenticateCount)
        assertTrue(root.completeAuthentication(request.id, "1234"))
        assertTrue(result.await())
        root.close()
    }

    @Test
    fun `one fresh app unlock restores only the exact suspended reader and credential visit`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" }, FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        })
        val host = Any()
        val visit = SecurityPresentationVisit { true }
        var prompts = 0
        suspend fun authorize(surface: ProtectedSurface?): Boolean = root.requireAuthentication(surface) {
            prompts++
            assertTrue(root.completeAuthentication(it.id, "1234"))
        }
        try {
            root.enterForeground(host, 7L, visit)
            assertTrue(authorize(ProtectedSurface.APP_UNLOCK))
            assertTrue(authorize(ProtectedSurface.FILTERS))
            assertTrue(authorize(null))
            val oldWrite = checkNotNull(MutationAuthorizationWitness.capture(root, ProtectedSurface.FILTERS))
            val revision = root.suspendForeground(host, 7L, visit)
            assertEquals(revision, root.suspendForeground(host, 7L, visit))
            assertTrue(root.state.value.authenticatedSurfaces.isEmpty())
            assertFalse(root.state.value.credentialsAuthenticated)
            assertFalse(oldWrite.isCurrent())
            assertFalse(authorize(ProtectedSurface.APP_UNLOCK))
            root.enterForeground(host, 7L, visit)
            prompts = 0
            assertTrue(authorize(ProtectedSurface.APP_UNLOCK))
            assertEquals(1, prompts)
            assertTrue(root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            assertTrue(root.state.value.isAuthorized(ProtectedSurface.FILTERS))
            assertTrue(root.state.value.credentialsAuthenticated)
            assertFalse(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
            assertFalse(root.state.value.isAuthorized(ProtectedSurface.APP_SETTINGS))
            assertFalse(root.state.value.isAuthorized(ProtectedSurface.PROTECTION_CONTROL))
            assertFalse(root.state.value.isAuthorized(ProtectedSurface.PROTECTION_PAUSE))
            assertFalse("Fresh read restoration cannot resurrect an old native write witness", oldWrite.isCurrent())
            assertTrue(checkNotNull(MutationAuthorizationWitness.capture(root, ProtectedSurface.FILTERS)).isCurrent())
        } finally { root.close() }
    }

    @Test
    fun `Open Lava OFF never prompts for app unlock or restores a backgrounded scoped grant`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" }, FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = setOf(ProtectedSurface.ACTIVITY))
        })
        val host = Any(); val visit = SecurityPresentationVisit { true }
        try {
            root.enterForeground(host, 7L, visit)
            assertTrue(root.requireAuthentication(ProtectedSurface.ACTIVITY) { root.completeAuthentication(it.id, "1234") })
            root.suspendForeground(host, 7L, visit)
            root.enterForeground(host, 7L, visit)
            var prompts = 0
            assertTrue(root.requireAuthentication(ProtectedSurface.APP_UNLOCK) {
                prompts++; assertTrue(root.completeAuthentication(it.id, "1234"))
            })
            assertEquals(0, prompts)
            assertFalse(root.state.value.isAuthorized(ProtectedSurface.ACTIVITY))
            assertTrue(root.requireAuthentication(ProtectedSurface.ACTIVITY) {
                prompts++; assertTrue(root.completeAuthentication(it.id, "1234"))
            })
            assertEquals(1, prompts)
        } finally { root.close() }
    }

    @Test
    fun `cancelled unlock keeps only the same suspended visit through a repeated real background`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" }, FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        })
        val host = Any(); val visit = SecurityPresentationVisit { true }
        try {
            root.enterForeground(host, 7L, visit)
            assertTrue(root.requireAuthentication(ProtectedSurface.FILTERS) { root.completeAuthentication(it.id, "1234") })
            root.suspendForeground(host, 7L, visit)
            root.enterForeground(host, 7L, visit)
            assertEquals(SecurityAuthorization.CANCELLED, root.requireAuthenticationOutcome(ProtectedSurface.APP_UNLOCK) {
                root.cancelAuthentication(it.id, userCancelled = true)
            })
            assertTrue(root.state.value.authenticatedSurfaces.isEmpty())
            root.pauseForeground(host, 7L, visit)
            root.suspendForeground(host, 7L, visit)
            root.enterForeground(host, 7L, visit)
            assertTrue(root.requireAuthentication(ProtectedSurface.APP_UNLOCK) { root.completeAuthentication(it.id, "1234") })
            assertTrue(root.state.value.isAuthorized(ProtectedSurface.FILTERS))
        } finally { root.close() }
    }

    @Test
    fun `old unlock completion cannot restore a departed replaced destroyed or retired visit`() = runTest {
        for (boundary in listOf("view", "visit", "host", "graph", "destroy", "owner")) {
            val root = owner(FakePasscodeVerifier().apply { configured = "1234" }, FakeSecurityPreferences().apply {
                stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
            })
            val host = Any(); var live = true; val visit = SecurityPresentationVisit { live }
            try {
                root.enterForeground(host, 7L, visit)
                assertTrue(root.requireAuthentication(ProtectedSurface.FILTERS) { root.completeAuthentication(it.id, "1234") })
                root.suspendForeground(host, 7L, visit); root.enterForeground(host, 7L, visit)
                val shown = CompletableDeferred<SecurityAuthenticationRequest>()
                val reply = async { root.requireAuthentication(ProtectedSurface.APP_UNLOCK) { shown.complete(it); awaitCancellation() } }
                val request = shown.await()
                when (boundary) {
                    "view" -> root.resetAuthenticationTurn()
                    "visit" -> { live = false; root.enterForeground(host, 7L, SecurityPresentationVisit { true }) }
                    "host" -> root.enterForeground(Any(), 7L, SecurityPresentationVisit { true })
                    "graph" -> root.enterForeground(host, 8L, SecurityPresentationVisit { true })
                    "destroy" -> { live = false; root.retireForeground(host) }
                    "owner" -> root.close()
                }
                val accepted = root.completeAuthentication(request.id, "1234")
                assertEquals(boundary, boundary == "view" || boundary == "visit", accepted)
                assertEquals(accepted, reply.await())
                assertFalse(boundary, root.state.value.isAuthorized(ProtectedSurface.FILTERS))
                assertFalse(root.state.value.credentialsAuthenticated)
            } finally { root.close() }
        }
    }

    @Test
    fun `an old host pause and retirement cannot erase a successor foreground grant`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" }, FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
        })
        val old = Any(); val successor = Any(); val visit = SecurityPresentationVisit { true }
        try {
            root.enterForeground(old, 7L, visit)
            root.enterForeground(successor, 7L, SecurityPresentationVisit { true })
            assertTrue(root.requireAuthentication(ProtectedSurface.APP_UNLOCK) { root.completeAuthentication(it.id, "1234") })
            val revision = root.state.value.authenticationRevision
            root.suspendForeground(old, 7L, visit); root.retireForeground(old)
            assertEquals(revision, root.state.value.authenticationRevision)
            assertTrue(root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            root.resetAuthenticationTurn()
            assertTrue("Leaving a view does not relock this foreground", root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            root.suspendForeground(successor, 7L, null)
            assertFalse(root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
        } finally { root.close() }
    }

    @Test
    fun `enabling app unlock from settings applies on the next foreground not the current control tap`() = runTest {
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" }, FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true)
        })
        val host = Any(); val visit = SecurityPresentationVisit { true }
        try {
            root.enterForeground(host, 7L, visit)
            assertTrue(root.setProtectedSurface(ProtectedSurface.APP_UNLOCK, true))
            assertTrue(root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            root.resetAuthenticationTurn()
            assertTrue(root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            root.suspendForeground(host, 7L, visit)
            assertFalse(root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
        } finally { root.close() }
    }

    @Test
    fun `a stale protected choice is refused at the serialized write edge without a false completion`() = runTest {
        val preferences = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = setOf(ProtectedSurface.ACTIVITY))
        }
        val root = owner(FakePasscodeVerifier().apply { configured = "1234" }, preferences)
        try {
            val revision = root.state.value.authenticationRevision
            assertFalse(root.setProtectedSurface(ProtectedSurface.ACTIVITY, false, isCurrent = { false }))
            assertEquals(setOf(ProtectedSurface.ACTIVITY), preferences.stored!!.protectedSurfaces)
            assertEquals(revision, root.state.value.authenticationRevision)
            assertTrue(root.setProtectedSurface(ProtectedSurface.ACTIVITY, false, isCurrent = { true }))
            assertTrue(preferences.stored!!.protectedSurfaces.isEmpty())
        } finally { root.close() }
    }

    @Test fun `transient interruption restores only exact session grants and never old write authority`() = runTest {
        for (boundary in listOf("resume", "background", "host", "visit", "turn", "retired", "graph", "owner")) {
            val root = owner(FakePasscodeVerifier().apply { configured = "1234" }, FakeSecurityPreferences().apply {
                stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL)
            })
            val host = Any(); val visit = SecurityPresentationVisit { true }
            try {
                root.enterForeground(host, 7, visit)
                for (surface in listOf(ProtectedSurface.APP_UNLOCK, ProtectedSurface.FILTERS)) {
                    assertTrue(root.requireAuthentication(surface) { root.completeAuthentication(it.id, "1234") })
                }
                val oldWrite = checkNotNull(MutationAuthorizationWitness.capture(root, ProtectedSurface.FILTERS))
                root.pauseForeground(host, 7, visit)
                assertTrue(root.state.value.authenticatedSurfaces.isEmpty())
                assertFalse(oldWrite.isCurrent())
                when (boundary) {
                    "background" -> root.suspendForeground(host, 7, visit)
                    "turn" -> root.resetAuthenticationTurn()
                    "retired" -> root.retirePresentationVisit(visit)
                    "owner" -> root.close()
                }
                root.enterForeground(if (boundary == "host") Any() else host, if (boundary == "graph") 8 else 7,
                    if (boundary == "visit") SecurityPresentationVisit { true } else visit)
                assertEquals(boundary, boundary == "resume", root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
                assertEquals(boundary, boundary == "resume", root.state.value.isAuthorized(ProtectedSurface.FILTERS))
                assertFalse(oldWrite.isCurrent())
            } finally { root.close() }
        }
    }

    @Test fun `known OFF survives conservative access gates and unknown is never promoted by a scoped edit`() = runTest {
        for (choice in listOf(false, null)) {
            val prefs = FakeSecurityPreferences().apply {
                stored = SecurityPreferences(enabled = true, protectedSurfaces = ProtectedSurface.ALL, appUnlockCoverChoice = choice)
            }
            val verifier = FakePasscodeVerifier().apply { configured = "1234" }
            val root = owner(verifier, prefs)
            try {
                assertEquals(choice, root.state.value.appUnlockCoverChoice)
                assertEquals(choice != false, SecurityPrivacyPolicy.requiresBackgroundCover(root.state.value))
                if (choice == false) assertTrue(root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
                assertFalse(root.state.value.isAuthorized(ProtectedSurface.FILTERS))
                assertTrue(root.setProtectedSurface(ProtectedSurface.ACTIVITY, false))
                assertEquals(choice, prefs.stored!!.appUnlockCoverChoice)
                assertEquals(choice, root.state.value.appUnlockCoverChoice)
            } finally { root.close() }
        }
    }

    @Test fun `temporary credential failure preserves OFF and public retry never grants scoped access`() = runTest {
        val verifier = FakePasscodeVerifier().apply { configured = "1234" }
        val prefs = FakeSecurityPreferences().apply {
            stored = SecurityPreferences(enabled = true, protectedSurfaces = setOf(ProtectedSurface.FILTERS), appUnlockCoverChoice = false)
        }
        val root = owner(verifier, prefs)
        try {
            verifier.verifyUnavailable = true
            assertTrue(root.retryAvailability())
            assertFalse(SecurityPrivacyPolicy.requiresBackgroundCover(root.state.value))
            assertFalse(root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            assertFalse(root.state.value.isAuthorized(ProtectedSurface.FILTERS))
            verifier.verifyUnavailable = false
            assertTrue(root.retryAvailability())
            assertFalse(SecurityPrivacyPolicy.requiresBackgroundCover(root.state.value))
            assertTrue(root.state.value.isAuthorized(ProtectedSurface.APP_UNLOCK))
            assertFalse(root.state.value.isAuthorized(ProtectedSurface.FILTERS))
            assertTrue(root.state.value.authenticatedSurfaces.isEmpty())
        } finally { root.close() }
    }

    private fun TestScope.owner(
        verifier: PasscodeVerifierPort,
        prefs: SecurityPreferencesPort = FakeSecurityPreferences(),
        biometric: BiometricAuthenticatorPort = UnavailableBiometricAuthenticatorPort,
    ) = SecurityOwner(
        verifier = verifier,
        preferences = prefs,
        scope = backgroundScope,
        nowMillis = { now },
        biometricAuthenticator = biometric,
    )
}

private enum class SecurityUiStateLike {
    Unavailable,
    Recovery,
    Disabled,
    Enabled,
}

private fun SecurityOwnerState.classify(): SecurityUiStateLike = when {
    availability == SecurityAvailability.Unavailable -> SecurityUiStateLike.Unavailable
    recoveryRequired -> SecurityUiStateLike.Recovery
    !enabled -> SecurityUiStateLike.Disabled
    else -> SecurityUiStateLike.Enabled
}
