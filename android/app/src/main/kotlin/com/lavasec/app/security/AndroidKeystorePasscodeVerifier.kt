package com.lavasec.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lavasec.app.foundation.security.PasscodeDerivation
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed passcode verifier. The verifier (SHA-256 of salt +
 * code, exactly the pinned iOS derivation) is encrypted with an AES-GCM key
 * that lives only in AndroidKeyStore; the encrypted blob, salt, and IV stay in
 * credential-protected SharedPreferences. Every Keystore operation is fenced:
 * a device without usable secure storage returns the honest Unavailable
 * outcomes instead of throwing, so the Security surface can degrade truthfully.
 */
class AndroidKeystorePasscodeVerifier(
    context: Context,
    /** Independent custody namespace for isolated OS instrumentation; production keeps its alias. */
    private val keyAlias: String = KEY_ALIAS,
    private val keyStoreProvider: () -> KeyStore? = {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    },
) : PasscodeVerifierPort {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val keyStore: KeyStore?
        get() = runCatching { keyStoreProvider() }.getOrNull()

    override fun availability(): PasscodeVerifierAvailability {
        val store = keyStore ?: return PasscodeVerifierAvailability.Unavailable
        return runCatching {
            // Prove the generator can create a key on this device without
            // persisting it, mirroring getOrCreateKey's StrongBox-first
            // fallback: only devices with no usable secure storage fail here.
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            runCatching {
                generator.init(strongBoxSpec(probeAlias))
                generator.generateKey()
            }.getOrElse {
                generator.init(plainSpec(probeAlias))
                generator.generateKey()
            }
            store.deleteEntry(probeAlias)
            PasscodeVerifierAvailability.Available
        }.getOrElse { PasscodeVerifierAvailability.Unavailable }
    }

    override fun configure(code: String): Boolean {
        if (keyStore == null) return false
        return try {
            val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            val verifier = PasscodeDerivation.derive(salt, code)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(verifier)
            val iv = cipher.iv
            val committed = preferences.edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
            committed
        } catch (_: Exception) {
            false
        }
    }

    override fun credentialState(): PasscodeCredentialState = try {
        val store = keyStore
        if (store == null) {
            PasscodeCredentialState.Unavailable
        } else if (RECORD_KEYS.none(preferences::contains)) {
            // An existing device key with a lost/failed ciphertext record is not confirmed
            // absence. Keep gates conservative until explicit recovery removes both halves.
            if (store.containsAlias(keyAlias)) PasscodeCredentialState.Unavailable
            else PasscodeCredentialState.Absent
        } else {
            val record = readCredential(store)
            record.first.fill(0)
            record.second.fill(0)
            PasscodeCredentialState.Available
        }
    } catch (_: Exception) {
        PasscodeCredentialState.Unavailable
    }

    override fun verify(code: String): PasscodeVerification {
        val store = keyStore ?: return PasscodeVerification.Unavailable
        return try {
            val (salt, expected) = readCredential(store)
            try {
                val candidate = PasscodeDerivation.derive(salt, code)
                try {
                    if (PasscodeDerivation.matches(expected, candidate)) {
                        PasscodeVerification.Verified
                    } else {
                        PasscodeVerification.Wrong
                    }
                } finally {
                    candidate.fill(0)
                }
            } finally {
                salt.fill(0)
                expected.fill(0)
            }
        } catch (_: Exception) {
            PasscodeVerification.Unavailable
        }
    }

    private fun readCredential(store: KeyStore): Pair<ByteArray, ByteArray> {
        fun read(key: String, bytes: Int): ByteArray {
            val encoded = preferences.getString(key, null) ?: error("Credential record unavailable")
            require(encoded.length <= 128) { "Credential record unavailable" }
            return Base64.decode(encoded, Base64.NO_WRAP).also {
                require(it.size == bytes) { "Credential record unavailable" }
            }
        }
        val salt = read(KEY_SALT, SALT_BYTES)
        try {
            val iv = read(KEY_IV, 12)
            val ciphertext = read(KEY_CIPHERTEXT, 32 + GCM_TAG_BITS / 8)
            val key = store.getKey(keyAlias, null) as? SecretKey
                ?: error("Credential key unavailable")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val expected = cipher.doFinal(ciphertext)
            require(expected.size == 32) { "Credential record unavailable" }
            return salt to expected
        } catch (error: Exception) {
            salt.fill(0)
            throw error
        }
    }

    override fun remove(): Boolean = try {
        val store = keyStore
        if (store == null) {
            false
        } else {
            store.deleteEntry(keyAlias)
            val committed = preferences.edit()
                .remove(KEY_SALT)
                .remove(KEY_IV)
                .remove(KEY_CIPHERTEXT)
                .commit()
            committed && !store.containsAlias(keyAlias) && RECORD_KEYS.none(preferences::contains)
        }
    } catch (_: Exception) {
        false
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore ?: error("Keystore unavailable during passcode setup")
        if (store.containsAlias(keyAlias)) {
            val existing = store.getKey(keyAlias, null) as? SecretKey
            if (existing != null) return existing
            store.deleteEntry(keyAlias)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        // StrongBox first; a device without secure hardware falls back to
        // the TEE Keystore, and only total failure degrades to Unavailable.
        return runCatching {
            generator.init(strongBoxSpec(keyAlias))
            generator.generateKey()
        }.getOrElse {
            generator.init(plainSpec(keyAlias))
            generator.generateKey()
        }
    }

    private fun strongBoxSpec(alias: String): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, ENCRYPT_PURPOSE)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setIsStrongBoxBacked(true)
            .build()

    private fun plainSpec(alias: String): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, ENCRYPT_PURPOSE)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build()

    private val probeAlias: String
        get() = if (keyAlias == KEY_ALIAS) TEST_ALIAS else "$keyAlias.availability-probe"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lavasec_passcode_verifier"
        const val TEST_ALIAS = "lavasec_passcode_availability_probe"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENCRYPT_PURPOSE = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val SALT_BYTES = 16
        const val KEY_SALT = "passcode_salt"
        const val KEY_IV = "passcode_iv"
        const val KEY_CIPHERTEXT = "passcode_ciphertext"
        const val PREFERENCES_NAME = "lavasec_security_keystore"
        val RECORD_KEYS = listOf(KEY_SALT, KEY_IV, KEY_CIPHERTEXT)
    }
}
