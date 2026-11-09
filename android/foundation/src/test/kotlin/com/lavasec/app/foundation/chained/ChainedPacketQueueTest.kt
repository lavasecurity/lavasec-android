package com.lavasec.app.foundation.chained

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounded FIFO queue for the chained data path. The failure being prevented is not packet loss —
 * it is the tunnel process being killed for exceeding its memory ceiling. An unbounded queue
 * absorbing a fast downlink grows without limit, so these tests are about the queue staying
 * bounded while it is being pushed, and about bounding it in BYTES rather than only in packets.
 */
class ChainedPacketQueueTest {

    private val mtu = 1280

    private fun makeQueue(
        packets: Int = ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH,
        bytes: Int = ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH * mtu,
    ): ChainedPacketQueue =
        ChainedPacketQueue(ChainedPacketQueueLimits(packets, bytes))

    private fun packet(size: Int, fill: Int = 0xAB): ByteArray = ByteArray(size) { fill.toByte() }

    // MARK: - The bound is real under sustained pressure

    @Test
    fun `the queue sheds under sustained overload rather than growing`() {
        val queue = makeQueue()
        var peakPackets = 0
        var peakBytes = 0

        repeat(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH * 20) {
            queue.enqueue(packet(mtu))
            peakPackets = maxOf(peakPackets, queue.count)
            peakBytes = maxOf(peakBytes, queue.byteCount)
            assertTrue(queue.count <= queue.limits.maximumPackets)
            assertTrue(queue.byteCount <= queue.limits.maximumBytes)
        }

        assertEquals(queue.limits.maximumPackets, peakPackets)
        assertTrue(peakBytes > 0)

        // Exact, not `> 0`: the shed counters are what the tunnel reports instead of inferring
        // congestion from a throughput dip, so an arithmetic error in them is a diagnostic that
        // lies. The expectation is computed from the offered load, not read back from the queue.
        val offered = ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH * 20
        assertEquals((offered - queue.count).toLong(), queue.shedPacketCount)
        assertEquals(((offered - queue.count) * mtu).toLong(), queue.shedByteCount)
    }

    @Test
    fun `the byte bound still holds for large but engine-legal packets`() {
        val queue = makeQueue(packets = 1000, bytes = 4 * 1500)
        repeat(500) {
            queue.enqueue(packet(1500))
            assertTrue(queue.byteCount <= 4 * 1500)
        }
        assertTrue(queue.byteCount <= 4 * 1500)
        assertEquals((500 - queue.count).toLong(), queue.shedPacketCount)
        assertEquals(((500 - queue.count) * 1500).toLong(), queue.shedByteCount)
    }

    @Test
    fun `a full queue does not admit one more than its limit`() {
        val queue = makeQueue(packets = 3, bytes = 3 * mtu)
        repeat(10) {
            queue.enqueue(packet(mtu))
            assertTrue(queue.count <= 3)
        }
        assertEquals(3, queue.count)
    }

    // MARK: - Which packet is dropped, and why it differs from the engine

    @Test
    fun `the oldest is evicted so queueing delay stays bounded`() {
        val queue = makeQueue(packets = 3, bytes = 3 * mtu)
        for (marker in 1..3) {
            queue.enqueue(packet(mtu, fill = marker))
        }
        val admission = queue.enqueue(packet(mtu, fill = 4))
        assertEquals(ChainedQueueAdmission.AdmittedAfterEvicting(1, mtu), admission)

        // 1 is gone; 2, 3, 4 remain in order.
        assertEquals(2, queue.dequeue()!!.first().toInt())
        assertEquals(3, queue.dequeue()!!.first().toInt())
        assertEquals(4, queue.dequeue()!!.first().toInt())
        assertNull(queue.dequeue())
    }

    @Test
    fun `eviction removes as many packets as the arrival needs`() {
        val queue = makeQueue(packets = 100, bytes = 1000)
        repeat(10) { queue.enqueue(packet(100)) }
        assertEquals(1000, queue.byteCount)

        val admission = queue.enqueue(packet(450))
        assertEquals(ChainedQueueAdmission.AdmittedAfterEvicting(5, 500), admission)
        assertTrue(queue.byteCount <= 1000)
        assertEquals(950, queue.byteCount)
    }

    // MARK: - Refusals

