package com.lavasec.app.foundation.chained

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ChainedDestinationTableTest {

    private fun address(text: String): Long =
        InetAddress.getByName(text).address.fold(0L) { acc, byte ->
            (acc shl 8) or (byte.toLong() and 0xFF)
        }

    @Test
    fun `a send opens a window and counts the demand`() {
        val table = ChainedDestinationTable()
        val host = address("100.64.0.10")

        table.recordSend(host, nowSeconds = 5)
        val first = requireNotNull(table.observationFor(host))
        assertEquals(1L, first.sentPacketCount)
        assertEquals(0L, first.receivedPacketCount)
        assertEquals(5, first.firstUnansweredSendAtSeconds)
        assertEquals(5, first.lastSendAtSeconds)
        assertEquals(1L, first.sendsInCurrentWindow)
        assertEquals(1, table.trackedCount)

        table.recordSend(host, nowSeconds = 6)
        val second = requireNotNull(table.observationFor(host))
        assertEquals(2L, second.sentPacketCount)
        // The window was not re-anchored: it is only 1 second old.
        assertEquals(5, second.firstUnansweredSendAtSeconds)
        assertEquals(2L, second.sendsInCurrentWindow)
    }

    @Test
    fun `an untracked destination has no observation`() {
        val table = ChainedDestinationTable()
        assertNull(table.observationFor(address("10.0.0.1")))
        assertEquals(0, table.trackedCount)
    }

    @Test
    fun `a receipt closes the window`() {
        val table = ChainedDestinationTable()
        val host = address("100.64.0.10")
        table.recordSend(host, nowSeconds = 1)
        table.recordSend(host, nowSeconds = 2)

        table.recordReceipt(host, nowSeconds = 3)
        val after = requireNotNull(table.observationFor(host))
        assertEquals(1L, after.receivedPacketCount)
        assertNull(after.firstUnansweredSendAtSeconds)
        assertEquals(0L, after.sendsInCurrentWindow)
        assertEquals(2, after.lastSendAtSeconds)
    }

    @Test
    fun `a receipt from an unknown source does not claim a slot`() {
        val table = ChainedDestinationTable()

        table.recordReceipt(address("203.0.113.9"), nowSeconds = 1)

        assertEquals(0, table.trackedCount)
        assertNull(table.observationFor(address("203.0.113.9")))
    }

    @Test
    fun `a send after a lapse re-anchors instead of inheriting the old question`() {
        val table = ChainedDestinationTable()
        val host = address("100.64.0.10")

        table.recordSend(host, nowSeconds = 0)
        // 100 seconds later the user asked again; the old window never qualified (one send), so the
        // second question must not inherit the age of the first.
        table.recordSend(host, nowSeconds = 100)

        val after = requireNotNull(table.observationFor(host))
        assertEquals(100, after.firstUnansweredSendAtSeconds)
        assertEquals(1L, after.sendsInCurrentWindow)
        assertEquals(100, after.lastSendAtSeconds)
    }

    @Test
    fun `a qualified window is neither lapsed nor re-anchored`() {
        val table = ChainedDestinationTable()
        val host = address("100.64.0.10")

        // Sustained demand through a stalled connect's backoff: 0, 1, 3, 7, 15, then a long gap.
        for (at in listOf(0, 1, 3, 7, 15)) {
            table.recordSend(host, nowSeconds = at)
        }
        val qualified = requireNotNull(table.observationFor(host))
        assertEquals(0, qualified.firstUnansweredSendAtSeconds)
        assertEquals(5L, qualified.sendsInCurrentWindow)

        // Past the threshold with the floor satisfied, a backoff-length gap is the failure, not a
        // lapse in asking.
        table.recordSend(host, nowSeconds = 40)
        val after = requireNotNull(table.observationFor(host))
        assertEquals(0, after.firstUnansweredSendAtSeconds)
        assertEquals(6L, after.sendsInCurrentWindow)
    }

    @Test
    fun `the destination being sent to survives a full table of chatter`() {
        val table = ChainedDestinationTable()
        // One free slot, filled by 31 other destinations each touched once.
        val chatter = (1..31).map { address("100.64.1.$it") }
        chatter.forEachIndexed { index, host ->
            table.recordSend(host, nowSeconds = index)
        }
        assertEquals(ChainedDestinationTable.CAPACITY - 1, table.trackedCount)

        val wanted = address("100.64.9.9")
        table.recordSend(wanted, nowSeconds = 100)
        table.recordSend(wanted, nowSeconds = 101)

        // A further destination must evict the least recently active chatter, never the host the
        // user is actively sending to (which is by definition the youngest by activity).
        table.recordSend(address("100.64.7.7"), nowSeconds = 102)

        assertEquals(ChainedDestinationTable.CAPACITY, table.trackedCount)
        assertNotNull(table.observationFor(wanted))
        assertNull(table.observationFor(chatter.first()))
    }

    @Test
    fun `destinations lists every tracked row`() {
        val table = ChainedDestinationTable()
        table.recordSend(address("100.64.0.1"), nowSeconds = 0)
        table.recordSend(address("100.64.0.2"), nowSeconds = 1)

        val rows = table.destinations()
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.first == address("100.64.0.1") })
        assertTrue(rows.any { it.first == address("100.64.0.2") })
    }

    // MARK: - Reachability policy

    @Test
    fun `the verdict reports sustained unanswered demand`() {
        val neverSent = ChainedDestinationReachabilityPolicy.verdict(
            ChainedDestinationObservation(0, 0, null, null, 0),
            nowSeconds = 50,
        )
        assertEquals(ChainedDestinationReachability.Idle, neverSent)

        val closed = ChainedDestinationReachabilityPolicy.verdict(
            ChainedDestinationObservation(3, 1, null, 10, 0),
            nowSeconds = 50,
        )
        assertEquals(ChainedDestinationReachability.Answering, closed)

        val awaiting = ChainedDestinationReachabilityPolicy.verdict(
            ChainedDestinationObservation(3, 0, 45, 45, 3),
            nowSeconds = 50,
        )
        assertEquals(ChainedDestinationReachability.Awaiting(5), awaiting)

        val unanswered = ChainedDestinationReachabilityPolicy.verdict(
            ChainedDestinationObservation(6, 0, 0, 40, 6),
            nowSeconds = 50,
        )
        assertEquals(ChainedDestinationReachability.Unanswered(50), unanswered)
    }

    @Test
    fun `a sustained failure stays reportable through backoff gaps`() {
        val qualified = ChainedDestinationObservation(
            sentPacketCount = 6,
            receivedPacketCount = 0,
            firstUnansweredSendAtSeconds = 0,
            lastSendAtSeconds = 40,
            sendsInCurrentWindow = 6,
        )
        // The last send is 20s ago (a backoff-length gap) but the window qualified, so it is still
        // reported rather than read as idle.
        assertEquals(
            ChainedDestinationReachability.Unanswered(60),
            ChainedDestinationReachabilityPolicy.verdict(qualified, nowSeconds = 60),
        )
    }

    @Test
    fun `a stray packet followed by silence is idle, not a complaint`() {
        val stray = ChainedDestinationObservation(
            sentPacketCount = 1,
            receivedPacketCount = 0,
            firstUnansweredSendAtSeconds = 0,
            lastSendAtSeconds = 0,
            sendsInCurrentWindow = 1,
        )
        assertEquals(
            ChainedDestinationReachability.Idle,
            ChainedDestinationReachabilityPolicy.verdict(stray, nowSeconds = 60),
        )
    }
}
