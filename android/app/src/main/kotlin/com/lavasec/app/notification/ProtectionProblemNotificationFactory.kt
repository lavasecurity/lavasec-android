package com.lavasec.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.lavasec.app.R
import com.lavasec.app.foundation.health.ProtectionConnectivityNotification
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationAction

/** Dismissible connectivity problem surface, separate from the persistent VPN notification. */
internal class ProtectionProblemNotificationFactory(context: Context) :
    ProtectionProblemNotificationPresenter {
    private val context = context.applicationContext
    private val actionIntents = NotificationActionIntents(context)
    private val notificationManager =
        this.context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.connectivity_problem_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { setShowBadge(false) },
        )
    }

    fun create(problem: ProtectionConnectivityNotification): Notification {
        require(problem.action == ProtectionConnectivityNotificationAction.RECONNECT)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_protection_notification)
            .setContentTitle(problem.title)
            .setContentText(problem.body)
            .setStyle(Notification.BigTextStyle().bigText(problem.body))
            .setContentIntent(reconnectIntent())
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun post(problem: ProtectionConnectivityNotification): Boolean = runCatching {
        ensureChannel()
        notificationManager.notify(NOTIFICATION_ID, create(problem))
    }.isSuccess

    override fun cancel(): Boolean = runCatching {
        notificationManager.cancel(NOTIFICATION_ID)
    }.isSuccess

    /**
     * The reconnect action is routed through [NotificationActionRouting] so the exact VPN service
     * command (and therefore its admission) is the single source; this factory never mutates
     * protection itself.
     */
    private fun reconnectIntent(): PendingIntent = checkNotNull(
        actionIntents.pendingIntent(NotificationAction.RECONNECT, CONTENT_INTENT_REQUEST_CODE),
    ) { "reconnect action must always resolve" }

    companion object {
        const val CHANNEL_ID = "lavasec-connectivity-problems"
        const val NOTIFICATION_ID = 1_002
        internal const val CONTENT_INTENT_REQUEST_CODE = 1_002
    }
}
