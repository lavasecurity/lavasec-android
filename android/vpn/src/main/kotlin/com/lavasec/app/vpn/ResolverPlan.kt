package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverPlanUnavailableReason
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.resolver.BuiltInDohResolverIds
import com.lavasec.app.foundation.resolver.CustomResolverValidator
import com.lavasec.app.foundation.resolver.CatalogResolverTransport
import com.lavasec.app.foundation.resolver.ResolverCatalog
import com.lavasec.app.foundation.resolver.ResolverProviderId
import com.lavasec.app.foundation.resolver.ResolverSelectionEntry
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.foundation.resolver.ResolverSelection
import com.lavasec.app.foundation.resolver.toFilterResolverPlan
import com.lavasec.app.foundation.resolver.toSelection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections

/** Immutable resolver routes selected for one network generation. */
internal class ResolverPlan internal constructor(
    val network: Network,
    val networkGeneration: Long,
    val isCellular: Boolean,
    deviceDnsServers: List<InetAddress>,
    val mullvadEnabled: Boolean,
    val primaryResolverKind: ResolverKind,
    val deviceDnsRequested: Boolean = primaryResolverKind == ResolverKind.DEVICE,
    val primaryResolverIdentity: PrimaryResolverIdentity,
    val fallbackCoverage: ResolverFallbackCoverage,
    /**
     * The normalized custom DoH endpoint URL when the encrypted route is a user-supplied
     * resolver, or null for the pinned Mullvad DoH endpoint. It is the exact URL the DoH
     * runtime connects to; both the socket protector and binder still guard the connection.
     */
    val dohEndpointUrl: String? = null,
    /**
     * The custom DoT endpoint (host and TCP port) when the encrypted route is a user-supplied
     * DoT resolver, or null for the pinned Mullvad DoH endpoint and every built-in route. At most
     * one of [dohEndpointUrl] and this value is ever set; both the socket protector and binder
     * still guard the connection.
     */
    val dotEndpoint: CustomDotEndpoint? = null,
    /**
     * The optional second DoH endpoint of the same custom route, or null when none was set. The
     * runtime tries [dohEndpointUrl] first and falls back to this endpoint. At most one of
     * [dohEndpointUrl] and [dotEndpoint] is ever set, and a secondary requires its primary.
     */
    val dohSecondaryEndpointUrl: String? = null,
    /**
     * The optional second DoT endpoint of the same custom route, or null when none was set. The
     * runtime tries [dotEndpoint] first and falls back to this endpoint.
     */
    val dotSecondaryEndpoint: CustomDotEndpoint? = null,
    val doqEndpoint: CustomDoqEndpoint? = null,
    val doqSecondaryEndpoint: CustomDoqEndpoint? = null,
    orderedRoutes: List<ResolverPlan>? = null,
    configuredTierNumbers: List<Int>? = null,
    val catalogEntry: ResolverSelectionEntry? = null,
    encryptedBootstrapAddresses: List<InetAddress> = emptyList(),
    fixedDnsServers: List<InetAddress> = emptyList(),
    /** Exact retained custom text: stamps keep properties/bootstrap and cannot inherit URL-only health. */
    val customEndpointValue: String? = null,
    val customSecondaryEndpointValue: String? = null,
    encryptedSecondaryBootstrapAddresses: List<InetAddress> = emptyList(),
) {
    init {
        require(listOfNotNull(dohEndpointUrl, dotEndpoint, doqEndpoint).size <= 1) {
            "a resolver plan names at most one custom encrypted endpoint"
        }
        require(dohSecondaryEndpointUrl == null || dohEndpointUrl != null) {
            "a custom DoH secondary requires a custom DoH primary"
        }
        require(dotSecondaryEndpoint == null || dotEndpoint != null) {
            "a custom DoT secondary requires a custom DoT primary"
        }
        require(dohSecondaryEndpointUrl == null || dotSecondaryEndpoint == null) {
            "a resolver plan names at most one custom secondary endpoint"
        }
        require(doqSecondaryEndpoint == null || doqEndpoint != null) {
            "a custom DoQ secondary requires a custom DoQ primary"
        }
    }

    val deviceDnsServers: List<InetAddress> = Collections.unmodifiableList(deviceDnsServers.toList())
    /** Exact active order for routes the old Device-first shape cannot express. Leaves are cold. */
    val orderedRoutes: List<ResolverPlan>? = orderedRoutes?.let { Collections.unmodifiableList(it.toList()) }
    val configuredTierNumbers: List<Int> = Collections.unmodifiableList((configuredTierNumbers ?: orderedRoutes?.indices?.map { it + 1 } ?: listOf(1)).toList())
    val encryptedBootstrapAddresses: List<InetAddress> = Collections.unmodifiableList(encryptedBootstrapAddresses.toList())
    val encryptedSecondaryBootstrapAddresses: List<InetAddress> = Collections.unmodifiableList(encryptedSecondaryBootstrapAddresses.toList())
    val fixedDnsServers: List<InetAddress> = Collections.unmodifiableList(fixedDnsServers.toList())
    val hasNonDeviceRoutes: Boolean get() = mullvadEnabled || primaryResolverKind == ResolverKind.PLAIN_DNS || orderedRoutes?.any { it.primaryResolverKind == ResolverKind.PLAIN_DNS } == true
    val hasDeviceRoute: Boolean get() = deviceDnsServers.isNotEmpty()
    val isCustomDoh: Boolean get() = catalogEntry == null && dohEndpointUrl != null
    val isCustomDot: Boolean get() = catalogEntry == null && dotEndpoint != null
    val isCustomEncrypted: Boolean get() = isCustomDoh || isCustomDot || doqEndpoint != null
}

