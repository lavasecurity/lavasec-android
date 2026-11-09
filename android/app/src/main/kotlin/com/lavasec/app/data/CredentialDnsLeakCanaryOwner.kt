package com.lavasec.app.data

import android.content.Context
import android.content.SharedPreferences
import com.lavasec.app.BuildConfig
import com.lavasec.app.vpn.qa.DnsLeakCanary
import com.lavasec.app.vpn.qa.DnsLeakCanaryArm
import com.lavasec.app.vpn.qa.DnsLeakCanaryChannel
import com.lavasec.app.vpn.qa.DnsLeakCanaryMutation
import com.lavasec.app.vpn.qa.DnsLeakCanarySend
import com.lavasec.app.vpn.qa.DnsLeakCanarySnapshot
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process-owned credential QA arm. Never mirrored, backed up, or counted as DNS activity. */
class CredentialDnsLeakCanaryOwner internal constructor(
    private val preferences: SharedPreferences,
    private val enabled: Boolean,
    private val nonce: () -> String = { UUID.randomUUID().toString().replace("-", "").take(10) },
) : DnsLeakCanaryChannel {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        BuildConfig.DEBUG && !context.isDeviceProtectedStorage,
    )

    private val lock = ReentrantLock()
    private var healthy = enabled
    private val state = MutableStateFlow(readSnapshot())
    val snapshot: StateFlow<DnsLeakCanarySnapshot> = state.asStateFlow()

    fun arm(resolverIP: String, isCurrent: () -> Boolean): DnsLeakCanaryMutation = lock.withLock {
        if (!enabled) return DnsLeakCanaryMutation.Unavailable
        val trimmed = resolverIP.trim()
        if (DnsLeakCanary.resolverBytes(trimmed) == null) return DnsLeakCanaryMutation.InvalidAddress
        if (!isCurrent()) return DnsLeakCanaryMutation.Stale
        val arm = DnsLeakCanaryArm(nonce(), trimmed)
        val committed = runCatching {
            preferences.edit().putString(KEY_NONCE, arm.nonce).putString(KEY_RESOLVER, arm.resolverIP).commit()
        }.getOrDefault(false)
        // SharedPreferences can update its memory map even when commit fails. Refuse all sends
        // until a later successful explicit mutation; never adopt that unconfirmed arm.
        healthy = committed
        state.value = if (committed) DnsLeakCanarySnapshot(arm) else DnsLeakCanarySnapshot(available = false)
        if (committed) DnsLeakCanaryMutation.Applied(state.value) else DnsLeakCanaryMutation.Unavailable
    }

    fun disarm(isCurrent: () -> Boolean): DnsLeakCanaryMutation = lock.withLock {
        if (!enabled) return DnsLeakCanaryMutation.Unavailable
        if (!isCurrent()) return DnsLeakCanaryMutation.Stale
        val committed = runCatching { preferences.edit().remove(KEY_NONCE).remove(KEY_RESOLVER).commit() }
            .getOrDefault(false)
        healthy = committed
        state.value = DnsLeakCanarySnapshot(available = committed)
        if (committed) DnsLeakCanaryMutation.Applied(state.value) else DnsLeakCanaryMutation.Unavailable
    }

    override fun captureArm(): DnsLeakCanaryArm? = lock.withLock {
        if (!healthy) null else readArm()
    }

    override fun sendIfCurrent(capturedArm: DnsLeakCanaryArm, isCurrent: () -> Boolean,
        send: () -> DnsLeakCanarySend): DnsLeakCanarySend = lock.withLock {
        if (!healthy || !isCurrent() || readArm() != capturedArm) return DnsLeakCanarySend.NoDatagramSent
        // Only this nonblocking syscall closure runs under the arm lock, not socket preparation.
        // A winning Disarm or re-Arm makes this send impossible; a winning send completes first.
        val result = runCatching(send).getOrDefault(DnsLeakCanarySend.Indeterminate)
        if (result is DnsLeakCanarySend.Accepted && result.receipt.arm == capturedArm) {
            state.value = DnsLeakCanarySnapshot(capturedArm, result.receipt)
        }
        result
    }

    private fun readSnapshot(): DnsLeakCanarySnapshot =
        if (!enabled) DnsLeakCanarySnapshot(available = false) else DnsLeakCanarySnapshot(readArm())

    private fun readArm(): DnsLeakCanaryArm? = runCatching {
        val nonce = preferences.getString(KEY_NONCE, null) ?: return@runCatching null
        val address = preferences.getString(KEY_RESOLVER, null) ?: return@runCatching null
        DnsLeakCanaryArm(nonce, address)
    }.getOrNull()

    companion object {
        private const val PREFERENCES_NAME = "lavasec_qa_dns_leak_canary"
        internal const val KEY_NONCE = "armed_nonce"
        internal const val KEY_RESOLVER = "resolver_ip"
    }
}
