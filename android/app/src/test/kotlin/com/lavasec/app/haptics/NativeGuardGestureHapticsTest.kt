package com.lavasec.app.haptics

import com.lavasec.app.foundation.haptics.HapticKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeGuardGestureHapticsTest {
    private class Fixture(scope: TestScope) {
        var host: Any? = Any()
        var foreground = true
        var enabled = true
        val played = mutableListOf<Triple<Long, Any, HapticKind>>()
        val owner = NativeGuardGestureHaptics(scope, { host }, { foreground }, { enabled },
            { acceptedHost, kind -> played += Triple(scope.testScheduler.currentTime, acceptedHost, kind) },
            StandardTestDispatcher(scope.testScheduler))
    }

    @Test fun `hold has 300ms silent grace then nine ascending discrete pulses before 1200ms reveal`() = runTest {
        val fixture = Fixture(this)
        assertEquals(GuardGestureHapticOutcome.COMPLETED, fixture.owner.handle("start"))
        runCurrent(); advanceTimeBy(299); runCurrent()
        assertTrue(fixture.played.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(300L), fixture.played.map { it.first })
        advanceUntilIdle()
        assertEquals((300L..1100L step 100L).toList(), fixture.played.map { it.first })
        assertEquals(List(3) { HapticKind.IMPACT_LIGHT } + List(3) { HapticKind.IMPACT_MEDIUM } +
            List(3) { HapticKind.IMPACT_HEAVY }, fixture.played.map { it.third })
        assertEquals(1_200L, GuardLongPressHapticSchedule.HOLD_DURATION_MILLIS)
        assertEquals(24, GuardLongPressHapticSchedule.MOVEMENT_TOLERANCE)
        fixture.owner.close()
    }

    @Test fun `a replacement start retires the original ramp and gets its own grace`() = runTest {
        val fixture = Fixture(this)
        fixture.owner.handle("start"); runCurrent(); advanceTimeBy(250)
        fixture.owner.handle("start"); runCurrent(); advanceTimeBy(299); runCurrent()
        assertTrue(fixture.played.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(550L, fixture.played.single().first)
        fixture.owner.close(); advanceUntilIdle(); assertEquals(1, fixture.played.size)
    }

    @Test fun `contact ending during grace has no effect and never replays`() = runTest {
        val fixture = Fixture(this)
        fixture.owner.handle("start"); runCurrent(); advanceTimeBy(100)
        assertEquals(GuardGestureHapticOutcome.COMPLETED, fixture.owner.handle("end"))
        advanceUntilIdle(); assertTrue(fixture.played.isEmpty()); fixture.owner.close()
    }

    @Test fun `reveal cancels remaining pulses and delivers one heavy acknowledgement`() = runTest {
        val fixture = Fixture(this)
        fixture.owner.handle("start"); runCurrent(); advanceTimeBy(350)
        fixture.owner.handle("reveal")
        advanceUntilIdle()
        assertEquals(listOf(300L, 350L), fixture.played.map { it.first })
        assertEquals(listOf(HapticKind.IMPACT_LIGHT, HapticKind.IMPACT_HEAVY), fixture.played.map { it.third })
        fixture.owner.close()
    }

    @Test fun `tap acknowledges lightly and does not replace a running hold`() = runTest {
        val fixture = Fixture(this)
        fixture.owner.handle("start"); runCurrent(); advanceTimeBy(150)
        fixture.owner.handle("tap"); advanceTimeBy(150); runCurrent()
        assertEquals(listOf(150L, 300L), fixture.played.map { it.first })
        assertEquals(listOf(HapticKind.IMPACT_LIGHT, HapticKind.IMPACT_LIGHT), fixture.played.map { it.third })
        fixture.owner.close()
    }

    @Test fun `App Haptics suppression parses unknown gestures and cancels an in-flight ramp`() = runTest {
        val fixture = Fixture(this)
        fixture.enabled = false
        assertEquals(GuardGestureHapticOutcome.SUPPRESSED, fixture.owner.handle("tap"))
        assertEquals(GuardGestureHapticOutcome.UNKNOWN_GESTURE, fixture.owner.handle("unknown"))
        fixture.enabled = true; fixture.owner.handle("start"); runCurrent(); advanceTimeBy(350)
        fixture.enabled = false; fixture.owner.authorityChanged()
        fixture.enabled = true; advanceUntilIdle()
        assertEquals(1, fixture.played.size); fixture.owner.close()
    }

    @Test fun `background or replacement host permanently retires accepted pulses`() = runTest {
        for (replaceHost in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.owner.handle("start"); runCurrent(); advanceTimeBy(350)
            if (replaceHost) fixture.host = Any() else fixture.foreground = false
            fixture.owner.authorityChanged()
            fixture.foreground = true; advanceUntilIdle()
            assertEquals(1, fixture.played.size); fixture.owner.close()
        }
    }

    @Test fun `per-pulse host witness works without a lifecycle callback and close is terminal`() = runTest {
        val fixture = Fixture(this)
        fixture.owner.handle("start"); runCurrent(); advanceTimeBy(350)
        fixture.host = Any(); advanceUntilIdle()
        assertEquals(1, fixture.played.size)
        fixture.owner.handle("start"); runCurrent(); fixture.owner.close(); advanceUntilIdle()
        assertEquals(GuardGestureHapticOutcome.SUPPRESSED, fixture.owner.handle("reveal"))
        assertEquals(GuardGestureHapticOutcome.COMPLETED, fixture.owner.handle("end"))
        assertEquals(1, fixture.played.size)
    }
}
