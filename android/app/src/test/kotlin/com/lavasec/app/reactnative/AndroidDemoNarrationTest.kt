package com.lavasec.app.reactnative

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Controlled installed-engine boundary; these assertions do not claim physical audibility. */
class AndroidDemoNarrationTest {
    @Test fun `enqueue does not succeed until the exact utterance finishes`() {
        val fixture = Fixture()
        val result = fixture.speak("First")
        val engine = fixture.ready()
        assertTrue(result.isEmpty())
        assertEquals("First", engine.requests.single().text)
        engine.done("foreign", true)
        fixture.scheduler.drain()
        assertTrue(result.isEmpty())
        engine.done(engine.requests.single().id, true)
        fixture.scheduler.drain()
        assertEquals(listOf(true), result)
        fixture.scheduler.advance(30_000)
        assertEquals(listOf(true), result)
        fixture.close()
        assertEquals(1, engine.shutdowns)
    }

    @Test fun `a newer scene cancels once and a late old completion cannot settle it`() {
        val fixture = Fixture()
        val first = fixture.speak("First")
        val engine = fixture.ready()
        val oldID = engine.requests.single().id
        val second = fixture.speak("Second")
        assertEquals(listOf(false), first)
        assertEquals(2, engine.requests.size)
        val newID = engine.requests.last().id
        assertNotEquals(oldID, newID)
        engine.done(oldID, true)
        fixture.scheduler.drain()
        assertTrue(second.isEmpty())
        engine.done(newID, true)
        fixture.scheduler.drain()
        assertEquals(listOf(true), second)
        fixture.close()
    }

    @Test fun `stop before initialization releases binding and late init cannot start speech`() {
        val fixture = Fixture()
        val first = fixture.speak()
        val oldEngine = fixture.engines.single()
        fixture.owner.stop()
        fixture.scheduler.drain()
        assertEquals(listOf(false), first)
        assertEquals(1, oldEngine.shutdowns)
        val second = fixture.speak("Second")
        oldEngine.initialize(true)
        fixture.scheduler.drain()
        assertTrue(oldEngine.requests.isEmpty())
        assertTrue(fixture.engines.last().requests.isEmpty())
        assertTrue(second.isEmpty())
        val next = fixture.ready()
        next.done(next.requests.single().id, true)
        fixture.scheduler.drain()
        assertEquals(listOf(true), second)
        fixture.close()
    }

    @Test fun `stop during speech cancels and ignores synchronous stop and subsequent completion`() {
        val fixture = Fixture()
        val result = fixture.speak()
        val engine = fixture.ready()
        engine.completeDuringStop = true
        fixture.owner.stop()
        fixture.scheduler.drain()
        engine.done(engine.requests.single().id, true)
        fixture.scheduler.drain()
        assertEquals(listOf(false), result)
        fixture.close()
        assertEquals(1, engine.shutdowns)
    }

    @Test fun `background or host destruction cancels and shuts down with no late success`() {
        for (duringInitialization in listOf(true, false)) {
            val fixture = Fixture()
            val result = fixture.speak()
            val engine = if (duringInitialization) fixture.engines.single() else fixture.ready()
            fixture.currentHost = null
            fixture.owner.hostUnavailable()
            fixture.scheduler.drain()
            engine.initialize(true)
            engine.requests.firstOrNull()?.let { engine.done(it.id, true) }
            fixture.scheduler.drain()
            assertEquals(listOf(false), result)
            assertEquals(1, engine.shutdowns)
            fixture.close()
            assertEquals(1, engine.shutdowns)
        }
    }

    @Test fun `Activity replacement cannot rebind initialization or a completed utterance`() {
        for (duringInitialization in listOf(true, false)) {
            val fixture = Fixture()
            val result = fixture.speak()
            val engine = if (duringInitialization) fixture.engines.single() else fixture.ready()
            fixture.currentHost = Any()
            if (duringInitialization) engine.initialize(true) else engine.done(engine.requests.single().id, true)
            fixture.scheduler.drain()
            assertEquals(listOf(false), result)
            if (duringInitialization) assertTrue(engine.requests.isEmpty())
            assertEquals(1, engine.shutdowns)
            fixture.close()
        }
    }

