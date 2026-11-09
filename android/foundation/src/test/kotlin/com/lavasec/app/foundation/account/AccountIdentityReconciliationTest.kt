package com.lavasec.app.foundation.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the pure provider-linking / legacy-identity reconciliation ported from the canonical iOS
 * `AccountAuthService` (pinned at `c4658d9`). Pure transitions over the existing account models.
 */
class AccountIdentityReconciliationTest {

    private val apple = AccountAuthProvider.APPLE
    private val google = AccountAuthProvider.GOOGLE

    private fun identity(
        accountId: String = "A",
        provider: AccountAuthProvider = apple,
        email: String? = "$accountId" + "@example.com",
    ) = LinkedAccountIdentity(accountId = accountId, provider = provider, email = email)

    @Test
    fun `binding a provider to the same account preserves the sibling link`() {
        val existing = AccountConnections().with(LinkedAccount("A", google, "a@example.com"))
        val linked = AccountIdentityReconciliation.bindProvider(existing, identity(accountId = "A", provider = apple))
        assertEquals("A", linked.apple?.accountId)
        assertEquals("A", linked.google?.accountId)
        assertEquals(listOf(apple, google), linked.all.map { it.provider })
    }

    @Test
    fun `binding a different account drops the previous account connections`() {
        val existing = AccountConnections().with(LinkedAccount("A", google, "a@example.com"))
        val linked = AccountIdentityReconciliation.bindProvider(existing, identity(accountId = "B", provider = apple))
        assertNull(linked.google)
        assertEquals("B", linked.apple?.accountId)
    }

    @Test
    fun `the credential fallback email is used only when the identity carries none`() {
        val existing = AccountConnections()
        val withFallback = AccountIdentityReconciliation.bindProvider(
            existing,
            identity(accountId = "A", provider = apple, email = null),
            fallbackEmail = "fallback@example.com",
        )
        assertEquals("fallback@example.com", withFallback.apple?.email)

        val identityEmail = AccountIdentityReconciliation.bindProvider(
            existing,
            identity(accountId = "A", provider = apple, email = "server@example.com"),
            fallbackEmail = "fallback@example.com",
        )
        assertEquals("server@example.com", identityEmail.apple?.email)
    }

    @Test
    fun `placement replaces the set only for a non-empty set of another account`() {
        val empty = AccountConnections()
        assertEquals(
            AccountLinkPlacement.BIND_PROVIDER,
            AccountIdentityReconciliation.placement(empty, identity(accountId = "B")),
        )
        val same = AccountConnections().with(LinkedAccount("A", google))
        assertEquals(
            AccountLinkPlacement.BIND_PROVIDER,
            AccountIdentityReconciliation.placement(same, identity(accountId = "A")),
        )
        val different = AccountConnections().with(LinkedAccount("A", google))
        assertEquals(
            AccountLinkPlacement.REPLACE_ALL,
            AccountIdentityReconciliation.placement(different, identity(accountId = "B")),
        )
    }

    @Test
    fun `canonical account selection uses majority with a lexicographic tie-break`() {
        assertNull(AccountIdentityReconciliation.canonicalAccountId(emptyList()))
        // One A, two B: B is canonical (majority).
        assertEquals(
            "B",
            AccountIdentityReconciliation.canonicalAccountId(
                listOf(
                    PersistedAccountSession("A", apple),
                    PersistedAccountSession("B", apple),
                    PersistedAccountSession("B", google),
                ),
            ),
        )
        // Tie: the lexicographically smaller account id wins.
        assertEquals(
            "A",
            AccountIdentityReconciliation.canonicalAccountId(
                listOf(PersistedAccountSession("B", apple), PersistedAccountSession("A", google)),
            ),
        )
    }

    @Test
    fun `legacy sessions are upgraded to the linking provider and folded into the canonical account`() {
        val sessions = listOf(
            PersistedAccountSession("A", provider = null, email = "legacy@example.com"),
            PersistedAccountSession("A", provider = google, email = "g@example.com"),
        )
        val connections = AccountIdentityReconciliation.connectionsFrom(sessions, linkingProvider = apple)
        assertEquals("A", connections.apple?.accountId)
        assertEquals("legacy@example.com", connections.apple?.email)
        assertEquals("A", connections.google?.accountId)
    }

    @Test
    fun `connections exclude a non-canonical account and upgrade the legacy canonical record`() {
        val sessions = listOf(
            PersistedAccountSession("A", provider = google),
            PersistedAccountSession("B", provider = apple),
            PersistedAccountSession("B", provider = null, email = "legacy@example.com"),
        )
        val connections = AccountIdentityReconciliation.connectionsFrom(sessions, linkingProvider = google)
        assertEquals(listOf("B"), connections.all.map { it.accountId }.distinct())
        assertEquals("B", connections.apple?.accountId)
        // The legacy (provider-less) record is upgraded to the linking provider, never fabricated.
        assertEquals("B", connections.google?.accountId)
        assertEquals("legacy@example.com", connections.google?.email)
    }
}
