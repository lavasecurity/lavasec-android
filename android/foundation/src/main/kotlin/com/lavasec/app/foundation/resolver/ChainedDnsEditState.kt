package com.lavasec.app.foundation.resolver

/**
 * Configuration availability for the DNS editor while VPN chaining is in play, the
 * Android mirror of the iOS `ChainedDNSSettingsPresentation` (LavaSecKit
 * `ChainedSurfaceState.swift`, pinned c4658d9).
 *
 * This is separate from the live protection verdict and from the saved fallback
 * consent: it answers only "may the user edit the saved DNS selection right now".
 * A full-tunnel chained session takes its DNS from the WireGuard configuration, so
 * the saved selection is bypassed and the editor is locked; an unknown routing policy
 * is not evidence of a full tunnel and stays editable.
 */
data class ChainedDnsEditState(
    val chainingEnabled: Boolean,
    val fallbackPreference: Boolean,
    val storedIsSplitTunnel: Boolean?,
) {
    /** Whether chaining owns the tunnel's DNS forwarding. */
    val usesWireGuard: Boolean get() = chainingEnabled

    /** Whether the saved fallback consent may be changed for the stored routing policy. */
    val canChangeFallback: Boolean get() = storedIsSplitTunnel != false

    /**
     * The effective fallback preference for display: false whenever the preference is
     * off or the stored policy is a full tunnel, the stored policy when it is known,
     * and null when the policy is unknown (neither true nor false may be claimed).
     */
    val fallbackEnabled: Boolean?
        get() = if (!fallbackPreference || storedIsSplitTunnel == false) false else storedIsSplitTunnel

    /**
     * THE canonical edit gate. A full-tunnel chained session or an explicit fallback-off
     * consent locks the DNS editor; chaining off, a split tunnel, or an unknown policy
     * stays editable. Every policy rejection for a locked editor keys on this one value.
     */
    val canEditDns: Boolean
        get() = !chainingEnabled || (fallbackPreference && storedIsSplitTunnel != false)

    companion object {
        /** No chaining, fallback consent on, routing policy unknown: the ordinary editor state. */
        val editable: ChainedDnsEditState = ChainedDnsEditState(
            chainingEnabled = false,
            fallbackPreference = true,
            storedIsSplitTunnel = null,
        )
    }
}
