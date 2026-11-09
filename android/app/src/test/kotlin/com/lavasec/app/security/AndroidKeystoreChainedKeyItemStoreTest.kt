package com.lavasec.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.platform.ChainedUpstreamSecretNaming
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Keystore-backed key-item contract.
 *
 * On a JVM (Robolectric) host the AndroidKeyStore provider may not support key generation, so every
 * operation degrades to the honest outcomes — a false `add`, a null `load` — instead of throwing. On
 * a device the same branches cover hardware without usable secure storage, and the round-trip
 * assertions run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class AndroidKeystoreChainedKeyItemStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `round-trips a key item or degrades honestly`() {
        val store = AndroidKeystoreChainedKeyItemStore(
            credentialProtectedContext = context,
            alias = "lavasec.test.chained.${UUID.randomUUID()}",
            preferencesName = "lavasec_test_chained_${UUID.randomUUID()}",
        )
        val account = ChainedUpstreamSecretNaming.keyAccount(1L)
        val value = ByteArray(33) { it.toByte() }

        val added = store.add(value, account)
        if (added) {
            assertArrayEquals(value, checkNotNull(store.load(account)))
            assertTrue(store.accounts().contains(account))
            assertFalse(store.add(value, account)) // additive: a second add refuses
            assertTrue(store.delete(account))
            assertNull(store.load(account))
            assertFalse(store.accounts().contains(account))
        } else {
            assertNull(store.load(account))
        }
    }

    @Test
    fun `an unreadable account loads as absent`() {
        val store = AndroidKeystoreChainedKeyItemStore(
            credentialProtectedContext = context,
            alias = "lavasec.test.chained.${UUID.randomUUID()}",
            preferencesName = "lavasec_test_chained_${UUID.randomUUID()}",
        )

        assertNull(store.load(ChainedUpstreamSecretNaming.keyAccount(9L)))
    }

    @Test
    fun `a keystore that cannot be opened is an operational failure, not an exception`() {
        val store = AndroidKeystoreChainedKeyItemStore(
            credentialProtectedContext = context,
            alias = "lavasec.test.chained.${UUID.randomUUID()}",
            preferencesName = "lavasec_test_chained_${UUID.randomUUID()}",
            keyStoreProvider = { throw IllegalStateException("keystore service unavailable") },
        )
        val account = ChainedUpstreamSecretNaming.keyAccount(1L)

        assertFalse(store.add(ByteArray(33), account))
        assertNull(store.load(account))
        assertTrue(store.accounts().isEmpty())
        assertTrue(store.delete(account))
    }

    @Test
    fun `a corrupt stored blob loads as absent rather than throwing`() {
        val preferencesName = "lavasec_test_chained_${UUID.randomUUID()}"
        val account = ChainedUpstreamSecretNaming.keyAccount(1L)
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString("chained-key:$account", "!!! not base64 !!!")
            .commit()

        val store = AndroidKeystoreChainedKeyItemStore(
            credentialProtectedContext = context,
            alias = "lavasec.test.chained.${UUID.randomUUID()}",
            preferencesName = preferencesName,
        )

        assertNull(store.load(account))
        assertTrue(store.accounts().contains(account))
        assertTrue(store.delete(account))
    }

    @Test
    fun `a persistently unusable alias is recreated so a new secret can be staged`() {
        val keystore = UnusableOnceKeystore()
        val store = AndroidKeystoreChainedKeyItemStore(
            credentialProtectedContext = context,
            alias = "lavasec.test.chained.${UUID.randomUUID()}",
            preferencesName = "lavasec_test_chained_${UUID.randomUUID()}",
            keyStoreProvider = { keystore },
        )
        val account = ChainedUpstreamSecretNaming.keyAccount(1L)
        val value = ByteArray(33) { it.toByte() }

        assertTrue(store.add(value, account))
        assertEquals(1, keystore.deletes)
        assertArrayEquals(value, checkNotNull(store.load(account)))
    }

    /**
     * A Keystore whose alias holds an unusable key until it is deleted, after which it holds a
     * usable one: models a damaged/invalidated entry that must be recreated before a new secret can
     * be staged.
     */
    private class UnusableOnceKeystore : ChainedKeystore {
        private val healthy = SecretKeySpec(ByteArray(32) { 0x42 }, "AES")
        var deletes = 0
            private set

        override fun containsAlias(alias: String): Boolean = true

        override fun getKey(alias: String): SecretKey =
            if (deletes == 0) SecretKeySpec(ByteArray(7) { 0x11 }, "AES") else healthy

        override fun deleteEntry(alias: String) {
            deletes++
        }
    }
}
