package com.lavasec.app.foundation.chained

import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections

/**
 * One IPv4 route the tunnel claims, as the network address plus prefix length Android's
 * [android.net.VpnService.Builder.addRoute] takes.
 *
 * Deliberately a prefix length, not an iOS-style dotted mask: Android's TUN builder speaks
 * `address/length`, and storing the platform's own shape here keeps the eventual translation
 * a field copy rather than an arithmetic step that can disagree with the plan. The address is
 * always the NETWORK address of the block (host bits masked off), so `10.1.2.3/8` and
 * `10.0.0.0/8` cannot describe the same block two ways.
 */
data class IPv4Route(val destinationAddress: String, val prefixLength: Int) {
    /** The route in the `address/length` form used by device logs and the Android builder. */
    val cidr: String get() = "$destinationAddress/$prefixLength"
}

/**
 * One IPv6 route the tunnel claims.
 *
 * IPv6 is claimed for exactly one reason in this engine: to be DROPPED. The full-tunnel plan
 * installs `::/0` so a dual-stack device cannot send IPv6 around a tunnel that only carries
 * IPv4 (`INV-CHAIN-1`). No other plan claims a v6 route.
 */
data class IPv6Route(val destinationAddress: String, val prefixLength: Int) {
    /** The route in the `address/length` form used by device logs. */
    val cidr: String get() = "$destinationAddress/$prefixLength"
}

/**
 * A routing collision the split-tunnel plan must refuse rather than install.
 *
 * Log/health identifiers, never user copy. The route plan is a pure description; the boundary
 * that owns configuration decides whether a collision is fatal. Exposing the reasons as a
 * typed vocabulary lets the caller name the exact condition in a device log instead of
 * "invalid configuration".
 */
enum class ChainedRoutePlanCollision {
    /**
     * An `AllowedIPs` prefix covers the DNS-proxy host route `10.111.0.1/32`.
     *
     * The split plan installs the DNS-capture route BESIDE the user's prefixes, and the two
     * claims would disagree about the proxy host: one says "user traffic", the other says
     * "the local resolver". Overlap is also how a prefix covering the proxy silently subsumes
     * the capture route, so the guard is checked before the plan is installed. The check is
     * against the INSTALLED `/32` host route, not a hypothetical `/24`, so a prefix disjoint
     * from the proxy (e.g. `10.111.0.128/25`) is not rejected.
     */
    ALLOWED_IPS_OVERLAPS_DNS_CAPTURE,

    /**
     * An `AllowedIPs` entry is EXACTLY the client's own `/32` host route.
     *
     * Only an exact host route to the interface's own address is flagged. A prefix that merely
     * CONTAINS the client (the ordinary case: client `10.7.0.2` with `AllowedIPs = 10.7.0.0/24`)
     * is the peer-side subnet the tunnel must carry and must stay usable — assigning the client
     * `/32` already makes that host local, so the containing route is not a loop.
     */
    ALLOWED_IPS_COVERS_CLIENT_ADDRESS,
}

/**
 * The routing shape the tunnel claims for a given lifecycle.
 *
 * A pure description, deliberately free of `android.net.VpnService` types so the decision is
 * testable on the JVM without a device or an Android runtime; `LavaVpnService` maps it onto
 * `VpnService.Builder` and decides nothing of its own. That split is the Android mirror of iOS
 * `TunnelRoutePlan.swift`: keeping the DECISION here means the DNS-only branch is the single
 * description of the routing that shipped before chaining existed, so a drift in the routes
 * every non-chaining user gets is caught by this type's tests rather than by a device.
 *
 * Three shapes exist, selected by [make]:
 *
 * - **Inactive (DNS-only)** — the pre-chaining tunnel. Claim only the DNS-proxy `/32`, no
 *   IPv6, tunnel numbered `10.111.0.2`, MTU 1280. DNS is filtered on everything.
 * - **Full tunnel** — claim `0.0.0.0/0` and `::/0`, number the interface with the configured
 *   `[Interface] Address`, and run at the configured MTU (or 1280). Claiming `::/0` with an
 *   IPv4-only upstream is what makes IPv6 fail CLOSED (packets enter the tunnel and are
 *   dropped) instead of leaking around it.
 * - **Split tunnel** — claim exactly the configured IPv4 `AllowedIPs` prefixes plus the resolver host
 *   routes in the captured floor and both DNS listeners. IPv6 outside the DNS ULA and
 *   resolver floor egresses direct by design.
 *
 * @property tunnelAddress the address assigned to the tunnel interface.
 * @property tunnelPrefixLength the prefix length paired with [tunnelAddress] (`32` in every
 *   mode: the config carries a bare host address and the DNS-only contract also installs a host
 *   address; nothing says the surrounding subnet is on-link).
 * @property dnsServerAddress the address the local DNS proxy answers on, in every mode.
 * @property routeDescription a human-readable summary of the claimed routes for device logs.
 * @property includedIPv4Routes the IPv4 routes claimed; DNS-only claims the DNS-proxy `/32`.
 * @property tunnelIPv6Address the interface's IPv6 address, or `null` when no IPv6 is claimed.
 * @property tunnelIPv6PrefixLength the prefix length for [tunnelIPv6Address].
 * @property includedIPv6Routes the DNS ULA/floor in split, or `::/0` in full.
 * @property mtu the interface MTU.
 */
