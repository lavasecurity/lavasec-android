package com.lavasec.app.foundation.chained

import java.util.Base64

/**
 * Why a chained upstream could not be staged from a WireGuard `.conf`.
 *
 * Distinct cases because the operator reads them from a device log: "which key of my file is
 * wrong", "this directive is in the wrong section", and "the parsed values were well-formed but
 * the configuration refused them" are different problems with different fixes. Mirrors iOS
 * `ChainedUpstreamStagingRefusal` minus its build-identity cases, which are an iOS Keychain
 * concern with no Android counterpart.
 */
sealed interface ChainedConfRefusal {
    /** A `.conf` shape this feature does not carry, named rather than dropped. */
    data class UnsupportedDirective(val directive: String) : ChainedConfRefusal {
        override val logValue: String get() = "staging-unsupported-$directive"
    }

    /** A required key is absent, or present more than once. Names the key. */
    data class MissingOrRepeatedKey(val key: String) : ChainedConfRefusal {
        override val logValue: String get() = "staging-missing-key-$key"
    }

    /** A key this parser supports, in a section it does not belong to. */
    data class DirectiveInWrongSection(
        val directive: String,
        val section: String,
    ) : ChainedConfRefusal {
        override val logValue: String
            get() = "staging-wrong-section-$directive-${section.lowercase().trim('[', ']')}"
    }

    /** A key's value is not the shape the key requires. Names the key. */
    data class MalformedValue(val key: String) : ChainedConfRefusal {
        override val logValue: String get() = "staging-malformed-$key"
    }

    /**
     * A non-empty, non-section line carries no `=`.
     *
     * Refused rather than dropped: a missing-equals typo such as `MTU 1400` or
     * `PersistentKeepalive 25` would otherwise vanish and the session would silently run the
     * default, which is the "behaves differently from the pasted file" divergence this parser
     * exists to prevent. The raw line is not carried, so file content cannot reach a log.
     */
    data object MalformedLine : ChainedConfRefusal {
        override val logValue: String get() = "staging-malformed-line"
    }

    /** An assignment whose key carries no owning `[Interface]`/`[Peer]` section. */
    data class DirectiveOutsideSection(val directive: String) : ChainedConfRefusal {
        override val logValue: String get() = "staging-directive-outside-section-$directive"
    }

    /** The parsed values were well-formed but the configuration validator refused them. */
    data class ConfigurationRefused(val failure: ChainedConfigurationFailure) : ChainedConfRefusal {
        override val logValue: String get() = "staging-config-$failure"
    }

    /** The parsed private key was refused at the rotation boundary. */
    data class RotationRefused(val reason: ChainedRotationRefusal) : ChainedConfRefusal {
        override val logValue: String get() = "staging-rotation-$reason"
    }

    /** A stable log identifier, never user copy. */
    val logValue: String
}

/** Why a parsed private or preshared key could not form a rotation. */
enum class ChainedRotationRefusal {
    /** Not base64, not canonical, or not exactly 32 bytes. */
    MALFORMED_KEY,

    /** The private key is the all-zero scalar, which Curve25519 rejects. */
    ALL_ZERO_PRIVATE_KEY,

    /** The peer's public key was pasted into `[Interface]` as the private key. */
    PEER_KEY_IN_INTERFACE,
}

/** Thrown by [ChainedUpstreamConfParser]; carries the typed [refusal]. */
class ChainedConfException(
    val refusal: ChainedConfRefusal,
) : IllegalArgumentException(refusal.logValue)

/**
 * A chained upstream parsed from a WireGuard `.conf`: the validated non-secret configuration
 * together with the private and optional preshared key material the session needs.
 *
 * Secret bytes are held privately and only exposed through copied accessors; `toString` is
 * redacted so a careless interpolation cannot log them. Mirrors iOS `ChainedUpstreamRotation`.
 */
