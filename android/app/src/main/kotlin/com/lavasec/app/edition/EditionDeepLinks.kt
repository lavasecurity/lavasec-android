package com.lavasec.app.edition

import com.lavasec.app.presentation.ProductDeepLink
import com.lavasec.app.presentation.ProductNavigation
import java.net.URI

/** Same reviewed route vocabulary, with separate custom scheme ownership for coinstalled editions. */
internal object EditionDeepLinks {
    fun parse(value: String, edition: AppEdition = AppEdition.current): ProductNavigation? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return when {
            uri.scheme.equals(edition.deepLinkScheme, ignoreCase = true) ->
                ProductDeepLink.parse("lavasecurity:" + value.substringAfter(':'))
            uri.scheme.equals("https", ignoreCase = true) -> ProductDeepLink.parse(value)
            else -> null
        }
    }
}
