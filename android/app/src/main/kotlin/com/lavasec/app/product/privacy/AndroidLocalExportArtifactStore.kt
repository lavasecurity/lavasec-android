package com.lavasec.app.product.privacy

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@JvmInline
value class LocalExportHandle(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

interface PendingLocalExportArtifact {
    val output: OutputStream
    suspend fun sync()
    suspend fun publish(): LocalExportHandle
    suspend fun discard()
}

interface LocalExportFileOperations {
    fun sync(output: FileOutputStream)
    fun moveAtomically(source: File, target: File)
}

private object NativeLocalExportFileOperations : LocalExportFileOperations {
    override fun sync(output: FileOutputStream) {
        output.flush()
        output.fd.sync()
    }

    override fun moveAtomically(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}

class AndroidLocalExportArtifactStore(
    private val context: Context,
    private val rootDirectory: File = File(context.cacheDir, DIRECTORY_NAME),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileOperations: LocalExportFileOperations = NativeLocalExportFileOperations,
    private val deleteFile: (File) -> Boolean = File::delete,
    private val outputForFile: (File) -> FileOutputStream = ::FileOutputStream,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val uriForFile: (File) -> Uri = { file ->
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.local-log-export",
            file,
        )
    },
) {
    init {
        check(rootDirectory.mkdirs() || rootDirectory.isDirectory) {
            "Unable to create local export cache"
        }
        cleanupAbandoned()
    }

    suspend fun create(generatedAtMillis: Long): PendingLocalExportArtifact =
        acquireCancellationSafe(
            dispatcher = ioDispatcher,
            acquire = {
                require(generatedAtMillis >= 0L)
                val id = UUID.randomUUID().toString()
                val directory = ownedDirectory(id)
                check(directory.mkdir()) { "Unable to create local export owner" }
                val partial = ownedFile(directory, PARTIAL_FILE_NAME)
                try {
                    check(partial.createNewFile()) { "Unable to stage local export" }
                    activeArtifacts += partial.absolutePath
                    AndroidPendingArtifact(
                        id = id,
                        directory = directory,
                        partial = partial,
                        published = ownedFile(directory, publishedFileName(generatedAtMillis)),
                    )
                } catch (failure: Throwable) {
                    activeArtifacts -= partial.absolutePath
                    runCatching { deleteAndVerify(partial) }
                    deleteDirectoryIfEmpty(directory)
                    throw failure
                }
            },
            release = AndroidPendingArtifact::discardAfterLostHandoff,
        )

    fun resolve(handle: LocalExportHandle): File = requireNotNull(resolveOrNull(handle)) {
        "Unknown local export artifact"
    }

    fun resolveOrNull(handle: LocalExportHandle): File? {
        val id = handle.value.takeIf(HANDLE_PATTERN::matches) ?: return null
        val directory = ownedDirectory(id).takeIf(File::isDirectory) ?: return null
        return directory.listFiles().orEmpty()
            .filter { it.isFile && PUBLISHED_FILE_PATTERN.matches(it.name) }
            .singleOrNull()
    }

    fun uri(handle: LocalExportHandle): Uri = uriForFile(resolve(handle))

    fun cleanupAbandoned(nowMillis: Long = System.currentTimeMillis()) {
        rootDirectory.listFiles().orEmpty().forEach { directory ->
            if (!directory.isDirectory || !HANDLE_PATTERN.matches(directory.name)) return@forEach
            if (!isCanonicalOwnedDirectory(directory)) return@forEach
            val children = directory.listFiles() ?: return@forEach
            if (children.any { it.absolutePath in activeArtifacts }) return@forEach
            children.forEach { file ->
                if (!file.isFile) return@forEach
                val partial = file.name == PARTIAL_FILE_NAME
                val stale = PUBLISHED_FILE_PATTERN.matches(file.name) &&
                    nowMillis - file.lastModified() >= PUBLISHED_RETENTION_MILLIS
                if (partial || stale) runCatching { deleteFile(file) }
            }
            deleteDirectoryIfEmpty(directory)
        }
    }

    private fun ownedDirectory(id: String): File {
        require(HANDLE_PATTERN.matches(id)) { "Invalid local export handle" }
        val directory = File(rootDirectory, id)
        require(isCanonicalOwnedDirectory(directory)) { "Local export path escapes its cache" }
        return directory
    }

    private fun ownedFile(directory: File, name: String): File = File(directory, name).also { file ->
        require(file.parentFile?.canonicalFile == directory.canonicalFile) {
            "Local export file escapes its owner"
        }
    }

    private fun isCanonicalOwnedDirectory(directory: File): Boolean =
        directory.canonicalFile.parentFile == rootDirectory.canonicalFile &&
            directory.canonicalFile.name == directory.name

    private fun publishedFileName(generatedAtMillis: Long): String =
        "$PUBLISHED_FILE_PREFIX${
            FILE_TIME_FORMATTER.withZone(zoneId).format(Instant.ofEpochMilli(generatedAtMillis))
        }.zip"

    private fun deleteAndVerify(file: File): Boolean {
        if (file.exists()) deleteFile(file)
        return !file.exists()
    }

    private fun deleteDirectoryIfEmpty(directory: File) {
        if (directory.listFiles()?.isEmpty() == true) directory.delete()
    }

    private inner class AndroidPendingArtifact(
        private val id: String,
        private val directory: File,
        private val partial: File,
        private val published: File,
    ) : PendingLocalExportArtifact {
        private val stream = outputForFile(partial)
        override val output: OutputStream get() = stream
        private var synced = false
        private var handle: LocalExportHandle? = null
        private var discarded = false

        override suspend fun sync() = withContext(ioDispatcher) {
            check(!discarded) { "Local export was discarded" }
            if (!synced) {
                fileOperations.sync(stream)
                synced = true
            }
        }

        override suspend fun publish(): LocalExportHandle = withContext(ioDispatcher) {
            handle?.let { return@withContext it }
            check(!discarded) { "Local export was discarded" }
            check(synced) { "Local export must be synced before publication" }
            stream.close()
            fileOperations.moveAtomically(partial, published)
            activeArtifacts -= partial.absolutePath
            activeArtifacts += published.absolutePath
            LocalExportHandle(id).also { handle = it }
        }

        override suspend fun discard() = withContext(ioDispatcher) { discardOwned() }

        fun discardAfterLostHandoff() {
            runCatching(::discardOwned)
        }

        private fun discardOwned() {
            if (discarded) return
            runCatching { stream.close() }
            val partialDeleted = deleteAndVerify(partial)
            val publishedDeleted = deleteAndVerify(published)
            if (!partialDeleted || !publishedDeleted) {
                activeArtifacts -= partial.absolutePath
                activeArtifacts -= published.absolutePath
            }
            check(partialDeleted && publishedDeleted) { "Unable to discard local export" }
            discarded = true
            activeArtifacts -= partial.absolutePath
            activeArtifacts -= published.absolutePath
            deleteDirectoryIfEmpty(directory)
        }
    }

    companion object {
        private const val DIRECTORY_NAME = "local-log-exports"
        private const val PARTIAL_FILE_NAME = "artifact.partial"
        private const val PUBLISHED_FILE_PREFIX = "lava-local-logs-"
        private const val PUBLISHED_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        private val HANDLE_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )
        private val PUBLISHED_FILE_PATTERN = Regex(
            "$PUBLISHED_FILE_PREFIX[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{4}\\.zip",
        )
        private val FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd-HHmm",
            Locale.ROOT,
        )
        private val activeArtifacts = ConcurrentHashMap.newKeySet<String>()
    }
}