internal data class CustomDoqEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank())
        require(port in 1..65535 && port != 53) { "DoQ cannot use the ordinary DNS port" }
    }
    val normalized: String get() = "doq://" + if (host.contains(':')) "[$host]:$port" else "$host:$port"
    companion object {
        fun fromNormalized(value: String): CustomDoqEndpoint? =
            CustomResolverValidator.doqHostPort(value)?.let { (host, port) -> CustomDoqEndpoint(host, port) }
    }
}

/** One normalized custom DoT endpoint the runtime connects to: a hostname/IP literal and TCP port. */
internal data class CustomDotEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "DoT host must not be blank" }
        require(port in 1..65535) { "DoT port must be a valid TCP port" }
    }

    /** The normalized `tls://host:port` (IPv6 bracketed) form shared with the persisted value. */
    val normalized: String
        get() = "tls://" + if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        /** Parses the normalized DoT endpoint (`tls://host:port`), or null when it is malformed. */
        fun fromNormalized(value: String): CustomDotEndpoint? =
            CustomResolverValidator.dotHostPort(value)?.let { (host, port) ->
                CustomDotEndpoint(host, port)
            }
    }
}

/** True only for a route shape the production factory can emit as Ready. */
internal fun ResolverPlan.isCanonicalReadyRoute(): Boolean {
    if (!ResolverPlanFactory.isCanonicalCustomMetadata(this)) return false
    orderedRoutes?.let { routes ->
        return routes.size in 1..2 && configuredTierNumbers.size == routes.size && configuredTierNumbers.distinct() == configuredTierNumbers &&
            configuredTierNumbers.all { it in 1..2 } && configuredTierNumbers == configuredTierNumbers.sorted() && routes.all { it.orderedRoutes == null && it.isCanonicalReadyRoute() &&
            it.network === network && it.networkGeneration == networkGeneration && it.isCellular == isCellular } &&
            primaryResolverKind == routes.first().primaryResolverKind &&
            primaryResolverIdentity == routes.first().primaryResolverIdentity &&
            deviceDnsServers == routes.firstOrNull { it.hasDeviceRoute }?.deviceDnsServers.orEmpty() &&
            mullvadEnabled == routes.any { it.mullvadEnabled } &&
            fallbackCoverage == (routes.getOrNull(1)?.let {
                ResolverPlanFactory.coverageFor(it.primaryResolverKind)
            } ?: ResolverFallbackCoverage.NONE)
    }
    return when (primaryResolverKind) {
        ResolverKind.PLAIN_DNS -> !mullvadEnabled && deviceDnsServers.isEmpty() && !deviceDnsRequested &&
            fallbackCoverage == ResolverFallbackCoverage.NONE && ResolverPlanFactory.isCanonicalCatalogRoute(this)
        ResolverKind.WIREGUARD_T0 -> false // Owned exclusively by the chained data path.
        ResolverKind.DEVICE ->
            deviceDnsRequested &&
                deviceDnsServers.isNotEmpty() &&
                primaryResolverIdentity == ResolverPlanFactory.deviceIdentity(deviceDnsServers) &&
                fallbackCoverage == if (mullvadEnabled) {
                    ResolverFallbackCoverage.ENCRYPTED_DNS
                } else {
                    ResolverFallbackCoverage.NONE
                }

        ResolverKind.MULLVAD_DOH -> {
            val identityMatches = when {
                catalogEntry != null -> ResolverPlanFactory.isCanonicalCatalogRoute(this)
                dohEndpointUrl != null ->
                    primaryResolverIdentity == ResolverPlanFactory.customDohIdentity(customEndpointValue ?: dohEndpointUrl, customSecondaryEndpointValue)
                dotEndpoint != null ->
                    primaryResolverIdentity == ResolverPlanFactory.customDotIdentity(customEndpointValue ?: dotEndpoint.normalized, customSecondaryEndpointValue)
                doqEndpoint != null ->
                    primaryResolverIdentity == ResolverPlanFactory.customDoqIdentity(customEndpointValue ?: doqEndpoint.normalized, customSecondaryEndpointValue)
                else -> primaryResolverIdentity == PrimaryResolverIdentity(BuiltInDohResolverIds.MULLVAD)
            }
            mullvadEnabled &&
                deviceDnsServers.isEmpty() &&
                identityMatches &&
                fallbackCoverage == ResolverFallbackCoverage.NONE
        }
    }
}

