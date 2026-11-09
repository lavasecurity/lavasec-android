package com.lavasec.app

import android.app.Application
import com.lavasec.app.billing.NativePlusProcessObserver
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ApplicationTerminationTest {
    @Test fun `teardown drains actual main looper cancellation while joining the owned observer`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val enteredMain = CompletableDeferred<Unit>()
        val finishedMain = AtomicBoolean(false)
        val observer = NativePlusProcessObserver(scope, MutableStateFlow(true), install = {}, loadProducts = {},
            refreshPurchases = {
                withContext(Dispatchers.Main) {
                    enteredMain.complete(Unit)
                    try { awaitCancellation() } finally { finishedMain.set(true) }
                }
            })
        try {
            awaitProcessTerminationForTest(enteredMain)
            val completion = CoroutineScope(Dispatchers.IO).async { observer.closeAndJoin() }
            awaitProcessTerminationForTest(completion)
            assertTrue(finishedMain.get())
            assertTrue(scope.coroutineContext[Job]!!.isCompleted)
        } finally {
            observer.close()
            awaitProcessTerminationForTest(CoroutineScope(Dispatchers.IO).async { observer.closeAndJoin() })
        }
    }

    @Test fun `teardown reports the actual cleanup failure instead of treating completion as success`() {
        val failure = IOException("durable cleanup failed")
        val completion = CompletableDeferred<Unit>().apply { completeExceptionally(failure) }
        val received = assertThrows(IOException::class.java) { awaitProcessTerminationForTest(completion) }
        // Coroutine stack recovery may copy an exception while retaining its original cause.
        assertEquals(failure.javaClass, received.javaClass)
        assertEquals(failure.message, received.message)
        assertTrue(generateSequence<Throwable>(received) { it.cause }.any { it === failure })
    }

    @Test fun `incomplete teardown fails within its deadline rather than abandoning work silently`() {
        val completion = CompletableDeferred<Unit>()
        val failure = assertThrows(AssertionError::class.java) {
            awaitProcessTerminationForTest(completion, timeoutMillis = 10)
        }
        assertTrue(failure.message!!.contains("did not complete"))
        assertFalse(completion.isCompleted)
        completion.complete(Unit)
    }
}
