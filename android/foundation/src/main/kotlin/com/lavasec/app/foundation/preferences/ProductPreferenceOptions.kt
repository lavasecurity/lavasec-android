package com.lavasec.app.foundation.preferences

/**
 * Valid string domains for the canonical retained preference keys, ported from
 * the iOS models at c4658d973bae05dd3b7aa84c014cb2f76ff61a0b.
 *
 * These parse the exact wire values the iOS bridge accepts (`LavaAppearancePreference`,
 * `GuardianShieldStyle`, `LavaTextSize`). A value outside the domain fails closed
 * rather than being coerced, matching the iOS `guard let ... else { throw }` cases.
 */

/** iOS `LavaAppearancePreference`; default `.system`. */
enum class AppearancePreference(val wire: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromWire(value: String): AppearancePreference? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * iOS `GuardianShieldStyle`. The raw values are the persisted iOS strings
 * (`fireOpal`/`cherryQuartz` are renamed by the enum cases). Alternate icons are
 * iOS-only; [hasAlternateAppIcon] records which styles selected one.
 */
enum class GuardianLook(val wire: String, val hasAlternateAppIcon: Boolean) {
    ORIGINAL("original", false),
    FIRE_OPAL("emberObsidian", true),
    PURPLE_OBSIDIAN("purpleObsidian", true),
    OBSIDIAN("obsidian", true),
    CHERRY_QUARTZ("strawberryObsidian", true),
    EMERALD("emerald", true),
    KIWI_CREME("kiwiCreme", true),
    AQUAMARINE("aquamarine", true),
    ;

    companion object {
        fun fromWire(value: String): GuardianLook? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * iOS `LavaTextSize`, in the canonical seven-step order. The persisted value is
 * the case index, not the raw string, so [index] is what `settings.set` takes and
 * [fromIndex] is the reverse map.
 */
enum class TextSizePreference(val wire: String, val index: Int) {
    X_SMALL("xSmall", 0),
    SMALL("small", 1),
    MEDIUM("medium", 2),
    LARGE("large", 3),
    X_LARGE("xLarge", 4),
    XX_LARGE("xxLarge", 5),
    XXX_LARGE("xxxLarge", 6),
    ;

    companion object {
        fun fromIndex(value: Int): TextSizePreference? = entries.firstOrNull { it.index == value }

        /** iOS `LavaTextSize.systemDefault` (`.large`), the seed when Match System is off. */
        val SYSTEM_DEFAULT: TextSizePreference = LARGE
    }
}
