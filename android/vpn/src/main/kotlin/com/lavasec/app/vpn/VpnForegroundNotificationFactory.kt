package com.lavasec.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context

/** Stable persistent notification required for the live VPN service lifetime. */
internal class VpnForegroundNotificationFactory(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                LavaVpnService.FOREGROUND_CHANNEL_ID,
                context.getString(R.string.protection_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun create(): Notification {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                context,
                CONTENT_INTENT_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return Notification.Builder(context, LavaVpnService.FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_protection_notification)
            .setContentTitle(context.getString(R.string.protection_notification_title))
            .setContentText(context.getString(R.string.protection_notification_text))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
    }

    private companion object {
        const val CONTENT_INTENT_REQUEST_CODE = 1_001
    }
}
