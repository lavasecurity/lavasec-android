package com.lavasec.app.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.lavasec.app.foundation.chained.ChainedRoutePlan
import java.io.Closeable

internal interface EstablishingTunBuilder : TunBuilder {
    fun establish(): Closeable?
}

internal fun interface EstablishingTunBuilderFactory {
    fun create(): EstablishingTunBuilder
}

/**
 * Consumes [descriptor] on entry, including every exceptional return path.
 *
 * The caller must never close the descriptor after invoking this adapter.
 */
internal fun interface TunPacketSessionAdapter {
    fun adapt(descriptor: Closeable): TunPacketSession
}

internal sealed interface TunEstablishmentFailure {
    data class BuilderCreationFailed(val cause: Throwable) : TunEstablishmentFailure
    data class ConfigurationFailed(val cause: Throwable) : TunEstablishmentFailure
    data class EstablishmentFailed(val cause: Throwable) : TunEstablishmentFailure
    data object DescriptorUnavailable : TunEstablishmentFailure
    data class SessionAdaptationFailed(val cause: Throwable) : TunEstablishmentFailure
}

internal sealed interface TunEstablishmentResult {
    data class Established(val session: TunPacketSession) : TunEstablishmentResult
    data class Failed(val failure: TunEstablishmentFailure) : TunEstablishmentResult
}

/**
 * Applies a reviewed route plan and transfers one established descriptor to one session.
 *
 * The default plan is the unchanged DNS-only plan; a chained caller passes a resolved plan
 * through [forService]. This factory does not select a network, start a runtime, or publish
 * protection state.
 */
internal class AndroidTunPacketSessionFactory(
    productLabel: String,
    private val sdkInt: Int,
    private val builderFactory: EstablishingTunBuilderFactory,
    private val sessionAdapter: TunPacketSessionAdapter = ANDROID_TUN_PACKET_SESSION_ADAPTER,
    private val routePlan: ChainedRoutePlan = ChainedRoutePlan.dnsOnly(),
) {
    private val builderPlan = TunBuilderPlan(productLabel)

    constructor(
        service: VpnService,
        productLabel: String,
    ) : this(
        productLabel = productLabel,
        sdkInt = Build.VERSION.SDK_INT,
        builderFactory = EstablishingTunBuilderFactory {
            AndroidVpnTunBuilder(service.Builder())
        },
    )

    fun establish(
        underlyingNetwork: Network,
        configureIntent: PendingIntent,
    ): TunEstablishmentResult {
        val builder = try {
            builderFactory.create()
        } catch (failure: Exception) {
            return TunEstablishmentResult.Failed(
                TunEstablishmentFailure.BuilderCreationFailed(failure),
            )
        }

        try {
            builderPlan.configure(
                builder = builder,
                underlyingNetwork = underlyingNetwork,
                configureIntent = configureIntent,
                sdkInt = sdkInt,
                plan = routePlan,
            )
        } catch (failure: Exception) {
            return TunEstablishmentResult.Failed(
                TunEstablishmentFailure.ConfigurationFailed(failure),
            )
        }

        val descriptor = try {
            builder.establish()
        } catch (failure: Exception) {
            return TunEstablishmentResult.Failed(
                TunEstablishmentFailure.EstablishmentFailed(failure),
            )
        } ?: return TunEstablishmentResult.Failed(
            TunEstablishmentFailure.DescriptorUnavailable,
        )

        return try {
            TunEstablishmentResult.Established(sessionAdapter.adapt(descriptor))
        } catch (failure: Exception) {
            // The adapter consumes the descriptor on entry. Closing it here would double-close
            // ParcelFileDescriptorTunPacketSession's constructor-failure cleanup.
            TunEstablishmentResult.Failed(
                TunEstablishmentFailure.SessionAdaptationFailed(failure),
            )
        }
    }

    companion object {
        /**
         * A service-backed factory that installs [routePlan] instead of the DNS-only plan.
         *
         * Kept off the constructor surface deliberately: the sole `VpnService`-taking
         * constructor is part of the audited contract that the device SDK level cannot be
         * overridden. Chained callers name the plan here; the DNS-only path keeps using the
         * two-argument constructor and therefore the unchanged DNS-only plan.
         */
        fun forService(
            service: VpnService,
            productLabel: String,
            routePlan: ChainedRoutePlan,
        ): AndroidTunPacketSessionFactory = AndroidTunPacketSessionFactory(
            productLabel = productLabel,
            sdkInt = Build.VERSION.SDK_INT,
            builderFactory = EstablishingTunBuilderFactory {
                AndroidVpnTunBuilder(service.Builder())
            },
            routePlan = routePlan,
        )
    }
}

internal class AndroidVpnTunBuilder(
    private val builder: VpnService.Builder,
) : EstablishingTunBuilder {
    override fun setSession(label: String) {
        builder.setSession(label)
    }

    override fun setMtu(mtu: Int) {
        builder.setMtu(mtu)
    }

    override fun setBlocking(blocking: Boolean) {
        builder.setBlocking(blocking)
    }

    override fun addAddress(address: String, prefix: Int) {
        builder.addAddress(address, prefix)
    }

    override fun addDnsServer(address: String) {
        builder.addDnsServer(address)
    }

    override fun addRoute(address: String, prefix: Int) {
        builder.addRoute(address, prefix)
    }

    override fun allowFamily(family: Int) {
        builder.allowFamily(family)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>) {
        builder.setUnderlyingNetworks(networks)
    }

    override fun setConfigureIntent(intent: PendingIntent) {
        builder.setConfigureIntent(intent)
    }

    @SuppressLint("NewApi")
    override fun setMetered(metered: Boolean) {
        builder.setMetered(metered)
    }

    override fun establish(): Closeable? = builder.establish()
}

private val ANDROID_TUN_PACKET_SESSION_ADAPTER = TunPacketSessionAdapter { descriptor ->
    if (descriptor !is ParcelFileDescriptor) {
        val failure = IllegalArgumentException(
            "VpnService.Builder.establish() did not return a ParcelFileDescriptor",
        )
        try {
            descriptor.close()
        } catch (cleanupFailure: Exception) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
    ParcelFileDescriptorTunPacketSession(descriptor)
}
