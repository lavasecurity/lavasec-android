package com.lavasec.app.account

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Apple authorization provider seam, mirroring [NativeGoogleSignIn] and the canonical iOS
 * `AccountAuthService.signInWithApple` / `ASAuthorizationControllerDelegate` (pinned at
 * `3bd86116`).
 *
 * Android uses Supabase's Apple browser OAuth configuration (Services ID, provider secret and
 * redirect allowlist), rather than a certificate-bound native Apple client. A configured client
 * completes through the SAME [AccountSessionOwner] admission/ticket authority as Google. The
 * PKCE verifier and all session tokens stay native; there is no parallel session store.
 */

/** The provider challenge. The raw nonce stays native-owned; only its hash crosses to the provider. */
internal data class AppleAuthorizationChallenge(
    /** `sha256(rawNonce)` presented to the provider. */
    val nonceChallenge: String,
    /** CSRF/callback state the callback must echo; bound to the exact pending admission. */
    val authorizationState: String,
    /** The redirect URI the callback must be delivered to. */
    val redirectUri: String,
    /** S256 PKCE challenge; its verifier is retained only by AccountSessionOwner. */
    val codeChallenge: String = "",
) {
    override fun toString() = "AppleAuthorizationChallenge(redacted)"
}

/** Native-only provider credential; never a data class, never part of an RN/product snapshot. */
internal class AppleAuthorizationCredential(
    val identityToken: String? = null,
    /** The callback's state echo; must equal the challenge's authorization state. */
    val stateEcho: String,
    val email: String? = null,
    /** Supabase Auth PKCE code, not an Apple ID token. Never crosses a product/JS boundary. */
    val authorizationCode: String? = null,
) {
    init { require((identityToken != null) != (authorizationCode != null)) }
    override fun toString() = "AppleAuthorizationCredential(redacted)"
}

/**
 * The provider/browser boundary. Implementations own the Custom Tab / callback Activity and
 * suspend until the callback; a user cancel throws [ProviderSignInCancelled].
 */
internal interface AppleAuthorizationClient {
    /** The redirect URI registered with the Apple client and fixed on every request. */
    val redirectUri: String

    suspend fun authorize(activity: Activity, challenge: AppleAuthorizationChallenge): AppleAuthorizationCredential
}

/**
 * Single-flight Apple admission, mirroring [GoogleSignInCoordinator]. The operation binds to the
 * owner's ticket/generation captured before the provider UI, so a late or foreign callback (one
 * that arrives after sign-out or does not echo this admission's state) is discarded and can never
 * commit a session.
 */
internal class AppleSignInCoordinator(
    private val accounts: AccountSessionOwner,
    private val configured: Boolean,
) {
    private val admission = Mutex()

    suspend fun signIn(
        redirectUri: String,
        authorize: suspend (AppleAuthorizationChallenge) -> AppleAuthorizationCredential,
    ): NativeSignInResult {
        if (!configured) return NativeSignInResult.UNAVAILABLE
        if (!admission.tryLock()) return NativeSignInResult.BUSY
        var ticket: Long? = null
        var completed = false
        try {
            val admitted = accounts.beginSignIn()
            ticket = admitted
            val challenge = AppleAuthorizationChallenge(
                nonceChallenge = accounts.appleProviderNonce(admitted),
                authorizationState = accounts.providerAuthorizationState(admitted),
                redirectUri = redirectUri,
                codeChallenge = accounts.applePkceChallenge(admitted),
            )
            val credential = supervisorScope {
                val provider = async { authorize(challenge) }
                val retired = async { accounts.state.first { it.generation != admitted } }
                try {
                    select {
                        provider.onAwait { it }
                        retired.onAwait { throw AccountSessionChanged() }
                    }
                } finally {
                    provider.cancel()
                    retired.cancel()
                }
            }
            // A callback that does not echo THIS admission's state is foreign/late: discard it
            // without withdrawing the account authority, and never exchange its token.
            if (credential.stateEcho != challenge.authorizationState) return NativeSignInResult.FAILED
            if (credential.authorizationCode != null) {
                accounts.finishApplePkceSignIn(admitted, credential.authorizationCode)
            } else {
                accounts.finishAppleSignIn(admitted, checkNotNull(credential.identityToken))
            }
            completed = true
            return NativeSignInResult.SIGNED_IN
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ProviderSignInCancelled) {
            return NativeSignInResult.CANCELLED
        } catch (_: ProviderSignInBusy) {
            return NativeSignInResult.BUSY
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

/** Native view adapter. Only an explicit foreground sign-in action may call [signIn]. */
internal class NativeAppleSignIn(
    private val accounts: AccountSessionOwner,
    private val client: AppleAuthorizationClient?,
) {
    /** True only when an Apple client is configured for this build; a null client is unavailable. */
    val configured: Boolean get() = client != null
    private val coordinator = AppleSignInCoordinator(accounts, configured)

    suspend fun signIn(activity: Activity): NativeSignInResult {
        val active = client ?: return NativeSignInResult.UNAVAILABLE
        return withContext(Dispatchers.Main.immediate) {
            if (activity.isFinishing || activity.isDestroyed) return@withContext NativeSignInResult.CANCELLED
            supervisorScope {
                val destroyed = CompletableDeferred<Unit>()
                val lifecycle = object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityDestroyed(host: Activity) {
                        if (host === activity) destroyed.complete(Unit)
                    }
                    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityResumed(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                }
                activity.application.registerActivityLifecycleCallbacks(lifecycle)
                val signingIn = async {
                    coordinator.signIn(active.redirectUri) { challenge -> active.authorize(activity, challenge) }
                }
                try {
                    select {
                        signingIn.onAwait { it }
                        destroyed.onAwait {
                            // RN's module scope can outlive its Activity. Cancel and drain this
                            // complete admission, including HTTP exchange, without rebinding it.
                            signingIn.cancelAndJoin()
                            NativeSignInResult.CANCELLED
                        }
                    }
                } finally {
                    signingIn.cancel()
                    activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
                }
            }
        }
    }
}
