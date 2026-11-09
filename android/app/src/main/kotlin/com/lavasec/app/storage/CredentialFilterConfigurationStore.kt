package com.lavasec.app.storage

import android.content.Context
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.PreparedFilterArtifact
import com.lavasec.app.platform.ConfirmedFilterState
import com.lavasec.app.platform.FilterMutationFailure
import com.lavasec.app.platform.FilterMutationPublishResult
import com.lavasec.app.platform.FilterMutationPublisher
import com.lavasec.app.platform.PersistedFilterArtifactRestorationVerifier
import com.lavasec.app.platform.PreparedFilterMutation
import com.lavasec.app.platform.VerifiedFilterRuntimeLease
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * One process-scoped serialization authority for the credential-protected configuration record.
 *
 * The authority is deliberately separate from artifact-install serialization: immutable
 * generations may be prepared concurrently, while only one complete configuration record can
 * become authoritative. Rebinding one authority to a different normalized root is rejected.
 */
class CredentialFilterConfigurationAuthority {
    internal val mutex = Mutex()

    /**
     * True until this process has proved the authoritative record's parent entry durable.
     *
     * This state is valid only for the app's current single-process storage model. Every store
     * for one root must share this authority. A future cross-process reader or writer requires a
     * cross-process lock/durability protocol instead of relying on this process-scoped latch.
     */
    internal var authoritativeDirectorySyncRequired: Boolean = true

    @Volatile
    private var boundRoot: String? = null

    @Synchronized
    internal fun bind(normalizedRoot: File) {
        val path = normalizedRoot.path
        val existing = boundRoot
        check(existing == null || existing == path) {
            "configuration authority is already bound to a different root"
        }
        boundRoot = path
    }
}

internal enum class CredentialFilterConfigurationPhase {
    WAITING_FOR_FINAL_AUTHORITY,
    RECORD_TEMP_SYNCED,
    RECORD_REPLACED,
    RECORD_DIRECTORY_SYNCED,
}

internal fun interface CredentialFilterConfigurationCheckpoint {
    fun checkpoint(phase: CredentialFilterConfigurationPhase)
}

enum class CredentialFilterConfigurationUnavailableReason {
    MISSING,
    RECORD_INVALID,
    ARTIFACT_UNAVAILABLE,
    IDENTITY_MISMATCH,
    STORAGE_UNAVAILABLE,
    QA_PROBES_DISABLED,
}

sealed interface CredentialFilterConfigurationRead {
    data class Available(val state: ConfirmedFilterState) :
        CredentialFilterConfigurationRead

    data class Unavailable(
        val reason: CredentialFilterConfigurationUnavailableReason,
        val artifactReason: FilterArtifactUnavailableReason? = null,
    ) : CredentialFilterConfigurationRead
}

sealed interface CredentialFilterConfigurationRuntimeRead {
    class Available internal constructor(
        val state: ConfirmedFilterState,
        lease: VerifiedFilterRuntimeLease,
    ) : CredentialFilterConfigurationRuntimeRead, AutoCloseable {
        private val lock = ReentrantLock()
        private val closeCompleted = lock.newCondition()
        private var ownedLease: VerifiedFilterRuntimeLease? = lease
        private var closing = false
        private var closed = false
        private var closeOwnerThread: Thread? = null
        private var closeFailure: Throwable? = null

        fun takeLease(): VerifiedFilterRuntimeLease = lock.withLock {
            check(!closing && !closed) {
                "authoritative runtime lease carrier is closing or closed"
            }
            checkNotNull(ownedLease).also {
                ownedLease = null
            }
        }

        override fun close() {
            var leaseToClose: VerifiedFilterRuntimeLease? = null
            var observedFailure: Throwable? = null
            lock.withLock {
                when {
                    closed -> observedFailure = closeFailure
                    closing -> {
                        check(closeOwnerThread !== Thread.currentThread()) {
                            "authoritative runtime carrier close cannot re-enter"
                        }
                        while (!closed) closeCompleted.awaitUninterruptibly()
                        observedFailure = closeFailure
                    }
                    ownedLease == null -> {
                        closed = true
                        closeCompleted.signalAll()
                    }
                    else -> {
                        closing = true
                        closeOwnerThread = Thread.currentThread()
                        leaseToClose = ownedLease
                    }
                }
            }
            if (leaseToClose == null) {
                observedFailure?.let { throw it }
                return
            }

            var failure: Throwable? = null
            try {
                checkNotNull(leaseToClose).close()
            } catch (thrown: Throwable) {
                failure = thrown
            } finally {
                lock.withLock {
                    ownedLease = null
                    closeFailure = failure
                    closing = false
                    closed = true
                    closeOwnerThread = null
                    closeCompleted.signalAll()
                }
            }
            failure?.let { throw it }
        }
    }

