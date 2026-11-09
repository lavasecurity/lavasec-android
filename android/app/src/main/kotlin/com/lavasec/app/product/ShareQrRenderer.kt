package com.lavasec.app.product

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream

/**
 * Renders the shareable-filter link as a QR card, the Android equivalent of the canonical iOS
 * `ShareableFilterCardRenderer`. The pure [encodeArgb] half is JVM-tested against the offline
 * reader in [QrCodeReader]; only the PNG/base64 step needs Android. No network, no cache — the
 * payload is a short share URL and the caller re-renders per query.
 */
internal object ShareQrRenderer {
    private const val SIZE = 512

    /** One QR matrix expanded to an ARGB_8888 pixel buffer, black modules on white. */
    fun encodeArgb(payload: String, size: Int = SIZE): IntArray? {
        if (payload.isBlank() || size <= 0) return null
        val matrix = runCatching {
            QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        }.getOrNull() ?: return null
        return IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (matrix.get(x, y)) BLACK else WHITE
        }
    }

    /** A `data:image/png;base64,…` card image, or null when encoding fails (never a fake image). */
    fun renderPngDataUri(payload: String, size: Int = SIZE): String? {
        val pixels = encodeArgb(payload, size) ?: return null
        val bitmap = runCatching {
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        return try {
            val bytes = ByteArrayOutputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) return null
                stream.toByteArray()
            }
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
