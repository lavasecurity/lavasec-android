package com.lavasec.app.data

import android.net.Network
import com.lavasec.app.foundation.filter.BlocklistFormat
import com.lavasec.app.foundation.filter.BlocklistParser
import com.lavasec.app.foundation.filter.ArtifactSourceIdentity
import com.lavasec.app.foundation.filter.FilterSourcePayload
import com.lavasec.app.foundation.share.ShareableSourceUrlValidator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class SeededSourceDescriptor(
    val id: String,
    val sourceUrl: String,
    val format: BlocklistFormat,
    /**
     * The signed catalog revision and digest for a curated source. A custom blocklist has no
     * catalog identity and carries the empty default; it is keyed by URL + format instead.
     */
    val catalogRevision: String = "",
    val catalogSha256: String = "",
) {
    init {
        require(SAFE_SOURCE_ID.matches(id))
        require(catalogSha256.isEmpty() || SAFE_SHA256.matches(catalogSha256))
        require(catalogRevision.isNotEmpty() == catalogSha256.isNotEmpty())
    }

    internal companion object {
        val SAFE_SOURCE_ID = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        val SAFE_SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class AcceptedSeededSource(
    val descriptor: SeededSourceDescriptor,
    val acceptedSha256: String,
    val acceptedByteSize: Long,
    val file: File,
    val acceptedAtEpochMillis: Long = file.lastModified(),
) {
    fun asFilterPayload(): FilterSourcePayload = FilterSourcePayload(
        id = descriptor.id,
        format = descriptor.format,
        acceptedSha256 = acceptedSha256,
        declaredByteSize = acceptedByteSize,
        openStream = { file.inputStream().buffered(STREAM_BUFFER_BYTES) },
        isCustom = descriptor.catalogSha256.isEmpty(),
    )
}

enum class SeededSourceCacheFailure {
    HTTPS_REQUIRED,
    REDIRECT_LOCATION_MISSING,
    REDIRECT_LIMIT,
    REDIRECT_HOST_MISMATCH,
    HTTP_STATUS,
    EMPTY_BODY,
    BODY_TOO_LARGE,
    TIMEOUT,
    NETWORK_IO,
    STORAGE_IO,
    PARSE_FAILED,
}

class SeededSourceCacheException(
    val reason: SeededSourceCacheFailure,
    cause: Throwable? = null,
) : IllegalStateException(reason.name, cause)

/** One response body whose close path also cancels its owning transport call. */
class SeededSourceHttpResponse(
    val statusCode: Int,
    val redirectLocation: String?,
    val body: InputStream,
    private val cancelCall: () -> Unit = {},
    private val closeResponse: () -> Unit = {},
) : Closeable {
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { body.close() }
            runCatching(closeResponse)
        }
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) runCatching(cancelCall)
        close()
    }

    companion object {
        fun ok(bytes: ByteArray): SeededSourceHttpResponse =
            SeededSourceHttpResponse(200, null, ByteArrayInputStream(bytes))

        fun redirect(location: String): SeededSourceHttpResponse =
            SeededSourceHttpResponse(302, location, ByteArrayInputStream(byteArrayOf()))
    }
}

fun interface SeededSourceTransport {
    suspend fun execute(url: String): SeededSourceHttpResponse
}

/** Injectable durability boundary used to prove that failed publication is never selected. */
interface SeededSourceFileOperations {
    fun sync(output: FileOutputStream)
    fun atomicMove(source: File, target: File, replace: Boolean)

    companion object {
        val SYSTEM: SeededSourceFileOperations = object : SeededSourceFileOperations {
            override fun sync(output: FileOutputStream) {
                output.fd.sync()
            }

            override fun atomicMove(source: File, target: File, replace: Boolean) {
                val options = if (replace) {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE)
                }
                Files.move(source.toPath(), target.toPath(), *options)
            }
        }
    }
}

/**
 * HTTPS transport pinned to one callback-selected, validated NOT_VPN Android Network.
 * Redirects are deliberately disabled here and validated by [SeededSourceCache].
 * Every fresh route rejects the entire DNS answer set if any address is non-public.
 */
