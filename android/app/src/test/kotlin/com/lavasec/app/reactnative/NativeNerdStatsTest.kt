package com.lavasec.app.reactnative

import com.lavasec.app.diagnostics.LiveNerdStatsSample
import com.lavasec.app.foundation.dns.*
import com.lavasec.app.foundation.health.*
import com.lavasec.app.foundation.resolver.*
import com.lavasec.app.vpn.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class NativeNerdStatsTest {
    @Test fun `internet access is independent of DNS counters and suppressed while chained or disconnected`() {
        val original = sample()
        fun status(validated: Boolean?, chained: Boolean = false, connected: Boolean = true): String =
            result(original.copy(health = original.health.copy(internetValidated = validated),
                chained = chained, connected = connected)).healthSections.first { it.id == "network" }
                .rows.first { it.id == "internet" }.value
        assertEquals("Not sampled", status(null))
        assertEquals("Internet unverified", status(false))
        assertEquals("Internet check passed", status(true))
        assertEquals("Not sampled", status(true, chained = true))
        assertEquals("Not sampled", status(true, connected = false))
    }
    private fun sample(connected: Boolean = true, now: Long = 2000) = LiveNerdStatsSample(
        now, VpnHealthStatus(diagnostics = VpnSessionDiagnostics(
            startedAtMillis = 100, upstreamSuccesses = 12, upstreamFailures = 3,
            responseLatenciesMillis = listOf(10, 20, 30), lastHttpProtocol = DnsHttpProtocol.HTTP_2,
            lastTransport = ResolverTransport.MULLVAD_DOH,
            tiers = mapOf(1 to VpnTierDiagnostics(
                ResolverTierDiagnosticObservation(1, ResolverKind.MULLVAD_DOH, ResolverTierDiagnosticOutcome.SERVED, ResolverTransport.MULLVAD_DOH),
                observedAtMillis = 1000, attempts = 12, served = 12)),
        )), connected, if (connected) "Connected" else "Not connected", false,
        "Wi-Fi", "Satisfied", "Network DNS\n192.0.2.53", listOf("192.0.2.53"), "Off\nNo configuration",
    )
    private fun result(sample: LiveNerdStatsSample = sample()) = nerdStatsResult(sample, ResolverSelectionPresets.DEVICE_DNS_WITH_MULLVAD_FALLBACK, listOf(listOf("Version", "1"))) { "at $it" }
    @Test fun `all shared sections and four configuration tiers are present with real endpoints`() {
        val value = result()
        assertEquals(listOf("T0 · VPN chaining", "1 · Primary DNS", "2 · Fallback DNS", "S · System DNS"), value.tiers.map { it[0] })
        assertTrue(value.tiers[1][1].contains("192.0.2.53"))
        assertTrue(value.tiers[2][1].contains("https://dns.mullvad.net/dns-query"))
        assertEquals(listOf("network", "dns-health-0", "dns-health-1", "dns-health-2", "performance"), value.healthSections.map { it.id })
        val performance = value.healthSections.last().rows.associate { it.id to it.value }
        assertEquals("12", performance["upstream-success"])
        assertEquals("3", performance["upstream-failures"])
        assertEquals("HTTP/2", performance["doh-protocol"])
        assertTrue(performance["response-time"]!!.contains("p95 30 ms"))
        assertEquals("at 2000", performance["sampled"])
        assertEquals("at 3000", result(sample(now = 3000)).healthSections.last().rows.last().value)
    }
    @Test fun `old or disconnected evidence never claims a healthy current resolver`() {
        fun observation(sample: LiveNerdStatsSample) = result(sample).healthSections.first { it.id == "dns-health-1" }.rows.first().value
        assertEquals("Served", observation(sample()))
        assertEquals("Sample out of date", observation(sample(now = 100_000)))
        assertEquals("Not connected", observation(sample(connected = false)))
        assertEquals("Sample out of date", observation(sample(now = 500)))
    }
    @Test fun `stats JSON includes tiers rather than silently losing them at the bridge`() {
        val json = ProductQueryWire.encode(result()) as org.json.JSONObject
        val serialized = json.toString()
        assertEquals(4, json.getJSONArray("tiers").length())
        assertTrue(serialized.contains("DNS over HTTPS"))
    }

    @Test fun `validated deadline response is labeled as liveness without service`() {
        val original = sample()
        val tier = VpnTierDiagnostics(latest = ResolverTierDiagnosticObservation(1, ResolverKind.MULLVAD_DOH,
            ResolverTierDiagnosticOutcome.ANSWERED, ResolverTransport.MULLVAD_DOH, peerLivenessOnly = true),
            observedAtMillis = 1000L)
        val changed = original.copy(health = original.health.copy(diagnostics = original.health.diagnostics.copy(tiers = mapOf(1 to tier))))
        val rows = result(changed).healthSections.first { it.id == "dns-health-1" }.rows.associate { it.id to it.value }
        assertEquals("Replied without service", rows["observation"])
        assertEquals("0", rows["served"])
        assertEquals("0", rows["rejected-streak"])
    }

    @Test fun `local admission is separate from peer health and only fresh admission explains no initiated repair`() {
        val original = sample()
        val peer = original.health.diagnostics.tiers.getValue(1)
        val local = ResolverTierDiagnosticObservation(1, ResolverKind.MULLVAD_DOH,
            ResolverTierDiagnosticOutcome.NOT_ATTEMPTED, failure = ResolverFailureReason.BACKED_OFF)
        val tier = peer.copy(latestAdmission = local, admissionObservedAtMillis = 1500L, notAttempted = 1L)
        fun rows(sample: LiveNerdStatsSample, value: VpnTierDiagnostics) = result(sample.copy(
            health = sample.health.copy(diagnostics = sample.health.diagnostics.copy(tiers = mapOf(1 to value)))))
            .healthSections.first { it.id == "dns-health-1" }.rows.associate { it.id to it.value }
        val fresh = rows(original, tier)
        assertEquals("Served", fresh["observation"])
        assertEquals("Waiting to retry", fresh["admission"])
        assertEquals("Not started", fresh["recovery"])
        assertEquals("0", fresh["failures"])
        assertEquals("Status unavailable", rows(original.copy(sampledAtMillis = 100_000L), tier)["admission"])
        assertEquals("Status unavailable", rows(original.copy(connected = false), tier)["admission"])
        val localOnly = rows(original, tier.copy(latest = null, observedAtMillis = null))
        assertEquals("No observations yet", localOnly["observation"])
        assertEquals("Waiting to retry", localOnly["admission"])
        assertEquals("Not started", localOnly["recovery"])
    }
}
