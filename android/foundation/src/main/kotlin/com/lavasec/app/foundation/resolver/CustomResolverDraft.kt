package com.lavasec.app.foundation.resolver

import com.lavasec.app.foundation.domain.DomainName
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Typed custom-resolver draft, kept isolated from live runtime configuration until
 * the generation-fenced owner commits its validated endpoint. It mirrors the iOS custom-resolver
 * editor semantics (DNSResolverSettingsView.swift): name is optional, the
 * primary address is required unless the whole draft is being cleared, the
 * secondary address is optional and must use the same transport as the primary.
 *
 * Dirty state is derived, never stored: a draft has changes exactly when its
 * trimmed content differs from the saved entry. A caller that needs a discard
 * confirmation keys it off [hasChangesAgainst], matching the iOS
 * `customResolverHasUnsavedDraft` predicate.
 */
data class CustomResolverDraft(
    val name: String = "",
    val primaryAddress: String = "",
    val secondaryAddress: String = "",
) {
    val normalizedName: String?
        get() = name.trim().takeIf { it.isNotEmpty() }

    val trimmedPrimary: String get() = primaryAddress.trim()

    val trimmedSecondary: String get() = secondaryAddress.trim()

    /** True only when every field is empty after trimming (the iOS "clear" shape). */
    val isCleared: Boolean
        get() = trimmedPrimary.isEmpty() && trimmedSecondary.isEmpty() && normalizedName == null

    /** True when the draft differs from the saved entry in any trimmed field. */
    fun hasChangesAgainst(saved: CustomResolverDraft?): Boolean = !matches(saved)

    /** Exact trimmed equality against the saved entry (nil saved = a cleared saved entry). */
    fun matches(saved: CustomResolverDraft?): Boolean {
        val reference = saved ?: CustomResolverDraft()
        return normalizedName == reference.normalizedName &&
            trimmedPrimary == reference.trimmedPrimary &&
            trimmedSecondary == reference.trimmedSecondary
    }
}

/** Typed validation failures, mirroring the iOS validation vocabulary plus the Android absence. */
enum class CustomResolverValidationFailure {
    EMPTY_PRIMARY,
    UNSUPPORTED_FORMAT,
    MISSING_DOH_PATH,
    CREDENTIALS_NOT_ALLOWED,
    LOCALHOST_NOT_ALLOWED,
    UNUSABLE_RESOLVER_ADDRESS,
    BAD_STAMP,
    DOQ_UNSUPPORTED_ON_ANDROID,
    SECONDARY_TRANSPORT_MISMATCH,
}

sealed interface CustomResolverValidation {
    data object Valid : CustomResolverValidation

    data class Invalid(val reason: CustomResolverValidationFailure) : CustomResolverValidation
}

/** The transport a parsed custom address names; Android-runtime capability is checked separately. */
enum class ParsedCustomResolverTransport { PLAIN, DOH, DOT, DOQ }

/** One parsed custom endpoint, normalized for durable comparison. */
data class ParsedCustomResolverEndpoint(
    val transport: ParsedCustomResolverTransport,
    val normalized: String,
    /** Decoded route target. A stamp's normalized value remains its exact retained raw text. */
    val resolvedEndpoint: String = normalized,
    /** Literal bootstrap peers decoded from a supported stamp, IPv4 then IPv6 as on iOS. */
    val bootstrapAddresses: List<String> = emptyList(),
)

/**
 * Pure custom-resolver validation. Parsing mirrors the iOS
 * `DNSResolverPreset.customValidationMessage` rules (https://, tls://, dot://,
 * doq://, quic://, sdns://, bare IP addresses). Nothing here probes the network;
 * the runtime preserves the normalized endpoint and verifies its TLS identity.
 */
