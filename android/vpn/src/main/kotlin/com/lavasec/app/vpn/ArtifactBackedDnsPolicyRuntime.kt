package com.lavasec.app.vpn

import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.platform.FilterRuntimeLeaseRead
import com.lavasec.app.platform.VerifiedFilterRuntimeLease
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException

internal class ProxyPolicyControl(
    val activeProtectionSessionId: Long,
    val pause: RuntimePauseDirective,
) {
    init {
        require(activeProtectionSessionId > 0L) {
            "active protection session id must be positive"
        }
    }
}

internal fun interface ProxyPolicyControlSource {
    fun current(): ProxyPolicyControl
}

/**
 * One mapped authoritative policy source paired unforgeably with its [DefaultDnsProxy].
 *
 * A successful exclusive claim transfers [lease] on every later outcome. A rejected duplicate
 * alias never acquires ownership and performs no cleanup.
 */
internal class ArtifactBackedDnsPolicyRuntime private constructor(
    private val source: ArtifactBackedProxyPolicySource,
    override val proxy: DnsProxy,
) : OwnedDnsPolicyRuntime {
    override val identity: ProxyPolicyIdentity = source.identity

    fun <T> withCurrent(block: (ProxyPolicySnapshot) -> T): ProxyPolicyRead<T> =
        source.withCurrent(block)

    override fun checkRetirementAllowed() {
        source.checkRetirementAllowed()
    }

    override fun beginRetirement() {
        source.beginRetirement()
    }

    override fun close() {
        source.close()
    }

    companion object {
        fun take(
            lease: VerifiedFilterRuntimeLease,
            controls: ProxyPolicyControlSource,
            eventIds: DecisionEventIdSource,
            wallClock: EpochMillisClock,
            monotonicClock: MonotonicClock,
            publicationSink: ProxyPublicationSink,
            unavailablePublicationFence: ProxyPublicationFence,
        ): ArtifactBackedDnsPolicyRuntime {
            lease.claimExclusiveRuntimeOwnership()
            val source = ArtifactBackedProxyPolicySource.take(lease, controls)
            return try {
                val proxy = DefaultDnsProxy(
                    policySource = source,
                    eventIds = eventIds,
                    wallClock = wallClock,
                    monotonicClock = monotonicClock,
                    publicationSink = publicationSink,
                    unavailablePublicationFence = unavailablePublicationFence,
                )
                ArtifactBackedDnsPolicyRuntime(source, proxy)
            } catch (failure: Throwable) {
                closeSuppressing(source, failure)
                throw failure
            }
        }
    }
}

/** Shared by DNS-only and chained mode; callers exclusively claim the lease before [take]. */
internal class ArtifactBackedProxyPolicySource private constructor(
    private val lease: VerifiedFilterRuntimeLease,
    private val controls: ProxyPolicyControlSource,
    val identity: ProxyPolicyIdentity,
) : ProxyPolicySource, AutoCloseable {
    private val lock = ReentrantLock()
    private val quiescent = lock.newCondition()
    private val closeCompleted = lock.newCondition()
    private val readDepth = ThreadLocal<Int>()
    private var enteredReads = 0
    private var retired = false
    private var physicalClosing = false
    private var closed = false
    private var closeOwnerThread: Thread? = null
    private var closeFailure: Throwable? = null

    override fun <T> withCurrent(
        block: (ProxyPolicySnapshot) -> T,
    ): ProxyPolicyRead<T> {
        val admitted = lock.withLock {
            if (retired || physicalClosing || closed) {
                false
            } else {
                enteredReads += 1
                true
            }
        }
        if (!admitted) return ProxyPolicyRead.Unavailable

        val priorDepth = readDepth.getOrZero()
        readDepth.set(priorDepth + 1)
        return try {
            val control = try {
                controls.current()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return ProxyPolicyRead.Unavailable
            }
            when (
                val read = lease.withDecisions { decisions ->
                    block(
                        ProxyPolicySnapshot(
                            identity = identity,
                            activeProtectionSessionId = control.activeProtectionSessionId,
                            pause = control.pause,
                            decisions = decisions,
                        ),
                    )
                }
            ) {
                is FilterRuntimeLeaseRead.Available ->
                    ProxyPolicyRead.Available(read.value)
                FilterRuntimeLeaseRead.Unavailable ->
                    ProxyPolicyRead.Unavailable
            }
        } finally {
            if (priorDepth == 0) {
                readDepth.remove()
            } else {
                readDepth.set(priorDepth)
            }
            lock.withLock {
                check(enteredReads > 0) { "policy read admission released more than once" }
                enteredReads -= 1
                if (enteredReads == 0) quiescent.signalAll()
            }
        }
    }

    fun checkRetirementAllowed() {
        check(readDepth.getOrZero() == 0) {
            "policy retirement cannot re-enter an admitted policy read"
        }
    }

    fun beginRetirement() {
        checkRetirementAllowed()
        lock.withLock {
            retired = true
        }
    }

    override fun close() {
        checkRetirementAllowed()

        var ownsClose = false
        var observedFailure: Throwable? = null
        lock.withLock {
            when {
                closed -> observedFailure = closeFailure
                physicalClosing -> {
                    check(closeOwnerThread !== Thread.currentThread()) {
                        "policy close cannot re-enter its physical drain"
                    }
                    while (!closed) closeCompleted.awaitUninterruptibly()
                    observedFailure = closeFailure
                }
                else -> {
                    retired = true
                    physicalClosing = true
                    closeOwnerThread = Thread.currentThread()
                    ownsClose = true
                }
            }
        }
        if (!ownsClose) {
            observedFailure?.let { throw it }
            return
        }

        var failure: Throwable? = null
        try {
            lock.withLock {
                while (enteredReads > 0) quiescent.awaitUninterruptibly()
            }
            try {
                lease.close()
            } catch (thrown: Throwable) {
                failure = thrown
            }
        } finally {
            lock.withLock {
                closeFailure = failure
                closed = true
                physicalClosing = false
                closeOwnerThread = null
                closeCompleted.signalAll()
            }
        }
        failure?.let { throw it }
    }

    private fun ThreadLocal<Int>.getOrZero(): Int = get() ?: 0

    companion object {
        fun take(
            lease: VerifiedFilterRuntimeLease,
            controls: ProxyPolicyControlSource,
        ): ArtifactBackedProxyPolicySource =
            try {
                val configuration = lease.configurationIdentity
                ArtifactBackedProxyPolicySource(
                    lease = lease,
                    controls = controls,
                    identity = ProxyPolicyIdentity(
                        configurationIdentity = configuration,
                        artifactDigest = lease.artifactDigest,
                        revision = configuration.generation,
                        sourceIncarnation = ProcessPolicySourceIncarnations.next(),
                    ),
                )
            } catch (failure: Throwable) {
                closeSuppressing(lease, failure)
                throw failure
            }
    }
}

private object ProcessPolicySourceIncarnations {
    private val next = AtomicLong(1L)

    fun next(): Long {
        while (true) {
            val candidate = next.get()
            check(candidate > 0L) { "policy source incarnation space is exhausted" }
            val successor = if (candidate == Long.MAX_VALUE) 0L else candidate + 1L
            if (next.compareAndSet(candidate, successor)) return candidate
        }
    }
}

private fun closeSuppressing(closeable: AutoCloseable, primary: Throwable) {
    try {
        closeable.close()
    } catch (closeFailure: Throwable) {
        if (primary !== closeFailure) primary.addSuppressed(closeFailure)
    }
}