class ChainedUpstreamRotation private constructor(
    val configuration: ChainedUpstreamConfiguration,
    private val privateKeyBase64: String,
    private val presharedKeyBase64: String?,
) {
    /** A fresh copy of the decoded 32-byte private key. */
    fun decodedPrivateKey(): ByteArray = Base64.getDecoder().decode(privateKeyBase64)

    /** A fresh copy of the decoded 32-byte preshared key, or `null` when the file carried none. */
    fun decodedPresharedKey(): ByteArray? =
        presharedKeyBase64?.let { Base64.getDecoder().decode(it) }

    val hasPresharedKey: Boolean get() = presharedKeyBase64 != null

    override fun toString(): String =
        "ChainedUpstreamRotation(endpoint: <redacted>:${configuration.endpointPort}, " +
            "psk: $hasPresharedKey)"

    companion object {
        /**
         * Validates the key material against the non-secret configuration.
         *
         * @throws ChainedConfException with [ChainedConfRefusal.RotationRefused].
         */
        fun create(
            configuration: ChainedUpstreamConfiguration,
            privateKey: String,
            presharedKey: String?,
        ): ChainedUpstreamRotation {
            val decodedPrivate = ChainedUpstreamConfigurationValidator.decodedKey(privateKey)
                ?: throw ChainedConfException(
                    ChainedConfRefusal.RotationRefused(ChainedRotationRefusal.MALFORMED_KEY),
                )
            if (decodedPrivate.all { it == 0.toByte() }) {
                throw ChainedConfException(
                    ChainedConfRefusal.RotationRefused(ChainedRotationRefusal.ALL_ZERO_PRIVATE_KEY),
                )
            }
            if (privateKey == configuration.peerPublicKey) {
                throw ChainedConfException(
                    ChainedConfRefusal.RotationRefused(ChainedRotationRefusal.PEER_KEY_IN_INTERFACE),
                )
            }
            if (presharedKey != null &&
                ChainedUpstreamConfigurationValidator.decodedKey(presharedKey) == null
            ) {
                throw ChainedConfException(
                    ChainedConfRefusal.RotationRefused(ChainedRotationRefusal.MALFORMED_KEY),
                )
            }
            return ChainedUpstreamRotation(configuration, privateKey, presharedKey)
        }
    }
}

/**
 * Reads the subset of the WireGuard `.conf` format this feature accepts, and refuses everything
 * else by omission plus an explicit refusal list. A parser that silently ignores a key it does
 * not implement is how a user's `Table = off` or `PreUp = …` becomes a configuration that behaves
 * differently from the file they pasted. Mirrors iOS `ChainedUpstreamConfParser`.
 *
 * Two `[Interface]` / `[Peer]` keys with the same name are refused rather than last-wins: the
 * file says two different things and nothing here can know which the user meant.
 */
object ChainedUpstreamConfParser {

    /**
     * wg-quick keys that change what a tunnel does, so dropping one would contradict the file.
     *
     * `fwmark` is included on Android, unlike iOS's known-inert treatment: BoringTun's
     * `set_fwmark` IS compiled for Android (`#[cfg(target_os = "android")]`), and although the
     * current seven-function C ABI exposes no setter, a mark is a real routing instruction this
     * build cannot honour. Refusing keeps the "the staged session does what the pasted file
     * says" promise; a known-inert classification would be correct only while the ABI can never
     * carry it. `includedapplications` / `excludedapplications` are the Android per-app routing
     * directives — a real WireGuard-for-Android export can carry them, and dropping them would
     * tunnel a different set of apps than the file asked for.
     */
    private val BEHAVIOUR_CHANGING_DIRECTIVES = setOf(
        "table", "preup", "postup", "predown", "postdown", "predns", "postdns", "saveconfig",
        "listenport", "fwmark", "includedapplications", "excludedapplications",
    )

