package com.lavasec.app.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the share-card QR encode by decoding it with the independent offline reader, so the render
 * path cannot silently emit an unscannable card.
 */
class ShareQrRendererTest {
    @Test
    fun `the encoded card round-trips through the reader`() {
        val url = "https://lavasecurity.app/app/filter?code=ABC123"
        val pixels = ShareQrRenderer.encodeArgb(url, 384)!!
        assertEquals(url, QrCodeReader.decodeArgb(pixels, 384, 384))
    }

    @Test
    fun `a blank payload has no card`() {
        assertNull(ShareQrRenderer.encodeArgb("", 384))
        assertNull(ShareQrRenderer.encodeArgb("   ", 384))
    }
}
