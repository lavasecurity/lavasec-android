package com.lavasec.app.notification

import com.lavasec.app.presentation.ProductDeepLink
import com.lavasec.app.vpn.LavaVpnService

/**
 * Closed routing vocabulary for Android system notifications (plan 309 F21).
 *
 * A notification action is never allowed to implement protection itself. Protection mutations
 * resolve to the exact [LavaVpnService] command action, so the decision to act (or refuse) is the
 * VPN service command owner's alone; navigation resolves to a product deep link that
 * [ProductDeepLink] has validated; OS settings resolve to an out-of-app destination. The JS side
 * mirrors this contract in `android/react-native/src/systemSurfaces.ts`, so the two wire
 * vocabularies must stay identical.
 *
 * An unknown wire id, an unrecognized deep-link route or a route the current build must not enter
 * resolves to `null`: the caller treats it as a refusal, never guesses a destination.
 */
internal enum class NotificationAction(val wireId: String) {
    OPEN_APP("openApp"),
    OPEN_GUARD("openGuard"),
    OPEN_FILTERS("openFilters"),
    OPEN_ACTIVITY("openActivity"),
    OPEN_SETTINGS("openSettings"),
    RECONNECT("reconnect"),
    PAUSE("pause"),
    RESUME("resume"),
    STOP("stop"),
    NOTIFICATION_SETTINGS("notificationSettings"),
    VPN_SETTINGS("vpnSettings"),
    ;

    companion object {
        fun fromWireId(value: String): NotificationAction? = entries.firstOrNull { it.wireId == value }
    }
}

/** An OS settings destination the user must resolve outside Lava. */
internal enum class SystemSettingsDestination(val wireId: String) {
    APP_NOTIFICATION("appNotification"),
    VPN("vpn"),
}

/** The resolved destination of one notification action. */
internal sealed interface NotificationActionRoute {
    /** A protection mutation, admitted or refused only by the VPN service command owner. */
    data class BackendCommand(val serviceAction: String) : NotificationActionRoute

    /** A validated product deep-link route (`lavasecurity://<route>`). */
    data class AppRoute(val route: String) : NotificationActionRoute

    /** A system-settings destination outside the app. */
    data class SystemSettings(val destination: SystemSettingsDestination) : NotificationActionRoute
}

internal object NotificationActionRouting {
    /**
     * The one gate an app-route action must pass. The route must both be a real product deep link
     * and be allowed by the current build's capability policy.
     */
    fun routeFor(action: NotificationAction, debugBuild: Boolean = false): NotificationActionRoute? =
        when (action) {
            NotificationAction.OPEN_APP,
            NotificationAction.OPEN_GUARD,
            -> appRoute(GUARD_ROUTE, debugBuild)

            NotificationAction.OPEN_FILTERS -> appRoute(FILTERS_ROUTE, debugBuild)
            NotificationAction.OPEN_ACTIVITY -> appRoute(ACTIVITY_ROUTE, debugBuild)
            NotificationAction.OPEN_SETTINGS -> appRoute(SETTINGS_ROUTE, debugBuild)

            NotificationAction.RECONNECT ->
                NotificationActionRoute.BackendCommand(LavaVpnService.ACTION_RECONNECT)

            NotificationAction.PAUSE ->
                NotificationActionRoute.BackendCommand(LavaVpnService.ACTION_PAUSE)

            NotificationAction.RESUME ->
                NotificationActionRoute.BackendCommand(LavaVpnService.ACTION_RESUME)

            NotificationAction.STOP ->
                NotificationActionRoute.BackendCommand(LavaVpnService.ACTION_STOP)

            NotificationAction.NOTIFICATION_SETTINGS ->
                NotificationActionRoute.SystemSettings(SystemSettingsDestination.APP_NOTIFICATION)

            NotificationAction.VPN_SETTINGS ->
                NotificationActionRoute.SystemSettings(SystemSettingsDestination.VPN)
        }

    /** Resolves a wire id, refusing unknown ids and capability-gated routes. */
    fun routeForWireId(wireId: String, debugBuild: Boolean = false): NotificationActionRoute? =
        NotificationAction.fromWireId(wireId)?.let { routeFor(it, debugBuild) }

    /**
     * The exact VPN service actions a notification may route to. Anything outside this set would
     * be a notification-only protection implementation and is never constructed.
     */
    val admissibleServiceActions: Set<String> = setOf(
        LavaVpnService.ACTION_RECONNECT,
        LavaVpnService.ACTION_PAUSE,
        LavaVpnService.ACTION_RESUME,
        LavaVpnService.ACTION_STOP,
    )

    private fun appRoute(route: String, debugBuild: Boolean): NotificationActionRoute? {
        val canonicalScreen = SCREEN_FOR_ROUTE[route] ?: return null
        if (!SystemCapabilityPolicy.routeAllowed(canonicalScreen, debugBuild)) return null
        if (com.lavasec.app.edition.EditionDeepLinks.parse("$DEEP_LINK_SCHEME$route") == null) return null
        return NotificationActionRoute.AppRoute(route)
    }

    val DEEP_LINK_SCHEME: String get() = "${com.lavasec.app.edition.AppEdition.current.deepLinkScheme}://"

    private const val GUARD_ROUTE = "guard"
    private const val FILTERS_ROUTE = "filters"
    private const val ACTIVITY_ROUTE = "activity"
    private const val SETTINGS_ROUTE = "settings"

    /** Deep-link route to the canonical screen the capability policy gates. */
    private val SCREEN_FOR_ROUTE: Map<String, String> = mapOf(
        GUARD_ROUTE to "Guard",
        FILTERS_ROUTE to "Filters",
        ACTIVITY_ROUTE to "Activity",
        SETTINGS_ROUTE to "Settings",
    )
}
