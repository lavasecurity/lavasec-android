package com.lavasec.app.reactnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real packaged-asset/native-decoder evidence; it does not establish speaker audibility. */
@RunWith(AndroidJUnit4::class)
class AndroidBundledDemoNarrationInstrumentationTest {
    @Test fun approvedPackagedClipActuallyCompletesWithOneReleasedNativeDecoder() {
        journey(cancel = false)
    }

    @Test fun foregroundClipCancellationSettlesFalseAndReleasesTheActualNativeDecoder() {
        journey(cancel = true)
    }

    private fun journey(cancel: Boolean) {
        val releases = AtomicInteger()
        val completions = AtomicInteger()
        ReactEmulatorJourney().use { hostJourney ->
            // Keep the actual shipping host alive until its native module, credential graph,
            // and Guard/Settings React routes are ready, including the immediate-cancel case.
            hostJourney.launch()
            val instrumentation = hostJourney.instrumentation
            val context = hostJourney.context
            val activity = hostJourney.requireActivity()
            // Missing assets must fail this qualification case, even though production can use TTS.
            val approved = ApprovedDemoNarrationAssets(AndroidDemoNarrationAssetSource(context)).load(TEXT, "en")
            assertNotNull("The exact approved packaged narration clip is absent or invalid", approved)
            val started = CountDownLatch(1)
            val settled = CountDownLatch(1)
            val result = AtomicReference<Boolean?>()
            val starts = AtomicInteger()
            var owner: AndroidBundledDemoNarration? = null
            try {
                instrumentation.runOnMainSync {
                    val playback = AndroidBundledDemoNarration(AndroidDemoBundledAudioLoader(context),
                        DemoBundledPlayerFactory { prepared, completed ->
                            val actual = AndroidDemoBundledPlayer(prepared, completed)
                            object : DemoBundledPlayer {
                                override fun prepare(audio: ByteArray) = actual.prepare(audio)
                                override fun start(): Boolean = actual.start().also {
                                    if (it) {
                                        assertNull("Decoder start must not claim completed narration", result.get())
                                        starts.incrementAndGet()
                                        started.countDown()
                                        // Cancel at the real start edge, avoiding a race with a busy test thread.
                                        if (cancel) requireNotNull(owner).stop()
                                    }
                                }
                                override fun release() { actual.release(); releases.incrementAndGet() }
                            }
                        }, { host -> host === activity && activity.foreground &&
                            !activity.isFinishing && !activity.isDestroyed }, ExploreNarrationMainScheduler())
                    owner = playback
                    playback.speak(TEXT, "en", activity) {
                        result.set(it)
                        completions.incrementAndGet()
                        settled.countDown()
                    }
                }
                assertTrue("The real packaged Opus decoder did not start", started.await(15, TimeUnit.SECONDS))
                assertTrue("Native narration did not settle within its deadline", settled.await(35, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertEquals(!cancel, result.get())
                assertEquals(1, starts.get())
                assertEquals(1, releases.get())
                assertEquals(1, completions.get())
            } finally {
                instrumentation.runOnMainSync { owner?.close() }
                instrumentation.waitForIdleSync()
            }
        }
        assertEquals("Closing the native owner and shipping host must not double release or settle", 1, releases.get())
        assertEquals(1, completions.get())
    }

    private companion object {
        const val TEXT = "It all starts here, on your device. Before it opens anything, it asks one question: where does this website live?"
    }
}
