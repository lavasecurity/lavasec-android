package com.lavasec.app.foundation.backup

import com.lavasec.app.foundation.filter.FilterLibraryDraft
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * The local envelope and device-key custody a successful restore adopts, so `backup.now` and the
 * automatic path continue from the restored ciphertext instead of an empty local store. The
 * envelope was re-keyed by the platform unlock step (every portable slot, including the user's
 * recovery phrase, is preserved); [deviceSecret] is the transient device-held re-seal key.
 *
 * The secret is copied in and zeroized on [close]; it is never logged or serialized.
 */
class BackupRestoreAdoption(
    val envelope: BackupEnvelope,
    deviceSecret: CharArray,
) : AutoCloseable {
    private val secret: CharArray = deviceSecret.copyOf()
    private var closed = false

    /** Runs [action] with the live device secret; false once closed. */
    fun <T> withDeviceSecret(action: (CharArray) -> T): T? = synchronized(this) {
        if (closed) null else action(secret)
    }

    override fun close() = synchronized(this) {
        closed = true
        secret.fill('\u0000')
    }
}

/**
 * The outcome of the platform's download + decrypt step for one restore attempt. Keeping this a
 * sealed result (rather than a thrown exception) is what lets the owner refuse a wrong secret or a
 * damaged ciphertext before any review is built and before any local state is touched.
 */
sealed interface BackupRestoreUnlock {
    /**
     * The platform decrypted and decoded a downloaded envelope into a portable payload. [adoption]
     * is the local envelope + device-key custody the restore should adopt on success, or `null` when
     * the platform does not retain a device key.
     */
    data class Unlocked(
        val payload: BackupConfigurationPayload,
        val adoption: BackupRestoreAdoption? = null,
    ) : BackupRestoreUnlock

    /** The supplied secret did not authenticate the envelope. */
    data object WrongSecret : BackupRestoreUnlock

    /** The user dismissed the platform unlock (for example a passkey sheet); nothing was produced. */
    data object Cancelled : BackupRestoreUnlock

    /** AES-GCM authentication or payload parsing failed: the ciphertext is damaged or tampered. */
    data object CorruptPayload : BackupRestoreUnlock

    /** The payload decrypted but advertises a schema newer than this build understands. */
    data class UnsupportedSchema(val schemaVersion: Int) : BackupRestoreUnlock

    /** No envelope, unlock secret or capability is available for this method. */
    data object Unavailable : BackupRestoreUnlock
}

/**
 * Opaque snapshot of everything an atomic restore publish can mutate: the filter library, the
 * retained preferences, the local envelope and its key custody, and the upload marker.
 */
interface BackupRestoreCheckpoint

/**
 * The atomic local publication of a reviewed restore.
 *
 * The contract is all-or-nothing: [publish] either fully commits the plan and its key custody, or
 * changes nothing and returns `false`. [restore] reverts a partial publish so an interrupted write
 * can never masquerade as a completed restore. A refused or malformed restore must never reach this
 * port at all.
 */
interface BackupRestorePublisher {
    fun checkpoint(): BackupRestoreCheckpoint

    /**
     * Atomically commits the plan and, when [adoption] is supplied, adopts the restored envelope and
     * its local key custody in the same transaction. The default ignores the adoption for a
     * publisher that owns no local envelope custody.
     */
    fun publish(plan: BackupRestorePlan, adoption: BackupRestoreAdoption? = null): Boolean

    /**
     * Reverts a partial publish. Returns `true` only when the checkpointed library AND preferences
     * were both durably re-committed; a `false` result means the local state may not match the
     * pre-restore state, so the caller must refuse with the stronger `RollbackFailed`, never the
     * "nothing was applied" copy.
     */
    fun restore(checkpoint: BackupRestoreCheckpoint): Boolean
}

