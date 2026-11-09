package com.lavasec.app

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.billing.NativePlusEntitlementOwner
import com.lavasec.app.backup.ProductBackupRestoreAuthority
import com.lavasec.app.backup.ProductionBackupRestorePublisher
import com.lavasec.app.backup.toLoggingSettings
import com.lavasec.app.foundation.backup.*
import com.lavasec.app.billing.PlusPlanPersistence
import com.lavasec.app.billing.VerifiedPlusReading
import com.lavasec.app.data.BundledBlocklistCatalog
import com.lavasec.app.data.CredentialConfigurationRepository
import com.lavasec.app.directboot.DeviceProtectedArtifactPublisher
import com.lavasec.app.foundation.config.AppConfiguration
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.filter.ManualRuleTarget
import com.lavasec.app.foundation.filter.ManualRuleEditResult
import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.DirectBootMirrorResult
import com.lavasec.app.platform.DirectBootSnapshotAuthenticator
import com.lavasec.app.platform.DirectBootSnapshotFreshnessAuthority
import com.lavasec.app.platform.DirectBootSnapshotKeyRotationResult
import com.lavasec.app.product.FilterProfileSwitchResult
import com.lavasec.app.storage.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class ProductionManualDomainAdmissionTest {
    @Test fun `native whole restore admits current Free Plus and FOSS manual quotas`() = runBlocking {
        for (limits in listOf(FeatureLimits.FREE, FeatureLimits.PLUS, FeatureLimits.FOSS)) {
            withAuthority(limits) { f ->
                val limit = if (limits == FeatureLimits.FOSS) 1_001 else limits.maxAllowedDomains
                for (allowed in listOf(true, false)) {
                    val before = f.current()
                    if (limits != FeatureLimits.FOSS) {
                        val excessive = f.withDomains(limit + 1, allowed)
                        assertFalse(f.library.replaceAll(excessive, clearEditing = false, expectedLibrary = before))
                        assertEquals(before, f.current())
                    }
                    val accepted = f.withDomains(limit, allowed)
                    assertTrue(f.library.replaceAll(accepted, clearEditing = false, expectedLibrary = before))
                    assertEquals(accepted, f.current())
                }
            }
        }
    }

    @Test fun `lapse preserves identical active rules and inactive retained data but refuses new activation`() = runBlocking {
        withAuthority(FeatureLimits.PLUS) { f ->
            assertTrue(f.library.replaceAll(f.withDomains(1_000), clearEditing = false))
            f.library.discardEditingSessions()
            f.lapse()
            val activeId = f.current().activeProfileId
            assertTrue(f.library.renameFilter(activeId, "Retained after lapse"))
            assertEquals(1_000, f.current().profile(activeId)!!.manualRules.allowedDomains.size)
            val inactive = f.current().profiles.first { it.id != activeId }
                .withManualRules(rules(1_001))
            val desired = f.current().withProfile(inactive)
            assertTrue(f.library.replaceAll(desired, clearEditing = false))
            assertEquals(1_001, f.current().profile(inactive.id)!!.manualRules.allowedDomains.size)
            assertTrue(f.hosts.profileSwitch.apply(inactive.id) is FilterProfileSwitchResult.Rejected)
            assertEquals(activeId, f.current().activeProfileId)
            assertFalse(f.library.replaceAll(f.withDomains(1_001), clearEditing = false))
        }
    }

    @Test fun `lapsed native draft and authority commit incremental subset reductions above quotas`() = runBlocking {
        withAuthority(FeatureLimits.PLUS) { f ->
            val activeId = f.current().activeProfileId
            val allowed = (0 until 1_000).mapTo(linkedSetOf()) { "allow-$it.example" }
            val blocked = (0 until 1_000).mapTo(linkedSetOf()) { "block-$it.example" }
            val initial = f.current().withProfile(f.current().profile(activeId)!!
                .withManualRules(ManualDomainRules(allowed, blocked)))
            assertTrue(f.library.replaceAll(initial, clearEditing = false))
            f.library.discardEditingSessions()
            f.lapse()
            assertTrue(f.library.beginEdit(activeId))
            assertTrue(f.library.removeDomainFromDraft(activeId, "allow-0.example", ManualRuleTarget.ALLOWED))
            assertTrue(f.library.removeDomainFromDraft(activeId, "block-0.example", ManualRuleTarget.BLOCKED))
            assertNull(f.library.reviewRejectionFor(activeId))
            val review = checkNotNull(f.library.captureReview(activeId))
            assertTrue(f.library.commitDraft(activeId, review))
            assertEquals(999, f.current().profile(activeId)!!.manualRules.allowedDomains.size)
            assertEquals(999, f.current().profile(activeId)!!.manualRules.blockedDomains.size)
            // The lower-level restore/native mutation path must enforce the same set relation.
            val before = f.current()
            val reduced = before.withProfile(before.profile(activeId)!!.withManualRules(
                ManualDomainRules(allowed - setOf("allow-0.example", "allow-1.example"),
                    blocked - setOf("block-0.example", "block-1.example"))))
            assertTrue(f.library.replaceAll(reduced, clearEditing = false, expectedLibrary = before))
            val reducedIdentity = f.committed().configurationIdentity
            val mixed = reduced.withProfile(reduced.profile(activeId)!!.withManualRules(
                ManualDomainRules(reduced.profile(activeId)!!.manualRules.allowedDomains - "allow-2.example" + "new.example",
                    reduced.profile(activeId)!!.manualRules.blockedDomains)))
            assertFalse(f.library.replaceAll(mixed, clearEditing = false, expectedLibrary = reduced))
            assertEquals(reduced, f.current())
            assertEquals(reducedIdentity, f.committed().configurationIdentity)
        }
    }

    @Test fun `lapsed editor and direct domain action reject cross-shelf additions while removal remains savable`() = runBlocking {
        withAuthority(FeatureLimits.PLUS) { f ->
            assertTrue(f.library.replaceAll(f.withDomains(1_000), clearEditing = false))
            f.library.discardEditingSessions()
            f.lapse()
            val before = f.current()
            val activeId = before.activeProfileId
            val generation = f.committed().configurationIdentity
            val action = f.hosts.domainActions.request("new-block.example",
                com.lavasec.app.product.domain.DomainAction.BLOCK)
                as com.lavasec.app.product.domain.DomainActionRequestResult.Rejected
            assertEquals(com.lavasec.app.foundation.filter.ManualRuleRejection.ALLOWED_LIMIT_REACHED, action.ruleRejection)
            val mixed = before.withProfile(before.profile(activeId)!!.withManualRules(
                ManualDomainRules(before.profile(activeId)!!.manualRules.allowedDomains - "domain-0.example",
                    setOf("new-block.example"))))
            assertFalse(f.library.replaceAll(mixed, clearEditing = false, expectedLibrary = before))
            assertEquals(generation, f.committed().configurationIdentity)
            assertTrue(f.library.beginEdit(activeId))
            assertTrue(f.library.removeDomainFromDraft(activeId, "domain-0.example", ManualRuleTarget.ALLOWED))
            val rejected = f.library.addDomainToDraft(activeId, "new-block.example", ManualRuleTarget.BLOCKED)
                as ManualRuleEditResult.Rejected
            assertEquals(com.lavasec.app.foundation.filter.ManualRuleRejection.ALLOWED_LIMIT_REACHED, rejected.reason)
            assertNull(f.library.reviewRejectionFor(activeId))
            assertTrue(f.library.commitDraft(activeId, checkNotNull(f.library.captureReview(activeId))))
            assertEquals(999, f.current().profile(activeId)!!.manualRules.allowedDomains.size)
            assertTrue(f.current().profile(activeId)!!.manualRules.blockedDomains.isEmpty())
        }
    }

    @Test fun `exact compensation restores prior data after a lapse at forward commit and cannot replay`() = runBlocking {
        withAuthority(FeatureLimits.PLUS) { f ->
            assertTrue(f.library.replaceAll(f.withDomains(1_000), clearEditing = false))
            f.library.discardEditingSessions()
            val prior = f.current()
            val forward = f.withDomains(1)
            // The entitlement changes after the durable forward publication, before its owner
            // returns the receipt to the restore transaction. Compensation captures Free anew.
            f.afterLibraryApplied = { f.afterLibraryApplied = null; runBlocking { f.lapse() } }
            assertTrue(f.library.replaceAll(forward, clearEditing = false, expectedLibrary = prior))
            assertEquals(FeatureLimits.FREE, f.plan.state.value.limits)
            assertFalse(f.library.replaceAll(prior, clearEditing = false, expectedLibrary = forward))
            assertFalse(f.library.rollbackReplacement(prior.withProfile(prior.profiles.first().withManualRules(rules(2))), forward))
            assertFalse(f.library.rollbackReplacement(prior, null))
            assertTrue(f.library.rollbackReplacement(prior, forward))
            assertEquals(prior, f.current())
            assertFalse(f.library.rollbackReplacement(prior, forward))
        }
    }

    @Test fun `preferences-only restore refusal acknowledges an unchanged library without a receipt`() = runBlocking {
        withAuthority(FeatureLimits.FREE) { f ->
            // Start from a previously restored library: backup mapping canonicalizes platform
            // profile metadata and legacy resolver routes, so a raw onboarding seed is not a no-op.
            val normalized = BackupPayloadMapper.restore(BackupPayloadMapper.toPayload(f.current()
                .withResolverPlan(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY))).library
            assertTrue(f.library.replaceAll(normalized, clearEditing = false))
            f.library.discardEditingSessions()
            val prior = f.current()
            val generation = f.committed().configurationIdentity
            val preferences = BackupRetainedPreferences(false, false, false, false, false)
            val basis = BackupRestoreBasis(accountID = null, configurationGeneration = 7)
            val native = ProductBackupRestoreAuthority(
                library = { f.current() }, logging = { preferences.toLoggingSettings() }, currentGeneration = { 7L },
                replaceLibraryWith = { library, expected -> f.library.replaceAll(library, clearEditing = false, expectedLibrary = expected) },
                rollbackLibraryWith = { library, expected -> f.library.rollbackReplacement(library, expected) },
                setLoggingWith = { false },
            )
            val backup = BackupRestoreOwner(ProductionBackupRestorePublisher(native, Dispatchers.Unconfined))
            val prepared = backup.prepare(BackupRestoreUnlock.Unlocked(BackupPayloadMapper.toPayload(prior,
                preferences.copy(keepNetworkActivity = true))), BackupRecoveryMethod.RECOVERY_CODE,
                prior, preferences, basis) as BackupRestorePreparation.Prepared
            assertEquals(prior, prepared.plan.library)
            assertEquals(BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted),
                backup.confirm(prepared.reviewID, false, basis, prior))
            assertEquals(prior, f.current())
            assertEquals(generation, f.committed().configurationIdentity)
        }
    }

    @Test fun `compensation refuses a later generation even when exact forward content returns`() = runBlocking {
        withAuthority(FeatureLimits.PLUS) { f ->
            assertTrue(f.library.replaceAll(f.withDomains(1_000), clearEditing = false))
            f.library.discardEditingSessions()
            val prior = f.current()
            val forward = f.withDomains(1)
            assertTrue(f.library.replaceAll(forward, clearEditing = false, expectedLibrary = prior))
            val active = forward.profile(forward.activeProfileId)!!
            assertTrue(f.library.renameFilter(active.id, "Intermediate"))
            assertTrue(f.library.renameFilter(active.id, active.name))
            assertEquals(forward, f.current())
            f.lapse()
            assertFalse(f.library.rollbackReplacement(prior, forward))
            assertEquals(forward, f.current())
        }
    }

    private suspend fun withAuthority(limits: FeatureLimits, run: suspend (Fixture) -> Unit) = withTimeout(60_000) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("manual-quota-").toFile()
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val f = Fixture(context, root, scope, limits)
        try {
            f.plan.initialize()
            f.repository.completeOnboarding(AppConfiguration.default())
            val persisted = f.repository.state.first()
            f.authority.provisionInitial(checkNotNull(persisted.configuration), checkNotNull(persisted.configurationIdentity))
            f.hosts = f.authority.createMutationHosts(f.committed(), scope) { _, _ -> f.afterLibraryApplied?.invoke() }
            run(f)
        } finally {
            f.close()
            f.authority.closePreparedCache()
            scope.cancel()
            context.preferencesDataStoreFile(f.name).delete()
            root.deleteRecursively()
        }
    }

    private class Fixture(context: Context, root: File, scope: CoroutineScope, limits: FeatureLimits) {
        val name = "manual-quota-${UUID.randomUUID()}"
        val catalog = BundledBlocklistCatalog(1, emptyList(), emptyList(), "initial", "c".repeat(64), "{}")
        val plan = NativePlusEntitlementOwner(object : PlusPlanPersistence {
            override suspend fun load() = limits == FeatureLimits.PLUS
            override suspend fun save(hasPlus: Boolean) = Unit
        }, localCapabilities = limits == FeatureLimits.FOSS)
        val repository = CredentialConfigurationRepository(context, name, scope, catalog,
            freshnessAuthority = TestFreshness(), mirrorPublisher = { _, _ -> DirectBootMirrorResult.MirrorUnavailable })
        private val files = JvmArtifactPublicationFileSystem
        private val store = CredentialFilterConfigurationStore(root.resolve("authority"), CredentialFilterConfigurationAuthority(),
            CredentialFilterArtifactPublicationStore(root.resolve("authority"), CredentialFilterArtifactPublicationAuthority(), fileSystem = files),
            files, FilterCompiler.MAX_SOURCE_RULES, allowQaProbeSets = true)
        val authority = ProductionFilterAuthority(context, catalog, repository, store,
            DeviceProtectedArtifactPublisher(root.resolve("device")), plusOwner = plan, allowQaProbeSets = true)
        lateinit var hosts: ProductionFilterMutationHosts
        val library get() = hosts.library
        fun close() { if (::hosts.isInitialized) library.close() }
        var afterLibraryApplied: (() -> Unit)? = null
        suspend fun committed() = (store.readAuthoritative() as CredentialFilterConfigurationRead.Available).state
        fun current() = library.currentLibrary()
        fun withDomains(count: Int, allowed: Boolean = true) = current().let { library ->
            library.withProfile(library.profile(library.activeProfileId)!!.withManualRules(rules(count, allowed)))
        }
        suspend fun lapse() { assertTrue(plan.apply(VerifiedPlusReading(false, confidence = EntitlementReadingConfidence.CONFIRMED))) }
    }

    private class TestFreshness : DirectBootSnapshotFreshnessAuthority() {
        private var authenticator: DirectBootSnapshotAuthenticator? = null
        override fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult {
            val next = DirectBootSnapshotAuthenticator { "a".repeat(64) }
            authenticator = next
            return rotated(next)
        }
        override fun currentAuthenticatorOrNull() = authenticator
    }

    companion object {
        private fun rules(count: Int, allowed: Boolean = true): ManualDomainRules {
            val domains = (0 until count).mapTo(linkedSetOf()) { "domain-$it.example" }
            return if (allowed) ManualDomainRules(allowedDomains = domains) else ManualDomainRules(blockedDomains = domains)
        }
    }
}
