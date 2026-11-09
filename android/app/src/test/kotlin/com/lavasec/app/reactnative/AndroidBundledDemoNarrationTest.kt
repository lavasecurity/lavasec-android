package com.lavasec.app.reactnative

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Controlled approval/decoder boundaries, with no claim of physical listening qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidBundledDemoNarrationTest {
    @Test fun `asset reader joins exact caption locale approval and source digest before reading audio`() {
        val fixture = Assets()
        val audio = fixture.reader().load("Caption 0", "en")!!
        assertArrayEquals(fixture.audio, audio.bytes)
        assertEquals(8.74, audio.durationSeconds, 0.0)
        assertEquals(listOf("source-manifest.json", "manifest.json", "scene0.en.ogg"), fixture.reads)
        assertNull(fixture.reader().load("Caption 0", "ja"))
        assertNull(fixture.reader().load("Caption 0 ", "en"))
    }

    @Test fun `production source pin rejects a self declared approval manifest`() {
        val fixture = Assets()
        assertNull(ApprovedDemoNarrationAssets(fixture.source()).load("Caption 0", "en"))
        assertEquals(listOf("source-manifest.json"), fixture.reads)
    }

    @Test fun `unqualified source and unapproved original clip refuse even with a matching byte pin`() {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.put("listeningQualified", false) },
            { it.getJSONArray("clips").getJSONObject(0).put("approved", false) },
            { it.getJSONArray("clips").getJSONObject(0).put("revision", "storytelling-rev-1") },
        )) {
            val fixture = Assets()
            mutate(fixture.original)
            fixture.repin()
            assertNull(fixture.reader().load("Caption 0", "en"))
            assertFalse(fixture.reads.any { it.endsWith(".ogg") })
        }
    }

    @Test fun `wrong schema source identity approval caption checksum path and duplicate derived rows refuse`() {
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("schemaVersion", 2) },
            { it.put("sourceGitSHA", "0".repeat(40)) },
            { it.put("sourceManifestSHA256", "0".repeat(64)) },
            { it.put("sourceListeningQualified", false) },
            { it.getJSONArray("clips").getJSONObject(0).put("approved", false) },
            { it.getJSONArray("clips").getJSONObject(0).put("text", "Foreign caption") },
            { it.getJSONArray("clips").getJSONObject(0).put("locale", "ja") },
            { it.getJSONArray("clips").getJSONObject(0).put("sourceSHA256", "0".repeat(64)) },
            { it.getJSONArray("clips").getJSONObject(0).put("file", "../foreign.ogg") },
            { it.getJSONArray("clips").getJSONObject(0).put("sourceFile", "missing.caf") },
            { it.getJSONArray("clips").getJSONObject(0).put("durationSeconds", 30.01) },
            { it.getJSONArray("clips").getJSONObject(0).put("durationSeconds", 8.75) },
            { it.getJSONArray("clips").getJSONObject(0).put("container", "caf") },
            { it.getJSONArray("clips").put(1, it.getJSONArray("clips").getJSONObject(0)) },
            { it.getJSONArray("clips").remove(129) },
        )
        for ((index, mutate) in mutations.withIndex()) {
            val fixture = Assets()
            mutate(fixture.derived)
            assertNull("Mutation $index", fixture.reader().load("Caption 0", "en"))
            assertFalse(fixture.reads.any { it.endsWith(".ogg") })
        }
    }

    @Test fun `corrupt missing oversized and non Ogg bytes cannot reach a decoder`() {
        for (bad in listOf(null, ByteArray(0), "OggS".toByteArray() + ByteArray(32),
                ByteArray(ApprovedDemoNarrationAssets.MAX_AUDIO_BYTES + 1))) {
            val fixture = Assets()
            fixture.suppliedAudio = bad
            assertNull(fixture.reader().load("Caption 0", "en"))
        }
        val fixture = Assets()
        fixture.suppliedAudio = ByteArray(50)
        fixture.derived.getJSONArray("clips").getJSONObject(0)
            .put("sha256", ApprovedDemoNarrationAssets.sha256(fixture.suppliedAudio!!))
        assertNull(fixture.reader().load("Caption 0", "en"))
    }

    @Test fun `prepare and start acceptance do not settle until this decoder actually completes`() {
        val fixture = Playback()
        val result = fixture.speak()
        val player = fixture.loaded()
        assertTrue(result.isEmpty())
        assertEquals(0, player.starts)
        player.prepared(8740)
        fixture.scheduler.drain()
        assertEquals(1, player.starts)
        assertTrue(result.isEmpty())
        player.completed(true)
        fixture.scheduler.drain()
        assertEquals(listOf(true), result)
        assertEquals(1, player.releases)
        fixture.scheduler.advance(31_000)
        assertEquals(listOf(true), result)
        fixture.close()
    }

    @Test fun `stop while loading cancels read and late bytes never create a decoder`() {
        val fixture = Playback()
        val result = fixture.speak()
        fixture.owner.stop()
        fixture.loader.requests.single().completed(AUDIO)
        fixture.scheduler.drain()
        assertEquals(listOf(false), result)
        assertTrue(fixture.loader.requests.single().cancelled)
        assertTrue(fixture.players.isEmpty())
        fixture.close()
    }

    @Test fun `retirement at decoder release is rechecked before reporting successful completion`() {
        val fixture = Playback()
        val result = fixture.speak()
        val player = fixture.loaded()
        player.prepared(8740)
        fixture.scheduler.drain()
        player.onRelease = { fixture.owner.stop() }
        player.completed(true)
        fixture.scheduler.drain()
        assertEquals(listOf(false), result)
        assertEquals(1, player.releases)
        fixture.close()
    }

    @Test fun `new scene and Activity replacement reject late prepare and completion from the old host`() {
        val fixture = Playback()
        val first = fixture.speak()
        val old = fixture.loaded()
        val second = fixture.speak("Second")
        old.prepared(8740)
        old.completed(true)
        fixture.scheduler.drain()
        assertEquals(listOf(false), first)
        assertEquals(0, old.starts)
        assertEquals(1, old.releases)
        assertTrue(second.isEmpty())
        val current = fixture.loaded()
        fixture.currentHost = Any()
        current.prepared(8740)
        fixture.scheduler.drain()
        assertEquals(0, current.starts)
        assertEquals(listOf(false), second)
        assertEquals(1, current.releases)
        fixture.close()
    }

    @Test fun `background and final module close release once and cannot be revived by callbacks`() {
        for (close in listOf(false, true)) {
            val fixture = Playback()
            val result = fixture.speak()
            val player = fixture.loaded()
            player.prepared(8740)
            fixture.scheduler.drain()
            if (close) fixture.owner.close() else {
                fixture.currentHost = null
                fixture.owner.hostUnavailable()
            }
            player.completed(true)
            fixture.scheduler.drain()
            assertEquals(listOf(false), result)
            assertEquals(1, player.releases)
            fixture.close()
            fixture.close()
            assertEquals(1, player.releases)
            assertEquals(1, fixture.loader.closes)
            assertEquals(listOf(false), fixture.speak())
        }
    }

    @Test fun `absent assets invalid actual duration refused start and decoder errors settle false`() {
        val absent = Playback()
        val absentResult = absent.speak()
        absent.loader.requests.single().completed(null)
        absent.scheduler.drain()
        assertEquals(listOf(false), absentResult)
        assertTrue(absent.players.isEmpty())
        absent.close()
        for (duration in listOf(0, -1, 30_001)) {
            val fixture = Playback()
            val result = fixture.speak()
            val player = fixture.loaded()
            player.prepared(duration)
            fixture.scheduler.drain()
            assertEquals(listOf(false), result)
            assertEquals(0, player.starts)
            assertEquals(1, player.releases)
            fixture.close()
        }
        for (startRefused in listOf(true, false)) {
            val fixture = Playback()
            val result = fixture.speak()
            val player = fixture.loaded()
            player.accepted = !startRefused
            player.prepared(8740)
            fixture.scheduler.drain()
            if (!startRefused) player.completed(false)
            fixture.scheduler.drain()
            assertEquals(listOf(false), result)
            assertEquals(1, player.releases)
            fixture.close()
        }
    }

    @Test fun `a missing load prepare or completion callback is bounded and closes owned resources`() {
        for (stage in 0..2) {
            val fixture = Playback()
            val result = fixture.speak()
            val player = if (stage == 0) null else fixture.loaded()
            if (stage == 2) { player!!.prepared(8740); fixture.scheduler.drain() }
            fixture.scheduler.advance(30_999)
            assertTrue(result.isEmpty())
            fixture.scheduler.advance(1)
            assertEquals(listOf(false), result)
            assertTrue(fixture.loader.requests.single().cancelled)
            assertEquals(if (player == null) 0 else 1, fixture.players.sumOf { it.releases })
            fixture.close()
        }
    }

    @Test fun `synchronous callbacks serialize after player installation and cancellation before dispatch wins`() {
        val fixture = Playback(synchronous = true)
        val result = fixture.speak()
        assertEquals(listOf(true), result)
        assertEquals(1, fixture.players.single().starts)
        assertEquals(1, fixture.players.single().releases)
        fixture.close()
        val retired = Playback()
        val result2 = mutableListOf<Boolean>()
        retired.owner.speak("Caption", "en", retired.host, result2::add)
        retired.owner.stop()
        retired.scheduler.drain()
        assertEquals(listOf(false), result2)
        assertTrue(retired.loader.requests.isEmpty())
        retired.close()
    }

    private class Assets {
        val audio = "OggS".toByteArray() + ByteArray(48) { (it + 1).toByte() }
        var suppliedAudio: ByteArray? = audio
        val reads = mutableListOf<String>()
        val original = JSONObject().put("schemaVersion", 1).put("listeningQualified", true)
            .put("clips", JSONArray().apply { repeat(130) { index -> put(JSONObject()
                .put("approved", true).put("file", "scene$index.en.caf")
                .put("sha256", ApprovedDemoNarrationAssets.sha256("source$index".toByteArray()))
                .put("sceneID", "scene$index").put("text", "Caption $index").put("locale", "en")
                .put("revision", "storytelling-rev-4").put("durationSeconds", 8.74)) } })
        val derived = JSONObject().put("schemaVersion", 1).put("sourceGitSHA", ApprovedDemoNarrationAssets.SOURCE_GIT_SHA)
            .put("sourceListeningQualified", true).put("clips", JSONArray().apply { repeat(130) { index ->
                put(JSONObject(original.getJSONArray("clips").getJSONObject(index).toString())
                    .put("file", "scene$index.en.ogg").put("sourceFile", "scene$index.en.caf")
                    .put("sourceSHA256", original.getJSONArray("clips").getJSONObject(index).getString("sha256"))
                    .put("sha256", ApprovedDemoNarrationAssets.sha256(audio)).put("codec", "opus").put("container", "ogg"))
            } })
        private var pin = ""
        init { repin() }
        fun repin() { pin = ApprovedDemoNarrationAssets.sha256(original.toString().toByteArray()); derived.put("sourceManifestSHA256", pin) }
        fun source() = DemoNarrationAssetSource { path, _ ->
            val file = path.substringAfterLast('/')
            reads += file
            when (file) {
                "source-manifest.json" -> original.toString().toByteArray()
                "manifest.json" -> derived.toString().toByteArray()
                else -> suppliedAudio ?: throw java.io.IOException("Missing controlled audio")
            }
        }
        fun reader() = ApprovedDemoNarrationAssets(source(), pin)
    }

    private class Playback(synchronous: Boolean = false) {
        val host = Any()
        var currentHost: Any? = host
        val scheduler = NarrationTestScheduler()
        val loader = Loader(synchronous)
        val players = mutableListOf<Player>()
        val owner = AndroidBundledDemoNarration(loader, DemoBundledPlayerFactory { prepared, completed ->
            Player(prepared, completed, synchronous).also { players += it }
        }, { it === currentHost }, scheduler)
        fun speak(text: String = "Caption") = mutableListOf<Boolean>().also {
            owner.speak(text, "en", host, it::add); scheduler.drain()
        }
        fun loaded(): Player {
            loader.requests.last().completed(AUDIO)
            scheduler.drain()
            return players.last()
        }
        fun close() { owner.close(); scheduler.drain() }
    }
    private class Loader(private val synchronous: Boolean) : DemoBundledAudioLoader {
        data class Request(val completed: (BundledDemoAudio?) -> Unit, var cancelled: Boolean = false)
        val requests = mutableListOf<Request>()
        var closes = 0
        override fun load(text: String, locale: String, completed: (BundledDemoAudio?) -> Unit): DemoNarrationCancellation {
            val request = Request(completed)
            requests += request
            if (synchronous) completed(AUDIO)
            return DemoNarrationCancellation { request.cancelled = true }
        }
        override fun close() { closes++ }
    }
    private class Player(val prepared: (Int) -> Unit, val completed: (Boolean) -> Unit, private val synchronous: Boolean) : DemoBundledPlayer {
        var accepted = true
        var starts = 0
        var releases = 0
        var onRelease: (() -> Unit)? = null
        override fun prepare(audio: ByteArray) { if (synchronous) prepared(8740) }
        override fun start(): Boolean { starts++; if (synchronous) completed(true); return accepted }
        override fun release() { releases++; onRelease?.invoke(); completed(false) }
    }
    private companion object { val AUDIO = BundledDemoAudio(ByteArray(48) { 1 }, 8.74) }
}

internal class NarrationTestScheduler : DemoNarrationScheduler {
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
