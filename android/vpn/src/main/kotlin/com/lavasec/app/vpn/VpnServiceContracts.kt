package com.lavasec.app.vpn

import android.app.PendingIntent
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.foundation.resolver.ResolverConfiguration
import com.lavasec.app.platform.ConfigurationIdentity
import com.lavasec.app.platform.ProtectionState
import com.lavasec.app.platform.StartOrigin
import com.lavasec.app.platform.VerifiedFilterRuntimeLease
import com.lavasec.app.vpn.chained.ChainedVpnCredentials
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CredentialVpnAttachRequest(
    val serviceSessionGeneration: Long,
    val origin: StartOrigin,
) {
    init {
        require(serviceSessionGeneration > 0L)
    }
}

/** App-owned credential graph port. The VPN service discovers it but never closes the graph. */
interface CredentialVpnHostPort {
    val graphGeneration: Long

    fun openAttachment(request: CredentialVpnAttachRequest): CredentialVpnAttachment?
}

/** One exact service-to-credential-graph attachment. [detach] must be idempotent. */
interface CredentialVpnAttachment {
    val graphGeneration: Long

    /** Nonblocking admission. False means stale, locked, closed, or backpressured. */
    fun tryAcceptTelemetry(telemetry: VpnTelemetry): Boolean

    /** Serialized exact runtime lifecycle evidence; unlike packet telemetry this is not droppable. */
    fun acceptProtectionEvidence(evidence: VpnProtectionEvidence): Boolean = false

    fun detach()
}

/** Runtime-capable extension implemented only by the current unlocked credential graph. */
interface CredentialVpnRuntimeAttachment : CredentialVpnAttachment {
    val configurationIdentity: ConfigurationIdentity
    val resolverConfiguration: ResolverConfiguration
    val activeProtectionSessionId: Long
    val configureIntent: PendingIntent

    /** Opens a fresh exact authoritative lease for each engine installation. */
    fun openFilterRuntimeLease(): VerifiedFilterRuntimeLease

    /**
     * Opens the chained data-path credentials for this exact attachment, or `null` for the
     * unchanged DNS-only path.
     *
     * The credential graph has already evaluated the whole readiness gate and read the protected
     * key half, so a non-null value is the runtime's authority to construct the chained engine
     * for this session; `null` must leave the DNS-only lifecycle byte-for-byte untouched. The
     * default is `null` so adapters that predate chaining (the Direct Boot host) are unaffected.
     *
     * The caller owns the returned value and must [ChainedVpnCredentials.close] it when the
     * session retires. This is the only chained secret the runtime sees; it must never re-read
     * ambient storage itself.
     */
    fun openChainedCredentials(): ChainedVpnCredentials? = null

    /** Debug credential QA positive control only; Direct Boot and Release supply no channel. */
    val dnsLeakCanaryChannel: com.lavasec.app.vpn.qa.DnsLeakCanaryChannel? get() = null

    fun currentPause(): RuntimePauseDirective

    fun requestPause(duration: PauseDuration): PauseState?

    /** [expectedPause] fences automatic expiry against a newer user-authored pause. */
    fun requestResume(expectedPause: PauseState? = null): Boolean
}

class VpnTelemetry(
    val serviceSessionGeneration: Long,
    val engineGeneration: Long,
    val graphGeneration: Long,
    val activity: DnsDecisionEvent? = null,
    healthEvents: List<DnsHealthEvent> = emptyList(),
    val resolverQueryEvidence: ResolverQueryHealthEvidence? = null,
) {
    init {
        require(serviceSessionGeneration > 0L)
        require(engineGeneration > 0L)
        require(graphGeneration > 0L)
    }

    val healthEvents: List<DnsHealthEvent> =
        Collections.unmodifiableList(healthEvents.toList())
}

sealed interface VpnProtectionEvidence {
    val serviceSessionGeneration: Long
    val engineGeneration: Long
    val graphGeneration: Long
    val observedAtMillis: Long

    data class State(
        override val serviceSessionGeneration: Long,
        override val engineGeneration: Long,
        override val graphGeneration: Long,
        override val observedAtMillis: Long,
        val active: Boolean,
    ) : VpnProtectionEvidence {
        init {
            require(serviceSessionGeneration > 0L)
            require(engineGeneration > 0L)
            require(graphGeneration > 0L)
            require(observedAtMillis >= 0L)
        }
    }

    data class Heartbeat(
        override val serviceSessionGeneration: Long,
        override val engineGeneration: Long,
        override val graphGeneration: Long,
        override val observedAtMillis: Long,
    ) : VpnProtectionEvidence {
        init {
            require(serviceSessionGeneration > 0L)
            require(engineGeneration > 0L)
            require(graphGeneration > 0L)
            require(observedAtMillis >= 0L)
        }
    }
}

