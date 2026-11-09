package com.lavasec.app.foundation.resolver

import java.util.Collections

/** Frozen iOS provider identities and exact endpoint data shared by selection and runtime. */
object ResolverCatalog {
    val deviceDns = ResolverProvider(
        id = ResolverProviderId.DEVICE_DNS,
        displayName = "Device DNS",
        privacySummary = "Uses the DNS resolver from the current Wi-Fi or cellular network while Lava still filters locally.",
        supportedTransports = setOf(CatalogResolverTransport.PLAIN_DNS),
        endpoints = emptyList(),
        // Device plain DNS is runtime-served and may occupy either rung of the ordered ladder,
        // so it is eligible as a second tier just like the encrypted transports.
        fallbackEligible = true,
        requiresEntitlement = false,
        recommended = true,
    )

    val mullvad = ResolverProvider(
        id = ResolverProviderId.MULLVAD,
        displayName = "Mullvad",
        privacySummary = "Privacy-focused resolver from Mullvad VPN with no logging and DNSSEC validation.",
        supportedTransports = setOf(
            CatalogResolverTransport.PLAIN_DNS,
            CatalogResolverTransport.DOH,
            CatalogResolverTransport.DOT,
        ),
        endpoints = listOf(
            ResolverEndpoint.Doh("https://dns.mullvad.net/dns-query"),
            ResolverEndpoint.HostPort("dns.mullvad.net", 853),
            ResolverEndpoint.Addresses(listOf("194.242.2.2"), listOf("2a07:e340::2")),
        ),
        fallbackEligible = true,
        requiresEntitlement = false,
    )

    val quad9Unfiltered = ResolverProvider(
        id = ResolverProviderId.QUAD9_UNFILTERED,
        displayName = "Quad9",
        privacySummary = "Quad9 resolver without upstream threat blocking; Lava applies your local filtering rules.",
        supportedTransports = setOf(CatalogResolverTransport.PLAIN_DNS, CatalogResolverTransport.DOH, CatalogResolverTransport.DOT),
        endpoints = listOf(
            ResolverEndpoint.Doh("https://dns10.quad9.net/dns-query"),
            ResolverEndpoint.HostPort("dns10.quad9.net", 853),
            ResolverEndpoint.Addresses(listOf("9.9.9.10"), listOf("2620:fe::10")),
        ),
        fallbackEligible = true,
        requiresEntitlement = false,
    )

    val hagezi = ResolverProvider(
        id = ResolverProviderId.HAGEZI_ROOT,
        displayName = "HaGeZi DNS",
        privacySummary = "Includes upstream ads, tracking, malware and phishing filtering.",
        supportedTransports = setOf(CatalogResolverTransport.PLAIN_DNS, CatalogResolverTransport.DOH, CatalogResolverTransport.DOT),
        endpoints = listOf(
            ResolverEndpoint.Doh("https://root.hagezi.org/dns-query"),
            ResolverEndpoint.HostPort("root.hagezi.org", 853),
            ResolverEndpoint.Addresses(listOf("188.34.161.210"), listOf("2a01:4f8:c17:1c66::1")),
        ),
        fallbackEligible = true,
        requiresEntitlement = false,
    )

    val cloudflare = ResolverProvider(
        id = ResolverProviderId.CLOUDFLARE,
        displayName = "Cloudflare 1.1.1.1",
        privacySummary = "Fast privacy-oriented resolver with no content filtering in the standard preset.",
        supportedTransports = setOf(
            CatalogResolverTransport.PLAIN_DNS,
            CatalogResolverTransport.DOH,
            CatalogResolverTransport.DOT,
        ),
        endpoints = listOf(
            ResolverEndpoint.Doh("https://cloudflare-dns.com/dns-query"),
            ResolverEndpoint.HostPort("one.one.one.one", 853),
            ResolverEndpoint.Addresses(
                listOf("1.1.1.1", "1.0.0.1"),
                listOf("2606:4700:4700::1111", "2606:4700:4700::1001"),
            ),
        ),
        fallbackEligible = true,
        requiresEntitlement = false,
    )

