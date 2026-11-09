package com.lavasec.app.foundation.share

import com.lavasec.app.foundation.backup.BackupJsonCodec
import com.lavasec.app.foundation.backup.BackupJsonSyntaxException
import com.lavasec.app.foundation.backup.BackupJsonValue
import com.lavasec.app.foundation.backup.asArrayOrNull
import com.lavasec.app.foundation.backup.asIntOrNull
import com.lavasec.app.foundation.backup.asObjectOrNull
import com.lavasec.app.foundation.backup.asStringOrNull
import com.lavasec.app.foundation.filter.FilterIdentityPolicy
import com.lavasec.app.foundation.filter.ManualDomainRules
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Why a Lava filter code was refused. Mirrors the canonical iOS
 * `ShareableFilterConfigurationCodeError` cases and adds the Android structural rejections
 * (duplicate/blank/oversized list members) the slice requires: a malformed payload must fail
 * closed, never be silently collapsed or rewritten into accepted settings.
 */
sealed interface ShareableFilterCodeError {
    /** The code does not start with the recognized `LF1-` envelope. */
    data object UnrecognizedFormat : ShareableFilterCodeError

    /** The code is structurally valid but the integrity tag does not match. */
    data object IntegrityCheckFailed : ShareableFilterCodeError

    /** The envelope advertises a schema this build cannot read. */
    data class UnsupportedVersion(val schemaVersion: Int) : ShareableFilterCodeError

    /** The decoded bytes are not a valid configuration payload. */
    data object MalformedPayload : ShareableFilterCodeError

    /** The encoded code, or its decompressed body, exceeds the import size budget. */
    data object PayloadTooLarge : ShareableFilterCodeError

    /** The same blocklist source id appears twice. */
    data object DuplicateSource : ShareableFilterCodeError

    /** The same blocked or allowed domain appears twice. */
    data object DuplicateDomain : ShareableFilterCodeError

    /** The same custom blocklist id appears twice. */
    data object DuplicateCustomSource : ShareableFilterCodeError

    data object TooManySources : ShareableFilterCodeError
    data object TooManyBlockedDomains : ShareableFilterCodeError
    data object TooManyAllowedDomains : ShareableFilterCodeError
    data object TooManyCustomSources : ShareableFilterCodeError

    /** A blocklist source id was blank. */
    data object BlankSource : ShareableFilterCodeError

    /** A blocklist source id exceeded the contract character budget. */
    data object SourceTooLong : ShareableFilterCodeError

    /** A custom blocklist definition was blank or exceeded the contract character budget. */
    data object InvalidCustomSource : ShareableFilterCodeError
}

sealed interface ShareableFilterDecodeResult {
    data class Decoded(val configuration: ShareableFilterConfiguration) : ShareableFilterDecodeResult

    data class Rejected(val error: ShareableFilterCodeError) : ShareableFilterDecodeResult
}

/**
 * The tamper-evident, size-bounded envelope that backs both the copyable text and the QR payload,
 * ported from the canonical iOS `ShareableFilterConfiguration` codec (pinned `c4658d9`).
 *
 * Wire shape: `LF1-` + base64url(6-byte SHA-256 tag ++ zlib(json body)). The integrity tag is a
 * corruption/accidental-tamper guard, NOT a signature — the code is meant to be shared, so there
 * is no shared secret. A decoded payload has earned the right to be *reviewed*, never to be
 * applied directly.
 *
 * The JSON body carries exactly the portable filter content (`v`, `lists`, `blocked`, `allowed`,
 * `custom`, and the v1-only `emoji`) with lexicographically sorted keys, so the same setup always
 * produces the same code. No credential, token, device, or local-cache field is representable.
 */
object ShareableFilterCodec {
    /** Human-recognizable prefix: "Lava Filters, format 1". */
    const val CODE_PREFIX = "LF1-"

    /** Cap on the encoded body (after [CODE_PREFIX]); far above any legitimate setup. */
    const val MAX_ENCODED_CODE_LENGTH = 16 * 1024

    /** Cap on the uncompressed JSON payload; guards a compression bomb in an untrusted code. */
    const val MAX_INFLATED_PAYLOAD_BYTES = 512 * 1024

    const val MAX_SOURCE_IDS = 4_096
    const val MAX_SOURCE_ID_CHARACTERS = 128
    const val MAX_CUSTOM_BLOCKLISTS = 256
    const val MAX_CUSTOM_STRING_CHARACTERS = 2_048

