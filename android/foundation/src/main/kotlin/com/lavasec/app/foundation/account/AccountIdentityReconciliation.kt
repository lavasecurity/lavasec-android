package com.lavasec.app.foundation.account

/**
 * Pure provider-linking and legacy-identity reconciliation, ported from the canonical iOS
 * `AccountAuthService.makeConnections` / `canonicalSessions` / `replaceSavedSessionsIfNeeded`
 * (pinned at `c4658d9`). No storage, no network: the caller supplies the persisted per-provider
 * sessions and receives the reconciled connection set, a placement plan, and the canonical account
 * selection. It operates on the existing account models rather than introducing a second authority.
 */

/**
 * One persisted provider session. [provider] is null for a legacy record written before the
 * provider was recorded; the reconciler upgrades it to the provider being linked.
 */
data class PersistedAccountSession(
    val accountId: String,
    val provider: AccountAuthProvider?,
    val email: String? = null,
)

/** Canonical `shouldReplaceConnections`: whether a new identity replaces the whole set or binds one. */
enum class AccountLinkPlacement {
    /** The identity belongs to the signed-in account: keep the sibling provider bindings. */
    BIND_PROVIDER,

    /** The identity belongs to a different account: the canonical account switch replaces the set. */
    REPLACE_ALL,
}

object AccountIdentityReconciliation {

    /**
     * Canonical `makeConnections(from:fallbackProvider:fallbackEmail:existingConnections:)`:
     * existing connections that belong to a different account are dropped, then [identity]'s
     * provider is bound. [fallbackEmail] is the provider credential's email, used only when the
     * server identity carries none.
     */
    fun bindProvider(
        existing: AccountConnections,
        identity: LinkedAccountIdentity,
        fallbackEmail: String? = null,
    ): AccountConnections {
        val connection = LinkedAccount(
            accountId = identity.accountId,
            provider = identity.provider,
            email = identity.email ?: fallbackEmail,
        )
        return existing.onlyAccount(identity.accountId).with(connection)
    }

    /**
     * Canonical `replaceSavedSessionsIfNeeded`: a non-empty connection set whose signed-in account
     * differs from [identity]'s account is replaced wholesale (account switch); otherwise only the
     * provider is bound and the sibling provider link is preserved.
     */
    fun placement(existing: AccountConnections, identity: LinkedAccountIdentity): AccountLinkPlacement =
        if (!existing.isEmpty && !existing.containsAccount(identity.accountId)) {
            AccountLinkPlacement.REPLACE_ALL
        } else {
            AccountLinkPlacement.BIND_PROVIDER
        }

    /**
     * Canonical `canonicalSessions`: among persisted sessions, the account with the most sessions
     * wins; ties break to the lexicographically smallest account id. Returns null when empty.
     */
    fun canonicalAccountId(sessions: List<PersistedAccountSession>): String? =
        sessions.groupBy { it.accountId }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<PersistedAccountSession>>> { it.value.size }
                    .thenBy { it.key },
            )
            .firstOrNull()
            ?.key

    /**
     * Upgrades a legacy persisted session (no recorded provider) by binding it to the provider being
     * linked. A non-legacy session is returned unchanged.
     */
    fun withLegacyProvider(
        session: PersistedAccountSession,
        linkingProvider: AccountAuthProvider,
    ): PersistedAccountSession =
        if (session.provider == null) session.copy(provider = linkingProvider) else session

    /**
     * Canonical `makeConnections(from savedSessions)`: selects the canonical account's sessions,
     * upgrades any legacy record to [linkingProvider], and folds them into the connection set.
     * Providers that still cannot be named after the legacy upgrade are skipped rather than
     * fabricating a link.
     */
    fun connectionsFrom(
        sessions: List<PersistedAccountSession>,
        linkingProvider: AccountAuthProvider,
    ): AccountConnections {
        val canonical = canonicalAccountId(sessions) ?: return AccountConnections()
        return sessions
            .asSequence()
            .filter { it.accountId == canonical }
            .map { withLegacyProvider(it, linkingProvider) }
            .fold(AccountConnections()) { connections, session ->
                val provider = session.provider ?: return@fold connections
                connections.with(LinkedAccount(session.accountId, provider, session.email))
            }
    }
}
