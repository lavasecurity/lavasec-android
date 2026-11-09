package com.lavasec.app.reactnative

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class DemoSpeechVoice(
    val name: String,
    val locale: Locale,
    val quality: Int,
    val requiresNetwork: Boolean,
    val installed: Boolean,
)

/** Public installed voices only; never request a voice download or a network voice. */
internal fun selectDemoSpeechVoice(voices: List<DemoSpeechVoice>, locale: Locale): DemoSpeechVoice? =
    voices.filter { it.installed && !it.requiresNetwork && it.locale.language == locale.language }
        .sortedWith(compareByDescending<DemoSpeechVoice> { it.quality }
            .thenByDescending { it.locale.toLanguageTag() == locale.toLanguageTag() }
            .thenBy { it.name })
        .firstOrNull()

internal interface DemoSpeechEngine {
    fun voices(): List<DemoSpeechVoice>
    /** Accepted enqueue is not completion. Only the matching onDone may report success. */
    fun speak(text: String, voice: DemoSpeechVoice, utteranceID: String): Boolean
    fun stop()
    fun shutdown()
}

internal fun interface DemoSpeechEngineFactory {
    fun create(initialized: (Boolean) -> Unit, completed: (String, Boolean) -> Unit): DemoSpeechEngine
}

internal fun interface DemoNarrationCancellation { fun cancel() }

internal interface DemoNarrationScheduler {
    /** Always enqueue, including when called on main, so synchronous init cannot beat creation. */
    fun post(action: () -> Unit)
    fun after(milliseconds: Long, action: () -> Unit): DemoNarrationCancellation
}

private class MainDemoNarrationScheduler : DemoNarrationScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun post(action: () -> Unit) { handler.post(action) }
    override fun after(milliseconds: Long, action: () -> Unit): DemoNarrationCancellation {
        val callback = Runnable(action)
        handler.postDelayed(callback, milliseconds)
        return DemoNarrationCancellation { handler.removeCallbacks(callback) }
    }
}

/**
 * Generation-owned local system narration, matching the frozen iOS system-speech fallback.
 * No caption is logged, persisted, uploaded, or synthesized to a file. Missing local voice data
 * returns false, keeping Explore's reading clock usable. All engine callbacks are serialized on
 * main; late initialization/completion cannot start or settle another scene or Activity.
 */
