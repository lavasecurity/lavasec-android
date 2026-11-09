package com.lavasec.app.platform

import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterArtifactIdentity
import com.lavasec.app.foundation.filter.FilterArtifactOpenResult
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.CanonicalSha256Encoder
import com.lavasec.app.foundation.filter.canonicalSha256
import java.io.File
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Untrusted persisted expectation for the exact artifact bytes and embedded identity. */
class FilterArtifactReference(
    sha256: String,
    artifactIdentity: FilterArtifactIdentity,
) {
    val sha256: String
    val artifactIdentity: FilterArtifactIdentity

    init {
        require(SHA_256.matches(sha256)) { "artifact SHA-256 must contain exactly 64 hex digits" }
        this.sha256 = sha256.lowercase(Locale.ROOT)
        this.artifactIdentity = immutableIdentity(artifactIdentity)
    }

    override fun equals(other: Any?): Boolean =
        other is FilterArtifactReference &&
            sha256 == other.sha256 &&
            artifactIdentity == other.artifactIdentity

    override fun hashCode(): Int = 31 * sha256.hashCode() + artifactIdentity.hashCode()

    override fun toString(): String =
        "FilterArtifactReference(sha256=$sha256, artifactIdentity=$artifactIdentity)"
}

/** Post-open proof minted only from a validated, still-open [FilterArtifact]. */
class VerifiedFilterArtifact private constructor(
    val sha256: String,
    val artifactIdentity: FilterArtifactIdentity,
) {
    override fun equals(other: Any?): Boolean =
        other is VerifiedFilterArtifact &&
            sha256 == other.sha256 &&
            artifactIdentity == other.artifactIdentity

    override fun hashCode(): Int = 31 * sha256.hashCode() + artifactIdentity.hashCode()

    override fun toString(): String =
        "VerifiedFilterArtifact(sha256=$sha256, artifactIdentity=$artifactIdentity)"

    companion object {
        internal fun fromOpened(
            reference: FilterArtifactReference,
            artifact: FilterArtifact,
        ): VerifiedFilterArtifact {
            check(artifact.channelIsOpenForTesting) { "verified artifact must still be open" }
            check(artifact.sha256 == reference.sha256) {
                "opened artifact digest must match its reference"
            }
            check(artifact.identity == reference.artifactIdentity) {
                "opened artifact identity must match its reference"
            }
            return VerifiedFilterArtifact(reference.sha256, reference.artifactIdentity)
        }
    }
}

/** Supplies HMAC-SHA256 authentication with one specific Direct Boot publication key. */
fun interface DirectBootSnapshotAuthenticator {
    fun hmacSha256OrNull(commitmentSha256: String): String?
}

/** Opaque, authority-current permit minted after invalidating every prior publication key. */
class DirectBootSnapshotPublicationEpoch private constructor(
    private val authority: DirectBootSnapshotFreshnessAuthority,
    private val authenticator: DirectBootSnapshotAuthenticator,
) {
    private enum class Phase {
        FRESH,
        COMMIT_CLAIMED,
        COMMITTED,
        SEALED,
    }

    private var phase = Phase.FRESH
    private var desiredProtectionIntent: Boolean? = null

    internal fun claimAuthoritativeCommit(desired: Boolean): Boolean {
        if (phase != Phase.FRESH) return false
        desiredProtectionIntent = desired
        phase = Phase.COMMIT_CLAIMED
        return true
    }

    internal fun confirmAuthoritativeCommit(
        state: DirectBootAppState,
    ): DirectBootCommittedSnapshotPublication? {
        if (
            phase != Phase.COMMIT_CLAIMED ||
            state.protectionIntent != desiredProtectionIntent
        ) {
            return null
        }
        phase = Phase.COMMITTED
        return DirectBootCommittedSnapshotPublication.create(authority, this, state)
    }

    internal fun authenticateCommittedOnceOrNull(
        publication: DirectBootCommittedSnapshotPublication,
        state: DirectBootAppState,
        commitmentSha256: String,
    ): String? {
        if (
            phase != Phase.COMMITTED ||
            publication.epoch !== this ||
            publication.committedState != state
        ) {
            return null
        }
        phase = Phase.SEALED
        return authenticator.hmacSha256OrNull(commitmentSha256)
    }

    internal companion object {
        fun create(
            authority: DirectBootSnapshotFreshnessAuthority,
            authenticator: DirectBootSnapshotAuthenticator,
        ): DirectBootSnapshotPublicationEpoch =
            DirectBootSnapshotPublicationEpoch(authority, authenticator)
    }
}