    val quad9Secure = ResolverProvider(
        id = ResolverProviderId.QUAD9_SECURE,
        displayName = "Quad9 Secure",
        privacySummary = "Includes Quad9 upstream threat blocking.",
        supportedTransports = setOf(
            CatalogResolverTransport.PLAIN_DNS,
            CatalogResolverTransport.DOH,
            CatalogResolverTransport.DOT,
        ),
        endpoints = listOf(
            ResolverEndpoint.Doh("https://dns.quad9.net/dns-query"),
            ResolverEndpoint.HostPort("dns.quad9.net", 853),
            ResolverEndpoint.Addresses(
                listOf("9.9.9.9", "149.112.112.112"),
                listOf("2620:fe::fe", "2620:fe::9"),
            ),
        ),
        fallbackEligible = true,
        requiresEntitlement = false,
    )

    val googlePublicDns = ResolverProvider(
        id = ResolverProviderId.GOOGLE_PUBLIC_DNS,
        displayName = "Google Public DNS",
        privacySummary = "Default unfiltered resolver.",
        supportedTransports = setOf(
            CatalogResolverTransport.PLAIN_DNS,
            CatalogResolverTransport.DOH,
            CatalogResolverTransport.DOT,
        ),
        endpoints = listOf(
            ResolverEndpoint.Doh("https://dns.google/dns-query"),
            ResolverEndpoint.HostPort("dns.google", 853),
            ResolverEndpoint.Addresses(
                listOf("8.8.8.8", "8.8.4.4"),
                listOf("2001:4860:4860::8888", "2001:4860:4860::8844"),
            ),
        ),
        fallbackEligible = true,
        requiresEntitlement = false,
    )

    val custom = ResolverProvider(
        id = ResolverProviderId.CUSTOM,
        displayName = "Custom DNS",
        privacySummary = "Use your own resolver.",
        supportedTransports = CatalogResolverTransport.entries.toSet(),
        endpoints = emptyList(),
        // A custom DoH/DoT resolver may be the primary or the encrypted second tier, so it is
        // eligible as a second tier too (the runtime serves it as the encrypted fallback under a
        // Device DNS primary).
        fallbackEligible = true,
        requiresEntitlement = true,
    )

    /** Stable display order: Device first (the policy-recommended default), then iOS order. */
    val providers: List<ResolverProvider> = Collections.unmodifiableList(
        listOf(deviceDns, quad9Unfiltered, cloudflare, hagezi, googlePublicDns, custom),
    )

    /** Read-only legacy authorities keep their actual endpoint until an explicit migration. */
    val legacyProviders: List<ResolverProvider> = Collections.unmodifiableList(listOf(mullvad, quad9Secure))

    fun presetId(entry: ResolverSelectionEntry): String = entry.providerId.rawId + when (entry.transport) {
        CatalogResolverTransport.DOH -> if (entry.providerId == ResolverProviderId.CUSTOM) "" else "-doh"
        CatalogResolverTransport.DOT -> if (entry.providerId == ResolverProviderId.CUSTOM) "" else "-dot"
        else -> ""
    }

    /** Portable preset lookup. Retired aliases migrate only when the caller explicitly permits it. */
    fun entryForPresetId(id: String, migrateRetired: Boolean = true): ResolverSelectionEntry? {
        val transport = when {
            id.endsWith("-doh") -> CatalogResolverTransport.DOH
            id.endsWith("-dot") -> CatalogResolverTransport.DOT
            else -> CatalogResolverTransport.PLAIN_DNS
        }
        val base = when (transport) {
            CatalogResolverTransport.DOH -> id.removeSuffix("-doh")
            CatalogResolverTransport.DOT -> id.removeSuffix("-dot")
            else -> id
        }
        val retired = base in setOf("dns-sb", "mullvad", "quad9-secure")
        if (retired && !migrateRetired) return null
        val canonical = if (retired) ResolverProviderId.QUAD9_UNFILTERED.rawId else base
        val provider = providers.firstOrNull { it.id.rawId == canonical && it.id != ResolverProviderId.CUSTOM }
            ?: return null
        if (!capability(provider, transport).available) return null
        return ResolverSelectionEntry(provider.id, transport)
    }

