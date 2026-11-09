package com.lavasec.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lavasec.app.foundation.activity.ActivityDateRangePolicy
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native SQLite must accept the first WITHOUT ROWID receipt and reject replays after reopen. */
@RunWith(AndroidJUnit4::class)
class ActivityReceiptInstrumentationTest {
    @Test
    fun firstTelemetryReceiptCommitsAndReplayRemainsIdempotentAcrossReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "receipt-${UUID.randomUUID()}.sqlite"
        val event = DnsDecisionEvent(
            id = 1L,
            timestampMillis = System.currentTimeMillis(),
            normalizedDomain = "receipt.invalid",
            decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
        )
        val range = ActivityDateRangePolicy.today(LocalDate.now())
        try {
            ActivityDatabase(context, name).use { db ->
                val repository = ActivityRepository(db)
                val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
                assertTrue(writer.record(event))
                assertEquals(1L, repository.summary(range).blockedCount)
                assertEquals(1, repository.history().size)
            }
            ActivityDatabase(context, name).use { db ->
                val repository = ActivityRepository(db)
                val writer = CredentialActivityWriter(repository, ActivityRevisionBus())
                val before = repository.revisions()
                assertFalse(writer.record(event))
                assertThrows(IllegalStateException::class.java) {
                    writer.record(event.copy(normalizedDomain = "collision.invalid"))
                }
                assertEquals(before, repository.revisions())
                assertEquals(1L, repository.summary(range).blockedCount)
                assertEquals(1, repository.history().size)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
