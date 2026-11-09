package com.lavasec.app.foundation.preferences

/**
 * Immutable, validated value model of the canonical retained product preferences.
 *
 * The model owns the same application rules as the iOS `settings.set` dispatch:
 * a key from [ProductPreferenceKey] with the shape it accepts, and a bounded
 * value domain (appearance/look string enums, the seven-step text-size index, and
 * the 1..30 Live Activity pause range). [set] mirrors that dispatch — it returns a
 * [Result] and rejects an unknown key or a wrong-typed/out-of-domain value instead
 * of coercing it.
 *
 * This is deliberately a pure Foundation value type: it performs no I/O, reads no
 * platform preference and never consults [AndroidCapability]. Persistence and the
 * "is it actually offered" decision belong to the caller that also reads
 * [AndroidCapabilityMap].
 */
class ProductPreferences private constructor(
    private val values: Map<ProductPreferenceKey, PreferenceValue>,
) {
    /** The stored (always valid) value for [key]. */
    fun value(key: ProductPreferenceKey): PreferenceValue = values.getValue(key)

    /**
     * Applies the canonical `settings.set` dispatch for [key].
     *
     * @return the updated model, or a failure holding the validation exception.
     */
    fun set(key: ProductPreferenceKey, value: PreferenceValue): Result<ProductPreferences> {
        val failure = validate(key, value)
        if (failure != null) return Result.failure(failure)
        return Result.success(ProductPreferences(values + (key to value)))
    }

    /** Wire-key overload of [set]; an unknown key fails with [UnknownPreferenceKeyException]. */
    fun set(wireKey: String, value: PreferenceValue): Result<ProductPreferences> {
        val key = ProductPreferenceKey.fromWire(wireKey)
            ?: return Result.failure(UnknownPreferenceKeyException(wireKey))
        return set(key, value)
    }

    // -- Typed accessors -------------------------------------------------------

    val appearance: AppearancePreference
        get() = requireNotNull(AppearancePreference.fromWire(asText(ProductPreferenceKey.APPEARANCE)))

    val look: GuardianLook
        get() = requireNotNull(GuardianLook.fromWire(asText(ProductPreferenceKey.LOOK)))

    val matchTextSize: Boolean
        get() = asBool(ProductPreferenceKey.MATCH_TEXT_SIZE)

    val textSize: TextSizePreference
        get() = requireNotNull(TextSizePreference.fromIndex(asInt(ProductPreferenceKey.TEXT_SIZE)))

    val haptics: Boolean
        get() = asBool(ProductPreferenceKey.HAPTICS)

    val liveActivityPauseMinutes: Int
        get() = asInt(ProductPreferenceKey.LIVE_ACTIVITY_PAUSE_MINUTES)

    val liveActivities: Boolean
        get() = asBool(ProductPreferenceKey.LIVE_ACTIVITIES)

    val matchIcon: Boolean
        get() = asBool(ProductPreferenceKey.MATCH_ICON)

    val backupAutomatic: Boolean
        get() = asBool(ProductPreferenceKey.BACKUP_AUTOMATIC)

    val notifiesFilterChanges: Boolean
        get() = asBool(ProductPreferenceKey.NOTIFICATIONS_FILTER_CHANGES)

    val notifiesFilterCouldNotSwitch: Boolean
        get() = asBool(ProductPreferenceKey.NOTIFICATIONS_FILTER_COULD_NOT_SWITCH)

    val notifiesProtectionResumed: Boolean
        get() = asBool(ProductPreferenceKey.NOTIFICATIONS_PROTECTION_RESUMED)

    val notifiesConnectionUpdates: Boolean
        get() = asBool(ProductPreferenceKey.NOTIFICATIONS_CONNECTION_UPDATES)

    val keepFilteringCounts: Boolean
        get() = asBool(ProductPreferenceKey.LOGS_FILTERING_COUNTS)

    val keepDomainLogs: Boolean
        get() = asBool(ProductPreferenceKey.LOGS_DOMAINS)

    val keepNetworkActivity: Boolean
        get() = asBool(ProductPreferenceKey.LOGS_NETWORK_ACTIVITY)

    val keepLavaGuardProgress: Boolean
        get() = asBool(ProductPreferenceKey.LOGS_LAVA_GUARD_PROGRESS)

    /** Snapshot of every retained key and its current value. */
    fun toMap(): Map<ProductPreferenceKey, PreferenceValue> = values.toMap()

    override fun equals(other: Any?): Boolean =
        this === other || (other is ProductPreferences && values == other.values)

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.entries.joinToString(
        prefix = "ProductPreferences(",
        postfix = ")",
    ) { (key, value) -> "${key.wire}=$value" }

    private fun asBool(key: ProductPreferenceKey): Boolean =
        (value(key) as PreferenceValue.Bool).value

    private fun asInt(key: ProductPreferenceKey): Int =
        (value(key) as PreferenceValue.IntValue).value

    private fun asText(key: ProductPreferenceKey): String =
        (value(key) as PreferenceValue.Text).value

    companion object {
        /** The canonical defaults, matching the iOS launch-time load exactly. */
        fun canonicalDefaults(): ProductPreferences = ProductPreferences(
            ProductPreferenceKey.entries.associateWith { it.canonicalDefault },
        )

        /**
         * Validates [value] against [key]'s declared shape and domain. Returns the
         * rejection, or `null` when the pair is valid.
         */
        private fun validate(
            key: ProductPreferenceKey,
            value: PreferenceValue,
        ): InvalidPreferenceValueException? {
            if (value.type != key.valueType) {
                return InvalidPreferenceValueException(
                    key.wire,
                    "expected ${key.valueType} but received ${value.type}",
                )
            }
            return when (key) {
                ProductPreferenceKey.APPEARANCE -> invalidUnless(
                    value is PreferenceValue.Text && AppearancePreference.fromWire(value.value) != null,
                    key,
                    "expected one of ${AppearancePreference.entries.map { it.wire }}",
                )
                ProductPreferenceKey.LOOK -> invalidUnless(
                    value is PreferenceValue.Text && GuardianLook.fromWire(value.value) != null,
                    key,
                    "expected one of ${GuardianLook.entries.map { it.wire }}",
                )
                ProductPreferenceKey.TEXT_SIZE -> invalidUnless(
                    value is PreferenceValue.IntValue && TextSizePreference.fromIndex(value.value) != null,
                    key,
                    "expected a text-size index in 0..${TextSizePreference.entries.last().index}",
                )
                ProductPreferenceKey.LIVE_ACTIVITY_PAUSE_MINUTES -> invalidUnless(
                    value is PreferenceValue.IntValue && value.value in LIVE_ACTIVITY_PAUSE_RANGE,
                    key,
                    "expected minutes in $LIVE_ACTIVITY_PAUSE_RANGE",
                )
                else -> null
            }
        }

        private fun invalidUnless(
            valid: Boolean,
            key: ProductPreferenceKey,
            detail: String,
        ): InvalidPreferenceValueException? =
            if (valid) null else InvalidPreferenceValueException(key.wire, detail)
    }
}

/** iOS `LiveActivityPausePreference.minutesRange` (1...30). */
val LIVE_ACTIVITY_PAUSE_RANGE: IntRange = 1..30
