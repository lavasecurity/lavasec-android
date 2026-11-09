package com.lavasec.app.foundation.filter

import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the ported [FilterLibraryEditSession], covering every canonical rule: begin /
 * end lifecycle, staged additions / deletions / renames / emoji changes, return-to-baseline
 * undo, stable displayed rows, and whole-batch validation (baseline, active, frozen, emoji,
 * names, ids, count cap, live metadata).
 */
class FilterLibraryEditSessionTest {
    @Test fun `whole-library batch preserves a native QA overlay without turning it into profile rules`() {
        val original = library().withQaProbeSet(DeviceQaProbeSet.HOSTED)
        val changed = FilterLibraryEditSession().beginEditing(original).rename("b", "Renamed")
            .validatedLibrary(original, maximumFilters = 10)!!
        assertEquals(DeviceQaProbeSet.HOSTED, changed.qaProbeSet)
        assertEquals(original.profiles.first().manualRules, changed.profiles.first().manualRules)
    }

    @Test
    fun `ordinary filter edit preserves the exact retained resolver ladder and custom metadata`() {
        val custom = com.lavasec.app.foundation.resolver.ResolverSelectionEntry(
            com.lavasec.app.foundation.resolver.ResolverProviderId.CUSTOM,
            com.lavasec.app.foundation.resolver.CatalogResolverTransport.DOQ,
            customDoqEndpoint = "doq://dns.example:8853", customName = "Private",
            customSecondaryEndpoint = "doq://secondary.example:853")
        val rows = listOf(com.lavasec.app.foundation.resolver.ResolverTier(custom),
            com.lavasec.app.foundation.resolver.ResolverTier(
                com.lavasec.app.foundation.resolver.ResolverSelectionPresets.DEVICE_PLAIN, true, "Network fallback"))
        val target = checkNotNull(com.lavasec.app.foundation.resolver.ResolverSelection(rows).toFilterResolverPlan())
        val original = library().withResolverPlan(target)
        val result = FilterLibraryEditSession().beginEditing(original).rename("b", "Renamed")
            .validatedLibrary(original, maximumFilters = 10)
        assertEquals(target, result?.resolverTarget)
    }

    private val plan = MilestoneResolverPlan.DEVICE_DNS_ONLY

    private fun profile(id: String, name: String, emoji: String? = null) =
        FilterProfileDraft(
            id = id,
            name = name,
            protectionLevel = null,
            enabledSourceIds = emptySet(),
            emoji = emoji,
        )

    private fun library(
        profiles: List<FilterProfileDraft> = listOf(profile("a", "Active"), profile("b", "Other")),
        activeProfileId: String = "a",
    ) = FilterLibraryDraft(
        profiles = profiles,
        activeProfileId = activeProfileId,
        resolverPlan = plan,
    )

    private fun threeFilterLibrary() = library(
        profiles = listOf(
            profile("a", "Active"),
            profile("b", "Other"),
            profile("c", "Third"),
        ),
    )

    @Test
    fun `staging before beginEditing is ignored and endEditing discards every draft state`() {
        var session = FilterLibraryEditSession()
        session = session.toggleDeletion("a")
        session = session.add(profile("c", "New"))
        assertFalse(session.isEditing)
        assertTrue(session.stagedDeletions.isEmpty())
        assertTrue(session.additions.isEmpty())

        session = session.beginEditing(library())
        session = session.toggleDeletion("a")
        session = session.toggleDeletion("b")
        session = session.toggleDeletion("a")
        assertEquals(setOf("b"), session.stagedDeletions)
        assertTrue(session.isEditing)

        session = session.endEditing()
        assertFalse(session.isEditing)
        assertNull(session.baseline)
        assertTrue(session.stagedDeletions.isEmpty())
        assertTrue(session.additions.isEmpty())
        assertTrue(session.renames.isEmpty())
        assertTrue(session.emojiChanges.isEmpty())
        assertFalse(session.hasChanges)
    }

    @Test
    fun `beginEditing captures the baseline once and repeated entry preserves the draft`() {
        val base = library()
        val other = library(activeProfileId = "b")

        var session = FilterLibraryEditSession().beginEditing(base)
        session = session.toggleDeletion("b")
        session = session.beginEditing(other)

        assertEquals(base, session.baseline)
        assertEquals(setOf("b"), session.stagedDeletions)
    }

