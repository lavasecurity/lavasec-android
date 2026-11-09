package com.lavasec.app.backup

import com.lavasec.app.platform.ChainedUpstreamKeyItemStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual custody adapter with retained port buffers; these are synthetic local fixture keys. */
class KeyItemBackupSealingKeyStoreTest {
    @Test
    fun `save preserves exact UTF8 bytes and caller material but spends the borrowed add buffer`() {
        val items = InspectableItems()
        val key = "fixture-custody-ä-保護-🛡".toCharArray()
        val original = key.copyOf()
        val expectedBytes = key.concatToString().toByteArray(Charsets.UTF_8)

        assertTrue(KeyItemBackupSealingKeyStore(items).save(key))

        assertArrayEquals(expectedBytes, items.stored)
        assertArrayEquals(original, key)
        assertZeroed(checkNotNull(items.borrowedAtAdd))
        assertEquals(listOf("delete", "add"), items.operations)
        assertTrue(items.accountsSeen.all { it == KeyItemBackupSealingKeyStore.ACCOUNT })
    }

    @Test
    fun `save refusal and add exception both spend the exact borrowed bytes without clearing the caller`() {
        for (result in listOf(AddResult.REFUSED, AddResult.THREW)) {
            val items = InspectableItems(addResult = result)
            val key = "public-fixture-custody".toCharArray()
            val original = key.copyOf()

            assertFalse(KeyItemBackupSealingKeyStore(items).save(key))

            assertZeroed(checkNotNull(items.borrowedAtAdd))
            assertArrayEquals(original, key)
            assertNull(items.stored)
            assertEquals(listOf("delete", "add"), items.operations)
        }
    }

    @Test
    fun `load spends the owned plaintext bytes while retaining an independent correct custody array`() {
        val expected = "fixture-custody-ä-保護-🛡".toCharArray()
        val durable = expected.concatToString().toByteArray(Charsets.UTF_8)
        val items = InspectableItems(stored = durable.copyOf())

        val loaded = checkNotNull(KeyItemBackupSealingKeyStore(items).load())

        assertArrayEquals(expected, loaded)
        assertZeroed(checkNotNull(items.ownedAtLoad))
        assertArrayEquals("The key-item authority keeps its independent stored value", durable, items.stored)
        loaded[0] = 'X'
        assertArrayEquals(durable, items.stored)
        assertEquals(listOf("load"), items.operations)
        assertEquals(listOf(KeyItemBackupSealingKeyStore.ACCOUNT), items.accountsSeen)
    }

    @Test
    fun `malformed UTF8 retains legacy replacement decoding and still spends the owned bytes`() {
        val durable = byteArrayOf(0x61, 0xc3.toByte(), 0x28, 0xff.toByte())
        val legacyCharacters = durable.toString(Charsets.UTF_8).toCharArray()
        val items = InspectableItems(stored = durable.copyOf())

        val loaded = checkNotNull(KeyItemBackupSealingKeyStore(items).load())

        assertArrayEquals(legacyCharacters, loaded)
        assertZeroed(checkNotNull(items.ownedAtLoad))
        assertArrayEquals(durable, items.stored)
        assertEquals(listOf("load"), items.operations)
    }

    @Test
    fun `unpaired UTF16 retains legacy UTF8 replacement bytes and caller ownership`() {
        val key = charArrayOf('a', '\ud800', 'b')
        val original = key.copyOf()
        val legacyBytes = key.concatToString().toByteArray(Charsets.UTF_8)
        val items = InspectableItems()

        assertTrue(KeyItemBackupSealingKeyStore(items).save(key))

        assertArrayEquals(legacyBytes, items.stored)
        assertZeroed(checkNotNull(items.borrowedAtAdd))
        assertArrayEquals(original, key)
    }

    @Test
    fun `absent or throwing load remains unavailable and performs no storage mutation`() {
        for (throws in listOf(false, true)) {
            val items = InspectableItems(loadThrows = throws)

            assertNull(KeyItemBackupSealingKeyStore(items).load())

            // No buffer ownership was transferred on these provider outcomes.
            assertNull(items.ownedAtLoad)
            assertNull(items.borrowedAtAdd)
            assertEquals(listOf("load"), items.operations)
            assertEquals(listOf(KeyItemBackupSealingKeyStore.ACCOUNT), items.accountsSeen)
        }
    }

    @Test
    fun `clear preserves the reserved account and key item operational refusal contract`() {
        for (result in listOf(AddResult.STORED, AddResult.REFUSED, AddResult.THREW)) {
            val items = InspectableItems(clearResult = result)

            assertEquals(result == AddResult.STORED, KeyItemBackupSealingKeyStore(items).clear())

            assertEquals(listOf("delete"), items.operations)
            assertEquals(listOf(KeyItemBackupSealingKeyStore.ACCOUNT), items.accountsSeen)
            assertNull(items.borrowedAtAdd)
            assertNull(items.ownedAtLoad)
        }
    }

    private fun assertZeroed(bytes: ByteArray) {
        assertTrue("The port must have received actual non-empty custody material", bytes.isNotEmpty())
        assertArrayEquals("The exact plaintext buffer handed across the key-item port is spent",
            ByteArray(bytes.size), bytes)
    }

    private enum class AddResult { STORED, REFUSED, THREW }

    private class InspectableItems(
        var stored: ByteArray? = null,
        val addResult: AddResult = AddResult.STORED,
        val loadThrows: Boolean = false,
        val clearResult: AddResult = AddResult.STORED,
    ) : ChainedUpstreamKeyItemStore {
        val operations = mutableListOf<String>()
        val accountsSeen = mutableListOf<String>()
        var borrowedAtAdd: ByteArray? = null
        var ownedAtLoad: ByteArray? = null

        override fun add(value: ByteArray, account: String): Boolean {
            operations += "add"
            accountsSeen += account
            borrowedAtAdd = value // Retain the actual borrow, not a copy that could fake cleanup.
            return when (addResult) {
                AddResult.STORED -> { stored = value.copyOf(); true }
                AddResult.REFUSED -> false
                AddResult.THREW -> throw IllegalStateException("synthetic key-item failure")
            }
        }

        override fun load(account: String): ByteArray? {
            operations += "load"
            accountsSeen += account
            if (loadThrows) throw IllegalStateException("synthetic key-item failure")
            return stored?.copyOf()?.also { ownedAtLoad = it }
        }

        override fun delete(account: String): Boolean {
            operations += "delete"
            accountsSeen += account
            return when (clearResult) {
                AddResult.STORED -> { stored = null; true }
                AddResult.REFUSED -> false
                AddResult.THREW -> throw IllegalStateException("synthetic key-item failure")
            }
        }

        override fun accounts(): List<String> = if (stored == null) emptyList()
            else listOf(KeyItemBackupSealingKeyStore.ACCOUNT)
    }
}
