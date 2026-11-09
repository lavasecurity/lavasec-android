package com.lavasec.app.directboot

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lavasec.app.platform.DirectBootSnapshotAuthenticator
import com.lavasec.app.platform.DirectBootSnapshotFreshnessAuthority
import com.lavasec.app.platform.DirectBootSnapshotKeyRotationResult
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Fixed-alias Android Keystore gateway used by Direct Boot freshness rotation. */
internal interface DirectBootKeyStoreGateway {
    /** Returns false only when the old alias could not be proven invalidated. */
    fun deleteCurrent(): Boolean

    /** Returns the newly installed non-exportable key, or null after proven invalidation. */
    fun createCurrent(): SecretKey?

    fun currentOrNull(): SecretKey?
}

/**
 * Forward-only freshness authority backed by one non-auth-bound Android Keystore HMAC key.
 *
 * Deletion is completed before replacement generation. A delete failure preserves the prior
 * mirror and rejects the credential mutation; a generation failure after deletion makes every
 * prior mirror unverifiable and therefore safely unavailable.
 */
internal class AndroidDirectBootFreshnessAuthority(
    private val gateway: DirectBootKeyStoreGateway = AndroidKeyStoreGateway(),
) : DirectBootSnapshotFreshnessAuthority() {
    override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult {
        if (!gateway.deleteCurrent()) {
            return DirectBootSnapshotKeyRotationResult.RejectedBeforeInvalidation
        }
        val replacement = gateway.createCurrent()
            ?: return DirectBootSnapshotKeyRotationResult.InvalidatedWithoutReplacement
        return rotated(replacement.authenticator())
    }

    override fun currentAuthenticatorOrNull(): DirectBootSnapshotAuthenticator? =
        gateway.currentOrNull()?.authenticator()

    internal fun rotateForTesting(): DirectBootSnapshotKeyRotationResult = rotateCurrentKey()

    internal fun authenticateForTesting(commitment: String): String? =
        currentAuthenticatorOrNull()?.hmacSha256OrNull(commitment)
}

private class AndroidKeyStoreGateway : DirectBootKeyStoreGateway {
    override fun deleteCurrent(): Boolean = try {
        keyStore().run {
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
            !containsAlias(KEY_ALIAS)
        }
    } catch (_: Exception) {
        false
    }

    override fun createCurrent(): SecretKey? = try {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
            }
            .generateKey()
    } catch (_: Exception) {
        null
    }

    override fun currentOrNull(): SecretKey? = try {
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey
    } catch (_: Exception) {
        null
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
}

private fun SecretKey.authenticator(): DirectBootSnapshotAuthenticator =
    DirectBootSnapshotAuthenticator { commitment ->
        try {
            Mac.getInstance(HMAC_SHA_256)
                .apply { init(this@authenticator) }
                .doFinal(commitment.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        } catch (_: Exception) {
            null
        }
    }

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val HMAC_SHA_256 = "HmacSHA256"
private const val KEY_ALIAS = "lavasec.direct_boot.snapshot.hmac.v1"