    /** AmneziaWG obfuscation keys the stock BoringTun engine cannot perform. */
    private val SEPARATELY_REFUSED_DIRECTIVES = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
    )

    /** Keys read only from `[Interface]`. */
    private val INTERFACE_ONLY = setOf("privatekey", "address", "dns", "mtu")

    /** Keys read only from `[Peer]`. */
    private val PEER_ONLY = setOf(
        "publickey", "endpoint", "allowedips", "persistentkeepalive", "presharedkey",
    )

    /** Every key this parser recognises, refused ones included, so a typo cannot pass silently. */
    private val RECOGNISED_KEYS = INTERFACE_ONLY + PEER_ONLY +
        BEHAVIOUR_CHANGING_DIRECTIVES + SEPARATELY_REFUSED_DIRECTIVES

    /** The only section headers this feature carries. */
    private val RECOGNISED_SECTIONS = setOf("[interface]", "[peer]")

    /** The rotation `conf` describes, or a typed refusal. */
    fun rotation(conf: String): ChainedUpstreamRotation {
        val interfaceSection = mutableMapOf<String, MutableList<String>>()
        val peerSection = mutableMapOf<String, MutableList<String>>()
        var section: String? = null
        val seenSections = mutableSetOf<String>()

        // A leading UTF-8 BOM (common in Windows-exported files) would otherwise hide the first
        // `[Interface]` header and turn that line into a MalformedLine refusal.
        conf.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            // Strip a trailing comment, then whitespace. Splitting lines with `lineSequence`
            // rather than on "\n" keeps a CRLF file from leaving an invisible '\r' on values.
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[")) {
                section = line.lowercase()
                // Only the two carried sections are accepted: an unsupported or mistyped header
                // ([Peer2], [Route]) would otherwise swallow its assignments into a bucket
                // nobody reads, staging a file whose contents were silently ignored.
                if (section !in RECOGNISED_SECTIONS) {
                    throw ChainedConfException(ChainedConfRefusal.UnsupportedDirective(section))
                }
                // A repeated header is refused, not merged: section is only a dictionary
                // selector, so a second [Peer] would append into the first one's bucket and its
                // optional keys would silently join a section the file presents as separate.
                if (!seenSections.add(section)) {
                    throw ChainedConfException(ChainedConfRefusal.UnsupportedDirective(section))
                }
                return@forEach
            }
            val separator = line.indexOf('=')
            if (separator < 0) {
                throw ChainedConfException(ChainedConfRefusal.MalformedLine)
            }
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (section) {
                "[interface]" -> interfaceSection.getOrPut(key) { mutableListOf() }.add(value)
                "[peer]" -> peerSection.getOrPut(key) { mutableListOf() }.add(value)
                // An assignment before any section has no home; dropping it would stage a file
                // whose directive was ignored (the same divergence the key/section checks close).
                else -> throw ChainedConfException(
                    ChainedConfRefusal.DirectiveOutsideSection(key),
                )
            }
        }

        // Every lookup is section-scoped, so a supported key in the wrong section is read by
        // nobody. Checked first so a misplaced required key is diagnosed as misplaced.
        for (directive in INTERFACE_ONLY) {
            if (peerSection.containsKey(directive)) {
                throw ChainedConfException(
                    ChainedConfRefusal.DirectiveInWrongSection(directive, "[Peer]"),
                )
            }
        }
        for (directive in PEER_ONLY) {
            if (interfaceSection.containsKey(directive)) {
                throw ChainedConfException(
                    ChainedConfRefusal.DirectiveInWrongSection(directive, "[Interface]"),
                )
            }
        }

        val privateKeyText = exactlyOne(interfaceSection, "PrivateKey")
        val address = exactlyOne(interfaceSection, "Address")
        val peerKey = exactlyOne(peerSection, "PublicKey")
        val endpoint = exactlyOne(peerSection, "Endpoint")
        val allowedIPsText = exactlyOne(peerSection, "AllowedIPs")

        for (directive in BEHAVIOUR_CHANGING_DIRECTIVES) {
            if (interfaceSection.containsKey(directive) || peerSection.containsKey(directive)) {
                throw ChainedConfException(ChainedConfRefusal.UnsupportedDirective(directive))
            }
        }
        for (directive in SEPARATELY_REFUSED_DIRECTIVES) {
            if (interfaceSection.containsKey(directive) || peerSection.containsKey(directive)) {
                throw ChainedConfException(ChainedConfRefusal.UnsupportedDirective(directive))
            }
        }
        // A key this parser does not recognise is refused, not skipped: a misspelled optional
        // directive (`PersistantKeepalive`) would otherwise stage green and run the default,
        // which is exactly the "behaves differently from the pasted file" divergence this
        // parser exists to prevent.
        for (key in interfaceSection.keys + peerSection.keys) {
            if (key !in RECOGNISED_KEYS) {
                throw ChainedConfException(ChainedConfRefusal.UnsupportedDirective(key))
            }
        }

        val (host, port) = endpointParts(endpoint)
        val clientAddress = parseClientAddress(address)
        val allowedIPs = allowedIPsText.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Absent is the ordinary shape; present-but-unusable is refused rather than collapsing
        // to an empty resolver list that later surfaces as an unexplained latch refusal.
        val dnsAddresses: List<String>
        if (!interfaceSection.containsKey("dns")) {
            dnsAddresses = emptyList()
        } else {
            dnsAddresses = exactlyOne(interfaceSection, "DNS")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (dnsAddresses.isEmpty()) {
                throw ChainedConfException(ChainedConfRefusal.MalformedValue("DNS"))
            }
        }

        val mtu = optionalUInt16(interfaceSection, "MTU")
        val keepalive = optionalUInt16(peerSection, "PersistentKeepalive") ?: 0

        val presharedKey: String?
        if (!peerSection.containsKey("presharedkey")) {
            presharedKey = null
        } else {
            val text = exactlyOne(peerSection, "PresharedKey")
            if (ChainedUpstreamConfigurationValidator.decodedKey(text) == null) {
                throw ChainedConfException(ChainedConfRefusal.MalformedValue("PresharedKey"))
            }
            presharedKey = text
        }

        val configuration = try {
            ChainedUpstreamConfiguration.create(
                endpointHost = host,
                endpointPort = port,
                peerPublicKey = peerKey,
                clientAddress = clientAddress,
                allowedIPs = allowedIPs,
                persistentKeepaliveSeconds = keepalive,
                interfaceMTU = mtu,
                dnsAddresses = dnsAddresses,
            )
        } catch (error: ChainedConfigurationException) {
            throw ChainedConfException(
                ChainedConfRefusal.ConfigurationRefused(error.failure),
            )
        }

        return ChainedUpstreamRotation.create(configuration, privateKeyText, presharedKey)
    }

    private fun exactlyOne(section: Map<String, List<String>>, key: String): String {
        val values = section[key.lowercase()] ?: emptyList()
        if (values.size != 1 || values[0].isEmpty()) {
            throw ChainedConfException(ChainedConfRefusal.MissingOrRepeatedKey(key))
        }
        return values[0]
    }

    private fun optionalUInt16(section: Map<String, List<String>>, key: String): Int? {
        val values = section[key.lowercase()] ?: emptyList()
        if (values.isEmpty()) return null
        val parsed = values.takeIf { it.size == 1 }?.get(0)?.toIntOrNull()
        if (parsed == null || parsed !in 0..65535) {
            // Present-but-unreadable is a refusal, never a silent default: an `MTU = 12800`
            // typo defaulting to 1280 is a session that behaves unlike the file.
            throw ChainedConfException(ChainedConfRefusal.MalformedValue(key))
        }
        return parsed
    }

    /** Splits `host:port`, including the bracketed IPv6 form, then strips the brackets. */
    private fun endpointParts(endpoint: String): Pair<String, Int> {
        val separator = endpoint.lastIndexOf(':')
        if (separator < 0) {
            throw ChainedConfException(ChainedConfRefusal.MalformedValue("Endpoint"))
        }
        var host = endpoint.substring(0, separator)
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length - 1)
        }
        val port = endpoint.substring(separator + 1).toIntOrNull()
        if (port == null || port !in 1..65535) {
            throw ChainedConfException(ChainedConfRefusal.MalformedValue("Endpoint"))
        }
        return host to port
    }

    /**
     * Reads the one IPv4 entry of a dual-stack `Address`, validating and then dropping any
     * prefix. The v6 half is unused (chained mode claims `::/0` only to drop it), so carrying one
     * IPv4 literal is required and an ambiguous or absent one is a genuine defect.
     */
    private fun parseClientAddress(address: String): String {
        val entries = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val ipv4Entries = entries.filter { !it.contains(':') }
        if (ipv4Entries.size != 1) {
            throw ChainedConfException(ChainedConfRefusal.MalformedValue("Address"))
        }
        val entry = ipv4Entries[0]
        val slash = entry.indexOf('/')
        val host: String
        if (slash < 0) {
            host = entry
        } else {
            host = entry.substring(0, slash)
            val lengthText = entry.substring(slash + 1)
            // A second '/' or an empty suffix is malformed, not silently dropped: `10.7.0.2/32/junk`
            // and `10.7.0.2//` describe an address this parser must not paper over.
            if (lengthText.isEmpty() ||
                lengthText.contains('/') ||
                !lengthText.all { it in '0'..'9' } ||
                (lengthText.length > 1 && lengthText[0] == '0') ||
                lengthText.toIntOrNull() !in 0..32
            ) {
                throw ChainedConfException(ChainedConfRefusal.MalformedValue("Address"))
            }
        }
        // The host half is not whitespace-repaired: a comma-separated list is conventionally
        // written with spaces after commas, but nothing writes a space inside an address.
        if (host.isEmpty() || host.any { it.isWhitespace() }) {
            throw ChainedConfException(ChainedConfRefusal.MalformedValue("Address"))
        }
        return host
    }
}
