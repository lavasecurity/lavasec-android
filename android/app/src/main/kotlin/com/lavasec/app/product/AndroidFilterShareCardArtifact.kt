package com.lavasec.app.product

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Private bounded image cache; grants exactly the selected image read access to the chooser. */
internal object AndroidFilterShareCardArtifact {
    private const val DIRECTORY = "filter-share-cards"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val KEEP = 8
    private const val RETAIN_MILLIS = 24L * 60L * 60L * 1000L

    @Synchronized
    fun create(context: Context, png: ByteArray, nowMillis: Long = System.currentTimeMillis()): Uri? {
        if (png.isEmpty() || png.size > MAX_BYTES) return null
        val directory = File(context.cacheDir, DIRECTORY)
        var file: File? = null
        return runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val entries = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".png") }
                .sortedByDescending { it.lastModified() }
            entries.forEachIndexed { index, old ->
                if (index >= KEEP - 1 || nowMillis - old.lastModified() > RETAIN_MILLIS) old.delete()
            }
            file = File(directory, "filter-${UUID.randomUUID()}.png")
            file!!.outputStream().use { it.write(png) }
            FileProvider.getUriForFile(context, "${context.packageName}.filter-card", file!!)
        }.getOrElse { file?.delete(); null }
    }

    fun shareIntent(context: Context, uri: Uri): Intent? {
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.filter-card") return null
        return Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply {
                clipData = ClipData.newUri(context.contentResolver, "Lava", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }

    fun discard(context: Context, uri: Uri) {
        if (uri.scheme == "content" && uri.authority == "${context.packageName}.filter-card") {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }
}
