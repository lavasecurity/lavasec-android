package com.lavasec.app.foundation.privacy

import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.ProductPreferenceKey

/**
 * The four independent local-log retention categories, ported from the iOS
 * `AppConfiguration` keep-flags at
 * c4658d973bae05dd3b7aa84c014cb2f76ff61a0b (`keepFilteringCounts`,
 * `keepDomainDiagnostics`, `keepNetworkActivity`, `keepLavaGuardProgress`).
 *
 * Each category owns its own opt-in. The categories are deliberately
 * independent: clearing, disabling or enrolling one must never touch another.
 * The wire key is the canonical `settings.set` key, so the retention model can
 * never drift from the retained-preference catalog.
 */
enum class PrivacyRetentionCategory(
    val preferenceKey: ProductPreferenceKey,
) {
    FILTERING_COUNTS(ProductPreferenceKey.LOGS_FILTERING_COUNTS),
    DOMAIN_HISTORY(ProductPreferenceKey.LOGS_DOMAINS),
    NETWORK_ACTIVITY(ProductPreferenceKey.LOGS_NETWORK_ACTIVITY),
    GUARD_PROGRESS(ProductPreferenceKey.LOGS_LAVA_GUARD_PROGRESS),
    ;

    /** The canonical `settings.set` wire key for this category (e.g. `logs.Domain logs`). */
    val wire: String
        get() = preferenceKey.wire
}

/**
 * A typed retention choice, replacing the bare iOS `keep*` boolean.
 *
 * [DO_NOT_COLLECT] is the only value an unresolved/absent preference may produce:
 * a category is never enrolled without an explicit `true` (see
 * [RetentionChoice.fromPersisted]). This is the "never silently permissive" rule
 * the iOS `ShareableFilters` comment warns about, where a placeholder default of
 * `keepNetworkActivity == true` must not be read as informed consent.
 */
enum class RetentionChoice {
    COLLECT,
    DO_NOT_COLLECT,
    ;

    companion object {
        /**
         * Maps a persisted keep-flag to a choice. `null` (absent) and `false` both
         * resolve to [DO_NOT_COLLECT]; only an explicit `true` collects.
         */
        fun fromPersisted(keep: Boolean?): RetentionChoice =
            if (keep == true) COLLECT else DO_NOT_COLLECT
    }
}

/**
 * The canonical clear scope, mirroring the iOS `LocalLogClearTarget` cases:
 * one target per retention category plus the aggregate "Clear all logs".
 */
sealed interface PrivacyClearTarget {
    /** A single retention category. */
    data class Category(val category: PrivacyRetentionCategory) : PrivacyClearTarget

    /** Every retention category at once. */
    data object All : PrivacyClearTarget
}

/** The exact set of categories [this] target clears. */
val PrivacyClearTarget.categories: Set<PrivacyRetentionCategory>
    get() = when (this) {
        is PrivacyClearTarget.Category -> setOf(category)
        PrivacyClearTarget.All -> PrivacyRetentionCategory.entries.toSet()
    }

/**
 * Immutable, pure model of the canonical privacy retention state: one
 * [RetentionChoice] per [PrivacyRetentionCategory] plus the set of categories that
 * currently hold retained local data.
 *
 * The model owns the three canonical rules and nothing else — no I/O, no
 * platform preference, no Android type:
 *
 * 1. **Independent categories.** [clear] and [disable] target exactly the named
 *    category; a sibling category's choice and retained data are untouched.
 * 2. **Disable stops collection immediately.** [record] returns `null` for a
 *    category whose choice is [RetentionChoice.DO_NOT_COLLECT], so no code path
 *    can append a datum after the opt-out. [disable] also clears that category by
 *    default, matching the iOS "Turn Off and Clear" action.
 * 3. **Conservative absence.** [fromPersisted] treats a missing entry as
 *    [RetentionChoice.DO_NOT_COLLECT]; a category is only enrolled by an explicit
 *    choice. [canonicalDefaults] re-reads the retained-preference catalog's own
 *    canonical defaults rather than hard-coding permissive values.
 *
 * Disabling or clearing one category never resets the others to a permissive
 * default: [withChoice], [disable] and [clear] carry every other entry forward
 * unchanged.
 */
