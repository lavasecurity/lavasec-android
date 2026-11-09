package com.lavasec.app.product

import android.content.Context
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.lavasec.app.R
import com.lavasec.app.foundation.share.ShareableFilterConfiguration
import java.text.NumberFormat
import java.util.Locale

/** Payload-derived counts only: the sender's chosen name never enters a recipient image. */
internal data class FilterShareCardSummary(
    val blocklists: Int, val blockedDomains: Int, val customLists: Int, val allowedDomains: Int,
) {
    companion object {
        fun from(configuration: ShareableFilterConfiguration): FilterShareCardSummary {
            val customIds = configuration.customBlocklists.map { it.id }.toSet()
            return FilterShareCardSummary(
                (configuration.enabledBlocklistIDs - customIds).size,
                configuration.blockedDomains.size, configuration.customBlocklists.size,
                configuration.allowedDomains.orEmpty().size,
            )
        }
    }
}

internal data class FilterShareCardQr(val modules: Array<BooleanArray>, val correction: ErrorCorrectionLevel) {
    val size: Int get() = modules.size
}

/** Full payload at Q, M, then L. Capacity failure never truncates or changes the link. */
internal object FilterShareCardQrEncoder {
    fun encode(payload: String): FilterShareCardQr? {
        if (payload.isBlank()) return null
        for (level in listOf(ErrorCorrectionLevel.Q, ErrorCorrectionLevel.M, ErrorCorrectionLevel.L)) {
            val matrix = runCatching {
                Encoder.encode(payload, level, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
            }.getOrNull() ?: continue
            return FilterShareCardQr(Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } }, level)
        }
        return null
    }
}

/** Native localizations of payload counts. Composition belongs to the shared RN component. */
internal object FilterShareCardCopy {
    fun labels(context: Context, summary: FilterShareCardSummary, locale: Locale = context.resources.configuration.locales[0] ?: Locale.ENGLISH): List<String> {
        val number = NumberFormat.getIntegerInstance(locale)
        val groups = listOf(
            Triple(summary.blocklists, R.string.share_card_blocklist_one, R.string.share_card_blocklist_many),
            Triple(summary.blockedDomains, R.string.share_card_blocked_one, R.string.share_card_blocked_many),
            Triple(summary.customLists, R.string.share_card_custom_one, R.string.share_card_custom_many),
            Triple(summary.allowedDomains, R.string.share_card_allowed_one, R.string.share_card_allowed_many),
        ).filter { it.first > 0 }.map { (count, singular, plural) -> context.getString(if (count == 1) singular else plural, number.format(count)) }
        return groups
    }
}
