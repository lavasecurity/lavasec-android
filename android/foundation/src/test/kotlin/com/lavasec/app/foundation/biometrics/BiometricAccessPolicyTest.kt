package com.lavasec.app.foundation.biometrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricAccessPolicyTest {

    private val fingerprint = BiometricAvailability.Available(BiometricKind.FINGERPRINT)
    private val face = BiometricAvailability.Available(BiometricKind.FACE)
    private val weak = BiometricAvailability.Available(BiometricKind.WEAK_CREDENTIAL)
    private val settings = BiometricSettings(passcodeEnabled = true, biometricEnabled = true)

    @Test
    fun `lockout always falls back to the passcode`() {
        for (availability in listOf(fingerprint, face, weak)) {
            assertEquals(
                BiometricAccessDecision.FALLBACK_TO_PASSCODE,
                BiometricAccessPolicy.decisionFor(BiometricPromptResult.Lockout(permanent = false), availability),
            )
            assertEquals(
                BiometricAccessDecision.FALLBACK_TO_PASSCODE,
                BiometricAccessPolicy.decisionFor(BiometricPromptResult.Lockout(permanent = true), availability),
            )
        }
    }

    @Test
    fun `cancellation is never success`() {
        val decision = BiometricAccessPolicy.decisionFor(BiometricPromptResult.Cancelled, fingerprint)
        assertEquals(BiometricAccessDecision.DENIED, decision)
        assertFalse(decision == BiometricAccessDecision.AUTHENTICATED)
    }

    @Test
    fun `a success result without a usable key is never authentication`() {
        for (availability in listOf(
            BiometricAvailability.NotEnrolled,
            BiometricAvailability.HardwareUnavailable,
            BiometricAvailability.KeyInvalidated,
            BiometricAvailability.Unavailable,
        )) {
            assertEquals(
                "success must not authenticate against $availability",
                BiometricAccessDecision.UNAVAILABLE,
                BiometricAccessPolicy.decisionFor(BiometricPromptResult.Success, availability),
            )
        }
    }

    @Test
    fun `an invalidated key is unavailable and can never be offered`() {
        val invalidated = BiometricAvailability.KeyInvalidated
        assertFalse(BiometricAccessPolicy.effectiveEnabled(settings, invalidated))
        assertEquals(
            BiometricPresentation.FallbackToPasscode,
            BiometricAccessPolicy.presentation(settings, invalidated),
        )
        assertEquals(
            BiometricAccessDecision.UNAVAILABLE,
            BiometricAccessPolicy.decisionFor(BiometricPromptResult.Success, invalidated),
        )
    }

    @Test
    fun `success against a usable key authenticates`() {
        assertEquals(
            BiometricAccessDecision.AUTHENTICATED,
            BiometricAccessPolicy.decisionFor(BiometricPromptResult.Success, fingerprint),
        )
    }

    @Test
    fun `fallback, failure and disabled-authentication all route to the passcode`() {
        for (result in listOf(
            BiometricPromptResult.FallbackRequested,
            BiometricPromptResult.Failed,
            BiometricPromptResult.AuthenticationDisabled,
        )) {
            assertEquals(
                BiometricAccessDecision.FALLBACK_TO_PASSCODE,
                BiometricAccessPolicy.decisionFor(result, fingerprint),
            )
        }
    }

    @Test
    fun `turning biometrics off keeps the passcode gate`() {
        val disabled = BiometricAccessPolicy.setBiometricEnabled(settings, enabled = false, availability = fingerprint)
        assertFalse(disabled.biometricEnabled)
        assertTrue(disabled.passcodeEnabled)
        assertTrue(disabled.hasAuthenticationMethod)
        // The canonical gate is now the passcode, never "no authentication".
        assertEquals(
            BiometricPresentation.FallbackToPasscode,
            BiometricAccessPolicy.presentation(disabled, fingerprint),
        )
    }

    @Test
    fun `enabling biometrics requires a passcode and a usable key`() {
        val noPasscode = BiometricSettings(passcodeEnabled = false, biometricEnabled = false)
        assertEquals(
            BiometricSettings(passcodeEnabled = false, biometricEnabled = false),
            BiometricAccessPolicy.setBiometricEnabled(noPasscode, enabled = true, availability = fingerprint),
        )

        val notEnrolled = BiometricSettings(passcodeEnabled = true, biometricEnabled = false)
        assertEquals(
            BiometricSettings(passcodeEnabled = true, biometricEnabled = false),
            BiometricAccessPolicy.setBiometricEnabled(notEnrolled, enabled = true, availability = BiometricAvailability.NotEnrolled),
        )

        val withoutUsage = BiometricSettings(passcodeEnabled = true, biometricEnabled = false)
        assertEquals(
            BiometricSettings(passcodeEnabled = true, biometricEnabled = false),
            BiometricAccessPolicy.setBiometricEnabled(
                withoutUsage,
                enabled = true,
                availability = face,
                usageDescriptionPresent = false,
            ),
        )

        assertEquals(
            BiometricSettings(passcodeEnabled = true, biometricEnabled = true),
            BiometricAccessPolicy.setBiometricEnabled(notEnrolled, enabled = true, availability = fingerprint),
        )
    }

    @Test
    fun `only confirmed passcode removal clears the biometric preference`() {
        assertEquals(
            BiometricSettings(passcodeEnabled = false, biometricEnabled = false),
            BiometricAccessPolicy.onPasscodeRemoved(),
        )
    }

    @Test
    fun `a persisted preference is inert without a usable key`() {
        assertFalse(
            BiometricAccessPolicy.effectiveEnabled(settings, BiometricAvailability.NotEnrolled),
        )
        assertFalse(
            BiometricAccessPolicy.effectiveEnabled(settings, BiometricAvailability.KeyInvalidated),
        )
        assertTrue(BiometricAccessPolicy.effectiveEnabled(settings, fingerprint))
    }

    @Test
    fun `canEnableBiometrics mirrors passcode plus usable key plus usage string`() {
        assertTrue(BiometricAccessPolicy.canEnable(BiometricSettings(passcodeEnabled = true), fingerprint))
        assertFalse(BiometricAccessPolicy.canEnable(BiometricSettings(passcodeEnabled = false), fingerprint))
        assertFalse(BiometricAccessPolicy.canEnable(BiometricSettings(passcodeEnabled = true), BiometricAvailability.NotEnrolled))
        assertFalse(
            BiometricAccessPolicy.canEnable(BiometricSettings(passcodeEnabled = true), face, usageDescriptionPresent = false),
        )
    }

    @Test
    fun `a weak credential is offered as its own kind`() {
        assertEquals(
            BiometricPresentation.Offer(BiometricKind.WEAK_CREDENTIAL),
            BiometricAccessPolicy.presentation(settings, weak),
        )
    }

    @Test
    fun `the toggle is shown for detected hardware but not absence`() {
        assertTrue(BiometricAccessPolicy.shouldShowToggle(fingerprint))
        assertTrue(BiometricAccessPolicy.shouldShowToggle(BiometricAvailability.NotEnrolled))
        assertTrue(BiometricAccessPolicy.shouldShowToggle(BiometricAvailability.HardwareUnavailable))
        assertTrue(BiometricAccessPolicy.shouldShowToggle(BiometricAvailability.KeyInvalidated))
        assertFalse(BiometricAccessPolicy.shouldShowToggle(BiometricAvailability.Unavailable))
    }

    @Test
    fun `presentation is unavailable without a passcode and offers a usable biometric`() {
        assertEquals(
            BiometricPresentation.Unavailable,
            BiometricAccessPolicy.presentation(BiometricSettings(), fingerprint),
        )
        assertEquals(
            BiometricPresentation.Offer(BiometricKind.FACE),
            BiometricAccessPolicy.presentation(settings, face),
        )
    }
}
