package com.lavasec.app.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import org.junit.Assert.assertEquals
import org.junit.Test

class LavaThemeSurfaceTest {
    @Test
    fun `light Material surface family resolves only to Lava roles`() {
        with(LavaLightColorScheme) {
            assertEquals(LavaColors.PanelStroke, surfaceDim)
            assertEquals(LavaColors.CardBackground, surfaceBright)
            assertEquals(LavaColors.CardBackground, surfaceContainerLowest)
            assertEquals(LavaColors.PanelBackground, surfaceContainerLow)
            assertEquals(LavaColors.CardBackground, surfaceContainer)
            assertEquals(LavaColors.SoftGreen, surfaceContainerHigh)
            assertEquals(LavaColors.PanelActionFill, surfaceContainerHighest)
            assertEquals(LavaColors.Ink, inverseSurface)
            assertEquals(LavaColors.CardBackground, inverseOnSurface)
            assertEquals(LavaColors.SafeGreenDark, inversePrimary)
            assertEquals(LavaColors.GuardianSleepGrayDark, onSurfaceVariant)
            assertEquals(LavaColors.QuietControl, outline)
            assertEquals(LavaColors.PanelStroke, outlineVariant)
        }
    }

    @Test
    fun `dark Material surface family resolves only to Lava roles`() {
        with(LavaDarkColorScheme) {
            assertEquals(LavaColors.PanelBackgroundDark, surfaceDim)
            assertEquals(LavaColors.QuietControlDark, surfaceBright)
            assertEquals(LavaColors.PanelBackgroundDark, surfaceContainerLowest)
            assertEquals(LavaColors.GroupedBackgroundDark, surfaceContainerLow)
            assertEquals(LavaColors.CardBackgroundDark, surfaceContainer)
            assertEquals(LavaColors.PanelActionFillDark, surfaceContainerHigh)
            assertEquals(LavaColors.QuietControlDark, surfaceContainerHighest)
            assertEquals(LavaColors.InkDark, inverseSurface)
            assertEquals(LavaColors.GroupedBackgroundDark, inverseOnSurface)
            assertEquals(LavaColors.SafeGreen, inversePrimary)
            assertEquals(LavaColors.GuardianSleepGray, onSurfaceVariant)
            assertEquals(LavaColors.GuardianSleepGray, outline)
            assertEquals(LavaColors.PanelStrokeDark, outlineVariant)
        }
    }

    @Test
    fun `fixed Material accent families resolve only to reviewed Lava roles`() {
        listOf(LavaLightColorScheme, LavaDarkColorScheme).forEach { scheme ->
            with(scheme) {
                assertEquals(LavaColors.SoftGreen, primaryFixed)
                assertEquals(LavaColors.PanelActionFill, primaryFixedDim)
                assertEquals(LavaColors.Ink, onPrimaryFixed)
                assertEquals(LavaColors.Ink, onPrimaryFixedVariant)
                assertEquals(LavaColors.LavaOrangeSoft, secondaryFixed)
                assertEquals(LavaColors.LavaOrangeSoft, secondaryFixedDim)
                assertEquals(LavaColors.LavaOrangeText, onSecondaryFixed)
                assertEquals(LavaColors.LavaOrangeText, onSecondaryFixedVariant)
                assertEquals(LavaColors.PanelActionFill, tertiaryFixed)
                assertEquals(LavaColors.SoftGreen, tertiaryFixedDim)
                assertEquals(LavaColors.Ink, onTertiaryFixed)
                assertEquals(LavaColors.Ink, onTertiaryFixedVariant)
            }
        }
    }

    @Test
    fun `all Material shape roles resolve to pinned Lava radii`() {
        with(LavaShapes) {
            assertEquals(RoundedCornerShape(LavaRadii.iconBadge), extraSmall)
            assertEquals(RoundedCornerShape(LavaRadii.control), small)
            assertEquals(RoundedCornerShape(LavaRadii.compact), medium)
            assertEquals(RoundedCornerShape(LavaRadii.card), large)
            assertEquals(RoundedCornerShape(LavaRadii.card), extraLarge)
        }
    }
}
