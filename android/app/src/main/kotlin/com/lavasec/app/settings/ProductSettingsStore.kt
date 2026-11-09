package com.lavasec.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.lavasec.app.foundation.notifications.NotificationCategory
import com.lavasec.app.foundation.notifications.NotificationPreferences
import com.lavasec.app.foundation.preferences.PreferenceValue
import com.lavasec.app.foundation.preferences.PreferenceValueType
import com.lavasec.app.foundation.preferences.ProductPreferenceKey
import com.lavasec.app.foundation.preferences.ProductPreferences

/**
 * SharedPreferences-backed durability for the retained preference models.
 *
 * The pure models own validation and defaults; this store only encodes the accepted
 * values and reports whether a write was durably acknowledged (`commit()`), so a
 * caller never treats a lost write as success. Every key is read back through
 * [ProductPreferences.set]/[NotificationPreferences.set], so a corrupted or
 * unknown persisted entry resolves to the canonical default rather than an
 * invalid model.
 *
 * These are presentation preferences, not credential evidence, so they stay in the
 * ordinary app storage alongside the customization preferences.
 */
internal class ProductSettingsStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun loadPreferences(): ProductPreferences {
        var model = ProductPreferences.canonicalDefaults()
        OWNED_PREFERENCE_KEYS.forEach { key ->
            val raw = preferences.all[key.wire] ?: return@forEach
            val value = decode(key.valueType, raw) ?: return@forEach
            model = model.set(key, value).getOrDefault(model)
        }
        return model
    }

    fun persistPreferences(model: ProductPreferences): Boolean {
        val editor = preferences.edit()
        OWNED_PREFERENCE_KEYS.forEach { key ->
            when (val value = model.value(key)) {
                is PreferenceValue.Bool -> editor.putBoolean(key.wire, value.value)
                is PreferenceValue.IntValue -> editor.putInt(key.wire, value.value)
                is PreferenceValue.Text -> editor.putString(key.wire, value.value)
            }
        }
        return editor.commit()
    }

    fun loadNotifications(): NotificationPreferences {
        var model = NotificationPreferences.canonicalDefaults()
        NotificationCategory.entries.forEach { category ->
            if (!preferences.contains(notificationKey(category))) return@forEach
            model = model.set(
                category,
                preferences.getBoolean(notificationKey(category), category.defaultEnabled),
            )
        }
        return model
    }

    fun persistNotifications(model: NotificationPreferences): Boolean {
        val editor = preferences.edit()
        NotificationCategory.entries.forEach { category ->
            editor.putBoolean(notificationKey(category), model.isEnabled(category))
        }
        return editor.commit()
    }

    private fun decode(type: PreferenceValueType, raw: Any): PreferenceValue? = when (type) {
        PreferenceValueType.BOOLEAN -> (raw as? Boolean)?.let(PreferenceValue::Bool)
        PreferenceValueType.INTEGER -> (raw as? Int)?.let(PreferenceValue::IntValue)
        PreferenceValueType.TEXT -> (raw as? String)?.let(PreferenceValue::Text)
    }

    private companion object {
        const val PREFERENCES_NAME = "lavasec_product_settings"
        const val NOTIFICATION_PREFIX = "notifications."

        /**
         * The retained keys this store is the durable authority for. `logs.*` belongs to the
         * privacy owner, `haptics` to the customization owner and `backup.automatic` to the
         * backup owner, so persisting their copies here would create a second, stale authority.
         */
        val OWNED_PREFERENCE_KEYS = listOf(
            ProductPreferenceKey.APPEARANCE,
            ProductPreferenceKey.LOOK,
            ProductPreferenceKey.MATCH_TEXT_SIZE,
            ProductPreferenceKey.TEXT_SIZE,
        )

        fun notificationKey(category: NotificationCategory): String =
            "$NOTIFICATION_PREFIX${category.stableKey}"
    }
}
