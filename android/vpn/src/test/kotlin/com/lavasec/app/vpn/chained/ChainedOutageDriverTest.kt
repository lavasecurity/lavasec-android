package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedReconnectPolicy
import com.lavasec.app.foundation.chained.ChainedSessionBuildFailure
import com.lavasec.app.foundation.chained.ChainedSessionEndCause
import com.lavasec.app.foundation.chained.ChainedSurrenderReason
import com.lavasec.app.foundation.chained.WireGuardEngineError
import com.lavasec.app.vpn.ChainedEngineQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android port of the iOS `ChainedOutageDriver`: the driver owns the attempt lifecycle —
 * budget, retry ladder, per-attempt watchdog, and building/superseding the runner through its
 * session source — so the control plane no longer installs replacements.
 */
class ChainedOutageDriverTest {
    @Test fun `wake requires new authenticated peer evidence instead of a retained handshake`() {
        val diagnostics = mutableListOf<ChainedNerdDiagnostics>()
        val driver = ChainedOutageDriver(ChainedEngineQueue(), FakeTimers(), FakeSource(), { 100 },
            onDiagnostics = { diagnostics.add(it) })
        val runner = FakeRunner(ChainedDriverLivenessSample(true, null))
        driver.adopt(runner)
        driver.tick()
        assertFalse(diagnostics.last().rechecking)
        driver.sleep()
        assertTrue(diagnostics.last().rechecking)
        driver.wake()
        driver.tick()
        assertTrue(diagnostics.last().rechecking)
        runner.sample = ChainedDriverLivenessSample(true, null, sawAuthenticatedPeerDatagram = true)
        driver.tick()
        assertFalse(diagnostics.last().rechecking)
    }

    private val expired: ChainedSessionEndCause =
        requireNotNull(ChainedSessionEndCause.engineVerdict(WireGuardEngineError.ConnectionExpired))

    private class FakeTimers : ChainedDriverTimers {
        var tickInterval: Long? = null
        private val armed = mutableListOf<() -> Unit>()

        /** Every armed delay, so a test can prove a timer was (or was not) re-armed. */
        val armedDelays = mutableListOf<Long>()

        override fun arm(afterMillis: Long, fire: () -> Unit): ChainedArmedTimer {
            armedDelays.add(afterMillis)
            armed.add(fire)
            return object : ChainedArmedTimer {
                override fun cancel() = Unit
            }
        }

        override fun scheduleTick(intervalMillis: Long?) {
            tickInterval = intervalMillis
        }

        val armedCount: Int get() = armed.size

        /** Fires exactly the timers armed as of this call; newly armed ones wait for the next. */
        fun fireAll() {
            val pending = armed.toList()
            armed.clear()
            pending.forEach { it() }
        }
    }

    private class FakeRunner(
        var sample: ChainedDriverLivenessSample = ChainedDriverLivenessSample(false, null),
    ) : ChainedSessionDriving {
        var resetCount = 0
        override fun resetLivenessAfterSuspension() { resetCount++ }
        var tickCount = 0
        var handshakeCount = 0
        var shutdownCount = 0
        var adoptedChannelCount = 0
        var nextRebindOutcome: ChainedRebindOutcome = ChainedRebindOutcome.REBOUND

        override fun handleOutboundBatch(packets: List<ByteArray>) = Unit
        override fun adoptChannel(replacement: ChainedUpstreamChannel): ChainedRebindOutcome {
            adoptedChannelCount++
            return nextRebindOutcome
        }
        override fun tick() { tickCount++ }
        override fun forceHandshake() { handshakeCount++ }
        override fun takeLivenessSample(): ChainedDriverLivenessSample = sample
        override fun acceptedUpstreamGeneration(): Long = 1L
        override fun shutdown() { shutdownCount++ }
    }

    private class FakeChannel : ChainedUpstreamChannel {
        var closeCount = 0
            private set

        override fun send(datagram: ByteArray): Boolean = true

        override suspend fun receive(): ChainedPeerDatagram? = null

        override fun close() {
            closeCount++
        }
    }

    private class FakeSource : ChainedSessionSource {
        var calls = 0
        var channelCalls = 0
        var lastEvents: ChainedSessionEvents? = null
        var lastDelivery: ChainedSessionDelivery? = null
        var next: ChainedSessionDriving? = FakeRunner()
        var nextChannel: ChainedUpstreamChannel? = FakeChannel()
        var failure: (() -> Throwable)? = null

