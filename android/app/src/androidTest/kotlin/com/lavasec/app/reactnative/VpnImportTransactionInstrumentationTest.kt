package com.lavasec.app.reactnative

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.data.CredentialChainedUpstreamConfigurationRepository
import com.lavasec.app.data.CredentialChainedPreferenceStore
import com.lavasec.app.chained.AndroidChainedDeviceEligibilityStore
import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.platform.ChainedConfigurationCommandOwner
import com.lavasec.app.platform.ChainedConfigurationEditorCommitResult
import com.lavasec.app.platform.ChainedConfigurationEditorRefusal
import com.lavasec.app.platform.ChainedConfigurationEditorResult
import com.lavasec.app.platform.ChainedComposition
import com.lavasec.app.platform.ChainedEntitlementPort
import com.lavasec.app.platform.ChainedConfigurationReview
import com.lavasec.app.platform.ChainedConfigurationSaveResult
import com.lavasec.app.platform.ChainedUpstreamConfigurationRepository
import com.lavasec.app.platform.ChainedUpstreamIdentity
import com.lavasec.app.platform.ChainedUpstreamKeyItemStore
import com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult
import com.lavasec.app.platform.ChainedUpstreamSaveExpectation
import com.lavasec.app.platform.ChainedUpstreamSaveResult
import com.lavasec.app.platform.ChainedUpstreamSecretMaterial
import com.lavasec.app.platform.ChainedUpstreamSecretStaging
import com.lavasec.app.platform.VpnConfigurationPick
import com.lavasec.app.platform.VpnConfigurationPickSource
import com.lavasec.app.platform.VpnFirstConfigurationBegin
import com.lavasec.app.platform.VpnFirstConfigurationCoordinator
import com.lavasec.app.platform.StoredChainedUpstreamConfiguration
import com.lavasec.app.foundation.chained.ChainedUpstreamRotation
import com.lavasec.app.security.AndroidKeystoreChainedKeyItemStore
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production Preferences DataStore and AndroidKeyStore; documents are controlled fixture text. */
@RunWith(AndroidJUnit4::class)
class VpnImportTransactionInstrumentationTest {
    @Test
    fun cancelledOrInvalidFirstDocumentNeverPersistsRotationOrKey() = runBlocking {
        withFixture { fixture ->
            val owner = ChainedConfigurationCommandOwner(fixture.repository)
            val cancelled = VpnFirstConfigurationCoordinator(owner, VpnConfigurationPickSource { VpnConfigurationPick.Cancelled })
            assertEquals(VpnFirstConfigurationBegin.Cancelled, cancelled.beginOrImport("cancelled", 0))
            assertNull(fixture.repository.loadConfiguration())
            assertTrue(fixture.keys.accounts().isEmpty())

            val invalid = VpnFirstConfigurationCoordinator(owner, VpnConfigurationPickSource { VpnConfigurationPick.Read("not a WireGuard document") })
            assertTrue(invalid.beginOrImport("invalid", 0) is VpnFirstConfigurationBegin.Rejected)
            assertNull(fixture.repository.loadConfiguration())
            assertTrue(fixture.keys.accounts().isEmpty())
        }
    }

    @Test
    fun pendingOwnershipSurvivesNativeOwnerRecreationAndExactRecoveryRemovesConfigurationAndKey() = runBlocking {
        withFixture { fixture ->
            fixture.importFirst("pending-editor")
            val pending = checkNotNull(fixture.repository.loadConfiguration())
            assertEquals("pending-editor", pending.pendingImportId)
            assertEquals(ChainedConfigurationIssue.UNAVAILABLE, fixture.repository.configurationIssue())
            fixture.assertPendingCannotActivate()
            assertNotNull(fixture.repository.loadSecrets()?.also { it.close() })

            // Drop every writer/owner and reopen the same OS-backed records. This covers restart
            // reconstruction; the CI harness can separately force-stop at this transaction boundary.
            fixture.reopen()
            val recovered = checkNotNull(fixture.repository.loadConfiguration())
            assertEquals(pending, recovered)
            assertEquals(
                ChainedUpstreamPendingRemovalResult.Removed,
                fixture.repository.removePendingImport("pending-editor", recovered.identity),
            )
            assertNull(fixture.repository.loadConfiguration())
            assertNull(fixture.repository.loadSecrets())
            assertTrue(fixture.keys.accounts().isEmpty())
            assertEquals(
                ChainedUpstreamPendingRemovalResult.Superseded,
                fixture.repository.removePendingImport("pending-editor", recovered.identity),
            )
        }
    }

