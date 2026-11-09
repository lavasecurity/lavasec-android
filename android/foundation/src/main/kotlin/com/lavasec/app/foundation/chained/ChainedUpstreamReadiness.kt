package com.lavasec.app.foundation.chained

/**
 * Why chained mode cannot be latched right now. Log/health identifiers, never user copy —
 * mirrors the iOS `ChainedUpstreamReadiness.Refusal` and `ChainedAvailability.Ineligibility`
 * vocabulary so both clients name the same condition in a device log.
 *
 * The cases are the union of the gates a latch must clear, grouped in the order
 * [ChainedReadiness.evaluate] applies them: the entitlement/device/preference gates that sit
 * ABOVE the configuration, then the configuration's own runtime requirements. The order is
 * settled by the same rule iOS `ChainedSurfaceState` documents — a condition that would make
 * the user's own switch meaningless outranks the switch, and the configuration is the last
 * thing consulted because an operational fault about a feature that is switched off answers a
 * question the user did not ask.
 */
enum class ChainedReadinessRefusal {
    /**
     * Chaining is a Lava Security Plus feature and the account is not entitled.
     *
     * FIRST, before the device and configuration gates, because a non-Plus account cannot
     * chain whatever its hardware or stored file says. Reporting a configuration fault to a
     * user who cannot buy their way out of it — the disabled-control-with-no-remedy defect
     * `ChainedSurfaceState` records — is the failure this ordering avoids.
     */
    NOT_ENTITLED,

    /**
     * The device is below the chaining memory floor (the Android analogue of iOS
     * `INV-CHAIN-2`: Plus AND RAM >= threshold AND not memory-kill-excluded).
     *
     * The engine is a roughly 50 MB resident (plan `2026-09-12` Slice 6), so the caller's
     * eligibility verdict is authoritative here and readiness never instantiates anything.
     */
    DEVICE_INELIGIBLE,

    /**
     * The user turned chaining off. A preference, not an outcome: this reason must never be
     * rendered in operational-failure vocabulary (plan `2026-09-01`).
     *
     * Below the entitlement/device gates because those disable the switch itself; above the
     * configuration because a stored file that cannot run is irrelevant to a user who is not
     * asking for chaining.
     */
    DISABLED_BY_USER,

    /**
     * Nothing has been configured. Distinct from a configured file that is unusable: the
     * remedy is "add an upstream", not "fix this upstream".
     */
    NO_CONFIGURATION,

    /**
     * No `DNS =` entry survives selection: the file carries none, or carries only entries the
     * chained data path cannot speak to.
     *
     * While chained, the configuration's own resolver — plain UDP DNS encapsulated through
     * WireGuard — is the SOLE DNS egress (plan `2026-09-12` Slice 4, "sole egress"; T0 in
     * `docs/architecture/dns-tiers.md`). T1/T2 and every physical-interface transport are
     * suspended, so a configuration with no usable resolver would claim its routes and then
     * answer every query fail-closed, indefinitely. "Carries packets, resolves nothing" is a
     * blackhole the user did not consent to. Absence is a readiness refusal rather than a
     * validation failure because a file with no `DNS =` line is perfectly well-formed — it is
     * simply non-functional while chained, and the boundary must keep admitting it so the
     * user can paste it, see this reason, and edit it.
     */
    NO_USABLE_TUNNEL_DNS,

    /**
     * A `DNS =` entry is not an IP literal.
     *
     * DEFENSIVE TODAY, kept deliberately. The configuration boundary
     * ([ChainedUpstreamConfigurationValidator.validateAndDerive]) already refuses a malformed
     * `DNS =` with [ChainedConfigurationFailure.MALFORMED_DNS_ADDRESS] and both construction
     * paths run it, so [ChainedReadiness.evaluate] cannot see one through a stored value. It
     * stays for the same asymmetry iOS records for its unreachable guards: a redundant parse
     * costs nothing, and a missing one would let a future construction path hand readiness a
     * list it silently skips — surfacing as a latched tunnel that resolves nothing rather
     * than as an error at the boundary. IP-literal parsing is the boundary's own
     * [ChainedUpstreamConfigurationValidator.isIPLiteral], not a second copy.
     */
    MALFORMED_TUNNEL_DNS,

    /**
     * The configuration has no IPv4 inner route.
     *
     * DEFENSIVE TODAY, kept deliberately, for the same reason the malformed-DNS case is: the
     * configuration boundary derives [ChainedRoutingPolicy] from `AllowedIPs` and refuses a
     * set with no usable IPv4 prefix ([ChainedConfigurationFailure.IPV6_ONLY_UPSTREAM_UNSUPPORTED]
     * / [ChainedConfigurationFailure.ALLOWED_IPS_EMPTY]), so a constructed configuration
     * always routes. The check is exposed so a future construction or migration path that
     * skips validation fails closed here — a tunnel that latches with no inner route is the
     * same silent blackhole as one with no resolver.
     */
    NO_USABLE_IPV4_ROUTE,

