package com.lavasec.app.foundation.filter

/**
 * Why a user-supplied custom blocklist could not be fetched or cached, as a stable class.
 *
 * Each value is a fact about the resource or transfer, never about the URL: the bridge maps it
 * to a wire code and the RN layer to localized copy, so the URL never leaves the native layer.
 * [PARSE_FAILED] is reserved for a payload the parser rejects; the acquisition cache itself only
 * produces the transfer/storage values.
 */
enum class CustomBlocklistSourceFailure {
    NETWORK,
    HTTP_STATUS,
    EMPTY_BODY,
    TOO_LARGE,
    TIMEOUT,
    INSECURE,
    REDIRECT,
    STORAGE,
    PARSE_FAILED,
}

/**
 * One custom blocklist [sourceId] could not be acquired. [failure] carries the stable class so
 * the mutation owner can name the exact source and reason on the wire without carrying a
 * response body, URL, or exception.
 */
class CustomBlocklistSourceAcquisitionException(
    val sourceId: String,
    val failure: CustomBlocklistSourceFailure,
    val sourceName: String? = null,
) : Exception("custom blocklist source could not be acquired")
