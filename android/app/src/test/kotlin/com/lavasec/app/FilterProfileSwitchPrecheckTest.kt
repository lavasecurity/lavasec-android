package com.lavasec.app

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.foundation.filter.FilterProfile
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.product.FilterProfileSwitchRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the whole-profile switch gate against the exact seeded drafts: the gate must refuse
 * exactly the switches the durable adoption (`validateManualFilterConfiguration`) would reject,
 * and admit every clean seeded switch.
 */
class FilterProfileSwitchPrecheckTest {
    private val draft = FilterLibraryDraft.seededDefaults(
        com.lavasec.app.foundation.filter.FilterCatalogSnapshot(
            listOf(
                com.lavasec.app.foundation.filter.CatalogFilterSource(
                    "blocklistproject-basic",
                    com.lavasec.app.foundation.filter.FilterSourceCategory.SECURITY,
                    true,
                ),
                com.lavasec.app.foundation.filter.CatalogFilterSource(
                    "stevenblack-unified",
                    com.lavasec.app.foundation.filter.FilterSourceCategory.MULTI_PURPOSE,
                    true,
                ),
                com.lavasec.app.foundation.filter.CatalogFilterSource(
                    "blocklistproject-ads",
                    com.lavasec.app.foundation.filter.FilterSourceCategory.ADS_TRACKING,
                    false,
                ),
                com.lavasec.app.foundation.filter.CatalogFilterSource(
                    "blocklistproject-tracking",
                    com.lavasec.app.foundation.filter.FilterSourceCategory.ADS_TRACKING,
                    false,
                ),
            ),
        ),
    )

    @Test
    fun `clean seeded draft admits every non-active profile switch`() {
        ProtectionLevel.entries.forEach { level ->
            val target = FilterProfile.stableId(level)
            if (target != draft.activeProfileId) {
                assertNull(draft.profileSwitchRejection(target))
            } else {
                assertEquals(
                    FilterProfileSwitchRejection.NO_CHANGE,
                    draft.profileSwitchRejection(target),
                )
            }
        }
    }

    @Test
    fun `unknown target is refused`() {
        assertEquals(
            FilterProfileSwitchRejection.UNKNOWN_TARGET,
            draft.profileSwitchRejection("not-a-seeded-profile"),
        )
    }

    @Test
    fun `switch away from a rule-carrying active profile is admitted`() {
        // Each saved filter carries its own rules; applying a filter loads them, so a
        // rule-carrying active profile never blocks a switch (iOS switchToFilter semantics).
        val active = requireNotNull(draft.profile(draft.activeProfileId))
        val withRules = draft.withProfile(
            active.withManualRules(
                ManualDomainRules(blockedDomains = setOf("manual.example")),
            ),
        )
        val target = ProtectionLevel.entries
            .first { FilterProfile.stableId(it) != withRules.activeProfileId }
            .let(FilterProfile::stableId)

        assertNull(withRules.profileSwitchRejection(target))
    }

    @Test
    fun `a frozen target is refused`() {
        val frozenLibrary = FilterLibraryDraft(
            profiles = draft.profiles + FilterProfileDraft(
                id = "filter-user",
                name = "User",
                protectionLevel = null,
                enabledSourceIds = setOf("blocklistproject-basic"),
            ),
            activeProfileId = draft.activeProfileId,
            resolverPlan = draft.resolverPlan,
        )
        // Three seeded + one user filter: the cap freezes the excess non-active filter.
        assertEquals(
            FilterProfileSwitchRejection.FROZEN_TARGET,
            frozenLibrary.profileSwitchRejection("filter-user"),
        )
    }

    @Test
    fun `switch to a profile that already carries rules is admitted`() {
        val targetLevel = ProtectionLevel.EXTRA
        val target = FilterProfile.stableId(targetLevel)
        val targetProfile = requireNotNull(draft.profile(target))
        val withTargetRules = draft.withProfile(
            targetProfile.withManualRules(
                ManualDomainRules(allowedDomains = setOf("allowed.example")),
            ),
        )

        assertNull(withTargetRules.profileSwitchRejection(target))
    }
}
