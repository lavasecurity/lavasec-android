package com.lavasec.app.platform

import com.lavasec.app.foundation.chained.ChainedConfigurationIssue
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.foundation.chained.ChainedUpstreamRotation
import kotlinx.coroutines.flow.Flow

/**
 * The generation-stamped identity of one committed chained-upstream rotation.
 *
 * The same shape as [ConfigurationIdentity] (monotonic generation plus a canonical fingerprint) so
 * the two durable authorities are reasoned about the same way, but a distinct type: the product
 * configuration and the chained upstream are independent records and a value of one must not be
 * usable as a key into the other.
 */
data class ChainedUpstreamIdentity(
    val generation: Long,
    val fingerprint: String,
) {
    init {
        require(generation > 0L) { "chained upstream generation must be positive" }
        require(fingerprint.length == 64 && fingerprint.all(::isLowerHexDigit)) {
            "chained upstream fingerprint must be a canonical SHA-256"
        }
    }

    /** Never render the fingerprint as user copy; it is a diagnostic identity. */
    override fun toString(): String = "ChainedUpstreamIdentity(generation=$generation)"
}

/**
 * The committed non-secret chained upstream together with the identity of its rotation.
 *
 * Carries no secret: the private and pre-shared keys are reachable only through
 * [ChainedUpstreamConfigurationRepository.loadSecrets] and are addressed by [identity]'s
 * generation.
 */
data class StoredChainedUpstreamConfiguration(
    val configuration: ChainedUpstreamConfiguration,
    val identity: ChainedUpstreamIdentity,
    /** When this rotation was committed, or `null` for records that predate the metadata. */
    val savedAtEpochMillis: Long?,
    /** Eager first import awaiting this editor's Save; persisted atomically with the rotation. */
    val pendingImportId: String? = null,
)

/** A whole-page mutation. Saved rows refer to their ORIGINAL index, never an edited position. */
data class ChainedUpstreamSelectionRow(
    val configuration: ChainedUpstreamConfiguration,
    val originalIndex: Int?,
    val replacement: ChainedUpstreamRotation? = null,
) {
    init {
        require(configuration.precedingHops.isEmpty()) { "selection rows must be independent" }
        require(originalIndex == null || originalIndex >= 0)
        require(originalIndex != null || replacement != null) { "new rows require their own credentials" }
    }
    override fun toString(): String = "ChainedUpstreamSelectionRow(originalIndex=$originalIndex, replacement=${replacement != null})"
}

/** Compare-and-remove of one exact unaccepted import; a newer saved rotation is never removed. */
sealed interface ChainedUpstreamPendingRemovalResult {
    data object Removed : ChainedUpstreamPendingRemovalResult
    data object Superseded : ChainedUpstreamPendingRemovalResult
    data object AuthoritativeReadFailed : ChainedUpstreamPendingRemovalResult
    data class Failed(val message: String) : ChainedUpstreamPendingRemovalResult
}

/** The outcome of one [ChainedUpstreamConfigurationRepository.save]. */
sealed interface ChainedUpstreamSaveResult {
    /** The configuration and its key are durably committed at [identity]. */
    data class Committed(val identity: ChainedUpstreamIdentity) : ChainedUpstreamSaveResult

    /** Nothing was committed; the prior committed rotation is intact. */
    data class Rejected(val failure: ChainedUpstreamSecretStaging.Failure) : ChainedUpstreamSaveResult

    /**
     * The authoritative generation did not match the caller's expectation, so nothing was staged.
     * This is the first-rotation CAS on absence: a concurrent commit won the race and the picked
     * text must not overwrite it. The prior committed rotation is intact.
     */
    data object Superseded : ChainedUpstreamSaveResult

    /**
     * The authoritative configuration could not be read, so the rotation was refused before
     * staging.
     *
     * A mutation must not treat an unreadable authoritative record as "nothing configured": it
     * would stage generation 1 over a durable generation 1, pre-deleting that record's live key
     * before the new key is proven, and any later failure would leave the on-disk configuration
     * without its secret. Reads that serve the UI may fall back; the commit path fails closed here.
     * The prior configuration and key are untouched.
     */
    data object AuthoritativeReadFailed : ChainedUpstreamSaveResult
}

/**
 * The precondition [ChainedUpstreamConfigurationRepository.save] enforces inside its serialized
 * write, so a check-then-save at the caller cannot be raced by a concurrent commit.
 */
sealed interface ChainedUpstreamSaveExpectation {
    /** Replace whatever rotation is committed (the ordinary editor commit). */
    data object AnyCommitted : ChainedUpstreamSaveExpectation

    /** Commit only while no rotation is committed (the first-rotation import). */
    data object Absent : ChainedUpstreamSaveExpectation

    /** Absence CAS whose publication also journals pending ownership in the same durable edit. */
    data class AbsentPendingImport(val editorId: String) : ChainedUpstreamSaveExpectation {
        init { require(editorId.isNotBlank()) { "pending import editor id must not be blank" } }
    }

    /** Commit only while exactly [generation] is committed. */
    data class Generation(val generation: Long) : ChainedUpstreamSaveExpectation
}

/**
 * Credential-protected authority for the user's chained upstream.
 *
 * The non-secret configuration and the secret half are stored separately, bound by one generation
 * per rotation. The interface deliberately offers no separate "save the key" or "save the
 * configuration alone" mutator: a value type that can express half a rotation makes per-half
 * generations something a caller has to remember not to produce, and the Android mirror of the iOS
 * C6 obligation is that a half rotation is unrepresentable. [save] therefore takes the parser's
 * [ChainedUpstreamRotation] — a configuration together with the key material that belongs to it.
 *
 * Every method is main-safe and serializes writes internally.
 */
