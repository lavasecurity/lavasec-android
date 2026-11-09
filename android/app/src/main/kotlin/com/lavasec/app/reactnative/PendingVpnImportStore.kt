package com.lavasec.app.reactnative

import android.content.Context
import android.content.SharedPreferences

/**
 * Legacy side journal for first VPN imports made before pending ownership was stored atomically.
 *
 * New imports persist `pendingImportId` in the native configuration record's same DataStore edit.
 * Older builds wrote this marker after the eager import. It cannot prove that an unchanged Save
 * never happened, so production recovery preserves ambiguous legacy configurations and uses only
 * the native atomic tag for automatic removal. Keep the reader for conservative migration.
 */
internal data class PendingVpnImport(
    /** The account the import was made under, or `null` when signed out. */
    val accountID: String?,
    val editorId: String,
    /** The generation the eager import committed; a later commit advances past it. */
    val importedGeneration: Long,
)

/** What recovery should do with a persisted pending import against the live committed state. */
internal enum class PendingVpnImportRecovery {
    /** The rotation is still the uncommitted eager import: remove it. */
    REMOVE_IMPORTED,

    /** The marker is stale (no rotation, a commit landed, or an unrelated generation): drop it. */
    CLEAR_MARKER,

    /** The marker belongs to a different account; leave it for that account's next launch. */
    RETAIN,
}

/**
 * The legacy generation-only decision, retained for compatibility tests. It assumes every Save
 * advances the generation; older unchanged saves did not, so production must not use this decision
 * to delete a legacy configuration without an atomic native pending ownership witness.
 */
internal fun pendingVpnImportRecovery(
    pending: PendingVpnImport,
    currentAccountID: String?,
    committedGeneration: Long?,
): PendingVpnImportRecovery = when {
    pending.accountID != currentAccountID -> PendingVpnImportRecovery.RETAIN
    committedGeneration == null -> PendingVpnImportRecovery.CLEAR_MARKER
    committedGeneration == pending.importedGeneration -> PendingVpnImportRecovery.REMOVE_IMPORTED
    else -> PendingVpnImportRecovery.CLEAR_MARKER
}

/** Durable storage for the pending first-VPN-import marker. */
internal interface PendingVpnImportStore {
    fun load(): PendingVpnImport?

    fun save(pending: PendingVpnImport): Boolean

    fun clear(): Boolean
}

/** SharedPreferences-backed marker; `commit()` so the marker is durable before the editor opens. */
internal class SharedPreferencesPendingVpnImportStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) : PendingVpnImportStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun load(): PendingVpnImport? {
        val editorId = preferences.getString(KEY_EDITOR, null) ?: return null
        val generation = preferences.getLong(KEY_GENERATION, -1L)
        if (generation <= 0L) return null
        return PendingVpnImport(
            accountID = preferences.getString(KEY_ACCOUNT, null),
            editorId = editorId,
            importedGeneration = generation,
        )
    }

    override fun save(pending: PendingVpnImport): Boolean {
        val editor = preferences.edit()
            .putString(KEY_EDITOR, pending.editorId)
            .putLong(KEY_GENERATION, pending.importedGeneration)
        if (pending.accountID == null) editor.remove(KEY_ACCOUNT) else editor.putString(KEY_ACCOUNT, pending.accountID)
        return editor.commit()
    }

    override fun clear(): Boolean = preferences.edit().clear().commit()

    private companion object {
        const val PREFERENCES_NAME = "lavasec.vpn.first-import"
        const val KEY_ACCOUNT = "accountID"
        const val KEY_EDITOR = "editorId"
        const val KEY_GENERATION = "importedGeneration"
    }
}
