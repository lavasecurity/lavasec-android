package com.lavasec.app.foundation.subscription

/**
 * The client-verified store receipt identity an account entitlement mirror carries, projected to
 * exactly the fields the canonical sync sends. Mirrors `LavaSecurityPlusEntitlementSyncRequest` and
 * the sync client's non-nil guard in `LavaSecApp/LavaSecurityPlusController.swift` (pinned
 * `c4658d973bae05dd3b7aa84c014cb2f76ff61a0b`, lines 24-37 and 93-106): `product_id`,
 * `transaction_id`, `original_transaction_id`, `signed_transaction_jws`.
 *
 * [identity] is the replay identity: retrying the exact same receipt for the same account is a
 * no-op, while a renewal (a new [transactionId] on the same [originalTransactionId]) is a new
 * receipt that must re-mirror so the account row refreshes. The signed JWS is deliberately excluded
 * from the identity — a freshly signed copy of the same transaction is the same evidence.
 */
data class AccountEntitlementReceipt(
    val productId: String,
    val transactionId: String,
    val originalTransactionId: String,
    val signedTransactionJws: String,
) {
    val identity: String get() = "$originalTransactionId:$transactionId:$productId"
}

/**
 * A device entitlement read projected to the evidence an account mirror needs, so the mirror stays
 * independent of device purchase access: once the platform store has produced this value, the
 * policy below needs nothing from the store.
 *
 * [receipt] is non-null only for an active, complete, signed transaction, matching the canonical
 * guard in `LavaSecApp/LavaSecurityPlusController.swift` (pinned `c4658d9`, lines 411-417) and the
 * sync client guard (lines 24-28). An inactive read, an empty JWS, or a missing transaction id is
 * not mirrorable evidence.
 */
data class AccountEntitlementEvidence(
    val isActive: Boolean,
    val productId: String? = null,
    val transactionId: String? = null,
    val originalTransactionId: String? = null,
    val signedTransactionJws: String? = null,
) {
    val receipt: AccountEntitlementReceipt?
        get() {
            if (!isActive) return null
            val jws = signedTransactionJws
            if (jws.isNullOrEmpty()) return null
            return AccountEntitlementReceipt(
                productId = productId ?: return null,
                transactionId = transactionId ?: return null,
                originalTransactionId = originalTransactionId ?: return null,
                signedTransactionJws = jws,
            )
        }
}

/**
 * A mirror operation bound, when it begins, to the account that owns it and the receipt being
 * mirrored. The binding is immutable so every retry — including the post-401 refreshed-session
 * retry at `LavaSecurityPlusController.swift` lines 432-447 — is re-checked against the SAME origin
 * account before it may write. Pinned `c4658d9`.
 */
data class AccountEntitlementMirrorOperation(
    val accountId: String,
    val receipt: AccountEntitlementReceipt,
)

/**
 * The last receipt successfully mirrored for an account. The canonical endpoint upserts the
 * `entitlements` row on `user_id` (`POST /v1/account/entitlements/app-store-sync`), so an account
 * holds at most one mirrored receipt and a newer one supersedes it.
 */
data class AccountEntitlementMirrorRecord(
    val accountId: String,
    val receiptIdentity: String,
)

/** Why a bound mirror operation may not write now. */
enum class AccountEntitlementMirrorRefusal {
    /** No active, complete, signed receipt to mirror. */
    NO_ACTIVE_RECEIPT,

    /** There was no signed-in account when the mirror began. */
    NO_ACCOUNT,

    /** The account that owned the operation is gone at this attempt (sign-out). */
    SIGNED_OUT,

    /** The live account is a different account than the one the operation was bound to. */
    ACCOUNT_CHANGED,
}

sealed interface AccountEntitlementMirrorDecision {
    /** Write the bound receipt to the bound account. */
    data class Write(val operation: AccountEntitlementMirrorOperation) : AccountEntitlementMirrorDecision

    /** The same account already holds this exact receipt; the retry is a no-op. */
    data class AlreadyMirrored(val operation: AccountEntitlementMirrorOperation) : AccountEntitlementMirrorDecision

    /** No write may happen; [reason] is a durable, testable fact. */
    data class Refused(val reason: AccountEntitlementMirrorRefusal) : AccountEntitlementMirrorDecision
}

/**
 * Pure decision for the optional device-to-account entitlement mirror. It models only the canonical
 * one-way mirror (a device receipt is written to the account that is signed in); it does not read an
 * account-held entitlement to grant Plus, and it never aggregates across stores.
 *
 * The safety contract is the account binding: the live account is passed in at every attempt, so a
 * retry that follows a sign-out ([AccountEntitlementMirrorRefusal.SIGNED_OUT]) or an account switch
 * ([AccountEntitlementMirrorRefusal.ACCOUNT_CHANGED]) can never write the first account's receipt
 * into another account.
 */
object AccountEntitlementMirrorPolicy {
    /**
     * Decide whether [evidence] may be mirrored to [currentAccountId] as a retry of an operation
     * bound to [boundAccountId].
     *
     * @param boundAccountId the account captured when the mirror began.
     * @param evidence the device entitlement read, projected to mirrorable evidence.
     * @param currentAccountId the account live at THIS attempt (null/blank after sign-out).
     * @param mirrored the last successfully mirrored record, if any.
     */
    fun decide(
        boundAccountId: String?,
        evidence: AccountEntitlementEvidence,
        currentAccountId: String?,
        mirrored: AccountEntitlementMirrorRecord?,
    ): AccountEntitlementMirrorDecision {
        val receipt = evidence.receipt
            ?: return AccountEntitlementMirrorDecision.Refused(AccountEntitlementMirrorRefusal.NO_ACTIVE_RECEIPT)
        val bound = boundAccountId?.takeIf { it.isNotEmpty() }
            ?: return AccountEntitlementMirrorDecision.Refused(AccountEntitlementMirrorRefusal.NO_ACCOUNT)
        val current = currentAccountId?.takeIf { it.isNotEmpty() }
            ?: return AccountEntitlementMirrorDecision.Refused(AccountEntitlementMirrorRefusal.SIGNED_OUT)
        if (current != bound) {
            return AccountEntitlementMirrorDecision.Refused(AccountEntitlementMirrorRefusal.ACCOUNT_CHANGED)
        }

        val operation = AccountEntitlementMirrorOperation(accountId = bound, receipt = receipt)
        val alreadyMirrored = mirrored != null &&
            mirrored.accountId == bound &&
            mirrored.receiptIdentity == receipt.identity
        return if (alreadyMirrored) {
            AccountEntitlementMirrorDecision.AlreadyMirrored(operation)
        } else {
            AccountEntitlementMirrorDecision.Write(operation)
        }
    }

    /** Record a successful write, to be passed back as `mirrored` on the next retry. */
    fun record(operation: AccountEntitlementMirrorOperation): AccountEntitlementMirrorRecord =
        AccountEntitlementMirrorRecord(
            accountId = operation.accountId,
            receiptIdentity = operation.receipt.identity,
        )
}
