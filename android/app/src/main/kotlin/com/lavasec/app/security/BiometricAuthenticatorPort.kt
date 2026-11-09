package com.lavasec.app.security

import com.lavasec.app.foundation.biometrics.BiometricAvailability
import com.lavasec.app.foundation.biometrics.BiometricPromptResult

/**
 * Android transport for the canonical biometric prompt.
 *
 * Mirrors [PasscodeVerifierPort]'s shape: capability/enrollment/lockout are read separately from
 * one prompt, every platform status is classified into the pure foundation vocabulary, and no raw
 * `BiometricPrompt` error or platform exception escapes. The port never grants access itself —
 * [SecurityOwner] owns the grant and consults this port only as evidence.
 *
 * Two invariants are load-bearing:
 *  - A prompt that was never presented, was cancelled, or ran while the authenticator was not
 *    [BiometricAvailability.Available] can never resolve to [BiometricPromptResult.Success].
 *  - [cancel] must revoke an in-flight prompt so a retired caller cannot receive a late success;
 *    a real adapter is device-gated and must be registered only on a device lane.
 */
interface BiometricAuthenticatorPort {
    /** The current hardware + enrollment state, classified honestly and never thrown. */
    fun availability(): BiometricAvailability

    /** True while the platform locks the authenticator after too many failed attempts. */
    fun isLockedOut(): Boolean

    /**
     * Presents the platform prompt for [purpose] and resolves a typed result. A recoverable
     * failure or a missing enrollment resolves to a non-success result, never a fabricated one.
     */
    suspend fun authenticate(purpose: String): BiometricPromptResult

    /** Revokes any in-flight prompt; a real adapter must not re-enter the owner from here. */
    fun cancel()

    /**
     * Deletes and regenerates the enrollment-bound platform key after a permanent invalidation
     * (a new biometric was enrolled), clearing any cached invalidation/lockout. Called from the
     * passcode-authorized re-enable path so a user can recover biometrics without an app restart.
     * Returns true only when a usable key was (re)created. Builds without a platform authenticator
     * have nothing to reset and report false.
     */
    fun resetEnrollmentBoundKey(): Boolean = false
}

/**
 * Fail-closed default for every build without a registered platform authenticator. Reports genuine
 * unavailability, so the Security surface neither offers a control it cannot honor nor claims a
 * biometric that no device lane has proven. This is the only production implementation until a
 * device-gated `androidx.biometric` adapter is registered by a build lane.
 */
object UnavailableBiometricAuthenticatorPort : BiometricAuthenticatorPort {
    override fun availability(): BiometricAvailability = BiometricAvailability.Unavailable

    override fun isLockedOut(): Boolean = false

    override suspend fun authenticate(purpose: String): BiometricPromptResult =
        BiometricPromptResult.AuthenticationDisabled

    override fun cancel() = Unit
}
