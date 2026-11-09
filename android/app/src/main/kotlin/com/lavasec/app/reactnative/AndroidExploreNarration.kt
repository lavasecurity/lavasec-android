package com.lavasec.app.reactnative

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface ExploreNarrationPlayback {
    fun speak(text: String, locale: String, host: Any, completion: (Boolean) -> Unit)
    fun stop()
    fun hostUnavailable()
    fun close()
}

internal class ExploreNarrationMainScheduler : DemoNarrationScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(action: () -> Unit) { handler.post(action) }
    override fun after(milliseconds: Long, action: () -> Unit): DemoNarrationCancellation {
        val callback = Runnable(action)
        handler.postDelayed(callback, milliseconds)
        return DemoNarrationCancellation { handler.removeCallbacks(callback) }
    }
}

/** A clip failure can fall back once; retirement can never launch speech for an old scene. */
internal class AndroidExploreNarration(
    private val bundled: ExploreNarrationPlayback,
    private val speech: ExploreNarrationPlayback,
    private val hostIsCurrent: (Any) -> Boolean,
    private val scheduler: DemoNarrationScheduler,
) : ExploreNarrationPlayback {
    constructor(context: Context, hostIsCurrent: (Any) -> Boolean) : this(
        context, hostIsCurrent, ExploreNarrationMainScheduler(),
    )
    private constructor(context: Context, hostIsCurrent: (Any) -> Boolean, scheduler: DemoNarrationScheduler) : this(
        AndroidBundledDemoNarration(context, hostIsCurrent, scheduler),
        SystemDemoNarrationPlayback(AndroidDemoNarration(context, hostIsCurrent)),
        hostIsCurrent, scheduler,
    )
    private data class Attempt(val ticket: Long, val host: Any, val completion: (Boolean) -> Unit, var fallback: Boolean = false)
    private val generation = AtomicLong()
    private val closed = AtomicBoolean(false)
    private var pending: Attempt? = null

    override fun speak(text: String, locale: String, host: Any, completion: (Boolean) -> Unit) {
        val ticket = generation.incrementAndGet()
        scheduler.post {
            retire()
            if (closed.get() || generation.get() != ticket || !hostIsCurrent(host)) {
                completion(false)
                return@post
            }
            val attempt = Attempt(ticket, host, completion)
            pending = attempt
            bundled.speak(text, locale, host) { success -> scheduler.post clipCompleted@{
                if (pending !== attempt) return@clipCompleted
                if (attempt.fallback) return@clipCompleted
                if (!current(attempt)) { finish(attempt, false); return@clipCompleted }
                if (success) finish(attempt, true)
                else if (!attempt.fallback) {
                    attempt.fallback = true
                    try {
                        speech.speak(text, locale, host) { spoken -> scheduler.post {
                            if (pending === attempt && attempt.fallback) finish(attempt, spoken && current(attempt))
                        } }
                    } catch (_: Exception) { finish(attempt, false) }
                }
            } }
        }
    }
    override fun stop() { generation.incrementAndGet(); scheduler.post { retire() } }
    override fun hostUnavailable() {
        generation.incrementAndGet()
        scheduler.post { retire(); bundled.hostUnavailable(); speech.hostUnavailable() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        scheduler.post { retire(); bundled.close(); speech.close() }
    }
    private fun current(attempt: Attempt) = !closed.get() && generation.get() == attempt.ticket && hostIsCurrent(attempt.host)
    private fun retire() {
        val previous = pending
        pending = null
        bundled.stop()
        speech.stop()
        previous?.completion?.invoke(false)
    }
    private fun finish(attempt: Attempt, success: Boolean) {
        if (pending !== attempt) return
        pending = null
        attempt.completion(success && current(attempt))
    }
    private class SystemDemoNarrationPlayback(private val speech: AndroidDemoNarration) : ExploreNarrationPlayback {
        override fun speak(text: String, locale: String, host: Any, completion: (Boolean) -> Unit) = speech.speak(text, locale, host, completion)
        override fun stop() = speech.stop()
        override fun hostUnavailable() = speech.hostUnavailable()
        override fun close() = speech.close()
    }
}
