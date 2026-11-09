package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactOpenResult
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.FilterArtifactReference
import com.lavasec.app.platform.FilterRuntimeLeaseRead
import com.lavasec.app.platform.VerifiedFilterRuntimeLease
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtifactBackedReadyDnsEngineFactoryTest {
    @Test
    fun `factory derives the complete policy epoch and keeps its exact proxy binding`() = runTest {
        FactoryFilterFixture.create().use { fixture ->
            val lease = fixture.openLease()
            val controls = FactoryPolicyControls()
            val resolverFactory = FactoryResolverFactory()
            val networkContext = testNetworkContext(networkGeneration = 317L)
            val resolverPlan = testResolverPlan(networkContext)
            val factory = factory(controls, resolverFactory)
            val engine = factory.create(
                lease = lease,
                sessionGeneration = 701L,
                engineGeneration = 709L,
                networkContext = networkContext,
                resolverPlan = resolverPlan,
            )

            try {
                assertEquals(701L, engine.epoch.sessionGeneration)
                assertEquals(709L, engine.epoch.engineGeneration)
                assertEquals(317L, engine.epoch.networkGeneration)
                assertEquals(
                    resolverPlan.primaryResolverIdentity,
                    engine.epoch.primaryResolverIdentity,
                )
                assertEquals(resolverPlan.fallbackCoverage, engine.epoch.fallbackCoverage)
                assertSame(networkContext, engine.networkContext)
                assertSame(resolverPlan, engine.resolverPlan)
                assertSame(engine.epoch, resolverFactory.epochs.single())
                assertSame(networkContext, resolverFactory.networkContexts.single())
                assertSame(resolverPlan, resolverFactory.resolverPlans.single())

                assertEquals(
                    fixture.configurationIdentity,
                    engine.policyIdentity.configurationIdentity,
                )
                assertEquals(fixture.reference.sha256, engine.policyIdentity.artifactDigest)
                assertEquals(
                    fixture.configurationIdentity.generation,
                    engine.policyIdentity.revision,
                )
                assertTrue(engine.policyIdentity.sourceIncarnation > 0L)
                assertEquals(
                    engine.policyIdentity.configurationIdentity,
                    engine.epoch.configurationIdentity,
                )
                assertEquals(engine.policyIdentity.artifactDigest, engine.epoch.artifactDigest)
                assertEquals(engine.policyIdentity.revision, engine.epoch.policyRevision)

                val plan = engine.proxy.plan(testDnsPacket(testDnsQuery()), engine)
                assertTrue(plan is ProxyPlan.Forward)
                plan as ProxyPlan.Forward
                assertSame(engine, plan.engine)
                assertSame(engine.policyIdentity, plan.policyIdentity)
                assertEquals(1, controls.reads.get())
            } finally {
                engine.close()
                assertLeaseRetired(lease)
            }
            assertEquals(1, resolverFactory.created.single().closeCalls.get())
        }
    }

    @Test
    fun `byte identical factory activations receive distinct uninjectable incarnations`() = runTest {
        FactoryFilterFixture.create().use { fixture ->
            val firstLease = fixture.openLease()
            val secondLease = fixture.openLease()
            val resolverFactory = FactoryResolverFactory()
            val networkContext = testNetworkContext(networkGeneration = 319L)
            val resolverPlan = testResolverPlan(networkContext)
            val factory = factory(FactoryPolicyControls(), resolverFactory)
            val first = factory.create(
                lease = firstLease,
                sessionGeneration = 711L,
                engineGeneration = 719L,
                networkContext = networkContext,
                resolverPlan = resolverPlan,
            )
            val second = factory.create(
                lease = secondLease,
                sessionGeneration = 711L,
                engineGeneration = 727L,
                networkContext = networkContext,
                resolverPlan = resolverPlan,
            )

            try {
                assertEquals(
                    first.policyIdentity.configurationIdentity,
                    second.policyIdentity.configurationIdentity,
                )
                assertEquals(
                    first.policyIdentity.artifactDigest,
                    second.policyIdentity.artifactDigest,
                )
                assertEquals(first.policyIdentity.revision, second.policyIdentity.revision)
                assertNotEquals(
                    first.policyIdentity.sourceIncarnation,
                    second.policyIdentity.sourceIncarnation,
                )
                assertEquals(
                    first.policyIdentity.configurationIdentity,
                    first.epoch.configurationIdentity,
                )
                assertEquals(
                    second.policyIdentity.configurationIdentity,
                    second.epoch.configurationIdentity,
                )
            } finally {
                second.close()
                first.close()
                assertLeaseRetired(firstLease)
                assertLeaseRetired(secondLease)
            }
        }
    }

    @Test
    fun `reusing the exact lease is rejected without disrupting its first engine`() = runTest {
        FactoryFilterFixture.create().use { fixture ->
            val lease = fixture.openLease()
            val controls = FactoryPolicyControls()
            val resolverFactory = FactoryResolverFactory()
            val networkContext = testNetworkContext(networkGeneration = 323L)
            val resolverPlan = testResolverPlan(networkContext)
            val factory = factory(controls, resolverFactory)
            val first = factory.create(
                lease = lease,
                sessionGeneration = 729L,
                engineGeneration = 731L,
                networkContext = networkContext,
                resolverPlan = resolverPlan,
            )

            try {
                val failure = assertThrows(IllegalStateException::class.java) {
                    factory.create(
                        lease = lease,
                        sessionGeneration = 729L,
                        engineGeneration = 733L,
                        networkContext = networkContext,
                        resolverPlan = resolverPlan,
                    )
                }

                assertTrue(failure.message.orEmpty().contains("claimed"))
                assertEquals(1, resolverFactory.createCalls.get())
                assertTrue(
                    first.proxy.plan(testDnsPacket(testDnsQuery()), first) is ProxyPlan.Forward,
                )
                assertEquals(1, controls.reads.get())
            } finally {
                first.close()
            }
            assertLeaseRetired(lease)
            assertThrows(IllegalStateException::class.java) {
                factory.create(
                    lease = lease,
                    sessionGeneration = 729L,
                    engineGeneration = 735L,
                    networkContext = networkContext,
                    resolverPlan = resolverPlan,
                )
            }
            assertEquals(1, resolverFactory.createCalls.get())
            assertEquals(1, resolverFactory.created.single().closeCalls.get())
        }
    }

    @Test
    fun `concurrent factories sharing one lease produce one live engine and one clean rejection`() =
        runTest {
            FactoryFilterFixture.create().use { fixture ->
                val lease = fixture.openLease()
                val controls = listOf(FactoryPolicyControls(), FactoryPolicyControls())
                val resolverFactories = listOf(FactoryResolverFactory(), FactoryResolverFactory())
                val factories = resolverFactories.indices.map { index ->
                    factory(controls[index], resolverFactories[index])
                }
                val networkContext = testNetworkContext(networkGeneration = 327L)
                val resolverPlan = testResolverPlan(networkContext)
                val start = CountDownLatch(1)
                val completed = CountDownLatch(2)
                val engines =
                    listOf(
                        AtomicReference<ReadyDnsEngineOwner?>(),
                        AtomicReference<ReadyDnsEngineOwner?>(),
                    )
                val failures =
                    listOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
                val threads = factories.indices.map { index ->
                    Thread {
                        try {
                            check(start.await(5L, TimeUnit.SECONDS))
                            engines[index].set(
                                factories[index].create(
                                    lease = lease,
                                    sessionGeneration = 737L,
                                    engineGeneration = 739L + index,
                                    networkContext = networkContext,
                                    resolverPlan = resolverPlan,
                                ),
                            )
                        } catch (failure: Throwable) {
                            failures[index].set(failure)
                        } finally {
                            completed.countDown()
                        }
                    }
                }
                try {
                    threads.forEach(Thread::start)
                    start.countDown()
                    assertTrue(completed.await(5L, TimeUnit.SECONDS))
                    threads.forEach {
                        it.join(5_000L)
                        assertFalse("factory thread did not complete", it.isAlive)
                    }

                    val winner = engines.mapNotNull { it.get() }.single()
                    assertEquals(1, failures.count { it.get() is IllegalStateException })
                    assertEquals(1, resolverFactories.sumOf { it.createCalls.get() })
                    assertTrue(
                        winner.proxy.plan(testDnsPacket(testDnsQuery()), winner) is
                            ProxyPlan.Forward,
                    )
                    assertEquals(1, controls.sumOf { it.reads.get() })

                    winner.close()
                    assertLeaseRetired(lease)
                    assertEquals(
                        1,
                        resolverFactories.sumOf { factory ->
                            factory.created.sumOf { it.closeCalls.get() }
                        },
                    )
                } finally {
                    start.countDown()
                    threads.forEach { it.join(5_000L) }
                    engines.mapNotNull { it.get() }
                        .forEach { runCatching { it.close() } }
                    runCatching { lease.close() }
                }
            }
        }

    @Test
    fun `epoch construction failure consumes and closes the lease`() = runTest {
        FactoryFilterFixture.create().use { fixture ->
            val lease = fixture.openLease()
            val resolverFactory = FactoryResolverFactory()
            val networkContext = testNetworkContext(networkGeneration = 331L)
            val resolverPlan = testResolverPlan(networkContext)

            try {
                assertThrows(IllegalArgumentException::class.java) {
                    factory(FactoryPolicyControls(), resolverFactory).create(
                        lease = lease,
                        sessionGeneration = 733L,
                        engineGeneration = 0L,
                        networkContext = networkContext,
                        resolverPlan = resolverPlan,
                    )
                }

                assertEquals(0, resolverFactory.createCalls.get())
                assertLeaseRetired(lease)
            } finally {
                lease.close()
            }
        }
    }

    @Test
    fun `owner validation failure consumes policy and closes its lease before resolver creation`() =
        runTest {
            FactoryFilterFixture.create().use { fixture ->
                val lease = fixture.openLease()
                val resolverFactory = FactoryResolverFactory()
                val networkContext = testNetworkContext(networkGeneration = 337L)
                val resolverPlan = testResolverPlan(networkContext)

                try {
                    assertThrows(IllegalArgumentException::class.java) {
                        factory(FactoryPolicyControls(), resolverFactory).create(
                            lease = lease,
                            sessionGeneration = 0L,
                            engineGeneration = 739L,
                            networkContext = networkContext,
                            resolverPlan = resolverPlan,
                        )
                    }

                    assertEquals(0, resolverFactory.createCalls.get())
                    assertLeaseRetired(lease)
                } finally {
                    lease.close()
                }
            }
        }

    @Test
    fun `resolver construction failure closes cold resources and the consumed policy lease`() =
        runTest {
            FactoryFilterFixture.create().use { fixture ->
                val lease = fixture.openLease()
                val expected = IllegalStateException("resolver construction failed")
                val partialResource = FactoryPartialResolverResource()
                val resolverFactory = FactoryResolverFactory(
                    failure = expected,
                    onCreate = { epoch, registry ->
                        assertFalse(registry.registerIfCurrent(partialResource, epoch))
                    },
                )
                val networkContext = testNetworkContext(networkGeneration = 347L)
                val resolverPlan = testResolverPlan(networkContext)

                try {
                    val observed = assertThrows(IllegalStateException::class.java) {
                        factory(FactoryPolicyControls(), resolverFactory).create(
                            lease = lease,
                            sessionGeneration = 743L,
                            engineGeneration = 751L,
                            networkContext = networkContext,
                            resolverPlan = resolverPlan,
                        )
                    }

                    assertSame(expected, observed)
                    assertEquals(1, resolverFactory.createCalls.get())
                    assertEquals(1, partialResource.closeCalls.get())
                    assertTrue(resolverFactory.created.isEmpty())
                    assertLeaseRetired(lease)
                } finally {
                    lease.close()
                }
            }
        }

    @Test
    fun `caller cancellation from resolver construction still closes the consumed policy lease`() =
        runTest {
            FactoryFilterFixture.create().use { fixture ->
                val lease = fixture.openLease()
                val expected = CancellationException("resolver construction cancelled")
                val resolverFactory = FactoryResolverFactory(failure = expected)
                val networkContext = testNetworkContext(networkGeneration = 353L)
                val resolverPlan = testResolverPlan(networkContext)

                try {
                    val observed = assertThrows(CancellationException::class.java) {
                        factory(FactoryPolicyControls(), resolverFactory).create(
                            lease = lease,
                            sessionGeneration = 757L,
                            engineGeneration = 761L,
                            networkContext = networkContext,
                            resolverPlan = resolverPlan,
                        )
                    }

                    assertSame(expected, observed)
                    assertEquals(1, resolverFactory.createCalls.get())
                    assertLeaseRetired(lease)
                } finally {
                    lease.close()
                }
            }
        }

    private fun factory(
        controls: ProxyPolicyControlSource,
        resolverFactory: ReadyDnsResolverFactory,
    ) = ArtifactBackedReadyDnsEngineFactory(
        controls = controls,
        resolverFactory = resolverFactory,
        eventIds = TestEventIds(),
        wallClock = TestEpochMillisClock(),
        monotonicClock = TestMonotonicClock(),
        publicationSink = RecordingPublicationSink(),
        unavailablePublicationFence = TestPublicationFence(),
    )

    private fun assertLeaseRetired(lease: VerifiedFilterRuntimeLease) {
        assertSame(
            FilterRuntimeLeaseRead.Unavailable,
            lease.withDecisions { error("retired factory lease admitted a decision") },
        )
    }
}

