package com.lavasec.app.foundation.backup

/**
 * A snapshot of the durable deletion fence: whether it could be read, and the persisted intent if
 * one exists. This is the ONLY state the deletion transitions read; it is produced by
 * [BackupDeletionState.from] over a [BackupDeletionRead] or rebuilt after an acknowledged write.
 */
data class BackupDeletionState(
    val fenceReadable: Boolean,
    val intent: BackupDeletionIntent? = null,
) {
    /**
     * True whenever ANY fence exists — including a completed `disabled` marker, which keeps Off
     * authoritative and continues to block writers until explicit re-enablement clears it. This
     * mirrors the canonical `hasBackupDeletionFence`.
     */
    val hasDeletionFence: Boolean get() = !fenceReadable || intent != null

    /** True while an unfinished deletion still blocks backup work by its phase. */
    val hasPendingDeletion: Boolean get() = intent?.blocksBackupWork == true

    /**
     * Whether the pending deletion may be retried right now. A `localCleanupPending` teardown is
     * account-independent (the remote copy is already gone), so it retries locally; a
     * `remotePending` remote delete may only be retried by the initiating account.
     */
    fun canRetryDeletion(currentAccountID: String?): Boolean =
        fenceReadable && intent != null &&
            (intent.phase == BackupDeletionPhase.LOCAL_CLEANUP_PENDING || intent.canDeleteRemote(currentAccountID))

    companion object {
        /** Fence absent and readable: backup work is unblocked. */
        val CLEAR = BackupDeletionState(fenceReadable = true, intent = null)

        /** Fence unreadable: fail closed and surface an unavailable state. */
        val UNREADABLE = BackupDeletionState(fenceReadable = false, intent = null)

        /**
         * Projects the durable read into the state the enablement decision consumes. An unreadable
         * fence becomes `fenceReadable = false`, which fails closed.
         */
        fun from(read: BackupDeletionRead): BackupDeletionState = when (read) {
            is BackupDeletionRead.Present -> BackupDeletionState(fenceReadable = true, intent = read.intent)
            BackupDeletionRead.Absent -> CLEAR
            BackupDeletionRead.Unreadable -> UNREADABLE
        }
    }
}

/** Why a deletion transition could not produce a new durable record. */
enum class BackupDeletionRefusal {
    /** No account is signed in and backup is not proven device-local, so no fence can be bound. */
    NO_ACCOUNT,

    /** The initiating account no longer matches the account an operation was prepared for. */
    ACCOUNT_MISMATCH,

    /** The fence could not be read, so no transition may proceed. */
    FENCE_UNREADABLE,

    /** A remote-confirmation transition was attempted from a non-`remotePending` phase. */
    NOT_REMOTE_PENDING,

    /** A local-cleanup transition was attempted before remote deletion was confirmed. */
    NOT_LOCAL_CLEANUP_PENDING,

    /** The deletion is already complete; explicit setup is required to re-enable. */
    ALREADY_COMPLETED,
}

/**
 * The next durable step a deletion transition requires. A [Persist] result is an instruction, not
 * an outcome: the caller must write it (and the write must be acknowledged) BEFORE performing the
 * authority-bearing step it unblocks. That ordering is what makes the fence durable across an
 * interruption.
 */
sealed interface BackupDeletionTransition {
    /** Write [next], then proceed. A failed write aborts the operation with no false progress. */
    data class Persist(val next: BackupDeletionIntent) : BackupDeletionTransition

    /** Clear the fence (safe to resume backup work) after an acknowledged delete. */
    data class Clear(val prior: BackupDeletionIntent) : BackupDeletionTransition

    /** No record change; resume/retry with the existing fence when [intent] is non-null. */
    data class Retryable(val intent: BackupDeletionIntent?) : BackupDeletionTransition

    /** Refuse the transition; the current state is retained. */
    data class Refused(val refusal: BackupDeletionRefusal) : BackupDeletionTransition
}