internal sealed interface ResolverPlanBuildResult {
    data class Ready(val plan: ResolverPlan) : ResolverPlanBuildResult

    data class Unavailable(
        val reason: ResolverPlanUnavailableReason,
    ) : ResolverPlanBuildResult
}

internal object ResolverPlanFactory {
    fun build(
        configuration: ResolverConfiguration,
        networkContext: NetworkContext,
    ): ResolverPlanBuildResult {
        configuration.savedTiers?.let { retained ->
            return orderedPlan(configuration, ResolverSelection(retained), networkContext)
        }
        if (configuration.useEncryptedFallback && configuration.encryptedFallbackId != BuiltInDohResolverIds.MULLVAD &&
            configuration.customDohUrl == null && configuration.customDotEndpoint == null && configuration.customDoqEndpoint == null) {
            return orderedPlan(configuration, configuration.toSelection(), networkContext)
        }

        val selectedDeviceServers = if (configuration.useDeviceDns) {
            DeviceDnsAddressPolicy.select(networkContext.dnsServers, networkContext.isCellular)
        } else {
            emptyList()
        }

        val customDoq = configuration.customDoqEndpoint
            ?.let(CustomResolverValidator::normalizedDoqEndpoint)
            ?.let(CustomDoqEndpoint::fromNormalized)
        if (customDoq != null) return customEncryptedPlan(
            configuration, networkContext, selectedDeviceServers,
            deviceDnsRequested = configuration.useDeviceDns && selectedDeviceServers.isNotEmpty(),
            doqEndpoint = customDoq,
            identity = customDoqIdentity(checkNotNull(configuration.customDoqEndpoint), configuration.customSecondaryEndpoint),
        )

        // A custom DoH endpoint is the encrypted resolver: it reuses the Mullvad DoH route
        // shape (kind, fallback coverage, backoff) but connects to the user's endpoint. Its
        // identity is the normalized URL, so a different custom endpoint cannot inherit
        // identity-scoped health evidence. With Device DNS requested it is the encrypted
        // fallback under the Device primary (the order the ordered ladder names), so the
        // Device route is claimed and the encrypted route carries the user endpoint.
        val customDoh = configuration.customDohUrl?.let(CustomResolverValidator::resolvedDohUrl)
        if (customDoh != null) {
            return customEncryptedPlan(
                configuration = configuration,
                networkContext = networkContext,
                selectedDeviceServers = selectedDeviceServers,
                deviceDnsRequested = configuration.useDeviceDns && selectedDeviceServers.isNotEmpty(),
                dohEndpointUrl = customDoh,
                identity = customDohIdentity(checkNotNull(configuration.customDohUrl), configuration.customSecondaryEndpoint),
            )
        }

        // A custom DoT endpoint is the encrypted resolver: it reuses the same encrypted route
        // shape (kind, fallback coverage, backoff) but opens a TLS socket to the user's endpoint.
        // Its identity is the normalized endpoint, so a different custom endpoint cannot inherit
        // identity-scoped health evidence. The Device-primary case mirrors the custom DoH route.
        val customDot = configuration.customDotEndpoint
            ?.let(CustomResolverValidator::normalizedDotEndpoint)
            ?.let(CustomDotEndpoint::fromNormalized)
        if (customDot != null) {
            return customEncryptedPlan(
                configuration = configuration,
                networkContext = networkContext,
                selectedDeviceServers = selectedDeviceServers,
                deviceDnsRequested = configuration.useDeviceDns && selectedDeviceServers.isNotEmpty(),
                dotEndpoint = customDot,
                identity = customDotIdentity(checkNotNull(configuration.customDotEndpoint), configuration.customSecondaryEndpoint),
            )
        }

        val mullvadEnabled = configuration.useEncryptedFallback

        return when {
            selectedDeviceServers.isNotEmpty() -> ResolverPlanBuildResult.Ready(
                ResolverPlan(
                    network = networkContext.network,
                    networkGeneration = networkContext.networkGeneration,
                    isCellular = networkContext.isCellular,
                    deviceDnsServers = selectedDeviceServers,
                    mullvadEnabled = mullvadEnabled,
                    primaryResolverKind = ResolverKind.DEVICE,
                    deviceDnsRequested = true,
                    primaryResolverIdentity = deviceIdentity(selectedDeviceServers),
                    fallbackCoverage = if (mullvadEnabled) {
                        ResolverFallbackCoverage.ENCRYPTED_DNS
                    } else {
                        ResolverFallbackCoverage.NONE
                    },
                ),
            )

            mullvadEnabled -> ResolverPlanBuildResult.Ready(
                ResolverPlan(
                    network = networkContext.network,
                    networkGeneration = networkContext.networkGeneration,
                    isCellular = networkContext.isCellular,
                    deviceDnsServers = emptyList(),
                    mullvadEnabled = true,
                    primaryResolverKind = ResolverKind.MULLVAD_DOH,
                    deviceDnsRequested = configuration.useDeviceDns,
                    configuredTierNumbers = listOf(if (configuration.useDeviceDns) 2 else 1),
                    primaryResolverIdentity = PrimaryResolverIdentity(BuiltInDohResolverIds.MULLVAD),
                    fallbackCoverage = ResolverFallbackCoverage.NONE,
                ),
            )

            else -> ResolverPlanBuildResult.Unavailable(
                ResolverPlanUnavailableReason.NO_USABLE_RESOLVER,
            )
        }
    }

