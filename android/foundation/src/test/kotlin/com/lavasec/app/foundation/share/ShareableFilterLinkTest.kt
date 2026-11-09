package com.lavasec.app.foundation.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareableFilterLinkTest {
    private val configuration = ShareableFilterConfiguration(
        enabledBlocklistIDs = setOf("blocklistproject-basic"),
        blockedDomains = setOf("ads.example"),
        allowedDomains = setOf("good.example"),
    )

    @Test
    fun `a raw code decodes into its configuration`() {
        val code = ShareableFilterCodec.encode(configuration)

        val decoded = ShareableFilterLink.decode(code) as ShareableFilterLinkResult.Decoded

        assertEquals(configuration, decoded.configuration)
    }

    @Test
    fun `a canonical link round-trips through creation and decoding`() {
        val code = ShareableFilterCodec.encode(configuration)
        val created = ShareableFilterLink.urlForCode(code) as ShareableFilterLinkCreationResult.Created

        assertTrue(created.url.startsWith(ShareableFilterLink.CANONICAL_URL_PREFIX))
        val decoded = ShareableFilterLink.decode(created.url) as ShareableFilterLinkResult.Decoded
        assertEquals(configuration, decoded.configuration)
    }

    @Test
    fun `a link with a query is refused because a query would reach the server`() {
        val code = ShareableFilterCodec.encode(configuration)
        val link = "https://lavasecurity.app/app/import/?x=1#$code"

        assertEquals(
            ShareableFilterInputError.InvalidUniversalLink,
            (ShareableFilterLink.decode(link) as ShareableFilterLinkResult.Rejected).error,
        )
    }

    @Test
    fun `userinfo is refused so a confusable authority cannot masquerade`() {
        val code = ShareableFilterCodec.encode(configuration)
        val link = "https://lavasecurity.app@evil.example/app/import/#$code"

        assertEquals(
            ShareableFilterInputError.InvalidUniversalLink,
            (ShareableFilterLink.decode(link) as ShareableFilterLinkResult.Rejected).error,
        )
    }

    @Test
    fun `a non-canonical host or path is refused`() {
        val code = ShareableFilterCodec.encode(configuration)
        val wrongHost = "https://evil.example/app/import/#$code"
        val wrongPath = "https://lavasecurity.app/elsewhere/#$code"

        assertEquals(
            ShareableFilterInputError.InvalidUniversalLink,
            (ShareableFilterLink.decode(wrongHost) as ShareableFilterLinkResult.Rejected).error,
        )
        assertEquals(
            ShareableFilterInputError.InvalidUniversalLink,
            (ShareableFilterLink.decode(wrongPath) as ShareableFilterLinkResult.Rejected).error,
        )
    }

    @Test
    fun `a code embedded in surrounding text is not searched for`() {
        val code = ShareableFilterCodec.encode(configuration)

        assertEquals(
            ShareableFilterInputError.UnrecognizedFormat,
            (ShareableFilterLink.decode("please import $code") as ShareableFilterLinkResult.Rejected).error,
        )
    }

    @Test
    fun `a corrupted code is reported as a configuration-code error`() {
        val code = ShareableFilterCodec.encode(configuration)
        val corrupated = code.dropLast(1) + if (code.last() == 'A') 'B' else 'A'

        val error = (ShareableFilterLink.decode(corrupated) as ShareableFilterLinkResult.Rejected).error
        assertTrue(error is ShareableFilterInputError.ConfigurationCode)
    }

    @Test
    fun `creation refuses a code that is not well formed`() {
        assertEquals(
            ShareableFilterInputError.UnrecognizedFormat,
            (ShareableFilterLink.urlForCode("not-a-code") as ShareableFilterLinkCreationResult.Rejected).error,
        )
    }
}