object CustomResolverValidator {
    fun validate(draft: CustomResolverDraft): CustomResolverValidation {
        if (draft.trimmedPrimary.isEmpty()) {
            return CustomResolverValidation.Invalid(CustomResolverValidationFailure.EMPTY_PRIMARY)
        }
        val primary = parse(draft.trimmedPrimary) ?: return CustomResolverValidation.Invalid(
            primaryFailure(draft.trimmedPrimary),
        )
        if (draft.trimmedSecondary.isEmpty()) return CustomResolverValidation.Valid
        val secondary = parse(draft.trimmedSecondary) ?: return CustomResolverValidation.Invalid(
            primaryFailure(draft.trimmedSecondary),
        )
        if (secondary.transport != primary.transport) {
            return CustomResolverValidation.Invalid(
                CustomResolverValidationFailure.SECONDARY_TRANSPORT_MISMATCH,
            )
        }
        return CustomResolverValidation.Valid
    }

    /**
     * The normalized DoH URL when [raw] parses as an https DoH endpoint, or null otherwise.
     * This is the value the runtime carries for a custom DoH resolver, so it is always the
     * parser's canonical `https://authority/path?query` form (or an admitted DNS stamp),
     * preserving encoded path/query data instead of decoding or re-encoding it.
     */
    fun normalizedDohUrl(raw: String): String? =
        parse(raw)
            ?.takeIf { it.transport == ParsedCustomResolverTransport.DOH }
            ?.normalized

    /**
     * The normalized DoT endpoint (`tls://host:port`, IPv6 bracketed) when [raw] parses as a
     * `tls://`/`dot://` endpoint, or null otherwise. This is the value the runtime carries for a
     * custom DoT resolver, so it is the parser's canonical form rather than the user's raw text.
     */
    fun normalizedDotEndpoint(raw: String): String? =
        parse(raw)
            ?.takeIf { it.transport == ParsedCustomResolverTransport.DOT }
            ?.let { if (it.normalized.startsWith("sdns://", ignoreCase = true)) it.normalized else "tls://${it.normalized}" }

    /** Canonical single usable IP literal, matching the portable custom resolver field. */
    fun normalizedPlainEndpoint(raw: String): String? = parse(raw)
        ?.takeIf { it.transport == ParsedCustomResolverTransport.PLAIN }?.normalized

    /** The exact canonical DoQ authority; RFC 9250 forbids UDP port 53. */
    fun normalizedDoqEndpoint(raw: String): String? = parse(raw)
        ?.takeIf { it.transport == ParsedCustomResolverTransport.DOQ }
        ?.let { if (it.normalized.startsWith("sdns://", ignoreCase = true)) it.normalized else "doq://${it.normalized}" }

    fun resolvedDohUrl(raw: String): String? = parse(raw)
        ?.takeIf { it.transport == ParsedCustomResolverTransport.DOH }?.resolvedEndpoint

    fun resolvedPlainAddress(raw: String): String? = parse(raw)
        ?.takeIf { it.transport == ParsedCustomResolverTransport.PLAIN }?.resolvedEndpoint

    fun bootstrapAddresses(raw: String): List<String> = parse(raw)?.bootstrapAddresses.orEmpty()

    /** Operational identity ignores a stamp's unused properties while retaining its bootstrap. */
    fun endpointIdentity(raw: String): String? = parse(raw)?.let {
        val route = when (it.transport) {
            ParsedCustomResolverTransport.DOT -> "tls://${it.resolvedEndpoint}"
            ParsedCustomResolverTransport.DOQ -> "doq://${it.resolvedEndpoint}"
            else -> it.resolvedEndpoint
        }
        route + if (it.transport == ParsedCustomResolverTransport.PLAIN) "" else "@${it.bootstrapAddresses.joinToString("+")}"
    }

    fun doqHostPort(normalized: String): Pair<String, Int>? {
        val endpoint = parse(normalized)?.takeIf { it.transport == ParsedCustomResolverTransport.DOQ } ?: return null
        val uri = URI("doq://${endpoint.resolvedEndpoint}")
        return (uri.host.removePrefix("[").removeSuffix("]")) to uri.port
    }

