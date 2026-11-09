package com.lavasec.app.billing

import org.junit.Assert.*
import org.junit.Test

class PlayReceiptCodecTest {
    private fun receipt(token: String = "secret-purchase") =
        PlayPurchase(token, setOf("lava_security_plus_yearly"), true, false)

    @Test fun `receipt codec preserves only native re-verification fields`() {
        val decoded = PlayReceiptCodec.decode(PlayReceiptCodec.encode(listOf(receipt())))
        assertEquals("secret-purchase", decoded.single().token)
        assertEquals(setOf("lava_security_plus_yearly"), decoded.single().products)
        assertTrue(decoded.single().purchased)
        assertFalse(decoded.single().acknowledged)
        assertFalse(decoded.toString().contains("secret-purchase"))
        assertTrue(PlayReceiptCodec.decode(PlayReceiptCodec.encode(emptyList())).isEmpty())
    }

    @Test fun `malformed unbounded and duplicate receipt records are rejected`() {
        val valid = PlayReceiptCodec.encode(listOf(receipt()))
        for (bytes in listOf(valid.dropLast(1).toByteArray(), valid + byteArrayOf(0),
            valid.copyOf().also { it[0] = 0 }, ByteArray(512 * 1024 + 1))) {
            assertTrue(runCatching { PlayReceiptCodec.decode(bytes) }.isFailure)
        }
        assertTrue(runCatching { PlayReceiptCodec.encode(listOf(receipt(), receipt())) }.isFailure)
        assertTrue(runCatching { PlayReceiptCodec.encode(List(101) { receipt("token-$it") }) }.isFailure)
        assertTrue(runCatching { PlayReceiptCodec.encode(listOf(receipt("x".repeat(4097)))) }.isFailure)
        assertTrue(runCatching { PlayReceiptCodec.encode(listOf(receipt("bad token"))) }.isFailure)
    }
}