@ConsistentCopyVisibility
data class ChainedRoutePlan private constructor(
    val tunnelAddress: String,
    val tunnelPrefixLength: Int,
    val dnsServerAddress: String,
    val routeDescription: String,
    val includedIPv4Routes: List<IPv4Route>,
    val tunnelIPv6Address: String?,
    val tunnelIPv6PrefixLength: Int?,
    val includedIPv6Routes: List<IPv6Route>,
    val mtu: Int,
    val dnsCaptureFloor: ChainedDnsCaptureFloor,
    val dnsServerIPv6Address: String?,
) {
    /**
     * Whether this plan takes the whole IPv4 space. Derived from the routes rather than stored,
     * so a flag cannot drift from the claims it describes, and computed by UNION COVERAGE rather
     * than a literal `/0` match — a plan built directly with `0.0.0.0/1` + `128.0.0.0/1` captures
     * all IPv4 traffic and must be recognized, or [leaksIPv6AroundTheTunnel] would miss it.
     */
    val claimsDefaultRoute: Boolean
        get() = ChainedUpstreamConfigurationValidator.coversIPv4DefaultRoute(
            includedIPv4Routes.map { it.cidr },
        )

    /**
     * Whether this plan takes the whole IPv6 space. Derived for the same reason as
     * [claimsDefaultRoute], and computed by UNION COVERAGE rather than a literal `/0` match, so a
     * plan built with `::/1` + `8000::/1` is recognized and does not read as a leak.
     */
    val claimsIPv6DefaultRoute: Boolean
        get() = coversIpv6DefaultRoute(includedIPv6Routes)

    /**
     * Whether the interface carries IPv6 at all — an address, any route, or both.
     *
     * Keyed on PRESENCE, not on the default route: RFC 8200's 1280 floor applies to a link that
     * carries IPv6, not only to one that carries all of it. Checking the specific shape the
     * current factory emits is how a guard passes while the invariant it names is violated.
     */
    val carriesIPv6: Boolean
        get() = tunnelIPv6Address != null || includedIPv6Routes.isNotEmpty()

    /**
     * Whether the plan's MTU is legal for the address families it claims.
     *
     * Only meaningful once IPv6 is claimed. The configuration boundary refuses a sub-1280 MTU
     * in both chained modes (the engine's packet-queue limits reject it), so a sub-floor plan
     * is never built; this predicate is the second, plan-level guard that a future factory
     * cannot bypass.
     */
    val mtuIsLegalForClaimedFamilies: Boolean
        get() = !carriesIPv6 || mtu >= MINIMUM_IPV6_LINK_MTU

    /**
     * True when the plan carries the whole IPv4 space but leaves IPv6 on the physical
     * interface — the shape of an IPv6 leak in FULL tunnel.
     *
     * Scoped to full tunnel by the [claimsDefaultRoute] term, and that scoping is the point: a
     * full tunnel promises to carry everything, so claiming all IPv4 while leaving IPv6 direct
     * is traffic escaping the tunnel the user turned on. A SPLIT tunnel does not claim the whole
     * IPv4 space, so this correctly returns `false` for it without special-casing — in split
     * mode the protected set is exactly `AllowedIPs`, and IPv6 egressing direct is INTENDED
     * (`INV-CHAIN-1` split clause), not a leak. Mirrors iOS `leaksIPv6AroundTheTunnel`.
     */
    val leaksIPv6AroundTheTunnel: Boolean
        get() = claimsDefaultRoute && !claimsIPv6DefaultRoute

    companion object {
        /**
         * Builds a plan, copying both route lists into unmodifiable snapshots.
         *
         * The constructor is private so a Java caller (or a Kotlin cast) cannot hand in a mutable
         * list and then clear it after construction, which would change the routes the builder
         * installs and the derived `claimsDefaultRoute`/`leaksIPv6AroundTheTunnel` while
         * [routeDescription] stayed stale.
         */
        @Suppress("LongParameterList")
        fun of(
            tunnelAddress: String,
            tunnelPrefixLength: Int,
            dnsServerAddress: String,
            routeDescription: String,
            includedIPv4Routes: List<IPv4Route>,
            tunnelIPv6Address: String?,
            tunnelIPv6PrefixLength: Int?,
            includedIPv6Routes: List<IPv6Route>,
            mtu: Int,
            dnsCaptureFloor: ChainedDnsCaptureFloor = ChainedDnsCaptureFloor.empty,
            dnsServerIPv6Address: String? = null,
        ): ChainedRoutePlan {
            require(includedIPv4Routes.containsAll(dnsCaptureFloor.ipv4Routes) &&
                includedIPv6Routes.containsAll(dnsCaptureFloor.ipv6Routes)
            ) {
                "classifier resolver claims must be installed as the same host routes"
            }
            require(dnsCaptureFloor.ipv6Routes.isEmpty() || dnsServerIPv6Address != null) {
                "an IPv6 resolver capture must have the served client DNS path"
            }
            require(dnsServerIPv6Address == null || (tunnelIPv6Address != null && includedIPv6Routes.isNotEmpty())) {
                "an advertised IPv6 DNS listener must be reachable through installed routes"
            }
            return ChainedRoutePlan(
                tunnelAddress = tunnelAddress,
                tunnelPrefixLength = tunnelPrefixLength,
                dnsServerAddress = dnsServerAddress,
                routeDescription = routeDescription,
                includedIPv4Routes = Collections.unmodifiableList(includedIPv4Routes.toList()),
                tunnelIPv6Address = tunnelIPv6Address,
                tunnelIPv6PrefixLength = tunnelIPv6PrefixLength,
                includedIPv6Routes = Collections.unmodifiableList(includedIPv6Routes.toList()),
                mtu = mtu,
                dnsCaptureFloor = dnsCaptureFloor,
                dnsServerIPv6Address = dnsServerIPv6Address,
            )
        }

        /**
         * Address the local DNS proxy answers on, in every mode.
         *
         * Shared with [ChainedUpstreamConfiguration] rather than re-spelled: the configuration
         * boundary already refuses a client address equal to it, and two spelling sites could
         * drift so that the collision check guarded one address while the tunnel used another.
         */
        const val DNS_SERVER_ADDRESS: String = ChainedUpstreamConfiguration.DNS_SERVER_ADDRESS

        /**
         * The installed DNS-proxy host route in `address/length` form, used by the collision guard.
         *
         * The ONLY DNS route installed is [DNS_PROXY_ROUTE] (`10.111.0.1/32`), matching the
         * shipped Android DNS-only contract. The guard compares against exactly that host, so an
         * `AllowedIPs` prefix disjoint from the proxy (e.g. `10.111.0.128/25`) is not rejected —
         * only a prefix that actually covers the proxy collides.
         */
        const val DNS_CAPTURE_PREFIX: String = "10.111.0.1/32"

        /**
         * A route covering only the DNS proxy host.
         *
         * Split mode adds this beside the `AllowedIPs` prefixes: a narrow `AllowedIPs` set need
         * not include the proxy, and a route is what keeps resolver traffic inside the tunnel
         * even then. It is a `/32`, not the whole capture `/24`, so a split plan claims no more
         * of the DNS subnet than it must.
         */
        val DNS_PROXY_ROUTE: IPv4Route = IPv4Route(DNS_SERVER_ADDRESS, 32)

        /** The DNS-only tunnel interface address, matching the shipped DNS-only contract. */
        const val DNS_ONLY_TUNNEL_ADDRESS: String = "10.111.0.2"

        /** The DNS-only tunnel prefix length, matching the shipped `/32` contract. */
        const val DNS_ONLY_TUNNEL_PREFIX_LENGTH: Int = 32

        /** MTU for the DNS-only path — unchanged from the pre-chaining tunnel. */
        const val DNS_ONLY_MTU: Int = 1280

        /**
         * The smallest link MTU IPv6 permits (RFC 8200 §5).
         *
         * A hard floor, not a tuning knob: an interface carrying IPv6 below this is out of
         * spec, and Android may refuse the TUN configuration rather than degrade. Shared with
         * the configuration boundary so the value cannot drift between validation and plan.
         */
        const val MINIMUM_IPV6_LINK_MTU: Int = ChainedUpstreamConfiguration.MINIMUM_IPV6_LINK_MTU

        /**
         * The tunnel's IPv6 address in chained full-tunnel mode, and its prefix length.
         *
         * A locally-assigned ULA (`fd00::/8`, RFC 4193) rather than anything routable: the
         * interface needs *an* IPv6 address before Android will install an IPv6 route, but this
         * address is never a source for traffic that leaves the device — full tunnel claims
         * IPv6 in order to DROP it, not to carry it.
         */
        const val CHAINED_TUNNEL_IPV6_ADDRESS: String = "fd00:1a7a::2"
        const val CHAINED_TUNNEL_IPV6_NETWORK: String = "fd00:1a7a::"
        const val CHAINED_DNS_SERVER_IPV6_ADDRESS: String = "fd00:1a7a::1"

        /** Prefix length paired with [CHAINED_TUNNEL_IPV6_ADDRESS]. */
        const val CHAINED_TUNNEL_IPV6_PREFIX_LENGTH: Int = 64

        /** The pre-chaining plan. A shared instance: the value type is immutable. */
        private val dnsOnlyPlan: ChainedRoutePlan = ChainedRoutePlan.of(
            tunnelAddress = DNS_ONLY_TUNNEL_ADDRESS,
            tunnelPrefixLength = DNS_ONLY_TUNNEL_PREFIX_LENGTH,
            dnsServerAddress = DNS_SERVER_ADDRESS,
            // The installed DNS-capture route, matching the shipped `/32` contract.
            routeDescription = "$DNS_SERVER_ADDRESS/32 (DNS)",
            includedIPv4Routes = listOf(DNS_PROXY_ROUTE),
            // DNS-only claims no IPv6 and never did. It is not carrying traffic, so it has
            // nothing to leak: DNS is captured regardless of transport, and everything else is
            // meant to stay on the physical interface.
            tunnelIPv6Address = null,
            tunnelIPv6PrefixLength = null,
            includedIPv6Routes = emptyList(),
            mtu = DNS_ONLY_MTU,
        )

        /**
         * The DNS-only (chained-inactive) plan.
         *
         * This branch must keep producing exactly the settings the tunnel claimed before
         * chaining existed. Every field feeds the routes the OS installs, so a drift would
         * change production behaviour for every user who never enables chaining.
         */
        fun dnsOnly(): ChainedRoutePlan = dnsOnlyPlan

        /**
         * Builds the plan for [configuration], or the inactive DNS-only plan when `null`.
         *
         * A `null` configuration is the chained-inactive lifecycle — no usable upstream was
         * latched, so the tunnel stays the DNS-only tunnel it always was. Passing the
         * configuration rather than a live flag is the Android mirror of iOS carrying the
         * resolved configuration in its mode: a plan can only claim an `[Interface] Address`
         * and `AllowedIPs` it was actually given, so "chained mode with nothing to claim from"
         * is unrepresentable.
         *
         * Full vs split is DERIVED into [ChainedUpstreamConfiguration.routingPolicy] from the
         * `AllowedIPs` shape; the plan reads it here rather than re-deriving, so the two cannot
         * disagree about the mode. The configuration is the immutable latched payload, so no
         * live re-read can change what the tunnel claims mid-session (`INV-CHAIN-1`).
         */
        fun make(
            configuration: ChainedUpstreamConfiguration?,
            dnsCaptureFloor: ChainedDnsCaptureFloor = ChainedDnsCaptureFloor.empty,
        ): ChainedRoutePlan {
            val active = configuration?.activeConfiguration ?: return dnsOnly()
            return when (active.effectiveRoutingPolicy) {
                ChainedRoutingPolicy.FULL_TUNNEL -> fullTunnel(active)
                ChainedRoutingPolicy.SPLIT_TUNNEL -> splitTunnel(active, dnsCaptureFloor)
            }
        }

        /**
         * Full tunnel: claim both default routes and number the interface with the configured
         * `[Interface] Address`.
         *
         * Claiming the default route is what makes chained mode a VPN rather than a DNS proxy.
         * The interface is numbered from the configuration, NOT the DNS-only `10.111.0.2`: a
         * WireGuard peer accepts a client's inner packets only from the source its `AllowedIPs`
         * for that client permit, so a tunnel numbered `10.111.0.2` has every encapsulated
         * packet silently dropped at the far end — after the tunnel reports established, with
         * `0.0.0.0/0` claimed. The mask is `/32` because the config carries a bare host address
         * and, with `0.0.0.0/0` claimed, the interface subnet decides nothing about routing; a
         * wider mask would only claim the surrounding subnet is on-link, which the peer never
         * said.
         */
        private fun fullTunnel(configuration: ChainedUpstreamConfiguration): ChainedRoutePlan =
            ChainedRoutePlan.of(
                tunnelAddress = configuration.clientAddress,
                tunnelPrefixLength = 32,
                dnsServerAddress = DNS_SERVER_ADDRESS,
                // Both families, because this string is what a device log reports as the
                // claimed route. Naming only "0.0.0.0/0" while `::/0` is installed would hide
                // the one fact a field log needs to confirm the IPv6 fail-closed behaviour is
                // actually engaged.
                routeDescription = "0.0.0.0/0, ::/0",
                includedIPv4Routes = listOf(IPv4Route("0.0.0.0", 0)),
                // Claim IPv6 in order to DROP it. Claiming only 0.0.0.0/0 on a dual-stack
                // network leaves IPv6 application traffic on the physical interface, outside
                // the tunnel entirely — in a privacy feature that is a leak, not a missing
                // feature. With no IPv6 upstream the packets enter the TUN and are dropped,
                // so the failure is CLOSED and Happy Eyeballs moves the connection to IPv4,
                // which the tunnel does carry.
                tunnelIPv6Address = CHAINED_TUNNEL_IPV6_ADDRESS,
                tunnelIPv6PrefixLength = CHAINED_TUNNEL_IPV6_PREFIX_LENGTH,
                includedIPv6Routes = listOf(IPv6Route("::", 0)),
                dnsServerIPv6Address = CHAINED_DNS_SERVER_IPV6_ADDRESS,
                // The configured `[Interface] MTU` when present, else the IPv6 minimum. The
                // configuration boundary refuses a sub-1280 value outright, so the floor
                // cannot be undercut from here.
                mtu = configuration.effectiveInterfaceMTU,
            )

        /**
         * Split tunnel: claim the configured IPv4 `AllowedIPs` prefixes and resolver floor plus the
         * DNS-proxy route.
         *
         * Everything outside the `AllowedIPs` — IPv6 included — leaves the TUN and egresses
         * direct on the physical interface. That is the protected set the user configured, so
         * it is intended egress rather than a leak (`INV-CHAIN-1` split clause). DNS is still
         * filtered on everything: the system resolver is pointed at [DNS_SERVER_ADDRESS], and
         * [DNS_PROXY_ROUTE] draws those queries into the TUN exactly as DNS-only does. (A raw
         * socket to a curated or captured public resolver reaches the same filter through its
         * host route. A resolver outside that floor still egresses direct.)
         *
         * Prefixes are masked to their network, matching what the inbound data path does with
         * `AllowedIPs`, so `10.1.2.3/8` and `10.0.0.0/8` claim the same block.
         */
        private fun splitTunnel(
            configuration: ChainedUpstreamConfiguration,
            dnsCaptureFloor: ChainedDnsCaptureFloor,
        ): ChainedRoutePlan {
            val allowedIPv4Routes = configuration.capturedAllowedIPs.mapNotNull(::ipv4RouteFromPrefix)
            return ChainedRoutePlan.of(
                tunnelAddress = configuration.clientAddress,
                tunnelPrefixLength = 32,
                dnsServerAddress = DNS_SERVER_ADDRESS,
                // Report the exact tunneled prefixes, both DNS listeners, and resolver host
                // routes; other traffic remains direct and split never installs ::/0.
                routeDescription = (
                    allowedIPv4Routes.map { it.cidr } +
                        listOf("$DNS_SERVER_ADDRESS/32 (DNS)") +
                        dnsCaptureFloor.ipv4Routes.map { "${it.cidr} (resolver DNS)" } +
                        listOf("$CHAINED_TUNNEL_IPV6_NETWORK/$CHAINED_TUNNEL_IPV6_PREFIX_LENGTH (DNS v6)") +
                        dnsCaptureFloor.ipv6Routes.map { "${it.destinationAddress}/128 (resolver DNS)" } +
                        listOf("rest direct (no ::/0)")
                    ).joinToString(", "),
                includedIPv4Routes = (allowedIPv4Routes + DNS_PROXY_ROUTE + dnsCaptureFloor.ipv4Routes).distinct(),
                // Only the served DNS ULA and exact resolver hosts, never ::/0 in split.
                tunnelIPv6Address = CHAINED_TUNNEL_IPV6_ADDRESS,
                tunnelIPv6PrefixLength = CHAINED_TUNNEL_IPV6_PREFIX_LENGTH,
                includedIPv6Routes = listOf(IPv6Route(CHAINED_TUNNEL_IPV6_NETWORK, CHAINED_TUNNEL_IPV6_PREFIX_LENGTH)) + dnsCaptureFloor.ipv6Routes,
                dnsServerIPv6Address = CHAINED_DNS_SERVER_IPV6_ADDRESS,
                mtu = configuration.effectiveInterfaceMTU,
                dnsCaptureFloor = dnsCaptureFloor,
            )
        }

        /**
         * Whether the IPv4 prefixes in [prefixes] collectively COVER `0.0.0.0/0` — whether
         * their union spans the whole 32-bit IPv4 space.
         *
         * Coverage, not a literal match, so the `0.0.0.0/1, 128.0.0.0/1` two-halves idiom
         * counts as a full tunnel; a set with any gap (`10.0.0.0/8, 192.168.0.0/16`) does not.
         * IPv6 entries are ignored. Delegates to the configuration boundary's sweep so the mode
         * discriminator and this guard cannot disagree about what "covers" means.
         */
        fun coversDefaultRoute(prefixes: List<String>): Boolean =
            ChainedUpstreamConfigurationValidator.coversIPv4DefaultRoute(prefixes)

        /**
         * Whether [prefix] covers the DNS-proxy host route `10.111.0.1/32`.
         *
         * A split plan installs the capture route beside its `AllowedIPs`, so an overlapping
         * prefix means two claims about the same subnet; see
         * [ChainedRoutePlanCollision.ALLOWED_IPS_OVERLAPS_DNS_CAPTURE]. Non-IPv4 and malformed
         * entries return `false` (the configuration boundary refuses them before this runs).
         */
        fun overlapsDnsCapture(prefix: String): Boolean {
            val block = ChainedUpstreamConfigurationValidator.ipv4Block(prefix) ?: return false
            val capture = ChainedUpstreamConfigurationValidator.ipv4Block(DNS_CAPTURE_PREFIX)!!
            return block.first <= capture.second && capture.first <= block.second
        }

        /**
         * Whether [prefix] is EXACTLY [clientAddress]'s own `/32` host route.
         *
         * See [ChainedRoutePlanCollision.ALLOWED_IPS_COVERS_CLIENT_ADDRESS]. Only an exact match is
         * flagged: a prefix that merely contains the client (`10.7.0.0/24` for client `10.7.0.2`)
         * is the ordinary peer-side subnet the tunnel must carry and is NOT a loop. Returns `false`
         * when either side is not an IPv4 literal the boundary accepts.
         */
        fun coversClientAddress(prefix: String, clientAddress: String): Boolean {
            val block = ChainedUpstreamConfigurationValidator.ipv4Block(prefix) ?: return false
            val client = ChainedUpstreamConfigurationValidator.ipv4Block("$clientAddress/32")
                ?: return false
            return block.first == client.first && block.second == client.second
        }

        /**
         * Every collision guard [allowedIPs] trips against [clientAddress], in a stable order
         * and without duplicates.
         *
         * The split-tunnel caller runs this before installing a plan and refuses on a non-empty
         * result; a full-tunnel `0.0.0.0/0` legitimately contains every address and must NOT be
         * run through this guard (its claim is the whole point of the mode).
         */
        fun collisions(
            allowedIPs: List<String>,
            clientAddress: String,
        ): List<ChainedRoutePlanCollision> {
            val found = LinkedHashSet<ChainedRoutePlanCollision>()
            for (prefix in allowedIPs) {
                if (overlapsDnsCapture(prefix)) {
                    found += ChainedRoutePlanCollision.ALLOWED_IPS_OVERLAPS_DNS_CAPTURE
                }
                if (coversClientAddress(prefix, clientAddress)) {
                    found += ChainedRoutePlanCollision.ALLOWED_IPS_COVERS_CLIENT_ADDRESS
                }
            }
            return found.toList()
        }

        /**
         * The `IPv4Route` for an `address/length` prefix, masked to its network so `10.1.2.3/8`
         * and `10.0.0.0/8` claim the same block. `null` for anything that is not an IPv4 prefix
         * (an IPv6 entry, or a malformed one), so the split builder can drop a mixed list; in
         * practice the configuration boundary has already refused a split config carrying a v6
         * or malformed `AllowedIPs`, so the filter is a defensive no-op there.
         */
        fun ipv4RouteFromPrefix(prefix: String): IPv4Route? {
            val parts = prefix.split("/")
            if (parts.size != 2) return null
            val length = parts[1].toIntOrNull() ?: return null
            if (length !in 0..32) return null
            val block = ChainedUpstreamConfigurationValidator.ipv4Block(prefix) ?: return null
            return IPv4Route(dottedQuad(block.first), length)
        }

        /**
         * Whether the IPv6 routes in [routes] collectively cover `::/0`, by the same union sweep
         * [coversDefaultRoute] uses for IPv4 (128-bit arithmetic).
         */
        private fun coversIpv6DefaultRoute(routes: List<IPv6Route>): Boolean {
            if (routes.isEmpty()) return false
            val full = BigInteger.ONE.shiftLeft(128)
            val blocks = routes.mapNotNull(::ipv6Block).sortedBy { it.first }
            var nextNeeded = BigInteger.ZERO
            for (block in blocks) {
                if (block.first > nextNeeded) return false
                if (block.second >= nextNeeded) nextNeeded = block.second.add(BigInteger.ONE)
                if (nextNeeded >= full) return true
            }
            return nextNeeded >= full
        }

        /** The inclusive 128-bit `[start, end]` range an IPv6 prefix covers, or `null`. */
        private fun ipv6Block(route: IPv6Route): Pair<BigInteger, BigInteger>? {
            if (route.prefixLength !in 0..128) return null
            val address = try {
                InetAddress.getByName(route.destinationAddress)
            } catch (_: Exception) {
                return null
            }
            if (address !is Inet6Address) return null
            val value = BigInteger(1, address.address)
            val hostBits = 128 - route.prefixLength
            val network = value.shiftRight(hostBits).shiftLeft(hostBits)
            val size = BigInteger.ONE.shiftLeft(hostBits)
            return network to network.add(size).subtract(BigInteger.ONE)
        }

        /**
         * Whether a textual IPv6 address is the unspecified address, however it is spelled.
         *
         * `::` has many equivalent forms — `::0`, `0::0`, `0:0:0:0:0:0:0:0` — and a literal
         * string compare recognizes exactly one. That matters because the comparison decides
         * whether the plan claims the IPv6 default route, and a plan that DOES claim it but
         * spells it differently would be read as leaking. Parsed rather than pattern-matched,
         * so every spelling the OS accepts is recognized.
         */
        fun isUnspecifiedIPv6Address(address: String): Boolean {
            if (address.isEmpty() || !address.contains(':')) return false
            val parsed = try {
                InetAddress.getByName(address)
            } catch (_: Exception) {
                return false
            }
            return parsed is Inet6Address && parsed.address.all { it == 0.toByte() }
        }

        /** A 32-bit value as a dotted quad. */
        private fun dottedQuad(value: Long): String =
            "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}." +
                "${(value shr 8) and 0xFF}.${value and 0xFF}"
    }
}
