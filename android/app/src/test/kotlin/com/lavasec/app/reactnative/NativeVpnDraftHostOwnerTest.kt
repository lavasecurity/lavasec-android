package com.lavasec.app.reactnative

import com.lavasec.app.foundation.chained.ChainedConfRefusal
import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.chained.ChainedUpstreamRotation
import com.lavasec.app.platform.ChainedConfigurationCommandOwner
import com.lavasec.app.platform.ChainedConfigurationEditorCommitResult
import com.lavasec.app.platform.ChainedConfigurationEditorRefusal
import com.lavasec.app.platform.ChainedUpstreamConfigurationRepository
import com.lavasec.app.platform.ChainedUpstreamIdentity
import com.lavasec.app.platform.ChainedUpstreamPendingRemovalResult
import com.lavasec.app.platform.ChainedUpstreamSaveExpectation
import com.lavasec.app.platform.ChainedUpstreamSaveResult
import com.lavasec.app.platform.ChainedUpstreamSecretMaterial
import com.lavasec.app.platform.ChainedUpstreamSelectionRow
import com.lavasec.app.platform.StoredChainedUpstreamConfiguration
import com.lavasec.app.platform.VpnConfigurationPick
import com.lavasec.app.platform.VpnConfigurationPickSource
import com.lavasec.app.platform.VpnFirstConfigurationBegin
import com.lavasec.app.platform.VpnFirstConfigurationCoordinator
import com.lavasec.app.platform.canonicalChainedUpstreamConfigurationFingerprint
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeVpnDraftHostOwnerTest {
    @Test fun `destroyed parent compensates its actual imported rotation without a read grant`() = runTest {
        val f = Fixture(this)
        try {
            val imported = f.begin("A") as VpnFirstConfigurationBegin.Draft
            assertTrue(imported.imported); assertEquals("A", f.repository.snapshot?.pendingImportId)
            assertEquals(imported.draft, f.commands.currentDraft())
            f.alive = false; f.readGrant = false
            f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            assertTrue("Retirement itself is synchronous and does not wait for repository IO", f.registry.hasImport(f.composition))
            advanceUntilIdle()
            assertNull(f.repository.snapshot); assertNull(f.commands.currentDraft())
            assertFalse(f.registry.hasImport(f.composition)); assertEquals(1, f.repository.removals)
            assertEquals(1, f.authorizations)
        } finally { f.close() }
    }

    @Test fun `queued Begin abandonment receives its own attempt across cleanup lane handoff`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A")
            var successor: Deferred<VpnFirstConfigurationBegin>? = null
            f.repository.onRemoved = {
                f.repository.onRemoved = {}
                f.replaceHost()
                f.afterPick = {
                    f.alive = false
                    f.registry.retireHost(f.host.owner, f.host.nativeHostID)
                }
                // Resume synchronously at Mutex unlock: the prior worker must relinquish its
                // scheduling token before B can return a newly abandoned import capability.
                successor = async(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) { f.begin("B") }
            }
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            advanceUntilIdle()
            assertEquals(VpnFirstConfigurationBegin.Unavailable, checkNotNull(successor).await())
            assertEquals(2, f.repository.removals)
            assertNull(f.repository.snapshot); assertNull(f.commands.currentDraft())
            assertFalse(f.registry.hasImport(f.composition))
        } finally { f.close() }
    }

    @Test fun `failed abandoned removal is retained with no loop until one explicit Begin retry`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A"); f.repository.failedRemovals = 2
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            advanceUntilIdle(); assertEquals(1, f.repository.removals)
            assertTrue(f.registry.hasImport(f.composition)); assertEquals("A", f.repository.snapshot?.pendingImportId)
            advanceUntilIdle(); assertEquals("No blind retry", 1, f.repository.removals)
            f.replaceHost()
            assertEquals(VpnFirstConfigurationBegin.Unavailable, f.begin("B"))
            assertEquals(2, f.repository.removals); assertEquals("A", f.repository.snapshot?.pendingImportId)
            val successor = f.begin("B") as VpnFirstConfigurationBegin.Draft
            assertTrue(successor.imported); assertEquals(3, f.repository.removals)
            assertEquals("B", f.repository.snapshot?.pendingImportId)
            f.registry.retireHost(f.oldHost.owner, f.oldHost.nativeHostID)
            advanceUntilIdle(); assertEquals("B", f.repository.snapshot?.pendingImportId)
            assertEquals(successor.draft, f.commands.currentDraft())
        } finally { f.close() }
    }

    @Test fun `unreadable import receipt retains actual draft generation until authoritative retry`() = runTest {
        val f = Fixture(this)
        try {
            f.afterPick = { f.repository.failedReads = 2 }
            val imported = f.begin("A") as VpnFirstConfigurationBegin.Draft
            assertTrue(imported.imported)
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            advanceUntilIdle(); assertEquals(0, f.repository.removals)
            assertTrue(f.registry.hasImport(f.composition)); assertEquals(imported.draft.generation, f.repository.snapshot?.identity?.generation)
            f.afterPick = {}; f.replaceHost()
            assertTrue(f.begin("B") is VpnFirstConfigurationBegin.Draft)
            assertEquals(1, f.repository.removals); assertEquals("B", f.repository.snapshot?.pendingImportId)
        } finally { f.close() }
    }

    @Test fun `destroy during actual publication keeps failed compensation owned after unavailable Begin`() = runTest {
        val f = Fixture(this)
        try {
            f.repository.afterPublication = CompletableDeferred()
            f.repository.failedRemovals = 2
            val importing = async { f.begin("A") }
            runCurrent(); assertTrue(f.repository.published.isCompleted)
            assertEquals("A", f.repository.snapshot?.pendingImportId)
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            assertFalse(importing.isCompleted)
            f.repository.afterPublication?.complete(Unit)
            assertEquals(VpnFirstConfigurationBegin.Unavailable, importing.await())
            advanceUntilIdle(); assertEquals(2, f.repository.removals)
            assertTrue(f.registry.hasImport(f.composition)); assertEquals("A", f.repository.snapshot?.pendingImportId)
            f.replaceHost(); assertTrue(f.begin("B") is VpnFirstConfigurationBegin.Draft)
            assertEquals(3, f.repository.removals); assertEquals("B", f.repository.snapshot?.pendingImportId)
        } finally { f.close() }
    }

    @Test fun `module invalidation cleanup survives cancellation of its command scope`() = runTest {
        val f = Fixture(this)
        val moduleScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            f.repository.afterPublication = CompletableDeferred(); f.repository.failedRemovals = 1
            val importing = moduleScope.async { f.begin("A") }
            runCurrent(); assertTrue(f.repository.published.isCompleted)
            f.moduleAlive = false; f.registry.retireModule(f.module)
            importing.cancel(); f.repository.afterPublication?.complete(Unit)
            importing.cancelAndJoin(); moduleScope.cancel()
            advanceUntilIdle()
            assertEquals("A", f.repository.snapshot?.pendingImportId)
            assertTrue(f.registry.hasImport(f.composition)); assertEquals(1, f.repository.removals)
            // The cancelled module cannot own this retry; the same surviving product registry does.
            f.replaceHost(newModule = true); assertTrue(f.begin("B") is VpnFirstConfigurationBegin.Draft)
            assertEquals(2, f.repository.removals); assertEquals("B", f.repository.snapshot?.pendingImportId)
        } finally { moduleScope.cancel(); f.close() }
    }

    @Test fun `ordinary saved editor retires without deleting its durable configuration`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A")
            f.commands.stageEditorName("A", 0, "Saved")
            f.registry.serialized(f.composition) {
                val commit = f.commands.commitEditor("A", forceNewGeneration = true) { true }
                assertTrue(commit is ChainedConfigurationEditorCommitResult.Committed)
                f.registry.finishCommit(f.host, 1L, f.composition, f.commands, "A")
            }
            val saved = checkNotNull(f.repository.snapshot); assertNull(saved.pendingImportId)
            val draft = f.begin("saved", saved.identity.generation) as VpnFirstConfigurationBegin.Draft
            assertFalse(draft.imported)
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            advanceUntilIdle(); assertEquals(saved, f.repository.snapshot); assertNull(f.commands.currentDraft())
            assertEquals(0, f.repository.removals)
        } finally { f.close() }
    }

    @Test fun `actual Commit publication serializes ahead of destroy and cannot be compensated afterward`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A"); f.commands.stageEditorName("A", 0, "Saved")
            f.repository.beforeSelection = CompletableDeferred()
            val committing = async {
                f.registry.serialized(f.composition) {
                    val outcome = f.commands.commitEditor("A", forceNewGeneration = true) { true }
                    assertTrue(outcome is ChainedConfigurationEditorCommitResult.Committed)
                    f.registry.finishCommit(f.host, 1L, f.composition, f.commands, "A")
                }
            }
            runCurrent(); assertTrue(f.repository.selecting.isCompleted)
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            f.repository.beforeSelection?.complete(Unit); committing.await(); advanceUntilIdle()
            assertNotNull(f.repository.snapshot); assertNull(f.repository.snapshot?.pendingImportId)
            assertFalse(f.registry.hasImport(f.composition)); assertEquals(0, f.repository.removals)
        } finally { f.close() }
    }

    @Test fun `durable profile with failed settings retires only the rebased ordinary editor`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A"); f.commands.stageEditorName("A", 0, "Saved")
            f.fullTunnelSettingsSaved = false
            f.registry.serialized(f.composition) {
                val outcome = f.commands.commitEditor("A", forceNewGeneration = true) { true }
                assertTrue(outcome is ChainedConfigurationEditorCommitResult.Failed)
                assertNotNull((outcome as ChainedConfigurationEditorCommitResult.Failed).committedIdentity)
                f.registry.finishCommit(f.host, 1L, f.composition, f.commands, "A")
            }
            val saved = checkNotNull(f.repository.snapshot)
            assertNull(saved.pendingImportId); assertNotNull(f.commands.currentDraft())
            assertFalse(f.registry.hasImport(f.composition))
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID); advanceUntilIdle()
            assertEquals(saved, f.repository.snapshot); assertNull(f.commands.currentDraft())
            assertEquals(0, f.repository.removals)
        } finally { f.close() }
    }

    @Test fun `explicit failed Cancel retains exact import for the same id retry only`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A"); val original = checkNotNull(f.repository.snapshot).identity
            f.repository.failedRemovals = 1
            f.registry.serialized(f.composition) {
                f.commands.cancelEditor("A")
                assertTrue(f.repository.removePendingImport("A", original) is ChainedUpstreamPendingRemovalResult.Failed)
            }
            assertTrue(f.registry.ownsImport(f.host, 1L, f.composition, f.commands, "A"))
            f.registry.release(f.host, 1L, f.composition, f.commands, "different")
            assertTrue(f.registry.hasImport(f.composition))
            f.registry.serialized(f.composition) {
                assertEquals(ChainedUpstreamPendingRemovalResult.Removed, f.repository.removePendingImport("A", original))
                f.registry.release(f.host, 1L, f.composition, f.commands, "A")
            }
            assertNull(f.repository.snapshot); assertFalse(f.registry.hasImport(f.composition))
        } finally { f.close() }
    }

    @Test fun `authoritative removal refusal is retained without inventing cleanup success`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A"); f.repository.refusedRemovals = 1
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            advanceUntilIdle()
            assertEquals("A", f.repository.snapshot?.pendingImportId)
            assertTrue(f.registry.hasImport(f.composition)); assertEquals(1, f.repository.removals)
            f.replaceHost(); assertTrue(f.begin("B") is VpnFirstConfigurationBegin.Draft)
            assertEquals(2, f.repository.removals); assertEquals("B", f.repository.snapshot?.pendingImportId)
        } finally { f.close() }
    }

    @Test fun `a wrong host cannot retire or release an imported parent`() = runTest {
        val f = Fixture(this)
        try {
            val original = f.begin("A") as VpnFirstConfigurationBegin.Draft
            val other = NativeVisitHost(Any(), "other") { true }
            f.registry.retireHost(other.owner, other.nativeHostID)
            f.registry.release(other, 1L, f.composition, f.commands, "A")
            f.registry.release(f.host, 2L, f.composition, f.commands, "A")
            advanceUntilIdle()
            assertEquals(original.draft, f.commands.currentDraft()); assertEquals("A", f.repository.snapshot?.pendingImportId)
            assertEquals(0, f.repository.removals); assertTrue(f.registry.hasImport(f.composition))
        } finally { f.close() }
    }

    @Test fun `obsolete graph failure cannot block a new composition or remove its saved successor`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A"); f.repository.failedRemovals = 1
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            advanceUntilIdle(); assertTrue(f.registry.hasImport(f.composition))
            f.graphAlive = false
            val repository = Repository(); val commands = ChainedConfigurationCommandOwner(repository)
            val composition = Any(); val host = NativeVisitHost(Any(), "new-graph") { true }
            val coordinator = VpnFirstConfigurationCoordinator(commands, VpnConfigurationPickSource { VpnConfigurationPick.Read(configuration) })
            val newDraft = f.registry.begin(Any(), host, 2L, composition, commands, repository, "B", 0L,
                { true }, { true }, {}, { coordinator.beginOrImport("B", 0L) }) as VpnFirstConfigurationBegin.Draft
            f.registry.retireModule(f.module); advanceUntilIdle()
            assertEquals("B", repository.snapshot?.pendingImportId); assertEquals(newDraft.draft, commands.currentDraft())
            assertEquals(1, f.repository.removals); assertEquals(0, repository.removals)
        } finally { f.close() }
    }

    @Test fun `failed old pending identity cannot remove a committed repository successor`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A"); f.repository.failedRemovals = 1
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID); advanceUntilIdle()
            val successor = f.repository.publishSuccessor()
            f.replaceHost()
            val draft = f.begin("B", successor.identity.generation) as VpnFirstConfigurationBegin.Draft
            assertFalse(draft.imported); assertEquals(successor, f.repository.snapshot)
            assertEquals(draft.draft, f.commands.currentDraft()); assertFalse(f.registry.hasImport(f.composition))
        } finally { f.close() }
    }

    @Test fun `current chooser cancellation keeps its real typed outcome while retirement drains`() = runTest {
        val f = Fixture(this)
        try {
            assertEquals(VpnFirstConfigurationBegin.Cancelled, f.begin("cancelled", pick = VpnConfigurationPick.Cancelled))
            assertNull(f.repository.snapshot); assertNull(f.commands.currentDraft())
            assertEquals(0, f.authorizations)
            advanceUntilIdle()
            assertFalse(f.registry.hasImport(f.composition)); assertEquals(0, f.repository.removals)
        } finally { f.close() }
    }

    @Test fun `current parsed rejection keeps its exact typed refusal without publishing an import`() = runTest {
        val f = Fixture(this)
        try {
            assertEquals(VpnFirstConfigurationBegin.Rejected(ChainedConfRefusal.MalformedLine),
                f.begin("rejected", pick = VpnConfigurationPick.Read("MTU 1400")))
            assertNull(f.repository.snapshot); assertNull(f.commands.currentDraft())
            assertEquals(1, f.authorizations)
            advanceUntilIdle()
            assertFalse(f.registry.hasImport(f.composition)); assertEquals(0, f.repository.removals)
        } finally { f.close() }
    }

    @Test fun `current stale generation refusal keeps its real type and saved successor`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A"); f.commands.stageEditorName("A", 0, "Saved")
            f.registry.serialized(f.composition) {
                assertTrue(f.commands.commitEditor("A", forceNewGeneration = true) { true } is ChainedConfigurationEditorCommitResult.Committed)
                f.registry.finishCommit(f.host, 1L, f.composition, f.commands, "A")
            }
            val saved = checkNotNull(f.repository.snapshot)
            assertEquals(VpnFirstConfigurationBegin.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
                f.begin("stale", saved.identity.generation + 1L))
            advanceUntilIdle()
            assertEquals(saved, f.repository.snapshot); assertNull(saved.pendingImportId)
            assertEquals(0, f.repository.removals); assertEquals(1, f.authorizations)
        } finally { f.close() }
    }

    @Test fun `a retired chooser cannot deliver even a real cancellation to a successor host`() = runTest {
        val f = Fixture(this)
        try {
            f.afterPick = { f.alive = false }
            assertEquals(VpnFirstConfigurationBegin.Unavailable, f.begin("old", pick = VpnConfigurationPick.Cancelled))
            advanceUntilIdle()
            assertNull(f.repository.snapshot); assertNull(f.commands.currentDraft())
            assertEquals(0, f.repository.removals); assertEquals(0, f.authorizations)
        } finally { f.close() }
    }

    @Test fun `cancelled identity capture still schedules actual pending import compensation`() = runTest {
        val f = Fixture(this)
        try {
            f.afterPick = { f.repository.cancelledReads = 1 }
            try { f.begin("A"); fail("Identity capture must preserve cancellation") }
            catch (_: CancellationException) { }
            assertEquals("A", f.repository.snapshot?.pendingImportId)
            assertTrue(f.registry.hasImport(f.composition)); assertEquals(1, f.repository.readCancellations)
            advanceUntilIdle()
            assertNull(f.repository.snapshot); assertNull(f.commands.currentDraft())
            assertFalse(f.registry.hasImport(f.composition)); assertEquals(1, f.repository.removals)
            f.afterPick = {}
            assertTrue(f.begin("B") is VpnFirstConfigurationBegin.Draft)
            assertEquals("B", f.repository.snapshot?.pendingImportId)
        } finally { f.close() }
    }

    @Test fun `cancelled cleanup retains the exact import for one explicit retry without a loop`() = runTest {
        val f = Fixture(this)
        try {
            f.afterPick = { f.repository.cancelledReads = 2 }
            try { f.begin("A"); fail("Identity capture must preserve cancellation") }
            catch (_: CancellationException) { }
            advanceUntilIdle()
            assertEquals(2, f.repository.readCancellations); assertEquals(0, f.repository.removals)
            assertEquals("A", f.repository.snapshot?.pendingImportId); assertTrue(f.registry.hasImport(f.composition))
            advanceUntilIdle(); assertEquals("No automatic retry", 2, f.repository.readCancellations)
            f.afterPick = {}
            assertTrue(f.begin("B") is VpnFirstConfigurationBegin.Draft)
            assertEquals(1, f.repository.removals); assertEquals("B", f.repository.snapshot?.pendingImportId)
        } finally { f.close() }
    }

    @Test fun `empty retired composition lanes are released while a live import lane remains owned`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A")
            repeat(32) { index ->
                val composition = Any(); val repository = Repository()
                val commands = ChainedConfigurationCommandOwner(repository)
                val host = NativeVisitHost(Any(), "cancelled-$index") { true }
                val coordinator = VpnFirstConfigurationCoordinator(commands,
                    VpnConfigurationPickSource { VpnConfigurationPick.Cancelled })
                assertEquals(VpnFirstConfigurationBegin.Cancelled,
                    f.registry.begin(Any(), host, 1L, composition, commands, repository, "cancelled-$index", 0L,
                        { true }, { true }, {}, { coordinator.beginOrImport("cancelled-$index", 0L) }))
                advanceUntilIdle()
                assertEquals("Only the actual live import remains retained", 1, retainedLaneCount(f.registry))
            }
            assertEquals("A", f.repository.snapshot?.pendingImportId)
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            advanceUntilIdle(); assertEquals(0, retainedLaneCount(f.registry))
            assertNull(f.repository.snapshot); assertEquals(1, f.repository.removals)
        } finally { f.close() }
    }

    @Test fun `queued callers cannot split the composition mutex while an empty lane is released`() = runTest {
        val f = Fixture(this)
        val releaseFirst = CompletableDeferred<Unit>(); val releaseSecond = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                f.registry.serialized(f.composition) {
                    order.add("first entered"); releaseFirst.await()
                    f.registry.release(f.host, 1L, f.composition, f.commands, "absent")
                    order.add("first left")
                }
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                f.registry.serialized(f.composition) { order.add("second entered"); releaseSecond.await(); order.add("second left") }
            }
            assertFalse(second.isCompleted); assertEquals(1, retainedLaneCount(f.registry))
            releaseFirst.complete(Unit); runCurrent(); first.await()
            assertEquals(listOf("first entered", "first left", "second entered"), order)
            val third = async(start = CoroutineStart.UNDISPATCHED) {
                f.registry.serialized(f.composition) { order.add("third entered") }
            }
            assertFalse(third.isCompleted); assertEquals(1, retainedLaneCount(f.registry))
            releaseSecond.complete(Unit); second.await(); third.await()
            assertEquals(listOf("first entered", "first left", "second entered", "second left", "third entered"), order)
            assertEquals(0, retainedLaneCount(f.registry))
        } finally { releaseFirst.complete(Unit); releaseSecond.complete(Unit); f.close() }
    }

    @Test fun `cancelled queued caller releases its lane reference without stealing the active mutex`() = runTest {
        val f = Fixture(this)
        val releaseFirst = CompletableDeferred<Unit>()
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                f.registry.serialized(f.composition) { releaseFirst.await() }
            }
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                f.registry.serialized(f.composition) { fail("Cancelled caller must never enter") }
            }
            queued.cancelAndJoin(); assertEquals(1, retainedLaneCount(f.registry))
            releaseFirst.complete(Unit); first.await()
            assertEquals(0, retainedLaneCount(f.registry))
            f.registry.serialized(f.composition) { assertEquals(1, retainedLaneCount(f.registry)) }
            assertEquals(0, retainedLaneCount(f.registry))
        } finally { releaseFirst.complete(Unit); f.close() }
    }

    private fun retainedLaneCount(registry: NativeVpnDraftHostOwner): Int {
        val field = NativeVpnDraftHostOwner::class.java.getDeclaredField("lanes").apply { isAccessible = true }
        return (field.get(registry) as Map<*, *>).size
    }

    @Test fun `successful native Begin issues one exact route lease and never borrows a mismatched draft`() = runTest {
        val f = Fixture(this)
        try {
            val original = f.begin("A") as VpnFirstConfigurationBegin.Draft
            val receipt = captureRetirement(f, original)
            assertEquals("A", receipt.id); assertEquals(1L, receipt.retirementGeneration)
            assertEquals(f.host.nativeHostID, receipt.nativeHostID)
            assertEquals(receipt.retirementLease, UUID.fromString(receipt.retirementLease).toString())
            assertEquals(receipt, captureRetirement(f, original))
            assertNull(f.registry.captureRouteRetirement(Any(), f.host, 1L, f.composition, f.commands, original.draft))
            assertNull(f.registry.captureRouteRetirement(f.module, NativeVisitHost(Any(), "other") { true }, 1L, f.composition, f.commands, original.draft))
            assertNull(f.registry.captureRouteRetirement(f.module, f.host, 2L, f.composition, f.commands, original.draft))
            assertNull(f.registry.captureRouteRetirement(f.module, f.host, 1L, Any(), f.commands, original.draft))
            assertNull(f.registry.captureRouteRetirement(f.module, f.host, 1L, f.composition, ChainedConfigurationCommandOwner(f.repository), original.draft))
            assertNull(f.registry.captureRouteRetirement(f.module, f.host, 1L, f.composition, f.commands, original.draft.copy(id = "other")))
            f.commands.stageEditorName("A", 0, "Changed")
            assertNull("A later draft cannot impersonate this Begin reply", f.registry.captureRouteRetirement(f.module, f.host, 1L, f.composition, f.commands, original.draft))
            assertEquals(0, f.repository.removals)
        } finally { f.close() }
    }

    @Test fun `valid lease retires a live unattempted route without reads or writes then fresh Begin compensates`() = runTest {
        val f = Fixture(this)
        try {
            val original = f.begin("A") as VpnFirstConfigurationBegin.Draft
            val receipt = captureRetirement(f, original); val stored = checkNotNull(f.repository.snapshot)
            val reads = f.repository.reads; val authorizations = f.authorizations
            assertTrue(retire(f, receipt))
            assertNull(f.registry.captureRouteRetirement(f.module, f.host, 1L, f.composition, f.commands, original.draft))
            advanceUntilIdle()
            assertEquals(original.draft, f.commands.currentDraft()); assertEquals(stored, f.repository.snapshot)
            assertEquals(reads, f.repository.reads); assertEquals(authorizations, f.authorizations)
            assertEquals("Marker does not schedule storage cancellation", 0, f.repository.removals)
            assertTrue(f.registry.ownsPendingImport(f.module, f.host, 1L, f.composition, f.commands, "A"))
            val successor = f.begin("B") as VpnFirstConfigurationBegin.Draft
            assertEquals(1, f.repository.removals); assertEquals("B", f.repository.snapshot?.pendingImportId)
            assertEquals(successor.draft, f.commands.currentDraft())
        } finally { f.close() }
    }

    @Test fun `failed explicit Cancel keeps its original lease and waits for one fresh Begin retry`() = runTest {
        val f = Fixture(this)
        try {
            val original = f.begin("A") as VpnFirstConfigurationBegin.Draft
            val receipt = captureRetirement(f, original); val identity = checkNotNull(f.repository.snapshot).identity
            f.repository.failedRemovals = 1
            f.registry.serialized(f.composition) {
                f.commands.cancelEditor("A")
                assertTrue(f.repository.removePendingImport("A", identity) is ChainedUpstreamPendingRemovalResult.Failed)
            }
            val reads = f.repository.reads
            assertTrue(retire(f, receipt)); advanceUntilIdle()
            assertEquals("No duplicate Discard", 1, f.repository.removals); assertEquals(reads, f.repository.reads)
            assertEquals(identity, f.repository.snapshot?.identity)
            assertTrue(f.begin("B") is VpnFirstConfigurationBegin.Draft)
            assertEquals(2, f.repository.removals); assertEquals("B", f.repository.snapshot?.pendingImportId)
        } finally { f.close() }
    }

    @Test fun `lease marker cannot let fresh Begin overtake the original in flight Cancel lane`() = runTest {
        val f = Fixture(this)
        try {
            val receipt = captureRetirement(f, f.begin("A") as VpnFirstConfigurationBegin.Draft)
            val identity = checkNotNull(f.repository.snapshot).identity
            f.repository.beforeRemoval = CompletableDeferred(); f.repository.failedRemovals = 1
            val cancelling = async {
                f.registry.serialized(f.composition) {
                    f.commands.cancelEditor("A")
                    f.repository.removePendingImport("A", identity)
                }
            }
            runCurrent(); assertTrue(f.repository.removing.isCompleted)
            assertTrue(retire(f, receipt))
            val opening = async { f.begin("B") }
            runCurrent(); assertFalse(cancelling.isCompleted); assertFalse(opening.isCompleted)
            assertEquals(1, f.repository.removals); assertEquals("A", f.repository.snapshot?.pendingImportId)
            f.repository.beforeRemoval?.complete(Unit)
            assertTrue(cancelling.await() is ChainedUpstreamPendingRemovalResult.Failed)
            assertTrue(opening.await() is VpnFirstConfigurationBegin.Draft)
            assertEquals(2, f.repository.removals); assertEquals("B", f.repository.snapshot?.pendingImportId)
        } finally { f.repository.beforeRemoval?.complete(Unit); f.close() }
    }

    @Test fun `missing forged or mismatched lease cannot retire a live native route`() = runTest {
        val f = Fixture(this)
        try {
            val original = f.begin("A") as VpnFirstConfigurationBegin.Draft
            assertFalse(f.registry.retireRoute(f.module, f.host.nativeHostID, 1L, f.composition, f.commands, "A", UUID.randomUUID().toString()))
            val receipt = captureRetirement(f, original)
            assertFalse(retire(f, receipt.copy(retirementLease = "")))
            assertFalse(retire(f, receipt.copy(retirementLease = UUID.randomUUID().toString())))
            assertFalse(f.registry.retireRoute(Any(), receipt.nativeHostID, 1L, f.composition, f.commands, "A", receipt.retirementLease))
            assertFalse(retire(f, receipt.copy(nativeHostID = "other")))
            assertFalse(retire(f, receipt.copy(retirementGeneration = 2L)))
            assertFalse(f.registry.retireRoute(f.module, receipt.nativeHostID, 1L, Any(), f.commands, "A", receipt.retirementLease))
            assertFalse(f.registry.retireRoute(f.module, receipt.nativeHostID, 1L, f.composition, ChainedConfigurationCommandOwner(f.repository), "A", receipt.retirementLease))
            assertFalse(retire(f, receipt.copy(id = "other")))
            f.graphAlive = false; assertFalse(retire(f, receipt)); f.graphAlive = true
            advanceUntilIdle(); assertEquals(0, f.repository.removals)
            assertEquals(original.draft, f.commands.currentDraft()); assertEquals("A", f.repository.snapshot?.pendingImportId)
        } finally { f.close() }
    }

    @Test fun `successful Cancel releases its lease and a late marker cannot touch successor B`() = runTest {
        val f = Fixture(this)
        try {
            val receipt = captureRetirement(f, f.begin("A") as VpnFirstConfigurationBegin.Draft)
            val identity = checkNotNull(f.repository.snapshot).identity
            f.registry.serialized(f.composition) {
                f.commands.cancelEditor("A")
                assertEquals(ChainedUpstreamPendingRemovalResult.Removed, f.repository.removePendingImport("A", identity))
                f.registry.release(f.host, 1L, f.composition, f.commands, "A")
            }
            val successor = f.begin("B") as VpnFirstConfigurationBegin.Draft
            assertFalse(retire(f, receipt)); advanceUntilIdle()
            assertEquals(1, f.repository.removals); assertEquals(successor.draft, f.commands.currentDraft())
            assertEquals("B", f.repository.snapshot?.pendingImportId)
        } finally { f.close() }
    }

    @Test fun `same id successor Visit has a new lease that old route metadata cannot borrow`() = runTest {
        val f = Fixture(this)
        try {
            val old = captureRetirement(f, f.begin("A") as VpnFirstConfigurationBegin.Draft)
            assertTrue(retire(f, old))
            val successor = f.begin("A") as VpnFirstConfigurationBegin.Draft
            val current = captureRetirement(f, successor)
            assertNotEquals(old.retirementLease, current.retirementLease)
            assertFalse(retire(f, old)); advanceUntilIdle()
            assertEquals(1, f.repository.removals); assertEquals(successor.draft, f.commands.currentDraft())
            assertEquals(successor.draft.generation, f.repository.snapshot?.identity?.generation)
        } finally { f.close() }
    }

    @Test fun `actual host death still owns one independent attempt after lease only route retirement`() = runTest {
        val f = Fixture(this)
        try {
            val receipt = captureRetirement(f, f.begin("A") as VpnFirstConfigurationBegin.Draft)
            f.repository.failedRemovals = 1
            assertTrue(retire(f, receipt)); advanceUntilIdle(); assertEquals(0, f.repository.removals)
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID); f.registry.retireModule(f.module)
            advanceUntilIdle(); assertEquals(1, f.repository.removals)
            assertTrue(f.registry.hasImport(f.composition)); assertEquals("A", f.repository.snapshot?.pendingImportId)
            f.registry.retireHost(f.host.owner, f.host.nativeHostID); f.registry.retireModule(f.module)
            advanceUntilIdle(); assertEquals("No automatic retry loop", 1, f.repository.removals)
            f.replaceHost(newModule = true)
            assertTrue(f.begin("B") is VpnFirstConfigurationBegin.Draft)
            assertEquals(2, f.repository.removals); assertEquals("B", f.repository.snapshot?.pendingImportId)
        } finally { f.close() }
    }

    @Test fun `late lease retirement of a committed editor cannot delete its saved configuration`() = runTest {
        val f = Fixture(this)
        try {
            val receipt = captureRetirement(f, f.begin("A") as VpnFirstConfigurationBegin.Draft)
            f.commands.stageEditorName("A", 0, "Saved")
            f.fullTunnelSettingsSaved = false
            f.registry.serialized(f.composition) {
                val outcome = f.commands.commitEditor("A", forceNewGeneration = true) { true }
                assertTrue(outcome is ChainedConfigurationEditorCommitResult.Failed)
                assertNotNull((outcome as ChainedConfigurationEditorCommitResult.Failed).committedIdentity)
                f.registry.finishCommit(f.host, 1L, f.composition, f.commands, "A")
            }
            val saved = checkNotNull(f.repository.snapshot); assertNull(saved.pendingImportId)
            assertTrue(retire(f, receipt)); advanceUntilIdle()
            assertEquals(saved, f.repository.snapshot); assertEquals(0, f.repository.removals)
            val successor = f.begin("B", saved.identity.generation) as VpnFirstConfigurationBegin.Draft
            assertFalse(successor.imported); assertEquals(saved, f.repository.snapshot)
            assertEquals(0, f.repository.removals); assertEquals(successor.draft, f.commands.currentDraft())
        } finally { f.close() }
    }

    private fun captureRetirement(f: Fixture, draft: VpnFirstConfigurationBegin.Draft): NativeVpnDraftHostOwner.RouteRetirement =
        checkNotNull(f.registry.captureRouteRetirement(f.module, f.host, 1L, f.composition, f.commands, draft.draft))

    private fun retire(f: Fixture, receipt: NativeVpnDraftHostOwner.RouteRetirement): Boolean =
        f.registry.retireRoute(f.module, receipt.nativeHostID, receipt.retirementGeneration,
            f.composition, f.commands, receipt.id, receipt.retirementLease)

    @Test fun `obsolete failed graph custody is discarded read free on a fresh composition acquisition`() = runTest {
        val f = Fixture(this)
        try {
            f.begin("A"); f.repository.failedRemovals = 1
            f.alive = false; f.registry.retireHost(f.host.owner, f.host.nativeHostID)
            advanceUntilIdle(); assertEquals(1, f.repository.removals)
            val retained = checkNotNull(f.repository.snapshot)
            assertEquals(1, retainedLaneCount(f.registry))
            f.graphAlive = false
            repeat(32) {
                f.registry.serialized(Any()) { }
                assertEquals("Obsolete and idle lanes do not accumulate", 0, retainedLaneCount(f.registry))
            }
            assertEquals("No obsolete repository mutation", 1, f.repository.removals)
            assertEquals(retained, f.repository.snapshot)
            val repository = Repository(); val commands = ChainedConfigurationCommandOwner(repository)
            val composition = Any(); val host = NativeVisitHost(Any(), "current-graph") { true }
            val coordinator = VpnFirstConfigurationCoordinator(commands,
                VpnConfigurationPickSource { VpnConfigurationPick.Read(configuration) })
            assertTrue(f.registry.begin(Any(), host, 2L, composition, commands, repository, "B", 0L,
                { true }, { true }, {}, { coordinator.beginOrImport("B", 0L) }) is VpnFirstConfigurationBegin.Draft)
            assertEquals(1, retainedLaneCount(f.registry)); assertEquals("B", repository.snapshot?.pendingImportId)
            assertEquals(retained, f.repository.snapshot); assertEquals(1, f.repository.removals)
        } finally { f.close() }
    }

    @Test fun `obsolete graph callback runs outside registry lock and cannot block a concurrent custody read`() = runTest {
        val f = Fixture(this)
        val executor = Executors.newSingleThreadExecutor()
        var graph = true; var probe = false
        try {
            val coordinator = VpnFirstConfigurationCoordinator(f.commands,
                VpnConfigurationPickSource { VpnConfigurationPick.Read(configuration) })
            val graphCurrent = {
                if (probe) assertTrue(executor.submit<Boolean> { f.registry.hasImport(f.composition) }.get(1, TimeUnit.SECONDS))
                graph
            }
            assertTrue(f.registry.begin(f.module, f.host, 1L, f.composition, f.commands, f.repository, "A", 0L,
                graphCurrent, { true }, {}, { coordinator.beginOrImport("A", 0L) }) is VpnFirstConfigurationBegin.Draft)
            f.repository.failedRemovals = 1; f.registry.retireModule(f.module)
            advanceUntilIdle(); val retained = checkNotNull(f.repository.snapshot)
            graph = false; probe = true
            f.registry.serialized(Any()) { }
            assertEquals(0, retainedLaneCount(f.registry)); assertEquals(retained, f.repository.snapshot)
            assertEquals(1, f.repository.removals)
        } finally { executor.shutdownNow(); executor.awaitTermination(1, TimeUnit.SECONDS); f.close() }
    }

    private class Fixture(test: TestScope) : AutoCloseable {
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        private val productScope = CoroutineScope(SupervisorJob() + dispatcher)
        val registry = NativeVpnDraftHostOwner(productScope, dispatcher)
        val composition = Any(); var module = Any()
        private class Lifetime(var alive: Boolean = true)
        private var lifetime = Lifetime()
        var alive: Boolean
            get() = lifetime.alive
            set(value) { lifetime.alive = value }
        var graphAlive = true; var moduleAlive = true; var readGrant = true
        var host = lifetime.let { captured -> NativeVisitHost(Any(), "original") { captured.alive } }
        var oldHost = host
        val repository = Repository()
        var fullTunnelSettingsSaved = true
        val commands = ChainedConfigurationCommandOwner(repository,
            onFullTunnelCommittedWhileCurrent = { _, current -> current() && fullTunnelSettingsSaved })
        var authorizations = 0
        var afterPick: () -> Unit = {}
        fun replaceHost(newModule: Boolean = false) {
            oldHost = host; lifetime = Lifetime(); moduleAlive = true
            if (newModule) module = Any()
            host = lifetime.let { captured -> NativeVisitHost(Any(), "successor") { captured.alive } }
        }
        suspend fun begin(id: String, expected: Long = 0L, pick: VpnConfigurationPick = VpnConfigurationPick.Read(configuration)): VpnFirstConfigurationBegin {
            val original = host
            val current = { graphAlive && moduleAlive && original.matches(host) }
            val coordinator = VpnFirstConfigurationCoordinator(commands, object : VpnConfigurationPickSource {
                override suspend fun pick(): VpnConfigurationPick = pick
                override fun finishPick(pick: VpnConfigurationPick) { afterPick() }
            }, revalidate = { ++authorizations; readGrant && current() }, isCurrent = current)
            return registry.begin(module, original, 1L, composition, commands, repository, id, expected,
                { graphAlive }, current, {}, { coordinator.beginOrImport(id, expected) })
        }
        override fun close() {
            alive = false; moduleAlive = false
            repository.afterPublication?.complete(Unit); repository.beforeSelection?.complete(Unit)
            productScope.cancel()
        }
    }

    private class Repository : ChainedUpstreamConfigurationRepository {
        private val stored = MutableStateFlow<StoredChainedUpstreamConfiguration?>(null)
        val snapshot get() = stored.value
        private var generation = 0L
        var reads = 0; var cancelledReads = 0; var readCancellations = 0
        var removals = 0; var failedRemovals = 0; var refusedRemovals = 0; var failedReads = 0
        val published = CompletableDeferred<Unit>(); val selecting = CompletableDeferred<Unit>()
        val removing = CompletableDeferred<Unit>(); var beforeRemoval: CompletableDeferred<Unit>? = null
        var afterPublication: CompletableDeferred<Unit>? = null
        var beforeSelection: CompletableDeferred<Unit>? = null
        var onRemoved: () -> Unit = {}
        override val storedConfiguration: Flow<StoredChainedUpstreamConfiguration?> = stored
        override suspend fun configurationIssue(): ChainedConfigurationIssue? =
            if (stored.value == null) ChainedConfigurationIssue.MISSING_CONFIGURATION else null
        override suspend fun loadConfiguration(): StoredChainedUpstreamConfiguration? {
            ++reads
            if (cancelledReads > 0) { --cancelledReads; ++readCancellations; throw CancellationException("bounded identity read cancellation") }
            if (failedReads > 0) { --failedReads; error("bounded read failure") }
            return stored.value
        }
        override suspend fun usableSecretsGeneration(): Long? = stored.value?.identity?.generation
        override suspend fun loadSecrets(): ChainedUpstreamSecretMaterial? = null
        override suspend fun deleteSecrets() = Unit
        override suspend fun removeAll() { stored.value = null }
        override suspend fun save(rotation: ChainedUpstreamRotation, expectation: ChainedUpstreamSaveExpectation): ChainedUpstreamSaveResult =
            saveWhileCurrent(rotation, expectation) { true }
        override suspend fun saveWhileCurrent(rotation: ChainedUpstreamRotation, expectation: ChainedUpstreamSaveExpectation,
            isCurrent: () -> Boolean): ChainedUpstreamSaveResult {
            if (!isCurrent()) return ChainedUpstreamSaveResult.Superseded
            if (expectation is ChainedUpstreamSaveExpectation.AbsentPendingImport && stored.value != null) return ChainedUpstreamSaveResult.Superseded
            val identity = ChainedUpstreamIdentity(++generation, canonicalChainedUpstreamConfigurationFingerprint(rotation.configuration))
            stored.value = StoredChainedUpstreamConfiguration(rotation.configuration, identity, 1L,
                (expectation as? ChainedUpstreamSaveExpectation.AbsentPendingImport)?.editorId)
            published.complete(Unit); afterPublication?.await()
            return ChainedUpstreamSaveResult.Committed(identity)
        }
        override suspend fun saveSelectionWhileCurrent(rows: List<ChainedUpstreamSelectionRow>, expectation: ChainedUpstreamSaveExpectation,
            isCurrent: () -> Boolean): ChainedUpstreamSaveResult {
            selecting.complete(Unit); beforeSelection?.await()
            if (!isCurrent() || (expectation as? ChainedUpstreamSaveExpectation.Generation)?.generation != stored.value?.identity?.generation) {
                return ChainedUpstreamSaveResult.Superseded
            }
            val configuration = rows.single().configuration
            val identity = ChainedUpstreamIdentity(++generation, canonicalChainedUpstreamConfigurationFingerprint(configuration))
            stored.value = StoredChainedUpstreamConfiguration(configuration, identity, 2L)
            return ChainedUpstreamSaveResult.Committed(identity)
        }
        override suspend fun removePendingImport(editorId: String, identity: ChainedUpstreamIdentity): ChainedUpstreamPendingRemovalResult {
            ++removals; removing.complete(Unit); beforeRemoval?.await()
            if (refusedRemovals > 0) { --refusedRemovals; return ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed }
            if (failedRemovals > 0) { --failedRemovals; return ChainedUpstreamPendingRemovalResult.Failed("bounded removal failure") }
            val current = stored.value
            if (current == null || current.pendingImportId != editorId || current.identity != identity) return ChainedUpstreamPendingRemovalResult.Superseded
            stored.value = null; onRemoved(); return ChainedUpstreamPendingRemovalResult.Removed
        }
        fun publishSuccessor(): StoredChainedUpstreamConfiguration {
            val old = checkNotNull(stored.value)
            return old.copy(identity = old.identity.copy(generation = ++generation), pendingImportId = null).also { stored.value = it }
        }
    }

    companion object {
        private val configuration = """
            [Interface]
            PrivateKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 0x11 })}
            Address = 10.7.0.2/32
            [Peer]
            PublicKey = ${Base64.getEncoder().encodeToString(ByteArray(32) { 0x22 })}
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
        """.trimIndent()
    }
}