    @Test
    fun `mixed changes stage without touching the baseline and commit as one batch`() {
        val original = library()
        var session = FilterLibraryEditSession().beginEditing(original)
        session = session.rename("a", "Core")
        session = session.toggleDeletion("b")
        session = session.add(profile("c", "New"))

        assertEquals(original, session.baseline)
        assertEquals(listOf("Active", "Other"), original.profiles.map { it.name })

        val committed = session.validatedLibrary(original, maximumFilters = 2)
        assertNotNull(committed)
        assertEquals(listOf("Core", "New"), committed!!.profiles.map { it.name })
        assertEquals("a", committed.activeProfileId)
    }

    @Test
    fun `undoing a rename and deleting a new addition returns to no changes`() {
        val base = library()
        var session = FilterLibraryEditSession().beginEditing(base)
        session = session.rename("b", "Renamed")
        session = session.rename("b", "Other")
        session = session.add(profile("c", "New"))
        session = session.toggleDeletion("c")

        assertFalse(session.hasChanges)
        assertTrue(session.renames.isEmpty())
        assertTrue(session.additions.isEmpty())
    }

    @Test
    fun `name and emoji stay staged in review until commit`() {
        val original = library()
        var session = FilterLibraryEditSession().beginEditing(original)
        session = session.rename("b", "Renamed", "🔥")

        assertEquals("Other", original.profile("b")!!.name)
        assertEquals("🔥", session.displayedFilters.first { it.id == "b" }.emoji)
        assertTrue(session.hasChanges)

        val committed = session.validatedLibrary(original, maximumFilters = 2)!!
        assertEquals("Renamed", committed.profile("b")!!.name)
        assertEquals("🔥", committed.profile("b")!!.emoji)

        session = session.rename("b", "Other", original.profile("b")!!.emoji)
        assertFalse(session.hasChanges)
    }

    @Test
    fun `emoji-only edit is reviewed and a staged deletion remains valid`() {
        val original = library()
        var session = FilterLibraryEditSession().beginEditing(original)
        session = session.rename("b", "Other", "🔥")

        assertTrue(session.hasChanges)
        assertEquals("🔥", session.validatedLibrary(original, 2)!!.profile("b")!!.emoji)

        session = session.toggleDeletion("b")
        assertNotNull(session.validatedLibrary(original, 2))
    }

    @Test
    fun `displayedFilters keep pending removals in stable row positions`() {
        val original = library()
        var session = FilterLibraryEditSession().beginEditing(original)
        session = session.rename("b", "Renamed")
        session = session.toggleDeletion("b")

        assertEquals(listOf("a", "b"), session.displayedFilters.map { it.id })
        assertEquals("Renamed", session.displayedFilters.first { it.id == "b" }.name)
        // The saved baseline is untouched; the removal is display-only until confirmation.
        assertEquals("Other", original.profile("b")!!.name)
    }

    @Test
    fun `a changed baseline rejects the whole batch and keeps the draft`() {
        val baseline = library()
        var session = FilterLibraryEditSession().beginEditing(baseline)
        session = session.rename("b", "Other renamed")

        val changed = baseline.withProfile(profile("b", "Concurrent rename"))
        assertNull(session.validatedLibrary(changed, maximumFilters = 3))
        assertEquals("Other renamed", session.renames["b"])
    }

    @Test
    fun `deleting the active filter rejects the whole batch and keeps the draft`() {
        val base = library()
        var session = FilterLibraryEditSession().beginEditing(base)
        session = session.rename("b", "Other renamed")
        session = session.toggleDeletion("a")

        assertNull(session.validatedLibrary(base, maximumFilters = 3))
        assertTrue("a" in session.stagedDeletions)
        assertEquals("Other renamed", session.renames["b"])
    }

    @Test
    fun `a name colliding with another filter rejects the whole batch`() {
        val base = library()
        val session = FilterLibraryEditSession().beginEditing(base).rename("b", "ACTIVE")
        assertNull(session.validatedLibrary(base, maximumFilters = 3))
    }

    @Test
    fun `the count cap permits an addition-free over-cap batch but refuses one with additions`() {
        val three = threeFilterLibrary()
        var session = FilterLibraryEditSession().beginEditing(three)
        session = session.rename("b", "Renamed")

        // 3 profiles > cap 2, but no additions: the canonical exception permits the rename.
        assertNotNull(session.validatedLibrary(three, maximumFilters = 2))

        session = session.add(profile("d", "New"))
        assertNull(session.validatedLibrary(three, maximumFilters = 2))
    }

