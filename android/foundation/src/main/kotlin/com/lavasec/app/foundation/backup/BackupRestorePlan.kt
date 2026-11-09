package com.lavasec.app.foundation.backup

import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.foundation.filter.FilterProfileDraft
import java.util.LinkedHashSet

/**
 * Why a reviewed restore could not be prepared, or no longer matches the state it was prepared
 * from. One sealed vocabulary so a caller can never mistake "not restorable" for a usable review.
 *
 * Ported from the canonical iOS `BackupRestorePlanError` (pinned at `c4658d9`) and extended with
 * the unlock outcomes the Android owner distinguishes: iOS surfaces a wrong secret / damaged
 * ciphertext as a thrown decrypt error from the envelope, whereas the owner receives them as a
 * [BackupRestoreUnlock] result and refuses before any review exists.
 */
sealed interface BackupRestoreRefusal {
    /** The unlock secret did not authenticate the envelope. */
    data object WrongSecret : BackupRestoreRefusal

    /** The ciphertext, or the JSON payload it decrypts to, is malformed or tampered. */
    data object CorruptPayload : BackupRestoreRefusal

    /** The payload advertises a schema newer than this build can safely restore. */
    data class UnsupportedSchema(val schemaVersion: Int) : BackupRestoreRefusal

    /** The payload decoded but carries no restorable filter library. */
    data object NotRestorable : BackupRestoreRefusal

    /** No envelope, unlock secret or capability was available to begin the restore. */
    data object Unavailable : BackupRestoreRefusal

    /** Passkey restore was requested but the platform cannot produce an authenticator PRF. */
    data object PasskeyUnavailable : BackupRestoreRefusal

    /** No prepared review exists, or its identifier did not match the pending one. */
    data object NoReview : BackupRestoreRefusal

    /** The library or configuration generation changed after the review was prepared. */
    data object StaleReview : BackupRestoreRefusal

    /** A changed resolver selection needs its own acknowledgement before it is applied. */
    data object ResolverConfirmationRequired : BackupRestoreRefusal

    /** The atomic local publish did not complete; nothing was applied. */
    data object PublishInterrupted : BackupRestoreRefusal

    /**
     * The publish failed and the compensating rollback could not be durably re-committed, so the
     * local state may not match the pre-restore state. This must never be presented as "nothing
     * was applied".
     */
    data object RollbackFailed : BackupRestoreRefusal
}

/**
 * One filter's before/after projection in a restore review, in stable identifier order. Mirrors the
 * canonical iOS `FilterReplacementSummary`: `before == null` is an added filter, `after == null` a
 * removed one, and equality is the change test.
 */
data class BackupFilterChange(
    val filterID: String,
    val before: FilterProfileDraft?,
    val after: FilterProfileDraft?,
) {
    val hasChanges: Boolean get() = before != after

    val isAdded: Boolean get() = before == null && after != null

    val isRemoved: Boolean get() = before != null && after == null
}

/**
 * The account and local configuration generation a restore review is bound to. A review may only be
 * confirmed while the same account and generation are still current, so an account switch or any
 * configuration write invalidates it (mirrors the iOS stale-review fence).
 */
data class BackupRestoreBasis(
    val accountID: String?,
    val configurationGeneration: Long,
    /** A same-account logout/login also invalidates a downloaded restore review. */
    val accountGeneration: Long? = null,
    /** Captured portable achievements; earning another guard invalidates the open review. */
    val guardianLedger: BackupLavaGuardAchievementLedger? = null,
)

/**
 * The immutable result of mapping a downloaded, decrypted payload onto Android's filter library:
 * the settings that will become authoritative, the pre-restore sides for review, the per-filter
 * changes and whether the DNS selection needs its own acknowledgement.
 *
 * Preparation is pure: it writes nothing and keeps no unlock material. Confirmation re-checks the
 * exact basis it was built from ([validateConfirmation]) before the owner stages any local write.
 *
 * Deliberate device-local carry-overs, mirroring iOS `BackupRestorePlan`:
 * - `protectionEnabledHint` belongs to the device, not the backup; a restore can never start or
 *   stop this device's protection intent. The current value is preserved on the restored side.
 * - ordered resolver rows retain their actual provider/transport/endpoints, including custom
 *   one usable IP literal per endpoint, DoH, DoT and DoQ routes. Only a change of that target requires its own
 *   acknowledgement; an unserved target is refused before publication.
 * - portable achievements replace the ledger; qualified usage remains local and excluded.
 */