    private fun orderedPlan(
        configuration: ResolverConfiguration,
        selection: ResolverSelection,
        networkContext: NetworkContext,
    ): ResolverPlanBuildResult {
        val routes = mutableListOf<ResolverPlan>()
        val tierNumbers = mutableListOf<Int>()
        for ((index, tier) in selection.tiers.withIndex().filter { it.value.isEnabled }) {
            when (val result = buildEntry(tier.entry, networkContext)) {
                is ResolverPlanBuildResult.Ready -> { routes += result.plan; tierNumbers += index + 1 }
                is ResolverPlanBuildResult.Unavailable -> {
                    // No device addresses on this exact network is the same promotion rule as
                    // the existing Device-first route. Never skip an unsupported encrypted row.
                    if (result.reason != ResolverPlanUnavailableReason.NO_USABLE_RESOLVER) return result
                }
            }
        }
        if (routes.isEmpty()) return ResolverPlanBuildResult.Unavailable(ResolverPlanUnavailableReason.NO_USABLE_RESOLVER)
        val first = routes.first()
        val encrypted = routes.firstOrNull { it.mullvadEnabled }
        val device = routes.firstOrNull { it.hasDeviceRoute }
        return ResolverPlanBuildResult.Ready(ResolverPlan(
            network = networkContext.network,
            networkGeneration = networkContext.networkGeneration,
            isCellular = networkContext.isCellular,
            deviceDnsServers = device?.deviceDnsServers.orEmpty(),
            mullvadEnabled = encrypted != null,
            primaryResolverKind = first.primaryResolverKind,
            deviceDnsRequested = selection.activeTiers.any { it.entry.providerId == ResolverProviderId.DEVICE_DNS },
            primaryResolverIdentity = first.primaryResolverIdentity,
            fallbackCoverage = routes.getOrNull(1)?.let {
                ResolverPlanFactory.coverageFor(it.primaryResolverKind)
            } ?: ResolverFallbackCoverage.NONE,
            dohEndpointUrl = encrypted?.dohEndpointUrl,
            dotEndpoint = encrypted?.dotEndpoint,
            doqEndpoint = encrypted?.doqEndpoint,
            dohSecondaryEndpointUrl = encrypted?.dohSecondaryEndpointUrl,
            dotSecondaryEndpoint = encrypted?.dotSecondaryEndpoint,
            doqSecondaryEndpoint = encrypted?.doqSecondaryEndpoint,
            customEndpointValue = encrypted?.customEndpointValue,
            customSecondaryEndpointValue = encrypted?.customSecondaryEndpointValue,
            encryptedBootstrapAddresses = encrypted?.encryptedBootstrapAddresses.orEmpty(),
            encryptedSecondaryBootstrapAddresses = encrypted?.encryptedSecondaryBootstrapAddresses.orEmpty(),
            orderedRoutes = routes,
            configuredTierNumbers = tierNumbers,
        ))
    }

