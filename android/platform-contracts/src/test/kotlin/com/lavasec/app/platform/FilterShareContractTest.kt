package com.lavasec.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterShareContractTest {
    @Test
    fun `a payload round-trips through the versioned envelope`() {
        val payload = FilterSharePayload(
            sourceIds = listOf("blocklistproject-basic", "stevenblack-unified"),
            blockedDomains = listOf("ads.example.com", "Adult.example.com"),
        )
        val encoded = FilterShareCodec.encode(payload)

        assertTrue(encoded.startsWith("LAVA-FILTER-1."))
        val decoded = FilterShareCodec.decode(encoded) as FilterShareDecodeResult.Decoded
        assertEquals(payload.sourceIds, decoded.payload.sourceIds)
        assertEquals(
            listOf("ads.example.com", "adult.example.com"),
            decoded.payload.blockedDomains,
        )
    }

    @Test
    fun `the codec normalizes idn domains with the local-editing validator`() {
        val payload = FilterSharePayload(
            sourceIds = emptyList(),
            blockedDomains = listOf("bücher.example"),
        )
        val decoded = FilterShareCodec.decode(FilterShareCodec.encode(payload))
            as FilterShareDecodeResult.Decoded

        assertEquals(listOf("xn--bcher-kva.example"), decoded.payload.blockedDomains)
    }

    @Test
    fun `the encoder refuses to serialize an invalid domain`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            FilterShareCodec.encode(
                FilterSharePayload(emptyList(), listOf("not a domain")),
            )
        }
    }

    @Test
    fun `a non-filter code is rejected`() {
        assertEquals(
            FilterShareRejection.NOT_A_FILTER_CODE,
            (FilterShareCodec.decode("hello world") as FilterShareDecodeResult.Rejected).reason,
        )
    }

    @Test
    fun `an oversized envelope is rejected`() {
        val huge = "LAVA-FILTER-1." + "A".repeat(FilterShareContract.MAX_ENCODED_CHARACTERS)
        assertEquals(
            FilterShareRejection.TOO_LARGE,
            (FilterShareCodec.decode(huge) as FilterShareDecodeResult.Rejected).reason,
        )
    }

    @Test
    fun `a tampered checksum is rejected`() {
        val encoded = FilterShareCodec.encode(
            FilterSharePayload(listOf("blocklistproject-basic"), emptyList()),
        )
        val tampered = encoded.dropLast(1) + if (encoded.last() == 'a') "b" else "a"

        assertEquals(
            FilterShareRejection.CHECKSUM_MISMATCH,
            (FilterShareCodec.decode(tampered) as FilterShareDecodeResult.Rejected).reason,
        )
    }

    @Test
    fun `a tampered body that keeps a valid structure still fails the checksum`() {
        val encoded = FilterShareCodec.encode(
            FilterSharePayload(listOf("blocklistproject-basic"), emptyList()),
        )
        // Replace the body but recompute nothing: the checksum must fail closed.
        val bodyStart = encoded.indexOf('.', "LAVA-FILTER-1.".length) + 1
        val swapped = encoded.substring(0, bodyStart) +
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"sources":["other"],"blocked":[]}""".toByteArray(Charsets.UTF_8),
            ) +
            encoded.substring(encoded.lastIndexOf('.'))

        assertEquals(
            FilterShareRejection.CHECKSUM_MISMATCH,
            (FilterShareCodec.decode(swapped) as FilterShareDecodeResult.Rejected).reason,
        )
    }

    @Test
    fun `decoded domains must pass the local-editing validator`() {
        val body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sources":[],"blocked":["not a domain"]}""".toByteArray(Charsets.UTF_8),
        )
        val signed = "LAVA-FILTER-1.$body"
        val checksum = sha256(signed.toByteArray(Charsets.UTF_8))
        val encoded = "$signed.$checksum"

        assertEquals(
            FilterShareRejection.INVALID_DOMAIN,
            (FilterShareCodec.decode(encoded) as FilterShareDecodeResult.Rejected).reason,
        )
    }

    @Test
    fun `duplicate domains after normalization are rejected`() {
        val body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sources":[],"blocked":["a.example.com","A.EXAMPLE.COM"]}""".toByteArray(Charsets.UTF_8),
        )
        val signed = "LAVA-FILTER-1.$body"
        val encoded = "$signed.${sha256(signed.toByteArray(Charsets.UTF_8))}"

        assertEquals(
            FilterShareRejection.DUPLICATE_DOMAIN,
            (FilterShareCodec.decode(encoded) as FilterShareDecodeResult.Rejected).reason,
        )
    }

    @Test
    fun `the rule-count limit rejects an over-limit blocked list`() {
        val many = (1..(com.lavasec.app.foundation.filter.ManualDomainRules.MAX_BLOCKED_DOMAINS + 1))
            .map { "d$it.example.com" }
        val body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sources":[],"blocked":["${many.joinToString("\",\"")}"]}""".toByteArray(Charsets.UTF_8),
        )
        val signed = "LAVA-FILTER-1.$body"
        val encoded = "$signed.${sha256(signed.toByteArray(Charsets.UTF_8))}"

        assertEquals(
            FilterShareRejection.TOO_MANY_DOMAINS,
            (FilterShareCodec.decode(encoded) as FilterShareDecodeResult.Rejected).reason,
        )
    }

    @Test
    fun `structural junk inside a valid checksum is rejected`() {
        val body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sources": "not-an-array"}""".toByteArray(Charsets.UTF_8),
        )
        val signed = "LAVA-FILTER-1.$body"
        val encoded = "$signed.${sha256(signed.toByteArray(Charsets.UTF_8))}"

        assertEquals(
            FilterShareRejection.INVALID_PAYLOAD,
            (FilterShareCodec.decode(encoded) as FilterShareDecodeResult.Rejected).reason,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
}
