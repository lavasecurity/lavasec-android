package com.lavasec.app.vpn

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class VpnForegroundNotificationFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val factory = VpnForegroundNotificationFactory(context)

    @Test
    fun `foreground notification is stable low importance and persistent`() {
        factory.ensureChannel()
        val notification = factory.create()

        assertEquals(1_001, LavaVpnService.FOREGROUND_NOTIFICATION_ID)
        assertEquals("lavasec-protection", LavaVpnService.FOREGROUND_CHANNEL_ID)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(LavaVpnService.FOREGROUND_CHANNEL_ID).importance,
        )
        assertEquals(LavaVpnService.FOREGROUND_CHANNEL_ID, notification.channelId)
        assertEquals(Notification.CATEGORY_SERVICE, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(
            context.getString(R.string.protection_notification_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.protection_notification_text),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
    }
}