class PrivacyRetention private constructor(
    private val choices: Map<PrivacyRetentionCategory, RetentionChoice>,
    private val retained: Set<PrivacyRetentionCategory>,
) {
    init {
        require(choices.keys == PrivacyRetentionCategory.entries.toSet()) {
            "PrivacyRetention must define a choice for every retention category"
        }
    }

    /** The current choice for [category]. Always defined for every category. */
    fun choice(category: PrivacyRetentionCategory): RetentionChoice = choices.getValue(category)

    /** Whether [category] may collect a new datum right now. */
    fun isCollecting(category: PrivacyRetentionCategory): Boolean =
        choice(category) == RetentionChoice.COLLECT

    /** Whether [category] currently holds retained local data. */
    fun hasRetainedData(category: PrivacyRetentionCategory): Boolean = category in retained

    /** A snapshot of the categories that currently hold retained local data. */
    val retainedCategories: Set<PrivacyRetentionCategory>
        get() = retained.toSet()

    /** A snapshot of the categories currently enrolled for collection. */
    val collectingCategories: Set<PrivacyRetentionCategory>
        get() = PrivacyRetentionCategory.entries.filterTo(LinkedHashSet()) { isCollecting(it) }

    /** The persisted keep-map, one entry per category. */
    fun toPersistedMap(): Map<PrivacyRetentionCategory, Boolean> =
        PrivacyRetentionCategory.entries.associateWith { isCollecting(it) }

    /** Returns this state with [category] set to an explicit [choice]; siblings are untouched. */
    fun withChoice(
        category: PrivacyRetentionCategory,
        choice: RetentionChoice,
    ): PrivacyRetention = PrivacyRetention(choices + (category to choice), retained)

    /**
     * Stops collection for [category] immediately. When [clear] is true (the
     * default, matching the iOS opt-out-and-clear action) it also removes only
     * this category's retained data; every other choice and retained category is
     * carried forward unchanged.
     */
    fun disable(
        category: PrivacyRetentionCategory,
        clear: Boolean = true,
    ): PrivacyRetention {
        val disabled = withChoice(category, RetentionChoice.DO_NOT_COLLECT)
        return if (clear) disabled.clear(PrivacyClearTarget.Category(category)) else disabled
    }

    /** Removes retained data for exactly [target]'s categories; every choice is unchanged. */
    fun clear(target: PrivacyClearTarget): PrivacyRetention =
        clear(target.categories)

    /** Removes retained data for exactly [categories]; every choice is unchanged. */
    fun clear(categories: Collection<PrivacyRetentionCategory>): PrivacyRetention =
        PrivacyRetention(choices, retained - categories.toSet())

    /**
     * Appends a datum for [category].
     *
     * Returns the next state, or `null` when the category is not collecting — the
     * model's immediate-stop guarantee. A refused datum leaves the state untouched.
     */
    fun record(category: PrivacyRetentionCategory): PrivacyRetention? =
        if (isCollecting(category)) {
            PrivacyRetention(choices, retained + category)
        } else {
            null
        }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PrivacyRetention && choices == other.choices && retained == other.retained)

    override fun hashCode(): Int = 31 * choices.hashCode() + retained.hashCode()

    override fun toString(): String =
        "PrivacyRetention(choices=$choices, retained=$retained)"

    companion object {
        /**
         * The explicit product defaults, read from the retained-preference catalog
         * rather than hard-coded here. If a catalog default were ever missing or
         * non-boolean, [RetentionChoice.fromPersisted] resolves it conservatively.
         */
        fun canonicalDefaults(): PrivacyRetention = PrivacyRetention(
            PrivacyRetentionCategory.entries.associateWith { category ->
                RetentionChoice.fromPersisted(canonicalDefaultOf(category))
            },
            emptySet(),
        )

        /** Every category opted out and holding no data. The fence's safe seed. */
        fun conservativeDefaults(): PrivacyRetention = PrivacyRetention(
            PrivacyRetentionCategory.entries.associateWith { RetentionChoice.DO_NOT_COLLECT },
            emptySet(),
        )

        /**
         * Resolves the model from persisted per-category keep-flags. A missing or
         * `null` entry is [RetentionChoice.DO_NOT_COLLECT]: no category is enrolled
         * without an explicit `true`.
         */
        fun fromPersisted(
            values: Map<PrivacyRetentionCategory, Boolean?>,
        ): PrivacyRetention = PrivacyRetention(
            PrivacyRetentionCategory.entries.associateWith { category ->
                RetentionChoice.fromPersisted(values[category])
            },
            emptySet(),
        )

        private fun canonicalDefaultOf(category: PrivacyRetentionCategory): Boolean? =
            (category.preferenceKey.canonicalDefault as? PreferenceValue.Bool)?.value
    }
}
