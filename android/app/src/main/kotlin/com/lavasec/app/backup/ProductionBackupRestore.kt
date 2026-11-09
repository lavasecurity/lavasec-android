package com.lavasec.app.backup

import com.lavasec.app.foundation.backup.BackupEnvelope
import com.lavasec.app.foundation.backup.BackupRestoreAdoption
import com.lavasec.app.foundation.backup.BackupRestoreCheckpoint
import com.lavasec.app.foundation.backup.BackupRestorePlan
import com.lavasec.app.foundation.backup.BackupRestorePublisher
import com.lavasec.app.foundation.backup.BackupRetainedPreferences
import com.lavasec.app.foundation.backup.BackupLavaGuardAchievementLedger
import com.lavasec.app.data.GuardianAchievementAuthority
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import com.lavasec.app.platform.LoggingSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Excludes automatic uploads throughout publication and any compensation. */
internal interface BackupRestorePublicationExclusion {
    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T
}

/** Optional cloud envelope/key custody, independent of publication exclusion for portable files. */
internal interface BackupRestoreLocalCustody : BackupRestorePublicationExclusion {
    /** Excludes automatic uploads for the entire publication and any compensation. */
    override suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = block()

    fun currentEnvelope(): BackupEnvelope?

    /** The account the persisted envelope is currently bound to (the rollback snapshot). */
    fun currentEnvelopeAccountID(): String?

    /** Receipt for the exact previous local ciphertext, retained only for compensation. */
    fun currentUploadedAtEpochSeconds(): Long? = null

    /** Restores the previous receipt after an uncommitted adoption; absence is acknowledged too. */
    fun restoreUploadMarker(uploadedAt: Long?): Boolean = uploadedAt == null

    /** The live signed-in account an adopted envelope must be bound to. */
    fun liveAccountID(): String?

    fun currentSealingKey(): CharArray?

    /** Persists the adopted envelope, its account binding and the retained device key. */
    fun adopt(envelope: BackupEnvelope, deviceSecret: CharArray, accountID: String?): Boolean

    /** Puts a captured custody snapshot back; best effort, false when not durably restored. */
    fun restore(envelope: BackupEnvelope?, accountID: String?, sealingKey: CharArray?): Boolean
}

/**
 * The authoritative product state one reviewed backup restore mutates. This is deliberately a
 * narrow seam over the SAME owners every other configuration/filter mutation uses — the live
 * filter library owner and the retained-logging privacy owner — never a second persistence
 * authority: [replaceLibrary] commits through the generation-fenced filter coordinator and
 * [applyPreferences] through the durable logging authority, so a restore lands on the same
 * generation-tracked state the app renders.
 *
 * Reads are synchronous (the live `StateFlow` values) so the foundation owner can capture its
 * checkpoint without suspending; writes suspend. [ProductionBackupRestorePublisher] bridges the
 * synchronous [BackupRestorePublisher] contract onto those suspend writes and must only be driven
 * from a background dispatcher (see the class doc).
 */
internal interface BackupRestoreAuthority {
    /** The live confirmed library, or `null` when no credential owner is attached. */
    fun currentLibrary(): FilterLibraryDraft?

    /** The live retained-logging preferences, or `null` when the privacy owner is unavailable. */
    fun currentPreferences(): BackupRetainedPreferences?

    /**
     * The credential generation every write in one restore transaction must stay bound to, or
     * `null` when no credential owner is attached. A rotation between the checkpoint, the library
     * write and the logging write would otherwise split the restore across two accounts.
     */
    fun generation(): Long?

    /** Commits the whole restored library; false guarantees the previous library is still live.
     *
     * [expectedLibrary] is the exact library the restore plan was prepared from, or the publisher's
     * exact forward write when compensating it. When non-null,
     * the comparison and the write are one atomic compare-and-replace through the generation-fenced
     * coordinator, so a concurrent filter mutation committed after the plan's checkpoint is refused
     * rather than overwritten. */
    suspend fun replaceLibrary(library: FilterLibraryDraft, expectedLibrary: FilterLibraryDraft?): Boolean

    /**
     * Compensates only this transaction's exact forward library. Publication still fences the
     * captured credential generation and CAS expectation, but a retired UI grant cannot prevent
     * putting back the pre-restore state after an otherwise-refused import.
     */
    suspend fun rollbackLibrary(library: FilterLibraryDraft, expectedLibrary: FilterLibraryDraft?): Boolean =
        replaceLibrary(library, expectedLibrary)

