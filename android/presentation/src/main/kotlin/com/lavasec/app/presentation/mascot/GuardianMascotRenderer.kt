package com.lavasec.app.presentation.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LavaColors
import com.lavasec.app.foundation.preferences.GuardianLook
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the Lava Guardian (shield + face) for one [GuardianMascotFrame] in the
 * selected [look].
 *
 * Ported from the pinned iOS renderer:
 *   lavasec-ios/Shared/SoftShieldGuardian.swift
 * (`SoftShieldGuardianContent.shield(_:)` / `face(_:)` / `morphedEyes(_:)` /
 * `eyePose(for:frame:)`, `LavaGuardianShieldShape`, `MorphingGuardianEyeShape`,
 * `GuardianMouthShape`, `LavaGuardianPathMapper`). [GuardianLook.ORIGINAL] draws
 * the original solid shield here; the obsidian/faceted looks (`ObsidianShieldBody`,
 * `LavaGuardianShellFacet`, `LavaGuardianShieldRimShape`) are drawn by
 * [drawObsidianShield] in GuardianObsidianShield.kt. Colors come from the Android
 * design system (LavaTokens); the token values are rounded sRGB resolutions of the
 * same iOS LavaGuardianStyle source values.
 *
 * Geometry contract with iOS: the shield lives in a 220 x 250 design space
 * letterboxed at its aspect into the canvas, while the face features are all
 * fractions of the canvas's square dimension `size` — exactly like the iOS
 * view whose shield path fills the same `size x size` frame.
 */
@Composable
fun GuardianMascotRenderer(
    frame: GuardianMascotFrame,
    modifier: Modifier = Modifier,
    minimumFeatureScale: Float = 1f,
    look: GuardianLook = GuardianLook.ORIGINAL,
) {
    val darkTheme = isSystemInDarkTheme()
    Canvas(modifier = modifier) {
        drawGuardian(frame = frame, darkTheme = darkTheme, minimumFeatureScale = minimumFeatureScale, look = look)
    }
}

/** Fixed light original awake leaf used by the frozen recipient-facing image export. */
fun DrawScope.drawOriginalAwakeGuardianForExport() {
    drawGuardian(
        frame = GuardianMascotAnimationPlan.animation(GuardianMascotState.AWAKE, GuardianMascotState.AWAKE).frame(at = 0.0),
        darkTheme = false, minimumFeatureScale = 1f, look = GuardianLook.ORIGINAL,
    )
}

private fun DrawScope.drawGuardian(
    frame: GuardianMascotFrame,
    darkTheme: Boolean,
    minimumFeatureScale: Float,
    look: GuardianLook,
) {
    val sizePx = min(size.width, size.height)
    if (sizePx <= 0f) return

    // iOS ZStack: shield below, face above. Only the shield carries shieldScale
    // (`.scaleEffect(frame.shieldScale)` sits on the shield ZStack, not the face).
    scale(scale = frame.shieldScale.toFloat(), pivot = center) {
        if (look == GuardianLook.ORIGINAL) {
            drawOriginalShield(frame = frame, darkTheme = darkTheme)
        } else {
            drawObsidianShield(frame = frame, darkTheme = darkTheme, look = look)
        }
    }
    drawFace(frame = frame, darkTheme = darkTheme, minimumFeatureScale = minimumFeatureScale)
}

private fun DrawScope.drawOriginalShield(frame: GuardianMascotFrame, darkTheme: Boolean) {
    val shieldPath = guardianShieldPath(GuardianDesignMapper(bounds = size))
    val lavaOrange = if (darkTheme) LavaColors.LavaOrangeDark else LavaColors.LavaOrange
    val sleepGray = if (darkTheme) LavaColors.GuardianSleepGrayDark else LavaColors.GuardianSleepGray

    // iOS `originalShieldBody`: the sleeping gray fill sits under the lava fill.
    drawPath(
        path = shieldPath,
        color = sleepGray,
        alpha = (1.0 - frame.shieldWakeAmount).toFloat(),
    )

    // iOS attaches `.shadow(color: lavaOrange.opacity(0.18 * glowAmount), radius: 12, x: 0, y: 8)`
    // to the GRADIENT fill, so the halo composites between the gray fill and the gradient
    // itself. SwiftUI scales shadow strength by the source layer's alpha, and the source
    // layer is the gradient fill at `shieldWakeAmount` opacity — hence the extra wakeAmount
    // factor. Android has no DrawScope blur below the API 31 RenderEffects, so the halo is a
    // native BlurMaskFilter pass. BlurMaskFilter may be ignored by hardware-accelerated
    // canvases on some releases (degrading the halo to a crisp silhouette); it renders
    // exactly on software canvases, which is what the Robolectric tests exercise.
    drawGlowShadow(
        path = shieldPath,
        glowColor = lavaOrange.copy(
            alpha = (0.18 * frame.glowAmount * frame.shieldWakeAmount).toFloat(),
        ),
    )

    // iOS `guardianGradient`: [lavaOrange.opacity(0.82), lavaOrange], top to bottom,
    // drawn at `shieldWakeAmount` opacity.
    drawPath(
        path = shieldPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                lavaOrange.copy(alpha = 0.82f),
                lavaOrange,
            ),
        ),
        alpha = frame.shieldWakeAmount.toFloat(),
    )
}

