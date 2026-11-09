package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReadyDnsEnginePolicyOwnershipTest {
    @Test
    fun `invalid candidate consumes policy before resolver construction`() {
        val fixture = PolicyOwnerFixture()
        val policy = fixture.policy()
        val factory = PolicyResolverFactory()
        val invalid = fixture.candidate(
            policy = policy,
            epoch = fixture.epoch.copy(artifactDigest = "foreign-artifact"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ReadyDnsEngineOwner.create(invalid, factory)
        }

        assertEquals(1, policy.closeCalls.get())
        assertEquals(0, factory.createCalls.get())
    }

    @Test
    fun `resolver factory failure consumes policy and preserves its close failure as suppressed`() {
        val fixture = PolicyOwnerFixture()
        val policyCloseFailure = IllegalStateException("policy close failed")
        val policy = fixture.policy(closeFailure = policyCloseFailure)
        val factoryFailure = IllegalArgumentException("resolver factory failed")
        val factory = PolicyResolverFactory(createFailure = factoryFailure)

        val observed = assertThrows(IllegalArgumentException::class.java) {
            ReadyDnsEngineOwner.create(fixture.candidate(policy = policy), factory)
        }

        assertSame(factoryFailure, observed)
        assertEquals(listOf(policyCloseFailure), observed.suppressed.toList())
        assertEquals(1, policy.closeCalls.get())
        assertEquals(1, factory.createCalls.get())
    }

    @Test
    fun `engine closes resolver before policy and repeats neither physical close`() {
        val order = mutableListOf<String>()
        val fixture = PolicyOwnerFixture()
        val policy = fixture.policy(onClose = { order += "policy" })
        val factory = PolicyResolverFactory(onClose = { order += "resolver" })
        val engine = ReadyDnsEngineOwner.create(fixture.candidate(policy = policy), factory)

        engine.close()
        engine.close()

        assertEquals(listOf("resolver", "policy"), order)
        assertEquals(1, factory.resolver.closeCalls.get())
        assertEquals(1, policy.closeCalls.get())
    }

    @Test
    fun `resolver and policy close failures are retained in close order`() {
        val resolverFailure = IllegalStateException("resolver close failed")
        val policyFailure = IllegalArgumentException("policy close failed")
        val fixture = PolicyOwnerFixture()
        val policy = fixture.policy(closeFailure = policyFailure)
        val factory = PolicyResolverFactory(closeFailure = resolverFailure)
        val engine = ReadyDnsEngineOwner.create(fixture.candidate(policy = policy), factory)

        val first = assertThrows(IllegalStateException::class.java) { engine.close() }
        val repeated = assertThrows(IllegalStateException::class.java) { engine.close() }

        assertSame(resolverFailure, first)
        assertSame(first, repeated)
        assertEquals(listOf(policyFailure), first.suppressed.toList())
        assertEquals(1, factory.resolver.closeCalls.get())
        assertEquals(1, policy.closeCalls.get())
    }

    @Test
    fun `successful engine creation consumes candidate policy exactly once`() {
        val fixture = PolicyOwnerFixture()
        val policy = fixture.policy()
        val candidate = fixture.candidate(policy = policy)
        val firstFactory = PolicyResolverFactory()
        val secondFactory = PolicyResolverFactory()
        val first = ReadyDnsEngineOwner.create(candidate, firstFactory)
        var unexpectedSecond: ReadyDnsEngineOwner? = null
        try {
            val secondFailure = try {
                unexpectedSecond = ReadyDnsEngineOwner.create(candidate, secondFactory)
                null
            } catch (failure: Throwable) {
                failure
            }

            assertTrue(secondFailure is IllegalStateException)
            assertTrue(first.isCurrent())
            assertEquals(0, secondFactory.createCalls.get())
            assertEquals(0, policy.closeCalls.get())
        } finally {
            unexpectedSecond?.close()
            first.close()
        }

        assertEquals(1, firstFactory.resolver.closeCalls.get())
        assertEquals(1, policy.closeCalls.get())
    }

    @Test
    fun `policy retirement begins before admitted resolver drain and physical close`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val resolveEntered = CountDownLatch(1)
        val allowResolveToFinish = CountDownLatch(1)
        val fixture = PolicyOwnerFixture()
        val policy = fixture.policy(
            onBeginRetirement = { events += "policy-retire" },
            onClose = { events += "policy-close" },
        )
        val factory = PolicyResolverFactory(
            onClose = { events += "resolver-close" },
            resolveHandler = {
                events += "resolver-enter"
                resolveEntered.countDown()
                check(allowResolveToFinish.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                events += "resolver-exit"
                ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
            },
        )
        val engine = ReadyDnsEngineOwner.create(fixture.candidate(policy = policy), factory)
        val resolveFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        val resolveThread = Thread {
            try {
                runBlocking { engine.resolveIfCurrent(request(engine)) }
            } catch (failure: Throwable) {
                resolveFailure.set(failure)
            }
        }
        val closeThread = Thread {
            try {
                engine.close()
            } catch (failure: Throwable) {
                closeFailure.set(failure)
            }
        }

        try {
            resolveThread.start()
            resolveEntered.awaitLifetimeOrFail()
            closeThread.start()
            policy.retirementStarted.awaitLifetimeOrFail()

            assertTrue("close returned before the admitted resolver drained", closeThread.isAlive)
            assertEquals(0, factory.resolver.closeCalls.get())
            assertEquals(0, policy.closeCalls.get())

            allowResolveToFinish.countDown()
            resolveThread.joinLifetimeOrFail()
            closeThread.joinLifetimeOrFail()

            assertEquals(null, resolveFailure.get())
            assertEquals(null, closeFailure.get())
            assertEquals(
                listOf(
                    "resolver-enter",
                    "policy-retire",
                    "resolver-exit",
                    "resolver-close",
                    "policy-close",
                ),
                events,
            )
        } finally {
            allowResolveToFinish.countDown()
            resolveThread.join(TIMEOUT_MILLIS)
            closeThread.join(TIMEOUT_MILLIS)
            runCatching { engine.close() }
        }
    }

    @Test
    fun `replacement installs successor before prior policy retirement`() = runTest {
        val fixture = PolicyReplacementFixture(this)
        lateinit var successor: ReadyDnsEngineOwner
        val successorWasCurrentAtRetirement = AtomicBoolean()
        val priorPolicy = fixture.policy(
            onBeginRetirement = {
                successorWasCurrentAtRetirement.set(
                    fixture.schedulerOwns(successor),
                )
            },
        )
        val successorPolicy = fixture.policy()
        val prior = fixture.engine(generation = 29L, policy = priorPolicy)
        successor = fixture.engine(generation = 30L, policy = successorPolicy)
        try {
            fixture.runtime.start(prior)

            assertTrue(fixture.runtime.replaceEngine(successor))

            assertTrue(successorWasCurrentAtRetirement.get())
            assertTrue(fixture.schedulerOwns(successor))
            assertEquals(1, priorPolicy.beginRetirementCalls.get())
            assertEquals(1, priorPolicy.closeCalls.get())
            assertEquals(0, successorPolicy.beginRetirementCalls.get())
            assertEquals(0, successorPolicy.closeCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `rejected successor retires its policy while prior engine stays current`() = runTest {
        val fixture = PolicyReplacementFixture(this)
        val priorPolicy = fixture.policy()
        val rejectedPolicy = fixture.policy()
        val prior = fixture.engine(generation = 29L, policy = priorPolicy)
        val rejected = fixture.engine(
            generation = 30L,
            policy = rejectedPolicy,
            sessionGeneration = POLICY_SESSION + 1L,
        )
        try {
            fixture.runtime.start(prior)

            assertFalse(fixture.runtime.replaceEngine(rejected))

            assertTrue(prior.isCurrent())
            assertTrue(fixture.schedulerOwns(prior))
            assertEquals(0, priorPolicy.beginRetirementCalls.get())
            assertEquals(0, priorPolicy.closeCalls.get())
            assertEquals(1, rejectedPolicy.beginRetirementCalls.get())
            assertEquals(1, rejectedPolicy.closeCalls.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `retirement rejects later publication while one admitted publication drains`() = runTest {
        val fixture = PolicyReplacementFixture(this)
        val publicationEntered = CountDownLatch(1)
        val allowPublication = CountDownLatch(1)
        val publicationFinished = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val priorPolicy = fixture.policy(
            onBeginRetirement = { events += "policy-retire" },
        )
        val successorPolicy = fixture.policy()
        val prior = fixture.engine(generation = 29L, policy = priorPolicy)
        val successor = fixture.engine(generation = 30L, policy = successorPolicy)
        val sink = RecordingPublicationSink()
        val admittedPublicationObservedRetirement = AtomicBoolean()
        val publicationResult = AtomicReference<Boolean>()
        val replacementResult = AtomicReference<Boolean>()
        val publicationFailure = AtomicReference<Throwable?>()
        val replacementFailure = AtomicReference<Throwable?>()
        val publicationThread = Thread {
            try {
                publicationResult.set(
                    prior.completeIfCurrent {
                        publicationEntered.countDown()
                        check(allowPublication.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        admittedPublicationObservedRetirement.set(priorPolicy.isRetired())
                        events += "publication"
                        sink.publish(testPublication())
                    },
                )
            } catch (failure: Throwable) {
                publicationFailure.set(failure)
            } finally {
                publicationFinished.countDown()
            }
        }
        val replacementThread = Thread {
            try {
                replacementResult.set(
                    runBlocking { fixture.runtime.replaceEngine(successor) },
                )
            } catch (failure: Throwable) {
                replacementFailure.set(failure)
            } finally {
                replacementFinished.countDown()
            }
        }

        try {
            fixture.runtime.start(prior)
            publicationThread.start()
            publicationEntered.awaitLifetimeOrFail()
            replacementThread.start()
            awaitLifetimeCondition { fixture.schedulerOwns(successor) }

            priorPolicy.retirementStarted.awaitLifetimeOrFail()
            assertFalse(
                prior.completeIfCurrent {
                    throw AssertionError("retired engine admitted a later publication")
                },
            )

            allowPublication.countDown()
            publicationFinished.awaitLifetimeOrFail()
            replacementFinished.awaitLifetimeOrFail()
            publicationThread.joinLifetimeOrFail()
            replacementThread.joinLifetimeOrFail()

            assertEquals(null, publicationFailure.get())
            assertEquals(null, replacementFailure.get())
            assertTrue(replacementResult.get())
            assertTrue(publicationResult.get())
            assertEquals(1, sink.calls.get())
            assertTrue(
                "prior policy did not retire before the admitted publication drained",
                priorPolicy.isRetired(),
            )
            assertTrue(
                "the admitted publication did not observe policy retirement",
                admittedPublicationObservedRetirement.get(),
            )
            assertEquals(listOf("policy-retire", "publication"), events)
        } finally {
            allowPublication.countDown()
            publicationThread.join(TIMEOUT_MILLIS)
            replacementThread.join(TIMEOUT_MILLIS)
            fixture.close()
        }
    }

    private fun request(engine: ReadyDnsEngine): ResolverRequest {
        val query = testDnsQuery()
        return ResolverRequest(
            query = query,
            question = DnsMessage.parseQuestion(query),
            epoch = engine.epoch,
            networkContext = engine.networkContext,
            queryId = 901L,
        )
    }
}

private class PolicyOwnerFixture {
    private val harness = DnsProxyHarness()
    private val networkContext = testNetworkContext()
    private val resolverPlan = testResolverPlan(networkContext)
    val epoch = testEpoch(harness.policyIdentity, resolverPlan)

    fun policy(
        onBeginRetirement: () -> Unit = {},
        onClose: () -> Unit = {},
        closeFailure: Throwable? = null,
    ) = RecordingDnsPolicyRuntime(
        identity = harness.policyIdentity,
        proxy = harness.proxy,
        onBeginRetirement = onBeginRetirement,
        onClose = onClose,
        closeFailure = closeFailure,
    )

    fun candidate(
        policy: OwnedDnsPolicyRuntime,
        epoch: EngineEpoch = this.epoch,
    ) = ReadyDnsEngineCandidate(
        epoch = epoch,
        networkContext = networkContext,
        resolverPlan = resolverPlan,
        policy = policy,
    )
}

private class RecordingDnsPolicyRuntime(
    override val identity: ProxyPolicyIdentity,
    override val proxy: DnsProxy,
    private val onBeginRetirement: () -> Unit,
    private val onClose: () -> Unit,
    private val closeFailure: Throwable?,
) : OwnedDnsPolicyRuntime {
    val beginRetirementCalls = AtomicInteger()
    val closeCalls = AtomicInteger()
    val retirementStarted = CountDownLatch(1)

    private val retired = AtomicBoolean()

    override fun checkRetirementAllowed() = Unit

    override fun beginRetirement() {
        beginRetirementCalls.incrementAndGet()
        if (retired.compareAndSet(false, true)) {
            onBeginRetirement()
            retirementStarted.countDown()
        }
    }

    fun isRetired(): Boolean = retired.get()

    override fun close() {
        closeCalls.incrementAndGet()
        onClose()
        closeFailure?.let { throw it }
    }
}

private class PolicyResolverFactory(
    private val createFailure: Throwable? = null,
    onClose: () -> Unit = {},
    closeFailure: Throwable? = null,
    resolveHandler: suspend (ResolverRequest) -> ResolverResult = {
        ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
    },
) : ReadyDnsResolverFactory {
    val createCalls = AtomicInteger()
    val resolver = PolicyOwnedResolver(onClose, closeFailure, resolveHandler)

    override fun create(
        epoch: EngineEpoch,
        networkContext: NetworkContext,
        resolverPlan: ResolverPlan,
        registry: ResolverResourceRegistry,
    ): OwnedReadyDnsResolver {
        createCalls.incrementAndGet()
        createFailure?.let { throw it }
        return resolver
    }
}

private class PolicyOwnedResolver(
    private val onClose: () -> Unit,
    private val closeFailure: Throwable?,
    private val resolveHandler: suspend (ResolverRequest) -> ResolverResult,
) : OwnedReadyDnsResolver {
    val closeCalls = AtomicInteger()

    override suspend fun resolve(request: ResolverRequest): ResolverResult =
        resolveHandler(request)

    override fun close() {
        closeCalls.incrementAndGet()
        onClose()
        closeFailure?.let { throw it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class PolicyReplacementFixture(
    scope: TestScope,
) {
    private val harness = DnsProxyHarness()
    private val networkContext = testNetworkContext()
    private val resolverPlan = testResolverPlan(networkContext)
    private val schedulerField = BoundedTunRuntime::class.java
        .getDeclaredField("scheduler")
        .apply { isAccessible = true }

    val runtime = BoundedTunRuntime(
        sessionToken = POLICY_SESSION,
        packetSession = ScriptedTunPacketSession(),
        observer = RecordingTunRuntimeObserver(),
        dispatcher = StandardTestDispatcher(scope.testScheduler),
    )

    fun policy(
        onBeginRetirement: () -> Unit = {},
        onClose: () -> Unit = {},
    ) = RecordingDnsPolicyRuntime(
        identity = harness.policyIdentity,
        proxy = harness.proxy,
        onBeginRetirement = onBeginRetirement,
        onClose = onClose,
        closeFailure = null,
    )

    fun engine(
        generation: Long,
        policy: OwnedDnsPolicyRuntime,
        sessionGeneration: Long = POLICY_SESSION,
    ): ReadyDnsEngineOwner = ReadyDnsEngineOwner.create(
        candidate = ReadyDnsEngineCandidate(
            epoch = testEpoch(
                policyIdentity = harness.policyIdentity,
                resolverPlan = resolverPlan,
                sessionGeneration = sessionGeneration,
                engineGeneration = generation,
            ),
            networkContext = networkContext,
            resolverPlan = resolverPlan,
            policy = policy,
        ),
        resolverFactory = PolicyResolverFactory(),
    )

    fun schedulerOwns(engine: ReadyDnsEngine): Boolean =
        (schedulerField.get(runtime) as? TunQueryScheduler)
            ?.isCurrentEngine(engine) == true

    suspend fun close() {
        runtime.closeAndJoin()
    }
}

private fun testPublication() = ProxyPublication(
    responsePacket = byteArrayOf(0x01),
    activity = null,
    healthEvents = emptyList(),
    resolverQueryEvidence = null,
)

private fun CountDownLatch.awaitLifetimeOrFail() {
    assertTrue(
        "timed out waiting for deterministic policy-lifetime gate",
        await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
    )
}

private fun Thread.joinLifetimeOrFail() {
    join(TIMEOUT_MILLIS)
    assertFalse("policy-lifetime thread did not terminate", isAlive)
}

private fun awaitLifetimeCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (!condition()) {
        assertTrue(
            "timed out waiting for policy-lifetime condition",
            System.nanoTime() < deadline,
        )
        Thread.yield()
    }
}

private const val POLICY_SESSION = 23L
private const val TIMEOUT_SECONDS = 5L
private const val TIMEOUT_MILLIS = 5_000L
private const val SHORT_WAIT_MILLIS = 100L