internal class AndroidDemoNarration(
    private val factory: DemoSpeechEngineFactory,
    private val hostIsCurrent: (Any) -> Boolean,
    private val scheduler: DemoNarrationScheduler,
) {
    constructor(context: Context, hostIsCurrent: (Any) -> Boolean) : this(
        DemoSpeechEngineFactory { initialized, completed ->
            AndroidTextToSpeechEngine(context.applicationContext, initialized, completed)
        }, hostIsCurrent, MainDemoNarrationScheduler(),
    )

    private data class Attempt(
        val generation: Long,
        val host: Any,
        val text: String,
        val locale: Locale,
        val completion: (Boolean) -> Unit,
        var deadline: DemoNarrationCancellation? = null,
        var utteranceID: String? = null,
    )

    private val closed = AtomicBoolean(false)
    private var generation = 0L
    private var engineEpoch = 0L
    private var engine: DemoSpeechEngine? = null
    private var initialized = false
    private var pending: Attempt? = null

    fun speak(text: String, locale: String, host: Any, completion: (Boolean) -> Unit) = scheduler.post {
        retire(releaseEngine = engine != null && !initialized)
        val language = try { Locale.Builder().setLanguageTag(locale.replace('_', '-')).build() }
            catch (_: IllformedLocaleException) { null }
        if (closed.get() || !hostIsCurrent(host) || text.isBlank() || text.length > MAX_CAPTION_LENGTH ||
            language == null || language.language.isBlank() || language.language == "und") {
            completion(false)
            return@post
        }
        val attempt = Attempt(++generation, host, text, language, completion)
        pending = attempt
        attempt.deadline = scheduler.after(DEADLINE_MILLIS) {
            if (pending === attempt) retire(releaseEngine = true)
        }
        if (engine == null) {
            val epoch = ++engineEpoch
            try {
                engine = factory.create(
                    initialized = { success -> scheduler.post initializedCallback@{
                        if (epoch != engineEpoch || closed.get()) return@initializedCallback
                        if (!success) retire(releaseEngine = true)
                        else { initialized = true; startPending() }
                    } },
                    completed = { id, success -> scheduler.post completedCallback@{
                        if (epoch != engineEpoch) return@completedCallback
                        val current = pending ?: return@completedCallback
                        if (current.utteranceID != id) return@completedCallback
                        val completedHere = success && !closed.get() && hostIsCurrent(current.host)
                        if (completedHere) finish(current, true) else retire(releaseEngine = true)
                    } },
                )
            } catch (_: Exception) {
                retire(releaseEngine = true)
            }
        } else startPending()
    }

    fun stop() = scheduler.post { retire(releaseEngine = engine != null && !initialized) }

    fun hostUnavailable() = scheduler.post { retire(releaseEngine = true) }

    fun close() {
        closed.set(true)
        scheduler.post { retire(releaseEngine = true) }
    }

    private fun startPending() {
        val attempt = pending ?: return
        val currentEngine = engine ?: return
        if (!initialized || attempt.utteranceID != null) return
        if (closed.get() || !hostIsCurrent(attempt.host)) { retire(releaseEngine = true); return }
        try {
            val voice = selectDemoSpeechVoice(currentEngine.voices(), attempt.locale)
            if (voice == null) { retire(releaseEngine = true); return }
            val id = "lava-demo-${attempt.generation}"
            attempt.utteranceID = id
            if (!currentEngine.speak(attempt.text, voice, id)) retire(releaseEngine = true)
        } catch (_: Exception) {
            retire(releaseEngine = true)
        }
    }

    private fun finish(attempt: Attempt, success: Boolean) {
        if (pending !== attempt) return
        pending = null
        attempt.deadline?.cancel()
        attempt.completion(success)
    }

    private fun retire(releaseEngine: Boolean) {
        ++generation
        // Detach before stop: engines may synchronously report onStop for the old utterance.
        val previous = pending
        pending = null
        previous?.deadline?.cancel()
        val previousEngine = engine
        if (releaseEngine) {
            engine = null
            initialized = false
            ++engineEpoch
        }
        try { previousEngine?.stop() } catch (_: Exception) { /* Already refused/cancelled. */ }
        if (releaseEngine) try { previousEngine?.shutdown() } catch (_: Exception) { /* Service gone. */ }
        previous?.completion?.invoke(false)
    }

    private companion object {
        const val DEADLINE_MILLIS = 30_000L
        const val MAX_CAPTION_LENGTH = 4_000
    }
}

private class AndroidTextToSpeechEngine(
    context: Context,
    initialized: (Boolean) -> Unit,
    completed: (String, Boolean) -> Unit,
) : DemoSpeechEngine {
    private val tts = TextToSpeech(context) { status -> initialized(status == TextToSpeech.SUCCESS) }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { utteranceId?.let { completed(it, true) } }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) { utteranceId?.let { completed(it, false) } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { completed(it, false) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { completed(it, false) } }
        })
    }

    override fun voices(): List<DemoSpeechVoice> = tts.voices.orEmpty().map { voice ->
        DemoSpeechVoice(voice.name, voice.locale, voice.quality, voice.isNetworkConnectionRequired,
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty())
    }

    override fun speak(text: String, voice: DemoSpeechVoice, utteranceID: String): Boolean {
        // Recheck the public inventory at enqueue; do not let changed voice data fall back online.
        val selected = tts.voices.orEmpty().firstOrNull { it.name == voice.name &&
            !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            ?: return false
        if (tts.setVoice(selected) != TextToSpeech.SUCCESS) return false
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceID) == TextToSpeech.SUCCESS
    }

    override fun stop() { tts.stop() }
    override fun shutdown() { tts.shutdown() }
}
