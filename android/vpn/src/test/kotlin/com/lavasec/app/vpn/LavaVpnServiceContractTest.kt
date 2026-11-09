package com.lavasec.app.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.platform.StartOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class LavaVpnServiceContractTest {
    @Test
    fun `terminal command removes foreground state and stops the current service start`() {
        val service = Robolectric.buildService(LavaVpnService::class.java).create().get()

        assertEquals(
            android.app.Service.START_NOT_STICKY,
            service.onStartCommand(Intent(LavaVpnService.ACTION_STOP), 0, 73),
        )

        val shadow = shadowOf(service)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(73, shadow.stopSelfResultId)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
    }

    @Test
    fun `intent vocabulary preserves every public command and rejects malformed pause`() {
        assertEquals(
            VpnServiceCommand.Start(StartOrigin.VisibleUser),
            classifyVpnServiceCommand(Intent(LavaVpnService.ACTION_START)),
        )
        assertEquals(VpnServiceCommand.Stop, classifyVpnServiceCommand(Intent(LavaVpnService.ACTION_STOP)))
        assertEquals(
            VpnServiceCommand.Pause(PauseDuration.TEN_MINUTES),
            classifyVpnServiceCommand(
                Intent(LavaVpnService.ACTION_PAUSE).putExtra(
                    LavaVpnService.EXTRA_PAUSE_MINUTES,
                    10,
                ),
            ),
        )
        assertNull(
            classifyVpnServiceCommand(
                Intent(LavaVpnService.ACTION_PAUSE).putExtra(
                    LavaVpnService.EXTRA_PAUSE_MINUTES,
                    11,
                ),
            ),
        )
        assertEquals(VpnServiceCommand.Resume, classifyVpnServiceCommand(Intent(LavaVpnService.ACTION_RESUME)))
        assertEquals(
            VpnServiceCommand.Reconnect,
            classifyVpnServiceCommand(Intent(LavaVpnService.ACTION_RECONNECT)),
        )
        assertEquals(
            VpnServiceCommand.Start(StartOrigin.SystemAlwaysOn),
            classifyVpnServiceCommand(Intent(VpnService.SERVICE_INTERFACE)),
        )
        assertEquals(
            VpnServiceCommand.Start(StartOrigin.StickyRestart),
            classifyVpnServiceCommand(null),
        )
        assertNull(classifyVpnServiceCommand(Intent("untrusted.action")))
    }

    @Test
    fun `a reload carries the service generation it was requested against`() {
        // A preference-triggered reload binds to the observed generation.
        assertEquals(
            VpnServiceCommand.Reload(expectedServiceGeneration = 42L),
            classifyVpnServiceCommand(
                Intent(LavaVpnService.ACTION_RELOAD).putExtra(
                    LavaVpnService.EXTRA_EXPECTED_SERVICE_GENERATION,
                    42L,
                ),
            ),
        )
        // An internal runtime rebuild is unbound.
        assertEquals(
            VpnServiceCommand.Reload(expectedServiceGeneration = null),
            classifyVpnServiceCommand(Intent(LavaVpnService.ACTION_RELOAD)),
        )
        // A non-positive extra is treated as absent.
        assertEquals(
            VpnServiceCommand.Reload(expectedServiceGeneration = null),
            classifyVpnServiceCommand(
                Intent(LavaVpnService.ACTION_RELOAD).putExtra(
                    LavaVpnService.EXTRA_EXPECTED_SERVICE_GENERATION,
                    0L,
                ),
            ),
        )
    }

    @Test
    fun `manifest exposes only the permission protected always on vpn service`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, LavaVpnService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertTrue(info.exported)
        assertTrue(info.directBootAware)
        assertEquals("android.permission.BIND_VPN_SERVICE", info.permission)
        assertEquals(true, info.metaData.getBoolean("android.net.VpnService.SUPPORTS_ALWAYS_ON"))
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            assertTrue(
                info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED != 0,
            )
        }
        assertFalse(info.name.isBlank())
    }
}