        /**
         * When true, `makeSession` starts a build and returns WITHOUT delivering it, so the test
         * can drive the watchdog/retirement while the allocation is notionally still in flight.
         */
        var deferBuild = false

        /**
         * When true, `makeChannel` starts a rebuild and returns WITHOUT delivering it, so the test
         * can drive the path/outage machinery while the off-queue resolution is notionally still in
         * flight (finding 4022018493).
         */
        var deferChannelBuild = false

        var lastChannelDelivery: ChainedChannelDelivery? = null

        /** Channels built and released by their source because the driver's fence refused them. */
        var releasedRefusedChannels = 0

        /** Builds refused and released by their source; the driver's ownership fence caused it. */
        var releasedRefusedBuilds = 0

        override fun makeChannel(delivery: ChainedChannelDelivery): Boolean {
            channelCalls++
            lastChannelDelivery = delivery
            if (deferChannelBuild) return true
            deliverChannel(delivery)
            return true
        }

        /**
         * Completes a deferred channel build exactly as a source would: the built channel is
         * offered to the driver's fence, and released when the fence refuses it (the rebind was
         * superseded while the lookup ran).
         */
        fun completeDeferredChannel(): Boolean {
            val delivery = requireNotNull(lastChannelDelivery) { "no deferred channel build in flight" }
            val channel = nextChannel
            if (channel == null) {
                delivery.failed()
                return false
            }
            val adopted = delivery.adopt(channel)
            if (!adopted) {
                channel.close()
                releasedRefusedChannels += 1
            }
            return adopted
        }

        private fun deliverChannel(delivery: ChainedChannelDelivery) {
            val channel = nextChannel
            if (channel == null) {
                delivery.failed()
            } else {
                delivery.adopt(channel)
            }
        }

        override fun makeSession(events: ChainedSessionEvents, delivery: ChainedSessionDelivery) {
            calls++
            lastEvents = events
            lastDelivery = delivery
            if (deferBuild) return
            failure?.let { throw it() }
            val built = next
            if (built == null) {
                delivery.failed(null)
            } else {
                delivery.adopt { built }
            }
        }

        /**
         * Completes a deferred build exactly as a source would: the built runner is offered to the
         * driver's fence, and released when the fence refuses it (the attempt was retired or
         * superseded while the allocation ran).
         */
        fun completeDeferredBuild(): Boolean {
            val delivery = requireNotNull(lastDelivery) { "no deferred build in flight" }
            val built = requireNotNull(next) { "deferred build expects a session" }
            val adopted = delivery.adopt { built }
            if (!adopted) {
                built.shutdown()
                releasedRefusedBuilds += 1
            }
            return adopted
        }
    }

    private object TransientBuildFailure : RuntimeException(), ChainedSessionBuildFailure {
        override val warrantsAnotherAttempt: Boolean get() = true
    }

    private object PermanentBuildFailure : RuntimeException(), ChainedSessionBuildFailure {
        override val warrantsAnotherAttempt: Boolean get() = false
    }

    private fun newDriver(
        queue: ChainedEngineQueue,
        timers: FakeTimers,
        source: FakeSource,
        clock: () -> Int,
        surrendered: MutableList<ChainedSurrenderReason>,
    ): ChainedOutageDriver = ChainedOutageDriver(
        engineQueue = queue,
        timers = timers,
        source = source,
        clock = clock,
        onSurrender = { reason, _ -> surrendered.add(reason) },
    )

    @Test
    fun `only traffic after the suspension boundary self heals and drains old evidence`() {
        val queue = ChainedEngineQueue()
        try {
            val timers = FakeTimers()
            val runner = FakeRunner(ChainedDriverLivenessSample(true, null))
            val driver = newDriver(queue, timers, FakeSource(), { 0 }, mutableListOf())
            driver.adopt(runner)
            driver.sleep()
            assertNull(timers.tickInterval)
            driver.handleOutboundBatch(emptyList())
            assertEquals(0, runner.handshakeCount)
            driver.confirmSuspensionBoundary()
            driver.handleOutboundBatch(listOf(byteArrayOf(1)))
            assertEquals(1, runner.handshakeCount)
            assertEquals(1, runner.resetCount)
            assertEquals(1, driver.snapshotCounters().suspensionSelfHealCount)
            assertEquals(500L, timers.tickInterval)
            driver.wake()
            assertEquals(1, runner.resetCount)
        } finally { queue.close() }
    }

