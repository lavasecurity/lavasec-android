package com.lavasec.app.presentation.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported 1:1 from the pinned iOS baseline tests:
//   lavasec-ios/Tests/LavaSecCoreTests/GuardianMascotAnimationTests.swift
// iOS `accuracy: 0.001` tolerance is tightened to a 1e-9 JUnit delta: every
// asserted quantity here is exact double arithmetic, so the tighter delta
// still passes while catching any accidental drift in the ported math.
class GuardianMascotAnimationTest {
    @Test
    fun `state graph keeps wakeup as the only sleeping exit`() {
        assertEquals(listOf(GuardianMascotState.WAKING), GuardianMascotState.SLEEPING.allowedNextStates)
        assertEquals(
            listOf(
                GuardianMascotState.AWAKE,
                GuardianMascotState.RETRYING,
                GuardianMascotState.CONCERNED,
                GuardianMascotState.SLEEPING,
            ),
            GuardianMascotState.WAKING.allowedNextStates,
        )
        assertEquals(
            listOf(
                GuardianMascotState.SLEEPING,
                GuardianMascotState.PAUSED,
                GuardianMascotState.RETRYING,
                GuardianMascotState.CONCERNED,
                GuardianMascotState.GRATEFUL,
            ),
            GuardianMascotState.AWAKE.allowedNextStates,
        )
        assertEquals(
            listOf(GuardianMascotState.AWAKE, GuardianMascotState.SLEEPING),
            GuardianMascotState.PAUSED.allowedNextStates,
        )
        assertEquals(
            listOf(
                GuardianMascotState.AWAKE,
                GuardianMascotState.CONCERNED,
                GuardianMascotState.SLEEPING,
            ),
            GuardianMascotState.RETRYING.allowedNextStates,
        )
        assertEquals(
            listOf(
                GuardianMascotState.AWAKE,
                GuardianMascotState.RETRYING,
                GuardianMascotState.SLEEPING,
            ),
            GuardianMascotState.CONCERNED.allowedNextStates,
        )
        assertEquals(listOf(GuardianMascotState.AWAKE), GuardianMascotState.GRATEFUL.allowedNextStates)
    }

    @Test
    fun `sleep wake transition mirrors wake sleep without wink`() {
        val wakePlan = GuardianMascotAnimationPlan.transition(from = GuardianMascotState.SLEEPING, to = GuardianMascotState.AWAKE)
        val sleepPlan = GuardianMascotAnimationPlan.transition(from = GuardianMascotState.AWAKE, to = GuardianMascotState.SLEEPING)

        assertEquals(GuardianMascotAnimationPlan.sleepWakeDuration, wakePlan.duration, 1e-9)
        assertEquals(GuardianMascotAnimationPlan.sleepWakeDuration, sleepPlan.duration, 1e-9)

        val sleeping = wakePlan.frame(0.0)
        assertEquals(0.0, sleeping.shieldWakeAmount, 1e-9)
        assertEquals(1.0, sleeping.sleepyEyeAmount, 1e-9)
        assertEquals(0.0, sleeping.leftEyeOpenAmount, 1e-9)
        assertEquals(0.0, sleeping.rightEyeOpenAmount, 1e-9)

        val progressSamples = listOf(0.2, 0.5, 0.8)
        for (progress in progressSamples) {
            val waking = wakePlan.frame(wakePlan.duration * progress)
            val fallingAsleep = sleepPlan.frame(sleepPlan.duration * (1.0 - progress))

            assertEquals(waking.shieldWakeAmount, fallingAsleep.shieldWakeAmount, 1e-9)
            assertEquals(waking.sleepyEyeAmount, fallingAsleep.sleepyEyeAmount, 1e-9)
            assertEquals(waking.leftEyeOpenAmount, fallingAsleep.leftEyeOpenAmount, 1e-9)
            assertEquals(waking.rightEyeOpenAmount, fallingAsleep.rightEyeOpenAmount, 1e-9)
            assertEquals(waking.mouthCurve, fallingAsleep.mouthCurve, 1e-9)
            assertEquals(0.0, waking.winkAmount, 1e-9)
            assertEquals(waking.leftEyeOpenAmount, waking.rightEyeOpenAmount, 1e-9)
        }

        val awake = wakePlan.frame(wakePlan.duration)
        assertEquals(1.0, awake.shieldWakeAmount, 1e-9)
        assertEquals(0.0, awake.sleepyEyeAmount, 1e-9)
        assertEquals(1.0, awake.leftEyeOpenAmount, 1e-9)
        assertEquals(1.0, awake.rightEyeOpenAmount, 1e-9)
        assertEquals(0.0, awake.winkAmount, 1e-9)
    }

