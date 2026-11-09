package com.lavasec.app.reactnative

import com.lavasec.app.data.AcceptedSeededSource
import com.lavasec.app.data.CachedCatalogSourceAcquirer
import com.lavasec.app.data.CatalogSourceAcquirer
import com.lavasec.app.data.SeededSourceCache
import com.lavasec.app.data.SeededSourceDescriptor
import com.lavasec.app.data.SeededSourceHttpResponse
import com.lavasec.app.data.SeededSourceTransport
import com.lavasec.app.foundation.filter.*
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.platform.*
import com.lavasec.app.product.*
import com.lavasec.app.toLibraryResult
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real bytes -> native acquisition/compiler -> mutation/library owner -> production wire. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CustomBlocklistProductionFailureTest {
    @Test
    fun `save and apply name actual malformed and HTTP failures while preserving the resident artifact`() = runTest {
        for (mode in listOf("network-malformed", "cached-malformed", "http")) {
            val root = createTempDirectory("custom-production-failure").toFile()
            val compiler = FilterCompiler(File(root, "compile"))
            val library = FilterLibraryDraft(
                listOf(FilterProfileDraft("filter", "Filter", null, emptySet(),
                    ManualDomainRules(blockedDomains = setOf("previous.example")))),
                "filter", MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK,
            )
            val revision = "fixture"
            val catalogHash = "a".repeat(64)
            val previous = compiler.compile(FilterCompileRequest(
                ArtifactConfigurationIdentity(1, canonicalFilterConfigurationFingerprint(library, revision, catalogHash)),
                emptyList(), revision, catalogHash, 100,
                manualBlockRules = listOf(DomainRule.parse("previous.example")),
            ))
            val staging = CredentialFilterArtifactStagingVerifier.verify(previous.file, previous.compilationReceipt)
                as CredentialFilterArtifactStagingResult.Verified
            val initial = ConfirmedFilterState(library, ConfigurationIdentity(1, previous.identity.configuration.fingerprint),
                true, staging.verifiedArtifact)
            staging.close()
            val bytes = "bad syntax / path\n-invalid.example\n".encodeToByteArray()
            val acquirer: CatalogSourceAcquirer = if (mode == "cached-malformed") {
                // A digest-correct legacy cache entry still has to pass the actual compiler.
                CatalogSourceAcquirer { descriptors, _ -> descriptors.map { descriptor ->
                    val file = File(root, "legacy-${descriptor.id}").apply { writeBytes(bytes) }
                    AcceptedSeededSource(descriptor, sha256(bytes), bytes.size.toLong(), file)
                } }
            } else {
                CachedCatalogSourceAcquirer(SeededSourceCache(File(root, "cache"))) {
                    SeededSourceTransport {
                        if (mode == "http") SeededSourceHttpResponse(503, null, bytes.inputStream())
                        else SeededSourceHttpResponse.ok(bytes)
                    }
                }
            }
            var publications = 0
            val coordinator = TransactionalFilterMutationCoordinator(
                initial, FilterCatalogCompileAuthority(revision, catalogHash, emptyList(), maxRules = FeatureLimits.PLUS.maxFilterRules),
                AcceptedFilterSourceAuthority { requested, refresh ->
                    // Acquisition streams real files on IO. Its real deadline must not be driven
                    // by runTest's virtual clock while a read awaits the IO pool.
                    withContext(Dispatchers.IO) {
                        acquirer.acquire(requested.map { SeededSourceDescriptor(it.id, requireNotNull(it.sourceUrl), it.format) }, refresh)
                            .map(AcceptedSeededSource::asFilterPayload)
                    }
                },
                FilterMutationPreparer { plan ->
                    compiler.compile(plan.compileRequest).close()
                    error("a malformed custom source must never prepare a candidate")
                },
                FilterMutationPublisher { _, _ -> publications++; FilterMutationPublishResult.Committed },
            )
            val port = object : FilterLibraryPort {
                override fun currentLibrary() = coordinator.confirmedState.value.configuration
                override suspend fun apply(desired: FilterLibraryDraft, admission: FeatureLimitAdmission) =
                    coordinator.apply(FilterMutationRequest(1, desired, admission = admission)).toLibraryResult()
                override suspend fun restoreDefaults(admission: FeatureLimitAdmission) =
                    FilterLibraryMutationResult.Superseded
            }
            val owner = FilterLibraryOwner(port, this, StandardTestDispatcher(testScheduler), limits = { FeatureLimits.PLUS })
            try {
                owner.beginEdit("filter")
                val added = owner.addCustomBlocklistToDraft("filter", "My List", "https://lists.example.org/private.txt",
                    BlocklistFormat.AUTO) as CustomBlocklistEditResult.Applied
                assertFalse(owner.commitDraft("filter"))
                val failed = (owner.state.value as FilterLibraryOwner.State.Failed).failure as FilterLibraryOwner.Failure.Source
                assertEquals(added.source.id, failed.sourceId)
                assertEquals("My List", failed.sourceName)
                assertEquals(if (mode == "http") CustomBlocklistSourceFailure.HTTP_STATUS else CustomBlocklistSourceFailure.PARSE_FAILED,
                    failed.failure)
                val wire = JSONObject(customBlocklistSourceFailureWireResult(failed.sourceName, failed.failure, failed.sourceId))
                assertEquals("rejected", wire.getString("status"))
                assertEquals(if (mode == "http") "custom-blocklist-fetch-http" else "custom-blocklist-parse-failed", wire.getString("reason"))
                assertEquals(added.source.id, wire.getString("sourceID"))
                assertEquals("My List", wire.getString("value"))
                assertFalse(wire.toString().contains("private.txt"))
                assertEquals(0, publications)
                assertSame(initial, coordinator.confirmedState.value)
                assertTrue(owner.hasUnsavedDraft("filter"))
                FilterArtifact.open(previous.file, previous.sha256, previous.identity).use {
                    assertEquals(FilterDecisionReason.BLOCKLIST, it.snapshot.decide("previous.example").reason)
                }
            } finally { owner.close(); previous.close(); root.deleteRecursively() }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
