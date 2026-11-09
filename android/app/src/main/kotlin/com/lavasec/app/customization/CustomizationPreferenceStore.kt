package com.lavasec.app.customization

import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat

/**
 * SharedPreferences-backed durable customization preferences. These are
 * presentation choices, not credential evidence, so they stay in the ordinary
 * credential-protected app storage; the owner still fences every write. All
 * access is synchronous and tiny — the owner ports keep the suspend shape so
 * a future durable store can swap in without touching the admission fence.
 */
class CustomizationPreferenceStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences: SharedPreferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): CustomizationPreferences {
        val reduceMotion = preferences.getString(KEY_REDUCE_MOTION, null)
            ?.takeIf { it in setOf(TRUE, FALSE) }
            ?.let { it == TRUE }
        return CustomizationPreferences(
            reduceMotion = reduceMotion,
            haptics = preferences.getBoolean(KEY_HAPTICS, true),
        )
    }

    fun persist(desired: CustomizationPreferences) {
        val editor = preferences.edit()
        when (desired.reduceMotion) {
            true -> editor.putString(KEY_REDUCE_MOTION, TRUE)
            false -> editor.putString(KEY_REDUCE_MOTION, FALSE)
            null -> editor.remove(KEY_REDUCE_MOTION)
        }
        editor.putBoolean(KEY_HAPTICS, desired.haptics).apply()
    }

    /** The real OS notification-channel status; null only when the platform cannot answer. */
    fun notificationChannelAllowed(): Boolean? = runCatching {
        NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
    }.getOrNull()

    private companion object {
        const val PREFERENCES_NAME = "lavasec_customization"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_HAPTICS = "haptics"
        const val TRUE = "true"
        const val FALSE = "false"
    }
}
