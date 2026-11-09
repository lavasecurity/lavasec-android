package com.lavasec.app.foundation.backup

import java.util.UUID

/**
 * Durable checkpoints of the account-bound backup deletion fence.
 *
 * Mirrors the canonical iOS `BackupDeletionIntent.Phase` raw values so both clients can read the
 * same persisted record and agree on what has been acknowledged. `remotePending` is the only phase
 * in which a remote retry is authorized; `localCleanupPending` means the server copy is confirmed
 * gone and only device-local teardown remains; `disabled` is the completed tombstone that overrides
 * stale ordinary preferences until explicit re-enablement clears it.
 */
enum class BackupDeletionPhase(val wireValue: String) {
    /** The original account still needs a confirmed remote deletion. */
    REMOTE_PENDING("remotePending"),

    /** Remote deletion was confirmed; only local teardown remains. */
    LOCAL_CLEANUP_PENDING("localCleanupPending"),

    /** Teardown completed; this marker overrides stale ordinary preferences. */
    DISABLED("disabled"),
    ;

    companion object {
        fun fromWireValue(value: String): BackupDeletionPhase? = entries.firstOrNull { it.wireValue == value }
    }
}

/** A stored deletion record was unsupported (unknown version) or corrupt. */
class BackupDeletionIntentException(message: String) : IllegalArgumentException(message)

/**
 * App-only durable intent that reconciles a requested backup turn-off with the two systems that
 * can change independently: the remote row and the device-local artifacts.
 *
 * Ported from the canonical iOS `BackupDeletionIntent` (pinned at `c4658d9`). It is pure: no
 * storage, no network, and no secrets. [version] and [operationID] are durable-format compatibility
 * facts (older builds must reject newer retained-envelope records), not runtime state.
 *
 * A pending intent blocks every local and remote backup writer. A completed `disabled` marker keeps
 * Off authoritative even if termination happened before ordinary preferences reached disk; that
 * marker is cleared only by a successful explicit setup.
 */
