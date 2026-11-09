package com.lavasec.app.foundation.account

/**
 * Pure value model for the canonical account authorization flow, ported from the iOS
 * `AccountController` / `AccountAuthService` (pinned at `c4658d9`). No Android types, no network,
 * no storage, no crypto: every provider-boundary fact (nonce challenge, callback state, token
 * expiry) is a plain field so the fencing rules are JVM-testable in isolation.
 *
 * The iOS service owns a monotonic `sessionGeneration` and captures it at the start of every
 * sign-in; a provider callback, refresh, or deletion is only applied while its capture still
 * matches. That generation is the fence reproduced here as [AccountFlowState.generation] plus the
 * request identity on [AccountAuthorizationRequest], so a result that arrives after sign-out, an
 * account switch, or a newer request is ignored rather than applied.
 */

/** Canonical providers, ordered as iOS does (`apple` sorts before `google`). */
enum class AccountAuthProvider(val wireValue: String, val sortOrder: Int) {
    APPLE("apple", 0),
    GOOGLE("google", 1),
    ;

    companion object {
        fun fromWireValue(value: String?): AccountAuthProvider? =
            entries.firstOrNull { it.wireValue == value?.lowercase() }
    }
}

/** One provider-confirmed identity bound to the account. The session tokens live outside this model. */
data class LinkedAccount(
    val accountId: String,
    val provider: AccountAuthProvider,
    val email: String? = null,
)

/** The provider-keyed connection set, mirroring canonical `AccountAuthConnections`. */
data class AccountConnections(
    val apple: LinkedAccount? = null,
    val google: LinkedAccount? = null,
) {
    operator fun get(provider: AccountAuthProvider): LinkedAccount? = when (provider) {
        AccountAuthProvider.APPLE -> apple
        AccountAuthProvider.GOOGLE -> google
    }

    fun with(connection: LinkedAccount): AccountConnections = when (connection.provider) {
        AccountAuthProvider.APPLE -> copy(apple = connection)
        AccountAuthProvider.GOOGLE -> copy(google = connection)
    }

    fun without(provider: AccountAuthProvider): AccountConnections = when (provider) {
        AccountAuthProvider.APPLE -> copy(apple = null)
        AccountAuthProvider.GOOGLE -> copy(google = null)
    }

    val all: List<LinkedAccount>
        get() = listOfNotNull(apple, google).sortedBy { it.provider.sortOrder }

    val isEmpty: Boolean get() = apple == null && google == null

    fun containsAccount(accountId: String): Boolean = all.any { it.accountId == accountId }

    /** Canonical `filtered(userID:)`: only the connections that belong to [accountId]. */
    fun onlyAccount(accountId: String): AccountConnections = AccountConnections(
        apple = apple?.takeIf { it.accountId == accountId },
        google = google?.takeIf { it.accountId == accountId },
    )

    fun withoutAccount(accountId: String): AccountConnections = AccountConnections(
        apple = apple?.takeIf { it.accountId != accountId },
        google = google?.takeIf { it.accountId != accountId },
    )
}

/**
 * Observable flow phase. `BUSY` is the admission-refused phase: a second request arrived while one
 * was already in flight, so the in-flight request is retained and completion still applies to it.
 */
enum class AccountFlowPhase { IDLE, AUTHORIZING, LINKING, BUSY, FAILED }

/** Which leg of a single in-flight request is active. */
enum class AccountFlowStep { AUTHORIZING, LINKING }

/**
 * The fenced identity of one provider authorization attempt. `requestId` and `generation` are minted
 * together at [AccountAuthorizationFlow.beginAuthorization] and must BOTH still match when the
 * provider or server result returns.
 */
data class AccountAuthorizationRequest(
    val requestId: Long,
    val generation: Long,
    val provider: AccountAuthProvider,
    /** Non-null when linking an additional provider to an already-signed-in account. */
    val expectedAccountId: String?,
    /** Raw nonce; native-owned and never sent to the provider. */
    val nonce: String,
    /** `sha256(nonce)` presented to the provider and echoed inside the identity token. */
    val nonceChallenge: String,
    /** CSRF/callback state; required for the Apple browser/callback boundary on Android. */
    val authorizationState: String,
    val redirectUri: String?,
    val requiresStateBinding: Boolean,
    val startedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
)

/** The in-flight attempt and its progress; `credential` is set when the provider leg completed. */
data class InFlightAuthorization(
    val request: AccountAuthorizationRequest,
    val step: AccountFlowStep,
    val credential: AccountProviderResult.Granted? = null,
)

/** Why a provider result was usable. */
enum class AccountProviderDenial { CANCELLED, REJECTED, EXPIRED }

/**
 * The provider/browser boundary result. A [Granted] credential still has to pass the pure binding
 * checks (nonce echo, callback state, provider-required tokens, expiry) before the flow may enter
 * the linking leg.
 */
