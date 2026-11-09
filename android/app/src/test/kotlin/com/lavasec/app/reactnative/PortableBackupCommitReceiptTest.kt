package com.lavasec.app.reactnative

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PortableBackupCommitReceiptTest {
    @Test fun `cancellation after durable pending commit preserves completed receipt`() = runTest {
        var receipt: PortableBackupResult? = null
        val dispatcher = StandardTestDispatcher(testScheduler, "file commit")
        val operation = launch {
            val caller = coroutineContext[kotlinx.coroutines.Job]!!
            val committed = withPortableBackupCommitReceipt(dispatcher) { active ->
                assertTrue(active())
                // The atomic file replacement durably finished at this point.
                caller.cancel()
                assertFalse(active())
                true
            }
            receipt = if (committed) PortableBackupResult("completed", "backup-import-pending") else null
        }
        runCurrent()
        assertTrue(operation.isCancelled)
        assertEquals(PortableBackupResult("completed", "backup-import-pending", applied = false), receipt)
    }

    @Test fun `cancellation after durable restore preserves applied receipt for upload scheduling`() = runTest {
        var receipt: PortableBackupResult? = null
        val operation = launch {
            val caller = coroutineContext[kotlinx.coroutines.Job]!!
            receipt = withPortableBackupCommitReceipt(StandardTestDispatcher(testScheduler, "restore commit")) {
                // The transactional publisher returned durable Applied, then its host retired.
                caller.cancel()
                PortableBackupResult("completed", applied = true)
            }
        }
        runCurrent()
        assertTrue(operation.isCancelled)
        assertEquals(PortableBackupResult("completed", applied = true), receipt)
    }

    @Test fun `cancellation while commit IO is queued refuses before any mutation`() = runTest {
        var writes = 0
        val operation = launch(UnconfinedTestDispatcher(testScheduler)) {
            withPortableBackupCommitReceipt(StandardTestDispatcher(testScheduler, "queued commit")) {
                writes++
            }
        }
        operation.cancel()
        runCurrent()
        assertTrue(operation.isCancelled)
        assertEquals(0, writes)
    }
}
