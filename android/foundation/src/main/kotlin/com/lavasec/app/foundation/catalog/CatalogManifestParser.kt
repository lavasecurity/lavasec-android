package com.lavasec.app.foundation.catalog

import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Outcome of validating one remote catalog document.
 *
 * [Accepted] carries a fully validated, immutable manifest; [Rejected] names exactly why the
 * document was refused. There is no partial result: a manifest either decodes under every bound
 * or it is discarded, so the caller always has a safe fallback.
 */
sealed interface CatalogParseResult {
    data class Accepted(val manifest: RemoteCatalogManifest) : CatalogParseResult

    data class Rejected(val failure: CatalogParseFailure, val detail: String? = null) : CatalogParseResult
}

/**
 * Bounded, pure validator/parser for a remote blocklist catalog manifest.
 *
 * This is the Android form of the iOS decode boundary (`BlocklistCatalog.init(from:)` plus
 * `validateDecodedMetadata`), hardened: the schema is decoded by [CatalogJsonDecoder], which
 * refuses oversized, truncated, duplicate-key, non-integer and over-long input before any object
 * is built. On top of that this parser rejects unknown keys, missing required fields, unsupported
 * schema versions and parse formats, duplicate source ids, non-public HTTPS source URLs and
 * out-of-range metadata.
 */
object CatalogManifestParser {
    fun parse(bytes: ByteArray): CatalogParseResult {
        if (bytes.size > CatalogLimits.MAX_DOCUMENT_BYTES) {
            return CatalogParseResult.Rejected(CatalogParseFailure.DOCUMENT_TOO_LARGE)
        }
        val document = try {
            CatalogJsonDecoder.decode(bytes)
        } catch (error: CatalogJsonException) {
            return CatalogParseResult.Rejected(error.failure)
        }
        if (document !is CatalogJson.Obj) {
            return CatalogParseResult.Rejected(
                CatalogParseFailure.INVALID_FIELD_TYPE,
                "root must be an object",
            )
        }
        return try {
            CatalogParseResult.Accepted(decodeManifest(document))
        } catch (error: CatalogJsonException) {
            CatalogParseResult.Rejected(error.failure)
        }
    }

    /**
     * Verifies the fetch envelope's out-of-band revision/SHA-256 claims against the document
     * before parsing it. A mismatch is rejected; a claim that is absent is simply not checked.
     */
    fun parseEnvelope(envelope: CatalogFetchEnvelope): CatalogParseResult {
        if (envelope.bytes.size > CatalogLimits.MAX_DOCUMENT_BYTES) {
            return CatalogParseResult.Rejected(CatalogParseFailure.DOCUMENT_TOO_LARGE)
        }
        envelope.claimedSha256?.let { claimed ->
            if (!isCanonicalSha256(claimed) ||
                !claimed.equals(sha256Hex(envelope.bytes), ignoreCase = true)
            ) {
                return CatalogParseResult.Rejected(CatalogParseFailure.CHECKSUM_MISMATCH)
            }
        }
        return when (val parsed = parse(envelope.bytes)) {
            is CatalogParseResult.Rejected -> parsed
            is CatalogParseResult.Accepted -> {
                val claimedRevision = envelope.claimedRevision
                if (claimedRevision != null && claimedRevision != parsed.manifest.catalogVersion) {
                    CatalogParseResult.Rejected(CatalogParseFailure.REVISION_MISMATCH)
                } else {
                    parsed
                }
            }
        }
    }

