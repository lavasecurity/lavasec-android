package com.lavasec.app.foundation.biometrics

/**
 * The typed result of a completed biometric prompt.
 *
 * This is the pure vocabulary a platform `BiometricPrompt` callback resolves to. It mirrors the
 * iOS `evaluatePolicy` Boolean plus the Android `BiometricPrompt.ERROR_*` cases that carry policy
 * meaning, so the access decision never depends on a raw platform status code.
 */
sealed interface BiometricPromptResult {
    /** The user authenticated with a currently usable biometric. */
    data object Success : BiometricPromptResult

    /** The user dismissed the prompt (hard cancel), not a fallback request. */
    data object Cancelled : BiometricPromptResult

    /**
     * Biometrics are locked out after too many failed attempts. [permanent] is Android's
     * `ERROR_LOCKOUT_PERMANENT`; both forms must fall back to the passcode gate.
     */
    data class Lockout(val permanent: Boolean) : BiometricPromptResult

    /** The user (or the platform) asked for the passcode / device credential instead. */
    data object FallbackRequested : BiometricPromptResult

    /**
     * Biometric authentication is disabled for Lava (the user turned it off, or no passcode
     * backs it). The passcode remains the gate.
     */
    data object AuthenticationDisabled : BiometricPromptResult

    /** A recoverable evaluation failure (hardware busy, timeout, vendor error). Fall back. */
    data object Failed : BiometricPromptResult
}

/** The access decision derived from a prompt result; only [AUTHENTICATED] grants access. */
enum class BiometricAccessDecision {
    AUTHENTICATED,
    DENIED,
    FALLBACK_TO_PASSCODE,
    UNAVAILABLE,
}

/**
 * The durable authentication settings. Mirrors the canonical iOS `isPasscodeEnabled` /
 * `isBiometricEnabled` pair, where the biometric preference is only effective while the
 * passcode exists (`isBiometricEnabled = defaults.bool && isPasscodeEnabled`).
 */
data class BiometricSettings(
    val passcodeEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
) {
    val hasAuthenticationMethod: Boolean
        get() = passcodeEnabled || biometricEnabled
}

/** What the UI should present before any prompt runs. */
sealed interface BiometricPresentation {
    /** Offer the biometric prompt for the given [kind]. */
    data class Offer(val kind: BiometricKind) : BiometricPresentation

    /** Biometrics cannot be used now; the passcode gate remains and must be presented. */
    data object FallbackToPasscode : BiometricPresentation

    /** No authentication method is configured, so the surface is not gated (canonical iOS `true`). */
    data object Unavailable : BiometricPresentation
}

/**
 * The pure biometric decision policy.
 *
 * Ported from `SecurityController` (`LavaSecApp/SecurityController.swift`, pinned iOS
 * `c4658d9`): `shouldShowBiometricToggle`, `canEnableBiometrics`,
 * `setBiometricEnabled`/`disablePasscode`, and `authenticate`'s
 * biometric-then-passcode flow.
 *
 * Two invariants are load-bearing and pinned by tests:
 *  - A missing, unenrolled, or invalidated biometric key never counts as success: a
 *    [BiometricPromptResult.Success] evaluated against a non-[BiometricAvailability.Available]
 *    state is [BiometricAccessDecision.UNAVAILABLE], never [BiometricAccessDecision.AUTHENTICATED].
 *  - Disabling biometrics never disables the passcode gate; only confirmed passcode removal
 *    clears both, mirroring
 *    `testDisablingBiometricsKeepsPasscodeProtectedSurfacesAndAppLock`.
 */
object BiometricAccessPolicy {

    /** The canonical `shouldShowBiometricToggle`: any detected hardware shows the toggle. */
    fun shouldShowToggle(availability: BiometricAvailability): Boolean = when (availability) {
        is BiometricAvailability.Available,
        BiometricAvailability.NotEnrolled,
        BiometricAvailability.HardwareUnavailable,
        BiometricAvailability.KeyInvalidated,
        -> true

        BiometricAvailability.Unavailable -> false
    }

