package com.lavasec.app.foundation.security

import java.security.MessageDigest

/**
 * The Android app-unlock timeout vocabulary (SecuritySettingsScreen lock timeout).
 * IMMEDIATELY re-asks the passcode on every entry into a protected surface; the
 * minute-based options keep an unlock session alive for exactly that long.
 */
enum class LockTimeoutOption(val minutes: Long) {
    IMMEDIATELY(0L),
    ONE_MINUTE(1L),
    FIVE_MINUTES(5L),
    ;

    companion object {
        val DEFAULT = IMMEDIATELY
    }
}

/**
 * Canonical opt-in surfaces implemented by the Android owner. Preserve ACTIVITY/FILTERS
 * names in saved preferences; protection commands have independent fresh-authentication gates.
 *
 * The wire set mirrors the pinned iOS `SecurityProtectedSurface`: protection control/pause gate
 * the fresh protection commands, ACTIVITY gates the Activity/diagnostics family, FILTERS gates
 * the library/detail/review family, APP_SETTINGS gates the Settings/account/upgrade/DNS/privacy/
 * customization family, and APP_UNLOCK is the whole-app foreground lock (it protects no single
 * destination; the shell blocks the entire UI until the passcode is re-entered).
 */
enum class ProtectedSurface {
    ACTIVITY,
    FILTERS,
    PROTECTION_CONTROL,
    PROTECTION_PAUSE,
    APP_SETTINGS,
    APP_UNLOCK,
    ;

    companion object {
        val ALL: Set<ProtectedSurface> = entries.toSet()
    }
}

/**
 * The pure unlock-session facts. The owner persists these; the UI derives the
 * locked decision through [requiresUnlock] with its own clock so a test can pin
 * every boundary without touching the device clock.
 */
data class AppUnlockSession(
    val enabled: Boolean,
    val lastUnlockEpochMillis: Long?,
    val lockTimeoutMinutes: Long,
) {
    init {
        require(lockTimeoutMinutes >= 0L) { "lock timeout must not be negative" }
    }

    /**
     * True when the passcode is on and the session has expired: never unlocked
     * yet, or the last unlock is older than the timeout. IMMEDIATELY (0 minutes)
     * therefore always re-asks.
     */
    fun requiresUnlock(nowEpochMillis: Long): Boolean {
        if (!enabled) return false
        val lastUnlock = lastUnlockEpochMillis ?: return true
        return nowEpochMillis - lastUnlock > lockTimeoutMinutes * MILLIS_PER_MINUTE
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

/**
 * Derives the passcode verifier exactly like the pinned iOS baseline
 * (SecurityPasscodeKeychainStore.verifier: SHA-256 of salt + code), so the
 * Android Keystore-backed store and iOS keychain never diverge on verification
 * semantics. The derivation is pure; the app layer owns the Keystore transport.
 */
object PasscodeDerivation {
    fun derive(salt: ByteArray, code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(salt + code.toByteArray(Charsets.UTF_8))

    /** Constant-time comparison; never short-circuits on the first mismatch. */
    fun matches(expected: ByteArray, candidate: ByteArray): Boolean =
        expected.size == candidate.size && MessageDigest.isEqual(expected, candidate)
}