    val MAX_BLOCKED_DOMAINS: Int get() = ManualDomainRules.MAX_STORED_BLOCKED_DOMAINS
    val MAX_ALLOWED_DOMAINS: Int get() = ManualDomainRules.MAX_STORED_ALLOWED_DOMAINS

    internal const val INTEGRITY_TAG_BYTE_COUNT = 6

    fun encode(configuration: ShareableFilterConfiguration): String {
        val json = deterministicJson(configuration)
        val body = deflate(json.toByteArray(Charsets.UTF_8))
        val framed = integrityTag(body) + body
        return CODE_PREFIX + base64UrlEncode(framed)
    }

    fun decode(rawCode: String): ShareableFilterDecodeResult {
        val trimmed = rawCode.trim()
        if (!trimmed.lowercase(Locale.ROOT).startsWith(CODE_PREFIX.lowercase(Locale.ROOT))) {
            return ShareableFilterDecodeResult.Rejected(ShareableFilterCodeError.UnrecognizedFormat)
        }
        val encodedBody = trimmed.substring(CODE_PREFIX.length)
        if (encodedBody.length > MAX_ENCODED_CODE_LENGTH) {
            return ShareableFilterDecodeResult.Rejected(ShareableFilterCodeError.PayloadTooLarge)
        }
        val framed = base64UrlDecode(encodedBody)
            ?: return ShareableFilterDecodeResult.Rejected(ShareableFilterCodeError.UnrecognizedFormat)
        if (framed.size <= INTEGRITY_TAG_BYTE_COUNT) {
            return ShareableFilterDecodeResult.Rejected(ShareableFilterCodeError.UnrecognizedFormat)
        }
        val tag = framed.copyOfRange(0, INTEGRITY_TAG_BYTE_COUNT)
        val body = framed.copyOfRange(INTEGRITY_TAG_BYTE_COUNT, framed.size)
        if (!MessageDigest.isEqual(integrityTag(body), tag)) {
            return ShareableFilterDecodeResult.Rejected(ShareableFilterCodeError.IntegrityCheckFailed)
        }
        val jsonBytes = when (val outcome = boundedInflate(body, MAX_INFLATED_PAYLOAD_BYTES)) {
            is InflateOutcome.Inflated -> outcome.bytes
            InflateOutcome.TooLarge ->
                return ShareableFilterDecodeResult.Rejected(ShareableFilterCodeError.PayloadTooLarge)
            // Rare (mirroring iOS): the body is not a deflate stream; treat it as raw JSON.
            InflateOutcome.NotCompressed -> body
        }
        return decodeBody(jsonBytes)
    }

    /** Whether [configuration] fits BOTH the encoded-code and inflated-payload import caps. */
    fun fitsShareableCodeCapacity(configuration: ShareableFilterConfiguration): Boolean {
        val json = deterministicJson(configuration).toByteArray(Charsets.UTF_8)
        if (json.size > MAX_INFLATED_PAYLOAD_BYTES) return false
        val body = deflate(json)
        val encoded = base64UrlEncode(integrityTag(body) + body)
        return encoded.length <= MAX_ENCODED_CODE_LENGTH
    }

    /**
     * The deterministic JSON body. Sorted sets and custom lists sorted by id make the payload
     * reproducible; the narrow field set is the whole grammar the decoder accepts.
     */
    internal fun deterministicJson(configuration: ShareableFilterConfiguration): String {
        require(configuration.schemaVersion >= 1) { "schema version must be at least 1" }
        if (configuration.schemaVersion == 1) {
            require(configuration.allowedDomains == null) { "v1 payload carries no allowed set" }
        } else {
            require(configuration.allowedDomains != null) { "v2 payload requires its allowed set" }
        }
        val members = LinkedHashMap<String, BackupJsonValue>()
        if (configuration.schemaVersion == 1) {
            configuration.emoji?.let { members["emoji"] = BackupJsonValue.JsonString(it) }
        }
        members["v"] = BackupJsonValue.JsonNumber(configuration.schemaVersion.toString())
        members["lists"] = stringArray(configuration.enabledBlocklistIDs)
        members["blocked"] = stringArray(configuration.blockedDomains)
        if (configuration.schemaVersion >= 2) {
            members["allowed"] = stringArray(configuration.allowedDomains.orEmpty())
        }
        members["custom"] = BackupJsonValue.JsonArray(
            configuration.customBlocklists.sortedBy { it.id }.map { source ->
                BackupJsonValue.JsonObject(
                    linkedMapOf(
                        "i" to BackupJsonValue.JsonString(source.id),
                        "n" to BackupJsonValue.JsonString(source.displayName),
                        "u" to BackupJsonValue.JsonString(source.sourceUrl),
                        "f" to BackupJsonValue.JsonString(source.parseFormat.wireValue),
                    ),
                )
            },
        )
        return BackupJsonCodec.print(BackupJsonValue.JsonObject(members))
    }

