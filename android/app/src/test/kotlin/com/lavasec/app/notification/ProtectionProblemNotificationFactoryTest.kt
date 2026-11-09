package com.lavasec.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.health.ProtectionConnectivityNotification
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationAction
import com.lavasec.app.foundation.health.ProtectionConnectivityNotificationKind
import com.lavasec.app.vpn.LavaVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class ProtectionProblemNotificationFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val factory = ProtectionProblemNotificationFactory(context)

    @Test
    fun `problem notification uses separate stable surface and exact reconnect command`() {
        factory.ensureChannel()

        val notification = factory.create(problem())
        val pending = shadowOf(notification.contentIntent)

        assertEquals(1_002, ProtectionProblemNotificationFactory.NOTIFICATION_ID)
        assertEquals(1_001, LavaVpnService.FOREGROUND_NOTIFICATION_ID)
        assertTrue(
            ProtectionProblemNotificationFactory.CHANNEL_ID !=
                LavaVpnService.FOREGROUND_CHANNEL_ID,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(ProtectionProblemNotificationFactory.CHANNEL_ID).importance,
        )
        assertEquals(ProtectionProblemNotificationFactory.CHANNEL_ID, notification.channelId)
        assertEquals(Notification.CATEGORY_ERROR, notification.category)
        assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(pending.isService)
        assertFalse(pending.isActivity)
        assertFalse(pending.isBroadcast)
        assertTrue(pending.isImmutable)
        assertEquals(
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            pending.flags,
        )
        assertEquals(LavaVpnService::class.java.name, pending.savedIntent.component?.className)
        assertEquals(LavaVpnService.ACTION_RECONNECT, pending.savedIntent.action)
        assertNull(pending.savedIntent.data)
        assertTrue(pending.savedIntent.extras == null || pending.savedIntent.extras!!.isEmpty)
    }

    @Test
    fun `post and cancel never replace foreground notification`() {
        assertTrue(factory.post(problem()))

        assertNull(
            shadowOf(manager).getNotification(LavaVpnService.FOREGROUND_NOTIFICATION_ID),
        )
        assertEquals(
            "Reconnect Lava",
            shadowOf(manager)
                .getNotification(ProtectionProblemNotificationFactory.NOTIFICATION_ID)
                .extras
                .getString(Notification.EXTRA_TITLE),
        )

        assertTrue(factory.cancel())
        assertNull(
            shadowOf(manager).getNotification(ProtectionProblemNotificationFactory.NOTIFICATION_ID),
        )
    }

    private fun problem() = ProtectionConnectivityNotification(
        kind = ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
        identifier = "reconnect-needed:1000",
        title = "Reconnect Lava",
        body = "DNS is not resolving on this network. Tap to reconnect protection.",
        action = ProtectionConnectivityNotificationAction.RECONNECT,
    )
}
