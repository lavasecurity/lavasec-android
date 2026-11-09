package com.lavasec.app.foundation.biometrics

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidBiometricCapabilityTest {

    private fun availability(
        status: BiometricManagerStatus,
        features: Set<AndroidBiometricFeature> = emptySet(),
        weakCredentialOnly: Boolean = false,
        keyInvalidated: Boolean = false,
    ): BiometricAvailability = AndroidBiometricCapability(
        status = status,
        features = features,
        weakCredentialOnly = weakCredentialOnly,
        keyInvalidated = keyInvalidated,
    ).availability()

    @Test
    fun `success classifies each declared feature to its concrete kind`() {
        assertEquals(
            BiometricAvailability.Available(BiometricKind.FINGERPRINT),
            availability(BiometricManagerStatus.BIOMETRIC_SUCCESS, setOf(AndroidBiometricFeature.FINGERPRINT)),
        )
        assertEquals(
            BiometricAvailability.Available(BiometricKind.FACE),
            availability(BiometricManagerStatus.BIOMETRIC_SUCCESS, setOf(AndroidBiometricFeature.FACE)),
        )
        assertEquals(
            BiometricAvailability.Available(BiometricKind.IRIS),
            availability(BiometricManagerStatus.BIOMETRIC_SUCCESS, setOf(AndroidBiometricFeature.IRIS)),
        )
    }

    @Test
    fun `a weak-only authenticator is classified explicitly`() {
        assertEquals(
            BiometricAvailability.Available(BiometricKind.WEAK_CREDENTIAL),
            availability(
                BiometricManagerStatus.BIOMETRIC_SUCCESS,
                setOf(AndroidBiometricFeature.FINGERPRINT),
                weakCredentialOnly = true,
            ),
        )
        assertEquals(
            BiometricAvailability.Available(BiometricKind.WEAK_CREDENTIAL),
            availability(BiometricManagerStatus.BIOMETRIC_SUCCESS),
        )
    }

    @Test
    fun `feature precedence is face then iris then fingerprint`() {
        assertEquals(
            BiometricAvailability.Available(BiometricKind.FACE),
            availability(
                BiometricManagerStatus.BIOMETRIC_SUCCESS,
                setOf(AndroidBiometricFeature.FINGERPRINT, AndroidBiometricFeature.FACE),
            ),
        )
        assertEquals(
            BiometricAvailability.Available(BiometricKind.IRIS),
            availability(
                BiometricManagerStatus.BIOMETRIC_SUCCESS,
                setOf(AndroidBiometricFeature.FINGERPRINT, AndroidBiometricFeature.IRIS),
            ),
        )
    }

    @Test
    fun `manager statuses map to the canonical availability states`() {
        assertEquals(
            BiometricAvailability.NotEnrolled,
            availability(BiometricManagerStatus.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertEquals(
            BiometricAvailability.HardwareUnavailable,
            availability(BiometricManagerStatus.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
        assertEquals(
            BiometricAvailability.HardwareUnavailable,
            availability(BiometricManagerStatus.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
        )
        assertEquals(
            BiometricAvailability.Unavailable,
            availability(BiometricManagerStatus.BIOMETRIC_ERROR_NO_HARDWARE),
        )
        assertEquals(
            BiometricAvailability.Unavailable,
            availability(BiometricManagerStatus.BIOMETRIC_ERROR_UNSUPPORTED),
        )
        assertEquals(
            BiometricAvailability.Unavailable,
            availability(BiometricManagerStatus.BIOMETRIC_STATUS_UNKNOWN),
        )
    }

    @Test
    fun `a permanently invalidated key overrides the manager status`() {
        assertEquals(
            BiometricAvailability.KeyInvalidated,
            availability(BiometricManagerStatus.BIOMETRIC_SUCCESS, keyInvalidated = true),
        )
        assertEquals(
            BiometricAvailability.KeyInvalidated,
            availability(BiometricManagerStatus.BIOMETRIC_ERROR_HW_UNAVAILABLE, keyInvalidated = true),
        )
    }

    @Test
    fun `manager status values pin the platform constants`() {
        assertEquals(0, BiometricManagerStatus.BIOMETRIC_SUCCESS.androidValue)
        assertEquals(1, BiometricManagerStatus.BIOMETRIC_ERROR_HW_UNAVAILABLE.androidValue)
        assertEquals(11, BiometricManagerStatus.BIOMETRIC_ERROR_NONE_ENROLLED.androidValue)
        assertEquals(12, BiometricManagerStatus.BIOMETRIC_ERROR_NO_HARDWARE.androidValue)
        assertEquals(15, BiometricManagerStatus.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED.androidValue)
        assertEquals(-2, BiometricManagerStatus.BIOMETRIC_ERROR_UNSUPPORTED.androidValue)
        assertEquals(-1, BiometricManagerStatus.BIOMETRIC_STATUS_UNKNOWN.androidValue)
    }

    @Test
    fun `prompt errors map to the typed result vocabulary`() {
        assertEquals(BiometricPromptResult.Success, BiometricPromptError.ERROR_NONE.toPromptResult())
        assertEquals(BiometricPromptResult.Cancelled, BiometricPromptError.ERROR_USER_CANCELED.toPromptResult())
        assertEquals(BiometricPromptResult.Cancelled, BiometricPromptError.ERROR_CANCELED.toPromptResult())
        assertEquals(BiometricPromptResult.FallbackRequested, BiometricPromptError.ERROR_NEGATIVE_BUTTON.toPromptResult())
        assertEquals(BiometricPromptResult.FallbackRequested, BiometricPromptError.ERROR_USER_FALLBACK.toPromptResult())
        assertEquals(BiometricPromptResult.FallbackRequested, BiometricPromptError.ERROR_NO_DEVICE_CREDENTIAL.toPromptResult())
        assertEquals(BiometricPromptResult.FallbackRequested, BiometricPromptError.ERROR_NO_BIOMETRICS.toPromptResult())
        assertEquals(BiometricPromptResult.Lockout(permanent = false), BiometricPromptError.ERROR_LOCKOUT.toPromptResult())
        assertEquals(BiometricPromptResult.Lockout(permanent = true), BiometricPromptError.ERROR_LOCKOUT_PERMANENT.toPromptResult())
        assertEquals(BiometricPromptResult.Failed, BiometricPromptError.ERROR_HW_UNAVAILABLE.toPromptResult())
        assertEquals(BiometricPromptResult.Failed, BiometricPromptError.ERROR_SECURITY_UPDATE_REQUIRED.toPromptResult())
        assertEquals(BiometricPromptResult.Failed, BiometricPromptError.ERROR_UNKNOWN.toPromptResult())
    }

    @Test
    fun `prompt error values pin the platform constants`() {
        assertEquals(0, BiometricPromptError.ERROR_NONE.androidValue)
        assertEquals(1, BiometricPromptError.ERROR_HW_UNAVAILABLE.androidValue)
        assertEquals(7, BiometricPromptError.ERROR_LOCKOUT.androidValue)
        assertEquals(9, BiometricPromptError.ERROR_LOCKOUT_PERMANENT.androidValue)
        assertEquals(10, BiometricPromptError.ERROR_USER_CANCELED.androidValue)
        assertEquals(13, BiometricPromptError.ERROR_NEGATIVE_BUTTON.androidValue)
        assertEquals(16, BiometricPromptError.ERROR_USER_FALLBACK.androidValue)
        assertEquals(-1, BiometricPromptError.ERROR_UNKNOWN.androidValue)
    }

    @Test
    fun `raw manager status codes resolve or degrade to unknown`() {
        assertEquals(
            BiometricManagerStatus.BIOMETRIC_SUCCESS,
            biometricManagerStatusFromAndroidValue(0),
        )
        assertEquals(
            BiometricManagerStatus.BIOMETRIC_ERROR_NONE_ENROLLED,
            biometricManagerStatusFromAndroidValue(11),
        )
        assertEquals(
            BiometricManagerStatus.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            biometricManagerStatusFromAndroidValue(15),
        )
        // A code this build does not model is honest unknown, never a fabricated success.
        assertEquals(
            BiometricManagerStatus.BIOMETRIC_STATUS_UNKNOWN,
            biometricManagerStatusFromAndroidValue(999),
        )
        assertEquals(
            BiometricManagerStatus.BIOMETRIC_STATUS_UNKNOWN,
            biometricManagerStatusFromAndroidValue(-7),
        )
    }

    @Test
    fun `raw prompt error codes resolve or degrade to unknown`() {
        assertEquals(BiometricPromptError.ERROR_NONE, biometricPromptErrorFromAndroidValue(0))
        assertEquals(
            BiometricPromptError.ERROR_LOCKOUT_PERMANENT,
            biometricPromptErrorFromAndroidValue(9),
        )
        assertEquals(
            BiometricPromptError.ERROR_NEGATIVE_BUTTON,
            biometricPromptErrorFromAndroidValue(13),
        )
        // An unmodelled code is recoverable failure, never success.
        assertEquals(BiometricPromptError.ERROR_UNKNOWN, biometricPromptErrorFromAndroidValue(4242))
        assertEquals(BiometricPromptResult.Failed, biometricPromptErrorFromAndroidValue(4242).toPromptResult())
    }

    @Test
    fun `only weak requires weak success and strong non-success`() {
        assertEquals(true, onlyWeakBiometricAvailable(strongStatusValue = 11, weakStatusValue = 0))
        assertEquals(true, onlyWeakBiometricAvailable(strongStatusValue = -2, weakStatusValue = 0))
        // Strong success means not weak-only even if weak succeeds.
        assertEquals(false, onlyWeakBiometricAvailable(strongStatusValue = 0, weakStatusValue = 0))
        // Weak failure is never weak-only.
        assertEquals(false, onlyWeakBiometricAvailable(strongStatusValue = 11, weakStatusValue = 11))
        assertEquals(false, onlyWeakBiometricAvailable(strongStatusValue = 1, weakStatusValue = 12))
    }

    @Test
    fun `pure availability classifies weak only and unknown statuses`() {
        assertEquals(
            BiometricAvailability.Available(BiometricKind.WEAK_CREDENTIAL),
            androidBiometricAvailability(
                statusValue = 0,
                features = setOf(AndroidBiometricFeature.FINGERPRINT),
                weakCredentialOnly = true,
            ),
        )
        assertEquals(
            BiometricAvailability.Available(BiometricKind.FINGERPRINT),
            androidBiometricAvailability(
                statusValue = 0,
                features = setOf(AndroidBiometricFeature.FINGERPRINT),
            ),
        )
        // An unmodelled status must not become available.
        assertEquals(
            BiometricAvailability.Unavailable,
            androidBiometricAvailability(statusValue = 777),
        )
        assertEquals(
            BiometricAvailability.KeyInvalidated,
            androidBiometricAvailability(statusValue = 0, keyInvalidated = true),
        )
    }
}
