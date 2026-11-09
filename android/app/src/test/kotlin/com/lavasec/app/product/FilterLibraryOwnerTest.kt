package com.lavasec.app.product

import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.foundation.subscription.FeatureLimits
import com.lavasec.app.foundation.filter.CatalogFilterSource
import com.lavasec.app.foundation.filter.CustomBlocklistSource
import com.lavasec.app.foundation.filter.FilterCatalogSnapshot
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterLibraryPolicy
import com.lavasec.app.foundation.filter.FilterLibraryRejection
import com.lavasec.app.foundation.filter.FilterSourceCategory
import com.lavasec.app.foundation.filter.ManualDomainRules
import com.lavasec.app.foundation.filter.ManualRuleEditResult
import com.lavasec.app.foundation.filter.ManualRuleRejection
import com.lavasec.app.foundation.filter.ManualRuleTarget
import com.lavasec.app.foundation.share.ShareableFilterConfiguration
import com.lavasec.app.foundation.resolver.toSelection
import com.lavasec.app.platform.FilterMutationFailure
import com.lavasec.app.reactnative.NativeLibraryRenameContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the generation-fenced library owner against the coordinator contract: every durable
 * mutation (draft commit, create, rename, staged delete, restore defaults) reports one
 * authoritative outcome, the previous library stays in effect on failure, a retry re-derives
 * from the current state (no duplicated writes, no stale generation), drafts are independent
 * per filter id, and the product cap/frozen rules are enforced below the UI.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FilterLibraryOwnerTest {
    @Test fun `preparation carries native progress into owned worker and Close cancels before publication`() = runTest {
        val (owner, port) = owner(seeded)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var nativeWorker: kotlinx.coroutines.Job? = null
        var current = true
        val phases = mutableListOf<FilterPreparationProgress.Phase>()
        val progress = FilterPreparationProgress({ current }, { phases += it }, { nativeWorker = it })
        try {
            val id = seeded.activeProfileId
            assertTrue(owner.beginEdit(id))
            owner.addDomainToDraft(id, "pending.example", ManualRuleTarget.BLOCKED)
            val draft = owner.draftFor(id)
            val review = checkNotNull(owner.captureReview(id))
            port.beforeReviewedApply = {
                val inherited = kotlinx.coroutines.currentCoroutineContext()[FilterPreparationProgress]
                assertSame(progress, inherited)
                inherited!!.advance(FilterPreparationProgress.Phase.COMPILING)
                entered.complete(Unit)
                release.await()
            }
            val request = async { kotlinx.coroutines.withContext(progress) { owner.commitDraft(id, review) } }
            entered.await()
            assertNotNull(nativeWorker)
            assertEquals(listOf(FilterPreparationProgress.Phase.COMPILING), phases)
            current = false
            nativeWorker!!.cancel()
            request.join(); advanceUntilIdle()
            assertTrue(request.isCancelled)
            assertEquals(0, port.applyCount); assertEquals(seeded, port.library)
            assertSame(draft, owner.draftFor(id))
            assertFalse(owner.retryLast())
        } finally { owner.close() }
    }

    @Test fun `typed full blocked and allowed review refusals retain all rows and recapture after Plus without applying`() = runTest {
        for (target in listOf(ManualRuleTarget.BLOCKED, ManualRuleTarget.ALLOWED)) {
            val id = seeded.activeProfileId
            val capacity = (0 until 26).map { "capacity-$it.example" }.toSet()
            val rules = if (target == ManualRuleTarget.BLOCKED) ManualDomainRules(blockedDomains = capacity)
                else ManualDomainRules(allowedDomains = capacity)
            val saved = seeded.withProfile(checkNotNull(seeded.profile(id)).withManualRules(rules))
            var tier = FeatureLimits.PLUS
            val (owner, port) = owner(saved, limits = { tier })
            try {
                assertTrue(owner.beginEdit(id))
                val other = if (target == ManualRuleTarget.BLOCKED) ManualRuleTarget.ALLOWED else ManualRuleTarget.BLOCKED
                assertTrue(owner.addDomainToDraft(id, "staged.example", other) is ManualRuleEditResult.Applied)
                val draft = checkNotNull(owner.draftFor(id))
                val lease = checkNotNull(owner.draftLease(id))
                // Stage a valid Plus edit, then lose the plan before native review. A Free
                // editor now correctly refuses this cross-shelf addition at staging time.
                tier = FeatureLimits.FREE
                val expected = if (target == ManualRuleTarget.BLOCKED)
                    com.lavasec.app.foundation.filter.FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT
                    else com.lavasec.app.foundation.filter.FilterDraftReviewRejection.ALLOWED_DOMAIN_LIMIT
                assertEquals(FilterLibraryOwner.ReviewAttempt.Rejected(expected), owner.captureReviewAttempt(id, draft, lease) { true })
                assertNull(owner.captureReview(id))
                assertSame(draft, owner.draftFor(id)); assertEquals(lease, owner.draftLease(id))
                assertEquals(saved, port.library); assertEquals(0, port.applyCount)
                tier = FeatureLimits.PLUS
                val fresh = owner.captureReviewAttempt(id, draft, lease) { true }
                assertTrue(fresh is FilterLibraryOwner.ReviewAttempt.Captured)
                assertSame(draft, (fresh as FilterLibraryOwner.ReviewAttempt.Captured).review.draft)
                assertEquals(capacity, if (target == ManualRuleTarget.BLOCKED) draft.manualRules.blockedDomains else draft.manualRules.allowedDomains)
                assertEquals(saved, port.library); assertEquals(0, port.applyCount)
            } finally { owner.close() }
        }
    }

    @Test fun `typed review cannot classify a replaced draft lease or lost authority as capacity`() = runTest {
        val id = seeded.activeProfileId
        val saved = seeded.withProfile(checkNotNull(seeded.profile(id)).withManualRules(
            ManualDomainRules(blockedDomains = (0 until 26).map { "capacity-$it.example" }.toSet())))
        var tier = FeatureLimits.PLUS
        val (owner, port) = owner(saved, limits = { tier })
        try {
            assertTrue(owner.beginEdit(id))
            val draft = checkNotNull(owner.draftFor(id)); val lease = checkNotNull(owner.draftLease(id))
            assertEquals(FilterLibraryOwner.ReviewAttempt.Unavailable, owner.captureReviewAttempt(id, draft, lease) { false })
            assertTrue(owner.addDomainToDraft(id, "changed.example", ManualRuleTarget.ALLOWED) is ManualRuleEditResult.Applied)
            tier = FeatureLimits.FREE
            assertEquals(FilterLibraryOwner.ReviewAttempt.Unavailable, owner.captureReviewAttempt(id, draft, lease) { true })
            val changed = checkNotNull(owner.draftFor(id))
            assertTrue(owner.discardDraft(id, changed, null, lease)); assertTrue(owner.beginEdit(id))
            assertEquals(FilterLibraryOwner.ReviewAttempt.Unavailable, owner.captureReviewAttempt(id, null, lease) { true })
            assertEquals(saved, port.library); assertEquals(0, port.applyCount)
        } finally { owner.close() }
    }

    @Test fun `draft lease survives edits but old cancellation and custom source cannot enter a reopened saved ID`() = runTest {
        val (owner, port) = owner(seeded)
        try {
            val id = seeded.activeProfileId
            assertTrue(owner.beginEdit(id))
            val original = checkNotNull(owner.draftLease(id))
            owner.addDomainToDraft(id, "changed.example", ManualRuleTarget.BLOCKED)
            assertEquals(original, owner.draftLease(id))
            assertTrue(owner.discardDraft(id, null, null, original))
            assertNull(owner.draftLease(id))
            assertTrue(owner.beginEdit(id))
            val replacement = checkNotNull(owner.draftLease(id))
            assertFalse(original == replacement)
            // Reopening an unchanged saved profile may reuse its very same draft object;
            // a visit lease is the required additional ownership witness.
            assertFalse(owner.discardDraft(id, null, null, original))
            assertNull(owner.addCustomBlocklistToDraft(id, "Late source", "https://example.com/list.txt",
                com.lavasec.app.foundation.filter.BlocklistFormat.AUTO, original))
            assertEquals(replacement, owner.draftLease(id))
            assertNotNull(owner.draftFor(id)); assertEquals(0, port.applyCount)
            assertTrue(owner.discardDraft(id, null, null, replacement))
            assertNull(owner.draftLease(id))
        } finally { owner.close() }
    }

    @Test fun `stable creation lease survives identity changes and retires after explicit discard`() = runTest {
        val (owner, _) = owner(seeded, limits = { FeatureLimits.PLUS })
        try {
            owner.beginLibraryEditing()
            val created = owner.beginCreatingFilter(null, null) as FilterLibraryEditOutcome.Created
            val lease = checkNotNull(owner.draftLease(created.filterId))
            assertTrue(owner.renameFilter(created.filterId, "Named", "🌿"))
            assertEquals(lease, owner.draftLease(created.filterId))
            assertTrue(owner.discardDraft(created.filterId, null, null, lease))
            assertNull(owner.draftLease(created.filterId)); assertNull(owner.newFilter.value)
        } finally { owner.close() }
    }
    @Test fun `QA composite uses one exact accepted DNS source probe write and refuses drafts limits and foreign fields`() = runTest {
        val (owner, port) = owner(seeded)
        val target = com.lavasec.app.foundation.resolver.FilterResolverPlan(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.MULLVAD_ONLY)
        val guard = com.lavasec.app.platform.NativeFilterPublicationGuard { it() }
        val ticket = ReviewedQaResolverEdit(ResolverDnsContext.encode(seeded.resolverTarget.toSelection()), target, FeatureLimits.FREE, guard)
        val active = seeded.profile(seeded.activeProfileId)!!
        val desired = seeded.withProfile(active.withEnabledSourceIds(emptySet())).withQaProbeSet(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED).withResolverPlan(target)
        try {
            owner.beginEdit(active.id)
            assertTrue(owner.replaceReviewedCompositeForQa(desired, seeded, ticket) { true } is FilterLibraryMutationResult.Superseded)
            owner.discardDraft(active.id)
            val wrong = ReviewedQaResolverEdit(ticket.expectedDnsContext, target, FeatureLimits.PLUS, guard)
            assertTrue(owner.replaceReviewedCompositeForQa(desired, seeded, wrong) { true } is FilterLibraryMutationResult.Superseded)
            val other = seeded.profiles.first { it.id != active.id }
            val illegal = desired.withProfile(other.withManualRules(ManualDomainRules(blockedDomains = setOf("unreviewed.example"))))
            assertTrue(owner.replaceReviewedCompositeForQa(illegal, seeded, ticket) { true } is FilterLibraryMutationResult.Superseded)
            assertEquals(0, port.applyCount)
            assertTrue(owner.replaceReviewedCompositeForQa(desired, seeded, ticket) { true } is FilterLibraryMutationResult.Applied)
            assertEquals(desired, port.library); assertEquals(1, port.applyCount)
            assertSame(guard, port.qaGuard)
            assertFalse(owner.retryLast())
        } finally { owner.close() }
    }

    @Test
    fun `a draft commit applies the exact desired library and drops the draft`() = runTest {
        val (owner, port) = owner(seeded)
        owner.beginEdit("filter-balanced")
        owner.addDomainToDraft("filter-balanced", "manual.example", ManualRuleTarget.BLOCKED)
        advanceUntilIdle()
        assertEquals(
            setOf("manual.example"),
            owner.draftFor("filter-balanced")!!.manualRules.blockedDomains,
        )

        assertTrue(owner.commitDraft("filter-balanced"))
        advanceUntilIdle()

        assertEquals(
            FilterLibraryOwner.State.Succeeded(
                FilterLibraryOperation.CommitDraft("filter-balanced"),
                "filter-balanced",
            ),
            owner.state.value,
        )
        assertTrue(owner.drafts.value.isEmpty())
        assertEquals(
            setOf("manual.example"),
            port.currentLibrary().profile("filter-balanced")!!.manualRules.blockedDomains,
        )
        owner.close()
    }

    @Test
    fun `hasUnsavedDraft reports an edited draft and clears on discard`() = runTest {
        val (owner, _) = owner(seeded)
        assertFalse(owner.hasUnsavedDraft("filter-balanced"))
        owner.beginEdit("filter-balanced")
        // Opening an edit that has no changes is not an unsaved draft.
        assertFalse(owner.hasUnsavedDraft("filter-balanced"))
        owner.addDomainToDraft("filter-balanced", "manual.example", ManualRuleTarget.BLOCKED)
        advanceUntilIdle()
        assertTrue(owner.hasUnsavedDraft("filter-balanced"))
        assertFalse(owner.hasUnsavedDraft(seeded.profiles.first { it.id != "filter-balanced" }.id))
        owner.discardDraft("filter-balanced")
        assertFalse(owner.hasUnsavedDraft("filter-balanced"))
        owner.close()
    }

    @Test fun `portable replacement carries exact authorization to final library publication`() = runTest {
        val (owner, port) = owner(seeded)
        var current = true
        val desired = seeded.withProfiles(seeded.profiles.map { profile ->
            profile.withManualRules(ManualDomainRules(profile.manualRules.allowedDomains, setOf("portable.example")))
        })
        port.beforeReviewedApply = { current = false }
        try {
            assertFalse(owner.replaceAll(desired, clearEditing = false, expectedLibrary = seeded, isCurrent = { current }))
            assertEquals(1, port.reviewedWhileCurrentCount)
            assertEquals(0, port.applyCount)
            assertEquals(seeded, port.library)
        } finally { owner.close() }
    }

    @Test fun `portable replacement reports committed result after authority retires so publisher can compensate`() = runTest {
        val (owner, port) = owner(seeded)
        var current = true
        val desired = seeded.withProfiles(seeded.profiles.map { profile ->
            profile.withManualRules(ManualDomainRules(profile.manualRules.allowedDomains, setOf("portable.example")))
        })
        port.afterReviewedApply = { current = false }
        try {
            assertTrue(owner.replaceAll(desired, clearEditing = false, expectedLibrary = seeded, isCurrent = { current }))
            assertFalse(current)
            assertEquals(desired, port.library)
            assertEquals(1, port.applyCount)
            // Compensation owns the captured generation/CAS and does not borrow a new UI grant.
            assertTrue(owner.replaceAll(seeded, clearEditing = false, expectedLibrary = desired))
            assertEquals(seeded, port.library)
        } finally { owner.close() }
    }

    @Test
    fun `a preserving replace keeps drafts until explicitly discarded`() = runTest {
        val (owner, _) = owner(seeded)
        owner.beginEdit("filter-balanced")
        owner.addDomainToDraft("filter-balanced", "manual.example", ManualRuleTarget.BLOCKED)
        advanceUntilIdle()
        assertTrue(owner.drafts.value.isNotEmpty())

        // A restore write preserves editing so a failed publication rollback loses no unsaved work.
        assertTrue(owner.replaceAll(seeded, clearEditing = false))
        assertTrue("drafts must survive a preserving replace", owner.drafts.value.isNotEmpty())
        assertTrue(owner.hasUnsavedDraft("filter-balanced"))

        owner.discardEditingSessions()
        assertTrue(owner.drafts.value.isEmpty())
        owner.close()
    }

    @Test
    fun `drafts are independent per filter id and survive an active-filter switch`() = runTest {
        val (owner, port) = owner(seeded)
        owner.beginEdit("filter-essential")
        owner.addDomainToDraft("filter-essential", "blocked.example", ManualRuleTarget.BLOCKED)
        advanceUntilIdle()

        // A switch moves only the active pointer; the draft stays with its own filter and
        // committing it later lands in filter-essential, never in the newly active filter.
        port.apply(port.currentLibrary().withActiveProfile("filter-comprehensive"), FeatureLimitAdmission(FeatureLimits.FREE))
        advanceUntilIdle()
        assertEquals("filter-comprehensive", port.currentLibrary().activeProfileId)
        assertEquals(
            setOf("blocked.example"),
            owner.draftFor("filter-essential")!!.manualRules.blockedDomains,
        )

        assertTrue(owner.commitDraft("filter-essential"))
        advanceUntilIdle()
        assertEquals(
            setOf("blocked.example"),
            port.currentLibrary().profile("filter-essential")!!.manualRules.blockedDomains,
        )
        // The newly active filter carries no rules — the draft was never written into it.
        assertTrue(
            port.currentLibrary().profile("filter-comprehensive")!!
                .manualRules.blockedDomains.isEmpty(),
        )
        owner.close()
    }

    @Test
    fun `a failed compile keeps the previous library and a retry re-derives without duplication`() =
        runTest {
            val (owner, port) = owner(seeded, failNext = true)
            owner.beginEdit("filter-balanced")
            owner.addDomainToDraft("filter-balanced", "manual.example", ManualRuleTarget.BLOCKED)
            advanceUntilIdle()

            assertFalse(owner.commitDraft("filter-balanced"))
            advanceUntilIdle()

            assertEquals(
                FilterLibraryOwner.Failure.Commit,
                (owner.state.value as FilterLibraryOwner.State.Failed).failure,
            )
            // The previous library is still authoritative and the draft is preserved for review.
            assertTrue(port.currentLibrary().profile("filter-balanced")!!.manualRules.blockedDomains.isEmpty())
            assertEquals(
                setOf("manual.example"),
                owner.draftFor("filter-balanced")!!.manualRules.blockedDomains,
            )

            assertTrue(owner.retryLast())
            advanceUntilIdle()
            // The failed attempt never wrote; the retry is one fresh admission.
            assertEquals(2, port.applyCount)
            assertEquals(
                setOf("manual.example"),
                port.currentLibrary().profile("filter-balanced")!!.manualRules.blockedDomains,
            )
            owner.close()
        }

    @Test
    fun `a superseded commit fails honestly and never publishes stale content`() = runTest {
        val (owner, port) = owner(seeded, supersede = true)
        assertFalse(owner.renameFilter("filter-essential", "Renamed"))
        advanceUntilIdle()

        assertEquals(
            FilterLibraryOwner.Failure.Commit,
            (owner.state.value as FilterLibraryOwner.State.Failed).failure,
        )
        assertEquals("Core", port.currentLibrary().profile("filter-essential")!!.name)
        owner.close()
    }

    @Test
    fun `failed identity write retains both fields and retry saves exact emoji without consuming rule draft`() = runTest {
        val (owner, port) = owner(seeded, failNext = true)
        val id = "filter-essential"
        owner.beginEdit(id)
        owner.addDomainToDraft(id, "draft.example", ManualRuleTarget.BLOCKED)
        assertFalse(owner.renameFilter(id, "Focus", "👩🏽‍💻"))
        assertEquals(seeded, port.currentLibrary())
        assertEquals(
            FilterLibraryOperation.RenameFilter(id, "Focus", "👩🏽‍💻"),
            (owner.state.value as FilterLibraryOwner.State.Failed).operation,
        )
        assertTrue(owner.retryLast())
        assertEquals(2, port.applyCount)
        assertEquals("Focus", port.currentLibrary().profile(id)!!.name)
        assertEquals("👩🏽‍💻", port.currentLibrary().profile(id)!!.emoji)
        assertEquals(setOf("draft.example"), owner.draftFor(id)!!.manualRules.blockedDomains)
        assertTrue(port.currentLibrary().profile(id)!!.manualRules.blockedDomains.isEmpty())
        // Saving the older rule draft later must not rederive or overwrite the saved identity.
        assertTrue(owner.commitDraft(id))
        assertEquals("Focus", port.currentLibrary().profile(id)!!.name)
        assertEquals("👩🏽‍💻", port.currentLibrary().profile(id)!!.emoji)
        assertEquals(setOf("draft.example"), port.currentLibrary().profile(id)!!.manualRules.blockedDomains)
        owner.close()
    }

    @Test
    fun `invalid emoji and unavailable tier refuse combined rename before any durable write`() = runTest {
        val (owner, port) = owner(seeded)
        assertFalse(owner.renameFilter("filter-essential", "Focus", "A"))
        assertEquals(
            FilterLibraryRejection.INVALID_EMOJI,
            ((owner.state.value as FilterLibraryOwner.State.Failed).failure as FilterLibraryOwner.Failure.Rejected).reason,
        )
        assertEquals(0, port.applyCount)
        assertEquals(seeded, port.currentLibrary())
        owner.close()

        val denied = FilterLibraryOwner(
            port, this, StandardTestDispatcher(testScheduler), captureAdmission = { null },
        )
        assertFalse(denied.renameFilter("filter-essential", "Focus", "🪴"))
        assertEquals(0, port.applyCount)
        assertEquals(seeded, port.currentLibrary())
        denied.close()
    }

    @Test
    fun `create rename delete and restore enforce the cap and frozen rules below the UI`() =
        runTest {
            val (owner, port) = owner(seeded)
            // The three seeded defaults fill the free-tier cap exactly.
            assertFalse(owner.createFilter("Fourth", null))
            assertEquals(
                FilterLibraryRejection.LIMIT_REACHED,
                (owner.state.value as FilterLibraryOwner.State.Failed).let {
                    (it.failure as FilterLibraryOwner.Failure.Rejected).reason
                },
            )

            // After a staged delete the cap frees a slot and create succeeds with a unique name.
            assertTrue(owner.deleteFilters(setOf("filter-comprehensive")))
            advanceUntilIdle()
            assertTrue(owner.createFilter("Gaming", "filter-essential"))
            advanceUntilIdle()
            val created = port.currentLibrary().profiles
                .first { it.id != "filter-essential" && it.id != "filter-balanced" }
            assertEquals("Gaming", created.name)
            assertEquals(
                seeded.profile("filter-essential")!!.enabledSourceIds,
                created.enabledSourceIds,
            )

            assertTrue(owner.renameFilter(created.id, "Gaming 2"))
            advanceUntilIdle()
            assertEquals("Gaming 2", port.currentLibrary().profile(created.id)!!.name)

            // Restore defaults reseeds and wipes every draft.
            owner.beginEdit("filter-balanced")
            assertTrue(owner.restoreDefaults())
            advanceUntilIdle()
            assertEquals(3, port.currentLibrary().profiles.size)
            assertEquals("filter-balanced", port.currentLibrary().activeProfileId)
            assertTrue(owner.drafts.value.isEmpty())
            owner.close()
        }

    @Test
    fun `deleting the active filter is refused with the precise rejection`() = runTest {
        val (owner, _) = owner(seeded)
        assertFalse(owner.deleteFilters(setOf("filter-balanced")))
        assertEquals(
            FilterLibraryRejection.ACTIVE_FILTER_CANNOT_BE_DELETED,
            (owner.state.value as FilterLibraryOwner.State.Failed).let {
                (it.failure as FilterLibraryOwner.Failure.Rejected).reason
            },
        )
        owner.close()
    }

    @Test
    fun `a draft for a deleted filter refuses to commit with the precise rejection`() = runTest {
        val (owner, port) = owner(seeded)
        owner.beginEdit("filter-essential")
        assertTrue(owner.deleteFilters(setOf("filter-essential")))
        advanceUntilIdle()

        assertFalse(owner.commitDraft("filter-essential"))
        assertEquals(
            FilterLibraryRejection.UNKNOWN_FILTER,
            (owner.state.value as FilterLibraryOwner.State.Failed).let {
                (it.failure as FilterLibraryOwner.Failure.Rejected).reason
            },
        )
        owner.close()
    }

    @Test
    fun `protection unavailability arms reconstruction and settles idle`() = runTest {
        val (owner, _) = owner(seeded, protectionUnavailable = true)
        assertFalse(owner.renameFilter("filter-essential", "Renamed"))
        advanceUntilIdle()

        assertTrue(owner.reconstructionRequired.value)
        assertEquals(FilterLibraryOwner.State.Idle, owner.state.value)
        owner.close()
    }

    @Test
    fun `the domain editor rejects duplicates and invalid domains`() = runTest {
        val (owner, port) = owner(seeded)
        owner.beginEdit("filter-balanced")
        val added = owner.addDomainToDraft("filter-balanced", "manual.example", ManualRuleTarget.BLOCKED)
        assertTrue(added is ManualRuleEditResult.Applied)
        val duplicate = owner.addDomainToDraft("filter-balanced", "MANUAL.EXAMPLE", ManualRuleTarget.BLOCKED)
        assertEquals(
            ManualRuleRejection.ALREADY_BLOCKED,
            (duplicate as ManualRuleEditResult.Rejected).reason,
        )
        val invalid = owner.addDomainToDraft("filter-balanced", "not a domain", ManualRuleTarget.BLOCKED)
        assertEquals(
            ManualRuleRejection.INVALID_DOMAIN,
            (invalid as ManualRuleEditResult.Rejected).reason,
        )
        owner.close()
    }

    @Test
    fun `undo restores a configured domain and removes a not-configured addition`() = runTest {
        val id = "filter-balanced"
        val configured = seeded.withProfile(
            com.lavasec.app.foundation.filter.FilterProfileDraft(
                id,
                "Balanced",
                null,
                emptySet(),
                ManualDomainRules(blockedDomains = setOf("kept.example")),
            ),
        )
        val (owner, _) = owner(configured)
        owner.beginEdit(id)
        owner.addDomainToDraft(id, "added.example", ManualRuleTarget.BLOCKED)
        owner.addDomainToDraft(id, "kept.example", ManualRuleTarget.ALLOWED)
        advanceUntilIdle()

        assertTrue(owner.undoDomainInDraft(id, "added.example"))
        assertTrue(owner.undoDomainInDraft(id, "kept.example"))
        assertEquals(
            setOf("kept.example"),
            owner.draftFor(id)!!.manualRules.blockedDomains,
        )
        assertTrue(owner.draftFor(id)!!.manualRules.allowedDomains.isEmpty())
        owner.close()
    }

    @Test
    fun `undo restores a moved domain to its configured shelf and never leaves both`() = runTest {
        val id = "filter-balanced"
        val configured = seeded.withProfile(
            com.lavasec.app.foundation.filter.FilterProfileDraft(
                id,
                "Balanced",
                null,
                emptySet(),
                ManualDomainRules(allowedDomains = setOf("move.example")),
            ),
        )
        val (owner, _) = owner(configured)
        owner.beginEdit(id)
        owner.addDomainToDraft(id, "move.example", ManualRuleTarget.BLOCKED)
        assertEquals(setOf("move.example"), owner.draftFor(id)!!.manualRules.blockedDomains)
        assertTrue(owner.draftFor(id)!!.manualRules.allowedDomains.isEmpty())

        assertTrue(owner.undoDomainInDraft(id, "move.example"))
        assertEquals(setOf("move.example"), owner.draftFor(id)!!.manualRules.allowedDomains)
        assertTrue(owner.draftFor(id)!!.manualRules.blockedDomains.isEmpty())
        owner.close()
    }

    @Test
    fun `undo restores a source's saved enabled state in either direction`() = runTest {
        val id = "filter-balanced"
        val saved = seeded.profile(id)!!
        val enabledSource = saved.enabledSourceIds.first()
        val (owner, _) = owner(seeded)
        owner.beginEdit(id)
        owner.toggleSourceInDraft(id, enabledSource, false)
        owner.toggleSourceInDraft(id, "not-default-source", true)
        assertFalse(owner.draftFor(id)!!.enabledSourceIds.contains(enabledSource))
        assertTrue(owner.draftFor(id)!!.enabledSourceIds.contains("not-default-source"))

        assertTrue(owner.undoSourceInDraft(id, enabledSource))
        assertTrue(owner.undoSourceInDraft(id, "not-default-source"))
        assertEquals(saved.enabledSourceIds, owner.draftFor(id)!!.enabledSourceIds)
        owner.close()
    }

    @Test
    fun `a frozen filter refuses rename delete and draft commit`() = runTest {
        val overCap = seeded.withProfiles(
            seeded.profiles + com.lavasec.app.foundation.filter.FilterProfileDraft(
                "filter-user",
                "User",
                null,
                emptySet(),
            ),
        )
        var currentLimits = FeatureLimits.PLUS
        val (owner, _) = owner(overCap, limits = { currentLimits })
        assertTrue(owner.beginEdit("filter-user"))
        owner.addDomainToDraft("filter-user", "x.example.com", ManualRuleTarget.BLOCKED)
        currentLimits = FeatureLimits.FREE
        assertFalse(owner.beginEdit("filter-user"))
        assertEquals(null, owner.addDomainToDraft("filter-user", "later.example", ManualRuleTarget.ALLOWED))
        assertFalse(owner.removeDomainFromDraft("filter-user", "x.example.com", ManualRuleTarget.BLOCKED))
        assertFalse(owner.toggleSourceInDraft("filter-user", "blocklistproject-basic", true))
        assertFalse(owner.undoDomainInDraft("filter-user", "x.example.com"))
        assertFalse(owner.undoSourceInDraft("filter-user", "blocklistproject-basic"))
        assertEquals(setOf("x.example.com"), owner.draftFor("filter-user")!!.manualRules.blockedDomains)

        assertFalse(owner.renameFilter("filter-user", "Y"))
        assertEquals(
            FilterLibraryRejection.FROZEN_FILTER,
            (owner.state.value as FilterLibraryOwner.State.Failed).let {
                (it.failure as FilterLibraryOwner.Failure.Rejected).reason
            },
        )
        assertFalse(owner.deleteFilters(setOf("filter-user")))
        assertEquals(
            FilterLibraryRejection.FROZEN_FILTER,
            (owner.state.value as FilterLibraryOwner.State.Failed).let {
                (it.failure as FilterLibraryOwner.Failure.Rejected).reason
            },
        )
        assertFalse(owner.commitDraft("filter-user"))
        assertEquals(
            FilterLibraryRejection.FROZEN_FILTER,
            (owner.state.value as FilterLibraryOwner.State.Failed).let {
                (it.failure as FilterLibraryOwner.Failure.Rejected).reason
            },
        )
        owner.close()
    }

    @Test
    fun `committing a draft preserves a rename that landed after the draft was created`() =
        runTest {
            val (owner, port) = owner(seeded)
            owner.beginEdit("filter-balanced")
            owner.addDomainToDraft("filter-balanced", "manual.example", ManualRuleTarget.BLOCKED)
            advanceUntilIdle()

            // The user renames the filter in the library while the draft sits in its slot.
            assertTrue(owner.renameFilter("filter-balanced", "Balanced Plus"))
            advanceUntilIdle()

            assertTrue(owner.commitDraft("filter-balanced"))
            advanceUntilIdle()

            // The committed profile keeps the rename; the draft contributed only the
            // draftable fields (sources + manual rules).
            val committed = checkNotNull(port.currentLibrary().profile("filter-balanced"))
            assertEquals("Balanced Plus", committed.name)
            assertEquals(
                setOf("manual.example"),
                committed.manualRules.blockedDomains,
            )
            owner.close()
        }

    @Test
    fun `a lapse refuses an over-limit open draft and preserves saved data`() = runTest {
        var tier = FeatureLimits.PLUS
        val (owner, port) = owner(seeded, limits = { tier })
        val id = seeded.activeProfileId
        owner.beginEdit(id)
        repeat(26) { owner.addDomainToDraft(id, "entry-$it.example", ManualRuleTarget.BLOCKED) }
        assertEquals(null, owner.reviewRejectionFor(id))
        tier = FeatureLimits.FREE
        assertEquals(com.lavasec.app.foundation.filter.FilterDraftReviewRejection.BLOCKED_DOMAIN_LIMIT, owner.reviewRejectionFor(id))
        assertFalse(owner.commitDraft(id))
        assertEquals(0, port.applyCount)
        assertEquals(26, owner.draftFor(id)!!.manualRules.blockedDomains.size)
        owner.removeDomainFromDraft(id, "entry-25.example", ManualRuleTarget.BLOCKED)
        assertTrue(owner.commitDraft(id))
        assertEquals(25, port.currentLibrary().profile(id)!!.manualRules.blockedDomains.size)
        owner.close()
    }

    @Test
    fun `Plus creation cap becomes Free freeze without removing any saved filters`() = runTest {
        var tier = FeatureLimits.PLUS
        val (owner, port) = owner(seeded, limits = { tier })
        repeat(47) { assertTrue(owner.createFilter("Custom $it", null)) }
        assertEquals(50, port.currentLibrary().profiles.size)
        assertFalse(owner.createFilter("Overflow", null))
        val saved = port.currentLibrary()
        tier = FeatureLimits.FREE
        assertFalse(owner.renameFilter(saved.profiles.last().id, "Frozen"))
        assertFalse(owner.createFilter("Another", null))
        assertEquals(saved, port.currentLibrary())
        tier = FeatureLimits.PLUS
        assertTrue(owner.renameFilter(saved.profiles.last().id, "Available again"))
        owner.close()
    }

    @Test fun `retained draft refuses mutation after its saved profile disappears`() = runTest {
        val (owner, port) = owner(seeded)
        val id = seeded.profiles.first { it.id != seeded.activeProfileId }.id
        assertTrue(owner.beginEdit(id))
        val draft = owner.draftFor(id)
        port.library = port.library.withProfiles(port.library.profiles.filter { it.id != id })
        assertEquals(null, owner.addDomainToDraft(id, "later.example", ManualRuleTarget.BLOCKED))
        assertFalse(owner.removeDomainFromDraft(id, "later.example", ManualRuleTarget.BLOCKED))
        assertFalse(owner.toggleSourceInDraft(id, "blocklistproject-basic", false))
        assertEquals(draft, owner.draftFor(id))
        owner.close()
    }

    @Test fun `repeated edit preserves changes and a discarded draft starts from current storage`() = runTest {
        val (owner, port) = owner(seeded)
        val id = seeded.activeProfileId
        assertTrue(owner.beginEdit(id))
        owner.addDomainToDraft(id, "retained.example", ManualRuleTarget.BLOCKED)
        assertTrue(owner.beginEdit(id))
        assertEquals(setOf("retained.example"), owner.draftFor(id)!!.manualRules.blockedDomains)
        owner.discardDraft(id)
        val saved = port.library.profile(id)!!
        port.library = port.library.withProfile(com.lavasec.app.foundation.filter.FilterProfileDraft(
            saved.id, saved.name, saved.protectionLevel, saved.enabledSourceIds,
            ManualDomainRules(blockedDomains = setOf("current.example")),
        ))
        assertTrue(owner.beginEdit(id))
        assertEquals(setOf("current.example"), owner.draftFor(id)!!.manualRules.blockedDomains)
        assertFalse(owner.beginEdit("missing"))
        owner.close()
        assertFalse(owner.beginEdit(id))
    }

    @Test fun `review confirmation publishes the exact captured draft`() = runTest {
        val (owner, port) = owner(seeded)
        val id = seeded.activeProfileId
        owner.beginEdit(id)
        owner.addDomainToDraft(id, "reviewed.example", ManualRuleTarget.BLOCKED)
        val review = checkNotNull(owner.captureReview(id))
        assertTrue(owner.commitDraft(id, review))
        assertEquals(setOf("reviewed.example"), port.library.profile(id)!!.manualRules.blockedDomains)
        owner.close()
    }

    @Test fun `review cannot confirm a changed draft baseline active filter or different owner`() = runTest {
        for (change in listOf("draft", "baseline", "resolver", "active", "owner")) {
            val (owner, port) = owner(seeded)
            val id = seeded.activeProfileId
            owner.beginEdit(id)
            owner.addDomainToDraft(id, "reviewed.example", ManualRuleTarget.BLOCKED)
            val review = checkNotNull(owner.captureReview(id))
            when (change) {
                "draft" -> owner.addDomainToDraft(id, "unreviewed.example", ManualRuleTarget.BLOCKED)
                "baseline" -> port.library = port.library.withProfile(port.library.profile(id)!!.let { com.lavasec.app.foundation.filter.FilterProfileDraft(it.id, it.name, it.protectionLevel, it.enabledSourceIds, ManualDomainRules(setOf("changed.example"), emptySet())) })
                "resolver" -> port.library = port.library.withResolverPlan(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY)
                "active" -> port.library = port.library.withActiveProfile(port.library.profiles.first { it.id != id }.id)
                "owner" -> {
                    val (other, otherPort) = this.owner(seeded)
                    other.beginEdit(id)
                    other.addDomainToDraft(id, "reviewed.example", ManualRuleTarget.BLOCKED)
                    assertFalse(other.commitDraft(id, review))
                    assertEquals(0, otherPort.applyCount)
                    other.close(); owner.close()
                    continue
                }
            }
            assertFalse(owner.commitDraft(id, review))
            assertEquals(0, port.applyCount)
            owner.close()
        }
    }

    @Test fun `persisted rename invalidates a review even though names are not rule inputs`() = runTest {
        val (owner, port) = owner(seeded)
        val id = seeded.activeProfileId
        owner.beginEdit(id)
        owner.addDomainToDraft(id, "reviewed.example", ManualRuleTarget.BLOCKED)
        val review = checkNotNull(owner.captureReview(id))
        val saved = port.library.profile(id)!!
        port.library = port.library.withProfile(com.lavasec.app.foundation.filter.FilterProfileDraft(
            saved.id, "Renamed", saved.protectionLevel, saved.enabledSourceIds, saved.manualRules,
        ))
        assertFalse(owner.commitDraft(id, review))
        assertEquals(0, port.applyCount)
        assertEquals("Renamed", port.library.profile(id)!!.name)
        assertTrue(port.library.profile(id)!!.manualRules.blockedDomains.isEmpty())
        owner.close()
    }

    @Test fun `generation change rejects a review even when library contents return to the same value`() = runTest {
        val (owner, port) = owner(seeded)
        val id = seeded.activeProfileId
        owner.beginEdit(id)
        owner.addDomainToDraft(id, "reviewed.example", ManualRuleTarget.BLOCKED)
        val review = checkNotNull(owner.captureReview(id))
        port.library = port.library.withResolverPlan(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY)
        port.library = seeded
        assertFalse(owner.commitDraft(id, review))
        assertEquals(0, port.applyCount)
        owner.close()
    }

    @Test fun `retry preserves review equality instead of accepting a new draft`() = runTest {
        for (change in listOf(false, true)) {
            val (owner, port) = owner(seeded, failNext = true)
            val id = seeded.activeProfileId
            owner.beginEdit(id)
            owner.addDomainToDraft(id, "reviewed.example", ManualRuleTarget.BLOCKED)
            assertFalse(owner.commitDraft(id, checkNotNull(owner.captureReview(id))))
            val failed = owner.state.value as FilterLibraryOwner.State.Failed
            if (change) owner.addDomainToDraft(id, "later.example", ManualRuleTarget.BLOCKED)
            assertEquals(!change, owner.retryReviewed(failed))
            assertEquals(if (change) 1 else 2, port.applyCount)
            owner.close()
        }
    }

    @Test fun `reviewed source failures reacquire only while draft baseline and failure remain current`() = runTest {
        for (change in listOf("none", "draft", "baseline", "operation")) {
            val (owner, port) = owner(seeded)
            val id = seeded.activeProfileId
            owner.beginEdit(id)
            owner.addDomainToDraft(id, "reviewed.example", ManualRuleTarget.BLOCKED)
            port.failNextSource = com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure.NETWORK
            assertFalse(owner.commitDraft(id, checkNotNull(owner.captureReview(id))))
            val failed = owner.state.value as FilterLibraryOwner.State.Failed
            assertTrue(failed.failure is FilterLibraryOwner.Failure.Source)
            assertEquals(seeded, port.library)
            when (change) {
                "draft" -> owner.addDomainToDraft(id, "later.example", ManualRuleTarget.BLOCKED)
                "baseline" -> port.library = seeded
                "operation" -> assertTrue(owner.renameFilter(seeded.profiles.first { it.id != id }.id, "Other filter"))
            }
            val before = port.applyCount
            assertEquals(change == "none", owner.retryReviewed(failed))
            assertEquals(before + if (change == "none") 1 else 0, port.applyCount)
            assertEquals(change == "none", "reviewed.example" in port.library.profile(id)!!.manualRules.blockedDomains)
            owner.close()
        }
    }

    @Test fun `publication refuses a baseline changed after owner review comparison`() = runTest {
        val (owner, port) = owner(seeded)
        val id = seeded.activeProfileId
        owner.beginEdit(id)
        owner.addDomainToDraft(id, "reviewed.example", ManualRuleTarget.BLOCKED)
        val review = checkNotNull(owner.captureReview(id))
        port.beforeReviewedApply = {
            port.library = port.library.withActiveProfile(port.library.profiles.first { it.id != id }.id)
        }
        assertFalse(owner.commitDraft(id, review))
        assertEquals(0, port.applyCount)
        assertTrue(port.library.profile(id)!!.manualRules.blockedDomains.isEmpty())
        owner.close()
    }

    @Test fun `retry refuses a failed operation replaced by another library operation`() = runTest {
        val (owner, port) = owner(seeded, failNext = true)
        val id = seeded.activeProfileId
        owner.beginEdit(id)
        owner.addDomainToDraft(id, "reviewed.example", ManualRuleTarget.BLOCKED)
        assertFalse(owner.commitDraft(id, checkNotNull(owner.captureReview(id))))
        val failed = owner.state.value as FilterLibraryOwner.State.Failed
        assertTrue(owner.renameFilter(seeded.profiles.first { it.id != id }.id, "Other filter"))
        assertFalse(owner.retryReviewed(failed))
        assertEquals(2, port.applyCount)
        owner.close()
    }

    @Test fun `queued duplicate retries cannot overwrite the first retry outcome`() = runTest {
        val (owner, port) = owner(seeded, failNext = true)
        val id = seeded.activeProfileId
        owner.beginEdit(id)
        owner.addDomainToDraft(id, "reviewed.example", ManualRuleTarget.BLOCKED)
        assertFalse(owner.commitDraft(id, checkNotNull(owner.captureReview(id))))
        val failed = owner.state.value as FilterLibraryOwner.State.Failed
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        port.beforeReviewedApply = { entered.complete(Unit); release.await() }
        val first = async { owner.retryReviewed(failed) }
        entered.await()
        val second = async { owner.retryReviewed(failed) }
        advanceUntilIdle()
        release.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(2, port.applyCount)
        assertTrue(owner.state.value is FilterLibraryOwner.State.Succeeded)
        owner.close()
    }

    private fun TestScope.owner(
        initial: FilterLibraryDraft,
        failNext: Boolean = false,
        supersede: Boolean = false,
        protectionUnavailable: Boolean = false,
        limits: () -> FeatureLimits = { FeatureLimits.FREE },
        creationName: () -> String = { "Untitled" },
    ): Pair<FilterLibraryOwner, RecordingPort> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = RecordingPort(initial, failNext, supersede, protectionUnavailable)
        val owner = FilterLibraryOwner(port, this, dispatcher, limits = limits, creationName = creationName)
        return owner to port
    }

    // ---- canonical library.* whole-library edit session ----

    private fun editCompleted(outcome: FilterLibraryEditOutcome) {
        assertTrue("expected Completed but was $outcome", outcome is FilterLibraryEditOutcome.Completed)
    }

    private fun editRejection(outcome: FilterLibraryEditOutcome): FilterLibraryEditRejection {
        assertTrue("expected Rejected but was $outcome", outcome is FilterLibraryEditOutcome.Rejected)
        return (outcome as FilterLibraryEditOutcome.Rejected).reason
    }

    @Test
    fun `library session stages a whole batch and writes only the validated commit`() = runTest {
        val (owner, port) = owner(seeded)
        editCompleted(owner.beginLibraryEditing())
        assertTrue(owner.editSession.value.isEditing)

        editCompleted(owner.stageLibraryRename("filter-essential", "Core Renamed", "🔥"))
        editCompleted(owner.toggleStagedDeletion("filter-comprehensive"))
        assertEquals("Core Renamed", owner.editSession.value.renames["filter-essential"])
        assertEquals(setOf("filter-comprehensive"), owner.editSession.value.stagedDeletions)

        // The saved library is untouched until confirmation.
        assertEquals(seeded, port.currentLibrary())
        assertEquals(0, port.applyCount)

        editCompleted(owner.commitLibraryEdit())
        assertEquals(1, port.applyCount)
        val saved = port.currentLibrary()
        assertEquals("Core Renamed", saved.profile("filter-essential")!!.name)
        assertEquals("🔥", saved.profile("filter-essential")!!.emoji)
        assertNull(saved.profile("filter-comprehensive"))
        // The session is discarded only after the durable apply.
        assertFalse(owner.editSession.value.isEditing)
        assertFalse(owner.editSession.value.hasChanges)
        owner.close()
    }

    @Test
    fun `library session refuses staging before edit and never touches the library`() = runTest {
        val (owner, port) = owner(seeded)
        assertEquals(
            FilterLibraryEditRejection.NOT_EDITING,
            editRejection(owner.toggleStagedDeletion("filter-comprehensive")),
        )
        assertEquals(
            FilterLibraryEditRejection.NOT_EDITING,
            editRejection(owner.stageLibraryCreate("New", null)),
        )
        assertEquals(
            FilterLibraryEditRejection.NOT_EDITING,
            editRejection(owner.commitLibraryEdit()),
        )
        assertEquals(0, port.applyCount)
        owner.close()
    }

    @Test
    fun `library session refuses the active filter and unknown or frozen deletions`() = runTest {
        val (owner, _) = owner(seeded)
        owner.beginLibraryEditing()
        assertEquals(
            FilterLibraryEditRejection.ACTIVE_FILTER,
            editRejection(owner.toggleStagedDeletion(seeded.activeProfileId)),
        )
        assertEquals(
            FilterLibraryEditRejection.UNKNOWN_FILTER,
            editRejection(owner.toggleStagedDeletion("missing")),
        )
        owner.close()

        val overCap = seeded.withProfiles(
            seeded.profiles + com.lavasec.app.foundation.filter.FilterProfileDraft(
                "filter-user", "User", null, emptySet(),
            ),
        )
        val (overOwner, _) = owner(overCap)
        overOwner.beginLibraryEditing()
        val frozen = overCap.profiles.last().id
        assertTrue(FilterLibraryPolicy.isFrozen(overCap, frozen, FeatureLimits.FREE.maxFilters))
        assertEquals(
            FilterLibraryEditRejection.FROZEN_FILTER,
            editRejection(overOwner.toggleStagedDeletion(frozen)),
        )
        overOwner.close()
    }

    @Test
    fun `library form create derives a unique name and enforces the cap and has-changes guards`() = runTest {
        // The three seeded defaults fill the free-tier cap exactly.
        val (owner, port) = owner(seeded)
        owner.beginLibraryEditing()
        assertEquals(
            FilterLibraryEditRejection.LIMIT_REACHED,
            editRejection(owner.stageLibraryCreate("Fourth", null)),
        )
        editCompleted(owner.toggleStagedDeletion("filter-comprehensive"))
        assertEquals(
            FilterLibraryEditRejection.HAS_CHANGES,
            editRejection(owner.stageLibraryCreate("Fourth", null)),
        )
        owner.cancelLibraryEditing()
        owner.close()

        // A library under the cap stages a derived-name addition without writing the library.
        val two = seeded.withProfiles(seeded.profiles.take(2))
        val (createOwner, createPort) = owner(two)
        createOwner.beginLibraryEditing()
        val created = createOwner.stageLibraryCreate(null, "filter-essential")
        assertTrue(created is FilterLibraryEditOutcome.Created)
        val id = (created as FilterLibraryEditOutcome.Created).filterId
        assertEquals(two, createPort.currentLibrary())
        val addition = checkNotNull(createOwner.editSession.value.additions.first { it.id == id })
        assertEquals("Untitled", addition.name)
        assertEquals(
            two.profile("filter-essential")!!.enabledSourceIds,
            addition.enabledSourceIds,
        )
        editCompleted(createOwner.commitLibraryEdit())
        assertEquals(3, createPort.currentLibrary().profiles.size)
        assertEquals("Untitled", createPort.currentLibrary().profile(id)!!.name)
        createOwner.close()
    }

    @Test
    fun `library form rename rejects invalid names and emoji and stages a valid identity edit`() = runTest {
        val (owner, _) = owner(seeded)
        owner.beginLibraryEditing()
        assertEquals(
            FilterLibraryEditRejection.INVALID_EMOJI,
            editRejection(owner.stageLibraryRename("filter-essential", "Core", "A")),
        )
        assertEquals(
            FilterLibraryEditRejection.INVALID_NAME,
            editRejection(owner.stageLibraryRename("filter-essential", "   ", null)),
        )
        assertEquals(
            FilterLibraryEditRejection.UNKNOWN_FILTER,
            editRejection(owner.stageLibraryRename("missing", "Renamed", null)),
        )
        editCompleted(owner.stageLibraryRename("filter-essential", "Core", "🪴"))
        assertEquals("🪴", owner.editSession.value.emojiChanges["filter-essential"])
        owner.close()
    }

    @Test
    fun `reopening a staged rename and saving unchanged retains its identity until batch confirmation`() = runTest {
        val (owner, port) = owner(seeded)
        try {
            val id = seeded.activeProfileId
            assertFalse("The staged emoji must differ from the saved identity", seeded.profile(id)?.emoji == "🌻")
            editCompleted(owner.beginLibraryEditing())
            editCompleted(owner.stageLibraryRename(id, "Staged identity", "🌻"))
            val reopened = NativeLibraryRenameContext(owner.editSession.value, id)
            val displayed = checkNotNull(reopened.target)
            assertEquals("Staged identity", displayed.name)
            assertEquals("🌻", displayed.emoji)
            assertTrue(reopened.isCurrent(owner.editSession.value))
            assertTrue(reopened.isNameAvailable(displayed.name))

            // The same production form context supplies the unchanged Save values and CAS.
            editCompleted(owner.stageLibraryRename(id, displayed.name, displayed.emoji,
                expectedSession = reopened.session))
            assertEquals(seeded, port.library)
            assertEquals(0, port.applyCount)
            assertEquals("Staged identity", owner.editSession.value.renames[id])
            assertEquals("🌻", owner.editSession.value.emojiChanges[id])
            assertTrue(owner.editSession.value.hasChanges)

            editCompleted(owner.commitLibraryEdit(expectedSession = owner.editSession.value))
            assertEquals("Staged identity", port.library.profile(id)?.name)
            assertEquals("🌻", port.library.profile(id)?.emoji)
            assertEquals(1, port.applyCount)
            assertFalse(owner.editSession.value.isEditing)
        } finally { owner.close() }
    }

    @Test
    fun `library rename form validates names against captured staged rows`() = runTest {
        val (owner, port) = owner(seeded)
        try {
            val id = seeded.activeProfileId
            val other = seeded.profiles.first { it.id != id }
            editCompleted(owner.beginLibraryEditing())
            editCompleted(owner.stageLibraryRename(other.id, "Staged taken", "🌿"))
            val form = NativeLibraryRenameContext(owner.editSession.value, id)
            assertFalse("A staged name is unavailable before it is saved", form.isNameAvailable(" staged TAKEN "))
            assertTrue("The captured rows released the other filter's saved name", form.isNameAvailable(other.name))
            assertTrue("An unchanged own identity is valid", form.isNameAvailable(checkNotNull(form.target).name))
            assertEquals(seeded, port.library)
            assertEquals(0, port.applyCount)
        } finally { owner.close() }
    }

    @Test
    fun `reopened library rename cannot save into a replacement edit session`() = runTest {
        val (owner, port) = owner(seeded)
        try {
            val id = seeded.activeProfileId
            editCompleted(owner.beginLibraryEditing())
            editCompleted(owner.stageLibraryRename(id, "Old staged identity", "🪴"))
            val form = NativeLibraryRenameContext(owner.editSession.value, id)
            val displayed = checkNotNull(form.target)
            editCompleted(owner.cancelLibraryEditing())
            editCompleted(owner.beginLibraryEditing())
            val successor = owner.editSession.value
            assertFalse(form.isCurrent(successor))
            assertEquals(FilterLibraryEditRejection.STALE_STAGING,
                editRejection(owner.stageLibraryRename(id, displayed.name, displayed.emoji,
                    expectedSession = form.session)))
            assertSame(successor, owner.editSession.value)
            assertFalse(successor.hasChanges)
            assertEquals(seeded, port.library)
            assertEquals(0, port.applyCount)
        } finally { owner.close() }
    }

    @Test
    fun `captured library rename refuses a saved-library change while the same session stays open`() = runTest {
        val (owner, port) = owner(seeded)
        try {
            val id = seeded.activeProfileId
            assertFalse("The staged emoji must differ from the saved identity", seeded.profile(id)?.emoji == "🌻")
            editCompleted(owner.beginLibraryEditing())
            editCompleted(owner.stageLibraryRename(id, "Captured identity", "🌻"))
            val form = NativeLibraryRenameContext(owner.editSession.value, id)
            val capturedSession = owner.editSession.value
            val saved = checkNotNull(port.library.profile(id))
            val changedLibrary = port.library.withProfile(saved.withManualRules(
                ManualDomainRules(blockedDomains = setOf("concurrent.example"))))
            // A real saved write can land during MainVM's awaited authorization, without
            // replacing this transient session. Simulate only that independent publication.
            port.library = changedLibrary
            assertTrue(form.isCurrent(owner.editSession.value))

            assertEquals(FilterLibraryEditRejection.STALE_STAGING,
                editRejection(owner.stageLibraryRename(id, "Save after authorization", "🌿",
                    expectedSession = form.session)))
            assertSame(capturedSession, owner.editSession.value)
            assertEquals("Captured identity", owner.editSession.value.renames[id])
            assertEquals("🌻", owner.editSession.value.emojiChanges[id])
            assertEquals(changedLibrary, port.library)
            assertEquals(0, port.applyCount)
        } finally { owner.close() }
    }

    @Test
    fun `saved template copies complete content under Free admission and a failed save retains the staged copy`() = runTest {
        val custom = CustomBlocklistSource(
            "custom-kept", "Kept source", "https://lists.example.com/kept.txt",
            createdAtEpochMillis = 123L, lastAcceptedHash = "accepted-hash",
        )
        val source = seeded.profile("filter-essential")!!
            .withCustomBlocklists(listOf(custom))
            .withEnabledSourceIds(setOf("custom-kept", "blocklistproject-basic"))
            .withManualRules(ManualDomainRules(
                blockedDomains = setOf("ads.example"), allowedDomains = setOf("trusted.example"),
            ))
        val two = seeded.withProfiles(seeded.profiles.take(2)).withProfile(source)
        val (owner, port) = owner(two, failNext = true)
        owner.beginLibraryEditing()
        val id = (owner.stageLibraryCreate(null, source.id) as FilterLibraryEditOutcome.Created).filterId
        assertFalse(id == source.id)
        val staged = owner.editSession.value.additions.single()
        assertEquals(id, staged.id)
        assertEquals(source.enabledSourceIds, staged.enabledSourceIds)
        assertEquals(source.customBlocklists, staged.customBlocklists)
        assertEquals(source.manualRules, staged.manualRules)
        assertNull(staged.protectionLevel)
        assertEquals(two, port.currentLibrary())
        assertEquals(0, port.applyCount)

        assertEquals(FilterLibraryEditRejection.COMMIT_FAILED, editRejection(owner.commitLibraryEdit()))
        assertEquals(two, port.currentLibrary())
        assertEquals(staged, owner.editSession.value.additions.single())
        editCompleted(owner.commitLibraryEdit())
        assertEquals(staged, port.currentLibrary().profile(id))
        assertEquals(source, port.currentLibrary().profile(source.id))
        assertEquals(two.activeProfileId, port.currentLibrary().activeProfileId)
        assertEquals(two.resolverTarget, port.currentLibrary().resolverTarget)
        assertEquals(FeatureLimits.FREE, port.lastAdmission!!.limits)
        owner.close()
    }

    @Test
    fun `template selection cancel or missing source cannot add a saved placeholder`() = runTest {
        val two = seeded.withProfiles(seeded.profiles.take(2))
        val (owner, port) = owner(two)
        owner.beginLibraryEditing()
        assertEquals(FilterLibraryEditRejection.UNKNOWN_FILTER, editRejection(owner.stageLibraryCreate(null, "missing")))
        val id = (owner.stageLibraryCreate(null, "filter-essential") as FilterLibraryEditOutcome.Created).filterId
        owner.cancelLibraryEditing()
        assertFalse(owner.editSession.value.hasChanges)
        assertNull(port.currentLibrary().profile(id))
        assertEquals(two, port.currentLibrary())
        assertEquals(0, port.applyCount)
        owner.close()
    }

    @Test
    fun `library confirm rejects a staged set that does not match the rendered ids`() = runTest {
        val (owner, port) = owner(seeded)
        owner.beginLibraryEditing()
        owner.toggleStagedDeletion("filter-comprehensive")
        assertEquals(
            FilterLibraryEditRejection.STALE_STAGING,
            editRejection(owner.commitLibraryEdit(expectedIds = setOf("filter-essential"))),
        )
        assertEquals(0, port.applyCount)
        editCompleted(owner.commitLibraryEdit(expectedIds = setOf("filter-comprehensive")))
        assertEquals(1, port.applyCount)
        owner.close()
    }

    @Test
    fun `library confirm with no staged changes is refused without a write`() = runTest {
        val (owner, port) = owner(seeded)
        owner.beginLibraryEditing()
        assertEquals(
            FilterLibraryEditRejection.NO_CHANGES,
            editRejection(owner.commitLibraryEdit()),
        )
        assertEquals(0, port.applyCount)
        owner.close()
    }

    @Test
    fun `library add then toggle off removes the staged addition from the batch`() = runTest {
        val two = seeded.withProfiles(seeded.profiles.take(2))
        val (owner, port) = owner(two)
        owner.beginLibraryEditing()
        val id = (owner.stageLibraryCreate("Extra one", null) as FilterLibraryEditOutcome.Created).filterId
        assertTrue(owner.editSession.value.hasChanges)
        editCompleted(owner.toggleStagedDeletion(id))
        assertFalse(owner.editSession.value.hasChanges)
        assertEquals(FilterLibraryEditRejection.NO_CHANGES, editRejection(owner.commitLibraryEdit()))
        assertEquals(0, port.applyCount)
        owner.close()
    }

    @Test
    fun `durable edit validates and publishes with one captured tier witness`() = runTest {
        val admitted = FeatureLimitAdmission(FeatureLimits.PLUS)
        val port = RecordingPort(seeded, false, false, false)
        val owner = FilterLibraryOwner(
            port, this, StandardTestDispatcher(testScheduler),
            limits = { FeatureLimits.FREE },
            captureAdmission = { admitted },
        )
        assertTrue(owner.createFilter("Fourth", null))
        assertEquals(4, port.library.profiles.size)
        assertSame(admitted, port.lastAdmission)
        owner.close()
    }

    @Test
    fun `unavailable tier refuses a library mutation and retry recaptures admission`() = runTest {
        var admitted: FeatureLimitAdmission? = null
        val port = RecordingPort(seeded, false, false, false)
        val owner = FilterLibraryOwner(
            port, this, StandardTestDispatcher(testScheduler),
            captureAdmission = { admitted },
        )
        assertFalse(owner.renameFilter(seeded.activeProfileId, "Renamed"))
        assertEquals(0, port.applyCount)
        admitted = FeatureLimitAdmission(FeatureLimits.FREE)
        assertTrue(owner.retryLast())
        assertSame(admitted, port.lastAdmission)
        owner.close()
    }

    @Test fun `import close during preparation revokes the final publication and cannot retry detached`() = runTest {
        val baseline = seeded.withProfiles(seeded.profiles.take(2))
        val (owner, port) = owner(baseline)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var current = true
        try {
            port.beforeReviewedApply = { entered.complete(Unit); release.await() }
            val applying = async { owner.commitImported(
                ShareableFilterConfiguration(blockedDomains = setOf("reviewed.example")), null,
                expectedLibrary = baseline, isCurrent = { current },
            ) }
            entered.await(); current = false; release.complete(Unit)
            assertTrue(applying.await() is FilterLibraryMutationResult.Superseded)
            assertEquals(baseline, port.library); assertEquals(0, port.applyCount)
            assertFalse(owner.retryLast())
        } finally { release.complete(Unit); owner.close() }
    }

    @Test fun `changed import review basis refuses before writing a replacement`() = runTest {
        val (owner, port) = owner(seeded)
        try {
            val reviewed = port.library
            port.library = reviewed.withProfile(checkNotNull(reviewed.profile("filter-essential"))
                .withManualRules(ManualDomainRules(blockedDomains = setOf("new-local.example"))))
            val result = owner.commitImported(
                ShareableFilterConfiguration(blockedDomains = setOf("imported.example")), "filter-essential",
                expectedLibrary = reviewed, isCurrent = { true },
            )
            assertTrue(result is FilterLibraryMutationResult.Superseded)
            assertEquals(setOf("new-local.example"), port.library.profile("filter-essential")!!.manualRules.blockedDomains)
            assertEquals(0, port.applyCount)
        } finally { owner.close() }
    }

    @Test
    fun `a confirmed import adds a new filter and replaces an existing one`() = runTest {
        val two = seeded.withProfiles(seeded.profiles.take(2))
        val (owner, port) = owner(two)
        val added = owner.commitImported(
            ShareableFilterConfiguration(
                enabledBlocklistIDs = setOf("stevenblack-unified"),
                blockedDomains = setOf("ads.example"),
                allowedDomains = setOf("good.example"),
            ),
            targetFilterId = null,
        )
        advanceUntilIdle()
        assertTrue(added is FilterLibraryMutationResult.Applied)
        val created = port.currentLibrary().profiles.last()
        assertEquals(3, port.currentLibrary().profiles.size)
        assertEquals(setOf("stevenblack-unified"), created.enabledSourceIds)
        assertEquals(setOf("ads.example"), created.manualRules.blockedDomains)
        assertEquals(setOf("good.example"), created.manualRules.allowedDomains)

        // Replace rewrites the target content while preserving its identity.
        val before = port.currentLibrary().profile("filter-essential")!!
        val replaced = owner.commitImported(
            ShareableFilterConfiguration(
                enabledBlocklistIDs = setOf("blocklistproject-basic"),
                blockedDomains = setOf("tracker.example"),
                allowedDomains = emptySet(),
            ),
            targetFilterId = "filter-essential",
        )
        advanceUntilIdle()
        assertTrue(replaced is FilterLibraryMutationResult.Applied)
        val target = port.currentLibrary().profile("filter-essential")!!
        assertEquals(before.name, target.name)
        assertEquals(before.protectionLevel, target.protectionLevel)
        assertEquals(setOf("blocklistproject-basic"), target.enabledSourceIds)
        assertEquals(setOf("tracker.example"), target.manualRules.blockedDomains)
        owner.close()
    }

    @Test
    fun `a legacy v1 import replacement preserves the recipient allowed exceptions`() = runTest {
        val two = seeded.withProfiles(seeded.profiles.take(2))
        val (owner, port) = owner(two)
        owner.beginEdit("filter-essential")
        owner.addDomainToDraft("filter-essential", "keep.example", ManualRuleTarget.ALLOWED)
        owner.commitDraft("filter-essential")
        advanceUntilIdle()
        val legacy = ShareableFilterConfiguration.legacy(
            enabledBlocklistIDs = setOf("blocklistproject-basic"),
            blockedDomains = setOf("new.example"),
        )
        assertTrue(
            owner.commitImported(legacy, targetFilterId = "filter-essential")
                is FilterLibraryMutationResult.Applied,
        )
        advanceUntilIdle()
        val target = port.currentLibrary().profile("filter-essential")!!
        assertEquals(setOf("keep.example"), target.manualRules.allowedDomains)
        assertEquals(setOf("new.example"), target.manualRules.blockedDomains)
        owner.close()
    }

    @Test
    fun `an import at the filter cap or on a frozen target is refused without a write`() = runTest {
        val (owner, port) = owner(seeded)
        val refused = owner.commitImported(
            ShareableFilterConfiguration(enabledBlocklistIDs = setOf("blocklistproject-basic")),
            targetFilterId = null,
        )
        advanceUntilIdle()
        assertTrue(refused is FilterLibraryMutationResult.Rejected)
        assertEquals(0, port.applyCount)
        owner.close()
    }

    @Test
    fun `replaceAll commits a wholesale library and drops drafts for filters it removes`() = runTest {
        val (owner, port) = owner(seeded)
        owner.beginEdit("filter-balanced")
        owner.addDomainToDraft("filter-balanced", "manual.example", ManualRuleTarget.BLOCKED)
        advanceUntilIdle()

        // Wholesale replacement keeps only filter-essential, so filter-balanced's draft is orphaned.
        val desired = FilterLibraryDraft(
            profiles = listOf(seeded.profile("filter-essential")!!),
            activeProfileId = "filter-essential",
            resolverPlan = seeded.resolverPlan,
        )
        assertTrue(owner.replaceAll(desired))
        advanceUntilIdle()

        assertEquals(desired, port.currentLibrary())
        assertNull(owner.draftFor("filter-balanced"))
        owner.close()
    }

    @Test
    fun `create from a saved template holds complete content outside the durable library`() = runTest {
        val original = seeded.profiles.first().withCustomBlocklists(listOf(
            CustomBlocklistSource("custom-source", "Private list", "https://example.com/list.txt"),
        )).withEnabledSourceIds(setOf("custom-source")).withManualRules(ManualDomainRules(
            blockedDomains = setOf("blocked.example"), allowedDomains = setOf("allowed.example"),
        ))
        val initial = seeded.withActiveProfile(original.id).withProfiles(listOf(original))
        val (owner, port) = owner(initial)
        try {
            owner.beginLibraryEditing()
            val created = owner.beginCreatingFilter(null, original.id) as FilterLibraryEditOutcome.Created
            val pending = checkNotNull(owner.newFilter.value)
            assertEquals(created.filterId, pending.id)
            assertTrue(pending.id != original.id)
            assertEquals("Untitled 1", pending.name)
            assertEquals(original.enabledSourceIds, pending.enabledSourceIds)
            assertEquals(original.customBlocklists, pending.customBlocklists)
            assertEquals(original.manualRules, pending.manualRules)
            assertTrue(owner.hasUnsavedDraft(pending.id))
            assertNull(port.library.profile(pending.id))
            assertEquals(initial.activeProfileId, port.library.activeProfileId)
            assertEquals(0, port.applyCount)
            // Exiting the chooser has no batch to write and leaves the editor's draft intact.
            editCompleted(owner.cancelLibraryEditing())
            assertSame(pending, owner.newFilter.value)
            assertTrue(owner.beginEdit(pending.id))
            assertFalse(owner.commitDraft(pending.id))
            assertEquals(0, port.applyCount)
            assertTrue(owner.renameFilter(pending.id, "My copy", "👩🏽‍💻"))
            owner.addDomainToDraft(pending.id, "extra.example", ManualRuleTarget.BLOCKED)
            assertTrue(owner.undoDomainInDraft(pending.id, "extra.example"))
            val review = checkNotNull(owner.captureReview(pending.id))
            assertTrue(owner.commitDraft(pending.id, review))
            assertEquals(1, port.applyCount)
            val saved = checkNotNull(port.library.profile(pending.id))
            assertEquals("My copy", saved.name)
            assertEquals("👩🏽‍💻", saved.emoji)
            assertEquals(original.customBlocklists, saved.customBlocklists)
            assertEquals(original.manualRules, saved.manualRules)
            assertEquals(initial.activeProfileId, port.library.activeProfileId)
            assertNull(owner.newFilter.value)
            assertNull(owner.draftFor(pending.id))
        } finally { owner.close() }
    }

    @Test
    fun `empty first save is meaningful and a failed save keeps the same owned draft for retry`() = runTest {
        val initial = seeded.withActiveProfile(seeded.profiles.first().id).withProfiles(seeded.profiles.take(1))
        val (owner, port) = owner(initial, failNext = true)
        try {
            owner.beginLibraryEditing()
            val id = (owner.beginCreatingFilter(null, null) as FilterLibraryEditOutcome.Created).filterId
            val identity = checkNotNull(owner.newFilter.value)
            assertTrue(identity.enabledSourceIds.isEmpty())
            assertNull(owner.reviewRejectionFor(id))
            assertFalse(owner.discardUnchangedDraft(id))
            val review = checkNotNull(owner.captureReview(id))
            assertFalse(owner.commitDraft(id, review))
            assertEquals(initial, port.library)
            assertSame(identity, owner.newFilter.value)
            assertSame(identity, owner.draftFor(id))
            val failure = owner.state.value as FilterLibraryOwner.State.Failed
            assertTrue(owner.retryReviewed(failure))
            assertEquals(2, port.applyCount)
            assertEquals(identity, port.library.profile(id))
            assertNull(owner.newFilter.value)
        } finally { owner.close() }
    }

    @Test
    fun `identity rules basis cancellation and replacement cannot revive a creation review`() = runTest {
        for (change in listOf("identity", "rules", "basis", "cancel", "replace", "close")) {
            val initial = seeded.withActiveProfile(seeded.profiles.first().id).withProfiles(seeded.profiles.take(1))
            val (owner, port) = owner(initial)
            try {
                owner.beginLibraryEditing()
                val id = (owner.beginCreatingFilter(null, null) as FilterLibraryEditOutcome.Created).filterId
                val review = checkNotNull(owner.captureReview(id))
                when (change) {
                    "identity" -> assertTrue(owner.renameFilter(id, "Different", "🪴"))
                    "rules" -> owner.addDomainToDraft(id, "different.example", ManualRuleTarget.BLOCKED)
                    "basis" -> port.library = initial.withProfiles(initial.profiles + seeded.profiles[1])
                    "cancel" -> owner.discardDraft(id)
                    "replace" -> owner.beginCreatingFilter(null, null)
                    "close" -> owner.close()
                }
                assertFalse("$change must retire the first-save witness", owner.commitDraft(id, review))
                assertEquals(0, port.applyCount)
                assertNull(port.library.profile(id))
                if (change == "cancel" || change == "close") assertNull(owner.newFilter.value)
            } finally { owner.close() }
        }
    }

    @Test
    fun `first save rechecks live free cap and duplicate identity without trimming the draft`() = runTest {
        var limits = FeatureLimits.PLUS
        val (owner, port) = owner(seeded, limits = { limits })
        try {
            owner.beginLibraryEditing()
            val id = (owner.beginCreatingFilter(null, null) as FilterLibraryEditOutcome.Created).filterId
            val review = checkNotNull(owner.captureReview(id))
            limits = FeatureLimits.FREE
            assertFalse(owner.commitDraft(id, review))
            assertEquals(0, port.applyCount)
            assertNotNull(owner.newFilter.value)
            assertEquals(com.lavasec.app.foundation.filter.FilterDraftReviewRejection.FILTER_LIMIT_REACHED,
                owner.reviewRejectionFor(id))
            limits = FeatureLimits.PLUS
            assertFalse(owner.renameFilter(id, seeded.profiles.first().name, "🌿"))
            assertFalse(owner.renameFilter(id, "Valid", "letters"))
            assertTrue(owner.commitDraft(id, checkNotNull(owner.captureReview(id))))
            assertEquals(1, port.applyCount)
        } finally { owner.close() }
    }

    @Test
    fun `cancelling a new filter preserves existing per-filter rule drafts`() = runTest {
        val (owner, port) = owner(seeded, limits = { FeatureLimits.PLUS })
        try {
            val savedId = seeded.activeProfileId
            owner.beginEdit(savedId)
            owner.addDomainToDraft(savedId, "retained.example", ManualRuleTarget.BLOCKED)
            owner.beginLibraryEditing()
            val id = (owner.beginCreatingFilter(null, savedId) as FilterLibraryEditOutcome.Created).filterId
            owner.discardDraft(id)
            assertNull(owner.newFilter.value)
            assertNull(owner.draftFor(id))
            assertEquals(setOf("retained.example"), owner.draftFor(savedId)!!.manualRules.blockedDomains)
            assertEquals(0, port.applyCount)
        } finally { owner.close() }
    }

    @Test
    fun `localized creation uses the frozen numbered-from-one sequence and the lowest free slot`() = runTest {
        val initial = FilterLibraryDraft(listOf(
            com.lavasec.app.foundation.filter.FilterProfileDraft("one", "無題 1", null, emptySet()),
            com.lavasec.app.foundation.filter.FilterProfileDraft("three", "無題 3", null, emptySet()),
        ), "one", seeded.resolverPlan)
        val (owner, port) = owner(initial, creationName = { "無題" })
        try {
            owner.beginLibraryEditing()
            owner.beginCreatingFilter(null, null)
            assertEquals("無題 2", owner.newFilter.value!!.name)
            assertEquals(0, port.applyCount)
        } finally { owner.close() }
    }

    @Test
    fun `QA overlay installs replaces and clears independently of saved manual rules and DNS`() = runTest {
        val (owner, port) = owner(seeded)
        try {
            val hosted = seeded.withQaProbeSet(com.lavasec.app.foundation.filter.DeviceQaProbeSet.HOSTED)
            assertTrue(owner.replaceReviewedForQa(hosted, seeded) { true })
            assertEquals(seeded.profiles, port.library.profiles)
            assertEquals(seeded.resolverTarget, port.library.resolverTarget)
            val custom = hosted.withQaProbeSet(com.lavasec.app.foundation.filter.DeviceQaProbeSet.fromSuffix("lan.example"))
            assertFalse(owner.replaceReviewedForQa(custom.withResolverPlan(com.lavasec.app.foundation.resolver.MilestoneResolverPlan.DEVICE_DNS_ONLY), hosted) { true })
            assertTrue(owner.replaceReviewedForQa(custom, hosted) { true })
            owner.beginEdit(custom.activeProfileId)
            assertFalse(owner.replaceReviewedForQa(seeded, custom) { true })
            assertEquals(custom, port.library)
            assertTrue(owner.hasOpenDrafts())
            owner.discardDraft(custom.activeProfileId)
            assertTrue(owner.replaceReviewedForQa(seeded, custom) { true })
            assertEquals(seeded, port.library)
            assertEquals(3, port.applyCount)
            assertFalse(owner.retryLast())
        } finally { owner.close() }
    }

    @Test
    fun `QA refuses rule creation and library editors without discarding them or reaching publication`() = runTest {
        for (editor in listOf("rules", "creation", "library")) {
            val initial = seeded.withActiveProfile(seeded.profiles.first().id).withProfiles(seeded.profiles.take(1))
            val (owner, port) = owner(initial)
            try {
                when (editor) {
                    "rules" -> owner.beginEdit(initial.activeProfileId)
                    "library" -> owner.beginLibraryEditing()
                    "creation" -> {
                        owner.beginLibraryEditing()
                        owner.beginCreatingFilter(null, null)
                        owner.cancelLibraryEditing()
                    }
                }
                assertTrue(owner.hasOpenDrafts())
                val desired = initial.withProfile(initial.profiles.first().withManualRules(
                    ManualDomainRules(blockedDomains = setOf("qa.example"))))
                assertFalse(owner.replaceReviewedForQa(desired, initial) { true })
                assertTrue(owner.hasOpenDrafts())
                assertEquals(0, port.applyCount)
                assertEquals(initial, port.library)
            } finally { owner.close() }
        }
    }

    @Test
    fun `QA carries exact basis and visit admission without installing a retryable operation`() = runTest {
        val (owner, port) = owner(seeded)
        try {
            val active = seeded.profile(seeded.activeProfileId)!!
            val desired = seeded.withProfile(active.withManualRules(ManualDomainRules(blockedDomains = setOf("qa.example"))))
            assertFalse(owner.replaceReviewedForQa(desired, seeded) { false })
            assertEquals(0, port.applyCount)
            assertTrue(owner.replaceReviewedForQa(desired, seeded) { true })
            assertEquals(desired, port.library)
            assertEquals(1, port.applyCount)
            assertFalse(owner.retryLast())
            // Exact expected library refuses a second write from the stale QA preparation.
            assertFalse(owner.replaceReviewedForQa(seeded, seeded) { true })
            assertEquals(1, port.applyCount)
        } finally { owner.close() }
    }

    @Test
    fun `QA cancellation alone or visit retirement during non-cancellable preparation cannot write`() = runTest {
        for (retireVisit in listOf(false, true)) {
            val (owner, port) = owner(seeded)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var current = true
            port.beforeReviewedApply = {
                started.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
            }
            try {
                val active = seeded.profile(seeded.activeProfileId)!!
                val desired = seeded.withProfile(active.withManualRules(ManualDomainRules(blockedDomains = setOf("qa.example"))))
                val caller = async { owner.replaceReviewedForQa(desired, seeded) { current } }
                started.await()
                current = !retireVisit
                caller.cancel()
                release.complete(Unit)
                caller.join()
                assertEquals(seeded, port.library)
                assertEquals(0, port.applyCount)
                assertFalse(owner.retryLast())
            } finally { release.complete(Unit); owner.close() }
        }
    }

    @Test
    fun `QA respects the live active-filter rule limits and cannot rewrite a different profile or DNS`() = runTest {
        val (owner, port) = owner(seeded)
        try {
            val active = seeded.profile(seeded.activeProfileId)!!
            val excessive = seeded.withProfile(active.withManualRules(ManualDomainRules(
                blockedDomains = (0 until 26).mapTo(linkedSetOf()) { "qa$it.example" })))
            assertFalse(owner.replaceReviewedForQa(excessive, seeded) { true })
            assertFalse(owner.replaceReviewedForQa(seeded.withActiveProfile(seeded.profiles.first().id), seeded) { true })
            assertEquals(0, port.applyCount)
        } finally { owner.close() }
    }

    @Test fun `shared forms cannot stage or create against a retired library session`() = runTest {
        val (owner,port)=owner(seeded,limits={FeatureLimits.PLUS})
        try {
            owner.beginLibraryEditing();val captured=owner.editSession.value
            owner.cancelLibraryEditing();owner.beginLibraryEditing()
            assertEquals(FilterLibraryEditRejection.STALE_STAGING,editRejection(owner.stageLibraryRename("filter-essential","New name","🌿",captured)))
            assertEquals(FilterLibraryEditRejection.STALE_STAGING,editRejection(owner.beginCreatingFilter(null,null,captured)))
            assertEquals(0,port.applyCount);assertTrue(owner.drafts.value.isEmpty());assertNull(owner.newFilter.value)
        } finally { owner.close() }
    }

    @Test fun `a whole-library review cannot confirm equal deletion IDs from a replaced session`() = runTest {
        val (owner,port)=owner(seeded)
        try {
            owner.beginLibraryEditing();owner.toggleStagedDeletion("filter-comprehensive")
            val reviewed=owner.editSession.value
            owner.cancelLibraryEditing();owner.beginLibraryEditing();owner.toggleStagedDeletion("filter-comprehensive")
            val successor=owner.editSession.value
            assertEquals(reviewed.stagedDeletions,successor.stagedDeletions)
            assertEquals(FilterLibraryEditRejection.STALE_STAGING,editRejection(owner.commitLibraryEdit(reviewed.stagedDeletions,reviewed)))
            assertSame(successor,owner.editSession.value);assertEquals(0,port.applyCount);assertEquals(seeded,port.library)
        } finally { owner.close() }
    }

    @Test fun `a queued whole-library review rechecks its session after another mutation finishes`() = runTest {
        val (owner,port)=owner(seeded)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            val id=seeded.activeProfileId
            owner.beginEdit(id);owner.addDomainToDraft(id,"first.example",ManualRuleTarget.BLOCKED)
            val draftReview=checkNotNull(owner.captureReview(id))
            port.beforeReviewedApply={entered.complete(Unit);release.await()}
            val occupying=async {owner.commitDraft(id,draftReview)};entered.await()
            owner.beginLibraryEditing();owner.toggleStagedDeletion("filter-comprehensive")
            val reviewed=owner.editSession.value
            val queued=async {owner.commitLibraryEdit(reviewed.stagedDeletions,reviewed)}
            advanceUntilIdle()
            owner.cancelLibraryEditing();owner.beginLibraryEditing();owner.toggleStagedDeletion("filter-comprehensive")
            val successor=owner.editSession.value
            release.complete(Unit);assertTrue(occupying.await())
            assertTrue(queued.await() is FilterLibraryEditOutcome.Rejected)
            assertSame(successor,owner.editSession.value);assertEquals(1,port.applyCount)
            assertNotNull(port.library.profile("filter-comprehensive"))
        } finally {release.complete(Unit);owner.close()}
    }

    @Test fun `a closed identity form cannot rename or discard the replacement per-filter draft`() = runTest {
        val (owner,port)=owner(seeded)
        try {
            val id=seeded.activeProfileId
            owner.beginEdit(id);val captured=checkNotNull(owner.draftFor(id))
            owner.addDomainToDraft(id,"newer.example",ManualRuleTarget.BLOCKED)
            val replacement=checkNotNull(owner.draftFor(id))
            assertFalse(owner.renameFilter(id,"Late rename","🌿",captured,null))
            assertFalse(owner.discardDraft(id,captured,null))
            assertSame(replacement,owner.draftFor(id));assertEquals(0,port.applyCount)
            assertTrue(owner.discardDraft(id,replacement,null));assertNull(owner.draftFor(id))
        } finally { owner.close() }
    }

    @Test fun `saved draft identity can rename while an unrelated captured creation remains current`() = runTest {
        val (owner, port) = owner(seeded, limits = { FeatureLimits.PLUS })
        try {
            val id = seeded.activeProfileId
            assertTrue(owner.beginEdit(id))
            val draft = checkNotNull(owner.draftFor(id))
            owner.beginLibraryEditing()
            val created = owner.beginCreatingFilter(null, null) as FilterLibraryEditOutcome.Created
            val creation = checkNotNull(owner.newFilter.value)
            val creationDraft = checkNotNull(owner.draftFor(created.filterId))

            assertTrue(owner.renameFilter(id, "Saved identity", "🌿", draft, creation))
            assertEquals("Saved identity", port.library.profile(id)?.name)
            assertEquals("🌿", port.library.profile(id)?.emoji)
            assertSame(draft, owner.draftFor(id))
            assertSame(creation, owner.newFilter.value)
            assertSame(creationDraft, owner.draftFor(created.filterId))
            assertNull("An unrelated creation remains unsaved", port.library.profile(created.filterId))
            assertEquals(1, port.applyCount)
        } finally { owner.close() }
    }

    @Test fun `saved identity form refuses a changed unrelated creation witness before publication`() = runTest {
        val (owner, port) = owner(seeded, limits = { FeatureLimits.PLUS })
        try {
            val id = seeded.activeProfileId
            assertTrue(owner.beginEdit(id))
            val draft = checkNotNull(owner.draftFor(id))
            owner.beginLibraryEditing()
            val created = owner.beginCreatingFilter(null, null) as FilterLibraryEditOutcome.Created
            val capturedCreation = checkNotNull(owner.newFilter.value)
            assertTrue(owner.renameFilter(created.filterId, "Newer creation", "🌱"))
            val successor = checkNotNull(owner.newFilter.value)

            assertFalse(owner.renameFilter(id, "Late saved identity", "🌿", draft, capturedCreation))
            assertEquals(seeded, port.library)
            assertSame(draft, owner.draftFor(id))
            assertSame(successor, owner.newFilter.value)
            assertEquals(0, port.applyCount)
        } finally { owner.close() }
    }

    @Test fun `creation teardown requires both the captured rule draft and identity`() = runTest {
        val (owner,port)=owner(seeded,limits={FeatureLimits.PLUS})
        try {
            owner.beginLibraryEditing()
            val id=(owner.beginCreatingFilter(null,null) as FilterLibraryEditOutcome.Created).filterId
            val draft=checkNotNull(owner.draftFor(id));val identity=checkNotNull(owner.newFilter.value)
            assertTrue(owner.renameFilter(id,"New identity","🌿",draft,identity))
            val successor=checkNotNull(owner.newFilter.value)
            assertFalse(owner.discardDraft(id,draft,identity));assertSame(successor,owner.newFilter.value)
            assertFalse(owner.renameFilter(id,"Late rename","🌱",draft,identity))
            assertEquals(0,port.applyCount)
            assertTrue(owner.discardDraft(id,draft,successor));assertNull(owner.newFilter.value)
        } finally { owner.close() }
    }

    @Test fun `queued foreground creation and staged rename reject a retired host inside admission`() = runTest {
        for (action in listOf("create", "stageRename")) {
            val (owner, port) = owner(seeded, limits = { FeatureLimits.PLUS })
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var current = true
            try {
                editCompleted(owner.beginLibraryEditing())
                val session = owner.editSession.value
                port.beforeReviewedApply = { entered.complete(Unit); release.await() }
                // A real reviewed rename holds the same owner mutex while preserving the
                // library value, so only host retirement can refuse the queued operation.
                val occupying = async { owner.renameFilter("filter-balanced", seeded.profile("filter-balanced")!!.name, isCurrent = { true }) }
                entered.await()
                val queued = async {
                    if (action == "create") owner.beginCreatingFilter(null, null, session, isCurrent = { current })
                    else owner.stageLibraryRename("filter-balanced", "Late staged name", "🌿", session, isCurrent = { current })
                }
                advanceUntilIdle(); assertFalse(queued.isCompleted)
                current = false; release.complete(Unit)
                assertTrue(occupying.await())
                assertEquals(FilterLibraryEditRejection.STALE_STAGING, editRejection(queued.await()))
                assertSame(session, owner.editSession.value)
                assertTrue(owner.drafts.value.isEmpty()); assertNull(owner.newFilter.value)
                assertEquals(seeded, port.library); assertEquals(1, port.applyCount)
            } finally { release.complete(Unit); owner.close() }
        }
    }

    @Test fun `queued foreground identity rename preserves the creation when its host retires`() = runTest {
        val (owner, port) = owner(seeded, limits = { FeatureLimits.PLUS })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var current = true
        try {
            editCompleted(owner.beginLibraryEditing())
            val id = (owner.beginCreatingFilter(null, null) as FilterLibraryEditOutcome.Created).filterId
            val creation = checkNotNull(owner.newFilter.value); val draft = checkNotNull(owner.draftFor(id))
            port.beforeReviewedApply = { entered.complete(Unit); release.await() }
            val occupying = async { owner.renameFilter("filter-balanced", seeded.profile("filter-balanced")!!.name, isCurrent = { true }) }
            entered.await()
            val queued = async { owner.renameFilter(id, "Late identity", "🪴", draft, creation, isCurrent = { current }) }
            advanceUntilIdle(); assertFalse(queued.isCompleted)
            current = false; release.complete(Unit)
            assertTrue(occupying.await()); assertFalse(queued.await())
            assertSame(creation, owner.newFilter.value); assertSame(draft, owner.draftFor(id))
            assertEquals(seeded, port.library); assertEquals(1, port.applyCount)
        } finally { release.complete(Unit); owner.close() }
    }

    @Test fun `queued foreground library commit cannot publish after its host retires`() = runTest {
        val (owner, port) = owner(seeded)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var current = true
        try {
            editCompleted(owner.beginLibraryEditing()); editCompleted(owner.toggleStagedDeletion("filter-comprehensive"))
            val session = owner.editSession.value
            port.beforeReviewedApply = { entered.complete(Unit); release.await() }
            val occupying = async { owner.renameFilter("filter-balanced", seeded.profile("filter-balanced")!!.name, isCurrent = { true }) }
            entered.await()
            val queued = async { owner.commitLibraryEdit(session.stagedDeletions, session, isCurrent = { current }) }
            advanceUntilIdle(); assertFalse(queued.isCompleted)
            current = false; release.complete(Unit)
            assertTrue(occupying.await())
            assertEquals(FilterLibraryEditRejection.COMMIT_FAILED, editRejection(queued.await()))
            assertSame(session, owner.editSession.value)
            assertEquals(seeded, port.library); assertEquals(1, port.applyCount)
        } finally { release.complete(Unit); owner.close() }
    }

    @Test fun `foreground rename and deletion carry host retirement through the real final reviewed write guard`() = runTest {
        for (action in listOf("rename", "delete")) {
            val (owner, port) = owner(seeded)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var current = true
            try {
                if (action == "delete") { editCompleted(owner.beginLibraryEditing()); editCompleted(owner.toggleStagedDeletion("filter-comprehensive")) }
                val session = owner.editSession.value
                port.beforeReviewedApply = { entered.complete(Unit); release.await() }
                val submitting = async {
                    if (action == "rename") owner.renameFilter("filter-balanced", "Host owned name", isCurrent = { current })
                    else owner.commitLibraryEdit(session.stagedDeletions, session, isCurrent = { current }) == FilterLibraryEditOutcome.Completed
                }
                entered.await(); current = false; release.complete(Unit)
                assertFalse(submitting.await())
                assertEquals(1, port.reviewedWhileCurrentCount)
                assertEquals(0, port.applyCount); assertEquals(seeded, port.library)
                assertSame(session, owner.editSession.value)
                current = true; port.beforeReviewedApply = null
                assertFalse("Retry cannot strip the retired foreground host witness", owner.retryLast())
                assertEquals(seeded, port.library); assertEquals(0, port.applyCount)
                // A distinct admitted visit can still make forward progress.
                if (action == "rename") assertTrue(owner.renameFilter("filter-balanced", "Successor name", isCurrent = { current }))
                else editCompleted(owner.commitLibraryEdit(session.stagedDeletions, session, isCurrent = { current }))
                assertEquals(1, port.applyCount)
            } finally { release.complete(Unit); owner.close() }
        }
    }

    @Test fun `foreground caller cancellation fences noncancellable final preparation without enabling retry`() = runTest {
        val (owner, port) = owner(seeded)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        port.beforeReviewedApply = {
            entered.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
        }
        try {
            val caller = async { owner.renameFilter("filter-balanced", "Cancelled name", isCurrent = { true }) }
            entered.await(); caller.cancel(); caller.join()
            release.complete(Unit); advanceUntilIdle()
            assertEquals(1, port.reviewedWhileCurrentCount)
            assertEquals(seeded, port.library); assertEquals(0, port.applyCount)
            assertFalse(owner.retryLast())
        } finally { release.complete(Unit); owner.close() }
    }

    @Test fun `foreground commit cannot delete through a replaced session during suspended preparation`() = runTest {
        val (owner, port) = owner(seeded)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            editCompleted(owner.beginLibraryEditing()); editCompleted(owner.toggleStagedDeletion("filter-comprehensive"))
            val captured = owner.editSession.value
            port.beforeReviewedApply = { entered.complete(Unit); release.await() }
            val submitting = async { owner.commitLibraryEdit(captured.stagedDeletions, captured, isCurrent = { true }) }
            entered.await()
            owner.cancelLibraryEditing(); editCompleted(owner.beginLibraryEditing())
            editCompleted(owner.toggleStagedDeletion("filter-comprehensive"))
            val successor = owner.editSession.value
            release.complete(Unit)
            assertEquals(FilterLibraryEditRejection.COMMIT_FAILED, editRejection(submitting.await()))
            assertSame(successor, owner.editSession.value)
            assertEquals(seeded, port.library); assertEquals(0, port.applyCount)
            assertFalse(owner.retryLast())
        } finally { release.complete(Unit); owner.close() }
    }

    @Test fun `foreground durable calls fail closed when the port lacks guarded reviewed publication`() = runTest {
        for (action in listOf("rename", "delete")) for (hasBasis in listOf(false, true)) {
            var writes = 0
            val port = object : FilterLibraryPort {
                override fun currentLibrary() = seeded
                override fun reviewBasis() = if (hasBasis) FilterLibraryReviewBasis(seeded, 7L) else null
                override suspend fun apply(desired: FilterLibraryDraft, admission: FeatureLimitAdmission): FilterLibraryMutationResult {
                    writes++; return FilterLibraryMutationResult.Applied(8L)
                }
                override suspend fun restoreDefaults(admission: FeatureLimitAdmission) = FilterLibraryMutationResult.Superseded
            }
            val owner = FilterLibraryOwner(port, this, StandardTestDispatcher(testScheduler))
            try {
                if (action == "rename") assertFalse(owner.renameFilter("filter-balanced", "Unsupported port name", isCurrent = { true }))
                else {
                    editCompleted(owner.beginLibraryEditing()); editCompleted(owner.toggleStagedDeletion("filter-comprehensive"))
                    assertEquals(FilterLibraryEditRejection.COMMIT_FAILED, editRejection(owner.commitLibraryEdit(isCurrent = { true })))
                }
                assertEquals(0, writes); assertEquals(seeded, port.currentLibrary()); assertFalse(owner.retryLast())
            } finally { owner.close() }
        }
    }

    @Test fun `foreground creation rechecks its host immediately before publishing the new draft`() = runTest {
        var current = true
        val (owner, port) = owner(seeded, limits = { FeatureLimits.PLUS }, creationName = {
            // Naming runs after initial admission, before any draft or lease publication.
            current = false; "Untitled"
        })
        try {
            editCompleted(owner.beginLibraryEditing())
            val session = owner.editSession.value
            assertEquals(FilterLibraryEditRejection.STALE_STAGING,
                editRejection(owner.beginCreatingFilter(null, null, session, isCurrent = { current })))
            assertSame(session, owner.editSession.value)
            assertTrue(owner.drafts.value.isEmpty()); assertNull(owner.newFilter.value)
            assertEquals(seeded, port.library); assertEquals(0, port.applyCount)
        } finally { owner.close() }
    }

    @Test fun `foreground rename does not acknowledge success to a host retired after the accepted durable write`() = runTest {
        val (owner, port) = owner(seeded)
        var current = true
        port.afterReviewedApply = { current = false }
        try {
            assertFalse(owner.renameFilter("filter-balanced", "Committed before retirement", isCurrent = { current }))
            assertEquals("Committed before retirement", port.library.profile("filter-balanced")!!.name)
            assertEquals(1, port.applyCount)
            assertFalse(owner.retryLast())
        } finally { owner.close() }
    }

    @Test fun `durably applied library batch retires its session without acknowledging a retired host`() = runTest {
        val (owner, port) = owner(seeded)
        var current = true
        try {
            editCompleted(owner.beginLibraryEditing())
            editCompleted(owner.toggleStagedDeletion("filter-comprehensive"))
            val captured = owner.editSession.value
            port.afterReviewedApply = { current = false }
            val outcome = owner.commitLibraryEdit(captured.stagedDeletions, captured, isCurrent = { current })
            assertEquals(FilterLibraryEditRejection.COMMIT_FAILED, editRejection(outcome))
            assertNull(port.library.profile("filter-comprehensive"))
            assertEquals(1, port.applyCount)
            assertTrue(owner.state.value is FilterLibraryOwner.State.Succeeded)
            assertFalse(owner.editSession.value.isEditing)
            assertFalse(owner.editSession.value.hasChanges)
            assertFalse(owner.retryLast())
            current = true
            editCompleted(owner.beginLibraryEditing())
            assertEquals(port.library, owner.editSession.value.baseline)
            assertTrue(owner.editSession.value.isEditing)
            assertEquals(1, port.applyCount)
        } finally { owner.close() }
    }

    @Test fun `durably applied old library batch never retires a successor session`() = runTest {
        val (owner, port) = owner(seeded)
        var successor: com.lavasec.app.foundation.filter.FilterLibraryEditSession? = null
        try {
            editCompleted(owner.beginLibraryEditing())
            editCompleted(owner.toggleStagedDeletion("filter-comprehensive"))
            val captured = owner.editSession.value
            port.afterReviewedApply = {
                editCompleted(owner.cancelLibraryEditing())
                editCompleted(owner.beginLibraryEditing())
                val nextID = port.library.profiles.first { it.id != port.library.activeProfileId }.id
                editCompleted(owner.toggleStagedDeletion(nextID))
                successor = owner.editSession.value
            }
            val outcome = owner.commitLibraryEdit(captured.stagedDeletions, captured, isCurrent = { true })
            assertEquals(FilterLibraryEditRejection.COMMIT_FAILED, editRejection(outcome))
            assertNull(port.library.profile("filter-comprehensive"))
            assertEquals(1, port.applyCount)
            assertNotNull(successor)
            assertSame(successor, owner.editSession.value)
            assertTrue(owner.editSession.value.isEditing)
            assertTrue(owner.editSession.value.hasChanges)
            assertEquals(port.library, owner.editSession.value.baseline)
            assertFalse(owner.retryLast())
        } finally { owner.close() }
    }

    @Test fun `caller cancellation after durable library publication still retires the owned session`() = runTest {
        val (owner, port) = owner(seeded)
        lateinit var submitting: kotlinx.coroutines.Deferred<FilterLibraryEditOutcome>
        try {
            editCompleted(owner.beginLibraryEditing())
            editCompleted(owner.toggleStagedDeletion("filter-comprehensive"))
            val captured = owner.editSession.value
            port.afterReviewedApply = { submitting.cancel() }
            submitting = async { owner.commitLibraryEdit(captured.stagedDeletions, captured, isCurrent = { true }) }
            advanceUntilIdle()
            submitting.join()
            assertTrue(submitting.isCancelled)
            assertNull(port.library.profile("filter-comprehensive"))
            assertEquals(1, port.applyCount)
            assertTrue(owner.state.value is FilterLibraryOwner.State.Succeeded)
            assertFalse(owner.editSession.value.isEditing)
            assertFalse(owner.editSession.value.hasChanges)
            assertFalse(owner.retryLast())
            editCompleted(owner.beginLibraryEditing())
            assertEquals(port.library, owner.editSession.value.baseline)
            assertEquals(1, port.applyCount)
        } finally { owner.close() }
    }

    @Test fun `queued exact draft retirement rechecks the native visit inside admission`() = runTest {
        val (owner, port) = owner(seeded, limits = { FeatureLimits.PLUS })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var current = true
        try {
            editCompleted(owner.beginLibraryEditing())
            val id = (owner.beginCreatingFilter(null, null) as FilterLibraryEditOutcome.Created).filterId
            val identity = owner.newFilter.value
            val draft = owner.draftFor(id)
            val lease = checkNotNull(owner.draftLease(id))
            port.beforeReviewedApply = { entered.complete(Unit); release.await() }
            val occupying = async { owner.renameFilter("filter-balanced", seeded.profile("filter-balanced")!!.name, isCurrent = { true }) }
            entered.await()
            val retiring = async { owner.discardDraft(id, null, null, lease, isCurrent = { current }) }
            advanceUntilIdle(); assertFalse(retiring.isCompleted)
            current = false; release.complete(Unit)
            assertTrue(occupying.await()); assertFalse(retiring.await())
            assertSame(identity, owner.newFilter.value)
            assertSame(draft, owner.draftFor(id))
            assertEquals(lease, owner.draftLease(id))
            assertEquals(seeded, port.library); assertEquals(1, port.applyCount)
            assertTrue(owner.discardDraft(id, null, null, lease, isCurrent = { true }))
            assertNull(owner.newFilter.value); assertNull(owner.draftFor(id)); assertNull(owner.draftLease(id))
            assertEquals(1, port.applyCount)
        } finally { release.complete(Unit); owner.close() }
    }

    private class RecordingPort(
        initial: FilterLibraryDraft,
        private var failNext: Boolean,
        private val supersede: Boolean,
        private val protectionUnavailable: Boolean,
    ) : FilterLibraryPort {
        private var generation = 0L
        var library = initial
            set(value) { field = value; generation++ }
        var lastAdmission: FeatureLimitAdmission? = null
        var applyCount = 0
            private set
        var failNextSource: com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure? = null
        var beforeReviewedApply: (suspend () -> Unit)? = null
        var afterReviewedApply: (() -> Unit)? = null
        var reviewedWhileCurrentCount = 0
            private set
        var qaGuard: com.lavasec.app.platform.NativeFilterPublicationGuard? = null

        override fun currentLibrary(): FilterLibraryDraft = library
        override fun reviewBasis() = FilterLibraryReviewBasis(library, generation)

        override suspend fun apply(desired: FilterLibraryDraft, admission: FeatureLimitAdmission): FilterLibraryMutationResult {
            applyCount++
            lastAdmission = admission
            if (protectionUnavailable) return FilterLibraryMutationResult.ProtectionUnavailable
            failNextSource?.let { failure ->
                failNextSource = null
                return FilterLibraryMutationResult.FailedSource("custom-source", "Reviewed source", failure)
            }
            if (failNext) {
                // One-shot compile failure: the durable store never saw a write.
                failNext = false
                return FilterLibraryMutationResult.Failed(FilterMutationFailure.COMPILE_FAILED)
            }
            if (supersede) return FilterLibraryMutationResult.Superseded
            library = desired
            return FilterLibraryMutationResult.Applied(applyCount.toLong())
        }

        override suspend fun applyReviewed(desired: FilterLibraryDraft, expected: FilterLibraryReviewBasis, admission: FeatureLimitAdmission): FilterLibraryMutationResult {
            beforeReviewedApply?.invoke()
            if (reviewBasis() != expected) return FilterLibraryMutationResult.Superseded
            return apply(desired, admission)
        }

        override suspend fun applyReviewedWhileCurrent(
            desired: FilterLibraryDraft, expected: FilterLibraryReviewBasis,
            admission: FeatureLimitAdmission, isCurrent: () -> Boolean,
        ): FilterLibraryMutationResult {
            reviewedWhileCurrentCount++
            beforeReviewedApply?.invoke()
            if (reviewBasis() != expected || !isCurrent()) return FilterLibraryMutationResult.Superseded
            return apply(desired, admission).also { afterReviewedApply?.invoke() }
        }

        override suspend fun applyReviewedForQaComposite(
            desired: FilterLibraryDraft, expected: FilterLibraryReviewBasis, admission: FeatureLimitAdmission,
            isCurrent: () -> Boolean, publicationGuard: com.lavasec.app.platform.NativeFilterPublicationGuard,
        ): FilterLibraryMutationResult {
            beforeReviewedApply?.invoke()
            if (reviewBasis() != expected || !isCurrent()) return FilterLibraryMutationResult.Superseded
            qaGuard = publicationGuard
            var result: FilterLibraryMutationResult = FilterLibraryMutationResult.Superseded
            publicationGuard.publish {
                if (!isCurrent()) com.lavasec.app.platform.FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED)
                else { result = apply(desired, admission); com.lavasec.app.platform.FilterMutationPublishResult.Committed }
            }
            return result
        }

        override suspend fun restoreDefaults(admission: FeatureLimitAdmission): FilterLibraryMutationResult {
            val restored = FilterLibraryDraft.seededDefaults(catalog)
                .withResolverPlan(library.resolverPlan)
            return apply(restored, admission)
        }
    }

    private companion object {
        val catalog = FilterCatalogSnapshot(
            listOf(
                CatalogFilterSource("blocklistproject-basic", FilterSourceCategory.SECURITY, true),
                CatalogFilterSource("stevenblack-unified", FilterSourceCategory.MULTI_PURPOSE, true),
            ),
        )
        val seeded = FilterLibraryDraft.seededDefaults(catalog)
    }
}
