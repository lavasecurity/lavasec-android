package com.lavasec.app.account

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Native external-browser OAuth. The Supabase provider and redirect allowlist are release inputs. */
internal class SupabaseAppleBrowserOAuthClient(
    context: Context,
    private val configuration: AccountServiceConfiguration,
    private val browser: AppleOAuthBrowserBroker = AppleOAuthBrowserBroker.process,
    private val launch: (Activity, String) -> Unit = { host, requestId ->
        host.startActivity(Intent(host, AppleOAuthBrowserActivity::class.java)
            .putExtra(AppleOAuthBrowserActivity.REQUEST_ID, requestId))
    },
    private val timeoutMillis: Long = 300_000,
) : AppleAuthorizationClient {
    private val application = context.applicationContext as Application
    override val redirectUri = "${context.packageName}.auth://oauth/apple/"

    override suspend fun authorize(activity: Activity, challenge: AppleAuthorizationChallenge): AppleAuthorizationCredential =
        withContext(Dispatchers.Main.immediate) {
            if (activity.isFinishing || activity.isDestroyed) throw ProviderSignInCancelled()
            require(challenge.redirectUri == redirectUri)
            require(challenge.authorizationState.matches(Regex("[A-Za-z0-9_-]{16,128}")))
            require(challenge.codeChallenge.matches(Regex("[A-Za-z0-9_-]{43}")))
            // Supabase owns the provider's OAuth state. Bind our separate native admission in
            // the redirect path rather than attempting to replace that provider state.
            val callback = redirectUri + challenge.authorizationState
            val url = configuration.projectUrl.newBuilder().encodedPath("/auth/v1/authorize")
                .addQueryParameter("provider", "apple")
                .addQueryParameter("redirect_to", callback)
                .addQueryParameter("scopes", "name email")
                .addQueryParameter("code_challenge", challenge.codeChallenge)
                .addQueryParameter("code_challenge_method", "s256").build()
            val request = AppleOAuthBrowserRequest(challenge.authorizationState, callback, url.toString())
            val lifecycle = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityDestroyed(destroyed: Activity) {
                    if (destroyed === activity) browser.cancel(request.id)
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            }
            application.registerActivityLifecycleCallbacks(lifecycle)
            try {
                val credential = withTimeoutOrNull(timeoutMillis) { browser.await(request) { launch(activity, it) } }
                    ?: throw ProviderSignInCancelled()
                if (activity.isFinishing || activity.isDestroyed) throw ProviderSignInCancelled()
                credential
            } finally {
                application.unregisterActivityLifecycleCallbacks(lifecycle)
            }
        }
}

/** No verifier, identity token or session token is stored in the browser/callback broker. */
internal class AppleOAuthBrowserRequest(val id: String, val callback: String, val authorizationUrl: String) {
    override fun toString() = "AppleOAuthBrowserRequest(redacted)"
}

internal class AppleBrowserAuthorizationFailed : Exception("Apple authorization could not be completed")

/** Process-only, one-use callback routing. Process loss never reloads or invents a pending flow. */
internal class AppleOAuthBrowserBroker {
    private class Pending(
        val request: AppleOAuthBrowserRequest,
        val continuation: CancellableContinuation<AppleAuthorizationCredential>,
        var closeHost: (() -> Unit)? = null,
    )
    private val lock = Any()
    private var pending: Pending? = null

    suspend fun await(request: AppleOAuthBrowserRequest, launch: (String) -> Unit): AppleAuthorizationCredential =
        suspendCancellableCoroutine { continuation ->
            val admitted = Pending(request, continuation)
            synchronized(lock) {
                if (pending != null) {
                    continuation.resumeWithException(ProviderSignInBusy())
                    return@suspendCancellableCoroutine
                }
                pending = admitted
            }
            continuation.invokeOnCancellation { retire(admitted) }
            if (continuation.isActive && isPending(request.id)) {
                try { launch(request.id) }
                catch (_: Exception) { unavailable(request.id) }
            }
        }