class BackupDeletionIntent private constructor(
    /** Persisted record version; unknown versions are rejected conservatively. */
    val version: Int,
    /** The authenticated account captured before the first deletion suspension. */
    val accountID: String,
    /** Last confirmed durable checkpoint. */
    val phase: BackupDeletionPhase,
    /**
     * Account removal retires local unlock keys but preserves encrypted recovery material. Older
     * records omit this field and still delete the envelope.
     */
    val retainsEnvelopeForRecovery: Boolean?,
    /** Binds a server-confirmation receipt to this preparation, never a later deletion. */
    val operationID: String?,
) {
    /** Pending teardown prevents every local and remote backup writer. */
    val blocksBackupWork: Boolean get() = phase != BackupDeletionPhase.DISABLED

    /** Remote retries may only use the initiating account. */
    fun canDeleteRemote(currentAccountID: String?): Boolean =
        phase == BackupDeletionPhase.REMOTE_PENDING && currentAccountID != null && currentAccountID == accountID

    /**
     * A separate confirmation can advance only the exact retained-envelope preparation: same
     * account, same operation receipt, and the `remotePending` -> `localCleanupPending` transition.
     */
    fun confirmsLocalCleanup(preparation: BackupDeletionIntent): Boolean =
        version == 3 && preparation.version == 3 &&
            retainsEnvelopeForRecovery == true && preparation.retainsEnvelopeForRecovery == true &&
            phase == BackupDeletionPhase.LOCAL_CLEANUP_PENDING &&
            preparation.phase == BackupDeletionPhase.REMOTE_PENDING &&
            accountID == preparation.accountID &&
            operationID != null && operationID == preparation.operationID

    /** Advances the durable checkpoint, preserving the record identity and version. */
    fun withPhase(next: BackupDeletionPhase): BackupDeletionIntent =
        of(
            version = version,
            accountID = accountID,
            phase = next,
            retainsEnvelopeForRecovery = retainsEnvelopeForRecovery,
            operationID = operationID,
        )

    override fun equals(other: Any?): Boolean =
        other is BackupDeletionIntent &&
            version == other.version &&
            accountID == other.accountID &&
            phase == other.phase &&
            retainsEnvelopeForRecovery == other.retainsEnvelopeForRecovery &&
            operationID == other.operationID

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + accountID.hashCode()
        result = 31 * result + phase.hashCode()
        result = 31 * result + (retainsEnvelopeForRecovery?.hashCode() ?: 0)
        result = 31 * result + (operationID?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "BackupDeletionIntent(version=$version, accountID=$accountID, phase=${phase.wireValue}, " +
            "retainsEnvelopeForRecovery=$retainsEnvelopeForRecovery, operationID=$operationID)"

    companion object {
        /**
         * The account identifier used only when backup is PROVEN device-local, so a local-only
         * teardown can complete without ever contacting a server.
         */
        const val DEVICE_LOCAL_ONLY_ACCOUNT_ID = "device-local-only"

        /**
         * Creates an intent for a known account before any network operation. Version is derived
         * exactly as canon does: a retained-envelope `remotePending` preparation is version 3 and
         * mints an operation receipt; a retained-envelope later phase is version 2; every other
         * record is version 1 with no receipt.
         */
        fun create(
            accountID: String,
            phase: BackupDeletionPhase = BackupDeletionPhase.REMOTE_PENDING,
            retainsEnvelopeForRecovery: Boolean? = null,
            operationID: String? = null,
            operationIDProvider: () -> String = { UUID.randomUUID().toString() },
        ): BackupDeletionIntent {
            val version = when {
                retainsEnvelopeForRecovery == true -> {
                    if (phase == BackupDeletionPhase.REMOTE_PENDING) 3 else 2
                }

                else -> 1
            }
            return of(
                version = version,
                accountID = accountID,
                phase = phase,
                retainsEnvelopeForRecovery = retainsEnvelopeForRecovery,
                // Older builds must not treat the retained material as an Off envelope. Only the
                // version-3 preparation carries the receipt that can confirm its cleanup.
                operationID = if (version == 3) operationID ?: operationIDProvider() else null,
            )
        }

        /**
         * Validates a record loaded from durable storage without treating corruption as an absent
         * intent. Rejects unsupported version/phase/retained combinations and a blank or
         * whitespace-padded account identifier, mirroring the canonical `decode(_:)`.
         */
        fun fromPersisted(
            version: Int,
            accountID: String,
            phase: BackupDeletionPhase,
            retainsEnvelopeForRecovery: Boolean?,
            operationID: String?,
        ): BackupDeletionIntent {
            if (!isSupportedRecord(version, retainsEnvelopeForRecovery, phase)) {
                throw BackupDeletionIntentException(
                    "unsupported backup deletion intent version=$version phase=${phase.wireValue} " +
                        "retainsEnvelopeForRecovery=$retainsEnvelopeForRecovery",
                )
            }
            if (!isWellFormedAccountID(accountID)) {
                throw BackupDeletionIntentException("backup deletion intent account id is blank or padded")
            }
            return of(version, accountID, phase, retainsEnvelopeForRecovery, operationID)
        }

        /**
         * Canonical version support matrix. A version-1 record must not retain envelope material;
         * a version-2 record retains it and has already advanced past remote deletion; a version-3
         * record retains it and may still be remote-pending.
         */
        fun isSupportedRecord(
            version: Int,
            retainsEnvelopeForRecovery: Boolean?,
            phase: BackupDeletionPhase,
        ): Boolean = when (version) {
            1 -> retainsEnvelopeForRecovery != true
            2 -> retainsEnvelopeForRecovery == true && phase != BackupDeletionPhase.REMOTE_PENDING
            3 -> retainsEnvelopeForRecovery == true
            else -> false
        }

        fun isWellFormedAccountID(accountID: String): Boolean =
            accountID.isNotEmpty() && accountID == accountID.trim()

        private fun of(
            version: Int,
            accountID: String,
            phase: BackupDeletionPhase,
            retainsEnvelopeForRecovery: Boolean?,
            operationID: String?,
        ): BackupDeletionIntent =
            BackupDeletionIntent(version, accountID, phase, retainsEnvelopeForRecovery, operationID)
    }
}
