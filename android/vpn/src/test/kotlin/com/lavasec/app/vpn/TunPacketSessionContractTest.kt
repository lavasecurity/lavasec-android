package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TunPacketSessionContractTest {
    @Test
    fun `packet reads require a positive byte count`() {
        assertThrows(IllegalArgumentException::class.java) {
            TunRead.Packet(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunRead.Packet(-1)
        }
    }

    @Test
    fun `short writes require a non-negative byte count`() {
        assertThrows(IllegalArgumentException::class.java) {
            TunWriteAttempt.ShortWrite(-1)
        }
    }

    @Test
    fun `runtime capacities require positive values and enough live records`() {
        assertThrows(IllegalArgumentException::class.java) {
            TunRuntimeCapacities(0, 1, 1, 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunRuntimeCapacities(1, 0, 1, 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunRuntimeCapacities(1, 1, 0, 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunRuntimeCapacities(1, 1, 1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunRuntimeCapacities(1, 1, 1, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunRuntimeCapacities(Int.MAX_VALUE, 1, 1, 1)
        }
    }

    @Test
    fun `production capacities are the approved 8 64 64 137 contract`() {
        assertEquals(8, TunRuntimeCapacities.Production.activeResolutions)
        assertEquals(64, TunRuntimeCapacities.Production.waitingResolutions)
        assertEquals(64, TunRuntimeCapacities.Production.materializedWrites)
        assertEquals(137, TunRuntimeCapacities.Production.liveRecords)
    }

    @Test
    fun `terminal evidence owns an immutable health snapshot`() {
        val source = mutableListOf<DnsHealthEvent>(
            DnsHealthEvent.ProtectionUnavailable(
                ProtectionUnavailableReason.RuntimeUnavailable,
            ),
        )
        val evidence = TunTerminalEvidence(1L, null, source, null)
        source.clear()

        assertEquals(1, evidence.healthEvents.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (evidence.healthEvents as MutableList<DnsHealthEvent>).clear()
        }
    }

    @Test
    fun `cancellation remains an exception rather than a failed result`() {
        assertThrows(IllegalArgumentException::class.java) {
            TunRead.Failed(CancellationException("read cancelled"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunWriteAttempt.Failed(CancellationException("write cancelled"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunWritable.Failed(CancellationException("await writable cancelled"))
        }
    }
}
