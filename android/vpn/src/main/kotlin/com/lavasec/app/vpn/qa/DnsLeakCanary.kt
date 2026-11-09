package com.lavasec.app.vpn.qa

import com.lavasec.app.foundation.chained.ChainedIPPrefix
import java.io.ByteArrayOutputStream

/** Deliberate QA positive control, separate from resolver queries and protection evidence. */
object DnsLeakCanary {
    const val SUFFIX = "leak-canary.lavasec.invalid"
    const val TRANSACTION_ID = 0x1EA4

    fun domain(nonce: String): String {
        require(nonce.length == 10 && nonce.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
        return "${nonce.lowercase()}.$SUFFIX"
    }

    fun query(nonce: String): ByteArray = ByteArrayOutputStream().apply {
        write(TRANSACTION_ID ushr 8); write(TRANSACTION_ID)
        write(byteArrayOf(1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        domain(nonce).split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            write(bytes.size); write(bytes)
        }
        write(byteArrayOf(0, 0, 1, 0, 1))
    }.toByteArray()

    /** Strict numeric syntax only, matching inet_pton; intentionally no resolver safety subset. */
    fun resolverBytes(value: String): ByteArray? {
        if (value.isEmpty() || value != value.trim() || '/' in value) return null
        return ChainedIPPrefix.parse(value)?.networkBytes
    }
}

data class DnsLeakCanaryArm(val nonce: String, val resolverIP: String) {
    init {
        DnsLeakCanary.domain(nonce)
        require(nonce == nonce.lowercase())
        require(DnsLeakCanary.resolverBytes(resolverIP) != null)
    }
}

/** Exact syscall fact. It is neither a DNS reply nor proof of an off-device capture. */
data class DnsLeakCanaryReceipt(
    val arm: DnsLeakCanaryArm,
    val serviceGeneration: Long,
    val chainedSessionGeneration: Long,
    val networkHandle: Long,
    val acceptedBytes: Int,
    val observedAtMillis: Long,
    val localAddress: String?,
    val localPort: Int?,
) {
    val destinationPort: Int get() = 53
    val outcome: String get() = "UDP_SEND_ACCEPTED"
    init {
        require(serviceGeneration > 0 && chainedSessionGeneration >= 0)
        require(acceptedBytes == DnsLeakCanary.query(arm.nonce).size)
        require(observedAtMillis >= 0)
        require((localAddress == null) == (localPort == null))
    }
}

data class DnsLeakCanarySnapshot(
    val arm: DnsLeakCanaryArm? = null,
    val lastReceipt: DnsLeakCanaryReceipt? = null,
    val available: Boolean = true,
)

sealed interface DnsLeakCanaryMutation {
    data class Applied(val snapshot: DnsLeakCanarySnapshot) : DnsLeakCanaryMutation
    data object InvalidAddress : DnsLeakCanaryMutation
    data object Stale : DnsLeakCanaryMutation
    data object Unavailable : DnsLeakCanaryMutation
}

sealed interface DnsLeakCanarySend {
    data object NoDatagramSent : DnsLeakCanarySend
    /** A nonzero short result or an unexpected post-syscall failure cannot safely be retried. */
    data object Indeterminate : DnsLeakCanarySend
    data class Accepted(val receipt: DnsLeakCanaryReceipt) : DnsLeakCanarySend
}

/** Default absent from Release/Direct Boot. Its lock linearizes Disarm with the final syscall. */
interface DnsLeakCanaryChannel {
    fun captureArm(): DnsLeakCanaryArm?
    fun sendIfCurrent(
        capturedArm: DnsLeakCanaryArm,
        isCurrent: () -> Boolean,
        send: () -> DnsLeakCanarySend,
    ): DnsLeakCanarySend
}

/** A prepared FD stays owned by the current native attempt until its final send/retirement. */
internal interface PreparedDnsLeakCanary : AutoCloseable {
    fun prepare(): Boolean
    fun send(chainedSessionGeneration: Long): DnsLeakCanarySend
}
