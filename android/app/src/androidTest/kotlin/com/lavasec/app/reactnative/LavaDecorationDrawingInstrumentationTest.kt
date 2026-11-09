package com.lavasec.app.reactnative

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.uimanager.ReactStylesDiffMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native Canvas bytes from the same drawing routines the Fabric/Compose leaf calls.
 * These are controlled artwork/alpha fixtures, not live Guardian availability or visual review.
 */
@RunWith(AndroidJUnit4::class)
class LavaDecorationDrawingInstrumentationTest {
    @Test
    fun rankingPaintsThreeOrderedRoundedBarsAtTheRequestedPointSize() {
        bitmap(100, 100) { ranking ->
            LavaDecorationDrawing.drawRanking(Canvas(ranking), 100f, 100f, Color.GREEN)
            for (y in listOf(20, 50, 80)) assertEquals(Color.GREEN, ranking.getPixel(30, y))
            assertEquals(Color.GREEN, ranking.getPixel(85, 20))
            assertEquals(Color.GREEN, ranking.getPixel(62, 50))
            assertEquals(Color.GREEN, ranking.getPixel(38, 80))
            assertEquals(0, Color.alpha(ranking.getPixel(70, 50)))
            assertEquals(0, Color.alpha(ranking.getPixel(50, 80)))
            assertEquals(0, Color.alpha(ranking.getPixel(30, 35)))
            val widths = listOf(20, 50, 80).map { y -> (0 until 100).count { x -> Color.alpha(ranking.getPixel(x, y)) > 0 } }
            assertTrue("Ranking lengths descend in their actual native paint: $widths", widths[0] > widths[1] && widths[1] > widths[2])
        }
        bitmap(100, 100) { sized ->
            LavaDecorationDrawing.drawRanking(Canvas(sized), 100f, 100f, Color.GREEN, pointSize = 20f)
            val centre = sized.getPixel(50, 44)
            assertTrue("The explicit typographic size stays centred inside its slot", Color.alpha(centre) > 0)
            assertEquals("Subpixel antialiasing retains the requested tint", 255, Color.green(centre))
            assertEquals(0, Color.alpha(sized.getPixel(30, 20)))
            assertEquals(0, Color.alpha(sized.getPixel(65, 44)))
        }
    }

    @Test
    fun brandUsesTheFacelessFrozenContourAndLockedUsesDashedQuestionArtwork() {
        bitmap(220, 250) { brand ->
            LavaDecorationDrawing.drawBrand(Canvas(brand), 220f, 250f, Color.GREEN)
            assertEquals("The brand must fill its centre without a mascot face", Color.GREEN, brand.getPixel(110, 125))
            assertEquals("The rounded shoulder must retain the frozen contour", Color.GREEN, brand.getPixel(55, 40))
            assertEquals("The shield tapers near its tip", 0, Color.alpha(brand.getPixel(30, 225)))
            assertEquals("The outer corner is transparent", 0, Color.alpha(brand.getPixel(10, 10)))
            val filled = paintedPixels(brand)
            bitmap(220, 250) { locked ->
                LavaDecorationDrawing.drawLocked(Canvas(locked), 220f, 250f, 1f, Color.GRAY)
                assertTrue("The locked portrait paints an outline and question mark", paintedPixels(locked) > 0)
                assertTrue("The locked portrait is an outline rather than a sleeping filled look", paintedPixels(locked) < filled / 3)
                assertEquals("The interior beside the question mark stays clear", 0, Color.alpha(locked.getPixel(60, 125)))
                assertTrue("The centred question glyph must paint", paintedPixels(locked, Rect(70, 55, 150, 190)) > 100)
                val shoulder = Rect(15, 15, 75, 75)
                assertTrue("The real contour stroke paints its shoulder", paintedPixels(locked, shoulder) > 0)
                assertTrue("The stroke leaves actual dash gaps", transparentPixels(locked, shoulder) > 0)
            }
        }
    }

    @Test
    fun radialRevealMasksOnlyExistingGlyphInkAndRetainsTheSubduedBase() {
        bitmap(100, 100) { output ->
            val canvas = Canvas(output)
            val glyph = Rect(10, 35, 90, 65)
            canvas.drawRect(glyph, Paint().apply { color = Color.GRAY })
            val layer = canvas.saveLayer(0f, 0f, 100f, 100f, null)
            canvas.drawRect(glyph, Paint().apply { color = Color.GREEN })
            LavaDecorationDrawing.applyRevealMask(canvas, 100f, 100f, 50f, 50f, 25f, 5f)
            canvas.restoreToCount(layer)
            assertEquals("The active glyph centre has the requested tint", Color.GREEN, output.getPixel(50, 50))
            assertEquals("Outside the aperture the same glyph retains secondary ink", Color.GRAY, output.getPixel(12, 50))
            assertEquals("No spotlight can paint outside the glyph strokes", 0, Color.alpha(output.getPixel(50, 20)))
            val feathered = output.getPixel(72, 50)
            assertTrue("The frozen five-point edge softens rather than a hard circular cut", feathered != Color.GREEN && feathered != Color.GRAY)
        }
        bitmap(100, 100) { alpha ->
            val canvas = Canvas(alpha)
            canvas.drawColor(Color.GREEN)
            LavaDecorationDrawing.applyRevealMask(canvas, 100f, 100f, 50f, 50f, 25f, 5f)
            assertEquals(255, Color.alpha(alpha.getPixel(50, 50)))
            assertTrue(Color.alpha(alpha.getPixel(72, 50)) in 1..254)
            assertEquals(0, Color.alpha(alpha.getPixel(80, 50)))
        }
    }

