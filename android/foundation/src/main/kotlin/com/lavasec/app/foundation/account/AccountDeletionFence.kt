package com.lavasec.app.foundation.account

/**
 * Result of reading the durable deletion fence. [Unreadable] is deliberately distinct from
 * [Absent]: an unreadable record must fail closed and never be treated as "no pending deletion",
 * exactly like the canonical backup deletion fence.
 */
sealed interface AccountDeletionRead {
    data class Present(val record: AccountDeletionRecord) : AccountDeletionRead

    data object Absent : AccountDeletionRead

    data object Unreadable : AccountDeletionRead
}

/**
 * Durable seam for the account deletion fence. Implementations own the on-disk format and platform
 * mechanics; the state machine stays format-agnostic and only consumes [AccountDeletionRead] /
 * emits a record to persist. A local sign-out must NOT clear this fence — the pending server
 * deletion outlives the local session and is completed only by an acknowledged deletion.
 */
interface AccountDeletionStore {
    fun read(): AccountDeletionRead

    /** Persists [record], returning whether the write was acknowledged. */
    fun write(record: AccountDeletionRecord): Boolean

    /** Removes the fence for [operationId], returning whether absence was acknowledged. */
    fun clear(operationId: Long): Boolean
}

/** Pure projection of the durable fence plus the acknowledged-write transitions. */
object AccountDeletionFence {

    /** Restores the pending record, or null when the fence is readable-and-absent. */
    fun restore(read: AccountDeletionRead): AccountDeletionRecord? = when (read) {
        is AccountDeletionRead.Present -> read.record
        AccountDeletionRead.Absent -> null
        AccountDeletionRead.Unreadable -> null
    }

    /** True while the fence cannot be read, so callers must fail closed. */
    fun isUnreadable(read: AccountDeletionRead): Boolean = read == AccountDeletionRead.Unreadable

    /**
     * Attaches or clears the durable fence around a result. A [AccountFlowResult.Applied] carrying a
     * newer record is written first and only adopted when the write is acknowledged; a completion
     * that removed the record clears it only on an acknowledged clear.
     */
    fun apply(
        result: AccountFlowResult,
        previous: AccountFlowState,
        store: AccountDeletionStore,
    ): AccountFlowState = when (result) {
        is AccountFlowResult.Applied -> {
            val next = result.state.deletion
            val before = previous.deletion
            when {
                next != null -> if (store.write(next)) result.state else previous
                before != null && next == null -> if (store.clear(before.operationId)) result.state else previous
                else -> result.state
            }
        }

        is AccountFlowResult.Ignored -> previous
        is AccountFlowResult.Refused -> previous
    }
}
