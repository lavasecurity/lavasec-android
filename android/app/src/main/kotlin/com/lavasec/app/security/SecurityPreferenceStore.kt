package com.lavasec.app.security

import android.content.Context
import android.content.SharedPreferences
import com.lavasec.app.foundation.security.LockTimeoutOption
import com.lavasec.app.foundation.security.ProtectedSurface

/**
 * SharedPreferences-backed security preferences. The passcode verifier itself
 * lives in Keystore-encrypted storage ([AndroidKeystorePasscodeVerifier]); this
 * store only carries the non-secret unlock-session facts (enabled flag, last
 * unlock, timeout, protected surfaces, biometric opt-in) that the lock decision
 * needs after a process restart.
 */
class SecurityPreferenceStore(context: Context) : SecurityPreferencesPort {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): SecurityPreferences = SecurityPreferences(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        lastUnlockEpochMillis = preferences.getLong(KEY_LAST_UNLOCK, ABSENT)
            .takeIf { it != ABSENT },
        lockTimeoutMinutes = preferences.getLong(
            KEY_LOCK_TIMEOUT_MINUTES,
            LockTimeoutOption.DEFAULT.minutes,
        ).coerceAtLeast(0L),
        protectedSurfaces = loadSurfaces(),
        credentialChangePending = preferences.getBoolean(KEY_CREDENTIAL_PENDING, false),
        biometricEnabled = preferences.getBoolean(KEY_BIOMETRIC_ENABLED, false),
        appUnlockCoverChoice = appUnlockCoverChoice(),
    )

    override fun appUnlockCoverChoice(): Boolean? = runCatching {
        when (preferences.getString(KEY_APP_UNLOCK_COVER, null)) {
            "on" -> true
            "off" -> false
            null -> {
                val names = preferences.getStringSet(KEY_SURFACES, null)
                // Legacy synthetic/repaired gates cannot prove explicit ON consent.
                if (names != null && names.all { name -> ProtectedSurface.entries.any { it.name == name } } &&
                    ProtectedSurface.APP_UNLOCK.name !in names) false else null
            }
            else -> null
        }
    }.getOrNull()

    override fun save(state: SecurityPreferences) {
        val editor = preferences.edit().putBoolean(KEY_ENABLED, state.enabled)
        if (state.lastUnlockEpochMillis != null) {
            editor.putLong(KEY_LAST_UNLOCK, state.lastUnlockEpochMillis)
        } else {
            editor.remove(KEY_LAST_UNLOCK)
        }
        val committed = editor.putLong(KEY_LOCK_TIMEOUT_MINUTES, state.lockTimeoutMinutes)
            .putStringSet(KEY_SURFACES, state.protectedSurfaces.map { it.name }.toSet())
            .putBoolean(KEY_CREDENTIAL_PENDING, state.credentialChangePending)
            .putBoolean(KEY_BIOMETRIC_ENABLED, state.biometricEnabled)
            .putString(KEY_APP_UNLOCK_COVER, when (state.appUnlockCoverChoice) { true -> "on"; false -> "off"; null -> "unknown" })
            .commit()
        check(committed) { "Security preference publication unavailable" }
    }

    private fun loadSurfaces(): Set<ProtectedSurface> {
        // Canonical SecurityProtectedSurfaceStorage: credential setup never selects gates,
        // and an explicitly empty selection must survive reopen without reseeding.
        val names = preferences.getStringSet(KEY_SURFACES, null) ?: return emptySet()
        val decoded = names.mapNotNull { name ->
            ProtectedSurface.entries.firstOrNull { it.name == name }
        }.toSet()
        // An unknown member is not an informed opt-out. Preserve fail-closed behavior for
        // corruption/future values, including a set mixed with currently known surfaces.
        return if (decoded.size != names.size) ProtectedSurface.ALL else decoded
    }

    private companion object {
        const val PREFERENCES_NAME = "lavasec_security"
        const val KEY_APP_UNLOCK_COVER = "app_unlock_cover_choice"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        const val KEY_CREDENTIAL_PENDING = "credential_change_pending"
        const val KEY_ENABLED = "passcode_enabled"
        const val KEY_LAST_UNLOCK = "last_unlock_epoch_millis"
        const val KEY_LOCK_TIMEOUT_MINUTES = "lock_timeout_minutes"
        const val KEY_SURFACES = "protected_surfaces"
        const val ABSENT = Long.MIN_VALUE
    }
}
