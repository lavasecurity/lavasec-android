package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ConfigurationIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecondaryEndpointFallbackResolverTest {
    @Test
    fun `a primary answer is returned without consulting the secondary`() = runBlocking {
        var secondaryCalls = 0
        val resolver = SecondaryEndpointFallbackResolver(
            primary = DnsResolver {
                ResolverResult.Answer(byteArrayOf(1, 2, 3), ResolverTransport.DEVICE_UDP)
            },
            secondary = DnsResolver {
                secondaryCalls++
                ResolverResult.Answer(byteArrayOf(9), ResolverTransport.DEVICE_UDP)
            },
        )

        val result = resolver.resolve(request())

        assertTrue(result is ResolverResult.Answer)
        assertEquals(0, secondaryCalls)
    }

    @Test
    fun `a primary failure falls back to the secondary`() = runBlocking {
        var secondaryCalls = 0
        val resolver = SecondaryEndpointFallbackResolver(
            primary = DnsResolver { ResolverResult.Failure(ResolverFailureReason.TIMEOUT) },
            secondary = DnsResolver {
                secondaryCalls++
                ResolverResult.Answer(byteArrayOf(9, 9), ResolverTransport.DEVICE_UDP)
            },
        )

        val result = resolver.resolve(request())

        assertTrue(result is ResolverResult.Answer)
        assertEquals(1, secondaryCalls)
    }

    @Test
    fun `a stale primary failure does not retry the secondary`() = runBlocking {
        var secondaryCalls = 0
        val resolver = SecondaryEndpointFallbackResolver(
            primary = DnsResolver { ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH) },
            secondary = DnsResolver {
                secondaryCalls++
                ResolverResult.Answer(byteArrayOf(9), ResolverTransport.DEVICE_UDP)
            },
        )

        val result = resolver.resolve(request())

        assertTrue(result is ResolverResult.Failure)
        assertEquals(0, secondaryCalls)
    }

    @Test
    fun `cancelled and overloaded work never tries another endpoint`() = runBlocking {
        for (reason in listOf(ResolverFailureReason.CANCELLED, ResolverFailureReason.OVERLOADED)) {
            var secondaryCalls = 0
            val result = SecondaryEndpointFallbackResolver(
                DnsResolver { ResolverResult.Failure(reason) },
                DnsResolver { secondaryCalls++; ResolverResult.Failure(ResolverFailureReason.TIMEOUT) },
            ).resolve(request())
            assertEquals(reason, (result as ResolverResult.Failure).reason)
            assertEquals(0, secondaryCalls)
        }
    }

    private fun request(): ResolverRequest {
        val query = byteArrayOf(
            0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 1, 0, 1,
        )
        return ResolverRequest(
            query,
            question = DnsMessage.parseQuestion(query),
            epoch = epoch(),
            networkContext = NetworkContext(network(7), 1, emptyList(), isCellular = false),
        )
    }

    private fun epoch() = EngineEpoch(
        sessionGeneration = 1,
        configurationIdentity = ConfigurationIdentity(1, "cfg"),
        artifactDigest = "artifact",
        policyRevision = 1,
        networkGeneration = 1,
        engineGeneration = 1,
        primaryResolverIdentity = PrimaryResolverIdentity("device-dns"),
        fallbackCoverage = ResolverFallbackCoverage.NONE,
    )

    private fun network(handle: Int): Network =
        Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(handle)
}
