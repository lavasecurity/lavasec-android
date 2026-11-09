package com.lavasec.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.resolver.MilestoneResolverPlan
import com.lavasec.app.MainActivity
import com.lavasec.app.vpn.LavaVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class ProtectionAmbientNotificationFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val factory = ProtectionAmbientNotificationFactory(context)

    @Test
    fun `ambient channel is silent ongoing private and never replaces the other surfaces`() {
        factory.ensureChannel()

        val channel = manager.getNotificationChannel(
            ProtectionAmbientNotificationFactory.CHANNEL_ID,
        )
        val notification = factory.create(state(status = ProtectionAmbientStatus.PROTECTED))

        assertEquals(1_003, ProtectionAmbientNotificationFactory.NOTIFICATION_ID)
        assertTrue(
            ProtectionAmbientNotificationFactory.CHANNEL_ID !=
                ProtectionProblemNotificationFactory.CHANNEL_ID,
        )
        assertTrue(
            ProtectionAmbientNotificationFactory.CHANNEL_ID !=
                LavaVpnService.FOREGROUND_CHANNEL_ID,
        )
        assertTrue(
            ProtectionAmbientNotificationFactory.NOTIFICATION_ID !=
                ProtectionProblemNotificationFactory.NOTIFICATION_ID,
        )
        assertTrue(
            ProtectionAmbientNotificationFactory.NOTIFICATION_ID !=
                LavaVpnService.FOREGROUND_NOTIFICATION_ID,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            channel.importance,
        )
        assertEquals("Protection status", channel.name)
        assertFalse(channel.canShowBadge())
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)

        assertEquals(ProtectionAmbientNotificationFactory.CHANNEL_ID, notification.channelId)
        assertEquals(Notification.CATEGORY_STATUS, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
    }

    @Test
    fun `the only action deep-links through the native validator without extras`() {
        val notification = factory.create(state(status = ProtectionAmbientStatus.PROTECTED))

        val pending = shadowOf(notification.contentIntent)
        assertTrue(pending.isActivity)
        assertFalse(pending.isService)
        assertFalse(pending.isBroadcast)
        assertTrue(pending.isImmutable)
        assertEquals(
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            pending.flags,
        )
        assertEquals(MainActivity::class.java.name, pending.savedIntent.component?.className)
        assertEquals(Intent.ACTION_VIEW, pending.savedIntent.action)
        assertEquals(Uri.parse("${NotificationActionRouting.DEEP_LINK_SCHEME}guard"), pending.savedIntent.data)
        assertTrue(
            pending.savedIntent.extras == null || pending.savedIntent.extras!!.isEmpty,
        )

        val action = notification.actions.single()
        assertEquals("Open Lava", action.title.toString())
        val actionPending = shadowOf(action.actionIntent)
        assertTrue(actionPending.isActivity)
        assertEquals(MainActivity::class.java.name, actionPending.savedIntent.component?.className)
    }

    @Test
    fun `status titles and the filter resolver summary render without domain or account data`() {
        val notification = factory.create(
            state(
                status = ProtectionAmbientStatus.PROTECTED,
                filterName = "Balanced",
                resolverPlan = MilestoneResolverPlan.MULLVAD_ONLY,
            ),
        )

        assertEquals(
            "Lava Security is on",
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            "Filter: Balanced · Resolver: Mullvad",
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(7_000L, notification.`when`)
    }

    @Test
    fun `post and cancel manage only the ambient id and OFF is refused`() {
        assertTrue(factory.post(state(status = ProtectionAmbientStatus.PROTECTED)))
        assertNotNull(
            shadowOf(manager).getNotification(ProtectionAmbientNotificationFactory.NOTIFICATION_ID),
        )
        assertNull(shadowOf(manager).getNotification(LavaVpnService.FOREGROUND_NOTIFICATION_ID))
        assertNull(
            shadowOf(manager).getNotification(ProtectionProblemNotificationFactory.NOTIFICATION_ID),
        )

        assertTrue(factory.cancel())
        assertNull(
            shadowOf(manager).getNotification(ProtectionAmbientNotificationFactory.NOTIFICATION_ID),
        )

        try {
            factory.create(state(status = ProtectionAmbientStatus.OFF))
            throw AssertionError("OFF must never build an ambient notification")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("OFF"))
        }
    }

    @Test
    fun `post refuses while notifications are disabled instead of pretending success`() {
        val blocked = ProtectionAmbientNotificationFactory(
            context,
            notificationsEnabled = { false },
        )

        assertFalse(blocked.post(state(status = ProtectionAmbientStatus.PROTECTED)))
        assertNull(
            shadowOf(manager).getNotification(ProtectionAmbientNotificationFactory.NOTIFICATION_ID),
        )

        // Once enabled, the same presenter posts successfully — the owner's
        // foreground retry drives exactly this transition.
        val enabled = ProtectionAmbientNotificationFactory(
            context,
            notificationsEnabled = { true },
        )
        assertTrue(enabled.post(state(status = ProtectionAmbientStatus.PROTECTED)))
        assertNotNull(
            shadowOf(manager).getNotification(ProtectionAmbientNotificationFactory.NOTIFICATION_ID),
        )
    }

    private fun state(
        status: ProtectionAmbientStatus,
        filterName: String? = null,
        resolverPlan: MilestoneResolverPlan? = null,
    ) = ProtectionAmbientUiState(
        status = status,
        filterName = filterName,
        resolverPlan = resolverPlan,
        timestampMillis = 7_000L,
        allowedActions = if (status == ProtectionAmbientStatus.OFF) {
            emptySet()
        } else {
            setOf(ProtectionAmbientAction.OPEN_APP)
        },
    )
}
