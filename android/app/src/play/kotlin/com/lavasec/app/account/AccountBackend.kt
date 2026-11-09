package com.lavasec.app.account

import android.content.Context
import com.lavasec.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Process-scoped session authority, first constructed only after credential unlock. */
internal class AccountBackend(
    context: Context,
    appleBrowserOAuthEnabled: Boolean = BuildConfig.APPLE_BROWSER_OAUTH_ENABLED,
) {
    private val configuration = runCatching {
        AccountServiceConfiguration(BuildConfig.SUPABASE_URL.toHttpUrl(), BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }.getOrNull()
    val sessions = AccountSessionOwner(configuration?.let(::SupabaseAccountAuthApi), AndroidAccountSessionStore(context))
    val googleSignIn = NativeGoogleSignIn(context, sessions, BuildConfig.GOOGLE_SERVER_CLIENT_ID)
    // Apple on Android uses Supabase browser OAuth. Enable only after the Apple Services ID/secret
    // and package-specific redirects are activated in Supabase; it is not certificate-bound OAuth.
    val appleSignIn = NativeAppleSignIn(sessions, client = configuration
        ?.takeIf { appleBrowserOAuthEnabled }?.let { SupabaseAppleBrowserOAuthClient(context, it) })
    val cloud = runCatching { AccountCloudService(BuildConfig.BACKEND_URL.toHttpUrl(), sessions) }.getOrNull()
    val backups = configuration?.let { com.lavasec.app.backup.SupabaseBackupClient(it, sessions) }
}
