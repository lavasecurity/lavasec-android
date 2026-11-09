package com.lavasec.app.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.biometrics.BiometricAvailability
import com.lavasec.app.foundation.biometrics.BiometricKind
import com.lavasec.app.foundation.biometrics.BiometricPromptResult
import com.lavasec.app.foundation.security.ProtectedSurface
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production owner/preferences/verifier and actual AndroidKeyStore, with isolated files. */
@RunWith(AndroidJUnit4::class)
class SecurityKeystoreJourneyInstrumentationTest {
    @Test
    fun passcodeChangeAuthenticationCancellationAndReopenUseTheRealVerifier() = runBlocking {
        Fixture().use { fixture ->
            val owner = fixture.owner()
            assertEquals(SecurityAvailability.Available, owner.state.value.availability)
            assertTrue(owner.setPasscode("1234"))
            assertTrue(owner.state.value.enabled)
            assertTrue(owner.setProtectedSurface(ProtectedSurface.FILTERS, true))
            assertFalse(owner.state.value.isAuthorized(ProtectedSurface.FILTERS))

            assertTrue(owner.changePasscode("incorrect", "5678"))
            assertEquals(SecurityFailure.WRONG_PASSCODE, owner.state.value.failure)
            assertEquals(PasscodeVerification.Verified, fixture.verifier().verify("1234"))
            assertTrue(owner.changePasscode("1234", "5678"))
            assertNotNull(owner.state.value.lastChangeEpochMillis)
            assertEquals(PasscodeVerification.Wrong, fixture.verifier().verify("1234"))
            assertEquals(PasscodeVerification.Verified, fixture.verifier().verify("5678"))

            var cancelledRequest: SecurityAuthenticationRequest? = null
            val dismissed = owner.requireAuthenticationOutcome(ProtectedSurface.FILTERS) { request ->
                cancelledRequest = request
                owner.cancelAuthentication(request.id)
            }
            assertEquals(SecurityAuthorization.DENIED, dismissed)
            assertFalse(owner.state.value.isAuthorized(ProtectedSurface.FILTERS))
            assertFalse(owner.completeAuthentication(checkNotNull(cancelledRequest).id, "5678"))

            assertEquals(SecurityAuthorization.AUTHORIZED,
                owner.requireAuthenticationOutcome(ProtectedSurface.FILTERS) { request ->
                    assertFalse(owner.completeAuthentication(request.id, "bad"))
                    assertEquals(SecurityFailure.WRONG_PASSCODE, owner.state.value.failure)
                    assertTrue(owner.completeAuthentication(request.id, "5678"))
                })
            assertTrue(owner.state.value.isAuthorized(ProtectedSurface.FILTERS))
            assertNotNull(SecurityPreferenceStore(fixture.context).load().lastUnlockEpochMillis)
            owner.close()

            // A new production owner must read real persisted choices, but an old timestamp never
            // carries the previous process/navigation authorization into this owner.
            val reopened = fixture.owner()
            assertTrue(reopened.state.value.enabled)
            assertEquals(setOf(ProtectedSurface.FILTERS), reopened.state.value.protectedSurfaces)
            assertFalse(reopened.state.value.isAuthorized(ProtectedSurface.FILTERS))
            assertTrue(reopened.state.value.authenticatedSurfaces.isEmpty())
            assertFalse(reopened.state.value.credentialsAuthenticated)
            assertTrue(reopened.removePasscode("5678"))
            assertFalse(reopened.state.value.enabled)
            assertEquals(PasscodeCredentialState.Absent, fixture.verifier().credentialState())
            assertFalse(fixture.keyStore().containsAlias(fixture.alias))
            assertFalse(fixture.owner().state.value.enabled)
        }
    }

    @Test
    fun retiringAuthenticationTurnRejectsLatePasscodeAndFreshRequestCanAuthenticate() = runBlocking {
        Fixture().use { fixture ->
            val owner = fixture.owner()
            assertTrue(owner.setPasscode("1234"))
            assertTrue(owner.setProtectedSurface(ProtectedSurface.PROTECTION_CONTROL, true))
            val shown = CompletableDeferred<SecurityAuthenticationRequest>()
            val release = CompletableDeferred<Unit>()
            val result = async {
                owner.requireAuthenticationOutcome(ProtectedSurface.PROTECTION_CONTROL) { request ->
                    shown.complete(request)
                    release.await()
                }
            }
            val retired = withTimeout(5_000L) { shown.await() }
            owner.resetAuthenticationTurn()
            assertFalse(owner.completeAuthentication(retired.id, "1234"))
            release.complete(Unit)
            assertEquals(SecurityAuthorization.DENIED, withTimeout(5_000L) { result.await() })
            assertFalse(owner.state.value.isAuthorized(ProtectedSurface.PROTECTION_CONTROL))
            assertEquals(SecurityAuthorization.AUTHORIZED,
                owner.requireAuthenticationOutcome(ProtectedSurface.PROTECTION_CONTROL) { request ->
                    assertTrue(owner.completeAuthentication(request.id, "1234"))
                })
            assertTrue(owner.state.value.isAuthorized(ProtectedSurface.PROTECTION_CONTROL))
        }
    }

