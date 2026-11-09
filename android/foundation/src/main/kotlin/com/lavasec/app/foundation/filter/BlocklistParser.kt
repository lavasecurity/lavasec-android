package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.domain.DomainValidationException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class BlocklistFormat { AUTO, PLAIN_DOMAINS, HOSTS, ADBLOCK, DNSMASQ }

enum class BlocklistDiagnosticReason { INVALID_DOMAIN, UNSUPPORTED_SYNTAX, LINE_TOO_LONG }

data class BlocklistDiagnostic(
    val lineNumber: Long,
    val reason: BlocklistDiagnosticReason,
    val content: String = "",
)

data class BlocklistParseSummary(
    val acceptedRuleCount: Long,
    val diagnostics: List<BlocklistDiagnostic>,
    val diagnosticsTruncated: Boolean,
    val bytesRead: Long,
) {
    /** Mixed lists remain usable; comments and allow-only lists are valid zero-rule inputs. */
    val whollyRejected: Boolean
        get() = acceptedRuleCount == 0L && (diagnostics.isNotEmpty() || diagnosticsTruncated)
}

data class BlocklistParseResult(
    val ruleSet: DomainRuleSet,
    val diagnostics: List<BlocklistDiagnostic>,
    val diagnosticsTruncated: Boolean,
    val bytesRead: Long,
)

