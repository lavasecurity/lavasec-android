package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverEndpointBackoffPolicyTest {
    @Test
    fun `device addresses and Mullvad have independent backoff keys`() {
        val clock = FakeClock(10L)
        val policy = ResolverEndpointBackoffPolicy(clock)
        val firstDevice = ResolverEndpointKey.DeviceAddress(address("192.0.2.1"))
        val secondDevice = ResolverEndpointKey.DeviceAddress(address("192.0.2.2"))

        policy.recordFailure(firstDevice, ResolverFailureReason.TIMEOUT)

        assertTrue(policy.isBackedOff(firstDevice))
        assertFalse(policy.isBackedOff(secondDevice))
        assertFalse(policy.isBackedOff(ResolverEndpointKey.MullvadDoh))

        policy.recordFailure(ResolverEndpointKey.MullvadDoh, ResolverFailureReason.TLS_FAILED)

        assertTrue(policy.isBackedOff(firstDevice))
        assertFalse(policy.isBackedOff(secondDevice))
        assertTrue(policy.isBackedOff(ResolverEndpointKey.MullvadDoh))
    }

    @Test
    fun `fixed interval suppresses through 29_999 milliseconds and expires at 30 seconds`() {
        val clock = FakeClock(4_000L)
        val policy = ResolverEndpointBackoffPolicy(clock)
        val endpoint = ResolverEndpointKey.DeviceAddress(address("192.0.2.3"))

        policy.recordFailure(endpoint, ResolverFailureReason.RECEIVE_FAILED)
        clock.advance(29_999_000_000L)

        assertTrue(policy.isBackedOff(endpoint))

        clock.advance(1_000_000L)

        assertFalse(policy.isBackedOff(endpoint))
    }

    @Test
    fun `expiry comparison remains correct across signed monotonic wrap`() {
        val clock = FakeClock(Long.MAX_VALUE - 10_000_000_000L)
        val policy = ResolverEndpointBackoffPolicy(clock)
        val endpoint = ResolverEndpointKey.DeviceAddress(address("2001:db8::1"))

        policy.recordFailure(endpoint, ResolverFailureReason.SOCKET_FAILED)
        clock.advance(29_999_000_000L)

        assertTrue(policy.isBackedOff(endpoint))

        clock.advance(1_000_000L)

        assertFalse(policy.isBackedOff(endpoint))
    }

    @Test
    fun `success clears only the observed endpoint`() {
        val policy = ResolverEndpointBackoffPolicy(FakeClock(0L))
        val device = ResolverEndpointKey.DeviceAddress(address("192.0.2.4"))
        val mullvad = ResolverEndpointKey.MullvadDoh
        policy.recordFailure(device, ResolverFailureReason.SEND_FAILED)
        policy.recordFailure(mullvad, ResolverFailureReason.HTTP_STATUS)

        policy.recordSuccess(device)

        assertFalse(policy.isBackedOff(device))
        assertTrue(policy.isBackedOff(mullvad))
    }

    @Test
    fun `retry worthy failure replaces expiry from its latest observation`() {
        val clock = FakeClock(0L)
        val policy = ResolverEndpointBackoffPolicy(clock)
        val endpoint = ResolverEndpointKey.DeviceAddress(address("192.0.2.5"))
        policy.recordFailure(endpoint, ResolverFailureReason.TIMEOUT)
        clock.advance(29_000_000_000L)

        policy.recordFailure(endpoint, ResolverFailureReason.INVALID_RESPONSE)
        clock.advance(1_000_000_000L)

        assertTrue(policy.isBackedOff(endpoint))

        clock.advance(29_000_000_000L)

        assertFalse(policy.isBackedOff(endpoint))
    }

    @Test
    fun `local and lifecycle outcomes never create or extend backoff`() {
        listOf(
            ResolverFailureReason.BACKED_OFF,
            ResolverFailureReason.CANCELLED,
            ResolverFailureReason.STALE_EPOCH,
            ResolverFailureReason.OVERLOADED,
        ).forEachIndexed { index, reason ->
            val clock = FakeClock(index.toLong())
            val policy = ResolverEndpointBackoffPolicy(clock)
            val endpoint = ResolverEndpointKey.DeviceAddress(address("192.0.2.6"))

            policy.recordFailure(endpoint, reason)
            assertFalse(reason.name, policy.isBackedOff(endpoint))

            policy.recordFailure(endpoint, ResolverFailureReason.TIMEOUT)
            clock.advance(29_999_000_000L)
            policy.recordFailure(endpoint, reason)
            clock.advance(1_000_000L)

            assertFalse(reason.name, policy.isBackedOff(endpoint))
        }
    }

    @Test
    fun `retirement makes later reads and mutations inert`() {
        val clock = FakeClock(0L)
        val policy = ResolverEndpointBackoffPolicy(clock)
        val endpoint = ResolverEndpointKey.DeviceAddress(address("192.0.2.7"))
        policy.recordFailure(endpoint, ResolverFailureReason.TIMEOUT)
        policy.retire()
        val readsAtRetirement = clock.readCount

        assertFalse(policy.isBackedOff(endpoint))
        policy.recordFailure(endpoint, ResolverFailureReason.TIMEOUT)
        policy.recordSuccess(endpoint)
        policy.reset()

        assertFalse(policy.isBackedOff(endpoint))
        assertTrue(clock.readCount == readsAtRetirement)
    }

    @Test
    fun `reset clears every endpoint`() {
        val policy = ResolverEndpointBackoffPolicy(FakeClock(0L))
        val device = ResolverEndpointKey.DeviceAddress(address("192.0.2.8"))
        policy.recordFailure(device, ResolverFailureReason.PROTECT_FAILED)
        policy.recordFailure(ResolverEndpointKey.MullvadDoh, ResolverFailureReason.TLS_FAILED)

        policy.reset()

        assertFalse(policy.isBackedOff(device))
        assertFalse(policy.isBackedOff(ResolverEndpointKey.MullvadDoh))
    }

    @Test
    fun `concurrent reads and records are deterministic`() {
        val policy = ResolverEndpointBackoffPolicy(FakeClock(8L))
        val endpoints = (1..32).map {
            ResolverEndpointKey.DeviceAddress(address("198.51.100.$it"))
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        try {
            val recordFutures = endpoints.map { endpoint ->
                executor.submit {
                    start.await()
                    repeat(100) {
                        policy.recordFailure(endpoint, ResolverFailureReason.TIMEOUT)
                        assertTrue(policy.isBackedOff(endpoint))
                    }
                }
            }
            start.countDown()
            recordFutures.forEach { it.get(10, TimeUnit.SECONDS) }
            endpoints.forEach { assertTrue(policy.isBackedOff(it)) }

            val clearFutures = endpoints.map { endpoint ->
                executor.submit {
                    repeat(100) {
                        policy.recordSuccess(endpoint)
                    }
                }
            }
            clearFutures.forEach { it.get(10, TimeUnit.SECONDS) }
            endpoints.forEach { assertFalse(policy.isBackedOff(it)) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun address(value: String): InetAddress = InetAddress.getByName(value)

    private class FakeClock(initialNanos: Long) : MonotonicClock {
        private val now = AtomicLong(initialNanos)
        val readCount: Long
            get() = reads.get()
        private val reads = AtomicLong(0L)

        override fun nowNanos(): Long {
            reads.incrementAndGet()
            return now.get()
        }

        fun advance(nanos: Long) {
            now.addAndGet(nanos)
        }
    }
}
