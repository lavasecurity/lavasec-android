package com.lavasec.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.platform.VpnPreparationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
class AndroidVpnPreparationTest {
    @After
    fun resetVpnServiceShadow() {
        ShadowVpnService.reset()
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 production adapter returns the exact platform consent intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = Intent("com.lavasec.app.REQUEST_VPN_CONSENT")
        ShadowVpnService.setPrepareResult(request)

        val result = AndroidVpnPreparation(context).prepare()
            as VpnPreparationResult.ConsentRequired

        assertSame(request, result.request)
    }

    @Test
    @Config(sdk = [36])
    fun `API 36 production adapter maps null to prepared`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowVpnService.setPrepareResult(null)

        assertEquals(
            VpnPreparationResult.Prepared,
            AndroidVpnPreparation(context).prepare(),
        )
    }

    @Test
    fun `production adapter eagerly normalizes a supplied context to the application`() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        var applicationContextReads = 0
        val activityLikeContext = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context {
                applicationContextReads += 1
                return application
            }
        }
        val preparation = AndroidVpnPreparation(activityLikeContext)
        ShadowVpnService.setPrepareResult(null)

        preparation.prepare()

        assertEquals(1, applicationContextReads)
    }

    @Test
    fun `injected adapter performs exactly one query and preserves request identity`() {
        val request = Intent("com.lavasec.app.TEST_VPN_CONSENT")
        var calls = 0
        val preparation = AndroidVpnPreparation {
            calls += 1
            request
        }

        val result = preparation.prepare() as VpnPreparationResult.ConsentRequired

        assertSame(request, result.request)
        assertEquals(1, calls)
    }

    @Test
    fun `platform exception is unavailable and is never retried implicitly`() {
        var calls = 0
        val preparation = AndroidVpnPreparation {
            calls += 1
            throw IllegalStateException("platform unavailable")
        }

        assertEquals(VpnPreparationResult.Unavailable, preparation.prepare())
        assertEquals(1, calls)
    }
}
