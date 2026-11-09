package com.lavasec.app.presentation.mascot

/**
 * Render parameters produced by a mascot animation plan at one point in time.
 *
 * Ported 1:1 from the pinned iOS baseline:
 *   lavasec-ios/Sources/LavaSecPresentation/GuardianMascotAnimation.swift
 * (`GuardianMascotFrame`). Every field, default, and the interpolation math
 * mirror the Swift struct; the renderer consumes these values without
 * reinterpreting them. The shared keyframe helpers (`interpolate`,
 * `smoothstep`, `clamp`) live in [GuardianMascotAnimationPlan]'s file because
 * the iOS source keeps them file-private at the bottom of that same file.
 */
data class GuardianMascotFrame(
    /** Amount used to blend between the sleeping and awake shield appearances. */
    val shieldWakeAmount: Double,
    /** Scale applied to the shield geometry. */
    val shieldScale: Double = 1.0,
    /** Amount applied to the shield's glow intensity. */
    val glowAmount: Double,
    /** Amount of the sleepy-eye curve used by the renderer. */
    val sleepyEyeAmount: Double,
    /** Open amount used to render the left eye. */
    val leftEyeOpenAmount: Double,
    /** Open amount used to render the right eye. */
    val rightEyeOpenAmount: Double,
    /** Amount of wink curvature applied to the right eye. */
    val winkAmount: Double = 0.0,
    /** Amount of happy-eye lengthening and curvature. */
    val happyEyeAmount: Double = 0.0,
    /** Amount of concerned-eye spacing, shape, and tilt. */
    val concernAmount: Double = 0.0,
    /** Amount of the paused-eyes pose. `package` in Swift; `internal` is the Kotlin match. */
    internal val pauseAmount: Double = 0.0,
    /** Amount used to widen the mouth for the grateful expression. */
    val gratitudeAmount: Double = 0.0,
    /** Curve value used to render the mouth. */
    val mouthCurve: Double,
) {
    companion object {
        /** iOS `static func interpolated(from:to:progress:)`, verbatim. */
        internal fun interpolated(
            from: GuardianMascotFrame,
            to: GuardianMascotFrame,
            progress: Double,
        ): GuardianMascotFrame {
            // Progress is clamped then smoothstepped BEFORE any field lerp;
            // iOS does the same, so overshoot/undershoot collapse to endpoints.
            val easedProgress = smoothstep(clamp(progress))

            return GuardianMascotFrame(
                shieldWakeAmount = interpolate(from.shieldWakeAmount, to.shieldWakeAmount, easedProgress),
                shieldScale = interpolate(from.shieldScale, to.shieldScale, easedProgress),
                glowAmount = interpolate(from.glowAmount, to.glowAmount, easedProgress),
                sleepyEyeAmount = interpolate(from.sleepyEyeAmount, to.sleepyEyeAmount, easedProgress),
                leftEyeOpenAmount = interpolate(from.leftEyeOpenAmount, to.leftEyeOpenAmount, easedProgress),
                rightEyeOpenAmount = interpolate(from.rightEyeOpenAmount, to.rightEyeOpenAmount, easedProgress),
                winkAmount = interpolate(from.winkAmount, to.winkAmount, easedProgress),
                happyEyeAmount = interpolate(from.happyEyeAmount, to.happyEyeAmount, easedProgress),
                concernAmount = interpolate(from.concernAmount, to.concernAmount, easedProgress),
                pauseAmount = interpolate(from.pauseAmount, to.pauseAmount, easedProgress),
                gratitudeAmount = interpolate(from.gratitudeAmount, to.gratitudeAmount, easedProgress),
                mouthCurve = interpolate(from.mouthCurve, to.mouthCurve, easedProgress),
            )
        }
    }
}