    @Test
    fun allKnownTintRolesUseActualLightAndDarkResourceInk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = mapOf(
            "primary" to (0xff263a2bL to 0xfff6f1e5L),
            "ink" to (0xff263a2bL to 0xfff6f1e5L),
            "error" to (0xffdb332eL to 0xffff7366L),
            "white" to (0xfffffaf1L to 0xffedf3ebL),
            "accentOrange" to (0xfff2572eL to 0xffff8a57L),
            "secondary" to (0xff59685bL to 0xffbdbdb6L),
            "tertiary" to (0xff68756aL to 0xff969b95L),
            "orange" to (0xffbf4017L to 0xffff8a57L),
            "green" to (0xff246743L to 0xff81bf9dL),
        )
        for (dark in listOf(false, true)) {
            val fixture = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
            expected.forEach { (role, inks) ->
                val actual = fixture.getColor(LavaDecorationPolicy.colorResource(role))
                assertEquals("$role dark=$dark uses the frozen semantic ink", (if (dark) inks.second else inks.first).toInt(), actual)
                bitmap(40, 40) { drawing ->
                    LavaDecorationDrawing.drawBrand(Canvas(drawing), 40f, 40f, actual)
                    assertEquals("$role must reach actual native paint", actual, drawing.getPixel(20, 20))
                }
            }
        }
    }

    @Test
    fun nativeManagerRetainsFullRevealAndFontPropsWithoutOwningSemantics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = LavaDecorationView(instrumentation.targetContext)
            val manager = LavaDecorationManager()
            manager.symbol(view, "lava.shield.fill")
            manager.mood(view, "locked")
            manager.colorScheme(view, "dark")
            manager.fontPointSize(view, 17f)
            manager.fontWeight(view, "bold")
            manager.revealEnabled(view, true)
            manager.revealVisible(view, false)
            manager.revealX(view, -12f)
            manager.revealY(view, 30f)
            manager.revealRadius(view, 24f)
            assertEquals("dark", view.colorScheme)
            assertEquals(17f, view.fontPointSize, 0f)
            assertEquals("bold", view.fontWeight)
            assertTrue(view.revealEnabled)
            assertFalse(view.revealVisible)
            assertEquals(-12f, view.revealX, 0f)
            assertEquals(30f, view.revealY, 0f)
            assertEquals(24f, view.revealRadius, 0f)
            assertFalse("The shared row owns the accessibility stop", view.isClickable)
            assertFalse(view.isFocusable)
            manager.fontPointSize(view, Float.NaN)
            manager.revealRadius(view, -1f)
            assertEquals(0f, view.fontPointSize, 0f)
            assertEquals(22f, view.revealRadius, 0f)
            view.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
            assertEquals(120, view.measuredWidth)
            assertEquals(80, view.measuredHeight)
        }
    }

    @Test
    fun decorativeNativeSubtreeSurvivesBasePropResetAndInterceptsHoverWithoutInterceptingTouch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = LavaDecorationView(instrumentation.targetContext)
            val manager = LavaDecorationManager()
            for (importance in listOf("auto", "yes")) {
                // Exercise the actual inherited BaseViewManager prop and transaction callback.
                manager.updateProperties(view, ReactStylesDiffMap(JavaOnlyMap.of("importantForAccessibility", importance)))
                assertEquals("Fabric/base props cannot expose the decorative host", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    view.importantForAccessibility)
            }
            assertEquals("The private Compose drawing cannot expose virtual glyph semantics",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, view.getChildAt(0).importantForAccessibility)
            assertFalse(view.isClickable)
            assertFalse(view.isFocusable)
            val hovered = mutableListOf<Int>()
            val touched = mutableListOf<Int>()
            val child = object : View(instrumentation.targetContext) {
                override fun onHoverEvent(event: MotionEvent): Boolean {
                    hovered += event.actionMasked
                    return true
                }
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    touched += event.actionMasked
                    return true
                }
            }
            view.addView(child)
            view.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 120, 80)
            child.layout(0, 0, 120, 80)
            val now = SystemClock.uptimeMillis()
            fun dispatch(action: Int, hover: Boolean) {
                val event = MotionEvent.obtain(now, now, action, 60f, 40f, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try {
                    if (hover) view.dispatchGenericMotionEvent(event)
                    else assertTrue("Ordinary touch still reaches its native child", view.dispatchTouchEvent(event))
                } finally { event.recycle() }
            }
            // A widget-dispatch fixture only; genuine InputReader/TalkBack qualification is separate.
            for (action in listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT)) {
                dispatch(action, hover = true)
            }
            assertTrue("A greedy drawing child must receive no hover target", hovered.isEmpty())
            dispatch(MotionEvent.ACTION_DOWN, hover = false)
            dispatch(MotionEvent.ACTION_UP, hover = false)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), touched)
        }
    }

    private fun bitmap(width: Int, height: Int, block: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { block(bitmap) } finally { bitmap.recycle() }
    }
    private fun paintedPixels(bitmap: Bitmap, bounds: Rect = Rect(0, 0, bitmap.width, bitmap.height)): Int =
        (bounds.top until bounds.bottom).sumOf { y -> (bounds.left until bounds.right).count { x -> Color.alpha(bitmap.getPixel(x, y)) != 0 } }
    private fun transparentPixels(bitmap: Bitmap, bounds: Rect): Int =
        (bounds.top until bounds.bottom).sumOf { y -> (bounds.left until bounds.right).count { x -> Color.alpha(bitmap.getPixel(x, y)) == 0 } }
}
