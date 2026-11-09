package com.lavasec.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainedAvailabilityPolicyTest {

    private fun available(
        totalMemoryBytes: Long = 4_000_000_000L,
        isLowRamDevice: Boolean = false,
        backoff: ChainedMemoryKillBackoffState = ChainedMemoryKillBackoffPolicy.clean,
        apiLevel: Int = 36,
    ): ChainedDeviceEligibilityRead.Available = ChainedDeviceEligibilityRead.Available(
        memory = ChainedDeviceMemoryReading(
            totalMemoryBytes = totalMemoryBytes,
            memoryClassMegabytes = 256,
            isLowRamDevice = isLowRamDevice,
        ),
        backoff = backoff,
        apiLevel = apiLevel,
    )

    private fun refusalOf(
        read: ChainedDeviceEligibilityRead,
        hasEntitlement: Boolean = true,
    ): ChainedAvailabilityRefusal {
        val result = ChainedAvailabilityPolicy.evaluate(hasEntitlement, read)
        assertTrue("expected ineligible but was $result", result is ChainedAvailability.Ineligible)
        return (result as ChainedAvailability.Ineligible).reason
    }

    @Test
    fun `an entitled device above the floor with no exclusion is eligible`() {
        val result = ChainedAvailabilityPolicy.evaluate(
            hasEntitlement = true,
            read = available(),
        )
        assertTrue(result is ChainedAvailability.Eligible)
        assertTrue(result.isEligible)
    }

    @Test
    fun `an unentitled account is refused before any device fact is consulted`() {
        // Even a device that would be unreadable reports the durable account reason, so the user
        // is not told about a device fault they can do nothing about.
        assertEquals(
            ChainedAvailabilityRefusal.NOT_ENTITLED,
            refusalOf(
                read = ChainedDeviceEligibilityRead.Unavailable(
                    ChainedDeviceEligibilityUnavailableReason.MEMORY_UNREADABLE,
                ),
                hasEntitlement = false,
            ),
        )
    }

    @Test
    fun `an unreadable device state fails closed`() {
        assertEquals(
            ChainedAvailabilityRefusal.DEVICE_STATE_UNREADABLE,
            refusalOf(
                ChainedDeviceEligibilityRead.Unavailable(
                    ChainedDeviceEligibilityUnavailableReason.ELIGIBILITY_STATE_UNREADABLE,
                ),
            ),
        )
        assertEquals(
            ChainedAvailabilityRefusal.DEVICE_STATE_UNREADABLE,
            refusalOf(
                ChainedDeviceEligibilityRead.Unavailable(
                    ChainedDeviceEligibilityUnavailableReason.MEMORY_UNREADABLE,
                ),
            ),
        )
    }

    @Test
    fun `one byte below the floor is insufficient memory`() {
        assertEquals(
            ChainedAvailabilityRefusal.INSUFFICIENT_MEMORY,
            refusalOf(available(totalMemoryBytes = ChainedAvailabilityPolicy.MINIMUM_DEVICE_MEMORY_BYTES - 1)),
        )
    }

    @Test
    fun `exactly the floor is eligible`() {
        val result = ChainedAvailabilityPolicy.evaluate(
            hasEntitlement = true,
            read = available(totalMemoryBytes = ChainedAvailabilityPolicy.MINIMUM_DEVICE_MEMORY_BYTES),
        )
        assertTrue(result is ChainedAvailability.Eligible)
    }

    @Test
    fun `a recorded exclusion refuses an otherwise-eligible device`() {
        assertEquals(
            ChainedAvailabilityRefusal.MEMORY_KILL_EXCLUDED,
            refusalOf(
                available(
                    backoff = ChainedMemoryKillBackoffState(
                        killStreak = ChainedMemoryKillBackoffPolicy.KILLS_BEFORE_EXCLUSION,
                        excluded = true,
                        lastKillEpochMillis = 1_750_000_000_000L,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the floor precedes the exclusion safety net`() {
        // A sub-floor, excluded device reports the measured hardware cause, not the durable one.
        assertEquals(
            ChainedAvailabilityRefusal.INSUFFICIENT_MEMORY,
            refusalOf(
                available(
                    totalMemoryBytes = 1_000_000_000L,
                    backoff = ChainedMemoryKillBackoffState(3, excluded = true),
                ),
            ),
        )
    }

    @Test
    fun `a pre-API-30 low-RAM device is conservatively excluded even above the floor`() {
        assertEquals(
            ChainedAvailabilityRefusal.MEMORY_KILL_EXCLUDED,
            refusalOf(available(isLowRamDevice = true, apiLevel = 28)),
        )
    }

    @Test
    fun `a pre-API-30 non-low-RAM device above the floor remains eligible`() {
        val result = ChainedAvailabilityPolicy.evaluate(
            hasEntitlement = true,
            read = available(isLowRamDevice = false, apiLevel = 28),
        )
        assertTrue(result is ChainedAvailability.Eligible)
    }

    @Test
    fun `on API 30 the low-RAM flag no longer refuses without exit evidence`() {
        val result = ChainedAvailabilityPolicy.evaluate(
            hasEntitlement = true,
            read = available(isLowRamDevice = true, apiLevel = 30),
        )
        assertTrue(result is ChainedAvailability.Eligible)
    }

    @Test
    fun `the memory floor sits in the empty band between the 2 GB and 3 GB clusters`() {
        // The band is load-bearing, not decoration: a 2 GB-class device reports under 1.95 GB and
        // a 3 GB-class device reports above 2.7 GB, so any value between classifies identically.
        assertTrue(ChainedAvailabilityPolicy.MINIMUM_DEVICE_MEMORY_BYTES > 1_950_000_000L)
        assertTrue(ChainedAvailabilityPolicy.MINIMUM_DEVICE_MEMORY_BYTES < 2_700_000_000L)
    }

    private fun authority(entitled: () -> Boolean): ChainedAvailabilityAuthority =
        ChainedAvailabilityAuthority(
            entitlement = ChainedEntitlementPort { entitled() },
            eligibilityStore = FakeEligibilityStore(available()),
        )

    private fun authorityFor(
        entitled: Boolean = true,
        read: ChainedDeviceEligibilityRead = available(),
    ): ChainedAvailabilityAuthority = ChainedAvailabilityAuthority(
        entitlement = ChainedEntitlementPort { entitled },
        eligibilityStore = FakeEligibilityStore(read),
    )

    @Test
    fun `only an eligible authority yields an engine permit`() {
        assertNotNull(ChainedEnginePermit.issue(authorityFor()))

        val ineligible = listOf(
            authorityFor(entitled = false),
            authorityFor(
                read = ChainedDeviceEligibilityRead.Unavailable(
                    ChainedDeviceEligibilityUnavailableReason.MEMORY_UNREADABLE,
                ),
            ),
            authorityFor(read = available(totalMemoryBytes = 1_000_000_000L)),
            authorityFor(
                read = available(
                    backoff = ChainedMemoryKillBackoffState(
                        killStreak = ChainedMemoryKillBackoffPolicy.KILLS_BEFORE_EXCLUSION,
                        excluded = true,
                    ),
                ),
            ),
        )
        ineligible.forEach { authority ->
            assertNull(
                "an ineligible authority must not mint an engine permit",
                ChainedEnginePermit.issue(authority),
            )
        }
    }

    @Test
    fun `a synthetic provider cannot mint a permit`() {
        // The permit's issue() is typed to the concrete store-backed authority, so no lambda or
        // custom ChainedAvailabilityProvider can produce one. This pins the compile-time property.
        val issueParameterType = requireNotNull(
            ChainedEnginePermit.Companion::class.java.declaredMethods
                .firstOrNull { it.name == "issue" },
        ).parameterTypes.single()
        assertEquals(ChainedAvailabilityAuthority::class.java, issueParameterType)
    }

    @Test
    fun `a permit is single-use`() {
        val permit = requireNotNull(ChainedEnginePermit.issue(authority { true }))
        assertTrue(permit.consumeForConstruction())
        assertFalse(
            "a spent permit must not construct a second session",
            permit.consumeForConstruction(),
        )
    }

    @Test
    fun `a permit minted before a lapse cannot construct after`() {
        var entitled = true
        val authority = authority { entitled }
        val permit = requireNotNull(ChainedEnginePermit.issue(authority))

        entitled = false
        assertFalse(
            "a permit must revalidate its bound authority at consumption",
            permit.consumeForConstruction(),
        )
    }

    @Test
    fun `re-minting after an eligible verdict works`() {
        var entitled = false
        val authority = authority { entitled }
        assertNull(ChainedEnginePermit.issue(authority))

        entitled = true
        val permit = requireNotNull(ChainedEnginePermit.issue(authority))
        assertTrue(permit.consumeForConstruction())
    }

    @Test
    fun `the authority composes the entitlement port and the eligibility store`() {
        val store = FakeEligibilityStore(available())
        val entitled = ChainedAvailabilityAuthority(
            entitlement = ChainedEntitlementPort { true },
            eligibilityStore = store,
        )
        assertTrue(entitled.current() is ChainedAvailability.Eligible)

        val unentitled = ChainedAvailabilityAuthority(
            entitlement = UnavailableChainedEntitlementPort,
            eligibilityStore = store,
        )
        assertEquals(
            ChainedAvailabilityRefusal.NOT_ENTITLED,
            (unentitled.current() as ChainedAvailability.Ineligible).reason,
        )
    }

    @Test
    fun `the unavailable entitlement port reports false`() {
        assertEquals(false, UnavailableChainedEntitlementPort.hasEntitlement())
    }

    private class FakeEligibilityStore(
        private val read: ChainedDeviceEligibilityRead,
    ) : ChainedDeviceEligibilityStore {
        override fun read(): ChainedDeviceEligibilityRead = read
        override fun markChainedSessionStarted(nowEpochMillis: Long): ChainedDeviceEligibilityRead = read
        override fun markChainedSessionStoppedCleanly(nowEpochMillis: Long): ChainedDeviceEligibilityRead = read
        override fun recordMemoryKill(nowEpochMillis: Long): ChainedDeviceEligibilityRead = read
        override fun clearMemoryKillBackoff(nowEpochMillis: Long): ChainedDeviceEligibilityRead = read
    }
}
