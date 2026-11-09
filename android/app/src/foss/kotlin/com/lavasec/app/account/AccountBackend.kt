package com.lavasec.app.account

import android.content.Context
import com.lavasec.app.backup.SupabaseBackupClient

/** Typed unavailable surface for shared presentation; no session file, provider or API is opened. */
internal class AccountBackend(context: Context, appleBrowserOAuthEnabled: Boolean = false) {
    private val unavailableStore = object : AccountSessionStore {
        override suspend fun load(): AccountSession? = null
        override suspend fun save(session: AccountSession): Unit = throw AccountUnavailable()
        override suspend fun clear() = Unit
    }
    val sessions = AccountSessionOwner(null, unavailableStore)
    val googleSignIn = NativeGoogleSignIn(sessions)
    val appleSignIn = NativeAppleSignIn(sessions, null)
    val cloud: AccountCloudService? = null
    val backups: SupabaseBackupClient? = null
}
