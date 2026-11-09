package com.lavasec.app

import android.os.Looper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/** Same production owners; keep their sandbox alive until cancellation and durable IO finish. */
class DrainingLavasecTestApplication : LavasecApplication() {
    override fun onTerminate() {
        try { awaitProcessTerminationForTest(beginProcessTermination()) }
        finally { super.onTerminate() }
    }
}

internal fun awaitProcessTerminationForTest(completion: Deferred<Unit>, timeoutMillis: Long = 60_000) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!completion.isCompleted) {
        // Joining a cancelled withContext(Main) from the test's main thread would deadlock.
        // Run the actual queued cancellation continuations while the IO drain remains alive.
        shadowOf(Looper.getMainLooper()).idle()
        if (completion.isCompleted) break
        if (System.nanoTime() >= deadline) throw AssertionError("Application cleanup did not complete within ${timeoutMillis}ms")
        Thread.sleep(1)
    }
    // Completion has already been observed. This cannot block the main looper; it propagates
    // the actual cleanup error instead of turning a failed/partial teardown into a passing test.
    runBlocking { completion.await() }
}
