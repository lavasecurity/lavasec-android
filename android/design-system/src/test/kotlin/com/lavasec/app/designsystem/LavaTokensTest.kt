package com.lavasec.app.designsystem

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LavaTokensTest {
    @Test
    fun `spacing scale matches the pinned portable contract`() {
        assertEquals(4.dp, LavaSpacing.xs)
        assertEquals(8.dp, LavaSpacing.sm)
        assertEquals(12.dp, LavaSpacing.md)
        assertEquals(16.dp, LavaSpacing.lg)
        assertEquals(18.dp, LavaSpacing.xl)
        assertEquals(18.dp, LavaSpacing.screenHorizontal)
        assertEquals(16.dp, LavaSpacing.screenTop)
        assertEquals(96.dp, LavaSpacing.screenBottom)
    }

    @Test
    fun `surface radii match the pinned portable contract`() {
        assertEquals(20.dp, LavaRadii.card)
        assertEquals(16.dp, LavaRadii.compact)
        assertEquals(12.dp, LavaRadii.selection)
        assertEquals(12.dp, LavaRadii.control)
        assertEquals(14.dp, LavaRadii.pill)
        assertEquals(10.dp, LavaRadii.iconBadge)
    }

    @Test
    fun `navigation card metrics match the pinned slice 01 visual contract`() {
        assertEquals(64.dp, LavaNavigationCardMetrics.rowMinHeight)
        assertEquals(38.dp, LavaNavigationCardMetrics.badgeSize)
        assertEquals(14.dp, LavaNavigationCardMetrics.rowSpacing)
        assertEquals(16.dp, LavaNavigationCardMetrics.horizontalPadding)
        assertEquals(10.dp, LavaNavigationCardMetrics.interCardSpacing)
    }

    @Test
    fun `screen metrics match the pinned slice 01 visual contract`() {
        assertEquals(18.dp, LavaMetrics.screenInset)
        assertEquals(16.dp, LavaMetrics.sectionLabelToFirstCard)
        assertEquals(24.dp, LavaMetrics.sectionSpacing)
        assertEquals(14.dp, LavaMetrics.badgeToTextGap)
        assertEquals(16.dp, LavaMetrics.navSurfaceClearance)
    }

    @Test
    fun `zero content insets keep only the pinned clearance as scroll bottom padding`() {
        assertEquals(16.dp, LavaContentInsets.Zero.scrollBottomPadding)
        assertEquals(
            64.dp + 16.dp,
            LavaContentInsets(
                top = 24.dp,
                bottom = 64.dp,
                navigationSurfaceHeight = 64.dp,
            ).scrollBottomPadding,
        )
    }

    @Test
    fun `metric numeral keeps the shared 42sp bold tabular role`() {
        assertEquals(42.sp, LavaTypography.metricNumeral.fontSize)
        assertEquals(FontWeight.Bold, LavaTypography.metricNumeral.fontWeight)
        assertTrue(
            LavaTypography.metricNumeral.fontFamily.toString().contains("sans-serif-rounded"),
        )
        assertTrue(LavaTypography.metricNumeral.fontFamily.toString().contains("sans-serif"))
        assertTrue(LavaTypography.metricNumeral.fontFeatureSettings.orEmpty().contains("tnum"))
    }
}
