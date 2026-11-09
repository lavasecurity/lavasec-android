package com.lavasec.app.storage

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactExpectation
import com.lavasec.app.foundation.filter.FilterArtifactOpenResult
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.foundation.filter.canonicalFilterArtifactIdentitySha256
import com.lavasec.app.platform.FilterArtifactReference
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * One app-scoped serialization authority for the credential-protected artifact root.
 *
 * Composition must inject the same instance into every store addressing that root. Binding an
 * authority to a different normalized root is rejected instead of silently creating two
 * unrelated writer locks.
 */
class CredentialFilterArtifactPublicationAuthority {
    internal val mutex = Mutex()

    @Volatile
    private var boundRoot: String? = null

    @Synchronized
    internal fun bind(normalizedRoot: File) {
        val path = normalizedRoot.path
        val existing = boundRoot
        check(existing == null || existing == path) {
            "artifact publication authority is already bound to a different root"
        }
        boundRoot = path
    }
}

internal enum class CredentialFilterArtifactPublicationPhase {
    STAGING_DIRECTORY_CREATED,
    ARTIFACT_COPIED_AND_SYNCED,
    STAGED_ARTIFACT_VERIFIED,
    MANIFEST_WRITTEN_AND_SYNCED,
    WAITING_FOR_AUTHORITY,
    AUTHORITY_ACQUIRED,
    GENERATION_INSTALLED,
    FINAL_GENERATION_VERIFIED,
    POINTER_TEMP_SYNCED,
    POINTER_REPLACED,
    POINTER_RECONCILED,
}

internal fun interface CredentialFilterArtifactPublicationCheckpoint {
    fun checkpoint(phase: CredentialFilterArtifactPublicationPhase)
}

sealed interface CredentialFilterArtifactPublicationResult {
    data class Published(val reference: FilterArtifactReference) :
        CredentialFilterArtifactPublicationResult

    data object Superseded : CredentialFilterArtifactPublicationResult

    data class Failed(val reason: FilterArtifactUnavailableReason) :
        CredentialFilterArtifactPublicationResult
}

sealed interface CredentialFilterArtifactInstallResult {
    data class Installed(val reference: FilterArtifactReference) :
        CredentialFilterArtifactInstallResult

    data class Failed(val reason: FilterArtifactUnavailableReason) :
        CredentialFilterArtifactInstallResult
}

sealed interface CredentialFilterArtifactCurrentRead {
    class Available internal constructor(
        val artifact: FilterArtifact,
        val reference: FilterArtifactReference,
    ) : CredentialFilterArtifactCurrentRead, AutoCloseable {
        override fun close() {
            artifact.close()
        }
    }

    data class Unavailable(val reason: FilterArtifactUnavailableReason) :
        CredentialFilterArtifactCurrentRead
}

sealed interface CredentialFilterArtifactExactRead {
    class Available internal constructor(
        artifact: FilterArtifact,
        val reference: FilterArtifactReference,
    ) : CredentialFilterArtifactExactRead, AutoCloseable {
        private val ownedArtifact = AtomicReference<FilterArtifact?>(artifact)

        internal val artifact: FilterArtifact
            get() = checkNotNull(ownedArtifact.get()) {
                "exact artifact ownership has already transferred or closed"
            }

        internal fun takeArtifact(): FilterArtifact =
            checkNotNull(ownedArtifact.getAndSet(null)) {
                "exact artifact ownership has already transferred or closed"
            }

        override fun close() {
            ownedArtifact.getAndSet(null)?.close()
        }
    }

    data class Unavailable(val reason: FilterArtifactUnavailableReason) :
        CredentialFilterArtifactExactRead
}

/**
 * Credential-protected immutable artifact publication.
 *
 * [Published][CredentialFilterArtifactPublicationResult.Published] means only that one exact
 * generation is selected by the store pointer. It is not a configuration commit and does not
 * activate the VPN or filter runtime.
 */