interface ChainedUpstreamConfigurationRepository {
    /**
     * The committed non-secret configuration, or `null` when nothing is stored. Never reads key
     * material.
     */
    val storedConfiguration: Flow<StoredChainedUpstreamConfiguration?>

    /**
     * Authoritative, coherent metadata/key probe for explicit start admission. Unknown storage
     * cannot authorize DNS-only fallback. Implementations without a classified read refuse.
     */
    suspend fun configurationIssue(): ChainedConfigurationIssue? = ChainedConfigurationIssue.UNAVAILABLE

    /** One-shot read of [storedConfiguration]. */
    suspend fun loadConfiguration(): StoredChainedUpstreamConfiguration?

    /**
     * Commits one whole rotation under a fresh generation. The configuration publish is the commit
     * point, so a failure leaves the prior configuration and key untouched. Returns
     * [ChainedUpstreamSaveResult.Rejected], [ChainedUpstreamSaveResult.Superseded] or
     * [ChainedUpstreamSaveResult.AuthoritativeReadFailed] rather than throwing for an operational
     * failure; the latter refuses before staging when the authoritative record cannot be read.
     *
     * [expectation] is enforced INSIDE the serialized write (after the authoritative read, before
     * staging), so a first-rotation import that began from an observed absence cannot overwrite a
     * rotation committed while its file picker was open.
     */
    suspend fun save(
        rotation: ChainedUpstreamRotation,
        expectation: ChainedUpstreamSaveExpectation = ChainedUpstreamSaveExpectation.AnyCommitted,
    ): ChainedUpstreamSaveResult

    /** Native presentation ownership must still hold inside the final durable metadata edit. */
    suspend fun saveWhileCurrent(
        rotation: ChainedUpstreamRotation,
        expectation: ChainedUpstreamSaveExpectation,
        isCurrent: () -> Boolean,
    ): ChainedUpstreamSaveResult = ChainedUpstreamSaveResult.Superseded

    /** Atomic metadata + all-row credential publication under the same generation CAS as save. */
    suspend fun saveSelection(
        rows: List<ChainedUpstreamSelectionRow>,
        expectation: ChainedUpstreamSaveExpectation,
    ): ChainedUpstreamSaveResult = ChainedUpstreamSaveResult.AuthoritativeReadFailed

    /** Selection publication must retain its exact native visit/authorization at the write edge. */
    suspend fun saveSelectionWhileCurrent(
        rows: List<ChainedUpstreamSelectionRow>,
        expectation: ChainedUpstreamSaveExpectation,
        isCurrent: () -> Boolean,
    ): ChainedUpstreamSaveResult = ChainedUpstreamSaveResult.Superseded

    /**
     * The generation of the committed rotation whose key half can actually be DECRYPTED for use,
     * or `null` when the committed rotation has no usable key.
     *
     * This deliberately attempts the decrypt rather than merely checking that a ciphertext item
     * exists: a restored device holds ciphertext whose Keystore key was never backed up, and a
     * presence-only check would report a runnable upstream that [loadSecrets] then refuses. The
     * verified material is discarded, never returned; use [loadSecrets] to obtain it.
     *
     * The generation is returned (not just a boolean) so a status read can pair the configuration
     * and its key COHERENTLY: a rotation can commit between an independent configuration read and
     * this check, and evaluating the OLD configuration against the NEW key's availability would
     * misreport readiness. Compare it with the configuration's own generation and retry on mismatch.
     */
    suspend fun usableSecretsGeneration(): Long?

    /**
     * Whether the committed rotation's key half can actually be DECRYPTED for use.
     *
     * Convenience for [usableSecretsGeneration] `!= null`; see that method for the decrypt and
     * generation-coherence rationale.
     */
    suspend fun hasUsableSecrets(): Boolean = usableSecretsGeneration() != null

    /**
     * The secret half of the currently committed rotation, or `null` when none is stored.
     *
     * The material is returned only through copied accessors; the repository holds no reference to
     * the key bytes between calls.
     */
    suspend fun loadSecrets(): ChainedUpstreamSecretMaterial?

    /**
     * Deletes every stored key generation, leaving the non-secret configuration in place.
     *
     * The resulting state — a committed configuration whose key is gone — is the honest shape of a
     * device migration or a requested key removal, and is distinct from "nothing configured": the
     * user needs to re-enter a key, not a whole upstream.
     */
    suspend fun deleteSecrets()

    /** Deletes the configuration and every key generation. */
    suspend fun removeAll()

    /** Whole-page delete guarded INSIDE the writer lock; a stale editor cannot delete a successor. */
    suspend fun removeIfGeneration(generation: Long): ChainedUpstreamPendingRemovalResult =
        ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed

    /** A guarded page deletion checks native authority inside the atomic metadata edit. */
    suspend fun removeIfGenerationWhileCurrent(
        generation: Long,
        isCurrent: () -> Boolean,
    ): ChainedUpstreamPendingRemovalResult = ChainedUpstreamPendingRemovalResult.Superseded

    /**
     * Removes only [editorId]'s still-pending [identity] under the same mutation serialization as
     * save. The default refuses so a backend without atomic comparison cannot erase a saved
     * replacement using a stale cleanup witness.
     */
    suspend fun removePendingImport(
        editorId: String,
        identity: ChainedUpstreamIdentity,
    ): ChainedUpstreamPendingRemovalResult = ChainedUpstreamPendingRemovalResult.AuthoritativeReadFailed
}

private fun isLowerHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'
