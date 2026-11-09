package com.lavasec.app.notification

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.MainActivity
import com.lavasec.app.vpn.LavaVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class NotificationActionIntentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val intents = NotificationActionIntents(context)

    @Test
    fun `protection commands are service intents admitted by the vpn service`() {
        val pending = shadowOf(intents.pendingIntent(NotificationAction.RECONNECT, 1))
        assertTrue(pending.isService)
        assertFalse(pending.isActivity)
        assertTrue(pending.isImmutable)
        assertEquals(LavaVpnService::class.java.name, pending.savedIntent.component?.className)
        assertEquals(LavaVpnService.ACTION_RECONNECT, pending.savedIntent.action)
    }

    @Test
    fun `navigation actions pass edition deep links through the native validator`() {
        val pending = shadowOf(intents.pendingIntent(NotificationAction.OPEN_SETTINGS, 2))
        assertTrue(pending.isActivity)
        assertEquals(MainActivity::class.java.name, pending.savedIntent.component?.className)
        assertEquals(Intent.ACTION_VIEW, pending.savedIntent.action)
        assertEquals(Uri.parse("${NotificationActionRouting.DEEP_LINK_SCHEME}settings"), pending.savedIntent.data)
    }

    @Test
    fun `system settings actions target the OS settings destinations`() {
        val notifications = shadowOf(intents.pendingIntent(NotificationAction.NOTIFICATION_SETTINGS, 3))
        assertTrue(notifications.isActivity)
        assertEquals(
            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            notifications.savedIntent.action,
        )
        assertEquals(
            context.packageName,
            notifications.savedIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE),
        )

        val vpn = shadowOf(intents.pendingIntent(NotificationAction.VPN_SETTINGS, 4))
        assertEquals(Settings.ACTION_VPN_SETTINGS, vpn.savedIntent.action)
        assertNotNull(vpn.savedIntent)
    }
}
