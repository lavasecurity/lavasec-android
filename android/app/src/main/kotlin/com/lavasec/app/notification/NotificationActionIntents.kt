package com.lavasec.app.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.lavasec.app.MainActivity
import com.lavasec.app.vpn.LavaVpnService

/**
 * Builds the Android intents and pending intents for a resolved [NotificationActionRoute].
 *
 * This is the only place a notification action becomes an executable intent. It never constructs a
 * protection mutation directly: a [NotificationActionRoute.BackendCommand] is an explicit service
 * intent the VPN command owner admits, and an [NotificationActionRoute.AppRoute] is an explicit
 * edition-specific deep link into the native validator, which stages the opaque RN navigation
 * ticket before the foreground host starts.
 */
internal class NotificationActionIntents(context: Context) {
    private val appContext = context.applicationContext

    /** The intent for an action, or `null` when the route is unknown or refused for this build. */
    fun intent(action: NotificationAction, debugBuild: Boolean = false): Intent? {
        val route = NotificationActionRouting.routeFor(action, debugBuild) ?: return null
        return when (route) {
            is NotificationActionRoute.BackendCommand ->
                Intent(appContext, LavaVpnService::class.java).setAction(route.serviceAction)

            is NotificationActionRoute.AppRoute ->
                Intent(appContext, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("${NotificationActionRouting.DEEP_LINK_SCHEME}${route.route}"))
                    // The validator handles warm entries and stages a fresh opaque RN ticket;
                    // raw URI data must never be sent straight to the React host.
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

            is NotificationActionRoute.SystemSettings -> systemSettingsIntent(route.destination)
        }
    }

    /**
     * The pending intent for an action. Protection commands are delivered as service intents;
     * navigation and settings entries are activity intents. `FLAG_IMMUTABLE` is always set so a
     * notification cannot be induced to mutate its target intent.
     */
    fun pendingIntent(
        action: NotificationAction,
        requestCode: Int,
        debugBuild: Boolean = false,
    ): PendingIntent? {
        val route = NotificationActionRouting.routeFor(action, debugBuild) ?: return null
        val intent = intent(action, debugBuild) ?: return null
        return when (route) {
            is NotificationActionRoute.BackendCommand ->
                PendingIntent.getService(appContext, requestCode, intent, FLAGS)

            else ->
                PendingIntent.getActivity(appContext, requestCode, intent, FLAGS)
        }
    }

    private fun systemSettingsIntent(destination: SystemSettingsDestination): Intent =
        when (destination) {
            SystemSettingsDestination.APP_NOTIFICATION ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)

            SystemSettingsDestination.VPN -> Intent(Settings.ACTION_VPN_SETTINGS)
        }

    companion object {
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
