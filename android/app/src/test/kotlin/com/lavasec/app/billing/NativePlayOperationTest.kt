package com.lavasec.app.billing

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NativePlayOperationTest {
    @Test fun `SDK timeout is retryable failure instead of cancelling the worker`() = runTest {
        val failure = runCatching { awaitPlayOperation(10) { delay(20) } }.exceptionOrNull()
        assertTrue(failure is PlayBillingUnavailable)
    }

    @Test fun `an enclosing worker deadline stays cancellation`() = runTest {
        val failure = runCatching {
            withTimeout(10) { awaitPlayOperation(100) { delay(20) } }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
    }

    @Test fun `completed SDK read keeps its value`() = runTest {
        assertEquals("result", awaitPlayOperation(10) { "result" })
    }
}