    @Test
    fun `a packet larger than the whole budget is refused without emptying the queue`() {
        val queue = makeQueue(packets = 10, bytes = 1000)
        repeat(5) { queue.enqueue(packet(100)) }
        val before = queue.count

        // 1200 is engine-legal (<=1500) but exceeds this queue's 1000-byte budget, so the
        // BUDGET refusal fires rather than the per-packet engine cap.
        val admission = queue.enqueue(packet(1200))
        assertEquals(ChainedQueueAdmission.RefusedOversized(1200, 1000), admission)
        assertEquals(before, queue.count)
        assertEquals(500, queue.byteCount)
    }

    @Test
    fun `empty packets are refused so they cannot consume the budget`() {
        val queue = makeQueue(packets = 4, bytes = 4 * mtu)
        assertEquals(ChainedQueueAdmission.RefusedEmpty, queue.enqueue(ByteArray(0)))
        assertTrue(queue.isEmpty)
        assertEquals(1L, queue.shedPacketCount)
    }

    @Test
    fun `a zero capacity queue refuses everything rather than throwing`() {
        val queue = makeQueue(packets = 0, bytes = 0)
        assertFalse(queue.enqueue(packet(10)).wasQueued)
        assertTrue(queue.isEmpty)
        assertNull(queue.dequeue())
    }

    @Test
    fun `no packet capacity is not reported as oversized`() {
        val queue = makeQueue(packets = 0, bytes = 1000)
        val admission = queue.enqueue(packet(10))
        assertEquals(ChainedQueueAdmission.RefusedNoCapacity, admission)
        assertEquals("refused-no-capacity", admission.logValue)
        assertFalse(admission == ChainedQueueAdmission.RefusedOversized(10, 1000))
    }

    @Test
    fun `a packet above the queue byte budget is refused as oversized`() {
        val queue = makeQueue(packets = 10, bytes = 1000)
        assertEquals(
            ChainedQueueAdmission.RefusedOversized(1200, 1000),
            queue.enqueue(packet(1200)),
        )
    }

    @Test
    fun `a packet above the engine IP ceiling is refused with the engine limit`() {
        val queue = makeQueue(packets = 10, bytes = 10_000)
        assertEquals(
            ChainedQueueAdmission.RefusedOversized(
                2000,
                ChainedPacketQueueLimits.MAXIMUM_IP_PACKET_BYTE_COUNT,
            ),
            queue.enqueue(packet(2000)),
        )
    }

    // MARK: - The bound is on memory held, not on bytes counted

    @Test
    fun `an absurd MTU yields a finite budget rather than none or a throw`() {
        for (value in listOf(Int.MAX_VALUE, Int.MAX_VALUE / 2, 1_000_000, 70_000)) {
            val limits = ChainedPacketQueueLimits.forChainedTunnel(value)
            requireNotNull(limits)
            assertTrue("mtu $value", limits.maximumBytes > 0)
            assertTrue(
                "mtu $value produced a budget that is not a real ceiling",
                limits.maximumBytes <=
                    ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH *
                    ChainedPacketQueueLimits.MAXIMUM_PLAUSIBLE_MTU,
            )
        }

        // And the bound still binds: a queue built from an absurd MTU sheds rather than growing.
        val queue = ChainedPacketQueue(requireNotNull(ChainedPacketQueueLimits.forChainedTunnel(Int.MAX_VALUE)))
        repeat(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH * 3) {
            queue.enqueue(packet(1500))
            assertTrue(queue.byteCount <= queue.limits.maximumBytes)
        }
        assertEquals(
            (ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH * 3 - queue.count).toLong(),
            queue.shedPacketCount,
        )
        // Sized to the engine's inner-IP ceiling, not the IP maximum: 65535 gave ~16 MB per
        // direction and over 32 MB for both, inside a process ceiling of the same order.
        assertTrue(queue.limits.maximumBytes * 2 < 4 * 1024 * 1024)
    }