    /** Build a leaf directly: catalog identities cannot be flattened through legacy Mullvad fields. */
    private fun buildEntry(entry: ResolverSelectionEntry, context: NetworkContext): ResolverPlanBuildResult {
        if ((entry.providerId == ResolverProviderId.CUSTOM && entry.transport != CatalogResolverTransport.PLAIN_DNS) || entry.providerId == ResolverProviderId.DEVICE_DNS ||
            entry == com.lavasec.app.foundation.resolver.ResolverSelectionPresets.MULLVAD_DOH) {
            val configuration = checkNotNull(ResolverSelection(listOf(com.lavasec.app.foundation.resolver.ResolverTier(entry)))
                .toFilterResolverPlan()).configuration
            return build(configuration, context)
        }
        if (entry.transport == CatalogResolverTransport.PLAIN_DNS) return ResolverPlanBuildResult.Ready(ResolverPlan(
            network = context.network, networkGeneration = context.networkGeneration, isCellular = context.isCellular,
            deviceDnsServers = emptyList(), mullvadEnabled = false, primaryResolverKind = ResolverKind.PLAIN_DNS,
            deviceDnsRequested = false, primaryResolverIdentity = catalogIdentity(entry), fallbackCoverage = ResolverFallbackCoverage.NONE,
            catalogEntry = entry, fixedDnsServers = catalogBootstrapAddresses(entry),
        ))
        val doh = if (entry.transport == CatalogResolverTransport.DOH) ResolverCatalog.dohUrl(entry.provider) else null
        val dot = if (entry.transport == CatalogResolverTransport.DOT) ResolverCatalog.dotEndpoint(entry.provider)
            ?.let(CustomDotEndpoint::fromNormalized) else null
        if (doh == null && dot == null) return ResolverPlanBuildResult.Unavailable(
            ResolverPlanUnavailableReason.UNSUPPORTED_ENCRYPTED_RESOLVER)
        return ResolverPlanBuildResult.Ready(ResolverPlan(
            network = context.network, networkGeneration = context.networkGeneration, isCellular = context.isCellular,
            deviceDnsServers = emptyList(), mullvadEnabled = true, primaryResolverKind = ResolverKind.MULLVAD_DOH,
            deviceDnsRequested = false, primaryResolverIdentity = catalogIdentity(entry),
            fallbackCoverage = ResolverFallbackCoverage.NONE, dohEndpointUrl = doh, dotEndpoint = dot,
            catalogEntry = entry, encryptedBootstrapAddresses = catalogBootstrapAddresses(entry),
        ))
    }