    /** Commits the retained-logging preferences; false leaves the previous settings in effect. */
    suspend fun applyPreferences(preferences: BackupRetainedPreferences): Boolean

    /** Revalidates the credential/account lease at the privacy owner's final commit boundary. */
    suspend fun commitPreferences(
        preferences: BackupRetainedPreferences,
        expectedGeneration: Long?,
        stillAuthorized: () -> Boolean,
    ): Boolean = generation() == expectedGeneration && stillAuthorized() && applyPreferences(preferences)

    /**
     * Ends the in-memory editing sessions after the whole restore transaction has committed. A
     * rollback must not have discarded them, so the restore's library write preserves drafts and
     * this is called only on success.
     */
    fun discardEditingSessions() {}
}

/**
 * The atomic local publication of a reviewed restore over [BackupRestoreAuthority].
 *
 * The foundation [BackupRestorePublisher] is synchronous (its pure owner calls it from
 * `confirm`), while the live filter/logging commits suspend. The publisher therefore runs each
 * write on [writeDispatcher] with a confined `runBlocking`, so the synchronous owner is served
 * without ever touching the main thread. Callers must drive the owner from a background
 * dispatcher.
 *
 * Publication excludes automatic uploads and uses a final irreversible commit boundary:
 * 1. the whole restored library is committed first (the expensive, generation-fenced step);
 * 2. envelope/key custody is adopted under the captured account binding;
 * 3. retained preferences persist before enforcing any destructive retention policy.
 *
 * A failure before step 3 is compensated while upload exclusion still holds. A durable preference
 * commit is final: a later reconciliation or session transition cannot ask a boolean rollback to
 * recreate deleted logs. The owner's subsequent [restore] reads the recorded compensation result.
 */