sealed interface AccountProviderResult {
    val requestId: Long
    val provider: AccountAuthProvider

    data class Granted(
        override val requestId: Long,
        override val provider: AccountAuthProvider,
        val identityToken: String,
        val accessToken: String? = null,
        val email: String? = null,
        val providerSubject: String? = null,
        /** Provider-echoed nonce; must equal [AccountAuthorizationRequest.nonceChallenge]. */
        val nonceEcho: String? = null,
        /** Browser-callback state echo; must equal the request's authorization state when required. */
        val stateEcho: String? = null,
        val tokenExpiresAtEpochSeconds: Long? = null,
    ) : AccountProviderResult

    data class Denied(
        override val requestId: Long,
        override val provider: AccountAuthProvider,
        val denial: AccountProviderDenial,
    ) : AccountProviderResult
}

/** The server-confirmed identity returned by the token exchange leg (the linking result). */
data class LinkedAccountIdentity(
    val accountId: String,
    val provider: AccountAuthProvider,
    val email: String? = null,
    val accessTokenExpiresAtEpochSeconds: Long? = null,
)

/** Terminal outcome recorded on the state so cancellation/expiry/error stay distinguishable. */
enum class AccountFlowOutcome {
    SIGNED_IN,
    CANCELLED,
    REJECTED,
    EXPIRED,
    FAILED,
    SIGNED_OUT,
    DELETION_PENDING,
    DELETED,
}

/**
 * A durably-representable pending account deletion. It is NOT complete until
 * [serverAcknowledged] is true; [operationId] binds the acknowledgement receipt to this exact
 * preparation so a later or cross-account deletion can never confirm it.
 */
data class AccountDeletionRecord(
    val operationId: Long,
    val accountId: String,
    val serverAcknowledged: Boolean = false,
    val startedAtEpochSeconds: Long,
)

/** The full pure flow state. */
data class AccountFlowState(
    val phase: AccountFlowPhase = AccountFlowPhase.IDLE,
    val generation: Long = 0L,
    val inFlight: InFlightAuthorization? = null,
    val connections: AccountConnections = AccountConnections(),
    val deletion: AccountDeletionRecord? = null,
    /**
     * False when the durable fence could not be read. This fails closed: an unreadable fence is
     * never treated as "no pending deletion", mirroring the canonical backup deletion fence.
     */
    val deletionFenceReadable: Boolean = true,
    /** Monotonic source of deletion operation ids; never reused. */
    val deletionSequence: Long = 0L,
    /** Id of the last completed deletion, so a retried completion is idempotent. */
    val lastCompletedDeletionId: Long? = null,
    val lastOutcome: AccountFlowOutcome? = null,
) {
    val isSignedIn: Boolean get() = !connections.isEmpty

    val isDeletionPending: Boolean get() = deletion != null

    val isDeletionFenceUnreadable: Boolean get() = !deletionFenceReadable

    val pendingDeletionAccountId: String? get() = deletion?.accountId

    /** The single signed-in account id, when exactly one exists. */
    val currentAccountId: String?
        get() = connections.all.firstOrNull()?.accountId

    fun isDeletionComplete(operationId: Long): Boolean = lastCompletedDeletionId == operationId

    companion object {
        val IDLE = AccountFlowState()
    }
}

/** A result/transition was fenced out; [state] is unchanged apart from any recorded phase hint. */
enum class AccountFlowIgnore {
    NOT_IN_FLIGHT,
    STALE_REQUEST,
    STALE_GENERATION,
    PROVIDER_MISMATCH,
    /** The result belongs to a different account than the request was bound to. */
    CROSS_ACCOUNT,
    STALE_OPERATION,
}

/** A transition was refused because the current state forbids it. */
enum class AccountFlowRefusal {
    FLOW_BUSY,
    NOT_AUTHORIZING_STEP,
    NOT_LINKING_STEP,
    NO_ACCOUNT,
    DELETION_ALREADY_PENDING,
    /** The durable deletion fence could not be read, so no deletion write may proceed. */
    FENCE_UNREADABLE,
    NO_PENDING_DELETION,
    NOT_ACKNOWLEDGED,
    ACCOUNT_MISMATCH,
    ALREADY_COMPLETE,
}

/** Pure result of a flow transition. */
sealed interface AccountFlowResult {
    val state: AccountFlowState

    /** The transition changed state; [outcome] is its typed settlement when terminal. */
    data class Applied(
        override val state: AccountFlowState,
        val outcome: AccountFlowOutcome? = null,
    ) : AccountFlowResult

    /** A fenced-out result: [state] is the unchanged state and [reason] says why. */
    data class Ignored(
        override val state: AccountFlowState,
        val reason: AccountFlowIgnore,
    ) : AccountFlowResult

    /** The transition is not legal from [state]; [reason] says why. */
    data class Refused(
        override val state: AccountFlowState,
        val reason: AccountFlowRefusal,
    ) : AccountFlowResult
}
