package com.lavasec.app.foundation.share

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterShareEligibilityTest {
    private fun configuration(
        blocked: Set<String> = setOf("ads.example"),
        custom: List<ShareableCustomBlocklistSource> = emptyList(),
    ) = ShareableFilterConfiguration(
        enabledBlocklistIDs = setOf("blocklistproject-basic"),
        blockedDomains = blocked,
        customBlocklists = custom,
        allowedDomains = emptySet(),
    )

    @Test
    fun `an empty filter has nothing to share`() {
        val empty = ShareableFilterConfiguration(allowedDomains = emptySet())

        assertEquals(FilterShareEligibility.Empty, FilterShareEligibilityPolicy.evaluate(empty))
    }

    @Test
    fun `a normal filter is shareable`() {
        assertEquals(
            FilterShareEligibility.Shareable,
            FilterShareEligibilityPolicy.evaluate(configuration()),
        )
    }

    @Test
    fun `a source carrying a query or userinfo makes the filter ineligible`() {
        val withQuery = configuration(
            custom = listOf(
                ShareableCustomBlocklistSource(
                    id = "custom-1",
                    displayName = "Token list",
                    sourceUrl = "https://lists.example/block.txt?token=secret",
                ),
            ),
        )
        val withUserInfo = configuration(
            custom = listOf(
                ShareableCustomBlocklistSource(
                    id = "custom-2",
                    displayName = "Credentialed",
                    sourceUrl = "https://user:pass@lists.example/block.txt",
                ),
            ),
        )

        assertEquals(
            FilterShareEligibility.ContainsPrivateSourceParameters,
            FilterShareEligibilityPolicy.evaluate(withQuery),
        )
        assertEquals(
            FilterShareEligibility.ContainsPrivateSourceParameters,
            FilterShareEligibilityPolicy.evaluate(withUserInfo),
        )
    }

    @Test
    fun `a setup too large for the code capacity is not shareable`() {
        val oversized = configuration(
            custom = listOf(
                ShareableCustomBlocklistSource(
                    id = "custom-big",
                    displayName = "Big",
                    sourceUrl = "https://lists.example/" + "a".repeat(600_000),
                ),
            ),
        )

        assertEquals(
            FilterShareEligibility.ExceedsShareCapacity,
            FilterShareEligibilityPolicy.evaluate(oversized),
        )
    }
}
