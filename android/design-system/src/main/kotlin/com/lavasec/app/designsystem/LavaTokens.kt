package com.lavasec.app.designsystem

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Portable spacing values pinned by contracts/design-tokens/lava-design-tokens.json. */
object LavaSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 18.dp
    val screenHorizontal = 18.dp
    val screenTop = 16.dp
    val screenBottom = 96.dp
}

/**
 * Pinned slice-01 visual-contract metrics. The values are starting contract targets
 * (tuned after golden comparison, never scattered per screen); call sites reference
 * these names instead of literal dp so every surface shares the same geometry.
 *
 * iOS anchors: the 38-point leading badge, the compact grouped navigation row, and the
 * 18-point screen inset of `SettingsView.swift` / `RootView.swift`.
 */
object LavaNavigationCardMetrics {
    /** Minimum navigation-row height; iOS grouped rows target ~60pt, Android 64dp. */
    val rowMinHeight = 64.dp

    /** Leading icon badge container edge (iOS 38-point badge). */
    val badgeSize = 38.dp

    /** Vertical rhythm between navigation rows inside one section. */
    val rowSpacing = 14.dp

    /** Horizontal padding inside a navigation-row card. */
    val horizontalPadding = 16.dp

    /** Vertical rhythm between compact panel cards (filters, retention toggles). */
    val interCardSpacing = 10.dp
}

/** Slice-01 layout metrics shared by every root screen (screen, section, badge). */
object LavaMetrics {
    /** Horizontal screen inset; matches the iOS 18-point screen inset. */
    val screenInset = LavaSpacing.screenHorizontal

    /** Gap between a section label and its first card. */
    val sectionLabelToFirstCard = 16.dp

    /** Gap between whole sections on a screen. */
    val sectionSpacing = 24.dp

    /** Gap between a navigation row's leading badge and its text column. */
    val badgeToTextGap = 14.dp

    /**
     * Minimum visual clearance between scrolled content and the navigation surface.
     * Geometry acceptance asserts the last focusable row's bottom is at least this far
     * above the navigation surface's top at maximum scroll.
     */
    val navSurfaceClearance = 16.dp
}

/**
 * Shell-owned viewport facts delivered to every scrollable screen through
 * [LocalLavaContentInsets]. The shell derives the values from the Scaffold inner
 * padding and [androidx.compose.foundation.layout.WindowInsets.safeDrawing]; no screen
 * hardcodes a navigation-surface height, so gesture-nav and API-28 three-button insets
 * flow through instead of a screenshot-pixel constant.
 */
@Immutable
data class LavaContentInsets(
    val top: Dp,
    val bottom: Dp,
    val navigationSurfaceHeight: Dp,
) {
    /**
     * Bottom padding a scroll container must reserve: the full navigation-surface
     * height plus the pinned clearance, so the last row clears the surface by
     * [LavaMetrics.navSurfaceClearance] at maximum scroll. Consumed at the scroll
     * container itself, never on a single child card.
     */
    val scrollBottomPadding: Dp
        get() = navigationSurfaceHeight + LavaMetrics.navSurfaceClearance

    /** Content padding for scroll containers that take [PaddingValues] (LazyColumn). */
    fun scrollContentPadding(): PaddingValues = PaddingValues(
        start = LavaMetrics.screenInset,
        end = LavaMetrics.screenInset,
        top = LavaSpacing.screenTop,
        bottom = scrollBottomPadding,
    )

    companion object {
        /**
         * Standalone screens (previews, onboarding outside the shell, isolated tests)
         * see no navigation surface and keep only the pinned clearance.
         */
        val Zero = LavaContentInsets(top = 0.dp, bottom = 0.dp, navigationSurfaceHeight = 0.dp)
    }
}

/** Provided by the product shell; screens consume it at their scroll container. */
val LocalLavaContentInsets = staticCompositionLocalOf { LavaContentInsets.Zero }

/**
 * Screen content padding for `verticalScroll` containers: the 18dp screen inset,
 * the 16dp top inset, and the insets-derived bottom reservation. The order matters —
 * this modifier follows `verticalScroll` so the padding travels with the scrolled
 * content and guarantees the bottom clearance at maximum scroll.
 */
fun Modifier.lavaScreenScrollPadding(insets: LavaContentInsets): Modifier = padding(
    start = LavaMetrics.screenInset,
    end = LavaMetrics.screenInset,
    top = LavaSpacing.screenTop,
    bottom = insets.scrollBottomPadding,
)

/**
 * Product-screen typography roles. Call sites use a role instead of choosing a raw
 * Material role (`headlineLarge`, `bodyLarge`, ...) so the same visual contract
 * composes on Guard, Filters, Settings, and every child screen.
 */
