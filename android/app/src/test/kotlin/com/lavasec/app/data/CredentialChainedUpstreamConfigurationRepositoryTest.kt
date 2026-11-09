package com.lavasec.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.chained.ChainedUpstreamConfParser
import com.lavasec.app.foundation.chained.ChainedUpstreamRotation
import com.lavasec.app.platform.ChainedUpstreamKeyItemStore
import com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult
import com.lavasec.app.platform.ChainedUpstreamSaveExpectation
import com.lavasec.app.platform.ChainedUpstreamSaveResult
import com.lavasec.app.platform.ChainedUpstreamSelectionRow
import com.lavasec.app.platform.ChainedUpstreamSecretStaging
import com.lavasec.app.platform.ChainedUpstreamSecretNaming
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip and failure-atomicity tests for the credential-protected chained-upstream store.
 *
 * The secret backend is an in-memory fake, so the DataStore codec, the commit protocol wiring, and
 * the read paths run on the host JVM. The live Keystore backend has its own Robolectric-gated test.
 */
@RunWith(RobolectricTestRunner::class)
class CredentialChainedUpstreamConfigurationRepositoryTest {
    private lateinit var context: Context
    private lateinit var storeName: String
    private lateinit var scope: CoroutineScope

    @Test
    fun `pending import ownership is published with metadata and exact cleanup removes both halves`() = runTest {
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys)
        val committed = repository.save(rotation(), ChainedUpstreamSaveExpectation.AbsentPendingImport("editor"))
            as ChainedUpstreamSaveResult.Committed
        val pending = checkNotNull(repository.loadConfiguration())
        assertEquals("editor", pending.pendingImportId)
        assertEquals(ChainedConfigurationIssue.UNAVAILABLE, repository.configurationIssue())
        assertEquals(ChainedUpstreamPendingRemovalResult.Superseded,
            repository.removePendingImport("other-editor", committed.identity))
        assertEquals(pending, repository.loadConfiguration())
        assertEquals(ChainedUpstreamPendingRemovalResult.Removed,
            repository.removePendingImport("editor", committed.identity))
        assertNull(repository.loadConfiguration())
        assertTrue(keys.entries.isEmpty())
    }

    @Test
    fun `pending authoritative read failure retains exact metadata and key for retry`() = runTest {
        val store = FlakyReadPreferencesStore()
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        val committed = repository.save(rotation(), ChainedUpstreamSaveExpectation.AbsentPendingImport("editor"))
            as ChainedUpstreamSaveResult.Committed
        store.failReads = true
        assertEquals(ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed,
            repository.removePendingImport("editor", committed.identity))
        assertTrue(keys.entries.isNotEmpty())
        store.failReads = false
        assertEquals("editor", repository.loadConfiguration()?.pendingImportId)
        assertEquals(ChainedUpstreamPendingRemovalResult.Removed,
            repository.removePendingImport("editor", committed.identity))
    }

    @Test
    fun `failed key cleanup keeps pending witness so retry can finish deletion`() = runTest {
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys)
        val committed = repository.save(rotation(), ChainedUpstreamSaveExpectation.AbsentPendingImport("editor"))
            as ChainedUpstreamSaveResult.Committed
        keys.failDeleteAccount = ChainedUpstreamSecretNaming.keyAccount(committed.identity.generation)
        assertTrue(repository.removePendingImport("editor", committed.identity) is ChainedUpstreamPendingRemovalResult.Failed)
        assertEquals("editor", repository.loadConfiguration()?.pendingImportId)
        keys.failDeleteAccount = null
        assertEquals(ChainedUpstreamPendingRemovalResult.Removed,
            repository.removePendingImport("editor", committed.identity))
        assertNull(repository.loadConfiguration())
        assertTrue(keys.entries.isEmpty())
    }

    @Test
    fun `saved successor cannot be removed with prior pending identity`() = runTest {
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys)
        val pending = repository.save(rotation(), ChainedUpstreamSaveExpectation.AbsentPendingImport("editor"))
            as ChainedUpstreamSaveResult.Committed
        val saved = repository.save(rotation(), ChainedUpstreamSaveExpectation.Generation(pending.identity.generation))
            as ChainedUpstreamSaveResult.Committed
        assertNull(repository.loadConfiguration()?.pendingImportId)
        assertEquals(ChainedUpstreamPendingRemovalResult.Superseded,
            repository.removePendingImport("editor", pending.identity))
        assertEquals(saved.identity, repository.loadConfiguration()?.identity)
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(saved.identity.generation)), keys.entries.keys)
    }

    @Test
    fun `codec reads legacy v1 while rejecting malformed pending ownership in v2`() = runTest {
        val repository = repository()
        repository.save(rotation())
        val saved = checkNotNull(repository.loadConfiguration())
        val preferences = emptyPreferences().toMutablePreferences()
        ChainedUpstreamConfigurationCodec.encodeLegacy(preferences, saved)
        preferences[intPreferencesKey("schema_version")] = 1
        assertEquals(saved, ChainedUpstreamConfigurationCodec.decode(preferences))
        preferences[intPreferencesKey("schema_version")] = 2
        preferences[stringPreferencesKey("pending_import_id")] = " "
        assertNull(ChainedUpstreamConfigurationCodec.decode(preferences))
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storeName = "chained-upstream-${UUID.randomUUID()}"
        scope = CoroutineScope(Job() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        context.preferencesDataStoreFile(storeName).delete()
    }

    @Test
    fun `an absent store has no configuration and no secrets`() = runTest {
        val repository = repository()

        assertNull(repository.loadConfiguration())
        assertNull(repository.loadSecrets())
        assertNull(repository.storedConfiguration.first())
    }

    @Test
    fun `save commits the configuration and both secrets across recreation`() =
        runTest {
            val keyItems = InMemoryKeyItemStore()
            var repository = repository(keyItems)
            val rotation = rotation(withPresharedKey = true)

            val result = repository.save(rotation)
            val committed = result as ChainedUpstreamSaveResult.Committed
            val stored = checkNotNull(repository.loadConfiguration())

            assertEquals(rotation.configuration, stored.configuration)
            assertEquals(1L, committed.identity.generation)
            assertEquals(committed.identity, stored.identity)
            val secrets = checkNotNull(repository.loadSecrets())
            assertEquals(1L, secrets.generation)
            assertTrue(secrets.hasPresharedKey)
            assertArrayEquals(rotation.decodedPrivateKey(), secrets.privateKeyCopy())
            assertArrayEquals(rotation.decodedPresharedKey(), secrets.presharedKeyCopy())

            scope.cancel()
            scope.coroutineContext[Job]?.join()
            scope = CoroutineScope(Job() + Dispatchers.IO)
            repository = repository(keyItems)

            val recreated = checkNotNull(repository.loadConfiguration())
            assertEquals(committed.identity, recreated.identity)
            assertEquals(rotation.configuration, recreated.configuration)
            assertArrayEquals(
                rotation.decodedPrivateKey(),
                checkNotNull(repository.loadSecrets()).privateKeyCopy(),
            )
        }

    @Test
    fun `save advances the generation and sweeps the previous key`() = runTest {
        val keyItems = InMemoryKeyItemStore()
        val repository = repository(keyItems)

        val first = repository.save(rotation()) as ChainedUpstreamSaveResult.Committed
        val second = repository.save(rotation()) as ChainedUpstreamSaveResult.Committed

        assertEquals(1L, first.identity.generation)
        assertEquals(2L, second.identity.generation)
        assertEquals(
            setOf(ChainedUpstreamSecretNaming.keyAccount(2L)),
            keyItems.entries.keys,
        )
    }

    @Test
    fun `save refuses the first-rotation absence CAS when a rotation is committed`() = runTest {
        val keyItems = InMemoryKeyItemStore()
        val repository = repository(keyItems)
        repository.save(rotation())

        val superseded = repository.save(rotation(), ChainedUpstreamSaveExpectation.Absent)

        assertEquals(ChainedUpstreamSaveResult.Superseded, superseded)
        assertEquals(1L, repository.loadConfiguration()?.identity?.generation)
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(1L)), keyItems.entries.keys)
    }

    @Test
    fun `save enforces the expected-generation CAS inside the serialized write`() = runTest {
        val keyItems = InMemoryKeyItemStore()
        val repository = repository(keyItems)
        repository.save(rotation())

        assertEquals(
            ChainedUpstreamSaveResult.Superseded,
            repository.save(rotation(), ChainedUpstreamSaveExpectation.Generation(2L)),
        )
        assertTrue(
            repository.save(rotation(), ChainedUpstreamSaveExpectation.Generation(1L))
                is ChainedUpstreamSaveResult.Committed,
        )
    }

    @Test
    fun `a failed key write leaves the prior configuration and key intact`() = runTest {
        val keyItems = InMemoryKeyItemStore()
        val repository = repository(keyItems)
        val first = rotation().also { repository.save(it) }

        keyItems.failNextAdd = true
        val rejected = repository.save(rotation())

        assertTrue(rejected is ChainedUpstreamSaveResult.Rejected)
        val stored = checkNotNull(repository.loadConfiguration())
        assertEquals(first.configuration, stored.configuration)
        assertEquals(1L, stored.identity.generation)
        assertArrayEquals(
            first.decodedPrivateKey(),
            checkNotNull(repository.loadSecrets()).privateKeyCopy(),
        )
    }

    @Test
    fun `hasUsableSecrets attempts the decrypt and refuses an unreadable ciphertext`() = runTest {
        val keyItems = InMemoryKeyItemStore()
        val repository = repository(keyItems)
        repository.save(rotation())

        assertTrue(repository.hasUsableSecrets())

        // The ciphertext item is still listed by `accounts()`, but the Keystore read now fails
        // (a restored device whose key never backed up): presence must not be reported as usable.
        keyItems.failLoads = true
        assertFalse(repository.hasUsableSecrets())
        assertNull(repository.loadSecrets())
    }

    @Test
    fun `deleteSecrets removes every key generation but keeps the configuration`() = runTest {
        val keyItems = InMemoryKeyItemStore()
        val repository = repository(keyItems)
        val rotation = rotation()
        repository.save(rotation)

        repository.deleteSecrets()

        assertTrue(keyItems.entries.isEmpty())
        assertNotNull(repository.loadConfiguration())
        assertNull(repository.loadSecrets())
    }

    @Test
    fun `removeAll clears the configuration and every key generation`() = runTest {
        val keyItems = InMemoryKeyItemStore()
        val repository = repository(keyItems)
        repository.save(rotation())

        repository.removeAll()

        assertNull(repository.loadConfiguration())
        assertNull(repository.loadSecrets())
        assertTrue(keyItems.entries.isEmpty())
    }

    @Test
    fun `two instances over one store cannot tear the committed pair`() = runBlocking {
        // Two repository instances share the injected store, as the credential-graph rotation
        // creates. The first writer is parked at its publish while the second attempts a save; the
        // second's add is failed so that, without one lock per backing store, it would pre-clear
        // the generation the first just committed and leave the configuration with no key.
        val gatedStore = GatedPreferencesStore()
        val keyItems = InMemoryKeyItemStore().apply { failAddNumber = 2 }
        val first = repository(keyItemStore = keyItems, providedStore = gatedStore)
        val second = repository(keyItemStore = keyItems, providedStore = gatedStore)
        gatedStore.gateNextUpdate = true

        val secondStarted = CompletableDeferred<Unit>()
        val firstSave = launch(Dispatchers.Default) { first.save(rotation()) }
        gatedStore.updateEntered.await()
        val secondSave = launch(Dispatchers.Default) {
            secondStarted.complete(Unit)
            second.save(rotation())
        }
        secondStarted.await()
        yield()
        gatedStore.releaseUpdate.complete(Unit)
        firstSave.join()
        secondSave.join()

        val stored = checkNotNull(first.loadConfiguration())
        val secrets = checkNotNull(first.loadSecrets())
        assertEquals(stored.identity.generation, secrets.generation)
    }

    @Test
    fun `secret storage runs off the caller thread`() = runTest {
        val keyItems = InMemoryKeyItemStore()
        val repository = repository(keyItems)
        val caller = Thread.currentThread()

        repository.save(rotation())
        repository.loadSecrets()

        assertTrue(keyItems.callerThreads.isNotEmpty())
        assertTrue(
            "key storage must not run on the caller thread",
            keyItems.callerThreads.none { it === caller },
        )
    }

    @Test
    fun `a failing authoritative read refuses the save and leaves the prior pair intact`() =
        runTest {
            val flakyStore = FlakyReadPreferencesStore()
            val keyItems = InMemoryKeyItemStore()
            val repository = repository(keyItemStore = keyItems, providedStore = flakyStore)
            val first = repository.save(rotation()) as ChainedUpstreamSaveResult.Committed
            assertEquals(1L, first.identity.generation)

            flakyStore.failReads = true
            val refused = repository.save(rotation())

            assertEquals(ChainedUpstreamSaveResult.AuthoritativeReadFailed, refused)
            // The prior pair survived: the failure refused before staging, so generation 1 and its
            // key are exactly as committed.
            flakyStore.failReads = false
            assertEquals(1L, checkNotNull(repository.loadConfiguration()).identity.generation)
            assertEquals(1L, checkNotNull(repository.loadSecrets()).generation)
            assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(1L)), keyItems.entries.keys)
        }

    @Test
    fun `configuration clear failure preserves the usable pair and reports failure for retry`() = runTest {
        val store = FlakyReadPreferencesStore()
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        repository.save(rotation())
        store.failWrites = true
        assertTrue(runCatching { repository.removeAll() }.exceptionOrNull() is IOException)
        assertNotNull(repository.loadConfiguration())
        assertEquals(1L, repository.loadSecrets()?.generation)
        assertFalse(keys.entries.isEmpty())
        store.failWrites = false
        repository.removeAll()
        assertNull(repository.loadConfiguration())
    }

    @Test
    fun `key enumeration failure is never proof that keys were removed`() = runTest {
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys)
        repository.save(rotation())
        keys.failEnumeration = true
        assertTrue(runCatching { repository.removeAll() }.exceptionOrNull() is IOException)
        assertFalse(keys.entries.isEmpty())
        keys.failEnumeration = false
        repository.removeAll()
        assertTrue(keys.entries.isEmpty())
    }

    @Test
    fun `one failed key deletion does not stop other deletions and remains retryable`() = runTest {
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys)
        repository.save(rotation())
        val first = ChainedUpstreamSecretNaming.keyAccount(1)
        val orphan = ChainedUpstreamSecretNaming.keyAccount(2)
        keys.entries[orphan] = byteArrayOf(1)
        keys.failDeleteAccount = first
        assertTrue(runCatching { repository.removeAll() }.exceptionOrNull() is IOException)
        assertEquals(setOf(first), keys.entries.keys)
        assertNull(repository.loadConfiguration())
        keys.failDeleteAccount = null
        repository.removeAll()
        assertTrue(keys.entries.isEmpty())
    }

    @Test
    fun `a backend returning success without deleting cannot confirm removal`() = runTest {
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys)
        repository.save(rotation())
        keys.ignoreDeletes = true
        assertTrue(runCatching { repository.deleteSecrets() }.exceptionOrNull() is IOException)
        assertNotNull(repository.loadConfiguration())
        assertFalse(keys.entries.isEmpty())
    }

    @Test
    fun `unreadable nonempty metadata cannot reuse generation one or overwrite its key`() = runTest {
        val store = FlakyReadPreferencesStore()
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        val first = repository.save(rotation()) as ChainedUpstreamSaveResult.Committed
        val account = ChainedUpstreamSecretNaming.keyAccount(first.identity.generation)
        val originalKey = checkNotNull(keys.entries[account]).copyOf()
        store.updateData { it.toMutablePreferences().apply { this[intPreferencesKey("schema_version")] = 99 } }
        assertEquals(ChainedUpstreamSaveResult.AuthoritativeReadFailed, repository.save(rotation(true)))
        assertArrayEquals(originalKey, keys.entries[account])
        assertEquals(99, store.data.first()[intPreferencesKey("schema_version")])
        // Explicit deletion remains the recovery route for unreadable metadata.
        repository.removeAll()
        assertTrue(keys.entries.isEmpty())
        assertTrue(repository.save(rotation()) is ChainedUpstreamSaveResult.Committed)
    }

    @Test
    fun `start probe distinguishes absent configuration key absence and unreadable storage`() = runTest {
        val store = FlakyReadPreferencesStore()
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        assertEquals(ChainedConfigurationIssue.MISSING_CONFIGURATION, repository.configurationIssue())
        repository.save(rotation())
        assertNull(repository.configurationIssue())
        keys.failLoads = true
        assertEquals(ChainedConfigurationIssue.UNAVAILABLE, repository.configurationIssue())
        keys.failLoads = false
        keys.failEnumeration = true
        assertEquals(ChainedConfigurationIssue.UNAVAILABLE, repository.configurationIssue())
        keys.failEnumeration = false
        store.failReads = true
        assertEquals(ChainedConfigurationIssue.UNAVAILABLE, repository.configurationIssue())
        store.failReads = false
        keys.entries.clear()
        assertEquals(ChainedConfigurationIssue.MISSING_PRIVATE_KEY, repository.configurationIssue())
        assertNotNull(repository.loadConfiguration())
    }

    @Test
    fun `start probe rejects corrupt metadata and malformed plaintext keys`() = runTest {
        val store = FlakyReadPreferencesStore()
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        repository.save(rotation())
        keys.entries[ChainedUpstreamSecretNaming.keyAccount(1)] = byteArrayOf(1, 2, 3)
        assertEquals(ChainedConfigurationIssue.UNAVAILABLE, repository.configurationIssue())
        store.updateData { it.toMutablePreferences().apply { this[intPreferencesKey("schema_version")] = 99 } }
        assertEquals(ChainedConfigurationIssue.UNAVAILABLE, repository.configurationIssue())
    }

    @Test
    fun `two saved rows retain distinct credentials across swapping disabling and reopen`() = runTest {
        val store = FlakyReadPreferencesStore()
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        val entry = rotation(host = "192.0.2.1", keyFill = 0x11)
        val exit = rotation(true, "192.0.2.2", 0x22)
        val first = repository.save(entry) as ChainedUpstreamSaveResult.Committed
        val added = repository.saveSelection(listOf(
            ChainedUpstreamSelectionRow(entry.configuration.named("Entry"), 0),
            ChainedUpstreamSelectionRow(exit.configuration.named("Exit"), null, exit),
        ), ChainedUpstreamSaveExpectation.Generation(first.identity.generation)) as ChainedUpstreamSaveResult.Committed
        val reopened = repository(keys, store)
        assertEquals(listOf("Entry", "Exit"), reopened.loadConfiguration()?.configuration?.orderedHops?.map { it.displayName })
        checkNotNull(reopened.loadSecrets()).let { material ->
            try {
                assertEquals(2, material.rowCount)
                assertArrayEquals(entry.decodedPrivateKey(), material.rowPrivateKeyCopy(0))
                assertArrayEquals(exit.decodedPrivateKey(), material.rowPrivateKeyCopy(1))
                assertArrayEquals(exit.decodedPresharedKey(), material.rowPresharedKeyCopy(1))
            } finally { material.close() }
        }
        val disabled = reopened.saveSelection(listOf(
            ChainedUpstreamSelectionRow(exit.configuration.named("Exit").enabled(false), 1),
            ChainedUpstreamSelectionRow(entry.configuration.named("Entry"), 0),
        ), ChainedUpstreamSaveExpectation.Generation(added.identity.generation)) as ChainedUpstreamSaveResult.Committed
        checkNotNull(reopened.loadSecrets()).let { material ->
            try { assertEquals(1, material.rowCount); assertArrayEquals(entry.decodedPrivateKey(), material.privateKeyCopy()) }
            finally { material.close() }
        }
        val enabled = reopened.saveSelection(listOf(
            ChainedUpstreamSelectionRow(exit.configuration.named("Exit"), 0),
            ChainedUpstreamSelectionRow(entry.configuration.named("Entry"), 1),
        ), ChainedUpstreamSaveExpectation.Generation(disabled.identity.generation)) as ChainedUpstreamSaveResult.Committed
        checkNotNull(reopened.loadSecrets()).let { material ->
            try {
                assertArrayEquals(exit.decodedPrivateKey(), material.rowPrivateKeyCopy(0))
                assertArrayEquals(exit.decodedPresharedKey(), material.rowPresharedKeyCopy(0))
                assertArrayEquals(entry.decodedPrivateKey(), material.rowPrivateKeyCopy(1))
            } finally { material.close() }
        }
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(enabled.identity.generation)), keys.entries.keys)
    }

    @Test
    fun `failed multi row publication preserves old metadata and credentials and stale deletion refuses`() = runTest {
        val store = FlakyReadPreferencesStore()
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        val entry = rotation(host = "192.0.2.1")
        val exit = rotation(host = "192.0.2.2", keyFill = 0x22)
        val first = repository.save(entry) as ChainedUpstreamSaveResult.Committed
        val original = repository.loadConfiguration()
        val selection = listOf(ChainedUpstreamSelectionRow(entry.configuration, 0), ChainedUpstreamSelectionRow(exit.configuration, null, exit))
        store.failWrites = true
        assertEquals(ChainedUpstreamSaveResult.Rejected(ChainedUpstreamSecretStaging.Failure.PUBLISH_FAILED),
            repository.saveSelection(selection, ChainedUpstreamSaveExpectation.Generation(first.identity.generation)))
        assertEquals(original, repository.loadConfiguration())
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(first.identity.generation)), keys.entries.keys)
        store.failWrites = false
        val saved = repository.saveSelection(selection, ChainedUpstreamSaveExpectation.Generation(first.identity.generation)) as ChainedUpstreamSaveResult.Committed
        assertEquals(ChainedUpstreamPendingRemovalResult.Superseded, repository.removeIfGeneration(first.identity.generation))
        assertEquals(saved.identity, repository.loadConfiguration()?.identity)
        assertEquals(ChainedUpstreamSaveResult.Superseded,
            repository.saveSelection(selection, ChainedUpstreamSaveExpectation.Generation(first.identity.generation)))
    }

    @Test
    fun `retained row reference cannot substitute endpoint metadata or borrow a missing key`() = runTest {
        val keys = InMemoryKeyItemStore()
        val repository = repository(keys)
        val first = repository.save(rotation()) as ChainedUpstreamSaveResult.Committed
        val borrowed = ChainedUpstreamSelectionRow(rotation(host = "different.example").configuration, 0)
        assertEquals(ChainedUpstreamSaveResult.Rejected(ChainedUpstreamSecretStaging.Failure.MISSING_REFERENCED_SECRET),
            repository.saveSelection(listOf(borrowed), ChainedUpstreamSaveExpectation.Generation(first.identity.generation)))
        keys.entries.clear()
        assertEquals(ChainedUpstreamSaveResult.Rejected(ChainedUpstreamSecretStaging.Failure.MISSING_REFERENCED_SECRET),
            repository.saveSelection(listOf(ChainedUpstreamSelectionRow(rotation().configuration.named("Rename"), 0)),
                ChainedUpstreamSaveExpectation.Generation(first.identity.generation)))
        assertEquals(first.identity, repository.loadConfiguration()?.identity)
    }

    @Test
    fun `host loss during the authoritative read refuses before any credential staging`() = runTest {
        listOf(false, true).forEach { hasPrior ->
            val store = GatedReadPreferencesStore(); val keys = InMemoryKeyItemStore()
            val repository = repository(keys, store)
            val original = Any(); val current = java.util.concurrent.atomic.AtomicReference<Any?>(original)
            val alive = java.util.concurrent.atomic.AtomicBoolean(true)
            val prior = if (hasPrior) repository.save(rotation(true)) as ChainedUpstreamSaveResult.Committed else null
            val before = repository.loadConfiguration()
            val beforeKeys = keys.entries.mapValues { it.value.copyOf() }
            val keyCalls = keys.callerThreads.size
            store.gateNextRead = true
            val saving = async(Dispatchers.Default) {
                repository.saveWhileCurrent(rotation(true, "new.vpn.example", 0x22),
                    prior?.identity?.generation?.let { ChainedUpstreamSaveExpectation.Generation(it) }
                        ?: ChainedUpstreamSaveExpectation.AbsentPendingImport("original-host")) {
                    alive.get() && current.get() === original
                }
            }
            store.readEntered.await()
            alive.set(false); current.set(Any()); store.releaseRead.complete(Unit)
            assertEquals(ChainedUpstreamSaveResult.Superseded, saving.await())
            assertEquals(before, repository.loadConfiguration())
            assertEquals(beforeKeys.keys, keys.entries.keys)
            beforeKeys.forEach { (account, bytes) -> assertArrayEquals(bytes, keys.entries[account]) }
            assertEquals("The host was refused before any key backend call", keyCalls, keys.callerThreads.size)
            if (prior == null) assertNull(repository.loadSecrets())
            else checkNotNull(repository.loadSecrets()).let { material ->
                try { assertEquals(prior.identity.generation, material.generation) } finally { material.close() }
            }
        }
    }

    @Test
    fun `inside edit host guard rolls back staged credentials and preserves the previous pair`() = runTest {
        val store = GatedPreferencesStore(); val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        val previous = rotation(true, "old.vpn.example", 0x55)
        val committed = repository.save(previous) as ChainedUpstreamSaveResult.Committed
        val metadata = repository.loadConfiguration()
        val oldAccount = ChainedUpstreamSecretNaming.keyAccount(committed.identity.generation)
        val oldKeyBytes = checkNotNull(keys.entries[oldAccount]).copyOf()
        val original = Any(); val current = java.util.concurrent.atomic.AtomicReference<Any?>(original)
        store.gateNextUpdate = true
        val saving = async(Dispatchers.Default) {
            repository.saveWhileCurrent(rotation(true, "new.vpn.example", 0x22),
                ChainedUpstreamSaveExpectation.Generation(committed.identity.generation)) { current.get() === original }
        }
        store.updateEntered.await()
        val stagedAccount = ChainedUpstreamSecretNaming.keyAccount(committed.identity.generation + 1)
        assertEquals(setOf(oldAccount, stagedAccount), keys.entries.keys)
        assertEquals(metadata, repository.loadConfiguration())
        current.set(Any()); store.releaseUpdate.complete(Unit)
        assertEquals(ChainedUpstreamSaveResult.Superseded, saving.await())
        assertEquals(metadata, repository.loadConfiguration())
        assertEquals(setOf(oldAccount), keys.entries.keys); assertArrayEquals(oldKeyBytes, keys.entries[oldAccount])
        checkNotNull(repository.loadSecrets()).let { material ->
            try {
                assertEquals(committed.identity.generation, material.generation)
                assertArrayEquals(previous.decodedPrivateKey(), material.privateKeyCopy())
                assertArrayEquals(previous.decodedPresharedKey(), material.presharedKeyCopy())
            } finally { material.close() }
        }
        current.set(original)
        val fresh = repository.saveWhileCurrent(rotation(true, "fresh.vpn.example", 0x33),
            ChainedUpstreamSaveExpectation.Generation(committed.identity.generation)) { current.get() === original }
            as ChainedUpstreamSaveResult.Committed
        assertEquals(committed.identity.generation + 1, fresh.identity.generation)
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(fresh.identity.generation)), keys.entries.keys)
    }

    @Test
    fun `inside first import edit host loss leaves neither pending metadata nor staged keys`() = runTest {
        val store = GatedPreferencesStore(); val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        val original = Any(); val current = java.util.concurrent.atomic.AtomicReference<Any?>(original)
        store.gateNextUpdate = true
        val saving = async(Dispatchers.Default) {
            repository.saveWhileCurrent(rotation(true), ChainedUpstreamSaveExpectation.AbsentPendingImport("retired")) {
                current.get() === original
            }
        }
        store.updateEntered.await()
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(1L)), keys.entries.keys)
        assertNull(repository.loadConfiguration())
        current.set(null); store.releaseUpdate.complete(Unit)
        assertEquals(ChainedUpstreamSaveResult.Superseded, saving.await())
        assertNull(repository.loadConfiguration()); assertNull(repository.loadSecrets()); assertTrue(keys.entries.isEmpty())
        current.set(original)
        val fresh = repository.saveWhileCurrent(rotation(true), ChainedUpstreamSaveExpectation.AbsentPendingImport("fresh")) {
            current.get() === original
        } as ChainedUpstreamSaveResult.Committed
        assertEquals(1L, fresh.identity.generation)
        assertEquals("fresh", repository.loadConfiguration()?.pendingImportId)
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(1L)), keys.entries.keys)
    }

    @Test
    fun `live original host return after a suspended read permits the fully guarded save`() = runTest {
        val store = GatedReadPreferencesStore(); val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        val original = Any(); val current = java.util.concurrent.atomic.AtomicReference<Any?>(original)
        val checks = java.util.concurrent.atomic.AtomicInteger()
        store.gateNextRead = true
        val saving = async(Dispatchers.Default) {
            repository.saveWhileCurrent(rotation(true), ChainedUpstreamSaveExpectation.AbsentPendingImport("same-host")) {
                checks.incrementAndGet(); current.get() === original
            }
        }
        store.readEntered.await()
        current.set(null); assertFalse(current.get() === original)
        current.set(original); store.releaseRead.complete(Unit)
        val committed = saving.await() as ChainedUpstreamSaveResult.Committed
        assertTrue("Admission, publication and actual DataStore transform each recheck the host", checks.get() >= 3)
        assertEquals("same-host", repository.loadConfiguration()?.pendingImportId)
        assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(committed.identity.generation)), keys.entries.keys)
        checkNotNull(repository.loadSecrets()).let { material ->
            try { assertArrayEquals(rotation(true).decodedPrivateKey(), material.privateKeyCopy()) }
            finally { material.close() }
        }
    }

    /** Blocks the real authoritative read before the repository can stage any credentials. */
    private class GatedReadPreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        @Volatile var gateNextRead = false
        override val data: Flow<Preferences> = flow {
            if (gateNextRead) {
                gateNextRead = false
                readEntered.complete(Unit); releaseRead.await()
            }
            emitAll(state)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private fun repository(
        keyItemStore: ChainedUpstreamKeyItemStore = InMemoryKeyItemStore(),
        providedStore: DataStore<Preferences>? = null,
    ): CredentialChainedUpstreamConfigurationRepository =
        CredentialChainedUpstreamConfigurationRepository(
            credentialProtectedContext = context,
            keyItemStore = keyItemStore,
            storeName = storeName,
            scope = scope,
            now = { SAVED_AT_EPOCH_MILLIS },
            providedStore = providedStore,
        )

    @Test
    fun `configured publication refuses native grant revocation and later regrant inside actual edit`() = runTest {
        for (regrant in listOf(false, true)) {
            val store = GatedPreferencesStore(); val keys = InMemoryKeyItemStore()
            val repository = repository(keys, store)
            val previous = rotation(true, "old.vpn.example", 0x55)
            val committed = repository.save(previous) as ChainedUpstreamSaveResult.Committed
            val metadata = repository.loadConfiguration()
            val oldAccount = ChainedUpstreamSecretNaming.keyAccount(committed.identity.generation)
            val oldKeyBytes = checkNotNull(keys.entries[oldAccount]).copyOf()
            val security = mutationSecurity(protected = true)
            try {
                authorizeMutation(security)
                val witness = checkNotNull(com.lavasec.app.security.MutationAuthorizationWitness.capture(security,
                    com.lavasec.app.foundation.security.ProtectedSurface.APP_SETTINGS))
                store.gateNextUpdate = true
                val saving = async(Dispatchers.Default) {
                    repository.saveWhileCurrent(rotation(true, "new.vpn.example", 0x22),
                        ChainedUpstreamSaveExpectation.Generation(committed.identity.generation), witness::isCurrent)
                }
                store.updateEntered.await()
                val stagedAccount = ChainedUpstreamSecretNaming.keyAccount(committed.identity.generation + 1)
                assertEquals(setOf(oldAccount, stagedAccount), keys.entries.keys)
                assertEquals(metadata, repository.loadConfiguration())
                security.resetAuthenticationTurn()
                if (regrant) authorizeMutation(security)
                assertFalse(witness.isCurrent())
                store.releaseUpdate.complete(Unit)
                assertEquals(ChainedUpstreamSaveResult.Superseded, saving.await())
                assertEquals(metadata, repository.loadConfiguration())
                assertEquals(setOf(oldAccount), keys.entries.keys)
                assertArrayEquals(oldKeyBytes, keys.entries[oldAccount])
                checkNotNull(repository.loadSecrets()).let { material ->
                    try {
                        assertEquals(committed.identity.generation, material.generation)
                        assertArrayEquals(previous.decodedPrivateKey(), material.privateKeyCopy())
                        assertArrayEquals(previous.decodedPresharedKey(), material.presharedKeyCopy())
                    } finally { material.close() }
                }
                authorizeMutation(security)
                val fresh = checkNotNull(com.lavasec.app.security.MutationAuthorizationWitness.capture(security,
                    com.lavasec.app.foundation.security.ProtectedSurface.APP_SETTINGS))
                val saved = repository.saveWhileCurrent(rotation(true, "fresh.vpn.example", 0x33),
                    ChainedUpstreamSaveExpectation.Generation(committed.identity.generation), fresh::isCurrent)
                    as ChainedUpstreamSaveResult.Committed
                assertEquals(committed.identity.generation + 1, saved.identity.generation)
                assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(saved.identity.generation)), keys.entries.keys)
            } finally { store.releaseUpdate.complete(Unit); security.close() }
        }
    }

    @Test
    fun `first import publication rolls back revoked native authority but accepts unchanged security off turn`() = runTest {
        for (transition in listOf("revoke", "regrant", "security-off-turn")) {
            val store = GatedPreferencesStore(); val keys = InMemoryKeyItemStore()
            val repository = repository(keys, store)
            val security = mutationSecurity(protected = transition != "security-off-turn")
            try {
                authorizeMutation(security)
                val witness = checkNotNull(com.lavasec.app.security.MutationAuthorizationWitness.capture(security,
                    com.lavasec.app.foundation.security.ProtectedSurface.APP_SETTINGS))
                store.gateNextUpdate = true
                val saving = async(Dispatchers.Default) {
                    repository.saveWhileCurrent(rotation(true), ChainedUpstreamSaveExpectation.AbsentPendingImport("original"),
                        witness::isCurrent)
                }
                store.updateEntered.await()
                assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(1L)), keys.entries.keys)
                assertNull(repository.loadConfiguration())
                security.resetAuthenticationTurn()
                if (transition == "regrant") authorizeMutation(security)
                store.releaseUpdate.complete(Unit)
                val result = saving.await()
                if (transition == "security-off-turn") {
                    assertTrue(result is ChainedUpstreamSaveResult.Committed)
                    assertEquals("original", repository.loadConfiguration()?.pendingImportId)
                    assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(1L)), keys.entries.keys)
                } else {
                    assertEquals(ChainedUpstreamSaveResult.Superseded, result)
                    assertNull(repository.loadConfiguration()); assertNull(repository.loadSecrets())
                    assertTrue(keys.entries.isEmpty())
                    authorizeMutation(security)
                    val fresh = checkNotNull(com.lavasec.app.security.MutationAuthorizationWitness.capture(security,
                        com.lavasec.app.foundation.security.ProtectedSurface.APP_SETTINGS))
                    val saved = repository.saveWhileCurrent(rotation(true),
                        ChainedUpstreamSaveExpectation.AbsentPendingImport("fresh"), fresh::isCurrent)
                        as ChainedUpstreamSaveResult.Committed
                    assertEquals(1L, saved.identity.generation)
                    assertEquals("fresh", repository.loadConfiguration()?.pendingImportId)
                }
            } finally { store.releaseUpdate.complete(Unit); security.close() }
        }
    }

    @Test
    fun `actual editor multi row save and pending confirmation refuse revoked and regranted native turns inside DataStore`() = runTest {
        for (pending in listOf(false, true)) for (regrant in listOf(false, true)) {
            val store = GatedPreferencesStore(); val keys = InMemoryKeyItemStore()
            val repository = repository(keys, store)
            val originals = if (pending) listOf(rotation(true)) else listOf(
                // Enabled entry/exit hops must satisfy the real literal endpoint contract.
                rotation(true, "192.0.2.10", 0x11), rotation(true, "198.51.100.20", 0x22))
            val saved = if (pending) repository.save(originals.single(), ChainedUpstreamSaveExpectation.AbsentPendingImport("editor"))
                else repository.saveSelection(originals.map { ChainedUpstreamSelectionRow(it.configuration, null, it) },
                    ChainedUpstreamSaveExpectation.Absent)
            val committed = saved as ChainedUpstreamSaveResult.Committed
            val previous = checkNotNull(repository.loadConfiguration())
            val previousKeys = keys.entries.mapValues { it.value.copyOf() }
            val owner = com.lavasec.app.platform.ChainedConfigurationCommandOwner(repository,
                onFullTunnelCommittedWhileCurrent = { _, current -> current() })
            assertTrue(owner.beginEditor("editor", committed.identity.generation) is com.lavasec.app.platform.ChainedConfigurationEditorResult.Draft)
            if (!pending) owner.stageEditorName("editor", 0, "Reviewed entry")
            val draft = checkNotNull(owner.currentDraft())
            val security = mutationSecurity(protected = true)
            try {
                authorizeMutation(security)
                val witness = checkNotNull(com.lavasec.app.security.MutationAuthorizationWitness.capture(security,
                    com.lavasec.app.foundation.security.ProtectedSurface.APP_SETTINGS))
                store.gateNextUpdate = true
                val saving = async(Dispatchers.Default) { owner.commitEditor("editor", forceNewGeneration = pending,
                    isCurrent = witness::isCurrent) }
                store.updateEntered.await()
                assertTrue(keys.entries.keys.containsAll(previousKeys.keys))
                security.resetAuthenticationTurn()
                if (regrant) authorizeMutation(security)
                store.releaseUpdate.complete(Unit)
                assertEquals(com.lavasec.app.platform.ChainedConfigurationEditorCommitResult.Refused(
                    com.lavasec.app.platform.ChainedConfigurationEditorRefusal.STALE_GENERATION), saving.await())
                assertEquals(previous, repository.loadConfiguration())
                assertEquals(previousKeys.keys, keys.entries.keys)
                previousKeys.forEach { (account, bytes) -> assertArrayEquals(bytes, keys.entries[account]) }
                assertEquals(draft, owner.currentDraft())
                checkNotNull(repository.loadSecrets()).let { material ->
                    try {
                        assertEquals(originals.size, material.rowCount)
                        originals.forEachIndexed { index, original ->
                            assertArrayEquals(original.decodedPrivateKey(), material.rowPrivateKeyCopy(index))
                            assertArrayEquals(original.decodedPresharedKey(), material.rowPresharedKeyCopy(index))
                        }
                    } finally { material.close() }
                }
                authorizeMutation(security)
                val fresh = checkNotNull(com.lavasec.app.security.MutationAuthorizationWitness.capture(security,
                    com.lavasec.app.foundation.security.ProtectedSurface.APP_SETTINGS))
                val retry = owner.commitEditor("editor", forceNewGeneration = pending, isCurrent = fresh::isCurrent)
                    as com.lavasec.app.platform.ChainedConfigurationEditorCommitResult.Committed
                assertEquals(committed.identity.generation + 1, retry.identity.generation)
                assertNull(repository.loadConfiguration()?.pendingImportId)
                assertEquals(setOf(ChainedUpstreamSecretNaming.keyAccount(retry.identity.generation)), keys.entries.keys)
                assertNull(owner.currentDraft())
            } finally { store.releaseUpdate.complete(Unit); security.close() }
        }
    }

    @Test
    fun `actual editor removal preserves exact metadata and keys when native authority retires inside clear`() = runTest {
        val store = GatedPreferencesStore(); val keys = InMemoryKeyItemStore()
        val repository = repository(keys, store)
        val saved = repository.save(rotation(true)) as ChainedUpstreamSaveResult.Committed
        val previous = checkNotNull(repository.loadConfiguration())
        val previousKeys = keys.entries.mapValues { it.value.copyOf() }
        var enabled = true
        var guardedPreferenceWrites = 0
        val preference = object : com.lavasec.app.platform.ChainedEnabledPreferencePort {
            override suspend fun setEnabled(value: Boolean): Boolean {
                enabled = value
                return true
            }
            override suspend fun setEnabledWhileCurrent(value: Boolean, isCurrent: () -> Boolean): Boolean {
                if (!isCurrent()) return false
                enabled = value
                guardedPreferenceWrites++
                return true
            }
        }
        val owner = com.lavasec.app.platform.ChainedConfigurationCommandOwner(repository,
            enabledPreference = preference, loadEnabled = { enabled })
        assertTrue(owner.beginEditor("remove", saved.identity.generation) is com.lavasec.app.platform.ChainedConfigurationEditorResult.Draft)
        owner.stageEditorRemoval("remove", 0)
        val security = mutationSecurity(protected = true)
        try {
            authorizeMutation(security)
            val witness = checkNotNull(com.lavasec.app.security.MutationAuthorizationWitness.capture(security,
                com.lavasec.app.foundation.security.ProtectedSurface.APP_SETTINGS))
            store.gateNextUpdate = true
            val removing = async(Dispatchers.Default) { owner.commitEditor("remove", isCurrent = witness::isCurrent) }
            store.updateEntered.await()
            assertFalse("Removal durably disables the admitted VPN preference before clearing metadata", enabled)
            assertEquals(1, guardedPreferenceWrites)
            security.resetAuthenticationTurn(); authorizeMutation(security)
            store.releaseUpdate.complete(Unit)
            assertEquals(com.lavasec.app.platform.ChainedConfigurationEditorCommitResult.Refused(
                com.lavasec.app.platform.ChainedConfigurationEditorRefusal.STALE_GENERATION), removing.await())
            assertEquals(previous, repository.loadConfiguration())
            assertEquals(previousKeys.keys, keys.entries.keys)
            previousKeys.forEach { (account, bytes) -> assertArrayEquals(bytes, keys.entries[account]) }
            assertTrue(checkNotNull(owner.currentDraft()).rows.isEmpty())
            val fresh = checkNotNull(com.lavasec.app.security.MutationAuthorizationWitness.capture(security,
                com.lavasec.app.foundation.security.ProtectedSurface.APP_SETTINGS))
            assertEquals(com.lavasec.app.platform.ChainedConfigurationEditorCommitResult.Removed,
                owner.commitEditor("remove", isCurrent = fresh::isCurrent))
            assertNull(repository.loadConfiguration()); assertTrue(keys.entries.isEmpty()); assertNull(owner.currentDraft())
        } finally { store.releaseUpdate.complete(Unit); security.close() }
    }

    private suspend fun authorizeMutation(security: com.lavasec.app.security.SecurityOwner) {
        for (surface in listOf(com.lavasec.app.foundation.security.ProtectedSurface.APP_UNLOCK,
            com.lavasec.app.foundation.security.ProtectedSurface.APP_SETTINGS)) {
            assertTrue(security.requireAuthentication(surface) { request ->
                assertTrue(security.completeAuthentication(request.id, "1234"))
            })
        }
    }

    private fun kotlinx.coroutines.test.TestScope.mutationSecurity(protected: Boolean): com.lavasec.app.security.SecurityOwner {
        val verifier = object : com.lavasec.app.security.PasscodeVerifierPort {
            override fun availability() = com.lavasec.app.security.PasscodeVerifierAvailability.Available
            override fun credentialState() = if (protected) com.lavasec.app.security.PasscodeCredentialState.Available
                else com.lavasec.app.security.PasscodeCredentialState.Absent
            override fun verify(code: String) = if (code == "1234") com.lavasec.app.security.PasscodeVerification.Verified
                else com.lavasec.app.security.PasscodeVerification.Wrong
            override fun configure(code: String) = false
            override fun remove() = false
        }
        val preferences = object : com.lavasec.app.security.SecurityPreferencesPort {
            var saved = com.lavasec.app.security.SecurityPreferences(enabled = protected,
                protectedSurfaces = setOf(com.lavasec.app.foundation.security.ProtectedSurface.APP_UNLOCK,
                    com.lavasec.app.foundation.security.ProtectedSurface.APP_SETTINGS))
            override fun load() = saved
            override fun save(preferences: com.lavasec.app.security.SecurityPreferences) { saved = preferences }
        }
        return com.lavasec.app.security.SecurityOwner(verifier, preferences, backgroundScope)
    }

    private fun rotation(withPresharedKey: Boolean = false, host: String = "vpn.example.com", keyFill: Int = 0x11): ChainedUpstreamRotation {
        fun key(fill: Int): String =
            Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })
        val conf = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${key(keyFill)}")
            appendLine("Address = 10.7.0.2/32")
            appendLine("DNS = 10.64.0.1")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${key(0x42)}")
            appendLine("AllowedIPs = 0.0.0.0/0")
            appendLine("Endpoint = $host:51820")
            if (withPresharedKey) appendLine("PresharedKey = ${key(0x33)}")
        }
        return ChainedUpstreamConfParser.rotation(conf)
    }

    private class InMemoryKeyItemStore : ChainedUpstreamKeyItemStore {
        val entries = LinkedHashMap<String, ByteArray>()
        val callerThreads = ConcurrentLinkedQueue<Thread>()
        var failNextAdd = false
        var failAddNumber: Int? = null
        var failLoads = false
        var failEnumeration = false
        var failDeleteAccount: String? = null
        var ignoreDeletes = false
        private var addCalls = 0

        override fun add(value: ByteArray, account: String): Boolean {
            addCalls++
            callerThreads += Thread.currentThread()
            if (failNextAdd) {
                failNextAdd = false
                return false
            }
            if (addCalls == failAddNumber) return false
            if (entries.containsKey(account)) return false
            entries[account] = value.copyOf()
            return true
        }

        override fun load(account: String): ByteArray? {
            callerThreads += Thread.currentThread()
            if (failLoads) return null
            return entries[account]?.copyOf()
        }

        override fun delete(account: String): Boolean {
            callerThreads += Thread.currentThread()
            if (account == failDeleteAccount) return false
            if (!ignoreDeletes) entries.remove(account)
            return true
        }

        override fun accounts(): List<String> {
            callerThreads += Thread.currentThread()
            if (failEnumeration) throw IOException("simulated enumeration failure")
            return entries.keys.toList()
        }
    }

    /**
     * A DataStore whose next [updateData] can be held at the commit point. This forces the
     * interleaving the shared rotation lock exists to prevent: one writer parked at publish while
     * another instance attempts a save.
     */
    private class GatedPreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        val updateEntered = CompletableDeferred<Unit>()
        val releaseUpdate = CompletableDeferred<Unit>()
        var gateNextUpdate = false

        override val data: Flow<Preferences> get() = state

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            if (gateNextUpdate) {
                gateNextUpdate = false
                updateEntered.complete(Unit)
                releaseUpdate.await()
            }
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    /**
     * A DataStore whose reads fail on demand. The repository's UI flow degrades this to "not
     * configured", but `save` must read the authoritative record directly and refuse.
     */
    private class FlakyReadPreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        var failReads = false
        var failWrites = false

        override val data: Flow<Preferences> = flow {
            if (failReads) throw IOException("simulated read failure")
            emitAll(state)
        }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            if (failWrites) throw IOException("simulated write failure")
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private companion object {
        const val SAVED_AT_EPOCH_MILLIS = 1_700_000_000_000L
    }
}
