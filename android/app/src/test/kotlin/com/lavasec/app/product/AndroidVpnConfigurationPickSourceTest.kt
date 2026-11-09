package com.lavasec.app.product

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import java.util.Locale
import org.robolectric.RuntimeEnvironment
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.lavasec.app.platform.VpnConfigurationPick
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AndroidVpnConfigurationPickSourceTest {
    @Test fun `real document registry result retains actual bounded read cause and closes each stream`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            for ((bytes, expected) in listOf(
                "# valid supplied text".toByteArray() to VpnConfigurationPick.Read("# valid supplied text"),
                ByteArray(StrictWireGuardConfigurationReader.MAXIMUM_BYTES + 1) { '#'.code.toByte() } to VpnConfigurationPick.ImportFailed(VpnConfigurationPick.ImportFailureReason.TOO_LARGE),
                byteArrayOf(0xc3.toByte(), 0x28) to VpnConfigurationPick.ImportFailed(VpnConfigurationPick.ImportFailureReason.NOT_TEXT),
            )) {
                val uri = Uri.parse("content://fixture/configuration")
                var closed = false
                val stream = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
                shadowOf(activity.contentResolver).registerInputStream(uri, stream)
                val registry = ReturningRegistry(uri)
                val pending = async { AndroidVpnConfigurationPickSource(activity, registry).pickDocument() }
                runCurrent()
                assertEquals(expected, pending.await()); assertTrue(closed)
                assertEquals(1, registry.launches); assertEquals("*/*", registry.mimeType)
            }
            val cancelledRegistry = ReturningRegistry(null)
            assertEquals(VpnConfigurationPick.Cancelled, AndroidVpnConfigurationPickSource(activity, cancelledRegistry).pickDocument())
            assertEquals(1, cancelledRegistry.launches)
        } finally { controller.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun `native error message uses current resource locale and states only the actual size lower bound`() {
        val application = RuntimeEnvironment.getApplication()
        val english = application.createConfigurationContext(Configuration(application.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        assertEquals("That file isn't text, so it isn't a WireGuard config.", vpnConfigurationImportFailureMessage(english, VpnConfigurationPick.ImportFailureReason.NOT_TEXT))
        assertEquals("This file is larger than 64 KB — far too big for a WireGuard config. Wrong file?", vpnConfigurationImportFailureMessage(english, VpnConfigurationPick.ImportFailureReason.TOO_LARGE))
        val german = application.createConfigurationContext(Configuration(application.resources.configuration).apply { setLocale(Locale.GERMAN) })
        assertEquals("Die Datei enthält keinen Text und ist daher keine WireGuard-Konfiguration.", vpnConfigurationImportFailureMessage(german, VpnConfigurationPick.ImportFailureReason.NOT_TEXT))
        assertTrue(vpnConfigurationImportFailureMessage(german, VpnConfigurationPick.ImportFailureReason.TOO_LARGE).contains("größer als 64 KB"))
    }

    private class ReturningRegistry(private val uri: Uri?) : ActivityResultRegistry() {
        var launches = 0; var mimeType: Any? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            ++launches; mimeType = input
            dispatchResult(requestCode, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, uri?.let { Intent().setData(it) })
        }
    }
}