    @Test
    fun `an absurd packet limit is clamped rather than allocated`() {
        val absurd = ChainedPacketQueueLimits(maximumPackets = 10_000_000, maximumBytes = 1_500)
        assertEquals(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH, absurd.maximumPackets)
        val queue = ChainedPacketQueue(absurd)
        assertEquals(ChainedQueueAdmission.Admitted, queue.enqueue(byteArrayOf(1, 2, 3)))
        assertEquals(1, queue.count)

        assertEquals(
            256,
            ChainedPacketQueueLimits(maximumPackets = 257, maximumBytes = 4096).maximumPackets,
        )
        assertEquals(
            10,
            ChainedPacketQueueLimits(maximumPackets = 10, maximumBytes = 4096).maximumPackets,
        )
    }

    @Test
    fun `negative limits are normalized rather than trusted`() {
        val limits = ChainedPacketQueueLimits(maximumPackets = -5, maximumBytes = -100)
        assertEquals(0, limits.maximumPackets)
        assertEquals(0, limits.maximumBytes)
    }

    // MARK: - Derived limits

    @Test
    fun `chained limits agree with the chained MTU`() {
        val limits = requireNotNull(ChainedPacketQueueLimits.forChainedTunnel(mtu))
        assertEquals(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH, limits.maximumPackets)
        assertEquals(ChainedPacketQueueLimits.ENGINE_QUEUE_DEPTH * mtu, limits.maximumBytes)

        // Both bounds bind together: a full queue of MTU-sized packets hits them at the same
        // moment, so neither is decorative.
        val queue = ChainedPacketQueue(limits)
        repeat(limits.maximumPackets) { queue.enqueue(packet(mtu)) }
        assertEquals(limits.maximumPackets, queue.count)
        assertEquals(limits.maximumBytes, queue.byteCount)
    }

    @Test
    fun `both directions together stay far under the process ceiling`() {
        val perDirection = requireNotNull(ChainedPacketQueueLimits.forChainedTunnel(mtu)).maximumBytes
        assertEquals(327_680, perDirection)
        assertTrue(perDirection * 2 < 2 * 1024 * 1024)
    }

    @Test
    fun `a sub-minimum MTU is refused rather than fabricated`() {
        for (value in listOf(0, -1, Int.MIN_VALUE, 1, 576, ChainedRoutePlan.MINIMUM_IPV6_LINK_MTU - 1)) {
            assertNull("mtu $value", ChainedPacketQueueLimits.forChainedTunnel(value))
        }
        assertTrue(ChainedPacketQueueLimits.forChainedTunnel(ChainedRoutePlan.MINIMUM_IPV6_LINK_MTU) != null)
    }

    // MARK: - Lifecycle

