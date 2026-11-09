package com.lavasec.app.account

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class AccountAvailability { NOT_CONFIGURED, SIGNED_OUT, SIGNED_IN, STORAGE_UNAVAILABLE }

/** Public projection never carries provider tokens, refresh tokens or bearer credentials. */
internal data class AccountSnapshot(
    val generation: Long,
    val availability: AccountAvailability,
    val userId: String? = null,
    val email: String? = null,
    val providers: Set<String> = emptySet(),
)

/** Native-only credentials; deliberately not a data class and never part of an RN snapshot. */
internal class AccountSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtSeconds: Long,
    val userId: String,
    val email: String?,
    val providers: Set<String>,
) {
    override fun toString() = "AccountSession(redacted)"
}

internal interface AccountSessionStore {
    suspend fun load(): AccountSession?
    /** Atomic replacement; throw if durability cannot be established. */
    suspend fun save(session: AccountSession)
    suspend fun clear()
}

internal interface AccountAuthApi {
    suspend fun exchangeGoogleIdToken(idToken: String, rawNonce: String): AccountSession

    /** Native Apple ID-token exchange, retained for an explicitly configured native provider. */
    suspend fun exchangeAppleIdToken(idToken: String, rawNonce: String): AccountSession =
        throw AccountUnavailable()

    /** Browser Auth-code exchange; verifier is native-only and sent solely to the fixed Auth server. */
    suspend fun exchangeApplePkceCode(authCode: String, codeVerifier: CharArray): AccountSession =
        throw AccountUnavailable()

    suspend fun refresh(refreshToken: String): AccountSession
    suspend fun signOut(accessToken: String)
}

internal class AccountAuthRejected : Exception("Account authentication was rejected")
internal class AccountSessionChanged : Exception("Account session changed")
internal class AccountUnavailable : Exception("Account is unavailable")

