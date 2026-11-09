package com.lavasec.app.reactnative

import com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure
import org.json.JSONObject

/** Shared production serialization for save/apply and catalog-refresh source failures. */
internal fun customBlocklistSourceFailureWireResult(
    sourceName: String?,
    failure: CustomBlocklistSourceFailure,
    sourceId: String? = null,
): String = JSONObject()
    .put("schema", 1)
    .put("status", "rejected")
    .put("reason", customBlocklistSourceFailureWireCode(failure))
    .apply {
        if (sourceName != null) put("value", sourceName)
        if (sourceId != null) put("sourceID", sourceId)
    }.toString()

/**
 * The stable wire code for one custom-blocklist fetch failure class. JS maps each code to
 * localized copy; the code carries no URL or other source detail.
 */
internal fun customBlocklistSourceFailureWireCode(failure: CustomBlocklistSourceFailure): String =
    when (failure) {
        CustomBlocklistSourceFailure.NETWORK -> "custom-blocklist-fetch-network"
        CustomBlocklistSourceFailure.HTTP_STATUS -> "custom-blocklist-fetch-http"
        CustomBlocklistSourceFailure.EMPTY_BODY -> "custom-blocklist-fetch-empty"
        CustomBlocklistSourceFailure.TOO_LARGE -> "custom-blocklist-fetch-too-large"
        CustomBlocklistSourceFailure.TIMEOUT -> "custom-blocklist-fetch-timeout"
        CustomBlocklistSourceFailure.INSECURE -> "custom-blocklist-fetch-insecure"
        CustomBlocklistSourceFailure.REDIRECT -> "custom-blocklist-fetch-redirect"
        CustomBlocklistSourceFailure.STORAGE -> "custom-blocklist-fetch-storage"
        CustomBlocklistSourceFailure.PARSE_FAILED -> "custom-blocklist-parse-failed"
    }