class CredentialFilterArtifactPublicationStore internal constructor(
    rootDirectory: File,
    private val authority: CredentialFilterArtifactPublicationAuthority,
    private val checkpoint: CredentialFilterArtifactPublicationCheckpoint =
        CredentialFilterArtifactPublicationCheckpoint { },
    private val fileSystem: ArtifactPublicationFileSystem =
        JvmArtifactPublicationFileSystem,
) {
    private val root = rootDirectory.absoluteFile.toPath().normalize().toFile()
    private val generations = File(root, GENERATIONS_DIRECTORY)
    private val staging = File(root, STAGING_DIRECTORY)
    private val pointer = File(root, POINTER_FILE)

    init {
        authority.bind(root)
        val rootParent = requireNotNull(root.parentFile) {
            "artifact publication root must have a parent directory"
        }
        fileSystem.ensureDirectory(root)
        fileSystem.syncDirectory(rootParent)
        fileSystem.ensureDirectory(generations)
        fileSystem.ensureDirectory(staging)
        fileSystem.syncDirectory(root)
    }

    suspend fun publish(
        prepared: PreparedFilterArtifact,
        expectedCurrentSha256: String?,
    ): CredentialFilterArtifactPublicationResult {
        if (
            expectedCurrentSha256 != null &&
            !expectedCurrentSha256.isCanonicalLowercaseSha256()
        ) {
            return CredentialFilterArtifactPublicationResult.Failed(
                FilterArtifactUnavailableReason.POINTER_INVALID,
            )
        }
        val receipt = prepared.compilationReceipt
        if (
            !receipt.artifactSha256.isCanonicalLowercaseSha256() ||
            receipt.artifactIdentity.configuration.generation < 0
        ) {
            return CredentialFilterArtifactPublicationResult.Failed(
                FilterArtifactUnavailableReason.POINTER_INVALID,
            )
        }

        val record = ArtifactPublicationRecord(
            generation = receipt.artifactIdentity.configuration.generation,
            artifactSha256 = receipt.artifactSha256,
            identitySha256 = canonicalFilterArtifactIdentitySha256(receipt.artifactIdentity),
        )
        var stagedDirectory: File? = null
        return try {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val candidateDirectory = stagePreparedArtifact(prepared, record)
                stagedDirectory = candidateDirectory

                currentCoroutineContext().ensureActive()
                val publicationContext = currentCoroutineContext()
                checkpoint.checkpoint(
                    CredentialFilterArtifactPublicationPhase.WAITING_FOR_AUTHORITY,
                )
                authority.mutex.lock()
                try {
                    publicationContext.ensureActive()
                    withContext(NonCancellable) {
                        commitPublication(
                            stagedDirectory = candidateDirectory,
                            record = record,
                            expectedIdentity = receipt.artifactIdentity,
                            expectedCurrentSha256 = expectedCurrentSha256,
                        )
                    }
                } finally {
                    authority.mutex.unlock()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: StoreUnavailableException) {
            CredentialFilterArtifactPublicationResult.Failed(error.reason)
        } catch (_: Exception) {
            CredentialFilterArtifactPublicationResult.Failed(
                FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE,
            )
        } finally {
            stagedDirectory?.let(fileSystem::deleteRecursivelyBestEffort)
        }
    }

    /**
     * Installs one compiler-minted immutable generation without reading or changing the
     * checkpoint-18 current pointer.
     *
     * Caller ownership of [prepared] is unchanged. Once authority is acquired, installation and
     * final verification complete non-cancellably so cancellation cannot expose a half-installed
     * generation.
     */
    suspend fun install(
        prepared: PreparedFilterArtifact,
    ): CredentialFilterArtifactInstallResult {
        val receipt = prepared.compilationReceipt
        if (
            !receipt.artifactSha256.isCanonicalLowercaseSha256() ||
            receipt.artifactIdentity.configuration.generation < 0
        ) {
            return CredentialFilterArtifactInstallResult.Failed(
                FilterArtifactUnavailableReason.POINTER_INVALID,
            )
        }
        val record = ArtifactPublicationRecord(
            generation = receipt.artifactIdentity.configuration.generation,
            artifactSha256 = receipt.artifactSha256,
            identitySha256 = canonicalFilterArtifactIdentitySha256(receipt.artifactIdentity),
        )
        var stagedDirectory: File? = null
        return try {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val candidateDirectory = stagePreparedArtifact(prepared, record)
                stagedDirectory = candidateDirectory

                currentCoroutineContext().ensureActive()
                val installationContext = currentCoroutineContext()
                checkpoint.checkpoint(
                    CredentialFilterArtifactPublicationPhase.WAITING_FOR_AUTHORITY,
                )
                authority.mutex.lock()
                try {
                    installationContext.ensureActive()
                    withContext(NonCancellable) {
                        checkpoint.checkpoint(
                            CredentialFilterArtifactPublicationPhase.AUTHORITY_ACQUIRED,
                        )
                        CredentialFilterArtifactInstallResult.Installed(
                            installGeneration(
                                stagedDirectory = candidateDirectory,
                                record = record,
                                expectedIdentity = receipt.artifactIdentity,
                            ),
                        )
                    }
                } finally {
                    authority.mutex.unlock()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: StoreUnavailableException) {
            CredentialFilterArtifactInstallResult.Failed(error.reason)
        } catch (_: Exception) {
            CredentialFilterArtifactInstallResult.Failed(
                FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE,
            )
        } finally {
            stagedDirectory?.let(fileSystem::deleteRecursivelyBestEffort)
        }
    }

    fun readCurrent(
        expectation: FilterArtifactExpectation,
        maxRules: Int,
    ): CredentialFilterArtifactCurrentRead {
        if (maxRules < 0) {
            return CredentialFilterArtifactCurrentRead.Unavailable(
                FilterArtifactUnavailableReason.RULE_TABLE_INVALID,
            )
        }
        return try {
            val current = readPointer(required = true)
                ?: throw StoreUnavailableException(FilterArtifactUnavailableReason.POINTER_MISSING)
            val artifact = verifyGeneration(
                record = current,
                expectedIdentity = null,
                expectation = expectation,
                maxRules = maxRules,
            )
            val reference = try {
                FilterArtifactReference(current.artifactSha256, artifact.identity)
            } catch (error: Throwable) {
                artifact.close()
                throw error
            }
            CredentialFilterArtifactCurrentRead.Available(artifact, reference)
        } catch (error: StoreUnavailableException) {
            CredentialFilterArtifactCurrentRead.Unavailable(error.reason)
        } catch (_: Exception) {
            CredentialFilterArtifactCurrentRead.Unavailable(
                FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE,
            )
        }
    }

    /**
     * Reopens one exact immutable generation by canonical digest and full identity commitment.
     *
     * This method never reads `current.pointer` and never scans the generations directory. The
     * caller-provided rule budget is enforced after the exact artifact has been descriptor-opened
     * and verified.
     */
    fun readExactGeneration(
        artifactSha256: String,
        identitySha256: String,
        maxRules: Int,
    ): CredentialFilterArtifactExactRead {
        if (
            !artifactSha256.isCanonicalLowercaseSha256() ||
            !identitySha256.isCanonicalLowercaseSha256()
        ) {
            return CredentialFilterArtifactExactRead.Unavailable(
                FilterArtifactUnavailableReason.POINTER_INVALID,
            )
        }
        if (maxRules < 0) {
            return CredentialFilterArtifactExactRead.Unavailable(
                FilterArtifactUnavailableReason.RULE_TABLE_INVALID,
            )
        }
        return try {
            val record = readGenerationRecord(artifactSha256)
            if (record.artifactSha256 != artifactSha256) {
                throw StoreUnavailableException(
                    FilterArtifactUnavailableReason.POINTER_INVALID,
                )
            }
            if (record.identitySha256 != identitySha256) {
                throw StoreUnavailableException(
                    FilterArtifactUnavailableReason.IDENTITY_MISMATCH,
                )
            }
            val artifact = verifyGeneration(
                record = record,
                expectedIdentity = null,
                expectation = null,
                maxRules = maxRules,
            )
            val reference = try {
                FilterArtifactReference(record.artifactSha256, artifact.identity)
            } catch (error: Throwable) {
                artifact.close()
                throw error
            }
            CredentialFilterArtifactExactRead.Available(artifact, reference)
        } catch (error: StoreUnavailableException) {
            CredentialFilterArtifactExactRead.Unavailable(error.reason)
        } catch (_: Exception) {
            CredentialFilterArtifactExactRead.Unavailable(
                FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE,
            )
        }
    }

    internal val currentPointerFileForTesting: File
        get() = pointer

    internal fun generationDirectoryForTesting(sha256: String): File =
        File(generations, sha256)

    private fun commitPublication(
        stagedDirectory: File,
        record: ArtifactPublicationRecord,
        expectedIdentity: com.lavasec.app.foundation.filter.FilterArtifactIdentity,
        expectedCurrentSha256: String?,
    ): CredentialFilterArtifactPublicationResult {
        var pointerTemporary: File? = null
        return try {
            checkpoint.checkpoint(
                CredentialFilterArtifactPublicationPhase.AUTHORITY_ACQUIRED,
            )
            val live = readPointer(required = false)
            if (live?.artifactSha256 != expectedCurrentSha256) {
                return CredentialFilterArtifactPublicationResult.Superseded
            }

            installGeneration(
                stagedDirectory = stagedDirectory,
                record = record,
                expectedIdentity = expectedIdentity,
            )

            val temporary = File(root, "$POINTER_TEMP_PREFIX${UUID.randomUUID()}")
            pointerTemporary = temporary
            fileSystem.writeCreateNewAndSync(
                temporary,
                ArtifactPublicationRecordCodec.encode(POINTER_MAGIC, record),
            )
            checkpoint.checkpoint(
                CredentialFilterArtifactPublicationPhase.POINTER_TEMP_SYNCED,
            )
            fileSystem.replaceFile(temporary, pointer)
            pointerTemporary = null
            fileSystem.syncDirectory(root)
            checkpoint.checkpoint(
                CredentialFilterArtifactPublicationPhase.POINTER_REPLACED,
            )

            val reference = readBackExactCandidate(record, expectedIdentity)
                ?: throw StoreUnavailableException(
                    FilterArtifactUnavailableReason.POINTER_INVALID,
                )
            checkpoint.checkpoint(
                CredentialFilterArtifactPublicationPhase.POINTER_RECONCILED,
            )
            CredentialFilterArtifactPublicationResult.Published(reference)
        } catch (error: Exception) {
            val reconciled = runCatching {
                readBackExactCandidate(record, expectedIdentity)
            }.getOrNull()
            if (reconciled != null) {
                CredentialFilterArtifactPublicationResult.Published(reconciled)
            } else {
                CredentialFilterArtifactPublicationResult.Failed(
                    (error as? StoreUnavailableException)?.reason
                        ?: FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE,
                )
            }
        } finally {
            pointerTemporary?.let(fileSystem::deleteRecursivelyBestEffort)
        }
    }

    private fun stagePreparedArtifact(
        prepared: PreparedFilterArtifact,
        record: ArtifactPublicationRecord,
    ): File {
        val candidateDirectory = createStagingDirectory()
        return try {
            checkpoint.checkpoint(
                CredentialFilterArtifactPublicationPhase.STAGING_DIRECTORY_CREATED,
            )
            val stagedArtifact = File(candidateDirectory, ARTIFACT_FILE)
            fileSystem.copyCreateNewAndSync(
                source = prepared.file,
                target = stagedArtifact,
                maxBytes = FilterArtifact.MAX_SERIALIZED_ARTIFACT_BYTES,
            )
            checkpoint.checkpoint(
                CredentialFilterArtifactPublicationPhase.ARTIFACT_COPIED_AND_SYNCED,
            )
            verifyArtifact(
                file = stagedArtifact,
                expectedRecord = record,
                expectedIdentity = prepared.compilationReceipt.artifactIdentity,
                expectation = null,
                maxRules = null,
            ).close()
            checkpoint.checkpoint(
                CredentialFilterArtifactPublicationPhase.STAGED_ARTIFACT_VERIFIED,
            )
            fileSystem.writeCreateNewAndSync(
                File(candidateDirectory, MANIFEST_FILE),
                ArtifactPublicationRecordCodec.encode(MANIFEST_MAGIC, record),
            )
            fileSystem.syncDirectory(candidateDirectory)
            checkpoint.checkpoint(
                CredentialFilterArtifactPublicationPhase.MANIFEST_WRITTEN_AND_SYNCED,
            )
            candidateDirectory
        } catch (error: Throwable) {
            fileSystem.deleteRecursivelyBestEffort(candidateDirectory)
            throw error
        }
    }

    private fun installGeneration(
        stagedDirectory: File,
        record: ArtifactPublicationRecord,
        expectedIdentity: com.lavasec.app.foundation.filter.FilterArtifactIdentity,
    ): FilterArtifactReference {
        val generationDirectory = File(generations, record.artifactSha256)
        if (fileSystem.existsNoFollow(generationDirectory)) {
            verifyGeneration(
                record = record,
                expectedIdentity = expectedIdentity,
                expectation = null,
                maxRules = null,
            ).close()
        } else {
            fileSystem.moveDirectoryNoReplace(stagedDirectory, generationDirectory)
            fileSystem.syncDirectory(staging)
        }
        // A prior rename may have become visible before its parent-directory sync failed.
        // Reused generations must therefore retry this durability barrier as well.
        fileSystem.syncDirectory(generations)
        checkpoint.checkpoint(
            CredentialFilterArtifactPublicationPhase.GENERATION_INSTALLED,
        )

        val artifact = verifyGeneration(
            record = record,
            expectedIdentity = expectedIdentity,
            expectation = null,
            maxRules = null,
        )
        val reference = try {
            FilterArtifactReference(record.artifactSha256, artifact.identity)
        } finally {
            artifact.close()
        }
        checkpoint.checkpoint(
            CredentialFilterArtifactPublicationPhase.FINAL_GENERATION_VERIFIED,
        )
        return reference
    }

    private fun readBackExactCandidate(
        expectedRecord: ArtifactPublicationRecord,
        expectedIdentity: com.lavasec.app.foundation.filter.FilterArtifactIdentity,
    ): FilterArtifactReference? {
        val live = readPointer(required = true) ?: return null
        if (live != expectedRecord) return null
        val artifact = verifyGeneration(
            record = live,
            expectedIdentity = expectedIdentity,
            expectation = null,
            maxRules = null,
        )
        return try {
            FilterArtifactReference(live.artifactSha256, artifact.identity)
        } finally {
            artifact.close()
        }
    }

    private fun verifyGeneration(
        record: ArtifactPublicationRecord,
        expectedIdentity: com.lavasec.app.foundation.filter.FilterArtifactIdentity?,
        expectation: FilterArtifactExpectation?,
        maxRules: Int?,
    ): FilterArtifact {
        val directory = File(generations, record.artifactSha256)
        if (!fileSystem.isDirectoryNoFollow(directory)) {
            throw StoreUnavailableException(
                if (fileSystem.existsNoFollow(directory)) {
                    FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE
                } else {
                    FilterArtifactUnavailableReason.ARTIFACT_MISSING
                },
            )
        }
        val manifest = readRecord(
            file = File(directory, MANIFEST_FILE),
            magic = MANIFEST_MAGIC,
            required = true,
            missingReason = FilterArtifactUnavailableReason.ARTIFACT_MISSING,
        ) ?: throw StoreUnavailableException(FilterArtifactUnavailableReason.ARTIFACT_MISSING)
        if (manifest != record) {
            throw StoreUnavailableException(FilterArtifactUnavailableReason.POINTER_INVALID)
        }
        return verifyArtifact(
            file = File(directory, ARTIFACT_FILE),
            expectedRecord = record,
            expectedIdentity = expectedIdentity,
            expectation = expectation,
            maxRules = maxRules,
        )
    }

    private fun readGenerationRecord(
        artifactSha256: String,
    ): ArtifactPublicationRecord {
        val directory = File(generations, artifactSha256)
        if (!fileSystem.isDirectoryNoFollow(directory)) {
            throw StoreUnavailableException(
                if (fileSystem.existsNoFollow(directory)) {
                    FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE
                } else {
                    FilterArtifactUnavailableReason.ARTIFACT_MISSING
                },
            )
        }
        return readRecord(
            file = File(directory, MANIFEST_FILE),
            magic = MANIFEST_MAGIC,
            required = true,
            missingReason = FilterArtifactUnavailableReason.ARTIFACT_MISSING,
        ) ?: throw StoreUnavailableException(FilterArtifactUnavailableReason.ARTIFACT_MISSING)
    }

    private fun verifyArtifact(
        file: File,
        expectedRecord: ArtifactPublicationRecord,
        expectedIdentity: com.lavasec.app.foundation.filter.FilterArtifactIdentity?,
        expectation: FilterArtifactExpectation?,
        maxRules: Int?,
    ): FilterArtifact {
        val channel = try {
            fileSystem.openRegularFile(
                file,
                maxBytes = FilterArtifact.MAX_SERIALIZED_ARTIFACT_BYTES,
            )
        } catch (error: MissingArtifactFileException) {
            throw StoreUnavailableException(FilterArtifactUnavailableReason.ARTIFACT_MISSING, error)
        } catch (error: IOException) {
            throw StoreUnavailableException(FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE, error)
        }
        val artifact = when (
            val opened = FilterArtifact.openResult(
                channel = channel,
                expectedSha256 = expectedRecord.artifactSha256,
                expectation = expectation,
            )
        ) {
            is FilterArtifactOpenResult.Available -> opened.artifact
            is FilterArtifactOpenResult.Unavailable ->
                throw StoreUnavailableException(opened.reason)
        }
        try {
            if (
                artifact.identity.configuration.generation != expectedRecord.generation ||
                artifact.identity.pointerSchema != FilterArtifact.POINTER_SCHEMA ||
                canonicalFilterArtifactIdentitySha256(artifact.identity) !=
                expectedRecord.identitySha256 ||
                (expectedIdentity != null && artifact.identity != expectedIdentity)
            ) {
                throw StoreUnavailableException(FilterArtifactUnavailableReason.IDENTITY_MISMATCH)
            }
            if (maxRules != null && artifact.identity.ruleCounts.total > maxRules) {
                throw StoreUnavailableException(FilterArtifactUnavailableReason.RULE_TABLE_INVALID)
            }
            return artifact
        } catch (error: Throwable) {
            artifact.close()
            throw error
        }
    }

    private fun readPointer(required: Boolean): ArtifactPublicationRecord? =
        readRecord(
            file = pointer,
            magic = POINTER_MAGIC,
            required = required,
            missingReason = FilterArtifactUnavailableReason.POINTER_MISSING,
        )

    private fun readRecord(
        file: File,
        magic: ByteArray,
        required: Boolean,
        missingReason: FilterArtifactUnavailableReason,
    ): ArtifactPublicationRecord? {
        if (!fileSystem.existsNoFollow(file)) {
            if (!required) return null
            throw StoreUnavailableException(missingReason)
        }
        val bytes = try {
            fileSystem.readExactRegularFile(file, ArtifactPublicationRecordCodec.SIZE)
        } catch (error: UnexpectedRecordSizeException) {
            throw StoreUnavailableException(FilterArtifactUnavailableReason.POINTER_INVALID, error)
        } catch (error: IOException) {
            throw StoreUnavailableException(FilterArtifactUnavailableReason.STORAGE_UNAVAILABLE, error)
        }
        return ArtifactPublicationRecordCodec.decode(magic, bytes)
    }

    private fun createStagingDirectory(): File {
        repeat(MAX_UNIQUE_ATTEMPTS) {
            val candidate = File(staging, UUID.randomUUID().toString())
            if (fileSystem.createDirectoryExclusive(candidate)) return candidate
        }
        throw IOException("could not allocate a private staging directory")
    }

    companion object {
        /**
         * Creates the production store under the app's credential-protected files directory.
         */
        fun credentialProtected(
            context: Context,
        ): CredentialFilterArtifactPublicationStore {
            return CredentialFilterArtifactPublicationStore(
                rootDirectory = credentialProtectedRoot(context),
                authority = PRODUCTION_AUTHORITY,
                fileSystem = AndroidOsArtifactPublicationFileSystem,
                checkpoint = CredentialFilterArtifactPublicationCheckpoint { },
            )
        }

        internal fun credentialProtectedRoot(context: Context): File {
            require(!context.isDeviceProtectedStorage) {
                "credential artifact publication cannot use device-protected storage"
            }
            val applicationContext = context.applicationContext ?: context
            require(!applicationContext.isDeviceProtectedStorage) {
                "credential artifact publication cannot use device-protected storage"
            }
            return File(applicationContext.filesDir, ROOT_DIRECTORY)
                .absoluteFile
                .toPath()
                .normalize()
                .toFile()
        }

        internal const val ROOT_DIRECTORY = "filter-artifacts"
        private const val GENERATIONS_DIRECTORY = "generations"
        private const val STAGING_DIRECTORY = "staging"
        private const val POINTER_FILE = "current.pointer"
        private const val POINTER_TEMP_PREFIX = ".current.pointer-"
        private const val ARTIFACT_FILE = "artifact.lsfa"
        private const val MANIFEST_FILE = "complete.manifest"
        private const val MAX_UNIQUE_ATTEMPTS = 8
        private val POINTER_MAGIC = "LAVAPTR1".encodeToByteArray()
        private val MANIFEST_MAGIC = "LAVAMAN1".encodeToByteArray()
        private val PRODUCTION_AUTHORITY = CredentialFilterArtifactPublicationAuthority()
    }
}

private data class ArtifactPublicationRecord(
    val generation: Long,
    val artifactSha256: String,
    val identitySha256: String,
)

private object ArtifactPublicationRecordCodec {
    const val SIZE = 84
    private const val SCHEMA = 1

    fun encode(magic: ByteArray, record: ArtifactPublicationRecord): ByteArray {
        require(magic.size == 8)
        require(record.generation >= 0)
        require(record.artifactSha256.isCanonicalLowercaseSha256())
        require(record.identitySha256.isCanonicalLowercaseSha256())
        return ByteBuffer.allocate(SIZE).apply {
            put(magic)
            putInt(SCHEMA)
            putLong(record.generation)
            put(record.artifactSha256.hexToBytes())
            put(record.identitySha256.hexToBytes())
        }.array()
    }

    fun decode(expectedMagic: ByteArray, bytes: ByteArray): ArtifactPublicationRecord {
        if (bytes.size != SIZE) {
            throw StoreUnavailableException(FilterArtifactUnavailableReason.POINTER_INVALID)
        }
        val buffer = ByteBuffer.wrap(bytes)
        val magic = ByteArray(8).also(buffer::get)
        if (!magic.contentEquals(expectedMagic)) {
            throw StoreUnavailableException(FilterArtifactUnavailableReason.POINTER_INVALID)
        }
        if (buffer.int != SCHEMA) {
            throw StoreUnavailableException(
                FilterArtifactUnavailableReason.POINTER_SCHEMA_MISMATCH,
            )
        }
        val generation = buffer.long
        if (generation < 0) {
            throw StoreUnavailableException(FilterArtifactUnavailableReason.POINTER_INVALID)
        }
        val artifactSha = ByteArray(32).also(buffer::get).toHex()
        val identitySha = ByteArray(32).also(buffer::get).toHex()
        if (buffer.hasRemaining()) {
            throw StoreUnavailableException(FilterArtifactUnavailableReason.POINTER_INVALID)
        }
        return ArtifactPublicationRecord(generation, artifactSha, identitySha)
    }
}

private class StoreUnavailableException(
    val reason: FilterArtifactUnavailableReason,
    cause: Throwable? = null,
) : IOException(reason.name, cause)

private class UnexpectedRecordSizeException : IOException()
private class MissingArtifactFileException : IOException()

internal interface ArtifactPublicationFileSystem {
    fun ensureDirectory(directory: File)
    fun createDirectoryExclusive(directory: File): Boolean
    fun existsNoFollow(file: File): Boolean
    fun isDirectoryNoFollow(directory: File): Boolean
    fun copyCreateNewAndSync(source: File, target: File, maxBytes: Long)
    fun writeCreateNewAndSync(target: File, bytes: ByteArray)
    fun readExactRegularFile(file: File, exactSize: Int): ByteArray
    fun readBoundedRegularFile(file: File, maxBytes: Int): ByteArray
    fun openRegularFile(file: File, maxBytes: Long): FileChannel
    fun moveDirectoryNoReplace(source: File, target: File)
    fun replaceFile(source: File, target: File)
    fun syncDirectory(directory: File)
    fun deleteRecursivelyBestEffort(file: File)
}

/**
 * Host-only test backend. Production atomicity is provided by the Android Os backend below.
 */
internal object JvmArtifactPublicationFileSystem : ArtifactPublicationFileSystem {
    override fun ensureDirectory(directory: File) {
        Files.createDirectories(directory.toPath())
        if (!isDirectoryNoFollow(directory)) throw IOException("not a regular directory")
    }

    override fun createDirectoryExclusive(directory: File): Boolean = try {
        Files.createDirectory(directory.toPath())
        true
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        false
    }

    override fun existsNoFollow(file: File): Boolean =
        Files.exists(file.toPath(), NOFOLLOW_LINKS)

    override fun isDirectoryNoFollow(directory: File): Boolean = runCatching {
        Files.readAttributes(
            directory.toPath(),
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        ).isDirectory
    }.getOrDefault(false)

    override fun copyCreateNewAndSync(source: File, target: File, maxBytes: Long) {
        val attributes = try {
            Files.readAttributes(
                source.toPath(),
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
        } catch (error: java.nio.file.NoSuchFileException) {
            throw MissingArtifactFileException()
        }
        if (!attributes.isRegularFile) throw IOException("not a regular source file")
        if (attributes.size() !in 1..maxBytes) throw IOException("invalid artifact source size")
        FileChannel.open(source.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            FileChannel.open(target.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    buffer.clear()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > maxBytes) throw IOException("artifact exceeds publication bound")
                    buffer.flip()
                    while (buffer.hasRemaining()) output.write(buffer)
                }
                output.force(true)
            }
        }
    }

    override fun writeCreateNewAndSync(target: File, bytes: ByteArray) {
        FileChannel.open(target.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    override fun readExactRegularFile(file: File, exactSize: Int): ByteArray {
        val attributes = try {
            Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
        } catch (error: java.nio.file.NoSuchFileException) {
            throw MissingArtifactFileException()
        }
        if (!attributes.isRegularFile) throw IOException("not a regular file")
        if (attributes.size() != exactSize.toLong()) throw UnexpectedRecordSizeException()
        return FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteArray(exactSize)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw UnexpectedRecordSizeException()
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) throw UnexpectedRecordSizeException()
            bytes
        }
    }

    override fun readBoundedRegularFile(file: File, maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "maximum byte count must be nonnegative" }
        val attributes = try {
            Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
        } catch (error: java.nio.file.NoSuchFileException) {
            throw MissingArtifactFileException()
        }
        if (!attributes.isRegularFile) throw IOException("not a regular file")
        if (attributes.size() !in 0..maxBytes.toLong()) {
            throw IOException("regular file exceeds read bound")
        }
        return FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size !in 0..maxBytes.toLong()) {
                throw IOException("regular file exceeds read bound")
            }
            val bytes = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw IOException("regular file changed during bounded read")
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw IOException("regular file changed during bounded read")
            }
            bytes
        }
    }

    override fun openRegularFile(file: File, maxBytes: Long): FileChannel {
        val attributes = try {
            Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
        } catch (error: java.nio.file.NoSuchFileException) {
            throw MissingArtifactFileException()
        }
        if (!attributes.isRegularFile) throw IOException("not a regular file")
        if (attributes.size() !in 1..maxBytes) throw IOException("invalid artifact size")
        return FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS)
    }

    override fun moveDirectoryNoReplace(source: File, target: File) {
        if (existsNoFollow(target)) throw IOException("immutable generation already exists")
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
    }

    override fun replaceFile(source: File, target: File) {
        if (!source.renameTo(target)) throw IOException("atomic host pointer replacement failed")
    }

    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    override fun deleteRecursivelyBestEffort(file: File) {
        runCatching { file.deleteRecursively() }
    }
}

