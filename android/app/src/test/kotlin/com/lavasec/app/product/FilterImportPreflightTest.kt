package com.lavasec.app.product

import com.lavasec.app.platform.FilterShareCodec
import com.lavasec.app.platform.FilterShareContract
import com.lavasec.app.platform.FilterSharePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterImportPreflightTest {
    @Test
    fun `a payload whose sources all resolve is ready to stage`() {
        val payload = FilterSharePayload(
            sourceIds = listOf("blocklistproject-basic"),
            blockedDomains = listOf("ads.example.com"),
        )
        val preview = FilterImportPreflight.preview(
            payload,
            catalogSourceIds = setOf("blocklistproject-basic", "stevenblack-unified"),
        )

        assertTrue(preview is FilterImportPreview.Ready)
        assertEquals(payload, (preview as FilterImportPreview.Ready).payload)
    }

    @Test
    fun `an unknown source id refuses the preview with the exact ids`() {
        val payload = FilterSharePayload(
            sourceIds = listOf("blocklistproject-basic", "not-in-catalog"),
            blockedDomains = emptyList(),
        )
        val preview = FilterImportPreflight.preview(
            payload,
            catalogSourceIds = setOf("blocklistproject-basic"),
        )

        assertEquals(
            setOf("not-in-catalog"),
            (preview as FilterImportPreview.Rejected).unknownSourceIds,
        )
    }

    @Test
    fun `a decoded payload is validated end to end before the preview can accept it`() {
        // The codec validates domains with the local-editing validator; the preflight then
        // resolves the catalog membership — a payload can only stage when both gates pass.
        val encoded = FilterShareCodec.encode(
            FilterSharePayload(
                sourceIds = listOf("blocklistproject-basic"),
                blockedDomains = listOf("ads.example.com"),
            ),
        )
        val decoded = FilterShareCodec.decode(encoded) as com.lavasec.app.platform.FilterShareDecodeResult.Decoded
        val preview = FilterImportPreflight.preview(
            decoded.payload,
            catalogSourceIds = setOf("blocklistproject-basic"),
        )

        assertTrue(preview is FilterImportPreview.Ready)
    }

    @Test
    fun `an empty catalog refuses every referenced source`() {
        val payload = FilterSharePayload(
            sourceIds = listOf("blocklistproject-basic", "stevenblack-unified"),
            blockedDomains = emptyList(),
        )

        val preview = FilterImportPreflight.preview(payload, catalogSourceIds = emptySet())

        assertEquals(
            setOf("blocklistproject-basic", "stevenblack-unified"),
            (preview as FilterImportPreview.Rejected).unknownSourceIds,
        )
    }

    @Test
    fun `the preview reports every unknown id without truncating and does not mutate the payload`() {
        val ids = (1..100).map { "source-$it" }
        val payload = FilterSharePayload(sourceIds = ids, blockedDomains = listOf("ads.example.com"))

        val preview = FilterImportPreflight.preview(payload, catalogSourceIds = setOf("source-1"))

        assertEquals(ids.drop(1).toSet(), (preview as FilterImportPreview.Rejected).unknownSourceIds)
        assertEquals(ids, payload.sourceIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a payload beyond the contract source bound cannot even be constructed`() {
        FilterSharePayload(
            sourceIds = (1..(FilterShareContract.MAX_SOURCE_IDS + 1)).map { "source-$it" },
            blockedDomains = emptyList(),
        )
    }
}
