package com.lavasec.app.foundation.notifications

/**
 * The canonical user-facing local-notification categories, ported from the iOS
 * `LavaNotificationCategory` in `Sources/LavaSecKit/LavaEventNotifications.swift`
 * at c4658d973bae05dd3b7aa84c014cb2f76ff61a0b.
 *
 * Each category is an independent opt-in toggle surfaced under
 * Customization -> Notifications, and each maps 1:1 to a canonical `settings.set`
 * wire key (`ReactNative/native-app/LavaAppSettings.swift`, the
 * `key.hasPrefix("notifications.")` branch). Default ON matches
 * `LavaNotificationPreferences.isEnabled`, where an absent key reads `true`.
 *
 * The model is deliberately pure: it records the canonical identity and meaning of
 * each category, never the platform channel that carries it. The Android OS
 * binding (permission + channel) is modelled separately in
 * [NotificationOsCapability] / [NotificationEligibilityPolicy] so a caller can
 * never present a suppressed category as delivered.
 */
enum class NotificationCategory(
    /** The canonical `settings.set` wire key, including the `notifications.` prefix. */
    val preferenceKey: String,
    /** The canonical iOS raw value; the stable category/request identity. */
    val stableKey: String,
    /** Canonical default enabled state (`LavaNotificationPreferences.isEnabled`). */
    val defaultEnabled: Boolean,
    /** The canonical body localization key, or `null` when the category is policy-driven. */
    val bodyKey: String?,
    /** What the category is for, from the pinned iOS documentation. */
    val semantics: String,
) {
    /**
     * "Switched to <Filter>": a Focus auto-switch OR the Shortcuts "Switch Filter"
     * action changed the active filter. One toggle covers both triggers; posted only
     * while the app is closed/backgrounded (a foreground app shows the change in-UI),
     * and for the Shortcuts caller only on committed switches.
     */
    FILTER_CHANGED(
        preferenceKey = "notifications.Filter changes",
        stableKey = "filter-changed",
        defaultEnabled = true,
        bodyKey = "notif.body.switchedTo",
        semantics = "\"Switched to <Filter>\": a Focus auto-switch or the Shortcuts " +
            "\"Switch Filter\" action changed the active filter. One toggle covers both " +
            "triggers; posted only while closed/backgrounded, and for Shortcuts only on " +
            "committed switches.",
    ),

    /**
     * "Couldn't switch to <Filter>": a Focus switch was refused, for example
     * auth-to-edit is on or the target filter no longer exists.
     */
    FILTER_COULD_NOT_SWITCH(
        preferenceKey = "notifications.Filter couldn't switch",
        stableKey = "filter-could-not-apply",
        defaultEnabled = true,
        bodyKey = "notif.body.couldNotSwitchTo",
        semantics = "\"Couldn't switch to <Filter>\": a Focus switch was refused, for " +
            "example auth-to-edit is on or the target filter is gone.",
    ),

    /**
     * "Pause ended - protection is back on": the temporary protection pause expired
     * and filtering resumed. Posted by the tunnel's expiry timer while the app is
     * closed/backgrounded; never for a user-initiated resume.
     */
    PROTECTION_RESUMED(
        preferenceKey = "notifications.Protection resumed",
        stableKey = "protection-resumed",
        defaultEnabled = true,
        bodyKey = "notif.body.pauseEnded",
        semantics = "\"Pause ended - protection is back on\": the temporary protection " +
            "pause expired and filtering resumed. Posted by the tunnel's expiry timer " +
            "while closed/backgrounded; never for a user-initiated resume.",
    ),

    /**
     * Gates the pre-existing connectivity/reconnect problem notifications (DNS not
     * resolving, network unavailable, ...). Policy-driven, with per-problem copy
     * rather than a single event body.
     */
    CONNECTION_UPDATES(
        preferenceKey = "notifications.Connection updates",
        stableKey = "connectivity",
        defaultEnabled = true,
        bodyKey = null,
        semantics = "Gates the connectivity/reconnect problem notifications (DNS not " +
            "resolving, network unavailable, ...). Policy-driven, with per-problem copy " +
            "rather than one event body.",
    ),
    ;

    /** iOS `LavaNotificationCategory.enabledDefaultsKey`. */
    val defaultsKey: String
        get() = "$DEFAULTS_KEY_PREFIX$stableKey"

    companion object {
        /** The shared app-group/DataStore prefix for a category's enabled flag. */
        const val DEFAULTS_KEY_PREFIX: String = "lavasec.notifications.enabled."

        private val byPreferenceKey: Map<String, NotificationCategory> =
            entries.associateBy { it.preferenceKey }

        private val byStableKey: Map<String, NotificationCategory> =
            entries.associateBy { it.stableKey }

        /** Resolves a canonical `settings.set` wire key, or `null` (fail closed). */
        fun fromPreferenceKey(key: String): NotificationCategory? = byPreferenceKey[key]

        /** Requires a canonical `settings.set` wire key. */
        fun requirePreferenceKey(key: String): NotificationCategory =
            fromPreferenceKey(key) ?: throw UnknownNotificationCategoryException(key)

        /** Resolves the canonical iOS raw value, or `null` (fail closed). */
        fun fromStableKey(key: String): NotificationCategory? = byStableKey[key]
    }
}

/** A notification-category key outside the canonical four-category set. */
class UnknownNotificationCategoryException(key: String) :
    IllegalArgumentException("Unknown notification category key: $key")
