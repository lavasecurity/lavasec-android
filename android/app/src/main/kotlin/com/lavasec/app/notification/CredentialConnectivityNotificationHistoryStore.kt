package com.lavasec.app.notification

import android.content.Context
import android.annotation.SuppressLint
import android.os.UserManager
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationHistory
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationKind

/** Strict credential-only journal for notification deduplication and cooldown state. */
@SuppressLint("UseKtx") // Synchronous commit success is part of the delivery journal contract.
internal class CredentialConnectivityNotificationHistoryStore(
    credentialContext: Context,
    storeName: String = STORE_NAME,
    private val credentialAvailable: () -> Boolean = {
        (credentialContext.getSystemService(UserManager::class.java)).isUserUnlocked
    },
) : ConnectivityNotificationHistoryStore {
    private val preferences = credentialContext.getSharedPreferences(storeName, Context.MODE_PRIVATE)

    init {
        require(!credentialContext.isDeviceProtectedStorage) {
            "connectivity notification history must remain credential protected"
        }
        require(credentialsAvailable()) {
            "connectivity notification history requires unlocked credentials"
        }
    }

    override fun read(): ProtectionConnectivityNotificationHistory? {
        if (!credentialsAvailable()) return null
        return runCatching {
            val values = preferences.all
            if (values.isEmpty()) return@runCatching ProtectionConnectivityNotificationHistory.EMPTY
            if (values.keys.any { it !in ALL_KEYS }) return@runCatching null
            if (values[SCHEMA_VERSION] != CURRENT_SCHEMA_VERSION) return@runCatching null
            if (values.any { (key, value) -> !hasExpectedType(key, value) }) {
                return@runCatching null
            }
            val unresolvedId = values[UNRESOLVED_ID] as String?
            val unresolvedKind = (values[UNRESOLVED_KIND] as String?)?.let { wireName ->
                ProtectionConnectivityNotificationKind.entries.singleOrNull {
                    it.wireName == wireName
                } ?: return@runCatching null
            }
            ProtectionConnectivityNotificationHistory(
                lastDeliveredNotificationId = (values[LAST_DELIVERED_ID] as String?)
                    ?.takeIf(String::isNotBlank),
                lastDeliveredAtMillis = values[LAST_DELIVERED_AT] as Long?,
                problemDeliveryCooldownUntilMillis = values[COOLDOWN_UNTIL] as Long?,
                unresolvedProblemNotificationId = unresolvedId?.takeIf(String::isNotBlank),
                unresolvedProblemKind = unresolvedKind,
            )
        }.getOrNull()
    }

    override fun write(history: ProtectionConnectivityNotificationHistory): Boolean {
        if (!credentialsAvailable()) return false
        return runCatching {
            val editor = preferences.edit()
                .clear()
                .putInt(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                .putOptionalString(LAST_DELIVERED_ID, history.lastDeliveredNotificationId)
                .putOptionalLong(LAST_DELIVERED_AT, history.lastDeliveredAtMillis)
                .putOptionalLong(COOLDOWN_UNTIL, history.problemDeliveryCooldownUntilMillis)
                .putOptionalString(UNRESOLVED_ID, history.unresolvedProblemNotificationId)
                .putOptionalString(UNRESOLVED_KIND, history.unresolvedProblemKind?.wireName)
            editor.commit()
        }.getOrDefault(false)
    }

    private fun credentialsAvailable(): Boolean =
        runCatching(credentialAvailable).getOrDefault(false)

    private fun hasExpectedType(key: String, value: Any?): Boolean = when (key) {
        SCHEMA_VERSION -> value is Int
        LAST_DELIVERED_AT,
        COOLDOWN_UNTIL,
        -> value is Long
        LAST_DELIVERED_ID,
        UNRESOLVED_ID,
        UNRESOLVED_KIND,
        -> value is String
        else -> false
    }

    private fun android.content.SharedPreferences.Editor.putOptionalString(
        key: String,
        value: String?,
    ) = if (value == null) remove(key) else putString(key, value)

    private fun android.content.SharedPreferences.Editor.putOptionalLong(
        key: String,
        value: Long?,
    ) = if (value == null) remove(key) else putLong(key, value)

    companion object {
        private const val STORE_NAME = "lavasec_connectivity_notification_history"
        private const val CURRENT_SCHEMA_VERSION = 1
        private const val SCHEMA_VERSION = "schema_version"
        private const val LAST_DELIVERED_ID = "last_delivered_id"
        private const val LAST_DELIVERED_AT = "last_delivered_at_millis"
        private const val COOLDOWN_UNTIL = "problem_delivery_cooldown_until_millis"
        private const val UNRESOLVED_ID = "unresolved_problem_id"
        private const val UNRESOLVED_KIND = "unresolved_problem_kind"
        private val ALL_KEYS = setOf(
            SCHEMA_VERSION,
            LAST_DELIVERED_ID,
            LAST_DELIVERED_AT,
            COOLDOWN_UNTIL,
            UNRESOLVED_ID,
            UNRESOLVED_KIND,
        )
    }
}