/**
 * Pre-ceremony passkey admission. Returns `true` only with evidence of a reachable native provider
 * and configured relying party. A particular credential's PRF remains unknown until the secure
 * ceremony produces authenticator output; the unlock source must refuse absent output. The
 * default supplies no provider evidence and keeps the method unavailable.
 */
fun interface BackupPasskeyPrfCapability {
    fun isAvailable(): Boolean
}

/** The honest default for a build that cannot provide an authenticator PRF. */
object UnavailableBackupPasskeyPrfCapability : BackupPasskeyPrfCapability {
    override fun isAvailable(): Boolean = false
}

/**
 * Local device-key admission: a retained usable key for the initiating account was observed.
 * The actual downloaded keychain slot must still decrypt successfully; losing the device key
 * keeps this method unavailable while recovery/passkey remain independent.
 */
fun interface BackupDeviceKeyCapability {
    fun isAvailable(): Boolean
}

/** The honest default for a build with no device-key restore path. */
object UnavailableBackupDeviceKeyCapability : BackupDeviceKeyCapability {
    override fun isAvailable(): Boolean = false
}

/** Lifecycle of the restore owner; a review is never implicitly applied. */
enum class BackupRestorePhase {
    /** No review is prepared. */
    IDLE,

    /** A validated review is awaiting explicit confirmation. */
    REVIEW,

    /** The reviewed plan was published atomically. */
    APPLIED,
}

/**
 * The owner's published state. It is a single immutable value so a refused or interrupted restore
 * leaves exactly the prior value in place — never a half-updated library or preferences.
 */
data class BackupRestoreState(
    val phase: BackupRestorePhase = BackupRestorePhase.IDLE,
    val reviewID: String? = null,
    /** The validated plan under review or the plan last applied; carries no unlock material. */
    val plan: BackupRestorePlan? = null,
    /** The most recent refusal, so a surface can explain why the restore stopped. */
    val lastRefusal: BackupRestoreRefusal? = null,
)

/** The outcome of preparing a review. */
sealed interface BackupRestorePreparation {
    data class Prepared(val reviewID: String, val plan: BackupRestorePlan) : BackupRestorePreparation

    data class Refused(val refusal: BackupRestoreRefusal) : BackupRestorePreparation
}

/** The outcome of confirming a prepared review. */
sealed interface BackupRestoreApplyOutcome {
    data class Applied(val plan: BackupRestorePlan) : BackupRestoreApplyOutcome

    data class Refused(val refusal: BackupRestoreRefusal) : BackupRestoreApplyOutcome
}

/**
 * App-level owner of the reviewed encrypted-backup restore.
 *
 * Ported from the canonical iOS `BackupController` restore flow (pinned at `c4658d9`,
 * `prepareEncryptedBackupRestore` / `confirmPreparedBackupRestore`) and `BackupRestorePlan`, reduced
 * to the pure, JVM-testable owner layer:
 *
 * 1. **Download/decrypt -> immutable review.** [prepare] accepts the platform's unlock result. A
 *    wrong secret, a corrupt payload, an unavailable capability or a schema newer than this build
 *    refuses with a typed [BackupRestoreRefusal] and prepares nothing. A restorable payload maps
 *    through [BackupPayloadMapper] into a [BackupRestorePlan] review that carries the before/after
 *    filter changes and the resolver-acknowledgement requirement.
 * 2. **Atomic publish.** [confirm] re-validates the review's exact basis, consumes it, then asks the
 *    [BackupRestorePublisher] to publish once. A publish that is not durable is rolled back and
 *    refused ([BackupRestoreRefusal.PublishInterrupted]); a refused or malformed restore never
 *    reaches the publisher.
 * 3. **Capability-tested passkey PRF.** The passkey method is only selectable, and passkey restore
 *    only admissible, when [BackupPasskeyPrfCapability] reports the platform can produce an
 *    authenticator PRF. The default is unavailable.
 *
 * The owner performs no I/O and holds no secret; the platform wires the publisher (local envelope +
 * key custody) and the capability probe (Credential Manager / PRF), and the hub remains the single
 * owner of the filter library and configuration generation.
 */