/** A single authority for login, token rotation and account-generation fences. */
internal class AccountSessionOwner(
    private val api: AccountAuthApi?,
    private val store: AccountSessionStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    private val generation = AtomicLong(0)
    private val identity = Any()
    private val mutations = Mutex()
    private var session: AccountSession? = null // identity monitor
    private class PendingSignIn(
        val ticket: Long,
        val rawNonce: String,
        val authorizationState: String,
        val codeVerifier: CharArray,
        val expiresAt: Long,
    ) {
        fun destroy() = codeVerifier.fill('\u0000')
    }
    private var pendingSignIn: PendingSignIn? = null // identity monitor
    private var initialized = false // mutation mutex
    private var persistedGeneration = 0L // mutation mutex
    private val mutableState = MutableStateFlow(snapshot(null))
    val state: StateFlow<AccountSnapshot> = mutableState.asStateFlow()

    suspend fun initialize() = mutations.withLock {
        if (initialized) return@withLock
        initialized = true
        if (api == null || generation.get() != 0L) return@withLock
        try {
            val loaded = store.load()
            synchronized(identity) {
                if (generation.get() == 0L) {
                    session = loaded
                    mutableState.value = snapshot(loaded)
                }
            }
        } catch (cancelled: CancellationException) { initialized = false; throw cancelled
        } catch (_: Exception) {
            synchronized(identity) {
                if (generation.get() == 0L) mutableState.value = AccountSnapshot(0L, AccountAvailability.STORAGE_UNAVAILABLE)
            }
        }
    }

    /** Capture before native provider UI. A later sign-out or sign-in invalidates this result. */
    suspend fun beginSignIn(): Long {
        initialize()
        if (api == null) throw AccountUnavailable()
        return synchronized(identity) {
            val ticket = generation.incrementAndGet()
            pendingSignIn?.destroy()
            pendingSignIn = null
            val bytes = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            val nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            bytes.fill(0)
            val stateBytes = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
            val authorizationState = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
            stateBytes.fill(0)
            pendingSignIn = PendingSignIn(ticket, nonce, authorizationState, newCodeVerifier(), nowSeconds() + 600L)
            mutableState.value = AccountSnapshot(ticket, AccountAvailability.SIGNED_OUT)
            ticket
        }
    }

    suspend fun cancelSignIn(ticket: Long) = mutations.withLock {
        synchronized(identity) {
            requireCurrent(ticket)
            // Cancellation can arrive while a non-cancellable credential commit settles.
            // A completed login remains authoritative; do not mislabel it as cancelled.
            if (mutableState.value.availability == AccountAvailability.SIGNED_IN) return@synchronized
            generation.incrementAndGet()
            pendingSignIn?.destroy()
            pendingSignIn = null
            mutableState.value = snapshot(session)
        }
    }

    /** SHA-256 challenge sent to Google's native credential provider; raw nonce stays native-owned. */
    fun googleProviderNonce(ticket: Long): String = providerNonce(ticket)

    /** SHA-256 challenge sent to the Apple authorization provider; raw nonce stays native-owned. */
    fun appleProviderNonce(ticket: Long): String = providerNonce(ticket)

    /** RFC 7636 S256 challenge; only this non-secret digest is sent to the browser. */
    fun applePkceChallenge(ticket: Long): String = synchronized(identity) {
        val verifier = pendingSignIn(ticket).codeVerifier
        val bytes = ByteArray(verifier.size) { verifier[it].code.toByte() }
        val digest = try { java.security.MessageDigest.getInstance("SHA-256").digest(bytes) }
        finally { bytes.fill(0) }
        try { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest) }
        finally { digest.fill(0) }
    }

    /**
     * The CSRF/callback state bound to a pending admission. A browser callback must echo it to be
     * applied; a callback carrying another request's state (or none) is discarded by the caller.
     */
    fun providerAuthorizationState(ticket: Long): String = synchronized(identity) {
        pendingSignIn(ticket).authorizationState
    }

    private fun providerNonce(ticket: Long): String = synchronized(identity) {
        val pending = pendingSignIn(ticket)
        java.security.MessageDigest.getInstance("SHA-256").digest(pending.rawNonce.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun pendingSignIn(ticket: Long): PendingSignIn {
        requireCurrent(ticket)
        val pending = pendingSignIn?.takeIf { it.ticket == ticket } ?: throw AccountSessionChanged()
        if (pending.expiresAt <= nowSeconds()) {
            pending.destroy()
            pendingSignIn = null
            throw AccountSessionChanged()
        }
        return pending
    }

    suspend fun finishGoogleSignIn(ticket: Long, idToken: String) {
        val auth = api ?: throw AccountUnavailable()
        val pending = synchronized(identity) { pendingSignIn(ticket).also { pendingSignIn = null } }
        val accepted = try { auth.exchangeGoogleIdToken(idToken, pending.rawNonce) }
        finally { pending.destroy() }
        mutations.withLock { commitSession(ticket, accepted) }
    }

    /** Native Apple token exchange, mirroring [finishGoogleSignIn]. */
    suspend fun finishAppleSignIn(ticket: Long, idToken: String) {
        val auth = api ?: throw AccountUnavailable()
        val pending = synchronized(identity) { pendingSignIn(ticket).also { pendingSignIn = null } }
        val accepted = try { auth.exchangeAppleIdToken(idToken, pending.rawNonce) }
        finally { pending.destroy() }
        mutations.withLock { commitSession(ticket, accepted) }
    }

    /** One-use browser code admission over the SAME ticket, generation and durable session store. */
    suspend fun finishApplePkceSignIn(ticket: Long, authCode: String) {
        val auth = api ?: throw AccountUnavailable()
        val pending = synchronized(identity) { pendingSignIn(ticket).also { pendingSignIn = null } }
        val accepted = try { auth.exchangeApplePkceCode(authCode, pending.codeVerifier) }
        finally { pending.destroy() }
        // Supabase validates the code and its user. Retain linked providers, but never adopt a
        // non-Apple session as the outcome of this Apple authorization.
        if ("apple" !in accepted.providers) throw AccountAuthRejected()
        mutations.withLock { commitSession(ticket, accepted) }
    }

    /** Called under mutations; the durable commit and its ownership check are indivisible. */
    private suspend fun commitSession(expected: Long, accepted: AccountSession) {
        requireCurrent(expected)
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            store.save(accepted)
            persistedGeneration = expected
            val published = synchronized(identity) {
                if (generation.get() != expected) false else {
                    session = accepted
                    pendingSignIn?.destroy()
                    pendingSignIn = null
                    initialized = true
                    mutableState.value = snapshot(accepted)
                    true
                }
            }
            if (!published) { store.clear(); throw AccountSessionChanged() }
        }
    }

    /** Refreshes once under the mutation lock; a rotated refresh token is persisted before use. */
    suspend fun <T> withSession(expectedGeneration: Long? = null, operation: suspend (AccountSession) -> T): T {
        initialize()
        val expected = expectedGeneration ?: generation.get()
        requireCurrent(expected)
        val current = mutations.withLock {
            var active = synchronized(identity) {
                requireCurrent(expected)
                if (mutableState.value.availability != AccountAvailability.SIGNED_IN) throw AccountUnavailable()
                session ?: throw AccountUnavailable()
            }
            if (active.expiresAtSeconds <= nowSeconds() + REFRESH_MARGIN_SECONDS) {
                try {
                    val refreshed = (api ?: throw AccountUnavailable()).refresh(active.refreshToken)
                    requireCurrent(expected)
                    if (refreshed.userId != active.userId) throw AccountAuthRejected()
                    commitSession(expected, refreshed)
                    active = refreshed
                } catch (rejected: AccountAuthRejected) {
                    synchronized(identity) {
                        requireCurrent(expected)
                        generation.incrementAndGet()
                        session = null
                        mutableState.value = snapshot(null)
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { store.clear() }
                    throw rejected
                }
            }
            active
        }
        requireCurrent(expected)
        val result = operation(current)
        requireCurrent(expected)
        return result
    }

    /** Revokes local authority before waiting for refresh or a remote sign-out request. */
    suspend fun signOut(expectedGeneration: Long? = null, isCurrent: () -> Boolean = { true }) {
        val (ticket, cached) = synchronized(identity) {
            expectedGeneration?.let(::requireCurrent)
            // The caller's original host/authority must still own this exact revocation.
            // Once revoked, durable cleanup finishes even if the presentation disappears.
            if (!isCurrent()) throw AccountSessionChanged()
            val ticket = generation.incrementAndGet()
            val old = session
            session = null
            pendingSignIn?.destroy()
            pendingSignIn = null
            mutableState.value = AccountSnapshot(ticket, if (api == null) AccountAvailability.NOT_CONFIGURED else AccountAvailability.SIGNED_OUT)
            ticket to old
        }
        val previous = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { mutations.withLock {
            // A newer login may have committed before this coroutine reached the mutex.
            // Never delete its credentials or revoke its server session.
            if (persistedGeneration > ticket) return@withLock cached
            val old = cached ?: try { store.load() } catch (_: Exception) { null }
            try { store.clear() } catch (failure: Exception) {
                synchronized(identity) {
                    if (generation.get() == ticket) mutableState.value = AccountSnapshot(ticket, AccountAvailability.STORAGE_UNAVAILABLE)
                }
                throw failure
            }
            old
        } }
        previous?.let {
            try { api?.signOut(it.accessToken) } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* Local revocation is durable even while the server is offline. */ }
        }
    }

    fun requireCurrent(expected: Long) {
        if (generation.get() != expected) throw AccountSessionChanged()
    }

    private fun snapshot(value: AccountSession?): AccountSnapshot = AccountSnapshot(
        generation.get(),
        when { api == null -> AccountAvailability.NOT_CONFIGURED; value == null -> AccountAvailability.SIGNED_OUT; else -> AccountAvailability.SIGNED_IN },
        value?.userId, value?.email, value?.providers.orEmpty(),
    )

    private companion object {
        const val REFRESH_MARGIN_SECONDS = 60L

        fun newCodeVerifier(): CharArray {
            val random = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            val encoded = try { java.util.Base64.getUrlEncoder().withoutPadding().encode(random) }
            finally { random.fill(0) }
            return try { CharArray(encoded.size) { encoded[it].toInt().toChar() } }
            finally { encoded.fill(0) }
        }
    }
}
