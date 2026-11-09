package com.lavasec.app.vpn.qa

import android.net.Network
import android.net.VpnService

/** Release has no active emitter, irrespective of retained QA preferences. */
internal object DnsLeakCanaryEmitterFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(service: VpnService, network: Network, arm: DnsLeakCanaryArm,
        serviceGeneration: Long, nowMillis: () -> Long): PreparedDnsLeakCanary? = null
}