    @Test
    fun `blink is atomic both eye action`() {
        val plan = GuardianMascotAnimationPlan.blink(on = GuardianMascotState.AWAKE)

        assertEquals(GuardianMascotAnimationPlan.blinkDuration, plan.duration, 1e-9)
        assertEquals(GuardianMascotState.AWAKE, plan.startState)
        assertEquals(GuardianMascotState.AWAKE, plan.endState)

        val start = plan.frame(0.0)
        assertEquals(1.0, start.leftEyeOpenAmount, 1e-9)
        assertEquals(1.0, start.rightEyeOpenAmount, 1e-9)
        assertEquals(0.0, start.winkAmount, 1e-9)

        val closed = plan.frame(plan.duration * 0.45)
        assertTrue(closed.leftEyeOpenAmount < 0.08)
        assertTrue(closed.rightEyeOpenAmount < 0.08)
        assertEquals(closed.leftEyeOpenAmount, closed.rightEyeOpenAmount, 1e-9)
        assertEquals(0.0, closed.winkAmount, 1e-9)
        assertEquals(1.0, closed.shieldWakeAmount, 1e-9)

        val end = plan.frame(plan.duration)
        assertEquals(1.0, end.leftEyeOpenAmount, 1e-9)
        assertEquals(1.0, end.rightEyeOpenAmount, 1e-9)
        assertEquals(0.0, end.winkAmount, 1e-9)
    }

    @Test
    fun `animation sequence adds blink only after sleeping wake`() {
        val wakeSequence = GuardianMascotAnimationPlan.sequence(from = GuardianMascotState.SLEEPING, to = GuardianMascotState.AWAKE)
        assertEquals(
            listOf(
                GuardianMascotAnimationPlan.transition(from = GuardianMascotState.SLEEPING, to = GuardianMascotState.AWAKE),
                GuardianMascotAnimationPlan.hold(on = GuardianMascotState.AWAKE),
                GuardianMascotAnimationPlan.blink(on = GuardianMascotState.AWAKE),
            ),
            wakeSequence,
        )

        val offSequence = GuardianMascotAnimationPlan.sequence(from = GuardianMascotState.AWAKE, to = GuardianMascotState.SLEEPING)
        assertEquals(
            listOf(
                GuardianMascotAnimationPlan.transition(from = GuardianMascotState.AWAKE, to = GuardianMascotState.SLEEPING),
            ),
            offSequence,
        )
    }

    @Test
    fun `wake animation composes sleep wake and atomic blink into one plan`() {
        val plan = GuardianMascotAnimationPlan.animation(from = GuardianMascotState.SLEEPING, to = GuardianMascotState.AWAKE)

        assertEquals(GuardianMascotState.SLEEPING, plan.startState)
        assertEquals(GuardianMascotState.AWAKE, plan.endState)
        assertEquals(
            GuardianMascotAnimationPlan.sleepWakeDuration + 0.5 + GuardianMascotAnimationPlan.blinkDuration,
            plan.duration,
            1e-9,
        )

        val beforeBlink = plan.frame(GuardianMascotAnimationPlan.sleepWakeDuration * 0.96)
        assertTrue(beforeBlink.leftEyeOpenAmount > 0.98)
        assertTrue(beforeBlink.rightEyeOpenAmount > 0.98)

        val waitingBeforeBlink = plan.frame(GuardianMascotAnimationPlan.sleepWakeDuration + 0.25)
        assertTrue(waitingBeforeBlink.leftEyeOpenAmount > 0.98)
        assertTrue(waitingBeforeBlink.rightEyeOpenAmount > 0.98)

        val blinkClosed = plan.frame(
            GuardianMascotAnimationPlan.sleepWakeDuration + 0.5 + GuardianMascotAnimationPlan.blinkDuration * 0.45,
        )
        assertTrue(blinkClosed.leftEyeOpenAmount < 0.08)
        assertTrue(blinkClosed.rightEyeOpenAmount < 0.08)
        assertEquals(blinkClosed.leftEyeOpenAmount, blinkClosed.rightEyeOpenAmount, 1e-9)
        assertEquals(0.0, blinkClosed.winkAmount, 1e-9)
    }

