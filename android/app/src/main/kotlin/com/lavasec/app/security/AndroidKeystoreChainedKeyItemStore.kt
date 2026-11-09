package com.lavasec.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lavasec.app.platform.ChainedUpstreamKeyItemStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The subset of the platform Keystore this store uses.
 *
 * A seam because the live `AndroidKeyStore` is not exercisable on a host JVM, and because the
 * alias-recovery path needs a provider whose key can be made deliberately unusable.
 */
internal interface ChainedKeystore {
    fun containsAlias(alias: String): Boolean

    /** The key at [alias], or `null` when it is absent or unreadable. */
    fun getKey(alias: String): SecretKey?

    fun deleteEntry(alias: String)
}

/**
 * The production chained-upstream key-item backend: one Keystore-encrypted blob per generation.
 *
 * Each key item's plaintext (a [com.lavasec.app.platform.ChainedUpstreamSecretCodec] value) is
 * encrypted with an AES-256-GCM key that lives only in AndroidKeyStore. The packed
 * `[12-byte IV][ciphertext+tag]` is persisted in credential-protected SharedPreferences under
 * `chained-key:<account>`; the Keystore key never leaves the provider and is not backed up, so a
 * restored device holds ciphertext it cannot decrypt and the honest state is "re-enter the key".
 *
 * At-rest posture, stated rather than defaulted:
 * - **No user authentication requirement.** Chained mode is built by a `VpnService`, which cannot
 *   present a biometric prompt, and the secret is needed only after first unlock. This matches iOS
 *   `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`; a user-presence gate would make chained mode
 *   unbuildable from the service. StrongBox is preferred and the TEE Keystore is the fallback.
 * - **Non-exportable, device-bound.** The key is generated in hardware-backed Keystore, so the
 *   ciphertext alone (a backup, a file copy) is useless.
 * - **Credential-protected storage.** Both the DataStore configuration and this ciphertext live in
 *   the credential-protected (default) area; nothing is needed before first unlock.
 *
 * [add] is additive: an account that already holds an item is not overwritten, because the commit
 * protocol depends on a new key landing without disturbing the currently committed one. Every
 * method honours the [ChainedUpstreamKeyItemStore] operational-failure contract: a Keystore that
 * cannot be opened, an alias that cannot be read or deleted, or a generation failure returns
 * `false`/`null` instead of throwing.
 *
 * ## Alias recovery
 *
 * An alias that exists but cannot be used (a damaged or invalidated entry, or a key that cannot
 * encrypt) is a PERMANENT failure, not a transient one: the ciphertext-side deletes cannot clear it,
 * so without recovery every later re-entry would fail against the same alias until app data is
 * cleared. [add] therefore drops an unreadable alias before regenerating and, when a key fails to
 * encrypt, drops it and retries once against a fresh key.
 */
