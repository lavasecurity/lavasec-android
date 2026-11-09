package com.lavasec.app.foundation.share

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareableFilterCodecTest {
    private val goldenConfiguration = ShareableFilterConfiguration(
        schemaVersion = 2,
        enabledBlocklistIDs = setOf("blocklistproject-basic", "stevenblack-unified"),
        blockedDomains = setOf("ads.example", "tracker.example"),
        customBlocklists = listOf(
            ShareableCustomBlocklistSource(
                id = "custom-1",
                displayName = "My List",
                sourceUrl = "https://lists.example/block.txt",
                parseFormat = ShareableBlocklistParseFormat.PLAIN_DOMAINS,
            ),
        ),
        allowedDomains = setOf("good.example"),
    )

    @Test
    fun `a version 2 payload round-trips through the golden vector and is stable`() {
        val encoded = ShareableFilterCodec.encode(goldenConfiguration)

        assertEquals(GOLDEN_CODE, encoded)
        val decoded = ShareableFilterCodec.decode(encoded) as ShareableFilterDecodeResult.Decoded
        assertEquals(goldenConfiguration, decoded.configuration)
        assertEquals("encoding must be deterministic", encoded, ShareableFilterCodec.encode(decoded.configuration))
    }

    @Test
    fun `FOSS manual domains beyond Plus quota survive share codec within existing byte budgets`() {
        val value = ShareableFilterConfiguration(
            allowedDomains = (0 until 1_001).mapTo(linkedSetOf()) { "allowed-$it.example" },
            blockedDomains = (0 until 1_001).mapTo(linkedSetOf()) { "blocked-$it.example" },
        )
        assertTrue(value.fitsShareableCodeCapacity())
        val encoded = ShareableFilterCodec.encode(value)
        assertTrue(encoded.length <= ShareableFilterCodec.MAX_ENCODED_CODE_LENGTH)
        assertEquals(value, (ShareableFilterCodec.decode(encoded) as ShareableFilterDecodeResult.Decoded).configuration)
    }

    @Test
    fun `version 1 carries its emoji and keeps its absent allowed set`() {
        val legacy = ShareableFilterConfiguration.legacy(
            emoji = "\uD83D\uDEE1\uFE0F",
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            blockedDomains = setOf("ads.example"),
        )
        val decoded = ShareableFilterCodec.decode(ShareableFilterCodec.encode(legacy))
            as ShareableFilterDecodeResult.Decoded

        assertEquals(legacy, decoded.configuration)
        assertEquals(null, decoded.configuration.allowedDomains)
        assertEquals("\uD83D\uDEE1\uFE0F", decoded.configuration.emoji)
    }

    @Test
    fun `an unsupported future version fails closed instead of decoding a subset`() {
        val future = ShareableFilterConfiguration(
            schemaVersion = 3,
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            allowedDomains = emptySet(),
        )
        val result = ShareableFilterCodec.decode(ShareableFilterCodec.encode(future))

        assertEquals(
            ShareableFilterCodeError.UnsupportedVersion(3),
            (result as ShareableFilterDecodeResult.Rejected).error,
        )
    }

    @Test
    fun `an oversized encoded body is rejected`() {
        val huge = ShareableFilterCodec.CODE_PREFIX +
            "A".repeat(ShareableFilterCodec.MAX_ENCODED_CODE_LENGTH + 1)

        assertEquals(
            ShareableFilterCodeError.PayloadTooLarge,
            (ShareableFilterCodec.decode(huge) as ShareableFilterDecodeResult.Rejected).error,
        )
    }

    @Test
    fun `a compression bomb over the inflate budget is rejected`() {
        val bomb = frame("x".repeat(ShareableFilterCodec.MAX_INFLATED_PAYLOAD_BYTES + 1))

        assertEquals(
            ShareableFilterCodeError.PayloadTooLarge,
            (ShareableFilterCodec.decode(bomb) as ShareableFilterDecodeResult.Rejected).error,
        )
    }

    @Test
    fun `a truncated code is rejected, never partially decoded`() {
        val truncated = GOLDEN_CODE.dropLast(4)
        val result = ShareableFilterCodec.decode(truncated)

        assertTrue("truncation must reject", result is ShareableFilterDecodeResult.Rejected)
    }

    @Test
    fun `a tampered body is rejected by the integrity tag`() {
        val tampered = GOLDEN_CODE.dropLast(1) + if (GOLDEN_CODE.last() == 'A') 'B' else 'A'
        val result = ShareableFilterCodec.decode(tampered)

        assertTrue(result is ShareableFilterDecodeResult.Rejected)
    }

    @Test
    fun `duplicate source ids are rejected`() {
        val json = """{"v":2,"lists":["a","a"],"blocked":[],"allowed":[],"custom":[]}"""

        assertEquals(
            ShareableFilterCodeError.DuplicateSource,
            (ShareableFilterCodec.decode(frame(json)) as ShareableFilterDecodeResult.Rejected).error,
        )
    }

    @Test
    fun `duplicate blocked domains are rejected`() {
        val json = """{"v":2,"lists":[],"blocked":["ads.example","ads.example"],"allowed":[],"custom":[]}"""

        assertEquals(
            ShareableFilterCodeError.DuplicateDomain,
            (ShareableFilterCodec.decode(frame(json)) as ShareableFilterDecodeResult.Rejected).error,
        )
    }

    @Test
    fun `duplicate allowed domains are rejected`() {
        val json = """{"v":2,"lists":[],"blocked":[],"allowed":["good.example","good.example"],"custom":[]}"""

        assertEquals(
            ShareableFilterCodeError.DuplicateDomain,
            (ShareableFilterCodec.decode(frame(json)) as ShareableFilterDecodeResult.Rejected).error,
        )
    }

    @Test
    fun `duplicate custom source ids are rejected`() {
        val json = """{"v":2,"lists":[],"blocked":[],"allowed":[],"custom":[
            {"i":"custom-1","n":"A","u":"https://a.example/l.txt","f":"auto"},
            {"i":"custom-1","n":"B","u":"https://b.example/l.txt","f":"auto"}]}"""

        assertEquals(
            ShareableFilterCodeError.DuplicateCustomSource,
            (ShareableFilterCodec.decode(frame(json)) as ShareableFilterDecodeResult.Rejected).error,
        )
    }

    @Test
    fun `an unknown custom parse format is a malformed payload, not a silent default`() {
        val json = """{"v":2,"lists":[],"blocked":[],"allowed":[],"custom":[
            {"i":"custom-1","n":"A","u":"https://a.example/l.txt","f":"yaml"}]}"""

        assertEquals(
            ShareableFilterCodeError.MalformedPayload,
            (ShareableFilterCodec.decode(frame(json)) as ShareableFilterDecodeResult.Rejected).error,
        )
    }

    @Test
    fun `a version 2 payload missing its explicit allowed set is rejected`() {
        val json = """{"v":2,"lists":[],"blocked":[]}"""

        assertEquals(
            ShareableFilterCodeError.MalformedPayload,
            (ShareableFilterCodec.decode(frame(json)) as ShareableFilterDecodeResult.Rejected).error,
        )
    }

    @Test
    fun `json null on an optional field is treated as absent, not malformed`() {
        val json = """{"v":2,"lists":null,"blocked":null,"allowed":[],"custom":null}"""

        val decoded = ShareableFilterCodec.decode(frame(json)) as ShareableFilterDecodeResult.Decoded

        assertTrue(decoded.configuration.isEmpty)
        assertEquals(emptySet<String>(), decoded.configuration.enabledBlocklistIDs)
    }

    @Test
    fun `malformed json is rejected`() {
        assertEquals(
            ShareableFilterCodeError.MalformedPayload,
            (ShareableFilterCodec.decode(frame("not json")) as ShareableFilterDecodeResult.Rejected).error,
        )
    }

    @Test
    fun `unicode survives the round trip`() {
        val configuration = ShareableFilterConfiguration(
            enabledBlocklistIDs = setOf("ümlaut-list"),
            blockedDomains = setOf("bücher.example"),
            customBlocklists = listOf(
                ShareableCustomBlocklistSource(
                    id = "custom-\u00fc",
                    displayName = "Bücher Liste \uD83D\uDCDA",
                    sourceUrl = "https://lists.example/блок.txt",
                    parseFormat = ShareableBlocklistParseFormat.HOSTS,
                ),
            ),
            allowedDomains = setOf("g\u00f6od.example"),
        )

        val decoded = ShareableFilterCodec.decode(ShareableFilterCodec.encode(configuration))
            as ShareableFilterDecodeResult.Decoded

        assertEquals(configuration, decoded.configuration)
    }

    @Test
    fun `the wire carries no credentials, tokens, or local cache internals`() {
        val json = ShareableFilterCodec.deterministicJson(goldenConfiguration)

        assertFalse(json.contains("createdAt"))
        assertFalse(json.contains("lastAcceptedHash"))
        assertFalse(json.contains("token"))
        assertFalse(json.contains("credential"))
        assertFalse(json.contains("password"))
        assertTrue(json.contains("\"custom\":[{\"f\":\"plain_domains\",\"i\":\"custom-1\","))
    }

    private companion object {
        const val GOLDEN_CODE =
            "LF1-DILtNeXMeJxNjl0OgjAQhK9i9pmf6GPPoCcwxvQPrZa2oQtiCHd3gIT4tPlmZndnIul9_FhD4kq" +
                "PGE1lR9kmb-lWkPJRvzdLmrw7BXEnYXT_Wd1nji2iEzUkKHnpwt3EFiNjw0HbIuURGICX7-HsMoN60JM" +
                "5ZVHXHtL-ql4bVDwyzfixekubVV4odfFlNZdKZqdxKbMdbFAe9co-uMahPRYHEqf5B-ZyUG8"

        fun frame(rawJson: String): String {
            val deflated = deflate(rawJson.toByteArray(Charsets.UTF_8))
            val tag = MessageDigest.getInstance("SHA-256").digest(deflated).copyOf(6)
            val framed = tag + deflated
            return ShareableFilterCodec.CODE_PREFIX +
                Base64.getUrlEncoder().withoutPadding().encodeToString(framed)
        }

        fun deflate(input: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16_384)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            deflater.end()
            return output.toByteArray()
        }
    }
}