    private fun decodeBody(jsonBytes: ByteArray): ShareableFilterDecodeResult {
        val text = String(jsonBytes, Charsets.UTF_8)
        val root = try {
            BackupJsonCodec.parse(text, MAX_INFLATED_PAYLOAD_BYTES).asObjectOrNull()
        } catch (_: BackupJsonSyntaxException) {
            return malformed()
        } catch (_: IllegalArgumentException) {
            return malformed()
        } ?: return malformed()

        val versionValue = root.optionalMember("v")
        val schemaVersion = if (versionValue == null) {
            ShareableFilterConfiguration.LEGACY_SCHEMA_VERSION
        } else {
            versionValue.asIntOrNull() ?: return malformed()
        }

        val sourceIds = readOptionalStringArray(root, "lists") ?: return malformed()
        val blocked = readOptionalStringArray(root, "blocked") ?: return malformed()
        val allowed = if (schemaVersion >= 2) {
            readRequiredStringArray(root, "allowed") ?: return malformed()
        } else {
            null
        }
        val custom = readCustomBlocklists(root) ?: return malformed()

        if (schemaVersion < 1 || schemaVersion > ShareableFilterConfiguration.CURRENT_SCHEMA_VERSION) {
            return ShareableFilterDecodeResult.Rejected(
                ShareableFilterCodeError.UnsupportedVersion(schemaVersion),
            )
        }

        checkSources(sourceIds)?.let { return ShareableFilterDecodeResult.Rejected(it) }
        checkDomains(blocked, MAX_BLOCKED_DOMAINS, ShareableFilterCodeError.TooManyBlockedDomains)
            ?.let { return ShareableFilterDecodeResult.Rejected(it) }
        allowed?.let { values ->
            checkDomains(values, MAX_ALLOWED_DOMAINS, ShareableFilterCodeError.TooManyAllowedDomains)
                ?.let { return ShareableFilterDecodeResult.Rejected(it) }
        }
        checkCustomSources(custom)?.let { return ShareableFilterDecodeResult.Rejected(it) }

        val emojiValue = root.optionalMember("emoji")
        val rawEmoji = if (emojiValue == null) {
            null
        } else {
            emojiValue.asStringOrNull() ?: return malformed()
        }
        val emoji = if (schemaVersion == 1) {
            rawEmoji?.takeIf(FilterIdentityPolicy::isValidEmoji)
        } else {
            null
        }

        return ShareableFilterDecodeResult.Decoded(
            ShareableFilterConfiguration(
                schemaVersion = schemaVersion,
                emoji = emoji,
                enabledBlocklistIDs = LinkedHashSet(sourceIds),
                blockedDomains = LinkedHashSet(blocked),
                customBlocklists = custom,
                allowedDomains = allowed?.let { LinkedHashSet(it) },
            ),
        )
    }

    private fun checkSources(sources: List<String>): ShareableFilterCodeError? {
        if (sources.size > MAX_SOURCE_IDS) return ShareableFilterCodeError.TooManySources
        val seen = LinkedHashSet<String>()
        for (source in sources) {
            if (source.isBlank()) return ShareableFilterCodeError.BlankSource
            if (source.length > MAX_SOURCE_ID_CHARACTERS) return ShareableFilterCodeError.SourceTooLong
            if (!seen.add(source)) return ShareableFilterCodeError.DuplicateSource
        }
        return null
    }

    private fun checkDomains(
        domains: List<String>,
        maximum: Int,
        tooMany: ShareableFilterCodeError,
    ): ShareableFilterCodeError? {
        if (domains.size > maximum) return tooMany
        val seen = LinkedHashSet<String>()
        for (domain in domains) {
            if (!seen.add(domain)) return ShareableFilterCodeError.DuplicateDomain
        }
        return null
    }

