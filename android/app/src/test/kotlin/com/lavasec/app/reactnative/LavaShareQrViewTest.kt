package com.lavasec.app.reactnative

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.facebook.react.views.view.ReactViewGroup
import com.lavasec.app.product.FilterShareCardQrEncoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native leaf overflow regression; the shipping RN journey qualifies the real shared composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LavaShareQrViewTest {
    @Test fun `QR software export preserves earlier header later footer and all pixels outside its own box`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val surface = LavaShareCardSurfaceView(context).apply { setBackgroundColor(Color.WHITE) }
        val qrField = ReactViewGroup(context)
        assertFalse(surface.clipChildren); assertFalse(qrField.clipChildren)
        val payload = "https://lavasecurity.app/app/filter?code=native-qr-overflow-regression"
        val code = checkNotNull(FilterShareCardQrEncoder.encode(payload))
        val side = (code.size + 8) * 6
        val qr = LavaShareQrView(context).apply { this.payload = payload; moduleCount = code.size }
        val stripe = Color.rgb(242, 87, 46)
        val header = Color.rgb(84, 77, 71)
        val footer = Color.rgb(107, 99, 92)
        // Earlier/later colored siblings represent paint ordering only, never a replacement card.
        surface.addView(painted(context, stripe).apply { layout(0, 0, 1080, 12) })
        surface.addView(painted(context, header).apply { layout(99, 36, 981, 144) })
        surface.addView(qrField)
        qrField.addView(qr)
        surface.addView(painted(context, footer).apply { layout(99, 1278, 981, 1329) })
        surface.layout(0, 0, 1080, 1350)
        qrField.layout(99, 156, 981, 1200)
        qr.layout(117, 150, 117 + side, 150 + side)
        val qrBounds = Rect(0, 0, side, side)
        surface.offsetDescendantRectToMyCoords(qr, qrBounds)
        assertTrue(qrBounds.right < 1080 && qrBounds.bottom < 1278)
        val baseline = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        val rendered = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        try {
            qr.visibility = View.INVISIBLE
            surface.draw(Canvas(baseline))
            assertEquals(stripe, baseline.getPixel(540, 3))
            assertEquals(header, baseline.getPixel(540, 72))
            assertEquals(footer, baseline.getPixel(540, 1300))
            qr.visibility = View.VISIBLE
            surface.draw(Canvas(rendered))
            assertEquals("QR must not white-clear the earlier stripe", stripe, rendered.getPixel(540, 3))
            assertEquals("QR must not white-clear the earlier header", header, rendered.getPixel(540, 72))
            assertEquals("QR clip must be restored before the later footer draws", footer, rendered.getPixel(540, 1300))
            val before = IntArray(1080 * 1350); val after = IntArray(before.size)
            baseline.getPixels(before, 0, 1080, 0, 0, 1080, 1350)
            rendered.getPixels(after, 0, 1080, 0, 0, 1080, 1350)
            for (y in 0 until 1350) for (x in 0 until 1080) if (!qrBounds.contains(x, y)) {
                val index = y * 1080 + x
                if (before[index] != after[index]) assertEquals("Exterior pixel $x,$y", before[index], after[index])
            }
            assertTrue("All actual encoded QR modules and the four-module white border remain exact", qr.matchesCaptured(rendered, surface))
        } finally { baseline.recycle(); rendered.recycle() }
    }

    private fun painted(context: Context, color: Int) = View(context).apply { setBackgroundColor(color) }
}