class NetworkBoundSeededSourceTransport internal constructor(
    socketFactory: SocketFactory,
    resolveAddresses: (String) -> List<InetAddress>,
    tlsSocketFactory: SSLSocketFactory? = null,
    tlsTrustManager: X509TrustManager? = null,
) : SeededSourceTransport, AutoCloseable {
    constructor(network: Network) : this(network.socketFactory, { network.getAllByName(it).toList() })

    private val client = OkHttpClient.Builder()
        .socketFactory(socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val answers = resolveAddresses(hostname).toList()
                if (answers.isEmpty() || answers.any { !ShareableSourceUrlValidator.isPublicAddress(it) }) {
                    throw UnknownHostException("source hostname did not resolve exclusively to public addresses")
                }
                return answers
            }
        })
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(WHOLE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .apply {
            require((tlsSocketFactory == null) == (tlsTrustManager == null))
            if (tlsSocketFactory != null && tlsTrustManager != null) {
                sslSocketFactory(tlsSocketFactory, tlsTrustManager)
            }
        }
        .build()

    override suspend fun execute(url: String): SeededSourceHttpResponse {
        // OkHttp can bypass Dns.lookup for IP literals, so each original/redirect URL also
        // passes the same public-source gate before a call can select or reuse a connection.
        if (!ShareableSourceUrlValidator.isAcceptable(url)) {
            throw SeededSourceCacheException(SeededSourceCacheFailure.NETWORK_IO)
        }
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .get()
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        SeededSourceCacheException(SeededSourceCacheFailure.NETWORK_IO, e),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    val seededResponse = SeededSourceHttpResponse(
                        statusCode = response.code,
                        redirectLocation = response.header("Location"),
                        body = responseBody?.byteStream() ?: ByteArrayInputStream(byteArrayOf()),
                        cancelCall = call::cancel,
                        closeResponse = response::close,
                    )
                    continuation.resume(seededResponse) { _, undelivered, _ ->
                        undelivered.cancel()
                    }
                }
            })
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WHOLE_CALL_TIMEOUT_SECONDS = 60L
    }
}

/**
 * Validated digest-addressed cache for the two public seeded lists. A manifest is the
 * only selection point; stale immutable payloads can remain but are never returned.
 */