    /**
     * Splits a normalized DoT endpoint into its host and TCP port, or null when malformed.
     * Accepts the `tls://`/`dot://` scheme the parser emits; the host is returned without brackets.
     */
    fun dotHostPort(normalized: String): Pair<String, Int>? {
        val endpoint = parse(normalized)?.takeIf { it.transport == ParsedCustomResolverTransport.DOT } ?: return null
        val uri = runCatching { URI("tls://${endpoint.resolvedEndpoint}") }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.trim() ?: return null
        if (host.isEmpty() || !validResolverHost(host)) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val path = uri.path?.trim()
        if (path != null && path.isNotEmpty() && path != "/") return null
        val port = if (uri.port == -1) DOT_DEFAULT_PORT else uri.port
        if (port !in 1..65535) return null
        return host to port
    }

    /** Parses one raw address into a normalized endpoint, or null when the format is unsupported. */
    fun parse(raw: String): ParsedCustomResolverEndpoint? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val lower = value.lowercase()

        return when {
            lower.startsWith("https://") -> parseDoh(value)
            lower.startsWith("tls://") || lower.startsWith("dot://") -> parseHostPortTransport(
                value,
                defaultPort = 853,
                transport = ParsedCustomResolverTransport.DOT,
            )
            lower.startsWith("doq://") || lower.startsWith("quic://") -> parseHostPortTransport(
                value,
                defaultPort = 853,
                transport = ParsedCustomResolverTransport.DOQ,
            )?.takeUnless { URI("doq://${it.normalized}").port == 53 }
            lower.startsWith("sdns://") -> DNSStampParser.parse(value)
            else -> parsePlainAddresses(value)
        }
    }

    /**
     * Maps an unparseable primary/secondary to the specific validation failure,
     * mirroring iOS: a malformed known scheme reports that scheme's error, a
     * stamp reports bad-stamp, anything else is unsupported.
     */
    fun parseFailure(raw: String): CustomResolverValidationFailure {
        val value = raw.trim()
        val lower = value.lowercase()
        return when {
            lower.startsWith("sdns://") -> CustomResolverValidationFailure.BAD_STAMP
            lower.startsWith("https://") -> dohFailure(value)
            lower.startsWith("tls://") || lower.startsWith("dot://") ||
                lower.startsWith("doq://") || lower.startsWith("quic://") -> hostTransportFailure(value)
            else -> CustomResolverValidationFailure.UNSUPPORTED_FORMAT
        }
    }

    private fun primaryFailure(raw: String): CustomResolverValidationFailure = when {
        raw.contains("://") -> parseFailure(raw)
        else -> bareAddressFailure(raw)
    }

    /**
     * A bare value that did not parse either names an unusable address literal
     * (loopback / link-local / multicast) or is not an address at all. Only IP
     * literals are ever passed to InetAddress so validation stays pure (no DNS).
     */
    private fun bareAddressFailure(raw: String): CustomResolverValidationFailure {
        if (!isIpLiteral(raw)) return CustomResolverValidationFailure.UNSUPPORTED_FORMAT
        val unusable = runCatching { InetAddress.getByName(raw) }.getOrNull()
            ?.takeIf(::unusableResolverAddress) ?: return CustomResolverValidationFailure.UNSUPPORTED_FORMAT
        return if (unusable.isLoopbackAddress) {
            CustomResolverValidationFailure.LOCALHOST_NOT_ALLOWED
        } else {
            CustomResolverValidationFailure.UNUSABLE_RESOLVER_ADDRESS
        }
    }

    private fun parseDoh(value: String): ParsedCustomResolverEndpoint? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        val host = uri.host?.trim() ?: return null
        if (!validResolverHost(host)) return null
        if (uri.userInfo != null || uri.rawFragment != null) return null
        // A DoH endpoint must carry a connectable TCP port; accept the default
        // (-1) or any explicit 1..65535 value.
        if (uri.port != -1 && uri.port !in 1..65535) return null
        val path = uri.path?.trim() ?: return null
        if (path.isEmpty() || path == "/") return null
        return ParsedCustomResolverEndpoint(
            transport = ParsedCustomResolverTransport.DOH,
            // Query parameters can select the service/profile. Preserve their encoded bytes,
            // ordering and empty values, including a bare trailing '?'; fragments are never HTTP.
            normalized = "https://${uri.rawAuthority}${uri.rawPath}" +
                (uri.rawQuery?.let { "?$it" } ?: ""),
        )
    }

    private fun parseHostPortTransport(
        value: String,
        defaultPort: Int,
        transport: ParsedCustomResolverTransport,
    ): ParsedCustomResolverEndpoint? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.trim() ?: return null
        if (!validResolverHost(host)) return null
        if (uri.userInfo != null) return null
        if (uri.query != null || uri.fragment != null) return null
        val path = uri.path?.trim() ?: return null
        if (path.isNotEmpty() && path != "/") return null
        val port = if (uri.port == -1) defaultPort else uri.port
        if (port !in 1..65535) return null
        return ParsedCustomResolverEndpoint(
            transport = transport,
            // A literal IPv6 host carries colons, so it is bracketed to keep `host:port`
            // unambiguous for the durable/runtime value. `URI.getHost()` already returns an IPv6
            // literal *with* its brackets, so strip any existing pair before re-bracketing —
            // otherwise a valid IPv6 DoT endpoint becomes `[[addr]]:port` and is unparseable.
            normalized = if (host.contains(':')) {
                "[${host.removePrefix("[").removeSuffix("]")}]:$port"
            } else {
                "$host:$port"
            },
        )
    }

    private fun parsePlainAddresses(value: String): ParsedCustomResolverEndpoint? {
        val address = plainAddress(value) ?: return null
        return ParsedCustomResolverEndpoint(
            transport = ParsedCustomResolverTransport.PLAIN,
            normalized = address,
        )
    }

    /** One usable literal; separators and internal whitespace never become a stored endpoint. */
    private fun plainAddress(value: String): String? {
        if (!isIpLiteral(value)) return null
        // Only IP literals reach InetAddress, so parsing never triggers a DNS lookup.
        val parsed = runCatching { InetAddress.getByName(value) }.getOrNull() ?: return null
        if (parsed !is Inet4Address && parsed !is Inet6Address) return null
        if (unusableResolverAddress(parsed)) return null
        return parsed.hostAddress
    }

    /** Literal-shape guard: dotted-decimal IPv4, or an IPv6 token containing ':'. */
    private fun isIpLiteral(token: String): Boolean {
        if (IPV4_LITERAL.matches(token)) return true
        return token.contains(":") &&
            token.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }

    private fun validResolverHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (isLocalhostName(host)) return false
        // IP-literal hosts (dotted quad, or a bracket-stripped IPv6) satisfy
        // the same unusable-address contract as plain endpoints: loopback,
        // link-local, multicast, and unspecified literals are never a remote
        // resolver, even when wrapped in an encrypted scheme.
        val candidate = host.removePrefix("[").removeSuffix("]")
        if (IPV4_LITERAL.matches(candidate) || candidate.contains(":")) {
            val literal = runCatching { InetAddress.getByName(candidate) }.getOrNull()
                ?: return false
            if (unusableResolverAddress(literal)) return false
            return true
        }
        // Match the frozen resolver-host grammar, without resolving the name. Ordinary local
        // DNS names remain eligible; the localhost namespace and unusable literals do not.
        return runCatching { DomainName.parse(host) }.isSuccess
    }

    internal fun isValidResolverHost(host: String): Boolean = validResolverHost(host)

    private fun isLocalhostName(host: String): Boolean = host.equals("localhost", ignoreCase = true) ||
        host.endsWith(".localhost", ignoreCase = true)

    /** The precise literal-host failure (null when the host is not a rejected literal). */
    private fun literalHostFailure(host: String?): CustomResolverValidationFailure? {
        if (host == null) return null
        val candidate = host.removePrefix("[").removeSuffix("]")
        if (!IPV4_LITERAL.matches(candidate) && !candidate.contains(":")) return null
        val literal = runCatching { InetAddress.getByName(candidate) }.getOrNull() ?: return null
        return when {
            literal.isLoopbackAddress -> CustomResolverValidationFailure.LOCALHOST_NOT_ALLOWED
            unusableResolverAddress(literal) ->
                CustomResolverValidationFailure.UNUSABLE_RESOLVER_ADDRESS
            else -> null
        }
    }

    /**
     * Addresses that would never be a remote resolver: loopback, unspecified,
     * multicast, and link-local (the iOS NetworkEndpointValidator classes them
     * localhost / unusable).
     */
    private fun unusableResolverAddress(address: InetAddress): Boolean {
        val bytes = address.address
        fun unusableIpv4(octets: ByteArray): Boolean {
            val a = octets[0].toInt() and 255
            val b = octets[1].toInt() and 255
            val c = octets[2].toInt() and 255
            return a == 0 || a == 127 || (a == 169 && b == 254) || a >= 224 ||
                (a == 100 && b in 64..127) || (a == 192 && b == 0 && c in setOf(0, 2)) ||
                (a == 192 && b == 88 && c == 99) || (a == 198 && b in 18..19) ||
                (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113)
        }
        if (bytes.size == 4) return unusableIpv4(bytes)
        if (bytes.size != 16) return true
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress || address.isLinkLocalAddress) return true
        // Borrow embedded IPv4 scope for mapped, compatible and well-known NAT64 literals.
        val mapped = bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        val compatible = bytes.take(12).all { it == 0.toByte() }
        val nat64 = bytes[0] == 0.toByte() && bytes[1] == 0x64.toByte() && bytes[2] == 0xff.toByte() &&
            bytes[3] == 0x9b.toByte() && bytes.slice(4..11).all { it == 0.toByte() }
        if (mapped || compatible || nat64) return unusableIpv4(bytes.copyOfRange(12, 16))
        // ULA and local-use NAT64 remain usable private resolver addresses. Documentation
        // addresses are reserved, matching frozen NetworkEndpointValidator.IPAddressScope.
        return bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() && bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte()
    }

    private fun dohFailure(value: String): CustomResolverValidationFailure {
        val uri = runCatching { URI(value) }.getOrNull() ?: return CustomResolverValidationFailure.UNSUPPORTED_FORMAT
        if (uri.userInfo != null) return CustomResolverValidationFailure.CREDENTIALS_NOT_ALLOWED
        val host = uri.host?.trim()
        literalHostFailure(host)?.let { return it }
        if (host != null && isLocalhostName(host)) return CustomResolverValidationFailure.LOCALHOST_NOT_ALLOWED
        if (host != null && !validResolverHost(host)) return CustomResolverValidationFailure.UNSUPPORTED_FORMAT
        val path = uri.path?.trim()
        if (path != null && (path.isEmpty() || path == "/")) {
            return CustomResolverValidationFailure.MISSING_DOH_PATH
        }
        return CustomResolverValidationFailure.UNSUPPORTED_FORMAT
    }

    private fun hostTransportFailure(value: String): CustomResolverValidationFailure {
        val uri = runCatching { URI(value) }.getOrNull() ?: return CustomResolverValidationFailure.UNSUPPORTED_FORMAT
        if (uri.userInfo != null) return CustomResolverValidationFailure.CREDENTIALS_NOT_ALLOWED
        val host = uri.host?.trim()
        literalHostFailure(host)?.let { return it }
        if (host != null && isLocalhostName(host)) return CustomResolverValidationFailure.LOCALHOST_NOT_ALLOWED
        return CustomResolverValidationFailure.UNSUPPORTED_FORMAT
    }

    private val IPV4_LITERAL = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    private const val DOT_DEFAULT_PORT = 853
}
