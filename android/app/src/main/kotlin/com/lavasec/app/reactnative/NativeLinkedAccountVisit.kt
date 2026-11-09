package com.lavasec.app.reactnative

import com.lavasec.app.account.AccountAvailability
import com.lavasec.app.account.AccountSessionChanged
import com.lavasec.app.account.AccountSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Native account labels never become an AppSnapshot or command result. */
internal data class NativeLinkedAccountConnection(val provider: String, val email: String?)
internal enum class NativeLinkedAccountAction { SIGN_OUT, DELETE }
internal enum class NativeLinkedAccountOutcome { COMPLETED, CANCELLED, STALE, REFUSED, FAILED, BUSY }

/** One original host/account visit; even an equal-looking successor cannot adopt its actions. */
internal class NativeLinkedAccountVisit(
    private val host: NativeVisitHost,
    private val generation: Long,
    private val backend: Any,
    account: AccountSnapshot,
    private val authorityRevision: Long,
    private val currentHost: () -> NativeVisitHost?,
    private val currentGeneration: () -> Long?,
    private val currentBackend: () -> Any?,
    private val currentAccount: () -> AccountSnapshot?,
    private val currentAuthorityRevision: () -> Long?,
    private val canRead: () -> Boolean,
    private val canMutate: () -> Boolean,
    private val authorize: suspend () -> Boolean,
    private val signOut: suspend (accountGeneration: Long, isCurrent: () -> Boolean) -> Unit,
    private val deleteAccount: suspend (accountGeneration: Long, isCurrent: () -> Boolean) -> Unit,
) {
    // Copy the provider set: a native dialog must not borrow a mutable API collection.
    private val account = account.copy(providers = account.providers.toSet())
    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val publishing = AtomicBoolean(false)

    fun connections(): List<NativeLinkedAccountConnection>? = if (readable())
        account.providers.sorted().map { NativeLinkedAccountConnection(it, account.email) } else null

    fun readable(): Boolean = currentOrigin() && canRead()
    fun cancel() { closed.set(true) }

    /** Conceal the original labels while its own acknowledged revocation completes. */
    fun completingRevocation(): Boolean = publishing.get() && currentLifetime() && expectedRevocation(currentAccount())

    suspend fun perform(action: NativeLinkedAccountAction, confirmed: Boolean = false): NativeLinkedAccountOutcome {
        if (!currentOrigin() || !canRead()) return NativeLinkedAccountOutcome.STALE
        if (action == NativeLinkedAccountAction.DELETE && !confirmed) return NativeLinkedAccountOutcome.REFUSED
        if (!busy.compareAndSet(false, true)) return NativeLinkedAccountOutcome.BUSY
        try {
            if (!authorize()) return if (currentOrigin()) NativeLinkedAccountOutcome.CANCELLED else NativeLinkedAccountOutcome.STALE
            if (!readable() || !canMutate()) return NativeLinkedAccountOutcome.STALE
            // This exact predicate reaches the existing native authority's write edge after
            // every suspension. It is never refreshed to a new host, account or grant epoch.
            val admitted = { readable() && canMutate() }
            publishing.set(true)
            when (action) {
                NativeLinkedAccountAction.SIGN_OUT -> signOut(account.generation, admitted)
                NativeLinkedAccountAction.DELETE -> deleteAccount(account.generation, admitted)
            }
            val after = currentAccount()
            // Successful local revocation changes this account generation. It must be the
            // expected signed-out successor, never another user's newer sign-in.
            val accepted = currentLifetime() && canRead() && expectedRevocation(after)
            return if (accepted) NativeLinkedAccountOutcome.COMPLETED else NativeLinkedAccountOutcome.STALE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AccountSessionChanged) {
            return NativeLinkedAccountOutcome.STALE
        } catch (_: Exception) {
            return if (currentOrigin()) NativeLinkedAccountOutcome.FAILED else NativeLinkedAccountOutcome.STALE
        } finally { publishing.set(false); busy.set(false) }
    }

    private fun expectedRevocation(after: AccountSnapshot?): Boolean = after != null &&
        after.generation == account.generation + 1 && after.availability == AccountAvailability.SIGNED_OUT && after.userId == null

    private fun currentOrigin(): Boolean = currentLifetime() && account.availability == AccountAvailability.SIGNED_IN &&
        account.userId != null && currentAccount() == account

    private fun currentLifetime(): Boolean = !closed.get() && host.matches(currentHost()) &&
        currentGeneration() == generation && currentBackend() === backend &&
        currentAuthorityRevision() == authorityRevision
}
