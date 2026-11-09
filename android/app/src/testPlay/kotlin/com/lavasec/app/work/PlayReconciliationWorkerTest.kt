package com.lavasec.app.work

import com.lavasec.app.billing.PlayPurchaseReadStatus
import com.lavasec.app.billing.PlayPurchaseSnapshot
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.lifecycle.LiveData
import androidx.work.Operation
import androidx.work.await
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlayReconciliationWorkerTest {
    @Test fun `unconfigured installation does not create WorkManager or its database operation`() = runTest {
        installPlayReconciliationIfConfigured(false) { error("unconfigured build must not initialize WorkManager") }
    }

    @Test fun `installation preserves durable enqueue failures instead of reporting success`() = runTest {
        val failure = java.io.IOException("enqueue refused")
        val operation = PendingOperation().apply { completion.setException(failure) }
        val received = try {
            installPlayReconciliationIfConfigured(true) { operation.await() }
            null
        } catch (error: java.io.IOException) { error }
        // Coroutine stack recovery may copy an exception while retaining its original cause.
        assertEquals(failure.javaClass, received?.javaClass)
        assertEquals(failure.message, received?.message)
        org.junit.Assert.assertTrue(generateSequence<Throwable>(received) { it.cause }.any { it === failure })
    }

    @Test fun `cancelled installation drains already submitted operation and never returns success`() = runTest {
        val operation = PendingOperation()
        var submitted = false
        var drained = false
        var installed = false
        val installing = launch {
            installPlayReconciliationIfConfigured(true) {
                submitted = true
                operation.await()
                drained = true
            }
            installed = true
        }
        runCurrent()
        org.junit.Assert.assertTrue(submitted)
        installing.cancel()
        runCurrent()
        org.junit.Assert.assertFalse(installing.isCompleted)
        org.junit.Assert.assertFalse("cancelling an observer must not abandon the enqueue result", operation.result.isCancelled)
        operation.completion.set(Operation.SUCCESS)
        installing.join()
        org.junit.Assert.assertTrue(drained)
        org.junit.Assert.assertTrue(installing.isCancelled)
        org.junit.Assert.assertFalse(installed)
    }

    @Test fun `installation does not conceal platform linkage failures`() = runTest {
        val failure = UnsatisfiedLinkError("platform database unavailable")
        val operation = PendingOperation().apply { completion.setException(failure) }
        val received = try {
            installPlayReconciliationIfConfigured(true) { operation.await() }
            null
        } catch (error: UnsatisfiedLinkError) { error }
        assertEquals(failure.javaClass, received?.javaClass)
        assertEquals(failure.message, received?.message)
        org.junit.Assert.assertTrue(generateSequence<Throwable>(received) { it.cause }.any { it === failure })
    }

    /** Real WorkManager Operation.await/future contract; no production database or worker thread. */
    private class PendingOperation : Operation {
        lateinit var completion: CallbackToFutureAdapter.Completer<Operation.State.SUCCESS>
        private val future = CallbackToFutureAdapter.getFuture<Operation.State.SUCCESS> {
            completion = it
            "controlled durable enqueue"
        }
        override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = future
        override fun getState(): LiveData<Operation.State> = error("await must use the durable terminal result")
    }

    @Test fun `durable job requires connectivity and carries no receipts`() {
        val request = PlayReconciliationScheduler.periodicRequest()
        assertEquals(java.util.concurrent.TimeUnit.HOURS.toMillis(6), request.workSpec.intervalDuration)
        assertEquals(androidx.work.NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(java.util.concurrent.TimeUnit.MINUTES.toMillis(15), request.workSpec.backoffDelayDuration)
        assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        org.junit.Assert.assertTrue(request.workSpec.input.keyValueMap.isEmpty())
    }

    @Test fun `locked and unconfigured workers never access receipts`() = runTest {
        assertEquals(PlayReconciliationOutcome.RETRY,
            reconcilePlayPurchases(false, true) { error("private storage must stay closed") })
        assertEquals(PlayReconciliationOutcome.SUCCESS,
            reconcilePlayPurchases(true, false) { error("no network on an unconfigured build") })
    }

    @Test fun `acknowledgement failure retries even after confirmed verification`() = runTest {
        assertEquals(PlayReconciliationOutcome.RETRY, reconcilePlayPurchases(true, true) {
            PlayPurchaseSnapshot(PlayPurchaseReadStatus.CHECKED, acknowledgementPending = true)
        })
        assertEquals(PlayReconciliationOutcome.SUCCESS, reconcilePlayPurchases(true, true) {
            PlayPurchaseSnapshot(PlayPurchaseReadStatus.CHECKED)
        })
    }

    @Test fun `pending uncertain and unavailable owners are retried`() = runTest {
        for (status in PlayPurchaseReadStatus.entries.filter { it != PlayPurchaseReadStatus.CHECKED }) {
            assertEquals(status.name, PlayReconciliationOutcome.RETRY,
                reconcilePlayPurchases(true, true) { PlayPurchaseSnapshot(status) })
        }
        assertEquals(PlayReconciliationOutcome.RETRY, reconcilePlayPurchases(true, true) { null })
        assertEquals(PlayReconciliationOutcome.RETRY, reconcilePlayPurchases(true, true) { throw java.io.IOException() })
    }

    @Test fun `worker cancellation propagates instead of requesting another retry`() {
        assertThrows(CancellationException::class.java) {
            runTest { reconcilePlayPurchases(true, true) { throw CancellationException() } }
        }
    }
}
