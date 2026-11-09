package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.*
import com.lavasec.app.foundation.health.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DnsLargeAnswerSelectionTest {
    @Test fun `ordered and legacy encrypted fallback select complete large answer before client truncation`() = runBlocking {
        for (ordered in listOf(false, true)) for (size in listOf(1_253, 4_096)) {
            val fixture = Fixture(ordered = ordered)
            val response = padded(fixture.positive(), size)
            val result = fixture.resolve(ResolverResult.Failure(ResolverFailureReason.TIMEOUT),
                ResolverResult.Answer(response, ResolverTransport.MULLVAD_DOH))
            assertSelected(fixture, result, response, ResolverTransport.MULLVAD_DOH, ProtectionFallback.ENCRYPTED_DNS)
            assertEquals(1, fixture.deviceCalls)
            assertEquals(1, fixture.encryptedCalls)
        }
    }

    @Test fun `large Device fallback after encrypted silence remains selected and served`() = runBlocking {
        for (size in listOf(1_253, 4_096)) {
            val fixture = Fixture(encryptedFirst = true)
            val response = padded(fixture.positive(), size)
            val result = fixture.resolve(ResolverResult.Answer(response, ResolverTransport.DEVICE_TCP),
                ResolverResult.Failure(ResolverFailureReason.TIMEOUT))
            assertSelected(fixture, result, response, ResolverTransport.DEVICE_TCP, ProtectionFallback.DEVICE_DNS)
        }
    }

    @Test fun `large positive replaces operational error without losing original error evidence`() = runBlocking {
        for (ordered in listOf(false, true)) {
            val fixture = Fixture(ordered = ordered)
            val error = withErrors(fixture.query, listOf(23))
            val response = padded(fixture.positive(), 1_253)
            val result = fixture.resolve(ResolverResult.Answer(error, ResolverTransport.DEVICE_UDP),
                ResolverResult.Answer(response, ResolverTransport.MULLVAD_DOH))
            val selected = assertSelected(fixture, result, response, ResolverTransport.MULLVAD_DOH, ProtectionFallback.ENCRYPTED_DNS)
            assertEquals(ResolverTierDiagnosticOutcome.ANSWERED, selected.resolverQueryEvidence!!.tierObservations.first().outcome)
            assertEquals(PrimaryResolverOutcome.REJECTED, selected.resolverQueryEvidence!!.primaryOutcome)
        }
    }

    @Test fun `large portal Device positive replaces negative and earns real address rescue credit`() = runBlocking {
        val fixture = Fixture(encryptedFirst = true, domain = "deltawifi.com")
        val negative = DnsMessage.servfailResponse(fixture.query)!!.also { it[3] = 0x83.toByte() }
        val response = padded(fixture.positive(), 4_096)
        val result = fixture.resolve(ResolverResult.Answer(response, ResolverTransport.DEVICE_TCP),
            ResolverResult.Answer(negative, ResolverTransport.MULLVAD_DOH))
        val selected = assertSelected(fixture, result, response, ResolverTransport.DEVICE_TCP, ProtectionFallback.DEVICE_DNS)
        assertTrue(fixture.lastDeviceRequest!!.fallbackContext.requiresPortalAdmission)
        assertEquals(DnsFallbackDisposition.SERVED, selected.resolverQueryEvidence!!.fallbackObservations.single().disposition)
        assertEquals(DnsFallbackReason.PORTAL_ADDRESS_NEGATIVE, selected.resolverQueryEvidence!!.fallbackObservations.single().reason)
    }

    @Test fun `large policy error remains terminal without consulting configured lower tier`() = runBlocking {
        for (ordered in listOf(false, true)) {
            val fixture = Fixture(ordered = ordered)
            val response = padded(withErrors(fixture.query, listOf(15, 23)), 1_253)
            val selected = fixture.resolve(ResolverResult.Answer(response, ResolverTransport.DEVICE_UDP),
                ResolverResult.Answer(fixture.positive(), ResolverTransport.MULLVAD_DOH)) as ResolverResult.Answer
            assertArrayEquals(response, selected.dnsMessage)
            assertEquals(0, fixture.encryptedCalls)
            assertEquals(ProtectionFallback.NONE, selected.resolverQueryEvidence!!.successfulFallback)
            assertEquals(ResolverTierDiagnosticOutcome.ANSWERED, selected.resolverQueryEvidence!!.tierObservations.single().outcome)
            assertBounded(fixture.query, selected.dnsMessage, 2)
        }
    }

    private fun assertSelected(fixture: Fixture, result: ResolverResult, source: ByteArray,
        transport: ResolverTransport, fallback: ProtectionFallback): ResolverResult.Answer {
        assertTrue("large validated response must remain an answer: $result", result is ResolverResult.Answer)
        val selected = result as ResolverResult.Answer
        assertArrayEquals(source, selected.dnsMessage)
        assertEquals(transport, selected.transport)
        val evidence = checkNotNull(selected.resolverQueryEvidence)
        assertEquals(fallback, evidence.successfulFallback)
        val served = evidence.tierObservations.last()
        assertEquals(ResolverTierDiagnosticOutcome.SERVED, served.outcome)
        assertEquals(DnsFallbackResponseClass.POSITIVE, served.responseClass)
        assertEquals(true, served.terminalAddressPresent)
        assertBounded(fixture.query, selected.dnsMessage, 0)
        return selected
    }

    private fun assertBounded(query: ByteArray, selected: ByteArray, responseCode: Int) {
        val bounded = checkNotNull(ResolverReplyBoundary.boundForClient(query, selected))
        assertEquals(12 + DnsMessage.parseQuestion(query).questionBytes(query).size, bounded.size)
        assertEquals(0x0200, bounded.readUInt16(2) and 0x0200)
        assertEquals(responseCode, bounded.readUInt16(2) and 15)
        assertEquals(0, bounded.readUInt16(6))
        assertEquals(0, bounded.readUInt16(8))
        assertEquals(0, bounded.readUInt16(10))
        assertEquals(0, selected.readUInt16(2) and 0x0200)
    }

    private class Fixture(ordered: Boolean = true, encryptedFirst: Boolean = false, domain: String = "example.com") {
        val context = testNetworkContext()
        private val device = testResolverPlan(context)
        private val encrypted = testResolverPlan(context, mullvadEnabled = true, primaryResolverKind = ResolverKind.MULLVAD_DOH)
        private val routes = if (encryptedFirst) listOf(encrypted, device) else listOf(device, encrypted)
        private val plan = if (!ordered) testResolverPlan(context, mullvadEnabled = true) else
            ResolverPlan(context.network, context.networkGeneration, false, device.deviceDnsServers, true,
                routes.first().primaryResolverKind, primaryResolverIdentity = routes.first().primaryResolverIdentity,
                fallbackCoverage = if (encryptedFirst) ResolverFallbackCoverage.DEVICE_DNS else ResolverFallbackCoverage.ENCRYPTED_DNS,
                orderedRoutes = routes)
        val query = testDnsQuery(domain)
        var deviceCalls = 0
        var encryptedCalls = 0
        var lastDeviceRequest: ResolverRequest? = null
        fun positive() = DnsMessage.bootstrapResponse(query, byteArrayOf(192.toByte(), 0, 2, 9), ByteArray(16))
        suspend fun resolve(deviceResult: ResolverResult, encryptedResult: ResolverResult): ResolverResult {
            val encryptedResolver = DnsResolver { encryptedCalls++; encryptedResult }
            val selectedEncrypted = if (plan.orderedRoutes == null) encryptedResolver else object : OrderedEncryptedDnsResolver {
                override fun resolverFor(identity: PrimaryResolverIdentity): DnsResolver = encryptedResolver
                override suspend fun resolve(request: ResolverRequest): ResolverResult = error("exact route required")
            }
            val clock = MonotonicClock { 0L }
            val sequencer = ResolverRouteSequencer(plan, DeviceDnsRoute { request, _ ->
                deviceCalls++; lastDeviceRequest = request
                DeviceDnsRouteResult(deviceResult, if (deviceResult is ResolverResult.Answer) ResolverEndpointKey.DeviceAddress(context.dnsServers.first()) else null)
            }, selectedEncrypted, ResolverEndpointBackoffPolicy(clock), DeviceResolverWedgeEvidence { true }, clock)
            return sequencer.resolve(ResolverRequest(query, DnsMessage.parseQuestion(query), testEpoch(resolverPlan = plan), context,
                fallbackContext = DnsFallbackQueryContext(true, true, true, true, DnsPortalTierFallback.registeredHosts))).result
        }
    }

    /** Uses the existing OPT for error packets; a second OPT would invalidate the regression. */
    private fun padded(response: ByteArray, size: Int): ByteArray {
        val walk = checkNotNull(DnsResponseWireWalker.walk(response))
        val opt = walk.records.singleOrNull { it.type == 41 }
        val padding = size - response.size - if (opt == null) 15 else 4
        require(padding >= 0)
        val option = byteArrayOf(0, 12, (padding ushr 8).toByte(), padding.toByte()) + ByteArray(padding)
        return if (opt == null) response.copyOf().also { it[11] = 1 } + testOptRecord(option)
        else (response + option).also { bytes ->
            val length = opt.endOffset - opt.rdataOffset + option.size
            bytes[opt.rdataOffset - 2] = (length ushr 8).toByte()
            bytes[opt.rdataOffset - 1] = length.toByte()
        }
    }

    private fun withErrors(query: ByteArray, codes: List<Int>) =
        DnsMessage.servfailResponse(query)!!.also { it[11] = 1 } +
            testOptRecord(codes.flatMap { listOf<Byte>(0, 15, 0, 2, (it ushr 8).toByte(), it.toByte()) }.toByteArray())
}
