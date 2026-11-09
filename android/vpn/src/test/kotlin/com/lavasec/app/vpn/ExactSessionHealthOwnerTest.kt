package com.lavasec.app.vpn

import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.PrimaryResolverOutcome
import com.lavasec.app.foundation.health.ProtectionHealthSeverity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.health.TunnelSelfReconnectPolicy
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverTierDiagnosticObservation
import com.lavasec.app.foundation.health.ResolverTierDiagnosticOutcome
import com.lavasec.app.foundation.health.DnsFallbackResponseClass
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.platform.ConfigurationIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ExactSessionHealthOwnerTest {
    @Test fun `internet observations are surface only and old engines cannot validate a successor`() {
        val reconnects = mutableListOf<Long>()
        val owner = ExactSessionHealthOwner(11, { 1000L }, {}, { reconnects.add(it) })
        val installed = identity(11, 1)
        owner.installEngine(installed)
        val prior = owner.current()
        assertTrue(owner.recordInternetValidation(1, false))
        repeat(5) { owner.recordInternetValidation(1, false) }
        assertEquals(prior.health, owner.current().health)
        assertEquals(prior.notificationEvidence, owner.current().notificationEvidence)
        assertEquals(prior.diagnostics, owner.current().diagnostics)
        assertTrue(reconnects.isEmpty())
        assertTrue(owner.recordInternetValidation(1, true))
        owner.installEngine(installed.copy(engineGeneration = 2, networkGeneration = installed.networkGeneration + 1))
        assertEquals(null, owner.current().internetValidated)
        assertFalse(owner.recordInternetValidation(1, true))
        assertTrue(owner.recordInternetValidation(2, false))
        owner.recordConnectionChanged(2, false)
        assertEquals(null, owner.current().internetValidated)
    }
    @Test fun `unsent primary remains neutral while a served lower tier receives its own success evidence`() {
        val owner = ExactSessionHealthOwner(11, { 1000L }, {}, {})
        val installed = identity(11, 1)
        owner.installEngine(installed, epoch(installed))
        val evidence = ResolverQueryHealthEvidence.primaryFailed(1, ResolverFailureReason.BIND_FAILED,
            ProtectionFallback.ENCRYPTED_DNS).copy(tierObservations = listOf(
                ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.NOT_ATTEMPTED,
                    failure = ResolverFailureReason.BIND_FAILED, packetSent = false),
                ResolverTierDiagnosticObservation(2, ResolverKind.MULLVAD_DOH, ResolverTierDiagnosticOutcome.SERVED,
                    packetSent = true)))
        owner.recordBatch(1, emptyList(), evidence)
        val current = owner.current()
        assertEquals(ProtectionFallback.ENCRYPTED_DNS, current.health.activeFallback)
        assertEquals(0, current.health.consecutiveResolverFailures)
        assertEquals(0, current.health.consecutivePrimaryRejections)
        assertEquals(null, current.notificationEvidence.lastPrimarySuccessAtMillis)
        assertEquals(null, current.notificationEvidence.lastTransportFailureAtMillis)
        assertEquals(1000L, current.notificationEvidence.lastFallbackSuccessAtMillis)
        assertEquals(1L, current.diagnostics.upstreamSuccesses)
    }

    @Test fun `returned primary errors preserve active rescue and cannot publish service recovery or success`() {
        var now = 1000L
        val owner = ExactSessionHealthOwner(11, { now }, {}, {})
        val installed = identity(11, 1)
        val epoch = epoch(installed)
        owner.installEngine(installed, epoch)
        repeat(3) { owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryFailed(it + 1L, ResolverFailureReason.TIMEOUT)) }
        assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch))
        val transitions = ResolverTransitionState()
        val fallbackEvidence = ResolverQueryHealthEvidence.primaryFailed(4, ResolverFailureReason.TIMEOUT,
            ProtectionFallback.ENCRYPTED_DNS).copy(tierObservations = listOf(
                ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.FAILED,
                    failure = ResolverFailureReason.TIMEOUT),
                ResolverTierDiagnosticObservation(2, ResolverKind.MULLVAD_DOH, ResolverTierDiagnosticOutcome.SERVED)))
        fun delivered(evidence: ResolverQueryHealthEvidence, fallback: Boolean): ResolverResult.Answer {
            val served = evidence.tierObservations.any { it.outcome == ResolverTierDiagnosticOutcome.SERVED }
            val query = DnsResolverSmokeProbe.query(0, 9)
            val response = if (served) query + byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1,
                0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 9) else query.copyOf()
            response[2] = 0x81.toByte(); response[3] = if (served) 0x80.toByte() else 0x82.toByte()
            if (served) response[7] = 1
            val result = ResolverResult.Answer(response,
                if (fallback) com.lavasec.app.foundation.dns.ResolverTransport.MULLVAD_DOH
                else com.lavasec.app.foundation.dns.ResolverTransport.DEVICE_UDP,
                resolverQueryEvidence = evidence)
            return transitions.commit(ResolverCandidate(result, ResolverKind.DEVICE,
                if (fallback) ResolverKind.MULLVAD_DOH else ResolverKind.DEVICE)) as ResolverResult.Answer
        }
        val fallback = delivered(fallbackEvidence, true)
        owner.recordBatch(1, fallback.healthEvents, fallback.resolverQueryEvidence)
        assertEquals(ProtectionFallback.ENCRYPTED_DNS, owner.current().health.activeFallback)
        val initialSuccesses = owner.current().diagnostics.upstreamSuccesses
        for (id in listOf(5L, 6L)) {
            now += 1L
            val error = ResolverQueryHealthEvidence.primaryAccepted(id, 10).copy(tierObservations = listOf(
                ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.ANSWERED,
                    responseClass = DnsFallbackResponseClass.DNS_ERROR, responseCode = 2)))
            val published = delivered(error, false)
            assertTrue(published.healthEvents.isEmpty())
            // A stale Boolean strict-answer claim cannot override the typed raw error.
            owner.recordBatch(1, published.healthEvents, error, primaryAnswerBearing = true)
            val state = owner.current()
            assertEquals(ProtectionFallback.ENCRYPTED_DNS, state.health.activeFallback)
            assertEquals((id - 4L).toInt(), state.health.consecutivePrimaryRejections)
            assertEquals(initialSuccesses, state.diagnostics.upstreamSuccesses)
            assertEquals(null, state.notificationEvidence.lastPrimarySuccessAtMillis)
            assertEquals(now, state.notificationEvidence.lastRejectedResponseAtMillis)
            assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch))
            assertFalse(owner.hasRecentOrganicPrimaryAnswer(epoch, now, 300_000L))
        }
        now += 1L
        val served = ResolverQueryHealthEvidence.primaryAccepted(7, 10).copy(tierObservations = listOf(
            ResolverTierDiagnosticObservation(1, ResolverKind.DEVICE, ResolverTierDiagnosticOutcome.SERVED)))
        val recovered = delivered(served, false)
        assertEquals(listOf(DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE)), recovered.healthEvents)
        owner.recordBatch(1, recovered.healthEvents, served, primaryAnswerBearing = true)
        assertEquals(ProtectionFallback.NONE, owner.current().health.activeFallback)
        assertEquals(0, owner.current().health.consecutivePrimaryRejections)
        assertEquals(now, owner.current().notificationEvidence.lastPrimarySuccessAtMillis)
        assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch))
        assertTrue(owner.hasRecentOrganicPrimaryAnswer(epoch, now, 300_000L))
    }

    @Test fun `diagnostic totals reject duplicate and retired evidence and survive a successful recovery`() {
        var now = 1000L
        val owner = ExactSessionHealthOwner(11, { now }, {}, {})
        val first = identity(11, 1)
        owner.installEngine(first, epoch(first))
        val failure = ResolverQueryHealthEvidence.primaryFailed(1, ResolverFailureReason.TIMEOUT)
        owner.recordResolverQuery(1, failure)
        owner.recordResolverQuery(1, failure)
        now += 100
        owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryAccepted(2, 23))
        assertEquals(1L, owner.current().diagnostics.upstreamFailures)
        assertEquals(1L, owner.current().diagnostics.upstreamSuccesses)
        assertEquals(listOf(23L), owner.current().diagnostics.responseLatenciesMillis)
        assertEquals(1000L, owner.current().diagnostics.lastFailureAtMillis)
        assertEquals(0, owner.current().health.consecutiveResolverFailures)
        val next = identity(11, 2).copy(networkGeneration = 2)
        owner.installEngine(next, epoch(next))
        assertFalse(owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryAccepted(3, 10)))
        assertEquals(1L, owner.current().diagnostics.upstreamSuccesses)
        assertEquals(1L, owner.current().diagnostics.networkChanges)
        assertEquals(1L, owner.current().diagnostics.runtimeResets)
        repeat(200) { owner.recordResolverQuery(2, ResolverQueryHealthEvidence.primaryAccepted(100L + it, it.toLong())) }
        assertEquals(128, owner.current().diagnostics.responseLatenciesMillis.size)
    }

    @Test
    fun `routine skip requires a new successful write fact for the exact current primary epoch`() {
        var now = 1_000L
        val owner = ExactSessionHealthOwner(11, { now }, {}, {})
        val first = identity(11, 1)
        val firstEpoch = epoch(first)
        owner.installEngine(first, firstEpoch)
        fun recent(at: Long = now) = owner.hasRecentOrganicPrimaryAnswer(firstEpoch, at, 300_000)
        assertFalse(recent())
        // Ordinary NXDOMAIN/NODATA service success remains accepted health, but has no strict fact.
        owner.recordBatch(1, emptyList(), ResolverQueryHealthEvidence.primaryAccepted(1, 1))
        assertFalse(recent())
        // Replaying an already admitted query with a later fact cannot upgrade it.
        owner.recordBatch(1, emptyList(), ResolverQueryHealthEvidence.primaryAccepted(1, 1), true)
        assertFalse(recent())
        owner.recordBatch(1, emptyList(), ResolverQueryHealthEvidence.primaryFailed(2, ResolverFailureReason.TIMEOUT), true)
        assertFalse(recent())
        owner.recordBatch(1, emptyList(), ResolverQueryHealthEvidence(3, PrimaryResolverOutcome.REJECTED), true)
        assertFalse(recent())
        owner.recordBatch(1, emptyList(), ResolverQueryHealthEvidence.primaryAccepted(4, 1), true)
        assertTrue(recent())
        assertFalse(recent(now - 1))
        assertTrue(recent(now + 300_000))
        assertFalse(recent(now + 300_001))
        assertFalse(owner.hasRecentOrganicPrimaryAnswer(firstEpoch.copy(networkGeneration = 2), now, 300_000))
        now += 1
        owner.recordBatch(1, emptyList(), ResolverQueryHealthEvidence.primaryRejectedWithFallback(5,
            com.lavasec.app.foundation.health.ProtectionFallback.ENCRYPTED_DNS), true)
        assertFalse(recent())
        // Missing fact on recovery cannot refresh the old stamp after its real age expires.
        now += 300_000
        owner.recordBatch(1, emptyList(), ResolverQueryHealthEvidence.primaryAccepted(6, 1))
        assertFalse(recent())
        val next = identity(11, 2)
        owner.installEngine(next, epoch(next))
        assertFalse(owner.recordBatch(1, emptyList(), ResolverQueryHealthEvidence.primaryAccepted(7, 1), true))
        assertFalse(owner.hasRecentOrganicPrimaryAnswer(epoch(next), now, 300_000))
        owner.recordBatch(2, emptyList(), ResolverQueryHealthEvidence.primaryAccepted(8, 1), true)
        assertTrue(owner.hasRecentOrganicPrimaryAnswer(epoch(next), now, 300_000))
        owner.recordConnectionChanged(2, false)
        owner.recordConnectionChanged(2, true)
        assertFalse(owner.hasRecentOrganicPrimaryAnswer(epoch(next), now, 300_000))
    }

    @Test
    fun `real terminal transport escalation qualifies only its exact installed Device epoch`() {
        val reconnects = mutableListOf<Long>()
        val owner = ExactSessionHealthOwner(11, { 1_000L }, {}, reconnects::add)
        val first = identity(11, 1)
        val epoch = epoch(first)
        owner.installEngine(first, devicePrimaryEpoch = epoch)
        repeat(3) { index ->
            assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch))
            owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryFailed(index + 1L, ResolverFailureReason.TIMEOUT))
        }
        assertEquals(listOf(1L), reconnects)
        assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch))
        for (stale in listOf(
            epoch.copy(engineGeneration = 2), epoch.copy(networkGeneration = 2),
            epoch.copy(sessionGeneration = 12), epoch.copy(configurationIdentity = ConfigurationIdentity(2, "changed")),
            epoch.copy(artifactDigest = "other-artifact"), epoch.copy(policyRevision = 2),
            epoch.copy(primaryResolverIdentity = PrimaryResolverIdentity("other-primary")),
        )) assertFalse(owner.deviceResolverReconnectEpisodeIsActive(stale))
    }

    @Test
    fun `network handoff resets smoke cadence while same primary retains its proven episode`() {
        val owner = ExactSessionHealthOwner(11, { 1000L }, {}, {})
        val first = identity(11, 1)
        val firstEpoch = epoch(first)
        owner.installEngine(first, firstEpoch)
        val query = DnsResolverSmokeProbe.query(0, 9)
        val refused = query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x85.toByte() }
        repeat(3) { owner.recordSmokeProbe(firstEpoch, it.toLong(), it + 1L, query,
            ResolverResult.Answer(refused, com.lavasec.app.foundation.dns.ResolverTransport.DEVICE_UDP)) }
        assertEquals(3, owner.current().smokeProbeFailureCount)
        assertTrue(owner.deviceResolverReconnectEpisodeIsActive(firstEpoch))
        val next = identity(11, 2).copy(networkGeneration = 2)
        val nextEpoch = epoch(next)
        owner.installEngine(next, nextEpoch)
        assertEquals(0, owner.current().smokeProbeFailureCount)
        assertTrue(owner.deviceResolverReconnectEpisodeIsActive(nextEpoch))
        assertFalse(owner.primaryProbeIsCurrent(firstEpoch))
        assertTrue(owner.primaryProbeIsCurrent(nextEpoch))
    }

    @Test
    fun `same Device primary reconnect retains episode until newly accepted primary recovery`() {
        val owner = ExactSessionHealthOwner(11, { 1_000L }, {}, {})
        val first = identity(11, 1)
        owner.installEngine(first, epoch(first))
        repeat(3) { owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryFailed(it + 1L, ResolverFailureReason.RECEIVE_FAILED)) }
        val second = identity(11, 2)
        owner.installEngine(second, epoch(second))
        assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch(first)))
        assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch(second)))
        assertEquals(0, owner.current().health.consecutiveResolverFailures)
        owner.recordResolverQuery(2, ResolverQueryHealthEvidence.primaryRejectedWithFallback(4, com.lavasec.app.foundation.health.ProtectionFallback.ENCRYPTED_DNS))
        assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch(second)))
        assertFalse(owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryAccepted(5, 0)))
        assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch(second)))
        owner.recordResolverQuery(2, ResolverQueryHealthEvidence.primaryAccepted(6, 0))
        assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch(second)))
    }

    @Test
    fun `replayed old primary success cannot erase a later genuine episode`() {
        val owner = ExactSessionHealthOwner(11, { 1_000L }, {}, {})
        val identity = identity(11, 1)
        val epoch = epoch(identity)
        owner.installEngine(identity, epoch)
        owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryAccepted(1, 0))
        repeat(3) { owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryFailed(it + 2L, ResolverFailureReason.TIMEOUT)) }
        assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch))
        owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryAccepted(1, 0))
        assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch))
        owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryAccepted(5, 0))
        assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch))
    }

    @Test
    fun `identity change role change and real disconnect retire the prior Device episode`() {
        for (boundary in listOf("identity", "role", "disconnect")) {
            val owner = ExactSessionHealthOwner(11, { 1_000L }, {}, {})
            val first = identity(11, 1)
            owner.installEngine(first, epoch(first))
            repeat(3) { owner.recordResolverQuery(1, ResolverQueryHealthEvidence.primaryFailed(it + 1L, ResolverFailureReason.TIMEOUT)) }
            assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch(first)))
            when (boundary) {
                "identity" -> {
                    val next = identity(11, 2, "device-dns:v4=08080808;v6=")
                    owner.installEngine(next, epoch(next))
                    assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch(next)))
                }
                "role" -> {
                    val next = identity(11, 2)
                    owner.installEngine(next) // Chained/plain/encrypted health carries no Device primary epoch.
                    assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch(next)))
                }
                else -> {
                    owner.recordConnectionChanged(1, false)
                    owner.recordConnectionChanged(1, true)
                }
            }
            assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch(first)))
        }
    }

    @Test
    fun `validated rejection lifecycle churn covered failure and artifact failure cannot manufacture Device wedge`() {
        val observations = listOf(
            ResolverQueryHealthEvidence(1, PrimaryResolverOutcome.REJECTED),
            ResolverQueryHealthEvidence.primaryFailed(1, ResolverFailureReason.CANCELLED),
            ResolverQueryHealthEvidence.primaryFailed(1, ResolverFailureReason.STALE_EPOCH),
            ResolverQueryHealthEvidence.primaryFailed(1, ResolverFailureReason.OVERLOADED),
            ResolverQueryHealthEvidence.primaryFailed(1, ResolverFailureReason.TIMEOUT,
                com.lavasec.app.foundation.health.ProtectionFallback.ENCRYPTED_DNS),
        )
        for (observation in observations) {
            val owner = ExactSessionHealthOwner(11, { 1_000L }, {}, {})
            val identity = identity(11, 1)
            val epoch = epoch(identity)
            owner.installEngine(identity, epoch)
            repeat(3) { owner.recordResolverQuery(1, observation.copy(queryId = it + 1L)) }
            owner.recordHealth(1, DnsHealthEvent.ProtectionUnavailable(ProtectionUnavailableReason.RuntimeUnavailable))
            assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch))
        }
        val owner = ExactSessionHealthOwner(11, { 1_000L }, {}, {})
        assertThrows(IllegalArgumentException::class.java) {
            owner.installEngine(identity(11, 1), epoch(identity(11, 2)))
        }
    }
    @Test
    fun `rejected response and transport timeout remain distinct evidence`() {
        val published = mutableListOf<VpnHealthStatus>()
        val owner = ExactSessionHealthOwner(
            sessionGeneration = 11L,
            nowMillis = { 1_000L },
            publish = published::add,
            requestReconnect = {},
        )
        owner.installEngine(identity(session = 11L, engine = 1L))

        assertTrue(
            owner.recordResolverQuery(
                engineGeneration = 1L,
                evidence = ResolverQueryHealthEvidence(
                    queryId = 1L,
                    primaryOutcome = PrimaryResolverOutcome.REJECTED,
                ),
            ),
        )
        assertTrue(
            owner.recordResolverQuery(
                engineGeneration = 1L,
                evidence = ResolverQueryHealthEvidence.primaryFailed(
                    queryId = 2L,
                    reason = ResolverFailureReason.TIMEOUT,
                ),
            ),
        )

        val current = published.last()
        assertEquals(1, current.health.consecutivePrimaryRejections)
        assertEquals(2, current.health.consecutiveResolverFailures)
        assertEquals(1_000L, current.notificationEvidence.lastRejectedResponseAtMillis)
        assertEquals(1_000L, current.notificationEvidence.lastTransportFailureAtMillis)
    }

    @Test
    fun `lifecycle cancellation is not transport failure while runtime unavailable is`() {
        var now = 1_500L
        val owner = ExactSessionHealthOwner(
            sessionGeneration = 15L,
            nowMillis = { now },
            publish = {},
            requestReconnect = {},
        )
        owner.installEngine(identity(session = 15L, engine = 1L))

        owner.recordResolverQuery(
            1L,
            ResolverQueryHealthEvidence.primaryFailed(
                queryId = 1L,
                reason = ResolverFailureReason.CANCELLED,
            ),
        )
        assertEquals(null, owner.current().notificationEvidence.lastTransportFailureAtMillis)

        now += 1L
        owner.recordHealth(
            1L,
            DnsHealthEvent.ProtectionUnavailable(ProtectionUnavailableReason.RuntimeUnavailable),
        )
        assertEquals(now, owner.current().notificationEvidence.lastTransportFailureAtMillis)
    }

    @Test
    fun `slow notification time advances only on a terminal slow response`() {
        var now = 2_000L
        val owner = ExactSessionHealthOwner(
            sessionGeneration = 16L,
            nowMillis = { now },
            publish = {},
            requestReconnect = {},
        )
        owner.installEngine(identity(session = 16L, engine = 1L))
        repeat(3) { index ->
            now += 1L
            owner.recordResolverQuery(
                1L,
                ResolverQueryHealthEvidence.primaryAccepted(index + 1L, 3_000L),
            )
        }
        val slowAt = now
        assertEquals(slowAt, owner.current().notificationEvidence.lastSlowResponseAtMillis)

        now += 1L
        owner.recordHealth(
            1L,
            DnsHealthEvent.ResolverAttemptFailed(
                ResolverKind.DEVICE,
                ResolverFailureReason.CANCELLED,
            ),
        )
        assertEquals(slowAt, owner.current().notificationEvidence.lastSlowResponseAtMillis)
    }

    @Test
    fun `engine replacement rejects stale evidence and fingerprints selected device DNS`() {
        var now = 2_000L
        val published = mutableListOf<VpnHealthStatus>()
        val owner = ExactSessionHealthOwner(
            sessionGeneration = 12L,
            nowMillis = { now },
            publish = published::add,
            requestReconnect = {},
        )
        owner.installEngine(identity(session = 12L, engine = 1L, primary = "device-dns:v4=01010101;v6="))
        owner.installEngine(identity(session = 12L, engine = 2L, primary = "device-dns:v4=08080808;v6="))
        val revisionAfterReplacement = published.last().revision

        now += 1L
        assertFalse(
            owner.recordResolverQuery(
                engineGeneration = 1L,
                evidence = ResolverQueryHealthEvidence.primaryFailed(
                    queryId = 3L,
                    reason = ResolverFailureReason.TIMEOUT,
                ),
            ),
        )
        assertEquals(revisionAfterReplacement, published.last().revision)
        assertEquals(
            PrimaryResolverIdentity("device-dns:v4=08080808;v6="),
            published.last().health.context?.primaryResolverIdentity,
        )
    }

    @Test
    fun `self reconnect is exact engine fenced and bounded by cooldown and attempt window`() {
        var now = 10_000_000L
        val reconnects = mutableListOf<Long>()
        val owner = ExactSessionHealthOwner(
            sessionGeneration = 13L,
            nowMillis = { now },
            publish = {},
            requestReconnect = reconnects::add,
        )
        owner.installEngine(identity(session = 13L, engine = 1L))

        repeat(3) { index ->
            owner.recordResolverQuery(
                1L,
                ResolverQueryHealthEvidence.primaryFailed(
                    queryId = index + 1L,
                    reason = ResolverFailureReason.TIMEOUT,
                ),
            )
        }
        assertEquals(listOf(1L), reconnects)

        owner.recordResolverQuery(
            1L,
            ResolverQueryHealthEvidence.primaryFailed(4L, ResolverFailureReason.TIMEOUT),
        )
        assertEquals(listOf(1L), reconnects)

        owner.installEngine(identity(session = 13L, engine = 2L))
        now += TunnelSelfReconnectPolicy.COOLDOWN_MILLIS + 1L
        repeat(3) { index ->
            owner.recordResolverQuery(
                2L,
                ResolverQueryHealthEvidence.primaryFailed(
                    queryId = index + 10L,
                    reason = ResolverFailureReason.TIMEOUT,
                ),
            )
        }
        assertEquals(listOf(1L, 2L), reconnects)

        owner.installEngine(identity(session = 13L, engine = 3L))
        now += TunnelSelfReconnectPolicy.COOLDOWN_MILLIS + 1L
        repeat(3) { index ->
            owner.recordResolverQuery(
                3L,
                ResolverQueryHealthEvidence.primaryFailed(
                    queryId = index + 20L,
                    reason = ResolverFailureReason.TIMEOUT,
                ),
            )
        }
        assertEquals(listOf(1L, 2L), reconnects)
        assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, owner.current().health.assessment.severity)
    }

    @Test
    fun `private telemetry drops advance only a domain free operational counter`() {
        val owner = ExactSessionHealthOwner(
            sessionGeneration = 14L,
            nowMillis = { 4_000L },
            publish = {},
            requestReconnect = {},
        )
        owner.installEngine(identity(session = 14L, engine = 1L))

        assertTrue(owner.recordPrivateTelemetryDrop(1L))
        assertFalse(owner.recordPrivateTelemetryDrop(2L))

        assertEquals(1L, owner.current().droppedPrivateTelemetryCount)
    }

    @Test
    fun `connection transitions track a session the DNS engine does not drive`() {
        // `installEngine` publishes the initial connected observation; a chained session then
        // reports recovering/surrendered and re-established transitions, so the accumulator's
        // `isConnected` does not stay at its reset default.
        val published = mutableListOf<VpnHealthStatus>()
        val owner = ExactSessionHealthOwner(
            sessionGeneration = 17L,
            nowMillis = { 5_000L },
            publish = published::add,
            requestReconnect = {},
        )
        owner.installEngine(identity(session = 17L, engine = 1L))
        assertTrue(published.last().health.isConnected)

        assertTrue(owner.recordConnectionChanged(1L, connected = false))
        assertFalse(published.last().health.isConnected)

        assertTrue(owner.recordConnectionChanged(1L, connected = true))
        assertTrue(published.last().health.isConnected)

        // A stale generation cannot repaint the current session's connection state.
        assertFalse(owner.recordConnectionChanged(2L, connected = false))
        assertTrue(published.last().health.isConnected)
    }

    @Test
    fun `failed Plain DNS probe retains the installed resolver kind`() {
        val owner = ExactSessionHealthOwner(11, { 1000L }, {}, {})
        val installed = identity(11, 1, "plain-dns:configured").copy(
            primaryResolverKind = com.lavasec.app.foundation.dns.ResolverKind.PLAIN_DNS)
        val epoch = epoch(installed)
        owner.installEngine(installed, primaryEpoch = epoch)
        assertTrue(owner.recordSmokeProbe(epoch, 1, 1, DnsResolverSmokeProbe.query(0, 9),
            ResolverResult.Failure(ResolverFailureReason.TIMEOUT)))
        val tier = owner.current().diagnostics.tiers[1]!!.latest!!
        assertEquals(com.lavasec.app.foundation.dns.ResolverKind.PLAIN_DNS, tier.resolver)
        assertEquals(ResolverFailureReason.TIMEOUT, tier.failure)
    }

    @Test
    fun `late smoke reply clears a warm Device outage without erasing rejection or cadence evidence`() {
        var now = 1_000L
        val reconnects = mutableListOf<Long>()
        val owner = ExactSessionHealthOwner(11, { now }, {}, reconnects::add)
        val installed = identity(11, 1).copy(primaryResolverKind = ResolverKind.DEVICE)
        val epoch = epoch(installed)
        owner.installEngine(installed, epoch)
        val query = DnsResolverSmokeProbe.query(0, 9)
        seedSmokeOutage(owner, epoch, query)
        val before = owner.current()
        assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch))
        assertEquals(listOf(1L), reconnects)
        assertEquals(2L, before.diagnostics.tiers[1]!!.failureStreak)
        assertEquals(1L, before.diagnostics.tiers[1]!!.rejectedStreak)

        now += TunnelSelfReconnectPolicy.COOLDOWN_MILLIS + 1L
        assertTrue(owner.recordSmokeProbe(epoch, 4, 4, query,
            ResolverResult.Failure(ResolverFailureReason.TIMEOUT, terminal = true, peerAnswered = true),
            latencyMillis = 987L))
        val after = owner.current()
        assertEquals(0, after.health.consecutiveResolverFailures)
        assertEquals(1, after.health.consecutivePrimaryRejections)
        assertEquals(3, after.smokeProbeFailureCount)
        assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch))
        assertEquals(listOf(1L), reconnects)
        assertEquals(before.notificationEvidence, after.notificationEvidence)
        assertTrue(after.health.recentResponseLatenciesMillis.isEmpty())
        assertFalse(owner.hasRecentOrganicPrimaryAnswer(epoch, now, 300_000L))
        assertEquals(4L, after.diagnostics.smokeProbes)
        assertEquals(0L, after.diagnostics.smokeSuccesses)
        assertEquals(0L, after.diagnostics.upstreamSuccesses)
        assertEquals(0L, after.diagnostics.upstreamFailures)
        assertEquals(null, after.diagnostics.lastSuccessAtMillis)
        assertEquals(null, after.diagnostics.lastFailureAtMillis)
        assertTrue(after.diagnostics.responseLatenciesMillis.isEmpty())
        val tier = after.diagnostics.tiers[1]!!
        assertEquals(ResolverTierDiagnosticOutcome.ANSWERED, tier.latest!!.outcome)
        assertEquals(ResolverFailureReason.TIMEOUT, tier.latest!!.failure)
        assertTrue(tier.latest!!.peerLivenessOnly)
        assertEquals(true, tier.latest!!.packetSent)
        assertEquals(0L, tier.failureStreak)
        assertEquals(1L, tier.rejectedStreak)
        assertEquals(2L, tier.failures)
        assertEquals(2L, tier.answered)
        assertEquals(0L, tier.served)
        assertEquals(before.diagnostics.tiers[1]!!.lastFailure, tier.lastFailure)
    }

    @Test
    fun `local smoke refusal preserves a warm Device episode after reconnect cooldown`() {
        for (reason in listOf(ResolverFailureReason.PROTECT_FAILED, ResolverFailureReason.BIND_FAILED,
            ResolverFailureReason.SOCKET_FAILED)) {
            var now = 1_000L
            val reconnects = mutableListOf<Long>()
            val owner = ExactSessionHealthOwner(11, { now }, {}, reconnects::add)
            val installed = identity(11, 1).copy(primaryResolverKind = ResolverKind.DEVICE)
            val epoch = epoch(installed)
            owner.installEngine(installed, epoch)
            val query = DnsResolverSmokeProbe.query(0, 9)
            seedSmokeOutage(owner, epoch, query)
            val before = owner.current()
            assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch))
            assertEquals(listOf(1L), reconnects)

            now += TunnelSelfReconnectPolicy.COOLDOWN_MILLIS + 1L
            assertTrue(owner.recordSmokeProbe(epoch, 4, 4, query,
                ResolverResult.Failure(reason, local = true), latencyMillis = 987L))
            val after = owner.current()
            assertEquals(3, after.health.consecutiveResolverFailures)
            assertEquals(1, after.health.consecutivePrimaryRejections)
            assertEquals(3, after.smokeProbeFailureCount)
            assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, after.health.assessment.severity)
            assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch))
            assertEquals(listOf(1L), reconnects)
            assertEquals(before.notificationEvidence, after.notificationEvidence)
            assertTrue(after.health.recentResponseLatenciesMillis.isEmpty())
            assertFalse(owner.hasRecentOrganicPrimaryAnswer(epoch, now, 300_000L))
            assertEquals(4L, after.diagnostics.smokeProbes)
            assertEquals(0L, after.diagnostics.smokeSuccesses)
            assertEquals(0L, after.diagnostics.upstreamSuccesses)
            assertEquals(0L, after.diagnostics.upstreamFailures)
            assertTrue(after.diagnostics.responseLatenciesMillis.isEmpty())
            val priorTier = before.diagnostics.tiers[1]!!
            val tier = after.diagnostics.tiers[1]!!
            assertEquals(priorTier.latest, tier.latest)
            assertEquals(priorTier.observedAtMillis, tier.observedAtMillis)
            assertEquals(priorTier.failureStreak, tier.failureStreak)
            assertEquals(priorTier.rejectedStreak, tier.rejectedStreak)
            assertEquals(priorTier.lastFailure, tier.lastFailure)
            assertEquals(priorTier.attempts, tier.attempts)
            assertEquals(1L, tier.notAttempted)
            assertEquals(ResolverTierDiagnosticOutcome.NOT_ATTEMPTED, tier.latestAdmission!!.outcome)
            assertEquals(reason, tier.latestAdmission!!.failure)
            assertEquals(false, tier.latestAdmission!!.packetSent)
            assertEquals(now, tier.admissionObservedAtMillis)
        }
    }

    @Test
    fun `neutral smoke metadata retains its raw tier but admits only the owned completion identity`() {
        for (answerBody in listOf(false, true)) {
            var now = 1_000L
            val reconnects = mutableListOf<Long>()
            val owner = ExactSessionHealthOwner(11, { now }, {}, reconnects::add)
            val installed = identity(11, 1, "plain-dns:configured").copy(
                primaryResolverKind = ResolverKind.PLAIN_DNS, fallbackCoverage = ResolverFallbackCoverage.NONE)
            val epoch = epoch(installed)
            owner.installEngine(installed, primaryEpoch = epoch)
            val query = DnsResolverSmokeProbe.query(0, 9)
            seedSmokeOutage(owner, epoch, query, ResolverTransport.PLAIN_TCP)
            val before = owner.current()
            val raw = ResolverTierDiagnosticObservation(2, ResolverKind.PLAIN_DNS,
                ResolverTierDiagnosticOutcome.ANSWERED, ResolverTransport.PLAIN_TCP,
                failure = ResolverFailureReason.TIMEOUT, peerLivenessOnly = true,
                responseClass = DnsFallbackResponseClass.NEGATIVE, responseCode = 3,
                queryType = 1, terminalAddressPresent = false, packetSent = true)
            val rawEvidence = ResolverQueryHealthEvidence.primaryFailed(9_999L, ResolverFailureReason.TIMEOUT)
                .copy(tierObservations = listOf(raw))
            val result = if (answerBody) ResolverResult.Answer(
                DnsMessage.bootstrapResponse(query, byteArrayOf(192.toByte(), 0, 2, 9), ByteArray(16)),
                ResolverTransport.PLAIN_TCP, resolverQueryEvidence = rawEvidence)
            else ResolverResult.Failure(ResolverFailureReason.TIMEOUT, resolver = ResolverKind.PLAIN_DNS,
                resolverQueryEvidence = rawEvidence, terminal = true, peerAnswered = true)

            now += TunnelSelfReconnectPolicy.COOLDOWN_MILLIS + 1L
            assertTrue(owner.recordSmokeProbe(epoch, 4, 4, query, result, latencyMillis = 987L))
            val after = owner.current()
            assertEquals(raw, after.diagnostics.tiers[2]!!.latest)
            assertEquals(0, after.health.consecutiveResolverFailures)
            assertEquals(1, after.health.consecutivePrimaryRejections)
            assertEquals(3, after.smokeProbeFailureCount)
            assertEquals(before.notificationEvidence, after.notificationEvidence)
            assertEquals(0L, after.diagnostics.smokeSuccesses)
            assertEquals(0L, after.diagnostics.upstreamSuccesses)
            assertTrue(after.health.recentResponseLatenciesMillis.isEmpty())
            assertTrue(after.diagnostics.responseLatenciesMillis.isEmpty())
            assertFalse(owner.hasRecentOrganicPrimaryAnswer(epoch, now, 300_000L))
            assertTrue(4L in after.health.recentResolverQueryIds)
            assertFalse(9_999L in after.health.recentResolverQueryIds)
            assertEquals(4L, after.health.resolverQueryIdHighWatermark)
            assertEquals(listOf(1L), reconnects)

            assertFalse(owner.recordSmokeProbe(epoch, 4, 5, query,
                ResolverResult.Failure(ResolverFailureReason.TIMEOUT)))
            assertEquals(after, owner.current())
            assertFalse(owner.recordSmokeProbe(epoch.copy(networkGeneration = 2), 5, 5, query,
                ResolverResult.Failure(ResolverFailureReason.TIMEOUT)))
            assertEquals(after, owner.current())
            assertEquals(listOf(1L), reconnects)
        }
    }

    @Test
    fun `genuine timeout and terminal mismatch still qualify the smoke reconnect threshold`() {
        for (reason in listOf(ResolverFailureReason.TIMEOUT, ResolverFailureReason.MISMATCH_LIMIT)) {
            var now = 1_000L
            val reconnects = mutableListOf<Long>()
            val owner = ExactSessionHealthOwner(11, { now }, {}, reconnects::add)
            val installed = identity(11, 1).copy(primaryResolverKind = ResolverKind.DEVICE)
            val epoch = epoch(installed)
            owner.installEngine(installed, epoch)
            val query = DnsResolverSmokeProbe.query(0, 9)
            repeat(2) { index ->
                assertTrue(owner.recordSmokeProbe(epoch, index + 1L, index + 1L, query,
                    ResolverResult.Failure(reason, terminal = reason == ResolverFailureReason.MISMATCH_LIMIT)))
            }
            assertEquals(2, owner.current().smokeProbeFailureCount)
            assertEquals(2, owner.current().health.consecutiveResolverFailures)
            assertFalse(owner.deviceResolverReconnectEpisodeIsActive(epoch))
            assertTrue(reconnects.isEmpty())
            now += 1L
            assertTrue(owner.recordSmokeProbe(epoch, 3, 3, query,
                ResolverResult.Failure(reason, terminal = reason == ResolverFailureReason.MISMATCH_LIMIT)))
            val after = owner.current()
            assertEquals(3, after.smokeProbeFailureCount)
            assertEquals(3, after.health.consecutiveResolverFailures)
            assertEquals(ProtectionHealthSeverity.NEEDS_RECONNECT, after.health.assessment.severity)
            assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch))
            assertEquals(listOf(1L), reconnects)
            assertEquals(now, after.notificationEvidence.lastTransportFailureAtMillis)
            assertEquals(ResolverTierDiagnosticOutcome.FAILED, after.diagnostics.tiers[1]!!.latest!!.outcome)
            assertFalse(after.diagnostics.tiers[1]!!.latest!!.peerLivenessOnly)
            assertEquals(3L, after.diagnostics.tiers[1]!!.failureStreak)
            assertEquals(0L, after.diagnostics.smokeSuccesses)

            now += TunnelSelfReconnectPolicy.COOLDOWN_MILLIS + 1L
            assertFalse(owner.recordSmokeProbe(epoch, 3, 4, query,
                ResolverResult.Failure(ResolverFailureReason.TIMEOUT, terminal = true, peerAnswered = true)))
            assertEquals(after, owner.current())
            assertTrue(owner.deviceResolverReconnectEpisodeIsActive(epoch))
            assertEquals(listOf(1L), reconnects)
        }
    }

    private fun seedSmokeOutage(
        owner: ExactSessionHealthOwner,
        epoch: EngineEpoch,
        query: ByteArray,
        transport: ResolverTransport = ResolverTransport.DEVICE_UDP,
    ) {
        val refused = query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x85.toByte() }
        assertTrue(owner.recordSmokeProbe(epoch, 1, 1, query, ResolverResult.Answer(refused, transport)))
        repeat(2) { index ->
            assertTrue(owner.recordSmokeProbe(epoch, index + 2L, index + 2L, query,
                ResolverResult.Failure(ResolverFailureReason.TIMEOUT)))
        }
        assertEquals(3, owner.current().smokeProbeFailureCount)
        assertEquals(3, owner.current().health.consecutiveResolverFailures)
        assertEquals(1, owner.current().health.consecutivePrimaryRejections)
    }

    private fun identity(
        session: Long,
        engine: Long,
        primary: String = "device-dns:v4=01010101;v6=",
    ) = VpnHealthEngineIdentity(
        sessionGeneration = session,
        networkGeneration = 1L,
        engineGeneration = engine,
        primaryResolverIdentity = PrimaryResolverIdentity(primary),
        fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
    )

    private fun epoch(identity: VpnHealthEngineIdentity) = EngineEpoch(
        identity.sessionGeneration, ConfigurationIdentity(1, "cfg"), "artifact", 1,
        identity.networkGeneration, identity.engineGeneration, identity.primaryResolverIdentity, identity.fallbackCoverage,
    )
}