internal fun DrawScope.drawGlowShadow(path: Path, glowColor: Color) {
    if (glowColor.alpha <= 0f) return

    // iOS shadow offset (0, 8) and blur radius 12, in points.
    val blurRadius = 12.dp.toPx()
    val offsetY = 8.dp.toPx()

    val androidPath = path.asAndroidPath()
    val blurPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor.toArgb()
        maskFilter = android.graphics.BlurMaskFilter(blurRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas
        val saveCount = nativeCanvas.save()
        nativeCanvas.translate(0f, offsetY)
        nativeCanvas.drawPath(androidPath, blurPaint)
        nativeCanvas.restoreToCount(saveCount)
    }
}

private fun DrawScope.drawFace(
    frame: GuardianMascotFrame,
    darkTheme: Boolean,
    minimumFeatureScale: Float,
) {
    val sizePx = min(size.width, size.height)
    if (sizePx <= 0f) return

    val faceColor = if (darkTheme) LavaColors.GuardianFaceLightDark else LavaColors.GuardianFaceLight

    val leftPose = guardianEyePose(GuardianEyeSide.LEFT, frame, sizePx, minimumFeatureScale)
    val rightPose = guardianEyePose(GuardianEyeSide.RIGHT, frame, sizePx, minimumFeatureScale)

    // iOS `morphedEyes`: an HStack(spacing:) centered on the face, shifted up by
    // -size * (0.06 + openAmount * 0.01 - concernAmount * 0.005). Every constant below
    // is verbatim from SoftShieldGuardian.swift.
    val openAmount = max(frame.leftEyeOpenAmount, frame.rightEyeOpenAmount)
    val happyAmount = clampUnit(frame.happyEyeAmount)
    val concernAmount = clampUnit(frame.concernAmount)
    val happyEyeLengthAmount = max(1.0 - openAmount, clampUnit(happyAmount / 0.85))
    val smileTransitionAmount = if (happyAmount > 0.0) clampUnit(openAmount + happyAmount) else openAmount
    val happySpacingCompensation = if (happyAmount > 0.0) happyEyeLengthAmount * 0.066 else 0.0
    val spacing = (sizePx * (0.34 + smileTransitionAmount * 0.09 - happySpacingCompensation - concernAmount * 0.04)).toFloat()
    val eyesCenterY = center.y - (sizePx * (0.06 + openAmount * 0.01 - concernAmount * 0.005)).toFloat()

    val totalWidth = leftPose.width + spacing + rightPose.width
    val leftEyeCenterX = center.x - totalWidth / 2f + leftPose.width / 2f
    val rightEyeCenterX = leftEyeCenterX + leftPose.width / 2f + spacing + rightPose.width / 2f

    drawEye(leftPose, leftEyeCenterX, eyesCenterY, faceColor)
    drawEye(rightPose, rightEyeCenterX, eyesCenterY, faceColor)

    // iOS mouth: stroked at max(3 * minimumFeatureScale, size * 0.038), framed
    // size * (0.48 + gratitudeAmount * 0.04) wide and size * 0.12 tall, offset
    // down by size * 0.11.
    val mouthLineWidth = max(3.dp.toPx() * minimumFeatureScale, sizePx * 0.038f)
    val mouthWidth = (sizePx * (0.48 + frame.gratitudeAmount * 0.04)).toFloat()
    val mouthHeight = sizePx * 0.12f
    val mouthCenter = Offset(center.x, center.y + sizePx * 0.11f)
    val mouthRect = Rect(
        left = mouthCenter.x - mouthWidth / 2f,
        top = mouthCenter.y - mouthHeight / 2f,
        right = mouthCenter.x + mouthWidth / 2f,
        bottom = mouthCenter.y + mouthHeight / 2f,
    )
    drawPath(
        path = guardianMouthPath(mouthRect, frame.mouthCurve),
        color = faceColor,
        style = Stroke(width = mouthLineWidth, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawEye(pose: GuardianEyePose, centerX: Float, centerY: Float, color: Color) {
    // iOS `.rotationEffect(pose.rotation)` rotates the eye view about its own center.
    rotate(degrees = pose.rotationDegrees, pivot = Offset(centerX, centerY)) {
        val eyeRect = Rect(
            left = centerX - pose.width / 2f,
            top = centerY - pose.height / 2f,
            right = centerX + pose.width / 2f,
            bottom = centerY + pose.height / 2f,
        )
        val stroke = morphingEyeStroke(eyeRect, pose.openAmount, pose.curveAmount)
        drawPath(
            path = stroke.path,
            color = color,
            style = Stroke(width = stroke.lineWidth, cap = StrokeCap.Round),
        )
    }
}

private enum class GuardianEyeSide { LEFT, RIGHT }

private data class GuardianEyePose(
    val openAmount: Double,
    val curveAmount: Double,
    val width: Float,
    val height: Float,
    val rotationDegrees: Float,
)

private data class MorphingEyeStroke(val path: Path, val lineWidth: Float)

/**
 * iOS `eyePose(for:frame:)` in SoftShieldGuardian.swift, ported EXACTLY —
 * every clamp, the per-side closed-tilt and concern-tilt signs, and the
 * compactFeatureScale clamp included.
 */
private fun guardianEyePose(
    side: GuardianEyeSide,
    frame: GuardianMascotFrame,
    sizePx: Float,
    minimumFeatureScale: Float,
): GuardianEyePose {
    val rawOpenAmount = if (side == GuardianEyeSide.LEFT) frame.leftEyeOpenAmount else frame.rightEyeOpenAmount
    val openAmount = clampUnit(rawOpenAmount)
    val sleepyAmount = clampUnit(frame.sleepyEyeAmount)
    val happyAmount = clampUnit(frame.happyEyeAmount)
    val concernAmount = clampUnit(frame.concernAmount)
    val winkAmount = if (side == GuardianEyeSide.RIGHT) clampUnit(frame.winkAmount) else 0.0
    val closedAmount = 1.0 - openAmount
    val happyLengthenAmount = clampUnit(happyAmount / 0.85)
    val happyBendAmount = clampUnit(happyAmount / 0.92)
    val sleepyCurveAmount = sleepyAmount * max(0.0, 1.0 - openAmount * 5.0)
    val winkCurveAmount = winkAmount * max(0.0, 1.0 - openAmount * 2.0) * 0.24
    val eyeLengthAmount = max(closedAmount, happyLengthenAmount)
    val compactFeatureScale = max(0.72, min(1.0, minimumFeatureScale.toDouble()))
    val width = sizePx * (0.074 + eyeLengthAmount * 0.066 + concernAmount * 0.014) * compactFeatureScale
    val height = sizePx * (0.064 + openAmount * 0.004 + happyAmount * 0.006 - concernAmount * 0.012) * compactFeatureScale
    val curveAmount = happyBendAmount - sleepyCurveAmount - winkCurveAmount
    val closedTilt = if (side == GuardianEyeSide.LEFT) 4.0 else -4.0
    val closedTiltRotation = closedTilt * (1.0 - openAmount) * (1.0 - happyAmount * 0.4)
    // iOS: raise the inner corners when concerned so the face reads as gentle,
    // help-seeking worry rather than a stern glare.
    val concernTilt = if (side == GuardianEyeSide.LEFT) -5.0 else 5.0
    val rotationDegrees = closedTiltRotation + concernTilt * concernAmount

    return GuardianEyePose(
        openAmount = openAmount,
        curveAmount = curveAmount,
        width = width.toFloat(),
        height = height.toFloat(),
        rotationDegrees = rotationDegrees.toFloat(),
    )
}

/**
 * iOS `MorphingGuardianEyeShape.path(in:)`: a single quad whose control point
 * and midline drop as the eye closes (openness -> 0) or bends (|curve| -> 1),
 * stroked round-cap at a width that shrinks from the full rect height (closed)
 * to half of it (open). Verbatim including the minimumSegment and progress
 * constants; the stroke is returned alongside the path because iOS strokes
 * inside the Shape, not on the canvas.
 */
private fun DrawScope.morphingEyeStroke(rect: Rect, openAmount: Double, curveAmount: Double): MorphingEyeStroke {
    val openness = clampUnit(openAmount).toFloat()
    val curve = min(max(curveAmount, -1.0), 1.0).toFloat()
    val bendAmount = abs(curve)
    val closedInfluence = max(1f - openness, bendAmount)
    val lineWidth = max(2.dp.toPx(), rect.height * interpolateFloat(1f, 0.5f, closedInfluence))
    val halfLineWidth = lineWidth / 2f
    val insetStartX = rect.left + halfLineWidth
    val insetEndX = rect.right - halfLineWidth
    val minimumSegment = max(0.5f, rect.width * 0.04f)
    val startX = min(insetStartX, rect.center.x - minimumSegment / 2f)
    val endX = max(insetEndX, rect.center.x + minimumSegment / 2f)
    val closedRestingProgress = if (curve > 0f) 0.32f + bendAmount * 0.30f else 0.32f
    val closedControlProgress = if (curve > 0f) 0.32f - bendAmount * 0.32f else 0.32f + bendAmount * 0.68f
    val restingProgress = interpolateFloat(0.5f, closedRestingProgress, closedInfluence)
    val controlProgress = interpolateFloat(0.5f, closedControlProgress, closedInfluence)
    val restingY = rect.top + rect.height * restingProgress
    val controlY = rect.top + rect.height * controlProgress

    val path = Path().apply {
        moveTo(startX, restingY)
        quadraticTo(rect.center.x, controlY, endX, restingY)
    }
    return MorphingEyeStroke(path = path, lineWidth = lineWidth)
}

/**
 * iOS `GuardianMouthShape.path(in:)`: a quad spanning the rect midline whose
 * control point drops by `height * curve * 0.48` below the midline.
 */
private fun guardianMouthPath(rect: Rect, curve: Double): Path {
    val restingY = rect.center.y
    val controlY = restingY + rect.height * curve.toFloat() * 0.48f
    return Path().apply {
        moveTo(rect.left, restingY)
        quadraticTo(rect.center.x, controlY, rect.right, restingY)
    }
}

/**
 * iOS `LavaGuardianPathMapper`: maps the 220 x 250 design space into the canvas,
 * letterboxed at its 220/250 aspect, then optional about-center `scaleX`/`scaleY`
 * (`LavaGuardianShieldShape(scaleX:scaleY:)`). scaleX/scaleY are 1 for the
 * original shield; the obsidian rim uses the [ObsidianShieldGeometry] inner scale.
 */
internal class GuardianDesignMapper(
    private val bounds: Size,
    private val scaleX: Float = 1f,
    private val scaleY: Float = 1f,
) {
    private val aspect = 220f / 250f
    private val width = min(bounds.width, bounds.height * aspect)
    private val height = width / aspect
    private val left = (bounds.width - width) / 2f
    private val top = (bounds.height - height) / 2f

    fun point(x: Float, y: Float): Offset {
        val rawX = left + x / 220f * width
        val rawY = top + y / 250f * height
        return Offset(
            x = bounds.width / 2f + (rawX - bounds.width / 2f) * scaleX,
            y = bounds.height / 2f + (rawY - bounds.height / 2f) * scaleY,
        )
    }
}

/**
 * iOS `LavaGuardianShieldShape.path(in:)` with the `LavaGuardianPathMapper`
 * math applied: a 220 x 250 design-space bezier (the wake-blend curve variant).
 */
internal fun guardianShieldPath(mapper: GuardianDesignMapper): Path {
    fun point(x: Float, y: Float) = mapper.point(x, y)

    return Path().apply {
        fun moveToPoint(x: Float, y: Float) {
            val p = point(x, y)
            moveTo(p.x, p.y)
        }

        fun cubicToPoints(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            x3: Float,
            y3: Float,
        ) {
            val c1 = point(x1, y1)
            val c2 = point(x2, y2)
            val end = point(x3, y3)
            cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
        }

        moveToPoint(110f, 8f)
        cubicToPoints(141f, 8f, 193f, 31f, 204f, 50f)
        cubicToPoints(216f, 72f, 209f, 166f, 200f, 187f)
        cubicToPoints(189f, 211f, 136f, 238f, 119f, 245f)
        cubicToPoints(113f, 248f, 107f, 248f, 101f, 245f)
        cubicToPoints(84f, 238f, 31f, 211f, 20f, 187f)
        cubicToPoints(11f, 166f, 4f, 72f, 16f, 50f)
        cubicToPoints(27f, 31f, 79f, 8f, 110f, 8f)
        close()
    }
}

/** iOS `clampUnit(_:)` in SoftShieldGuardian.swift: clamp into 0...1. */
private fun clampUnit(value: Double): Double = min(max(value, 0.0), 1.0)

/** iOS `interpolate(_:_:_:)` (CGFloat flavor), clamped like the Swift helper. */
private fun interpolateFloat(start: Float, end: Float, progress: Float): Float {
    val clampedProgress = min(max(progress, 0f), 1f)
    return start + (end - start) * clampedProgress
}
