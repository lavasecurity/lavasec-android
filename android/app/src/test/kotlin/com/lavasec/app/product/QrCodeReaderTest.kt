package com.lavasec.app.product

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the offline QR decode the camera import path relies on. The code is generated with the same
 * library's encoder, so the round trip exercises the real reader (no Android frame, no camera).
 */
class QrCodeReaderTest {
    private fun render(payload: String, size: Int = 240): IntArray {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        return IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }

    @Test
    fun `decodes a generated qr payload exactly`() {
        val payload = "lavasec://filter/3f9a1c2b7e"
        assertEquals(payload, QrCodeReader.decodeArgb(render(payload), 240, 240))
    }

    @Test
    fun `decodes a canonical share url`() {
        val url = "https://lavasecurity.app/app/filter?code=ABC123"
        assertEquals(url, QrCodeReader.decodeArgb(render(url), 240, 240))
    }

    @Test
    fun `a blank image yields no candidate`() {
        assertNull(QrCodeReader.decodeArgb(IntArray(240 * 240) { 0xFFFFFFFF.toInt() }, 240, 240))
    }

    @Test
    fun `a malformed buffer is refused rather than throwing`() {
        assertNull(QrCodeReader.decodeArgb(IntArray(4), 240, 240))
    }

    @Test
    fun `downsampling samples the long edge all the way to the cap`() {
        // 6399px must fall to <= 1600px, i.e. inSampleSize = 4 (6399/4 = 1599), not 2 (3199px).
        assertEquals(4, AndroidQrImageDecoder.sampleSize(6399, 4800, 1600))
        assertEquals(1, AndroidQrImageDecoder.sampleSize(1600, 1200, 1600))
        assertEquals(2, AndroidQrImageDecoder.sampleSize(1601, 1200, 1600))
        assertEquals(1, AndroidQrImageDecoder.sampleSize(0, 1200, 1600))
        assertEquals(1, AndroidQrImageDecoder.sampleSize(1200, 1200, 0))
    }
}
