package com.lavasec.app.product

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Decodes one QR code from an ARGB_8888 pixel buffer with ZXing's offline codec. Deliberately free
 * of Android types so it is unit-tested on the JVM with a generated code; only the URI/Bitmap
 * plumbing lives in [AndroidQrImageDecoder]. The camera import path reads a code, it never renders
 * one, so `QR_CODE` is the only format requested.
 */
internal object QrCodeReader {
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        return runCatching { reader.decode(bitmap).text }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/**
 * Android adapter for [QrCodeReader]: decode a captured image URI into the QR payload. A camera
 * capture can be 12–48 MP, so the image is first bounds-probed and DOWN-SAMPLED to at most
 * [MAX_DIMENSION] on the long edge before the pixel/luminance buffers are allocated — a full-size
 * decode would allocate hundreds of megabytes and can OOM or ANR. Callers must invoke this off the
 * main thread. Bounded and best-effort: a missing/undecodable image returns `null` so the import
 * flow reports its explicit refusal instead of fabricating a candidate.
 */
internal object AndroidQrImageDecoder {
    /** Long-edge cap after sub-sampling; a QR remains decodable well above this. */
    private const val MAX_DIMENSION = 1600

    fun decode(context: Context, uri: Uri): String? {
        // Freeze one bounded stream. A provider cannot substitute a larger image between the
        // bounds probe and decode; encoded bytes and decoded pixel allocations both stay bounded.
        val encoded = runCatching {
            context.contentResolver.openInputStream(uri)?.use { readBoundedImage(it) }
        }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        }.getOrNull() ?: return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            bitmap.recycle()
            return null
        }
        return try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            QrCodeReader.decodeArgb(pixels, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    internal fun readBoundedImage(stream: java.io.InputStream, limit: Int = 16 * 1024 * 1024): ByteArray? {
        require(limit > 0 && limit < Int.MAX_VALUE)
        return java.io.ByteArrayOutputStream(minOf(limit, 8192)).use { output ->
            val buffer = ByteArray(minOf(limit + 1, 8192))
            while (output.size() <= limit) {
                val count = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                if (count == 0) {
                    val single = stream.read()
                    if (single < 0) break
                    output.write(single)
                } else output.write(buffer, 0, count)
            }
            if (output.size() == 0 || output.size() > limit) null else output.toByteArray()
        }
    }

    /** The smallest power-of-two sample that keeps the long edge at or below [maxDimension]. */
    internal fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sample = 1
        // Compare the already-sampled long edge, not the next halving, so the loop does not stop
        // one power of two early (6399px must sample to 1600px with inSampleSize = 4, not 2).
        while ((maxOf(width, height).toLong() + sample - 1) / sample > maxDimension) sample *= 2
        return sample
    }
}
