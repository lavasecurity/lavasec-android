package com.lavasec.app.presentation.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import com.lavasec.app.designsystem.LavaColors
import com.lavasec.app.foundation.preferences.GuardianLook

/**
 * Draws the faceted "obsidian" Guardian shield for every alternate look.
 *
 * Faithful port of `Shared/SoftShieldGuardian.swift`:
 * `SoftShieldGuardianContent.obsidianShieldBody` / `ObsidianShieldBody` /
 * `ObsidianShieldLayer` / `ObsidianOuterShell` / `ObsidianShieldPalette` /
 * `ObsidianShieldColorway` / `ObsidianSleepingPalette` / `LavaGuardianColorStop` /
 * `LavaGuardianShellFacet` / `LavaGuardianShieldRimShape`.
 *
 * The iOS ZStack is inner-gradient shield, then `ObsidianOuterShell` (a full-rect
 * shell gradient plus three facet polygons) masked to the even-odd rim between the
 * outer shield and the shield scaled by [ObsidianShieldGeometry.innerShieldScale].
 * The whole body carries the `.shadow(...)` glow (before `scaleEffect`).
 */
internal fun DrawScope.drawObsidianShield(
    frame: GuardianMascotFrame,
    darkTheme: Boolean,
    look: GuardianLook,
) {
    if (look == GuardianLook.ORIGINAL) return

    val wakeAmount = frame.shieldWakeAmount.toFloat()
    val palette = obsidianPalette(look = look, wakeAmount = wakeAmount, darkTheme = darkTheme)
    val mapper = GuardianDesignMapper(bounds = size)
    val shield = guardianShieldPath(mapper)
    val innerShield = guardianShieldPath(
        GuardianDesignMapper(bounds = size, scaleX = ObsidianShieldGeometry.innerShieldScale, scaleY = ObsidianShieldGeometry.innerShieldScale),
    )

    // iOS attaches `.shadow(...)` to the whole ObsidianShieldBody, so the halo
    // composites behind the shield. Unlike the original shield the source body is
    // always opaque, so the glow alpha carries only glowAmount (no wake factor).
    drawGlowShadow(
        path = shield,
        glowColor = palette.glow.copy(alpha = 0.18f * frame.glowAmount.toFloat()),
    )

    val gradientStart = Offset.Zero
    val gradientEnd = Offset(x = size.width, y = size.height)

    // Inner shield fill (`palette.innerGradient`, topLeading -> bottomTrailing).
    drawPath(
        path = shield,
        brush = Brush.linearGradient(
            colors = palette.innerGradient,
            start = gradientStart,
            end = gradientEnd,
        ),
    )

    // Rim band: outer shield minus the 0.91-scaled inner shield (even-odd).
    val rim = Path().apply {
        fillType = PathFillType.EvenOdd
        addPath(shield)
        addPath(innerShield)
    }
    drawPath(
        path = rim,
        brush = Brush.linearGradient(
            colors = palette.shellGradient,
            start = gradientStart,
            end = gradientEnd,
        ),
    )

    // Facets are masked to the rim; clipped so their polygon corners, which extend
    // beyond the shield edge, never leak outside the shell band.
    clipPath(rim) {
        drawPath(guardianShellFacetPath(mapper, GuardianShellFacetKind.RIGHT), color = palette.rightFacet)
        drawPath(guardianShellFacetPath(mapper, GuardianShellFacetKind.LOWER_RIGHT), color = palette.lowerRightFacet)
        drawPath(guardianShellFacetPath(mapper, GuardianShellFacetKind.WARM_SIDE), color = palette.warmSideFacet)
    }
}

/** iOS `ObsidianShieldGeometry`. */
internal object ObsidianShieldGeometry {
    const val innerShieldScale = 0.91f
}

/** iOS `LavaGuardianShellFacet.Kind`. */
internal enum class GuardianShellFacetKind { RIGHT, LOWER_RIGHT, WARM_SIDE }

