package com.lavasec.app.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TunBuilderPlanTest {
    @Test
    fun `API 28 configures only the DNS host route and permits IPv6 bypass`() {
        val builder = RecordingTunBuilder()
        val underlyingNetwork = network(42)

        TunBuilderPlan("Lava Security").configure(
            builder = builder,
            underlyingNetwork = underlyingNetwork,
            configureIntent = configureIntent(),
            sdkInt = 28,
        )

        assertEquals(
            listOf(
                "session:Lava Security",
                "mtu:1280",
                "blocking:false",
                "address:10.111.0.2/32",
                "dns:10.111.0.1",
                "route:10.111.0.1/32",
                "family:2",
                "family:10",
                "underlying:1",
                "configure",
            ),
            builder.calls,
        )
        assertSame(underlyingNetwork, builder.recordedUnderlyingNetworks.single())
        assertFalse(builder.calls.any { it.contains("0.0.0.0") || it.contains("/0") })
        assertFalse(builder.calls.any { it.contains("allowed-app") || it.contains("disallowed-app") })
    }

    @Test
    fun `API 29 and API 36 mark the DNS VPN unmetered before setting its configure intent`() {
        val api29 = RecordingTunBuilder()
        val api36 = RecordingTunBuilder()
        val network = network(42)
        val configureIntent = configureIntent()

        TunBuilderPlan("Lava Security").configure(api29, network, configureIntent, sdkInt = 29)
        TunBuilderPlan("Lava Security").configure(api36, network, configureIntent, sdkInt = 36)

        val expected = listOf(
            "session:Lava Security",
            "mtu:1280",
            "blocking:false",
            "address:10.111.0.2/32",
            "dns:10.111.0.1",
            "route:10.111.0.1/32",
            "family:2",
            "family:10",
            "underlying:1",
            "metered:false",
            "configure",
        )
        assertEquals(expected, api29.calls)
        assertEquals(expected, api36.calls)
    }

    private fun configureIntent(): PendingIntent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PendingIntent.getActivity(
            context,
            0,
            Intent("com.lavasec.app.CONFIGURE_VPN"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun network(handle: Int): Network =
        Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(handle)

    private class RecordingTunBuilder : TunBuilder {
        val calls = mutableListOf<String>()
        var recordedUnderlyingNetworks: Array<Network> = emptyArray()

        override fun setSession(label: String) {
            calls += "session:$label"
        }

        override fun setMtu(mtu: Int) {
            calls += "mtu:$mtu"
        }

        override fun setBlocking(blocking: Boolean) {
            calls += "blocking:$blocking"
        }

        override fun addAddress(address: String, prefix: Int) {
            calls += "address:$address/$prefix"
        }

        override fun addDnsServer(address: String) {
            calls += "dns:$address"
        }

        override fun addRoute(address: String, prefix: Int) {
            calls += "route:$address/$prefix"
        }

        override fun allowFamily(family: Int) {
            calls += "family:$family"
        }

        override fun setUnderlyingNetworks(networks: Array<Network>) {
            recordedUnderlyingNetworks = networks
            calls += "underlying:${networks.size}"
        }

        override fun setConfigureIntent(intent: PendingIntent) {
            calls += "configure"
        }

        override fun setMetered(metered: Boolean) {
            calls += "metered:$metered"
        }
    }
}
