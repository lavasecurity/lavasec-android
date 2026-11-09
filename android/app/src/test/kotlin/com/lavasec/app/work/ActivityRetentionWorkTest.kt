package com.lavasec.app.work

import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActivityRetentionWorkTest {
    @Test
    fun `terminal enqueue success is the first point that completes installation`() = runTest {
        val enqueueTerminal = CompletableDeferred<Unit>()
        val installationLoop = ActivityRetentionInstallationLoop(
            userUnlocked = { true },
            enqueueAndAwait = { enqueueTerminal.await() },
            awaitRetry = { error("retry must not run") },
        )

        val installation = requireNotNull(installationLoop.start(this))
        runCurrent()
        assertFalse(installation.isCompleted)

        enqueueTerminal.complete(Unit)
        installation.await()
        assertTrue(installation.isCompleted)
    }

    @Test
    fun `asynchronous enqueue failure retries and terminal success stops retrying`() = runTest {
        var enqueueAttempts = 0
        val retryEntered = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val installationLoop = ActivityRetentionInstallationLoop(
            userUnlocked = { true },
            enqueueAndAwait = {
                enqueueAttempts += 1
                if (enqueueAttempts == 1) error("asynchronous enqueue failure")
            },
            awaitRetry = {
                retryEntered.complete(Unit)
                releaseRetry.await()
            },
        )

        val installation = requireNotNull(installationLoop.startWithReceipt(this))
        retryEntered.await()
        assertEquals(1, enqueueAttempts)
        val firstAttemptFailure =
            runCatching { installation.firstAttemptReceipt.await() }.exceptionOrNull()
        assertEquals("asynchronous enqueue failure", firstAttemptFailure?.message)
        assertFalse(installation.completion.isCompleted)

        releaseRetry.complete(Unit)
        installation.completion.await()
        assertEquals(2, enqueueAttempts)
        runCurrent()
        assertEquals(2, enqueueAttempts)
    }

    @Test
    fun `persistent enqueue failure is terminal after exactly two attempts`() = runTest {
        val failureMessage = "persistent enqueue failure"
        var enqueueAttempts = 0
        var retryCalls = 0
        val installationLoop = ActivityRetentionInstallationLoop(
            userUnlocked = { true },
            enqueueAndAwait = {
                enqueueAttempts += 1
                error(failureMessage)
            },
            awaitRetry = {
                retryCalls += 1
                if (retryCalls > 1) {
                    throw AssertionError("retry budget exceeded")
                }
            },
        )
        val installationScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val installation = requireNotNull(installationLoop.start(installationScope))
            val failure = runCatching { installation.await() }.exceptionOrNull()

            assertEquals(failureMessage, failure?.message)
            assertEquals(2, enqueueAttempts)
            assertEquals(1, retryCalls)
            assertTrue(installation.isCompleted)
        } finally {
            installationScope.cancel()
        }
    }

    @Test
    fun `terminal installation failure permits a new run`() = runTest {
        var failing = true
        var enqueueAttempts = 0
        val installationLoop = ActivityRetentionInstallationLoop(
            userUnlocked = { true },
            enqueueAndAwait = {
                enqueueAttempts += 1
                if (failing) error("persistent enqueue failure")
            },
            awaitRetry = {},
        )
        val installationScope = CoroutineScope(coroutineContext + SupervisorJob())

        try {
            val failedRun = requireNotNull(installationLoop.start(installationScope))
            assertEquals(
                "persistent enqueue failure",
                runCatching { failedRun.await() }.exceptionOrNull()?.message,
            )
            assertEquals(2, enqueueAttempts)

            failing = false
            val replacementRun = requireNotNull(installationLoop.start(installationScope))
            assertFalse(failedRun === replacementRun)
            replacementRun.await()
            assertEquals(3, enqueueAttempts)
        } finally {
            installationScope.cancel()
        }
    }

    @Test
    fun `unlock receiver handoff always finishes once without awaiting process supervision`() {
        listOf<(CompletableDeferred<Unit>) -> Unit>(
            { it.complete(Unit) },
            { it.completeExceptionally(IllegalStateException("asynchronous process failure")) },
            { it.cancel(CancellationException("process installation cancelled")) },
        ).forEachIndexed { index, completeFirstAttempt ->
            val firstAttemptReceipt = CompletableDeferred<Unit>()
            var finishes = 0

            runActivityRetentionUnlockHandoff(
                scheduleInProcess = { firstAttemptReceipt },
                finishPendingResult = { finishes += 1 },
            )

            assertEquals("outcome $index must await its first-attempt receipt", 0, finishes)
            completeFirstAttempt(firstAttemptReceipt)
            assertEquals("outcome $index", 1, finishes)
            firstAttemptReceipt.cancel()
            assertEquals("later completion must not finish twice", 1, finishes)
        }

        var alreadyCompleteFinishes = 0
        runActivityRetentionUnlockHandoff(
            scheduleInProcess = { CompletableDeferred(Unit) },
            finishPendingResult = { alreadyCompleteFinishes += 1 },
        )
        assertEquals(1, alreadyCompleteFinishes)

        var synchronousFailureFinishes = 0
        val synchronousFailure = runCatching {
            runActivityRetentionUnlockHandoff(
                scheduleInProcess = { error("synchronous handoff failure") },
                finishPendingResult = { synchronousFailureFinishes += 1 },
            )
        }.exceptionOrNull()

        assertEquals("synchronous handoff failure", synchronousFailure?.message)
        assertEquals(1, synchronousFailureFinishes)
    }

    @Test
    fun `installation cancellation is not converted to retry`() = runTest {
        val cancellation = CancellationException("installation cancelled")
        var retryCalls = 0
        val installationLoop = ActivityRetentionInstallationLoop(
            userUnlocked = { true },
            enqueueAndAwait = { throw cancellation },
            awaitRetry = {
                retryCalls += 1
                throw AssertionError("cancellation reached retry boundary")
            },
        )

        val failure = runCatching {
            requireNotNull(installationLoop.start(this)).await()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(0, retryCalls)
    }

    @Test
    fun `concurrent startup and unlock triggers share one installation loop`() = runTest {
        val enqueueEntered = CompletableDeferred<Unit>()
        val releaseEnqueue = CompletableDeferred<Unit>()
        var enqueueAttempts = 0
        val installationLoop = ActivityRetentionInstallationLoop(
            userUnlocked = { true },
            enqueueAndAwait = {
                enqueueAttempts += 1
                enqueueEntered.complete(Unit)
                releaseEnqueue.await()
            },
            awaitRetry = { error("retry must not run") },
        )

        val startup = requireNotNull(installationLoop.start(this))
        enqueueEntered.await()
        val unlock = requireNotNull(installationLoop.start(this))

        assertTrue(startup === unlock)
        assertEquals(1, enqueueAttempts)
        releaseEnqueue.complete(Unit)
        startup.await()
    }

    @Test
    fun `periodic request is unique-policy compatible and unconstrained at twelve hours`() {
        val request = ActivityRetentionScheduler.periodicRequest()

        assertEquals(TimeUnit.HOURS.toMillis(12), request.workSpec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(2), request.workSpec.flexDuration)
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertFalse(request.workSpec.constraints.requiresCharging())
        assertFalse(request.workSpec.constraints.requiresDeviceIdle())
        assertEquals("lavasec-activity-retention", ActivityRetentionScheduler.UNIQUE_WORK_NAME)
        assertEquals(
            ExistingPeriodicWorkPolicy.KEEP,
            ActivityRetentionScheduler.EXISTING_WORK_POLICY,
        )
        assertTrue(ActivityRetentionScheduler.UNIQUE_WORK_NAME in request.tags)
    }

    @Test
    fun `locked and transient failures retry while successful pruning completes`() = runTest {
        var calls = 0
        assertEquals(
            ActivityPruneWorkOutcome.RETRY,
            runActivityPrune(
                userUnlocked = false,
                nowMillis = 10L,
                prune = {
                    calls += 1
                },
            ),
        )
        assertEquals(0, calls)

        assertEquals(
            ActivityPruneWorkOutcome.RETRY,
            runActivityPrune(
                userUnlocked = true,
                nowMillis = 11L,
                prune = {
                    calls += 1
                    error("runtime closing")
                },
            ),
        )
        assertEquals(1, calls)

        assertEquals(
            ActivityPruneWorkOutcome.SUCCESS,
            runActivityPrune(
                userUnlocked = true,
                nowMillis = 12L,
                prune = {
                    calls += 1
                    assertEquals(12L, it)
                },
            ),
        )
        assertEquals(2, calls)
    }

    @Test
    fun `worker cancellation is never converted into retry`() = runTest {
        val cancellation = CancellationException("stopped")
        val thrown = runCatching {
            runActivityPrune(
                userUnlocked = true,
                nowMillis = 1L,
                prune = { throw cancellation },
            )
        }.exceptionOrNull()

        assertTrue(thrown === cancellation)
    }
}
