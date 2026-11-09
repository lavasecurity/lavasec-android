package com.lavasec.app.vpn.chained

import android.app.PendingIntent
import android.net.Network
import android.net.VpnService
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import com.lavasec.app.foundation.chained.ChainedRoutePlanCollision
import com.lavasec.app.foundation.chained.ChainedUpstreamConfiguration
import com.lavasec.app.vpn.AndroidTunPacketSessionFactory
import com.lavasec.app.vpn.TunEstablishmentFailure
import com.lavasec.app.vpn.TunEstablishmentResult
import com.lavasec.app.vpn.TunPacketSession

/** What [ChainedTunEstablishment.establish] produced. */
internal sealed interface ChainedTunEstablishmentResult {
    /** The TUN is up with [plan] installed and [session] owns the descriptor. */
    data class Established(
        val session: TunPacketSession,
        val plan: ChainedRoutePlan,
    ) : ChainedTunEstablishmentResult

    /** A split-tunnel configuration collides with the tunnel's own addresses; nothing installed. */
    data class Rejected(
        val collisions: List<ChainedRoutePlanCollision>,
    ) : ChainedTunEstablishmentResult

    /** The platform refused the plan or the descriptor. */
    data class Failed(
        val failure: TunEstablishmentFailure,
    ) : ChainedTunEstablishmentResult
}

/**
 * The single entry point that installs the mode-specific route plan and hands back one TUN
 * session.
 *
 * It resolves [ChainedTunPlan] first so the split-tunnel collision guard runs before any
 * platform resource is created, then delegates to the existing
 * [AndroidTunPacketSessionFactory] with the resolved plan. A `null` configuration installs the
 * unchanged DNS-only plan through the same factory, so the chained-inactive lifecycle and the
 * chained one differ only in the plan they pass.
 *
 * Device-gated: `VpnService`, `Network`, and `PendingIntent` require the platform. The
 * installable-vs-rejected decision is [ChainedTunPlan], which is host-JVM testable on its own.
 */
internal object ChainedTunEstablishment {
    fun establish(
        service: VpnService,
        productLabel: String,
        configuration: ChainedUpstreamConfiguration?,
        underlyingNetwork: Network,
        configureIntent: PendingIntent,
        capturedResolverAddresses: List<String> = emptyList(),
    ): ChainedTunEstablishmentResult {
        val plan = when (val resolution = ChainedTunPlan.resolve(configuration, capturedResolverAddresses)) {
            is ChainedTunPlanResolution.Rejected -> {
                return ChainedTunEstablishmentResult.Rejected(resolution.collisions)
            }
            is ChainedTunPlanResolution.Ready -> resolution.plan
        }
        val factory = if (configuration == null) {
            AndroidTunPacketSessionFactory(service, productLabel)
        } else {
            AndroidTunPacketSessionFactory.forService(service, productLabel, plan)
        }
        return when (val result = factory.establish(underlyingNetwork, configureIntent)) {
            is TunEstablishmentResult.Established ->
                ChainedTunEstablishmentResult.Established(result.session, plan)
            is TunEstablishmentResult.Failed ->
                ChainedTunEstablishmentResult.Failed(result.failure)
        }
    }
}
