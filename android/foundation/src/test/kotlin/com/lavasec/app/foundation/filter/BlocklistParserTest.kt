package com.lavasec.app.foundation.filter

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistParserTest {
    @Test
    fun `custom payload diagnostics distinguish malformed input from valid zero and mixed lists`() {
        // Frozen iOS 3bd86116 skips comments/allow rules and rejected lines, while retaining
        // valid rules in mixed lists. A zero accepted count alone is not a parse failure.
        for (format in BlocklistFormat.entries) {
            for (text in listOf("", " \n", "# comments\n! comment\n[Adblock Plus 2.0]\n", "@@||allowed.example^\n")) {
                val summary = BlocklistParser(maxDiagnostics = 0).forEachRule(text.byteInputStream(), format) { }
                assertEquals(0L, summary.acceptedRuleCount)
                assertFalse("$format: $text", summary.whollyRejected)
            }
        }
        for (text in listOf("bad syntax / path\n", "-invalid.example\n")) {
            val summary = BlocklistParser(maxDiagnostics = 0).forEachRule(text.byteInputStream()) { }
            assertTrue(summary.whollyRejected)
        }
        val mixed = BlocklistParser(maxDiagnostics = 0).forEachRule(
            "good.example\r\n-invalid.example\rbad syntax / path".byteInputStream(),
        ) { }
        assertEquals(1L, mixed.acceptedRuleCount)
        assertFalse(mixed.whollyRejected)
    }

    @Test
    fun `streaming parser supports every pinned syntax as suffix rules`() {
        val text = """
            # header
            example.com
            .leading.example
            *.wild.example
            0.0.0.0 one.example two.example # inline
            127.0.0.1 loop.example
            :: v6.example
            ::1 v6loop.example
            ||ads.example^path${'$'}third-party
            |https://url.example/path|
            address=/address.example/0.0.0.0
            server=/server.example/1.1.1.1
            local=/local.example/
            @@||allowed.example^
        """.trimIndent()

        val result = BlocklistParser().parse(text.byteInputStream(), BlocklistFormat.AUTO)

        assertEquals(13, result.ruleSet.count)
        assertTrue(result.ruleSet.exactDomainList.isEmpty())
        for (domain in listOf(
            "example.com", "leading.example", "wild.example", "one.example", "two.example",
            "loop.example", "v6.example", "v6loop.example", "ads.example", "url.example",
            "address.example", "server.example", "local.example",
        )) {
            assertTrue(domain, result.ruleSet.contains(domain))
        }
        assertFalse(result.ruleSet.contains("allowed.example"))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `rules version five retains service domains that older prepared artifacts exempted`() {
        // The cache version must change with the removed implicit suffix policy; otherwise
        // a prepared v4 source could keep silently omitting ordinary user-selected rules.
        assertEquals(5, BlocklistParser.RULES_VERSION)
        val domains = listOf("apple.com", "google.com", "lavasecurity.app")
        val result = BlocklistParser().parse(domains.joinToString("\n").byteInputStream())
        assertEquals(domains.size, result.ruleSet.count)
        for (domain in domains) {
            assertTrue(domain, result.ruleSet.contains(domain))
            assertTrue("child.$domain", result.ruleSet.contains("child.$domain"))
        }
        assertFalse(result.ruleSet.contains("unrelated.example"))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `streaming parser isolates malformed bytes line endings and bounded diagnostics`() {
        val bytes = buildList<Byte> {
            addAll("good.example\r\n".encodeToByteArray().toList())
            add(0xff.toByte())
            add('\r'.code.toByte())
            addAll("bad syntax / path\n".encodeToByteArray().toList())
            addAll(("x".repeat(4_097) + "\n").encodeToByteArray().toList())
            addAll("also-good.example".encodeToByteArray().toList())
        }.toByteArray()

        val result = BlocklistParser(maxDiagnostics = 2).parse(ByteArrayInputStream(bytes))

        assertTrue(result.ruleSet.contains("good.example"))
        assertTrue(result.ruleSet.contains("also-good.example"))
        assertEquals(2, result.diagnostics.size)
        assertTrue(result.diagnosticsTruncated)
        assertEquals(
            setOf(BlocklistDiagnosticReason.INVALID_DOMAIN, BlocklistDiagnosticReason.UNSUPPORTED_SYNTAX),
            result.diagnostics.mapTo(mutableSetOf(), BlocklistDiagnostic::reason),
        )
    }

    @Test
    fun `malformed utf8 drops its whole line even when it follows an inline comment marker`() {
        val bytes = "must-drop.example#".encodeToByteArray() + byteArrayOf(0xff.toByte()) +
            "\nstill-good.example\n".encodeToByteArray()

        val result = BlocklistParser().parse(ByteArrayInputStream(bytes))

        assertFalse(result.ruleSet.contains("must-drop.example"))
        assertTrue(result.ruleSet.contains("still-good.example"))
        assertEquals(BlocklistDiagnosticReason.INVALID_DOMAIN, result.diagnostics.single().reason)
    }

    @Test
    fun `format-specific parsing rejects unsupported variants`() {
        val parser = BlocklistParser()
        val cases = listOf(
            BlocklistFormat.PLAIN_DOMAINS to "bad/path.example",
            BlocklistFormat.PLAIN_DOMAINS to "bad\tpath.example",
            BlocklistFormat.HOSTS to "192.0.2.1 host.example",
            BlocklistFormat.ADBLOCK to "/regex.example/",
            BlocklistFormat.ADBLOCK to "*.wildcard.example",
            BlocklistFormat.ADBLOCK to "example.com##cosmetic",
            BlocklistFormat.DNSMASQ to "address=missing-slashes.example",
            BlocklistFormat.DNSMASQ to "address=/missing-closing.example",
        )

        for ((format, line) in cases) {
            val result = parser.parse(line.byteInputStream(), format)
            assertTrue("$format accepted $line", result.ruleSet.isEmpty)
            assertEquals(BlocklistDiagnosticReason.UNSUPPORTED_SYNTAX, result.diagnostics.single().reason)
        }

        val auto = parser.parse("address=/missing-closing.example".byteInputStream(), BlocklistFormat.AUTO)
        assertTrue(auto.ruleSet.isEmpty)
        assertEquals(BlocklistDiagnosticReason.UNSUPPORTED_SYNTAX, auto.diagnostics.single().reason)

        val cosmeticAuto = parser.parse("example.com##cosmetic".byteInputStream(), BlocklistFormat.AUTO)
        assertTrue(cosmeticAuto.ruleSet.contains("example.com"))
    }

    @Test
    fun `explicit adblock rejects cosmetic syntax before inline comment cleaning`() {
        val result = BlocklistParser().parse(
            "||example.com##selector".byteInputStream(),
            BlocklistFormat.ADBLOCK,
        )

        assertTrue(result.ruleSet.isEmpty)
        assertEquals(BlocklistDiagnosticReason.UNSUPPORTED_SYNTAX, result.diagnostics.single().reason)
        assertEquals("||example.com##selector", result.diagnostics.single().content)
    }

    @Test
    fun `URL-form adblock rules preserve Unicode hosts for IDNA normalization`() {
        val result = BlocklistParser().parse(
            "|https://bücher.example/path|".byteInputStream(),
            BlocklistFormat.ADBLOCK,
        )

        assertEquals(1, result.ruleSet.count)
        assertTrue(result.ruleSet.contains("xn--bcher-kva.example"))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `URL-form adblock rules decode percent-encoded Unicode hosts like iOS`() {
        val result = BlocklistParser().parse(
            "|https://b%C3%BCcher.example/path|".byteInputStream(),
            BlocklistFormat.ADBLOCK,
        )

        assertEquals(1, result.ruleSet.count)
        assertTrue(result.ruleSet.contains("xn--bcher-kva.example"))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `auto preserves cosmetic cleaning quirk`() {
        val result = BlocklistParser().parse(
            "||example.com##selector".byteInputStream(),
            BlocklistFormat.AUTO,
        )

        assertTrue(result.ruleSet.contains("example.com"))
        assertTrue(result.diagnostics.isEmpty())
    }
}