    fun dohUrl(provider: ResolverProvider): String? = provider.endpoints.filterIsInstance<ResolverEndpoint.Doh>().singleOrNull()?.url
    fun dotEndpoint(provider: ResolverProvider): String? = provider.endpoints.filterIsInstance<ResolverEndpoint.HostPort>()
        .singleOrNull()?.let { "tls://${it.hostname}:${it.port}" }
    fun addresses(provider: ResolverProvider): List<String> = provider.endpoints.filterIsInstance<ResolverEndpoint.Addresses>()
        .singleOrNull()?.let { it.ipv4 + it.ipv6 }.orEmpty()

    /**
     * The policy-recommended provider for the milestone: the default plan
     * (Device DNS with encrypted fallback) keeps Device DNS as its primary, so
     * the recommended badge belongs to Device DNS and to nothing else. The
     * badge must only render for this provider.
     */
    val recommendedProvider: ResolverProvider = deviceDns

    fun provider(id: ResolverProviderId): ResolverProvider =
        (providers + legacyProviders).firstOrNull { it.id == id }
            ?: error("unknown resolver provider id: $id")

    fun capability(
        provider: ResolverProvider,
        transport: CatalogResolverTransport,
        customDohUrl: String? = null,
        customDotEndpoint: String? = null,
        customDoqEndpoint: String? = null,
        customPlainEndpoint: String? = null,
    ): ResolverTransportCapability {
        if (provider.id == ResolverProviderId.CUSTOM) {
            // The runtime serves a custom DoH resolver when the selection carries a
            // validated normalized URL, and a custom DoT resolver when it carries a
            // validated normalized endpoint, and DoQ when it carries a canonical authority.
            // Plain custom DNS carries an IP-literal list, never a device-network DNS alias.
            // A transport missing its endpoint cannot be selected.
            val available = when (transport) {
                CatalogResolverTransport.DOH ->
                    customDohUrl != null &&
                        customDotEndpoint == null && customDoqEndpoint == null && customPlainEndpoint == null &&
                        CustomResolverValidator.normalizedDohUrl(customDohUrl) != null
                CatalogResolverTransport.DOT ->
                    customDotEndpoint != null &&
                        customDohUrl == null && customDoqEndpoint == null && customPlainEndpoint == null &&
                        CustomResolverValidator.normalizedDotEndpoint(customDotEndpoint) != null
                CatalogResolverTransport.DOQ -> customDoqEndpoint != null &&
                    customDohUrl == null && customDotEndpoint == null && customPlainEndpoint == null &&
                    CustomResolverValidator.normalizedDoqEndpoint(customDoqEndpoint) == customDoqEndpoint
                CatalogResolverTransport.PLAIN_DNS -> customPlainEndpoint != null &&
                    listOfNotNull(customDohUrl, customDotEndpoint, customDoqEndpoint).isEmpty() &&
                    CustomResolverValidator.normalizedPlainEndpoint(customPlainEndpoint) == customPlainEndpoint
            }
            return if (available) {
                ResolverTransportCapability.available()
            } else {
                ResolverTransportCapability.unavailable(
                    ResolverTransportUnavailableReason.CUSTOM_RESOLVER_RUNTIME_ABSENT,
                )
            }
        }
        return when (transport) {
            CatalogResolverTransport.PLAIN_DNS -> if (provider.id == ResolverProviderId.DEVICE_DNS || addresses(provider).isNotEmpty()) {
                ResolverTransportCapability.available()
            } else ResolverTransportCapability.unavailable(ResolverTransportUnavailableReason.PLAIN_PROVIDER_ENDPOINTS_UNSERVED)
            CatalogResolverTransport.DOH -> if (transport in provider.supportedTransports && dohUrl(provider) != null) {
                ResolverTransportCapability.available()
            } else ResolverTransportCapability.unavailable(ResolverTransportUnavailableReason.RUNTIME_SERVES_ONLY_MULLVAD_DOH)
            CatalogResolverTransport.DOT -> if (transport in provider.supportedTransports && dotEndpoint(provider) != null) {
                ResolverTransportCapability.available()
            } else ResolverTransportCapability.unavailable(ResolverTransportUnavailableReason.DOT_RUNTIME_ABSENT)
            // The frozen built-in catalog contains no DoQ preset. Custom DoQ is independently served.
            CatalogResolverTransport.DOQ -> ResolverTransportCapability.unavailable(ResolverTransportUnavailableReason.DOQ_RUNTIME_ABSENT)
        }
    }