/** Bounded line-at-a-time parser for the list syntaxes accepted by the pinned iOS pipeline. */
class BlocklistParser(
    private val maxLineLength: Int = 4_096,
    private val maxDiagnostics: Int = 100,
) {
    init {
        require(maxLineLength > 0)
        require(maxDiagnostics >= 0)
    }

    fun parse(input: InputStream, format: BlocklistFormat = BlocklistFormat.AUTO): BlocklistParseResult {
        var rules = DomainRuleSet.EMPTY
        val summary = forEachRule(input, format) { rules = rules.adding(it) }
        return BlocklistParseResult(rules, summary.diagnostics, summary.diagnosticsTruncated, summary.bytesRead)
    }

    fun forEachRule(
        input: InputStream,
        format: BlocklistFormat = BlocklistFormat.AUTO,
        checkpoint: () -> Unit = {},
        onRule: (DomainRule) -> Unit,
    ): BlocklistParseSummary {
        val diagnostics = ArrayList<BlocklistDiagnostic>(minOf(maxDiagnostics, 16))
        var diagnosticsTruncated = false
        var accepted = 0L
        var bytesRead = 0L
        var lineNumber = 0L
        val stream = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        val bytes = ByteArrayOutputStream(minOf(maxLineLength, 512))
        val maxBufferedBytes = maxLineLength * 4
        var overlongBytes = false
        var pending: Int? = null

        fun reject(reason: BlocklistDiagnosticReason, content: String = "") {
            if (diagnostics.size < maxDiagnostics) {
                diagnostics += BlocklistDiagnostic(lineNumber, reason, content.take(maxLineLength))
            } else {
                diagnosticsTruncated = true
            }
        }

        fun processLine() {
            checkpoint()
            lineNumber++
            if (overlongBytes) {
                reject(BlocklistDiagnosticReason.LINE_TOO_LONG)
                bytes.reset()
                overlongBytes = false
                return
            }
            val rawBytes = bytes.toByteArray()
            bytes.reset()
            val decoded = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawBytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                reject(BlocklistDiagnosticReason.INVALID_DOMAIN)
                return
            }
            if (decoded.length > maxLineLength) {
                reject(BlocklistDiagnosticReason.LINE_TOO_LONG)
                return
            }
            val cleaned = clean(decoded, format)
            if (cleaned.isEmpty() || cleaned.startsWith("@@")) return
            val candidates = candidates(cleaned, format)
            if (candidates.isEmpty()) {
                if (!cleaned.startsWith("@@")) reject(BlocklistDiagnosticReason.UNSUPPORTED_SYNTAX, cleaned)
                return
            }
            for (candidate in candidates) {
                val rule = try {
                    DomainRule.parse(candidate, matchesSubdomains = true)
                } catch (_: DomainValidationException) {
                    reject(BlocklistDiagnosticReason.INVALID_DOMAIN, cleaned)
                    continue
                }
                onRule(rule)
                accepted++
            }
        }

        while (true) {
            val value = pending ?: stream.read().also { if (it >= 0) bytesRead++ }
            pending = null
            if (value < 0) break
            if (value == '\n'.code || value == '\r'.code) {
                processLine()
                if (value == '\r'.code) {
                    val next = stream.read().also { if (it >= 0) bytesRead++ }
                    if (next >= 0 && next != '\n'.code) pending = next
                }
            } else if (!overlongBytes) {
                if (bytes.size() >= maxBufferedBytes) overlongBytes = true else bytes.write(value)
            }
            if (bytesRead and 0xfffL == 0L) checkpoint()
        }
        if (bytes.size() > 0 || overlongBytes) processLine()
        return BlocklistParseSummary(accepted, diagnostics, diagnosticsTruncated, bytesRead)
    }

    private fun clean(raw: String, format: BlocklistFormat): String {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith('#') || line.startsWith('!') || line.startsWith('[')) return ""
        if (format == BlocklistFormat.ADBLOCK && ("##" in line || "#@#" in line)) return line
        line = line.substringBefore('#').trim()
        return line
    }

    private fun candidates(line: String, format: BlocklistFormat): List<String> = when (format) {
        BlocklistFormat.PLAIN_DOMAINS -> parsePlain(line)?.let(::listOf).orEmpty()
        BlocklistFormat.HOSTS -> parseHosts(line)
        BlocklistFormat.ADBLOCK -> parseAdblock(line)?.let(::listOf).orEmpty()
        BlocklistFormat.DNSMASQ -> parseDnsmasq(line)?.let(::listOf).orEmpty()
        BlocklistFormat.AUTO -> if (line.startsWith("@@")) emptyList() else parseHosts(line).takeIf(List<String>::isNotEmpty)
            ?: parseDnsmasq(line)?.let(::listOf)
            ?: parseAdblock(line)?.let(::listOf)
            ?: parsePlain(line)?.let(::listOf)
            ?: emptyList()
    }

    private fun parsePlain(line: String): String? {
        val candidate = when {
            line.startsWith("*.") -> line.drop(2)
            line.startsWith('.') -> line.drop(1)
            else -> line
        }
        if ('/' in candidate || '*' in candidate || candidate.any { it.isWhitespace() || Character.isSpaceChar(it) }) return null
        return candidate.takeIf(String::isNotEmpty)
    }

    private fun parseHosts(line: String): List<String> {
        val parts = line.split(HORIZONTAL_SPACE).filter(String::isNotEmpty)
        if (parts.size < 2 || parts.first() !in NULL_ROUTES) return emptyList()
        return parts.drop(1)
    }

    private fun parseAdblock(line: String): String? {
        if (line.startsWith("@@") || "##" in line || "#@#" in line) return null
        if (line.startsWith("||")) {
            val value = line.drop(2).takeWhile { it != '^' && it != '/' && it != '$' }
            if (value.isEmpty() || '*' in value) return null
            return value
        }
        val candidate = line.trim('|')
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) return null
        return try {
            val encodedHost = URI(candidate).run { host ?: toURL().host }
            URLDecoder.decode(encodedHost, Charsets.UTF_8.name())
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDnsmasq(line: String): String? {
        val prefix = DNSMASQ_PREFIXES.firstOrNull(line::startsWith) ?: return null
        val remainder = line.drop(prefix.length)
        if ('/' !in remainder) return null
        return remainder.substringBefore('/').takeIf(String::isNotEmpty)
    }

    companion object {
        // v5 retains all service-domain source rules; old prepared artifacts omitted them.
        const val RULES_VERSION = 5
        private val NULL_ROUTES = setOf("0.0.0.0", "127.0.0.1", "::", "::1")
        private val DNSMASQ_PREFIXES = listOf("address=/", "server=/", "local=/")
        private val HORIZONTAL_SPACE = Regex("[ \\t]+")
    }
}