    @Test
    fun unchangedSavePublishesDurableSuccessorAndStaleRecoveryCannotEraseIt() = runBlocking {
        withFixture { fixture ->
            val owner = fixture.importFirst("saved-editor")
            val pending = checkNotNull(fixture.repository.loadConfiguration())
            assertTrue(owner.beginEditor("foreign-editor", pending.identity.generation) is ChainedConfigurationEditorResult.Refused)
            assertEquals("saved-editor", owner.currentDraft()?.id)
            val committed = owner.commitEditor("saved-editor", forceNewGeneration = true)
                as? ChainedConfigurationEditorCommitResult.Committed
                ?: throw AssertionError("unchanged pending import Save failed")
            assertEquals(pending.identity.generation + 1, committed.identity.generation)
            assertEquals(pending.identity.fingerprint, committed.identity.fingerprint)
            assertNull(owner.currentDraft())

            fixture.reopen()
            val stored = checkNotNull(fixture.repository.loadConfiguration())
            assertEquals(committed.identity, stored.identity)
            assertNull(stored.pendingImportId)
            assertNull(fixture.repository.configurationIssue())
            val secrets = checkNotNull(fixture.repository.loadSecrets())
            try {
                assertEquals(stored.identity.generation, secrets.generation)
                assertArrayEquals(ByteArray(32) { 0x11 }, secrets.privateKeyCopy())
                assertArrayEquals(ByteArray(32) { 0x33 }, secrets.presharedKeyCopy())
            } finally { secrets.close() }
            assertEquals(
                ChainedUpstreamPendingRemovalResult.Superseded,
                fixture.repository.removePendingImport("saved-editor", pending.identity),
            )
            assertEquals(stored, fixture.repository.loadConfiguration())
        }
    }

    @Test
    fun failedSaveKeepsPendingPairAndDraftRetryableThenCancelRemovesExactTransaction() = runBlocking {
        withFixture { fixture ->
            val owner = fixture.importFirst("failed-editor")
            val pending = checkNotNull(fixture.repository.loadConfiguration())
            fixture.keys.refuseAdds = true
            assertEquals(
                ChainedConfigurationEditorCommitResult.Rejected(ChainedUpstreamSecretStaging.Failure.KEY_WRITE_FAILED),
                owner.commitEditor("failed-editor", forceNewGeneration = true),
            )
            assertNotNull(owner.currentDraft())
            assertEquals(pending, fixture.repository.loadConfiguration())
            assertNotNull(fixture.repository.loadSecrets()?.also { it.close() })
            fixture.keys.refuseAdds = false
            owner.cancelEditor("failed-editor")
            assertEquals(
                ChainedUpstreamPendingRemovalResult.Removed,
                fixture.repository.removePendingImport("failed-editor", pending.identity),
            )
            assertNull(fixture.repository.loadConfiguration())
            assertTrue(fixture.keys.accounts().isEmpty())
        }
    }

    @Test
    fun staleEditorOrPendingIdentityCannotRemoveAnotherImportOrCommittedReplacement() = runBlocking {
        withFixture { fixture ->
            val owner = fixture.importFirst("owner-editor")
            val pending = checkNotNull(fixture.repository.loadConfiguration())
            assertEquals(
                ChainedUpstreamPendingRemovalResult.Superseded,
                fixture.repository.removePendingImport("foreign-editor", pending.identity),
            )
            val replacement = owner.save(owner.importReview(configuration(endpoint = "replacement.example:51820")),
                ChainedUpstreamSaveExpectation.Generation(pending.identity.generation)) as ChainedConfigurationSaveResult.Committed
            assertEquals(
                ChainedUpstreamPendingRemovalResult.Superseded,
                fixture.repository.removePendingImport("owner-editor", pending.identity),
            )
            assertEquals(replacement.identity, fixture.repository.loadConfiguration()?.identity)
            assertNull(fixture.repository.loadConfiguration()?.pendingImportId)
        }
    }

