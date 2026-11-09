package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.platform.ConfigurationIdentity
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomDoqResolverTest {
    @Test
    fun `protect and exact bind precede QUIC packets and caller ID is restored`() = runBlocking {
        val f = Fixture()
        val result = f.resolver.resolve(f.request) as ResolverResult.Answer
        assertEquals(listOf("create", "protect", "bind", "session", "send"), f.operations)
        assertEquals(ResolverTransport.DOQ, result.transport)
        assertArrayEquals(f.query, result.dnsMessage.copyOf().apply { this[2] = 0 })
        assertArrayEquals(byteArrayOf(0, 0), f.posted!!.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(0x12, 0x34), f.query.copyOfRange(0, 2))
        assertEquals(1, f.socketCloses)
        assertEquals(1, f.sessionCloses)
        assertEquals(0, f.cancellations)
    }

    @Test
    fun `protection and binding failures never create or send a QUIC session`() = runBlocking {
        for (protect in listOf(false, true)) {
            val f = Fixture().apply { protectionSucceeds = protect; bindingSucceeds = false }
            val result = f.resolver.resolve(f.request) as ResolverResult.Failure
            assertEquals(if (protect) ResolverFailureReason.BIND_FAILED else ResolverFailureReason.PROTECT_FAILED, result.reason)
            assertFalse(f.operations.contains("session"))
            assertFalse(f.operations.contains("send"))
            assertEquals(1, f.socketCloses)
        }
    }

    @Test
    fun `retirement while an answer arrives refuses it and closes the socket exactly once`() = runBlocking {
        val f = Fixture().apply { onAnswer = { registry.retire() } }
        val result = f.resolver.resolve(f.request) as ResolverResult.Failure
        assertEquals(ResolverFailureReason.STALE_EPOCH, result.reason)
        assertEquals(1, f.socketCloses)
        assertEquals(1, f.sessionCloses)
        assertEquals(0, f.cancellations) // A retired network never sends further packets.
    }

    @Test
    fun `cancellation sends request cancellation before releasing query resources`() = runBlocking {
        val f = Fixture().apply { cancelOnFirstEvent = true }
        try {
            f.resolver.resolve(f.request)
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, f.cancellations)
        assertEquals(listOf("create", "protect", "bind", "session", "cancel", "send"), f.operations)
        assertEquals(1, f.socketCloses)
        assertEquals(1, f.sessionCloses)
    }

    @Test
    fun `deadline expires before answer publication and TLS failures remain typed`() = runBlocking {
        val late = Fixture().apply { onAnswer = { now = 5_000_000_000; timer!!() } }
        assertEquals(ResolverFailureReason.TIMEOUT, (late.resolver.resolve(late.request) as ResolverResult.Failure).reason)
        assertEquals(1, late.socketCloses)
        val tls = Fixture().apply { terminalEvent = byteArrayOf(254.toByte()) }
        assertEquals(ResolverFailureReason.TLS_FAILED, (tls.resolver.resolve(tls.request) as ResolverResult.Failure).reason)
    }

    @Test
    fun `nonzero DoQ response ID is refused rather than repaired`() = runBlocking {
        val f = Fixture().apply { terminalEvent = byteArrayOf(2) + response().apply { this[0] = 1 } }
        assertEquals(ResolverFailureReason.INVALID_RESPONSE, (f.resolver.resolve(f.request) as ResolverResult.Failure).reason)
    }

    @Test
    fun `literal TLS identity requires the matching IP SAN and never accepts a DNS SAN or CN`() {
        val ip = HeldCertificate.Builder().commonName("wrong.example").addSubjectAlternativeName("192.0.2.53").build()
        assertTrue(DoqIpIdentity.matches("192.0.2.53", ip.certificate.encoded))
        assertFalse(DoqIpIdentity.matches("192.0.2.54", ip.certificate.encoded))
        val dns = HeldCertificate.Builder().commonName("192.0.2.53").addSubjectAlternativeName("resolver.example").build()
        assertFalse(DoqIpIdentity.matches("192.0.2.53", dns.certificate.encoded))
        assertFalse(DoqIpIdentity.matches("resolver.example", dns.certificate.encoded))
    }

    @Test
    fun `retained bootstrap peers bypass network lookup and retry only non-lifecycle failure`() = runBlocking {
        val peers = listOf(InetAddress.getByName("192.168.1.53"), InetAddress.getByName("192.168.1.54"))
        val f = Fixture(peers).apply { failFirstBootstrap = true }
        assertTrue(f.resolver.resolve(f.request) is ResolverResult.Answer)
        assertEquals(peers, f.sessionPeers)
        assertEquals(0, f.bootstrapLookups)
        assertEquals(2, f.socketCloses)
        assertEquals(2, f.sessionCloses)
        val retired = Fixture(peers).apply { onAnswer = { registry.retire() } }
        assertEquals(ResolverFailureReason.STALE_EPOCH, (retired.resolver.resolve(retired.request) as ResolverResult.Failure).reason)
        assertEquals(listOf(peers.first()), retired.sessionPeers)
        assertEquals(0, retired.bootstrapLookups)
    }

    @Test
    fun `resolved address snapshot tries unreachable IPv6 then IPv4 in order within one deadline`() = runBlocking {
        val peers = mutableListOf(InetAddress.getByName("fd00::53"), InetAddress.getByName("192.168.1.54"))
        peers += peers.first()
        val expected = peers.distinct()
        val f = Fixture(resolved = peers).apply {
            failFirstSend = true
            onSessionCreated = { if (sessionPeers.size == 1) peers.clear() }
            onFailedSend = { now = 1_500_000_000L }
        }
        val result = f.resolver.resolve(f.request) as ResolverResult.Answer
        assertEquals(ResolverTransport.DOQ, result.transport)
        assertArrayEquals(f.query, result.dnsMessage.copyOf().apply { this[2] = 0 })
        assertEquals(expected, f.sessionPeers)
        assertEquals(1, f.bootstrapLookups)
        assertEquals(listOf(2_500_000_000L, 3_500_000_000L), f.attemptBudgets)
        assertEquals(2, f.socketCloses)
        assertEquals(2, f.sessionCloses)
        assertEquals(2, f.operations.count { it == "protect" })
        assertEquals(2, f.operations.count { it == "bind" })
        assertNull(f.registry.owned)
    }

    @Test
    fun `resolved alternate is never attempted after actual query job cancellation`() = runBlocking {
        val peers = listOf(InetAddress.getByName("fd00::53"), InetAddress.getByName("192.168.1.54"))
        val f = Fixture(resolved = peers).apply { failFirstSend = true }
        var published = false
        val queryJob = launch {
            val ownerJob = currentCoroutineContext().job
            f.onFailedSend = { ownerJob.cancel() }
            f.resolver.resolve(f.request)
            published = true
        }
        queryJob.join()
        assertTrue(queryJob.isCancelled)
        assertFalse(published)
        assertEquals(listOf(peers.first()), f.sessionPeers)
        assertEquals(1, f.bootstrapLookups)
        assertEquals(1, f.socketCloses)
        assertEquals(1, f.sessionCloses)
        assertNull(f.registry.owned)
    }

    @Test
    fun `resolved alternate is never attempted after first peer retires the captured epoch`() = runBlocking {
        val peers = listOf(InetAddress.getByName("fd00::53"), InetAddress.getByName("192.168.1.54"))
        val f = Fixture(resolved = peers).apply {
            failFirstSend = true
            onFailedSend = { registry.retire() }
        }
        assertEquals(ResolverFailureReason.STALE_EPOCH, (f.resolver.resolve(f.request) as ResolverResult.Failure).reason)
        assertEquals(listOf(peers.first()), f.sessionPeers)
        assertEquals(1, f.bootstrapLookups)
        assertEquals(1, f.socketCloses)
        assertEquals(1, f.sessionCloses)
        assertEquals(0, f.cancellations)
        assertNull(f.registry.owned)
    }

    @Test
    fun `resolved alternate cannot restart the expired overall query budget`() = runBlocking {
        val peers = listOf(InetAddress.getByName("fd00::53"), InetAddress.getByName("192.168.1.54"))
        val f = Fixture(resolved = peers).apply {
            failFirstSend = true
            onFailedSend = { now = 5_000_000_000L }
        }
        assertEquals(ResolverFailureReason.TIMEOUT, (f.resolver.resolve(f.request) as ResolverResult.Failure).reason)
        assertEquals(listOf(peers.first()), f.sessionPeers)
        assertEquals(1, f.socketCloses)
        assertEquals(1, f.sessionCloses)
        assertNull(f.registry.owned)
    }

    @Test
    fun `empty resolved address list fails before acquiring a transport resource`() = runBlocking {
        val f = Fixture(resolved = emptyList())
        assertEquals(ResolverFailureReason.RECEIVE_FAILED, (f.resolver.resolve(f.request) as ResolverResult.Failure).reason)
        assertEquals(1, f.bootstrapLookups)
        assertTrue(f.operations.isEmpty())
        assertTrue(f.sessionPeers.isEmpty())
        assertNull(f.registry.owned)
    }

    private class Fixture(
        bootstrap: List<InetAddress> = emptyList(),
        resolved: List<InetAddress> = listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 53))),
    ) {
        val query = byteArrayOf(
            0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            1, 97, 7, 101, 120, 97, 109, 112, 108, 101, 0, 0, 1, 0, 1,
        ) // a.example, a valid synthetic multi-label DNS question.
        val network = Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(7)
        val epoch = EngineEpoch(1, ConfigurationIdentity(1, "cfg"), "artifact", 1, 1, 1,
            PrimaryResolverIdentity("custom-doq:doq://resolver.example:853"), ResolverFallbackCoverage.NONE)
        val context = NetworkContext(network, 1, emptyList(), false)
        val request = ResolverRequest(query, DnsMessage.parseQuestion(query), epoch, context)
        val operations = mutableListOf<String>()
        var posted: ByteArray? = null
        var socketCloses = 0
        var sessionCloses = 0
        var cancellations = 0
        var protectionSucceeds = true
        var bindingSucceeds = true
        var cancelOnFirstEvent = false
        var now = 0L
        var timer: (() -> Unit)? = null
        var onAnswer: () -> Unit = {}
        var terminalEvent: ByteArray? = null
        var failFirstBootstrap = false
        var failFirstSend = false
        var onFailedSend: () -> Unit = {}
        var onSessionCreated: () -> Unit = {}
        var bootstrapLookups = 0
        val sessionPeers = mutableListOf<InetAddress>()
        val attemptBudgets = mutableListOf<Long>()
        val registry = Registry()
        inner class Registry : ResolverResourceRegistry {
            var current = true
            var owned: AutoCloseable? = null
            override fun registerIfCurrent(resource: AutoCloseable, epoch: EngineEpoch): Boolean {
                if (!current) { resource.close(); return false }
                owned = resource; return true
            }
            override fun isCurrent(epoch: EngineEpoch) = current
            override fun unregister(resource: AutoCloseable): Boolean = if (owned === resource) {
                owned = null; true
            } else false
            override fun completeIfCurrent(resource: AutoCloseable, epoch: EngineEpoch, completion: () -> Boolean): Boolean =
                error("the engine's publication fence owns completion")
            fun retire() { current = false; owned?.close(); owned = null }
        }
        fun response(): ByteArray = query.copyOf().apply { this[0] = 0; this[1] = 0; this[2] = 0x80.toByte() }
        val resolver = CustomDoqResolver(epoch, context, registry, object : DeviceDnsSocketPlatform {
            override fun createUdp(): DeviceUdpSocket {
                operations += "create"
                return object : DeviceUdpSocket {
                    override fun send(query: ByteArray, server: InetAddress, port: Int, timeoutMillis: Int) {
                        assertEquals(853, port)
                        assertEquals(sessionPeers.last(), server)
                        operations += "send"
                        if (failFirstSend && sessionPeers.size == 1) {
                            onFailedSend()
                            throw java.io.IOException("first resolved peer is unreachable")
                        }
                    }
                    override fun receive(maxBytes: Int, timeoutMillis: Int): DatagramReceive = error("fixture always produces an event")
                    override fun close() { socketCloses++ }
                }
            }
            override fun createTcp(): DeviceTcpSocket = error("DoQ uses UDP only")
            override fun protect(socket: DeviceDnsSocket): Boolean { operations += "protect"; return protectionSucceeds }
            override fun bind(socket: DeviceDnsSocket, network: Network) {
                assertSame(this@Fixture.network, network); operations += "bind"
                if (!bindingSucceeds) throw java.io.IOException("bind refused")
            }
        }, CustomDoqEndpoint("resolver.example", 853), DoqSessionFactory { endpoint, peer, query ->
            assertEquals("resolver.example", endpoint.host)
            sessionPeers += peer
            val attempt = sessionPeers.size
            onSessionCreated()
            operations += "session"; posted = query.copyOf()
            object : DoqSession {
                var events = 0
                var cancelled = false
                override fun nextEvent(): ByteArray {
                    events++
                    if (cancelled) return if (events == 2) byteArrayOf(1, 3) else byteArrayOf(0)
                    if (events == 1) {
                        if (cancelOnFirstEvent) throw CancellationException("cancel fixture")
                        return byteArrayOf(1, 7)
                    }
                    onAnswer()
                    return if (failFirstBootstrap && attempt == 1) byteArrayOf(254.toByte()) else terminalEvent ?: (byteArrayOf(2) + response())
                }
                override fun receive(packet: ByteArray) = 0
                override fun timeoutMillis() = 100L
                override fun onTimeout() = Unit
                override fun approveIpIdentity() = Unit
                override fun cancel() { cancelled = true; cancellations++; operations += "cancel" }
                override fun close() { sessionCloses++ }
            }
        }, DoqAddressLookup { captured, host ->
            bootstrapLookups++
            assertSame(network, captured)
            assertEquals("resolver.example", host)
            resolved
        }, MonotonicClock { now }, DeviceDnsWatchdog { budget, expire ->
            attemptBudgets += budget
            timer = expire; AutoCloseable { timer = null }
        }, Dispatchers.Unconfined, bootstrap)
    }
}
