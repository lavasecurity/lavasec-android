package com.lavasec.app.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real packaged JNI and Android trust-directory loading; no external service traffic. */
@RunWith(AndroidJUnit4::class)
class NativeDoqPackagingInstrumentationTest {
    @Test
    fun packagedDoqEngineLoadsSystemTrustAndEmitsHandshakeWithoutOpeningSocket() {
        val session = NativeDoqSessionFactory().create(
            CustomDoqEndpoint("resolver.example", 853),
            java.net.InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 53)),
            query(),
        )
        try {
            val first = session.nextEvent()
            assertEquals(1, first[0].toInt())
            assertTrue("QUIC Initial must contain at least 1200 UDP bytes", first.size >= 1201)
            assertTrue(session.timeoutMillis() >= 0)
            assertEquals(0, session.receive(byteArrayOf(0)))
            session.onTimeout()
            session.cancel()
        } finally { session.close() }
        session.close() // Kotlin ownership is idempotent.
    }

    @Test
    fun packagedDoqEngineRejectsMissingTrustAndNonzeroWireIds() {
        val invalid = query().apply { this[1] = 1 }
        assertEquals(0L, NativeDoqCore.create("resolver.example", "192.0.2.53:853", invalid, ""))
        assertEquals(0L, NativeDoqCore.create("resolver.example", "192.0.2.53:853", query(), ""))
        assertEquals(0L, NativeDoqCore.create("resolver.example", "192.0.2.53:53", query(), ""))
    }

    private fun query() = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 97, 0, 0, 1, 0, 1)
}
