package com.lavasec.app.foundation.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the durable deletion-record contract: canonical JSON round-trips, the version support
 * matrix rejects unsupported/legacy-incompatible records, and a confirmation receipt binds to the
 * exact preparation.
 */
class BackupDeletionIntentCodecTest {

    @Test
    fun `every supported phase round-trips through the canonical record`() {
        val records = listOf(
            BackupDeletionIntent.create("A"),
            BackupDeletionIntent.create("A", BackupDeletionPhase.LOCAL_CLEANUP_PENDING),
            BackupDeletionIntent.create("A", BackupDeletionPhase.DISABLED),
            BackupDeletionIntent.create("A", BackupDeletionPhase.REMOTE_PENDING, retainsEnvelopeForRecovery = true),
            BackupDeletionIntent.create(
                "A",
                BackupDeletionPhase.LOCAL_CLEANUP_PENDING,
                retainsEnvelopeForRecovery = true,
            ),
        )

        for (record in records) {
            val decoded = BackupDeletionIntentCodec.decode(BackupDeletionIntentCodec.encode(record))
            assertEquals(record, decoded)
        }
    }

    @Test
    fun `legacy version three preparation without an operation id decodes`() {
        val decoded = BackupDeletionIntentCodec.decode(
            """{"version":3,"accountID":"A","phase":"remotePending","retainsEnvelopeForRecovery":true}""",
        )

        assertEquals(3, decoded.version)
        assertTrue(decoded.retainsEnvelopeForRecovery == true)
        assertNull(decoded.operationID)
    }

    @Test
    fun `legacy version one record without retained material decodes`() {
        val decoded = BackupDeletionIntentCodec.decode(
            """{"version":1,"accountID":"A","phase":"localCleanupPending"}""",
        )

        assertEquals(1, decoded.version)
        assertNull(decoded.retainsEnvelopeForRecovery)
    }

    @Test
    fun `the version support matrix rejects every unsupported combination`() {
        val unsupported = listOf(
            // Version 2 is retained-envelope cleanup; it may not still be remote-pending.
            """{"version":2,"accountID":"A","phase":"remotePending","retainsEnvelopeForRecovery":true}""",
            // Version 2 must retain envelope material.
            """{"version":2,"accountID":"A","phase":"localCleanupPending"}""",
            // Version 1 may not retain envelope material.
            """{"version":1,"accountID":"A","phase":"localCleanupPending","retainsEnvelopeForRecovery":true}""",
            // Unknown version.
            """{"version":9,"accountID":"A","phase":"disabled"}""",
        )

        for (record in unsupported) {
            assertThrows(BackupDeletionIntentException::class.java) {
                BackupDeletionIntentCodec.decode(record)
            }
        }
    }

    @Test
    fun `a blank or padded account id is corrupt, not absent`() {
        val malformed = listOf(
            """{"version":1,"accountID":"","phase":"disabled"}""",
            """{"version":1,"accountID":" A","phase":"disabled"}""",
            """{"version":1,"accountID":"A ","phase":"disabled"}""",
        )

        for (record in malformed) {
            assertThrows(BackupDeletionIntentException::class.java) {
                BackupDeletionIntentCodec.decode(record)
            }
        }
    }

    @Test
    fun `missing required fields and unknown phases are rejected`() {
        val malformed = listOf(
            """{"accountID":"A","phase":"disabled"}""",
            """{"version":1,"phase":"disabled"}""",
            """{"version":1,"accountID":"A"}""",
            """{"version":1,"accountID":"A","phase":"unknownPhase"}""",
            """{"version":1,"accountID":"A","phase":"disabled","version":2}""",
            """not json""",
        )

        for (record in malformed) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupDeletionIntentCodec.decode(record)
            }
        }
    }

    @Test
    fun `a confirmation advances only the exact same-operation preparation`() {
        val preparation = BackupDeletionIntent.create(
            "account-A",
            BackupDeletionPhase.REMOTE_PENDING,
            retainsEnvelopeForRecovery = true,
            operationID = "operation-1",
        )
        val confirmation = preparation
            .withPhase(BackupDeletionPhase.LOCAL_CLEANUP_PENDING)

        assertTrue(confirmation.confirmsLocalCleanup(preparation = preparation))
        assertFalse(preparation.confirmsLocalCleanup(preparation = confirmation))

        val otherAccount = BackupDeletionIntent.create(
            "account-B",
            BackupDeletionPhase.REMOTE_PENDING,
            retainsEnvelopeForRecovery = true,
            operationID = "operation-1",
        )
        assertFalse(confirmation.confirmsLocalCleanup(preparation = otherAccount))

        // A different operation receipt for the same account must not confirm.
        val otherOperation = BackupDeletionIntent.create(
            "account-A",
            BackupDeletionPhase.REMOTE_PENDING,
            retainsEnvelopeForRecovery = true,
            operationID = "operation-2",
        )
        assertFalse(confirmation.confirmsLocalCleanup(preparation = otherOperation))
    }

    @Test
    fun `canDeleteRemote is bound to the initiating account and the remote phase`() {
        val pending = BackupDeletionIntent.create("account-A", BackupDeletionPhase.REMOTE_PENDING)

        assertTrue(pending.canDeleteRemote("account-A"))
        assertFalse(pending.canDeleteRemote("account-B"))
        assertFalse(pending.canDeleteRemote(null))
        assertFalse(pending.withPhase(BackupDeletionPhase.DISABLED).canDeleteRemote("account-A"))
    }
}
