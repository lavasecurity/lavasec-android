package com.lavasec.app.presentation.mascot

import kotlin.math.max
import kotlin.math.min

/**
 * A mascot state animation and its rendered frames over time.
 *
 * Ported 1:1 from the pinned iOS baseline:
 *   lavasec-ios/Sources/LavaSecPresentation/GuardianMascotAnimation.swift
 * (`GuardianMascotAnimationPlan`). Every duration constant, keyframe curve,
 * and the frame(at:) branch ordering are verbatim; the Android renderer must
 * see the exact same numbers as the iOS Guardian at the same elapsed time or
 * the two mascots diverge frame-by-frame. Swift `package` visibility maps to
 * Kotlin `internal` (module scope, visible to this module's tests).
 *
 * Equality is hand-written over all stored fields, including the private
 * [style], mirroring Swift's synthesized `Equatable`; it is not a data class
 * because the style must stay private while equality still has to see it.
 */
class GuardianMascotAnimationPlan private constructor(
    internal val startState: GuardianMascotState,
    /** State represented when the plan completes. */
    val endState: GuardianMascotState,
    /** Elapsed-time span of the plan, in seconds. */
    val duration: Double,
    style: Style,
) {
    private val style: Style = style

    /** iOS `Style` enum with associated values, kept private as in Swift. */
    private sealed interface Style {
        data object Interpolate : Style
        data class Hold(val state: GuardianMascotState) : Style
        data class Blink(val state: GuardianMascotState) : Style
        data class Sequence(val plans: List<GuardianMascotAnimationPlan>) : Style
    }

    /**
     * Returns the rendered frame sampled at the supplied elapsed time.
     *
     * Branch order matters: the awake<->grateful special cases run BEFORE the
     * generic interpolation, and a non-positive duration collapses to the
     * completed frame exactly as `duration <= 0 ? 1 : elapsed / duration` does
     * in Swift.
     */
    fun frame(at: Double): GuardianMascotFrame = when (val currentStyle = style) {
        Style.Interpolate -> {
            if (startState == GuardianMascotState.AWAKE && endState == GuardianMascotState.GRATEFUL) {
                return awakeToGratefulFrame(if (duration <= 0) 1.0 else at / duration)
            }
            if (startState == GuardianMascotState.GRATEFUL && endState == GuardianMascotState.AWAKE) {
                return gratefulToAwakeFrame(if (duration <= 0) 1.0 else at / duration)
            }

            GuardianMascotFrame.interpolated(
                from = stableFrame(startState),
                to = stableFrame(endState),
                progress = if (duration <= 0) 1.0 else at / duration,
            )
        }
        is Style.Hold -> stableFrame(currentStyle.state)
        is Style.Blink -> blinkFrame(currentStyle.state, if (duration <= 0) 1.0 else at / duration)
        is Style.Sequence -> sequenceFrame(currentStyle.plans, at)
    }

    override fun equals(other: Any?): Boolean =
        other is GuardianMascotAnimationPlan &&
            startState == other.startState &&
            endState == other.endState &&
            duration == other.duration &&
            style == other.style

    override fun hashCode(): Int {
        var result = startState.hashCode()
        result = 31 * result + endState.hashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + style.hashCode()
        return result
    }

    override fun toString(): String =
        "GuardianMascotAnimationPlan(startState=$startState, endState=$endState, duration=$duration)"

    companion object {
        /** iOS `package static let sleepWakeDuration`, verbatim. */
        internal const val sleepWakeDuration = 0.82
        /** iOS `internal static let blinkDelayDuration`, verbatim. */
        internal const val blinkDelayDuration = 0.5
        /** iOS `package static let blinkDuration`, verbatim. */
        internal const val blinkDuration = 0.46
        /** Default total duration of the wake transition, hold, and blink sequence. */
        const val wakeDuration = sleepWakeDuration + blinkDelayDuration + blinkDuration
        /** iOS `internal static let sleepDuration`, verbatim. */
        internal const val sleepDuration = sleepWakeDuration
        /** Default duration for transitions that do not use sleep/wake or waking-settle timing. */
        const val stateChangeDuration = 0.44
        /** iOS `internal static let settleDuration`, verbatim. */
        internal const val settleDuration = 0.22
        private const val awakeShieldWakeAmount = 1.0
        private const val awakeGlowAmount = 0.74
        private const val awakeMouthCurve = 1.0

        /**
         * iOS `package static func transition(from:to:duration:)`, verbatim,
         * including the three-way default-duration rule.
         */
        internal fun transition(
            from: GuardianMascotState,
            to: GuardianMascotState,
            duration: Double? = null,
        ): GuardianMascotAnimationPlan {
            val touchesSleeping =
                (from == GuardianMascotState.SLEEPING &&
                    (to == GuardianMascotState.WAKING || to == GuardianMascotState.AWAKE)) ||
                    (to == GuardianMascotState.SLEEPING && from != GuardianMascotState.SLEEPING)
            val defaultDuration = when {
                touchesSleeping -> sleepWakeDuration
                from == GuardianMascotState.WAKING && to == GuardianMascotState.AWAKE -> settleDuration
                else -> stateChangeDuration
            }

            return GuardianMascotAnimationPlan(
                startState = from,
                endState = to,
                duration = duration ?: defaultDuration,
                style = Style.Interpolate,
            )
        }

        /** Creates a paired-eye blink plan for a mascot state. iOS `blink(on:duration:)`. */
        fun blink(
            on: GuardianMascotState,
            duration: Double? = null,
        ): GuardianMascotAnimationPlan = GuardianMascotAnimationPlan(
            startState = on,
            endState = on,
            duration = duration ?: blinkDuration,
            style = Style.Blink(on),
        )

        /** iOS `package static func hold(on:duration:)`, verbatim. */
        internal fun hold(
            on: GuardianMascotState,
            duration: Double? = null,
        ): GuardianMascotAnimationPlan = GuardianMascotAnimationPlan(
            startState = on,
            endState = on,
            duration = duration ?: blinkDelayDuration,
            style = Style.Hold(on),
        )

        /**
         * iOS `package static func sequence(from:to:)`, verbatim: a sleeping
         * wake appends a hold and a blink ON AWAKE even when the end state is
         * still `.waking` — the hold/blink anchors are hardcoded to awake.
         */
        internal fun sequence(
            from: GuardianMascotState,
            to: GuardianMascotState,
        ): List<GuardianMascotAnimationPlan> {
            val transitionPlan = transition(from = from, to = to)

            if (!(from == GuardianMascotState.SLEEPING &&
                    (to == GuardianMascotState.WAKING || to == GuardianMascotState.AWAKE))
            ) {
                return listOf(transitionPlan)
            }

            return listOf(
                transitionPlan,
                hold(on = GuardianMascotState.AWAKE),
                blink(on = GuardianMascotState.AWAKE),
            )
        }

        /**
         * Creates the rendered state animation, including the wake hold and
         * blink when applicable. iOS `animation(from:to:duration:)`.
         */
        fun animation(
            from: GuardianMascotState,
            to: GuardianMascotState,
            duration: Double? = null,
        ): GuardianMascotAnimationPlan {
            val plans = scaledPlans(sequence(from = from, to = to), duration = duration)

            if (plans.size <= 1) return plans[0]

            return GuardianMascotAnimationPlan(
                startState = plans[0].startState,
                endState = plans[plans.size - 1].endState,
                duration = plans.fold(0.0) { acc, plan -> acc + plan.duration },
                style = Style.Sequence(plans),
            )
        }

        /** iOS `package static func stableFrame(for:)`, verbatim per state. */
        internal fun stableFrame(state: GuardianMascotState): GuardianMascotFrame = when (state) {
            GuardianMascotState.SLEEPING -> GuardianMascotFrame(
                shieldWakeAmount = 0.0,
                glowAmount = 0.0,
                sleepyEyeAmount = 1.0,
                leftEyeOpenAmount = 0.0,
                rightEyeOpenAmount = 0.0,
                mouthCurve = awakeMouthCurve,
            )
            GuardianMascotState.WAKING,
            GuardianMascotState.AWAKE,
            -> GuardianMascotFrame(
                shieldWakeAmount = awakeShieldWakeAmount,
                glowAmount = awakeGlowAmount,
                sleepyEyeAmount = 0.0,
                leftEyeOpenAmount = 1.0,
                rightEyeOpenAmount = 1.0,
                mouthCurve = awakeMouthCurve,
            )
            GuardianMascotState.PAUSED -> GuardianMascotFrame(
                shieldWakeAmount = awakeShieldWakeAmount,
                glowAmount = awakeGlowAmount,
                sleepyEyeAmount = 1.0,
                leftEyeOpenAmount = 0.0,
                rightEyeOpenAmount = 0.0,
                pauseAmount = 1.0,
                mouthCurve = awakeMouthCurve,
            )
            GuardianMascotState.RETRYING ->
                // "Working on it" — relaxed lids (not wide/stunned, not sleepy), level eyes,
                // flat mouth. No concern tilt: this is the unworried, self-healing counterpart
                // to .concerned. Motion is carried by the status badge, not the face.
                GuardianMascotFrame(
                    shieldWakeAmount = awakeShieldWakeAmount,
                    glowAmount = awakeGlowAmount,
                    sleepyEyeAmount = 0.0,
                    leftEyeOpenAmount = 0.80,
                    rightEyeOpenAmount = 0.80,
                    mouthCurve = 0.0,
                )
            GuardianMascotState.CONCERNED -> GuardianMascotFrame(
                shieldWakeAmount = awakeShieldWakeAmount,
                glowAmount = awakeGlowAmount,
                sleepyEyeAmount = 0.0,
                leftEyeOpenAmount = 0.78,
                rightEyeOpenAmount = 0.78,
                concernAmount = 1.0,
                mouthCurve = -0.22,
            )
            GuardianMascotState.GRATEFUL -> GuardianMascotFrame(
                shieldWakeAmount = 1.0,
                glowAmount = 0.88,
                sleepyEyeAmount = 0.0,
                leftEyeOpenAmount = 0.0,
                rightEyeOpenAmount = 0.0,
                happyEyeAmount = 1.0,
                gratitudeAmount = 1.0,
                mouthCurve = 1.18,
            )
        }

        /** iOS `private static func scaledPlans(_:duration:)`, verbatim. */
        private fun scaledPlans(
            plans: List<GuardianMascotAnimationPlan>,
            duration: Double?,
        ): List<GuardianMascotAnimationPlan> {
            val overrideDuration = duration ?: return plans

            val currentDuration = plans.fold(0.0) { acc, plan -> acc + plan.duration }
            if (currentDuration <= 0) return plans

            val scale = max(0.0, overrideDuration) / currentDuration
            return plans.map { plan ->
                GuardianMascotAnimationPlan(
                    startState = plan.startState,
                    endState = plan.endState,
                    duration = plan.duration * scale,
                    style = plan.style,
                )
            }
        }

        /** iOS `private static func sequenceFrame(_:at:)`, verbatim. */
        private fun sequenceFrame(
            plans: List<GuardianMascotAnimationPlan>,
            at: Double,
        ): GuardianMascotFrame {
            val firstPlan = plans.firstOrNull() ?: return stableFrame(GuardianMascotState.AWAKE)

            var remainingElapsed = max(0.0, at)
            for (plan in plans) {
                if (remainingElapsed <= plan.duration) return plan.frame(remainingElapsed)
                remainingElapsed -= plan.duration
            }

            val lastPlan = plans.lastOrNull()
            return lastPlan?.frame(lastPlan.duration) ?: firstPlan.frame(firstPlan.duration)
        }

        /**
         * iOS `private static func awakeToGratefulFrame(at:)`, verbatim: eyes
         * lengthen over the first 44% of eased progress, then close over the
         * remaining 66% past 0.34.
         */
        private fun awakeToGratefulFrame(at: Double): GuardianMascotFrame {
            val progress = smoothstep(clamp(at))
            val lengthenProgress = smoothstep(clamp(progress / 0.44))
            val closeProgress = smoothstep(clamp((progress - 0.34) / 0.66))
            val awake = stableFrame(GuardianMascotState.AWAKE)
            val grateful = stableFrame(GuardianMascotState.GRATEFUL)

            return GuardianMascotFrame(
                shieldWakeAmount = interpolate(awake.shieldWakeAmount, grateful.shieldWakeAmount, progress),
                shieldScale = interpolate(awake.shieldScale, grateful.shieldScale, progress),
                glowAmount = interpolate(awake.glowAmount, grateful.glowAmount, progress),
                sleepyEyeAmount = 0.0,
                leftEyeOpenAmount = interpolate(awake.leftEyeOpenAmount, grateful.leftEyeOpenAmount, closeProgress),
                rightEyeOpenAmount = interpolate(awake.rightEyeOpenAmount, grateful.rightEyeOpenAmount, closeProgress),
                happyEyeAmount = interpolate(awake.happyEyeAmount, grateful.happyEyeAmount, lengthenProgress),
                gratitudeAmount = interpolate(awake.gratitudeAmount, grateful.gratitudeAmount, progress),
                mouthCurve = interpolate(awake.mouthCurve, grateful.mouthCurve, progress),
            )
        }

        /**
         * iOS `private static func gratefulToAwakeFrame(at:)`, verbatim —
         * INCLUDING the eye lerp direction: eyes blend from the AWAKE value
         * toward the GRATEFUL value while `closeProgress` drives the eyes open
         * in reverse, so the open-amount curve is symmetric to the closing one.
         */
        private fun gratefulToAwakeFrame(at: Double): GuardianMascotFrame {
            val progress = smoothstep(clamp(at))
            val reverseProgress = 1 - progress
            val lengthenProgress = smoothstep(clamp(reverseProgress / 0.44))
            val closeProgress = smoothstep(clamp((reverseProgress - 0.34) / 0.66))
            val awake = stableFrame(GuardianMascotState.AWAKE)
            val grateful = stableFrame(GuardianMascotState.GRATEFUL)

            return GuardianMascotFrame(
                shieldWakeAmount = interpolate(grateful.shieldWakeAmount, awake.shieldWakeAmount, progress),
                shieldScale = interpolate(grateful.shieldScale, awake.shieldScale, progress),
                glowAmount = interpolate(grateful.glowAmount, awake.glowAmount, progress),
                sleepyEyeAmount = 0.0,
                leftEyeOpenAmount = interpolate(awake.leftEyeOpenAmount, grateful.leftEyeOpenAmount, closeProgress),
                rightEyeOpenAmount = interpolate(awake.rightEyeOpenAmount, grateful.rightEyeOpenAmount, closeProgress),
                happyEyeAmount = interpolate(awake.happyEyeAmount, grateful.happyEyeAmount, lengthenProgress),
                gratitudeAmount = interpolate(grateful.gratitudeAmount, awake.gratitudeAmount, progress),
                mouthCurve = interpolate(grateful.mouthCurve, awake.mouthCurve, progress),
            )
        }

        /** iOS `private static func blinkFrame(on:at:)`, verbatim. */
        private fun blinkFrame(on: GuardianMascotState, at: Double): GuardianMascotFrame {
            val progress = clamp(at)
            val baseFrame = stableFrame(on)
            val blinkAmount = triangle(progress, start = 0.12, peak = 0.45, end = 0.78)
            val eyeOpenAmount = 1 - blinkAmount

            return GuardianMascotFrame(
                shieldWakeAmount = baseFrame.shieldWakeAmount,
                shieldScale = baseFrame.shieldScale,
                glowAmount = baseFrame.glowAmount,
                sleepyEyeAmount = baseFrame.sleepyEyeAmount,
                leftEyeOpenAmount = baseFrame.leftEyeOpenAmount * eyeOpenAmount,
                rightEyeOpenAmount = baseFrame.rightEyeOpenAmount * eyeOpenAmount,
                winkAmount = 0.0,
                happyEyeAmount = baseFrame.happyEyeAmount,
                concernAmount = baseFrame.concernAmount,
                pauseAmount = baseFrame.pauseAmount,
                gratitudeAmount = baseFrame.gratitudeAmount,
                mouthCurve = baseFrame.mouthCurve,
            )
        }
    }
}