private class FactoryPolicyControls : ProxyPolicyControlSource {
    val reads = AtomicInteger()

    override fun current(): ProxyPolicyControl {
        reads.incrementAndGet()
        return ProxyPolicyControl(
            activeProtectionSessionId = 811L,
            pause = com.lavasec.app.foundation.pause.RuntimePauseDirective.Filtering,
        )
    }
}

private class FactoryResolverFactory(
    private val failure: Throwable? = null,
    private val onCreate: (EngineEpoch, ResolverResourceRegistry) -> Unit = { _, _ -> },
) : ReadyDnsResolverFactory {
    val createCalls = AtomicInteger()
    val created = mutableListOf<FactoryOwnedResolver>()
    val epochs = mutableListOf<EngineEpoch>()
    val networkContexts = mutableListOf<NetworkContext>()
    val resolverPlans = mutableListOf<ResolverPlan>()

    override fun create(
        epoch: EngineEpoch,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
        registry: ResolverResourceRegistry,
    ): OwnedReadyDnsResolver {
        createCalls.incrementAndGet()
        epochs += epoch
        networkContexts += networkContext
        resolverPlans += resolverPlan
        onCreate(epoch, registry)
        failure?.let { throw it }
        return FactoryOwnedResolver().also { created += it }
    }
}