/**
 * API-28 production backend. All path opens are no-follow and descriptor-validated.
 */
internal fun interface AndroidOsDescriptorCloser {
    fun close(descriptor: java.io.FileDescriptor)
}

internal object AndroidOsArtifactPublicationFileSystem :
    ArtifactPublicationFileSystem by AndroidOsArtifactPublicationFileSystemBackend()

internal class AndroidOsArtifactPublicationFileSystemBackend(
    private val readDescriptorCloser: AndroidOsDescriptorCloser =
        AndroidOsDescriptorCloser(Os::close),
) : ArtifactPublicationFileSystem {
    override fun ensureDirectory(directory: File) {
        if (!existsNoFollow(directory)) {
            try {
                Os.mkdir(directory.path, DIRECTORY_MODE)
            } catch (error: ErrnoException) {
                if (!existsNoFollow(directory)) throw IOException(error)
            }
        }
        if (!isDirectoryNoFollow(directory)) throw IOException("not a regular directory")
    }

    override fun createDirectoryExclusive(directory: File): Boolean = try {
        Os.mkdir(directory.path, DIRECTORY_MODE)
        true
    } catch (error: ErrnoException) {
        if (existsNoFollow(directory)) false else throw IOException(error)
    }

    override fun existsNoFollow(file: File): Boolean = try {
        Os.lstat(file.path)
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw IOException(error)
    }

    override fun isDirectoryNoFollow(directory: File): Boolean = try {
        OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw IOException(error)
    }

    override fun copyCreateNewAndSync(source: File, target: File, maxBytes: Long) {
        val sourceDescriptor = openReadOnly(source)
        try {
            withDescriptorClose(
                descriptor = sourceDescriptor,
                closer = OS_DESCRIPTOR_CLOSER,
            ) {
                val sourceStat = Os.fstat(sourceDescriptor)
                if (!OsConstants.S_ISREG(sourceStat.st_mode)) {
                    throw IOException("not a regular source file")
                }
                if (sourceStat.st_size !in 1..maxBytes) {
                    throw IOException("invalid artifact source size")
                }
                val targetDescriptor = openCreateNew(target)
                withDescriptorClose(
                    descriptor = targetDescriptor,
                    closer = OS_DESCRIPTOR_CLOSER,
                ) {
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = Os.read(sourceDescriptor, buffer, 0, buffer.size)
                        if (count <= 0) break
                        total += count
                        if (total > maxBytes) {
                            throw IOException("artifact exceeds publication bound")
                        }
                        writeFully(targetDescriptor, buffer, count)
                    }
                    Os.fsync(targetDescriptor)
                }
            }
        } catch (error: ErrnoException) {
            throw IOException(error)
        }
    }

    override fun writeCreateNewAndSync(target: File, bytes: ByteArray) {
        val descriptor = openCreateNew(target)
        try {
            withDescriptorClose(
                descriptor = descriptor,
                closer = OS_DESCRIPTOR_CLOSER,
            ) {
                writeFully(descriptor, bytes, bytes.size)
                Os.fsync(descriptor)
            }
        } catch (error: ErrnoException) {
            throw IOException(error)
        }
    }

    override fun readExactRegularFile(file: File, exactSize: Int): ByteArray {
        val descriptor = openReadOnly(file)
        return try {
            withDescriptorClose(
                descriptor = descriptor,
                closer = readDescriptorCloser,
            ) {
                val stat = Os.fstat(descriptor)
                if (!OsConstants.S_ISREG(stat.st_mode)) throw IOException("not a regular file")
                if (stat.st_size != exactSize.toLong()) throw UnexpectedRecordSizeException()
                val bytes = ByteArray(exactSize)
                var offset = 0
                while (offset < bytes.size) {
                    val count = Os.read(descriptor, bytes, offset, bytes.size - offset)
                    if (count <= 0) throw UnexpectedRecordSizeException()
                    offset += count
                }
                if (Os.read(descriptor, ByteArray(1), 0, 1) > 0) {
                    throw UnexpectedRecordSizeException()
                }
                bytes
            }
        } catch (error: ErrnoException) {
            throw IOException(error)
        }
    }

    override fun readBoundedRegularFile(file: File, maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "maximum byte count must be nonnegative" }
        val descriptor = openReadOnly(file)
        return try {
            withDescriptorClose(
                descriptor = descriptor,
                closer = readDescriptorCloser,
            ) {
                val stat = Os.fstat(descriptor)
                if (!OsConstants.S_ISREG(stat.st_mode)) throw IOException("not a regular file")
                if (stat.st_size !in 0..maxBytes.toLong()) {
                    throw IOException("regular file exceeds read bound")
                }
                val bytes = ByteArray(stat.st_size.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val count = Os.read(descriptor, bytes, offset, bytes.size - offset)
                    if (count <= 0) {
                        throw IOException("regular file changed during bounded read")
                    }
                    offset += count
                }
                if (Os.read(descriptor, ByteArray(1), 0, 1) > 0) {
                    throw IOException("regular file changed during bounded read")
                }
                bytes
            }
        } catch (error: ErrnoException) {
            throw IOException(error)
        }
    }

    override fun openRegularFile(file: File, maxBytes: Long): FileChannel {
        val descriptor = openReadOnly(file)
        var ownedChannel: FileChannel? = null
        try {
            return withDescriptorClose(
                descriptor = descriptor,
                closer = OS_DESCRIPTOR_CLOSER,
            ) {
                val stat = Os.fstat(descriptor)
                if (!OsConstants.S_ISREG(stat.st_mode)) throw IOException("not a regular file")
                if (stat.st_size !in 1..maxBytes) throw IOException("invalid artifact size")
                val ownedDescriptor = ParcelFileDescriptor.dup(descriptor)
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(ownedDescriptor).channel.also {
                        ownedChannel = it
                    }
                } catch (error: Throwable) {
                    try {
                        ownedDescriptor.close()
                    } catch (closeFailure: Throwable) {
                        if (closeFailure !== error) error.addSuppressed(closeFailure)
                    }
                    throw error
                }
            }
        } catch (error: Throwable) {
            ownedChannel?.let { channel ->
                try {
                    channel.close()
                } catch (closeFailure: Throwable) {
                    if (closeFailure !== error) error.addSuppressed(closeFailure)
                }
            }
            if (error is IOException) throw error
            throw IOException(error)
        }
    }

    override fun moveDirectoryNoReplace(source: File, target: File) {
        if (existsNoFollow(target)) throw IOException("immutable generation already exists")
        try {
            Os.rename(source.path, target.path)
        } catch (error: ErrnoException) {
            throw IOException(error)
        }
    }

    override fun replaceFile(source: File, target: File) {
        try {
            Os.rename(source.path, target.path)
        } catch (error: ErrnoException) {
            throw IOException(error)
        }
    }

    override fun syncDirectory(directory: File) {
        val descriptor = try {
            Os.open(
                directory.path,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            )
        } catch (error: ErrnoException) {
            throw IOException(error)
        }
        try {
            withDescriptorClose(
                descriptor = descriptor,
                closer = OS_DESCRIPTOR_CLOSER,
            ) {
                if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                    throw IOException("not a regular directory")
                }
                Os.fsync(descriptor)
            }
        } catch (error: ErrnoException) {
            throw IOException(error)
        }
    }

    override fun deleteRecursivelyBestEffort(file: File) {
        runCatching { file.deleteRecursively() }
    }

    private fun openCreateNew(file: File) = try {
        Os.open(
            file.path,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            FILE_MODE,
        )
    } catch (error: ErrnoException) {
        throw IOException(error)
    }

    private fun openReadOnly(file: File) = try {
        Os.open(
            file.path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0,
        )
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) throw MissingArtifactFileException()
        throw IOException(error)
    }

    private fun writeFully(
        descriptor: java.io.FileDescriptor,
        bytes: ByteArray,
        size: Int,
    ) {
        var offset = 0
        while (offset < size) {
            val count = Os.write(descriptor, bytes, offset, size - offset)
            if (count <= 0) throw IOException("short write")
            offset += count
        }
    }

    private companion object {
        const val DIRECTORY_MODE = 0x1c0
        const val FILE_MODE = 0x180
        val OS_DESCRIPTOR_CLOSER = AndroidOsDescriptorCloser(Os::close)
    }
}

internal inline fun <T> withDescriptorClose(
    descriptor: java.io.FileDescriptor,
    closer: AndroidOsDescriptorCloser,
    block: () -> T,
): T {
    var primaryFailure: Throwable? = null
    try {
        return block()
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        try {
            closer.close(descriptor)
        } catch (closeFailure: Throwable) {
            val closeIOException = if (closeFailure is IOException) {
                closeFailure
            } else {
                IOException("descriptor close failed", closeFailure)
            }
            val primary = primaryFailure
            if (primary == null) {
                throw closeIOException
            }
            if (closeIOException !== primary) {
                primary.addSuppressed(closeIOException)
            }
        }
    }
}

private fun String.isCanonicalLowercaseSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val COPY_BUFFER_SIZE = 64 * 1024
