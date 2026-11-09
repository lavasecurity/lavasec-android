package com.lavasec.app.foundation.notifications

/**
 * Immutable, validated value model of the four canonical per-category
 * notification toggles.
 *
 * Each category is independent: writing one never changes another, and the stored
 * value set always covers exactly [NotificationCategory.entries]. The defaults
 * match canon ([NotificationCategory.defaultEnabled], all ON), and a write via the
 * `settings.set` wire key either resolves to a canonical category or fails closed
 * with [UnknownNotificationCategoryException] - unknown keys are never coerced or
 * silently dropped.
 *
 * This is a pure Foundation value type: it performs no I/O, reads no platform
 * preference, and never consults the Android OS notification state. The
 * "will the OS actually deliver this?" decision belongs to
 * [NotificationEligibilityPolicy].
 */
class NotificationPreferences private constructor(
    private val values: Map<NotificationCategory, Boolean>,
) {
    /** Whether [category] is enabled by the user's preference. */
    fun isEnabled(category: NotificationCategory): Boolean = values.getValue(category)

    /** Applies an in-memory toggle for [category]; the receiver is unchanged. */
    fun set(category: NotificationCategory, enabled: Boolean): NotificationPreferences =
        NotificationPreferences(values + (category to enabled))

    /**
     * Applies the canonical `settings.set` dispatch for a raw wire key.
     *
     * @return the updated model, or a failure holding
     *   [UnknownNotificationCategoryException] for a non-canonical key.
     */
    fun set(preferenceKey: String, enabled: Boolean): Result<NotificationPreferences> {
        val category = NotificationCategory.fromPreferenceKey(preferenceKey)
            ?: return Result.failure(UnknownNotificationCategoryException(preferenceKey))
        return Result.success(set(category, enabled))
    }

    /** Snapshot of every canonical category and its current preference. */
    fun toMap(): Map<NotificationCategory, Boolean> = values.toMap()

    override fun equals(other: Any?): Boolean =
        this === other || (other is NotificationPreferences && values == other.values)

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.entries.joinToString(
        prefix = "NotificationPreferences(",
        postfix = ")",
    ) { (category, enabled) -> "${category.preferenceKey}=$enabled" }

    companion object {
        /** The canonical defaults, all categories ON, matching the iOS read. */
        fun canonicalDefaults(): NotificationPreferences = NotificationPreferences(
            NotificationCategory.entries.associateWith { it.defaultEnabled },
        )
    }
}