    /**
     * A usable T0 resolver exists, but none of them is routed by the peer's `AllowedIPs`.
     *
     * T0 egresses THROUGH WireGuard, and the engine exposes each decrypted reply's source so
     * the peer's `AllowedIPs` can be enforced. A resolver whose address lies outside those
     * prefixes therefore has its replies dropped, blackholing the sole DNS egress once latched.
     * Full tunnel (`0.0.0.0/0`) routes every resolver; this refusal is the split-tunnel hazard
     * the plan calls out.
     */
    TUNNEL_DNS_NOT_ROUTED,

    /**
     * The split `AllowedIPs` collide with the DNS capture/proxy or the client's own host route.
     *
     * The route plan refuses such a split install; readiness rejects it first so the latch never
     * reaches that state. Only split is checked — a full tunnel's `0.0.0.0/0` necessarily
     * contains the proxy and is not a collision.
     */
    ROUTES_COLLIDE,

    /**
     * The platform cannot serve client DNS through the chained tunnel.
     *
     * The TUN advertises the tunnel resolver and the outbound classifier hands ordinary UDP/53 to
     * the client-DNS sink; if that sink is not wired to a resolver egress, latching chained mode
     * would discard every intercepted query and blackhole hostname resolution. The production
     * `ChainedT0ResolverEgress` now wires it, so production passes `clientDnsOverTunnelAvailable =
     * true`; the default stays `false` so any caller that has NOT wired an egress refuses
     * fail-closed and stays on the working DNS-only tunnel.
     */
    CLIENT_DNS_OVER_TUNNEL_UNAVAILABLE,
    ;

    /**
     * Stable identifier for device logs. Never user copy: the UI maps these to localized
     * strings, so the spelling here is a diagnostic contract, not a message.
     */
    val logValue: String
        get() = "chained-not-ready-" + name.lowercase().replace('_', '-')
}

/**
 * Whether chained mode may claim its routes right now.
 *
 * The question is narrow on purpose: is there a configured upstream we could actually run this
 * instant, given the account, this device, and the user's switch. Not "will the handshake
 * succeed", which nothing can know before trying, and not the routes themselves, which are the
 * caller's to install only after this returns [Ready]. A tunnel that claims its routes on the
 * strength of a preference and only then discovers it has no usable resolver is a blackhole the
 * user did not consent to — readiness is the boundary that stops that.
 *
 * PURE and non-mutating by construction: it reads a [ChainedUpstreamConfiguration] value and
 * caller-supplied facts, never touches Android APIs, keystores, sockets, or storage, and never
 * returns an altered configuration. That keeps it a JVM-only policy with real behavioural tests
 * rather than another platform pin.
 *
 * Mirrors iOS `ChainedUpstreamReadiness` (the configuration half; iOS gathers the secret store
 * into the same gate, Android has no equivalent credential store yet) and folds in the
 * entitlement/device/preference gates iOS splits across `ChainedAvailability` and
 * `ChainedSurfaceState`. Plan: lavasec-infra
 * `plans/2026-09-12-android-chained-upstream-engine-plan.md` (Slice 4 "sole egress", Slice 6
 * entitlement + device eligibility).
 */
sealed interface ChainedReadiness {

    /** Every gate cleared; a session may be built and the routes claimed. */
    data object Ready : ChainedReadiness

    /** The upstream cannot be latched; [reason] names the first gate that refused. */
    data class NotReady(val reason: ChainedReadinessRefusal) : ChainedReadiness

    /** Whether chained mode may claim its routes. Never conflate with the user's preference. */
    val isReady: Boolean
        get() = this is Ready

    /** Stable identifier for device logs. Never user copy, never any part of a secret. */
    val logValue: String
        get() = when (this) {
            Ready -> "chained-ready"
            is NotReady -> reason.logValue
        }

