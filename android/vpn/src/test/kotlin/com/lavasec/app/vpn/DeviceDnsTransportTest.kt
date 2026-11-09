package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ConfigurationIdentity
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceDnsTransportTest {
    @Test
    fun `late matching UDP negative ends the ladder without penalizing its peer`() {
        val h = Harness()
        val server = address("192.0.2.51")
        val next = address("192.0.2.52")
        val policy = ResolverEndpointBackoffPolicy(h.clock)
        h.udp("udp", receive = {
            h.clock.advance(1_800_000_000L)
            datagram(server, body = RESPONSE.copyOf().apply { this[3] = 0x83.toByte() })
        })
        h.udp("unused", receive = { datagram(next) })

        val result = h.resolveWithPolicy(listOf(server, next), policy, absoluteDeadlineNanos = 1_800_000_000L)
        assertFailure(result, ResolverFailureReason.TIMEOUT)
        result as ResolverResult.Failure
        assertTrue(result.terminal)
        assertTrue(result.peerAnswered)
        assertFalse(policy.isBackedOff(ResolverEndpointKey.DeviceAddress(server)))
        assertEquals(1, h.platform.udpCreateCount)
        assertEquals(0, h.platform.tcpCreateCount)
        assertEquals(0, h.registry.registeredCount)
    }

    @Test
    fun `late matching TCP negative ends the ladder without penalizing its peer`() {
        val h = Harness()
        val server = address("192.0.2.51")
        val policy = ResolverEndpointBackoffPolicy(h.clock)
        h.udp("udp", receive = { throw SocketTimeoutException() })
        h.tcp("tcp", RESPONSE.copyOf().apply { this[3] = 0x83.toByte() }).readBodyAction = {
            h.clock.advance(1_800_000_000L)
        }

        val result = h.resolveWithPolicy(listOf(server), policy, absoluteDeadlineNanos = 1_800_000_000L)
        assertFailure(result, ResolverFailureReason.TIMEOUT)
        result as ResolverResult.Failure
        assertTrue(result.terminal)
        assertTrue(result.peerAnswered)
        assertFalse(policy.isBackedOff(ResolverEndpointKey.DeviceAddress(server)))
        assertEquals(0, h.registry.registeredCount)
    }

    @Test
    fun `received negative after UDP subattempt deadline cannot enter failing TCP or next endpoint`() {
        val h = Harness()
        val server = address("192.0.2.51")
        val policy = ResolverEndpointBackoffPolicy(h.clock)
        h.udp("udp", receive = {
            h.clock.advance(1_000_000_000L)
            datagram(server, body = RESPONSE.copyOf().apply { this[3] = 0x83.toByte() })
        })
        h.tcp("unused-tcp", ByteArray(11))
        h.udp("unused-next", receive = { datagram(address("192.0.2.52")) })

        val result = h.resolveWithPolicy(listOf(server, address("192.0.2.52")), policy,
            absoluteDeadlineNanos = 1_800_000_000L)
        assertFailure(result, ResolverFailureReason.TIMEOUT)
        result as ResolverResult.Failure
        assertTrue(result.terminal)
        assertTrue(result.peerAnswered)
        assertFalse(policy.isBackedOff(ResolverEndpointKey.DeviceAddress(server)))
        assertEquals(1, h.platform.udpCreateCount)
        assertEquals(0, h.platform.tcpCreateCount)
    }

    @Test
    fun `late mismatched malformed and off-source UDP packets receive no peer credit`() {
        val server = address("192.0.2.51")
        val invalid = listOf(
            datagram(server, body = RESPONSE.copyOf().apply { this[0] = 1 }),
            datagram(server, body = RESPONSE.copyOf().apply { this[7] = 1 }),
            datagram(address("192.0.2.99")),
        )
        for (packet in invalid) {
            val h = Harness()
            h.udp("udp", receive = { h.clock.advance(1_800_000_000L); packet })
            val result = h.resolve(listOf(server), absoluteDeadlineNanos = 1_800_000_000L)
            assertFailure(result, ResolverFailureReason.TIMEOUT)
            result as ResolverResult.Failure
            assertFalse(result.peerAnswered)
            assertFalse(result.terminal)
        }
    }

    @Test
    fun `explicit policy evidence in malformed alias reply stays terminal at the transport`() {
        val h = Harness()
        val server = address("192.0.2.51")
        val policy = ResolverEndpointBackoffPolicy(h.clock)
        val header = RESPONSE.copyOf().apply { this[3] = 0x82.toByte(); this[7] = 1; this[11] = 1 }
        val malformedCname = byteArrayOf(0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 4, 65)
        val blockedEde = byteArrayOf(0, 0, 41, 4, 0xd0.toByte(), 0, 0, 0, 0, 0, 6, 0, 15, 0, 2, 0, 15)
        h.udp("udp", receive = { datagram(server, body = header + malformedCname + blockedEde) })

        val result = h.resolveWithPolicy(listOf(server, address("192.0.2.52")), policy)
        assertFailure(result, ResolverFailureReason.INVALID_RESPONSE)
        result as ResolverResult.Failure
        assertTrue(result.terminal)
        assertTrue(result.peerAnswered)
        assertFalse(policy.isBackedOff(ResolverEndpointKey.DeviceAddress(server)))
        assertEquals(1, h.platform.udpCreateCount)
        assertEquals(0, h.platform.tcpCreateCount)
    }

    @Test
    fun `portal network admission is rechecked before UDP send and TCP write`() {
        for (tcpStage in listOf(false, true)) {
            val h = Harness()
            val server = address("192.0.2.51")
            var current = tcpStage
            val udp = h.udp("udp", receive = { throw SocketTimeoutException() })
            val tcp = h.tcp("tcp", RESPONSE).apply { connectAction = { current = false } }
            val context = DnsFallbackQueryContext(requiresPortalAdmission = true, portalNetworkIsCurrent = { current })

            assertFailure(h.resolve(listOf(server), fallbackContext = context), ResolverFailureReason.STALE_EPOCH)
            assertEquals(if (tcpStage) 1 else 0, udp.sendCount)
            assertEquals(0, tcp.writeCount)
            assertEquals(0, h.registry.registeredCount)
        }
    }

    @Test
    fun `ordinary Device DNS retains continuity without portal admission`() {
        val h = Harness()
        val server = address("192.0.2.51")
        h.udp("udp", receive = { datagram(server) })
        assertAnswer(h.resolve(listOf(server), fallbackContext = DnsFallbackQueryContext(
            requiresPortalAdmission = false, portalNetworkIsCurrent = { false },
        )), ResolverTransport.DEVICE_UDP)
    }

    @Test
    fun `fixed plain UDP uses retained addresses rather than the Device DNS snapshot`() {
        val fixed = address("192.0.2.53")
        val h = Harness(fixedDnsServers = listOf(fixed))
        val udp = h.udp("udp", receive = { datagram(fixed) })
        assertAnswer(h.resolve(listOf(address("192.0.2.99"))), ResolverTransport.PLAIN_UDP)
        assertEquals(listOf(fixed), udp.sentServers)
        assertEquals(h.network, h.platform.boundNetworks.single())
        assertTrue(h.events.indexOf("udp.protect") < h.events.indexOf("udp.bind"))
        assertTrue(h.events.indexOf("udp.bind") < h.events.indexOf("udp.send"))
        assertEquals(0, h.registry.registeredCount)
    }

    @Test
    fun `fixed plain truncation enters same endpoint TCP and retains exact message identity`() {
        val fixed = address("192.0.2.53")
        val h = Harness(fixedDnsServers = listOf(fixed))
        h.udp("udp", receive = { datagram(fixed, body = RESPONSE.copyOf().apply { this[2] = 0x83.toByte() }) })
        val tcp = h.tcp("tcp", RESPONSE)
        assertAnswer(h.resolve(emptyList()), ResolverTransport.PLAIN_TCP)
        assertEquals(listOf(fixed), tcp.connectedServers)
        assertArrayEquals(frame(QUERY), tcp.frames.single())
        assertEquals(0, h.registry.registeredCount)
    }

    @Test
    fun `fixed plain ignores wrong source and ID and cannot publish after retirement`() {
        val fixed = address("192.0.2.53")
        val h = Harness(fixedDnsServers = listOf(fixed))
        h.udp("udp", receives = listOf({ datagram(address("192.0.2.99")) },
            { datagram(fixed, body = RESPONSE.copyOf().apply { this[0] = 1 }) }, { datagram(fixed) }))
        assertAnswer(h.resolve(emptyList()), ResolverTransport.PLAIN_UDP)
        val retired = Harness(fixedDnsServers = listOf(fixed))
        retired.udp("udp", receive = { retired.registry.current = false; datagram(fixed) })
        assertFailure(retired.resolve(emptyList()), ResolverFailureReason.STALE_EPOCH)
        assertEquals(0, retired.registry.registeredCount)
    }

    @Test
    fun `UDP uses one network snapshot and exact lifecycle before returning an answer`() {
        val harness = Harness(dnsPort = 5_300)
        val server = address("192.0.2.53")
        val udp = harness.udp("udp", receive = { datagram(server, 5_300) })

        val result = harness.resolve(listOf(server))

        assertAnswer(result, ResolverTransport.DEVICE_UDP)
        assertArrayEquals(QUERY, udp.sentQueries.single())
        assertEquals(listOf(server), udp.sentServers)
        assertEquals(listOf(5_300), udp.sentPorts)
        assertEquals(listOf(4_097), udp.receiveMaxBytes)
        assertEquals(
            listOf(
                "udp.create",
                "udp.register",
                "udp.protect",
                "udp.bind",
                "udp.send",
                "udp.receive",
                "udp.unregister",
                "udp.close",
            ),
            harness.events,
        )
        assertEquals(harness.network, harness.platform.boundNetworks.single())
        assertEquals(1, udp.closeCount)
        assertEquals(0, harness.registry.registeredCount)
    }

    @Test
    fun `UDP timeout enters same-server TCP with exact lifecycle and frame`() {
        val harness = Harness()
        val server = address("192.0.2.60")
        harness.udp("udp", receive = { throw SocketTimeoutException("udp timeout") })
        val tcp = harness.tcp("tcp", response = RESPONSE)

        val result = harness.resolve(listOf(server))

        assertAnswer(result, ResolverTransport.DEVICE_TCP)
        assertEquals(listOf(server), tcp.connectedServers)
        assertEquals(listOf(53), tcp.connectedPorts)
        assertArrayEquals(frame(QUERY), tcp.frames.single())
        assertEquals(
            listOf(
                "udp.create",
                "udp.register",
                "udp.protect",
                "udp.bind",
                "udp.send",
                "udp.receive",
                "udp.unregister",
                "udp.close",
                "tcp.create",
                "tcp.register",
                "tcp.protect",
                "tcp.bind",
                "tcp.connect",
                "tcp.write",
                "tcp.readLength",
                "tcp.readBody",
                "tcp.unregister",
                "tcp.close",
            ),
            harness.events,
        )
        assertEquals(1, tcp.closeCount)
        assertEquals(0, harness.registry.registeredCount)
    }

    @Test
    fun `protect and bind failures stay typed perform no IO and close once`() {
        listOf(
            SetupCase("protect false", ResolverFailureReason.PROTECT_FAILED) {
                it.protectResult = false
            },
            SetupCase("protect throw", ResolverFailureReason.PROTECT_FAILED) {
                it.protectFailure = IOException("protect")
            },
            SetupCase("bind throw", ResolverFailureReason.BIND_FAILED) {
                it.bindFailure = IOException("bind")
            },
        ).forEach { case ->
            val udpHarness = Harness()
            val udp = udpHarness.udp("udp", receive = { datagram(address("192.0.2.1")) })
            case.configure(udp)
            assertFailure(udpHarness.resolve(listOf(address("192.0.2.1"))), case.reason, case.name)
            assertEquals(case.name, 0, udp.sendCount)
            assertEquals(case.name, 0, udp.receiveCount)
            assertEquals(case.name, 1, udp.closeCount)
            assertEquals(case.name, 0, udpHarness.registry.registeredCount)

            val tcpHarness = Harness()
            tcpHarness.udp("udp", receive = { throw SocketTimeoutException() })
            val tcp = tcpHarness.tcp("tcp", response = RESPONSE)
            case.configure(tcp)
            assertFailure(tcpHarness.resolve(listOf(address("192.0.2.2"))), case.reason, case.name)
            assertEquals(case.name, 0, tcp.connectCount)
            assertEquals(case.name, 1, tcp.closeCount)
            assertEquals(case.name, 0, tcpHarness.registry.registeredCount)
        }
    }

    @Test
    fun `UDP source validation accepts IPv4 IPv6 and the internal port seam only`() {
        val ipv4 = address("192.0.2.53")
        val ipv4Harness = Harness(dnsPort = 5_300)
        val ipv4Udp = ipv4Harness.udp(
            "udp4",
            receives = listOf(
                { datagram(address("192.0.2.54"), 5_300) },
                { datagram(ipv4, 53) },
                { datagram(ipv4, 5_300) },
            ),
        )
        assertAnswer(ipv4Harness.resolve(listOf(ipv4)), ResolverTransport.DEVICE_UDP)
        assertEquals(3, ipv4Udp.receiveCount)

        val ipv6 = address("2001:db8::53")
        val ipv6Harness = Harness(dnsPort = 5_301)
        ipv6Harness.udp("udp6", receive = { datagram(ipv6, 5_301) })
        assertAnswer(ipv6Harness.resolve(listOf(ipv6)), ResolverTransport.DEVICE_UDP)
    }

    @Test
    fun `exactly eight rejected datagrams returns mismatch limit without TCP`() {
        val harness = Harness(dnsPort = 5_300)
        val server = address("192.0.2.53")
        val wrongId = RESPONSE.copyOf().also { it[1] = 0x35 }
        val oversized = RESPONSE + ByteArray(4_097 - RESPONSE.size)
        val udp = harness.udp(
            "udp",
            receives = listOf(
                { datagram(address("192.0.2.54"), 5_300) },
                { datagram(server, 53) },
                { datagram(server, 5_300, wrongId) },
                { datagram(server, 5_300, ByteArray(11)) },
                { datagram(server, 5_300, oversized) },
                { datagram(address("2001:db8::53"), 5_300) },
                { datagram(server, 5_300, wrongId) },
                { datagram(server, 5_300, ByteArray(11)) },
            ),
        )

        assertFailure(
            harness.resolve(listOf(server)),
            ResolverFailureReason.MISMATCH_LIMIT,
        )
        assertEquals(8, udp.receiveCount)
        assertEquals(0, harness.platform.tcpCreateCount)
    }

    @Test
    fun `only UDP receive timeout and validator accepted truncation enter TCP`() {
        listOf<(Harness, InetAddress) -> FakeUdpSocket>(
            { harness, _ ->
                harness.udp("udp", receive = { throw SocketTimeoutException("receive") })
            },
            { harness, server ->
                harness.udp(
                    "udp",
                    receive = {
                        datagram(
                            server,
                            body = RESPONSE.copyOf().also {
                                it[2] = (it[2].toInt() or 0x02).toByte()
                            },
                        )
                    },
                )
            },
        ).forEachIndexed { index, udpFactory ->
            val harness = Harness()
            val server = address("192.0.2.${70 + index}")
            udpFactory(harness, server)
            harness.tcp("tcp", response = RESPONSE)
            assertAnswer(harness.resolve(listOf(server)), ResolverTransport.DEVICE_TCP)
            assertEquals(1, harness.platform.tcpCreateCount)
        }
    }

    @Test
    fun `excluded UDP failures never enter TCP`() {
        val cases = listOf(
            FailureCase("send timeout", ResolverFailureReason.TIMEOUT) { harness, server ->
                harness.udp("udp", send = { throw SocketTimeoutException("send") })
            },
            FailureCase("send failure", ResolverFailureReason.SEND_FAILED) { harness, server ->
                harness.udp("udp", send = { throw IOException("send") })
            },
            FailureCase("receive failure", ResolverFailureReason.RECEIVE_FAILED) { harness, server ->
                harness.udp("udp", receive = { throw IOException("receive") })
            },
            FailureCase("stale after send", ResolverFailureReason.STALE_EPOCH) { harness, server ->
                harness.udp("udp", send = { harness.registry.current = false })
            },
            FailureCase("mismatch cap", ResolverFailureReason.MISMATCH_LIMIT) { harness, server ->
                harness.udp(
                    "udp",
                    receives = List(8) { { datagram(address("192.0.2.254")) } },
                )
            },
            FailureCase("whole deadline", ResolverFailureReason.TIMEOUT) { harness, server ->
                harness.udp("udp", send = { harness.clock.advance(8_000_000_000L) })
            },
        )
        cases.forEachIndexed { index, case ->
            val harness = Harness()
            val server = address("192.0.2.${80 + index}")
            case.configure(harness, server)
            assertFailure(harness.resolve(listOf(server)), case.reason, case.name)
            assertEquals(case.name, 0, harness.platform.tcpCreateCount)
        }
    }

    @Test
    fun `TCP failure advances once and first later success terminates transport iteration`() {
        val harness = Harness()
        val first = address("192.0.2.91")
        val second = address("192.0.2.92")
        val never = address("192.0.2.93")
        val firstUdp = harness.udp("udp1", receive = { throw SocketTimeoutException() })
        harness.tcp("tcp1", response = RESPONSE).readLengthResult = null
        val secondUdp = harness.udp("udp2", receive = { datagram(second) })
        harness.udp("udp3", receive = { datagram(never) })

        assertAnswer(harness.resolve(listOf(first, second, never)), ResolverTransport.DEVICE_UDP)

        assertEquals(listOf(first), firstUdp.sentServers)
        assertEquals(listOf(second), secondUdp.sentServers)
        assertEquals(2, harness.platform.udpCreateCount)
        assertEquals(1, harness.platform.tcpCreateCount)
        assertFalse(harness.events.any { it.startsWith("udp3.") })
    }

    @Test
    fun `transport selects raw servers once and is the only owner of ordered iteration`() {
        val harness = Harness()
        val ipv4 = address("192.0.2.94")
        val ipv6 = address("2001:db8::94")
        val first = harness.udp("udp1", send = { throw IOException("advance") })
        val second = harness.udp("udp2", receive = { datagram(ipv6) })

        assertAnswer(
            harness.resolve(
                listOf(
                    address("0.0.0.0"),
                    ipv6,
                    ipv4,
                    InetAddress.getByAddress(ipv4.address.copyOf()),
                ),
            ),
            ResolverTransport.DEVICE_UDP,
        )

        assertEquals(listOf(ipv4), first.sentServers)
        assertEquals(listOf(ipv6), second.sentServers)
        assertEquals(2, harness.platform.udpCreateCount)
    }

    @Test
    fun `policy route keeps complete selection and suppresses only the failed address next query`() {
        val harness = Harness()
        val first = address("192.0.2.95")
        val second = address("2001:db8::95")
        val policy = ResolverEndpointBackoffPolicy(harness.clock)
        val firstAttempt = harness.udp("udp1", send = { throw IOException("first failed") })
        val secondAttempt = harness.udp("udp2", receive = { datagram(second) })
        val nextQuery = harness.udp("udp3", receive = { datagram(second) })

        assertAnswer(
            harness.resolveWithPolicy(
                listOf(
                    address("0.0.0.0"),
                    second,
                    first,
                    InetAddress.getByAddress(first.address.copyOf()),
                ),
                policy,
            ),
            ResolverTransport.DEVICE_UDP,
        )
        assertEquals(listOf(first), firstAttempt.sentServers)
        assertEquals(listOf(second), secondAttempt.sentServers)

        assertAnswer(
            harness.resolveWithPolicy(listOf(first, second), policy),
            ResolverTransport.DEVICE_UDP,
        )
        assertEquals(listOf(second), nextQuery.sentServers)
        assertEquals(3, harness.platform.udpCreateCount)
    }

    @Test
    fun `successful address completion clears backoff recorded during the attempt`() {
        val harness = Harness()
        val server = address("192.0.2.96")
        val policy = ResolverEndpointBackoffPolicy(harness.clock)
        harness.udp(
            "udp1",
            receive = {
                policy.recordFailure(
                    ResolverEndpointKey.DeviceAddress(server),
                    ResolverFailureReason.TIMEOUT,
                )
                datagram(server)
            },
        )
        val nextQuery = harness.udp("udp2", receive = { datagram(server) })

        assertAnswer(
            harness.resolveWithPolicy(listOf(server), policy),
            ResolverTransport.DEVICE_UDP,
        )
        assertAnswer(
            harness.resolveWithPolicy(listOf(server), policy),
            ResolverTransport.DEVICE_UDP,
        )
        assertEquals(listOf(server), nextQuery.sentServers)
        assertEquals(2, harness.platform.udpCreateCount)
    }

    @Test
    fun `exact backoff expiry restores eligibility`() {
        val harness = Harness()
        val server = address("192.0.2.97")
        val policy = ResolverEndpointBackoffPolicy(harness.clock)
        policy.recordFailure(
            ResolverEndpointKey.DeviceAddress(server),
            ResolverFailureReason.TIMEOUT,
        )

        assertFailure(
            harness.resolveWithPolicy(listOf(server), policy),
            ResolverFailureReason.BACKED_OFF,
        )
        assertEquals(0, harness.platform.udpCreateCount)

        harness.clock.advance(30_000_000_000L)
        harness.udp("udp", receive = { datagram(server) })
        assertAnswer(
            harness.resolveWithPolicy(listOf(server), policy),
            ResolverTransport.DEVICE_UDP,
        )
        assertEquals(1, harness.platform.udpCreateCount)
    }

    @Test
    fun `backoff expiry remains exact across monotonic clock wrap`() {
        val harness = Harness(startNanos = Long.MAX_VALUE - 15_000_000_000L)
        val server = address("192.0.2.98")
        val policy = ResolverEndpointBackoffPolicy(harness.clock)
        policy.recordFailure(
            ResolverEndpointKey.DeviceAddress(server),
            ResolverFailureReason.TIMEOUT,
        )

        harness.clock.advance(29_999_999_999L)
        assertFailure(
            harness.resolveWithPolicy(listOf(server), policy),
            ResolverFailureReason.BACKED_OFF,
        )
        assertEquals(0, harness.platform.udpCreateCount)

        harness.clock.advance(1L)
        harness.udp("udp", receive = { datagram(server) })
        assertAnswer(
            harness.resolveWithPolicy(listOf(server), policy),
            ResolverTransport.DEVICE_UDP,
        )
        assertEquals(1, harness.platform.udpCreateCount)
    }

    @Test
    fun `all suppressed addresses return backed off without creating a socket`() {
        val harness = Harness()
        val first = address("192.0.2.99")
        val second = address("2001:db8::99")
        val policy = ResolverEndpointBackoffPolicy(harness.clock)
        listOf(first, second).forEach { server ->
            policy.recordFailure(
                ResolverEndpointKey.DeviceAddress(server),
                ResolverFailureReason.TIMEOUT,
            )
        }

        assertFailure(
            harness.resolveWithPolicy(listOf(first, second), policy),
            ResolverFailureReason.BACKED_OFF,
        )
        assertEquals(0, harness.platform.udpCreateCount)
        assertEquals(0, harness.platform.tcpCreateCount)
    }

    @Test
    fun `suppressed addresses on either side preserve the real attempted failure`() {
        val attemptedThenSuppressed = Harness()
        val firstAttempted = address("192.0.2.110")
        val secondSuppressed = address("192.0.2.111")
        val firstPolicy = ResolverEndpointBackoffPolicy(attemptedThenSuppressed.clock)
        firstPolicy.recordFailure(
            ResolverEndpointKey.DeviceAddress(secondSuppressed),
            ResolverFailureReason.TIMEOUT,
        )
        attemptedThenSuppressed.udp("udp", send = { throw IOException("attempted") })
        assertFailure(
            attemptedThenSuppressed.resolveWithPolicy(
                listOf(firstAttempted, secondSuppressed),
                firstPolicy,
            ),
            ResolverFailureReason.SEND_FAILED,
        )
        assertEquals(1, attemptedThenSuppressed.platform.udpCreateCount)

        val suppressedThenAttempted = Harness()
        val firstSuppressed = address("192.0.2.112")
        val secondAttempted = address("192.0.2.113")
        val secondPolicy = ResolverEndpointBackoffPolicy(suppressedThenAttempted.clock)
        secondPolicy.recordFailure(
            ResolverEndpointKey.DeviceAddress(firstSuppressed),
            ResolverFailureReason.TIMEOUT,
        )
        suppressedThenAttempted.udp("udp", send = { throw IOException("attempted") })
        assertFailure(
            suppressedThenAttempted.resolveWithPolicy(
                listOf(firstSuppressed, secondAttempted),
                secondPolicy,
            ),
            ResolverFailureReason.SEND_FAILED,
        )
        assertEquals(1, suppressedThenAttempted.platform.udpCreateCount)
    }

    @Test
    fun `skipped address cannot reset the shared absolute device deadline`() {
        val harness = Harness()
        val suppressed = address("192.0.2.114")
        val attempted = address("192.0.2.115")
        val policy = ResolverEndpointBackoffPolicy(harness.clock)
        policy.recordFailure(
            ResolverEndpointKey.DeviceAddress(suppressed),
            ResolverFailureReason.TIMEOUT,
        )
        harness.clock.advance(7_500_000_000L)
        harness.clock.scriptedReads += 0L
        val socket = harness.udp("udp", send = { throw IOException("attempted") })

        assertFailure(
            harness.resolveWithPolicy(listOf(suppressed, attempted), policy),
            ResolverFailureReason.SEND_FAILED,
        )
        assertEquals(500, socket.sendTimeouts.single())
    }

    @Test
    fun `stale cancelled and overloaded outcomes never suppress a device address`() {
        listOf(
            ResolverFailureReason.STALE_EPOCH,
            ResolverFailureReason.CANCELLED,
            ResolverFailureReason.OVERLOADED,
        ).forEachIndexed { index, reason ->
            val harness = Harness()
            val server = address("192.0.2.${120 + index}")
            val policy = ResolverEndpointBackoffPolicy(harness.clock)
            policy.recordFailure(ResolverEndpointKey.DeviceAddress(server), reason)
            harness.udp("udp", receive = { datagram(server) })

            assertAnswer(
                harness.resolveWithPolicy(listOf(server), policy),
                ResolverTransport.DEVICE_UDP,
            )
            assertEquals(reason.name, 1, harness.platform.udpCreateCount)
        }

        val stale = Harness()
        val staleServer = address("192.0.2.123")
        val stalePolicy = ResolverEndpointBackoffPolicy(stale.clock)
        stale.udp("udp1", send = { stale.registry.current = false })
        assertFailure(
            stale.resolveWithPolicy(listOf(staleServer), stalePolicy),
            ResolverFailureReason.STALE_EPOCH,
        )
        stale.registry.current = true
        stale.udp("udp2", receive = { datagram(staleServer) })
        assertAnswer(
            stale.resolveWithPolicy(listOf(staleServer), stalePolicy),
            ResolverTransport.DEVICE_UDP,
        )

        val cancelled = Harness()
        val cancelledServer = address("192.0.2.124")
        val cancelledPolicy = ResolverEndpointBackoffPolicy(cancelled.clock)
        cancelled.udp("udp1", send = { throw CancellationException("cancelled") })
        var cancelledObserved = false
        try {
            cancelled.resolveWithPolicy(listOf(cancelledServer), cancelledPolicy)
        } catch (_: CancellationException) {
            cancelledObserved = true
        }
        assertTrue(cancelledObserved)
        cancelled.udp("udp2", receive = { datagram(cancelledServer) })
        assertAnswer(
            cancelled.resolveWithPolicy(listOf(cancelledServer), cancelledPolicy),
            ResolverTransport.DEVICE_UDP,
        )
    }

    @Test
    fun `policy retirement racing with completion makes the late failure inert`() {
        val harness = Harness()
        val server = address("192.0.2.125")
        val policy = ResolverEndpointBackoffPolicy(harness.clock)
        harness.udp(
            "udp1",
            receive = {
                policy.retire()
                throw IOException("late receive failure")
            },
        )

        assertFailure(
            harness.resolveWithPolicy(listOf(server), policy),
            ResolverFailureReason.RECEIVE_FAILED,
        )

        harness.udp("udp2", receive = { datagram(server) })
        assertAnswer(
            harness.resolveWithPolicy(listOf(server), policy),
            ResolverTransport.DEVICE_UDP,
        )
        assertEquals(2, harness.platform.udpCreateCount)
    }

    @Test
    fun `normal resolver entry remains a no-policy device transport`() {
        val harness = Harness()
        val server = address("192.0.2.126")
        val policy = ResolverEndpointBackoffPolicy(harness.clock)
        policy.recordFailure(
            ResolverEndpointKey.DeviceAddress(server),
            ResolverFailureReason.TIMEOUT,
        )

        assertFailure(
            harness.resolveWithPolicy(listOf(server), policy),
            ResolverFailureReason.BACKED_OFF,
        )
        harness.udp("udp", receive = { datagram(server) })
        assertAnswer(harness.resolve(listOf(server)), ResolverTransport.DEVICE_UDP)
        assertEquals(1, harness.platform.udpCreateCount)
    }

    @Test
    fun `TCP stage and frame failures map exactly`() {
        val unrelated = RESPONSE.copyOf().also { it[1] = 0x35 }
        val truncated = RESPONSE.copyOf().also {
            it[2] = (it[2].toInt() or 0x02).toByte()
        }
        val cases = listOf(
            TcpCase("connect timeout", ResolverFailureReason.TIMEOUT) {
                it.connectAction = { throw SocketTimeoutException() }
            },
            TcpCase("connect error", ResolverFailureReason.SOCKET_FAILED) {
                it.connectAction = { throw IOException() }
            },
            TcpCase("write timeout", ResolverFailureReason.TIMEOUT) {
                it.writeAction = { throw SocketTimeoutException() }
            },
            TcpCase("write error", ResolverFailureReason.SEND_FAILED) {
                it.writeAction = { throw IOException() }
            },
            TcpCase("prefix timeout", ResolverFailureReason.TIMEOUT) {
                it.readLengthAction = { throw SocketTimeoutException() }
            },
            TcpCase("prefix error", ResolverFailureReason.RECEIVE_FAILED) {
                it.readLengthAction = { throw IOException() }
            },
            TcpCase("prefix EOF", ResolverFailureReason.RECEIVE_FAILED) {
                it.readLengthResult = null
            },
            TcpCase("zero", ResolverFailureReason.INVALID_RESPONSE) {
                it.readLengthResult = 0
            },
            TcpCase("oversized", ResolverFailureReason.OVERSIZED_RESPONSE) {
                it.readLengthResult = 4_097
            },
            TcpCase("body timeout", ResolverFailureReason.TIMEOUT) {
                it.readBodyAction = { throw SocketTimeoutException() }
            },
            TcpCase("body error", ResolverFailureReason.RECEIVE_FAILED) {
                it.readBodyAction = { throw IOException() }
            },
            TcpCase("body EOF", ResolverFailureReason.RECEIVE_FAILED) {
                it.readBodyResult = null
            },
            TcpCase("partial body", ResolverFailureReason.RECEIVE_FAILED) {
                it.readBodyResult = RESPONSE.copyOf(RESPONSE.size - 1)
            },
            // A wrong transaction/question is terminal for the selected resolver ladder.
            TcpCase("unrelated response", ResolverFailureReason.MISMATCH_LIMIT) {
                it.response(unrelated)
            },
            TcpCase("malformed response", ResolverFailureReason.INVALID_RESPONSE) {
                it.response(ByteArray(11))
            },
            TcpCase("TCP truncation", ResolverFailureReason.INVALID_RESPONSE) {
                it.response(truncated)
            },
        )
        cases.forEachIndexed { index, case ->
            val harness = Harness()
            val server = address("198.51.100.${index + 1}")
            harness.udp("udp", receive = { throw SocketTimeoutException() })
            val tcp = harness.tcp("tcp", response = RESPONSE)
            case.configure(tcp)
            assertFailure(harness.resolve(listOf(server)), case.reason, case.name)
            assertEquals(case.name, 1, tcp.closeCount)
            assertEquals(case.name, 0, harness.registry.registeredCount)
        }
    }

    @Test
    fun `attempt and whole budgets are clipped shared and wrap safe`() {
        val mismatchHarness = Harness()
        val server = address("192.0.2.101")
        val udp = mismatchHarness.udp(
            "udp",
            send = { mismatchHarness.clock.advance(400_000_000L) },
            receives = listOf(
                {
                    mismatchHarness.clock.advance(300_000_000L)
                    datagram(address("192.0.2.254"))
                },
                { throw SocketTimeoutException() },
            ),
        )
        val tcp = mismatchHarness.tcp("tcp", response = RESPONSE)
        assertAnswer(mismatchHarness.resolve(listOf(server)), ResolverTransport.DEVICE_TCP)
        assertEquals(listOf(1_000), udp.sendTimeouts)
        assertEquals(listOf(600, 300), udp.receiveTimeouts)
        assertEquals(2_000, tcp.connectTimeouts.single())

        val sharedHarness = Harness()
        val first = address("192.0.2.102")
        val second = address("192.0.2.103")
        sharedHarness.udp(
            "udp1",
            send = {
                sharedHarness.clock.advance(7_500_000_000L)
                throw IOException()
            },
        )
        val clipped = sharedHarness.udp("udp2", send = { throw IOException() })
        assertFailure(
            sharedHarness.resolve(listOf(first, second)),
            ResolverFailureReason.SEND_FAILED,
        )
        assertEquals(500, clipped.sendTimeouts.single())

        val wrapHarness = Harness(startNanos = Long.MAX_VALUE - 500_000_000L)
        val wrapServer = address("192.0.2.104")
        val wrapUdp = wrapHarness.udp("udp", receive = { datagram(wrapServer) })
        assertAnswer(wrapHarness.resolve(listOf(wrapServer)), ResolverTransport.DEVICE_UDP)
        assertEquals(1_000, wrapUdp.sendTimeouts.single())

        val singleSnapshot = Harness()
        singleSnapshot.clock.scriptedReads.addAll(
            listOf(
                0L,
                0L,
                100_000_000L,
                100_000_000L,
                100_000_000L,
                100_000_000L,
                100_000_000L,
            ),
        )
        val snapshotUdp = singleSnapshot.udp("udp", send = { throw IOException() })
        assertFailure(
            singleSnapshot.resolve(
                listOf(address("192.0.2.105")),
                absoluteDeadlineNanos = 500_000_000L,
            ),
            ResolverFailureReason.SEND_FAILED,
        )
        assertEquals(400, snapshotUdp.sendTimeouts.single())
    }

    @Test
    fun `watchdog closes blocked IO and UDP receive attempt expiry still enters TCP`() {
        listOf("udp-send", "tcp-connect", "tcp-write", "tcp-body").forEach { stage ->
            val harness = Harness()
            val server = address("203.0.113.${stage.length}")
            val udp = harness.udp(
                "udp",
                receive = { throw SocketTimeoutException() },
            )
            val tcp = harness.tcp("tcp", response = RESPONSE)
            when (stage) {
                "udp-send" -> udp.sendAction = {
                    harness.watchdog.fireLatest()
                    assertEquals(1, udp.closeCount)
                    throw IOException("closed")
                }
                "tcp-connect" -> tcp.connectAction = {
                    harness.watchdog.fireLatest()
                    assertEquals(1, tcp.closeCount)
                    throw IOException("closed")
                }
                "tcp-write" -> tcp.writeAction = {
                    harness.watchdog.fireLatest()
                    assertEquals(1, tcp.closeCount)
                    throw IOException("closed")
                }
                "tcp-body" -> tcp.readBodyAction = {
                    harness.watchdog.fireLatest()
                    assertEquals(1, tcp.closeCount)
                    throw IOException("closed")
                }
            }
            assertFailure(harness.resolve(listOf(server)), ResolverFailureReason.TIMEOUT, stage)
            assertEquals(stage, 1, udp.closeCount)
            assertEquals(stage, if (stage == "udp-send") 0 else 1, tcp.closeCount)
        }

        val receiveHarness = Harness()
        val server = address("203.0.113.53")
        val receiveSocket = receiveHarness.udp(
            "udp",
        )
        receiveSocket.receives += {
            receiveHarness.watchdog.fireLatest()
            assertEquals(1, receiveSocket.closeCount)
            throw IOException("watchdog closed receive")
        }
        receiveHarness.tcp("tcp", response = RESPONSE)
        assertAnswer(receiveHarness.resolve(listOf(server)), ResolverTransport.DEVICE_TCP)
        assertTrue(receiveHarness.clock.nowNanos() < 8_000_000_000L)
    }

    @Test
    fun `dequeued watchdog callback cannot expire IO after completion wins disarm`() {
        val events = mutableListOf<String>()
        val server = address("203.0.113.54")
        val watchdog = DequeuedCallbackOnCloseWatchdog(raceArm = 2)
        val registry = FakeRegistry(events)
        val platform = FakePlatform(events).apply {
            onCreated = registry::noteCreated
        }
        val udp = FakeUdpSocket("udp", events).apply {
            receives += {
                assertTrue(watchdog.callbackDequeued)
                datagram(server)
            }
        }
        platform.udpSockets += udp
        val resolver = DeviceDnsTransport(
            platform = platform,
            registry = registry,
            clock = FakeClock(0L),
            watchdog = watchdog,
        )
        val request = ResolverRequest(
            query = QUERY,
            question = DnsMessage.parseQuestion(QUERY),
            epoch = epoch(),
            networkContext = NetworkContext(network(43), 1L, listOf(server), isCellular = false),
        )

        val result = runBlocking { resolver.resolve(request) }

        assertAnswer(result, ResolverTransport.DEVICE_UDP)
        assertEquals(
            listOf(
                "udp.create",
                "udp.register",
                "udp.protect",
                "udp.bind",
                "udp.send",
                "udp.receive",
                "udp.unregister",
                "udp.close",
            ),
            events,
        )
        assertTrue(watchdog.callbackRan)
        assertEquals(1, udp.closeCount)
        assertEquals(0, registry.registeredCount)
    }

    @Test
    fun `registry advancement and successor admission wait for watchdog physical close`() {
        val events = mutableListOf<String>()
        val oldEpoch = epoch()
        val nextEpoch = oldEpoch.copy(engineGeneration = 2L)
        val registry = GenerationSocketRegistry(oldEpoch)
        val watchdog = FakeWatchdog()
        val platform = FakePlatform(events)
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val resolverDone = CountDownLatch(1)
        val advanceDone = CountDownLatch(1)
        val successorDone = CountDownLatch(1)
        val resolverResult = AtomicReference<ResolverResult?>()
        val workerFailure = AtomicReference<Throwable?>()
        val successorAccepted = AtomicBoolean(false)
        val advanceRestoredInterrupt = AtomicBoolean(false)
        val successor = CountingResource()
        val socket = FakeUdpSocket("udp", events).apply {
            sendAction = {
                sendStarted.countDown()
                releaseSend.await()
                throw IOException("close-unblocked send")
            }
            closeAction = {
                closeStarted.countDown()
                releaseClose.await()
            }
        }
        platform.udpSockets += socket
        val resolver = DeviceDnsTransport(
            platform = platform,
            registry = registry,
            clock = FakeClock(0L),
            watchdog = watchdog,
        )
        val request = ResolverRequest(
            query = QUERY,
            question = DnsMessage.parseQuestion(QUERY),
            epoch = oldEpoch,
            networkContext = NetworkContext(
                network(45),
                1L,
                listOf(address("203.0.113.55")),
                isCellular = false,
            ),
        )
        val resolverThread = Thread {
            try {
                resolverResult.set(runBlocking { resolver.resolve(request) })
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            } finally {
                resolverDone.countDown()
            }
        }
        val watchdogThread = Thread {
            try {
                watchdog.fireLatest()
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            }
        }
        val advanceThread = Thread {
            try {
                registry.advanceTo(nextEpoch)
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            } finally {
                advanceRestoredInterrupt.set(Thread.currentThread().isInterrupted)
                advanceDone.countDown()
            }
        }
        val successorThread = Thread {
            try {
                successorAccepted.set(registry.registerIfCurrent(successor, nextEpoch))
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            } finally {
                successorDone.countDown()
            }
        }
        try {
            resolverThread.start()
            assertTrue(sendStarted.await(2, TimeUnit.SECONDS))
            watchdogThread.start()
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            advanceThread.start()
            assertTrue(awaitCondition { !registry.isCurrent(oldEpoch) })
            successorThread.start()

            assertFalse(advanceDone.await(100, TimeUnit.MILLISECONDS))
            assertFalse(successorDone.await(100, TimeUnit.MILLISECONDS))
            advanceThread.interrupt()
            assertFalse(advanceDone.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseClose.countDown()
            releaseSend.countDown()
            resolverThread.join(2_000)
            watchdogThread.join(2_000)
            advanceThread.join(2_000)
            successorThread.join(2_000)
            listOf(resolverThread, watchdogThread, advanceThread, successorThread)
                .filter(Thread::isAlive)
                .forEach(Thread::interrupt)
            resolverThread.join(2_000)
            watchdogThread.join(2_000)
            advanceThread.join(2_000)
            successorThread.join(2_000)
            registry.close()
        }
        assertFalse(resolverThread.isAlive)
        assertFalse(watchdogThread.isAlive)
        assertFalse(advanceThread.isAlive)
        assertFalse(successorThread.isAlive)
        assertTrue(resolverDone.await(0, TimeUnit.MILLISECONDS))
        assertTrue(advanceDone.await(0, TimeUnit.MILLISECONDS))
        assertTrue(successorDone.await(0, TimeUnit.MILLISECONDS))
        assertEquals(null, workerFailure.get())
        assertTrue(successorAccepted.get())
        assertTrue(advanceRestoredInterrupt.get())
        assertFailure(
            checkNotNull(resolverResult.get()),
            ResolverFailureReason.STALE_EPOCH,
        )
        assertEquals(1, socket.closeCount)
        assertEquals(1, successor.closeCount)
        assertEquals(0, registry.sizeForTesting)
    }

    @Test
    fun `terminal answers cannot cross watchdog whole deadline or epoch invalidation`() {
        val udpWatchdog = Harness()
        val server = address("203.0.113.61")
        udpWatchdog.udp(
            "udp",
            receive = {
                udpWatchdog.watchdog.fireLatest()
                datagram(server)
            },
        )
        udpWatchdog.tcp("tcp", response = ByteArray(11))
        assertFailure(
            udpWatchdog.resolve(listOf(server)),
            ResolverFailureReason.TIMEOUT,
        )
        assertEquals(0, udpWatchdog.platform.tcpCreateCount)

        val tcpWatchdog = Harness()
        tcpWatchdog.udp("udp", receive = { throw SocketTimeoutException() })
        tcpWatchdog.tcp("tcp", response = RESPONSE).readBodyAction = {
            tcpWatchdog.watchdog.fireLatest()
        }
        assertFailure(
            tcpWatchdog.resolve(listOf(server)),
            ResolverFailureReason.TIMEOUT,
        )

        val stale = Harness()
        stale.udp(
            "udp",
            receive = {
                stale.registry.current = false
                datagram(server)
            },
        )
        assertFailure(stale.resolve(listOf(server)), ResolverFailureReason.STALE_EPOCH)

        val staleTcp = Harness()
        staleTcp.udp("udp", receive = { throw SocketTimeoutException() })
        staleTcp.tcp("tcp", response = RESPONSE).readBodyAction = {
            staleTcp.registry.current = false
        }
        assertFailure(staleTcp.resolve(listOf(server)), ResolverFailureReason.STALE_EPOCH)

        val whole = Harness()
        whole.udp(
            "udp",
            receive = {
                whole.registry.current = false
                whole.clock.advance(8_000_000_000L)
                datagram(server)
            },
        )
        assertFailure(whole.resolve(listOf(server)), ResolverFailureReason.TIMEOUT)
    }

    @Test
    fun `UDP answer cannot cross epoch retirement during physical close`() {
        val events = mutableListOf<String>()
        val oldEpoch = epoch()
        val nextEpoch = oldEpoch.copy(engineGeneration = 2L)
        val registry = GenerationSocketRegistry(oldEpoch)
        val platform = FakePlatform(events)
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val result = AtomicReference<ResolverResult?>()
        val failure = AtomicReference<Throwable?>()
        val socket = FakeUdpSocket("udp", events).apply {
            receives += { datagram(address("203.0.113.62")) }
            closeAction = {
                closeStarted.countDown()
                releaseClose.await()
            }
        }
        platform.udpSockets += socket
        val resolver = DeviceDnsTransport(
            platform = platform,
            registry = registry,
            clock = FakeClock(0L),
            watchdog = FakeWatchdog(),
        )
        val request = ResolverRequest(
            query = QUERY,
            question = DnsMessage.parseQuestion(QUERY),
            epoch = oldEpoch,
            networkContext = NetworkContext(
                network(46),
                oldEpoch.networkGeneration,
                listOf(address("203.0.113.62")),
                isCellular = false,
            ),
        )
        val worker = Thread {
            try {
                result.set(runBlocking { resolver.resolve(request) })
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            worker.start()
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            registry.advanceTo(nextEpoch)
            assertTrue(registry.isCurrent(nextEpoch))
        } finally {
            releaseClose.countDown()
            worker.join(2_000)
            if (worker.isAlive) worker.interrupt()
            worker.join(2_000)
            registry.close()
        }

        assertFalse(worker.isAlive)
        assertEquals(null, failure.get())
        assertFailure(checkNotNull(result.get()), ResolverFailureReason.STALE_EPOCH)
        assertEquals(1, socket.closeCount)
    }

    @Test
    fun `TCP answer cannot cross whole deadline during physical close`() {
        val harness = Harness()
        val server = address("203.0.113.63")
        harness.udp("udp", receive = { throw SocketTimeoutException() })
        val tcp = harness.tcp("tcp", response = RESPONSE).apply {
            closeAction = { harness.clock.advance(8_000_000_000L) }
        }

        assertFailure(
            harness.resolve(
                servers = listOf(server),
                absoluteDeadlineNanos = 8_000_000_000L,
            ),
            ResolverFailureReason.TIMEOUT,
        )
        assertEquals(1, tcp.closeCount)
    }

    @Test
    fun `cancelling blocked UDP send closes its resource and completes promptly`() = runBlocking {
        val harness = Harness()
        val server = address("203.0.113.64")
        val nextServer = address("203.0.113.164")
        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val socket = harness.udp(
            "udp",
            send = {
                sendStarted.countDown()
                releaseSend.await()
                throw IOException("socket closed")
            },
        ).apply {
            closeAction = {
                closeStarted.countDown()
                releaseSend.countDown()
            }
        }
        val unusedNextSocket = harness.udp("udp-next")
        val task = async(Dispatchers.Default) {
            harness.resolveSuspending(listOf(server, nextServer))
        }

        try {
            assertTrue(sendStarted.await(2, TimeUnit.SECONDS))
            task.cancel()
            assertTrue(
                "cancellation did not close the blocked UDP socket",
                closeStarted.await(2, TimeUnit.SECONDS),
            )
            withTimeout(2_000) { task.join() }
        } finally {
            releaseSend.countDown()
            task.cancel()
            withTimeout(2_000) { task.join() }
        }

        assertTrue(task.isCancelled)
        assertEquals(1, socket.closeCount)
        assertEquals(1, harness.platform.udpCreateCount)
        assertEquals(0, harness.platform.tcpCreateCount)
        assertEquals(0, unusedNextSocket.closeCount)
        assertEquals(0, harness.registry.registeredCount)
    }

    @Test
    fun `cancelling blocked TCP body read closes its resource and completes promptly`() =
        runBlocking {
            val harness = Harness()
            val server = address("203.0.113.65")
            val nextServer = address("203.0.113.165")
            harness.udp("udp", receive = { throw SocketTimeoutException() })
            val unusedNextSocket = harness.udp("udp-next")
            val readStarted = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val closeStarted = CountDownLatch(1)
            val socket = harness.tcp("tcp", response = RESPONSE).apply {
                readBodyAction = {
                    readStarted.countDown()
                    releaseRead.await()
                    throw IOException("socket closed")
                }
                closeAction = {
                    closeStarted.countDown()
                    releaseRead.countDown()
                }
            }
            val task = async(Dispatchers.Default) {
                harness.resolveSuspending(listOf(server, nextServer))
            }

            try {
                assertTrue(readStarted.await(2, TimeUnit.SECONDS))
                task.cancel()
                assertTrue(
                    "cancellation did not close the blocked TCP socket",
                    closeStarted.await(2, TimeUnit.SECONDS),
                )
                withTimeout(2_000) { task.join() }
            } finally {
                releaseRead.countDown()
                task.cancel()
                withTimeout(2_000) { task.join() }
            }

            assertTrue(task.isCancelled)
            assertEquals(1, socket.closeCount)
            assertEquals(1, harness.platform.udpCreateCount)
            assertEquals(1, harness.platform.tcpCreateCount)
            assertEquals(0, unusedNextSocket.closeCount)
            assertEquals(0, harness.registry.registeredCount)
        }

    @Test
    fun `blocking socket IO is dispatched away from the caller`() {
        val harness = Harness()
        val server = address("203.0.113.66")
        val callerThread = AtomicReference<Thread>()
        val sendThread = AtomicReference<Thread>()
        val receiveThread = AtomicReference<Thread>()
        harness.udp(
            "udp",
            send = { sendThread.set(Thread.currentThread()) },
            receive = {
                receiveThread.set(Thread.currentThread())
                datagram(server)
            },
        )
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "device-dns-caller")
        }.asCoroutineDispatcher()

        val result = try {
            runBlocking {
                withContext(callerDispatcher) {
                    callerThread.set(Thread.currentThread())
                    harness.resolveSuspending(listOf(server))
                }
            }
        } finally {
            callerDispatcher.close()
        }

        assertAnswer(result, ResolverTransport.DEVICE_UDP)
        assertFalse(callerThread.get() === sendThread.get())
        assertFalse(callerThread.get() === receiveThread.get())
    }

    @Test
    fun `queued dispatcher rechecks epoch before socket IO`() {
        val socketIoDispatcher = QueuedDispatcher()
        val harness = Harness(socketIoDispatcher = socketIoDispatcher)
        val server = address("203.0.113.67")
        val udp = harness.udp("udp", receive = { datagram(server) })

        val result = runBlocking {
            val pending = async(Dispatchers.Default) {
                harness.resolveSuspending(listOf(server))
            }
            assertTrue(socketIoDispatcher.queued.await(2, TimeUnit.SECONDS))
            harness.registry.current = false
            socketIoDispatcher.runNext()
            pending.await()
        }

        assertFailure(result, ResolverFailureReason.STALE_EPOCH)
        assertEquals(0, udp.sendCount)
    }

    @Test
    fun `full epoch is gated immediately before every UDP and TCP IO`() {
        val stages =
            listOf("udp-send", "udp-receive", "tcp-connect", "tcp-write", "tcp-prefix", "tcp-body")
        stages.forEachIndexed { index, stage ->
            val harness = Harness()
            val server = address("198.51.100.${100 + index}")
            val udp = harness.udp(
                "udp",
                send = {
                    if (stage == "udp-receive") harness.registry.current = false
                },
                receive = {
                    if (stage == "tcp-connect") harness.registry.current = false
                    throw SocketTimeoutException()
                },
            )
            val tcp = harness.tcp("tcp", response = RESPONSE)
            if (stage == "udp-send") {
                harness.registry.invalidateOnCheck = 1
            }
            if (stage == "tcp-write") {
                tcp.connectAction = { harness.registry.current = false }
            }
            if (stage == "tcp-prefix") {
                tcp.writeAction = { harness.registry.current = false }
            }
            if (stage == "tcp-body") {
                tcp.readLengthAction = { harness.registry.current = false }
            }

            assertFailure(harness.resolve(listOf(server)), ResolverFailureReason.STALE_EPOCH, stage)
            when (stage) {
                "udp-send" -> assertEquals(stage, 0, udp.sendCount)
                "udp-receive" -> assertEquals(stage, 0, udp.receiveCount)
                "tcp-connect" -> assertEquals(stage, 0, tcp.connectCount)
                "tcp-write" -> assertEquals(stage, 0, tcp.writeCount)
                "tcp-prefix" -> assertEquals(stage, 0, tcp.readLengthCount)
                "tcp-body" -> assertEquals(stage, 0, tcp.readBodyCount)
            }
        }
    }

    @Test
    fun `registration cleanup registry close and races converge on one physical close`() {
        listOf(RegisterMode.REJECT, RegisterMode.THROW).forEach { mode ->
            val harness = Harness()
            harness.registry.registerModes += mode
            val udp = harness.udp("udp", receive = { datagram(address("192.0.2.1")) })
            val expected = if (mode == RegisterMode.REJECT) {
                ResolverFailureReason.STALE_EPOCH
            } else {
                ResolverFailureReason.SOCKET_FAILED
            }
            assertFailure(harness.resolve(listOf(address("192.0.2.1"))), expected)
            assertEquals(1, udp.closeCount)
            assertEquals(0, harness.registry.registeredCount)
        }

        listOf(UnregisterMode.FALSE, UnregisterMode.THROW).forEach { mode ->
            val harness = Harness()
            harness.registry.unregisterMode = mode
            val server = address("192.0.2.2")
            val udp = harness.udp("udp", receive = { datagram(server) })
            assertAnswer(harness.resolve(listOf(server)), ResolverTransport.DEVICE_UDP)
            assertEquals(1, udp.closeCount)
            assertEquals(0, harness.registry.registeredCount)
        }

        val closedRegistry = Harness()
        val server = address("192.0.2.3")
        val closedSocket = closedRegistry.udp(
            "udp",
            send = { closedRegistry.registry.closeRegistered() },
        )
        assertFailure(closedRegistry.resolve(listOf(server)), ResolverFailureReason.STALE_EPOCH)
        assertEquals(1, closedSocket.closeCount)
        assertEquals(0, closedRegistry.registry.registeredCount)

        val race = Harness()
        val racedSocket = race.udp(
            "udp",
            send = {
                race.registry.current = false
                race.watchdog.fireLatest()
                throw IOException("closed")
            },
        )
        assertFailure(race.resolve(listOf(server)), ResolverFailureReason.STALE_EPOCH)
        assertEquals(1, racedSocket.closeCount)
        assertEquals(0, race.registry.registeredCount)

        val actualEpoch = epoch()
        val actualRegistry = GenerationSocketRegistry(actualEpoch)
        val actualEvents = mutableListOf<String>()
        val actualPlatform = FakePlatform(actualEvents)
        val actualClock = FakeClock(0L)
        val actualWatchdog = FakeWatchdog()
        val actualSocket = FakeUdpSocket("udp", actualEvents)
        val nextEpoch = actualEpoch.copy(engineGeneration = 2L)
        actualSocket.sendAction = {
            val start = CountDownLatch(1)
            val watchdogThread = Thread {
                start.await()
                actualWatchdog.fireLatest()
            }
            val invalidationThread = Thread {
                start.await()
                actualRegistry.advanceTo(nextEpoch)
            }
            watchdogThread.start()
            invalidationThread.start()
            start.countDown()
            watchdogThread.join()
            invalidationThread.join()
            throw IOException("close-unblocked send")
        }
        actualPlatform.udpSockets += actualSocket
        val actualResolver = DeviceDnsTransport(
            platform = actualPlatform,
            registry = actualRegistry,
            clock = actualClock,
            watchdog = actualWatchdog,
        )
        val actualRequest = ResolverRequest(
            query = QUERY,
            question = DnsMessage.parseQuestion(QUERY),
            epoch = actualEpoch,
            networkContext = NetworkContext(
                network(44),
                1L,
                listOf(server),
                isCellular = false,
            ),
        )
        val actualResult = runBlocking { actualResolver.resolve(actualRequest) }
        assertFailure(actualResult, ResolverFailureReason.STALE_EPOCH)
        assertEquals(1, actualSocket.closeCount)
        assertEquals(0, actualRegistry.sizeForTesting)
    }

    @Test
    fun `empty input creation registration watchdog and final failure rules are exact`() {
        val empty = Harness()
        assertFailure(empty.resolve(emptyList()), ResolverFailureReason.INVALID_RESPONSE)
        assertEquals(0, empty.platform.udpCreateCount)

        val create = Harness()
        create.platform.udpCreateFailure = IOException("create")
        assertFailure(
            create.resolve(listOf(address("192.0.2.10"))),
            ResolverFailureReason.SOCKET_FAILED,
        )

        val arm = Harness()
        arm.watchdog.throwOnArm = 1
        val armSocket = arm.udp("udp", receive = { datagram(address("192.0.2.11")) })
        assertFailure(
            arm.resolve(listOf(address("192.0.2.11"))),
            ResolverFailureReason.SOCKET_FAILED,
        )
        assertEquals(0, armSocket.sendCount)
        assertEquals(1, armSocket.closeCount)

        val last = Harness()
        val first = last.udp("udp1", receive = { datagram(address("192.0.2.12")) })
        first.protectResult = false
        val second = last.udp("udp2", receive = { datagram(address("192.0.2.13")) })
        second.bindFailure = IOException("bind")
        assertFailure(
            last.resolve(listOf(address("192.0.2.12"), address("192.0.2.13"))),
            ResolverFailureReason.BIND_FAILED,
        )

        val postLoop = Harness()
        val postServer = address("192.0.2.14")
        postLoop.udp(
            "udp",
            send = {
                postLoop.clock.advance(8_000_000_000L)
                throw IOException("send")
            },
        )
        assertFailure(postLoop.resolve(listOf(postServer)), ResolverFailureReason.TIMEOUT)
    }

    private class Harness(
        startNanos: Long = 0L,
        val dnsPort: Int = 53,
        socketIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
        fixedDnsServers: List<InetAddress>? = null,
    ) {
        val events = mutableListOf<String>()
        val clock = FakeClock(startNanos)
        val watchdog = FakeWatchdog()
        val registry = FakeRegistry(events)
        val platform = FakePlatform(events)
        val network: Network = network(42)
        val epoch: EngineEpoch = epoch()
        private val resolver = DeviceDnsTransport(
            platform = platform,
            registry = registry,
            clock = clock,
            watchdog = watchdog,
            socketIoDispatcher = socketIoDispatcher,
            dnsPort = dnsPort,
            fixedDnsServers = fixedDnsServers,
            fixedResolverIdentity = fixedDnsServers?.let { PrimaryResolverIdentity("controlled-fixed-plain") },
        )

        init {
            platform.onCreated = registry::noteCreated
        }

        fun udp(
            name: String,
            send: () -> Unit = {},
            receive: (() -> DatagramReceive)? = null,
            receives: List<() -> DatagramReceive> = emptyList(),
        ): FakeUdpSocket {
            val socket = FakeUdpSocket(name, events)
            socket.sendAction = send
            if (receive != null) socket.receives += receive
            socket.receives.addAll(receives)
            platform.udpSockets += socket
            return socket
        }

        fun tcp(name: String, response: ByteArray): FakeTcpSocket {
            val socket = FakeTcpSocket(name, events)
            socket.response(response)
            platform.tcpSockets += socket
            return socket
        }

        fun resolve(
            servers: List<InetAddress>,
            absoluteDeadlineNanos: Long? = null,
            fallbackContext: DnsFallbackQueryContext = DnsFallbackQueryContext(),
        ): ResolverResult = runBlocking {
            resolveSuspending(servers, absoluteDeadlineNanos, fallbackContext)
        }

        suspend fun resolveSuspending(
            servers: List<InetAddress>,
            absoluteDeadlineNanos: Long? = null,
            fallbackContext: DnsFallbackQueryContext = DnsFallbackQueryContext(),
        ): ResolverResult {
            return (resolver as DnsResolver).resolve(request(servers, absoluteDeadlineNanos, fallbackContext))
        }

        fun resolveWithPolicy(
            servers: List<InetAddress>,
            endpointBackoff: ResolverEndpointBackoffPolicy,
            absoluteDeadlineNanos: Long? = null,
            fallbackContext: DnsFallbackQueryContext = DnsFallbackQueryContext(),
        ): ResolverResult = runBlocking {
            (resolver as DeviceDnsRoute).resolve(
                request(servers, absoluteDeadlineNanos, fallbackContext),
                endpointBackoff,
            ).result
        }

        private fun request(
            servers: List<InetAddress>,
            absoluteDeadlineNanos: Long?,
            fallbackContext: DnsFallbackQueryContext,
        ): ResolverRequest =
            ResolverRequest(
                query = QUERY,
                question = DnsMessage.parseQuestion(QUERY),
                epoch = epoch,
                networkContext = NetworkContext(network, 1L, servers, isCellular = false),
                absoluteDeviceDeadlineNanos = absoluteDeadlineNanos,
                fallbackContext = fallbackContext,
            )
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        val queued = CountDownLatch(1)
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.put(block)
            queued.countDown()
        }

        fun runNext() {
            checkNotNull(tasks.poll(2, TimeUnit.SECONDS)) {
                "Expected queued socket I/O"
            }.run()
        }
    }

    private interface ConfigurableSocket : DeviceDnsSocket {
        val name: String
        var protectResult: Boolean
        var protectFailure: Throwable?
        var bindFailure: Throwable?
        val closeCount: Int
    }

    private class FakeUdpSocket(
        override val name: String,
        private val events: MutableList<String>,
    ) : DeviceUdpSocket, ConfigurableSocket {
        override var protectResult = true
        override var protectFailure: Throwable? = null
        override var bindFailure: Throwable? = null
        override var closeCount = 0
            private set
        var sendAction: () -> Unit = {}
        var closeAction: () -> Unit = {}
        val receives = ArrayDeque<() -> DatagramReceive>()
        val sentQueries = mutableListOf<ByteArray>()
        val sentServers = mutableListOf<InetAddress>()
        val sentPorts = mutableListOf<Int>()
        val sendTimeouts = mutableListOf<Int>()
        val receiveMaxBytes = mutableListOf<Int>()
        val receiveTimeouts = mutableListOf<Int>()
        val sendCount: Int
            get() = sentQueries.size
        var receiveCount = 0
            private set

        override fun send(query: ByteArray, server: InetAddress, port: Int, timeoutMillis: Int) {
            events += "$name.send"
            sentQueries += query.copyOf()
            sentServers += server
            sentPorts += port
            sendTimeouts += timeoutMillis
            sendAction()
        }

        override fun receive(maxBytes: Int, timeoutMillis: Int): DatagramReceive {
            events += "$name.receive"
            receiveCount += 1
            receiveMaxBytes += maxBytes
            receiveTimeouts += timeoutMillis
            return receives.pollFirst()?.invoke()
                ?: throw IOException("no UDP receive scripted")
        }

        override fun close() {
            events += "$name.close"
            closeCount += 1
            closeAction()
        }
    }

    private class FakeTcpSocket(
        override val name: String,
        private val events: MutableList<String>,
    ) : DeviceTcpSocket, ConfigurableSocket {
        override var protectResult = true
        override var protectFailure: Throwable? = null
        override var bindFailure: Throwable? = null
        override var closeCount = 0
            private set
        var connectAction: () -> Unit = {}
        var writeAction: () -> Unit = {}
        var readLengthAction: () -> Unit = {}
        var readBodyAction: () -> Unit = {}
        var closeAction: () -> Unit = {}
        var readLengthResult: Int? = null
        var readBodyResult: ByteArray? = null
        val connectedServers = mutableListOf<InetAddress>()
        val connectedPorts = mutableListOf<Int>()
        val connectTimeouts = mutableListOf<Int>()
        val frames = mutableListOf<ByteArray>()
        val writeTimeouts = mutableListOf<Int>()
        val lengthTimeouts = mutableListOf<Int>()
        val bodyLengths = mutableListOf<Int>()
        val bodyTimeouts = mutableListOf<Int>()
        val connectCount: Int
            get() = connectedServers.size
        val writeCount: Int
            get() = frames.size
        var readLengthCount = 0
            private set
        var readBodyCount = 0
            private set

        fun response(response: ByteArray) {
            readLengthResult = response.size
            readBodyResult = response.copyOf()
        }

        override fun connect(server: InetAddress, port: Int, timeoutMillis: Int) {
            events += "$name.connect"
            connectedServers += server
            connectedPorts += port
            connectTimeouts += timeoutMillis
            connectAction()
        }

        override fun writeFrame(frame: ByteArray, timeoutMillis: Int) {
            events += "$name.write"
            frames += frame.copyOf()
            writeTimeouts += timeoutMillis
            writeAction()
        }

        override fun readLength(timeoutMillis: Int): Int? {
            events += "$name.readLength"
            readLengthCount += 1
            lengthTimeouts += timeoutMillis
            readLengthAction()
            return readLengthResult
        }

        override fun readBody(length: Int, timeoutMillis: Int): ByteArray? {
            events += "$name.readBody"
            readBodyCount += 1
            bodyLengths += length
            bodyTimeouts += timeoutMillis
            readBodyAction()
            return readBodyResult?.copyOf()
        }

        override fun close() {
            events += "$name.close"
            closeCount += 1
            closeAction()
        }
    }

    private class FakePlatform(
        private val events: MutableList<String>,
    ) : DeviceDnsSocketPlatform {
        val udpSockets = ArrayDeque<FakeUdpSocket>()
        val tcpSockets = ArrayDeque<FakeTcpSocket>()
        val boundNetworks = mutableListOf<Network>()
        var udpCreateCount = 0
            private set
        var tcpCreateCount = 0
            private set
        var udpCreateFailure: Throwable? = null
        var onCreated: (String) -> Unit = {}
        private var lastCreated: ConfigurableSocket? = null
        val lastCreatedName: String
            get() = checkNotNull(lastCreated).name

        override fun createUdp(): DeviceUdpSocket {
            udpCreateCount += 1
            udpCreateFailure?.let { throw it }
            return udpSockets.removeFirst().also {
                lastCreated = it
                onCreated(it.name)
                events += "${it.name}.create"
            }
        }

        override fun createTcp(): DeviceTcpSocket {
            tcpCreateCount += 1
            return tcpSockets.removeFirst().also {
                lastCreated = it
                onCreated(it.name)
                events += "${it.name}.create"
            }
        }

        override fun protect(socket: DeviceDnsSocket): Boolean {
            socket as ConfigurableSocket
            events += "${socket.name}.protect"
            socket.protectFailure?.let { throw it }
            return socket.protectResult
        }

        override fun bind(socket: DeviceDnsSocket, network: Network) {
            socket as ConfigurableSocket
            events += "${socket.name}.bind"
            socket.bindFailure?.let { throw it }
            boundNetworks += network
        }
    }

    private class FakeRegistry(
        private val events: MutableList<String>,
    ) : ResolverResourceRegistry {
        @Volatile
        var current = true
        var invalidateOnCheck: Int? = null
        private var isCurrentCount = 0
        val registerModes = ArrayDeque<RegisterMode>()
        var unregisterMode = UnregisterMode.TRUE
        private val names = IdentityHashMap<AutoCloseable, String>()
        private val registered =
            Collections.newSetFromMap(IdentityHashMap<AutoCloseable, Boolean>())
        var createdName: String = ""
        val registeredCount: Int
            get() = registered.size

        override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean {
            val name = createdName
            events += "$name.register"
            return when (registerModes.pollFirst() ?: RegisterMode.ACCEPT) {
                RegisterMode.ACCEPT -> {
                    if (!current) {
                        resource.close()
                        false
                    } else {
                        names[resource] = name
                        registered += resource
                        true
                    }
                }
                RegisterMode.REJECT -> false
                RegisterMode.THROW -> throw IOException("register")
            }
        }

        override fun isCurrent(epoch: EngineEpoch): Boolean {
            isCurrentCount += 1
            if (invalidateOnCheck == isCurrentCount) current = false
            return current
        }

        override fun unregister(resource: AutoCloseable): Boolean {
            val name = names.remove(resource) ?: createdName
            events += "$name.unregister"
            registered.remove(resource)
            return when (unregisterMode) {
                UnregisterMode.TRUE -> true
                UnregisterMode.FALSE -> false
                UnregisterMode.THROW -> throw IOException("unregister")
            }
        }

        override fun completeIfCurrent(
            resource: AutoCloseable,
            epoch: EngineEpoch,
            completion: () -> Boolean,
        ): Boolean = synchronized(this) {
            if (!current || !registered.contains(resource)) return@synchronized false
            val completed = runCatching(completion).getOrDefault(false)
            registered.remove(resource)
            names.remove(resource)
            if (!completed) runCatching { resource.close() }
            completed
        }

        fun noteCreated(name: String) {
            createdName = name
        }

        fun closeRegistered() {
            current = false
            registered.toList().also { registered.clear() }.forEach(AutoCloseable::close)
            names.clear()
        }
    }

    private class FakeClock(
        private var now: Long,
    ) : MonotonicClock {
        val scriptedReads = ArrayDeque<Long>()

        override fun nowNanos(): Long = scriptedReads.pollFirst() ?: now

        fun advance(nanos: Long) {
            now += nanos
        }
    }

    private class FakeWatchdog : DeviceDnsWatchdog {
        private data class Entry(
            val action: () -> Unit,
            val cancelled: AtomicBoolean = AtomicBoolean(false),
        )

        private val entries = mutableListOf<Entry>()
        val delays = mutableListOf<Long>()
        var throwOnArm: Int? = null
        private var armCount = 0

        override fun arm(delayNanos: Long, onExpire: () -> Unit): AutoCloseable {
            armCount += 1
            if (throwOnArm == armCount) throw IOException("watchdog arm")
            delays += delayNanos
            val entry = Entry(onExpire)
            entries += entry
            return AutoCloseable { entry.cancelled.set(true) }
        }

        fun fireLatest() {
            val entry = entries.last()
            if (!entry.cancelled.get()) entry.action()
        }
    }

    private class DequeuedCallbackOnCloseWatchdog(
        private val raceArm: Int,
    ) : DeviceDnsWatchdog {
        private var armCount = 0
        var callbackDequeued = false
            private set
        var callbackRan = false
            private set

        override fun arm(delayNanos: Long, onExpire: () -> Unit): AutoCloseable {
            armCount += 1
            if (armCount != raceArm) return AutoCloseable {}
            callbackDequeued = true
            return AutoCloseable {
                callbackRan = true
                onExpire()
            }
        }
    }

    private class CountingResource : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }

    private data class SetupCase(
        val name: String,
        val reason: ResolverFailureReason,
        val configure: (ConfigurableSocket) -> Unit,
    )

    private data class FailureCase(
        val name: String,
        val reason: ResolverFailureReason,
        val configure: (Harness, InetAddress) -> Unit,
    )

    private data class TcpCase(
        val name: String,
        val reason: ResolverFailureReason,
        val configure: (FakeTcpSocket) -> Unit,
    )

    private enum class RegisterMode { ACCEPT, REJECT, THROW }

    private enum class UnregisterMode { TRUE, FALSE, THROW }

    private companion object {
        val QUERY = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0, 1, 0, 1,
        )
        val RESPONSE = QUERY.copyOf().also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
        }

        fun address(value: String): InetAddress = InetAddress.getByName(value)

        fun datagram(
            source: InetAddress,
            port: Int = 53,
            body: ByteArray = RESPONSE,
        ) = DatagramReceive(body, DatagramSource(source, port))

        fun frame(query: ByteArray): ByteArray =
            byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()) + query

        fun epoch() = EngineEpoch(
            sessionGeneration = 1,
            configurationIdentity = ConfigurationIdentity(1, "cfg"),
            artifactDigest = "artifact",
            policyRevision = 1,
            networkGeneration = 1,
            engineGeneration = 1,
            primaryResolverIdentity = PrimaryResolverIdentity("device-dns"),
            fallbackCoverage = ResolverFallbackCoverage.NONE,
        )

        fun network(handle: Int): Network =
            Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .newInstance(handle)

        fun awaitCondition(condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() - deadline < 0L) {
                if (condition()) return true
                Thread.yield()
            }
            return condition()
        }

        fun assertAnswer(result: ResolverResult, transport: ResolverTransport) {
            assertTrue("expected answer, got $result", result is ResolverResult.Answer)
            result as ResolverResult.Answer
            assertEquals(transport, result.transport)
            assertArrayEquals(RESPONSE, result.dnsMessage)
        }

        fun assertFailure(
            result: ResolverResult,
            reason: ResolverFailureReason,
            label: String = "",
        ) {
            assertTrue("$label expected failure, got $result", result is ResolverResult.Failure)
            result as ResolverResult.Failure
            assertEquals(label, reason, result.reason)
        }
    }
}