private class FactoryOwnedResolver : OwnedReadyDnsResolver {
    val closeCalls = AtomicInteger()

    override suspend fun resolve(request: ResolverRequest): ResolverResult =
        ResolverResult.Failure(ResolverFailureReason.TIMEOUT)

    override fun close() {
        closeCalls.incrementAndGet()
    }
}

private class FactoryPartialResolverResource : AutoCloseable {
    val closeCalls = AtomicInteger()

    override fun close() {
        closeCalls.incrementAndGet()
    }
}

internal class FactoryFilterFixture private constructor(
    private val root: File,
    private val prepared: PreparedFilterArtifact,
    val configuration: FilterLibraryDraft,
    val configurationIdentity: ConfigurationIdentity,
) : AutoCloseable {
    val reference = FilterArtifactReference(prepared.sha256, prepared.identity)

    fun openLease(): VerifiedFilterRuntimeLease {
        val artifact = when (
            val opened = FilterArtifact.openResult(
                file = prepared.file,
                expectedSha256 = reference.sha256,
            )
        ) {
            is FilterArtifactOpenResult.Available -> opened.artifact
            is FilterArtifactOpenResult.Unavailable ->
                error("factory fixture artifact must open: ${opened.reason}")
        }
        val lease = VerifiedFilterRuntimeLease.takeOpenedOrNull(
            configuration = configuration,
            configurationIdentity = configurationIdentity,
            credentialUnlockRequired = false,
            reference = reference,
            artifact = artifact,
        )
        if (lease != null) return lease
        artifact.close()
        error("factory fixture artifact must become a runtime lease")
    }

    override fun close() {
        prepared.close()
        root.deleteRecursively()
    }

    companion object {
        private const val CATALOG_REVISION = "vpn-ready-factory-catalog"
        private val CATALOG_SHA256 = "b".repeat(64)

        suspend fun create(): FactoryFilterFixture {
            val root = Files.createTempDirectory("vpn-ready-factory-").toFile()
            val configuration = FilterLibraryDraft.seededDefaults(
                FilterCatalogSnapshot(emptyList()),
            )
            val identity = ConfigurationIdentity(
                generation = 41L,
                fingerprint = canonicalFilterConfigurationFingerprint(
                    configuration = configuration,
                    catalogRevision = CATALOG_REVISION,
                    catalogSha256 = CATALOG_SHA256,
                ),
            )
            return try {
                val prepared = FilterCompiler(File(root, "scratch")).compile(
                    FilterCompileRequest(
                        configurationIdentity = ArtifactConfigurationIdentity(
                            identity.generation,
                            identity.fingerprint,
                        ),
                        selectedSources = emptyList(),
                        catalogRevision = CATALOG_REVISION,
                        catalogSha256 = CATALOG_SHA256,
                        maxRules = 0,
                        intentionalEmpty = true,
                    ),
                )
                FactoryFilterFixture(root, prepared, configuration, identity)
            } catch (failure: Throwable) {
                root.deleteRecursively()
                throw failure
            }
        }
    }
}
