package com.lavasec.app.reactnative

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lavasec.app.product.QrCodeReader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** The shipping RN ShareDetail composes the pixels; no replacement React root or test layout. */
@RunWith(AndroidJUnit4::class)
class LavaReactShareCardJourneyTest {
    @Test fun shippingComposerRetainsFullQrAndDisclosureAcrossTenLocalesThenCancelsRealImageChooser() {
        ReactEmulatorJourney().use { journey ->
            journey.launch(); journey.ensureConfigured()
            val library = journey.application.foregroundProduct.currentFilterLibrary()
            val plus = journey.snapshot().getJSONObject("plus").getBoolean("enabled")
            val selected = journey.snapshot().getJSONObject("filters").getJSONArray("rows").let { rows ->
                (0 until rows.length()).map(rows::getJSONObject).first { it.getBoolean("active") }
            }
            val id = selected.getString("id")
            assertNotNull("The native owner must accept this real saved filter",
                journey.completed("share.query", JSONObject().put("id", id)).getJSONObject("value").optJSONObject("card"))
            journey.click("Guard", prefix = true, scroll = false)
            journey.click("Now filtering", prefix = true)
            journey.waitLabel("Switch or manage filters")
            journey.click("Now filtering", prefix = true)
            journey.waitLabel("Lava blocks these")
            journey.waitLabel("Share your filter")
            journey.click("Share your filter", scroll = false)
            journey.waitLabel("Show the QR code")
            val previousLocale = Locale.getDefault()
            var firstToken: String? = null
            try {
                for (tag in listOf("en", "de", "es", "fr", "it", "ja", "ko", "pt-BR", "zh-Hans", "zh-Hant")) {
                    Locale.setDefault(Locale.forLanguageTag(tag))
                    val content = journey.completed("share.query", JSONObject().put("id", id)).getJSONObject("value")
                    val card = content.getJSONObject("card")
                    val token = card.getString("token")
                    if (firstToken == null) firstToken = token
                    var rendered: Bitmap? = null
                    journey.await("$tag actual shared RN export surface", 25_000) {
                        journey.instrumentation.runOnMainSync {
                            rendered = LavaShareCardSurfaces.capture(token, content.getString("url"), journey.requireActivity())
                        }
                        rendered != null
                    }
                    val bitmap = requireNotNull(rendered)
                    try {
                        // Retain actual rendered pixels even when a visual assertion fails.
                        File(journey.context.cacheDir, "rn-share-card-controlled-$tag.png").outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        assertEquals(1080, bitmap.width); assertEquals(1350, bitmap.height)
                        assertEquals("$tag full saved payload", content.getString("url"), decode(bitmap))
                        val reduced = Bitmap.createScaledBitmap(bitmap, 540, 675, true)
                        try { assertEquals("$tag image downscale", content.getString("url"), decode(reduced)) } finally { reduced.recycle() }
                        if (bitmap.getPixel(540, 3) != Color.rgb(242, 87, 46)) {
                            journey.instrumentation.runOnMainSync { dumpSurface(journey.requireActivity().window.decorView) }
                        }
                        assertEquals(Color.WHITE, bitmap.getPixel(0, 1349))
                        assertEquals(Color.rgb(242, 87, 46), bitmap.getPixel(540, 3))
                        assertTrue("$tag actual recipient disclosure remains inside export", ink(bitmap, Color.rgb(107, 99, 92), 1180, 1339) > 20)
                    } finally { bitmap.recycle() }
                    println("LAVA_UI_SHARE_CARD locale=$tag pixels=1080x1350 payload=full decode=passed disclosure=visible composer=shipping-RN")
                }
            } finally { Locale.setDefault(previousLocale) }
            val current = journey.completed("share.query", JSONObject().put("id", id)).getJSONObject("value")
            val launchedToken = current.getJSONObject("card").getString("token")
            journey.await("current English export witness rendered", 25_000) {
                var captured: Bitmap? = null
                journey.instrumentation.runOnMainSync {
                    captured = LavaShareCardSurfaces.capture(launchedToken, current.getString("url"), journey.requireActivity())
                }
                captured?.let { it.recycle(); true } ?: false
            }
            journey.waitEnabled("Photo", true)
            val exportDirectory = File(journey.context.cacheDir, "filter-share-cards")
            val previousExports = canonicalExports(exportDirectory).map { it.name }.toSet()
            journey.click("Photo", scroll = false)
            journey.await("real Android image chooser", 20_000) {
                focusedApplicationWindow(journey, setOf("android", "com.android.intentresolver")) != null &&
                    canonicalExports(exportDirectory).any { it.name !in previousExports }
            }
            val newlyExported = canonicalExports(exportDirectory).filter { it.name !in previousExports }
            assertEquals("Exactly one PNG must be produced by this real Share action", 1, newlyExported.size)
            val exportedFile = newlyExported.single()
            val chooser = requireNotNull(focusedApplicationWindow(journey, setOf("android", "com.android.intentresolver")))
            val exported = requireNotNull(BitmapFactory.decodeFile(exportedFile.absolutePath)) {
                "The native chooser artifact ${exportedFile.name} must be a readable PNG"
            }
            try {
                assertEquals("Actual chooser artifact width", 1080, exported.width)
                assertEquals("Actual chooser artifact height", 1350, exported.height)
                assertEquals("Actual chooser artifact contains the full saved URL", current.getString("url"), decode(exported))
                val reduced = Bitmap.createScaledBitmap(exported, 540, 675, true)
                try {
                    assertEquals("Actual chooser artifact remains decodable at half size", current.getString("url"), decode(reduced))
                } finally { reduced.recycle() }
                assertEquals(Color.WHITE, exported.getPixel(0, 1349))
                assertEquals("Shared top stripe survives the actual producer", Color.rgb(242, 87, 46), exported.getPixel(540, 3))
                assertTrue("Actual chooser artifact retains recipient disclosure",
                    ink(exported, Color.rgb(107, 99, 92), 1180, 1339) > 20)
                // Shared FilterShareCard.tsx: these header roles sit above the QR, separately
                // from its black modules and the orange stripe. The native labels remain factual.
                assertTrue("Actual chooser artifact retains orange Lava Security header",
                    ink(exported, Color.rgb(242, 87, 46), 24, 100, 297, 981) > 20)
                assertTrue("Actual chooser artifact retains dark Shared filter header",
                    ink(exported, Color.rgb(20, 18, 15), 24, 100, 297, 981) > 20)
                assertTrue("Actual chooser artifact retains the original awake Guardian",
                    (36..161).sumOf { y -> (123 until 249).count { x ->
                        val pixel = exported.getPixel(x, y)
                        Color.red(pixel) > 200 && Color.green(pixel) in 60..130 && Color.blue(pixel) in 30..110
                    } } > 100)
                if (current.getJSONObject("card").getJSONArray("labels").length() > 0) {
                    assertTrue("Actual chooser artifact retains the saved-count chip background",
                        ink(exported, Color.rgb(244, 240, 235), 100, 220, 297, 981) > 100)
                    assertTrue("Actual chooser artifact retains the saved-count text",
                        ink(exported, Color.rgb(84, 77, 71), 100, 220, 297, 981) > 20)
                }
            } finally { exported.recycle() }
            assertNotNull("Inspect the actual exported PNG while its OS chooser is still focused",
                focusedApplicationWindow(journey, setOf("android", "com.android.intentresolver")))
            println("LAVA_UI_SHARE_CARD artifact=${exportedFile.name} new-artifacts=1 pixels=1080x1350 payload=full half-decode=passed header=visible disclosure=visible chooser-package=${chooser.root?.packageName} chooser-window=${chooser.id}")
            val chooserHost = journey.requireActivity()
            val chooserGeneration = checkNotNull(journey.application.foregroundProduct.state.value.graphGeneration)
            journey.back()
            journey.await("image chooser cancelled back to the same admitted Share owner") {
                val product = journey.application.foregroundProduct
                assertSame(chooserHost, journey.requireActivity())
                assertEquals(chooserGeneration, product.state.value.graphGeneration)
                var ready = false
                journey.instrumentation.runOnMainSync {
                    val security = product.securityStateForSnapshot(chooserGeneration)
                    ready = journey.application.nativeVisitHosts.current()?.owner === chooserHost &&
                        journey.application.reactHost.currentReactContext?.currentActivity === chooserHost &&
                        chooserHost.nativeHostAlive && chooserHost.foreground && chooserHost.hasWindowFocus() &&
                        chooserHost.isPresentationReady(chooserGeneration) &&
                        security?.isAuthorized(com.lavasec.app.foundation.security.ProtectedSurface.APP_UNLOCK) == true &&
                        security.isAuthorized(com.lavasec.app.foundation.security.ProtectedSurface.FILTERS)
                }
                ready && focusedApplicationWindow(journey, setOf(journey.context.packageName)) != null &&
                    journey.visible("Photo")
            }
            assertEquals("No export changes the real saved library", library, journey.application.foregroundProduct.currentFilterLibrary())
            assertEquals(plus, journey.snapshot().getJSONObject("plus").getBoolean("enabled"))
            val stale = journey.command("share.card", JSONObject().put("id", id).put("token", launchedToken))
            assertEquals("rejected", stale.getString("status"))
            assertEquals("stale-share", stale.getString("reason"))
            assertTrue("No text-link substitute", current.has("card"))
            println("LAVA_UI_SHARE_CARD chooser=image/png cancelled=true retired-token=refused saved-library=unchanged")
            journey.assertNoFallback()
        }
    }

