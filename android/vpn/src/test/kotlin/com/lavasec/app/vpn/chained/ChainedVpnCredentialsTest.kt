package com.lavasec.app.vpn.chained

import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.platform.ChainedAvailabilityAuthority
import com.lavasec.app.platform.ChainedDeviceEligibilityRead
import com.lavasec.app.platform.ChainedDeviceEligibilityStore
import com.lavasec.app.platform.ChainedDeviceMemoryReading
import com.lavasec.app.platform.ChainedMemoryKillBackoffPolicy
import com.lavasec.app.platform.ChainedUpstreamSecretMaterial
import com.lavasec.app.platform.UnavailableChainedEntitlementPort
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential carrier's ownership rules: it copies on the way in, releases on close, and
 * refuses a malformed key rather than handing it to the JNI engine.
 */
class ChainedVpnCredentialsTest {

    private class FakeLifecycle : ChainedSessionEligibilityLifecycle {
        var started = 0
        var stopped = 0
        override fun markChainedSessionStarted(nowEpochMillis: Long): Boolean {
            started++
            return true
        }
        override fun markChainedSessionStoppedCleanly(
            nowEpochMillis: Long,
            owningStartEpochMillis: Long,
        ) {
            stopped++
        }
    }

    private object EligibleStore : ChainedDeviceEligibilityStore {
        private val read = ChainedDeviceEligibilityRead.Available(
            memory = ChainedDeviceMemoryReading(
                totalMemoryBytes = 4_000_000_000L,
                memoryClassMegabytes = 256,
                isLowRamDevice = false,
            ),
            backoff = ChainedMemoryKillBackoffPolicy.clean,
            apiLevel = 36,
        )
        override fun read() = read
        override fun markChainedSessionStarted(nowEpochMillis: Long) = read
        override fun markChainedSessionStoppedCleanly(nowEpochMillis: Long) = read
        override fun recordMemoryKill(nowEpochMillis: Long) = read
        override fun clearMemoryKillBackoff(nowEpochMillis: Long) = read
    }

    private fun authority(): ChainedAvailabilityAuthority =
        ChainedAvailabilityAuthority.production(UnavailableChainedEntitlementPort, EligibleStore)

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
    fun `close zeroizes the owned key material and is idempotent`() {
        val credentials = ChainedVpnCredentials.create(
            configuration = configuration(),
            privateKey = ByteArray(32) { 0x11 },
            peerPublicKey = ByteArray(32) { 0x22 },
            presharedKey = ByteArray(32) { 0x33 },
            availability = authority(),
            lifecycle = FakeLifecycle(),
        )

        credentials.close()
        credentials.close()

        assertArrayEquals(ByteArray(32), credentials.privateKeyForSession())
        assertArrayEquals(ByteArray(32), credentials.peerPublicKeyForSession())
        assertArrayEquals(ByteArray(32), credentials.presharedKeyForSession())
    }

    @Test
    fun `create copies the caller's arrays so a later mutation cannot change the credential`() {
        val privateKey = ByteArray(32) { 0x11 }
        val credentials = ChainedVpnCredentials.create(
            configuration = configuration(),
            privateKey = privateKey,
            peerPublicKey = ByteArray(32) { 0x22 },
            presharedKey = null,
            availability = authority(),
            lifecycle = FakeLifecycle(),
        )

        privateKey.fill(0x7F)

        assertTrue(credentials.privateKeyForSession().all { it == 0x11.toByte() })
        assertFalse(credentials.presharedKeyForSession() != null)
    }