    @Test
    fun `frozen filters cannot be renamed deleted or emoji-changed`() {
        val three = threeFilterLibrary()
        assertTrue(FilterLibraryPolicy.isFrozen(three, "c", 2))

        assertNull(
            FilterLibraryEditSession().beginEditing(three).rename("c", "X")
                .validatedLibrary(three, 2),
        )
        assertNull(
            FilterLibraryEditSession().beginEditing(three).toggleDeletion("c")
                .validatedLibrary(three, 2),
        )
        assertNull(
            FilterLibraryEditSession().beginEditing(three).rename("c", "Third", "🔥")
                .validatedLibrary(three, 2),
        )
    }

    @Test
    fun `staged targets must still exist`() {
        val base = library()
        val start = FilterLibraryEditSession().beginEditing(base)

        assertNull(start.toggleDeletion("nope").validatedLibrary(base, 3))
        assertNull(start.rename("nope", "X").validatedLibrary(base, 3))
        assertNull(start.rename("nope", "X", "🔥").validatedLibrary(base, 3))
    }

    @Test
    fun `an invalid emoji rejects the whole batch`() {
        val base = library()
        val start = FilterLibraryEditSession().beginEditing(base)

        assertNull(start.rename("b", "Other", "A").validatedLibrary(base, 3))
        assertNull(start.rename("b", "Other", "").validatedLibrary(base, 3))
    }

    @Test
    fun `blank and duplicate names reject the whole batch`() {
        val base = library()

        assertNull(
            FilterLibraryEditSession().beginEditing(base).rename("b", "   ")
                .validatedLibrary(base, 3),
        )

        assertNull(
            FilterLibraryEditSession().beginEditing(base).add(profile("c", "New")).rename("b", "New")
                .validatedLibrary(base, 3),
        )

        assertNull(
            FilterLibraryEditSession().beginEditing(base)
                .add(profile("c", "New")).add(profile("d", "New"))
                .validatedLibrary(base, maximumFilters = 4),
        )
    }

    @Test
    fun `duplicate ids reject the whole batch`() {
        val base = library()
        val session = FilterLibraryEditSession().beginEditing(base).add(profile("a", "Duplicate id"))
        assertNull(session.validatedLibrary(base, maximumFilters = 3))
    }

    @Test
    fun `the committed library keeps the live active filter and resolver plan`() {
        val base = library(activeProfileId = "b")
        val session = FilterLibraryEditSession().beginEditing(base).rename("b", "Renamed")
        val committed = session.validatedLibrary(base, 3)!!

        assertEquals("b", committed.activeProfileId)
        assertEquals(plan, committed.resolverPlan)
    }

    @Test
    fun `nothing to commit returns null`() {
        val base = library()
        assertNull(FilterLibraryEditSession().validatedLibrary(base, 3))
        assertNull(FilterLibraryEditSession().beginEditing(base).validatedLibrary(base, 3))
        // A session that never captured a baseline cannot validate, even with staged changes.
        assertNull(
            FilterLibraryEditSession().beginEditing().add(profile("c", "New"))
                .validatedLibrary(base, 3),
        )
    }

    @Test
    fun `toggling a staged addition discards its rename and emoji change too`() {
        val base = library()
        var session = FilterLibraryEditSession().beginEditing(base)
        session = session.add(profile("c", "New"))
        session = session.rename("c", "Renamed", "🔥")
        assertEquals("Renamed", session.renames["c"])
        assertEquals("🔥", session.emojiChanges["c"])

        session = session.toggleDeletion("c")
        assertTrue(session.additions.isEmpty())
        assertFalse("c" in session.renames)
        assertFalse("c" in session.emojiChanges)
        assertFalse(session.hasChanges)
    }

    @Test
    fun `renaming an addition back to its added name clears the staged rename`() {
        var session = FilterLibraryEditSession().beginEditing(library()).add(profile("c", "New"))
        session = session.rename("c", "Renamed")
        assertEquals("Renamed", session.renames["c"])

        session = session.rename("c", "New")
        assertFalse("c" in session.renames)
        // The addition itself is still a change.
        assertTrue(session.hasChanges)
        assertEquals("New", session.displayedFilters.first { it.id == "c" }.name)
    }

    @Test
    fun `an addition can stage a name and emoji and commit them`() {
        val base = library()
        var session = FilterLibraryEditSession().beginEditing(base)
        session = session.add(profile("c", "New"))
        session = session.rename("c", "Renamed", "🔥")

        val committed = session.validatedLibrary(base, maximumFilters = 3)!!
        val added = committed.profile("c")!!
        assertEquals("Renamed", added.name)
        assertEquals("🔥", added.emoji)
    }
}