/** iOS `LavaGuardianShellFacet.path(in:)`: three straight-edged facet polygons. */
internal fun guardianShellFacetPath(mapper: GuardianDesignMapper, kind: GuardianShellFacetKind): Path =
    Path().apply {
        fun move(x: Float, y: Float) {
            val p = mapper.point(x, y)
            moveTo(p.x, p.y)
        }

        fun line(x: Float, y: Float) {
            val p = mapper.point(x, y)
            lineTo(p.x, p.y)
        }

        when (kind) {
            GuardianShellFacetKind.RIGHT -> {
                move(147f, 0f)
                line(214f, 43f)
                line(208f, 158f)
                line(155f, 120f)
            }
            GuardianShellFacetKind.LOWER_RIGHT -> {
                move(157f, 118f)
                line(208f, 158f)
                line(193f, 205f)
                line(128f, 236f)
            }
            GuardianShellFacetKind.WARM_SIDE -> {
                move(147f, 2f)
                line(198f, 43f)
                line(156f, 119f)
                line(122f, 17f)
            }
        }
        close()
    }

/**
 * iOS `LavaGuardianColorStop`: an sRGB stop (components in 0...1) blended from its
 * sleeping value toward the awake colorway by `wakeAmount`.
 */
private class GuardianColorStop(
    val red: Float,
    val green: Float,
    val blue: Float,
    val opacity: Float = 1f,
) {
    fun blendTo(awake: GuardianColorStop, amount: Float): Color {
        val a = amount.coerceIn(0f, 1f)
        return Color(
            red = red + (awake.red - red) * a,
            green = green + (awake.green - green) * a,
            blue = blue + (awake.blue - blue) * a,
            alpha = opacity + (awake.opacity - opacity) * a,
        )
    }
}

/** A fully resolved obsidian palette for one look at one wake amount. */
private class ObsidianPalette(
    val innerGradient: List<Color>,
    val shellGradient: List<Color>,
    val rightFacet: Color,
    val lowerRightFacet: Color,
    val warmSideFacet: Color,
    val glow: Color,
)

/** iOS `ObsidianSleepingPalette`: the shared desaturated sleeping stops. */
private object ObsidianSleepingPalette {
    val innerTop = GuardianColorStop(0.73f, 0.76f, 0.74f)
    val innerMid = GuardianColorStop(0.56f, 0.60f, 0.58f)
    val innerBottom = GuardianColorStop(0.42f, 0.46f, 0.44f)
    val shellTop = GuardianColorStop(0.76f, 0.79f, 0.77f)
    val shellMid = GuardianColorStop(0.58f, 0.62f, 0.60f)
    val shellDeep = GuardianColorStop(0.30f, 0.33f, 0.31f)
    val shellBottom = GuardianColorStop(0.12f, 0.14f, 0.13f)
    val rightFacet = GuardianColorStop(0.28f, 0.31f, 0.30f, 0.82f)
    val lowerRightFacet = GuardianColorStop(0.10f, 0.12f, 0.11f, 0.62f)
    val warmSideFacet = GuardianColorStop(0.47f, 0.50f, 0.48f, 0.72f)
}

/** The awake stops for one colorway (`ObsidianShieldColorway`). */
private class ObsidianAwakeColorway(
    val innerTop: GuardianColorStop,
    val innerMid: GuardianColorStop,
    val innerBottom: GuardianColorStop,
    val shellTop: GuardianColorStop,
    val shellMid: GuardianColorStop,
    val shellDeep: GuardianColorStop,
    val shellBottom: GuardianColorStop,
    val rightFacet: GuardianColorStop,
    val lowerRightFacet: GuardianColorStop,
    val warmSideFacet: GuardianColorStop,
)

private fun obsidianPalette(look: GuardianLook, wakeAmount: Float, darkTheme: Boolean): ObsidianPalette {
    val awake = awakeColorway(look)
    val sleeping = ObsidianSleepingPalette
    fun blended(sleepingStop: GuardianColorStop, awakeStop: GuardianColorStop): Color =
        sleepingStop.blendTo(awakeStop, wakeAmount)

    return ObsidianPalette(
        innerGradient = listOf(
            blended(sleeping.innerTop, awake.innerTop),
            blended(sleeping.innerMid, awake.innerMid),
            blended(sleeping.innerBottom, awake.innerBottom),
        ),
        shellGradient = listOf(
            blended(sleeping.shellTop, awake.shellTop),
            blended(sleeping.shellMid, awake.shellMid),
            blended(sleeping.shellDeep, awake.shellDeep),
            blended(sleeping.shellBottom, awake.shellBottom),
        ),
        rightFacet = blended(sleeping.rightFacet, awake.rightFacet),
        lowerRightFacet = blended(sleeping.lowerRightFacet, awake.lowerRightFacet),
        warmSideFacet = blended(sleeping.warmSideFacet, awake.warmSideFacet),
        glow = obsidianGlowColor(look, darkTheme),
    )
}

