package com.lavasec.app.foundation.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the redirect/browser callback binding ported from the canonical iOS
 * `AccountAuthService` `ASAuthorizationControllerDelegate` (pinned at `c4658d9`): a callback is
 * applied only to the exact initiating request, and a late or foreign callback is discarded.
 */
class AccountAuthorizationCallbackBinderTest {

    private val apple = AccountAuthProvider.APPLE
    private val google = AccountAuthProvider.GOOGLE

    private fun appleFlow(state: AccountFlowState = AccountFlowState.IDLE): AccountFlowState {
        val result = AccountAuthorizationFlow.beginAuthorization(
            state = state,
            provider = apple,
            nowEpochSeconds = 1_000L,
            nonce = "raw-nonce",
            nonceChallenge = "hashed-nonce",
            authorizationState = "expected-state",
            redirectUri = "https://lavasecurity.app/account/apple",
            requiresStateBinding = true,
        )
        return (result as AccountFlowResult.Applied).state
    }

    private fun callback(
        stateEcho: String? = "expected-state",
        provider: AccountAuthProvider = apple,
        redirectUri: String? = "https://lavasecurity.app/account/apple",
        denial: AccountProviderDenial? = null,
    ) = AccountAuthorizationCallback(
        provider = provider,
        stateEcho = stateEcho,
        redirectUri = redirectUri,
        nonceEcho = "hashed-nonce",
        denial = denial,
        identityToken = "identity-token",
        email = "someone@example.com",
    )

    @Test
    fun `a matching callback binds to the exact initiating request`() {
        val flow = appleFlow()
        val request = requireNotNull(flow.inFlight).request
        val binding = AccountAuthorizationCallbackBinder.bind(flow, callback())
        assertTrue(binding is AccountAuthorizationBinding.Accepted)
        binding as AccountAuthorizationBinding.Accepted
        assertEquals(request.requestId, binding.requestId)
        assertEquals(request.generation, binding.generation)
        assertEquals(apple, binding.provider)

        val granted = AccountAuthorizationCallbackBinder.providerResult(binding, callback())
        assertTrue(granted is AccountProviderResult.Granted)
        granted as AccountProviderResult.Granted
        assertEquals(request.requestId, granted.requestId)
        assertEquals("expected-state", granted.stateEcho)
    }

    @Test
    fun `a foreign state echo is discarded and never becomes a credential`() {
        val flow = appleFlow()
        val binding = AccountAuthorizationCallbackBinder.bind(flow, callback(stateEcho = "someone-elses-state"))
        assertEquals(AccountAuthorizationBinding.Discarded(AccountCallbackDiscard.FOREIGN_CALLBACK), binding)
        assertNull(AccountAuthorizationCallbackBinder.providerResult(binding, callback(stateEcho = "someone-elses-state")))
    }

    @Test
    fun `a callback after completion or sign-out has no in-flight request and is discarded`() {
        val settled = AccountAuthorizationFlow.signOut(appleFlow())
        val state = (settled as AccountFlowResult.Applied).state
        val binding = AccountAuthorizationCallbackBinder.bind(state, callback())
        assertEquals(AccountAuthorizationBinding.Discarded(AccountCallbackDiscard.NOT_IN_FLIGHT), binding)
        assertNull(AccountAuthorizationCallbackBinder.providerResult(binding, callback()))
    }

    @Test
    fun `a callback for another provider or redirect is discarded`() {
        val flow = appleFlow()
        assertEquals(
            AccountAuthorizationBinding.Discarded(AccountCallbackDiscard.PROVIDER_MISMATCH),
            AccountAuthorizationCallbackBinder.bind(flow, callback(provider = google)),
        )
        assertEquals(
            AccountAuthorizationBinding.Discarded(AccountCallbackDiscard.REDIRECT_MISMATCH),
            AccountAuthorizationCallbackBinder.bind(flow, callback(redirectUri = "https://evil.example/callback")),
        )
    }

    @Test
    fun `a denial callback binds and projects a typed denial`() {
        val flow = appleFlow()
        val denial = callback(denial = AccountProviderDenial.CANCELLED)
        val binding = AccountAuthorizationCallbackBinder.bind(flow, denial)
        val result = AccountAuthorizationCallbackBinder.providerResult(binding, denial)
        assertTrue(result is AccountProviderResult.Denied)
        result as AccountProviderResult.Denied
        assertEquals(AccountProviderDenial.CANCELLED, result.denial)
        assertEquals(apple, result.provider)
    }
}
