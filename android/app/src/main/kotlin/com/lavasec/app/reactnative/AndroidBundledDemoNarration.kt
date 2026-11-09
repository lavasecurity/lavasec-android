package com.lavasec.app.reactnative

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class BundledDemoAudio(val bytes: ByteArray, val durationSeconds: Double)

internal fun interface DemoNarrationAssetSource {
    fun read(path: String, maximumBytes: Int): ByteArray
}

/** The original approval is verified independently of the derived container manifest. */
internal class ApprovedDemoNarrationAssets(
    private val source: DemoNarrationAssetSource,
    // Controlled asset-reader tests can pin their own approval bytes; production uses the frozen pin.
    private val sourceManifestSHA256: String = SOURCE_MANIFEST_SHA256,
) {
    fun load(text: String, locale: String): BundledDemoAudio? = try {
        val originalBytes = source.read("$DIRECTORY/source-manifest.json", MAX_MANIFEST_BYTES)
        require(originalBytes.size <= MAX_MANIFEST_BYTES && sha256(originalBytes) == sourceManifestSHA256)
        val original = JSONObject(originalBytes.toString(Charsets.UTF_8))
        require(original.getInt("schemaVersion") == 1 && original.getBoolean("listeningQualified"))
        val originals = original.getJSONArray("clips")
        require(originals.length() == 130)
        val approved = mutableMapOf<String, JSONObject>()
        val originalIdentities = mutableSetOf<Pair<String, String>>()
        for (index in 0 until originals.length()) {
            val row = originals.getJSONObject(index)
            val file = row.getString("file")
            require(safeFile(file, ".caf") && row.getBoolean("approved"))
            require(row.getString("revision") == "storytelling-rev-4")
            require(approved.put(file, row) == null)
            require(originalIdentities.add(row.getString("text") to row.getString("locale")))
        }

        val derivedBytes = source.read("$DIRECTORY/manifest.json", MAX_MANIFEST_BYTES)
        require(derivedBytes.size <= MAX_MANIFEST_BYTES)
        val derived = JSONObject(derivedBytes.toString(Charsets.UTF_8))
        require(derived.getInt("schemaVersion") == 1)
        require(derived.getString("sourceGitSHA") == SOURCE_GIT_SHA)
        require(derived.getString("sourceManifestSHA256") == sourceManifestSHA256)
        require(derived.getBoolean("sourceListeningQualified"))
        val clips = derived.getJSONArray("clips")
        require(clips.length() == originals.length())
        val files = mutableSetOf<String>()
        val sourceFiles = mutableSetOf<String>()
        val identities = mutableSetOf<Pair<String, String>>()
        var match: JSONObject? = null
        for (index in 0 until clips.length()) {
            val row = clips.getJSONObject(index)
            val file = row.getString("file")
            val sourceFile = row.getString("sourceFile")
            require(safeFile(file, ".ogg") && files.add(file) && sourceFiles.add(sourceFile))
            val basis = requireNotNull(approved[sourceFile])
            for (field in listOf("text", "locale", "sceneID", "revision")) {
                require(row.getString(field) == basis.getString(field))
            }
            require(row.getBoolean("approved") && row.getString("sourceSHA256") == basis.getString("sha256"))
            require(row.getString("codec") == "opus" && row.getString("container") == "ogg")
            require(row.getString("sha256").matches(Regex("[0-9a-f]{64}")))
            val duration = row.getDouble("durationSeconds")
            require(duration.isFinite() && duration > 0 && duration <= 30)
            require(duration == basis.getDouble("durationSeconds"))
            val identity = row.getString("text") to row.getString("locale")
            require(identities.add(identity))
            if (identity == (text to locale)) match = row
        }
        val row = match
        if (row == null) null else {
            val bytes = source.read("$DIRECTORY/${row.getString("file")}", MAX_AUDIO_BYTES)
            require(bytes.size in 28..MAX_AUDIO_BYTES && bytes.copyOfRange(0, 4).contentEquals("OggS".toByteArray(Charsets.US_ASCII)))
            require(sha256(bytes) == row.getString("sha256"))
            BundledDemoAudio(bytes, row.getDouble("durationSeconds"))
        }
    } catch (_: Exception) {
        // Missing, corrupt, unmatched or unapproved local assets leave system speech available.
        null
    }

    private fun safeFile(value: String, suffix: String) =
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}")) && value.endsWith(suffix)

    companion object {
        const val DIRECTORY = "ExploreNarration"
        const val SOURCE_GIT_SHA = "3bd86116f0d12595965df03cf634066d393b4896"
        const val SOURCE_MANIFEST_SHA256 = "e668885aa69245725d8c6ec3b458efd9b1a7fc792e910161d62e6882c920ffe3"
        const val MAX_MANIFEST_BYTES = 256 * 1024
        const val MAX_AUDIO_BYTES = 1024 * 1024
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

internal class AndroidDemoNarrationAssetSource(context: Context) : DemoNarrationAssetSource {
    private val assets = context.applicationContext.assets
    override fun read(path: String, maximumBytes: Int): ByteArray = assets.open(path).use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximumBytes) throw IOException("Narration asset exceeds its bound")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

internal interface DemoBundledAudioLoader {
    fun load(text: String, locale: String, completed: (BundledDemoAudio?) -> Unit): DemoNarrationCancellation
    fun close()
}

internal class AndroidDemoBundledAudioLoader(context: Context) : DemoBundledAudioLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val assets = ApprovedDemoNarrationAssets(AndroidDemoNarrationAssetSource(context))
    override fun load(text: String, locale: String, completed: (BundledDemoAudio?) -> Unit): DemoNarrationCancellation {
        val job = scope.launch {
            ensureActive()
            val audio = assets.load(text, locale)
            ensureActive()
            completed(audio)
        }
        return DemoNarrationCancellation { job.cancel() }
    }
    override fun close() { scope.cancel() }
}