    /**
     * The transports the Settings transport group renders for one provider, in
     * the stable iOS menu order (IP, DoH, DoT, DoQ), each with its runtime
     * capability. A provider whose only transport is served renders exactly
     * one row; everything else renders with an explicit unavailability reason.
     */
    fun transportRows(provider: ResolverProvider): List<Pair<CatalogResolverTransport, ResolverTransportCapability>> =
        provider.supportedTransports.sortedBy(CatalogResolverTransport.entries::indexOf)
            .map { transport -> transport to capability(provider, transport) }
}

/** Stable catalog identity; raw ids mirror the iOS `DNSResolverPreset` ids. */
enum class ResolverProviderId(val rawId: String) {
    DEVICE_DNS("device-dns"),
    MULLVAD("mullvad"),
    CLOUDFLARE("cloudflare-1111"),
    QUAD9_SECURE("quad9-secure"),
    GOOGLE_PUBLIC_DNS("google-public-dns"),
    CUSTOM("custom-dns"),
    QUAD9_UNFILTERED("quad9-unfiltered"),
    HAGEZI_ROOT("hagezi-root"),
}

/**
 * User-facing resolver transports, mirroring the iOS resolver transport menu.
 * [token] is the stable menu abbreviation (iOS `menuTitle`): a technical token,
 * identical across locales, rendered as product data.
 */
enum class CatalogResolverTransport(val token: String) {
    PLAIN_DNS("IP"),
    DOH("DoH"),
    DOT("DoT"),
    DOQ("DoQ"),
}

/** Exact endpoint metadata; runtime routes retain this endpoint identity. */
sealed interface ResolverEndpoint {
    data class Doh(val url: String) : ResolverEndpoint {
        init {
            require(url.startsWith("https://")) { "DoH endpoints must use https" }
        }
    }

    data class HostPort(val hostname: String, val port: Int) : ResolverEndpoint {
        init {
            require(hostname.isNotBlank()) { "hostname must not be blank" }
            require(port in 1..65535) { "port must be a valid TCP/UDP port" }
        }
    }

    data class Addresses(val ipv4: List<String>, val ipv6: List<String>) : ResolverEndpoint
}

/** Product-data provider record with the exact milestone fallback/entitlement facts. */
data class ResolverProvider(
    val id: ResolverProviderId,
    val displayName: String,
    val privacySummary: String,
    val supportedTransports: Set<CatalogResolverTransport>,
    val endpoints: List<ResolverEndpoint>,
    val fallbackEligible: Boolean,
    val requiresEntitlement: Boolean,
    val recommended: Boolean = false,
) {
    init {
        require(displayName.isNotBlank()) { "provider display name must not be blank" }
        require(supportedTransports.isNotEmpty()) { "a provider must offer at least one transport" }
    }
}

/** Typed reason a provider/transport pair cannot be served by the milestone runtime. */
enum class ResolverTransportUnavailableReason {
    /**
     * The engine's only encrypted route is the pinned Mullvad DoH endpoint;
     * every other built-in DoH id is rejected by ResolverPlanFactory as
     * UNSUPPORTED_ENCRYPTED_RESOLVER.
     */
    RUNTIME_SERVES_ONLY_MULLVAD_DOH,

    /** The runtime reads only the network's own DNS servers, never fixed provider addresses. */
    PLAIN_PROVIDER_ENDPOINTS_UNSERVED,

    /** No DoT transport exists in the milestone engine. */
    DOT_RUNTIME_ABSENT,

    /** No DoQ transport exists in the milestone engine. */
    DOQ_RUNTIME_ABSENT,

    /** The coordinator and engine have no custom-resolver route yet. */
    CUSTOM_RESOLVER_RUNTIME_ABSENT,
}

/** One provider/transport pair's runtime capability: served, or unavailable with a reason. */
data class ResolverTransportCapability(
    val available: Boolean,
    val unavailableReason: ResolverTransportUnavailableReason? = null,
) {
    init {
        require(available == (unavailableReason == null)) {
            "capability must be served or carry exactly one unavailability reason"
        }
    }

    companion object {
        fun available() = ResolverTransportCapability(true)

        fun unavailable(reason: ResolverTransportUnavailableReason) =
            ResolverTransportCapability(false, reason)
    }
}
