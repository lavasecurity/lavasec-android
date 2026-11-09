package com.lavasec.app.reactnative

import com.lavasec.app.foundation.backup.BackupRestorePlan
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.resolver.ResolverTier
import com.lavasec.app.foundation.resolver.toSelection
import org.json.JSONArray
import org.json.JSONObject

/** Non-secret, grant-gated review of the exact immutable plan the native publisher will consume. */
internal object NativeBackupRestoreReview {
    fun project(plan: BackupRestorePlan): JSONArray {
        fun row(title: String, before: Any?, after: Any?) = JSONObject().put("title", title)
            .put("before", before?.toString() ?: "None").put("after", after?.toString() ?: "None")
        fun state(value: Boolean) = if (value) "On" else "Off"
        fun section(id: String, rows: JSONArray) = JSONObject().put("id", id).put("rows", rows).put("notes", JSONArray())
        fun names(library: FilterLibraryDraft) = library.profiles.joinToString("\n") { "${it.emoji} ${it.name}" }
        fun resolver(tier: ResolverTier?): String? = tier?.let {
            val entry = it.entry
            listOfNotNull(it.displayName, entry.transport.name, entry.customDohUrl, entry.customDotEndpoint,
                entry.customDoqEndpoint, entry.customPlainEndpoint, entry.customSecondaryEndpoint).joinToString("\n")
        }
        val before = plan.previousLibrary; val after = plan.library
        val a = plan.previousPreferences; val b = plan.preferences
        val oldTiers = before.resolverTarget.toSelection().tiers; val newTiers = after.resolverTarget.toSelection().tiers
        val rows = JSONArray()
            .put(row("Protection on this device", state(a.protectionEnabledHint), state(b.protectionEnabledHint)))
            .put(row("Primary DNS", resolver(oldTiers.getOrNull(0)), resolver(newTiers.getOrNull(0))))
            .put(row("Primary DNS enabled", state(oldTiers[0].isEnabled), state(newTiers[0].isEnabled)))
            .put(row("Fallback DNS", resolver(oldTiers.getOrNull(1)), resolver(newTiers.getOrNull(1))))
            .put(row("Fallback DNS enabled", state(oldTiers.getOrNull(1)?.isEnabled == true), state(newTiers.getOrNull(1)?.isEnabled == true)))
            .put(row("Filtering Counts", state(a.keepFilteringCounts), state(b.keepFilteringCounts)))
            .put(row("Domain diagnostics", state(a.keepDomainDiagnostics), state(b.keepDomainDiagnostics)))
            .put(row("Network Activity", state(a.keepNetworkActivity), state(b.keepNetworkActivity)))
            .put(row("Lava Guard progress", state(a.keepLavaGuardProgress), state(b.keepLavaGuardProgress)))
            .put(row("Unlocked Lava Guards", plan.basis.guardianLedger?.records?.size ?: 0, plan.restored.lavaGuardUnlocks.records.size))
            .put(row("Active filter", before.profile(before.activeProfileId)?.name, after.profile(after.activeProfileId)?.name))
            .put(row("Filters", names(before), names(after)))
        val sections = JSONArray().put(section("settings", rows))
        plan.filterChanges.forEach { change ->
            val previous = change.before; val restored = change.after
            sections.put(section("filter:${change.filterID}", JSONArray()
                .put(row("Name", previous?.name, restored?.name))
                .put(row("Emoji", previous?.emoji, restored?.emoji))
                .put(row("Blocklists", previous?.enabledSourceIds?.sorted()?.joinToString("\n"), restored?.enabledSourceIds?.sorted()?.joinToString("\n")))
                .put(row("Custom blocklists", previous?.customBlocklists?.joinToString("\n") { "${it.displayName}\n${it.sourceUrl}" },
                    restored?.customBlocklists?.joinToString("\n") { "${it.displayName}\n${it.sourceUrl}" }))
                .put(row("Blocked domains", previous?.manualRules?.blockedDomains?.sorted()?.joinToString("\n"), restored?.manualRules?.blockedDomains?.sorted()?.joinToString("\n")))
                .put(row("Allowed domains", previous?.manualRules?.allowedDomains?.sorted()?.joinToString("\n"), restored?.manualRules?.allowedDomains?.sorted()?.joinToString("\n")))))
        }
        return sections
    }
}
