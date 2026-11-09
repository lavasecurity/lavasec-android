package com.lavasec.app.product

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.billing.AndroidPlusPlanPersistence
import com.lavasec.app.data.CredentialChainedUpstreamConfigurationRepository
import com.lavasec.app.foundation.chained.ChainedUpstreamConfParser
import com.lavasec.app.platform.ChainedUpstreamKeyItemStore
import com.lavasec.app.platform.FilterMutationPublishResult
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ChainedQaDnsPublicationAuthorityTest {
    @get:Rule val directory = TemporaryFolder()
    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = directory.root
    }

    @Test fun `final native guard rejects durable preference and profile changes without relying on flows`(): Unit = runTest {
        for (change in listOf("consent", "enabled", "profile", "plan")) {
            val store = MemoryStore(); val keys = MemoryKeys()
            val preferences = AndroidPlusPlanPersistence(context())
            preferences.save(true); preferences.setChainedEnabled(false); preferences.setChainedDnsFallbackEnabled(false)
            val repository = CredentialChainedUpstreamConfigurationRepository(context(), keys, providedStore = store)
            val captured = checkNotNull(ChainedQaDnsPublicationAuthority(preferences, repository).capture())
            assertTrue(captured.chained.canEditDns)
            when (change) {
                "consent" -> preferences.setChainedDnsFallbackEnabled(true)
                "enabled" -> preferences.setChainedEnabled(true)
                "profile" -> repository.save(rotation())
                "plan" -> preferences.save(false)
            }
            var writes = 0
            assertTrue(captured.guard.publish { writes++; FilterMutationPublishResult.Committed } is FilterMutationPublishResult.Failed)
            assertEquals(0, writes)
        }
    }

    @Test fun `short final publication excludes real consent and cross-instance profile writers`(): Unit = runTest {
        val store = MemoryStore(); val keys = MemoryKeys()
        val preferences = AndroidPlusPlanPersistence(context()); preferences.save(true)
        val repository = CredentialChainedUpstreamConfigurationRepository(context(), keys, providedStore = store)
        val second = CredentialChainedUpstreamConfigurationRepository(context(), keys, providedStore = store)
        val captured = checkNotNull(ChainedQaDnsPublicationAuthority(preferences, repository).capture())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val publication = async(Dispatchers.IO) { captured.guard.publish {
            entered.complete(Unit); release.await(); FilterMutationPublishResult.Committed
        } }
        entered.await()
        val preferenceStarted = CompletableDeferred<Unit>(); val profileStarted = CompletableDeferred<Unit>()
        val preferenceWrite = async(Dispatchers.IO) { preferenceStarted.complete(Unit); preferences.setChainedEnabled(true) }
        val profileWrite = async(Dispatchers.IO) { profileStarted.complete(Unit); second.save(rotation()) }
        try {
            preferenceStarted.await(); profileStarted.await()
            assertFalse(preferenceWrite.isCompleted); assertFalse(profileWrite.isCompleted)
        } finally { release.complete(Unit) }
        assertEquals(FilterMutationPublishResult.Committed, publication.await())
        assertTrue(preferenceWrite.await())
        profileWrite.await()
        assertTrue(preferences.loadEnabled()); assertNotNull(repository.loadConfiguration())
        // The prior capability is stale after either durable writer wins.
        assertTrue(captured.guard.publish { fail("must not write"); FilterMutationPublishResult.Committed } is FilterMutationPublishResult.Failed)
    }

    @Test fun `full chain and split without consent never admit DNS publication`(): Unit = runTest {
        val preferences = AndroidPlusPlanPersistence(context()); preferences.save(true); preferences.setChainedEnabled(true)
        val repository = CredentialChainedUpstreamConfigurationRepository(context(), MemoryKeys(), providedStore = MemoryStore())
        for (split in listOf(false, true)) {
            repository.save(rotation(split))
            val captured = checkNotNull(ChainedQaDnsPublicationAuthority(preferences, repository).capture())
            assertFalse(captured.chained.canEditDns)
            assertTrue(captured.guard.publish { fail("locked chain must refuse"); FilterMutationPublishResult.Committed } is FilterMutationPublishResult.Failed)
        }
    }

    private fun rotation(split: Boolean = false) = ChainedUpstreamConfParser.rotation("""
        [Interface]
        PrivateKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 1 })}
        Address = 10.7.0.2/32
        DNS = 10.64.0.1
        [Peer]
        PublicKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 2 })}
        AllowedIPs = ${if (split) "10.0.0.0/8" else "0.0.0.0/0"}
        Endpoint = 192.0.2.1:51820
    """.trimIndent())

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()
        override val data = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }
    private class MemoryKeys : ChainedUpstreamKeyItemStore {
        private val values = mutableMapOf<String, ByteArray>()
        override fun add(value: ByteArray, account: String): Boolean { if (account in values) return false; values[account] = value.copyOf(); return true }
        override fun load(account: String) = values[account]?.copyOf()
        override fun delete(account: String): Boolean { values.remove(account)?.fill(0); return true }
        override fun accounts() = values.keys.toList()
    }
}