    fun isPending(id: String): Boolean = synchronized(lock) { pending?.request?.id == id }
    fun authorizationUrl(id: String): String? = synchronized(lock) {
        pending?.takeIf { it.request.id == id }?.request?.authorizationUrl
    }

    fun attach(id: String, closeHost: () -> Unit): Boolean = synchronized(lock) {
        val current = pending?.takeIf { it.request.id == id } ?: return@synchronized false
        current.closeHost = closeHost
        true
    }

    fun detach(id: String) = synchronized(lock) {
        pending?.takeIf { it.request.id == id }?.closeHost = null
    }

    fun cancel(id: String) = fail(id, ProviderSignInCancelled())
    fun unavailable(id: String) = fail(id, AccountUnavailable())

    /** Foreign, replayed and process-lost callbacks leave all account authority untouched. */
    fun callback(rawUri: String): Boolean {
        val current = synchronized(lock) { pending } ?: return false
        val result = AppleOAuthCallback.parse(current.request.callback, rawUri) ?: return false
        if (!retire(current)) return false
        if (!current.continuation.isActive) return false
        when (result) {
            is AppleOAuthCallback.Code -> current.continuation.resume(AppleAuthorizationCredential(
                stateEcho = current.request.id, authorizationCode = result.value))
            AppleOAuthCallback.Cancelled -> current.continuation.resumeWithException(ProviderSignInCancelled())
            AppleOAuthCallback.Invalid -> current.continuation.resumeWithException(AppleBrowserAuthorizationFailed())
        }
        return true
    }

    private fun fail(id: String, failure: Exception) {
        val current = synchronized(lock) { pending?.takeIf { it.request.id == id } } ?: return
        if (retire(current) && current.continuation.isActive) current.continuation.resumeWithException(failure)
    }

    private fun retire(current: Pending): Boolean {
        val closeHost = synchronized(lock) {
            if (pending !== current) return false
            pending = null
            current.closeHost
        }
        closeHost?.let { close -> Handler(Looper.getMainLooper()).post { close() } }
        return true
    }

    companion object { val process = AppleOAuthBrowserBroker() }
}

/** Exact callback admission. Implicit fragment tokens and ambiguous fields are never accepted. */
internal sealed interface AppleOAuthCallback {
    class Code(val value: String) : AppleOAuthCallback {
        override fun toString() = "AppleOAuthCallback.Code(redacted)"
    }
    data object Cancelled : AppleOAuthCallback
    data object Invalid : AppleOAuthCallback

    companion object {
        fun parse(expected: String, raw: String): AppleOAuthCallback? {
            if (raw.length > 8192) return null
            val target = runCatching { URI(expected) }.getOrNull() ?: return null
            val received = runCatching { URI(raw) }.getOrNull() ?: return null
            if (received.scheme != target.scheme || received.rawAuthority != target.rawAuthority ||
                received.rawPath != target.rawPath || received.userInfo != null || received.port != -1
            ) return null
            if (received.rawFragment != null) return Invalid
            val parameters = linkedMapOf<String, String>()
            try {
                for (part in received.rawQuery?.split('&').orEmpty()) {
                    val pair = part.split('=', limit = 2)
                    if (pair.size != 2) return Invalid
                    val key = URLDecoder.decode(pair[0], "UTF-8")
                    val value = URLDecoder.decode(pair[1], "UTF-8")
                    if (key !in setOf("code", "error", "error_code", "error_description") ||
                        parameters.put(key, value) != null
                    ) return Invalid
                }
            } catch (_: IllegalArgumentException) { return Invalid }
            val code = parameters["code"]
            if (code != null) {
                if (parameters.size != 1 || code.length !in 1..2048 ||
                    code.any { it.isWhitespace() || it.isISOControl() }
                ) return Invalid
                return Code(code)
            }
            return if (parameters["error"] == "access_denied") Cancelled else Invalid
        }
    }
}
