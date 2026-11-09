package com.lavasec.app.product

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lavasec.app.foundation.share.ShareableCustomBlocklistSource
import com.lavasec.app.foundation.share.ShareableFilterConfiguration
import org.junit.Assert.*
import org.junit.Test

class FilterShareCardQrTest {
    @Test fun `summary subtracts custom source identities and includes disabled definitions and exceptions`() {
        val configuration = ShareableFilterConfiguration(
            enabledBlocklistIDs = setOf("curated", "custom.enabled"),
            blockedDomains = setOf("blocked.example"), allowedDomains = setOf("allowed.example"),
            customBlocklists = listOf(
                ShareableCustomBlocklistSource("custom.enabled", "Source", "https://lists.example/a"),
                ShareableCustomBlocklistSource("custom.disabled", "Other", "https://lists.example/b"),
            ),
        )
        assertEquals(FilterShareCardSummary(1, 1, 2, 1), FilterShareCardSummary.from(configuration))
    }

    @Test fun `short link uses robust Q correction and decodes exactly`() {
        val payload = "https://lavasecurity.app/app/filter?code=LH1.TEST"
        val qr = requireNotNull(FilterShareCardQrEncoder.encode(payload))
        assertEquals(ErrorCorrectionLevel.Q, qr.correction)
        assertEquals(payload, decode(qr))
    }

    @Test fun `large link lowers correction while retaining every byte`() {
        val payload = "https://lavasecurity.app/app/filter?code=" + "x".repeat(2400)
        val qr = requireNotNull(FilterShareCardQrEncoder.encode(payload))
        assertEquals(ErrorCorrectionLevel.L, qr.correction)
        assertEquals(payload, decode(qr))
    }

    @Test fun `capacity exhaustion refuses the whole card without a truncated QR`() {
        assertNull(FilterShareCardQrEncoder.encode("x".repeat(5000)))
        assertNull(FilterShareCardQrEncoder.encode(" "))
    }

    private fun decode(qr: FilterShareCardQr): String {
        val scale = 4
        val width = (qr.size + 8) * scale
        val pixels = IntArray(width * width) { index ->
            val x = index % width / scale - 4; val y = index / width / scale - 4
            if (x in 0 until qr.size && y in 0 until qr.size && qr.modules[y][x]) 0xff000000.toInt() else -1
        }
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels)))).text
    }
}
