package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverSmokeProbeCadence
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome
import com.lavasec.app.platform.ConfigurationIdentity
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ResolverSmokeProbeOwnerTest {
    @Test fun `validated late primary through coordinator proves only liveness in cold and warm smoke state`() = runTest {
        for (warm in listOf("cold", "timeout", "rejected")) for (lateReply in listOf("positive", "negative", "rejected")) {
            var now = 1000L
            var nanos = 0L
            var mode = warm
            val ids = AtomicLong()
            val reconnects = mutableListOf<Long>()
            val health = ExactSessionHealthOwner(1, { now }, {}, reconnects::add)
            val engine = Engine(1)
            install(health, engine)
            val registry = primaryCoordinator(engine, MonotonicClock { nanos }, StandardTestDispatcher(testScheduler)) { request ->
                when (mode) {
                    "timeout" -> ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
                    "rejected" -> ResolverResult.Answer(emptyReply(request.query, 5), ResolverTransport.DEVICE_UDP)
                    else -> {
                        nanos = checkNotNull(request.absoluteDeviceDeadlineNanos)
                        ResolverResult.Answer(when (lateReply) {
                            "positive" -> served(request.query)
                            "negative" -> emptyReply(request.query, 3)
                            else -> emptyReply(request.query, 5)
                        }, ResolverTransport.DEVICE_UDP)
                    }
                }
            }
            val owner = ResolverSmokeProbeOwner(backgroundScope, { now }, ids::incrementAndGet,
                { it.isCurrent() }, { health }, nowNanos = { nanos })
            try {
                var installed = false
                if (warm != "cold") {
                    owner.install(engine); runCurrent(); installed = true
                    repeat(2) { now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS; owner.tick(force = true); runCurrent() }
                    assertEquals(3, health.current().smokeProbeFailureCount)
                    assertTrue(health.deviceResolverReconnectEpisodeIsActive(engine.epoch))
                    assertEquals(listOf(1L), reconnects)
                }
                val before = health.current()
                val previousReconnects = reconnects.toList()
                val priorTier = before.diagnostics.tiers[1]
                mode = "late"
                repeat(3) { index ->
                    now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS
                    if (!installed) { owner.install(engine); installed = true } else owner.tick(force = true)
                    runCurrent()
                    val result = engine.lastResult as ResolverResult.Failure
                    assertEquals(ResolverFailureReason.TIMEOUT, result.reason)
                    assertTrue(result.terminal)
                    assertTrue(result.peerAnswered)
                    assertFalse(result.local)
                    val after = health.current()
                    assertEquals(before.smokeProbeFailureCount, after.smokeProbeFailureCount)
                    assertEquals(0, after.health.consecutiveResolverFailures)
                    assertEquals(before.health.consecutivePrimaryRejections, after.health.consecutivePrimaryRejections)
                    assertEquals(before.health.activeFallback, after.health.activeFallback)
                    assertEquals(before.notificationEvidence, after.notificationEvidence)
                    assertEquals(previousReconnects, reconnects)
                    assertFalse(health.deviceResolverReconnectEpisodeIsActive(engine.epoch))
                    assertFalse(health.hasRecentOrganicPrimaryAnswer(engine.epoch, now, ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS))
                    assertEquals(before.diagnostics.smokeProbes + index + 1, after.diagnostics.smokeProbes)
                    assertEquals(before.diagnostics.smokeSuccesses, after.diagnostics.smokeSuccesses)
                    assertEquals(before.diagnostics.upstreamSuccesses, after.diagnostics.upstreamSuccesses)
                    assertEquals(before.diagnostics.upstreamFailures, after.diagnostics.upstreamFailures)
                    assertEquals(before.health.recentResponseLatenciesMillis, after.health.recentResponseLatenciesMillis)
                    assertEquals(before.diagnostics.responseLatenciesMillis, after.diagnostics.responseLatenciesMillis)
                    val tier = after.diagnostics.tiers.getValue(1)
                    val observation = checkNotNull(tier.latest)
                    assertEquals(ResolverTierDiagnosticOutcome.ANSWERED, observation.outcome)
                    assertEquals(ResolverFailureReason.TIMEOUT, observation.failure)
                    assertTrue(observation.peerLivenessOnly)
                    assertEquals(true, observation.packetSent)
                    assertEquals(0L, tier.failureStreak)
                    assertEquals(priorTier?.served ?: 0L, tier.served)
                    assertEquals(priorTier?.failures ?: 0L, tier.failures)
                    assertEquals(priorTier?.rejectedStreak ?: 0L, tier.rejectedStreak)
                    assertEquals(0, engine.ordinaryCalls)
                    assertEquals(0, registry.sizeForTesting)
                }
            } finally { owner.close(); registry.close(); engine.close() }
        }
    }

    @Test fun `local smoke refusals stay neutral while real timeout and terminal mismatch still qualify failure`() = runTest {
        for (failure in listOf(ResolverResult.Failure(ResolverFailureReason.TIMEOUT),
            ResolverResult.Failure(ResolverFailureReason.TIMEOUT, terminal = true),
            ResolverResult.Failure(ResolverFailureReason.MISMATCH_LIMIT, terminal = true),
            ResolverResult.Failure(ResolverFailureReason.TIMEOUT, terminal = true, local = true),
            ResolverResult.Failure(ResolverFailureReason.PROTECT_FAILED),
            ResolverResult.Failure(ResolverFailureReason.BIND_FAILED),
            ResolverResult.Failure(ResolverFailureReason.SOCKET_FAILED, local = true))) {
            var now = 1000L
            val reconnects = mutableListOf<Long>()
            val health = ExactSessionHealthOwner(1, { now }, {}, reconnects::add)
            val engine = Engine(1).apply { primaryResolution = { failure } }
            install(health, engine)
            val owner = ResolverSmokeProbeOwner(backgroundScope, { now }, AtomicLong()::incrementAndGet,
                { it.isCurrent() }, { health })
            try {
                owner.install(engine); runCurrent()
                repeat(2) { now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS; owner.tick(force = true); runCurrent() }
                val after = health.current()
                assertEquals(if (failure.local) 0 else 3, after.smokeProbeFailureCount)
                assertEquals(if (failure.local) 0 else 3, after.health.consecutiveResolverFailures)
                assertEquals(if (failure.local) emptyList<Long>() else listOf(1L), reconnects)
                assertEquals(!failure.local, health.deviceResolverReconnectEpisodeIsActive(engine.epoch))
                assertEquals(3L, after.diagnostics.smokeProbes)
                assertEquals(0L, after.diagnostics.smokeSuccesses)
                val tier = after.diagnostics.tiers.getValue(1)
                if (failure.local) {
                    assertNull(tier.latest)
                    val admission = checkNotNull(tier.latestAdmission)
                    assertEquals(ResolverTierDiagnosticOutcome.NOT_ATTEMPTED, admission.outcome)
                    assertEquals(false, admission.packetSent)
                    assertEquals(0L, tier.attempts)
                    assertEquals(VpnHealthNotificationEvidence(), after.notificationEvidence)
                } else {
                    val observation = checkNotNull(tier.latest)
                    assertEquals(ResolverTierDiagnosticOutcome.FAILED, observation.outcome)
                    assertFalse(observation.peerLivenessOnly)
                    assertEquals(failure.reason, observation.failure)
                    assertEquals(3L, tier.failures)
                    assertEquals(3L, tier.failureStreak)
                }
            } finally { owner.close(); engine.close() }
        }
    }

    @Test fun `owned wire timeout counts but lifecycle and backoff refusal abandon neutrally`() = runTest {
        for (reason in listOf(ResolverFailureReason.CANCELLED, ResolverFailureReason.STALE_EPOCH,
            ResolverFailureReason.OVERLOADED, ResolverFailureReason.BACKED_OFF)) {
            val health = ExactSessionHealthOwner(1, { 1000L }, {}, {})
            val engine = Engine(1).apply { failure = reason }
            install(health, engine)
            val owner = ResolverSmokeProbeOwner(backgroundScope, { 1000L }, AtomicLong()::incrementAndGet,
                { it.isCurrent() }, { health })
            owner.install(engine); runCurrent()
            assertEquals(reason.name, 0, health.current().smokeProbeFailureCount)
            assertEquals(reason.name, 0, health.current().health.consecutiveResolverFailures)
            owner.close()
        }
        val health = ExactSessionHealthOwner(1, { 1000L }, {}, {})
        val engine = Engine(1).apply { waitFor = CompletableDeferred() }
        install(health, engine)
        val owner = ResolverSmokeProbeOwner(backgroundScope, { 1000L }, AtomicLong()::incrementAndGet,
            { it.isCurrent() }, { health }, timeoutMillis = 10)
        owner.install(engine); runCurrent()
        advanceTimeBy(11); runCurrent()
        assertEquals(1, health.current().smokeProbeFailureCount)
        assertEquals(1, health.current().health.consecutiveResolverFailures)
        assertFalse(health.deviceResolverReconnectEpisodeIsActive(engine.epoch))
        owner.close()
    }

    @Test fun `missing primary role disconnected or unavailable network do not schedule or stamp a probe`() = runTest {
        for (boundary in listOf("role", "disconnect", "network")) {
            val health = ExactSessionHealthOwner(1, { 1000L }, {}, {})
            val engine = Engine(1)
            install(health, engine, primary = boundary != "role")
            if (boundary == "disconnect") health.recordConnectionChanged(1, false)
            if (boundary == "network") health.recordHealth(1,
                DnsHealthEvent.ProtectionUnavailable(ProtectionUnavailableReason.NetworkUnavailable))
            val owner = ResolverSmokeProbeOwner(backgroundScope, { 1000L }, AtomicLong()::incrementAndGet,
                { it.isCurrent() }, { health })
            owner.install(engine); owner.tick(force = true); runCurrent()
            assertTrue(boundary, engine.questions.isEmpty())
            assertEquals(boundary, 0, health.current().smokeProbeFailureCount)
            owner.close()
        }
    }

    @Test fun `routine only skips recent strict organic primary receipts and forced or future dated probes still run`() = runTest {
        var now = 1_000L
        var nanos = 0L
        val ids = AtomicLong()
        val health = ExactSessionHealthOwner(1, { now }, {}, {})
        val engine = Engine(1).apply { reply = ::served }
        install(health, engine)
        val owner = ResolverSmokeProbeOwner(backgroundScope, { now }, ids::incrementAndGet,
            { it.isCurrent() }, { health }, nowNanos = { nanos.also { nanos += 7_000_000 } })
        owner.install(engine); runCurrent()
        assertEquals(listOf(7L), health.current().health.recentResponseLatenciesMillis)
        now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS
        // Service success alone has no answer-bearing receipt and cannot skip the due probe.
        health.recordBatch(1, emptyList(), ResolverQueryHealthEvidence.primaryAccepted(ids.incrementAndGet(), 1))
        owner.tick(); runCurrent()
        assertEquals(2, engine.questions.size)
        now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS
        health.recordBatch(1, emptyList(), ResolverQueryHealthEvidence.primaryAccepted(ids.incrementAndGet(), 1), true)
        owner.tick(); runCurrent()
        assertEquals(2, engine.questions.size)
        owner.tick(force = true); runCurrent()
        assertEquals(3, engine.questions.size)
        now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS
        health.recordBatch(1, emptyList(), ResolverQueryHealthEvidence.primaryAccepted(ids.incrementAndGet(), 1), true)
        now -= 1
        owner.tick(); runCurrent()
        assertEquals(3, engine.questions.size) // Not due by the wire cadence yet.
        now -= ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS
        owner.tick(); runCurrent() // Both future stamps are distrusted.
        assertEquals(4, engine.questions.size)
        owner.close()
    }

    @Test fun `query rotation and strict full rcode answer bar do not accept organic negatives or malformed identity`() {
        assertEquals(listOf("example.com", "apple.com", "cloudflare.com", "example.com"),
            (0L..3L).map { DnsMessage.parseQuestion(DnsResolverSmokeProbe.query(it, 0x4c56)).normalizedDomain })
        assertEquals("cloudflare.com", DnsResolverSmokeProbe.domain(-1L))
        val query = DnsResolverSmokeProbe.query(0, 0x4c56)
        val accepted = served(query)
        assertEquals(DnsResolverSmokeProbe.Reply.ACCEPTED_ANSWER, DnsResolverSmokeProbe.classify(query, accepted))
        for (rcode in listOf(0, 2, 3, 5)) {
            assertEquals(DnsResolverSmokeProbe.Reply.REJECTED_OR_ANSWERLESS,
                DnsResolverSmokeProbe.classify(query, emptyReply(query, rcode)))
        }
        assertEquals(DnsResolverSmokeProbe.Reply.INVALID, DnsResolverSmokeProbe.classify(query,
            accepted.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        assertEquals(DnsResolverSmokeProbe.Reply.INVALID, DnsResolverSmokeProbe.classify(query, accepted.copyOf(accepted.size - 1)))
        val extendedError = accepted + byteArrayOf(0, 0, 41, 4, 0, 1, 0, 0, 0, 0, 0)
        extendedError[11] = 1
        assertEquals(DnsResolverSmokeProbe.Reply.REJECTED_OR_ANSWERLESS, DnsResolverSmokeProbe.classify(query, extendedError))
    }

    @Test fun `real primary completions rotate cold rejections and survive only same primary reconnect until recovery`() = runTest {
        var now = 1000L
        val ids = AtomicLong()
        val reconnects = mutableListOf<Long>()
        val health = ExactSessionHealthOwner(1, { now }, {}, reconnects::add)
        val first = Engine(1)
        install(health, first)
        val owner = ResolverSmokeProbeOwner(backgroundScope, { now }, ids::incrementAndGet, { it.isCurrent() }, { health })
        owner.install(first)
        runCurrent()
        assertEquals(1, health.current().smokeProbeFailureCount)
        assertFalse(health.deviceResolverReconnectEpisodeIsActive(first.epoch))
        repeat(2) {
            now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS
            owner.tick()
            runCurrent()
        }
        assertEquals(listOf("example.com", "apple.com", "cloudflare.com"), first.questions)
        assertEquals(0, first.ordinaryCalls)
        assertEquals(listOf(1L), reconnects)
        assertTrue(health.deviceResolverReconnectEpisodeIsActive(first.epoch))
        val next = Engine(2).apply { reply = ::served }
        first.close()
        install(health, next)
        assertTrue(health.deviceResolverReconnectEpisodeIsActive(next.epoch))
        owner.install(next)
        runCurrent()
        assertEquals(listOf("example.com"), next.questions)
        assertEquals(0, health.current().smokeProbeFailureCount)
        assertFalse(health.deviceResolverReconnectEpisodeIsActive(next.epoch))
        // A different primary cannot carry prior rejected-canary evidence.
        next.reply = { emptyReply(it, 5) }
        now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS
        owner.tick(); runCurrent()
        val changed = Engine(3, "other-device-primary")
        install(health, changed)
        assertEquals(0, health.current().smokeProbeFailureCount)
        assertFalse(health.recordSmokeProbe(first.epoch, 999, ids.incrementAndGet(),
            DnsResolverSmokeProbe.query(0, 5), ResolverResult.Answer(emptyReply(DnsResolverSmokeProbe.query(0, 5), 5), ResolverTransport.DEVICE_UDP)))
        owner.close()
    }

    @Test fun `a single denied canary cannot sustain a wedge and routine failures use chronic cadence`() = runTest {
        var now = 1000L
        val ids = AtomicLong()
        val health = ExactSessionHealthOwner(1, { now }, {}, {})
        val engine = Engine(1)
        install(health, engine)
        engine.reply = { query -> if (DnsMessage.parseQuestion(query).normalizedDomain == "example.com") emptyReply(query, 5) else served(query) }
        val owner = ResolverSmokeProbeOwner(backgroundScope, { now }, ids::incrementAndGet, { it.isCurrent() }, { health })
        owner.install(engine); runCurrent()
        repeat(5) {
            now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS
            owner.tick(); runCurrent()
            assertFalse(health.deviceResolverReconnectEpisodeIsActive(engine.epoch))
        }
        assertEquals(0, health.current().smokeProbeFailureCount)
        engine.reply = { emptyReply(it, 2) }
        repeat(5) {
            now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS
            owner.tick(); runCurrent()
        }
        val before = engine.questions.size
        now += ResolverSmokeProbeCadence.BASE_INTERVAL_MILLIS
        owner.tick(); runCurrent()
        assertEquals(before, engine.questions.size)
        // Event-driven work is not delayed by routine chronic backoff.
        owner.tick(force = true); runCurrent()
        assertEquals(before + 1, engine.questions.size)
        owner.close()
    }

    @Test fun `single in flight cancellation stale owner and chained role provide no failure or physical retry`() = runTest {
        val ids = AtomicLong()
        val health = ExactSessionHealthOwner(1, { 1000L }, {}, {})
        val engine = Engine(1)
        install(health, engine)
        val pending = CompletableDeferred<ByteArray>()
        engine.waitFor = pending
        var current = true
        val owner = ResolverSmokeProbeOwner(backgroundScope, { 1000L }, ids::incrementAndGet, { current && it.isCurrent() }, { health })
        owner.install(engine); runCurrent()
        owner.tick(force = true); runCurrent()
        assertEquals(1, engine.questions.size)
        owner.close(); runCurrent()
        pending.complete(served(DnsResolverSmokeProbe.query(0, 1)))
        runCurrent()
        assertEquals(0, health.current().smokeProbeFailureCount)
        assertFalse(health.deviceResolverReconnectEpisodeIsActive(engine.epoch))
        current = false // Same gate used for chained/superseded/retired runtime; no installed query target.
        val refused = ResolverSmokeProbeOwner(backgroundScope, { 1000L }, ids::incrementAndGet, { current }, { health })
        refused.install(engine); refused.tick(force = true); runCurrent()
        assertEquals(1, engine.questions.size)
        install(health, Engine(2), primary = false)
        assertFalse(health.recordSmokeProbe(engine.epoch, 3, 20, DnsResolverSmokeProbe.query(0, 1),
            ResolverResult.Failure(ResolverFailureReason.TIMEOUT)))
        refused.close()
    }

    private fun install(health: ExactSessionHealthOwner, engine: Engine, primary: Boolean = true) = health.installEngine(
        VpnHealthEngineIdentity(1, 1, engine.epoch.engineGeneration, engine.epoch.primaryResolverIdentity, engine.epoch.fallbackCoverage),
        devicePrimaryEpoch = engine.epoch.takeIf { primary }, primaryEpoch = engine.epoch.takeIf { primary },
    )

    private fun primaryCoordinator(engine: Engine, clock: MonotonicClock, dispatcher: CoroutineDispatcher,
        result: (ResolverRequest) -> ResolverResult): GenerationSocketRegistry {
        val plan = testResolverPlan(engine.networkContext, mullvadEnabled = true,
            primaryResolverIdentity = engine.epoch.primaryResolverIdentity)
        val registry = GenerationSocketRegistry(engine.epoch)
        val coordinator = ResolverCoordinator(engine.epoch, plan, registry, DeviceDnsRoute { request, _ ->
            val resolved = result(request)
            DeviceDnsRouteResult(resolved, if (resolved is ResolverResult.Answer)
                ResolverEndpointKey.DeviceAddress(engine.networkContext.dnsServers.first()) else null)
        }, DnsResolver { error("a primary-only smoke cannot consult encrypted fallback") },
            DeviceResolverWedgeEvidence { false }, clock = clock, childDispatcher = dispatcher)
        engine.primaryResolution = coordinator::resolvePrimary
        return registry
    }

    private class Engine(generation: Long, primary: String = "device-primary") : ReadyDnsEngine {
        override val epoch = EngineEpoch(1, ConfigurationIdentity(1, "config"), "artifact", 0, 1, generation,
            PrimaryResolverIdentity(primary), ResolverFallbackCoverage.ENCRYPTED_DNS)
        override val networkContext = NetworkContext(testNetwork(3), 1, listOf(InetAddress.getByName("192.168.1.53")), false)
        override val resolverPlan: ResolverPlan get() = error("unused controlled engine adapter")
        override val policyIdentity: ProxyPolicyIdentity get() = error("unused controlled engine adapter")
        override val proxy: DnsProxy get() = error("smoke must not issue a product query")
        private var current = true
        val questions = mutableListOf<String>()
        var ordinaryCalls = 0
        var reply: (ByteArray) -> ByteArray = { emptyReply(it, 5) }
        var waitFor: CompletableDeferred<ByteArray>? = null
        var failure: ResolverFailureReason? = null
        var primaryResolution: (suspend (ResolverRequest) -> ResolverResult)? = null
        var lastResult: ResolverResult? = null
        override fun isCurrent(): Boolean = current
        override fun completeIfCurrent(completion: () -> Boolean): Boolean = current && completion()
        override suspend fun resolveIfCurrent(request: ResolverRequest): ResolverResult {
            ordinaryCalls++
            error("smoke must never resolve the whole ladder")
        }
        override suspend fun resolvePrimaryIfCurrent(request: ResolverRequest): ResolverResult {
            assertSame(epoch, request.epoch); assertSame(networkContext, request.networkContext)
            questions += request.question.normalizedDomain
            primaryResolution?.let { return it(request).also { resolved -> lastResult = resolved } }
            failure?.let { return ResolverResult.Failure(it) }
            return ResolverResult.Answer(waitFor?.await() ?: reply(request.query), ResolverTransport.DEVICE_UDP)
        }
        override fun close() { current = false }
    }

    private companion object {
        fun served(query: ByteArray): ByteArray = DnsMessage.bootstrapResponse(query,
            byteArrayOf(8, 8, 8, 8), ByteArray(16).also { it[15] = 1 })
        fun emptyReply(query: ByteArray, rcode: Int): ByteArray = query.copyOf().also {
            it[2] = 0x81.toByte(); it[3] = (0x80 or rcode).toByte()
        }
    }
}