    @Test
    fun missingKeystoreKeyAndTamperedCiphertextRequireExplicitRecovery() = runBlocking {
        Fixture().use { fixture ->
            val owner = fixture.owner()
            assertTrue(owner.setPasscode("1234"))
            assertTrue(owner.setProtectedSurface(ProtectedSurface.APP_SETTINGS, true))
            val aes = fixture.keyStore().getKey(fixture.alias, null)
            assertNotNull(aes)
            assertNull("Keystore wrapping key must not be exportable", aes.encoded)
            val record = fixture.context.getSharedPreferences("lavasec_security_keystore", Context.MODE_PRIVATE)
            assertEquals(setOf("passcode_salt", "passcode_iv", "passcode_ciphertext"), record.all.keys)
            assertFalse("Raw passcode must not be persisted", record.all.values.any { it == "1234" })
            val encoded = checkNotNull(record.getString("passcode_ciphertext", null))
            val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            assertTrue(record.edit().putString("passcode_ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit())
            owner.close()

            assertEquals(PasscodeCredentialState.Unavailable, fixture.verifier().credentialState())
            assertEquals(PasscodeVerification.Unavailable, fixture.verifier().verify("1234"))
            val damaged = fixture.owner()
            assertTrue(damaged.state.value.recoveryRequired)
            assertFalse(damaged.state.value.isAuthorized(ProtectedSurface.APP_SETTINGS))
            assertEquals(SecurityAuthorization.DENIED,
                damaged.requireAuthenticationOutcome(ProtectedSurface.APP_SETTINGS) { error("Must not present against damaged custody") })
            fixture.keyStore().deleteEntry(fixture.alias)
            assertEquals(PasscodeCredentialState.Unavailable, fixture.verifier().credentialState())
            assertTrue(damaged.resetAfterRecoveryFailure())
            assertFalse(damaged.state.value.recoveryRequired)
            assertFalse(damaged.state.value.enabled)
            assertEquals(PasscodeCredentialState.Absent, fixture.verifier().credentialState())
            assertTrue(record.all.isEmpty())
            assertFalse(fixture.owner().state.value.enabled)
        }
    }

    @Test
    fun controlledBiometricCancellationNeverFallsThroughOrGrantsAProtectedAction() = runBlocking {
        Fixture().use { fixture ->
            // The biometric ceremony is the controlled port here. This proves the real owner's
            // cancellation policy, while physical sensor/enrollment qualification stays explicit.
            val biometric = object : BiometricAuthenticatorPort {
                override fun availability() = BiometricAvailability.Available(BiometricKind.FINGERPRINT)
                override fun isLockedOut() = false
                override suspend fun authenticate(purpose: String) = BiometricPromptResult.Cancelled
                override fun cancel() = Unit
            }
            val owner = fixture.owner(biometric)
            assertTrue(owner.setPasscode("1234"))
            assertTrue(owner.setProtectedSurface(ProtectedSurface.PROTECTION_PAUSE, true))
            assertTrue(owner.setBiometrics(true))
            assertEquals(SecurityAuthorization.CANCELLED,
                owner.requireAuthenticationOutcome(ProtectedSurface.PROTECTION_PAUSE) { error("Biometric cancel must stop presentation") })
            assertFalse(owner.state.value.isAuthorized(ProtectedSurface.PROTECTION_PAUSE))
            assertTrue(owner.state.value.authenticatedSurfaces.isEmpty())
            assertEquals(PasscodeVerification.Verified, fixture.verifier().verify("1234"))
        }
    }

    private class Fixture : AutoCloseable {
        private val namespace = "security-journey-${UUID.randomUUID()}"
        val alias = "$namespace.verifier"
        val context = IsolatedPreferencesContext(InstrumentationRegistry.getInstrumentation().targetContext, namespace)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val owners = mutableListOf<SecurityOwner>()
        fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun verifier() = AndroidKeystorePasscodeVerifier(context, keyAlias = alias)
        fun owner(biometrics: BiometricAuthenticatorPort = UnavailableBiometricAuthenticatorPort) =
            SecurityOwner(verifier(), SecurityPreferenceStore(context), scope,
                biometricAuthenticator = biometrics).also(owners::add)
        override fun close() {
            owners.forEach(SecurityOwner::close)
            scope.cancel()
            keyStore().deleteEntry(alias)
            keyStore().deleteEntry("$alias.availability-probe")
            context.clear()
        }
    }

    private class IsolatedPreferencesContext(base: Context, private val namespace: String) : ContextWrapper(base) {
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$namespace.$name"
            synchronized(names) { names.add(isolatedName) }
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun clear() { synchronized(names) { names.toList() }.forEach { baseContext.deleteSharedPreferences(it) } }
    }
}