class BackupRestoreOwner(
    private val publisher: BackupRestorePublisher,
    private val passkeyPrf: BackupPasskeyPrfCapability = UnavailableBackupPasskeyPrfCapability,
    private val deviceKey: BackupDeviceKeyCapability = UnavailableBackupDeviceKeyCapability,
    private val reviewIDMint: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private var current = BackupRestoreState()

    /** The local envelope custody a prepared review would adopt on success. Closed on discard. */
    private var pendingAdoption: BackupRestoreAdoption? = null

    fun state(): BackupRestoreState = synchronized(lock) { current }

    /**
     * The unlock-method capabilities for this build/device. Recovery code is always present; device
     * key and passkey are present only when their own capability probe says so (both default
     * unavailable, so no method is declared without a production unlock path behind it).
     */
    fun capabilities(): List<BackupRecoveryCapability> = listOf(
        BackupRecoveryCapability(
            BackupRecoveryMethod.DEVICE_KEY,
            if (deviceKey.isAvailable()) {
                BackupRecoveryAvailability.AVAILABLE
            } else {
                BackupRecoveryAvailability.UNAVAILABLE
            },
        ),
        BackupRecoveryCapability(BackupRecoveryMethod.RECOVERY_CODE, BackupRecoveryAvailability.AVAILABLE),
        BackupRecoveryCapability(
            BackupRecoveryMethod.PASSKEY,
            if (passkeyPrf.isAvailable()) {
                BackupRecoveryAvailability.AVAILABLE
            } else {
                BackupRecoveryAvailability.UNAVAILABLE
            },
        ),
    )

    fun selectMethod(method: BackupRecoveryMethod): BackupRecoverySelection =
        BackupRecoveryCapabilityPolicy.select(method, capabilities())

    /**
     * Prepares an immutable review from the platform's unlock result. On any refusal the owner
     * returns to a clean [BackupRestorePhase.IDLE] with nothing staged.
     */
    fun prepare(
        unlock: BackupRestoreUnlock,
        method: BackupRecoveryMethod,
        currentLibrary: FilterLibraryDraft,
        currentPreferences: BackupRetainedPreferences,
        basis: BackupRestoreBasis,
    ): BackupRestorePreparation = synchronized(lock) {
        // A new preparation replaces any pending review and its adoption custody.
        pendingAdoption?.close()
        pendingAdoption = (unlock as? BackupRestoreUnlock.Unlocked)?.adoption
        val unlockRefusal = refusalFor(unlock, method)
        if (unlockRefusal != null) {
            pendingAdoption?.close()
            pendingAdoption = null
            current = BackupRestoreState(lastRefusal = unlockRefusal)
            return@synchronized BackupRestorePreparation.Refused(unlockRefusal)
        }
        val payload = (unlock as BackupRestoreUnlock.Unlocked).payload
        return@synchronized when (
            val result = BackupRestorePlan.prepare(payload, currentLibrary, currentPreferences, basis)
        ) {
            is BackupRestorePlanResult.Refused -> {
                pendingAdoption?.close()
                pendingAdoption = null
                current = BackupRestoreState(lastRefusal = result.refusal)
                BackupRestorePreparation.Refused(result.refusal)
            }

            is BackupRestorePlanResult.Valid -> {
                val reviewID = reviewIDMint()
                current = BackupRestoreState(
                    phase = BackupRestorePhase.REVIEW,
                    reviewID = reviewID,
                    plan = result.plan,
                    lastRefusal = null,
                )
                BackupRestorePreparation.Prepared(reviewID, result.plan)
            }
        }
    }

    /**
     * Discards a prepared review. Closing a review must discard it so a later confirmation cannot
     * apply a stale plan; the exact identified review is required.
     */
    fun discard(reviewID: String): Boolean = synchronized(lock) {
        if (current.reviewID != reviewID) return@synchronized false
        pendingAdoption?.close()
        pendingAdoption = null
        current = BackupRestoreState(lastRefusal = null)
        true
    }

    /**
     * Confirms a prepared review exactly once. The review is consumed before the non-cancellable
     * publish, the current basis is re-validated, and the single publish either commits atomically
     * or is rolled back and refused.
     */
    fun confirm(
        reviewID: String,
        resolverChangeConfirmed: Boolean,
        currentBasis: BackupRestoreBasis,
        currentLibrary: FilterLibraryDraft,
    ): BackupRestoreApplyOutcome = synchronized(lock) {
        val plan = current.plan
        if (plan == null || current.reviewID != reviewID) {
            return@synchronized BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.NoReview)
        }
        val stale = plan.validateConfirmation(currentBasis, currentLibrary, resolverChangeConfirmed)
        if (stale != null) {
            // A stale or unacknowledged review is consumed; the caller must re-review.
            pendingAdoption?.close()
            pendingAdoption = null
            current = BackupRestoreState(lastRefusal = stale)
            return@synchronized BackupRestoreApplyOutcome.Refused(stale)
        }
        // Consume the review before the publish, exactly like iOS; an interrupted publish can never
        // leave the review available to be applied twice.
        val adoption = pendingAdoption
        pendingAdoption = null
        current = BackupRestoreState(lastRefusal = null)
        try {
            val checkpoint = try {
                publisher.checkpoint()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                current = BackupRestoreState(lastRefusal = BackupRestoreRefusal.PublishInterrupted)
                return@synchronized BackupRestoreApplyOutcome.Refused(BackupRestoreRefusal.PublishInterrupted)
            }
            val committed = try {
                publisher.publish(plan, adoption)
            } catch (cancelled: CancellationException) {
                // The port may have partially written before cancellation. Compensate before
                // propagating retirement, and release adoption custody in the outer finally.
                runCatching { publisher.restore(checkpoint) }
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!committed) {
                // A rollback that is not durably re-committed leaves a possibly-inconsistent local
                // state; refuse with the stronger reason instead of promising nothing changed.
                val refusal = if (runCatching { publisher.restore(checkpoint) }.getOrDefault(false)) {
                    BackupRestoreRefusal.PublishInterrupted
                } else {
                    BackupRestoreRefusal.RollbackFailed
                }
                current = BackupRestoreState(lastRefusal = refusal)
                return@synchronized BackupRestoreApplyOutcome.Refused(refusal)
            }
            current = BackupRestoreState(phase = BackupRestorePhase.APPLIED, plan = plan, lastRefusal = null)
            BackupRestoreApplyOutcome.Applied(plan)
        } finally {
            adoption?.close()
        }
    }

    private fun refusalFor(unlock: BackupRestoreUnlock, method: BackupRecoveryMethod): BackupRestoreRefusal? {
        val unlockRefusal = when (unlock) {
            is BackupRestoreUnlock.Unlocked -> null
            BackupRestoreUnlock.WrongSecret -> BackupRestoreRefusal.WrongSecret
            BackupRestoreUnlock.Cancelled -> BackupRestoreRefusal.Unavailable
            BackupRestoreUnlock.CorruptPayload -> BackupRestoreRefusal.CorruptPayload
            is BackupRestoreUnlock.UnsupportedSchema -> BackupRestoreRefusal.UnsupportedSchema(unlock.schemaVersion)
            BackupRestoreUnlock.Unavailable -> BackupRestoreRefusal.Unavailable
        }
        if (unlockRefusal != null) return unlockRefusal
        if (method == BackupRecoveryMethod.PASSKEY && !selectMethod(method).isUsable) {
            return BackupRestoreRefusal.PasskeyUnavailable
        }
        if (method == BackupRecoveryMethod.DEVICE_KEY && !selectMethod(method).isUsable) {
            return BackupRestoreRefusal.Unavailable
        }
        return null
    }
}