    private fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return QrCodeReader.decodeArgb(pixels, bitmap.width, bitmap.height)
    }
    private fun canonicalExports(directory: File): List<File> = directory.listFiles().orEmpty().filter {
        it.isFile && it.name.matches(Regex("filter-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png"))
    }
    private fun focusedApplicationWindow(journey: ReactEmulatorJourney, packages: Set<String>): AccessibilityWindowInfo? =
        journey.instrumentation.uiAutomation.windows.singleOrNull { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive && window.isFocused &&
                window.root?.let { root -> root.refresh() && root.windowId == window.id && root.packageName?.toString() in packages } == true
        }
    private fun dumpSurface(root: android.view.View, inside: Boolean = false, depth: Int = 0) {
        val card = inside || root is LavaShareCardSurfaceView
        if (card) println("LAVA_CARD_CHILD depth=$depth type=${root.javaClass.simpleName} xy=${root.left},${root.top} size=${root.width}x${root.height} visibility=${root.visibility} alpha=${root.alpha} text=${(root as? android.widget.TextView)?.text}")
        if (root is android.view.ViewGroup) for (i in 0 until root.childCount) dumpSurface(root.getChildAt(i), card, if (card) depth + 1 else 0)
    }
    private fun ink(bitmap: Bitmap, color: Int, startY: Int, endY: Int, startX: Int = 99, endX: Int = 981): Int =
        (startY..endY).sumOf { y -> (startX until endX).count { x -> bitmap.getPixel(x, y) == color } }
}