    @Test fun `missing callback is bounded during both initialization and speech`() {
        for (duringInitialization in listOf(true, false)) {
            val fixture = Fixture()
            val result = fixture.speak()
            val engine = if (duringInitialization) fixture.engines.single() else fixture.ready()
            fixture.scheduler.advance(29_999)
            assertTrue(result.isEmpty())
            fixture.scheduler.advance(1)
            assertEquals(listOf(false), result)
            assertEquals(1, engine.shutdowns)
            engine.initialize(true)
            engine.requests.firstOrNull()?.let { engine.done(it.id, true) }
            fixture.scheduler.drain()
            assertEquals(listOf(false), result)
            fixture.close()
        }
    }

    @Test fun `failed init enqueue and synthesis refuse instead of claiming completion`() {
        val failedInit = Fixture()
        val initialization = failedInit.speak()
        failedInit.engines.single().initialize(false)
        failedInit.scheduler.drain()
        assertEquals(listOf(false), initialization)
        assertTrue(failedInit.engines.single().requests.isEmpty())
        failedInit.close()

        val failedEnqueue = Fixture()
        val enqueue = failedEnqueue.speak()
        failedEnqueue.engines.single().accepted = false
        val refusedEngine = failedEnqueue.ready()
        assertEquals(listOf(false), enqueue)
        assertEquals(1, refusedEngine.shutdowns)
        failedEnqueue.close()

        val failedSpeech = Fixture()
        val speech = failedSpeech.speak()
        val engine = failedSpeech.ready()
        engine.done(engine.requests.single().id, false)
        failedSpeech.scheduler.drain()
        assertEquals(listOf(false), speech)
        assertEquals(1, engine.shutdowns)
        failedSpeech.close()
    }

    @Test fun `synchronous factory init is serialized after the engine is installed`() {
        val fixture = Fixture(synchronousInitialization = true)
        val result = fixture.speak()
        val engine = fixture.engines.single()
        assertEquals(1, engine.requests.size)
        assertTrue(result.isEmpty())
        engine.done(engine.requests.single().id, true)
        fixture.scheduler.drain()
        assertEquals(listOf(true), result)
        fixture.close()
    }

    @Test fun `module close is final releases once and refuses callbacks and new speech`() {
        val fixture = Fixture()
        val result = fixture.speak()
        val engine = fixture.ready()
        fixture.owner.close()
        val afterClose = mutableListOf<Boolean>()
        fixture.owner.speak("Late", "en", fixture.host, afterClose::add)
        engine.done(engine.requests.single().id, true)
        fixture.scheduler.drain()
        fixture.close()
        assertEquals(listOf(false), result)
        assertEquals(listOf(false), afterClose)
        assertEquals(1, engine.shutdowns)
        assertEquals(1, engine.requests.size)
    }

    @Test fun `blank oversized invalid locale and absent foreground host do not bind an engine`() {
        for ((text, locale) in listOf(" " to "en", "x".repeat(4001) to "en", "Caption" to "invalid_locale_!", "Caption" to "")) {
            val fixture = Fixture()
            assertEquals(listOf(false), fixture.speak(text, locale))
            assertTrue(fixture.engines.isEmpty())
            fixture.close()
        }
        val fixture = Fixture()
        fixture.currentHost = null
        assertEquals(listOf(false), fixture.speak())
        assertTrue(fixture.engines.isEmpty())
        fixture.close()
    }

