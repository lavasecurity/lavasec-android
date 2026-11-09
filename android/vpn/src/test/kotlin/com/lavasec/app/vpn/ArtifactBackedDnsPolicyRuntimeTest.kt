package com.lavasec.app.vpn

import com.lavasec.app.foundation.filter.ArtifactConfigurationIdentity
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactOpenResult
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterCompileRequest
import com.lavasec.app.foundation.filter.FilterCompiler
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.FilterArtifactReference
import com.lavasec.app.platform.VerifiedFilterRuntimeLease
import com.lavasec.app.platform.canonicalFilterConfigurationFingerprint
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
class ArtifactBackedDnsPolicyRuntimeTest {
    @Test
    fun `identity is derived from the exact lease while session and pause remain live controls`() =
        runTest {
            PolicyArtifactFixture.create().use { fixture ->
                val controls = MutablePolicyControls(
                    ProxyPolicyControl(
                        activeProtectionSessionId = 401L,
                        pause = RuntimePauseDirective.Filtering,
                    ),
                )
                val runtime = fixture.runtime(controls)
                try {
                    assertEquals(fixture.configurationIdentity, runtime.identity.configurationIdentity)
                    assertEquals(fixture.reference.sha256, runtime.identity.artifactDigest)
                    assertEquals(37L, runtime.identity.revision)
                    assertTrue(runtime.identity.sourceIncarnation > 0L)

                    val first = runtime.withCurrent { snapshot ->
                        assertSame(runtime.identity, snapshot.identity)
                        snapshot.activeProtectionSessionId to snapshot.pause
                    }.availableValue()
                    assertEquals(401L, first.first)
                    assertSame(RuntimePauseDirective.Filtering, first.second)

                    val pause = RuntimePauseDirective.Paused(
                        PauseState(
                            deadlineMillis = TEST_NOW_MILLIS + 60_000L,
                            protectionSessionId = 997L,
                        ),
                    )
                    controls.value = ProxyPolicyControl(
                        activeProtectionSessionId = 997L,
                        pause = pause,
                    )

                    val second = runtime.withCurrent { snapshot ->
                        assertSame(runtime.identity, snapshot.identity)
                        snapshot.activeProtectionSessionId to snapshot.pause
                    }.availableValue()
                    assertEquals(997L, second.first)
                    assertSame(pause, second.second)
                    assertEquals(2, controls.reads.get())
                } finally {
                    runtime.close()
                }
            }
        }

