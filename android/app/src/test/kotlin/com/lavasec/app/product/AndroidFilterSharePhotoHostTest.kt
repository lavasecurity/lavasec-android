package com.lavasec.app.product

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidFilterSharePhotoHostTest {
    private class Registry : ActivityResultRegistry() {
        var code = -1
        var mime: String? = null
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            this.code = code
            mime = input as? String
        }
        fun picked(uri: Uri?) { dispatchResult(code, uri) }
    }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun awaitPick(result: AtomicReference<FilterShareDevicePick?>): FilterShareDevicePick {
        val end = System.nanoTime() + 5_000_000_000L
        while (result.get() == null && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return checkNotNull(result.get()) { "Photo acquisition did not finish" }
    }
    @Test fun `photo requests image content and decodes off main before one delivery`() {
        val registry = Registry()
        val result = AtomicReference<FilterShareDevicePick?>()
        val image = Uri.parse("content://synthetic/photo.png")
        var deliveries = 0
        val host = AndroidFilterShareDeviceHost(context, registry, documentMimeType = "image/*", decodeDocumentImage = {
            assertEquals(image, it)
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            "LF1-synthetic-acquired-code"
        })
        try {
            host.launchDocument { deliveries++; result.set(it) }
            assertEquals("image/*", registry.mime)
            registry.picked(image)
            assertEquals(FilterShareDevicePick.Acquired("LF1-synthetic-acquired-code"), awaitPick(result))
            registry.picked(image)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, deliveries)
        } finally { host.dispose() }
    }
    @Test fun `cancelled photo never decodes or manufactures a candidate`() {
        val registry = Registry()
        var pick: FilterShareDevicePick? = null
        var decoded = false
        val host = AndroidFilterShareDeviceHost(context, registry, documentMimeType = "image/*", decodeDocumentImage = {
            decoded = true; "unexpected"
        })
        try {
            host.launchDocument { pick = it }
            registry.picked(null)
            assertEquals(FilterShareDevicePick.Cancelled, pick)
            assertFalse(decoded)
        } finally { host.dispose() }
    }
    @Test fun `unreadable photo is a typed refusal`() {
        val registry = Registry()
        val result = AtomicReference<FilterShareDevicePick?>()
        val host = AndroidFilterShareDeviceHost(context, registry, documentMimeType = "image/*", decodeDocumentImage = { null })
        try {
            host.launchDocument { result.set(it) }
            registry.picked(Uri.parse("content://synthetic/invalid.png"))
            assertEquals(FilterShareDevicePick.Unavailable, awaitPick(result))
        } finally { host.dispose() }
    }
    @Test fun `disposed picker ignores a late result`() {
        val registry = Registry()
        var delivered = false
        val host = AndroidFilterShareDeviceHost(context, registry, documentMimeType = "image/*", decodeDocumentImage = { "late" })
        host.launchDocument { delivered = true }
        host.dispose()
        registry.picked(Uri.parse("content://synthetic/late.png"))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(delivered)
    }
    @Test fun `sampling bounds include rounded up pixel dimensions`() {
        assertEquals(4, AndroidQrImageDecoder.sampleSize(6399, 3000, 1600))
        assertEquals(8, AndroidQrImageDecoder.sampleSize(6401, 3000, 1600))
    }
    @Test fun `encoded image bound reads only one byte beyond capacity`() {
        var reads = 0
        val stream = object : java.io.InputStream() {
            override fun read(): Int { reads++; return 1 }
        }
        assertNull(AndroidQrImageDecoder.readBoundedImage(stream, limit = 64))
        assertEquals(65, reads)
        assertArrayEquals(byteArrayOf(1, 2), AndroidQrImageDecoder.readBoundedImage(byteArrayOf(1, 2).inputStream(), 2))
        assertNull(AndroidQrImageDecoder.readBoundedImage(byteArrayOf().inputStream(), 2))
    }
}
