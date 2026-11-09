package com.lavasec.app.foundation.account

/**
 * Pure, fenced account authorization-flow state machine, ported from the canonical iOS
 * `AccountController` / `AccountAuthService` (pinned at `c4658d9`). It covers single-flight
 * admission, the provider leg (Google native and Apple browser/callback), the server token-exchange
 * leg ("linking"), cancellation, account switch, sign-out, and account deletion acknowledgement.
 *
 * Fencing rules, all structural:
 *
 * 1. **Generation bump on every ownership change.** Beginning a request, settling a failure or
 *    cancellation, completing a link, and signing out all advance [AccountFlowState.generation].
 *    A provider or server result returns with the `requestId`/`generation` captured at issuance and
 *    is only applied while BOTH still match.
 * 2. **Provider binding.** A result's provider must equal the request's provider, its nonce echo
 *    must equal the request's `nonceChallenge`, and — when the request requires it — its callback
 *    state echo must equal the request's authorization state.
 * 3. **Account binding.** A request may carry an `expectedAccountId` (provider linking); an
 *    identity for a different account is never applied. Deletion acknowledgement and completion
 *    refuse when the signed-in account is a different user.
 * 4. **Deletion is never silently complete.** A pending record is only completed after a matching
 *    acknowledgement; an unconfirmed delete stays pending and local sign-out does not clear it.
 */
object AccountAuthorizationFlow {

    /** Canonical `makeRandomNonce` lifetime window (±10 minutes). */
    const val NONCE_LIFETIME_SECONDS: Long = 600L

    /** Admission. Refused `FLOW_BUSY` (and the phase moves to `BUSY`) while a request is in flight. */
    fun beginAuthorization(
        state: AccountFlowState,
        provider: AccountAuthProvider,
        nowEpochSeconds: Long,
        nonce: String,
        nonceChallenge: String,
        authorizationState: String,
        redirectUri: String? = null,
        requiresStateBinding: Boolean = false,
        expectedAccountId: String? = null,
        lifetimeSeconds: Long = NONCE_LIFETIME_SECONDS,
    ): AccountFlowResult {
        if (state.inFlight != null) {
            return AccountFlowResult.Refused(state.copy(phase = AccountFlowPhase.BUSY), AccountFlowRefusal.FLOW_BUSY)
        }
        val generation = state.generation + 1
        val request = AccountAuthorizationRequest(
            requestId = generation,
            generation = generation,
            provider = provider,
            expectedAccountId = expectedAccountId,
            nonce = nonce,
            nonceChallenge = nonceChallenge,
            authorizationState = authorizationState,
            redirectUri = redirectUri,
            requiresStateBinding = requiresStateBinding,
            startedAtEpochSeconds = nowEpochSeconds,
            expiresAtEpochSeconds = nowEpochSeconds + lifetimeSeconds,
        )
        return AccountFlowResult.Applied(
            state.copy(
                phase = AccountFlowPhase.AUTHORIZING,
                generation = generation,
                inFlight = InFlightAuthorization(request, AccountFlowStep.AUTHORIZING),
                lastOutcome = null,
            ),
        )
    }

    /**
     * Provider/browser leg completion. Denials settle to a typed outcome; a [AccountProviderResult.Granted]
     * credential that fails any binding check settles to `REJECTED`/`EXPIRED`. A valid credential
     * advances the SAME request to the linking leg.
     */
    fun completeAuthorization(
        state: AccountFlowState,
        result: AccountProviderResult,
        nowEpochSeconds: Long,
    ): AccountFlowResult {
        val flight = state.inFlight ?: return ignore(state, AccountFlowIgnore.NOT_IN_FLIGHT)
        if (flight.step != AccountFlowStep.AUTHORIZING) {
            return AccountFlowResult.Refused(state, AccountFlowRefusal.NOT_AUTHORIZING_STEP)
        }
        if (result.requestId != flight.request.requestId) return ignore(state, AccountFlowIgnore.STALE_REQUEST)
        if (state.generation != flight.request.generation) return ignore(state, AccountFlowIgnore.STALE_GENERATION)
        if (result.provider != flight.request.provider) return ignore(state, AccountFlowIgnore.PROVIDER_MISMATCH)

        return when (result) {
            is AccountProviderResult.Denied -> settle(
                state = state,
                outcome = when (result.denial) {
                    AccountProviderDenial.CANCELLED -> AccountFlowOutcome.CANCELLED
                    AccountProviderDenial.REJECTED -> AccountFlowOutcome.REJECTED
                    AccountProviderDenial.EXPIRED -> AccountFlowOutcome.EXPIRED
                },
                phase = if (result.denial == AccountProviderDenial.CANCELLED) {
                    AccountFlowPhase.IDLE
                } else {
                    AccountFlowPhase.FAILED
                },
            )

            is AccountProviderResult.Granted -> {
                val rejection = credentialRejection(flight.request, result, nowEpochSeconds)
                if (rejection != null) {
                    settle(state, rejection, AccountFlowPhase.FAILED)
                } else {
                    AccountFlowResult.Applied(
                        state.copy(
                            phase = AccountFlowPhase.LINKING,
                            inFlight = flight.copy(step = AccountFlowStep.LINKING, credential = result),
                            lastOutcome = null,
                        ),
                    )
                }
            }
        }
    }