// The four keyframe helpers below are the file-private functions at the bottom
// of the iOS GuardianMascotAnimation.swift. They are `internal` here because
// GuardianMascotFrame.interpolated lives in its own Kotlin file but must share
// the exact same math; nothing outside this module may touch them.

/** iOS `private func interpolate(_:_:_:)`, verbatim lerp. */
internal fun interpolate(start: Double, end: Double, progress: Double): Double =
    start + (end - start) * progress

/**
 * iOS `private func triangle(_:start:peak:end:)`, verbatim: a 0..1..0 triangle
 * wave over [start, peak, end]; degenerate windows evaluate to 0 instead of
 * dividing by zero.
 */
internal fun triangle(value: Double, start: Double, peak: Double, end: Double): Double {
    if (peak <= start || end <= peak) return 0.0
    if (value < start || value > end) return 0.0

    return if (value <= peak) {
        clamp((value - start) / (peak - start))
    } else {
        clamp((end - value) / (end - peak))
    }
}

/** iOS `private func smoothstep(_:)`, verbatim: clamped x^2(3 - 2x). */
internal fun smoothstep(value: Double): Double {
    val clampedValue = clamp(value)
    return clampedValue * clampedValue * (3 - 2 * clampedValue)
}

/** iOS `private func clamp(_:)`, verbatim min(max(x, 0), 1). */
internal fun clamp(value: Double): Double = min(max(value, 0.0), 1.0)
