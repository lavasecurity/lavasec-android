package com.lavasec.app.reactnative

import org.junit.Assert.*
import org.junit.Test

class AndroidExploreNarrationTest {
    @Test fun `completed approved clip resolves true without synthesizing another voice`() {
        val fixture = Fixture()
        val result = fixture.speak()
        assertTrue(result.isEmpty())
        fixture.clip.requests.single().completed(true)
        fixture.scheduler.drain()
        assertEquals(listOf(true), result)
        assertTrue(fixture.speech.requests.isEmpty())
        fixture.close()
    }

    @Test fun `a refused clip falls back once with the exact caption locale and initiating host`() {
        val fixture = Fixture()
        val result = fixture.speak()
        val clip = fixture.clip.requests.single()
        clip.completed(false)
        fixture.scheduler.drain()
        val speech = fixture.speech.requests.single()
        assertEquals("Approved caption", speech.text)
        assertEquals("zh-Hant", speech.locale)
        assertSame(fixture.host, speech.host)
        assertTrue(result.isEmpty())
        clip.completed(true)
        clip.completed(false)
        fixture.scheduler.drain()
        assertTrue(result.isEmpty())
        assertEquals(1, fixture.speech.requests.size)
        speech.completed(true)
        fixture.scheduler.drain()
        assertEquals(listOf(true), result)
        fixture.close()
    }

    @Test fun `unavailable local speech returns false and leaves the reading clock contract intact`() {
        val fixture = Fixture()
        val result = fixture.speak()
        fixture.clip.requests.single().completed(false)
        fixture.scheduler.drain()
        fixture.speech.requests.single().completed(false)
        fixture.scheduler.drain()
        assertEquals(listOf(false), result)
        fixture.close()
    }

    @Test fun `stop requested before an already queued clip failure prevents fallback`() {
        val fixture = Fixture()
        val result = fixture.speak()
        fixture.clip.requests.single().completed(false)
        fixture.owner.stop()
        fixture.scheduler.drain()
        assertEquals(listOf(false), result)
        assertTrue(fixture.speech.requests.isEmpty())
        fixture.close()
    }

    @Test fun `a superseding scene prevents old clip failure from speaking and old speech from settling`() {
        for (fallbackStarted in listOf(false, true)) {
            val fixture = Fixture()
            val first = fixture.speak()
            val oldClip = fixture.clip.requests.single()
            if (fallbackStarted) { oldClip.completed(false); fixture.scheduler.drain() }
            val oldSpeech = fixture.speech.requests.firstOrNull()
            val second = fixture.speak("Next")
            oldClip.completed(false)
            oldSpeech?.completed?.invoke(true)
            fixture.scheduler.drain()
            assertEquals(listOf(false), first)
            assertTrue(second.isEmpty())
            assertEquals(if (fallbackStarted) 1 else 0, fixture.speech.requests.size)
            fixture.clip.requests.last().completed(true)
            fixture.scheduler.drain()
            assertEquals(listOf(true), second)
            fixture.close()
        }
    }

    @Test fun `recreated or background host cannot adopt a pending clip or fallback result`() {
        for (fallbackStarted in listOf(false, true)) {
            val fixture = Fixture()
            val result = fixture.speak()
            if (fallbackStarted) { fixture.clip.requests.single().completed(false); fixture.scheduler.drain() }
            fixture.currentHost = Any()
            if (fallbackStarted) fixture.speech.requests.single().completed(true)
            else fixture.clip.requests.single().completed(false)
            fixture.scheduler.drain()
            assertEquals(listOf(false), result)
            if (!fallbackStarted) assertTrue(fixture.speech.requests.isEmpty())
            fixture.owner.hostUnavailable()
            fixture.scheduler.drain()
            assertEquals(1, fixture.clip.hostRetirements)
            assertEquals(1, fixture.speech.hostRetirements)
            fixture.close()
        }
    }

    @Test fun `module closure is immediate final and settles once even when stop callbacks are synchronous`() {
        val fixture = Fixture()
        val result = fixture.speak()
        fixture.owner.close()
        fixture.clip.requests.single().completed(false)
        val after = fixture.speak("Late")
        fixture.close()
        assertEquals(listOf(false), result)
        assertEquals(listOf(false), after)
        assertTrue(fixture.speech.requests.isEmpty())
        assertEquals(1, fixture.clip.closes)
        assertEquals(1, fixture.speech.closes)
        assertEquals(1, fixture.clip.requests.size)
    }

    @Test fun `a request retired before dispatch never starts either audio boundary`() {
        val fixture = Fixture()
        val result = mutableListOf<Boolean>()
        fixture.owner.speak("Caption", "en", fixture.host, result::add)
        fixture.owner.stop()
        fixture.scheduler.drain()
        assertEquals(listOf(false), result)
        assertTrue(fixture.clip.requests.isEmpty())
        assertTrue(fixture.speech.requests.isEmpty())
        fixture.close()
    }

    private class Fixture {
        val host = Any()
        var currentHost: Any? = host
        val scheduler = NarrationTestScheduler()
        val clip = Playback()
        val speech = Playback()
        val owner = AndroidExploreNarration(clip, speech, { it === currentHost }, scheduler)
        fun speak(text: String = "Approved caption") = mutableListOf<Boolean>().also {
            owner.speak(text, "zh-Hant", host, it::add); scheduler.drain()
        }
        fun close() { owner.close(); scheduler.drain() }
    }
    private class Playback : ExploreNarrationPlayback {
        data class Request(val text: String, val locale: String, val host: Any, val completed: (Boolean) -> Unit)
        val requests = mutableListOf<Request>()
        var active: Request? = null
        var hostRetirements = 0
        var closes = 0
        override fun speak(text: String, locale: String, host: Any, completion: (Boolean) -> Unit) {
            Request(text, locale, host, completion).also { requests += it; active = it }
        }
        override fun stop() { val old = active; active = null; old?.completed?.invoke(false) }
        override fun hostUnavailable() { hostRetirements++; stop() }
        override fun close() { closes++; stop() }
    }
}
