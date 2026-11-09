package com.lavasec.app.foundation.backup

/**
 * Result of reading the durable deletion fence. [Unreadable] is a real, distinct outcome: it means
 * the fence could not be read or did not validate, which fails closed exactly like a present
 * pending intent — an absent intent is the only state in which backup work is unblocked.
 */
sealed interface BackupDeletionRead {
    /** A valid intent was present. */
    data class Present(val intent: BackupDeletionIntent) : BackupDeletionRead

    /** No intent was stored. */
    data object Absent : BackupDeletionRead

    /** A record was found but could not be read or validated; treat it as a fence, never as absent. */
    data object Unreadable : BackupDeletionRead
}

/**
 * Durable seam for the account-bound deletion fence. Implementations own the on-disk format and the
 * platform mechanics (Keychain/UserDefaults on iOS, Keystore/DataStore on Android). The state
 * machine is deliberately format-agnostic: it consumes [BackupDeletionRead] and emits an intent to
 * persist, never bytes.
 */
interface BackupDeletionIntentStore {
    fun read(): BackupDeletionRead

    /** Persists [intent], returning whether the write was acknowledged. */
    fun write(intent: BackupDeletionIntent): Boolean

    /** Removes the fence, returning whether absence was acknowledged. */
    fun clear(): Boolean
}

/**
 * Canonical JSON codec for the durable deletion record. This is the exact wire shape the iOS
 * `BackupDeletionIntent` `Codable` synthesis produces (`version`, `accountID`, `phase`, optional
 * `retainsEnvelopeForRecovery`, optional `operationID`; nil optionals omitted).
 *
 * It is provided so a durable store can be implemented and round-tripped, and so the version
 * support matrix has an executable boundary. It is not required by, and carries no Android types
 * into, the pure state machine.
 */
object BackupDeletionIntentCodec {

    fun encode(intent: BackupDeletionIntent): String {
        val members = linkedMapOf<String, BackupJsonValue>(
            "accountID" to BackupJsonValue.JsonString(intent.accountID),
            "phase" to BackupJsonValue.JsonString(intent.phase.wireValue),
            "version" to BackupJsonValue.JsonNumber(intent.version.toString()),
        )
        intent.retainsEnvelopeForRecovery?.let {
            members["retainsEnvelopeForRecovery"] = BackupJsonValue.JsonBoolean(it)
        }
        intent.operationID?.let {
            members["operationID"] = BackupJsonValue.JsonString(it)
        }
        return BackupJsonCodec.print(BackupJsonValue.JsonObject(members))
    }

    fun decode(text: String): BackupDeletionIntent {
        val root = BackupJsonCodec.parse(text) as? BackupJsonValue.JsonObject
            ?: throw BackupDeletionIntentException("backup deletion intent must be a JSON object")
        val version = root.members["version"]?.asIntOrNull()
            ?: throw BackupDeletionIntentException("backup deletion intent is missing an integer version")
        val accountID = root.members["accountID"]?.asStringOrNull()
            ?: throw BackupDeletionIntentException("backup deletion intent is missing an accountID")
        val phaseValue = root.members["phase"]?.asStringOrNull()
            ?: throw BackupDeletionIntentException("backup deletion intent is missing a phase")
        val phase = BackupDeletionPhase.fromWireValue(phaseValue)
            ?: throw BackupDeletionIntentException("unsupported backup deletion intent phase='$phaseValue'")
        val retains = root.members["retainsEnvelopeForRecovery"]?.asBooleanOrNull()
        val operationID = root.members["operationID"]?.asStringOrNull()
        return BackupDeletionIntent.fromPersisted(version, accountID, phase, retains, operationID)
    }
}