data class BackupRestorePlan(
    /** The library and retained preferences that will become authoritative on this device. */
    val restored: RestoredBackupSettings,
    /** The library the review was prepared from (the "before" side). */
    val previousLibrary: FilterLibraryDraft,
    /** The retained preferences the review was prepared from. */
    val previousPreferences: BackupRetainedPreferences,
    /** Every added, removed or changed filter, in stable identifier order. */
    val filterChanges: List<BackupFilterChange>,
    /** Whether the mapped resolver plan differs from the current one. */
    val requiresResolverConfirmation: Boolean,
    /** The account and configuration generation this review is bound to. */
    val basis: BackupRestoreBasis,
) {
    val library: FilterLibraryDraft get() = restored.library

    val preferences: BackupRetainedPreferences get() = restored.preferences

    /**
     * Refuses a stale or incompletely acknowledged review before the owner stages any write. A
     * `null` result is the only authority to apply.
     */
    fun validateConfirmation(
        currentBasis: BackupRestoreBasis,
        currentLibrary: FilterLibraryDraft,
        resolverChangeConfirmed: Boolean,
    ): BackupRestoreRefusal? {
        if (currentBasis != basis || currentLibrary != previousLibrary) {
            return BackupRestoreRefusal.StaleReview
        }
        if (requiresResolverConfirmation && !resolverChangeConfirmed) {
            return BackupRestoreRefusal.ResolverConfirmationRequired
        }
        return null
    }

    companion object {
        /**
         * Validates a decrypted payload and computes the effective restore without writing state.
         * A schema newer than this build supports, or a payload that cannot map onto Android's
         * models, refuses here rather than partially applying.
         */
        fun prepare(
            payload: BackupConfigurationPayload,
            currentLibrary: FilterLibraryDraft,
            currentPreferences: BackupRetainedPreferences,
            basis: BackupRestoreBasis,
        ): BackupRestorePlanResult {
            val restored = try {
                BackupPayloadMapper.restore(payload)
            } catch (unsupported: BackupSchemaUnsupportedException) {
                return BackupRestorePlanResult.Refused(
                    BackupRestoreRefusal.UnsupportedSchema(unsupported.schemaVersion),
                )
            } catch (_: BackupPayloadMappingException) {
                return BackupRestorePlanResult.Refused(BackupRestoreRefusal.NotRestorable)
            }
            val effectivePreferences = restored.preferences.copy(
                protectionEnabledHint = currentPreferences.protectionEnabledHint,
            )
            val library = restored.library
            val filterIDs = LinkedHashSet<String>()
            currentLibrary.profiles.forEach { filterIDs.add(it.id) }
            library.profiles.forEach { filterIDs.add(it.id) }
            val changes = filterIDs
                .sorted()
                .map { id -> BackupFilterChange(id, currentLibrary.profile(id), library.profile(id)) }
                .filter(BackupFilterChange::hasChanges)
            return BackupRestorePlanResult.Valid(
                BackupRestorePlan(
                    restored = restored.copy(preferences = effectivePreferences),
                    previousLibrary = currentLibrary,
                    previousPreferences = currentPreferences,
                    filterChanges = changes,
                    requiresResolverConfirmation = library.resolverTarget != currentLibrary.resolverTarget,
                    basis = basis,
                ),
            )
        }
    }
}

/** The pure outcome of [BackupRestorePlan.prepare]: a validated plan or an explicit refusal. */
sealed interface BackupRestorePlanResult {
    data class Valid(val plan: BackupRestorePlan) : BackupRestorePlanResult

    data class Refused(val refusal: BackupRestoreRefusal) : BackupRestorePlanResult
}
