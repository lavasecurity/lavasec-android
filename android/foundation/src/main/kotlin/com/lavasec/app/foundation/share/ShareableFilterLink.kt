package com.lavasec.app.foundation.share

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Why a raw share input could not be turned into a [ShareableFilterConfiguration]. Mirrors the
 * canonical iOS `ShareableFilterInputError` so a recipient can be told the truth: a mistyped code,
 * a link that is not ours, and a genuine payload that arrived corrupted are different problems.
 */
sealed interface ShareableFilterInputError {
    data object UnrecognizedFormat : ShareableFilterInputError
    data object InvalidUniversalLink : ShareableFilterInputError
    data class ConfigurationCode(val error: ShareableFilterCodeError) : ShareableFilterInputError
}

sealed interface ShareableFilterLinkResult {
    data class Decoded(val configuration: ShareableFilterConfiguration) : ShareableFilterLinkResult
    data class Rejected(val error: ShareableFilterInputError) : ShareableFilterLinkResult
}

sealed interface ShareableFilterLinkCreationResult {
    data class Created(val url: String) : ShareableFilterLinkCreationResult
    data class Rejected(val error: ShareableFilterInputError) : ShareableFilterLinkCreationResult
}

/**
 * The single boundary that turns untrusted share input into a configuration, ported from the
 * canonical iOS `ShareableFilterLink` (pinned `c4658d9`).
 *
 * Every import origin funnels through [decode]; a second, laxer path is how a payload eventually
 * reaches the importer through a link nobody audited. The payload rides in the URL fragment
 * (never transmitted in HTTP requests, so a shared code never reaches a server or its logs), and
 * the parser never searches for `LF1-` inside arbitrary text — a substring search is how a hostile
 * page smuggles a payload past a host check.
 *
 * This establishes bounded authority, not authenticity: the integrity tag detects corruption, not
 * forgery. A link that survives this parser has earned the right to be *reviewed*, never applied.
 */
object ShareableFilterLink {
    /** The canonical, published prefix. Everything after it is the complete code. */
    const val CANONICAL_URL_PREFIX = "https://lavasecurity.app/app/import/#"

    const val CANONICAL_HOST = "lavasecurity.app"
    const val CANONICAL_PATH = "/app/import/"

    /**
     * base64url alphabet plus the `LF1-` prefix's own characters. Notably absent: `+`, `/`, `=`,
     * and `%` — so a percent-encoded fragment can never decode into something that looks legit.
     */
    private val ALLOWED_CODE_CHARACTERS: Set<Char> = buildSet {
        for (character in 'A'..'Z') add(character)
        for (character in 'a'..'z') add(character)
        for (character in '0'..'9') add(character)
        add('-')
        add('_')
    }

    private val MAX_CODE_LENGTH: Int =
        ShareableFilterCodec.CODE_PREFIX.length + ShareableFilterCodec.MAX_ENCODED_CODE_LENGTH

    private val MAX_URL_LENGTH: Int = CANONICAL_URL_PREFIX.length + MAX_CODE_LENGTH

    /** Wraps [configuration] in the canonical Universal Link; never trims a payload to fit. */
    fun urlFor(configuration: ShareableFilterConfiguration): ShareableFilterLinkCreationResult =
        urlForCode(ShareableFilterCodec.encode(configuration))

    /** Wraps an existing code — exactly the string the share sheet already holds. */
    fun urlForCode(code: String): ShareableFilterLinkCreationResult {
        val trimmed = code.trim()
        if (trimmed.length > MAX_CODE_LENGTH) {
            return ShareableFilterLinkCreationResult.Rejected(
                ShareableFilterInputError.ConfigurationCode(ShareableFilterCodeError.PayloadTooLarge),
            )
        }
        if (!isWellFormedCode(trimmed)) {
            return ShareableFilterLinkCreationResult.Rejected(
                ShareableFilterInputError.UnrecognizedFormat,
            )
        }
        return ShareableFilterLinkCreationResult.Created(CANONICAL_URL_PREFIX + trimmed)
    }

    /**
     * Decodes either a raw `LF1-…` code or the exact canonical Universal Link. Rejects everything
     * else, including a code embedded in surrounding text.
     */
    fun decode(rawInput: String): ShareableFilterLinkResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return rejected(ShareableFilterInputError.UnrecognizedFormat)
        }
        // Branch before bounding so each format reports its own precise reason.
        if (hasCodePrefix(trimmed)) {
            if (trimmed.length > MAX_CODE_LENGTH) {
                return rejected(
                    ShareableFilterInputError.ConfigurationCode(
                        ShareableFilterCodeError.PayloadTooLarge,
                    ),
                )
            }
            return decodeCode(trimmed)
        }

        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return rejected(ShareableFilterInputError.UnrecognizedFormat)
        }
        if (uri.scheme == null) {
            return rejected(ShareableFilterInputError.UnrecognizedFormat)
        }
        if (trimmed.length > MAX_URL_LENGTH) {
            return rejected(
                ShareableFilterInputError.ConfigurationCode(ShareableFilterCodeError.PayloadTooLarge),
            )
        }
        return decodeCanonicalLink(uri)
    }

    private fun decodeCanonicalLink(uri: URI): ShareableFilterLinkResult {
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return rejected(ShareableFilterInputError.InvalidUniversalLink)
        }
        // Userinfo is the classic confusable (`https://lavasecurity.app@evil.com/`); refusing it
        // outright removes the whole class. A port or query is also a deviation, and a query would
        // send the code to the server — the one thing the fragment design exists to prevent.
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null) {
            return rejected(ShareableFilterInputError.InvalidUniversalLink)
        }
        // Compare percent-encoded forms so `%2F` or an encoded homograph cannot masquerade.
        if (uri.rawAuthority?.lowercase(Locale.ROOT) != CANONICAL_HOST) {
            return rejected(ShareableFilterInputError.InvalidUniversalLink)
        }
        if (uri.rawPath != CANONICAL_PATH) {
            return rejected(ShareableFilterInputError.InvalidUniversalLink)
        }
        val fragment = uri.rawFragment
        if (fragment == null || !isWellFormedCode(fragment)) {
            return rejected(ShareableFilterInputError.InvalidUniversalLink)
        }
        return decodeCode(fragment)
    }

    private fun decodeCode(code: String): ShareableFilterLinkResult =
        when (val result = ShareableFilterCodec.decode(code)) {
            is ShareableFilterDecodeResult.Decoded ->
                ShareableFilterLinkResult.Decoded(result.configuration)
            is ShareableFilterDecodeResult.Rejected ->
                ShareableFilterLinkResult.Rejected(
                    ShareableFilterInputError.ConfigurationCode(result.error),
                )
        }

    /** Matches the codec, which accepts the prefix case-insensitively so a hand-typed code works. */
    private fun hasCodePrefix(value: String): Boolean =
        value.lowercase(Locale.ROOT)
            .startsWith(ShareableFilterCodec.CODE_PREFIX.lowercase(Locale.ROOT))

    /** A complete, self-consistent code: correct prefix, non-empty body, allowed alphabet only. */
    private fun isWellFormedCode(value: String): Boolean {
        if (!hasCodePrefix(value)) return false
        if (value.length <= ShareableFilterCodec.CODE_PREFIX.length) return false
        return value.all { it in ALLOWED_CODE_CHARACTERS }
    }

    private fun rejected(error: ShareableFilterInputError): ShareableFilterLinkResult.Rejected =
        ShareableFilterLinkResult.Rejected(error)
}
