package com.lavasec.app.foundation.domain

import com.ibm.icu.text.IDNA
import com.ibm.icu.util.ICUException

/** Version-pinned non-transitional UTS46 conversion compatible with the pinned iOS URL host path. */
internal object IdnaHostCanonicalizer {
    fun toAscii(host: String): String {
        val output = StringBuilder(host.length)
        val info = IDNA.Info()
        try {
            UTS46.nameToASCII(host, output, info)
        } catch (error: ICUException) {
            throw IllegalArgumentException("ICU could not canonicalize hostname", error)
        }
        require(!info.hasErrors()) { "Invalid UTS46 hostname: ${info.errors}" }
        return output.toString()
    }

    private val UTS46 = IDNA.getUTS46Instance(
        IDNA.NONTRANSITIONAL_TO_ASCII or
            IDNA.USE_STD3_RULES or
            IDNA.CHECK_BIDI or
            IDNA.CHECK_CONTEXTJ,
    )
}
