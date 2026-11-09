package com.lavasec.app.foundation.notifications

/**
 * The OS-level notification state the Android adapter observes for one category,
 * expressed as pure data so the deliverability decision is JVM-testable without
 * the platform.
 *
 * On Android the two gates are independent: the app-wide `POST_NOTIFICATIONS`
 * permission ([NotificationOsCapability.notificationsPermissionGranted], from
 * `NotificationManagerCompat.areNotificationsEnabled()`) and the per-category
 * `NotificationChannel` ([NotificationOsCapability.channelState], from
 * `NotificationManager.getNotificationChannel(...)`). A caller must consult both
 * before posting or it can report a delivery the OS silently suppresses.
 */
data class NotificationOsCapability(
    /** Whether the app is allowed to post notifications at all. */
    val notificationsPermissionGranted: Boolean,
    /** The state of the channel that carries the category. */
    val channelState: NotificationChannelState,
)

/** The state of the Android notification channel bound to one category. */
enum class NotificationChannelState {
    /** The channel exists and is not set to [android.app.NotificationManager.IMPORTANCE_NONE]. */
    ENABLED,

    /** The channel exists but the user (or app) set it to `IMPORTANCE_NONE`. */
    DISABLED,

    /** No channel is registered for the category yet, or it could not be created. */
    UNAVAILABLE,
}

/**
 * Why a notification would be suppressed before the OS ever shows it. Every value
 * is distinct so a caller can explain the remedy rather than lump the states
 * together.
 */
enum class NotificationSuppression {
    /** The user's own canonical category toggle is OFF. */
    PREFERENCE_OFF,

    /** The app-wide notification permission is denied. */
    OS_PERMISSION_DENIED,

    /** The category's notification channel is set to `IMPORTANCE_NONE`. */
    CHANNEL_DISABLED,

    /** No notification channel is registered for the category. */
    CHANNEL_UNAVAILABLE,
}

/**
 * The outcome of gating a post through the user preference and the Android OS
 * capability. [isDeliverable] is false for every suppression, so a caller that
 * checks it can never report "delivered" when the OS would drop the request.
 *
 * "Deliverable" means no modelled gate refuses the post; it is not proof the OS
 * completed it (a `notify()` call can still fail), so a caller must only treat it
 * as a precondition to attempt, not as a delivery receipt.
 */
sealed interface NotificationEligibility {
    /** Whether posting may be attempted (no modelled suppression applies). */
    val isDeliverable: Boolean

    /** The suppression reason, or `null` when [isDeliverable]. */
    val suppressionReason: NotificationSuppression?

    /** No modelled gate suppresses the post. */
    data object Deliverable : NotificationEligibility {
        override val isDeliverable: Boolean = true
        override val suppressionReason: NotificationSuppression? = null
    }

    /** A gate suppresses the post; [suppressionReason] names it. */
    data class Suppressed(
        override val suppressionReason: NotificationSuppression,
    ) : NotificationEligibility {
        override val isDeliverable: Boolean = false
    }
}

/**
 * The single decision point for "may this category be posted?". Precedence is
 * deterministic and ordered by the caller the reason belongs to:
 *
 *  1. the user preference ([NotificationSuppression.PREFERENCE_OFF]) - the app owns
 *     this gate and a suppressed preference is not an error to surface;
 *  2. the app-wide OS permission ([NotificationSuppression.OS_PERMISSION_DENIED]) -
 *     no channel state can rescue a denied permission;
 *  3. the category channel, first [NotificationSuppression.CHANNEL_DISABLED] then
 *     [NotificationSuppression.CHANNEL_UNAVAILABLE].
 *
 * The preference is checked before the OS state so an opted-out user is never told
 * to fix a system permission for a notification they disabled on purpose.
 */
object NotificationEligibilityPolicy {
    fun evaluate(
        preferences: NotificationPreferences,
        category: NotificationCategory,
        capability: NotificationOsCapability,
    ): NotificationEligibility {
        if (!preferences.isEnabled(category)) {
            return NotificationEligibility.Suppressed(NotificationSuppression.PREFERENCE_OFF)
        }
        if (!capability.notificationsPermissionGranted) {
            return NotificationEligibility.Suppressed(NotificationSuppression.OS_PERMISSION_DENIED)
        }
        return when (capability.channelState) {
            NotificationChannelState.ENABLED -> NotificationEligibility.Deliverable
            NotificationChannelState.DISABLED ->
                NotificationEligibility.Suppressed(NotificationSuppression.CHANNEL_DISABLED)
            NotificationChannelState.UNAVAILABLE ->
                NotificationEligibility.Suppressed(NotificationSuppression.CHANNEL_UNAVAILABLE)
        }
    }
}
