package com.lavasec.app.foundation.biometrics

/**
 * The Android biometric feature flags (`PackageManager.FEATURE_*`) used to name an available
 * authenticator's [BiometricKind]. Android does not expose a reliable `biometryType` equivalent
 * to iOS `LAContext.biometryType`, so the declared features are the classification source.
 */
enum class AndroidBiometricFeature {
    FINGERPRINT,
    FACE,
    IRIS,
}

/**
 * The `android.hardware.biometrics.BiometricManager.canAuthenticate` status codes, mirrored as a
 * pure-JVM enum (foundation has no Android dependency). The [androidValue] pins the platform
 * constant so the adapter's mapping stays exact.
 */
enum class BiometricManagerStatus(val androidValue: Int) {
    BIOMETRIC_SUCCESS(0),
    BIOMETRIC_ERROR_HW_UNAVAILABLE(1),
    BIOMETRIC_ERROR_NONE_ENROLLED(11),
    BIOMETRIC_ERROR_NO_HARDWARE(12),
    BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED(15),
    BIOMETRIC_ERROR_UNSUPPORTED(-2),
    BIOMETRIC_STATUS_UNKNOWN(-1),
}

/**
 * The `android.hardware.biometrics.BiometricPrompt` error codes that carry policy meaning,
 * mirrored as a pure-JVM enum. [ERROR_NONE] is the success sentinel; [ERROR_UNKNOWN] absorbs any
 * code this build does not model.
 */
enum class BiometricPromptError(val androidValue: Int) {
    ERROR_NONE(0),
    ERROR_HW_UNAVAILABLE(1),
    ERROR_UNABLE_TO_PROCESS(2),
    ERROR_TIMEOUT(3),
    ERROR_CANCELED(5),
    ERROR_LOCKOUT(7),
    ERROR_VENDOR(8),
    ERROR_LOCKOUT_PERMANENT(9),
    ERROR_USER_CANCELED(10),
    ERROR_NO_BIOMETRICS(11),
    ERROR_HW_NOT_PRESENT(12),
    ERROR_NEGATIVE_BUTTON(13),
    ERROR_NO_DEVICE_CREDENTIAL(14),
    ERROR_SECURITY_UPDATE_REQUIRED(15),
    ERROR_USER_FALLBACK(16),
    ERROR_UNKNOWN(-1),
}

/**
 * The pure snapshot of the Android capability signals, classified into the canonical
 * [BiometricAvailability]. [keyInvalidated] is set by the adapter when Keystore throws a
 * permanently-invalidated-key error, which is the only signal that must override the manager
 * status and force the passcode path.
 */
data class AndroidBiometricCapability(
    val status: BiometricManagerStatus,
    val features: Set<AndroidBiometricFeature> = emptySet(),
    /** True when only `BIOMETRIC_WEAK` (Class 2) is available and no strong biometric exists. */
    val weakCredentialOnly: Boolean = false,
    val keyInvalidated: Boolean = false,
) {
    /** Classify the raw capability into the canonical availability vocabulary. */
    fun availability(): BiometricAvailability {
        if (keyInvalidated) return BiometricAvailability.KeyInvalidated
        return when (status) {
            BiometricManagerStatus.BIOMETRIC_SUCCESS ->
                BiometricAvailability.Available(kind())

            BiometricManagerStatus.BIOMETRIC_ERROR_NONE_ENROLLED ->
                BiometricAvailability.NotEnrolled

            BiometricManagerStatus.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManagerStatus.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            -> BiometricAvailability.HardwareUnavailable

            BiometricManagerStatus.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManagerStatus.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManagerStatus.BIOMETRIC_STATUS_UNKNOWN,
            -> BiometricAvailability.Unavailable
        }
    }

    private fun kind(): BiometricKind {
        if (weakCredentialOnly) return BiometricKind.WEAK_CREDENTIAL
        return when {
            AndroidBiometricFeature.FACE in features -> BiometricKind.FACE
            AndroidBiometricFeature.IRIS in features -> BiometricKind.IRIS
            AndroidBiometricFeature.FINGERPRINT in features -> BiometricKind.FINGERPRINT
            // No declared feature but the manager reports success: the platform has an
            // unnamed strong authenticator. Classify it conservatively as weak rather than
            // claim a concrete biometric the adapter cannot name.
            else -> BiometricKind.WEAK_CREDENTIAL
        }
    }
}

