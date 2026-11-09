package com.lavasec.app.account

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Native view adapter. Only an explicit foreground sign-in action may call [signIn]. */
internal class NativeGoogleSignIn(
    context: Context,
    private val accounts: AccountSessionOwner,
    private val serverClientId: String,
) {
    private val credentials = CredentialManager.create(context.applicationContext)
    val configured = Regex("[0-9]+-[a-zA-Z0-9_-]+\\.apps\\.googleusercontent\\.com").matches(serverClientId)
    private val coordinator = GoogleSignInCoordinator(accounts, configured)

    suspend fun signIn(activity: Activity): NativeSignInResult = coordinator.signIn { nonce ->
        check(!activity.isFinishing && !activity.isDestroyed)
        val option = GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = try {
            credentials.getCredential(MutableContextWrapper(activity), request)
        } catch (_: GetCredentialCancellationException) { throw ProviderSignInCancelled() }
        val credential = result.credential as? CustomCredential ?: error("Unexpected credential type")
        check(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    suspend fun signOut(expectedGeneration: Long, isCurrent: () -> Boolean = { true }) {
        accounts.requireCurrent(expectedGeneration)
        var revoked = false
        try { accounts.signOut(expectedGeneration) {
            isCurrent().also { admitted -> revoked = admitted }
        } }
        finally {
            // App authority is already revoked. Clearing the provider's account-selection cache
            // cannot resurrect it, and a disconnected Play service must not wedge local logout.
            if (revoked && accounts.state.value.generation == expectedGeneration + 1 &&
                accounts.state.value.availability != AccountAvailability.SIGNED_IN) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5_000) {
                        // The Main/worker hop may have admitted a successor login. Its
                        // provider selection must not be cleared by this original logout.
                        if (accounts.state.value.generation == expectedGeneration + 1 &&
                            accounts.state.value.availability != AccountAvailability.SIGNED_IN) {
                            runCatching { credentials.clearCredentialState(ClearCredentialStateRequest()) }
                        }
                    }
                }
            }
        }
    }
}