object LavaScreenTypography {
    /** Screen title: display role at the current Material scale, semi-bold. */
    val screenTitle: TextStyle
        @Composable get() = MaterialTheme.typography.headlineLarge.copy(
            fontWeight = FontWeight.SemiBold,
        )

    /** Section label: compact heading role (LavaSection applies the heading semantic). */
    val sectionHeading: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
        )

    /** Navigation-row title inside a LavaNavigationCard. */
    val rowTitle: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        )

    /** Navigation-row one-line summary. */
    val rowSummary: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium

    /** Screen footer (version/build line); never a heading. */
    val screenFooter: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium
}

object LavaRadii {
    val card = 20.dp
    val compact = 16.dp
    val selection = 12.dp
    val control = 12.dp
    val pill = 14.dp
    val iconBadge = 10.dp
}

object LavaIconSizes {
    val badge = 10.dp
    val inline = 13.dp
    val small = 16.dp
    val control = 17.dp
    val endpointCompact = 25.dp
    val endpoint = 30.dp
    val node = 40.dp
    val hero = 44.dp
    val heroResult = 56.dp
}

object LavaTypography {
    /** Fixed shared display role; body and label text stays on Material semantic typography. */
    val metricNumeral = TextStyle(
        fontFamily = FontFamily(
            Font(DeviceFontFamilyName("sans-serif-rounded"), FontWeight.Bold),
            Font(DeviceFontFamilyName("sans-serif"), FontWeight.Bold),
        ),
        fontSize = 42.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    )
}

/** Rounded sRGB resolutions of the pinned iOS source values. */
object LavaColors {
    val CardBackground = Color(0xFFFFFFFF)
    val CardBackgroundDark = Color(0xFF2B2B2E)
    val Cream = Color(0xFFFFFAF0)
    val CreamDark = Color(0xFF1C1A17)
    val Danger = Color(0xFFDB332E)
    val DangerDark = Color(0xFFFF7366)
    val GroupedBackground = Color(0xFFF5FAF5)
    val GroupedBackgroundDark = Color(0xFF0A120F)
    val GuardianFaceLight = Color(0xFFFFFAED)
    val GuardianFaceLightDark = Color(0xFFF0FAF2)
    val GuardianSleepGray = Color(0xFFABB5B0)
    val GuardianSleepGrayDark = Color(0xFF5C6661)
    val Ink = Color(0xFF213B33)
    val InkDark = Color(0xFFEBF5ED)
    val LavaOrange = Color(0xFFF2572E)
    val LavaOrangeDark = Color(0xFFFF8A57)
    val LavaOrangeSoft = Color(0xFFFFEBDB)
    val LavaOrangeSoftDark = Color(0xFF4D2114)
    val PanelActionFill = Color(0xFFD1EDDE)
    val PanelActionFillDark = Color(0xFF1F4A36)
    val PanelActionGreen = Color(0xFF1F6647)
    val PanelActionGreenDark = Color(0xFF73DBA1)
    val PanelActionPressedFill = Color(0xFFBFE0CF)
    val PanelActionPressedFillDark = Color(0xFF265940)
    val PanelBackground = Color(0xFFFAFFFA)
    val PanelBackgroundDark = Color(0xFF030D09)
    val PanelStroke = Color(0xFFB8DBC2)
    val PanelStrokeDark = Color(0xFF29523D)
    val QuietControl = Color(0xFF61756B)
    val QuietControlDark = Color(0xFF384D42)
    val SafeControlGreen = Color(0xFF297857)
    val SafeControlGreenDark = Color(0xFF218052)
    val SafeGreen = Color(0xFF297857)
    val SafeGreenDark = Color(0xFF73DBA1)
    val SoftGreen = Color(0xFFE8F7F0)
    val SoftGreenDark = Color(0xFF1A382B)

    /** iOS source additions intentionally kept outside the canonical JSON artifact. */
    val LavaOrangeText = Color(0xFFBF4017)
    val LavaOrangeTextDark = Color(0xFFFF8A57)
    val LavaOrangeSelectedFill = Color(0xFFC7471F)
    val LavaOrangeSelectedFillDark = Color(0xFFAD4726)
}

enum class LavaAccentRole { SAFE, ATTENTION, INK }

enum class LavaTier(
    val accent: LavaAccentRole,
    val allowsDelightMotion: Boolean,
    val usesMonospacedMetadata: Boolean,
) {
    CALM(LavaAccentRole.SAFE, false, false),
    CELEBRATORY(LavaAccentRole.ATTENTION, true, false),
    TECHNICAL(LavaAccentRole.INK, false, true),
}

/** Tests and accessibility settings can opt into motion-free transitions at the theme root. */
val LocalLavaReducedMotion = staticCompositionLocalOf { false }