    private fun checkCustomSources(
        sources: List<ShareableCustomBlocklistSource>,
    ): ShareableFilterCodeError? {
        if (sources.size > MAX_CUSTOM_BLOCKLISTS) {
            return ShareableFilterCodeError.TooManyCustomSources
        }
        val seen = LinkedHashSet<String>()
        for (source in sources) {
            if (source.id.isBlank()) return ShareableFilterCodeError.InvalidCustomSource
            if (source.id.length > MAX_CUSTOM_STRING_CHARACTERS ||
                source.displayName.length > MAX_CUSTOM_STRING_CHARACTERS ||
                source.sourceUrl.length > MAX_CUSTOM_STRING_CHARACTERS
            ) {
                return ShareableFilterCodeError.InvalidCustomSource
            }
            if (!seen.add(source.id)) return ShareableFilterCodeError.DuplicateCustomSource
        }
        return null
    }

    private fun readOptionalStringArray(
        root: BackupJsonValue.JsonObject,
        key: String,
    ): List<String>? {
        val value = root.optionalMember(key) ?: return emptyList()
        return readStringArray(value)
    }

    private fun readRequiredStringArray(
        root: BackupJsonValue.JsonObject,
        key: String,
    ): List<String>? = readStringArray(root.members[key] ?: return null)

    /** A member treated as absent when missing OR JSON `null` (mirrors `decodeIfPresent`). */
    private fun BackupJsonValue.JsonObject.optionalMember(key: String): BackupJsonValue? =
        members[key]?.takeUnless { it is BackupJsonValue.JsonNull }

    private fun readStringArray(value: BackupJsonValue): List<String>? {
        val array = value.asArrayOrNull() ?: return null
        val result = ArrayList<String>(array.values.size)
        for (element in array.values) {
            result += element.asStringOrNull() ?: return null
        }
        return result
    }

    private fun readCustomBlocklists(
        root: BackupJsonValue.JsonObject,
    ): List<ShareableCustomBlocklistSource>? {
        val value = root.optionalMember("custom") ?: return emptyList()
        val array = value.asArrayOrNull() ?: return null
        val result = ArrayList<ShareableCustomBlocklistSource>(array.values.size)
        for (element in array.values) {
            val entry = element.asObjectOrNull() ?: return null
            val id = entry.members["i"]?.asStringOrNull() ?: return null
            val name = entry.members["n"]?.asStringOrNull() ?: return null
            val url = entry.members["u"]?.asStringOrNull() ?: return null
            val formatWire = entry.members["f"]?.asStringOrNull() ?: return null
            val format = ShareableBlocklistParseFormat.fromWire(formatWire) ?: return null
            result += ShareableCustomBlocklistSource(
                id = id,
                displayName = name,
                sourceUrl = url,
                parseFormat = format,
            )
        }
        return result
    }

    private fun stringArray(values: Collection<String>): BackupJsonValue =
        BackupJsonValue.JsonArray(values.sorted().map { BackupJsonValue.JsonString(it) })

    private fun base64UrlEncode(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun base64UrlDecode(value: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun integrityTag(body: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(body).copyOf(INTEGRITY_TAG_BYTE_COUNT)

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        deflater.setInput(data)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        try {
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
        } finally {
            deflater.end()
        }
        return output.toByteArray()
    }

    private sealed interface InflateOutcome {
        data class Inflated(val bytes: ByteArray) : InflateOutcome
        data object TooLarge : InflateOutcome
        data object NotCompressed : InflateOutcome
    }

    /** Streaming inverse of [deflate] that stops once output passes [limit]. */
    private fun boundedInflate(input: ByteArray, limit: Int): InflateOutcome {
        if (input.isEmpty()) return InflateOutcome.NotCompressed
        val inflater = Inflater()
        inflater.setInput(input)
        val buffer = ByteArray(32_768)
        val output = ByteArrayOutputStream()
        try {
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (_: DataFormatException) {
                    return InflateOutcome.NotCompressed
                }
                if (count == 0) {
                    return if (inflater.finished()) InflateOutcome.Inflated(output.toByteArray())
                    else InflateOutcome.NotCompressed
                }
                output.write(buffer, 0, count)
                if (output.size() > limit) return InflateOutcome.TooLarge
            }
        } finally {
            inflater.end()
        }
        return InflateOutcome.Inflated(output.toByteArray())
    }

    private fun malformed(): ShareableFilterDecodeResult.Rejected =
        ShareableFilterDecodeResult.Rejected(ShareableFilterCodeError.MalformedPayload)
}