class AndroidLocalExportShareIntentFactory(
    private val context: Context,
    private val artifactStore: AndroidLocalExportArtifactStore,
) {
    fun create(handle: LocalExportHandle): Intent {
        val uri = artifactStore.uri(handle)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Local log export", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

private suspend fun <T> acquireCancellationSafe(
    dispatcher: CoroutineDispatcher,
    acquire: () -> T,
    release: (T) -> Unit,
): T = suspendCancellableCoroutine { continuation ->
    val callerContext = continuation.context
    val acquisition = Runnable {
        if (!continuation.isActive) return@Runnable
        val resource = try {
            acquire()
        } catch (failure: Throwable) {
            continuation.resumeWithException(failure)
            return@Runnable
        }
        continuation.resume(resource) { _, lostResource, _ ->
            dispatchRelease(dispatcher, callerContext) { release(lostResource) }
        }
    }
    try {
        if (dispatcher.isDispatchNeeded(callerContext)) dispatcher.dispatch(callerContext, acquisition)
        else acquisition.run()
    } catch (failure: Throwable) {
        continuation.resumeWithException(failure)
    }
}

private fun dispatchRelease(
    dispatcher: CoroutineDispatcher,
    context: CoroutineContext,
    release: () -> Unit,
) {
    val cleanup = Runnable { runCatching(release) }
    try {
        if (dispatcher.isDispatchNeeded(context)) dispatcher.dispatch(context, cleanup)
        else cleanup.run()
    } catch (_: Throwable) {
        cleanup.run()
    }
}
