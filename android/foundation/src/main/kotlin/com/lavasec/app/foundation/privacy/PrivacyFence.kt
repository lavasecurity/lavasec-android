package com.lavasec.app.foundation.privacy

/** An immutable `(version, retention)` snapshot an operation is admitted against. */
data class PrivacyAdmission(
    val version: Long,
    val retention: PrivacyRetention,
)

/**
 * Pure, Android-free fence around the privacy retention state.
 *
 * Every write, query and export runs against an [admission] snapshot taken at
 * [admission]. Disabling or clearing a category is a privacy mutation: it applies
 * the next [PrivacyRetention] and advances a monotonically increasing [version].
 * An operation admitted before a mutation is therefore fenced off — its `version`
 * no longer matches — so a pre-clear write can never resurrect data the user just
 * erased ([write]) and a pre-clear query/export can never publish a stale view
 * ([read]).
 *
 * The fence serializes operations and mutations on one internal monitor: a
 * mutation waits for an in-flight admitted operation to finish, and any operation
 * admitted afterwards observes the new version. Operations must be non-nested and
 * must not themselves call [apply]/[disable]/[clear].
 *
 * The default seed is [PrivacyRetention.conservativeDefaults]: an unconfigured
 * fence collects nothing. Callers enroll categories explicitly with
 * [PrivacyRetention.canonicalDefaults] or [PrivacyRetention.fromPersisted].
 */
class PrivacyFence(
    initial: PrivacyRetention = PrivacyRetention.conservativeDefaults(),
) {
    private val monitor = Any()
    private var retentionState: PrivacyRetention = initial
    private var currentVersion: Long = 0L

    /** The current retention state. */
    fun retention(): PrivacyRetention = synchronized(monitor) { retentionState }

    /** The current monotonic version; advances on every privacy mutation. */
    fun version(): Long = synchronized(monitor) { currentVersion }

    /** An admission snapshot for a write/query/export operation. */
    fun admission(): PrivacyAdmission = synchronized(monitor) {
        PrivacyAdmission(currentVersion, retentionState)
    }

    /**
     * Applies [next] as a privacy mutation and advances the version, fencing any
     * operation admitted against an earlier version.
     */
    fun apply(next: PrivacyRetention): PrivacyAdmission = synchronized(monitor) {
        retentionState = next
        currentVersion += 1
        PrivacyAdmission(currentVersion, retentionState)
    }

    /** Disables [category] (optionally clearing it) and returns the new admission. */
    fun disable(
        category: PrivacyRetentionCategory,
        clear: Boolean = true,
    ): PrivacyAdmission = apply(retention().disable(category, clear))

    /** Clears exactly [target]'s categories and returns the new admission. */
    fun clear(target: PrivacyClearTarget): PrivacyAdmission = apply(retention().clear(target))

    /**
     * Write gate. Runs [transform] and commits its result only when [admission] is
     * still current and [category] is collecting; otherwise returns `null` and
     * leaves the state untouched. [transform] must be pure or its effect discarded
     * by the caller if the write is refused.
     */
    fun write(
        admission: PrivacyAdmission,
        category: PrivacyRetentionCategory,
        transform: (PrivacyRetention) -> PrivacyRetention,
    ): PrivacyRetention? = synchronized(monitor) {
        if (admission.version != currentVersion) return null
        if (!retentionState.isCollecting(category)) return null
        val next = transform(retentionState)
        retentionState = next
        next
    }

    /**
     * Records a datum for [category] through the write gate. Returns the committed
     * state, or `null` when the admission is stale or the category is opted out.
     */
    fun record(
        admission: PrivacyAdmission,
        category: PrivacyRetentionCategory,
    ): PrivacyRetention? = write(admission, category) { state ->
        state.record(category) ?: state
    }

    /**
     * Query/export gate. Runs [block] against the current state only when
     * [admission] is still current; otherwise returns `null`.
     */
    fun <T> read(
        admission: PrivacyAdmission,
        block: (PrivacyRetention) -> T,
    ): T? = synchronized(monitor) {
        if (admission.version != currentVersion) null else block(retentionState)
    }
}
