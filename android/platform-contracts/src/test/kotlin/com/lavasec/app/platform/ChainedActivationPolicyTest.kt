package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedReadiness
import com.lavasec.app.foundation.chained.ChainedReadinessRefusal
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition decision's no-instantiation property, exercised purely.
 *
 * The engine is represented by a spy that the test only invokes when the policy returns
 * [ChainedActivation.Chained]; the point is that a disabled, ineligible, or unconfigured device
 * never reaches that branch, so the ~50 MB engine is never constructed. This is the ordinary-path
 * half of the structural [ChainedEnginePermit] guarantee.
 */
class ChainedActivationPolicyTest {

    private class EngineConstructionSpy {
        var constructed = false
        fun construct() {
            constructed = true
        }
    }

    private fun eligibleAvailability(): ChainedAvailability = ChainedAvailabilityPolicy.evaluate(
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

    private fun stored(): StoredChainedUpstreamConfiguration =
        StoredChainedUpstreamConfiguration(
            configuration = configuration(),
            identity = ChainedUpstreamIdentity(1L, "a".repeat(64)),
            savedAtEpochMillis = null,
        )

    /** Routes a decision through the spy exactly as the runtime would. */
    private fun decide(
        spy: EngineConstructionSpy,
        stored: StoredChainedUpstreamConfiguration?,
        hasEntitlement: Boolean,
        availability: ChainedAvailability,
        userEnabled: Boolean,
        secretsAvailable: Boolean,
        clientDnsOverTunnelAvailable: Boolean = true,
    ): ChainedActivation {
        val activation = ChainedActivationPolicy.decide(
            stored = stored,
            hasEntitlement = hasEntitlement,
            availability = availability,
            userEnabled = userEnabled,
            secretsAvailable = secretsAvailable,
            clientDnsOverTunnelAvailable = clientDnsOverTunnelAvailable,
        )
        if (activation is ChainedActivation.Chained) spy.construct()
        return activation
    }

    @Test
    fun `the inactive case is the DNS-only passthrough and constructs nothing`() {
        val spy = EngineConstructionSpy()
        val activation = decide(
            spy = spy,
            stored = null,
            hasEntitlement = true,
            availability = eligibleAvailability(),
            userEnabled = false,
            secretsAvailable = false,
        )
        assertEquals(ChainedActivation.DnsOnly, activation)
        assertFalse(spy.constructed)
    }

    @Test
    fun `an ineligible device cannot construct the engine even with a stored enabled upstream`() {
        val spy = EngineConstructionSpy()
        val activation = decide(
            spy = spy,
            stored = stored(),
            hasEntitlement = true,
            availability = ChainedAvailability.Ineligible(
                ChainedAvailabilityRefusal.MEMORY_KILL_EXCLUDED,
            ),
            userEnabled = true,
            secretsAvailable = true,
        )
        assertEquals(ChainedActivation.DnsOnly, activation)
        assertFalse(spy.constructed)
    }

    @Test
    fun `a configured eligible enabled upstream starts the chained path`() {
        val spy = EngineConstructionSpy()
        val activation = decide(
            spy = spy,
            stored = stored(),
            hasEntitlement = true,
            availability = eligibleAvailability(),
            userEnabled = true,
            secretsAvailable = true,
        )
        assertTrue(activation is ChainedActivation.Chained)
        assertTrue(spy.constructed)
        assertEquals(1L, (activation as ChainedActivation.Chained).identity.generation)
    }

    @Test
    fun `a configured eligible upstream without its key half stays DNS-only`() {
        val spy = EngineConstructionSpy()
        val activation = decide(
            spy = spy,
            stored = stored(),
            hasEntitlement = true,
            availability = eligibleAvailability(),
            userEnabled = true,
            secretsAvailable = false,
        )
        assertEquals(ChainedActivation.DnsOnly, activation)
        assertFalse(spy.constructed)
    }

    @Test
    fun `an untitled account cannot construct the engine`() {
        val spy = EngineConstructionSpy()
        val activation = decide(
            spy = spy,
            stored = stored(),
            hasEntitlement = false,
            availability = eligibleAvailability(),
            userEnabled = true,
            secretsAvailable = true,
        )
        assertEquals(ChainedActivation.DnsOnly, activation)
        assertFalse(spy.constructed)
    }

    @Test
    fun `canEnable ignores the stored preference but honors every hard gate`() {
        assertTrue(
            ChainedActivationPolicy.canEnable(
                stored = stored(),
                hasEntitlement = true,
                availability = eligibleAvailability(),
                secretsAvailable = true,
                clientDnsOverTunnelAvailable = true,
            ),
        )
        assertFalse(
            ChainedActivationPolicy.canEnable(
                stored = null,
                hasEntitlement = true,
                availability = eligibleAvailability(),
                secretsAvailable = true,
            ),
        )
        assertFalse(
            ChainedActivationPolicy.canEnable(
                stored = stored(),
                hasEntitlement = false,
                availability = eligibleAvailability(),
                secretsAvailable = true,
            ),
        )
        assertFalse(
            ChainedActivationPolicy.canEnable(
                stored = stored(),
                hasEntitlement = true,
                availability = ChainedAvailability.Ineligible(
                    ChainedAvailabilityRefusal.INSUFFICIENT_MEMORY,
                ),
                secretsAvailable = true,
            ),
        )
        assertFalse(
            ChainedActivationPolicy.canEnable(
                stored = stored(),
                hasEntitlement = true,
                availability = eligibleAvailability(),
                secretsAvailable = false,
            ),
        )
    }

    @Test
    fun `a disabled-by-user status is not ready but can be enabled`() {
        val status = ChainedStatus.evaluate(
            stored = stored(),
            hasEntitlement = true,
            availability = eligibleAvailability(),
            userEnabled = false,
            secretsAvailable = true,
            clientDnsOverTunnelAvailable = true,
        )
        assertFalse(status.ready)
        assertTrue(status.canEnable)
        assertEquals(
            ChainedReadinessRefusal.DISABLED_BY_USER,
            (status.readiness as ChainedReadiness.NotReady).reason,
        )
    }

    @Test
    fun `enabling a chained configuration requires a transport change`() {
        // Transport configuration is independent of the filtering pause deadline.
        val decision = ChainedActivationPolicy.decide(
            stored = stored(),
            hasEntitlement = true,
            availability = eligibleAvailability(),
            userEnabled = true,
            secretsAvailable = true,
            clientDnsOverTunnelAvailable = true,
        )
        assertTrue(decision is ChainedActivation.Chained)
        assertTrue(
            ChainedActivationPolicy.requiresRuntimeRebuild(
                currentChained = false,
                desiredChained = decision is ChainedActivation.Chained,
            ),
        )
        // An already-chained runtime does not need a transport change.
        assertFalse(
            ChainedActivationPolicy.requiresRuntimeRebuild(
                currentChained = true,
                desiredChained = decision is ChainedActivation.Chained,
            ),
        )
        // An unchanged DNS-only configuration leaves its runtime in place.
        assertFalse(
            ChainedActivationPolicy.requiresRuntimeRebuild(
                currentChained = false,
                desiredChained = false,
            ),
        )
    }

    @Test
    fun `disabling a chained configuration requires a transport change`() {
        assertTrue(
            ChainedActivationPolicy.requiresRuntimeRebuild(
                currentChained = true,
                desiredChained = false,
            ),
        )
        // An already DNS-only runtime does not need a transport change.
        assertFalse(
            ChainedActivationPolicy.requiresRuntimeRebuild(
                currentChained = false,
                desiredChained = false,
            ),
        )
    }

    @Test
    fun `activation fails closed when client DNS over the tunnel is unavailable`() {
        // Production has no T0 egress yet, so a latched chained tunnel would blackhole client DNS;
        // the gate refuses and the app stays on the DNS-only tunnel.
        val spy = EngineConstructionSpy()
        val activation = ChainedActivationPolicy.decide(
            stored = stored(),
            hasEntitlement = true,
            availability = eligibleAvailability(),
            userEnabled = true,
            secretsAvailable = true,
            // clientDnsOverTunnelAvailable defaults to false.
        )
        assertEquals(ChainedActivation.DnsOnly, activation)
        assertFalse(spy.constructed)
        assertFalse(
            ChainedActivationPolicy.canEnable(
                stored = stored(),
                hasEntitlement = true,
                availability = eligibleAvailability(),
                secretsAvailable = true,
            ),
        )
        val status = ChainedStatus.evaluate(
            stored = stored(),
            hasEntitlement = true,
            availability = eligibleAvailability(),
            userEnabled = true,
            secretsAvailable = true,
        )
        assertFalse(status.ready)
        assertFalse(status.canEnable)
        assertEquals(
            ChainedReadinessRefusal.CLIENT_DNS_OVER_TUNNEL_UNAVAILABLE,
            (status.readiness as ChainedReadiness.NotReady).reason,
        )
    }

    @Test
    fun `a downstream blocker outranks disabled-by-user when enabling is impossible`() {
        // Preference off, so `readiness` stops at DISABLED_BY_USER; but client-DNS egress is also
        // unavailable, which is the real reason the user cannot enable. The reported refusal must
        // name that blocker, not the switch.
        val status = ChainedStatus.evaluate(
            stored = stored(),
            hasEntitlement = true,
            availability = eligibleAvailability(),
            userEnabled = false,
            secretsAvailable = true,
            clientDnsOverTunnelAvailable = false,
        )
        assertFalse(status.canEnable)
        assertEquals(
            ChainedReadinessRefusal.DISABLED_BY_USER,
            (status.readiness as ChainedReadiness.NotReady).reason,
        )
        assertEquals(
            ChainedReadinessRefusal.CLIENT_DNS_OVER_TUNNEL_UNAVAILABLE,
            status.blockingReadinessRefusal(),
        )
    }

    @Test
    fun `a genuinely-off-and-otherwise-fine upstream reports disabled-by-user`() {
        val status = ChainedStatus.evaluate(
            stored = stored(),
            hasEntitlement = true,
            availability = eligibleAvailability(),
            userEnabled = false,
            secretsAvailable = true,
            clientDnsOverTunnelAvailable = true,
        )
        assertTrue(status.canEnable)
        assertEquals(ChainedReadinessRefusal.DISABLED_BY_USER, status.blockingReadinessRefusal())
    }

    @Test
    fun `an unreadable key outranks disabled-by-user in the blocking refusal`() {
        // Preference off and the gate otherwise fine, but the key half cannot be read. The blocker
        // is the credential store, not the switch; returning DISABLED_BY_USER would tell the user
        // to flip a switch that cannot help instead of re-entering the key.
        val status = ChainedStatus.evaluate(
            stored = stored(),
            hasEntitlement = true,
            availability = eligibleAvailability(),
            userEnabled = false,
            secretsAvailable = false,
            clientDnsOverTunnelAvailable = true,
        )
        assertFalse(status.canEnable)
        assertEquals(
            ChainedReadinessRefusal.DISABLED_BY_USER,
            (status.readiness as ChainedReadiness.NotReady).reason,
        )
        // No readiness refusal blocks enabling, so the bridge reports the key condition instead.
        assertNull(status.blockingReadinessRefusal())
    }
}
