package com.lavasec.app.platform

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the staged secret-rotation protocol and the generation-addressed key
 * framing. Pure JVM: no Android, no Keystore, no coroutines.
 */
class ChainedUpstreamSecretStagingTest {

    private class InMemoryKeyItemStore : ChainedUpstreamKeyItemStore {
        val entries = LinkedHashMap<String, ByteArray>()
        var failNextAdd = false
        var corruptNextLoad = false
        var failAccounts = false

        override fun add(value: ByteArray, account: String): Boolean {
            if (failNextAdd) {
                failNextAdd = false
                return false
            }
            if (entries.containsKey(account)) return false
            entries[account] = value.copyOf()
            return true
        }

        override fun load(account: String): ByteArray? {
            if (corruptNextLoad) {
                corruptNextLoad = false
                return ByteArray(33) { 0x5A }
            }
            return entries[account]?.copyOf()
        }

        override fun delete(account: String): Boolean {
            entries.remove(account)
            return true
        }

        override fun accounts(): List<String> {
            if (failAccounts) throw IllegalStateException("accounts unavailable")
            return entries.keys.toList()
        }
    }

    private val privateKey = ByteArray(32) { it.toByte() }
    private val presharedKey = ByteArray(32) { (it + 1).toByte() }

    /** `replace` is suspend (its publish callback runs in a coroutine), so drive it from a block. */
    private fun commit(
        staging: ChainedUpstreamSecretStaging,
        committedGeneration: Long?,
        privateKey: ByteArray,
        presharedKey: ByteArray?,
        publish: suspend (generation: Long) -> Boolean,
    ): ChainedUpstreamSecretStaging.Result =
        runBlocking { staging.replace(committedGeneration, privateKey, presharedKey, publish) }

    @Test
    fun `first save publishes generation one and the key is addressed by that generation`() {
        val store = InMemoryKeyItemStore()
        val staging = ChainedUpstreamSecretStaging(store)

        val result = commit(staging, null, privateKey, presharedKey) { true }

        assertEquals(ChainedUpstreamSecretStaging.Result.Published(1L), result)
        val stored = checkNotNull(
            ChainedUpstreamSecretCodec.decode(
                checkNotNull(store.entries[ChainedUpstreamSecretNaming.keyAccount(1L)]),
                1L,
            ),
        )
        assertArrayEquals(privateKey, stored.privateKeyCopy())
    }

