package com.lavasec.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.lavasec.app.R
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan

/**
 * The ongoing ambient protection surface (iOS Live Activity equivalent), defined as one
 * dedicated channel so the user's system-level notification choices stay independent of
 * the connectivity-problem alerts:
 *
 *  - channel importance [NotificationManager.IMPORTANCE_LOW]: visible in the shade and on
 *    the lock screen, but silent — status must never buzz or raise a heads-up;
 *  - dismissal behavior: ongoing (non-dismissible); only this presenter's [cancel]
 *    removes it, which the owner does when protection is off or the credential graph
 *    retires;
 *  - lock-screen visibility [Notification.VISIBILITY_PRIVATE]: filter names are user
 *    data, so the text stays hidden on the lock screen until the device is unlocked;
 *  - no badge, no sound, no vibration.
 *
 * The content is deliberately domain-free and account-free: status title, the composed
 * filter/resolver summary, the status-change time, and one [ProtectionAmbientAction.OPEN_APP]
 * action whose intent is a plain MainActivity launch restoring the saved navigation path
 * — the same open-app shortcut semantics the future widget/tile surfaces would reuse.
 */
internal class ProtectionAmbientNotificationFactory(
    context: Context,
    private val notificationsEnabled: () -> Boolean = {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    },
) : ProtectionAmbientNotificationPresenter {
    private val context = context.applicationContext
    private val actionIntents = NotificationActionIntents(context)
    private val notificationManager =
        this.context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.protection_status_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    fun create(state: ProtectionAmbientUiState): Notification {
        require(state.status != ProtectionAmbientStatus.OFF) {
            "OFF has no ambient presentation"
        }
        val subtitle = listOfNotNull(
            state.filterName?.let { name ->
                context.getString(R.string.protection_ambient_filter_line, name)
            },
            state.resolverPlan?.let { plan ->
                context.getString(R.string.protection_ambient_resolver_line, resolverLabel(plan))
            },
        ).joinToString(" · ")
        val openApp = openAppPendingIntent()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_protection_notification)
            .setContentTitle(context.getString(statusTitleResource(state.status)))
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(state.timestampMillis)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openApp)
        if (subtitle.isNotBlank()) {
            builder.setContentText(subtitle)
            builder.setStyle(Notification.BigTextStyle().bigText(subtitle))
        }
        // Exactly one safe action: open the app. The VPN ownership/VPN-permission paths
        // cannot run from a background component, so no on/off or pause action ships here.
        // The action is a validated `lavasecurity://guard` deep link into the RN activity:
        // the notification requests navigation, it does not own it.
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    context,
                    R.drawable.ic_protection_notification,
                ),
                context.getString(R.string.protection_ambient_open_action),
                openApp,
            ).build(),
        )
        return builder.build()
    }

    override fun post(state: ProtectionAmbientUiState): Boolean {
        // Android silently drops notify() while the app or channel is blocked
        // instead of throwing, so availability is checked before reporting
        // success; the owner then keeps the state "not posted" and the host's
        // foreground refresh retries once notifications are enabled.
        if (!notificationsEnabled()) return false
        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return false
        }
        return runCatching {
            ensureChannel()
            notificationManager.notify(NOTIFICATION_ID, create(state))
        }.isSuccess
    }

    override fun cancel(): Boolean = runCatching {
        notificationManager.cancel(NOTIFICATION_ID)
    }.isSuccess

    private fun statusTitleResource(status: ProtectionAmbientStatus): Int = when (status) {
        ProtectionAmbientStatus.PROTECTED -> R.string.protection_ambient_status_on
        ProtectionAmbientStatus.PAUSED -> R.string.protection_ambient_status_paused
        ProtectionAmbientStatus.STARTING -> R.string.protection_ambient_status_starting
        ProtectionAmbientStatus.STOPPING -> R.string.protection_ambient_status_stopping
        ProtectionAmbientStatus.PLAIN_DNS_FALLBACK -> R.string.protection_ambient_status_plain_fallback
        ProtectionAmbientStatus.DEVICE_DNS_FALLBACK ->
            R.string.protection_ambient_status_device_fallback
        ProtectionAmbientStatus.ENCRYPTED_FALLBACK ->
            R.string.protection_ambient_status_encrypted_fallback
        ProtectionAmbientStatus.RECONNECT_NEEDED ->
            R.string.protection_ambient_status_reconnect_needed
        ProtectionAmbientStatus.OFF -> error("OFF has no ambient presentation")
    }

    private fun resolverLabel(plan: MilestoneResolverPlan): String = context.getString(
        when (plan) {
            MilestoneResolverPlan.DEVICE_DNS_WITH_MULLVAD_FALLBACK ->
                R.string.protection_ambient_provider_device_fallback
            MilestoneResolverPlan.DEVICE_DNS_ONLY ->
                R.string.protection_ambient_provider_device
            MilestoneResolverPlan.MULLVAD_ONLY ->
                R.string.protection_ambient_provider_mullvad
        },
    )

    private fun openAppPendingIntent(): PendingIntent = checkNotNull(
        actionIntents.pendingIntent(NotificationAction.OPEN_APP, OPEN_APP_INTENT_REQUEST_CODE),
    ) { "open-app action must always resolve" }

    companion object {
        const val CHANNEL_ID = "lavasec-protection-status"
        const val NOTIFICATION_ID = 1_003
        private const val OPEN_APP_INTENT_REQUEST_CODE = 1_003
    }
}