    internal fun coverageFor(kind: ResolverKind): ResolverFallbackCoverage = when (kind) {
        ResolverKind.DEVICE -> ResolverFallbackCoverage.DEVICE_DNS
        ResolverKind.PLAIN_DNS -> ResolverFallbackCoverage.PLAIN_DNS
        ResolverKind.MULLVAD_DOH -> ResolverFallbackCoverage.ENCRYPTED_DNS
        ResolverKind.WIREGUARD_T0 -> ResolverFallbackCoverage.NONE
    }

    internal fun catalogIdentity(entry: ResolverSelectionEntry): PrimaryResolverIdentity {
        val token = when (entry.transport) {
            CatalogResolverTransport.DOH -> "doh:${entry.providerId.rawId}:${checkNotNull(ResolverCatalog.dohUrl(entry.provider))}"
            CatalogResolverTransport.DOT -> "dot:${entry.providerId.rawId}:${checkNotNull(ResolverCatalog.dotEndpoint(entry.provider))}"
            CatalogResolverTransport.PLAIN_DNS -> "ip:${entry.providerId.rawId}:${entry.customPlainEndpoint ?: ResolverCatalog.addresses(entry.provider).joinToString(",")};secondary=${entry.customSecondaryEndpoint.orEmpty()}"
            else -> error("catalog identity requires a served preset transport")
        }
        return PrimaryResolverIdentity("catalog-$token")
    }

    internal fun catalogBootstrapAddresses(entry: ResolverSelectionEntry): List<InetAddress> =
        (entry.customPlainEndpoint?.let { listOf(checkNotNull(CustomResolverValidator.resolvedPlainAddress(it))) }
            ?: ResolverCatalog.addresses(entry.provider))
            .plus(if (entry.transport == CatalogResolverTransport.PLAIN_DNS) entry.customSecondaryEndpoint?.let {
                listOf(checkNotNull(CustomResolverValidator.resolvedPlainAddress(it)))
            }.orEmpty() else emptyList())
            .map(InetAddress::getByName)

    internal fun isCanonicalCatalogRoute(plan: ResolverPlan): Boolean {
        val entry = plan.catalogEntry ?: return false
        if ((entry.providerId == ResolverProviderId.CUSTOM && entry.transport != CatalogResolverTransport.PLAIN_DNS) || entry.providerId == ResolverProviderId.DEVICE_DNS ||
            entry.transport !in setOf(CatalogResolverTransport.PLAIN_DNS, CatalogResolverTransport.DOH, CatalogResolverTransport.DOT)) return false
        if (entry.transport == CatalogResolverTransport.PLAIN_DNS) return plan.primaryResolverIdentity == catalogIdentity(entry) &&
            plan.fixedDnsServers == catalogBootstrapAddresses(entry) && plan.fixedDnsServers.isNotEmpty() &&
            listOfNotNull(plan.dohEndpointUrl, plan.dotEndpoint, plan.doqEndpoint).isEmpty()
        return plan.primaryResolverIdentity == catalogIdentity(entry) &&
            plan.dohEndpointUrl == (if (entry.transport == CatalogResolverTransport.DOH) ResolverCatalog.dohUrl(entry.provider) else null) &&
            plan.dotEndpoint?.normalized == (if (entry.transport == CatalogResolverTransport.DOT) ResolverCatalog.dotEndpoint(entry.provider) else null) &&
            plan.encryptedBootstrapAddresses == catalogBootstrapAddresses(entry) && plan.fixedDnsServers.isEmpty() &&
            plan.dohSecondaryEndpointUrl == null && plan.dotSecondaryEndpoint == null && plan.doqEndpoint == null
    }

