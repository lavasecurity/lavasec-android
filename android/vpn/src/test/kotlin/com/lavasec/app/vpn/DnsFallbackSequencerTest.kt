package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.*
import com.lavasec.app.foundation.health.*
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DnsFallbackSequencerTest {
    @Test fun `single label alias positives survive selection and client delivery without granting portal admission`() = runBlocking {
        for (type in listOf(1, 28)) {
            val primary = Fixture("files.example.com", rawType = type)
            val primaryBytes = aliasAnswer(primary.query, "intranet", type)
            val selected = primary.resolve(primaryBytes, primary.positive()) as ResolverResult.Answer
            assertEquals(ResolverTransport.MULLVAD_DOH, selected.transport)
            assertEquals(0, primary.deviceCalls)
            assertArrayEquals(primaryBytes, selected.dnsMessage)
            assertArrayEquals(primaryBytes, ResolverReplyBoundary.boundForClient(primary.query, selected.dnsMessage))
            assertEquals(ResolverTierDiagnosticOutcome.SERVED, selected.resolverQueryEvidence!!.tierObservations.single().outcome)

            val fallback = Fixture("files.example.com", rawType = type)
            val fallbackBytes = aliasAnswer(fallback.query, "intranet", type)
            val rescued = fallback.resolve(error(fallback.query, listOf(23)), fallbackBytes) as ResolverResult.Answer
            assertEquals(ResolverTransport.DEVICE_UDP, rescued.transport)
            assertEquals(1, fallback.deviceCalls)
            assertFalse(fallback.lastDeviceRequest!!.fallbackContext.requiresPortalAdmission)
            assertEquals(ProtectionFallback.DEVICE_DNS, rescued.resolverQueryEvidence!!.successfulFallback)
            assertArrayEquals(fallbackBytes, rescued.dnsMessage)
            assertArrayEquals(fallbackBytes, ResolverReplyBoundary.boundForClient(fallback.query, rescued.dnsMessage))

            val portal = Fixture(rawType = type)
            val original = negative(portal.query)
            val rejected = portal.resolve(original, aliasAnswer(portal.query, "intranet", type)) as ResolverResult.Answer
            assertEquals(1, portal.deviceCalls)
            assertArrayEquals(original, rejected.dnsMessage)
            assertEquals(ProtectionFallback.NONE, rejected.resolverQueryEvidence!!.successfulFallback)
            assertEquals(DnsFallbackDisposition.PRESERVED_NEGATIVE, rejected.resolverQueryEvidence!!.fallbackObservations.single().disposition)
            assertEquals(ResolverTierDiagnosticOutcome.ANSWERED, rejected.resolverQueryEvidence!!.tierObservations.last().outcome)
        }
    }

    @Test fun `only exact eligible portal negative reaches enabled Device and earns credit after family answer`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.resolve(negative(fixture.query), fixture.positive()) as ResolverResult.Answer
        assertEquals(ResolverTransport.DEVICE_UDP, result.transport)
        assertEquals(1, fixture.deviceCalls)
        assertTrue(fixture.lastDeviceRequest!!.fallbackContext.requiresPortalAdmission)
        assertEquals(ProtectionFallback.DEVICE_DNS, result.resolverQueryEvidence!!.successfulFallback)
        assertEquals(DnsFallbackReason.PORTAL_ADDRESS_NEGATIVE, result.resolverQueryEvidence!!.fallbackObservations.single().reason)
        assertEquals(DnsFallbackResponseClass.NEGATIVE, result.resolverQueryEvidence!!.tierObservations.first().responseClass)
        for (domain in listOf("private.example", "sub.deltawifi.com")) {
            val ordinary = Fixture(domain)
            ordinary.resolve(negative(ordinary.query), ordinary.positive())
            assertEquals(0, ordinary.deviceCalls)
        }
    }
    @Test fun `only selected portal reply earns service while replaced primary keeps raw negative facts`() = runBlocking {
        for (type in listOf(1, 28)) for (code in listOf(0, 3)) for (rescued in listOf(true, false)) {
            val fixture = Fixture(rawType = type)
            val original = DnsMessage.servfailResponse(fixture.query)!!.also { it[3] = (0x80 or code).toByte() }
            val secondary = if (rescued) fixture.positive() else negative(fixture.query)
            val result = fixture.resolve(original, secondary) as ResolverResult.Answer
            assertArrayEquals(if (rescued) secondary else original, result.dnsMessage)
            assertArrayEquals(result.dnsMessage, ResolverReplyBoundary.boundForClient(fixture.query, result.dnsMessage))
            assertEquals(if (rescued) ResolverTransport.DEVICE_UDP else ResolverTransport.MULLVAD_DOH, result.transport)
            val evidence = checkNotNull(result.resolverQueryEvidence)
            assertEquals(if (rescued) ProtectionFallback.DEVICE_DNS else ProtectionFallback.NONE, evidence.successfulFallback)
            assertEquals(if (rescued) DnsFallbackDisposition.SERVED else DnsFallbackDisposition.PRESERVED_NEGATIVE,
                evidence.fallbackObservations.single().disposition)
            assertEquals(1, evidence.tierObservations.count { it.outcome == ResolverTierDiagnosticOutcome.SERVED })
            assertEquals(ResolverTierDiagnosticObservation(1, ResolverKind.MULLVAD_DOH,
                if (rescued) ResolverTierDiagnosticOutcome.ANSWERED else ResolverTierDiagnosticOutcome.SERVED,
                ResolverTransport.MULLVAD_DOH, responseClass = DnsFallbackResponseClass.NEGATIVE, responseCode = code,
                queryType = type, terminalAddressPresent = false, packetSent = true), evidence.tierObservations.first())
            assertEquals(if (rescued) ResolverTierDiagnosticOutcome.SERVED else ResolverTierDiagnosticOutcome.ANSWERED,
                evidence.tierObservations.last().outcome)
            val diagnostics = VpnSessionDiagnostics(tiers = mapOf(
                1 to VpnTierDiagnostics(served = 7, answered = 2, failures = 2, failureStreak = 4, rejectedStreak = 3),
                2 to VpnTierDiagnostics(served = 11, answered = 4, rejectedStreak = 5))).query(evidence, 1_000)
            assertEquals(if (rescued) 7L else 8L, diagnostics.tiers.getValue(1).served)
            assertEquals(if (rescued) 3L else 2L, diagnostics.tiers.getValue(1).answered)
            assertEquals(if (rescued) 4L else 0L, diagnostics.tiers.getValue(1).rejectedStreak)
            assertEquals(0L, diagnostics.tiers.getValue(1).failureStreak)
            assertEquals(2L, diagnostics.tiers.getValue(1).failures)
            assertEquals(if (rescued) 12L else 11L, diagnostics.tiers.getValue(2).served)
            assertEquals(if (rescued) 0L else 6L, diagnostics.tiers.getValue(2).rejectedStreak)
            assertEquals(if (rescued) ResolverKind.DEVICE else ResolverKind.MULLVAD_DOH, diagnostics.lastResolver)
            assertEquals(result.transport, diagnostics.lastTransport)
            assertEquals(if (rescued) 1L else 0L, diagnostics.deviceFallbacks)
            assertEquals(1L, diagnostics.upstreamSuccesses)
            assertEquals(0L, diagnostics.upstreamFailures)
        }
    }
    @Test fun `failed negative malformed or answerless rescue preserves original valid negative byte for byte`() = runBlocking {
        for (mode in 0..3) {
            val fixture = Fixture()
            val original = negative(fixture.query)
            val rescue: ResolverResult = when (mode) {
                0 -> ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
                1 -> ResolverResult.Answer(negative(fixture.query), ResolverTransport.DEVICE_UDP)
                2 -> ResolverResult.Answer(fixture.positive().also { it[3] = 0x83.toByte() }, ResolverTransport.DEVICE_UDP)
                else -> ResolverResult.Answer(fixture.query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }, ResolverTransport.DEVICE_UDP)
            }
            val result = fixture.resolveResult(ResolverResult.Answer(original, ResolverTransport.MULLVAD_DOH), rescue) as ResolverResult.Answer
            assertArrayEquals(original, result.dnsMessage)
            assertEquals(ProtectionFallback.NONE, result.resolverQueryEvidence!!.successfulFallback)
            assertEquals(DnsFallbackDisposition.PRESERVED_NEGATIVE, result.resolverQueryEvidence!!.fallbackObservations.single().disposition)
        }
    }
    @Test fun `discarded portal negative replies retain primary service and Device rejection evidence`() = runBlocking {
        for (queryType in listOf(1, 28)) for (responseCode in listOf(0, 3)) {
            val fixture = Fixture(rawType = queryType)
            val original = negative(fixture.query)
            val discarded = DnsMessage.servfailResponse(fixture.query)!!.also { it[3] = (0x80 or responseCode).toByte() }
            val result = fixture.resolveResult(ResolverResult.Answer(original, ResolverTransport.MULLVAD_DOH),
                ResolverResult.Answer(discarded, ResolverTransport.DEVICE_UDP)) as ResolverResult.Answer

            assertArrayEquals(original, result.dnsMessage)
            assertEquals(ResolverTransport.MULLVAD_DOH, result.transport)
            val evidence = checkNotNull(result.resolverQueryEvidence)
            assertEquals(ProtectionFallback.NONE, evidence.successfulFallback)
            assertEquals(DnsFallbackDisposition.PRESERVED_NEGATIVE, evidence.fallbackObservations.single().disposition)
            assertEquals(listOf(ResolverTierDiagnosticOutcome.SERVED, ResolverTierDiagnosticOutcome.ANSWERED),
                evidence.tierObservations.map { it.outcome })
            val device = evidence.tierObservations.last()
            assertEquals(ResolverKind.DEVICE, device.resolver)
            assertEquals(ResolverTransport.DEVICE_UDP, device.transport)
            assertEquals(DnsFallbackResponseClass.NEGATIVE, device.responseClass)
            assertEquals(responseCode, device.responseCode)
            assertEquals(queryType, device.queryType)
            assertEquals(false, device.terminalAddressPresent)

            val previous = VpnSessionDiagnostics(tiers = mapOf(2 to VpnTierDiagnostics(
                served = 7, answered = 2, rejectedStreak = 3)))
            val diagnostics = previous.query(evidence, 1_000)
            assertEquals(ResolverKind.MULLVAD_DOH, diagnostics.lastResolver)
            assertEquals(ResolverTransport.MULLVAD_DOH, diagnostics.lastTransport)
            assertEquals(0L, diagnostics.deviceFallbacks)
            assertEquals(1L, diagnostics.tiers[1]!!.served)
            assertEquals(7L, diagnostics.tiers[2]!!.served)
            assertEquals(3L, diagnostics.tiers[2]!!.answered)
            assertEquals(4L, diagnostics.tiers[2]!!.rejectedStreak)
        }
    }

    @Test fun `operational error can advance but policy evidence and bare encrypted error remain terminal`() = runBlocking {
        for (errors in listOf(listOf(23, 15), listOf(23, 35), emptyList())) {
            val fixture = Fixture()
            val result = fixture.resolve(error(fixture.query, errors), fixture.positive()) as ResolverResult.Answer
            assertEquals(ResolverTransport.MULLVAD_DOH, result.transport)
            assertEquals(0, fixture.deviceCalls)
        }
        val fixture = Fixture()
        val result = fixture.resolve(error(fixture.query, listOf(23)), fixture.positive()) as ResolverResult.Answer
        assertEquals(1, fixture.deviceCalls)
        assertEquals(ProtectionFallback.DEVICE_DNS, result.resolverQueryEvidence!!.successfulFallback)
    }
    @Test fun `lower operational or policy error cannot replace original error or claim fallback service`() = runBlocking {
        for (errors in listOf(emptyList(), listOf(23), listOf(15))) {
            val fixture = Fixture()
            val original = error(fixture.query, listOf(23))
            val result = fixture.resolve(original, error(fixture.query, errors)) as ResolverResult.Answer
            assertArrayEquals(original, result.dnsMessage)
            assertEquals(ProtectionFallback.NONE, result.resolverQueryEvidence!!.successfulFallback)
            assertEquals(DnsFallbackDisposition.ATTEMPTED, result.resolverQueryEvidence!!.fallbackObservations.single().disposition)
            assertTrue(result.resolverQueryEvidence!!.tierObservations.all { it.outcome == ResolverTierDiagnosticOutcome.ANSWERED })
        }
    }
    @Test fun `validated late primary ends ladder with liveness and no fallback or peer failure`() = runBlocking {
        val fixture = Fixture()
        fixture.afterPrimary = { fixture.now = ResolverExecutionBudget.TIER_NANOS }
        val result = fixture.resolve(fixture.positive(), fixture.positive()) as ResolverResult.Failure
        assertTrue(result.terminal)
        assertTrue(result.peerAnswered)
        assertEquals(0, fixture.deviceCalls)
        assertTrue(result.healthEvents.isEmpty())
        val observation = result.resolverQueryEvidence!!.tierObservations.single()
        assertTrue(observation.peerLivenessOnly)
        assertEquals(ResolverTierDiagnosticOutcome.ANSWERED, observation.outcome)
    }
    @Test fun `queued expiry and stale live network permission cannot send portal rescue`() = runBlocking {
        val fixture = Fixture()
        fixture.now = ResolverExecutionBudget.WHOLE_QUERY_NANOS
        val result = fixture.resolveResult(ResolverResult.Answer(fixture.positive(), ResolverTransport.MULLVAD_DOH),
            ResolverResult.Answer(fixture.positive(), ResolverTransport.DEVICE_UDP), deadline = fixture.now) as ResolverResult.Failure
        assertEquals(0, fixture.primaryCalls)
        assertTrue(result.local)
        assertTrue(result.terminal)
        val stale = Fixture()
        val original = negative(stale.query)
        val response = stale.resolveResult(ResolverResult.Answer(original, ResolverTransport.MULLVAD_DOH),
            ResolverResult.Answer(stale.positive(), ResolverTransport.DEVICE_UDP), context = stale.context.copy(portalNetworkIsCurrent = { false })) as ResolverResult.Answer
        assertArrayEquals(original, response.dnsMessage)
        assertEquals(0, stale.deviceCalls)
    }

    @Test fun `unsent local failures may use configured fallback without peer failure or portal permission`() = runBlocking {
        for (failure in listOf(ResolverResult.Failure(ResolverFailureReason.PROTECT_FAILED),
            ResolverResult.Failure(ResolverFailureReason.BIND_FAILED), ResolverResult.Failure(ResolverFailureReason.SOCKET_FAILED, local = true))) {
            val fixture = Fixture()
            val result = fixture.resolveResult(failure, ResolverResult.Answer(fixture.positive(), ResolverTransport.DEVICE_UDP)) as ResolverResult.Answer
            assertEquals(ProtectionFallback.DEVICE_DNS, result.resolverQueryEvidence!!.successfulFallback)
            assertTrue(result.healthEvents.isEmpty())
            val primary = result.resolverQueryEvidence!!.tierObservations.first()
            assertEquals(ResolverTierDiagnosticOutcome.NOT_ATTEMPTED, primary.outcome)
            assertEquals(false, primary.packetSent)
            assertFalse(fixture.lastDeviceRequest!!.fallbackContext.requiresPortalAdmission)
        }
        val peer = Fixture()
        val result = peer.resolveResult(ResolverResult.Failure(ResolverFailureReason.SOCKET_FAILED),
            ResolverResult.Answer(peer.positive(), ResolverTransport.DEVICE_UDP)) as ResolverResult.Answer
        assertEquals(ResolverTierDiagnosticOutcome.FAILED, result.resolverQueryEvidence!!.tierObservations.first().outcome)
        assertEquals(1, result.healthEvents.size)
    }

    private class Fixture(domain: String = "deltawifi.com", rawType: Int = 1) {
        val network = testNetworkContext()
        val device = testResolverPlan(network)
        val encrypted = testResolverPlan(network, mullvadEnabled = true, primaryResolverKind = ResolverKind.MULLVAD_DOH)
        val plan = ResolverPlan(network.network, network.networkGeneration, false, device.deviceDnsServers,
            true, ResolverKind.MULLVAD_DOH, primaryResolverIdentity = encrypted.primaryResolverIdentity,
            fallbackCoverage = ResolverFallbackCoverage.DEVICE_DNS, orderedRoutes = listOf(encrypted, device))
        val query = testDnsQuery(domain, rawType = rawType)
        val context = DnsFallbackQueryContext(true, true, true, true, DnsPortalTierFallback.registeredHosts)
        var now = 0L
        var primaryCalls = 0
        var deviceCalls = 0
        var lastDeviceRequest: ResolverRequest? = null
        var afterPrimary: () -> Unit = {}
        fun positive() = DnsMessage.bootstrapResponse(query, byteArrayOf(1, 2, 3, 4), ByteArray(16))
        suspend fun resolve(primary: ByteArray, rescue: ByteArray) = resolveResult(
            ResolverResult.Answer(primary, ResolverTransport.MULLVAD_DOH), ResolverResult.Answer(rescue, ResolverTransport.DEVICE_UDP))
        suspend fun resolveResult(primary: ResolverResult, rescue: ResolverResult, deadline: Long? = null, context: DnsFallbackQueryContext = this.context): ResolverResult {
            val clock = MonotonicClock { now }
            val route = ResolverRouteSequencer(plan, DeviceDnsRoute { request, _ ->
                deviceCalls++; lastDeviceRequest = request
                DeviceDnsRouteResult(rescue, if (rescue is ResolverResult.Answer) ResolverEndpointKey.DeviceAddress(network.dnsServers.first()) else null)
            }, object : OrderedEncryptedDnsResolver {
                override fun resolverFor(identity: PrimaryResolverIdentity) = DnsResolver { primaryCalls++; afterPrimary(); primary }
                override suspend fun resolve(request: ResolverRequest): ResolverResult = error("exact route required")
            }, ResolverEndpointBackoffPolicy(clock), DeviceResolverWedgeEvidence { false }, clock)
            return route.resolve(ResolverRequest(query, DnsMessage.parseQuestion(query), testEpoch(resolverPlan = plan), network,
                wholeQueryDeadlineNanos = deadline, fallbackContext = context)).result
        }
    }
    private fun negative(query: ByteArray) = DnsMessage.servfailResponse(query)!!.also { it[3] = 0x83.toByte() }
    private fun aliasAnswer(query: ByteArray, target: String, type: Int): ByteArray {
        val name = target.split('.').fold(byteArrayOf()) { bytes, label ->
            bytes + byteArrayOf(label.length.toByte()) + label.toByteArray()
        } + byteArrayOf(0)
        fun record(owner: ByteArray, recordType: Int, data: ByteArray) = owner +
            byteArrayOf(0, recordType.toByte(), 0, 1, 0, 0, 0, 60, 0, data.size.toByte()) + data
        return DnsMessage.servfailResponse(query)!!.also { it[3] = 0x80.toByte(); it[7] = 2 } +
            record(byteArrayOf(0xc0.toByte(), 12), 5, name) +
            record(name, type, ByteArray(if (type == 1) 4 else 16) { 1 })
    }
    private fun error(query: ByteArray, codes: List<Int>): ByteArray {
        val reply = DnsMessage.servfailResponse(query)!!
        if (codes.isEmpty()) return reply
        reply[11] = 1
        return reply + testOptRecord(codes.flatMap { listOf<Byte>(0, 15, 0, 2, (it ushr 8).toByte(), it.toByte()) }.toByteArray())
    }
}
