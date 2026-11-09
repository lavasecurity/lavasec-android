package com.lavasec.app.foundation.domain

import java.net.URI

/** A hostname or HTTP(S) URL reduced to the same hostname grammar as the DNS engine. */
data class DomainCheckInput(val domain: String, val ignoredURLParts: Boolean, val repairedScheme: Boolean) {
    companion object {
        fun parse(raw: String): DomainCheckInput {
            if (raw.toByteArray(Charsets.UTF_8).size > 4096) throw DomainCheckInputException("This address is too long. Enter its domain instead.")
            var input = raw.trim()
            if (input.isEmpty()) throw DomainCheckInputException("Enter a domain or URL.")
            fun invalid(): Nothing = throw DomainCheckInputException("Enter one valid domain or web URL.")
            if (input.any { it.isWhitespace() || Character.isSpaceChar(it) || it.isISOControl() } || '\\' in input) invalid()
            val repaired = input.startsWith("http//", true) || input.startsWith("https//", true)
            if (repaired) input = input.replaceFirst("//", "://")
            val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(input)
            val ignored = hasScheme || repaired || input.any { it in "/?#:" }
            val text = if (hasScheme) input else if (input.startsWith("//")) "https:$input" else "https://$input"
            val uri = try { URI(text) } catch (_: Exception) { invalid() }
            if (uri.scheme?.lowercase() !in setOf("http", "https")) invalid()
            val authority = uri.rawAuthority ?: invalid()
            if (authority.isEmpty() || authority.any { it in "%@[]" } || authority.endsWith(':')) invalid()
            val pieces = authority.split(':')
            if (pieces.size > 2) invalid()
            if (pieces.size == 2 && (pieces[1].isEmpty() || pieces[1].any { it !in '0'..'9' } || pieces[1].toIntOrNull() !in 1..65535)) invalid()
            val domain = try { DomainName.parse(pieces[0]).value } catch (_: DomainValidationException) { invalid() }
            return DomainCheckInput(domain, ignored, repaired)
        }
    }
}

/** Fixed copy only; never reflect an entered URL's credentials, path or query. */
class DomainCheckInputException(message: String) : IllegalArgumentException(message)