/**
 * iOS `ObsidianShieldColorway.init(style:)`. `original` never reaches the obsidian
 * renderer; it maps onto the ember (fire opal) colorway exactly as iOS does.
 */
private fun awakeColorway(look: GuardianLook): ObsidianAwakeColorway = when (look) {
    GuardianLook.ORIGINAL,
    GuardianLook.FIRE_OPAL,
    -> ObsidianAwakeColorway(
        innerTop = GuardianColorStop(1.00f, 0.60f, 0.40f),
        innerMid = GuardianColorStop(1.00f, 0.35f, 0.22f),
        innerBottom = GuardianColorStop(0.85f, 0.27f, 0.18f),
        shellTop = GuardianColorStop(1.00f, 0.56f, 0.38f),
        shellMid = GuardianColorStop(1.00f, 0.35f, 0.22f),
        shellDeep = GuardianColorStop(0.29f, 0.15f, 0.13f),
        shellBottom = GuardianColorStop(0.05f, 0.035f, 0.03f),
        rightFacet = GuardianColorStop(0.23f, 0.14f, 0.12f, 0.82f),
        lowerRightFacet = GuardianColorStop(0.05f, 0.035f, 0.03f, 0.62f),
        warmSideFacet = GuardianColorStop(0.72f, 0.28f, 0.19f, 0.72f),
    )
    GuardianLook.PURPLE_OBSIDIAN -> ObsidianAwakeColorway(
        innerTop = GuardianColorStop(0.82f, 0.68f, 1.00f),
        innerMid = GuardianColorStop(0.56f, 0.34f, 0.88f),
        innerBottom = GuardianColorStop(0.35f, 0.20f, 0.58f),
        shellTop = GuardianColorStop(0.78f, 0.62f, 1.00f),
        shellMid = GuardianColorStop(0.49f, 0.28f, 0.78f),
        shellDeep = GuardianColorStop(0.18f, 0.11f, 0.27f),
        shellBottom = GuardianColorStop(0.045f, 0.035f, 0.06f),
        rightFacet = GuardianColorStop(0.16f, 0.10f, 0.23f, 0.82f),
        lowerRightFacet = GuardianColorStop(0.035f, 0.025f, 0.055f, 0.62f),
        warmSideFacet = GuardianColorStop(0.55f, 0.34f, 0.82f, 0.72f),
    )
    GuardianLook.OBSIDIAN -> ObsidianAwakeColorway(
        innerTop = GuardianColorStop(0.70f, 0.73f, 0.72f),
        innerMid = GuardianColorStop(0.43f, 0.46f, 0.45f),
        innerBottom = GuardianColorStop(0.24f, 0.26f, 0.25f),
        shellTop = GuardianColorStop(0.62f, 0.66f, 0.65f),
        shellMid = GuardianColorStop(0.38f, 0.41f, 0.40f),
        shellDeep = GuardianColorStop(0.17f, 0.18f, 0.18f),
        shellBottom = GuardianColorStop(0.055f, 0.06f, 0.06f),
        rightFacet = GuardianColorStop(0.18f, 0.20f, 0.20f, 0.82f),
        lowerRightFacet = GuardianColorStop(0.055f, 0.06f, 0.06f, 0.62f),
        warmSideFacet = GuardianColorStop(0.50f, 0.54f, 0.53f, 0.72f),
    )
    GuardianLook.CHERRY_QUARTZ -> ObsidianAwakeColorway(
        innerTop = GuardianColorStop(1.00f, 0.84f, 0.92f),
        innerMid = GuardianColorStop(1.00f, 0.62f, 0.80f),
        innerBottom = GuardianColorStop(0.86f, 0.32f, 0.56f),
        shellTop = GuardianColorStop(1.00f, 0.78f, 0.90f),
        shellMid = GuardianColorStop(0.96f, 0.46f, 0.70f),
        shellDeep = GuardianColorStop(0.28f, 0.06f, 0.16f),
        shellBottom = GuardianColorStop(0.06f, 0.025f, 0.055f),
        rightFacet = GuardianColorStop(0.22f, 0.06f, 0.14f, 0.82f),
        lowerRightFacet = GuardianColorStop(0.06f, 0.025f, 0.055f, 0.62f),
        warmSideFacet = GuardianColorStop(0.98f, 0.42f, 0.66f, 0.72f),
    )
    GuardianLook.EMERALD -> ObsidianAwakeColorway(
        innerTop = GuardianColorStop(0.45f, 0.86f, 0.63f),
        innerMid = GuardianColorStop(0.24f, 0.61f, 0.41f),
        innerBottom = GuardianColorStop(0.16f, 0.47f, 0.34f),
        shellTop = GuardianColorStop(0.55f, 0.96f, 0.72f),
        shellMid = GuardianColorStop(0.20f, 0.56f, 0.38f),
        shellDeep = GuardianColorStop(0.04f, 0.18f, 0.11f),
        shellBottom = GuardianColorStop(0.02f, 0.06f, 0.04f),
        rightFacet = GuardianColorStop(0.03f, 0.16f, 0.09f, 0.82f),
        lowerRightFacet = GuardianColorStop(0.02f, 0.06f, 0.04f, 0.62f),
        warmSideFacet = GuardianColorStop(0.24f, 0.66f, 0.43f, 0.72f),
    )
    GuardianLook.KIWI_CREME -> ObsidianAwakeColorway(
        innerTop = GuardianColorStop(1.00f, 0.98f, 0.91f),
        innerMid = GuardianColorStop(0.91f, 0.84f, 0.72f),
        innerBottom = GuardianColorStop(0.68f, 0.60f, 0.51f),
        shellTop = GuardianColorStop(0.99f, 0.94f, 0.84f),
        shellMid = GuardianColorStop(0.66f, 0.58f, 0.49f),
        shellDeep = GuardianColorStop(0.25f, 0.22f, 0.19f),
        shellBottom = GuardianColorStop(0.08f, 0.07f, 0.06f),
        rightFacet = GuardianColorStop(0.56f, 0.50f, 0.43f, 0.82f),
        lowerRightFacet = GuardianColorStop(0.22f, 0.19f, 0.16f, 0.62f),
        warmSideFacet = GuardianColorStop(0.82f, 0.75f, 0.64f, 0.72f),
    )
    GuardianLook.AQUAMARINE -> ObsidianAwakeColorway(
        innerTop = GuardianColorStop(138f / 255f, 221f / 255f, 229f / 255f),
        innerMid = GuardianColorStop(75f / 255f, 168f / 255f, 187f / 255f),
        innerBottom = GuardianColorStop(43f / 255f, 129f / 255f, 150f / 255f),
        shellTop = GuardianColorStop(211f / 255f, 246f / 255f, 247f / 255f),
        shellMid = GuardianColorStop(138f / 255f, 221f / 255f, 229f / 255f),
        shellDeep = GuardianColorStop(43f / 255f, 129f / 255f, 150f / 255f),
        shellBottom = GuardianColorStop(0.035f, 0.20f, 0.25f),
        rightFacet = GuardianColorStop(0.09f, 0.37f, 0.43f, 0.82f),
        lowerRightFacet = GuardianColorStop(0.035f, 0.20f, 0.25f, 0.62f),
        warmSideFacet = GuardianColorStop(0.30f, 0.68f, 0.74f, 0.72f),
    )
}

/**
 * iOS `obsidianGlowColor`. `kiwiCreme` and the `original`/`fireOpal` fallback are
 * the adaptive iOS style tokens; the rest are fixed sRGB values.
 */
private fun obsidianGlowColor(look: GuardianLook, darkTheme: Boolean): Color = when (look) {
    GuardianLook.PURPLE_OBSIDIAN -> Color(0.62f, 0.38f, 0.95f)
    GuardianLook.OBSIDIAN -> Color(0.50f, 0.55f, 0.54f)
    GuardianLook.CHERRY_QUARTZ -> Color(1.00f, 0.58f, 0.78f)
    GuardianLook.EMERALD -> Color(0.16f, 0.47f, 0.34f)
    GuardianLook.KIWI_CREME -> if (darkTheme) Color(1.00f, 0.94f, 0.84f) else Color(0.46f, 0.39f, 0.32f)
    GuardianLook.AQUAMARINE -> Color(138f / 255f, 221f / 255f, 229f / 255f)
    GuardianLook.ORIGINAL,
    GuardianLook.FIRE_OPAL,
    -> if (darkTheme) LavaColors.LavaOrangeDark else LavaColors.LavaOrange
}
