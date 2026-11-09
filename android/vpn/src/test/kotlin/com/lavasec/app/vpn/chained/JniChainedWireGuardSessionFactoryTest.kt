package com.lavasec.app.vpn.chained

import com.lavasec.app.platform.ChainedAvailabilityAuthority
import com.lavasec.app.platform.ChainedDeviceEligibilityRead
import com.lavasec.app.platform.ChainedDeviceEligibilityStore
import com.lavasec.app.platform.ChainedDeviceMemoryReading
import com.lavasec.app.platform.ChainedEntitlementPort
import com.lavasec.app.platform.ChainedMemoryKillBackoffPolicy
import com.lavasec.app.vpn.engine.LavaWgResponse
import com.lavasec.app.vpn.engine.LavaWgStats
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reconnect-capable production session factory revalidates availability on every attempt.
 *
 * A permit minted from an earlier eligible verdict must not be reused after the account loses
 * Plus or the device becomes ineligible, or a reconnect would reinstantiate the ~50 MB engine
 * past entitlement. The factory depends on the concrete [ChainedAvailabilityAuthority] rather than
 * a permit value or a synthetic provider; the test supplies that authority over a fixed device
 * reading and a toggleable entitlement port, and the injected session-creator seam lets it
 * exercise the gate without the native library.
 */
class JniChainedWireGuardSessionFactoryTest {

    private class FixedStore(private val read: ChainedDeviceEligibilityRead) :
        ChainedDeviceEligibilityStore {
        override fun read(): ChainedDeviceEligibilityRead = read
        override fun markChainedSessionStarted(nowEpochMillis: Long): ChainedDeviceEligibilityRead = read
        override fun markChainedSessionStoppedCleanly(nowEpochMillis: Long): ChainedDeviceEligibilityRead =
            read
        override fun recordMemoryKill(nowEpochMillis: Long): ChainedDeviceEligibilityRead = read
        override fun clearMemoryKillBackoff(nowEpochMillis: Long): ChainedDeviceEligibilityRead = read
    }

    private fun authority(entitled: () -> Boolean): ChainedAvailabilityAuthority =
        ChainedAvailabilityAuthority.production(
            entitlement = ChainedEntitlementPort { entitled() },
            eligibilityStore = FixedStore(
                ChainedDeviceEligibilityRead.Available(
                    memory = ChainedDeviceMemoryReading(
                        totalMemoryBytes = 4_000_000_000L,
                        memoryClassMegabytes = 256,
                        isLowRamDevice = false,
                    ),
                    backoff = ChainedMemoryKillBackoffPolicy.clean,
                    apiLevel = 36,
                ),
            ),
        )

    private class StubSession : WireGuardSession {
        override val isOpen: Boolean = true
        override fun encapsulate(packet: ByteArray): LavaWgResponse = LavaWgResponse.None
        override fun decapsulate(packet: ByteArray, sourceAddress: ByteArray): LavaWgResponse =
            LavaWgResponse.None
        override fun tick(): LavaWgResponse = LavaWgResponse.None
        override fun forceHandshake(): LavaWgResponse = LavaWgResponse.None
        override fun stats(): LavaWgStats? = null
        override fun close() = Unit
    }

    @Test
    fun `a fresh permit is minted per attempt and a lapsed entitlement blocks reinstantiation`() {
        var entitled = true
        var constructions = 0
        val factory = JniChainedWireGuardSessionFactory(
            availability = authority { entitled },
            privateKey = ByteArray(32),
            peerPublicKey = ByteArray(32),
            sessionCreator = { _, _, _, _, _, _ ->
                constructions++
                StubSession()
            },
        )

        assertNotNull(factory.create())
        assertEquals(1, constructions)

        entitled = false
        assertNull(factory.create())
        assertEquals(
            "an ineligible verdict must not construct a replacement engine",
            1,
            constructions,
        )
    }

    @Test
    fun `the factory reconsults the authority on each call`() {
        var entitled = true
        var constructions = 0
        val factory = JniChainedWireGuardSessionFactory(
            availability = authority { entitled },
            privateKey = ByteArray(32),
            peerPublicKey = ByteArray(32),
            sessionCreator = { _, _, _, _, _, _ ->
                constructions++
                StubSession()
            },
        )

        factory.create()
        entitled = false
        factory.create()
        entitled = true
        factory.create()

        assertEquals(2, constructions)
    }

    @Test
    fun `each build reads its own secret copy so a concurrent stop cannot mutate the key in flight`() {
        val originalPrivateKey = ByteArray(32) { 0x11 }
        var capturedPrivateKey: ByteArray? = null
        val factory = JniChainedWireGuardSessionFactory(
            availability = authority { true },
            privateKey = originalPrivateKey,
            peerPublicKey = ByteArray(32) { 0x22 },
            presharedKey = ByteArray(32) { 0x33 },
            sessionCreator = { _, privateKey, _, _, _, _ ->
                capturedPrivateKey = privateKey
                StubSession()
            },
        )

        assertNotNull(factory.create())

        val buildKey = checkNotNull(capturedPrivateKey)
        // The build read a per-build copy, not the array the factory retains for the next attempt...
        assertFalse(originalPrivateKey === buildKey)
        // ...the retained key is intact, so a later reconnect still builds from it...
        assertArrayEquals(ByteArray(32) { 0x11 }, originalPrivateKey)
        // ...and the per-build copy is scrubbed once the engine has copied it.
        assertArrayEquals(ByteArray(32), buildKey)
    }
}
