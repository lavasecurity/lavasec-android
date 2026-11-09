package com.lavasec.app.notification

/**
 * Native mirror of the Android system-surface capability catalog (plan 309 F21).
 *
 * The presentation-side table lives in `android/react-native/src/systemCapabilities.ts`. This
 * Kotlin copy exists so a native surface (notification routing, QA entry) can consult the same
 * dispositions and wire ids without crossing into React, and so the advertised-capability audit
 * reflects what the native build can actually honour. iOS-only features are explicitly
 * `UNAVAILABLE`, never mapped to a fake Android equivalent.
 */
internal enum class CapabilityDisposition { AVAILABLE, EQUIVALENT, UNAVAILABLE, DEFERRED, DEBUG_ONLY }

internal enum class SystemCapability(
    val wireId: String,
    val disposition: CapabilityDisposition,
    val iosOnly: Boolean,
    val androidEquivalent: String?,
) {
    DNS_PATCH("dnsPatch", CapabilityDisposition.UNAVAILABLE, true, null),
    LIVE_ACTIVITY(
        "liveActivity",
        CapabilityDisposition.EQUIVALENT,
        false,
        "Ongoing ambient protection-status notification",
    ),
    FOCUS_FILTER("focusFilter", CapabilityDisposition.UNAVAILABLE, true, null),
    APP_INTENT("appIntent", CapabilityDisposition.UNAVAILABLE, true, null),
    ALTERNATE_ICON("alternateIcon", CapabilityDisposition.UNAVAILABLE, true, null),
    WIDGET("widget", CapabilityDisposition.DEFERRED, false, null),
    SHORTCUT("shortcut", CapabilityDisposition.DEFERRED, false, null),
    // Frozen 3bd86116 FilterLibraryView.swift's AutoSwitchHowToContent is Apple Shortcuts/Focus
    // setup only, backed by SwitchFilterIntent/SetFocusFilterIntent rather than an in-app rule
    // editor or scheduler. F21/B21 classify those OS entry points; no Android equivalent ships.
    AUTO_SWITCH("autoSwitch", CapabilityDisposition.UNAVAILABLE, true, null),
    DEVICE_QA(
        "deviceQA",
        CapabilityDisposition.DEBUG_ONLY,
        false,
        "Debug/authorized QA builds only",
    ),
    DESIGN_GALLERY("designGallery", CapabilityDisposition.DEBUG_ONLY, false, "Debug builds only"),
    NOTIFICATION_ACTIONS(
        "notificationActions",
        CapabilityDisposition.AVAILABLE,
        false,
        "Notification actions routed to the VPN service owner or a validated deep link",
    ),
    DEEP_LINKS(
        "deepLinks",
        CapabilityDisposition.AVAILABLE,
        false,
        "lavasecurity:// product routes",
    ),
    VPN_PERMISSION_EXPLANATION(
        "vpnPermissionExplanation",
        CapabilityDisposition.AVAILABLE,
        false,
        "Onboarding VPN consent and notification permission explanation",
    ),
    SYSTEM_SETTINGS_LINKS(
        "systemSettingsLinks",
        CapabilityDisposition.AVAILABLE,
        false,
        "App notification and VPN system-settings destinations",
    ),
    BOOT_RETURN(
        "bootReturn",
        CapabilityDisposition.AVAILABLE,
        false,
        "User-unlocked direct-boot receiver resumes deferred retention work",
    ),
}

internal object SystemCapabilityPolicy {
    /** Whether the build can honour a capability; debug-only follows the supplied build flag. */
    fun isAvailable(capability: SystemCapability, debugBuild: Boolean): Boolean =
        when (capability.disposition) {
            CapabilityDisposition.AVAILABLE,
            CapabilityDisposition.EQUIVALENT,
            -> true

            CapabilityDisposition.DEBUG_ONLY -> debugBuild
            CapabilityDisposition.UNAVAILABLE,
            CapabilityDisposition.DEFERRED,
            -> false
        }

    /** Routes whose availability is owned by a capability; everything else is ungated. */
    fun capabilityForRoute(route: String): SystemCapability? = when (route) {
        "DNSPatch" -> SystemCapability.DNS_PATCH
        "AutoSwitch" -> SystemCapability.AUTO_SWITCH
        "DeviceQA", "phoneQA" -> SystemCapability.DEVICE_QA
        "Components" -> SystemCapability.DESIGN_GALLERY
        else -> null
    }

    fun routeAllowed(route: String, debugBuild: Boolean): Boolean {
        val capability = capabilityForRoute(route) ?: return true
        return isAvailable(capability, debugBuild)
    }

    fun advertisedCapabilities(debugBuild: Boolean): List<SystemCapability> =
        SystemCapability.entries.filter { isAvailable(it, debugBuild) }
}
