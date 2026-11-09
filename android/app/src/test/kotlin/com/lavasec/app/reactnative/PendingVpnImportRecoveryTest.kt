package com.lavasec.app.reactnative

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM coverage of the durable pending-first-VPN-import recovery decision. */
class PendingVpnImportRecoveryTest {

    private val pending = PendingVpnImport(
        accountID = "account-A",
        editorId = "editor-1",
        importedGeneration = 1L,
    )

    @Test
    fun `an unchanged committed generation removes the uncommitted import`() {
        assertEquals(
            PendingVpnImportRecovery.REMOVE_IMPORTED,
            pendingVpnImportRecovery(pending, "account-A", committedGeneration = 1L),
        )
    }

    @Test
    fun `a later committed generation drops the marker`() {
        assertEquals(
            PendingVpnImportRecovery.CLEAR_MARKER,
            pendingVpnImportRecovery(pending, "account-A", committedGeneration = 2L),
        )
    }

    @Test
    fun `a missing committed rotation drops the stale marker`() {
        assertEquals(
            PendingVpnImportRecovery.CLEAR_MARKER,
            pendingVpnImportRecovery(pending, "account-A", committedGeneration = null),
        )
    }

    @Test
    fun `a marker from another account is retained`() {
        assertEquals(
            PendingVpnImportRecovery.RETAIN,
            pendingVpnImportRecovery(pending, "account-B", committedGeneration = 1L),
        )
    }
}