/** One-shot seal capability minted only after the authoritative commit callback succeeds. */
class DirectBootCommittedSnapshotPublication private constructor(
    private val authority: DirectBootSnapshotFreshnessAuthority,
    internal val epoch: DirectBootSnapshotPublicationEpoch,
    internal val committedState: DirectBootAppState,
) {
    internal fun authenticateOnceOrNull(
        state: DirectBootAppState,
        commitmentSha256: String,
    ): String? = authority.authenticateCommittedOnceOrNull(
        this,
        state,
        commitmentSha256,
    )?.value

    internal companion object {
        fun create(
            authority: DirectBootSnapshotFreshnessAuthority,
            epoch: DirectBootSnapshotPublicationEpoch,
            committedState: DirectBootAppState,
        ): DirectBootCommittedSnapshotPublication =
            DirectBootCommittedSnapshotPublication(authority, epoch, committedState)
    }
}

/** Result of attempting to replace the current fixed-alias Direct Boot authentication key. */
sealed interface DirectBootSnapshotKeyRotationResult {
    /** No key was invalidated; reject the new authoritative change and retain the prior mirror. */
    data object RejectedBeforeInvalidation : DirectBootSnapshotKeyRotationResult

    /** The old key is irrecoverably invalid, but a replacement key could not be installed. */
    data object InvalidatedWithoutReplacement : DirectBootSnapshotKeyRotationResult

    class Rotated internal constructor(
        internal val authenticator: DirectBootSnapshotAuthenticator,
    ) : DirectBootSnapshotKeyRotationResult
}

/** Internal start result retaining whether a failed rotation invalidated the prior mirror key. */
internal sealed interface DirectBootSnapshotPublicationStart {
    data object RejectedBeforeInvalidation : DirectBootSnapshotPublicationStart

    data object InvalidatedWithoutReplacement : DirectBootSnapshotPublicationStart

    class Ready internal constructor(
        internal val epoch: DirectBootSnapshotPublicationEpoch,
    ) : DirectBootSnapshotPublicationStart
}

/** Internal wrapper prevents the authority surface from exposing a raw tag-signing method. */
internal class DirectBootSnapshotAuthenticationTag private constructor(
    internal val value: String,
) {
    companion object {
        internal fun create(value: String): DirectBootSnapshotAuthenticationTag =
            DirectBootSnapshotAuthenticationTag(value)
    }
}

/**
 * Serializes forward-only Direct Boot snapshot freshness independently of configuration generation.
 *
 * [beginPublication] invalidates the prior fixed-alias key before returning, then installs
 * a fresh non-exportable HMAC key usable before credential unlock. Once it returns an epoch, every
 * older envelope must fail current-key verification, including when sealing or the subsequent
 * atomic device-protected file replacement fails. Callers begin publication before committing the
 * authoritative state change; the typed start result preserves whether a failed rotation happened
 * before or after invalidation.
 *
 * The production Android adapter rotates one non-auth-bound AndroidKeyStore alias and serializes
 * publications. An interleaved or interrupted publication may make the mirror unavailable but must
 * never re-admit an older key. A counter or digest stored beside the envelope is not an
 * implementation of this authority. A proven failure before invalidation rejects the state change
 * and may retain the coherent prior mirror; any failure or uncertainty after invalidation fails
 * closed. Existing mirror data with a missing key is never treated as a fresh-install bootstrap or
 * automatically re-signed: backup/migration requires an unlocked authoritative republish or safe
 * reset, and mirror/key metadata is excluded from backup. This detects rolled-back app files while
 * the current installation's Keystore state survives; it does not claim a universal hardware
 * monotonic counter.
 */
