package com.lavasec.app.account

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Shared wire contract with the Publisher verifier; also supplied to Play Billing at purchase. */
internal object GooglePlayAccountBinding {
    fun forUser(userId: String): String {
        val canonical = userId.lowercase(Locale.ROOT)
        require(UUID.fromString(canonical).toString() == canonical)
        return MessageDigest.getInstance("SHA-256")
            .digest("lavasec:google-play:v1:$canonical".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
