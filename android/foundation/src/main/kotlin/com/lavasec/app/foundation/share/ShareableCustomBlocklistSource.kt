package com.lavasec.app.foundation.share

import com.lavasec.app.foundation.domain.DomainName
import com.lavasec.app.foundation.domain.DomainValidationException
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Canonical custom-blocklist parse format, ported from iOS
 * `CatalogBlocklistSource.CatalogParseFormat` (pinned `c4658d9`). The wire value is the raw
 * string ("auto", "plain_domains", "hosts", "adblock", "dnsmasq"); an unrecognized value is a
 * malformed payload, never a silently defaulted format.
 */
enum class ShareableBlocklistParseFormat(val wireValue: String) {
    AUTO("auto"),
    PLAIN_DOMAINS("plain_domains"),
    HOSTS("hosts"),
    ADBLOCK("adblock"),
    DNSMASQ("dnsmasq"),
    ;

    companion object {
        fun fromWire(value: String): ShareableBlocklistParseFormat? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The portable definition of a user-supplied custom blocklist, matching the canonical iOS wire
 * `WireCustomBlocklist` (`i`/`n`/`u`/`f`).
 *
 * Deliberately absent: `createdAt` and `lastAcceptedHash`. Those are device-local cache/metadata
 * the recipient regenerates, and including them would let a share payload carry local cache
 * internals. Two sources are the same definition when all four wire fields match.
 */
data class ShareableCustomBlocklistSource(
    val id: String,
    val displayName: String,
    val sourceUrl: String,
    val parseFormat: ShareableBlocklistParseFormat = ShareableBlocklistParseFormat.AUTO,
) {
    init {
        require(id.isNotBlank()) { "custom source id must not be blank" }
    }

    /** The portable identity a review compares: the wire definition, nothing local. */
    val definitionIdentity: String =
        listOf(id, displayName, sourceUrl, parseFormat.wireValue).joinToString("|")
}

/**
 * Why a custom blocklist URL cannot be fetched. Mirrors iOS
 * `CustomBlocklistSourceError`/`NetworkEndpointValidationError` closely enough for the import
 * plan's `unsafeSource` drop; an LH1 code is unsigned, so every imported source is re-validated
 * here rather than trusted.
 */
enum class ShareableSourceUrlRejection {
    INVALID_URL,
    UNSUPPORTED_SCHEME,
    MISSING_HOST,
    PRIVATE_NETWORK_HOST,
    CREDENTIALS_NOT_ALLOWED,
}

/**
 * The public-source URL gate used when importing an unsigned share payload, ported from iOS
 * `CustomBlocklistSource.init` + `NetworkEndpointValidator.validatePublicSourceURL`
 * (pinned `c4658d9`).
 *
 * Only an HTTPS URL with a non-empty, non-credentialed host that is either a public IP literal
 * or a valid hostname is accepted. Localhost names, private/loopback/link-local/reserved IP
 * literals (IPv4 and IPv6, including IPv4-mapped and NAT64 embeddings) are refused so a crafted
 * code cannot point the syncer at an SSRF target.
 */
object ShareableSourceUrlValidator {
    /** Returns `null` when [rawUrl] is acceptable, otherwise the specific rejection. */
    fun validate(rawUrl: String): ShareableSourceUrlRejection? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ShareableSourceUrlRejection.INVALID_URL
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return ShareableSourceUrlRejection.INVALID_URL
        }
        if (uri.scheme == null) return ShareableSourceUrlRejection.INVALID_URL
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return ShareableSourceUrlRejection.UNSUPPORTED_SCHEME
        }
        if (uri.userInfo != null) return ShareableSourceUrlRejection.CREDENTIALS_NOT_ALLOWED
        val rawHost = uri.host?.trim().orEmpty()
        if (rawHost.isEmpty()) return ShareableSourceUrlRejection.MISSING_HOST

        var host = rawHost
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length - 1)
        }
        if (isLocalhostName(host)) return ShareableSourceUrlRejection.PRIVATE_NETWORK_HOST

        // Reject alternate numeric spellings before they can be interpreted as hostnames.
        // URL clients disagree about shortened, integer, hex and octal IPv4 forms.
        val numericParts = host.trimEnd('.').split('.')
        if (numericParts.all { it.matches(Regex("(?:[0-9]+|0[xX][0-9a-fA-F]+)")) } &&
            (host.endsWith('.') || numericParts.size != 4 || numericParts.any {
                it.length > 1 && it.startsWith('0')
            } || ipv4Octets(host) == null)) return ShareableSourceUrlRejection.PRIVATE_NETWORK_HOST

        val scope = ipAddressScope(host)
        if (scope != ShareableIpScope.NOT_AN_IP) {
            return if (scope.isPublic) null else ShareableSourceUrlRejection.PRIVATE_NETWORK_HOST
        }

        return try {
            DomainName.parse(host)
            null
        } catch (_: DomainValidationException) {
            ShareableSourceUrlRejection.INVALID_URL
        }
    }

    fun isAcceptable(rawUrl: String): Boolean = validate(rawUrl) == null

    /**
     * Applies the URL literal policy to an already resolved address, without another lookup.
     * A public hostname is only syntactic admission; its complete DNS answer set must pass this
     * gate before a source transport opens a connection.
     */
    fun isPublicAddress(address: InetAddress): Boolean = addressScope(address).isPublic

    private fun isLocalhostName(value: String): Boolean {
        val host = value.trim().trimEnd('.').lowercase(Locale.ROOT)
        return host == "localhost" || host.endsWith(".localhost")
    }
}