    /**
     * The canonical `canEnableBiometrics`: a passcode must exist, the biometric must be enrolled
     * and currently evaluable, and the build must declare its usage string. On iOS the usage
     * check is `faceIDUsageDescriptionIsPresent`; Android always passes [usageDescriptionPresent]
     * because the prompt string is a manifest/activity constant.
     */
    fun canEnable(
        settings: BiometricSettings,
        availability: BiometricAvailability,
        usageDescriptionPresent: Boolean = true,
    ): Boolean =
        settings.passcodeEnabled &&
            availability is BiometricAvailability.Available &&
            usageDescriptionPresent

    /**
     * Whether the stored biometric preference is actually in force right now. A persisted `true`
     * is inert without a passcode or an enrolled biometric, matching iOS `refreshBiometricKind`
     * clearing `isBiometricEnabled` when `canEvaluatePolicy` is false.
     */
    fun effectiveEnabled(
        settings: BiometricSettings,
        availability: BiometricAvailability,
    ): Boolean =
        settings.passcodeEnabled &&
            settings.biometricEnabled &&
            availability is BiometricAvailability.Available

    /**
     * Applies a biometric toggle request. Enabling is refused (persisted off) unless [canEnable];
     * disabling always succeeds and leaves the passcode untouched. Mirrors canonical
     * `setBiometricEnabled`, which never clears protected surfaces and never unlocks app-lock.
     */
    fun setBiometricEnabled(
        settings: BiometricSettings,
        enabled: Boolean,
        availability: BiometricAvailability,
        usageDescriptionPresent: Boolean = true,
    ): BiometricSettings =
        if (enabled && !canEnable(settings, availability, usageDescriptionPresent)) {
            settings.copy(biometricEnabled = false)
        } else {
            settings.copy(biometricEnabled = enabled)
        }

    /**
     * The canonical `clearSecurityPreferencesAfterPasscodeRemoval`: only confirmed passcode
     * removal clears the biometric preference. Disabling biometrics must call
     * [setBiometricEnabled]`(enabled = false)` instead and leave [BiometricSettings.passcodeEnabled].
     */
    fun onPasscodeRemoved(): BiometricSettings =
        BiometricSettings(passcodeEnabled = false, biometricEnabled = false)

    /** The pre-prompt presentation for the current settings and availability. */
    fun presentation(
        settings: BiometricSettings,
        availability: BiometricAvailability,
    ): BiometricPresentation {
        if (!settings.passcodeEnabled) return BiometricPresentation.Unavailable
        if (!settings.biometricEnabled) return BiometricPresentation.FallbackToPasscode
        return when (availability) {
            is BiometricAvailability.Available -> BiometricPresentation.Offer(availability.kind)
            else -> BiometricPresentation.FallbackToPasscode
        }
    }

    /**
     * Maps a completed prompt result to the access decision. Success is the only path to
     * [BiometricAccessDecision.AUTHENTICATED], and even then only against a usable availability;
     * lockout and fallback both route to the passcode gate.
     */
    fun decisionFor(
        result: BiometricPromptResult,
        availability: BiometricAvailability,
    ): BiometricAccessDecision = when (result) {
        BiometricPromptResult.Success ->
            if (availability is BiometricAvailability.Available) {
                BiometricAccessDecision.AUTHENTICATED
            } else {
                BiometricAccessDecision.UNAVAILABLE
            }

        BiometricPromptResult.Cancelled -> BiometricAccessDecision.DENIED
        is BiometricPromptResult.Lockout -> BiometricAccessDecision.FALLBACK_TO_PASSCODE
        BiometricPromptResult.FallbackRequested -> BiometricAccessDecision.FALLBACK_TO_PASSCODE
        BiometricPromptResult.AuthenticationDisabled -> BiometricAccessDecision.FALLBACK_TO_PASSCODE
        BiometricPromptResult.Failed -> BiometricAccessDecision.FALLBACK_TO_PASSCODE
    }
}
