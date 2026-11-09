package com.lavasec.app.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LavaThemeContrastTest {
    @Test
    fun `semantic orange text and selected fills meet normal-text contrast`() {
        listOf(LavaLightSemanticColors, LavaDarkSemanticColors).forEach { colors ->
            assertContrast(colors.attentionText, colors.attentionBackground, 4.5)
            assertContrast(colors.attentionSelectedFill, Color.White, 4.5)
        }
    }

    @Test
    fun `all exposed static Material container pairs meet normal-text contrast`() {
        listOf(LavaLightColorScheme, LavaDarkColorScheme).forEach { scheme ->
            materialPairs(scheme).forEach { (foreground, background) ->
                assertContrast(foreground, background, 4.5)
            }
        }
    }

    @Test
    fun `native tertiary text resolution remains readable on application surfaces`() {
        listOf(LavaLightColorScheme, LavaDarkColorScheme).forEach { scheme ->
            assertNotEquals(scheme.onSurface, scheme.onSurfaceVariant)
            assertContrast(scheme.onSurfaceVariant, scheme.surface, 4.5)
            assertContrast(scheme.onSurfaceVariant, scheme.surfaceContainer, 4.5)
        }
    }

    @Test
    fun `semantic safety colors do not depend on dynamic Material accents`() {
        assertTrue(LavaLightSemanticColors.safe != LavaLightSemanticColors.attentionText)
        assertTrue(LavaDarkSemanticColors.safe != LavaDarkSemanticColors.attentionText)
        assertTrue(LavaLightSemanticColors.danger != LavaLightColorScheme.primary)
        assertTrue(LavaDarkSemanticColors.danger != LavaDarkColorScheme.primary)
    }

    private fun materialPairs(scheme: ColorScheme): List<Pair<Color, Color>> = listOf(
        scheme.onPrimary to scheme.primary,
        scheme.onPrimaryContainer to scheme.primaryContainer,
        scheme.onSecondary to scheme.secondary,
        scheme.onSecondaryContainer to scheme.secondaryContainer,
        scheme.onTertiary to scheme.tertiary,
        scheme.onTertiaryContainer to scheme.tertiaryContainer,
        scheme.onPrimaryFixed to scheme.primaryFixed,
        scheme.onPrimaryFixedVariant to scheme.primaryFixedDim,
        scheme.onSecondaryFixed to scheme.secondaryFixed,
        scheme.onSecondaryFixedVariant to scheme.secondaryFixedDim,
        scheme.onTertiaryFixed to scheme.tertiaryFixed,
        scheme.onTertiaryFixedVariant to scheme.tertiaryFixedDim,
        scheme.onBackground to scheme.background,
        scheme.onSurface to scheme.surface,
        scheme.onSurface to scheme.surfaceDim,
        scheme.onSurface to scheme.surfaceBright,
        scheme.onSurface to scheme.surfaceContainerLowest,
        scheme.onSurface to scheme.surfaceContainerLow,
        scheme.onSurface to scheme.surfaceContainer,
        scheme.onSurface to scheme.surfaceContainerHigh,
        scheme.onSurface to scheme.surfaceContainerHighest,
        scheme.onSurfaceVariant to scheme.surfaceVariant,
        scheme.onError to scheme.error,
        scheme.onErrorContainer to scheme.errorContainer,
        scheme.inverseOnSurface to scheme.inverseSurface,
        scheme.inversePrimary to scheme.inverseSurface,
    )

    @Test
    fun `active Material outlines meet non-text contrast on application surfaces`() {
        listOf(LavaLightColorScheme, LavaDarkColorScheme).forEach { scheme ->
            assertContrast(scheme.outline, scheme.background, 3.0)
            assertContrast(scheme.outline, scheme.surfaceContainer, 3.0)
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Double) {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        assertTrue("contrast ${(lighter + 0.05) / (darker + 0.05)} < $minimum", (lighter + 0.05) / (darker + 0.05) >= minimum)
    }

    private fun luminance(color: Color): Double {
        fun linear(component: Float): Double =
            if (component <= 0.04045f) component / 12.92 else Math.pow((component + 0.055) / 1.055, 2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