class SeededSourceCache(
    private val root: File,
    private val fileOperations: SeededSourceFileOperations = SeededSourceFileOperations.SYSTEM,
    private val acquisitionTimeoutMillis: Long = WHOLE_ACQUISITION_TIMEOUT_MILLIS,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val maximumCacheBytes: Long = MAX_CACHE_BYTES,
    private val protectedDigests: () -> Set<String> = { emptySet() },
) {
    private val lock = Any()

    init {
        require(root.mkdirs() || root.isDirectory)
        require(acquisitionTimeoutMillis > 0)
        require(maximumCacheBytes > 0L)
        deleteOrphanedTemporaryFiles()
    }

    fun exactOrNull(source: SeededSourceDescriptor): AcceptedSeededSource? {
        val candidate = synchronized(lock) { readExactManifestMetadata(source) } ?: return null
        if (sha256OrNull(candidate.file) != candidate.acceptedSha256) return null
        return synchronized(lock) {
            readExactManifestMetadata(source).takeIf { it == candidate }
        }
    }

    fun acceptedAtFor(sources: List<ArtifactSourceIdentity>): Long? = synchronized(lock) {
        if (sources.isEmpty()) return@synchronized null
        sources.map { source ->
            val manifest = readManifestFile(manifestFile(source.id)) ?: return@synchronized null
            val acceptedAt: Long? = when (source.acceptedSha256) {
                manifest.digest -> manifest.acceptedAtEpochMillis
                manifest.previousDigest -> manifest.previousAcceptedAtEpochMillis
                else -> return@synchronized null
            }
            acceptedAt?.takeIf { it > 0L } ?: return@synchronized null
        }.minOrNull()
    }

    suspend fun acquireExact(
        source: SeededSourceDescriptor,
        transport: SeededSourceTransport,
        refreshSource: Boolean = false,
    ): AcceptedSeededSource {
        if (!refreshSource) exactOrNull(source)?.let { return it }
        return withTimeoutOrNull(acquisitionTimeoutMillis) {
            acquireFromNetwork(source, transport)
        } ?: throw SeededSourceCacheException(SeededSourceCacheFailure.TIMEOUT)
    }

    private suspend fun acquireFromNetwork(
        source: SeededSourceDescriptor,
        transport: SeededSourceTransport,
    ): AcceptedSeededSource {
        var currentUrl = requireHttps(source.sourceUrl)
        val sourceHost = checkNotNull(URI(currentUrl).host).lowercase()
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val response = try {
                transport.execute(currentUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (typed: SeededSourceCacheException) {
                throw typed
            } catch (error: Throwable) {
                throw SeededSourceCacheException(SeededSourceCacheFailure.NETWORK_IO, error)
            }
            try {
                if (response.statusCode in REDIRECT_STATUSES) {
                    if (redirects >= MAX_REDIRECTS) fail(SeededSourceCacheFailure.REDIRECT_LIMIT)
                    val location = response.redirectLocation
                        ?: fail(SeededSourceCacheFailure.REDIRECT_LOCATION_MISSING)
                    val redirected = requireHttps(resolve(currentUrl, location))
                    if (URI(redirected).host?.lowercase() != sourceHost) {
                        fail(SeededSourceCacheFailure.REDIRECT_HOST_MISMATCH)
                    }
                    currentUrl = redirected
                    redirects += 1
                } else {
                    if (response.statusCode !in 200..299) fail(SeededSourceCacheFailure.HTTP_STATUS)
                    return publish(source, response)
                }
            } finally {
                response.close()
            }
        }
    }

    private suspend fun publish(
        source: SeededSourceDescriptor,
        response: SeededSourceHttpResponse,
    ): AcceptedSeededSource {
        val temporary = File(root, ".payload-${UUID.randomUUID()}.tmp")
        registerTemporary(temporary)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try {
                storage { FileOutputStream(temporary) }.use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = try {
                            readCancellable(response, buffer)
                        } catch (error: java.io.IOException) {
                            throw SeededSourceCacheException(SeededSourceCacheFailure.NETWORK_IO, error)
                        }
                        if (count < 0) break
                        if (count == 0) continue
                        if (total + count > MAX_DECODED_BODY_BYTES) fail(SeededSourceCacheFailure.BODY_TOO_LARGE)
                        storage { output.write(buffer, 0, count) }
                        digest.update(buffer, 0, count)
                        total += count
                    }
                    if (total == 0L) fail(SeededSourceCacheFailure.EMPTY_BODY)
                    storage { output.flush() }
                    sync(output)
                }
            } catch (cancelled: CancellationException) {
                response.cancel()
                throw cancelled
            } catch (typed: SeededSourceCacheException) {
                throw typed
            } catch (error: java.io.IOException) {
                throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO, error)
            }
            currentCoroutineContext().ensureActive()
            // Reject an entirely malformed custom response before moving its cache selection.
            // A valid empty rule set (comments, allow rules, protected domains) is still loaded.
            if (source.catalogSha256.isEmpty()) {
                val context = currentCoroutineContext()
                val summary = try {
                    storage { temporary.inputStream() }.use { input ->
                        BlocklistParser(maxDiagnostics = 0).forEachRule(
                            input, source.format, checkpoint = context::ensureActive,
                        ) { }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: java.io.IOException) {
                    throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO, error)
                }
                if (summary.whollyRejected) fail(SeededSourceCacheFailure.PARSE_FAILED)
            }
            val acceptedDigest = digest.digest().toHex()
            val immutable = File(root, "$acceptedDigest.src")
            var createdImmutable = false
            var acceptedAtEpochMillis = 0L
            synchronized(lock) {
                if (immutable.isFile) {
                    if (immutable.length() != total || sha256OrNull(immutable) != acceptedDigest) {
                        throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO)
                    }
                    temporary.delete()
                } else {
                    atomicMove(temporary, immutable, replace = false)
                    createdImmutable = true
                }
                val prior = readManifestFile(manifestFile(source.id))
                val priorAcceptedAt = prior?.acceptedAtEpochMillis?.takeIf { it > 0L }
                    ?: prior?.digest
                        ?.let { File(root, "$it.src").lastModified() }
                        ?.takeIf { it > 0L }
                val previous = when {
                    prior == null -> null
                    prior.digest != acceptedDigest && priorAcceptedAt != null ->
                        prior.digest to priorAcceptedAt
                    prior.digest == acceptedDigest &&
                        prior.previousDigest != null &&
                        prior.previousAcceptedAtEpochMillis != null ->
                        prior.previousDigest to prior.previousAcceptedAtEpochMillis
                    else -> null
                }
                val acceptedAt = nowEpochMillis().also { require(it > 0L) }
                acceptedAtEpochMillis = acceptedAt
                val manifest = Manifest(
                    source = source,
                    digest = acceptedDigest,
                    byteSize = total,
                    acceptedAtEpochMillis = acceptedAt,
                    previousDigest = previous?.first,
                    previousAcceptedAtEpochMillis = previous?.second,
                )
                try {
                    enforceRetention(manifest)
                    writeManifest(manifest)
                    enforceRetention(manifest)
                } catch (error: Throwable) {
                    if (createdImmutable && !isDigestReferenced(acceptedDigest)) immutable.delete()
                    throw error
                }
            }
            return AcceptedSeededSource(
                source,
                acceptedDigest,
                total,
                immutable,
                acceptedAtEpochMillis = acceptedAtEpochMillis,
            )
        } finally {
            releaseTemporary(temporary)
        }
    }

    private fun readExactManifestMetadata(source: SeededSourceDescriptor): AcceptedSeededSource? {
        val manifestFile = manifestFile(source.id)
        if (!manifestFile.isFile || manifestFile.length() !in 1..MAX_MANIFEST_BYTES.toLong()) return null
        val manifest = readManifestFile(manifestFile) ?: return null
        if (manifest.source != source) return null
        if (!SeededSourceDescriptor.SAFE_SHA256.matches(manifest.digest)) return null
        if (
            manifest.previousDigest != null &&
            (!SeededSourceDescriptor.SAFE_SHA256.matches(manifest.previousDigest) ||
                manifest.previousDigest == manifest.digest)
        ) return null
        if ((manifest.previousDigest == null) != (manifest.previousAcceptedAtEpochMillis == null)) return null
        if (manifest.previousAcceptedAtEpochMillis != null && manifest.previousAcceptedAtEpochMillis <= 0L) return null
        if (manifest.byteSize !in 1..MAX_DECODED_BODY_BYTES) return null
        val immutable = File(root, "${manifest.digest}.src")
        if (!immutable.isFile || immutable.length() != manifest.byteSize) return null
        val acceptedAt = manifest.acceptedAtEpochMillis.takeIf { it > 0L }
            ?: immutable.lastModified().takeIf { it > 0L }
            ?: return null
        if (manifest.acceptedAtEpochMillis <= 0L) {
            runCatching { writeManifest(manifest.copy(acceptedAtEpochMillis = acceptedAt)) }
        }
        return AcceptedSeededSource(source, manifest.digest, manifest.byteSize, immutable, acceptedAt)
    }

    private fun writeManifest(manifest: Manifest) {
        val temporary = File(root, ".manifest-${UUID.randomUUID()}.tmp")
        registerTemporary(temporary)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encodeManifest(manifest))
                output.flush()
                sync(output)
            }
            atomicMove(temporary, manifestFile(manifest.source.id), replace = true)
        } catch (typed: SeededSourceCacheException) {
            throw typed
        } catch (error: Throwable) {
            throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO, error)
        } finally {
            releaseTemporary(temporary)
        }
    }

    private fun manifestFile(sourceId: String): File = File(root, "$sourceId.manifest")

    private fun encodeManifest(manifest: Manifest): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(MANIFEST_MAGIC)
            output.writeInt(MANIFEST_SCHEMA)
            output.writeStringChecked(manifest.source.id)
            output.writeStringChecked(manifest.source.catalogRevision)
            output.writeStringChecked(manifest.source.catalogSha256)
            output.writeStringChecked(manifest.source.sourceUrl)
            output.writeInt(manifest.source.format.ordinal)
            output.writeStringChecked(manifest.digest)
            output.writeLong(manifest.byteSize)
            output.writeLong(manifest.acceptedAtEpochMillis)
            output.writeBoolean(manifest.previousDigest != null)
            manifest.previousDigest?.let {
                output.writeStringChecked(it)
                output.writeLong(requireNotNull(manifest.previousAcceptedAtEpochMillis))
            }
        }
        bytes.toByteArray().also { require(it.size <= MAX_MANIFEST_BYTES) }
    }

    private fun decodeManifest(bytes: ByteArray): Manifest = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val magic = ByteArray(MANIFEST_MAGIC.size)
        input.readFully(magic)
        require(magic.contentEquals(MANIFEST_MAGIC))
        val schema = input.readInt().also { require(it in 1..MANIFEST_SCHEMA) }
        val id = input.readStringChecked()
        val revision = input.readStringChecked()
        val catalogSha = input.readStringChecked()
        val url = input.readStringChecked()
        val format = BlocklistFormat.entries.getOrNull(input.readInt()) ?: error("Invalid format")
        val digest = input.readStringChecked()
        val byteSize = input.readLong()
        val acceptedAt = if (schema >= 2) input.readLong() else 0L
        val previousDigest = if (schema >= 2 && input.readBoolean()) input.readStringChecked() else null
        val previousAcceptedAt = previousDigest?.let { input.readLong() }
        require(input.read() == -1)
        Manifest(
            SeededSourceDescriptor(id, url, format, revision, catalogSha),
            digest,
            byteSize,
            acceptedAt,
            previousDigest,
            previousAcceptedAt,
        )
    }

    private fun readManifestFile(file: File): Manifest? {
        if (!file.isFile || file.length() !in 1..MAX_MANIFEST_BYTES.toLong()) return null
        return try {
            decodeManifest(file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    private fun enforceRetention(prospective: Manifest) {
        val manifests = root.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".manifest") }
            .mapNotNull(::readManifestFile)
            .filterNot { it.source.id == prospective.source.id } + prospective
        val retained = linkedSetOf<String>().apply {
            manifests.forEach { manifest ->
                add(manifest.digest)
                manifest.previousDigest?.let(::add)
            }
            protectedDigests().forEach { digest ->
                require(SeededSourceDescriptor.SAFE_SHA256.matches(digest))
                add(digest)
            }
        }
        val payloads = root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".src") }
        val retainedBytes = payloads.filter { it.nameWithoutExtension in retained }.sumOf(File::length)
        if (retainedBytes > maximumCacheBytes) {
            throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO)
        }
        var totalBytes = payloads.sumOf(File::length)
        payloads.asSequence()
            .filterNot { it.nameWithoutExtension in retained }
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
            .forEach { file ->
                val length = file.length()
                if (!file.delete() && file.exists()) {
                    throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO)
                }
                totalBytes -= length
            }
        if (totalBytes > maximumCacheBytes) {
            throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO)
        }
    }

    private fun deleteOrphanedTemporaryFiles() {
        synchronized(TEMPORARY_REGISTRY_LOCK) {
            root.listFiles()
                .orEmpty()
                .asSequence()
                .filter { file ->
                    file.isFile &&
                        (ORPHANED_PAYLOAD_TEMP.matches(file.name) ||
                            ORPHANED_MANIFEST_TEMP.matches(file.name)) &&
                        temporaryKey(file) !in ACTIVE_TEMPORARIES
                }
                .forEach { file ->
                    if (!file.delete() && file.exists()) {
                        throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO)
                    }
                }
        }
    }

    private fun registerTemporary(file: File) {
        synchronized(TEMPORARY_REGISTRY_LOCK) {
            check(ACTIVE_TEMPORARIES.add(temporaryKey(file)))
        }
    }

    private fun releaseTemporary(file: File) {
        synchronized(TEMPORARY_REGISTRY_LOCK) {
            file.delete()
            ACTIVE_TEMPORARIES.remove(temporaryKey(file))
        }
    }

    private fun isDigestReferenced(digest: String): Boolean = root.listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".manifest") }
        .mapNotNull(::readManifestFile)
        .any { it.digest == digest || it.previousDigest == digest }

    private fun DataOutputStream.writeStringChecked(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readStringChecked(): String {
        val length = readInt().also { require(it in 0..MAX_STRING_BYTES) }
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun requireHttps(url: String): String {
        val uri = try { URI(url) } catch (error: Exception) {
            throw SeededSourceCacheException(SeededSourceCacheFailure.HTTPS_REQUIRED, error)
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrEmpty() ||
            uri.rawUserInfo != null
        ) {
            fail(SeededSourceCacheFailure.HTTPS_REQUIRED)
        }
        return uri.toString()
    }

    private fun resolve(base: String, location: String): String = try {
        URI(base).resolve(location).toString()
    } catch (error: Exception) {
        throw SeededSourceCacheException(SeededSourceCacheFailure.HTTPS_REQUIRED, error)
    }

    private fun atomicMove(source: File, target: File, replace: Boolean) {
        try {
            fileOperations.atomicMove(source, target, replace)
        } catch (error: Throwable) {
            throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO, error)
        }
    }

    private fun sync(output: FileOutputStream) {
        try {
            fileOperations.sync(output)
        } catch (error: Throwable) {
            throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO, error)
        }
    }

    /** Registers call cancellation before entering the potentially blocking decoded-body read. */
    private suspend fun readCancellable(
        response: SeededSourceHttpResponse,
        buffer: ByteArray,
    ): Int = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { response.cancel() }
            try {
                val count = response.body.read(buffer)
                if (continuation.isActive) continuation.resume(count)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (typed: SeededSourceCacheException) {
        throw typed
    } catch (error: Throwable) {
        throw SeededSourceCacheException(SeededSourceCacheFailure.STORAGE_IO, error)
    }

    private fun sha256OrNull(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(STREAM_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex()
    } catch (_: Exception) {
        null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class Manifest(
        val source: SeededSourceDescriptor,
        val digest: String,
        val byteSize: Long,
        val acceptedAtEpochMillis: Long,
        val previousDigest: String?,
        val previousAcceptedAtEpochMillis: Long?,
    )

    private fun fail(reason: SeededSourceCacheFailure): Nothing = throw SeededSourceCacheException(reason)

    companion object {
        const val MAX_DECODED_BODY_BYTES = 45L * 1024L * 1024L
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val MANIFEST_MAGIC = "LAVASRC1".encodeToByteArray()
        private const val MANIFEST_SCHEMA = 2
        const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
        private const val UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        private val ORPHANED_PAYLOAD_TEMP = Regex("\\.payload-$UUID_PATTERN\\.tmp")
        private val ORPHANED_MANIFEST_TEMP = Regex("\\.manifest-$UUID_PATTERN\\.tmp")
        private val TEMPORARY_REGISTRY_LOCK = Any()
        private val ACTIVE_TEMPORARIES = mutableSetOf<String>()
        private fun temporaryKey(file: File): String = runCatching { file.canonicalPath }
            .getOrElse { file.absolutePath }
        private const val MAX_REDIRECTS = 5
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_STRING_BYTES = 16 * 1024
        private const val WHOLE_ACQUISITION_TIMEOUT_MILLIS = 60_000L
    }
}

private const val STREAM_BUFFER_BYTES = 64 * 1024
