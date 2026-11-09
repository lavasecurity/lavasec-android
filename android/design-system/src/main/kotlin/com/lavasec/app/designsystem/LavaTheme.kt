package com.lavasec.app.designsystem

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal val LavaLightColorScheme = lightColorScheme(
    primary = LavaColors.SafeGreen,
    onPrimary = Color.White,
    primaryContainer = LavaColors.SoftGreen,
    onPrimaryContainer = LavaColors.Ink,
    secondary = LavaColors.LavaOrangeSelectedFill,
    onSecondary = Color.White,
    secondaryContainer = LavaColors.LavaOrangeSoft,
    onSecondaryContainer = LavaColors.LavaOrangeText,
    tertiary = LavaColors.PanelActionGreen,
    onTertiary = Color.White,
    tertiaryContainer = LavaColors.PanelActionFill,
    onTertiaryContainer = LavaColors.Ink,
    background = LavaColors.GroupedBackground,
    onBackground = LavaColors.Ink,
    surface = LavaColors.GroupedBackground,
    onSurface = LavaColors.Ink,
    surfaceDim = LavaColors.PanelStroke,
    surfaceBright = LavaColors.CardBackground,
    surfaceContainerLowest = LavaColors.CardBackground,
    surfaceContainerLow = LavaColors.PanelBackground,
    surfaceContainer = LavaColors.CardBackground,
    surfaceContainerHigh = LavaColors.SoftGreen,
    surfaceContainerHighest = LavaColors.PanelActionFill,
    surfaceVariant = LavaColors.SoftGreen,
    onSurfaceVariant = LavaColors.GuardianSleepGrayDark,
    inverseSurface = LavaColors.Ink,
    inverseOnSurface = LavaColors.CardBackground,
    inversePrimary = LavaColors.SafeGreenDark,
    outline = LavaColors.QuietControl,
    outlineVariant = LavaColors.PanelStroke,
    primaryFixed = LavaColors.SoftGreen,
    primaryFixedDim = LavaColors.PanelActionFill,
    onPrimaryFixed = LavaColors.Ink,
    onPrimaryFixedVariant = LavaColors.Ink,
    secondaryFixed = LavaColors.LavaOrangeSoft,
    secondaryFixedDim = LavaColors.LavaOrangeSoft,
    onSecondaryFixed = LavaColors.LavaOrangeText,
    onSecondaryFixedVariant = LavaColors.LavaOrangeText,
    tertiaryFixed = LavaColors.PanelActionFill,
    tertiaryFixedDim = LavaColors.SoftGreen,
    onTertiaryFixed = LavaColors.Ink,
    onTertiaryFixedVariant = LavaColors.Ink,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

internal val LavaDarkColorScheme = darkColorScheme(
    primary = LavaColors.SafeGreenDark,
    onPrimary = Color(0xFF073821),
    primaryContainer = LavaColors.SoftGreenDark,
    onPrimaryContainer = LavaColors.InkDark,
    secondary = LavaColors.LavaOrangeSelectedFillDark,
    onSecondary = Color.White,
    secondaryContainer = LavaColors.LavaOrangeSoftDark,
    onSecondaryContainer = LavaColors.LavaOrangeTextDark,
    tertiary = LavaColors.PanelActionGreenDark,
    onTertiary = Color(0xFF073821),
    tertiaryContainer = LavaColors.PanelActionFillDark,
    onTertiaryContainer = LavaColors.InkDark,
    background = LavaColors.GroupedBackgroundDark,
    onBackground = LavaColors.InkDark,
    surface = LavaColors.GroupedBackgroundDark,
    onSurface = LavaColors.InkDark,
    surfaceDim = LavaColors.PanelBackgroundDark,
    surfaceBright = LavaColors.QuietControlDark,
    surfaceContainerLowest = LavaColors.PanelBackgroundDark,
    surfaceContainerLow = LavaColors.GroupedBackgroundDark,
    surfaceContainer = LavaColors.CardBackgroundDark,
    surfaceContainerHigh = LavaColors.PanelActionFillDark,
    surfaceContainerHighest = LavaColors.QuietControlDark,
    surfaceVariant = LavaColors.SoftGreenDark,
    onSurfaceVariant = LavaColors.GuardianSleepGray,
    inverseSurface = LavaColors.InkDark,
    inverseOnSurface = LavaColors.GroupedBackgroundDark,
    inversePrimary = LavaColors.SafeGreen,
    outline = LavaColors.GuardianSleepGray,
    outlineVariant = LavaColors.PanelStrokeDark,
    primaryFixed = LavaColors.SoftGreen,
    primaryFixedDim = LavaColors.PanelActionFill,
    onPrimaryFixed = LavaColors.Ink,
    onPrimaryFixedVariant = LavaColors.Ink,
    secondaryFixed = LavaColors.LavaOrangeSoft,
    secondaryFixedDim = LavaColors.LavaOrangeSoft,
    onSecondaryFixed = LavaColors.LavaOrangeText,
    onSecondaryFixedVariant = LavaColors.LavaOrangeText,
    tertiaryFixed = LavaColors.PanelActionFill,
    tertiaryFixedDim = LavaColors.SoftGreen,
    onTertiaryFixed = LavaColors.Ink,
    onTertiaryFixedVariant = LavaColors.Ink,
    error = LavaColors.DangerDark,
    onError = Color(0xFF5B0000),
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Immutable
data class LavaSemanticColors(
    val safe: Color,
    val danger: Color,
    val attentionText: Color,
    val attentionBackground: Color,
    val attentionSelectedFill: Color,
    val panelBackground: Color,
    val panelStroke: Color,
)

internal val LavaLightSemanticColors = LavaSemanticColors(
    safe = LavaColors.SafeGreen,
    danger = LavaColors.Danger,
    attentionText = LavaColors.LavaOrangeText,
    attentionBackground = LavaColors.LavaOrangeSoft,
    attentionSelectedFill = LavaColors.LavaOrangeSelectedFill,
    panelBackground = LavaColors.PanelBackground,
    panelStroke = LavaColors.PanelStroke,
)

internal val LavaDarkSemanticColors = LavaSemanticColors(
    safe = LavaColors.SafeGreenDark,
    danger = LavaColors.DangerDark,
    attentionText = LavaColors.LavaOrangeTextDark,
    attentionBackground = LavaColors.LavaOrangeSoftDark,
    attentionSelectedFill = LavaColors.LavaOrangeSelectedFillDark,
    panelBackground = LavaColors.PanelBackgroundDark,
    panelStroke = LavaColors.PanelStrokeDark,
)

val LocalLavaSemanticColors = staticCompositionLocalOf { LavaLightSemanticColors }

internal val LavaShapes = Shapes(
    extraSmall = RoundedCornerShape(LavaRadii.iconBadge),
    small = RoundedCornerShape(LavaRadii.control),
    medium = RoundedCornerShape(LavaRadii.compact),
    large = RoundedCornerShape(LavaRadii.card),
    extraLarge = RoundedCornerShape(LavaRadii.card),
)

@Composable
fun LavaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = false,
    reducedMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemReducedMotion = if (reducedMotion == null) observeReducedMotion() else false
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> LavaDarkColorScheme
        else -> LavaLightColorScheme
    }
    val semanticColors = if (darkTheme) LavaDarkSemanticColors else LavaLightSemanticColors
    CompositionLocalProvider(
        LocalLavaReducedMotion provides (reducedMotion ?: systemReducedMotion),
        LocalLavaSemanticColors provides semanticColors,
    ) {
        MaterialTheme(colorScheme = colorScheme, shapes = LavaShapes, content = content)
    }
}

@Composable
private fun observeReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val settingUri = remember { Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE) }
    val state = remember(resolver) { mutableStateOf(readReducedMotion(resolver)) }
    DisposableEffect(resolver, settingUri) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = readReducedMotion(resolver)
            }
        }
        resolver.registerContentObserver(settingUri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return state.value
}

private fun readReducedMotion(resolver: android.content.ContentResolver): Boolean = runCatching {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)
