package com.lavasec.app.foundation.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterReplacementSummaryTest {
    private fun configuration(
        version: Int = 2,
        lists: Set<String> = emptySet(),
        blocked: Set<String> = emptySet(),
        allowed: Set<String> = emptySet(),
        custom: List<ShareableCustomBlocklistSource> = emptyList(),
    ) = if (version == 1) {
        ShareableFilterConfiguration.legacy(
            enabledBlocklistIDs = lists,
            blockedDomains = blocked,
            customBlocklists = custom,
        )
    } else {
        ShareableFilterConfiguration(
            enabledBlocklistIDs = lists,
            blockedDomains = blocked,
            allowedDomains = allowed,
            customBlocklists = custom,
        )
    }

    @Test
    fun `the review reports additions and removals for every selection field`() {
        val before = configuration(
            lists = setOf("a", "b"),
            blocked = setOf("old-blocked.example"),
            allowed = setOf("old-allowed.example"),
        )
        val after = configuration(
            lists = setOf("b", "c"),
            blocked = setOf("new-blocked.example"),
            allowed = setOf("new-allowed.example"),
        )

        val summary = FilterReplacementSummary(before, after)

        assertEquals(listOf("c"), summary.selectionDiff.addedBlocklistIDs)
        assertEquals(listOf("a"), summary.selectionDiff.removedBlocklistIDs)
        assertEquals(listOf("new-blocked.example"), summary.selectionDiff.addedBlockedDomains)
        assertEquals(listOf("old-blocked.example"), summary.selectionDiff.removedBlockedDomains)
        assertEquals(listOf("new-allowed.example"), summary.selectionDiff.addedAllowedDomains)
        assertEquals(listOf("old-allowed.example"), summary.selectionDiff.removedAllowedDomains)
        assertTrue(summary.hasChanges)
        assertEquals(6, summary.selectionDiff.changeCount)
        assertTrue(summary.addedCustomBlocklists.isEmpty())
        assertTrue(summary.removedCustomBlocklists.isEmpty())
    }

    @Test
    fun `a changed custom definition under the same id is not also reported as a list change`() {
        val before = configuration(
            lists = setOf("custom-1"),
            custom = listOf(
                ShareableCustomBlocklistSource("custom-1", "List", "https://old.example/l.txt"),
            ),
        )
        val after = configuration(
            lists = setOf("custom-1"),
            custom = listOf(
                ShareableCustomBlocklistSource("custom-1", "List", "https://new.example/l.txt"),
            ),
        )

        val summary = FilterReplacementSummary(before, after)

        assertEquals(listOf("custom-1"), summary.removedCustomBlocklists.map { it.id })
        assertEquals(listOf("custom-1"), summary.addedCustomBlocklists.map { it.id })
        assertTrue("the list diff stays empty", summary.selectionDiff.addedBlocklistIDs.isEmpty())
        assertTrue("the list diff stays empty", summary.selectionDiff.removedBlocklistIDs.isEmpty())
        assertTrue(summary.removedBlocklistIDs.isEmpty())
        assertTrue(summary.addedBlocklistIDs.isEmpty())
    }

    @Test
    fun `replacementSummary projects the incoming content over the target`() {
        val target = configuration(
            lists = setOf("a"),
            blocked = setOf("kept.example"),
            allowed = setOf("old-allowed.example"),
        )
        val incoming = configuration(
            lists = setOf("a", "b"),
            blocked = setOf("incoming.example"),
            allowed = setOf("new-allowed.example"),
        )

        val summary = incoming.replacementSummary(target)

        assertEquals(listOf("a", "b"), summary.after?.enabledBlocklistIDs?.sorted())
        assertEquals(setOf("incoming.example"), summary.after?.blockedDomains)
        assertEquals(setOf("new-allowed.example"), summary.after?.allowedDomains)
        assertTrue(summary.hasChanges)
    }

    @Test
    fun `a legacy version 1 import preserves the target exceptions`() {
        val target = configuration(
            allowed = setOf("recipient-allowed.example"),
            blocked = setOf("recipient-blocked.example"),
        )
        val legacyIncoming = ShareableFilterConfiguration.legacy(
            enabledBlocklistIDs = setOf("a"),
            blockedDomains = setOf("sender-blocked.example"),
        )

        val summary = legacyIncoming.replacementSummary(target)

        assertEquals(setOf("recipient-allowed.example"), summary.after?.allowedDomains)
        assertEquals(setOf("sender-blocked.example"), summary.after?.blockedDomains)
        assertFalse(summary.selectionDiff.addedAllowedDomains.contains("recipient-allowed.example"))
        assertFalse(summary.selectionDiff.removedAllowedDomains.contains("recipient-allowed.example"))
        assertTrue(summary.selectionDiff.removedAllowedDomains.isEmpty())
    }
}
