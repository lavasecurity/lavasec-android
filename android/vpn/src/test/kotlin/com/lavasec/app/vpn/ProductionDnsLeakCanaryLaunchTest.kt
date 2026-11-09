package com.lavasec.app.vpn

import android.app.PendingIntent
import android.net.VpnService
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VerifiedFilterRuntimeLease
import com.lavasec.app.vpn.qa.DnsLeakCanaryArm
import com.lavasec.app.vpn.qa.DnsLeakCanaryChannel
import com.lavasec.app.vpn.qa.DnsLeakCanarySend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the real production start/preflight entry points without a VPN/Plus capability. */
class ProductionDnsLeakCanaryLaunchTest {
    @Test fun `unarmed launch cannot adopt a later arm during its preflight retries or start`() = runBlocking {
        val channel = RecordingChannel()
        val runtime = runtime(channel)
        try {
            runtime.preflight()
            assertEquals(1, channel.captures)
            channel.arm = DnsLeakCanaryArm("a01b02c03d", "192.168.1.53")
            runtime.preflight()
            runtime.start()
            assertEquals(1, channel.captures)
            assertEquals(0, channel.sends)
        } finally { runtime.close() }
        val next = runtime(channel)
        try {
            next.preflight()
            assertEquals(2, channel.captures)
            assertEquals(channel.arm, channel.captured.last())
        } finally { next.close() }
    }

    @Test fun `armed launch captures once before handshake and no-network DNS-only start never sends`() = runBlocking {
        val channel = RecordingChannel().apply { arm = DnsLeakCanaryArm("a01b02c03d", "192.168.1.53") }
        val runtime = runtime(channel)
        try {
            runtime.preflight()
            channel.arm = DnsLeakCanaryArm("a01b02c03e", "192.168.1.54")
            runtime.start()
            assertEquals(listOf(DnsLeakCanaryArm("a01b02c03d", "192.168.1.53")), channel.captured)
            assertEquals(0, channel.sends)
        } finally { runtime.close() }
    }

    private fun runtime(channel: RecordingChannel): ProductionVpnOwnedRuntime {
        val attachment = object : CredentialVpnRuntimeAttachment {
            override val graphGeneration = 12L
            override val configurationIdentity = ConfigurationIdentity(1, "a".repeat(64))
            override val resolverConfiguration = ResolverConfiguration()
            override val activeProtectionSessionId = 1L
            override val configureIntent: PendingIntent get() = error("no TUN establishment")
            override val dnsLeakCanaryChannel = channel
            override fun openFilterRuntimeLease(): VerifiedFilterRuntimeLease = error("no TUN establishment")
            override fun currentPause() = RuntimePauseDirective.Filtering
            override fun requestPause(duration: PauseDuration): PauseState? = null
            override fun requestResume(expectedPause: PauseState?) = false
            override fun tryAcceptTelemetry(telemetry: VpnTelemetry) = false
            override fun detach() = Unit
        }
        val host = object : CredentialVpnHostPort {
            override val graphGeneration = 12L
            override fun openAttachment(request: CredentialVpnAttachRequest) = attachment
        }
        return ProductionVpnOwnedRuntime(VpnService(), VpnOwnedRuntimeRequest(
            owner = VpnServiceOwner(7), origin = StartOrigin.VisibleUser,
            host = CredentialHostSnapshot(12, host), attachment = attachment,
            publication = VpnStatePublication { true },
        ), eligibleNetwork = { null }, observeNetworkAvailability = { AutoCloseable { } })
    }

    private class RecordingChannel : DnsLeakCanaryChannel {
        var arm: DnsLeakCanaryArm? = null
        var captures = 0
        var sends = 0
        val captured = mutableListOf<DnsLeakCanaryArm?>()
        override fun captureArm(): DnsLeakCanaryArm? = arm.also { captures++; captured += it }
        override fun sendIfCurrent(capturedArm: DnsLeakCanaryArm, isCurrent: () -> Boolean,
            send: () -> DnsLeakCanarySend): DnsLeakCanarySend { sends++; return send() }
    }
}