    @Test
    fun `transition into sleeping gets dedicated closing duration`() {
        val plan = GuardianMascotAnimationPlan.transition(from = GuardianMascotState.AWAKE, to = GuardianMascotState.SLEEPING)

        assertEquals(GuardianMascotAnimationPlan.sleepWakeDuration, plan.duration, 1e-9)
        assertTrue(plan.duration > GuardianMascotAnimationPlan.stateChangeDuration)

        val nearlySleeping = plan.frame(plan.duration * 0.92)
        assertTrue(nearlySleeping.leftEyeOpenAmount > 0)
        assertTrue(nearlySleeping.rightEyeOpenAmount > 0)
        assertTrue(nearlySleeping.sleepyEyeAmount > 0.95)

        val sleeping = plan.frame(plan.duration)
        assertEquals(0.0, sleeping.leftEyeOpenAmount, 1e-9)
        assertEquals(0.0, sleeping.rightEyeOpenAmount, 1e-9)
        assertEquals(1.0, sleeping.sleepyEyeAmount, 1e-9)
    }

    @Test
    fun `stable expressions expose distinct mascot poses`() {
        val sleeping = GuardianMascotAnimationPlan.stableFrame(GuardianMascotState.SLEEPING)
        assertEquals(0.0, sleeping.shieldWakeAmount, 1e-9)
        assertEquals(1.0, sleeping.sleepyEyeAmount, 1e-9)

        val awake = GuardianMascotAnimationPlan.stableFrame(GuardianMascotState.AWAKE)
        assertEquals(1.0, awake.shieldWakeAmount, 1e-9)
        assertEquals(1.0, awake.leftEyeOpenAmount, 1e-9)
        assertEquals(1.0, awake.rightEyeOpenAmount, 1e-9)
        assertTrue(awake.glowAmount > sleeping.glowAmount)
        assertEquals(sleeping.mouthCurve, awake.mouthCurve, 1e-9)

        val paused = GuardianMascotAnimationPlan.stableFrame(GuardianMascotState.PAUSED)
        assertEquals(1.0, paused.pauseAmount, 1e-9)
        assertEquals(awake.shieldWakeAmount, paused.shieldWakeAmount, 1e-9)
        assertEquals(awake.glowAmount, paused.glowAmount, 1e-9)
        assertEquals(1.0, paused.sleepyEyeAmount, 1e-9)
        assertEquals(0.0, paused.leftEyeOpenAmount, 1e-9)
        assertEquals(0.0, paused.rightEyeOpenAmount, 1e-9)
        assertEquals(awake.mouthCurve, paused.mouthCurve, 1e-9)

        val concerned = GuardianMascotAnimationPlan.stableFrame(GuardianMascotState.CONCERNED)
        assertEquals(1.0, concerned.concernAmount, 1e-9)
        assertEquals(awake.shieldWakeAmount, concerned.shieldWakeAmount, 1e-9)
        assertEquals(awake.glowAmount, concerned.glowAmount, 1e-9)
        assertTrue(concerned.leftEyeOpenAmount < awake.leftEyeOpenAmount)
        assertTrue(concerned.rightEyeOpenAmount < awake.rightEyeOpenAmount)
        assertTrue(concerned.mouthCurve < 0)
        assertTrue(concerned.mouthCurve > -0.30)

        val retrying = GuardianMascotAnimationPlan.stableFrame(GuardianMascotState.RETRYING)
        assertEquals(0.0, retrying.concernAmount, 1e-9)
        assertEquals(0.0, retrying.mouthCurve, 1e-9)
        assertEquals(0.0, retrying.sleepyEyeAmount, 1e-9)
        assertEquals(awake.shieldWakeAmount, retrying.shieldWakeAmount, 1e-9)
        assertEquals(awake.glowAmount, retrying.glowAmount, 1e-9)
        assertEquals(retrying.leftEyeOpenAmount, retrying.rightEyeOpenAmount, 1e-9)
        assertTrue(retrying.leftEyeOpenAmount < awake.leftEyeOpenAmount)
        assertTrue(retrying.leftEyeOpenAmount > concerned.leftEyeOpenAmount)

        val grateful = GuardianMascotAnimationPlan.stableFrame(GuardianMascotState.GRATEFUL)
        assertEquals(1.0, grateful.gratitudeAmount, 1e-9)
        assertTrue(grateful.happyEyeAmount > awake.happyEyeAmount)
        assertTrue(grateful.mouthCurve > awake.mouthCurve)
    }

