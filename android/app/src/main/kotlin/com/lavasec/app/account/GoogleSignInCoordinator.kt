package com.lavasec.app.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class NativeSignInResult { SIGNED_IN, CANCELLED, BUSY, UNAVAILABLE, SUPERSEDED, FAILED }
internal class ProviderSignInCancelled : Exception()
internal class ProviderSignInBusy : Exception()

/** Process-owned admission; provider credentials never become a product/RN result. */
internal class GoogleSignInCoordinator(private val accounts: AccountSessionOwner, private val configured: Boolean) {
    private val admission = Mutex()
    suspend fun signIn(authorize: suspend (hashedNonce: String) -> String): NativeSignInResult {
        if (!configured) return NativeSignInResult.UNAVAILABLE
        if (!admission.tryLock()) return NativeSignInResult.BUSY
        var ticket: Long? = null
        var completed = false
        try {
            val admitted = accounts.beginSignIn()
            ticket = admitted
            val identityToken = authorize(accounts.googleProviderNonce(admitted))
            accounts.finishGoogleSignIn(admitted, identityToken)
            completed = true
            return NativeSignInResult.SIGNED_IN
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ProviderSignInCancelled) {
            return NativeSignInResult.CANCELLED
        } catch (_: AccountSessionChanged) {
            return NativeSignInResult.SUPERSEDED
        } catch (_: AccountUnavailable) {
            return NativeSignInResult.UNAVAILABLE
        } catch (_: Exception) {
            return NativeSignInResult.FAILED
        } finally {
            if (!completed) ticket?.let { value ->
                withContext(NonCancellable) { runCatching { accounts.cancelSignIn(value) } }
            }
            admission.unlock()
        }
    }
}
