package com.lavasec.app.backend

import java.security.MessageDigest
import java.util.Base64

internal enum class AndroidServiceAction(val path: String, val maximumBodyBytes: Int) {
    VERIFY_PURCHASE("/v1/android/entitlements/google-play-verify", 8 * 1024),
    SUBMIT_REPORT("/v1/android/bug-reports", 64 * 1024),
}

/** Native token stays between the platform SDK and the fixed application service. */
internal fun interface PlayIntegrityTokenPort {
    suspend fun token(requestHash: String): String
}

internal fun playIntegrityRequestHash(action: AndroidServiceAction, body: ByteArray): String {
    require(body.size <= action.maximumBodyBytes)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("POST\n${action.path}\n".toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(body))
}
