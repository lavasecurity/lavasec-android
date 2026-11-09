package com.lavasec.app.reactnative

import com.lavasec.app.foundation.backup.*
import com.lavasec.app.foundation.filter.*
import com.lavasec.app.foundation.resolver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeBackupRestoreReviewTest {
    @Test fun `review exposes exact DNS and same-count domain replacements with retained logging preferences`() {
        fun library(domain: String, resolver: MilestoneResolverPlan) = FilterLibraryDraft(
            listOf(FilterProfileDraft("one", "Personal", null, emptySet(),
                ManualDomainRules(blockedDomains = setOf(domain)))), "one", resolver)
        val before = library("old.example", MilestoneResolverPlan.DEVICE_DNS_ONLY)
        val after = library("new.example", MilestoneResolverPlan.MULLVAD_ONLY)
        val preferences = BackupRetainedPreferences.DEFAULT
        val plan = BackupRestorePlan(RestoredBackupSettings(after, preferences.copy(keepDomainDiagnostics = true)),
            before, preferences, listOf(BackupFilterChange("one", before.profiles[0], after.profiles[0])),
            true, BackupRestoreBasis("account", 1))
        val projected = NativeBackupRestoreReview.project(plan)
        val rows = projected.getJSONObject(0).getJSONArray("rows")
        val dns = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("title") == "Primary DNS" }
        assertNotEquals(dns.getString("before"), dns.getString("after"))
        assertTrue(dns.getString("after").contains("DOH"))
        val logging = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("title") == "Domain diagnostics" }
        assertEquals("Off", logging.getString("before")); assertEquals("On", logging.getString("after"))
        val details = projected.getJSONObject(1).getJSONArray("rows")
        val domains = (0 until details.length()).map(details::getJSONObject).single { it.getString("title") == "Blocked domains" }
        assertEquals("old.example", domains.getString("before")); assertEquals("new.example", domains.getString("after"))
    }
}