    @Test fun `absent local voice network voice and unfinished download never enqueue`() {
        for (voices in listOf(emptyList(), listOf(VOICE.copy(requiresNetwork = true)),
                listOf(VOICE.copy(installed = false)), listOf(VOICE.copy(locale = Locale.JAPANESE)))) {
            val fixture = Fixture()
            val result = fixture.speak()
            fixture.engines.single().availableVoices = voices
            val engine = fixture.ready()
            assertEquals(listOf(false), result)
            assertTrue(engine.requests.isEmpty())
            assertEquals(1, engine.shutdowns)
            fixture.close()
        }
    }

    @Test fun `installed same-language quality wins and exact locale breaks quality ties`() {
        val exact = VOICE.copy(name = "exact", locale = Locale.US)
        val other = exact.copy(name = "other", locale = Locale.UK)
        val network = exact.copy(name = "network", quality = 500, requiresNetwork = true)
        val missing = exact.copy(name = "missing", quality = 600, installed = false)
        assertEquals(exact, selectDemoSpeechVoice(listOf(other, exact, network, missing), Locale.US))
        assertEquals(other.copy(quality = 400), selectDemoSpeechVoice(listOf(exact, other.copy(quality = 400)), Locale.US))
        assertNull(selectDemoSpeechVoice(listOf(exact), Locale.JAPANESE))
        val traditional = exact.copy(name = "traditional", locale = Locale.forLanguageTag("zh-Hant"))
        val simplified = traditional.copy(name = "simplified", locale = Locale.forLanguageTag("zh-Hans"))
        assertEquals(traditional, selectDemoSpeechVoice(listOf(simplified, traditional), Locale.forLanguageTag("zh-Hant")))
    }

    private class Fixture(synchronousInitialization: Boolean = false) {
        val host = Any()
        var currentHost: Any? = host
        val scheduler = Scheduler()
        val engines = mutableListOf<Engine>()
        val owner = AndroidDemoNarration(DemoSpeechEngineFactory { initialized, completed ->
            Engine(initialized, completed).also { engines += it; if (synchronousInitialization) initialized(true) }
        }, { it === currentHost }, scheduler)
        fun speak(text: String = "Caption", locale: String = "en"): MutableList<Boolean> =
            mutableListOf<Boolean>().also { result -> owner.speak(text, locale, host, result::add); scheduler.drain() }
        fun ready(): Engine = engines.last().also { it.initialize(true); scheduler.drain() }
        fun close() { owner.close(); scheduler.drain() }
    }

    private data class Request(val text: String, val voice: DemoSpeechVoice, val id: String)

    private class Engine(val initialize: (Boolean) -> Unit, val done: (String, Boolean) -> Unit) : DemoSpeechEngine {
        var availableVoices = listOf(VOICE)
        var accepted = true
        var completeDuringStop = false
        var shutdowns = 0
        val requests = mutableListOf<Request>()
        override fun voices() = availableVoices
        override fun speak(text: String, voice: DemoSpeechVoice, utteranceID: String): Boolean {
            requests += Request(text, voice, utteranceID)
            return accepted
        }
        override fun stop() { if (completeDuringStop) requests.lastOrNull()?.let { done(it.id, false) } }
        override fun shutdown() { ++shutdowns }
    }

    private class Scheduler : DemoNarrationScheduler {
        private data class Deadline(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private var now = 0L
        private val queued = ArrayDeque<() -> Unit>()
        private val deadlines = mutableListOf<Deadline>()
        override fun post(action: () -> Unit) { queued.addLast(action) }
        override fun after(milliseconds: Long, action: () -> Unit): DemoNarrationCancellation {
            val deadline = Deadline(now + milliseconds, action)
            deadlines += deadline
            return DemoNarrationCancellation { deadline.cancelled = true }
        }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
        fun advance(milliseconds: Long) {
            now += milliseconds
            val due = deadlines.filter { it.at <= now }
            deadlines.removeAll(due.toSet())
            due.filterNot { it.cancelled }.forEach { post(it.action) }
            drain()
        }
    }

    private companion object {
        val VOICE = DemoSpeechVoice("local", Locale.ENGLISH, 300, requiresNetwork = false, installed = true)
    }
}