/** The scope of an IP literal; only [PUBLIC] may be fetched as a blocklist source. */
private enum class ShareableIpScope(val isPublic: Boolean) {
    PUBLIC(true),
    PRIVATE(false),
    LOOPBACK(false),
    LINK_LOCAL(false),
    MULTICAST(false),
    UNSPECIFIED(false),
    RESERVED(false),
    NOT_AN_IP(false),
}

private fun ipAddressScope(value: String): ShareableIpScope {
    ipv4Octets(value)?.let { return classifyIpv4(it) }
    if (':' !in value) return ShareableIpScope.NOT_AN_IP
    if (!IPV6_LITERAL.matches(value)) return ShareableIpScope.NOT_AN_IP
    val address = try {
        InetAddress.getByName(value)
    } catch (_: Exception) {
        return ShareableIpScope.NOT_AN_IP
    }
    return addressScope(address)
}

private fun addressScope(address: InetAddress): ShareableIpScope = when {
    address.isAnyLocalAddress -> ShareableIpScope.UNSPECIFIED
    address.isLoopbackAddress -> ShareableIpScope.LOOPBACK
    address.isLinkLocalAddress -> ShareableIpScope.LINK_LOCAL
    address.isSiteLocalAddress -> ShareableIpScope.PRIVATE
    address.isMulticastAddress -> ShareableIpScope.MULTICAST
    address.address.size == 4 -> classifyIpv4(address.address)
    address.address.size == 16 -> classifyIpv6(address.address)
    else -> ShareableIpScope.RESERVED
}

private fun ipv4Octets(value: String): ByteArray? {
    if (':' in value) return null
    val pieces = value.split('.')
    if (pieces.size != 4) return null
    val octets = ByteArray(4)
    for (index in 0 until 4) {
        val piece = pieces[index]
        if (piece.isEmpty() || piece.length > 3 || piece.any { it !in '0'..'9' }) return null
        val number = piece.toInt()
        if (number !in 0..255) return null
        octets[index] = number.toByte()
    }
    return octets
}

private fun classifyIpv4(bytes: ByteArray): ShareableIpScope {
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val third = bytes[2].toInt() and 0xff
    return when {
        first == 0 -> ShareableIpScope.UNSPECIFIED
        first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168) ->
            ShareableIpScope.PRIVATE
        first == 127 -> ShareableIpScope.LOOPBACK
        first == 169 && second == 254 -> ShareableIpScope.LINK_LOCAL
        first in 224..239 -> ShareableIpScope.MULTICAST
        first in 240..255 ||
            (first == 100 && second in 64..127) ||
            (first == 192 && second == 0 && (third == 0 || third == 2)) ||
            (first == 192 && second == 88 && third == 99) ||
            (first == 198 && second in 18..19) ||
            (first == 198 && second == 51 && third == 100) ||
            (first == 203 && second == 0 && third == 113) -> ShareableIpScope.RESERVED
        else -> ShareableIpScope.PUBLIC
    }
}

private fun classifyIpv6(bytes: ByteArray): ShareableIpScope {
    if (bytes.all { it.toInt() == 0 }) return ShareableIpScope.UNSPECIFIED
    if (bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1) {
        return ShareableIpScope.LOOPBACK
    }
    // IPv4-mapped (::ffff:a.b.c.d): borrow the embedded address's scope so a mapped private or
    // loopback literal cannot masquerade as a public v6 source.
    if (bytes.take(10).all { it.toInt() == 0 } &&
        bytes[10].toInt() and 0xff == 0xff &&
        bytes[11].toInt() and 0xff == 0xff
    ) {
        return classifyIpv4(bytes.copyOfRange(12, 16))
    }
    // NAT64 well-known prefix (64:ff9b::/96): the literal resolves to the embedded address.
    if (bytes[0].toInt() == 0x00 && bytes[1].toInt() == 0x64 &&
        bytes[2].toInt() and 0xff == 0xff && bytes[3].toInt() and 0xff == 0x9b
    ) {
        if (bytes.copyOfRange(4, 12).all { it.toInt() == 0 }) {
            return classifyIpv4(bytes.copyOfRange(12, 16))
        }
        if (bytes[4].toInt() == 0 && bytes[5].toInt() == 1) {
            return ShareableIpScope.PRIVATE
        }
    }
    // IPv4-compatible (::a.b.c.d, deprecated): borrow the embedded scope.
    if (bytes.take(12).all { it.toInt() == 0 }) {
        return classifyIpv4(bytes.copyOfRange(12, 16))
    }
    return when {
        bytes[0].toInt() and 0xff == 0xff -> ShareableIpScope.MULTICAST
        bytes[0].toInt() and 0xfe == 0xfc -> ShareableIpScope.PRIVATE
        bytes[0].toInt() and 0xff == 0xfe && bytes[1].toInt() and 0xc0 == 0x80 ->
            ShareableIpScope.LINK_LOCAL
        bytes[0].toInt() == 0x20 && bytes[1].toInt() == 0x01 &&
            bytes[2].toInt() == 0x0d && bytes[3].toInt() and 0xff == 0xb8 ->
            ShareableIpScope.RESERVED
        else -> ShareableIpScope.PUBLIC
    }
}

private val IPV6_LITERAL = Regex("^[0-9a-fA-F:.%]+$")