    @Test
    fun `byte identical lease lifetimes receive distinct source incarnations`() = runTest {
        PolicyArtifactFixture.create().use { fixture ->
            val first = fixture.runtime(MutablePolicyControls.filtering(101L))
            val second = fixture.runtime(MutablePolicyControls.filtering(101L))
            try {
                assertEquals(first.identity.configurationIdentity, second.identity.configurationIdentity)
                assertEquals(first.identity.artifactDigest, second.identity.artifactDigest)
                assertEquals(first.identity.revision, second.identity.revision)
                assertNotEquals(first.identity.sourceIncarnation, second.identity.sourceIncarnation)
                assertTrue(first.identity.sourceIncarnation > 0L)
                assertTrue(second.identity.sourceIncarnation > 0L)
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun `scoped result cannot expose usable mapped decisions after callback return`() = runTest {
        PolicyArtifactFixture.create().use { fixture ->
            val runtime = fixture.runtime(MutablePolicyControls.filtering(102L))
            val escapedDecisions = runtime.withCurrent { it.decisions }.availableValue()

            assertEquals(
                FilterDecision(FilterAction.BLOCK, FilterDecisionReason.PROTECTION_UNAVAILABLE),
                escapedDecisions.decide("example.com"),
            )
            runtime.close()

            assertEquals(
                FilterDecision(FilterAction.BLOCK, FilterDecisionReason.PROTECTION_UNAVAILABLE),
                escapedDecisions.decide("example.com"),
            )
            assertTrue(runtime.withCurrent { error("retired callback must not run") } is
                ProxyPolicyRead.Unavailable)
        }
    }

    @Test
    fun `unreadable controls fail closed while a stale pause remains ordinary control state`() =
        runTest {
            PolicyArtifactFixture.create().use { fixture ->
                val controls = MutablePolicyControls.filtering(103L)
                val runtime = fixture.runtime(controls)
                val callbacks = AtomicInteger()
                try {
                    controls.failure = IllegalStateException("control unavailable")
                    assertTrue(
                        runtime.withCurrent {
                            callbacks.incrementAndGet()
                        } is ProxyPolicyRead.Unavailable,
                    )

                    controls.failure = null
                    controls.value = ProxyPolicyControl(
                        activeProtectionSessionId = 103L,
                        pause = RuntimePauseDirective.Paused(
                            PauseState(
                                deadlineMillis = TEST_NOW_MILLIS + 60_000L,
                                protectionSessionId = 104L,
                            ),
                        ),
                    )
                    val read = runtime.withCurrent { snapshot ->
                        callbacks.incrementAndGet()
                        snapshot.pause
                    }
                    assertTrue(
                        read is ProxyPolicyRead.Available<*>,
                    )
                    assertEquals(1, callbacks.get())
                } finally {
                    runtime.close()
                }
            }
        }

    @Test
    fun `retirement rejects later reads and drains one already admitted scoped read`() = runTest {
        PolicyArtifactFixture.create().use { fixture ->
            val runtime = fixture.runtime(MutablePolicyControls.filtering(105L))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val readResult = AtomicReference<ProxyPolicyRead<Long>>()
            val readFailure = AtomicReference<Throwable?>()
            val closeReturned = AtomicBoolean(false)
            val closeFailure = AtomicReference<Throwable?>()

            val reader = thread(name = "policy-read") {
                try {
                    readResult.set(
                        runtime.withCurrent { snapshot ->
                            entered.countDown()
                            release.awaitOrFail()
                            snapshot.activeProtectionSessionId
                        },
                    )
                } catch (failure: Throwable) {
                    readFailure.set(failure)
                }
            }
            entered.awaitOrFail()
            val closer = thread(name = "policy-close") {
                try {
                    runtime.close()
                    closeReturned.set(true)
                } catch (failure: Throwable) {
                    closeFailure.set(failure)
                }
            }

            assertFalse(closeReturned.awaitTrue(100L))
            awaitUnavailable(runtime)
            assertTrue(runtime.withCurrent { error("retired callback must not run") } is
                ProxyPolicyRead.Unavailable)
            release.countDown()
            reader.joinOrFail()
            closer.joinOrFail()

            assertEquals(105L, requireNotNull(readResult.get()).availableValue())
            assertSame(null, readFailure.get())
            assertSame(null, closeFailure.get())
            assertTrue(closeReturned.get())
        }
    }

    @Test
    fun `concurrent and repeated close share one completed retirement`() = runTest {
        PolicyArtifactFixture.create().use { fixture ->
            val runtime = fixture.runtime(MutablePolicyControls.filtering(106L))
            val start = CountDownLatch(1)
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val closers = List(2) { index ->
                thread(name = "policy-close-$index") {
                    start.awaitOrFail()
                    try {
                        runtime.close()
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }
            start.countDown()
            closers.forEach { it.joinOrFail() }

            runtime.close()
            assertTrue(failures.isEmpty())
            assertTrue(runtime.withCurrent { error("closed callback must not run") } is
                ProxyPolicyRead.Unavailable)
        }
    }

    @Test
    fun `close reentry from a scoped read fails fast without retiring the runtime`() = runTest {
        PolicyArtifactFixture.create().use { fixture ->
            val runtime = fixture.runtime(MutablePolicyControls.filtering(107L))
            try {
                val read = runtime.withCurrent { snapshot ->
                    assertThrows(IllegalStateException::class.java) {
                        runtime.close()
                    }
                    snapshot.activeProtectionSessionId
                }

                assertEquals(107L, read.availableValue())
                assertEquals(
                    107L,
                    runtime.withCurrent { it.activeProtectionSessionId }.availableValue(),
                )
            } finally {
                runtime.close()
            }
        }
    }

    @Test
    fun `bound proxy reads the runtime controls and fails closed when they become unavailable`() =
        runTest {
            PolicyArtifactFixture.create().use { fixture ->
                val controls = MutablePolicyControls.filtering(108L)
                val runtime = fixture.runtime(controls)
                try {
                    val context = testNetworkContext()
                    val plan = testResolverPlan(context)
                    val engine = TestReadyDnsEngine(
                        epoch = testEpoch(runtime.identity, plan),
                        networkContext = context,
                        resolverPlan = plan,
                        policyIdentity = runtime.identity,
                        proxy = runtime.proxy,
                    )

                    assertTrue(
                        runtime.proxy.plan(testDnsPacket(testDnsQuery()), engine) is
                            ProxyPlan.Forward,
                    )
                    assertEquals(1, controls.reads.get())

                    controls.failure = IllegalStateException("control unavailable")
                    assertTrue(
                        runtime.proxy.plan(testDnsPacket(testDnsQuery()), engine) is
                            ProxyPlan.Immediate,
                    )
                    assertEquals(2, controls.reads.get())
                } finally {
                    runtime.close()
                }
            }
        }
}

private class MutablePolicyControls(
    @Volatile var value: ProxyPolicyControl,
) : ProxyPolicyControlSource {
    val reads = AtomicInteger()
    @Volatile var failure: Throwable? = null

    override fun current(): ProxyPolicyControl {
        reads.incrementAndGet()
        failure?.let { throw it }
        return value
    }

    companion object {
        fun filtering(activeProtectionSessionId: Long) = MutablePolicyControls(
            ProxyPolicyControl(
                activeProtectionSessionId = activeProtectionSessionId,
                pause = RuntimePauseDirective.Filtering,
            ),
        )
    }
}

private class PolicyArtifactFixture private constructor(
    private val root: File,
    private val prepared: PreparedFilterArtifact,
    val configuration: FilterLibraryDraft,
    val configurationIdentity: ConfigurationIdentity,
) : AutoCloseable {
    val reference = FilterArtifactReference(prepared.sha256, prepared.identity)

    fun runtime(controls: ProxyPolicyControlSource): ArtifactBackedDnsPolicyRuntime =
        ArtifactBackedDnsPolicyRuntime.take(
            lease = openLease(),
            controls = controls,
            eventIds = TestEventIds(),
            wallClock = TestEpochMillisClock(),
            monotonicClock = TestMonotonicClock(),
            publicationSink = RecordingPublicationSink(),
            unavailablePublicationFence = TestPublicationFence(),
        )

    private fun openLease(): VerifiedFilterRuntimeLease {
        val artifact = when (
            val opened = FilterArtifact.openResult(
                file = prepared.file,
                expectedSha256 = reference.sha256,
            )
        ) {
            is FilterArtifactOpenResult.Available -> opened.artifact
            is FilterArtifactOpenResult.Unavailable ->
                error("fixture artifact must open: ${opened.reason}")
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
        error("fixture artifact must become a runtime lease")
    }

    override fun close() {
        prepared.close()
        root.deleteRecursively()
    }

    companion object {
        private const val CATALOG_REVISION = "vpn-policy-test-catalog"
        private val CATALOG_SHA256 = "a".repeat(64)

        suspend fun create(): PolicyArtifactFixture {
            val root = Files.createTempDirectory("vpn-policy-runtime-").toFile()
            val configuration = FilterLibraryDraft.seededDefaults(FilterCatalogSnapshot(emptyList()))
            val identity = ConfigurationIdentity(
                generation = 37L,
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
                PolicyArtifactFixture(root, prepared, configuration, identity)
            } catch (failure: Throwable) {
                root.deleteRecursively()
                throw failure
            }
        }
    }
}

private fun <T> ProxyPolicyRead<T>.availableValue(): T {
    assertTrue("expected an available policy read", this is ProxyPolicyRead.Available<*>)
    @Suppress("UNCHECKED_CAST")
    return (this as ProxyPolicyRead.Available<T>).value
}

private fun CountDownLatch.awaitOrFail() {
    assertTrue("timed out waiting for deterministic gate", await(2, TimeUnit.SECONDS))
}

private fun Thread.joinOrFail() {
    join(2_000L)
    assertFalse("thread did not terminate", isAlive)
}

private fun AtomicBoolean.awaitTrue(timeoutMillis: Long): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!get() && System.nanoTime() < deadline) Thread.yield()
    return get()
}

private fun awaitUnavailable(runtime: ArtifactBackedDnsPolicyRuntime) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
    while (System.nanoTime() < deadline) {
        if (runtime.withCurrent { Unit } is ProxyPolicyRead.Unavailable) return
        Thread.yield()
    }
    throw AssertionError("policy runtime did not begin retirement")
}
