package com.lavasec.app.reactnative

import com.lavasec.app.foundation.preferences.GuardianLook
import com.lavasec.app.presentation.mascot.GuardianMascotState
import com.lavasec.app.R

/**
 * Pure presentation policy for the `LavaDecoration` platform leaf.
 *
 * Kept free of Android view state so it is JVM-testable without Robolectric
 * (which cannot execute on this host's aarch64 runtime).
 */
internal object LavaDecorationPolicy {
    /** Cadence of the minimal native mascot blink, in milliseconds. */
    const val BLINK_INTERVAL_MILLIS = 3_600L
    const val REVEAL_DURATION_MILLIS = 600

    enum class Drawing { TRANSPARENT, CATALOG_CONTROL, SYMBOL, BRAND, RANKING, LOCKED, GUARDIAN }

    fun drawing(symbol: String, mood: String): Drawing = when {
        // Shared GuardianDrawing owns artwork; this leaf is only its contact catcher.
        symbol == "guardian.gestures" -> Drawing.TRANSPARENT
        symbol == "catalog.control.material" -> Drawing.CATALOG_CONTROL
        symbol == "lava.shield.fill" -> Drawing.BRAND
        symbol == "lava.ranking" -> Drawing.RANKING
        symbol.isNotEmpty() -> Drawing.SYMBOL
        mood == "locked" -> Drawing.LOCKED
        else -> Drawing.GUARDIAN
    }

    /** Draw-only materials cannot own a contact, delayed reveal or haptic action. */
    fun admitsGuardianContact(symbol: String): Boolean =
        symbol != "form.separator" && symbol != "catalog.control.material"

    /** A shared appearance override takes priority over the native system theme. */
    fun darkAppearance(appearance: String, systemDark: Boolean): Boolean = when (appearance) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }

    /** Frozen LavaSymbolPalette roles resolve through the live Android appearance resources. */
    fun colorResource(tone: String): Int = when (tone) {
        "primary" -> R.color.lava_rn_primarytext
        "ink" -> R.color.lava_rn_ink
        "error", "red", "danger" -> R.color.lava_rn_errortext
        "white" -> R.color.lava_rn_actionforeground
        "accentOrange" -> R.color.lava_rn_lavaorange
        "secondary", "muted" -> R.color.lava_rn_secondarytext
        "tertiary" -> R.color.lava_rn_tertiarytext
        "orange", "blocked", "warning" -> R.color.lava_rn_lavaorangetext
        else -> R.color.lava_rn_safegreen
    }

    data class Reveal(val enabled: Boolean, val visible: Boolean, val active: Boolean)

    /** Hidden/unmeasured, reopened, paused and Reduce Motion frames settle at their destination. */
    fun animateReveal(previous: Reveal, next: Reveal, reducedMotion: Boolean): Boolean =
        previous.enabled && previous.visible && previous.active &&
            next.enabled && next.visible && next.active && !reducedMotion

    /** The mood wire value maps onto the same mascot state graph the Guard renders. */
    fun moodState(mood: String?): GuardianMascotState =
        GuardianMascotState.entries.firstOrNull { it.name.equals(mood, ignoreCase = true) }
            ?: GuardianMascotState.SLEEPING

    /**
     * The resolved Guardian look; an unknown/absent value preserves the original.
     *
     * Every value the canonical `GuardianShieldStyle` catalog defines is now
     * drawable on Android: [com.lavasec.app.presentation.mascot.GuardianMascotRenderer]
     * ports both the original shield and the iOS faceted artwork
     * (`ObsidianShieldBody`/`LavaGuardianShellFacet`), so the leaf can render any
     * look the settings owner accepts.
     */
    fun look(value: String?): GuardianLook =
        GuardianLook.fromWire(value.orEmpty()) ?: GuardianLook.ORIGINAL
}