internal class ProductionBackupRestorePublisher(
    private val authority: BackupRestoreAuthority,
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The local envelope custody a restore adopts; `null` keeps the restore library/prefs-only. */
    private val custody: BackupRestoreLocalCustody? = null,
    private val accounts: BackupAccountAuthority? = null,
    private val guardian: GuardianAchievementAuthority? = null,
    // Portable import needs the same upload exclusion without reading/adopting cloud custody.
    private val publicationExclusion: BackupRestorePublicationExclusion? = custody,
) : BackupRestorePublisher {
    private data class Checkpoint(
        val library: FilterLibraryDraft?,
        val preferences: BackupRetainedPreferences?,
        val generation: Long?,
        val envelope: BackupEnvelope?,
        val envelopeAccountID: String?,
        val uploadedAtEpochSeconds: Long?,
        val sealingKey: CharArray?,
        val accountBinding: BackupAccountBinding?,
        val accountGeneration: Long?,
        val guardianLedger: BackupLavaGuardAchievementLedger?,
    ) : BackupRestoreCheckpoint

    // The generation the current transaction's checkpoint was captured at. `confirm` calls
    // `checkpoint()` then `publish()` in one serialized step, so a field is sufficient and lets
    // `publish(plan)` (which does not receive the checkpoint) enforce the binding.
    @Volatile private var boundGeneration: Long? = null

    /**
     * Whether THIS transaction's forward library write committed. The rollback must only revert the
     * publisher's own write: when the compare-and-replace refused (a concurrent mutation landed
     * first), the live library is newer than the checkpoint and re-committing the checkpoint would
     * wrongly overwrite it.
     */
    @Volatile private var libraryWritten = false

    /** The previous transaction's sealing key, zeroized once it is no longer needed. */
    @Volatile private var boundSealingKey: CharArray? = null
    private var capturedCheckpoint: Checkpoint? = null
    private var adoptedCustody = false
    private var publishedLibrary: FilterLibraryDraft? = null
    private var compensated: Boolean? = null
    private var guardianWritten = false
    private var publishedGuardianLedger: BackupLavaGuardAchievementLedger? = null

    override fun checkpoint(): BackupRestoreCheckpoint = blocking { exclusive {
        capturedCheckpoint?.let(::clearCapturedKey)
        boundSealingKey?.fill('\u0000')
        boundSealingKey = null
        capturedCheckpoint = null
        boundGeneration = null
        adoptedCustody = false
        publishedLibrary = null
        compensated = null
        libraryWritten = false
        guardianWritten = false
        publishedGuardianLedger = null
        val generation = authority.generation()
        val sealingKey = custody?.currentSealingKey()
        var captured = false
        try {
            Checkpoint(
                library = authority.currentLibrary(),
                preferences = authority.currentPreferences(),
                generation = generation,
                envelope = custody?.currentEnvelope(),
                envelopeAccountID = custody?.currentEnvelopeAccountID(),
                uploadedAtEpochSeconds = custody?.currentUploadedAtEpochSeconds(),
                sealingKey = sealingKey,
                accountBinding = accounts?.binding(),
                accountGeneration = accounts?.generation(),
                guardianLedger = guardian?.captureLedger(),
            ).also {
                boundGeneration = generation
                boundSealingKey = sealingKey
                capturedCheckpoint = it
                captured = true
            }
        } finally {
            // Any later local read can fail after the key was copied. It must not escape into an
            // incomplete checkpoint or remain reachable through the publisher's previous fields.
            if (!captured) sealingKey?.fill('\u0000')
        }
    } }

    /** True while the credential generation has not rotated since the checkpoint. */
    private fun generationStillBound(): Boolean = authority.generation() == boundGeneration

    private fun accountStillBound(plan: BackupRestorePlan): Boolean {
        val captured = capturedCheckpoint ?: return false
        val accountAuthority = accounts
        if (accountAuthority != null) {
            val binding = captured.accountBinding ?: return false
            if (!binding.signedIn || binding.accountID != plan.basis.accountID) return false
            if (plan.basis.accountGeneration != null && plan.basis.accountGeneration != captured.accountGeneration) return false
            return accountAuthority.binding() == binding && accountAuthority.generation() == captured.accountGeneration
        }
        return custody == null || custody.liveAccountID() == plan.basis.accountID
    }

    override fun publish(plan: BackupRestorePlan, adoption: BackupRestoreAdoption?): Boolean = blocking { exclusive {
        val captured = capturedCheckpoint ?: return@exclusive false
        val committed = try {
            publishBound(plan, adoption)
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            // Compensate before releasing custody exclusion. An automatic upload must not seal
            // the failed forward library or overwrite a half-adopted envelope between these steps.
            compensated = restoreCaptured(captured)
            return@exclusive false
        }
        // Preference persistence is the final commit point. Retention deletion may now have
        // happened; a later graph/session transition or UI cleanup cannot turn it into rollback.
        runCatching { authority.discardEditingSessions() }
        clearCapturedKey(captured)
        libraryWritten = false
        adoptedCustody = false
        guardianWritten = false
        true
    } }

    private suspend fun publishBound(plan: BackupRestorePlan, adoption: BackupRestoreAdoption?): Boolean {
        libraryWritten = false
        if (capturedCheckpoint?.generation != plan.basis.configurationGeneration) return false
        val capturedLedger = capturedCheckpoint?.guardianLedger
        if (guardian != null) {
            if (capturedLedger == null || capturedLedger != plan.basis.guardianLedger ||
                guardian.captureLedger() != capturedLedger) return false
        } else if (plan.basis.guardianLedger != null || plan.restored.lavaGuardUnlocks.records.isNotEmpty()) {
            // Pure legacy publishers can serve an empty fixture; production always has the real
            // progress authority. A decoded portable ledger must never be silently discarded.
            return false
        }
        // Re-read at the publish edge so a restore cannot land against a state the review never
        // saw. A missing authority, a library/preferences state that moved after the review's
        // confirmation check, or a credential-graph rotation refuses rather than overwriting the
        // newer state or splitting the transaction across two accounts.
        val liveLibrary = authority.currentLibrary()
        val livePreferences = authority.currentPreferences()
        if (liveLibrary == null || livePreferences == null) return false
        if (liveLibrary != plan.previousLibrary || livePreferences != plan.previousPreferences) {
            return false
        }
        if (!generationStillBound() || !accountStillBound(plan)) return false
        // Compare-and-replace: the expected library is enforced inside the coordinator's serialized
        // write, so a mutation after the check above but before the write is refused.
        if (!authority.replaceLibrary(plan.library, plan.previousLibrary)) return false
        libraryWritten = true
        publishedLibrary = plan.library
        if (!generationStillBound() || !accountStillBound(plan)) return false
        // Adopt the restored envelope + its device key custody in the same transaction, so a
        // restored backup continues to upload instead of leaving BackupLocalStore empty.
        if (adoption != null) {
            val localCustody = custody ?: return false
            adoptedCustody = true // Even a false result may have written part of the custody pair.
            val adopted = adoption.withDeviceSecret { secret ->
                localCustody.adopt(adoption.envelope, secret, plan.basis.accountID)
            } ?: false
            if (!adopted) return false
        }
        if (!generationStillBound() || !accountStillBound(plan)) return false
        if (guardian != null) {
            val target = plan.restored.lavaGuardUnlocks
            publishedGuardianLedger = target
            // A throwing port can be ambiguous. Compensation checks actual live state before
            // replacing only this exact forward value, preserving concurrent earned unlocks.
            guardianWritten = true
            if (!guardian.replaceLedger(target, checkNotNull(capturedLedger))) {
                // A false CAS acknowledges no write, including when a guard was earned first.
                guardianWritten = false
                publishedGuardianLedger = null
                return false
            }
        }
        if (!generationStillBound() || !accountStillBound(plan)) return false
        // Restore's privacy authority persists FIRST, without deleting any data on refusal.
        // A true result is the final durable commit; post-commit retention reconciliation never
        // returns false and therefore never asks compensation to recreate deleted local logs.
        return authority.commitPreferences(plan.preferences, boundGeneration) { accountStillBound(plan) }
    }

    override fun restore(checkpoint: BackupRestoreCheckpoint): Boolean {
        val captured = checkpoint as? Checkpoint ?: return true
        compensated?.let { return it }
        return blocking { exclusive { restoreCaptured(captured) } }
    }

    private suspend fun restoreCaptured(captured: Checkpoint): Boolean = try {
        // Account changes do not rotate this device's library; graph changes do. Never overwrite
        // a new graph or a newer library when compensating this transaction's exact forward write.
        val libraryRestored = runCatching {
            if (!libraryWritten) true else if (authority.generation() != captured.generation) false
            else captured.library?.let { authority.rollbackLibrary(it, expectedLibrary = publishedLibrary) } ?: false
        }.getOrDefault(false)
        val preferencesRestored = runCatching {
            authority.currentPreferences()?.toLoggingSettings() == captured.preferences?.toLoggingSettings()
        }.getOrDefault(false)
        val custodyRestored = runCatching {
            if (adoptedCustody) {
                val local = custody ?: return@runCatching false
                val restored = local.restore(captured.envelope, captured.envelopeAccountID, captured.sealingKey)
                // The receipt belongs to the previous envelope and is never proof of the forward one.
                // Restore it only after its envelope/key pair is durably back in place.
                restored && local.restoreUploadMarker(captured.uploadedAtEpochSeconds)
            } else true
        }.getOrDefault(false)
        val guardianRestored = runCatching {
            if (!guardianWritten) true else {
                val owner = guardian ?: return@runCatching false
                val previous = captured.guardianLedger ?: return@runCatching false
                if (owner.captureLedger() == previous) true
                else owner.replaceLedger(previous, publishedGuardianLedger ?: return@runCatching false)
            }
        }.getOrDefault(false)
        libraryRestored && preferencesRestored && custodyRestored && guardianRestored
    } catch (_: Exception) {
        false
    } finally {
        clearCapturedKey(captured)
    }

    private fun clearCapturedKey(captured: Checkpoint) {
        captured.sealingKey?.fill('\u0000')
        boundSealingKey?.fill('\u0000')
        boundSealingKey = null
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T =
        if (publicationExclusion != null) publicationExclusion.withExclusiveAccess(block) else block()

    private fun <T> blocking(block: suspend () -> T): T = runBlocking(writeDispatcher) { block() }
}

/** Android's retained-logging model mapped onto the portable backup retention facts. */
internal fun LoggingSettings.toBackupRetainedPreferences(
    protectionEnabledHint: Boolean,
): BackupRetainedPreferences = BackupRetainedPreferences(
    keepFilteringCounts = keepCounts,
    keepDomainDiagnostics = keepHistory,
    keepNetworkActivity = keepNetworkActivity,
    keepLavaGuardProgress = keepLavaGuardProgress,
    protectionEnabledHint = protectionEnabledHint,
)

/**
 * The restored retention facts mapped onto Android's logging authority. The protection intent
 * hint is device-local (it belongs to this device, not the backup) and is not part of
 * [LoggingSettings], so it is intentionally not carried by the write.
 */
internal fun BackupRetainedPreferences.toLoggingSettings(): LoggingSettings = LoggingSettings(
    keepCounts = keepFilteringCounts,
    keepHistory = keepDomainDiagnostics,
    keepNetworkActivity = keepNetworkActivity,
    keepLavaGuardProgress = keepLavaGuardProgress,
)

/**
 * Production [BackupRestoreAuthority] over the live product. Every lambda reads or commits the
 * same owner the native surfaces use, captured as a `()` so a credential-graph rotation is
 * observed rather than pinned at construction.
 */
internal class ProductBackupRestoreAuthority(
    private val library: () -> FilterLibraryDraft?,
    private val logging: () -> LoggingSettings?,
    private val currentGeneration: () -> Long?,
    private val replaceLibraryWith: suspend (FilterLibraryDraft, FilterLibraryDraft?) -> Boolean,
    private val setLoggingWith: suspend (LoggingSettings) -> Boolean,
    private val discardEditingWith: () -> Unit = {},
    /**
     * The live device-local protection intent, carried into [currentPreferences] so it matches the
     * hint the review plan was prepared with. It is a non-persisted hint, so a hardcoded value
     * would make an otherwise valid restore look stale whenever protection is enabled.
     */
    private val protectionEnabledHint: () -> Boolean = { false },
    private val restoreLoggingWith: (suspend (LoggingSettings, Long?, () -> Boolean) -> Boolean)? = null,
    private val rollbackLibraryWith: (suspend (FilterLibraryDraft, FilterLibraryDraft?) -> Boolean)? = null,
) : BackupRestoreAuthority {
    override fun currentLibrary(): FilterLibraryDraft? = library()

    override fun currentPreferences(): BackupRetainedPreferences? =
        logging()?.toBackupRetainedPreferences(protectionEnabledHint = protectionEnabledHint())

    override fun generation(): Long? = currentGeneration()

    override suspend fun replaceLibrary(library: FilterLibraryDraft, expectedLibrary: FilterLibraryDraft?): Boolean =
        replaceLibraryWith(library, expectedLibrary)

    override suspend fun rollbackLibrary(library: FilterLibraryDraft, expectedLibrary: FilterLibraryDraft?): Boolean =
        rollbackLibraryWith?.invoke(library, expectedLibrary) ?: replaceLibrary(library, expectedLibrary)

    override suspend fun applyPreferences(preferences: BackupRetainedPreferences): Boolean =
        setLoggingWith(preferences.toLoggingSettings())

    override suspend fun commitPreferences(
        preferences: BackupRetainedPreferences,
        expectedGeneration: Long?,
        stillAuthorized: () -> Boolean,
    ): Boolean = restoreLoggingWith?.invoke(preferences.toLoggingSettings(), expectedGeneration, stillAuthorized)
        ?: super.commitPreferences(preferences, expectedGeneration, stillAuthorized)

    override fun discardEditingSessions() = discardEditingWith()
}

/**
 * The production local envelope/key custody over the same [BackupLocalStore] and
 * [BackupSealingKeyStore] setup and every upload use, so a restored device's envelope is one
 * authority rather than a second store.
 */
internal class ProductBackupRestoreCustody(
    private val store: BackupLocalStore,
    private val sealingKeys: BackupSealingKeyStore,
    private val liveAccount: () -> String?,
) : BackupRestoreLocalCustody {
    override suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = store.withCustodyLock(block)

    override fun currentEnvelope(): BackupEnvelope? = store.loadEnvelope()

    override fun currentEnvelopeAccountID(): String? = store.loadEnvelopeAccountID()

    override fun currentUploadedAtEpochSeconds(): Long? = store.loadUploadedAtEpochSeconds()

    override fun restoreUploadMarker(uploadedAt: Long?): Boolean =
        if (uploadedAt == null) store.clearUploadMarker() else store.recordUploadedAtEpochSeconds(uploadedAt)

    override fun liveAccountID(): String? = liveAccount.invoke()

    override fun currentSealingKey(): CharArray? = sealingKeys.load()

    override fun adopt(envelope: BackupEnvelope, deviceSecret: CharArray, accountID: String?): Boolean {
        // Snapshots read outside custody exclusion. Retire the old receipt before replacing any
        // custody, so a crash or interrupted compensation cannot label new ciphertext as uploaded.
        if (!store.clearUploadMarker()) return false
        if (!store.saveEnvelope(envelope)) return false
        if (!store.saveEnvelopeAccountID(accountID)) return false
        return sealingKeys.save(deviceSecret)
    }

    override fun restore(envelope: BackupEnvelope?, accountID: String?, sealingKey: CharArray?): Boolean =
        if (envelope == null) {
            store.deleteEnvelope() && sealingKeys.clear()
        } else {
            store.saveEnvelope(envelope) && store.saveEnvelopeAccountID(accountID) &&
                (if (sealingKey == null) sealingKeys.clear() else sealingKeys.save(sealingKey))
        }
}