    /** Returns the typed failure of an otherwise usable credential, or null when it is accepted. */
    private fun credentialRejection(
        request: AccountAuthorizationRequest,
        credential: AccountProviderResult.Granted,
        nowEpochSeconds: Long,
    ): AccountFlowOutcome? = when {
        nowEpochSeconds > request.expiresAtEpochSeconds -> AccountFlowOutcome.EXPIRED

        credential.identityToken.isBlank() -> AccountFlowOutcome.REJECTED

        credential.nonceEcho != request.nonceChallenge -> AccountFlowOutcome.REJECTED

        request.requiresStateBinding && credential.stateEcho != request.authorizationState ->
            AccountFlowOutcome.REJECTED

        request.provider == AccountAuthProvider.GOOGLE && credential.accessToken.isNullOrBlank() ->
            AccountFlowOutcome.REJECTED

        credential.tokenExpiresAtEpochSeconds != null &&
            credential.tokenExpiresAtEpochSeconds <= nowEpochSeconds -> AccountFlowOutcome.EXPIRED

        else -> null
    }

    /**
     * Server token-exchange leg completion. The identity is bound to the request's
     * `expectedAccountId` (when present) and to the request's provider; a different account is
     * ignored. On success the connection set is merged when the identity matches the signed-in
     * account, or replaced when signing in as a different account (canonical account switch).
     */
    fun completeLinking(
        state: AccountFlowState,
        identity: LinkedAccountIdentity,
    ): AccountFlowResult {
        val flight = state.inFlight ?: return ignore(state, AccountFlowIgnore.NOT_IN_FLIGHT)
        if (flight.step != AccountFlowStep.LINKING) {
            return AccountFlowResult.Refused(state, AccountFlowRefusal.NOT_LINKING_STEP)
        }
        if (state.generation != flight.request.generation) return ignore(state, AccountFlowIgnore.STALE_GENERATION)
        if (identity.provider != flight.request.provider) return ignore(state, AccountFlowIgnore.PROVIDER_MISMATCH)
        val expected = flight.request.expectedAccountId
        if (expected != null && expected != identity.accountId) return ignore(state, AccountFlowIgnore.CROSS_ACCOUNT)
        if (identity.accountId.isBlank()) return settle(state, AccountFlowOutcome.REJECTED, AccountFlowPhase.FAILED)

        val baseline = when (AccountIdentityReconciliation.placement(state.connections, identity)) {
            // Different account: the canonical account switch replaces the prior connections.
            AccountLinkPlacement.REPLACE_ALL -> AccountConnections()
            // Same account (or first link): keep that account's connections, then bind this provider.
            AccountLinkPlacement.BIND_PROVIDER -> state.connections
        }
        val nextConnections = AccountIdentityReconciliation.bindProvider(
            existing = baseline,
            identity = identity,
            fallbackEmail = flight.credential?.email,
        )
        return AccountFlowResult.Applied(
            state.copy(
                phase = AccountFlowPhase.IDLE,
                generation = state.generation + 1,
                inFlight = null,
                connections = nextConnections,
                lastOutcome = AccountFlowOutcome.SIGNED_IN,
            ),
            AccountFlowOutcome.SIGNED_IN,
        )
    }

    /**
     * Cancellation. A completed login is authoritative and is never mislabelled, but a cancellation
     * arriving while a request is in flight (and before its linking commit) supersedes that request.
     */
    fun cancelAuthorization(state: AccountFlowState, requestId: Long): AccountFlowResult {
        val flight = state.inFlight ?: return ignore(state, AccountFlowIgnore.NOT_IN_FLIGHT)
        if (flight.request.requestId != requestId) return ignore(state, AccountFlowIgnore.STALE_REQUEST)
        return settle(state, AccountFlowOutcome.CANCELLED, AccountFlowPhase.IDLE)
    }

    /**
     * Local sign-out: revokes local authority and clears the connection set, but leaves any pending
     * deletion fence intact (the server acknowledgement is durable and outlives the session).
     */
    fun signOut(state: AccountFlowState, expectedGeneration: Long? = null): AccountFlowResult {
        if (expectedGeneration != null && expectedGeneration != state.generation) {
            return AccountFlowResult.Refused(state, AccountFlowRefusal.ACCOUNT_MISMATCH)
        }
        return AccountFlowResult.Applied(
            state.copy(
                phase = AccountFlowPhase.IDLE,
                generation = state.generation + 1,
                inFlight = null,
                connections = AccountConnections(),
                lastOutcome = AccountFlowOutcome.SIGNED_OUT,
            ),
            AccountFlowOutcome.SIGNED_OUT,
        )
    }