abstract class DirectBootSnapshotFreshnessAuthority {
    private val publicationLock = Any()
    private val publicationWriteMutex = Mutex()
    private var currentEpoch: DirectBootSnapshotPublicationEpoch? = null

    protected fun rotated(
        authenticator: DirectBootSnapshotAuthenticator,
    ): DirectBootSnapshotKeyRotationResult = DirectBootSnapshotKeyRotationResult.Rotated(
        authenticator,
    )

    /**
     * Deletes the prior key before installing a replacement and reports whether invalidation
     * definitely did not happen, definitely happened without replacement, or fully succeeded.
     */
    protected abstract fun rotateCurrentKey(): DirectBootSnapshotKeyRotationResult

    /** Supplies only the current verifier key; no public API returns a tag-signing oracle. */
    protected abstract fun currentAuthenticatorOrNull(): DirectBootSnapshotAuthenticator?

    /**
     * Serializes the entire invalidate -> authoritative commit -> mirror publication transaction
     * for every repository adapter sharing this authority, including separate coordinator objects.
     */
    internal suspend fun <T> serializePublicationWrite(
        block: suspend () -> T,
    ): T = publicationWriteMutex.withLock { block() }

    internal fun beginPublication(): DirectBootSnapshotPublicationStart =
        synchronized(publicationLock) {
            when (val rotation = rotateCurrentKey()) {
                DirectBootSnapshotKeyRotationResult.RejectedBeforeInvalidation ->
                    DirectBootSnapshotPublicationStart.RejectedBeforeInvalidation
                DirectBootSnapshotKeyRotationResult.InvalidatedWithoutReplacement -> {
                    currentEpoch = null
                    DirectBootSnapshotPublicationStart.InvalidatedWithoutReplacement
                }
                is DirectBootSnapshotKeyRotationResult.Rotated -> {
                    DirectBootSnapshotPublicationEpoch.create(this, rotation.authenticator)
                        .also { currentEpoch = it }
                        .let { DirectBootSnapshotPublicationStart.Ready(it) }
                }
            }
        }

    internal fun claimAuthoritativeCommit(
        epoch: DirectBootSnapshotPublicationEpoch,
        desired: Boolean,
    ): Boolean = synchronized(publicationLock) {
        currentEpoch === epoch && epoch.claimAuthoritativeCommit(desired)
    }

    internal fun confirmAuthoritativeCommit(
        epoch: DirectBootSnapshotPublicationEpoch,
        state: DirectBootAppState,
    ): DirectBootCommittedSnapshotPublication? = synchronized(publicationLock) {
        if (currentEpoch !== epoch) return@synchronized null
        epoch.confirmAuthoritativeCommit(state)
    }

    internal fun authenticateCommittedOnceOrNull(
        publication: DirectBootCommittedSnapshotPublication,
        state: DirectBootAppState,
        commitmentSha256: String,
    ): DirectBootSnapshotAuthenticationTag? = synchronized(publicationLock) {
        if (currentEpoch !== publication.epoch) return@synchronized null
        publication.epoch.authenticateCommittedOnceOrNull(
            publication,
            state,
            commitmentSha256,
        )?.let(DirectBootSnapshotAuthenticationTag::create)
    }

    internal fun verifyCurrent(
        commitmentSha256: String,
        authenticationTag: String,
    ): Boolean = synchronized(publicationLock) {
        val expectedTag = currentAuthenticatorOrNull()
            ?.hmacSha256OrNull(commitmentSha256)
            ?: return@synchronized false
        if (!LOWERCASE_SHA_256.matches(expectedTag)) return@synchronized false
        constantTimeHexEquals(expectedTag, authenticationTag)
    }
}

/** Untrusted serialized Direct Boot state, exact artifact expectation, and authentication tag. */
data class PersistedDirectBootConfigurationSnapshot(
    val state: DirectBootAppState,
    val artifact: FilterArtifactReference,
    val authenticationTag: String,
)

