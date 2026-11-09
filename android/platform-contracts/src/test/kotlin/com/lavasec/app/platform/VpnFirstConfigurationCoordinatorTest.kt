package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedConfException
import com.lavasec.app.foundation.chained.ChainedConfRefusal
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.foundation.chained.ChainedUpstreamRotation
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnFirstConfigurationCoordinatorTest {

    @Test
    fun `begin imports a picked configuration, saves it and opens the draft`() = runTest {
        val identity = ChainedUpstreamIdentity(
            generation = 1L,
            fingerprint = canonicalChainedUpstreamConfigurationFingerprint(configuration()),
        )
        val repository = RecordingRepository(
            nextSave = ChainedUpstreamSaveResult.Committed(identity),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Read("conf"))

        val outcome = VpnFirstConfigurationCoordinator(owner, picks).beginOrImport("e1", 0L)

        assertTrue(outcome is VpnFirstConfigurationBegin.Draft)
        assertEquals(1L, (outcome as VpnFirstConfigurationBegin.Draft).draft.generation)
        assertTrue("a first import is pending-until-commit", outcome.imported)
        assertEquals(1, picks.calls)
        assertEquals(1, picks.finished.size)
        assertEquals(1, repository.savedRotations.size)
        assertEquals(configuration(), repository.savedRotations.single().configuration)
    }

    @Test
    fun `a concurrent commit between the picker and the save refuses the first import`() = runTest {
        val repository = RecordingRepository()
        // A rotation commits while the picker is open, after the coordinator's initial
        // NOT_CONFIGURED observation but before the serialized save. The absence CAS must refuse
        // rather than overwrite the newly committed rotation.
        repository.beforeSave = { repository.setStored(stored(configuration(), generation = 1L)) }
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Read("conf"))

        val outcome = VpnFirstConfigurationCoordinator(owner, picks).beginOrImport("e1", 0L)

        assertEquals(VpnFirstConfigurationBegin.Unavailable, outcome)
        assertTrue("the concurrent rotation must survive", repository.savedRotations.isEmpty())
        assertEquals(1L, repository.loadConfiguration()?.identity?.generation)
    }

    @Test
    fun `begin drops the pick when post-picker revalidation fails`() = runTest {
        val repository = RecordingRepository(
            nextSave = ChainedUpstreamSaveResult.Committed(
                ChainedUpstreamIdentity(
                    generation = 1L,
                    fingerprint = canonicalChainedUpstreamConfigurationFingerprint(configuration()),
                ),
            ),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Read("conf"))

        val outcome = VpnFirstConfigurationCoordinator(owner, picks, revalidate = { false })
            .beginOrImport("e1", 0L)

        assertEquals(VpnFirstConfigurationBegin.Unavailable, outcome)
        assertEquals(1, picks.calls)
        assertEquals(1, picks.finished.size)
        assertTrue("a failed revalidation must not save the pick", repository.savedRotations.isEmpty())
    }

    @Test
    fun `begin opens the existing rotation without prompting for a configuration`() = runTest {
        val repository = RecordingRepository(
            initial = stored(configuration(), generation = 3L),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Read("conf"))

        val outcome = VpnFirstConfigurationCoordinator(owner, picks).beginOrImport("e1", 3L)

        assertTrue(outcome is VpnFirstConfigurationBegin.Draft)
        assertEquals(3L, (outcome as VpnFirstConfigurationBegin.Draft).draft.generation)
        assertFalse("opening an existing rotation is not a pending import", outcome.imported)
        assertEquals(0, picks.calls)
        assertTrue(picks.finished.isEmpty())
        assertTrue(repository.savedRotations.isEmpty())
    }

    @Test
    fun `begin refuses a stale generation without prompting`() = runTest {
        val repository = RecordingRepository(
            initial = stored(configuration(), generation = 3L),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Read("conf"))

        val outcome = VpnFirstConfigurationCoordinator(owner, picks).beginOrImport("e1", 2L)

        assertEquals(
            VpnFirstConfigurationBegin.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
            outcome,
        )
        assertEquals(0, picks.calls)
        assertTrue(picks.finished.isEmpty())
    }

    @Test
    fun `begin reports cancellation when the first configuration is dismissed`() = runTest {
        val repository = RecordingRepository(
            nextSave = ChainedUpstreamSaveResult.Rejected(
                ChainedUpstreamSecretStaging.Failure.PUBLISH_FAILED,
            ),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Cancelled)

        val outcome = VpnFirstConfigurationCoordinator(owner, picks).beginOrImport("e1", 0L)

        assertEquals(VpnFirstConfigurationBegin.Cancelled, outcome)
        assertTrue(repository.savedRotations.isEmpty())
    }

    @Test
    fun `begin reports unavailable when no presentation is registered`() = runTest {
        val repository = RecordingRepository()
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Unavailable)

        val outcome = VpnFirstConfigurationCoordinator(owner, picks).beginOrImport("e1", 0L)

        assertEquals(VpnFirstConfigurationBegin.Unavailable, outcome)
        assertTrue(repository.savedRotations.isEmpty())
    }

    @Test
    fun `begin rejects a picked payload the owner cannot parse`() = runTest {
        val repository = RecordingRepository(
            nextSave = ChainedUpstreamSaveResult.Rejected(
                ChainedUpstreamSecretStaging.Failure.PUBLISH_FAILED,
            ),
        )
        val owner = ChainedConfigurationCommandOwner(repository) {
            throw ChainedConfException(ChainedConfRefusal.MalformedLine)
        }
        val picks = RecordingPickSource(VpnConfigurationPick.Read("not a configuration"))

        val outcome = VpnFirstConfigurationCoordinator(owner, picks).beginOrImport("e1", 0L)

        assertEquals(
            VpnFirstConfigurationBegin.Rejected(ChainedConfRefusal.MalformedLine),
            outcome,
        )
        assertTrue(repository.savedRotations.isEmpty())
    }

    @Test
    fun `edit stages the picked replacement for the single open row`() = runTest {
        val repository = RecordingRepository(
            initial = stored(configuration(), generation = 2L),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Read("replacement"))

        val coordinator = VpnFirstConfigurationCoordinator(owner, picks)
        assertTrue(coordinator.beginOrImport("e1", 2L) is VpnFirstConfigurationBegin.Draft)

        val outcome = coordinator.stagePickedReplacement("e1", 0)

        assertTrue(outcome is VpnPickedConfigurationEdit.Draft)
        assertTrue((outcome as VpnPickedConfigurationEdit.Draft).draft.changed)
        assertEquals(1, picks.calls)
        assertEquals(1, picks.finished.size)
        assertTrue(repository.savedRotations.isEmpty())
    }

    @Test
    fun `edit refuses a third row without prompting`() = runTest {
        val repository = RecordingRepository(
            initial = stored(configuration(), generation = 2L),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Read("replacement"))

        val coordinator = VpnFirstConfigurationCoordinator(owner, picks)
        coordinator.beginOrImport("e1", 2L)
        val outcome = coordinator.stagePickedReplacement("e1", 2)

        assertEquals(
            VpnPickedConfigurationEdit.Refused(
                ChainedConfigurationEditorRefusal.INVALID_ROW,
            ),
            outcome,
        )
        assertEquals(0, picks.calls)
        assertTrue(picks.finished.isEmpty())
    }

    @Test
    fun `native name only edit retains the saved credential and cancels without publication`() = runTest {
        val repository = RecordingRepository(initial = stored(configuration(), 2))
        val owner = ChainedConfigurationCommandOwner(repository) { throw AssertionError("rename must not parse saved secrets") }
        val coordinator = VpnFirstConfigurationCoordinator(owner, RecordingPickSource(VpnConfigurationPick.Edited("Private name", null)))
        coordinator.beginOrImport("rename", 2)
        val result = coordinator.stagePickedReplacement("rename", 0) as VpnPickedConfigurationEdit.Draft
        assertEquals("Private name", result.draft.rows.single().name)
        assertTrue(result.draft.changed)
        assertTrue(repository.savedRotations.isEmpty())
        owner.cancelEditor("rename")
        assertEquals(configuration(), repository.loadConfiguration()?.configuration)
    }

    @Test
    fun `late native name callback cannot overwrite a newer draft revision`() = runTest {
        val repository = RecordingRepository(initial = stored(configuration(), 2))
        val owner = ChainedConfigurationCommandOwner(repository)
        owner.beginEditor("rename", 2)
        val opened = CompletableDeferred<Unit>()
        val returnFromSheet = CompletableDeferred<Unit>()
        val picks = VpnConfigurationPickSource {
            opened.complete(Unit)
            returnFromSheet.await()
            VpnConfigurationPick.Edited("Late name", null)
        }
        var result: VpnPickedConfigurationEdit? = null
        val request = launch { result = VpnFirstConfigurationCoordinator(owner, picks).stagePickedReplacement("rename", 0) }
        opened.await()
        owner.stageEditorName("rename", 0, "Current name")
        returnFromSheet.complete(Unit)
        request.join()
        assertEquals(VpnPickedConfigurationEdit.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION), result)
        assertEquals("Current name", owner.editorDraft("rename")?.rows?.single()?.name)
        assertTrue(repository.savedRotations.isEmpty())
    }

    @Test
    fun `edit cancels when the replacement presentation is dismissed`() = runTest {
        val repository = RecordingRepository(
            initial = stored(configuration(), generation = 2L),
        )
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Cancelled)
        val coordinator = VpnFirstConfigurationCoordinator(owner, picks)
        assertTrue(coordinator.beginOrImport("e1", 2L) is VpnFirstConfigurationBegin.Draft)

        assertEquals(VpnPickedConfigurationEdit.Cancelled, coordinator.stagePickedReplacement("e1", 0))
    }

    @Test
    fun `reopen failure compensates the exact published pending import`() = runTest {
        val repository = pendingRepository()
        repository.afterPublish = {
            repository.beforeLoad = {
                repository.beforeLoad = null
                throw IOException("reopen failed")
            }
        }
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val coordinator = VpnFirstConfigurationCoordinator(owner, RecordingPickSource(VpnConfigurationPick.Read("conf")))

        try {
            coordinator.beginOrImport("failed-reopen", 0)
            throw AssertionError("reopen failure must propagate")
        } catch (failure: IOException) {
            assertEquals("reopen failed", failure.message)
        }

        assertEquals(listOf("failed-reopen" to pendingIdentity()), repository.pendingRemovals)
        assertNull(repository.loadConfiguration())
        assertNull(owner.currentDraft())
    }

    @Test
    fun `cancellation during reopen compensates in an active noncancellable context`() = runTest {
        val repository = pendingRepository()
        val reopening = CompletableDeferred<Unit>()
        repository.afterPublish = {
            repository.beforeLoad = {
                repository.beforeLoad = null
                reopening.complete(Unit)
                awaitCancellation()
            }
        }
        val picks = RecordingPickSource(VpnConfigurationPick.Read("conf"))
        var cleanupFinished = false
        repository.beforePendingRemoval = {
            assertTrue("The native sheet must stay owned until exact pending rollback finishes", picks.finished.isEmpty())
            // A cleanup that inherits the cancelled editor job would fail at these suspensions.
            currentCoroutineContext().ensureActive()
            yield()
            currentCoroutineContext().ensureActive()
            cleanupFinished = true
        }
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val coordinator = VpnFirstConfigurationCoordinator(owner, picks)
        val request = launch { coordinator.beginOrImport("cancelled-reopen", 0) }
        reopening.await()
        assertEquals("cancelled-reopen", repository.loadConfiguration()?.pendingImportId)

        request.cancelAndJoin()

        assertTrue(request.isCancelled)
        assertTrue(cleanupFinished)
        assertEquals(1, picks.finished.size)
        assertEquals(listOf("cancelled-reopen" to pendingIdentity()), repository.pendingRemovals)
        assertNull(repository.loadConfiguration())
        assertNull(owner.currentDraft())
    }

    @Test
    fun `failed reopen cleanup retains pending ownership and retries without an open draft`() = runTest {
        val repository = pendingRepository()
        repository.pendingRemovalFailure = ChainedUpstreamPendingRemovalResult.Failed("key deletion failed")
        repository.afterPublish = {
            repository.beforeLoad = {
                repository.beforeLoad = null
                throw IOException("reopen failed")
            }
        }
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val coordinator = VpnFirstConfigurationCoordinator(owner, RecordingPickSource(VpnConfigurationPick.Read("conf")))
        try {
            coordinator.beginOrImport("retry-reopen", 0)
            throw AssertionError("reopen failure must propagate")
        } catch (_: IOException) { }
        assertEquals("retry-reopen", repository.loadConfiguration()?.pendingImportId)
        assertNull(owner.currentDraft())

        repository.pendingRemovalFailure = null
        assertEquals(ChainedUpstreamPendingRemovalResult.Removed,
            owner.cancelFirstImport("retry-reopen", pendingIdentity()))

        assertEquals(List(2) { "retry-reopen" to pendingIdentity() }, repository.pendingRemovals)
        assertNull(repository.loadConfiguration())
    }

    @Test
    fun `a saved successor survives compensation for a refused reopen`() = runTest {
        val repository = pendingRepository()
        val successor = stored(configuration(), generation = 2)
        repository.afterPublish = { repository.setStored(successor) }
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val coordinator = VpnFirstConfigurationCoordinator(owner, RecordingPickSource(VpnConfigurationPick.Read("conf")))

        assertEquals(VpnFirstConfigurationBegin.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION),
            coordinator.beginOrImport("superseded-reopen", 0))

        assertEquals(listOf("superseded-reopen" to pendingIdentity()), repository.pendingRemovals)
        assertEquals(successor, repository.loadConfiguration())
        assertNull(owner.currentDraft())
    }

    private class VisitAuthority {
        val original = Any()
        var current: Any? = original
        var alive = true
        fun isCurrent(): Boolean = alive && current === original
    }

    @Test
    fun `host replacement during the initial owner read cannot open a new host picker`() = runTest {
        val repository = pendingRepository()
        val authority = VisitAuthority()
        val reading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        repository.beforeLoad = {
            repository.beforeLoad = null
            reading.complete(Unit); release.await()
        }
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Read("conf"))
        val request = async { VpnFirstConfigurationCoordinator(owner, picks, isCurrent = authority::isCurrent)
            .beginOrImport("original-host", 0) }
        reading.await(); authority.current = Any(); release.complete(Unit)
        assertEquals(VpnFirstConfigurationBegin.Unavailable, request.await())
        assertEquals("No successor presentation is launched for the retired request", 0, picks.calls)
        assertEquals(0, repository.guardedSaveCalls); assertTrue(repository.savedRotations.isEmpty())
        assertNull(repository.loadConfiguration()); assertNull(owner.currentDraft())
    }

    @Test
    fun `host death during the post-picker absence read drops the imported candidate before save`() = runTest {
        val repository = pendingRepository(); val authority = VisitAuthority()
        val reading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var presentations = 0
        val picks = VpnConfigurationPickSource {
            ++presentations
            repository.beforeLoad = {
                repository.beforeLoad = null
                reading.complete(Unit); release.await()
            }
            VpnConfigurationPick.Read("conf")
        }
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val request = async { VpnFirstConfigurationCoordinator(owner, picks, isCurrent = authority::isCurrent)
            .beginOrImport("original-host", 0) }
        reading.await(); authority.alive = false; release.complete(Unit)
        assertEquals(VpnFirstConfigurationBegin.Unavailable, request.await())
        assertEquals(1, presentations); assertEquals(0, repository.guardedSaveCalls)
        assertTrue(repository.savedRotations.isEmpty()); assertNull(repository.loadConfiguration())
        assertNull(owner.currentDraft())
    }

    @Test
    fun `guarded first import checks original host after suspended repository admission and accepts only live return`() = runTest {
        listOf("death", "replacement", "absent", "same-host-return").forEach { change ->
            val repository = pendingRepository(); val authority = VisitAuthority()
            val writing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            repository.beforeGuardedWrite = { writing.complete(Unit); release.await() }
            val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
            val request = async {
                VpnFirstConfigurationCoordinator(owner, RecordingPickSource(VpnConfigurationPick.Read("conf")),
                    isCurrent = authority::isCurrent).beginOrImport("original-host", 0)
            }
            writing.await()
            when (change) {
                "death" -> { authority.alive = false; authority.current = authority.original }
                "replacement" -> authority.current = Any()
                "absent" -> authority.current = null
                "same-host-return" -> {
                    authority.current = null
                    assertFalse(authority.isCurrent())
                    authority.current = authority.original
                }
            }
            release.complete(Unit)
            val outcome = request.await()
            assertEquals(1, repository.guardedSaveCalls)
            if (change == "same-host-return") {
                assertTrue(outcome is VpnFirstConfigurationBegin.Draft)
                assertTrue((outcome as VpnFirstConfigurationBegin.Draft).imported)
                assertEquals("original-host", repository.loadConfiguration()?.pendingImportId)
                assertEquals(1, repository.savedRotations.size)
            } else {
                assertEquals(change, VpnFirstConfigurationBegin.Unavailable, outcome)
                assertTrue(repository.savedRotations.isEmpty()); assertNull(repository.loadConfiguration())
                assertNull(owner.currentDraft())
                authority.current = authority.original
                // Returning a live original permits a fresh request; it never adopts the old one.
                if (authority.alive) {
                    repository.beforeGuardedWrite = null
                    val fresh = VpnFirstConfigurationCoordinator(owner,
                        RecordingPickSource(VpnConfigurationPick.Read("fresh")), isCurrent = authority::isCurrent)
                        .beginOrImport("fresh-host", 0)
                    assertTrue(fresh is VpnFirstConfigurationBegin.Draft)
                    assertEquals("fresh-host", repository.loadConfiguration()?.pendingImportId)
                }
            }
        }
    }

    @Test
    fun `host loss after guarded publication compensates the exact pending import`() = runTest {
        val repository = pendingRepository(); val authority = VisitAuthority()
        repository.afterPublish = { authority.current = Any() }
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val result = VpnFirstConfigurationCoordinator(owner,
            RecordingPickSource(VpnConfigurationPick.Read("conf")), isCurrent = authority::isCurrent)
            .beginOrImport("original-host", 0)
        assertEquals(VpnFirstConfigurationBegin.Unavailable, result)
        assertEquals(1, repository.guardedSaveCalls)
        assertEquals(listOf("original-host" to pendingIdentity()), repository.pendingRemovals)
        assertNull(repository.loadConfiguration()); assertNull(owner.currentDraft())
    }

    @Test
    fun `configured old host read cannot publish an editor over a fresh successor draft`() = runTest {
        val entry = configuration(endpointHost = "192.0.2.1").named("Entry")
        val exit = configuration(endpointHost = "192.0.2.2").named("Exit")
        val committed = stored(exit.withEntryHop(entry), generation = 5)
        val repository = RecordingRepository(initial = committed)
        val owner = ChainedConfigurationCommandOwner(repository)
        val authority = VisitAuthority()
        val reading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        repository.beforeLoad = {
            // Only A's real read is blocked. B must be able to open its own saved editor.
            repository.beforeLoad = null
            reading.complete(Unit); release.await()
        }
        val oldPicks = RecordingPickSource(VpnConfigurationPick.Read("old-host-conf"))
        val oldRequest = async {
            VpnFirstConfigurationCoordinator(owner, oldPicks, isCurrent = authority::isCurrent)
                .beginOrImport("old-host", 5)
        }
        reading.await()
        val successorHost = Any(); authority.current = successorHost
        val newPicks = RecordingPickSource(VpnConfigurationPick.Read("new-host-conf"))
        val fresh = VpnFirstConfigurationCoordinator(owner, newPicks,
            isCurrent = { authority.current === successorHost }).beginOrImport("new-host", 5)
        assertTrue(fresh is VpnFirstConfigurationBegin.Draft)
        assertEquals(listOf("Entry", "Exit"), (fresh as VpnFirstConfigurationBegin.Draft).draft.rows.map { it.name })
        assertTrue(owner.stageEditorName("new-host", 0, "Successor entry") is ChainedConfigurationEditorResult.Draft)
        val swapped = owner.swapEditorRows("new-host") as ChainedConfigurationEditorResult.Draft
        val successor = swapped.draft
        assertEquals("new-host", successor.id); assertEquals(5L, successor.generation)
        assertEquals(2L, successor.revision); assertTrue(successor.changed)
        assertEquals(listOf("Exit", "Successor entry"), successor.rows.map { it.name })
        release.complete(Unit)
        assertEquals(VpnFirstConfigurationBegin.Unavailable, oldRequest.await())
        assertEquals("Old read must not replace any successor snapshot fields", successor, owner.currentDraft())
        assertEquals(successor, owner.editorDraft("new-host")); assertNull(owner.editorDraft("old-host"))
        assertEquals(0, oldPicks.calls); assertEquals(0, newPicks.calls)
        assertEquals(0, repository.guardedSaveCalls); assertTrue(repository.savedRotations.isEmpty())
        assertEquals(committed, repository.loadConfiguration())
    }

    @Test
    fun `every returned pick is acknowledged after cancellation absence parse rejection and stale draft refusal`() = runTest {
        for (editing in listOf(false, true)) {
            for (selected in listOf(VpnConfigurationPick.Cancelled, VpnConfigurationPick.Unavailable,
                    VpnConfigurationPick.ImportFailed(VpnConfigurationPick.ImportFailureReason.TOO_LARGE),
                    VpnConfigurationPick.ImportFailed(VpnConfigurationPick.ImportFailureReason.NOT_TEXT),
                    VpnConfigurationPick.Read("bad"), VpnConfigurationPick.Edited("Renamed", "bad"))) {
                val initial = if (editing) stored(configuration(), generation = 2L) else null
                val repository = RecordingRepository(initial = initial)
                var parses = 0
                val owner = ChainedConfigurationCommandOwner(repository) {
                    ++parses
                    throw ChainedConfException(ChainedConfRefusal.MalformedLine)
                }
                val picks = RecordingPickSource(selected)
                val coordinator = VpnFirstConfigurationCoordinator(owner, picks)
                val outcome: Any = if (editing) {
                    assertTrue(coordinator.beginOrImport("row", 2L) is VpnFirstConfigurationBegin.Draft)
                    coordinator.stagePickedReplacement("row", 0)
                } else coordinator.beginOrImport("first", 0L)
                assertSame(selected, picks.finished.single())
                assertEquals(1, picks.calls)
                assertTrue(repository.savedRotations.isEmpty())
                assertEquals(initial, repository.loadConfiguration())
                if (selected is VpnConfigurationPick.ImportFailed) {
                    assertEquals(0, parses); assertEquals(0, repository.guardedSaveCalls)
                    assertEquals(initial?.configuration, repository.loadConfiguration()?.configuration)
                }
                when (selected) {
                    VpnConfigurationPick.Cancelled -> assertEquals(if (editing) VpnPickedConfigurationEdit.Cancelled else VpnFirstConfigurationBegin.Cancelled, outcome)
                    VpnConfigurationPick.Unavailable, is VpnConfigurationPick.ImportFailed -> assertEquals(if (editing) VpnPickedConfigurationEdit.Unavailable else VpnFirstConfigurationBegin.Unavailable, outcome)
                    else -> assertTrue(if (editing) outcome is VpnPickedConfigurationEdit.Rejected else outcome is VpnFirstConfigurationBegin.Rejected)
                }
            }
        }
        // A stale exact draft revision after the picker returns is a refusal, still acknowledged.
        val repository = RecordingRepository(initial = stored(configuration(), 2L))
        val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
        val picks = RecordingPickSource(VpnConfigurationPick.Edited("Changed", null))
        val coordinator = VpnFirstConfigurationCoordinator(owner, picks, revalidate = {
            owner.stageEditorName("row", 0, "Newer native edit")
            true
        })
        coordinator.beginOrImport("row", 2L)
        val outcome = coordinator.stagePickedReplacement("row", 0)
        assertEquals(VpnPickedConfigurationEdit.Refused(ChainedConfigurationEditorRefusal.STALE_GENERATION), outcome)
        assertEquals("Newer native edit", owner.currentDraft()?.rows?.single()?.name)
        assertEquals(1, picks.finished.size)
    }

    @Test
    fun `post selection revalidation denial exception and coroutine cancellation all release only the returned pick`() = runTest {
        for (editing in listOf(false, true)) for (failure in listOf("denied", "exception", "cancelled")) supervisorScope {
            val initial = if (editing) stored(configuration(), generation = 2L) else null
            val repository = RecordingRepository(initial = initial)
            val owner = ChainedConfigurationCommandOwner(repository) { rotation() }
            val selected = VpnConfigurationPick.Edited("Named", "conf")
            val picks = RecordingPickSource(selected)
            val checking = CompletableDeferred<Unit>()
            val coordinator = VpnFirstConfigurationCoordinator(owner, picks, revalidate = {
                checking.complete(Unit)
                when (failure) {
                    "exception" -> throw IOException("reviewed owner unavailable")
                    "cancelled" -> awaitCancellation()
                    else -> false
                }
            })
            if (editing) coordinator.beginOrImport("row", 2L)
            val request = async {
                if (editing) coordinator.stagePickedReplacement("row", 0)
                else coordinator.beginOrImport("first", 0L)
            }
            runCurrent()
            assertTrue(checking.isCompleted)
            when (failure) {
                "cancelled" -> {
                    assertFalse(request.isCompleted); assertTrue(picks.finished.isEmpty())
                    request.cancelAndJoin()
                }
                "exception" -> try { request.await(); throw AssertionError("The actual revalidation exception must propagate") }
                    catch (error: IOException) { assertEquals("reviewed owner unavailable", error.message) }
                else -> assertEquals(if (editing) VpnPickedConfigurationEdit.Unavailable else VpnFirstConfigurationBegin.Unavailable, request.await())
            }
            assertSame(selected, picks.finished.single())
            assertEquals(initial, repository.loadConfiguration())
            assertTrue(repository.savedRotations.isEmpty())
        }
    }

    private class RecordingPickSource(private val pick: VpnConfigurationPick) : VpnConfigurationPickSource {
        val finished = mutableListOf<VpnConfigurationPick>()
        override fun finishPick(pick: VpnConfigurationPick) {
            assertSame("Only the actual returned native selection may be acknowledged", this.pick, pick)
            finished += pick
        }
        var calls = 0
            private set

        override suspend fun pick(): VpnConfigurationPick {
            calls += 1
            return pick
        }
    }

    private class RecordingRepository(
        initial: StoredChainedUpstreamConfiguration? = null,
        var nextSave: ChainedUpstreamSaveResult =
            ChainedUpstreamSaveResult.Rejected(ChainedUpstreamSecretStaging.Failure.PUBLISH_FAILED),
    ) : ChainedUpstreamConfigurationRepository {
        private val storedFlow = MutableStateFlow(initial)
        val savedRotations = mutableListOf<ChainedUpstreamRotation>()
        /** Simulates a concurrent commit landing between the picker returning and the save. */
        var beforeSave: (() -> Unit)? = null
        var beforeGuardedWrite: (suspend () -> Unit)? = null
        var guardedSaveCalls = 0
        var afterPublish: (() -> Unit)? = null
        var beforeLoad: (suspend () -> Unit)? = null
        var beforePendingRemoval: (suspend () -> Unit)? = null
        var pendingRemovalFailure: ChainedUpstreamPendingRemovalResult? = null
        val pendingRemovals = mutableListOf<Pair<String, ChainedUpstreamIdentity>>()

        override val storedConfiguration: Flow<StoredChainedUpstreamConfiguration?> = storedFlow

        fun setStored(value: StoredChainedUpstreamConfiguration?) {
            storedFlow.value = value
        }

        override suspend fun loadConfiguration(): StoredChainedUpstreamConfiguration? {
            beforeLoad?.invoke()
            return storedFlow.value
        }

        override suspend fun save(
            rotation: ChainedUpstreamRotation,
            expectation: ChainedUpstreamSaveExpectation,
        ): ChainedUpstreamSaveResult = saveAdmitted(rotation, expectation, null)

        override suspend fun saveWhileCurrent(
            rotation: ChainedUpstreamRotation,
            expectation: ChainedUpstreamSaveExpectation,
            isCurrent: () -> Boolean,
        ): ChainedUpstreamSaveResult {
            ++guardedSaveCalls
            return saveAdmitted(rotation, expectation, isCurrent)
        }

        private suspend fun saveAdmitted(
            rotation: ChainedUpstreamRotation,
            expectation: ChainedUpstreamSaveExpectation,
            isCurrent: (() -> Boolean)?,
        ): ChainedUpstreamSaveResult {
            beforeSave?.invoke()
            if (isCurrent != null) beforeGuardedWrite?.invoke()
            if (isCurrent?.invoke() == false) return ChainedUpstreamSaveResult.Superseded
            if ((expectation == ChainedUpstreamSaveExpectation.Absent ||
                    expectation is ChainedUpstreamSaveExpectation.AbsentPendingImport) && storedFlow.value != null) {
                return ChainedUpstreamSaveResult.Superseded
            }
            if (expectation is ChainedUpstreamSaveExpectation.Generation &&
                storedFlow.value?.identity?.generation != expectation.generation
            ) {
                return ChainedUpstreamSaveResult.Superseded
            }
            savedRotations += rotation
            val result = nextSave
            if (result is ChainedUpstreamSaveResult.Committed) {
                storedFlow.value = StoredChainedUpstreamConfiguration(
                    configuration = rotation.configuration,
                    identity = result.identity,
                    savedAtEpochMillis = 1L,
                    pendingImportId = (expectation as? ChainedUpstreamSaveExpectation.AbsentPendingImport)?.editorId,
                )
                afterPublish?.invoke()
            }
            return result
        }

        override suspend fun usableSecretsGeneration(): Long? =
            storedFlow.value?.identity?.generation

        override suspend fun loadSecrets(): ChainedUpstreamSecretMaterial? = null

        override suspend fun deleteSecrets() = Unit

        override suspend fun removeAll() {
            storedFlow.value = null
        }

        override suspend fun removePendingImport(
            editorId: String,
            identity: ChainedUpstreamIdentity,
        ): ChainedUpstreamPendingRemovalResult {
            pendingRemovals += editorId to identity
            beforePendingRemoval?.invoke()
            pendingRemovalFailure?.let { return it }
            val current = storedFlow.value
            if (current == null || current.pendingImportId != editorId || current.identity != identity) {
                return ChainedUpstreamPendingRemovalResult.Superseded
            }
            storedFlow.value = null
            return ChainedUpstreamPendingRemovalResult.Removed
        }
    }

    private companion object {
        private val PRIVATE_KEY: String = base64Key(1)
        private val PEER_KEY: String = base64Key(33)
        private val PRESHARED_KEY: String = base64Key(65)

        private fun pendingIdentity() = ChainedUpstreamIdentity(1,
            canonicalChainedUpstreamConfigurationFingerprint(configuration()))

        private fun pendingRepository() = RecordingRepository(
            nextSave = ChainedUpstreamSaveResult.Committed(pendingIdentity()))

        private fun base64Key(seed: Int): String =
            Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + seed).toByte() })

        private fun configuration(endpointHost: String = "vpn.example.com"): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
            endpointHost = endpointHost,
            endpointPort = 51820,
            peerPublicKey = PEER_KEY,
            clientAddress = "10.7.0.2",
            allowedIPs = listOf("0.0.0.0/0"),
            persistentKeepaliveSeconds = 25,
            interfaceMTU = 1420,
            dnsAddresses = listOf("10.64.0.1"),
        )

        private fun rotation(): ChainedUpstreamRotation =
            ChainedUpstreamRotation.create(configuration(), PRIVATE_KEY, PRESHARED_KEY)

        private fun stored(
            configuration: ChainedUpstreamConfiguration,
            generation: Long,
        ): StoredChainedUpstreamConfiguration = StoredChainedUpstreamConfiguration(
            configuration = configuration,
            identity = ChainedUpstreamIdentity(
                generation = generation,
                fingerprint = canonicalChainedUpstreamConfigurationFingerprint(configuration),
            ),
            savedAtEpochMillis = 17L,
        )
    }
}