    data class Unavailable(
        val reason: CredentialFilterConfigurationUnavailableReason,
        val artifactReason: FilterArtifactUnavailableReason? = null,
    ) : CredentialFilterConfigurationRuntimeRead
}

private sealed interface CredentialFilterConfigurationExactOpen {
    class Available(
        val record: AuthoritativeFilterConfigurationRecord,
        val exact: CredentialFilterArtifactExactRead.Available,
    ) : CredentialFilterConfigurationExactOpen

    data class Unavailable(
        val read: CredentialFilterConfigurationRead.Unavailable,
    ) : CredentialFilterConfigurationExactOpen
}

sealed interface CredentialFilterConfigurationInitializationResult {
    data class Initialized(val state: ConfirmedFilterState) :
        CredentialFilterConfigurationInitializationResult

    data class Existing(val state: ConfirmedFilterState) :
        CredentialFilterConfigurationInitializationResult

    data class Failed(val unavailable: CredentialFilterConfigurationRead.Unavailable) :
        CredentialFilterConfigurationInitializationResult
}

/**
 * Credential-protected authoritative filter configuration and generation-CAS publisher.
 *
 * An exact authoritative preflight avoids staging candidates that are already superseded.
 * Candidate installation remains outside the configuration authority, never moves checkpoint-18
 * `current.pointer`, and is followed by a mandatory locked recheck. Atomic replacement of this
 * store's one complete record is the sole authoritative commit point. Runtime policy activation
 * is intentionally outside this class and checkpoint.
 */