    /**
     * One custom encrypted route, either as the encrypted-only primary (when Device DNS is not
     * requested or has no selected servers) or as the encrypted fallback under a Device primary
     * (the ordered-ladder "Device first, custom second" route). The fallback shape reuses the
     * existing Device-plus-encrypted coverage, so the runtime route and the ladder agree.
     */
    private fun customEncryptedPlan(
        configuration: ResolverConfiguration,
        networkContext: NetworkContext,
        selectedDeviceServers: List<InetAddress>,
        deviceDnsRequested: Boolean,
        dohEndpointUrl: String? = null,
        dotEndpoint: CustomDotEndpoint? = null,
        doqEndpoint: CustomDoqEndpoint? = null,
        identity: PrimaryResolverIdentity,
    ): ResolverPlanBuildResult {
        val primaryIsDevice = deviceDnsRequested && selectedDeviceServers.isNotEmpty()
        val retainedPrimary = checkNotNull(configuration.customDohUrl ?: configuration.customDotEndpoint ?: configuration.customDoqEndpoint)
        val retainedSecondary = configuration.customSecondaryEndpoint
        return ResolverPlanBuildResult.Ready(
            ResolverPlan(
                network = networkContext.network,
                networkGeneration = networkContext.networkGeneration,
                isCellular = networkContext.isCellular,
                deviceDnsServers = if (primaryIsDevice) selectedDeviceServers else emptyList(),
                mullvadEnabled = true,
                primaryResolverKind = if (primaryIsDevice) ResolverKind.DEVICE else ResolverKind.MULLVAD_DOH,
                deviceDnsRequested = primaryIsDevice,
                primaryResolverIdentity = if (primaryIsDevice) {
                    deviceIdentity(selectedDeviceServers)
                } else {
                    identity
                },
                fallbackCoverage = if (primaryIsDevice) {
                    ResolverFallbackCoverage.ENCRYPTED_DNS
                } else {
                    ResolverFallbackCoverage.NONE
                },
                dohEndpointUrl = dohEndpointUrl,
                dotEndpoint = dotEndpoint,
                doqEndpoint = doqEndpoint,
                customEndpointValue = retainedPrimary,
                customSecondaryEndpointValue = retainedSecondary,
                encryptedBootstrapAddresses = CustomResolverValidator.bootstrapAddresses(retainedPrimary).map(InetAddress::getByName),
                encryptedSecondaryBootstrapAddresses = retainedSecondary?.let(CustomResolverValidator::bootstrapAddresses)
                    .orEmpty().map(InetAddress::getByName),
                dohSecondaryEndpointUrl = dohEndpointUrl?.let {
                    configuration.customSecondaryEndpoint
                        ?.let(CustomResolverValidator::resolvedDohUrl)
                },
                dotSecondaryEndpoint = dotEndpoint?.let {
                    configuration.customSecondaryEndpoint
                        ?.let(CustomResolverValidator::normalizedDotEndpoint)
                        ?.let(CustomDotEndpoint::fromNormalized)
                },
                doqSecondaryEndpoint = doqEndpoint?.let {
                    configuration.customSecondaryEndpoint
                        ?.let(CustomResolverValidator::normalizedDoqEndpoint)
                        ?.let(CustomDoqEndpoint::fromNormalized)
                },
            ),
        )
    }

