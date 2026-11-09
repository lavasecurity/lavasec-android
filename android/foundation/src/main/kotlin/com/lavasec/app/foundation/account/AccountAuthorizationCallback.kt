package com.lavasec.app.foundation.account

/**
 * Redirect/browser provider-callback seam, ported from the canonical iOS `AccountAuthService`
 * `ASAuthorizationControllerDelegate` (pinned at `c4658d9`). iOS stores ONE
 * `authorizationContinuation`; a delegate callback that arrives with no live continuation is
 * dropped, and the `sessionGeneration` captured at issuance invalidates a callback whose
 * operation was superseded. On Android the provider callback returns through a deep-link/Activity
 * intent after a browser round-trip, so it carries no request identity: it is matched back to the
 * initiating request by the `state`/nonce echo, and anything else is discarded before it can reach
 * [AccountAuthorizationFlow.completeAuthorization].
 *
 * This is a pure helper over the existing flow types; it is not a second authority. The flow still
 * owns the fenced transition and re-checks request id, generation, provider and state echo.
 */

/** A raw provider callback as it arrives from the browser/redirect boundary. */
data class AccountAuthorizationCallback(
    val provider: AccountAuthProvider,
    /** The provider-echoed callback `state`; must equal the request's authorization state. */
    val stateEcho: String?,
    /** The redirect URI the callback was delivered to, when the request fixed one. */
    val redirectUri: String? = null,
    /** Provider-echoed nonce; carried through so the flow's own echo check also applies. */
    val nonceEcho: String? = null,
    /** Non-null for a denial callback; the credential fields are then ignored. */
    val denial: AccountProviderDenial? = null,
    val identityToken: String? = null,
    val email: String? = null,
)

/** Why a callback could not be bound to an initiating request. */
enum class AccountCallbackDiscard {
    /** No request is in flight (a late callback after completion/cancel/sign-out). */
    NOT_IN_FLIGHT,

    /** The callback's provider differs from the initiating request's provider. */
    PROVIDER_MISMATCH,

    /** The in-flight request's generation no longer matches the state's generation. */
    STALE_GENERATION,

    /** The callback was delivered to a different redirect URI than the request fixed. */
    REDIRECT_MISMATCH,

    /** The state echo does not match the request's authorization state (foreign callback). */
    FOREIGN_CALLBACK,
}

/** The result of binding a raw callback to the initiating request. */
sealed interface AccountAuthorizationBinding {
    data class Accepted(
        val requestId: Long,
        val generation: Long,
        val provider: AccountAuthProvider,
    ) : AccountAuthorizationBinding

    data class Discarded(val reason: AccountCallbackDiscard) : AccountAuthorizationBinding
}

object AccountAuthorizationCallbackBinder {

    /**
     * Binds [callback] to the state's in-flight request. Only a callback whose provider, redirect
     * URI and authorization state echo match the exact initiating request is [Accepted]; every
     * late, foreign or stale callback is [AccountAuthorizationBinding.Discarded] and must never be
     * turned into a credential.
     */
    fun bind(
        state: AccountFlowState,
        callback: AccountAuthorizationCallback,
    ): AccountAuthorizationBinding {
        val flight = state.inFlight ?: return AccountAuthorizationBinding.Discarded(AccountCallbackDiscard.NOT_IN_FLIGHT)
        val request = flight.request
        if (state.generation != request.generation) {
            return AccountAuthorizationBinding.Discarded(AccountCallbackDiscard.STALE_GENERATION)
        }
        if (request.provider != callback.provider) {
            return AccountAuthorizationBinding.Discarded(AccountCallbackDiscard.PROVIDER_MISMATCH)
        }
        if (request.redirectUri != null && callback.redirectUri != request.redirectUri) {
            return AccountAuthorizationBinding.Discarded(AccountCallbackDiscard.REDIRECT_MISMATCH)
        }
        if (request.requiresStateBinding && callback.stateEcho != request.authorizationState) {
            return AccountAuthorizationBinding.Discarded(AccountCallbackDiscard.FOREIGN_CALLBACK)
        }
        return AccountAuthorizationBinding.Accepted(request.requestId, request.generation, request.provider)
    }

    /**
     * Projects a bound callback into the provider result the flow consumes, or null for a discarded
     * callback (so a late/foreign callback can never be applied). The flow still re-validates the
     * result against the live request.
     */
    fun providerResult(
        binding: AccountAuthorizationBinding,
        callback: AccountAuthorizationCallback,
    ): AccountProviderResult? = when (binding) {
        is AccountAuthorizationBinding.Accepted -> {
            val denial = callback.denial
            if (denial != null) {
                AccountProviderResult.Denied(binding.requestId, binding.provider, denial)
            } else {
                AccountProviderResult.Granted(
                    requestId = binding.requestId,
                    provider = binding.provider,
                    identityToken = callback.identityToken.orEmpty(),
                    email = callback.email,
                    nonceEcho = callback.nonceEcho,
                    stateEcho = callback.stateEcho,
                )
            }
        }

        is AccountAuthorizationBinding.Discarded -> null
    }
}
