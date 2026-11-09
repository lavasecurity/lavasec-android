package com.lavasec.app.storage

import com.lavasec.app.billing.NativePlusEntitlementOwner
import com.lavasec.app.billing.PlusPlanPersistence
import com.lavasec.app.billing.VerifiedPlusReading
import com.lavasec.app.billing.openPlanCheckedFilterLease
import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence
import com.lavasec.app.foundation.subscription.FeatureLimits

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.foundation.filter.canonicalFilterArtifactIdentitySha256
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.CredentialFilterArtifactStagingResult
import com.lavasec.app.platform.CredentialFilterArtifactStagingVerifier
import com.lavasec.app.platform.FilterRuntimeLeaseRead
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialFilterConfigurationRuntimeReadTest {
    @Test
    fun `release refuses the retained QA runtime and only a fresh ordinary CAS restores it`(): Unit = runBlocking {
        for (failure in listOf("none", "visit", "storage")) withRoot { root ->
            var failWrite = false
            val fs = object : ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
                override fun replaceFile(source: File, target: File) {
                    if (failWrite && target.name == "authoritative.filter-configuration") throw IOException("controlled final write failure")
                    JvmArtifactPublicationFileSystem.replaceFile(source, target)
                }
            }
            val authorities = Authorities(root, fs)
            val probes = com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED
            val initial = fixture(root.resolve("qa"), baseConfiguration().withQaProbeSet(probes), 1L)
            val ordinary = fixture(root.resolve("ordinary"), initial.state.configuration.withQaProbeSet(null), 2L)
            try {
                val debug = authorities.store(10, allowQaProbeSets = true)
                assertTrue(debug.initialize(initial.state, initial.prepared) is CredentialFilterConfigurationInitializationResult.Initialized)
                debug.readRuntimeAvailable().use { carrier ->
                    carrier.takeLease().use { lease ->
                        val expected = listOf(FilterDecision.DEFAULT_ALLOW, FilterDecision(com.lavasec.app.foundation.filter.FilterAction.BLOCK, com.lavasec.app.foundation.filter.FilterDecisionReason.BLOCKLIST), FilterDecision(com.lavasec.app.foundation.filter.FilterAction.ALLOW, com.lavasec.app.foundation.filter.FilterDecisionReason.LOCAL_ALLOWLIST), FilterDecision(com.lavasec.app.foundation.filter.FilterAction.BLOCK, com.lavasec.app.foundation.filter.FilterDecisionReason.THREAT_GUARDRAIL))
                        probes.allDomains.forEachIndexed { index, host ->
                            assertEquals(expected[index], availableValue(lease.withDecisions { it.decide(host) }))
                        }
                        assertEquals(FilterDecision.DEFAULT_ALLOW, availableValue(lease.withDecisions { it.decide("sub.${probes.guardrailDomain}") }))
                    }
                }
                val release = authorities.store(10)
                assertEquals(initial.state, (release.readAuthoritative() as CredentialFilterConfigurationRead.Available).state)
                assertRuntimeUnavailable(release.readAuthoritativeRuntime(), CredentialFilterConfigurationUnavailableReason.QA_PROBES_DISABLED)
                failWrite = failure == "storage"
                val mutation = com.lavasec.app.platform.PreparedFilterMutation(
                    candidate = ordinary.state,
                    compilationReceipt = ordinary.prepared.compilationReceipt,
                    publicationArtifact = ordinary.prepared,
                    publicationAdmission = { failure != "visit" },
                    cleanup = {},
                )
                mutation.use {
                    val result = release.commit(1L, it)
                    if (failure == "none") {
                        assertTrue(result is com.lavasec.app.platform.FilterMutationPublishResult.Committed)
                        release.readRuntimeAvailable().use { carrier ->
                            assertEquals(ordinary.state, carrier.state)
                            carrier.takeLease().use { lease -> probes.allDomains.forEach { host ->
                                assertEquals(FilterDecision.DEFAULT_ALLOW, availableValue(lease.withDecisions { it.decide(host) }))
                            } }
                        }
                        // A stale release cleanup cannot supersede the already adopted generation.
                        assertTrue(release.commit(1L, it) is com.lavasec.app.platform.FilterMutationPublishResult.Superseded)
                    } else {
                        assertFalse(result is com.lavasec.app.platform.FilterMutationPublishResult.Committed)
                        assertEquals(initial.state, (release.readAuthoritative() as CredentialFilterConfigurationRead.Available).state)
                        assertRuntimeUnavailable(release.readAuthoritativeRuntime(), CredentialFilterConfigurationUnavailableReason.QA_PROBES_DISABLED)
                    }
                }
            } finally { initial.close(); ordinary.close() }
        }
    }

    @Test
    fun `release cannot initialize or publish a new QA artifact`(): Unit = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val ordinary = fixture(root.resolve("ordinary"), baseConfiguration(), 1L)
            val qa = fixture(root.resolve("qa"), baseConfiguration().withQaProbeSet(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED), 2L)
            try {
                val release = authorities.store(10)
                assertTrue(release.initialize(qa.state, qa.prepared) is CredentialFilterConfigurationInitializationResult.Failed)
                assertTrue(release.initialize(ordinary.state, ordinary.prepared) is CredentialFilterConfigurationInitializationResult.Initialized)
                com.lavasec.app.platform.PreparedFilterMutation(qa.state, qa.prepared.compilationReceipt, qa.prepared, cleanup = {}).use {
                    assertFalse(release.commit(1L, it) is com.lavasec.app.platform.FilterMutationPublishResult.Committed)
                }
                assertEquals(ordinary.state, (release.readAuthoritative() as CredentialFilterConfigurationRead.Available).state)
            } finally { ordinary.close(); qa.close() }
        }
    }

    @Test
    fun `Plus artifact survives lapse on disk and in resident lease but cannot open a new Free lease`() = runBlocking {
        withRoot { root ->
            val count = FeatureLimits.FREE.maxFilterRules + 1
            val initial = fixture(root.resolve("large"), baseConfiguration(), 1L,
                List(count) { DomainRule.parse("r$it.example") })
            try {
                assertTrue(initial.state.credentialUnlockRequired)
                var durable = true
                val plan = NativePlusEntitlementOwner(object : PlusPlanPersistence {
                    override suspend fun load() = durable
                    override suspend fun save(hasPlus: Boolean) { durable = hasPlus }
                })
                plan.initialize()
                val store = Authorities(root).store(FeatureLimits.PLUS.maxFilterRules)
                assertTrue(store.initialize(initial.state, initial.prepared) is CredentialFilterConfigurationInitializationResult.Initialized)
                val lease = openPlanCheckedFilterLease(plan, initial.state.configurationIdentity, { false }, store::readAuthoritativeRuntime)
                try {
                    assertTrue(plan.apply(VerifiedPlusReading(false, confidence = EntitlementReadingConfidence.CONFIRMED)))
                    // Existing resident state is still readable. Lapse never wipes a saved artifact.
                    assertEquals(initial.state, lease.confirmedState)
                    assertTrue(lease.withDecisions { it.decide("r0.example") } is FilterRuntimeLeaseRead.Available)
                    assertTrue(store.readAuthoritative() is CredentialFilterConfigurationRead.Available)
                    assertThrows(IllegalStateException::class.java) { runBlocking {
                        openPlanCheckedFilterLease(plan, initial.state.configurationIdentity, { false }, store::readAuthoritativeRuntime)
                    } }
                    assertThrows(IllegalStateException::class.java) { runBlocking {
                        openPlanCheckedFilterLease(null, initial.state.configurationIdentity, { false }, store::readAuthoritativeRuntime)
                    } }
                } finally { lease.close() }
            } finally { initial.close() }
        }
    }

    @Test
    fun `unavailable plan refuses a new runtime before opening private artifact storage`(): Unit = runBlocking {
        val plan = NativePlusEntitlementOwner(object : PlusPlanPersistence {
            override suspend fun load(): Boolean = throw IOException("unreadable")
            override suspend fun save(hasPlus: Boolean) = error("must not write")
        })
        assertFalse(plan.initialize())
        assertThrows(IllegalStateException::class.java) { runBlocking {
            openPlanCheckedFilterLease(plan, ConfigurationIdentity(1L, "a".repeat(64)), { false }) {
                error("must not open an artifact without an available plan")
            }
        } }
    }

    @Test
    fun `exact runtime read transfers one usable lease and carrier close cannot retire it`() =
        runBlocking {
            withRoot { root ->
                val authorities = Authorities(root)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                try {
                    val store = authorities.store()
                    assertTrue(
                        store.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )

                    val carrier = store.readAuthoritativeRuntime()
                    assertTrue(carrier is CredentialFilterConfigurationRuntimeRead.Available)
                    carrier as CredentialFilterConfigurationRuntimeRead.Available
                    assertEquals(initial.state, carrier.state)

                    val lease = carrier.takeLease()
                    try {
                        assertEquals(initial.state, lease.confirmedState)
                        assertThrows(IllegalStateException::class.java) {
                            carrier.takeLease()
                        }

                        carrier.close()
                        assertEquals(
                            FilterDecision.DEFAULT_ALLOW,
                            availableValue(
                                lease.withDecisions { decisions ->
                                    decisions.decide("runtime-read.example")
                                },
                            ),
                        )

                        lease.close()
                        assertSame(
                            FilterRuntimeLeaseRead.Unavailable,
                            lease.withDecisions { decisions ->
                                decisions.decide("runtime-read.example")
                            },
                        )
                    } finally {
                        lease.close()
                    }
                } finally {
                    initial.close()
                }
            }
        }

    @Test
    fun `restart reopens the exact authoritative runtime lease`() = runBlocking {
        withRoot { root ->
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            try {
                val writer = Authorities(root)
                assertTrue(
                    writer.store().initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )

                val restarted = Authorities(root).store()
                val carrier = restarted.readRuntimeAvailable()
                try {
                    assertEquals(initial.state, carrier.state)
                    val lease = carrier.takeLease()
                    try {
                        assertEquals(initial.state, lease.confirmedState)
                        assertEquals(
                            FilterDecision.DEFAULT_ALLOW,
                            availableValue(
                                lease.withDecisions { decisions ->
                                    decisions.decide("restart-runtime.example")
                                },
                            ),
                        )
                    } finally {
                        lease.close()
                    }
                } finally {
                    carrier.close()
                }
            } finally {
                initial.close()
            }
        }
    }

    @Test
    fun `cancellation before runtime admission opens no artifact`() = runBlocking {
        withRoot { root ->
            val fileSystem = TrackingArtifactFileSystem()
            val authorities = Authorities(root, fileSystem)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            try {
                val store = authorities.store()
                assertTrue(
                    store.initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                fileSystem.resetMetrics()
                authorities.configurationAuthority.mutex.lock()
                val escaped = AtomicReference<CredentialFilterConfigurationRuntimeRead?>()
                try {
                    val read = launch(start = CoroutineStart.UNDISPATCHED) {
                        escaped.set(store.readAuthoritativeRuntime())
                    }

                    read.cancelAndJoin()

                    assertTrue(read.isCancelled)
                    assertEquals(null, escaped.get())
                    assertEquals(0, fileSystem.artifactOpenCalls.get())
                    assertEquals(0, fileSystem.artifactCloseCalls.get())
                } finally {
                    authorities.configurationAuthority.mutex.unlock()
                }
            } finally {
                initial.close()
            }
        }
    }

    @Test
    fun `cancellation after runtime admission returns one close-owned carrier`() = runBlocking {
        withRoot { root ->
            val fileSystem = TrackingArtifactFileSystem()
            val authorities = Authorities(root, fileSystem)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            try {
                val store = authorities.store()
                assertTrue(
                    store.initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                fileSystem.resetMetrics()
                val openGate = fileSystem.armOpenGate()
                val escaped = AtomicReference<CredentialFilterConfigurationRuntimeRead?>()
                val read = launch(start = CoroutineStart.UNDISPATCHED) {
                    escaped.set(store.readAuthoritativeRuntime())
                }
                try {
                    openGate.entered.awaitOrFail()
                    read.cancel()
                } finally {
                    openGate.release.countDown()
                    read.join()
                }

                assertTrue(read.isCancelled)
                val carrier = escaped.get()
                assertTrue(carrier is CredentialFilterConfigurationRuntimeRead.Available)
                carrier as CredentialFilterConfigurationRuntimeRead.Available
                assertEquals(1, fileSystem.artifactOpenCalls.get())
                assertEquals(0, fileSystem.artifactCloseCalls.get())

                carrier.close()
                assertEquals(1, fileSystem.artifactCloseCalls.get())
                assertFalse(requireNotNull(fileSystem.lastOpenedChannel.get()).isOpen)
            } finally {
                initial.close()
            }
        }
    }

    @Test
    fun `missing authoritative generation cannot create a runtime lease`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            try {
                val store = authorities.store()
                assertTrue(
                    store.initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                val generation = authorities.artifactStore.generationDirectoryForTesting(
                    initial.prepared.sha256,
                )
                assertTrue(File(generation, "artifact.lsfa").delete())

                val read = store.readAuthoritativeRuntime()

                assertRuntimeUnavailable(
                    read = read,
                    reason = CredentialFilterConfigurationUnavailableReason.ARTIFACT_UNAVAILABLE,
                    artifactReason = FilterArtifactUnavailableReason.ARTIFACT_MISSING,
                )
            } finally {
                initial.close()
            }
        }
    }

    @Test
    fun `trusted rule budget is enforced before runtime lease activation`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(
                root = root.resolve("initial"),
                configuration = baseConfiguration(),
                generation = 1L,
                guardrailRules = listOf(DomainRule.parse("budget-runtime.example")),
            )
            try {
                assertTrue(
                    authorities.store(maxRules = 1).initialize(
                        initial.state,
                        initial.prepared,
                    ) is CredentialFilterConfigurationInitializationResult.Initialized,
                )

                val read = authorities.store(maxRules = 0).readAuthoritativeRuntime()

                assertRuntimeUnavailable(
                    read = read,
                    reason = CredentialFilterConfigurationUnavailableReason.ARTIFACT_UNAVAILABLE,
                    artifactReason = FilterArtifactUnavailableReason.RULE_TABLE_INVALID,
                )
            } finally {
                initial.close()
            }
        }
    }

    @Test
    fun `post-open authoritative identity mismatch closes the exact artifact once`() =
        runBlocking {
            withRoot { root ->
                val fileSystem = TrackingArtifactFileSystem()
                val authorities = Authorities(root, fileSystem)
                val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
                try {
                    val store = authorities.store()
                    assertTrue(
                        store.initialize(initial.state, initial.prepared) is
                            CredentialFilterConfigurationInitializationResult.Initialized,
                    )
                    val authoritative =
                        AuthoritativeFilterConfigurationRecord.fromConfirmedState(initial.state)
                    val fingerprint = authoritative.configurationIdentity.fingerprint
                    val mismatchedFingerprint =
                        (if (fingerprint.first() == '0') "1" else "0") + fingerprint.drop(1)
                    store.authoritativeRecordFileForTesting.writeBytes(
                        AuthoritativeFilterConfigurationRecordCodec.encode(
                            AuthoritativeFilterConfigurationRecord(
                                configuration = authoritative.configuration,
                                configurationIdentity = ConfigurationIdentity(
                                    generation = authoritative.configurationIdentity.generation,
                                    fingerprint = mismatchedFingerprint,
                                ),
                                credentialUnlockRequired =
                                    authoritative.credentialUnlockRequired,
                                artifactSha256 = authoritative.artifactSha256,
                                artifactIdentitySha256 = authoritative.artifactIdentitySha256,
                            ),
                        ),
                    )
                    fileSystem.resetMetrics()

                    val read = store.readAuthoritativeRuntime()

                    assertRuntimeUnavailable(
                        read = read,
                        reason = CredentialFilterConfigurationUnavailableReason.IDENTITY_MISMATCH,
                    )
                    assertEquals(1, fileSystem.artifactOpenCalls.get())
                    assertEquals(1, fileSystem.artifactCloseCalls.get())
                    assertFalse(requireNotNull(fileSystem.lastOpenedChannel.get()).isOpen)
                } finally {
                    initial.close()
                }
            }
        }

    @Test
    fun `exact read transfer prevents wrapper close from closing the artifact`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            var transferred: FilterArtifact? = null
            try {
                assertTrue(
                    authorities.store().initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                val read = authorities.artifactStore.readExactGeneration(
                    artifactSha256 = initial.state.verifiedArtifact.sha256,
                    identitySha256 = canonicalFilterArtifactIdentitySha256(
                        initial.state.verifiedArtifact.artifactIdentity,
                    ),
                    maxRules = 0,
                )
                assertTrue(read is CredentialFilterArtifactExactRead.Available)
                read as CredentialFilterArtifactExactRead.Available

                transferred = read.takeArtifact()
                read.close()

                assertTrue(transferred.channelIsOpenForTesting)
                assertThrows(IllegalStateException::class.java) { read.takeArtifact() }
                assertThrows(IllegalStateException::class.java) { read.artifact }

                transferred.close()
                assertFalse(transferred.channelIsOpenForTesting)
                read.close()
            } finally {
                if (transferred?.channelIsOpenForTesting == true) transferred.close()
                initial.close()
            }
        }
    }

    @Test
    fun `carrier close before transfer closes the runtime lease exactly once`() = runBlocking {
        withRoot { root ->
            val fileSystem = TrackingArtifactFileSystem()
            val authorities = Authorities(root, fileSystem)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            try {
                val store = authorities.store()
                assertTrue(
                    store.initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                fileSystem.resetMetrics()
                val carrier = store.readRuntimeAvailable()
                assertEquals(1, fileSystem.artifactOpenCalls.get())
                assertEquals(0, fileSystem.artifactCloseCalls.get())

                carrier.close()
                carrier.close()

                assertEquals(1, fileSystem.artifactCloseCalls.get())
                assertFalse(requireNotNull(fileSystem.lastOpenedChannel.get()).isOpen)
                assertThrows(IllegalStateException::class.java) { carrier.takeLease() }
            } finally {
                initial.close()
            }
        }
    }

    @Test
    fun `concurrent and repeated carrier close share one physical close failure`() = runBlocking {
        withRoot { root ->
            val fileSystem = TrackingArtifactFileSystem()
            val authorities = Authorities(root, fileSystem)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            try {
                val store = authorities.store()
                assertTrue(
                    store.initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                fileSystem.resetMetrics()
                val carrier = store.readRuntimeAvailable()
                val physicalFailure = IOException("injected runtime artifact close failure")
                val closeGate = fileSystem.armCloseFailure(physicalFailure)
                val failures = Collections.synchronizedList(mutableListOf<Throwable>())
                val secondStarted = CountDownLatch(1)
                val secondReturned = CountDownLatch(1)
                val first = Thread {
                    try {
                        carrier.close()
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
                val second = Thread {
                    secondStarted.countDown()
                    try {
                        carrier.close()
                    } catch (failure: Throwable) {
                        failures += failure
                    } finally {
                        secondReturned.countDown()
                    }
                }
                try {
                    first.start()
                    closeGate.entered.awaitOrFail()
                    second.start()
                    secondStarted.awaitOrFail()
                    assertFalse(secondReturned.await(100, TimeUnit.MILLISECONDS))
                } finally {
                    closeGate.release.countDown()
                    first.joinOrFail()
                    second.joinOrFail()
                }

                assertEquals(2, failures.size)
                assertTrue(failures.all { it === physicalFailure })
                val repeated = assertThrows(IOException::class.java) { carrier.close() }
                assertSame(physicalFailure, repeated)
                assertEquals(1, fileSystem.artifactCloseCalls.get())
                assertThrows(IllegalStateException::class.java) { carrier.takeLease() }
            } finally {
                initial.close()
            }
        }
    }

    @Test
    fun `checkpoint 18 pointer movement cannot influence exact runtime activation`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            val orphan = fixture(
                root.resolve("orphan"),
                baseConfiguration().withResolverPlan(MilestoneResolverPlan.DEVICE_DNS_ONLY),
                2L,
            )
            try {
                val store = authorities.store()
                assertTrue(
                    store.initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                assertTrue(
                    authorities.artifactStore.publish(
                        prepared = orphan.prepared,
                        expectedCurrentSha256 = null,
                    ) is CredentialFilterArtifactPublicationResult.Published,
                )

                val carrier = store.readAuthoritativeRuntime()
                assertTrue(carrier is CredentialFilterConfigurationRuntimeRead.Available)
                carrier as CredentialFilterConfigurationRuntimeRead.Available
                try {
                    assertEquals(initial.state, carrier.state)
                    val lease = carrier.takeLease()
                    try {
                        assertEquals(initial.state, lease.confirmedState)
                        assertEquals(
                            FilterDecision.DEFAULT_ALLOW,
                            availableValue(
                                lease.withDecisions { decisions ->
                                    decisions.decide("pointer-must-be-ignored.example")
                                },
                            ),
                        )
                    } finally {
                        lease.close()
                    }
                } finally {
                    carrier.close()
                }
            } finally {
                orphan.close()
                initial.close()
            }
        }
    }

    @Test
    fun `corrupt exact generation is unavailable and never yields a lease`() = runBlocking {
        withRoot { root ->
            val authorities = Authorities(root)
            val initial = fixture(root.resolve("initial"), baseConfiguration(), 1L)
            try {
                val store = authorities.store()
                assertTrue(
                    store.initialize(initial.state, initial.prepared) is
                        CredentialFilterConfigurationInitializationResult.Initialized,
                )
                File(
                    authorities.artifactStore.generationDirectoryForTesting(
                        initial.prepared.sha256,
                    ),
                    "artifact.lsfa",
                ).appendBytes(byteArrayOf(0))

                val read = store.readAuthoritativeRuntime()

                assertTrue(read is CredentialFilterConfigurationRuntimeRead.Unavailable)
                read as CredentialFilterConfigurationRuntimeRead.Unavailable
                assertEquals(
                    CredentialFilterConfigurationUnavailableReason.ARTIFACT_UNAVAILABLE,
                    read.reason,
                )
                assertEquals(FilterArtifactUnavailableReason.DIGEST_MISMATCH, read.artifactReason)
            } finally {
                initial.close()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> availableValue(read: FilterRuntimeLeaseRead<T>): T {
        assertTrue(
            "expected an available runtime lease read, got $read",
            read is FilterRuntimeLeaseRead.Available<*>,
        )
        return (read as FilterRuntimeLeaseRead.Available<T>).value
    }

    private suspend fun CredentialFilterConfigurationStore.readRuntimeAvailable():
        CredentialFilterConfigurationRuntimeRead.Available {
        val read = readAuthoritativeRuntime()
        return read as? CredentialFilterConfigurationRuntimeRead.Available
            ?: throw AssertionError("expected an available runtime read, got $read")
    }

    private fun assertRuntimeUnavailable(
        read: CredentialFilterConfigurationRuntimeRead,
        reason: CredentialFilterConfigurationUnavailableReason,
        artifactReason: FilterArtifactUnavailableReason? = null,
    ) {
        if (read is CredentialFilterConfigurationRuntimeRead.Available) {
            read.close()
        }
        assertTrue(
            "expected an unavailable runtime read, got $read",
            read is CredentialFilterConfigurationRuntimeRead.Unavailable,
        )
        read as CredentialFilterConfigurationRuntimeRead.Unavailable
        assertEquals(reason, read.reason)
        assertEquals(artifactReason, read.artifactReason)
    }

    private suspend fun fixture(
        root: File,
        configuration: FilterLibraryDraft,
        generation: Long,
        guardrailRules: List<DomainRule> = emptyList(),
    ): Fixture {
        val identity = ConfigurationIdentity(
            generation = generation,
            fingerprint = canonicalFilterConfigurationFingerprint(
                configuration = configuration,
                catalogRevision = CATALOG_REVISION,
                catalogSha256 = CATALOG_SHA256,
            ),
        )
        val prepared = FilterCompiler(root.resolve("scratch")).compile(
            FilterCompileRequest(
                configurationIdentity = ArtifactConfigurationIdentity(
                    identity.generation,
                    identity.fingerprint,
                ),
                selectedSources = emptyList(),
                catalogRevision = CATALOG_REVISION,
                catalogSha256 = CATALOG_SHA256,
                maxRules = guardrailRules.size + if (configuration.qaProbeSet == null) 0 else 6,
                intentionalEmpty = guardrailRules.isEmpty() && configuration.qaProbeSet == null,
                manualAllowRules = configuration.qaProbeSet?.allowRules.orEmpty(),
                manualBlockRules = configuration.qaProbeSet?.blockRules.orEmpty(),
                guardrailRules = guardrailRules + configuration.qaProbeSet?.guardrailRules.orEmpty(),
            ),
        )
        val verified = CredentialFilterArtifactStagingVerifier.verify(
            prepared.file,
            prepared.compilationReceipt,
        ) as CredentialFilterArtifactStagingResult.Verified
        val state = ConfirmedFilterState(
            configuration = configuration,
            configurationIdentity = identity,
            credentialUnlockRequired = com.lavasec.app.platform.filterRequiresCredentialUnlock(
                configuration, prepared.compilationReceipt.artifactIdentity.ruleCounts.total,
            ),
            verifiedArtifact = verified.verifiedArtifact,
        )
        return Fixture(prepared, verified, state)
    }

    private fun baseConfiguration(): FilterLibraryDraft =
        FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList()))

    private suspend fun withRoot(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("authoritative-runtime-read-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class Authorities(
        private val root: File,
        private val fileSystem: ArtifactPublicationFileSystem =
            JvmArtifactPublicationFileSystem,
    ) {
        private val artifactAuthority = CredentialFilterArtifactPublicationAuthority()
        val configurationAuthority = CredentialFilterConfigurationAuthority()

        val artifactStore = CredentialFilterArtifactPublicationStore(
            rootDirectory = root,
            authority = artifactAuthority,
            fileSystem = fileSystem,
        )

        fun store(maxRules: Int = 0, allowQaProbeSets: Boolean = false): CredentialFilterConfigurationStore =
            CredentialFilterConfigurationStore(
                rootDirectory = root,
                authority = configurationAuthority,
                artifactStore = artifactStore,
                fileSystem = fileSystem,
                maxRules = maxRules,
                allowQaProbeSets = allowQaProbeSets,
            )
    }

    private class TrackingArtifactFileSystem :
        ArtifactPublicationFileSystem by JvmArtifactPublicationFileSystem {
        val artifactOpenCalls = AtomicInteger()
        val artifactCloseCalls = AtomicInteger()
        val lastOpenedChannel = AtomicReference<FileChannel?>()
        private val injectedCloseFailure = AtomicReference<IOException?>()
        private val closeGate = AtomicReference<CloseGate?>()
        private val openGate = AtomicReference<CloseGate?>()

        override fun openRegularFile(file: File, maxBytes: Long): FileChannel {
            artifactOpenCalls.incrementAndGet()
            openGate.get()?.let {
                it.entered.countDown()
                check(it.release.await(5, TimeUnit.SECONDS)) {
                    "timed out waiting to release injected artifact open"
                }
            }
            val delegate = JvmArtifactPublicationFileSystem.openRegularFile(file, maxBytes)
            return TrackingFileChannel(delegate) { channel ->
                artifactCloseCalls.incrementAndGet()
                val gate = closeGate.get()
                gate?.let {
                    it.entered.countDown()
                    check(it.release.await(5, TimeUnit.SECONDS)) {
                        "timed out waiting to release injected artifact close"
                    }
                }
                var delegateFailure: IOException? = null
                try {
                    channel.close()
                } catch (failure: IOException) {
                    delegateFailure = failure
                }
                val injected = injectedCloseFailure.get()
                if (injected != null) {
                    delegateFailure?.let(injected::addSuppressed)
                    throw injected
                }
                delegateFailure?.let { throw it }
            }.also(lastOpenedChannel::set)
        }

        fun resetMetrics() {
            artifactOpenCalls.set(0)
            artifactCloseCalls.set(0)
            lastOpenedChannel.set(null)
            injectedCloseFailure.set(null)
            closeGate.set(null)
            openGate.set(null)
        }

        fun armOpenGate(): CloseGate = CloseGate().also(openGate::set)

        fun armCloseFailure(failure: IOException): CloseGate = CloseGate().also {
            injectedCloseFailure.set(failure)
            closeGate.set(it)
        }
    }

    private class TrackingFileChannel(
        private val delegate: FileChannel,
        private val onClose: (FileChannel) -> Unit,
    ) : FileChannel() {
        override fun read(destination: ByteBuffer): Int = delegate.read(destination)

        override fun read(
            destinations: Array<out ByteBuffer>,
            offset: Int,
            length: Int,
        ): Long = delegate.read(destinations, offset, length)

        override fun write(source: ByteBuffer): Int = delegate.write(source)

        override fun write(
            sources: Array<out ByteBuffer>,
            offset: Int,
            length: Int,
        ): Long = delegate.write(sources, offset, length)

        override fun position(): Long = delegate.position()

        override fun position(newPosition: Long): FileChannel {
            delegate.position(newPosition)
            return this
        }

        override fun size(): Long = delegate.size()

        override fun truncate(size: Long): FileChannel {
            delegate.truncate(size)
            return this
        }

        override fun force(metaData: Boolean) = delegate.force(metaData)

        override fun transferTo(
            position: Long,
            count: Long,
            target: WritableByteChannel,
        ): Long = delegate.transferTo(position, count, target)

        override fun transferFrom(
            source: ReadableByteChannel,
            position: Long,
            count: Long,
        ): Long = delegate.transferFrom(source, position, count)

        override fun read(destination: ByteBuffer, position: Long): Int =
            delegate.read(destination, position)

        override fun write(source: ByteBuffer, position: Long): Int =
            delegate.write(source, position)

        override fun map(
            mode: FileChannel.MapMode,
            position: Long,
            size: Long,
        ): MappedByteBuffer = delegate.map(mode, position, size)

        override fun lock(
            position: Long,
            size: Long,
            shared: Boolean,
        ): FileLock = delegate.lock(position, size, shared)

        override fun tryLock(
            position: Long,
            size: Long,
            shared: Boolean,
        ): FileLock? = delegate.tryLock(position, size, shared)

        override fun implCloseChannel() = onClose(delegate)
    }

    private class CloseGate(
        val entered: CountDownLatch = CountDownLatch(1),
        val release: CountDownLatch = CountDownLatch(1),
    )

    private fun CountDownLatch.awaitOrFail() {
        assertTrue("timed out waiting for test synchronization", await(2, TimeUnit.SECONDS))
    }

    private fun Thread.joinOrFail() {
        join(2_000)
        assertFalse("test thread did not finish", isAlive)
    }

    private class Fixture(
        val prepared: PreparedFilterArtifact,
        val verified: CredentialFilterArtifactStagingResult.Verified,
        val state: ConfirmedFilterState,
    ) : AutoCloseable {
        override fun close() {
            if (verified.artifact.channelIsOpenForTesting) verified.close()
            prepared.close()
        }
    }

    private companion object {
        const val CATALOG_REVISION = "authoritative-runtime-read-catalog"
        val CATALOG_SHA256 = "c".repeat(64)
    }
}