    private fun decodeManifest(root: CatalogJson.Obj): RemoteCatalogManifest {
        rejectUnknownKeys(root, MANIFEST_KEYS)
        val schemaVersion = root.requireInt("schema_version")
        if (schemaVersion != RemoteCatalogManifest.SUPPORTED_SCHEMA_VERSION) {
            throw CatalogJsonException(CatalogParseFailure.UNSUPPORTED_SCHEMA_VERSION)
        }
        val catalogVersion = root.requireString("catalog_version")
        if (catalogVersion.isBlank()) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
        }
        val generatedAt = parseTimestamp(root.requireString("generated_at"))
        val sources = root.requireArray("sources").mapIndexed { index, value ->
            decodeSource("sources[$index]", value)
        }
        val guardrails = root.requireArray("guardrails").mapIndexed { index, value ->
            decodeSource("guardrails[$index]", value).markedAsGuardrail()
        }
        validateMetadata(sources, guardrails)
        return RemoteCatalogManifest(
            schemaVersion = schemaVersion,
            catalogVersion = catalogVersion,
            generatedAtEpochSeconds = generatedAt,
            sources = sources,
            guardrails = guardrails,
        )
    }

    private fun decodeSource(label: String, value: CatalogJson): CatalogSource {
        if (value !is CatalogJson.Obj) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_TYPE)
        }
        rejectUnknownKeys(value, SOURCE_KEYS)
        val parseFormat = CatalogParseFormat.fromWire(value.requireString("parse_format"))
            ?: throw CatalogJsonException(CatalogParseFailure.UNSUPPORTED_PARSE_FORMAT)
        val acceptedHashes = value.optionalArray("accepted_source_hashes")
            .orEmpty()
            .mapIndexed { index, hash -> decodeAcceptedHash("$label.accepted_source_hashes[$index]", hash) }
        return CatalogSource(
            id = value.requireString("id"),
            name = value.requireString("name"),
            category = value.requireString("category"),
            riskLevel = value.optionalString("risk_level") ?: DEFAULT_RISK_LEVEL,
            defaultEnabled = value.requireBool("default_enabled"),
            licenseName = value.requireString("license_name"),
            attribution = value.requireString("attribution"),
            projectUrl = value.requireString("project_url"),
            sourceUrl = value.requireString("source_url"),
            versionId = value.requireString("version_id"),
            entryCount = value.requireInt("entry_count"),
            byteSize = value.requireLong("byte_size"),
            sourceHash = value.requireString("source_hash"),
            acceptedSourceHashes = acceptedHashes,
            normalizedHash = value.optionalString("normalized_hash") ?: "",
            publishedAtEpochSeconds = parseTimestamp(value.requireString("published_at")),
            redistributionMode = value.requireString("redistribution_mode"),
            parseFormat = parseFormat,
            licenseTextUrl = value.optionalString("license_text_url"),
            noticeUrl = value.optionalString("notice_url"),
        )
    }

    private fun decodeAcceptedHash(label: String, value: CatalogJson): CatalogAcceptedSourceHash {
        if (value !is CatalogJson.Obj) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_TYPE)
        }
        rejectUnknownKeys(value, ACCEPTED_HASH_KEYS)
        val sha256 = value.requireString("sha256")
        if (!isCanonicalSha256(sha256)) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
        }
        val byteSize = value.optionalLong("byte_size")
        if (byteSize != null && byteSize < 0) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
        }
        val entryCount = value.optionalInt("entry_count")
        if (entryCount != null && entryCount !in 0..CatalogLimits.MAX_SOURCE_ENTRY_COUNT) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
        }
        val status = value.optionalString("status") ?: CatalogAcceptedSourceHash.STATUS_ACCEPTED
        if (status.isBlank()) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
        }
        return CatalogAcceptedSourceHash(
            sha256 = sha256,
            byteSize = byteSize,
            entryCount = entryCount,
            reviewedAtEpochSeconds = value.optionalString("reviewed_at")?.let { parseTimestamp(it) },
            expiresAtEpochSeconds = value.optionalString("expires_at")?.let { parseTimestamp(it) },
            status = status,
        )
    }

    private fun validateMetadata(sources: List<CatalogSource>, guardrails: List<CatalogSource>) {
        val entries = sources + guardrails
        if (entries.size > CatalogLimits.MAX_SOURCES) {
            throw CatalogJsonException(CatalogParseFailure.TOO_MANY_SOURCES)
        }
        val identifiers = HashSet<String>()
        for (source in entries) {
            validateSource(source, identifiers)
        }
    }

    private fun validateSource(source: CatalogSource, identifiers: MutableSet<String>) {
        if (source.id.isEmpty() ||
            source.id.encodeToByteArray().size > CatalogLimits.MAX_SOURCE_ID_BYTES ||
            source.id == "." ||
            source.id == ".." ||
            !source.id.all { it in IDENTIFIER_CHARACTERS }
        ) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
        }
        if (!identifiers.add(source.id.lowercase())) {
            throw CatalogJsonException(CatalogParseFailure.DUPLICATE_SOURCE_ID)
        }
        if (source.versionId.encodeToByteArray().size > CatalogLimits.MAX_VERSION_ID_BYTES) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
        }
        if (source.entryCount !in 0..CatalogLimits.MAX_SOURCE_ENTRY_COUNT || source.byteSize < 0) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
        }
        if ((source.sourceHash.isNotEmpty() && !isCanonicalSha256(source.sourceHash)) ||
            (source.normalizedHash.isNotEmpty() && !isCanonicalSha256(source.normalizedHash))
        ) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
        }
        if (source.category.isBlank() || source.projectUrl.isBlank()) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
        }
        if (!CatalogSourceUrlValidator.isPublicHttpsUrl(source.sourceUrl)) {
            throw CatalogJsonException(CatalogParseFailure.INVALID_SOURCE_URL)
        }
    }

    private val IDENTIFIER_CHARACTERS: Set<Char> =
        (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('.', '_', '-')).toSet()

    private const val DEFAULT_RISK_LEVEL = "normal"

    private val MANIFEST_KEYS = setOf(
        "schema_version",
        "catalog_version",
        "generated_at",
        "sources",
        "guardrails",
    )

    private val SOURCE_KEYS = setOf(
        "id",
        "name",
        "category",
        "risk_level",
        "default_enabled",
        "license_name",
        "attribution",
        "project_url",
        "source_url",
        "version_id",
        "entry_count",
        "byte_size",
        "source_hash",
        "accepted_source_hashes",
        "normalized_hash",
        "published_at",
        "redistribution_mode",
        "parse_format",
        "license_text_url",
        "notice_url",
    )

    private val ACCEPTED_HASH_KEYS = setOf(
        "sha256",
        "byte_size",
        "entry_count",
        "reviewed_at",
        "expires_at",
        "status",
    )
}