internal class AndroidKeystoreChainedKeyItemStore(
    credentialProtectedContext: Context,
    private val alias: String = KEY_ALIAS,
    private val preferencesName: String = PREFERENCES_NAME,
    private val keyStoreProvider: () -> ChainedKeystore? = ::androidKeystoreOrNull,
) : ChainedUpstreamKeyItemStore {
    private val preferences: SharedPreferences =
        credentialProtectedContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private val keyStore: ChainedKeystore?
        get() = runCatching { keyStoreProvider() }.getOrNull()

    override fun add(value: ByteArray, account: String): Boolean {
        val entry = entryKey(account)
        return try {
            // Additive, checked before the write; the staging protocol serializes writers, so this
            // is a correctness guard against a reused account, not a concurrency primitive.
            if (preferences.contains(entry)) return false
            when (storeWithKey(value, entry)) {
                StoreOutcome.STORED -> true
                // A key that cannot encrypt is dropped so a fresh key can be generated; without
                // this every later re-entry fails against the same unusable alias.
                StoreOutcome.KEY_UNUSABLE ->
                    dropAlias() && storeWithKey(value, entry) == StoreOutcome.STORED
                StoreOutcome.STORAGE_FAILED -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun load(account: String): ByteArray? = runCatching {
        val key = keyStore?.getKey(alias) ?: return@runCatching null
        val packed = preferences.getString(entryKey(account), null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return@runCatching null
        if (packed.size <= IV_BYTES) return@runCatching null
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ciphertext)
    }.getOrNull() // A vanished/unusable key reads as absent; a re-save recreates the alias.

    override fun delete(account: String): Boolean =
        try {
            preferences.edit().remove(entryKey(account)).commit()
        } catch (_: Exception) {
            false
        }

    override fun accounts(): List<String> =
        preferences.all.keys
            .asSequence()
            .filter { it.startsWith(ENTRY_PREFIX) }
            .map { it.removePrefix(ENTRY_PREFIX) }
            .toList()

    private enum class StoreOutcome { STORED, KEY_UNUSABLE, STORAGE_FAILED }

    /**
     * Encrypts and durably stores one item, distinguishing an unusable key (retryable with a fresh
     * key) from a storage failure (not a key problem, so the alias must not be dropped).
     */
    private fun storeWithKey(value: ByteArray, entry: String): StoreOutcome {
        val key = getOrCreateKey() ?: return StoreOutcome.KEY_UNUSABLE
        val packed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(value)
            val iv = cipher.iv
            ByteArray(iv.size + ciphertext.size).also { out ->
                System.arraycopy(iv, 0, out, 0, iv.size)
                System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
            }
        } catch (_: Exception) {
            return StoreOutcome.KEY_UNUSABLE
        }
        return try {
            val committed = preferences.edit()
                .putString(entry, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit()
            if (committed) StoreOutcome.STORED else StoreOutcome.STORAGE_FAILED
        } catch (_: Exception) {
            StoreOutcome.STORAGE_FAILED
        }
    }

    /** Drops the alias so the next generation can create a fresh key. Returns false on failure. */
    private fun dropAlias(): Boolean = runCatching {
        val store = keyStore ?: return@runCatching true
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        true
    }.getOrDefault(false)

    /**
     * The usable AES key, or `null` when the Keystore cannot provide one.
     *
     * The WHOLE lookup/recovery path is contained, and an alias that is present but unreadable is
     * dropped before regenerating so it cannot wedge every later attempt. StrongBox is preferred,
     * with the TEE Keystore as the fallback.
     */
    private fun getOrCreateKey(): SecretKey? = runCatching {
        val store = keyStore ?: return@runCatching null
        if (store.containsAlias(alias)) {
            val existing = runCatching { store.getKey(alias) }.getOrNull()
            if (existing != null) return@runCatching existing
            runCatching { store.deleteEntry(alias) }
        }
        generateKey()
    }.getOrNull()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        return runCatching {
            generator.init(strongBoxSpec(alias))
            generator.generateKey()
        }.getOrElse {
            generator.init(plainSpec(alias))
            generator.generateKey()
        }
    }

    private fun strongBoxSpec(keyAlias: String): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(keyAlias, ENCRYPT_PURPOSE)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(true)
            .build()

    private fun plainSpec(keyAlias: String): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(keyAlias, ENCRYPT_PURPOSE)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(false)
            .build()

    private fun entryKey(account: String): String = ENTRY_PREFIX + account

    private companion object {
        const val KEY_ALIAS = "lavasec.chained_upstream.secret.aes.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENCRYPT_PURPOSE = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128

        /** The AES-GCM nonce width; `Cipher` emits 12 bytes by default for GCM. */
        const val IV_BYTES = 12
        const val ENTRY_PREFIX = "chained-key:"
        const val PREFERENCES_NAME = "lavasec_chained_upstream_secrets"
    }
}

private const val ANDROID_KEY_STORE = "AndroidKeyStore"

/** The platform Keystore as a [ChainedKeystore], or `null` when it cannot be opened. */
private fun androidKeystoreOrNull(): ChainedKeystore? = runCatching {
    val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    object : ChainedKeystore {
        override fun containsAlias(alias: String): Boolean = store.containsAlias(alias)

        override fun getKey(alias: String): SecretKey? = store.getKey(alias, null) as? SecretKey

        override fun deleteEntry(alias: String) {
            store.deleteEntry(alias)
        }
    }
}.getOrNull()