class CredentialVpnHostRegistry {
    private val lock = ReentrantLock()
    private var current: RegisteredCredentialHost? = null
    private val registrations = AtomicLong()

    fun attach(host: CredentialVpnHostPort): AutoCloseable {
        require(host.graphGeneration > 0L)
        val registered = lock.withLock {
            val registration = registrations.incrementAndGet()
            check(registration > 0L) { "credential host registration space is exhausted" }
            RegisteredCredentialHost(registration, host.graphGeneration, host).also {
                current = it
            }
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                lock.withLock {
                    if (current === registered) current = null
                }
            }
        }
    }

    internal fun currentForRuntime(): CredentialHostSnapshot? = lock.withLock {
        current?.let { registered ->
            CredentialHostSnapshot(
                graphGeneration = registered.graphGeneration,
                host = registered.host,
            )
        }
    }

    private class RegisteredCredentialHost(
        val registration: Long,
        val graphGeneration: Long,
        val host: CredentialVpnHostPort,
    )
}

internal data class CredentialHostSnapshot(
    val graphGeneration: Long,
    val host: CredentialVpnHostPort,
)

internal class ExactCredentialRuntimeAttachment(
    private val expectedServiceSessionGeneration: Long,
    expectedEngineGeneration: Long,
    host: CredentialVpnHostPort,
    private val attachment: CredentialVpnAttachment,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private var open = true
    private var currentEngineGeneration = expectedEngineGeneration

    init {
        require(expectedServiceSessionGeneration > 0L)
        require(expectedEngineGeneration > 0L)
        require(host.graphGeneration == attachment.graphGeneration) {
            "credential attachment generation does not match its host"
        }
    }

    fun tryAccept(telemetry: VpnTelemetry): Boolean {
        if (
            telemetry.serviceSessionGeneration != expectedServiceSessionGeneration ||
            telemetry.graphGeneration != attachment.graphGeneration
        ) {
            return false
        }
        if (!lock.tryLock()) return false
        return try {
            open &&
                telemetry.engineGeneration == currentEngineGeneration &&
                runCatching { attachment.tryAcceptTelemetry(telemetry) }.getOrDefault(false)
        } finally {
            lock.unlock()
        }
    }

    fun acceptProtectionEvidence(evidence: VpnProtectionEvidence): Boolean {
        if (
            evidence.serviceSessionGeneration != expectedServiceSessionGeneration ||
            evidence.graphGeneration != attachment.graphGeneration ||
            evidence.observedAtMillis < 0L
        ) {
            return false
        }
        return lock.withLock {
            open &&
                evidence.engineGeneration == currentEngineGeneration &&
                runCatching { attachment.acceptProtectionEvidence(evidence) }.getOrDefault(false)
        }
    }

    fun installEngine(engineGeneration: Long) = lock.withLock {
        check(open)
        require(engineGeneration > currentEngineGeneration)
        currentEngineGeneration = engineGeneration
    }

    override fun close() {
        val detach = lock.withLock {
            if (!open) false else {
                open = false
                true
            }
        }
        if (detach) attachment.detach()
    }
}

internal data class VpnServiceOwner(
    val sessionGeneration: Long,
)

internal class ExactVpnServiceState {
    private val lock = ReentrantLock()
    private val generations = AtomicLong()
    private val mutableState = MutableStateFlow<ProtectionState>(ProtectionState.Off)
    private var currentOwner: VpnServiceOwner? = null

    val state: StateFlow<ProtectionState> = mutableState.asStateFlow()

    /** The installed session generation, or `null` when no runtime is installed. */
    fun currentGenerationOrNull(): Long? = lock.withLock { currentOwner?.sessionGeneration }

    fun install(origin: StartOrigin): VpnServiceOwner = lock.withLock {
        val generation = generations.incrementAndGet()
        check(generation > 0L) { "VPN service session generation space is exhausted" }
        VpnServiceOwner(generation).also { owner ->
            currentOwner = owner
            mutableState.value = ProtectionState.Starting(origin, generation)
        }
    }

    fun publish(owner: VpnServiceOwner, state: ProtectionState): Boolean = lock.withLock {
        if (currentOwner != owner) return@withLock false
        mutableState.value = state
        true
    }

    fun retire(owner: VpnServiceOwner, terminal: ProtectionState): Boolean = lock.withLock {
        if (currentOwner != owner) return@withLock false
        currentOwner = null
        mutableState.value = terminal
        true
    }
}
