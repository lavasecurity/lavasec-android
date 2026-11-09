package com.lavasec.app.foundation.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountEntitlementMirrorTest {
    private fun activeEvidence(
        productId: String = "lava_security_plus_yearly",
        transactionId: String = "tx-1",
        originalTransactionId: String = "orig-1",
        jws: String = "jws.tx-1",
    ) = AccountEntitlementEvidence(
        isActive = true,
        productId = productId,
        transactionId = transactionId,
        originalTransactionId = originalTransactionId,
        signedTransactionJws = jws,
    )

    private fun writeOperation(decision: AccountEntitlementMirrorDecision): AccountEntitlementMirrorOperation {
        assertTrue("expected Write, was $decision", decision is AccountEntitlementMirrorDecision.Write)
        return (decision as AccountEntitlementMirrorDecision.Write).operation
    }

    private fun refusal(decision: AccountEntitlementMirrorDecision): AccountEntitlementMirrorRefusal {
        assertTrue("expected Refused, was $decision", decision is AccountEntitlementMirrorDecision.Refused)
        return (decision as AccountEntitlementMirrorDecision.Refused).reason
    }

    @Test fun `binds the receipt identity to the account that is signed in`() {
        val evidence = activeEvidence()
        val decision = AccountEntitlementMirrorPolicy.decide(
            boundAccountId = "user-A",
            evidence = evidence,
            currentAccountId = "user-A",
            mirrored = null,
        )
        val operation = writeOperation(decision)
        assertEquals("user-A", operation.accountId)
        assertEquals("orig-1:tx-1:lava_security_plus_yearly", operation.receipt.identity)
        assertEquals(evidence.receipt, operation.receipt)
    }

    @Test fun `an inactive or incomplete read has no mirrorable receipt`() {
        assertNull(activeEvidence().copy(isActive = false).receipt)
        assertNull(activeEvidence(jws = "").receipt)
        assertNull(activeEvidence().copy(productId = null).receipt)
        assertNull(activeEvidence().copy(transactionId = null).receipt)
        assertNull(activeEvidence().copy(originalTransactionId = null).receipt)

        val inactive = AccountEntitlementMirrorPolicy.decide("user-A", activeEvidence().copy(isActive = false), "user-A", null)
        assertEquals(AccountEntitlementMirrorRefusal.NO_ACTIVE_RECEIPT, refusal(inactive))
        val unsigned = AccountEntitlementMirrorPolicy.decide("user-A", activeEvidence(jws = ""), "user-A", null)
        assertEquals(AccountEntitlementMirrorRefusal.NO_ACTIVE_RECEIPT, refusal(unsigned))
    }

    @Test fun `no signed-in account cannot begin a mirror`() {
        val decision = AccountEntitlementMirrorPolicy.decide(
            boundAccountId = null,
            evidence = activeEvidence(),
            currentAccountId = "user-A",
            mirrored = null,
        )
        assertEquals(AccountEntitlementMirrorRefusal.NO_ACCOUNT, refusal(decision))
    }

    @Test fun `sign-out refuses a pending retry`() {
        val decision = AccountEntitlementMirrorPolicy.decide(
            boundAccountId = "user-A",
            evidence = activeEvidence(),
            currentAccountId = null,
            mirrored = null,
        )
        assertEquals(AccountEntitlementMirrorRefusal.SIGNED_OUT, refusal(decision))
        val blank = AccountEntitlementMirrorPolicy.decide("user-A", activeEvidence(), "", null)
        assertEquals(AccountEntitlementMirrorRefusal.SIGNED_OUT, refusal(blank))
    }

    @Test fun `an account switch refuses to write the first account's receipt`() {
        val decision = AccountEntitlementMirrorPolicy.decide(
            boundAccountId = "user-A",
            evidence = activeEvidence(),
            currentAccountId = "user-B",
            mirrored = null,
        )
        assertEquals(AccountEntitlementMirrorRefusal.ACCOUNT_CHANGED, refusal(decision))
    }

    @Test fun `retrying the same receipt for the same account is idempotent`() {
        val first = writeOperation(
            AccountEntitlementMirrorPolicy.decide("user-A", activeEvidence(), "user-A", null),
        )
        val record = AccountEntitlementMirrorPolicy.record(first)
        val retry = AccountEntitlementMirrorPolicy.decide("user-A", activeEvidence(), "user-A", record)
        assertTrue("expected AlreadyMirrored, was $retry", retry is AccountEntitlementMirrorDecision.AlreadyMirrored)
        assertEquals(first, (retry as AccountEntitlementMirrorDecision.AlreadyMirrored).operation)
    }

    @Test fun `a refreshed JWS for the same transaction stays the same receipt`() {
        val first = writeOperation(
            AccountEntitlementMirrorPolicy.decide("user-A", activeEvidence(jws = "jws.old"), "user-A", null),
        )
        val record = AccountEntitlementMirrorPolicy.record(first)
        val retry = AccountEntitlementMirrorPolicy.decide(
            "user-A",
            activeEvidence(jws = "jws.new"),
            "user-A",
            record,
        )
        assertTrue(retry is AccountEntitlementMirrorDecision.AlreadyMirrored)
    }

    @Test fun `another account's mirrored record does not satisfy idempotency`() {
        val foreignRecord = AccountEntitlementMirrorRecord("user-B", activeEvidence().receipt!!.identity)
        val decision = AccountEntitlementMirrorPolicy.decide("user-A", activeEvidence(), "user-A", foreignRecord)
        assertTrue(decision is AccountEntitlementMirrorDecision.Write)
    }

    @Test fun `a renewal re-mirrors because it is a new receipt`() {
        val first = writeOperation(
            AccountEntitlementMirrorPolicy.decide("user-A", activeEvidence(transactionId = "tx-1"), "user-A", null),
        )
        val record = AccountEntitlementMirrorPolicy.record(first)
        val renewal = AccountEntitlementMirrorPolicy.decide(
            "user-A",
            activeEvidence(transactionId = "tx-2"),
            "user-A",
            record,
        )
        assertTrue("expected Write for a renewed receipt, was $renewal", renewal is AccountEntitlementMirrorDecision.Write)
    }

    @Test fun `switching away and back still recognises the mirrored receipt`() {
        val first = writeOperation(
            AccountEntitlementMirrorPolicy.decide("user-A", activeEvidence(), "user-A", null),
        )
        val record = AccountEntitlementMirrorPolicy.record(first)
        val backAgain = AccountEntitlementMirrorPolicy.decide("user-A", activeEvidence(), "user-A", record)
        assertTrue(backAgain is AccountEntitlementMirrorDecision.AlreadyMirrored)
    }

    @Test fun `evidence takes precedence over account state`() {
        val decision = AccountEntitlementMirrorPolicy.decide(
            boundAccountId = "user-A",
            evidence = activeEvidence().copy(isActive = false),
            currentAccountId = "user-B",
            mirrored = null,
        )
        assertEquals(AccountEntitlementMirrorRefusal.NO_ACTIVE_RECEIPT, refusal(decision))
    }
}
