package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedReadiness
import com.lavasec.app.foundation.chained.ChainedReadinessRefusal
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainedReadinessBridgeTest {

    private fun eligible(): ChainedAvailability = ChainedAvailabilityPolicy.evaluate(
        hasEntitlement = true,
        read = ChainedDeviceEligibilityRead.Available(
            memory = ChainedDeviceMemoryReading(
                totalMemoryBytes = 4_000_000_000L,
                memoryClassMegabytes = 256,
                isLowRamDevice = false,
            ),
            backoff = ChainedMemoryKillBackoffPolicy.clean,
            apiLevel = 36,
        ),
    )

    private fun reasonOf(readiness: ChainedReadiness): ChainedReadinessRefusal =
        (readiness as ChainedReadiness.NotReady).reason

    private fun configuration(): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
        endpointHost = "vpn.example.com",
        endpointPort = 51820,
        peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
        clientAddress = "10.7.0.2",
        allowedIPs = listOf("0.0.0.0/0"),
        persistentKeepaliveSeconds = 0,
        interfaceMTU = null,
        dnsAddresses = listOf("10.7.0.1"),
    )

    @Test
    fun `the account gate outranks the device verdict`() {
        val readiness = chainedReadinessFrom(
            configuration = null,
            hasEntitlement = false,
            availability = ChainedAvailability.Ineligible(
                ChainedAvailabilityRefusal.INSUFFICIENT_MEMORY,
            ),
            userEnabled = true,
        )
        assertEquals(ChainedReadinessRefusal.NOT_ENTITLED, reasonOf(readiness))
    }

    @Test
    fun `an ineligible device feeds DEVICE_INELIGIBLE to readiness`() {
        val readiness = chainedReadinessFrom(
            configuration = null,
            hasEntitlement = true,
            availability = ChainedAvailability.Ineligible(
                ChainedAvailabilityRefusal.MEMORY_KILL_EXCLUDED,
            ),
            userEnabled = true,
        )
        assertEquals(ChainedReadinessRefusal.DEVICE_INELIGIBLE, reasonOf(readiness))
    }

    @Test
    fun `an eligible device passes the device gate and reaches the configuration gate`() {
        val readiness = chainedReadinessFrom(
            configuration = null,
            hasEntitlement = true,
            availability = eligible(),
            userEnabled = true,
        )
        assertEquals(ChainedReadinessRefusal.NO_CONFIGURATION, reasonOf(readiness))
    }

    @Test
    fun `the user switch still outranks the configuration`() {
        val readiness = chainedReadinessFrom(
            configuration = null,
            hasEntitlement = true,
            availability = eligible(),
            userEnabled = false,
        )
        assertEquals(ChainedReadinessRefusal.DISABLED_BY_USER, reasonOf(readiness))
    }

    @Test
    fun `client DNS over the tunnel is refused fail-closed by default`() {
        val readiness = chainedReadinessFrom(
            configuration = configuration(),
            hasEntitlement = true,
            availability = eligible(),
            userEnabled = true,
        )
        assertEquals(
            ChainedReadinessRefusal.CLIENT_DNS_OVER_TUNNEL_UNAVAILABLE,
            reasonOf(readiness),
        )
    }

    @Test
    fun `the ready gate is reachable once client DNS over the tunnel is available`() {
        val readiness = chainedReadinessFrom(
            configuration = configuration(),
            hasEntitlement = true,
            availability = eligible(),
            userEnabled = true,
            clientDnsOverTunnelAvailable = true,
        )
        assertEquals(ChainedReadiness.Ready, readiness)
    }

    @Test
    fun `every refusal maps to a stable user-facing code, never a log identifier`() {
        ChainedReadinessRefusal.entries.forEach { refusal ->
            val code = refusal.userFacingReasonCode()
            withClue(refusal.name) {
                assertTrue(code.isNotBlank())
                assertNotEquals(refusal.logValue, code)
                assertFalse(code.startsWith("chained-not-ready-"))
            }
        }
    }

    private inline fun withClue(clue: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: AssertionError) {
            throw AssertionError("$clue: ${failure.message}", failure)
        }
    }
}
