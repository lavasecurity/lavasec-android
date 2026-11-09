package com.lavasec.app.foundation.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterLibraryEditorTest {
    @Test
    fun `create appends an empty user filter and derives a unique default name`() {
        val created = FilterLibraryEditor.create(
            library = twoFilterLibrary,
            name = "",
            duplicateFromId = null,
            maxFilters = 3,
            newId = "filter-a",
        ) as FilterLibraryEditResult.Applied

        assertEquals("Filter", created.library.profile("filter-a")!!.name)
        assertNull(created.library.profile("filter-a")!!.protectionLevel)
        assertTrue(created.library.profile("filter-a")!!.enabledSourceIds.isEmpty())
        assertEquals(3, created.library.profiles.size)
        assertTrue(created.library.profile("filter-a")!!.manualRules.blockedDomains.isEmpty())
    }

    @Test
    fun `create duplicates the source filters blocklists and manual domains`() {
        val withRules = twoFilterLibrary.withProfile(
            twoFilterLibrary.profile("filter-essential")!!.withManualRules(
                ManualDomainRules(
                    blockedDomains = setOf("ads.example.com"),
                    allowedDomains = setOf("trusted.example.com"),
                ),
            ),
        )
        val created = FilterLibraryEditor.create(
            library = withRules,
            name = "Gaming",
            duplicateFromId = "filter-essential",
            maxFilters = 3,
            newId = "filter-a",
        ) as FilterLibraryEditResult.Applied

        val duplicate = created.library.profile("filter-a")!!
        assertEquals("Gaming", duplicate.name)
        assertEquals(
            withRules.profile("filter-essential")!!.enabledSourceIds,
            duplicate.enabledSourceIds,
        )
        assertEquals(setOf("ads.example.com"), duplicate.manualRules.blockedDomains)
        assertEquals(setOf("trusted.example.com"), duplicate.manualRules.allowedDomains)
    }

    @Test
    fun `create refuses the count cap and duplicate names`() {
        assertEquals(
            FilterLibraryRejection.LIMIT_REACHED,
            (FilterLibraryEditor.create(seeded, "X", null, 3, "filter-a") as FilterLibraryEditResult.Rejected).reason,
        )
        val result = FilterLibraryEditor.create(
            library = FilterLibraryDraft(
                profiles = seeded.profiles.dropLast(1),
                activeProfileId = seeded.activeProfileId,
                resolverPlan = seeded.resolverPlan,
            ),
            name = "Core",
            duplicateFromId = null,
            maxFilters = 3,
            newId = "filter-a",
        ) as FilterLibraryEditResult.Rejected
        assertEquals(FilterLibraryRejection.DUPLICATE_NAME, result.reason)
    }

    @Test
    fun `rename is trimmed and unique case-insensitively`() {
        val renamed = FilterLibraryEditor.rename(seeded, "filter-essential", "  Gaming  ", 3)
            as FilterLibraryEditResult.Applied
        assertEquals("Gaming", renamed.library.profile("filter-essential")!!.name)

        val duplicate = FilterLibraryEditor.rename(seeded, "filter-essential", "BALANCED", 3)
            as FilterLibraryEditResult.Rejected
        assertEquals(FilterLibraryRejection.DUPLICATE_NAME, duplicate.reason)

        // Renaming to its own name (trimmed) is legal — the exclusion covers the target.
        val same = FilterLibraryEditor.rename(seeded, "filter-essential", "Core", 3)
            as FilterLibraryEditResult.Applied
        assertEquals("Core", same.library.profile("filter-essential")!!.name)
    }

    @Test
    fun `combined identity rename accepts one Unicode emoji and preserves all configuration`() {
        val custom = CustomBlocklistSource("custom-kept", "Kept", "https://lists.example.com/kept.txt")
        val original = seeded.profile("filter-essential")!!
            .withManualRules(ManualDomainRules(blockedDomains = setOf("ads.example"), allowedDomains = setOf("trusted.example")))
            .withCustomBlocklists(listOf(custom))
            .withEnabledSourceIds(setOf("custom-kept"))
        val library = seeded.withProfile(original)
        val applied = FilterLibraryEditor.rename(library, original.id, " Focus ", 3, "👩🏽‍💻")
            as FilterLibraryEditResult.Applied
        val saved = applied.library.profile(original.id)!!
        assertEquals("Focus", saved.name)
        assertEquals("👩🏽‍💻", saved.emoji)
        assertEquals(original.id, saved.id)
        assertEquals(original.protectionLevel, saved.protectionLevel)
        assertEquals(original.enabledSourceIds, saved.enabledSourceIds)
        assertEquals(original.customBlocklists, saved.customBlocklists)
        assertEquals(original.manualRules, saved.manualRules)
        assertEquals(library.activeProfileId, applied.library.activeProfileId)
        assertEquals(library.resolverTarget, applied.library.resolverTarget)
    }

    @Test
    fun `invalid emoji or duplicate name rejects the entire identity edit without fallback`() {
        for (emoji in listOf("A", "", "🌿🪴")) {
            val refused = FilterLibraryEditor.rename(seeded, "filter-essential", "Focus", 3, emoji)
                as FilterLibraryEditResult.Rejected
            assertEquals(FilterLibraryRejection.INVALID_EMOJI, refused.reason)
            assertEquals(seeded, refused.previous)
        }
        val duplicate = FilterLibraryEditor.rename(seeded, "filter-essential", "BALANCED", 3, "🪴")
            as FilterLibraryEditResult.Rejected
        assertEquals(FilterLibraryRejection.DUPLICATE_NAME, duplicate.reason)
        assertEquals(seeded, duplicate.previous)
    }

    @Test
    fun `create and rename refuse a name today's grammar rejects`() {
        assertEquals(
            FilterLibraryRejection.INVALID_NAME,
            (FilterLibraryEditor.create(twoFilterLibrary, "bad-name", null, 3, "filter-a")
                as FilterLibraryEditResult.Rejected).reason,
        )
        assertEquals(
            FilterLibraryRejection.INVALID_NAME,
            (FilterLibraryEditor.rename(seeded, "filter-essential", "bad-name", 3)
                as FilterLibraryEditResult.Rejected).reason,
        )
    }

    @Test
    fun `rename retains an unchanged legacy name and normalizes a real rename`() {
        val legacy = seeded.withProfile(
            FilterProfileDraft(
                id = "filter-essential",
                name = "My-Filter",
                protectionLevel = com.lavasec.app.foundation.config.ProtectionLevel.CORE,
                enabledSourceIds = seeded.profile("filter-essential")!!.enabledSourceIds,
            ),
        )
        val retained = FilterLibraryEditor.rename(legacy, "filter-essential", "My-Filter", 3)
            as FilterLibraryEditResult.Applied
        assertEquals("My-Filter", retained.library.profile("filter-essential")!!.name)

        val normalized = FilterLibraryEditor.rename(
            seeded,
            "filter-essential",
            "cafe\u0301",
            3,
        ) as FilterLibraryEditResult.Applied
        assertEquals("café", normalized.library.profile("filter-essential")!!.name)
    }

    @Test
    fun `names are unique under canonical equivalence`() {
        val result = FilterLibraryEditor.rename(seeded, "filter-essential", "cafe\u0301", 3)
            as FilterLibraryEditResult.Applied
        val same = FilterLibraryEditor.rename(result.library, "filter-balanced", "café", 3)
            as FilterLibraryEditResult.Rejected
        assertEquals(FilterLibraryRejection.DUPLICATE_NAME, same.reason)
    }

    @Test
    fun `rename refuses a blank name, an unknown filter, and a frozen filter`() {
        assertEquals(
            FilterLibraryRejection.BLANK_NAME,
            (FilterLibraryEditor.rename(seeded, "filter-essential", "  ", 3) as FilterLibraryEditResult.Rejected).reason,
        )
        assertEquals(
            FilterLibraryRejection.UNKNOWN_FILTER,
            (FilterLibraryEditor.rename(seeded, "nope", "X", 3) as FilterLibraryEditResult.Rejected).reason,
        )
        val overCap = seeded.withProfiles(
            seeded.profiles + FilterProfileDraft(
                id = "filter-user",
                name = "User",
                protectionLevel = null,
                enabledSourceIds = emptySet(),
            ),
        )
        // Four filters with cap 3: the excess non-active filter freezes.
        assertEquals(
            FilterLibraryRejection.FROZEN_FILTER,
            (FilterLibraryEditor.rename(overCap, "filter-user", "Y", 3) as FilterLibraryEditResult.Rejected).reason,
        )
    }

    @Test
    fun `delete refuses the active filter and the last remaining filter`() {
        assertEquals(
            FilterLibraryRejection.ACTIVE_FILTER_CANNOT_BE_DELETED,
            (FilterLibraryEditor.delete(seeded, setOf("filter-balanced"), 3) as FilterLibraryEditResult.Rejected).reason,
        )
        val single = seeded.withProfiles(listOf(seeded.profile("filter-balanced")!!))
        assertEquals(
            FilterLibraryRejection.LAST_FILTER_CANNOT_BE_DELETED,
            (FilterLibraryEditor.delete(single, setOf("filter-balanced"), 3) as FilterLibraryEditResult.Rejected).reason,
        )
    }

    @Test
    fun `delete removes the exact staged batch and drops nothing else`() {
        val deleted = FilterLibraryEditor.delete(
            seeded,
            setOf("filter-essential", "filter-comprehensive"),
            3,
        ) as FilterLibraryEditResult.Applied

        assertEquals(listOf("filter-balanced"), deleted.library.profiles.map(FilterProfileDraft::id))
    }

    @Test
    fun `delete refuses an unknown id and a frozen filter`() {
        assertEquals(
            FilterLibraryRejection.UNKNOWN_FILTER,
            (FilterLibraryEditor.delete(seeded, setOf("nope"), 3) as FilterLibraryEditResult.Rejected).reason,
        )
        val overCap = seeded.withProfiles(
            seeded.profiles + FilterProfileDraft(
                id = "filter-user",
                name = "User",
                protectionLevel = null,
                enabledSourceIds = emptySet(),
            ),
        )
        assertEquals(
            FilterLibraryRejection.FROZEN_FILTER,
            (FilterLibraryEditor.delete(overCap, setOf("filter-user"), 3) as FilterLibraryEditResult.Rejected).reason,
        )
    }

    @Test
    fun `restore defaults reseeds the three filters with balanced active and the resolver kept`() {
        val plan = com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY
        val library = seeded.withResolverPlan(plan)
        val restored = FilterLibraryEditor.restoreDefaults(library, catalog)

        assertEquals(3, restored.profiles.size)
        assertEquals("filter-balanced", restored.activeProfileId)
        assertEquals(plan, restored.resolverPlan)
        assertEquals(
            listOf("Core", "Balanced", "Extra"),
            restored.profiles.map(FilterProfileDraft::name),
        )
    }

    @Test
    fun `unique name derives base then base 2`() {
        assertEquals("Unique", FilterLibraryEditor.uniqueName(seeded, "Unique"))
        assertEquals("Balanced 2", FilterLibraryEditor.uniqueName(seeded, "Balanced"))
    }

    private companion object {
        val catalog = FilterCatalogSnapshot(
            listOf(
                CatalogFilterSource("blocklistproject-basic", FilterSourceCategory.SECURITY, true),
                CatalogFilterSource("stevenblack-unified", FilterSourceCategory.MULTI_PURPOSE, true),
            ),
        )

        val seeded = FilterLibraryDraft.seededDefaults(catalog)

        /** Two seeded filters (Core + Balanced active) so create fits under the cap. */
        val twoFilterLibrary = seeded.withProfiles(
            seeded.profiles.filter { it.id != "filter-comprehensive" },
        )
    }
}