/**
 * Resolve a raw `android.hardware.biometrics.BiometricManager.canAuthenticate` status int to the
 * modelled enum. A code this build does not model is the honest [BiometricManagerStatus.BIOMETRIC_STATUS_UNKNOWN],
 * never a fabricated success, so the adapter's availability read can stay total.
 */
fun biometricManagerStatusFromAndroidValue(value: Int): BiometricManagerStatus =
    BiometricManagerStatus.entries.firstOrNull { it.androidValue == value }
        ?: BiometricManagerStatus.BIOMETRIC_STATUS_UNKNOWN

/**
 * Resolve a raw `android.hardware.biometrics.BiometricPrompt` error int to the modelled enum.
 * Any unmodelled code is [BiometricPromptError.ERROR_UNKNOWN], which classifies to a recoverable
 * [BiometricPromptResult.Failed] and can never be a success.
 */
fun biometricPromptErrorFromAndroidValue(value: Int): BiometricPromptError =
    BiometricPromptError.entries.firstOrNull { it.androidValue == value }
        ?: BiometricPromptError.ERROR_UNKNOWN

/**
 * True when only a weak (Class 2) authenticator is usable: the weak probe succeeds while the
 * strong probe does not. API levels that cannot distinguish the two pass the same value for both,
 * which yields false (never mislabels a strong authenticator as weak).
 */
fun onlyWeakBiometricAvailable(strongStatusValue: Int, weakStatusValue: Int): Boolean =
    weakStatusValue == BiometricManagerStatus.BIOMETRIC_SUCCESS.androidValue &&
        strongStatusValue != BiometricManagerStatus.BIOMETRIC_SUCCESS.androidValue

/**
 * The pure classification of the platform adapter's raw capability probes into canonical
 * availability, so a status int an adapter does not model degrades to [BiometricAvailability.Unavailable].
 */
fun androidBiometricAvailability(
    statusValue: Int,
    features: Set<AndroidBiometricFeature> = emptySet(),
    weakCredentialOnly: Boolean = false,
    keyInvalidated: Boolean = false,
): BiometricAvailability = AndroidBiometricCapability(
    status = biometricManagerStatusFromAndroidValue(statusValue),
    features = features,
    weakCredentialOnly = weakCredentialOnly,
    keyInvalidated = keyInvalidated,
).availability()

/**
 * Map a completed Android prompt error to the canonical [BiometricPromptResult]. Negative
 * button and explicit fallback are distinct from a hard cancel, and both lockout forms route
 * to the passcode gate.
 */
fun BiometricPromptError.toPromptResult(): BiometricPromptResult = when (this) {
    BiometricPromptError.ERROR_NONE -> BiometricPromptResult.Success
    BiometricPromptError.ERROR_USER_CANCELED,
    BiometricPromptError.ERROR_CANCELED,
    -> BiometricPromptResult.Cancelled

    BiometricPromptError.ERROR_NEGATIVE_BUTTON,
    BiometricPromptError.ERROR_USER_FALLBACK,
    BiometricPromptError.ERROR_NO_DEVICE_CREDENTIAL,
    BiometricPromptError.ERROR_NO_BIOMETRICS,
    -> BiometricPromptResult.FallbackRequested

    BiometricPromptError.ERROR_LOCKOUT -> BiometricPromptResult.Lockout(permanent = false)
    BiometricPromptError.ERROR_LOCKOUT_PERMANENT -> BiometricPromptResult.Lockout(permanent = true)

    BiometricPromptError.ERROR_HW_UNAVAILABLE,
    BiometricPromptError.ERROR_UNABLE_TO_PROCESS,
    BiometricPromptError.ERROR_TIMEOUT,
    BiometricPromptError.ERROR_VENDOR,
    BiometricPromptError.ERROR_HW_NOT_PRESENT,
    BiometricPromptError.ERROR_SECURITY_UPDATE_REQUIRED,
    BiometricPromptError.ERROR_UNKNOWN,
    -> BiometricPromptResult.Failed
}