    /**
     * Begins a deletion for the currently signed-in account. A concurrent/pending deletion is
     * refused rather than duplicated; resume it with [resumeDeletion].
     */
    fun beginDeletion(
        state: AccountFlowState,
        accountId: String,
        nowEpochSeconds: Long,
    ): AccountFlowResult {
        if (!state.deletionFenceReadable) {
            return AccountFlowResult.Refused(state, AccountFlowRefusal.FENCE_UNREADABLE)
        }
        if (state.deletion != null) return AccountFlowResult.Refused(state, AccountFlowRefusal.DELETION_ALREADY_PENDING)
        if (accountId.isBlank()) return AccountFlowResult.Refused(state, AccountFlowRefusal.NO_ACCOUNT)
        val current = state.currentAccountId ?: return AccountFlowResult.Refused(state, AccountFlowRefusal.NO_ACCOUNT)
        if (current != accountId) return AccountFlowResult.Refused(state, AccountFlowRefusal.ACCOUNT_MISMATCH)

        val sequence = state.deletionSequence + 1
        val record = AccountDeletionRecord(
            operationId = sequence,
            accountId = accountId,
            serverAcknowledged = false,
            startedAtEpochSeconds = nowEpochSeconds,
        )
        return AccountFlowResult.Applied(
            state.copy(
                deletion = record,
                deletionSequence = sequence,
                lastOutcome = AccountFlowOutcome.DELETION_PENDING,
            ),
            AccountFlowOutcome.DELETION_PENDING,
        )
    }

    /** Idempotent resume: returns the existing pending record without minting a new one. */
    fun resumeDeletion(state: AccountFlowState): AccountFlowResult {
        val record = state.deletion ?: return AccountFlowResult.Refused(state, AccountFlowRefusal.NO_PENDING_DELETION)
        return AccountFlowResult.Applied(state.copy(deletion = record), AccountFlowOutcome.DELETION_PENDING)
    }

    /**
     * Server deletion acknowledgement. This is the ONLY transition that flips
     * [AccountDeletionRecord.serverAcknowledged]; it is idempotent and refuses a cross-user write.
     */
    fun acknowledgeDeletion(state: AccountFlowState, operationId: Long): AccountFlowResult {
        val record = state.deletion ?: return AccountFlowResult.Refused(state, AccountFlowRefusal.NO_PENDING_DELETION)
        if (record.operationId != operationId) return ignore(state, AccountFlowIgnore.STALE_OPERATION)
        val current = state.currentAccountId
        if (current != null && current != record.accountId) {
            return AccountFlowResult.Refused(state, AccountFlowRefusal.ACCOUNT_MISMATCH)
        }
        if (record.serverAcknowledged) return AccountFlowResult.Applied(state, AccountFlowOutcome.DELETION_PENDING)
        return AccountFlowResult.Applied(
            state.copy(deletion = record.copy(serverAcknowledged = true)),
            AccountFlowOutcome.DELETION_PENDING,
        )
    }

    /**
     * Completes local teardown. Refuses while the server has not acknowledged (`NOT_ACKNOWLEDGED`);
     * a retry of an already-completed operation is idempotent; a cross-user completion is refused.
     */
    fun completeDeletion(state: AccountFlowState, operationId: Long): AccountFlowResult {
        if (state.lastCompletedDeletionId == operationId) {
            return AccountFlowResult.Applied(state, AccountFlowOutcome.DELETED)
        }
        val record = state.deletion ?: return AccountFlowResult.Refused(state, AccountFlowRefusal.NO_PENDING_DELETION)
        if (record.operationId != operationId) return ignore(state, AccountFlowIgnore.STALE_OPERATION)
        if (!record.serverAcknowledged) return AccountFlowResult.Refused(state, AccountFlowRefusal.NOT_ACKNOWLEDGED)
        val current = state.currentAccountId
        if (current != null && current != record.accountId) {
            return AccountFlowResult.Refused(state, AccountFlowRefusal.ACCOUNT_MISMATCH)
        }
        return AccountFlowResult.Applied(
            state.copy(
                phase = AccountFlowPhase.IDLE,
                generation = state.generation + 1,
                inFlight = null,
                connections = state.connections.withoutAccount(record.accountId),
                deletion = null,
                lastCompletedDeletionId = operationId,
                lastOutcome = AccountFlowOutcome.DELETED,
            ),
            AccountFlowOutcome.DELETED,
        )
    }

    /** Restores a persisted pending deletion into the state, replacing any in-memory record. */
    fun restoreDeletion(state: AccountFlowState, read: AccountDeletionRead): AccountFlowState =
        when (read) {
            is AccountDeletionRead.Present ->
                state.copy(deletion = read.record, deletionFenceReadable = true)
            AccountDeletionRead.Absent ->
                state.copy(deletion = null, deletionFenceReadable = true)
            AccountDeletionRead.Unreadable ->
                state.copy(deletion = null, deletionFenceReadable = false)
        }

    private fun settle(
        state: AccountFlowState,
        outcome: AccountFlowOutcome,
        phase: AccountFlowPhase,
    ): AccountFlowResult = AccountFlowResult.Applied(
        state.copy(
            phase = phase,
            generation = state.generation + 1,
            inFlight = null,
            lastOutcome = outcome,
        ),
        outcome,
    )

    private fun ignore(state: AccountFlowState, reason: AccountFlowIgnore): AccountFlowResult =
        AccountFlowResult.Ignored(state, reason)
}
