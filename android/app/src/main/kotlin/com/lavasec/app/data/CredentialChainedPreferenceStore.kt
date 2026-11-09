package com.lavasec.app.data

import android.content.Context
import android.content.SharedPreferences
import com.lavasec.app.platform.ChainedPreferenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Credential-protected [ChainedPreferenceStore] over a small `SharedPreferences` file.
 *
 * The preference is a single boolean that is a property of this installation, not content that
 * should ride a backup to another device, so it uses the same `MODE_PRIVATE` credential-protected
 * convention as the chained eligibility record and the secure key items rather than the product
 * DataStore. `commit()` (not `apply()`) is used because the VPN lifecycle acts on the result
 * synchronously and must not race a write that only lands later.
 *
 * ## Reads are always durable, never a construction-time cache
 *
 * A credential-graph rotation creates a replacement store over the SAME file while the retiring
 * store can still commit ([setChainedEnabled]). A [MutableStateFlow] seeded once at construction
 * would then return a stale value until the next rotation or process restart. [loadEnabled]
 * therefore reads the shared preference directly, and [chainedEnabled] is kept current by a
 * `SharedPreferences` change listener (which fires for commits from any instance over the file;
 * listeners are held weakly, so registering does not leak the store).
 */
class CredentialChainedPreferenceStore(
    context: Context,
    private val preferencesName: String = PREFERENCES_NAME,
) : ChainedPreferenceStore {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private val enabled = MutableStateFlow(read())
    private val fallback = MutableStateFlow(readFallback())

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_CHAINED_ENABLED) enabled.value = read()
            if (key == KEY_CHAINED_DNS_FALLBACK) fallback.value = readFallback()
        }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override val chainedEnabled: Flow<Boolean> = enabled

    override val chainedDnsFallbackEnabled: Flow<Boolean> = fallback
    override suspend fun loadDnsFallbackEnabled(): Boolean = readFallback()
    override suspend fun setChainedDnsFallbackEnabled(enabled: Boolean): Boolean {
        val committed = preferences.edit().putBoolean(KEY_CHAINED_DNS_FALLBACK, enabled).commit()
        if (committed) fallback.value = readFallback()
        return committed
    }

    override suspend fun loadEnabled(): Boolean = read()

    override suspend fun setChainedEnabled(enabled: Boolean): Boolean {
        val committed = preferences.edit().putBoolean(KEY_CHAINED_ENABLED, enabled).commit()
        if (committed) this.enabled.value = read()
        return committed
    }

    private fun readFallback(): Boolean = preferences.getBoolean(KEY_CHAINED_DNS_FALLBACK, false)

    private fun read(): Boolean = preferences.getBoolean(KEY_CHAINED_ENABLED, false)

    private companion object {
        const val PREFERENCES_NAME = "lavasec_chained_preferences"
        const val KEY_CHAINED_ENABLED = "chained_enabled"
        const val KEY_CHAINED_DNS_FALLBACK = "chained_dns_fallback_enabled"
    }
}