    @Test
    fun `DNS outage survives replacement keepalives and ends on a current resolver reply`() {
        val queue = ChainedEngineQueue()
        try {
            val timers = FakeTimers()
            val source = FakeSource()
            val replacement = FakeRunner(ChainedDriverLivenessSample(true, null, true))
            source.next = replacement
            var now = 0
            val driver = newDriver(queue, timers, source, { now }, mutableListOf())
            driver.adopt(FakeRunner(ChainedDriverLivenessSample(true, null)))
            val epoch = driver.dnsObservationEpoch()
            driver.reportTunnelDns(epoch, "a")
            now = 21
            driver.reportTunnelDns(epoch, "b")
            assertEquals(1, driver.snapshotCounters().tunnelDnsOutageCount)
            timers.fireAll()
            assertTrue(driver.isTimingAnOutage())
            driver.tick()
            assertTrue(driver.isTimingAnOutage())
            driver.reportTunnelDns(driver.dnsObservationEpoch(), null)
            assertFalse(driver.isTimingAnOutage())
        } finally { queue.close() }
    }

    @Test
    fun `egress outage requires delivered application traffic after rebuilding`() {
        val queue = ChainedEngineQueue()
        try {
            val timers = FakeTimers()
            val source = FakeSource()
            val replacement = FakeRunner(ChainedDriverLivenessSample(true, null, true, forwardingGeneration = 1))
            source.next = replacement
            var now = 0
            val driver = ChainedOutageDriver(queue, timers, source, { now }, fullTunnel = true)
            driver.adopt(FakeRunner(ChainedDriverLivenessSample(true, null, true, true)))
            for (second in 0..21) { now = second; driver.tick() }
            assertEquals(1, driver.snapshotCounters().egressDeadOutageCount)
            timers.fireAll()
            assertTrue(driver.isTimingAnOutage())
            driver.tick()
            assertTrue(driver.isTimingAnOutage())
            replacement.sample = replacement.sample.copy(forwardedNonDnsBytes = 100)
            driver.tick()
            assertFalse(driver.isTimingAnOutage())
        } finally { queue.close() }
    }

    @Test
    fun `a session end reaches the ladder and the driver builds the replacement itself`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        val replacement = FakeRunner()
        source.next = replacement
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        driver.adopt(FakeRunner())
        driver.sessionEnded(expired)

        assertEquals(1, driver.snapshotCounters().outageCount)
        assertEquals(1, driver.snapshotCounters().sessionEndCount)
        assertTrue(driver.isTimingAnOutage())
        assertTrue(timers.armedCount >= 1)

        timers.fireAll()

