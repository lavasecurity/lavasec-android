package com.lavasec.app.vpn.chained

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.vpn.TunBuilder
import com.lavasec.app.vpn.TunBuilderPlan
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The plan-to-builder mapping, device-gated by `Network` and `PendingIntent`. The pure
 * installable/reject decision is covered by [ChainedTunPlanTest] on the host JVM.
 */
@RunWith(RobolectricTestRunner::class)
class ChainedTunBuilderPlanTest {

    @Test
    fun `the inactive plan is byte-for-byte the pre-chaining DNS-only sequence`() {
        val builder = RecordingTunBuilder()

        TunBuilderPlan("Lava Security").configure(
            builder = builder,
            underlyingNetwork = network(42),
            configureIntent = configureIntent(),
            sdkInt = 28,
            plan = (ChainedTunPlan.resolve(null) as ChainedTunPlanResolution.Ready).plan,
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
    }

    @Test
    fun `full tunnel claims both default routes with the configured address and MTU`() {
        val builder = RecordingTunBuilder()

        ChainedTunBuilderPlan.configure(
            builder = builder,
            productLabel = "LavaSec",
            plan = ChainedRoutePlan.make(configuration(listOf("0.0.0.0/0"), mtu = 1420)),
            underlyingNetwork = network(42),
            configureIntent = configureIntent(),
            sdkInt = 36,
        )

        assertEquals(
            listOf(
                "session:LavaSec",
                "mtu:1420",
                "blocking:false",
                "address:10.7.0.2/32",
                "dns:10.111.0.1",
                "route:0.0.0.0/0",
                "address:fd00:1a7a::2/64",
                "dns:fd00:1a7a::1",
                "route:::/0",
                "family:2",
                "family:10",
                "underlying:1",
                "metered:false",
                "configure",
            ),
            builder.calls,
        )
    }

    @Test
    fun `split tunnel claims allowed prefixes and advertises reachable served IPv6 DNS`() {
        val builder = RecordingTunBuilder()

        ChainedTunBuilderPlan.configure(
            builder = builder,
            productLabel = "LavaSec",
            plan = ChainedRoutePlan.make(
                configuration(listOf("10.0.0.0/8", "192.168.0.0/16")),
            ),
            underlyingNetwork = network(42),
            configureIntent = configureIntent(),
            sdkInt = 36,
        )

        assertEquals(
            listOf(
                "session:LavaSec",
                "mtu:1280",
                "blocking:false",
                "address:10.7.0.2/32",
                "dns:10.111.0.1",
                "route:10.0.0.0/8",
                "route:192.168.0.0/16",
                "route:10.111.0.1/32",
                "address:fd00:1a7a::2/64",
                "dns:fd00:1a7a::1",
                "route:fd00:1a7a::/64",
                "family:2",
                "family:10",
                "underlying:1",
                "metered:false",
                "configure",
            ),
            builder.calls,
        )
    }

    private fun configuration(
        allowedIPs: List<String>,
        mtu: Int? = null,
    ): ChainedUpstreamConfiguration = ChainedUpstreamConfiguration.create(
        endpointHost = "vpn.example.com",
        endpointPort = 51820,
        peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 }),
        clientAddress = "10.7.0.2",
        allowedIPs = allowedIPs,
        persistentKeepaliveSeconds = 0,
        interfaceMTU = mtu,
        dnsAddresses = listOf("10.64.0.1"),
    )

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

        override fun setSession(label: String) = record("session:$label")
        override fun setMtu(mtu: Int) = record("mtu:$mtu")
        override fun setBlocking(blocking: Boolean) = record("blocking:$blocking")
        override fun addAddress(address: String, prefix: Int) = record("address:$address/$prefix")
        override fun addDnsServer(address: String) = record("dns:$address")
        override fun addRoute(address: String, prefix: Int) = record("route:$address/$prefix")
        override fun allowFamily(family: Int) = record("family:$family")
        override fun setUnderlyingNetworks(networks: Array<Network>) =
            record("underlying:${networks.size}")

        override fun setConfigureIntent(intent: PendingIntent) = record("configure")
        override fun setMetered(metered: Boolean) = record("metered:$metered")

        private fun record(call: String) {
            calls += call
        }
    }
}
