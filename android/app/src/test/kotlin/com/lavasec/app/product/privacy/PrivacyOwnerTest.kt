package com.lavasec.app.product.privacy

import com.lavasec.app.platform.LoggingSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyOwnerTest {
    @Test fun `QA reset preserves original caller admission and reports committed cleanup after retirement`() = runTest {
        lateinit var subject: PrivacyOwner
        var calls = 0
        subject = PrivacyOwner(initialLogging = LoggingSettings(), setLogging = { PrivacyMutationResult.Completed },
            clear = { error("ordinary clear cannot serve QA reset") }, export = { LocalExportResult.Cancelled }, scope = backgroundScope,
            resetQa = { previous, generation, current ->
                calls++; assertEquals(LoggingSettings(), previous); assertEquals(8L, generation); assertTrue(current())
                subject.close(); PrivacyMutationResult.CommittedReconciliationRequired("cleanup interrupted")
            })
        assertEquals(PrivacyMutationResult.Cancelled, subject.resetForQa(LoggingSettings(false, false), 8) { true })
        assertEquals(0, calls)
        assertTrue(subject.resetForQa(LoggingSettings(), 8) { true } is PrivacyMutationResult.CommittedReconciliationRequired)
        assertEquals(1, calls)
        assertEquals(PrivacyMutationResult.Cancelled, subject.resetForQa(LoggingSettings(), 8) { true })
    }

    @Test
    fun `restore exposes the final durable result even when the owner retires after commit`() = runTest {
        lateinit var owner: PrivacyOwner
        owner = PrivacyOwner(
            initialLogging = LoggingSettings(),
            setLogging = { error("ordinary privacy mutation must not serve restore") },
            clear = { PrivacyMutationResult.Completed }, export = { LocalExportResult.Cancelled },
            scope = backgroundScope,
            restoreLogging = { _, isCurrent ->
                assertTrue(isCurrent())
                owner.close()
                PrivacyMutationResult.CommittedReconciliationRequired("generation retired after durable commit")
            },
        )
        assertTrue(owner.setLoggingForRestore(LoggingSettings(false, false, false, false)) { true }
            is PrivacyMutationResult.CommittedReconciliationRequired)
        assertEquals(PrivacyMutationResult.Cancelled, owner.setLoggingForRestore(LoggingSettings()) { true })
    }

    @Test
    fun `logging state changes only after an exact committed mutation`() = runTest {
        val initial = LoggingSettings(keepCounts = true, keepHistory = false)
        val desired = initial.copy(keepHistory = true)
        val owner = PrivacyOwner(
            initialLogging = initial,
            setLogging = { PrivacyMutationResult.Completed },
            clear = { PrivacyMutationResult.Completed },
            export = { LocalExportResult.Cancelled },
            scope = backgroundScope,
        )

        assertEquals(initial, owner.state.value.logging)
        assertTrue(owner.setLogging(desired))
        assertEquals(desired, owner.state.value.logging)

        val rejected = PrivacyOwner(
            initialLogging = initial,
            setLogging = { PrivacyMutationResult.Failed("rejected") },
            clear = { PrivacyMutationResult.Completed },
            export = { LocalExportResult.Cancelled },
            scope = backgroundScope,
        )
        assertTrue(rejected.setLogging(desired))
        assertEquals(initial, rejected.state.value.logging)

        val committed = PrivacyOwner(
            initialLogging = initial,
            setLogging = {
                PrivacyMutationResult.CommittedReconciliationRequired("reopen required")
            },
            clear = { PrivacyMutationResult.Completed },
            export = { LocalExportResult.Cancelled },
            scope = backgroundScope,
        )
        assertTrue(committed.setLogging(desired))
        assertEquals(desired, committed.state.value.logging)
        assertEquals("reopen required", committed.state.value.error)
    }

    @Test
    fun `owner serializes operations and publishes exact export handle`() = runTest {
        val release = CompletableDeferred<Unit>()
        val owner = PrivacyOwner(
            setLogging = { PrivacyMutationResult.Completed },
            clear = {
                release.await()
                PrivacyMutationResult.Completed
            },
            export = { LocalExportResult.Completed(LocalExportHandle("export-1")) },
            scope = backgroundScope,
        )
        val clear = async {
            owner.clear(LocalActivityClearSelection(countsAndUptime = true, domainHistory = true))
        }
        testScheduler.runCurrent()

        assertTrue(owner.state.value.inFlight)
        assertFalse(owner.export(LocalActivityExportOptions()))
        release.complete(Unit)
        assertTrue(clear.await())
        assertEquals(PrivacyMutationResult.Completed, owner.state.value.lastMutation)

        assertTrue(owner.export(LocalActivityExportOptions()))
        assertEquals(LocalExportHandle("export-1"), owner.state.value.exportHandle)
        assertNull(owner.state.value.error)
    }

    @Test
    fun `failure remains truthful and closed owner freezes publication`() = runTest {
        val owner = PrivacyOwner(
            setLogging = { PrivacyMutationResult.Failed("durable write failed") },
            clear = { PrivacyMutationResult.Completed },
            export = { LocalExportResult.Failed("archive failed") },
            scope = backgroundScope,
        )

        assertTrue(
            owner.setLogging(
                LoggingSettings(
                    keepCounts = false,
                    keepHistory = false,
                    keepNetworkActivity = false,
                ),
            ),
        )
        assertEquals("durable write failed", owner.state.value.error)
        assertTrue(owner.export(LocalActivityExportOptions()))
        assertEquals("archive failed", owner.state.value.error)
        val frozen = owner.state.value
        owner.close()

        assertFalse(owner.clear(LocalActivityClearSelection(true, true)))
        assertFalse(owner.export(LocalActivityExportOptions()))
        assertEquals(frozen, owner.state.value)
    }

    @Test
    fun `graph retirement cancels an in flight privacy mutation`() = runTest {
        val started = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val owner = PrivacyOwner(
            setLogging = { PrivacyMutationResult.Completed },
            clear = {
                started.complete(Unit)
                neverComplete.await()
                PrivacyMutationResult.Completed
            },
            export = { LocalExportResult.Cancelled },
            scope = backgroundScope,
        )
        val clear = async { owner.clear(LocalActivityClearSelection(true, true)) }
        started.await()

        owner.close()

        assertFalse(clear.await())
        assertFalse(owner.state.value.inFlight)
        assertNull(owner.state.value.lastMutation)
    }
}