    internal fun deviceIdentity(
        selectedDeviceServers: List<InetAddress>,
    ): PrimaryResolverIdentity {
        val ipv4Tokens = selectedDeviceServers.filterIsInstance<Inet4Address>().joinToString(",") { it.address.hexToken() }
        val ipv6Tokens = selectedDeviceServers.filterIsInstance<Inet6Address>().joinToString(",") { it.address.hexToken() }
        return PrimaryResolverIdentity("device-dns:v4=$ipv4Tokens;v6=$ipv6Tokens")
    }

    /** The identity of one custom DoH route: the normalized endpoint URL, never the raw input. */
    internal fun customDohIdentity(normalizedUrl: String, secondary: String? = null): PrimaryResolverIdentity =
        PrimaryResolverIdentity("custom-doh:${retainedIdentity(normalizedUrl, secondary)}")

    /** The identity of one custom DoT route: the normalized endpoint, never the raw input. */
    internal fun customDotIdentity(normalizedEndpoint: String, secondary: String? = null): PrimaryResolverIdentity =
        PrimaryResolverIdentity("custom-dot:${retainedIdentity(normalizedEndpoint, secondary)}")

    internal fun customDoqIdentity(normalizedEndpoint: String, secondary: String? = null): PrimaryResolverIdentity =
        PrimaryResolverIdentity("custom-doq:${retainedIdentity(normalizedEndpoint, secondary)}")

    private fun retainedIdentity(primary: String, secondary: String?): String = primary +
        if (primary.startsWith("sdns://", ignoreCase = true) || secondary?.startsWith("sdns://", ignoreCase = true) == true)
            ";secondary=${secondary.orEmpty()}" else ""

    internal fun isCanonicalCustomMetadata(plan: ResolverPlan): Boolean {
        val raw = plan.customEndpointValue ?: return plan.customSecondaryEndpointValue == null &&
            plan.encryptedSecondaryBootstrapAddresses.isEmpty()
        val parsed = CustomResolverValidator.parse(raw) ?: return false
        val matches = when (parsed.transport) {
            com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOH -> plan.dohEndpointUrl == parsed.resolvedEndpoint &&
                plan.dotEndpoint == null && plan.doqEndpoint == null
            com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOT -> plan.dotEndpoint == CustomDotEndpoint.fromNormalized(raw) &&
                plan.dohEndpointUrl == null && plan.doqEndpoint == null
            com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOQ -> plan.doqEndpoint == CustomDoqEndpoint.fromNormalized(raw) &&
                plan.dohEndpointUrl == null && plan.dotEndpoint == null
            else -> false
        }
        if (!matches || plan.encryptedBootstrapAddresses != parsed.bootstrapAddresses.map(InetAddress::getByName)) return false
        val secondary = plan.customSecondaryEndpointValue
        if (secondary == null) return plan.encryptedSecondaryBootstrapAddresses.isEmpty() &&
            plan.dohSecondaryEndpointUrl == null && plan.dotSecondaryEndpoint == null && plan.doqSecondaryEndpoint == null
        val parsedSecondary = CustomResolverValidator.parse(secondary) ?: return false
        if (parsedSecondary.transport != parsed.transport ||
            plan.encryptedSecondaryBootstrapAddresses != parsedSecondary.bootstrapAddresses.map(InetAddress::getByName)) return false
        return when (parsed.transport) {
            com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOH -> plan.dohSecondaryEndpointUrl == parsedSecondary.resolvedEndpoint
            com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOT -> plan.dotSecondaryEndpoint == CustomDotEndpoint.fromNormalized(secondary)
            com.lavasec.app.foundation.resolver.ParsedCustomResolverTransport.DOQ -> plan.doqSecondaryEndpoint == CustomDoqEndpoint.fromNormalized(secondary)
            else -> false
        }
    }

    private fun ByteArray.hexToken(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