    @Test
    fun `awake to grateful lengthens eyes before closing`() {
        val plan = GuardianMascotAnimationPlan.transition(from = GuardianMascotState.AWAKE, to = GuardianMascotState.GRATEFUL)

        val early = plan.frame(plan.duration * 0.35)
        assertTrue(early.happyEyeAmount > 0.55)
        assertTrue(early.leftEyeOpenAmount > 0.90)
        assertTrue(early.rightEyeOpenAmount > 0.90)

        val middle = plan.frame(plan.duration * 0.55)
        assertTrue(middle.happyEyeAmount > 0.90)
        assertTrue(middle.leftEyeOpenAmount < early.leftEyeOpenAmount)
        assertTrue(middle.rightEyeOpenAmount < early.rightEyeOpenAmount)
        assertTrue(middle.leftEyeOpenAmount > 0.50)
        assertTrue(middle.rightEyeOpenAmount > 0.50)

        val late = plan.frame(plan.duration * 0.85)
        assertTrue(late.leftEyeOpenAmount < middle.leftEyeOpenAmount)
        assertTrue(late.rightEyeOpenAmount < middle.rightEyeOpenAmount)
        assertTrue(late.leftEyeOpenAmount < 0.12)
        assertTrue(late.rightEyeOpenAmount < 0.12)
        assertEquals(1.0, late.happyEyeAmount, 1e-9)
    }

    @Test
    fun `grateful to awake opens before relaxing happy eyes`() {
        val plan = GuardianMascotAnimationPlan.transition(from = GuardianMascotState.GRATEFUL, to = GuardianMascotState.AWAKE)

        val early = plan.frame(plan.duration * 0.35)
        assertTrue(early.leftEyeOpenAmount > 0.25)
        assertTrue(early.rightEyeOpenAmount > 0.25)
        assertTrue(early.happyEyeAmount > 0.95)

        val middle = plan.frame(plan.duration * 0.55)
        assertTrue(middle.leftEyeOpenAmount > 0.90)
        assertTrue(middle.rightEyeOpenAmount > 0.90)
        assertTrue(middle.happyEyeAmount > 0.90)

        val late = plan.frame(plan.duration * 0.85)
        assertTrue(late.leftEyeOpenAmount > 0.98)
        assertTrue(late.rightEyeOpenAmount > 0.98)
        assertTrue(late.happyEyeAmount < 0.15)
    }

    @Test
    fun `awake tap acknowledgement returns to the exact awake pose`() {
        // The slice-02 awake tap gratitude (iOS playGuardianTapGratitude) plays
        // awake -> grateful -> awake; the round trip must land back on the exact
        // awake stable frame so repeated taps never drift the base pose.
        val toGrateful = GuardianMascotAnimationPlan.transition(
            from = GuardianMascotState.AWAKE,
            to = GuardianMascotState.GRATEFUL,
        )
        val toAwake = GuardianMascotAnimationPlan.transition(
            from = GuardianMascotState.GRATEFUL,
            to = GuardianMascotState.AWAKE,
        )
        val awake = GuardianMascotAnimationPlan.stableFrame(GuardianMascotState.AWAKE)

        assertEquals(toGrateful.duration, GuardianMascotAnimationPlan.stateChangeDuration, 1e-9)
        assertEquals(toAwake.duration, GuardianMascotAnimationPlan.stateChangeDuration, 1e-9)

        val grateful = toGrateful.frame(toGrateful.duration)
        val returned = toAwake.frame(toAwake.duration)
        assertEquals(1.0, grateful.gratitudeAmount, 1e-9)
        assertEquals(awake.shieldWakeAmount, returned.shieldWakeAmount, 1e-9)
        assertEquals(awake.leftEyeOpenAmount, returned.leftEyeOpenAmount, 1e-9)
        assertEquals(awake.rightEyeOpenAmount, returned.rightEyeOpenAmount, 1e-9)
        assertEquals(awake.mouthCurve, returned.mouthCurve, 1e-9)
        assertEquals(0.0, returned.gratitudeAmount, 1e-9)
    }
}
