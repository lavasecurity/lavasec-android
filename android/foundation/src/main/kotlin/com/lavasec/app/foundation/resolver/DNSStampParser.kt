package com.lavasec.app.foundation.resolver

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** The four stamp protocols served by frozen DNSResolverPreset.DNSStampParser. */
internal object DNSStampParser {
    fun parse(raw: String): ParsedCustomResolverEndpoint? {
        if (!raw.startsWith("sdns://", ignoreCase = true)) return null
        val encoded = raw.substring(7).replace('-', '+').replace('_', '/')
        val padding = "=".repeat((4 - encoded.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(encoded + padding) }.getOrNull() ?: return null
        if (bytes.size < 9) return null
        val reader = Reader(bytes)
        val protocol = reader.byte() ?: return null
        if (!reader.skipProperties()) return null
        val addressValue = reader.string() ?: return null
        if (protocol == 0) {
            if (reader.hasRemaining) return null
            val address = addressPort(addressValue) ?: return null
            if (address.port != null && address.port != 53) return null
            val literal = CustomResolverValidator.normalizedPlainEndpoint(address.host) ?: return null
            return ParsedCustomResolverEndpoint(ParsedCustomResolverTransport.PLAIN, raw, literal)
        }
        val transport = when (protocol) {
            2 -> ParsedCustomResolverTransport.DOH
            3 -> ParsedCustomResolverTransport.DOT
            4 -> ParsedCustomResolverTransport.DOQ
            else -> return null
        }
        // Frozen source refuses certificate hash pinning; accepting it without verification
        // would silently discard the user's identity requirement.
        val hashes = reader.values() ?: return null
        if (hashes.any { it.isNotEmpty() }) return null
        val hostname = addressPort(reader.string() ?: return null) ?: return null
        if (hostname.host.isEmpty() || !CustomResolverValidator.isValidResolverHost(hostname.host)) return null
        val path = if (transport == ParsedCustomResolverTransport.DOH) {
            reader.string()?.takeIf { it.isNotEmpty() } ?: return null
        } else null
        val extraBootstrap = if (reader.hasRemaining) reader.values()?.map { utf8(it) ?: "" } ?: return null else emptyList()
        if (reader.hasRemaining) return null
        val bootstrap = bootstrap(listOf(addressValue) + extraBootstrap)
        val port = hostname.port ?: if (transport == ParsedCustomResolverTransport.DOH) 443
            else addressPort(addressValue)?.port ?: 853
        // Connectable ports and RFC 9250's ordinary-DNS-port prohibition remain admission rules.
        if (port !in 1..65535 || (transport == ParsedCustomResolverTransport.DOQ && port == 53)) return null
        val resolved = if (transport == ParsedCustomResolverTransport.DOH) {
            runCatching {
                URI("https", null, hostname.host, if (port == 443) -1 else port,
                    if (path!!.startsWith('/')) path else "/$path", null, null).toASCIIString()
            }.getOrNull() ?: return null
        } else if (hostname.host.contains(':')) "[${hostname.host}]:$port" else "${hostname.host}:$port"
        return ParsedCustomResolverEndpoint(transport, raw, resolved, bootstrap)
    }

    private data class AddressPort(val host: String, val port: Int?)

    private fun addressPort(raw: String): AddressPort? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.startsWith('[')) {
            val closing = value.indexOf(']')
            if (closing < 0) return null
            val host = value.substring(1, closing)
            val remainder = value.substring(closing + 1)
            if (remainder.isEmpty()) return AddressPort(host, null)
            if (!remainder.startsWith(':')) return null
            return AddressPort(host, port(remainder.substring(1)) ?: return null)
        }
        if (value.count { it == ':' } == 1) {
            val index = value.indexOf(':')
            return AddressPort(value.substring(0, index), port(value.substring(index + 1)) ?: return null)
        }
        return AddressPort(value, null)
    }

    private fun port(value: String): Int? = value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it in 0..65535 }

    private fun bootstrap(values: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val v4 = mutableListOf<String>()
        val v6 = mutableListOf<String>()
        for (value in values) {
            val address = addressPort(value) ?: continue
            if (!seen.add(address.host)) continue
            val literal = CustomResolverValidator.normalizedPlainEndpoint(address.host) ?: continue
            if (literal.contains(':')) v6 += literal else v4 += literal
        }
        return v4 + v6
    }

    private fun utf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0
        val hasRemaining: Boolean get() = offset < bytes.size
        fun byte(): Int? = if (hasRemaining) bytes[offset++].toInt() and 255 else null
        fun skipProperties(): Boolean {
            if (bytes.size - offset < 8) return false
            offset += 8
            return true
        }
        fun string(): String? {
            val length = byte() ?: return null
            if (length and 128 != 0) return null
            return take(length)?.let(::utf8)
        }
        fun values(): List<ByteArray>? {
            val result = mutableListOf<ByteArray>()
            do {
                val lengthByte = byte() ?: return null
                result += take(lengthByte and 127) ?: return null
            } while (lengthByte and 128 != 0)
            return result
        }
        private fun take(length: Int): ByteArray? {
            if (bytes.size - offset < length) return null
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
    }
}