    companion object {
        /**
         * The Android DNS-only tunnel's own proxy address, `DnsOnlyTunContract.DNS_ADDRESS`.
         *
         * Queries to it are served by in-tunnel interception, NOT encapsulated to the upstream:
         * the OS delivers to it locally because the interface owns it. Using it as the chained
         * upstream resolver is a loop — the interceptor asks itself — so it is never usable T0.
         *
         * A reference to the configuration boundary's constant (which the shipped Android
         * DNS-only contract also uses) so the proxy address cannot drift between the two.
         * Pinned by [isUsableTunnelResolver] and the readiness tests.
         */
        const val TUNNEL_DNS_PROXY_ADDRESS = ChainedUpstreamConfiguration.DNS_SERVER_ADDRESS

        /**
         * Decides whether chained mode may be latched, from the configuration and the facts
         * only the platform can supply.
         *
         * Gate order, and why it is this way:
         *
         * 1. [ChainedReadinessRefusal.NOT_ENTITLED] — the paid gate.
         * 2. [ChainedReadinessRefusal.DEVICE_INELIGIBLE] — the hardware gate.
         * 3. [ChainedReadinessRefusal.DISABLED_BY_USER] — the switch.
         * 4. [ChainedReadinessRefusal.NO_CONFIGURATION] — then the file.
         * 5. T0 DNS and the IPv4 inner route.
         *
         * The first three outrank the configuration, mirroring iOS `ChainedSurfaceState`: a
         * condition that makes the toggle meaningless outranks the toggle, and a file fault
         * reported to a user who is not asking for chaining (or cannot have it) is noise. The
         * DNS check precedes the route check because a configuration always arrives with a
         * derived route ([ChainedRoutingPolicy] is guaranteed full or split), whereas a
         * missing `DNS =` line is the ordinary, reachable failure.
         *
         * @param configuration the stored upstream, or `null` when none is configured.
         * @param hasEntitlement the authoritative Plus verdict. Never inferred here.
         * @param deviceEligible the caller's memory/eligibility verdict (`INV-CHAIN-2`). The
         *   caller owns the measurement and the memory-kill exclusion; readiness only reports.
         * @param userEnabled the user's stored preference. NOT an outcome: the tunnel latches
         *   separately, and losing a gate later tears a live session down elsewhere.
         */
        fun evaluate(
            configuration: ChainedUpstreamConfiguration?,
            hasEntitlement: Boolean,
            deviceEligible: Boolean,
            userEnabled: Boolean,
        ): ChainedReadiness {
            if (!hasEntitlement) return NotReady(ChainedReadinessRefusal.NOT_ENTITLED)
            if (!deviceEligible) return NotReady(ChainedReadinessRefusal.DEVICE_INELIGIBLE)
            if (!userEnabled) return NotReady(ChainedReadinessRefusal.DISABLED_BY_USER)
            val active = configuration?.activeConfiguration ?: return NotReady(ChainedReadinessRefusal.NO_CONFIGURATION)

            // T0 is the sole chained egress: it must select a resolver AND that resolver must
            // be routed by the peer's AllowedIPs, or its replies are dropped after latch.
            tunnelDnsRefusal(
                active.stackDNSAddresses,
                active.clientAddress,
                active.capturedAllowedIPs,
            )?.let { refusal ->
                return NotReady(refusal)
            }

            // A split install whose AllowedIPs collide with the DNS capture/proxy or the client
            // host route is refused by the route plan; reject it before the latch reaches that.
            if (active.effectiveRoutingPolicy == ChainedRoutingPolicy.SPLIT_TUNNEL &&
                ChainedRoutePlan.collisions(active.capturedAllowedIPs, active.clientAddress)
                    .isNotEmpty()
            ) {
                return NotReady(ChainedReadinessRefusal.ROUTES_COLLIDE)
            }

            // Defensive: the boundary guarantees a full or split route, so a configuration that
            // reaches this module has an IPv4 prefix. Re-derive anyway so a construction path
            // that skipped validation cannot latch a tunnel with no inner route.
            if (!hasUsableIpv4Route(active.capturedAllowedIPs)) {
                return NotReady(ChainedReadinessRefusal.NO_USABLE_IPV4_ROUTE)
            }

            return Ready
        }

        /**
         * Whether `allowedIPs` carry at least one IPv4 prefix — the inner route a full or split
         * chained data path installs.
         *
         * The configuration boundary already guarantees this: it derives [ChainedRoutingPolicy]
         * from the same prefixes and refuses a set with no usable IPv4 prefix
         * ([ChainedConfigurationFailure.ALLOWED_IPS_EMPTY],
         * [ChainedConfigurationFailure.IPV6_ONLY_UPSTREAM_UNSUPPORTED]). Exposed and re-derived
         * so the defensive [ChainedReadinessRefusal.NO_USABLE_IPV4_ROUTE] path is reachable in
         * a test and so a future construction path that skips validation fails closed rather
         * than latching a tunnel with no inner route.
         */
        fun hasUsableIpv4Route(allowedIPs: List<String>): Boolean =
            allowedIPs.any { ChainedUpstreamConfigurationValidator.ipv4Block(it) != null }

        /**
         * Why the configuration's T0 `DNS =` cannot serve chained DNS, or `null` when every
         * entry is well-formed, at least one is usable, and at least one usable entry is routed
         * by `allowedIPs`.
         *
         * Split out from [evaluate] so the malformed case — unreachable through the validating
         * construction path — is still directly testable, and so a caller can name the DNS
         * fault without re-deriving the rest of the gate. Absence is
         * [ChainedReadinessRefusal.NO_USABLE_TUNNEL_DNS], not
         * [ChainedReadinessRefusal.MALFORMED_TUNNEL_DNS]: an empty list is well-formed, just
         * non-functional. A usable-but-unrouted resolver is
         * [ChainedReadinessRefusal.TUNNEL_DNS_NOT_ROUTED].
         */
        fun tunnelDnsRefusal(
            dnsAddresses: List<String>,
            clientAddress: String,
            allowedIPs: List<String>,
        ): ChainedReadinessRefusal? {
            if (dnsAddresses.isEmpty()) return ChainedReadinessRefusal.NO_USABLE_TUNNEL_DNS
            if (dnsAddresses.any { !ChainedUpstreamConfigurationValidator.isIPLiteral(it) }) {
                return ChainedReadinessRefusal.MALFORMED_TUNNEL_DNS
            }
            if (dnsAddresses.none { isUsableTunnelResolver(it, clientAddress) }) {
                return ChainedReadinessRefusal.NO_USABLE_TUNNEL_DNS
            }
            if (dnsAddresses.none {
                    isUsableTunnelResolver(it, clientAddress) && isRoutedByAllowedIPs(it, allowedIPs)
                }
            ) {
                return ChainedReadinessRefusal.TUNNEL_DNS_NOT_ROUTED
            }
            return null
        }

        /**
         * Whether `address` falls inside any `allowedIPs` prefix, so a T0 reply's source is
         * admitted by the engine's peer-AllowedIPs enforcement.
         */
        fun isRoutedByAllowedIPs(address: String, allowedIPs: List<String>): Boolean {
            val addressBlock =
                ChainedUpstreamConfigurationValidator.ipv4Block("$address/32") ?: return false
            return allowedIPs.any { prefix ->
                val block = ChainedUpstreamConfigurationValidator.ipv4Block(prefix)
                block != null && block.first <= addressBlock.first && addressBlock.second <= block.second
            }
        }

        /**
         * Whether `address` can serve as the chained upstream's T0 resolver.
         *
         * USABILITY, not well-formedness: the configuration boundary has already proven the
         * entry is an IP literal. The rules mirror iOS
         * `ChainedTunnelResolverSelection.isUsableResolverAddress`, each for the same reason —
         * an address that means LOCAL DELIVERY rather than egress can never carry a query to
         * the upstream:
         *
         * - **IPv4 only.** Chained mode claims no IPv6 it can carry: full tunnel claims `::/0`
         *   to DROP v6 rather than route it, and split leaves v6 direct (a leak the design
         *   forbids for T0). A v6 literal therefore never yields a parseable octet and is
         *   refused — the same parse enforces the family rule, so there is no separate colon
         *   check to drift from it.
         * - **`0.0.0.0/8`** — "this network" including the unspecified address; never a
         *   destination.
         * - **`127.0.0.0/8`** — loopback: the query returns to this device, not the upstream.
         * - **`169.254.0.0/16`** — link-local, what a host assigns itself when configuration
         *   failed.
         * - **`224.0.0.0/4` and above** — multicast, reserved, broadcast: a hand-written
         *   `DNS =` line can name one, and no peer routes it.
         * - **The tunnel's own DNS proxy** ([TUNNEL_DNS_PROXY_ADDRESS], and the boundary's own
         *   [ChainedUpstreamConfiguration.DNS_SERVER_ADDRESS]): in-tunnel interception serves
         *   it, so using it upstream is a loop.
         * - **The configuration's own [ChainedUpstreamConfiguration.clientAddress]**: the
         *   interface owns it, so the OS delivers locally instead of routing into the tunnel.
         *
         * Private ranges, by contrast, are deliberately ADMITTED: a peer handing its clients
         * addresses or resolvers out of `10/8`, `172.16/12` or `192.168/16` is the ordinary
         * case, not a mistake.
         */
        fun isUsableTunnelResolver(address: String, clientAddress: String): Boolean {
            // IPv4 literals only; this parse is the family gate as well as the syntax gate.
            val octets = ChainedUpstreamConfigurationValidator.ipv4Octets(address) ?: return false
            if (octets[0] == 0) return false // 0.0.0.0/8 — this network / unspecified
            if (octets[0] == 127) return false // loopback
            if (octets[0] == 169 && octets[1] == 254) return false // link-local
            if (octets[0] >= 224) return false // multicast / reserved / broadcast
            if (address == TUNNEL_DNS_PROXY_ADDRESS) return false // in-tunnel interception
            if (address == clientAddress) return false // interface owns it -> local delivery
            return true
        }
    }
}
