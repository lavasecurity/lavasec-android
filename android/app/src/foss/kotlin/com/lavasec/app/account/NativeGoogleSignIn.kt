package com.lavasec.app.account

import android.app.Activity

/** No credential provider is queried by the FOSS edition. */
internal class NativeGoogleSignIn(private val accounts: AccountSessionOwner) {
    val configured = false
    suspend fun signIn(activity: Activity): NativeSignInResult = NativeSignInResult.UNAVAILABLE
    suspend fun signOut(expectedGeneration: Long, isCurrent: () -> Boolean = { true }) = Unit
}
