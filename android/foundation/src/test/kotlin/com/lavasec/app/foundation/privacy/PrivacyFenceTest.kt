package com.lavasec.app.foundation.privacy

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the privacy fence: an unconfigured fence collects nothing,
 * a write/query/export admitted before a disable/clear is fenced off, and a
 * mutation waits for an in-flight admitted operation.
 */
class PrivacyFenceTest {
    private val counts = PrivacyRetentionCategory.FILTERING_COUNTS
    private val network = PrivacyRetentionCategory.NETWORK_ACTIVITY

    @Test
    fun `an unconfigured fence is conservative and refuses writes`() {
        val fence = PrivacyFence()
        val admission = fence.admission()

        assertTrue(fence.retention().collectingCategories.isEmpty())
        assertEquals(0L, admission.version)
        assertNull(fence.record(admission, counts))
    }

    @Test
    fun `recording data is admitted and does not advance the version`() {
        val fence = PrivacyFence(PrivacyRetention.canonicalDefaults())
        val admission = fence.admission()

        val committed = fence.record(admission, counts)

        assertNotNull(committed)
        assertTrue(fence.retention().hasRetainedData(counts))
        assertEquals(
            "recording is not a privacy mutation",
            admission.version,
            fence.version(),
        )
    }

    @Test
    fun `a pre-clear admission cannot resurrect cleared data`() {
        val fence = PrivacyFence(PrivacyRetention.canonicalDefaults())
        val stale = fence.admission()
        assertNotNull(fence.record(stale, counts))

        fence.clear(PrivacyClearTarget.All)

        assertNull(fence.record(stale, counts))
        assertTrue(fence.retention().retainedCategories.isEmpty())
    }

    @Test
    fun `a pre-disable write is refused`() {
        val fence = PrivacyFence(PrivacyRetention.canonicalDefaults())
        val stale = fence.admission()

        fence.disable(network)

        assertNull(fence.record(stale, network))
    }

    @Test
    fun `a pre-mutation query or export admission is fenced`() {
        val fence = PrivacyFence(PrivacyRetention.canonicalDefaults())
        val stale = fence.admission()

        fence.disable(counts)

        assertNull(fence.read(stale) { it })
        assertNotNull(fence.read(fence.admission()) { it })
    }

    @Test
    fun `a mutation waits for an in-flight admitted operation and fences stale writes`() {
        val fence = PrivacyFence(PrivacyRetention.canonicalDefaults())
        val stale = fence.admission()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        val reader = Thread {
            fence.read(stale) { retention ->
                entered.countDown()
                release.await()
                retention
            }
        }
        reader.start()
        assertTrue("the admitted operation must enter the fence", entered.await(5, TimeUnit.SECONDS))

        val clearStarted = CountDownLatch(1)
        val clearedAdmission = AtomicReference<PrivacyAdmission>()
        val clearer = Thread {
            clearStarted.countDown()
            clearedAdmission.set(fence.clear(PrivacyClearTarget.All))
        }
        clearer.start()
        assertTrue(clearStarted.await(5, TimeUnit.SECONDS))

        Thread.sleep(150)
        assertNull(
            "the mutation must wait for the in-flight admitted operation",
            clearedAdmission.get(),
        )

        release.countDown()
        reader.join(5_000)
        clearer.join(5_000)

        assertEquals(stale.version + 1, clearedAdmission.get().version)
        assertNull(
            "an admission taken before the clear must not write after it",
            fence.record(stale, counts),
        )
    }
}