/**
 * Pure deletion state machine, ported from the canonical iOS `BackupController` disable/delete
 * ordering (pinned at `c4658d9`).
 *
 * Two invariants are structural, not conventions:
 *
 * 1. The durable fence is written BEFORE the remote delete and BEFORE local teardown
 *    ([beginUserDisable] returns a [BackupDeletionTransition.Persist]); a remote delete that is not
 *    confirmed never advances the phase ([advanceAfterRemoteDeletionUnconfirmed]).
 * 2. The phase only advances forward: `remotePending` -> `localCleanupPending` -> `disabled`.
 *    Local teardown is unreachable while remote deletion is unconfirmed, and a remote retry is a
 *    no-op once confirmation has landed.
 *
 * Account binding is enforced at every authority-bearing step: a transition prepared for account A
 * refuses to run against account B, which is what invalidates a pending operation across an account
 * switch.
 */
object BackupDeletionStateMachine {

    /**
     * Begins (or resumes) the user-requested turn-off. With no existing intent this creates the
     * account-bound fence; a proven device-local backup starts directly in `localCleanupPending`
     * because there is nothing remote to delete.
     */
    fun beginUserDisable(
        existing: BackupDeletionIntent?,
        currentAccountID: String?,
        isProvenLocalOnly: Boolean,
    ): BackupDeletionTransition {
        if (existing != null) {
            if (existing.phase == BackupDeletionPhase.DISABLED) {
                return BackupDeletionTransition.Refused(BackupDeletionRefusal.ALREADY_COMPLETED)
            }
            return BackupDeletionTransition.Retryable(existing)
        }
        val accountID = currentAccountID
            ?: if (isProvenLocalOnly) BackupDeletionIntent.DEVICE_LOCAL_ONLY_ACCOUNT_ID else null
        if (accountID == null || !BackupDeletionIntent.isWellFormedAccountID(accountID)) {
            return BackupDeletionTransition.Refused(BackupDeletionRefusal.NO_ACCOUNT)
        }
        val phase = if (isProvenLocalOnly) {
            BackupDeletionPhase.LOCAL_CLEANUP_PENDING
        } else {
            BackupDeletionPhase.REMOTE_PENDING
        }
        return BackupDeletionTransition.Persist(BackupDeletionIntent.create(accountID, phase))
    }

    /**
     * Remote deletion was confirmed for the initiating account, so the fence advances to
     * `localCleanupPending`. Both the expected account and the currently signed-in account must
     * still match the binding, or the operation is stale and must not proceed.
     */
    fun advanceAfterRemoteDeletionConfirmed(
        intent: BackupDeletionIntent,
        expectedAccountID: String?,
        currentAccountID: String?,
    ): BackupDeletionTransition {
        if (intent.phase != BackupDeletionPhase.REMOTE_PENDING) {
            return BackupDeletionTransition.Refused(BackupDeletionRefusal.NOT_REMOTE_PENDING)
        }
        if (expectedAccountID != intent.accountID || currentAccountID != intent.accountID) {
            return BackupDeletionTransition.Refused(BackupDeletionRefusal.ACCOUNT_MISMATCH)
        }
        return BackupDeletionTransition.Persist(intent.withPhase(BackupDeletionPhase.LOCAL_CLEANUP_PENDING))
    }

    /**
     * Remote deletion could not be confirmed. The phase stays `remotePending`, so local teardown is
     * unreachable and the operation is retryable by the initiating account only.
     */
    fun advanceAfterRemoteDeletionUnconfirmed(intent: BackupDeletionIntent): BackupDeletionTransition =
        if (intent.phase == BackupDeletionPhase.REMOTE_PENDING) {
            BackupDeletionTransition.Retryable(intent)
        } else {
            BackupDeletionTransition.Refused(BackupDeletionRefusal.NOT_REMOTE_PENDING)
        }