        assertEquals(1, source.calls)
        assertEquals(1, driver.snapshotCounters().startedAttemptCount)
        assertEquals(1, replacement.handshakeCount)
        assertTrue(surrendered.isEmpty())
        queue.close()
    }

    @Test
    fun `the driver consumes runner ends reported through its own events sink`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        driver.adopt(FakeRunner())
        driver.sessionEnded(expired)
        timers.fireAll()

        // The replacement runner reports its own end through the events the driver installed.
        source.lastEvents?.sessionEnded(expired)

        assertEquals(2, driver.snapshotCounters().sessionEndCount)
        queue.close()
    }

    @Test
    fun `a transient build failure spends a rung rather than surrendering`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        source.failure = { TransientBuildFailure }
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        driver.adopt(FakeRunner())
        driver.sessionEnded(expired)
        timers.fireAll()

        assertEquals(1, source.calls)
        assertEquals(1, driver.snapshotCounters().startedAttemptCount)
        assertTrue(driver.isTimingAnOutage())
        assertTrue(surrendered.isEmpty())
        queue.close()
    }

    @Test
    fun `a permanent build failure surrenders chaining`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        source.failure = { PermanentBuildFailure }
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        driver.adopt(FakeRunner())
        driver.sessionEnded(expired)
        timers.fireAll()

        assertEquals(listOf(ChainedSurrenderReason.ENGINE_UNUSABLE), surrendered)
        assertTrue(driver.hasSurrenderedChaining())
        queue.close()
    }

    @Test
    fun `a refused factory surrenders chaining`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        source.next = null
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        driver.adopt(FakeRunner())
        driver.sessionEnded(expired)
        timers.fireAll()

        assertEquals(listOf(ChainedSurrenderReason.ENGINE_UNUSABLE), surrendered)
        queue.close()
    }

    @Test
    fun `the per-attempt watchdog surrenders at the authorized deadline`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        driver.adopt(FakeRunner())
        driver.sessionEnded(expired)
        timers.fireAll() // build attempt 1 and arm its watchdog

        now = ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS
        timers.fireAll() // the watchdog fires at budget exhaustion

        assertEquals(listOf(ChainedSurrenderReason.BUDGET_EXHAUSTED), surrendered)
        assertTrue(driver.hasSurrenderedChaining())
        queue.close()
    }

    @Test
    fun `the watchdog can fire and surrender while the source is still allocating`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        source.deferBuild = true
        val built = FakeRunner()
        source.next = built
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        driver.adopt(FakeRunner())
        driver.sessionEnded(expired)
        timers.fireAll() // the retry delay authorizes attempt 1; its build is left in flight

        assertEquals(1, driver.snapshotCounters().startedAttemptCount)
        assertTrue(driver.isTimingAnOutage())

        // The ~50 MB allocation is STILL in flight. Because `makeSession` did not hold the engine
        // queue, this attempt's watchdog fires (instead of waiting behind the build) and bounds
        // the blackhole.
        now = ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS
        timers.fireAll()

        assertEquals(listOf(ChainedSurrenderReason.BUDGET_EXHAUSTED), surrendered)
        assertTrue(driver.hasSurrenderedChaining())

        // The late allocation is refused — not installed — and the source releases it.
        assertFalse(source.completeDeferredBuild())
        assertEquals(1, source.releasedRefusedBuilds)
        assertEquals(0, built.handshakeCount)
        assertEquals(1, built.shutdownCount)
        queue.close()
    }

    @Test
    fun `a session built for an attempt retired while allocating is closed, not installed`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        source.deferBuild = true
        val built = FakeRunner()
        source.next = built
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        driver.adopt(FakeRunner())
        driver.sessionEnded(expired)
        timers.fireAll() // authorize attempt 1; the allocation is in flight
        driver.retire() // the lifecycle tears down while the allocation is still running

        assertFalse(source.completeDeferredBuild())
        assertEquals(1, source.releasedRefusedBuilds)
        assertEquals(0, built.handshakeCount)
        assertEquals(1, built.shutdownCount)
        queue.close()
    }

    @Test
    fun `an off-queue build that completes while its attempt is current is installed and handshook`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        source.deferBuild = true
        val built = FakeRunner()
        source.next = built
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        driver.adopt(FakeRunner())
        driver.sessionEnded(expired)
        timers.fireAll()

        assertTrue(source.completeDeferredBuild())
        assertEquals(0, source.releasedRefusedBuilds)
        assertEquals(1, built.handshakeCount)
        assertEquals(0, built.shutdownCount)
        queue.close()
    }

    @Test
    fun `a satisfied path change mid-outage keeps the original blackhole deadline`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        val fullWindowMillis = ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS * 1_000L

        // The outage opens at t=0; its deadline is anchored there and armed for the full window.
        driver.adopt(FakeRunner(sample = ChainedDriverLivenessSample(hasHandshake = false, null)))
        driver.tick()
        assertTrue(driver.isTimingAnOutage())
        assertEquals(1, timers.armedDelays.count { it == fullWindowMillis })

        // Most of the budget later, a satisfied path change (a roam) re-enters the outage without
        // starting a new one. The driver must keep the ORIGINAL deadline: re-arming it at `now +
        // MAX` would extend the blackhole toward a second full window.
        now = ChainedReconnectPolicy.MAXIMUM_BLACKHOLE_SECONDS - 1
        driver.pathChanged(satisfied = true, interfacesChanged = true)
        assertTrue(driver.isTimingAnOutage())
        assertEquals(1, timers.armedDelays.count { it == fullWindowMillis })
        queue.close()
    }

    @Test
    fun `a tick with no handshake opens the outage and pumps at the outage cadence`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        driver.adopt(FakeRunner(sample = ChainedDriverLivenessSample(false, null)))
        driver.tick()

        // The outage is open, but with no runner there is nothing to pump, so the tick is
        // cancelled until the ladder's first attempt builds one.
        assertTrue(driver.isTimingAnOutage())
        assertNull(timers.tickInterval)

        timers.fireAll() // the retry delay fires and builds the first attempt

        assertEquals(250L, timers.tickInterval)
        assertEquals(1, driver.snapshotCounters().startedAttemptCount)
        queue.close()
    }

    @Test
    fun `a retired driver refuses batches and is inert`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        val runner = FakeRunner()
        driver.adopt(runner)
        driver.retire()

        assertEquals(1, runner.shutdownCount)
        assertNull(timers.tickInterval)
        assertFalse(driver.handleOutboundBatch(listOf(ByteArray(4))))
        queue.close()
    }

    @Test
    fun `a satisfied path change outside an outage rebinds rather than rebuilding`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        val runner = FakeRunner()
        driver.adopt(runner)

        driver.pathChanged(satisfied = true, interfacesChanged = true)

        assertEquals(1, source.channelCalls)
        assertEquals(1, runner.adoptedChannelCount)
        assertEquals(1, driver.snapshotCounters().rebindCount)
        assertFalse(driver.isTimingAnOutage())
        assertTrue(surrendered.isEmpty())
        queue.close()
    }

    @Test
    fun `a rebind with no current keypair declines and falls back to a rebuild`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        val runner = FakeRunner().apply {
            nextRebindOutcome = ChainedRebindOutcome.NO_CURRENT_SESSION
        }
        driver.adopt(runner)

        driver.pathChanged(satisfied = true, interfacesChanged = true)

        assertEquals(1, driver.snapshotCounters().rebindDeclinedCount)
        assertTrue(driver.isTimingAnOutage())
        queue.close()
    }

    @Test
    fun `a refused rebind declines and falls back to the ladder`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        val runner = FakeRunner().apply {
            nextRebindOutcome = ChainedRebindOutcome.REFUSED
        }
        driver.adopt(runner)

        driver.pathChanged(satisfied = true, interfacesChanged = true)

        assertEquals(1, driver.snapshotCounters().rebindDeclinedCount)
        assertTrue(driver.isTimingAnOutage())
        queue.close()
    }

    @Test
    fun `a rebind channel delivered after the ladder began is refused`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        val runner = FakeRunner(sample = ChainedDriverLivenessSample(true, 0))
        source.deferChannelBuild = true
        driver.adopt(runner)

        // The rebind starts its off-queue lookup and returns; the engine queue is free to tick.
        driver.pathChanged(satisfied = true, interfacesChanged = true)
        assertEquals(1, source.channelCalls)
        assertFalse(driver.isTimingAnOutage())

        // The lookup is still running when a stall opens the outage and retires the runner.
        now = 16
        driver.tick()
        assertTrue(driver.isTimingAnOutage())

        // The late channel arrives after the ladder took over: the fence refuses it and the
        // source releases it rather than swapping it under a runner the outage replaced.
        assertFalse(source.completeDeferredChannel())
        assertEquals(1, source.releasedRefusedChannels)
        assertEquals(0, runner.adoptedChannelCount)
        assertEquals(0, driver.snapshotCounters().rebindCount)
        queue.close()
    }

    @Test
    fun `an unconfirmed rebind retries the re-anchor before rebuilding`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        // A stalled-but-not-yet-outage sample: the confirmation window elapses without evidence,
        // and without a stall long enough to open an outage, so the lone rebind gets its retry.
        val runner = FakeRunner(sample = ChainedDriverLivenessSample(true, 0))
        driver.adopt(runner)

        driver.pathChanged(satisfied = true, interfacesChanged = true)
        timers.fireAll() // the confirmation fires -> one re-anchor retry

        assertEquals(1, driver.snapshotCounters().rebindReanchorRetryCount)
        assertEquals(0, driver.snapshotCounters().rebindUnconfirmedCount)
        assertTrue(runner.handshakeCount >= 1)
        assertFalse(driver.isTimingAnOutage())
        queue.close()
    }

    @Test
    fun `a second confirmation miss rebuilds through the ladder`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        val runner = FakeRunner(sample = ChainedDriverLivenessSample(true, 0))
        driver.adopt(runner)

        driver.pathChanged(satisfied = true, interfacesChanged = true)
        timers.fireAll() // re-anchor retry
        timers.fireAll() // the retry's window misses -> rebuild

        assertEquals(1, driver.snapshotCounters().rebindReanchorRetryCount)
        assertEquals(1, driver.snapshotCounters().rebindUnconfirmedCount)
        assertTrue(driver.isTimingAnOutage())
        queue.close()
    }

    @Test
    fun `authenticated peer evidence newer than the swap confirms a rebind without a rebuild`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        // A peer datagram was decapsulated on the REPLACEMENT channel since the swap: the one
        // signal that can only come from a socket that actually carried traffic.
        val runner = FakeRunner(
            sample = ChainedDriverLivenessSample(
                hasHandshake = true,
                firstUnansweredSendAtSeconds = null,
                sawAuthenticatedPeerDatagram = true,
            ),
        )
        driver.adopt(runner)

        driver.pathChanged(satisfied = true, interfacesChanged = true)
        timers.fireAll()

        assertEquals(0, driver.snapshotCounters().rebindUnconfirmedCount)
        assertEquals(0, driver.snapshotCounters().rebindReanchorRetryCount)
        assertFalse(driver.isTimingAnOutage())
        queue.close()
    }

    @Test
    fun `an idle established session does not confirm a rebind without new peer evidence`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        // Handshake complete AND nothing outstanding — a pre-rebind state that looks perfectly
        // healthy — but NO authenticated datagram since the swap. Treating this as confirmation
        // let a dead replacement bypass the 3 s fallback and blackhole the first traffic until the
        // much longer stall detector reacted.
        val runner = FakeRunner(
            sample = ChainedDriverLivenessSample(
                hasHandshake = true,
                firstUnansweredSendAtSeconds = null,
                sawAuthenticatedPeerDatagram = false,
            ),
        )
        driver.adopt(runner)

        driver.pathChanged(satisfied = true, interfacesChanged = true)
        // The first scheduled tick must NOT clear the confirmation: the session is merely idle.
        driver.tick()
        assertEquals(0, driver.snapshotCounters().rebindReanchorRetryCount)
        assertEquals(0, driver.snapshotCounters().rebindUnconfirmedCount)

        // The window expires, and the lone rebind gets its bounded re-anchor retry rather than
        // being silently confirmed on liveness that predates the swap.
        timers.fireAll()
        assertEquals(1, driver.snapshotCounters().rebindReanchorRetryCount)
        assertEquals(0, driver.snapshotCounters().rebindUnconfirmedCount)
        assertFalse(driver.isTimingAnOutage())
        queue.close()
    }

    @Test
    fun `a flap burst coalesces into one leading and one trailing recovery`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        // Peer evidence on the leading socket, so the leading rebind is confirmed and the trailing
        // recovery is what rebuilds — the coalescing the test is about.
        val runner = FakeRunner(
            sample = ChainedDriverLivenessSample(
                hasHandshake = true,
                firstUnansweredSendAtSeconds = null,
                sawAuthenticatedPeerDatagram = true,
            ),
        )
        driver.adopt(runner)

        driver.pathChanged(satisfied = true, interfacesChanged = true)
        driver.pathChanged(satisfied = true, interfacesChanged = true)

        assertEquals(1, driver.snapshotCounters().coalescedPathRecoveryCount)
        assertEquals(1, source.channelCalls)

        timers.fireAll()
        assertEquals(2, source.channelCalls)
        queue.close()
    }

    @Test
    fun `a ladder build satisfies a coalesced path recovery so it is not repeated`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        // The first path change cannot rebind (no current keypair), so it opens the outage and
        // records the leading socket recovery. The replacement the ladder will build is healthy.
        val runner = FakeRunner().apply {
            nextRebindOutcome = ChainedRebindOutcome.NO_CURRENT_SESSION
        }
        source.next = FakeRunner(
            sample = ChainedDriverLivenessSample(hasHandshake = true, firstUnansweredSendAtSeconds = null),
        )
        driver.adopt(runner)

        driver.pathChanged(satisfied = true, interfacesChanged = true)
        assertTrue(driver.isTimingAnOutage())
        assertEquals(1, source.channelCalls)

        // A second transition inside the settle window while the outage is timed: the driver records
        // the coalesced recovery as a debt for the ladder's next fresh build.
        driver.pathChanged(satisfied = true, interfacesChanged = true)
        assertEquals(1, driver.snapshotCounters().coalescedPathRecoveryCount)

        // The ladder builds a fresh session on the CURRENT path, satisfying the debt.
        timers.fireAll()
        assertEquals(1, driver.snapshotCounters().startedAttemptCount)

        // Ending the outage on healthy evidence must NOT run the deferred socket recovery again: the
        // fresh build already bound a socket on the current path. Before the debt was cleared on
        // adoption, `endOutage` called `performPathSocketRecovery` again — a redundant rebind (or an
        // immediate new outage when the channel build declines).
        driver.tick()
        assertEquals(1, source.channelCalls)
        assertEquals(0, driver.snapshotCounters().rebindCount)
        queue.close()
    }

    @Test
    fun `a path transition during a deferred ladder build keeps the coalesced recovery debt`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        source.deferBuild = true
        // The fresh build is healthy, so its adoption ends the outage on evidence.
        val built = FakeRunner(
            sample = ChainedDriverLivenessSample(hasHandshake = true, firstUnansweredSendAtSeconds = null),
        )
        source.next = built
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)

        // Open an outage. A leading transition records the socket-recovery instant (there is no
        // runner yet, so it just re-enters the outage).
        driver.adopt(
            FakeRunner(
                sample = ChainedDriverLivenessSample(hasHandshake = false, firstUnansweredSendAtSeconds = null),
            ),
        )
        driver.tick()
        driver.pathChanged(satisfied = true, interfacesChanged = true)

        // Now start a ladder build whose underlay is selected off-queue and left in flight.
        timers.fireAll()
        assertEquals(1, driver.snapshotCounters().startedAttemptCount)

        // A second transition inside the settle window coalesces into a debt for the ladder's next
        // fresh build. It lands while the allocation is still in flight, after the build captured
        // the path epoch and before adoption.
        now = 1
        driver.pathChanged(satisfied = true, interfacesChanged = true)
        assertEquals(1, driver.snapshotCounters().coalescedPathRecoveryCount)

        // The build adopts AFTER that transition, so its socket was selected against the
        // pre-transition underlay. The debt must survive adoption.
        assertTrue(source.completeDeferredBuild())

        // Healthy evidence ends the outage; the retained debt must still run the socket recovery
        // (a rebind). Erasing it unconditionally at adoption skipped this.
        now = 2
        driver.tick()
        assertEquals(1, source.channelCalls)
        queue.close()
    }

    @Test
    fun `an unsatisfied path quiesces and a later satisfied transition recovers`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        val runner = FakeRunner(sample = ChainedDriverLivenessSample(true, null))
        driver.adopt(runner)

        driver.pathChanged(satisfied = false, interfacesChanged = false)
        assertEquals(1, driver.snapshotCounters().offlinePathCount)
        assertFalse(driver.isTimingAnOutage())
        assertNull(timers.tickInterval)

        driver.pathChanged(satisfied = true, interfacesChanged = true)
        assertEquals(1, driver.snapshotCounters().pathRecoveryCount)
        assertEquals(1, source.channelCalls)
        queue.close()
    }

    @Test
    fun `a path transition during suspension is deferred to the paired wake`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        val runner = FakeRunner(sample = ChainedDriverLivenessSample(true, null))
        driver.adopt(runner)

        driver.sleep()
        driver.pathChanged(satisfied = true, interfacesChanged = true)
        assertEquals(0, source.channelCalls)

        driver.wake()
        assertEquals(1, source.channelCalls)
        queue.close()
    }

    @Test
    fun `an unpaired wake does not refund or re-arm`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        val runner = FakeRunner(sample = ChainedDriverLivenessSample(true, null))
        driver.adopt(runner)

        driver.wake()

        assertEquals(0, runner.handshakeCount)
        queue.close()
    }

    @Test
    fun `a paired wake re-arms a surviving runner`() {
        val queue = ChainedEngineQueue()
        val timers = FakeTimers()
        val source = FakeSource()
        var now = 0
        val surrendered = mutableListOf<ChainedSurrenderReason>()
        val driver = newDriver(queue, timers, source, { now }, surrendered)
        val runner = FakeRunner(sample = ChainedDriverLivenessSample(true, null))
        driver.adopt(runner)

        driver.sleep()
        driver.wake()

        assertTrue(runner.handshakeCount >= 1)
        queue.close()
    }
}
