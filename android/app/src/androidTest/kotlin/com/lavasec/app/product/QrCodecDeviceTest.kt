package com.lavasec.app.product

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device evidence for the filter-share QR codec (F10/F11): the shared `share.query` card and
 * the camera import both rely on this. It exercises the real Android Bitmap/PNG path (not just the
 * JVM ZXing round trip in `QrCodeReaderTest`/`ShareQrRendererTest`), so a regression in the
 * Bitmap/Base64 glue fails on the emulator lane. Runs without app state or credentials.
 */
@RunWith(AndroidJUnit4::class)
class QrCodecDeviceTest {
    private val payload = "https://lavasecurity.app/app/filter?code=DEVICE123"

    @Test
    fun shareCardRendersAPngThatDecodesBackToThePayload() {
        val dataUri = ShareQrRenderer.renderPngDataUri(payload, 320)
        assertNotNull("the share card must render", dataUri)
        assertTrue(dataUri!!.startsWith("data:image/png;base64,"))
        val bytes = Base64.decode(dataUri.substringAfter(','), Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("the card bytes must be a PNG", bitmap)
        assertEquals(payload, QrCodeReader.decodeArgb(bitmap.toArgb(width = bitmap!!.width, height = bitmap.height), bitmap.width, bitmap.height))
    }

    @Test
    fun cameraDecoderReadsACapturedImageFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "qr-codec-device-test.png")
        try {
            val pixels = ShareQrRenderer.encodeArgb(payload, 320)!!
            val bitmap = Bitmap.createBitmap(pixels, 320, 320, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            assertEquals(payload, AndroidQrImageDecoder.decode(context, Uri.fromFile(file)))
        } finally {
            file.delete()
        }
    }

    private fun Bitmap.toArgb(width: Int, height: Int): IntArray =
        IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
}