    @Test
    fun failedKeystoreCleanupRetainsPendingWitnessAcrossOwnerRecreationForRetry() = runBlocking {
        withFixture { fixture ->
            fixture.importFirst("retry-editor")
            val pending = checkNotNull(fixture.repository.loadConfiguration())
            fixture.keys.refuseDeletes = true
            assertTrue(fixture.repository.removePendingImport("retry-editor", pending.identity)
                is ChainedUpstreamPendingRemovalResult.Failed)
            assertEquals(pending, fixture.repository.loadConfiguration())
            fixture.keys.refuseDeletes = false
            fixture.reopen()
            assertEquals(ChainedUpstreamPendingRemovalResult.Removed,
                fixture.repository.removePendingImport("retry-editor", pending.identity))
            assertNull(fixture.repository.loadConfiguration())
            assertTrue(fixture.keys.accounts().isEmpty())
        }
    }

    @Test
    fun failureAfterNativePublicationBeforeReopenCompensatesConfigurationAndKey() = runBlocking {
        withFixture { fixture ->
            val boundary = AfterPendingPublicationRepository(fixture.repository) {
                throw IOException("controlled reopen failure")
            }
            val owner = ChainedConfigurationCommandOwner(boundary)
            try {
                firstImportCoordinator(owner).beginOrImport("failed-reopen", 0)
                throw AssertionError("reopen failure must propagate")
            } catch (failure: IOException) {
                assertEquals("controlled reopen failure", failure.message)
            }

            assertEquals(listOf("failed-reopen" to checkNotNull(boundary.publishedIdentity)), boundary.pendingRemovals)
            assertEquals(listOf(ChainedUpstreamPendingRemovalResult.Removed), boundary.removalResults)
            assertNull(owner.currentDraft())
            assertNull(fixture.repository.loadConfiguration())
            assertNull(fixture.repository.loadSecrets())
            assertTrue(fixture.keys.accounts().isEmpty())
        }
    }

    @Test
    fun cancellationAfterNativePublicationBeforeReopenCompletesCompensation() = runBlocking {
        withFixture { fixture ->
            coroutineScope {
                val reopening = CompletableDeferred<Unit>()
                val boundary = AfterPendingPublicationRepository(fixture.repository) {
                    reopening.complete(Unit)
                    awaitCancellation()
                }
                val owner = ChainedConfigurationCommandOwner(boundary)
                val request = launch { firstImportCoordinator(owner).beginOrImport("cancelled-reopen", 0) }
                withTimeout(30_000) { reopening.await() }
                val pending = checkNotNull(fixture.repository.loadConfiguration())
                assertEquals("cancelled-reopen", pending.pendingImportId)
                fixture.assertPendingCannotActivate()

                withTimeout(30_000) { request.cancelAndJoin() }

                assertTrue(request.isCancelled)
                assertEquals(listOf("cancelled-reopen" to pending.identity), boundary.pendingRemovals)
                assertEquals(listOf(ChainedUpstreamPendingRemovalResult.Removed), boundary.removalResults)
                assertNull(owner.currentDraft())
                assertNull(fixture.repository.loadConfiguration())
                assertNull(fixture.repository.loadSecrets())
                assertTrue(fixture.keys.accounts().isEmpty())
            }
        }
    }