    @Test
    fun `a second save advances the generation and sweeps the previous key`() {
        val store = InMemoryKeyItemStore()
        val staging = ChainedUpstreamSecretStaging(store)
        commit(staging, null, privateKey, null) { true }

        val result = commit(staging, 1L, privateKey, presharedKey) { true }

        assertEquals(ChainedUpstreamSecretStaging.Result.Published(2L), result)
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(2L)), store.entries.keys)
    }

    @Test
    fun `a failed publish removes the staged key and leaves the prior rotation intact`() {
        val store = InMemoryKeyItemStore()
        val staging = ChainedUpstreamSecretStaging(store)
        commit(staging, null, privateKey, null) { true }

        val result = commit(staging, 1L, privateKey, presharedKey) { false }

        assertEquals(
            ChainedUpstreamSecretStaging.Result.Rejected(
                ChainedUpstreamSecretStaging.Failure.PUBLISH_FAILED,
            ),
            result,
        )
        // The new key is gone; the committed generation 1 key survives.
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(1L)), store.entries.keys)
    }

    @Test
    fun `a failed key write does not call publish and writes nothing`() {
        val store = InMemoryKeyItemStore().apply { failNextAdd = true }
        val staging = ChainedUpstreamSecretStaging(store)
        var publishCalls = 0

        val result = commit(staging, null, privateKey, null) {
            publishCalls++
            true
        }

        assertEquals(
            ChainedUpstreamSecretStaging.Result.Rejected(
                ChainedUpstreamSecretStaging.Failure.KEY_WRITE_FAILED,
            ),
            result,
        )
        assertEquals(0, publishCalls)
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `a read-back mismatch removes the staged key and does not publish`() {
        val store = InMemoryKeyItemStore().apply { corruptNextLoad = true }
        val staging = ChainedUpstreamSecretStaging(store)
        var publishCalls = 0

        val result = commit(staging, null, privateKey, null) {
            publishCalls++
            true
        }

        assertEquals(
            ChainedUpstreamSecretStaging.Result.Rejected(
                ChainedUpstreamSecretStaging.Failure.KEY_VERIFY_FAILED,
            ),
            result,
        )
        assertEquals(0, publishCalls)
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `the sweep never deletes the committed generation`() {
        val store = InMemoryKeyItemStore().apply {
            // Generation 1 is the committed key; 3 and 4 are pre-existing orphans. The new
            // rotation mints 2, so a sweep that used the OLD generation as its committed argument
            // would delete the new key.
            entries[ChainedUpstreamSecretNaming.keyAccount(1L)] = ByteArray(33)
            entries[ChainedUpstreamSecretNaming.keyAccount(3L)] = ByteArray(33)
            entries[ChainedUpstreamSecretNaming.keyAccount(4L)] = ByteArray(33)
        }
        val staging = ChainedUpstreamSecretStaging(store)

        val result = commit(staging, 1L, privateKey, null) { true }

        assertEquals(ChainedUpstreamSecretStaging.Result.Published(2L), result)
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(2L)), store.entries.keys)
    }

    @Test
    fun `residue at the target generation is cleared before the add`() {
        val store = InMemoryKeyItemStore().apply {
            // An earlier rotation was killed after writing generation 2 but before publishing, so
            // generation 1 is committed and 2 is orphan residue. A monotonic re-save targets 2
            // again and must not fail against it.
            entries[ChainedUpstreamSecretNaming.keyAccount(1L)] = ByteArray(33)
            entries[ChainedUpstreamSecretNaming.keyAccount(2L)] = ByteArray(33) { 0x7F }
        }
        val staging = ChainedUpstreamSecretStaging(store)

        val result = commit(staging, 1L, privateKey, presharedKey) { true }

        assertEquals(ChainedUpstreamSecretStaging.Result.Published(2L), result)
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(2L)), store.entries.keys)
        val stored = checkNotNull(
            ChainedUpstreamSecretCodec.decode(
                checkNotNull(store.entries[ChainedUpstreamSecretNaming.keyAccount(2L)]),
                2L,
            ),
        )
        assertArrayEquals(presharedKey, stored.presharedKeyCopy())
    }

    @Test
    fun `an exhausted generation space is rejected before writing`() {
        val store = InMemoryKeyItemStore()
        val staging = ChainedUpstreamSecretStaging(store)

        val result = commit(staging, Long.MAX_VALUE, privateKey, null) { true }

        assertEquals(
            ChainedUpstreamSecretStaging.Result.Rejected(
                ChainedUpstreamSecretStaging.Failure.GENERATION_EXHAUSTED,
            ),
            result,
        )
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `a wrong-width key is rejected before writing`() {
        val store = InMemoryKeyItemStore()
        val staging = ChainedUpstreamSecretStaging(store)

        val result = commit(staging, null, ByteArray(31), null) { true }

        assertEquals(
            ChainedUpstreamSecretStaging.Result.Rejected(
                ChainedUpstreamSecretStaging.Failure.MALFORMED_SECRET,
            ),
            result,
        )
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `a sweep that cannot enumerate keeps the committed key`() {
        val store = InMemoryKeyItemStore().apply { failAccounts = true }
        val staging = ChainedUpstreamSecretStaging(store)

        val result = commit(staging, null, privateKey, null) { true }

        // The publish succeeded; the failed sweep must not turn it into an error.
        assertEquals(ChainedUpstreamSecretStaging.Result.Published(1L), result)
        assertTrue(store.entries.containsKey(ChainedUpstreamSecretNaming.keyAccount(1L)))
    }

    @Test
    fun `account naming is round-trip strict`() {
        assertEquals(1L, ChainedUpstreamSecretNaming.generationOrNull("chained-upstream-key/1"))
        assertEquals(255L, ChainedUpstreamSecretNaming.generationOrNull("chained-upstream-key/ff"))
        assertNull(ChainedUpstreamSecretNaming.generationOrNull("chained-upstream-key/FF"))
        assertNull(ChainedUpstreamSecretNaming.generationOrNull("chained-upstream-key/01"))
        assertNull(ChainedUpstreamSecretNaming.generationOrNull("chained-upstream-key/0"))
        assertNull(ChainedUpstreamSecretNaming.generationOrNull("chained-upstream-key/"))
        assertNull(ChainedUpstreamSecretNaming.generationOrNull("other/1"))
    }

    @Test
    fun `orphan selection never names the committed generation`() {
        val accounts = listOf(
            ChainedUpstreamSecretNaming.keyAccount(1L),
            ChainedUpstreamSecretNaming.keyAccount(2L),
            "unrelated-account",
        )

        assertEquals(
            listOf(ChainedUpstreamSecretNaming.keyAccount(1L)),
            ChainedUpstreamSecretNaming.orphanedKeyAccounts(accounts, committed = 2L),
        )
    }

    @Test
    fun `codec round-trips a private key with and without a pre-shared key`() {
        val withoutPsk = checkNotNull(ChainedUpstreamSecretCodec.encode(privateKey, null))
        assertEquals(33, withoutPsk.size)
        val decodedWithout = checkNotNull(ChainedUpstreamSecretCodec.decode(withoutPsk, 7L))
        assertFalse(decodedWithout.hasPresharedKey)
        assertArrayEquals(privateKey, decodedWithout.privateKeyCopy())
        assertNull(decodedWithout.presharedKeyCopy())

        val withPsk = checkNotNull(ChainedUpstreamSecretCodec.encode(privateKey, presharedKey))
        assertEquals(65, withPsk.size)
        val decodedWith = checkNotNull(ChainedUpstreamSecretCodec.decode(withPsk, 9L))
        assertTrue(decodedWith.hasPresharedKey)
        assertArrayEquals(privateKey, decodedWith.privateKeyCopy())
        assertArrayEquals(presharedKey, decodedWith.presharedKeyCopy())
    }

    @Test
    fun `codec reads a legacy bare private key as no pre-shared key`() {
        val decoded = checkNotNull(ChainedUpstreamSecretCodec.decode(privateKey, 3L))

        assertFalse(decoded.hasPresharedKey)
        assertArrayEquals(privateKey, decoded.privateKeyCopy())
    }

    @Test
    fun `codec refuses an unrecognised layout`() {
        assertNull(ChainedUpstreamSecretCodec.decode(ByteArray(40) { 0x11 }, 4L))
        assertNull(ChainedUpstreamSecretCodec.decode(ByteArray(34), 4L))
        assertNull(ChainedUpstreamSecretCodec.decode(ByteArray(0), 4L))
    }

    @Test
    fun `two row codec retains separate keys and PSK custody and refuses truncated framing`() {
        val entry = ByteArray(32) { 0x11 }
        val exit = ByteArray(32) { 0x22 }
        val value = checkNotNull(ChainedUpstreamSecretCodec.encodeRows(listOf(entry, exit), listOf(null, presharedKey)))
        val original = value.copyOf()
        val decoded = checkNotNull(ChainedUpstreamSecretCodec.decodeAndZeroize(value, 11))
        try {
            assertEquals(2, decoded.rowCount)
            assertArrayEquals(entry, decoded.rowPrivateKeyCopy(0))
            assertArrayEquals(exit, decoded.rowPrivateKeyCopy(1))
            assertNull(decoded.rowPresharedKeyCopy(0))
            assertArrayEquals(presharedKey, decoded.rowPresharedKeyCopy(1))
            assertTrue(value.all { it == 0.toByte() })
            assertNull(ChainedUpstreamSecretCodec.decode(original.copyOf(original.size - 1), 11))
            val mutated = decoded.rowPrivateKeyCopy(0).apply { fill(0) }
            assertFalse(entry.contentEquals(mutated))
            assertArrayEquals(entry, decoded.rowPrivateKeyCopy(0))
        } finally { decoded.close(); original.fill(0) }
    }

    @Test
    fun `secret material accessors return independent copies`() {
        val material = checkNotNull(
            ChainedUpstreamSecretMaterial.create(1L, privateKey, presharedKey),
        )
        val first = material.privateKeyCopy()
        first[0] = 0x7F

        assertArrayEquals(privateKey, material.privateKeyCopy())
        assertFalse(material.toString().contains("7f"))
    }
}