    @Test
    fun `a malformed key is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChainedVpnCredentials.create(
                configuration = configuration(),
                privateKey = ByteArray(16),
                peerPublicKey = ByteArray(32),
                presharedKey = null,
                availability = authority(),
                lifecycle = FakeLifecycle(),
            )
        }
    }

    @Test
    fun `createFromMaterial transfers the copies and zeroizes every intermediate`() {
        val material = checkNotNull(
            ChainedUpstreamSecretMaterial.create(
                generation = 1L,
                privateKey = ByteArray(32) { 0x11 },
                presharedKey = ByteArray(32) { 0x33 },
            ),
        )
        val peer = ByteArray(32) { 0x22 }

        val credentials = ChainedVpnCredentials.createFromMaterial(
            configuration = configuration(),
            material = material,
            peerPublicKey = peer,
            availability = authority(),
            lifecycle = FakeLifecycle(),
        )

        // The transferred copy is intact...
        assertArrayEquals(ByteArray(32) { 0x11 }, credentials.privateKeyForSession())
        assertArrayEquals(ByteArray(32) { 0x22 }, credentials.peerPublicKeyForSession())
        assertArrayEquals(ByteArray(32) { 0x33 }, credentials.presharedKeyForSession())

        // ...and every intermediate is cleared: the caller's peer buffer and the store's arrays.
        assertArrayEquals(ByteArray(32), peer)
        assertArrayEquals(ByteArray(32), material.privateKeyCopy())
        assertArrayEquals(ByteArray(32), material.presharedKeyCopy())
    }
    @Test
    fun `coherent ordered transfer retains independent keys and zeroizes both on close`() {
        val base = configuration()
        val entry = ChainedUpstreamConfiguration.create("192.0.2.1", 51820, base.peerPublicKey, "10.1.0.2", listOf("0.0.0.0/0"), 0, 1420, base.dnsAddresses)
        val exit = ChainedUpstreamConfiguration.create("192.0.2.2", 51820, base.peerPublicKey, "10.2.0.2", listOf("0.0.0.0/0"), 0, 1420, base.dnsAddresses)
        val material = checkNotNull(ChainedUpstreamSecretMaterial.createRows(7, listOf(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }), listOf(null, ByteArray(32) { 0x33 })))
        val credentials = ChainedVpnCredentials.createFromMaterial(exit.withEntryHop(entry), material, ByteArray(32) { 0x44 }, authority(), FakeLifecycle())
        assertArrayEquals(ByteArray(32) { 0x11 }, credentials.entry!!.privateKeyForSession())
        assertArrayEquals(ByteArray(32) { 0x22 }, credentials.privateKeyForSession())
        assertArrayEquals(ByteArray(32) { 0x33 }, credentials.presharedKeyForSession())
        assertArrayEquals(ByteArray(32), material.rowPrivateKeyCopy(0))
        assertArrayEquals(ByteArray(32), material.rowPrivateKeyCopy(1))
        credentials.close()
        assertArrayEquals(ByteArray(32), credentials.entry!!.privateKeyForSession())
        assertArrayEquals(ByteArray(32), credentials.privateKeyForSession())
    }

    @Test
    fun `second engine allocation failure closes the first handle without losing owned credentials`() {
        val base = configuration()
        val entry = ChainedUpstreamConfiguration.create("192.0.2.1", 51820, base.peerPublicKey, "10.1.0.2", listOf("0.0.0.0/0"), 0, 1420, base.dnsAddresses)
        val exit = ChainedUpstreamConfiguration.create("192.0.2.2", 51820, base.peerPublicKey, "10.2.0.2", listOf("0.0.0.0/0"), 0, 1420, base.dnsAddresses)
        val material = checkNotNull(ChainedUpstreamSecretMaterial.createRows(7, listOf(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }), listOf(null, null)))
        val credentials = ChainedVpnCredentials.createFromMaterial(exit.withEntryHop(entry), material, ByteArray(32) { 0x44 }, authority(), FakeLifecycle())
        val first = FakeWireGuardSession()
        var calls = 0
        val factory = OrderedChainedWireGuardSessionFactory(credentials) { row, index ->
            assertArrayEquals(ByteArray(32) { if(index == 0) 0x11 else 0x22 }, row.privateKeyForSession())
            ChainedWireGuardSessionFactory { calls++; if(index == 0) first else null }
        }
        assertTrue(factory.create() == null)
        assertTrue(!first.isOpen)
        assertTrue(calls == 2)
        assertArrayEquals(ByteArray(32) { 0x22 }, credentials.privateKeyForSession())
        credentials.close()
    }

}