    @Test
    fun failedReopenCompensationRetainsNativePendingTagForRecreatedOwnerRetry() = runBlocking {
        withFixture { fixture ->
            val boundary = AfterPendingPublicationRepository(fixture.repository) {
                fixture.keys.refuseDeletes = true
                throw IOException("controlled reopen failure")
            }
            val owner = ChainedConfigurationCommandOwner(boundary)
            try {
                firstImportCoordinator(owner).beginOrImport("retry-reopen", 0)
                throw AssertionError("reopen failure must propagate")
            } catch (_: IOException) { }
            val pending = checkNotNull(fixture.repository.loadConfiguration())
            assertEquals("retry-reopen", pending.pendingImportId)
            assertTrue(boundary.removalResults.single() is ChainedUpstreamPendingRemovalResult.Failed)
            assertNull(owner.currentDraft())
            assertNotNull(fixture.repository.loadSecrets()?.also { it.close() })
            fixture.assertPendingCannotActivate()

            fixture.keys.refuseDeletes = false
            fixture.reopen()
            val recreatedOwner = ChainedConfigurationCommandOwner(fixture.repository)
            assertNull(recreatedOwner.currentDraft())
            assertEquals(pending, fixture.repository.loadConfiguration())
            assertEquals(ChainedUpstreamPendingRemovalResult.Removed,
                recreatedOwner.cancelFirstImport("retry-reopen", pending.identity))
            assertNull(fixture.repository.loadConfiguration())
            assertNull(fixture.repository.loadSecrets())
            assertTrue(fixture.keys.accounts().isEmpty())
        }
    }

    @Test
    fun refusedReopenCompensationPreservesDurableSavedSuccessorAndItsKeys() = runBlocking {
        withFixture { fixture ->
            var successorIdentity: ChainedUpstreamIdentity? = null
            val boundary = AfterPendingPublicationRepository(fixture.repository) {
                val pending = checkNotNull(fixture.repository.loadConfiguration())
                val successorOwner = ChainedConfigurationCommandOwner(fixture.repository)
                val saved = successorOwner.save(
                    successorOwner.importReview(configuration(endpoint = "successor.example:51820")),
                    ChainedUpstreamSaveExpectation.Generation(pending.identity.generation),
                ) as? ChainedConfigurationSaveResult.Committed
                    ?: throw AssertionError("controlled successor save failed")
                successorIdentity = saved.identity
            }
            val owner = ChainedConfigurationCommandOwner(boundary)

            assertEquals(VpnFirstConfigurationBegin.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
                firstImportCoordinator(owner).beginOrImport("superseded-reopen", 0))

            assertEquals(listOf("superseded-reopen" to checkNotNull(boundary.publishedIdentity)), boundary.pendingRemovals)
            assertEquals(listOf(ChainedUpstreamPendingRemovalResult.Superseded), boundary.removalResults)
            assertNull(owner.currentDraft())
            fixture.reopen()
            val successor = checkNotNull(fixture.repository.loadConfiguration())
            assertEquals(checkNotNull(successorIdentity), successor.identity)
            assertNull(successor.pendingImportId)
            assertEquals("successor.example", successor.configuration.endpointHost)
            assertNull(fixture.repository.configurationIssue())
            val secrets = checkNotNull(fixture.repository.loadSecrets())
            try {
                assertEquals(successor.identity.generation, secrets.generation)
                assertArrayEquals(ByteArray(32) { 0x11 }, secrets.privateKeyCopy())
                assertArrayEquals(ByteArray(32) { 0x33 }, secrets.presharedKeyCopy())
            } finally { secrets.close() }
        }
    }

