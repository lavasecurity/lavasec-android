package com.lavasec.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Keystore degradation contract: on a JVM (Robolectric) host the
 * AndroidKeyStore provider does not exist, so every operation must degrade to
 * the honest unavailable outcomes instead of throwing. On-device the same
 * fences cover devices without usable secure hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class AndroidKeystorePasscodeVerifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every operation degrades to an honest outcome instead of throwing`() {
        val verifier = AndroidKeystorePasscodeVerifier(context)

        val availability = verifier.availability()
        assertTrue(
            "availability must be a valid enum value",
            availability in PasscodeVerifierAvailability.entries,
        )
        // Configure/verify either work end-to-end or degrade honestly.
        val configured = verifier.configure("1234")
        val verification = verifier.verify("1234")
        if (configured) {
            assertEquals(PasscodeVerification.Verified, verification)
            assertEquals(PasscodeCredentialState.Available, verifier.credentialState())
        } else {
            assertTrue(
                "verification must degrade honestly without a usable keystore",
                verification == PasscodeVerification.Unavailable,
            )
            assertTrue(verifier.credentialState() != PasscodeCredentialState.Available)
        }
        verifier.remove()
        assertTrue(verifier.credentialState() != PasscodeCredentialState.Available)
    }
    @Test
    fun `complete authenticated records read available and partial or corrupt records never read absent`() {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
        }
        val verifier = AndroidKeystorePasscodeVerifier(context) { keyStore }
        val preferences = context.getSharedPreferences("lavasec_security_keystore", Context.MODE_PRIVATE)
        assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
        keyStore.setKeyEntry("lavasec_passcode_verifier", SecretKeySpec(ByteArray(32) { 7 }, "AES"), null, null)
        assertEquals(PasscodeCredentialState.Unavailable, verifier.credentialState())
        assertTrue(verifier.configure("1234"))
        assertEquals(PasscodeCredentialState.Available, verifier.credentialState())
        assertEquals(PasscodeVerification.Verified, verifier.verify("1234"))
        assertEquals(PasscodeVerification.Wrong, verifier.verify("4321"))
        val intact = preferences.all.mapValues { it.value as String }
        for (field in intact.keys) {
            preferences.edit().remove(field).commit()
            assertEquals(PasscodeCredentialState.Unavailable, verifier.credentialState())
            assertEquals(PasscodeVerification.Unavailable, verifier.verify("1234"))
            preferences.edit().putString(field, intact.getValue(field)).commit()
        }
        for (invalid in listOf("!invalid", "A".repeat(129), "AA==")) {
            preferences.edit().putString("passcode_ciphertext", invalid).commit()
            assertEquals(PasscodeCredentialState.Unavailable, verifier.credentialState())
        }
        preferences.edit().putString("passcode_ciphertext", intact.getValue("passcode_ciphertext")).commit()
        keyStore.deleteEntry("lavasec_passcode_verifier")
        assertEquals(PasscodeCredentialState.Unavailable, verifier.credentialState())
        assertTrue(verifier.remove())
        assertEquals(PasscodeCredentialState.Absent, verifier.credentialState())
    }

    @Test
    fun `unavailable keystore cannot falsely confirm removal or erase the stored record`() {
        val preferences = context.getSharedPreferences("lavasec_security_keystore", Context.MODE_PRIVATE)
        preferences.edit().putString("passcode_ciphertext", "opaque").commit()
        val verifier = AndroidKeystorePasscodeVerifier(context) { null }
        assertFalse(verifier.remove())
        assertTrue(preferences.contains("passcode_ciphertext"))
        assertEquals(PasscodeCredentialState.Unavailable, verifier.credentialState())
    }

}
