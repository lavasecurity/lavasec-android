package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.health.ResolverTierDiagnosticObservation
import com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverTransitionStateTest {
    @Test
    fun `first accepted Mullvad fallback commits one activation`() {
        val state = ResolverTransitionState()
        val candidate = candidate(
            primary = ResolverKind.DEVICE,
            accepted = ResolverKind.MULLVAD_DOH,
            events = listOf(DnsHealthEvent.ResolverAttemptFailed(
                ResolverKind.DEVICE,
                ResolverFailureReason.TIMEOUT,
            )),
        )

        val committed = state.commit(candidate).answer()

        assertEquals(
            listOf(
                DnsHealthEvent.ResolverAttemptFailed(
                    ResolverKind.DEVICE,
                    ResolverFailureReason.TIMEOUT,
                ),
                DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH),
            ),
            committed.healthEvents,
        )
        assertEquals(1, candidate.result.healthEvents.size)
    }

    @Test
    fun `later accepted Mullvad fallbacks do not duplicate activation`() {
        val state = ResolverTransitionState()
        val fallback = candidate(ResolverKind.DEVICE, ResolverKind.MULLVAD_DOH)

        state.commit(fallback)
        val later = state.commit(fallback).answer()

        assertTrue(later.healthEvents.isEmpty())
    }

    @Test
    fun `first accepted Device answer after fallback commits one recovery`() {
        val state = ResolverTransitionState()
        state.commit(candidate(ResolverKind.DEVICE, ResolverKind.MULLVAD_DOH))

        val recovered = state.commit(candidate(ResolverKind.DEVICE, ResolverKind.DEVICE)).answer()
        val later = state.commit(candidate(ResolverKind.DEVICE, ResolverKind.DEVICE)).answer()

        assertEquals(
            listOf(DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE)),
            recovered.healthEvents,
        )
        assertTrue(later.healthEvents.isEmpty())
    }

    @Test
    fun `primary errors local admission and late liveness do not retire the fallback episode`() {
        for ((outcome, late) in listOf(ResolverTierDiagnosticOutcome.ANSWERED to false,
            ResolverTierDiagnosticOutcome.NOT_ATTEMPTED to false, ResolverTierDiagnosticOutcome.ANSWERED to true)) {
            val state = ResolverTransitionState()
            val fallback = candidate(ResolverKind.DEVICE, ResolverKind.MULLVAD_DOH)
            state.commit(fallback)
            val primary = candidate(ResolverKind.DEVICE, ResolverKind.DEVICE, evidence =
                ResolverQueryHealthEvidence.primaryAccepted(1, 1).copy(tierObservations = listOf(
                    ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, outcome, peerLivenessOnly = late))))
            assertTrue(state.evaluate(primary).answer().healthEvents.isEmpty())
            assertTrue(state.commit(primary).answer().healthEvents.isEmpty())
            // A later rescue belongs to the existing episode; it cannot activate it again.
            assertTrue(state.commit(fallback).answer().healthEvents.isEmpty())
            val served = candidate(ResolverKind.DEVICE, ResolverKind.DEVICE, evidence =
                ResolverQueryHealthEvidence.primaryAccepted(2, 1).copy(tierObservations = listOf(
                    ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.SERVED))))
            assertEquals(listOf(DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE)),
                state.commit(served).answer().healthEvents)
        }
    }

    @Test
    fun `Mullvad primary acceptance never activates fallback`() {
        val state = ResolverTransitionState()

        val primary = state.commit(candidate(ResolverKind.MULLVAD_DOH, ResolverKind.MULLVAD_DOH)).answer()
        state.commit(candidate(ResolverKind.DEVICE, ResolverKind.MULLVAD_DOH))
        val laterPrimary = state.commit(candidate(ResolverKind.MULLVAD_DOH, ResolverKind.MULLVAD_DOH)).answer()
        val device = state.commit(candidate(ResolverKind.DEVICE, ResolverKind.DEVICE)).answer()

        assertTrue(primary.healthEvents.isEmpty())
        assertTrue(laterPrimary.healthEvents.isEmpty())
        assertEquals(
            listOf(DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE)),
            device.healthEvents,
        )
    }

    @Test
    fun `failed and backed off candidates do not create transitions`() {
        val state = ResolverTransitionState()

        val failed = state.commit(failure(ResolverFailureReason.TIMEOUT))
        val backedOff = state.commit(failure(ResolverFailureReason.BACKED_OFF))
        val device = state.commit(candidate(ResolverKind.DEVICE, ResolverKind.DEVICE)).answer()

        assertTrue(failed.healthEvents.isEmpty())
        assertTrue(backedOff.healthEvents.isEmpty())
        assertTrue(device.healthEvents.isEmpty())
    }

    @Test
    fun `concurrent commits publish each transition at most once`() {
        val state = ResolverTransitionState()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val fallbackResults = executor.invokeAll(
                List(32) {
                    Callable {
                        state.commit(candidate(ResolverKind.DEVICE, ResolverKind.MULLVAD_DOH)).answer()
                    }
                },
            ).map { it.get() }
            assertEquals(
                1,
                fallbackResults.sumOf { result ->
                    result.healthEvents.count { it is DnsHealthEvent.FallbackActivated }
                },
            )

            val recoveryResults = executor.invokeAll(
                List(32) {
                    Callable {
                        state.commit(candidate(ResolverKind.DEVICE, ResolverKind.DEVICE)).answer()
                    }
                },
            ).map { it.get() }
            assertEquals(
                1,
                recoveryResults.sumOf { result ->
                    result.healthEvents.count { it is DnsHealthEvent.ResolverRecovered }
                },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `candidate and returned event lists are immutable snapshots`() {
        val state = ResolverTransitionState()
        val sourceEvents = mutableListOf<DnsHealthEvent>(
            DnsHealthEvent.ResolverAttemptFailed(ResolverKind.DEVICE, ResolverFailureReason.TIMEOUT),
        )
        val candidate = candidate(
            primary = ResolverKind.DEVICE,
            accepted = ResolverKind.MULLVAD_DOH,
            events = sourceEvents,
        )

        val committed = state.commit(candidate).answer()
        sourceEvents.clear()

        assertEquals(1, candidate.result.healthEvents.size)
        assertEquals(2, committed.healthEvents.size)
        assertImmutable(candidate.result.healthEvents)
        assertImmutable(committed.healthEvents)
    }

    @Test
    fun `evaluation does not mutate transition state`() {
        val state = ResolverTransitionState()
        val fallback = candidate(ResolverKind.DEVICE, ResolverKind.MULLVAD_DOH)

        val evaluated = state.evaluate(fallback).answer()
        val committed = state.commit(fallback).answer()

        assertEquals(
            listOf(DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH)),
            evaluated.healthEvents,
        )
        assertEquals(
            listOf(DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH)),
            committed.healthEvents,
        )
        assertTrue(fallback.result.healthEvents.isEmpty())
    }

    @Test
    fun `throwing result construction leaves transition state unchanged`() {
        val sentinel = IllegalStateException("sentinel result construction")
        val throwNext = AtomicBoolean(true)
        val state = ResolverTransitionState { result, event ->
            if (throwNext.compareAndSet(true, false)) throw sentinel
            resultWithEvent(result, event)
        }
        val fallback = candidate(ResolverKind.DEVICE, ResolverKind.MULLVAD_DOH)

        val thrown = assertThrows(IllegalStateException::class.java) {
            state.commit(fallback)
        }
        val activated = state.commit(fallback).answer()
        val recovered = state.commit(
            candidate(ResolverKind.DEVICE, ResolverKind.DEVICE),
        ).answer()

        assertSame(sentinel, thrown)
        assertEquals(
            listOf(DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH)),
            activated.healthEvents,
        )
        assertEquals(
            listOf(DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE)),
            recovered.healthEvents,
        )
    }

    private fun candidate(
        primary: ResolverKind,
        accepted: ResolverKind,
        events: List<DnsHealthEvent> = emptyList(),
        evidence: ResolverQueryHealthEvidence? = null,
    ): ResolverCandidate = ResolverCandidate(
        result = ResolverResult.Answer(
            dnsMessage = byteArrayOf(1, 2, 3),
            transport = ResolverTransport.MULLVAD_DOH,
            healthEvents = events,
            resolverQueryEvidence = evidence,
        ),
        primaryResolverKind = primary,
        acceptedResolverKind = accepted,
    )

    private fun failure(reason: ResolverFailureReason): ResolverCandidate = ResolverCandidate(
        result = ResolverResult.Failure(reason),
        primaryResolverKind = ResolverKind.DEVICE,
        acceptedResolverKind = null,
    )

    private fun ResolverResult.answer(): ResolverResult.Answer {
        assertTrue("expected terminal answer", this is ResolverResult.Answer)
        return this as ResolverResult.Answer
    }

    private fun resultWithEvent(
        result: ResolverResult,
        event: DnsHealthEvent?,
    ): ResolverResult {
        val healthEvents = if (event == null) result.healthEvents else result.healthEvents + event
        return when (result) {
            is ResolverResult.Answer -> ResolverResult.Answer(
                dnsMessage = result.dnsMessage,
                transport = result.transport,
                healthEvents = healthEvents,
                resolverQueryEvidence = result.resolverQueryEvidence,
            )

            is ResolverResult.Failure -> ResolverResult.Failure(
                reason = result.reason,
                healthEvents = healthEvents,
                resolver = result.resolver,
                resolverQueryEvidence = result.resolverQueryEvidence,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertImmutable(events: List<DnsHealthEvent>) {
        val mutableEvents = events as MutableList<DnsHealthEvent>
        assertThrows(UnsupportedOperationException::class.java) { mutableEvents.clear() }
        assertFalse(events.isEmpty())
    }
}
