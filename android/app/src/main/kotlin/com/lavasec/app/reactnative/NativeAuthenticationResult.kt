package com.lavasec.app.reactnative

import org.json.JSONObject

/** Preserve native prompt cancellation across protected command families. */
internal fun authenticationResult(outcome: String, authenticationCancelled: Boolean): String {
    if (!authenticationCancelled) return outcome
    val result = JSONObject(outcome)
    // A late presentation dismissal must never overwrite an admitted success or its payload.
    if (result.getString("status") != "rejected") return outcome
    return result.put("status", "cancelled").put("reason", "authentication").toString()
}