    /** Local teardown completed for every artifact; persist the `disabled` tombstone. */
    fun advanceAfterLocalCleanupCompleted(intent: BackupDeletionIntent): BackupDeletionTransition =
        if (intent.phase == BackupDeletionPhase.LOCAL_CLEANUP_PENDING) {
            BackupDeletionTransition.Persist(intent.withPhase(BackupDeletionPhase.DISABLED))
        } else {
            BackupDeletionTransition.Refused(BackupDeletionRefusal.NOT_LOCAL_CLEANUP_PENDING)
        }

    /**
     * Local teardown failed for at least one artifact. The fence stays `localCleanupPending` so the
     * retry resumes cleanup without re-contacting the server and without ever reporting success.
     */
    fun advanceAfterLocalCleanupFailed(intent: BackupDeletionIntent): BackupDeletionTransition =
        if (intent.phase == BackupDeletionPhase.LOCAL_CLEANUP_PENDING) {
            BackupDeletionTransition.Retryable(intent)
        } else {
            BackupDeletionTransition.Refused(BackupDeletionRefusal.NOT_LOCAL_CLEANUP_PENDING)
        }

    /**
     * Account-deletion preflight. A durable retained-envelope fence (version 3) must exist before
     * the irreversible account request; an already-pending intent for the same account is reused,
     * a different account's fence is never overwritten.
     */
    fun prepareAccountDeletion(
        existing: BackupDeletionIntent?,
        accountID: String,
    ): BackupDeletionTransition {
        if (!BackupDeletionIntent.isWellFormedAccountID(accountID)) {
            return BackupDeletionTransition.Refused(BackupDeletionRefusal.NO_ACCOUNT)
        }
        if (existing != null) {
            if (existing.accountID != accountID) {
                return BackupDeletionTransition.Refused(BackupDeletionRefusal.ACCOUNT_MISMATCH)
            }
            if (existing.blocksBackupWork) {
                return BackupDeletionTransition.Retryable(existing)
            }
            // A completed tombstone means the envelope is already gone; no new fence is needed.
            return BackupDeletionTransition.Retryable(existing)
        }
        return BackupDeletionTransition.Persist(
            BackupDeletionIntent.create(
                accountID = accountID,
                phase = BackupDeletionPhase.REMOTE_PENDING,
                retainsEnvelopeForRecovery = true,
            ),
        )
    }

    /**
     * A lost account-deletion response is ambiguous. Only a fresh session proven for the EXACT
     * account bound by a version-3 `remotePending` preparation authorizes clearing its fence; any
     * other account or phase leaves the durable intent intact.
     */
    fun cancelAccountDeletionPreparation(
        intent: BackupDeletionIntent,
        currentAccountID: String?,
        freshSessionAccountID: String?,
    ): BackupDeletionTransition {
        val prepared = intent.version == 3 &&
            intent.retainsEnvelopeForRecovery == true &&
            intent.phase == BackupDeletionPhase.REMOTE_PENDING
        if (prepared && currentAccountID == intent.accountID && freshSessionAccountID == intent.accountID) {
            return BackupDeletionTransition.Clear(intent)
        }
        return BackupDeletionTransition.Refused(BackupDeletionRefusal.ACCOUNT_MISMATCH)
    }

    /**
     * Applies a transition through the durable store. The rule encoded here is the whole point of
     * the fence: a [BackupDeletionTransition.Persist] only becomes the new state once the write is
     * ACKNOWLEDGED. A failed write leaves the previous state untouched, so an interruption during
     * local persistence can never masquerade as a completed deletion.
     */
    fun apply(
        transition: BackupDeletionTransition,
        current: BackupDeletionState,
        store: BackupDeletionIntentStore,
    ): BackupDeletionState = when (transition) {
        is BackupDeletionTransition.Persist ->
            if (store.write(transition.next)) {
                BackupDeletionState(fenceReadable = true, intent = transition.next)
            } else {
                current
            }

        is BackupDeletionTransition.Clear ->
            if (store.clear()) BackupDeletionState.CLEAR else current

        is BackupDeletionTransition.Retryable -> current

        is BackupDeletionTransition.Refused -> current
    }
}