/** Authenticated behavior state bound to the exact artifact bytes expected at Direct Boot. */
class DirectBootConfigurationSnapshot private constructor(
    val state: DirectBootAppState,
    val artifact: FilterArtifactReference,
) {
    companion object {
        /**
         * Creates one untrusted serialized envelope after freshness invalidation and authoritative
         * state commit. The caller then atomically replaces the device-protected mirror file.
         */
        fun sealForPublication(
            state: DirectBootAppState,
            artifact: FilterArtifactReference,
            committedPublication: DirectBootCommittedSnapshotPublication,
        ): PersistedDirectBootConfigurationSnapshot {
            validateStateArtifactCoherence(state, artifact)
            val commitment = canonicalDirectBootSnapshotCommitmentSha256(state, artifact)
            val authenticationTag = requireNotNull(
                committedPublication.authenticateOnceOrNull(state, commitment),
            ) { "Direct Boot committed publication is stale, mismatched, or already consumed" }
            require(LOWERCASE_SHA_256.matches(authenticationTag)) {
                "Direct Boot authentication tag must be lowercase HMAC-SHA256"
            }
            return PersistedDirectBootConfigurationSnapshot(
                state = state,
                artifact = artifact,
                authenticationTag = authenticationTag,
            )
        }

        /**
         * Admits a persisted envelope only when every behavior field and the exact artifact digest
         * have an intact HMAC under the current publication key. Missing, stale, or mismatched keys,
         * malformed state, and mismatched tags fail closed.
         */
        fun restore(
            persisted: PersistedDirectBootConfigurationSnapshot,
            freshnessAuthority: DirectBootSnapshotFreshnessAuthority,
        ): DirectBootConfigurationSnapshot? {
            if (!LOWERCASE_SHA_256.matches(persisted.authenticationTag)) return null
            val verified = try {
                validateStateArtifactCoherence(persisted.state, persisted.artifact)
                val commitment = canonicalDirectBootSnapshotCommitmentSha256(
                    persisted.state,
                    persisted.artifact,
                )
                freshnessAuthority.verifyCurrent(commitment, persisted.authenticationTag)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
            if (!verified) return null
            return DirectBootConfigurationSnapshot(persisted.state, persisted.artifact)
        }
    }
}

fun interface DirectBootConfigurationReader {
    /** Returns only an envelope already admitted by [DirectBootConfigurationSnapshot.restore]. */
    fun readOrNull(): DirectBootConfigurationSnapshot?
}

sealed interface DirectBootArtifactReadResult {
    /** Owns both the validated artifact and its post-open witness. */
    class Available private constructor(
        val state: DirectBootAppState,
        val artifact: FilterArtifact,
        val verifiedArtifact: VerifiedFilterArtifact,
    ) : DirectBootArtifactReadResult, AutoCloseable {
        override fun close() {
            artifact.close()
        }

        companion object {
            internal fun open(
                file: File,
                snapshot: DirectBootConfigurationSnapshot,
            ): DirectBootArtifactReadResult {
                val reference = snapshot.artifact
                val opened = when (val result = FilterArtifact.openResult(file, reference.sha256)) {
                    is FilterArtifactOpenResult.Available -> result.artifact
                    is FilterArtifactOpenResult.Unavailable -> return Unavailable(
                        result.reason.toProtectionFailure(),
                    )
                }
                if (opened.identity != reference.artifactIdentity) {
                    opened.close()
                    return Unavailable(ProtectionFailure.ArtifactMismatched)
                }
                return try {
                    Available(
                        state = snapshot.state,
                        artifact = opened,
                        verifiedArtifact = VerifiedFilterArtifact.fromOpened(reference, opened),
                    )
                } catch (error: Throwable) {
                    opened.close()
                    throw error
                }
            }
        }
    }

    data class Unavailable(val failure: ProtectionFailure) : DirectBootArtifactReadResult
}

fun interface DirectBootArtifactReader {
    fun read(snapshot: DirectBootConfigurationSnapshot): DirectBootArtifactReadResult

    companion object {
        /**
         * Builds a reader that can open bytes only after authenticated snapshot admission, then
         * verifies the exact digest and complete embedded artifact identity.
         */
        fun verifying(fileFor: (FilterArtifactReference) -> File): DirectBootArtifactReader =
            DirectBootArtifactReader { snapshot ->
                val reference = snapshot.artifact
                val file = try {
                    fileFor(reference)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@DirectBootArtifactReader DirectBootArtifactReadResult.Unavailable(
                        ProtectionFailure.ArtifactCorrupt,
                    )
                }
                DirectBootArtifactReadResult.Available.open(file, snapshot)
            }
    }
}

private fun FilterArtifactUnavailableReason.toProtectionFailure(): ProtectionFailure = when (this) {
    FilterArtifactUnavailableReason.ARTIFACT_MISSING -> ProtectionFailure.ArtifactMissing
    FilterArtifactUnavailableReason.IDENTITY_MISMATCH,
    FilterArtifactUnavailableReason.SOURCE_COVERAGE_MISMATCH,
    -> ProtectionFailure.ArtifactMismatched
    else -> ProtectionFailure.ArtifactCorrupt
}

private fun immutableIdentity(identity: FilterArtifactIdentity): FilterArtifactIdentity =
    identity.copy(
        selectedSources = Collections.unmodifiableList(ArrayList(identity.selectedSources)),
    )

private fun validateStateArtifactCoherence(
    state: DirectBootAppState,
    artifact: FilterArtifactReference,
) {
    require(!state.credentialUnlockRequired) {
        "credential-unlock-required state cannot be represented in Direct Boot storage"
    }
    val configuration = requireNotNull(state.configurationIdentity) {
        "Direct Boot artifact requires a configuration identity"
    }
    val artifactConfiguration = artifact.artifactIdentity.configuration
    require(
        configuration.generation == artifactConfiguration.generation &&
            configuration.fingerprint == artifactConfiguration.fingerprint,
    ) { "Direct Boot state and artifact identity must match" }
}

private fun canonicalDirectBootSnapshotCommitmentSha256(
    state: DirectBootAppState,
    artifact: FilterArtifactReference,
): String = canonicalSha256("direct-boot-snapshot-v1") {
    writeString(artifact.sha256)
    writeBoolean(state.onboardingCompleted)
    writeBoolean(state.protectionIntent)
    writeBoolean(state.useDeviceDns)
    writeBoolean(state.useEncryptedFallback)
    writeNullableString(state.encryptedFallbackId)
    writeNullableString(state.activeProfileId)
    val configuration = requireNotNull(state.configurationIdentity)
    writeLong(configuration.generation)
    writeString(configuration.fingerprint)
    writeBoolean(state.credentialUnlockRequired)
    // Appended only when present, so every built-in resolver keeps its exact commitment while a
    // custom DoH URL or DoT endpoint is bound into the freshness proof.
    state.customDohUrl?.let(::writeString)
    state.customDotEndpoint?.let(::writeString)
    state.customDoqEndpoint?.let { writeString("custom-doq"); writeString(it) }
    state.customName?.let { writeString("custom-name"); writeString(it) }
    state.customSecondaryEndpoint?.let { writeString("custom-secondary"); writeString(it) }
    state.savedTiers?.let { tiers ->
        writeString("retained-resolver-tiers-v1")
        writeInt(tiers.size)
        tiers.forEach { tier ->
            writeString(tier.entry.providerId.rawId)
            writeString(tier.entry.transport.name)
            writeNullableString(tier.entry.customDohUrl)
            writeNullableString(tier.entry.customDotEndpoint)
            writeNullableString(tier.entry.customDoqEndpoint)
            writeNullableString(tier.entry.customName)
            writeNullableString(tier.entry.customSecondaryEndpoint)
            writeBoolean(tier.isEnabled)
            writeNullableString(tier.retainedName)
            tier.entry.customPlainEndpoint?.let { writeString("custom-plain"); writeString(it) }
        }
    }
}

private fun CanonicalSha256Encoder.writeNullableString(
    value: String?,
) {
    writeBoolean(value != null)
    if (value != null) writeString(value)
}

private fun constantTimeHexEquals(first: String, second: String): Boolean =
    MessageDigest.isEqual(first.hexBytes(), second.hexBytes())

private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

private val SHA_256 = Regex("[0-9a-fA-F]{64}")
private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