class CredentialFilterConfigurationStore internal constructor(
    rootDirectory: File,
    private val authority: CredentialFilterConfigurationAuthority,
    private val artifactStore: CredentialFilterArtifactPublicationStore,
    private val fileSystem: ArtifactPublicationFileSystem,
    private val maxRules: Int,
    private val checkpoint: CredentialFilterConfigurationCheckpoint =
        CredentialFilterConfigurationCheckpoint { },
    private val allowQaProbeSets: Boolean = false,
) : FilterMutationPublisher {
    private val root = rootDirectory.absoluteFile.toPath().normalize().toFile()
    private val authoritativeRecord = File(root, AUTHORITATIVE_RECORD_FILE)

    init {
        require(maxRules >= 0) { "trusted rule budget must not be negative" }
        authority.bind(root)
        fileSystem.ensureDirectory(root)
    }

    /**
     * Returns one exact authoritative snapshot serialized against record replacement.
     *
     * Reads remain cancellable while waiting for the process authority. Once admitted, the
     * durability barrier, bounded record read, exact artifact reopen, and descriptor close finish
     * non-cancellably.
     */
    suspend fun readAuthoritative(): CredentialFilterConfigurationRead =
        withConfigurationAuthorityAfterAdmission {
            readAuthoritativeInternal()
        }

    /**
     * Returns one close-owned exact-authority runtime capability.
     *
     * The returned carrier owns the mapped lease until
     * [CredentialFilterConfigurationRuntimeRead.Available.takeLease] transfers it. Callers must
     * close either the carrier or transferred lease.
     */
    suspend fun readAuthoritativeRuntime(): CredentialFilterConfigurationRuntimeRead =
        withConfigurationAuthorityAfterAdmission {
            readAuthoritativeRuntimeInternal()
        }

    /**
     * Installs and records the first exact state only when no authoritative record exists.
     *
     * Existing unreadable, corrupt, or incoherent state is never overwritten as a fresh install.
     * Caller ownership of [prepared] is unchanged.
     */
    suspend fun initialize(
        state: ConfirmedFilterState,
        prepared: PreparedFilterArtifact,
    ): CredentialFilterConfigurationInitializationResult {
        if (state.configuration.qaProbeSet != null && !allowQaProbeSets) {
            return CredentialFilterConfigurationInitializationResult.Failed(
                unavailable(CredentialFilterConfigurationUnavailableReason.QA_PROBES_DISABLED),
            )
        }
        if (!matchesPrepared(state, prepared)) {
            return CredentialFilterConfigurationInitializationResult.Failed(
                unavailable(CredentialFilterConfigurationUnavailableReason.IDENTITY_MISMATCH),
            )
        }
        when (
            val preflight = withConfigurationAuthorityAfterAdmission {
                readAuthoritativeInternal()
            }
        ) {
            is CredentialFilterConfigurationRead.Available ->
                return CredentialFilterConfigurationInitializationResult.Existing(
                    preflight.state,
                )
            is CredentialFilterConfigurationRead.Unavailable ->
                if (
                    preflight.reason !=
                    CredentialFilterConfigurationUnavailableReason.MISSING
                ) {
                    return CredentialFilterConfigurationInitializationResult.Failed(
                        preflight,
                    )
                }
        }
        when (val installed = artifactStore.install(prepared)) {
            is CredentialFilterArtifactInstallResult.Installed -> Unit
            is CredentialFilterArtifactInstallResult.Failed ->
                return withConfigurationAuthorityAfterAdmission {
                    reconcileInitializationInstallFailure(installed.reason)
                }
        }

        checkpoint.checkpoint(
            CredentialFilterConfigurationPhase.WAITING_FOR_FINAL_AUTHORITY,
        )
        return withConfigurationAuthorityAfterAdmission {
            initializeLocked(state)
        }
    }

    override suspend fun commit(
        expectedGeneration: Long,
        mutation: PreparedFilterMutation,
    ): FilterMutationPublishResult {
        val prepared = mutation.publicationArtifact
            ?: return FilterMutationPublishResult.Failed(
                FilterMutationFailure.IDENTITY_MISMATCH,
            )
        val candidate = mutation.candidate
        if (candidate.configuration.qaProbeSet != null && !allowQaProbeSets) {
            return FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED)
        }
        if (
            candidate.configurationIdentity.generation !=
            runCatching { Math.addExact(expectedGeneration, 1L) }.getOrNull() ||
            mutation.compilationReceipt.artifactSha256 !=
            candidate.verifiedArtifact.sha256 ||
            mutation.compilationReceipt.artifactIdentity !=
            candidate.verifiedArtifact.artifactIdentity ||
            !matchesPrepared(candidate, prepared)
        ) {
            return FilterMutationPublishResult.Failed(
                FilterMutationFailure.IDENTITY_MISMATCH,
            )
        }

        val preflight = withConfigurationAuthorityAfterAdmission {
            readAuthoritativeInternal()
        }
        val preflightState = when (preflight) {
            is CredentialFilterConfigurationRead.Unavailable ->
                return FilterMutationPublishResult.ProtectionUnavailable
            is CredentialFilterConfigurationRead.Available -> preflight.state
        }
        val preflightGeneration = preflightState.configurationIdentity.generation
        if (preflightGeneration != expectedGeneration) {
            return if (preflightGeneration > expectedGeneration) {
                FilterMutationPublishResult.Superseded(preflightState)
            } else {
                FilterMutationPublishResult.ProtectionUnavailable
            }
        }

        when (artifactStore.install(prepared)) {
            is CredentialFilterArtifactInstallResult.Installed -> Unit
            is CredentialFilterArtifactInstallResult.Failed ->
                return withConfigurationAuthorityAfterAdmission {
                    reconcileCommitInstallFailure(
                        expectedGeneration = expectedGeneration,
                        preflight = preflightState,
                    )
                }
        }

        checkpoint.checkpoint(
            CredentialFilterConfigurationPhase.WAITING_FOR_FINAL_AUTHORITY,
        )
        return withConfigurationAuthorityAfterAdmission {
            commitLocked(expectedGeneration, candidate, mutation.publicationAdmission)
        }
    }

    internal val authoritativeRecordFileForTesting: File
        get() = authoritativeRecord

    private fun initializeLocked(
        candidate: ConfirmedFilterState,
    ): CredentialFilterConfigurationInitializationResult {
        when (val existing = readAuthoritativeInternal()) {
            is CredentialFilterConfigurationRead.Available ->
                return CredentialFilterConfigurationInitializationResult.Existing(existing.state)
            is CredentialFilterConfigurationRead.Unavailable ->
                if (
                    existing.reason !=
                    CredentialFilterConfigurationUnavailableReason.MISSING
                ) {
                    return CredentialFilterConfigurationInitializationResult.Failed(existing)
                }
        }

        return try {
            writeAuthoritativeRecord(candidate)
            reconcileInitialization(candidate)
        } catch (error: Exception) {
            if (
                error.replacementMayHaveOccurred() &&
                !synchronizeAuthoritativeDirectory()
            ) {
                CredentialFilterConfigurationInitializationResult.Failed(
                    unavailable(
                        CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE,
                    ),
                )
            } else {
                reconcileInitialization(candidate)
            }
        }
    }

    private fun commitLocked(
        expectedGeneration: Long,
        candidate: ConfirmedFilterState,
        publicationAdmission: (() -> Boolean)? = null,
    ): FilterMutationPublishResult {
        val previous = when (val current = readAuthoritativeInternal()) {
            is CredentialFilterConfigurationRead.Available -> current.state
            is CredentialFilterConfigurationRead.Unavailable ->
                return FilterMutationPublishResult.ProtectionUnavailable
        }
        val durableGeneration = previous.configurationIdentity.generation
        if (durableGeneration != expectedGeneration) {
            return if (durableGeneration > expectedGeneration) {
                FilterMutationPublishResult.Superseded(previous)
            } else {
                FilterMutationPublishResult.ProtectionUnavailable
            }
        }

        // Recheck under the final configuration authority, after installation and generation CAS.
        // A retired QA visit must not change the sole authoritative record even when an owned
        // compiler or non-cancellable storage operation completes after caller cancellation.
        if (publicationAdmission?.invoke() == false) {
            return FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED)
        }
        return try {
            writeAuthoritativeRecord(candidate)
            reconcileCommit(expectedGeneration, previous, candidate)
        } catch (error: Exception) {
            if (
                error.replacementMayHaveOccurred() &&
                !synchronizeAuthoritativeDirectory()
            ) {
                FilterMutationPublishResult.ProtectionUnavailable
            } else {
                reconcileCommit(expectedGeneration, previous, candidate)
            }
        }
    }

    private fun reconcileInitialization(
        candidate: ConfirmedFilterState,
    ): CredentialFilterConfigurationInitializationResult =
        when (val authoritative = readAuthoritativeInternal()) {
            is CredentialFilterConfigurationRead.Available ->
                if (authoritative.state == candidate) {
                    CredentialFilterConfigurationInitializationResult.Initialized(
                        authoritative.state,
                    )
                } else {
                    CredentialFilterConfigurationInitializationResult.Existing(
                        authoritative.state,
                    )
                }
            is CredentialFilterConfigurationRead.Unavailable ->
                CredentialFilterConfigurationInitializationResult.Failed(authoritative)
        }

    private fun reconcileInitializationInstallFailure(
        artifactReason: FilterArtifactUnavailableReason,
    ): CredentialFilterConfigurationInitializationResult =
        when (val authoritative = readAuthoritativeInternal()) {
            is CredentialFilterConfigurationRead.Available ->
                CredentialFilterConfigurationInitializationResult.Existing(
                    authoritative.state,
                )
            is CredentialFilterConfigurationRead.Unavailable ->
                if (
                    authoritative.reason ==
                    CredentialFilterConfigurationUnavailableReason.MISSING
                ) {
                    CredentialFilterConfigurationInitializationResult.Failed(
                        unavailable(
                            CredentialFilterConfigurationUnavailableReason.ARTIFACT_UNAVAILABLE,
                            artifactReason,
                        ),
                    )
                } else {
                    CredentialFilterConfigurationInitializationResult.Failed(authoritative)
                }
        }

    private fun reconcileCommitInstallFailure(
        expectedGeneration: Long,
        preflight: ConfirmedFilterState,
    ): FilterMutationPublishResult =
        when (val authoritative = readAuthoritativeInternal()) {
            is CredentialFilterConfigurationRead.Unavailable ->
                FilterMutationPublishResult.ProtectionUnavailable
            is CredentialFilterConfigurationRead.Available -> {
                val winner = authoritative.state
                when {
                    winner.configurationIdentity.generation > expectedGeneration ->
                        FilterMutationPublishResult.Superseded(winner)
                    winner == preflight ->
                        FilterMutationPublishResult.Failed(
                            FilterMutationFailure.PUBLISH_FAILED,
                        )
                    else -> FilterMutationPublishResult.ProtectionUnavailable
                }
            }
        }

    private fun reconcileCommit(
        expectedGeneration: Long,
        previous: ConfirmedFilterState,
        candidate: ConfirmedFilterState,
    ): FilterMutationPublishResult =
        when (val authoritative = readAuthoritativeInternal()) {
            is CredentialFilterConfigurationRead.Unavailable ->
                FilterMutationPublishResult.ProtectionUnavailable
            is CredentialFilterConfigurationRead.Available -> {
                val winner = authoritative.state
                when {
                    winner == candidate -> FilterMutationPublishResult.Committed
                    winner.configurationIdentity.generation > expectedGeneration ->
                        FilterMutationPublishResult.Superseded(winner)
                    winner == previous ->
                        FilterMutationPublishResult.Failed(FilterMutationFailure.COMMIT_FAILED)
                    else -> FilterMutationPublishResult.ProtectionUnavailable
                }
            }
        }

    private fun readAuthoritativeInternal(): CredentialFilterConfigurationRead =
        when (val opened = openAuthoritativeExactInternal()) {
            is CredentialFilterConfigurationExactOpen.Unavailable -> opened.read
            is CredentialFilterConfigurationExactOpen.Available ->
                restoreExactState(opened.record, opened.exact)
        }

    private fun readAuthoritativeRuntimeInternal(): CredentialFilterConfigurationRuntimeRead =
        when (val opened = openAuthoritativeExactInternal()) {
            is CredentialFilterConfigurationExactOpen.Unavailable ->
                CredentialFilterConfigurationRuntimeRead.Unavailable(
                    reason = opened.read.reason,
                    artifactReason = opened.read.artifactReason,
                )
            is CredentialFilterConfigurationExactOpen.Available ->
                restoreExactRuntime(opened.record, opened.exact)
        }

    private fun openAuthoritativeExactInternal(): CredentialFilterConfigurationExactOpen {
        val recordExists = try {
            fileSystem.existsNoFollow(authoritativeRecord)
        } catch (_: Exception) {
            return CredentialFilterConfigurationExactOpen.Unavailable(
                unavailable(CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE),
            )
        }
        if (!recordExists) {
            return CredentialFilterConfigurationExactOpen.Unavailable(
                unavailable(CredentialFilterConfigurationUnavailableReason.MISSING),
            )
        }
        // A prior same-process rename may be visible even when its directory sync failed.
        // The process authority keeps that recovery requirement armed until one complete sync.
        if (!synchronizeAuthoritativeDirectory()) {
            return CredentialFilterConfigurationExactOpen.Unavailable(
                unavailable(CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE),
            )
        }
        val bytes = try {
            fileSystem.readBoundedRegularFile(
                authoritativeRecord,
                AuthoritativeFilterConfigurationRecordCodec.MAX_RECORD_BYTES,
            )
        } catch (_: IOException) {
            return CredentialFilterConfigurationExactOpen.Unavailable(
                unavailable(CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE),
            )
        } catch (_: Exception) {
            return CredentialFilterConfigurationExactOpen.Unavailable(
                unavailable(CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE),
            )
        }
        val record = AuthoritativeFilterConfigurationRecordCodec.decodeOrNull(bytes)
            ?: return CredentialFilterConfigurationExactOpen.Unavailable(
                unavailable(CredentialFilterConfigurationUnavailableReason.RECORD_INVALID),
            )
        val exact = artifactStore.readExactGeneration(
            artifactSha256 = record.artifactSha256,
            identitySha256 = record.artifactIdentitySha256,
            maxRules = maxRules,
        )
        return when (exact) {
            is CredentialFilterArtifactExactRead.Unavailable ->
                CredentialFilterConfigurationExactOpen.Unavailable(
                    unavailable(
                        CredentialFilterConfigurationUnavailableReason.ARTIFACT_UNAVAILABLE,
                        exact.reason,
                    ),
                )
            is CredentialFilterArtifactExactRead.Available ->
                CredentialFilterConfigurationExactOpen.Available(record, exact)
        }
    }

    private fun restoreExactState(
        record: AuthoritativeFilterConfigurationRecord,
        exact: CredentialFilterArtifactExactRead.Available,
    ): CredentialFilterConfigurationRead {
        val restored = try {
            val verified = PersistedFilterArtifactRestorationVerifier.verifyOrNull(
                reference = exact.reference,
                artifact = exact.artifact,
            )
            if (verified == null) {
                unavailable(
                    CredentialFilterConfigurationUnavailableReason.IDENTITY_MISMATCH,
                )
            } else {
                try {
                    CredentialFilterConfigurationRead.Available(
                        ConfirmedFilterState(
                            configuration = record.configuration,
                            configurationIdentity = record.configurationIdentity,
                            credentialUnlockRequired = record.credentialUnlockRequired,
                            verifiedArtifact = verified,
                        ),
                    )
                } catch (_: IllegalArgumentException) {
                    unavailable(
                        CredentialFilterConfigurationUnavailableReason.IDENTITY_MISMATCH,
                    )
                }
            }
        } catch (_: Exception) {
            unavailable(
                CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE,
            )
        }
        return try {
            exact.close()
            restored
        } catch (_: Exception) {
            unavailable(
                CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE,
            )
        }
    }

    private fun restoreExactRuntime(
        record: AuthoritativeFilterConfigurationRecord,
        exact: CredentialFilterArtifactExactRead.Available,
    ): CredentialFilterConfigurationRuntimeRead {
        // A debug-created compiled artifact is never a release runtime capability. Metadata
        // stays readable so the credential graph can recompile an ordinary replacement safely.
        if (record.configuration.qaProbeSet != null && !allowQaProbeSets) {
            return closeRuntimeUnavailable(exact, CredentialFilterConfigurationUnavailableReason.QA_PROBES_DISABLED)
        }
        val verified = try {
            PersistedFilterArtifactRestorationVerifier.verifyOrNull(
                reference = exact.reference,
                artifact = exact.artifact,
            )
        } catch (failure: Throwable) {
            if (failure is Exception) {
                return closeRuntimeUnavailable(
                    exact = exact,
                    reason = CredentialFilterConfigurationUnavailableReason.IDENTITY_MISMATCH,
                )
            }
            closeSuppressing(exact, failure)
            throw failure
        } ?: return closeRuntimeUnavailable(
            exact = exact,
            reason = CredentialFilterConfigurationUnavailableReason.IDENTITY_MISMATCH,
        )

        val state = try {
            ConfirmedFilterState(
                configuration = record.configuration,
                configurationIdentity = record.configurationIdentity,
                credentialUnlockRequired = record.credentialUnlockRequired,
                verifiedArtifact = verified,
            )
        } catch (failure: Throwable) {
            if (failure is IllegalArgumentException) {
                return closeRuntimeUnavailable(
                    exact = exact,
                    reason = CredentialFilterConfigurationUnavailableReason.IDENTITY_MISMATCH,
                )
            }
            closeSuppressing(exact, failure)
            throw failure
        }

        val artifact = try {
            exact.takeArtifact()
        } catch (failure: Throwable) {
            if (failure is Exception) {
                return closeRuntimeUnavailable(
                    exact = exact,
                    reason = CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE,
                )
            }
            closeSuppressing(exact, failure)
            throw failure
        }
        val lease = try {
            VerifiedFilterRuntimeLease.takeOpenedOrNull(
                confirmedState = state,
                artifact = artifact,
            )
        } catch (failure: Throwable) {
            if (failure is Exception) {
                return closeArtifactRuntimeUnavailable(
                    artifact = artifact,
                    reason = CredentialFilterConfigurationUnavailableReason.IDENTITY_MISMATCH,
                )
            }
            closeSuppressing(artifact, failure)
            throw failure
        }
        if (lease == null) {
            return closeArtifactRuntimeUnavailable(
                artifact = artifact,
                reason = CredentialFilterConfigurationUnavailableReason.IDENTITY_MISMATCH,
            )
        }
        return try {
            CredentialFilterConfigurationRuntimeRead.Available(state, lease)
        } catch (failure: Throwable) {
            closeSuppressing(lease, failure)
            throw failure
        }
    }

    private fun closeRuntimeUnavailable(
        exact: CredentialFilterArtifactExactRead.Available,
        reason: CredentialFilterConfigurationUnavailableReason,
        artifactReason: FilterArtifactUnavailableReason? = null,
    ): CredentialFilterConfigurationRuntimeRead.Unavailable =
        try {
            exact.close()
            CredentialFilterConfigurationRuntimeRead.Unavailable(reason, artifactReason)
        } catch (_: Exception) {
            CredentialFilterConfigurationRuntimeRead.Unavailable(
                CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE,
            )
        }

    private fun closeArtifactRuntimeUnavailable(
        artifact: AutoCloseable,
        reason: CredentialFilterConfigurationUnavailableReason,
    ): CredentialFilterConfigurationRuntimeRead.Unavailable =
        try {
            artifact.close()
            CredentialFilterConfigurationRuntimeRead.Unavailable(reason)
        } catch (_: Exception) {
            CredentialFilterConfigurationRuntimeRead.Unavailable(
                CredentialFilterConfigurationUnavailableReason.STORAGE_UNAVAILABLE,
            )
        }

    private fun closeSuppressing(
        closeable: AutoCloseable,
        primary: Throwable,
    ) {
        try {
            closeable.close()
        } catch (closeFailure: Throwable) {
            if (primary !== closeFailure) primary.addSuppressed(closeFailure)
        }
    }

    private fun writeAuthoritativeRecord(state: ConfirmedFilterState) {
        val record = AuthoritativeFilterConfigurationRecord.fromConfirmedState(state)
        val bytes = AuthoritativeFilterConfigurationRecordCodec.encode(record)
        var temporary: File? = File(
            root,
            "$AUTHORITATIVE_RECORD_TEMP_PREFIX${UUID.randomUUID()}",
        )
        var replacementMayHaveOccurred = false
        try {
            fileSystem.writeCreateNewAndSync(checkNotNull(temporary), bytes)
            checkpoint.checkpoint(CredentialFilterConfigurationPhase.RECORD_TEMP_SYNCED)
            authority.authoritativeDirectorySyncRequired = true
            replacementMayHaveOccurred = true
            fileSystem.replaceFile(checkNotNull(temporary), authoritativeRecord)
            temporary = null
            checkpoint.checkpoint(CredentialFilterConfigurationPhase.RECORD_REPLACED)
            synchronizeAuthoritativeDirectoryOrThrow()
            checkpoint.checkpoint(
                CredentialFilterConfigurationPhase.RECORD_DIRECTORY_SYNCED,
            )
        } catch (error: Exception) {
            throw AuthoritativeRecordWriteException(
                replacementMayHaveOccurred = replacementMayHaveOccurred,
                cause = error,
            )
        } finally {
            temporary?.let(fileSystem::deleteRecursivelyBestEffort)
        }
    }

    private fun synchronizeAuthoritativeDirectoryOrThrow() {
        if (!authority.authoritativeDirectorySyncRequired) return
        fileSystem.syncDirectory(root)
        authority.authoritativeDirectorySyncRequired = false
    }

    private fun synchronizeAuthoritativeDirectory(): Boolean =
        try {
            synchronizeAuthoritativeDirectoryOrThrow()
            true
        } catch (_: Exception) {
            false
        }

    /**
     * Remains cancellable through mutex admission, then completes storage and result recovery
     * non-cancellably. The outer non-cancellable context keeps the admitted result available to
     * the coordinator; the nested IO dispatch returns into that active context before caller
     * cancellation is observed.
     */
    private suspend fun <T> withConfigurationAuthorityAfterAdmission(
        block: () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        val admissionContext = currentCoroutineContext()
        authority.mutex.lock()
        try {
            admissionContext.ensureActive()
            return withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    block()
                }
            }
        } finally {
            authority.mutex.unlock()
        }
    }

    private fun matchesPrepared(
        state: ConfirmedFilterState,
        prepared: PreparedFilterArtifact,
    ): Boolean =
        prepared.compilationReceipt.artifactSha256 == state.verifiedArtifact.sha256 &&
            prepared.compilationReceipt.artifactIdentity ==
            state.verifiedArtifact.artifactIdentity

    private fun unavailable(
        reason: CredentialFilterConfigurationUnavailableReason,
        artifactReason: FilterArtifactUnavailableReason? = null,
    ): CredentialFilterConfigurationRead.Unavailable =
        CredentialFilterConfigurationRead.Unavailable(reason, artifactReason)

    companion object {
        /**
         * Creates the production repository under the app's credential-protected files directory.
         *
         * [maxRules] must come from the independently verified catalog/compile authority. This
         * storage boundary never accepts a persisted rule budget.
         */
        fun credentialProtected(
            context: Context,
            maxRules: Int,
        ): CredentialFilterConfigurationStore {
            val artifactStore =
                CredentialFilterArtifactPublicationStore.credentialProtected(context)
            return CredentialFilterConfigurationStore(
                rootDirectory =
                    CredentialFilterArtifactPublicationStore.credentialProtectedRoot(context),
                authority = PRODUCTION_AUTHORITY,
                artifactStore = artifactStore,
                fileSystem = AndroidOsArtifactPublicationFileSystem,
                maxRules = maxRules,
                checkpoint = CredentialFilterConfigurationCheckpoint { },
                allowQaProbeSets = com.lavasec.app.BuildConfig.DEBUG,
            )
        }

        private const val AUTHORITATIVE_RECORD_FILE =
            "authoritative.filter-configuration"
        private const val AUTHORITATIVE_RECORD_TEMP_PREFIX =
            ".authoritative.filter-configuration-"
        private val PRODUCTION_AUTHORITY = CredentialFilterConfigurationAuthority()
    }
}

private class AuthoritativeRecordWriteException(
    val replacementMayHaveOccurred: Boolean,
    cause: Exception,
) : IOException(cause)

private fun Exception.replacementMayHaveOccurred(): Boolean =
    (this as? AuthoritativeRecordWriteException)?.replacementMayHaveOccurred == true
