package com.lavasec.app.reactnative

import org.json.JSONObject

/** Fences a retained React root to the Activity that mounted it. */
internal object NativeHostBinding {
    // Untagged callers are the legacy native/instrumentation entry point. Every shipping app
    // root supplies its immutable launch nonce; a tagged caller must own a live current window.
    fun accepts(requested: String?, current: String?, destroyed: Boolean): Boolean =
        requested == null || (!destroyed && current != null && requested.isNotEmpty() && requested == current)

    fun accepts(request: JSONObject, current: String?, destroyed: Boolean): Boolean =
        accepts(if (request.has("nativeHostID")) request.optString("nativeHostID") else null, current, destroyed)

    /** Only exact read-free retirement capabilities can survive replacement of their window. */
    fun isExactRetirement(request: JSONObject): Boolean {
        if (request.optInt("schema") != 1) return false
        return when (request.optString("type")) {
            "domains.cancel" -> request.optString("token").isNotEmpty()
            "foreground.dismiss" -> request.optString("id").isNotEmpty()
            "filter.cancel" -> request.optString("id").isNotEmpty() &&
                request.optString("retirementGeneration").toLongOrNull() != null &&
                request.optString("retirementLease").isNotEmpty()
            "customEntry.dismiss" -> request.optString("id").isNotEmpty() &&
                request.optString("retirementGeneration").toLongOrNull() != null
            else -> false
        }
    }
}
