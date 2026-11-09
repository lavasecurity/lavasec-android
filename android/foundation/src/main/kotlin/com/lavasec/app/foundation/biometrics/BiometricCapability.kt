package com.lavasec.app.foundation.biometrics

/**
 * The biometric authenticator an available platform capability maps to.
 *
 * Ports the canonical `SecurityBiometricKind` (`LavaSecApp/SecurityController.swift`, pinned
 * iOS `c4658d9`) and extends it with the Android authenticator classes: `fingerprint`/`face`
 * mirror iOS `touchID`/`faceID`, and `iris`/`weakCredential` are Android-only concrete kinds.
 */
enum class BiometricKind {
    FINGERPRINT,
    FACE,
    IRIS,

    /**
     * Android `BiometricManager.Authenticators.BIOMETRIC_WEAK` (Class 2) with no strong
     * (Class 3) biometric available. Classified explicitly so a policy can refuse to treat it
     * as a strong biometric even though the platform reports an available authenticator.
     */
    WEAK_CREDENTIAL,
}

/**
 * The canonical biometric availability vocabulary.
 *
 * Distinguishes hardware absence from temporary hardware unavailability, missing enrollment,
 * and a permanently invalidated key. Only [Available] means the platform can currently evaluate
 * biometrics; every other state must resolve to the passcode path and can never produce a
 * successful authentication.
 */
sealed interface BiometricAvailability {
    /** An enrolled biometric the platform can evaluate now, with the concrete [kind]. */
    data class Available(val kind: BiometricKind) : BiometricAvailability

    /** Hardware exists but nothing is enrolled; the setting toggle is shown, enabling is refused. */
    data object NotEnrolled : BiometricAvailability

    /** Hardware is temporarily unavailable (in use, locked by too many attempts, security patch). */
    data object HardwareUnavailable : BiometricAvailability

    /** An enrolled biometric key was permanently invalidated (re-enrollment / biometric change). */
    data object KeyInvalidated : BiometricAvailability

    /** No usable biometric authenticator exists (absent, unsupported, or unknown status). */
    data object Unavailable : BiometricAvailability

    /** True only for an enrolled biometric the platform can currently evaluate. */
    val isUsable: Boolean
        get() = this is Available
}