    @Test
    fun `clearing for a new session is not counted as shedding`() {
        // A small queue so the shed counters are non-zero BEFORE the clear: a cumulative counter
        // that is only checked at zero cannot show that removeAll preserves it.
        val queue = makeQueue(packets = 4, bytes = 4 * mtu)
        repeat(10) { queue.enqueue(packet(mtu)) }
        val shedPacketsBefore = queue.shedPacketCount
        val shedBytesBefore = queue.shedByteCount
        assertTrue(shedPacketsBefore > 0)

        queue.removeAll()
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.byteCount)
        assertEquals(shedPacketsBefore, queue.shedPacketCount)
        assertEquals(shedBytesBefore, queue.shedByteCount)
    }

    @Test
    fun `FIFO order is preserved for everything not evicted`() {
        val queue = makeQueue(packets = 100, bytes = 100 * mtu)
        for (marker in 1..50) queue.enqueue(packet(10, fill = marker))
        for (marker in 1..50) {
            assertEquals(marker, queue.dequeue()!!.first().toInt())
        }
        assertNull(queue.dequeue())
    }

    @Test
    fun `byte accounting survives interleaved enqueue and dequeue`() {
        // The byte budget is deliberately tighter than 8 x the largest packet (8 x 80 = 640), so
        // the byte arm of the eviction loop — not only the packet-count arm — is actually
        // exercised here. Sizes are 40..80, so a full queue can exceed 400 bytes and force
        // byte-driven eviction.
        val packetBudget = 8
        val byteBudget = 400
        val queue = makeQueue(packets = packetBudget, bytes = byteBudget)
        val expected = ArrayDeque<Int>()
        for (step in 0 until 200) {
            if (step % 3 == 0 && expected.isNotEmpty()) {
                queue.dequeue()
                expected.removeFirst()
            } else {
                val size = 40 + (step % 5) * 10
                if (queue.enqueue(packet(size)).wasQueued) {
                    expected.addLast(size)
                    while (expected.size > packetBudget || expected.sum() > byteBudget) {
                        expected.removeFirst()
                    }
                }
            }
            assertEquals("step $step", expected.size, queue.count)
            assertEquals("step $step", expected.sum(), queue.byteCount)
        }
    }

    @Test
    fun `log values name the outcome without carrying payload`() {
        assertEquals("queued", ChainedQueueAdmission.Admitted.logValue)
        assertEquals(
            "queued-evicting-3",
            ChainedQueueAdmission.AdmittedAfterEvicting(3, 300).logValue,
        )
        assertEquals(
            "refused-oversized",
            ChainedQueueAdmission.RefusedOversized(9000, 1000).logValue,
        )
        assertEquals("refused-empty", ChainedQueueAdmission.RefusedEmpty.logValue)
    }

    // MARK: - The ring

    @Test
    fun `the ring behaves exactly like the shifting array it replaced`() {
        // The storage is a fixed ring with a head index, because eviction runs on every arrival
        // once the queue is full and removing the head of a list made that O(depth). Refactors
        // like that fail on modular arithmetic at the wrap, so the oracle is the OLD
        // implementation: a plain list with `removeAt(0)`, driven through the same operations,
        // with every observable compared after each step. The sequence is a fixed LCG, so a
        // failure is reproducible from the seed alone.
        // The byte budget is deliberately smaller than 8 x the largest packet (8 x 48 = 384), so
        // the byte arm of the eviction loop fires and its modular-arithmetic wrap is exercised;
        // a budget the packet-count arm alone can never exceed would leave that path untested.
        val limits = ChainedPacketQueueLimits(maximumPackets = 8, maximumBytes = 128)
        val queue = ChainedPacketQueue(limits)
        val model = ArrayDeque<ByteArray>()
        var modelBytes = 0
        var seed = 0x5EEDL

        fun next(bound: Int): Int {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return ((seed ushr 33) % bound.toLong()).toInt()
        }

        for (step in 0 until 4_000) {
            if (next(10) < 7) {
                val size = 1 + next(48)
                val value = ByteArray(size) { (step % 251).toByte() }
                queue.enqueue(value)
                if (size <= limits.maximumBytes) {
                    while (model.isNotEmpty() &&
                        (model.size + 1 > limits.maximumPackets || modelBytes + size > limits.maximumBytes)
                    ) {
                        modelBytes -= model.removeFirst().size
                    }
                    model.addLast(value)
                    modelBytes += size
                }
            } else if (next(20) == 0) {
                queue.removeAll()
                model.clear()
                modelBytes = 0
            } else {
                val dequeued = queue.dequeue()
                val expected = if (model.isEmpty()) null else model.removeFirst()
                assertArrayEquals("step $step", expected, dequeued)
                modelBytes -= expected?.size ?: 0
            }

            assertEquals("step $step", model.size, queue.count)
            assertEquals("step $step", modelBytes, queue.byteCount)
            assertEquals("step $step", model.isEmpty(), queue.isEmpty)
        }

        // Contents agree, not just counts: a ring that kept the right NUMBER in the wrong ORDER
        // fails here.
        while (model.isNotEmpty()) {
            val expected = model.removeFirst()
            val dequeued = queue.dequeue()
            assertArrayEquals(expected, dequeued)
        }
        assertNull(queue.dequeue())
    }

    @Test
    fun `a reused ring slot starts clean rather than inheriting its old packet`() {
        val queue = ChainedPacketQueue(
            ChainedPacketQueueLimits(maximumPackets = 2, maximumBytes = 4096),
        )
        queue.enqueue(byteArrayOf(7, 7, 7, 7))
        queue.enqueue(byteArrayOf(8, 8, 8, 8))
        assertEquals(4, queue.dequeue()!!.size)
        queue.enqueue(byteArrayOf(9))
        assertEquals(8, queue.dequeue()!!.first().toInt())
        assertArrayEquals(byteArrayOf(9), queue.dequeue())
        assertNull(queue.dequeue())

        queue.removeAll()
        assertEquals(0, queue.byteCount)
        queue.enqueue(byteArrayOf(9))
        assertEquals(1, queue.count)
        assertEquals(1, queue.byteCount)
        assertArrayEquals(byteArrayOf(9), queue.dequeue())
    }
}