internal interface DemoBundledPlayer {
    fun prepare(audio: ByteArray)
    fun start(): Boolean
    fun release()
}

internal fun interface DemoBundledPlayerFactory {
    fun create(prepared: (Int) -> Unit, completed: (Boolean) -> Unit): DemoBundledPlayer
}

/** Owns one decoder and data source. Never opens an arbitrary URI or writes temporary audio. */
internal class AndroidDemoBundledPlayer(
    prepared: (Int) -> Unit,
    completed: (Boolean) -> Unit,
) : DemoBundledPlayer {
    private val player = MediaPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        setOnPreparedListener { prepared(it.duration) }
        setOnCompletionListener { completed(true) }
        setOnErrorListener { _, _, _ -> completed(false); true }
    }
    private var data: NarrationDataSource? = null
    private var released = false
    override fun prepare(audio: ByteArray) {
        check(!released && data == null)
        data = NarrationDataSource(audio)
        player.setDataSource(requireNotNull(data))
        player.prepareAsync()
    }
    override fun start(): Boolean {
        check(!released)
        player.start()
        return true
    }
    override fun release() {
        if (released) return
        released = true
        player.setOnPreparedListener(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        try { player.release() } finally { data?.close(); data = null }
    }
    private class NarrationDataSource(private var bytes: ByteArray?) : MediaDataSource() {
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val current = bytes ?: throw IOException("Narration data source is closed")
            require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
            if (size == 0) return 0
            if (position >= current.size) return -1
            val count = minOf(size, current.size - position.toInt())
            current.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        @Synchronized override fun getSize(): Long = bytes?.size?.toLong() ?: throw IOException("Narration data source is closed")
        @Synchronized override fun close() { bytes = null }
    }
}

/** Completion means the current local clip actually ended, not that preparation was accepted. */
internal class AndroidBundledDemoNarration(
    private val loader: DemoBundledAudioLoader,
    private val factory: DemoBundledPlayerFactory,
    private val hostIsCurrent: (Any) -> Boolean,
    private val scheduler: DemoNarrationScheduler,
) : ExploreNarrationPlayback {
    constructor(context: Context, hostIsCurrent: (Any) -> Boolean, scheduler: DemoNarrationScheduler) : this(
        AndroidDemoBundledAudioLoader(context), DemoBundledPlayerFactory { prepared, completed ->
            AndroidDemoBundledPlayer(prepared, completed)
        }, hostIsCurrent, scheduler,
    )
    private data class Attempt(
        val ticket: Long, val host: Any, val completion: (Boolean) -> Unit,
        var loading: DemoNarrationCancellation? = null,
        var deadline: DemoNarrationCancellation? = null,
        var player: DemoBundledPlayer? = null,
        var started: Boolean = false,
    )
    private val generation = AtomicLong()
    private val closed = AtomicBoolean(false)
    private var pending: Attempt? = null

    override fun speak(text: String, locale: String, host: Any, completion: (Boolean) -> Unit) {
        val ticket = generation.incrementAndGet()
        scheduler.post {
            retire()
            if (closed.get() || generation.get() != ticket || !hostIsCurrent(host) ||
                text.isBlank() || text.length > 4000 || locale.isBlank()) {
                completion(false)
                return@post
            }
            val attempt = Attempt(ticket, host, completion)
            pending = attempt
            attempt.deadline = scheduler.after(31_000) { if (pending === attempt) finish(attempt, false) }
            try {
                attempt.loading = loader.load(text, locale) { audio -> scheduler.post loaded@{
                    if (pending !== attempt) return@loaded
                    if (!current(attempt) || audio == null || audio.bytes.isEmpty() ||
                        audio.bytes.size > ApprovedDemoNarrationAssets.MAX_AUDIO_BYTES ||
                        !audio.durationSeconds.isFinite() || audio.durationSeconds <= 0 || audio.durationSeconds > 30) {
                        finish(attempt, false)
                        return@loaded
                    }
                    try {
                        val player = factory.create(
                            prepared = { duration -> scheduler.post prepared@{
                                if (pending !== attempt) return@prepared
                                if (!current(attempt) || duration <= 0 || duration > 30_000 || attempt.started) {
                                    finish(attempt, false)
                                    return@prepared
                                }
                                try {
                                    attempt.started = true
                                    if (attempt.player?.start() != true) finish(attempt, false)
                                } catch (_: Exception) { finish(attempt, false) }
                            } },
                            completed = { success -> scheduler.post {
                                if (pending === attempt) finish(attempt, success && attempt.started && current(attempt))
                            } },
                        )
                        attempt.player = player
                        player.prepare(audio.bytes)
                    } catch (_: Exception) { finish(attempt, false) }
                } }
            } catch (_: Exception) { finish(attempt, false) }
        }
    }
    override fun stop() { generation.incrementAndGet(); scheduler.post { retire() } }
    override fun hostUnavailable() = stop()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        scheduler.post { retire(); loader.close() }
    }
    private fun current(attempt: Attempt) = !closed.get() && generation.get() == attempt.ticket && hostIsCurrent(attempt.host)
    private fun retire() { pending?.let { finish(it, false) } }
    private fun finish(attempt: Attempt, success: Boolean) {
        if (pending !== attempt) return
        pending = null
        attempt.loading?.cancel()
        attempt.deadline?.cancel()
        var released = true
        try { attempt.player?.release() } catch (_: Exception) { released = false }
        attempt.player = null
        attempt.completion(success && released && current(attempt))
    }
}
