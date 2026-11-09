package com.lavasec.app.vpn.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the packaged ABI on a device; host-JVM fakes cannot detect a missing .so or JNI symbol. */
@RunWith(AndroidJUnit4::class)
class NativeEnginePackagingInstrumentationTest {
    @Test
    fun packagedEngineCreatesSessionAndEmitsHandshakeWithoutNetworkAccess() {
        // Public test material only. 9 is the X25519 base point; no server, socket,
        // VPN, entitlement or production configuration participates in this test.
        val privateKey = ByteArray(32) { (it + 1).toByte() }
        val peerPublicKey = ByteArray(32).also { it[0] = 9 }
        val handle = NativeLavaWgCore.sessionNew(privateKey, peerPublicKey, ByteArray(0), 0, 0)
        privateKey.fill(0)
        assertNotEquals("The packaged native engine must allocate a session", 0L, handle)
        try {
            assertNotNull(LavaWgCodec.decodeStats(NativeLavaWgCore.sessionStats(handle)))
            val response = LavaWgCodec.decode(NativeLavaWgCore.forceHandshake(handle))
            assertTrue("JNI must return a handshake packet", response is LavaWgResponse.Produced)
            response as LavaWgResponse.Produced
            assertEquals(LavaWgOp.WRITE_TO_NETWORK, response.op)
            assertEquals("WireGuard initiation packet size", 148, response.packet.size)
        } finally {
            NativeLavaWgCore.sessionFree(handle)
        }
    }

    @Test
    fun packagedEngineRejectsInvalidKeyWidthsAtTheJniBoundary() {
        val handle = NativeLavaWgCore.sessionNew(ByteArray(31), ByteArray(32), ByteArray(0), 0, 0)
        try {
            assertEquals(0L, handle)
        } finally {
            if (handle != 0L) NativeLavaWgCore.sessionFree(handle)
        }
    }
}
