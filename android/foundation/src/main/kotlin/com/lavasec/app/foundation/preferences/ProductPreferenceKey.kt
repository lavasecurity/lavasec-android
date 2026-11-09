package com.lavasec.app.foundation.preferences

/**
 * The canonical retained product-preference keys, ported from the iOS
 * `LavaAppBridge.setSetting` dispatch at
 * c4658d973bae05dd3b7aa84c014cb2f76ff61a0b
 * (`ReactNative/native-app/LavaAppSettings.swift`), with defaults taken from
 * `CustomizationController.loadCustomizationPreferences`,
 * `LiveActivityPausePreference`, `LavaNotificationPreferences` and
 * `AppConfiguration`.
 *
 * This enum is the single source of truth for the model: the wire name, the
 * accepted value shape, the canonical default and the [AndroidCapability] mapping
 * all live here, so a new canonical key cannot be introduced without an explicit
 * model change (an unknown wire key is rejected by [fromWire]).
 *
 * Keys intentionally out of this slice: `biometrics` / `protectedActions.*`
 * (security surface) and the resolver/`dnsPatch*` keys (DNS slice). They are
 * rejected here rather than silently accepted.
 */
enum class ProductPreferenceKey(
    val wire: String,
    val valueType: PreferenceValueType,
    val canonicalDefault: PreferenceValue,
    val capability: AndroidCapability,
) {
    // -- Customization ---------------------------------------------------------
    APPEARANCE(
        wire = "appearance",
        valueType = PreferenceValueType.TEXT,
        canonicalDefault = PreferenceValue.Text("system"),
        capability = AndroidCapability.AVAILABLE,
    ),
    LOOK(
        wire = "look",
        valueType = PreferenceValueType.TEXT,
        canonicalDefault = PreferenceValue.Text("original"),
        capability = AndroidCapability.EQUIVALENT_DEGRADED,
    ),
    MATCH_TEXT_SIZE(
        wire = "matchTextSize",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.EQUIVALENT_DEGRADED,
    ),
    TEXT_SIZE(
        wire = "textSize",
        valueType = PreferenceValueType.INTEGER,
        canonicalDefault = PreferenceValue.IntValue(TextSizePreference.SYSTEM_DEFAULT.index),
        capability = AndroidCapability.EQUIVALENT_DEGRADED,
    ),
    HAPTICS(
        wire = "haptics",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.AVAILABLE,
    ),
    LIVE_ACTIVITY_PAUSE_MINUTES(
        wire = "liveActivityPauseMinutes",
        valueType = PreferenceValueType.INTEGER,
        canonicalDefault = PreferenceValue.IntValue(5),
        capability = AndroidCapability.UNAVAILABLE_PER_PLATFORM,
    ),
    LIVE_ACTIVITIES(
        wire = "liveActivities",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(false),
        capability = AndroidCapability.UNAVAILABLE_PER_PLATFORM,
    ),
    MATCH_ICON(
        wire = "matchIcon",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.UNAVAILABLE_PER_PLATFORM,
    ),

    // -- Encrypted backup ------------------------------------------------------
    BACKUP_AUTOMATIC(
        wire = "backup.automatic",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(false),
        capability = AndroidCapability.EQUIVALENT_DEGRADED,
    ),

    // -- Notifications ---------------------------------------------------------
    NOTIFICATIONS_FILTER_CHANGES(
        wire = "notifications.Filter changes",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.EQUIVALENT_DEGRADED,
    ),
    NOTIFICATIONS_FILTER_COULD_NOT_SWITCH(
        wire = "notifications.Filter couldn't switch",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.EQUIVALENT_DEGRADED,
    ),
    NOTIFICATIONS_PROTECTION_RESUMED(
        wire = "notifications.Protection resumed",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.EQUIVALENT_DEGRADED,
    ),
    NOTIFICATIONS_CONNECTION_UPDATES(
        wire = "notifications.Connection updates",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.EQUIVALENT_DEGRADED,
    ),

    // -- Local log retention ---------------------------------------------------
    LOGS_FILTERING_COUNTS(
        wire = "logs.Filtering Counts",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.AVAILABLE,
    ),
    LOGS_DOMAINS(
        wire = "logs.Domain logs",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.AVAILABLE,
    ),
    LOGS_NETWORK_ACTIVITY(
        wire = "logs.Network activity",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.AVAILABLE,
    ),
    LOGS_LAVA_GUARD_PROGRESS(
        wire = "logs.Lava Guard Progress",
        valueType = PreferenceValueType.BOOLEAN,
        canonicalDefault = PreferenceValue.Bool(true),
        capability = AndroidCapability.AVAILABLE,
    ),
    ;

    companion object {
        private val byWire: Map<String, ProductPreferenceKey> =
            entries.associateBy { it.wire }

        /** Resolves a canonical wire key, or `null` for an unknown key (fail closed). */
        fun fromWire(wire: String): ProductPreferenceKey? = byWire[wire]

        /** Requires a canonical wire key, throwing [UnknownPreferenceKeyException] otherwise. */
        fun requireWire(wire: String): ProductPreferenceKey =
            fromWire(wire) ?: throw UnknownPreferenceKeyException(wire)
    }
}
