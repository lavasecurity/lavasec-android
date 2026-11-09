package com.lavasec.app.product

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual read-granted FileProvider bytes. Shared RN composition is tested by its shipping journey. */
@RunWith(AndroidJUnit4::class)
class FilterShareCardDrawingInstrumentationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun actualCachedPngHasOnlyAReadGrantedImageChooserAndCanBeRemoved() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val bytes = try { java.io.ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() } } finally { bitmap.recycle() }
        val uri = requireNotNull(AndroidFilterShareCardArtifact.create(context, bytes))
        try {
            assertEquals("content", uri.scheme)
            assertEquals(context.packageName + ".filter-card", uri.authority)
            val reopened = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
            assertArrayEquals(bytes, reopened)
            val intent = requireNotNull(AndroidFilterShareCardArtifact.shareIntent(context, uri))
            assertEquals(Intent.ACTION_SEND, intent.action); assertEquals("image/png", intent.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            @Suppress("DEPRECATION")
            assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
            assertNull(AndroidFilterShareCardArtifact.shareIntent(context, Uri.parse("content://unrelated/private")))
        } finally { AndroidFilterShareCardArtifact.discard(context, uri) }
        assertTrue(runCatching { context.contentResolver.openInputStream(uri)?.close() }.isFailure)
        assertNull(AndroidFilterShareCardArtifact.create(context, byteArrayOf()))
        assertNull(AndroidFilterShareCardArtifact.create(context, ByteArray(8 * 1024 * 1024 + 1)))
    }

    @Test fun everyPrivateExportAuthorityResolvesItsOwnConcreteProviderAndExactFileRoot() {
        val fixtures = listOf(
            Triple("local-log-exports", "local-log-export", "controlled-provider-fixture.zip"),
            Triple("camera-captures", "camera", "controlled-provider-fixture.png"),
            Triple("filter-share-cards", "filter-card", "controlled-provider-fixture.png"),
        )
        val files = fixtures.map { (directory, _, filename) ->
            java.io.File(java.io.File(context.cacheDir, directory).apply { mkdirs() }, filename)
        }
        try {
            files.forEachIndexed { index, file -> file.writeBytes(byteArrayOf(11, index.toByte(), 33)) }
            // Exercise every authority twice, in opposite orders, within the same actual process.
            for (indices in listOf(fixtures.indices.toList(), fixtures.indices.reversed())) {
                for (index in indices) {
                    val authority = context.packageName + "." + fixtures[index].second
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, files[index])
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                    assertArrayEquals(byteArrayOf(11, index.toByte(), 33), bytes)
                }
            }
            val cardAuthority = context.packageName + ".filter-card"
            assertTrue("Card grants cannot address camera or logs", runCatching {
                androidx.core.content.FileProvider.getUriForFile(context, cardAuthority, files[0])
            }.isFailure)
            assertTrue(runCatching {
                androidx.core.content.FileProvider.getUriForFile(context, cardAuthority, files[1])
            }.isFailure)
        } finally { files.forEach { it.delete() } }
    }

}