    @Test
    fun recreationRetainsLegacyPreferenceMarkerWithItsExactAccountAndGeneration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "vpn-legacy-marker-${UUID.randomUUID()}"
        val store = SharedPreferencesPendingVpnImportStore(context, preferencesName)
        assertTrue(store.clear())
        try {
            val pending = PendingVpnImport("account-fixture", "legacy-editor", 7)
            assertTrue(store.save(pending))
            val recreated = SharedPreferencesPendingVpnImportStore(context, preferencesName)
            assertEquals(pending, recreated.load())
            assertEquals(PendingVpnImportRecovery.REMOVE_IMPORTED, pendingVpnImportRecovery(
                checkNotNull(recreated.load()), "account-fixture", 7,
            ))
            assertEquals(PendingVpnImportRecovery.RETAIN, pendingVpnImportRecovery(
                checkNotNull(recreated.load()), "other-account", 7,
            ))
            assertTrue(recreated.clear())
            assertNull(SharedPreferencesPendingVpnImportStore(context, preferencesName).load())
        } finally { store.clear() }
    }

    @Test
    fun orderedNativeDraftPreservesDistinctCredentialsThroughSaveSwapDisableAndReopen() = runBlocking {
        withFixture { fixture ->
            val owner = fixture.importFirst("ordered")
            assertTrue(owner.stageEditorConfiguration("ordered", 0, configuration("192.0.2.1:51820"), "Entry") is ChainedConfigurationEditorResult.Draft)
            assertTrue(owner.stageEditorConfiguration("ordered", 1, configuration("192.0.2.2:51820", 0x22), "Exit") is ChainedConfigurationEditorResult.Draft)
            val committed = owner.commitEditor("ordered", true) as ChainedConfigurationEditorCommitResult.Committed
            fixture.reopen()
            val stored = checkNotNull(fixture.repository.loadConfiguration())
            assertEquals(committed.identity, stored.identity)
            assertNull(stored.pendingImportId)
            assertEquals(listOf("Entry", "Exit"), stored.configuration.orderedHops.map { it.displayName })
            withSecrets(fixture) { keys ->
                assertEquals(2, keys.rowCount)
                assertArrayEquals(ByteArray(32) { 0x11 }, keys.rowPrivateKeyCopy(0))
                assertArrayEquals(ByteArray(32) { 0x22 }, keys.rowPrivateKeyCopy(1))
            }
            val recreated = ChainedConfigurationCommandOwner(fixture.repository)
            recreated.beginEditor("cancel-swap", committed.identity.generation)
            recreated.swapEditorRows("cancel-swap")
            recreated.cancelEditor("cancel-swap")
            assertEquals(stored, fixture.repository.loadConfiguration())
            recreated.beginEditor("save-swap", committed.identity.generation)
            recreated.swapEditorRows("save-swap")
            val swapped = recreated.commitEditor("save-swap") as ChainedConfigurationEditorCommitResult.Committed
            assertTrue(recreated.setRowEnabled(0, false, swapped.identity.generation) is ChainedConfigurationEditorCommitResult.Committed)
            fixture.reopen()
            val disabled = fixture.repository.loadConfiguration()!!
            assertEquals(listOf("Exit", "Entry"), disabled.configuration.orderedHops.map { it.displayName })
            assertEquals(listOf(false, true), disabled.configuration.orderedHops.map { it.isEnabled })
            withSecrets(fixture) { keys ->
                assertEquals(1, keys.rowCount)
                assertArrayEquals(ByteArray(32) { 0x11 }, keys.privateKeyCopy())
            }
            assertTrue(ChainedConfigurationCommandOwner(fixture.repository).setRowEnabled(0, true, disabled.identity.generation)
                is ChainedConfigurationEditorCommitResult.Committed)
            withSecrets(fixture) { keys ->
                assertEquals(2, keys.rowCount)
                assertArrayEquals(ByteArray(32) { 0x22 }, keys.rowPrivateKeyCopy(0))
                assertArrayEquals(ByteArray(32) { 0x11 }, keys.rowPrivateKeyCopy(1))
            }
        }
    }

    private suspend fun withSecrets(fixture: Fixture, block: (ChainedUpstreamSecretMaterial) -> Unit) {
        val keys = checkNotNull(fixture.repository.loadSecrets())
        try { block(keys) } finally { keys.close() }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(InstrumentationRegistry.getInstrumentation().targetContext)
        try { block(fixture) } finally { fixture.close() }
    }

    private fun firstImportCoordinator(owner: ChainedConfigurationCommandOwner) =
        VpnFirstConfigurationCoordinator(owner,
            VpnConfigurationPickSource { VpnConfigurationPick.Read(configuration()) })

    /** Injects a one-shot reopen boundary; all durable writes/removals use the native repository. */
    private class AfterPendingPublicationRepository(
        private val delegate: ChainedUpstreamConfigurationRepository,
        private val beforeReopen: suspend () -> Unit,
    ) : ChainedUpstreamConfigurationRepository by delegate {
        private var awaitingReopen = false
        var publishedIdentity: ChainedUpstreamIdentity? = null
            private set
        val pendingRemovals = mutableListOf<Pair<String, ChainedUpstreamIdentity>>()
        val removalResults = mutableListOf<ChainedUpstreamPendingRemovalResult>()

        override suspend fun save(
            rotation: ChainedUpstreamRotation,
            expectation: ChainedUpstreamSaveExpectation,
        ): ChainedUpstreamSaveResult {
            val saved = delegate.save(rotation, expectation)
            if (saved is ChainedUpstreamSaveResult.Committed &&
                expectation is ChainedUpstreamSaveExpectation.AbsentPendingImport) {
                publishedIdentity = saved.identity
                awaitingReopen = true
            }
            return saved
        }

        override suspend fun loadConfiguration(): StoredChainedUpstreamConfiguration? {
            if (awaitingReopen) {
                awaitingReopen = false
                beforeReopen()
            }
            return delegate.loadConfiguration()
        }

        override suspend fun removePendingImport(
            editorId: String,
            identity: ChainedUpstreamIdentity,
        ): ChainedUpstreamPendingRemovalResult {
            pendingRemovals += editorId to identity
            return delegate.removePendingImport(editorId, identity).also { removalResults += it }
        }
    }

    internal class Fixture(private val context: Context, private val id: String = UUID.randomUUID().toString()) {
        private val storeName = "vpn-transaction-$id"
        private val alias = "vpn-transaction-key-$id"
        private val preferences = "vpn-transaction-ciphertext-$id"
        private val preferenceName = "vpn-transaction-enabled-$id"
        val keys = FailingAddKeyStore(AndroidKeystoreChainedKeyItemStore(context, alias, preferences))
        private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var repository = repository()
            private set

        private fun repository() = CredentialChainedUpstreamConfigurationRepository(
            credentialProtectedContext = context, keyItemStore = keys, storeName = storeName, scope = scope,
        )

        suspend fun importFirst(editorId: String): ChainedConfigurationCommandOwner {
            val owner = ChainedConfigurationCommandOwner(repository)
            val coordinator = VpnFirstConfigurationCoordinator(owner,
                VpnConfigurationPickSource { VpnConfigurationPick.Read(configuration()) })
            val result = coordinator.beginOrImport(editorId, 0) as? VpnFirstConfigurationBegin.Draft
                ?: throw AssertionError("fixture WireGuard first import failed")
            assertTrue(result.imported)
            assertFalse(result.draft.changed)
            assertTrue(owner.importReview(configuration()) is ChainedConfigurationReview.Ready)
            return owner
        }

        suspend fun reopen() {
            scope.coroutineContext.job.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            repository = repository()
        }

        suspend fun release() = scope.coroutineContext.job.cancelAndJoin()

        suspend fun assertPendingCannotActivate() {
            val preference = CredentialChainedPreferenceStore(context, preferenceName)
            assertTrue(preference.setChainedEnabled(true))
            val composition = ChainedComposition.production(
                entitlement = ChainedEntitlementPort { throw AssertionError("pending import consulted entitlement") },
                eligibilityStore = AndroidChainedDeviceEligibilityStore(context),
                configurationRepository = repository,
                preferenceStore = preference,
                clientDnsOverTunnelAvailable = true,
            )
            assertEquals(ChainedConfigurationIssue.UNAVAILABLE, composition.configurationIssueForStart())
            assertNull(composition.resolveActivation())
        }

        suspend fun close() {
            keys.refuseAdds = false
            keys.refuseDeletes = false
            try { repository.removeAll() } finally {
                scope.coroutineContext.job.cancelAndJoin()
                context.preferencesDataStoreFile(storeName).delete()
                context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
                context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }
        }
    }

    internal class FailingAddKeyStore(private val delegate: ChainedUpstreamKeyItemStore) : ChainedUpstreamKeyItemStore by delegate {
        @Volatile var refuseAdds = false
        @Volatile var refuseDeletes = false
        override fun add(value: ByteArray, account: String) = !refuseAdds && delegate.add(value, account)
        override fun delete(account: String) = !refuseDeletes && delegate.delete(account)
    }

    private companion object {
        fun configuration(endpoint: String = "vpn.example:51820", privateFill: Int = 0x11): String = """
            [Interface]
            PrivateKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { privateFill.toByte() })}
            Address = 10.7.0.2/32
            DNS = 10.64.0.1
            [Peer]
            PublicKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 })}
            PresharedKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 0x33 })}
            AllowedIPs = 0.0.0.0/0
            Endpoint = $endpoint
        """.trimIndent()
    }
}