/** ISO-8601 catalog timestamps, matching the iOS decoder's fractional/non-fractional tolerance. */
internal object CatalogTimestamps {
    fun parseEpochSeconds(value: String): Long? = try {
        Instant.parse(value).epochSecond
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(value).toEpochSecond()
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Pure (no I/O) public-HTTPS check for a source URL. DNS re-resolution / rebinding defence is
 * deliberately out of scope here and remains the transport layer's job; this rejects the
 * obviously-hostile shapes (non-HTTPS, credentials, loopback/private/link-local IP literals and
 * local-only hostnames) before a URL is ever handed to a fetcher.
 */
internal object CatalogSourceUrlValidator {
    private val LOCAL_HOST_SUFFIXES = listOf(".localhost", ".local", ".internal", ".home.arpa")

    fun isPublicHttpsUrl(raw: String): Boolean {
        if (raw.isBlank()) return false
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]")?.trimEnd('.') ?: return false
        if (host.isEmpty()) return false
        if (isLocalHostname(host)) return false
        return when {
            host.contains(':') -> isPublicIpv6Literal(host)
            host.all { it.isDigit() || it == '.' } -> parseIpv4(host)?.let(::isPublicIpv4) ?: false
            else -> true
        }
    }

    private fun isLocalHostname(host: String): Boolean =
        host == "localhost" || LOCAL_HOST_SUFFIXES.any { host.endsWith(it) }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in 0 until 4) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[index] = value
        }
        return octets
    }

    private fun isPublicIpv4(octets: IntArray): Boolean {
        val first = octets[0]
        val second = octets[1]
        val third = octets[2]
        return when {
            first == 0 || first == 10 || first == 127 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 && third in listOf(0, 2) -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun isPublicIpv6Literal(host: String): Boolean {
        val address = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            return false
        }
        if (address.isLoopbackAddress || address.isAnyLocalAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address
        if (bytes.size == 4) {
            return isPublicIpv4(IntArray(4) { bytes[it].toInt() and 0xff })
        }
        // Unique local addresses fc00::/7 are not covered by the deprecated site-local flag.
        if ((bytes[0].toInt() and 0xfe) == 0xfc) return false
        return true
    }
}

internal fun isCanonicalSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun parseTimestamp(value: String): Long =
    CatalogTimestamps.parseEpochSeconds(value)
        ?: throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)

private fun rejectUnknownKeys(objectValue: CatalogJson.Obj, known: Set<String>) {
    if (objectValue.fields.keys.any { it !in known }) {
        throw CatalogJsonException(CatalogParseFailure.UNKNOWN_KEY)
    }
}

private fun CatalogJson.Obj.requireString(key: String): String =
    when (val value = require(key)) {
        is CatalogJson.Str -> value.value
        else -> throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_TYPE)
    }

private fun CatalogJson.Obj.optionalString(key: String): String? =
    when (val value = fields[key]) {
        null, CatalogJson.Null -> null
        is CatalogJson.Str -> value.value
        else -> throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_TYPE)
    }

private fun CatalogJson.Obj.requireBool(key: String): Boolean =
    when (val value = require(key)) {
        is CatalogJson.Bool -> value.value
        else -> throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_TYPE)
    }

private fun CatalogJson.Obj.requireInt(key: String): Int {
    val value = when (val raw = require(key)) {
        is CatalogJson.Num -> raw.value
        else -> throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_TYPE)
    }
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
    }
    return value.toInt()
}

private fun CatalogJson.Obj.optionalInt(key: String): Int? {
    val value = optionalLong(key) ?: return null
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_VALUE)
    }
    return value.toInt()
}

private fun CatalogJson.Obj.requireLong(key: String): Long =
    when (val value = require(key)) {
        is CatalogJson.Num -> value.value
        else -> throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_TYPE)
    }

private fun CatalogJson.Obj.optionalLong(key: String): Long? =
    when (val value = fields[key]) {
        null, CatalogJson.Null -> null
        is CatalogJson.Num -> value.value
        else -> throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_TYPE)
    }

private fun CatalogJson.Obj.requireArray(key: String): List<CatalogJson> =
    when (val value = require(key)) {
        is CatalogJson.Arr -> value.items
        else -> throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_TYPE)
    }

private fun CatalogJson.Obj.optionalArray(key: String): List<CatalogJson>? =
    when (val value = fields[key]) {
        null, CatalogJson.Null -> null
        is CatalogJson.Arr -> value.items
        else -> throw CatalogJsonException(CatalogParseFailure.INVALID_FIELD_TYPE)
    }

private fun CatalogJson.Obj.require(key: String): CatalogJson =
    fields[key] ?: throw CatalogJsonException(CatalogParseFailure.MISSING_REQUIRED_FIELD)
