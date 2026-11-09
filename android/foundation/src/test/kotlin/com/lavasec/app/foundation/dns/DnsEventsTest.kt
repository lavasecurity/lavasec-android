package com.lavasec.app.foundation.dns

import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DnsEventsTest {
    @Test
    fun `portable resolver and write failure reason contracts are exact`() {
        assertEquals(
            listOf("DEVICE_UDP", "DEVICE_TCP", "MULLVAD_DOH", "WIREGUARD_UDP", "DOQ", "DOT", "PLAIN_UDP", "PLAIN_TCP"),
            ResolverTransport.values().map { it.name },
        )
        assertEquals(listOf("DEVICE", "MULLVAD_DOH", "WIREGUARD_T0", "PLAIN_DNS"), ResolverKind.values().map { it.name })
        assertEquals(
            listOf(
                "TIMEOUT",
                "PROTECT_FAILED",
                "BIND_FAILED",
                "SOCKET_FAILED",
                "SEND_FAILED",
                "RECEIVE_FAILED",
                "MISMATCH_LIMIT",
                "INVALID_RESPONSE",
                "OVERSIZED_RESPONSE",
                "HTTP_STATUS",
                "HTTP_CONTENT_TYPE",
                "TLS_FAILED",
                "BACKED_OFF",
                "CANCELLED",
                "STALE_EPOCH",
                "OVERLOADED",
            ),
            ResolverFailureReason.values().map { it.name },
        )
        assertEquals(
            listOf("NO_USABLE_RESOLVER", "NO_RESOLVER_ENABLED", "UNSUPPORTED_ENCRYPTED_RESOLVER"),
            ResolverPlanUnavailableReason.values().map { it.name },
        )
        assertEquals(
            listOf("DESCRIPTOR_CLOSED", "WRITE_FAILED", "STALE_SESSION"),
            TunWriteFailureReason.values().map { it.name },
        )
    }

    @Test
    fun `health event and unavailable reason variants preserve their typed payloads`() {
        val artifactReason = ProtectionUnavailableReason.Artifact(
            FilterArtifactUnavailableReason.DIGEST_MISMATCH,
        )
        val resolverPlanReason = ProtectionUnavailableReason.ResolverPlan(
            ResolverPlanUnavailableReason.NO_USABLE_RESOLVER,
        )
        assertEquals(FilterArtifactUnavailableReason.DIGEST_MISMATCH, artifactReason.reason)
        assertEquals(ResolverPlanUnavailableReason.NO_USABLE_RESOLVER, resolverPlanReason.reason)
        assertEquals(
            ProtectionUnavailableReason.NetworkUnavailable,
            ProtectionUnavailableReason.NetworkUnavailable,
        )
        assertEquals(
            ProtectionUnavailableReason.RuntimeUnavailable,
            ProtectionUnavailableReason.RuntimeUnavailable,
        )

        assertEquals(
            ResolverFailureReason.TIMEOUT,
            DnsHealthEvent.ResolverAttemptFailed(
                ResolverKind.DEVICE,
                ResolverFailureReason.TIMEOUT,
            ).reason,
        )
        assertEquals(
            ResolverKind.MULLVAD_DOH,
            DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH).resolver,
        )
        assertEquals(
            ResolverKind.DEVICE,
            DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE).resolver,
        )
        assertEquals(
            artifactReason,
            DnsHealthEvent.ProtectionUnavailable(artifactReason).reason,
        )
        assertEquals(
            TunWriteFailureReason.STALE_SESSION,
            DnsHealthEvent.TunWriteFailed(TunWriteFailureReason.STALE_SESSION).reason,
        )
    }

    @Test
    fun `decision event carries stable identity time normalized domain and filter decision`() {
        val decision = FilterDecision(FilterAction.ALLOW, FilterDecisionReason.PAUSED_ALLOW)
        assertEquals(
            DnsDecisionEvent(
                id = 9,
                timestampMillis = 123_456,
                normalizedDomain = "example.com",
                decision = decision,
            ),
            DnsDecisionEvent(9, 123_456, "example.com", decision),
        )
    }

    @Test
    fun `decision event rejects an invalid normalized domain`() {
        rejected { DnsDecisionEvent(1, 2, "localhost", allowDecision()) }
    }

    @Test
    fun `decision event rejects negative latency`() {
        rejected { DnsDecisionEvent(1, 2, "example.com", allowDecision(), latencyMillis = -1) }
    }

    @Test
    fun `decision event rejects transport without a resolver`() {
        rejected {
            DnsDecisionEvent(
                1,
                2,
                "example.com",
                allowDecision(),
                transport = ResolverTransport.DEVICE_UDP,
            )
        }
    }

    @Test
    fun `decision event rejects resolver evidence for a local block`() {
        rejected {
            DnsDecisionEvent(
                1,
                2,
                "example.com",
                FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
                resolver = ResolverKind.DEVICE,
            )
        }
    }

    @Test
    fun `decision event rejects Mullvad transport for the device resolver`() {
        rejected {
            DnsDecisionEvent(
                1,
                2,
                "example.com",
                allowDecision(),
                resolver = ResolverKind.DEVICE,
                transport = ResolverTransport.MULLVAD_DOH,
            )
        }
    }

    @Test
    fun `decision event rejects device transports for the Mullvad resolver`() {
        for (transport in listOf(ResolverTransport.DEVICE_UDP, ResolverTransport.DEVICE_TCP)) {
            rejected {
                DnsDecisionEvent(
                    1,
                    2,
                    "example.com",
                    allowDecision(),
                    resolver = ResolverKind.MULLVAD_DOH,
                    transport = transport,
                )
            }
        }
    }

    @Test
    fun `chained evidence identifies tunnel UDP and rejects physical resolver transports`() {
        val event = DnsDecisionEvent(
            1, 2, "example.com", allowDecision(),
            resolver = ResolverKind.WIREGUARD_T0,
            transport = ResolverTransport.WIREGUARD_UDP,
        )
        assertEquals(ResolverTransport.WIREGUARD_UDP, event.transport)
        for (transport in listOf(ResolverTransport.DEVICE_UDP, ResolverTransport.DEVICE_TCP, ResolverTransport.MULLVAD_DOH)) {
            rejected { event.copy(transport = transport) }
        }
        rejected { event.copy(resolver = ResolverKind.DEVICE) }
    }

    @Test
    fun `fixed plain query evidence cannot be labelled device encrypted or tunnel DNS`() {
        val answer = DnsDecisionEvent(1, 2, "example.com", allowDecision(),
            resolver = ResolverKind.PLAIN_DNS, transport = ResolverTransport.PLAIN_UDP)
        assertEquals(ResolverTransport.PLAIN_TCP, answer.copy(transport = ResolverTransport.PLAIN_TCP).transport)
        ResolverTransport.entries.filterNot { it == ResolverTransport.PLAIN_UDP || it == ResolverTransport.PLAIN_TCP }
            .forEach { transport -> rejected { answer.copy(transport = transport) } }
        rejected { answer.copy(resolver = ResolverKind.DEVICE) }
        rejected { answer.copy(resolver = ResolverKind.WIREGUARD_T0) }
    }

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Expected DNS decision event rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun allowDecision(): FilterDecision =
        FilterDecision(FilterAction.ALLOW, FilterDecisionReason.DEFAULT_ALLOW)
}
