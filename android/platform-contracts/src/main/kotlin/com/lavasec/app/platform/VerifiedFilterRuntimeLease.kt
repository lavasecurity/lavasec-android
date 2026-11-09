package com.lavasec.app.platform

import com.lavasec.app.foundation.filter.ArtifactRuleCounts
import com.lavasec.app.foundation.filter.DomainFilterDecisionSource
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterArtifact
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.filter.FilterLibraryDraft
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Result of one synchronous decision read admitted by a mapped-filter lease. */
sealed interface FilterRuntimeLeaseRead<out T> {
    data class Available<T>(val value: T) : FilterRuntimeLeaseRead<T>

    data object Unavailable : FilterRuntimeLeaseRead<Nothing>
}

/**
 * Close-owned capability for one exact, still-open mapped filter artifact.
 *
 * The raw artifact and mapped decision source never escape this class. Reads are admitted only
 * through [withDecisions], and physical artifact close waits for every admitted callback.
 */
class VerifiedFilterRuntimeLease private constructor(
    /** Present only for credential-backed leases; Direct Boot must not reconstruct private rules. */
    val confirmedState: ConfirmedFilterState?,
    val configurationIdentity: ConfigurationIdentity,
    val artifactDigest: String,
    val ruleCounts: ArtifactRuleCounts,
    decisions: DomainFilterDecisionSource,
    closeArtifact: () -> Unit,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val quiescent = lock.newCondition()
    private val closeCompleted = lock.newCondition()
    private val decisionDepth = ThreadLocal<Int>()
    private var ownedDecisions: DomainFilterDecisionSource? = decisions
    private var ownedCloseArtifact: (() -> Unit)? = closeArtifact
    private var enteredDecisions = 0
    private var runtimeOwnershipClaimed = false
    private var closing = false
    private var closed = false
    private var closeOwnerThread: Thread? = null
    private var closeFailure: Throwable? = null

    internal val retainedRuntimeResourceCountForTesting: Int
        get() = lock.withLock {
            listOfNotNull(ownedDecisions, ownedCloseArtifact).size
        }

    /**
     * Permanently transfers this lease to one exclusive runtime owner.
     *
     * The claim and physical-close state share one lock. A failed duplicate claim never closes the
     * lease already owned by the winner.
     */
    @JvmSynthetic
    fun claimExclusiveRuntimeOwnership() {
        lock.withLock {
            check(!runtimeOwnershipClaimed) {
                "filter runtime lease ownership was already claimed"
            }
            check(!closing && !closed) {
                "closed filter runtime lease cannot transfer ownership"
            }
            runtimeOwnershipClaimed = true
        }
    }

    /**
     * Runs one synchronous callback while the exact mapped artifact remains open.
     *
     * Callers must return only detached values. The supplied decision source must not be retained
     * beyond [block].
     */
    fun <T> withDecisions(
        block: (DomainFilterDecisionSource) -> T,
    ): FilterRuntimeLeaseRead<T> {
        val admittedDecisions = lock.withLock {
            if (closing || closed) {
                null
            } else {
                enteredDecisions += 1
                checkNotNull(ownedDecisions) {
                    "open filter runtime lease must retain mapped decisions"
                }
            }
        }
        if (admittedDecisions == null) return FilterRuntimeLeaseRead.Unavailable

        val priorDepth = decisionDepth.getOrZero()
        decisionDepth.set(priorDepth + 1)
        val scopedDecisions = ScopedDecisionSource(admittedDecisions)
        return try {
            FilterRuntimeLeaseRead.Available(block(scopedDecisions))
        } finally {
            scopedDecisions.retire()
            if (priorDepth == 0) {
                decisionDepth.remove()
            } else {
                decisionDepth.set(priorDepth)
            }
            lock.withLock {
                check(enteredDecisions > 0) {
                    "filter runtime decision admission released more than once"
                }
                enteredDecisions -= 1
                if (enteredDecisions == 0) quiescent.signalAll()
            }
        }
    }

    override fun close() {
        check(decisionDepth.getOrZero() == 0) {
            "filter runtime lease close cannot re-enter an admitted decision callback"
        }

        var ownsClose = false
        var observedFailure: Throwable? = null
        lock.withLock {
            when {
                closed -> observedFailure = closeFailure
                closing -> {
                    check(closeOwnerThread !== Thread.currentThread()) {
                        "filter runtime lease close cannot re-enter its physical close"
                    }
                    while (!closed) closeCompleted.awaitUninterruptibly()
                    observedFailure = closeFailure
                }
                else -> {
                    closing = true
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
            val artifactCloser = lock.withLock {
                while (enteredDecisions > 0) quiescent.awaitUninterruptibly()
                checkNotNull(ownedCloseArtifact) {
                    "open filter runtime lease must retain its artifact closer"
                }
            }
            artifactCloser()
        } catch (thrown: Throwable) {
            failure = thrown
        } finally {
            lock.withLock {
                ownedDecisions = null
                ownedCloseArtifact = null
                closeFailure = failure
                closed = true
                closing = false
                closeOwnerThread = null
                closeCompleted.signalAll()
            }
        }
        failure?.let { throw it }
    }

    private fun ThreadLocal<Int>.getOrZero(): Int = get() ?: 0

    companion object {
        /**
         * Mints a lease only from an exact still-open artifact matching the confirmed witness.
         *
         * Success transfers [artifact] ownership to the returned lease. A null result leaves
         * ownership with the caller.
         */
        fun takeOpenedOrNull(
            confirmedState: ConfirmedFilterState,
            artifact: FilterArtifact,
        ): VerifiedFilterRuntimeLease? =
            takeOpenedOrNullForTesting(
                confirmedState = confirmedState,
                artifact = artifact,
                closeArtifact = artifact::close,
            )

        /**
         * Mints a lease from an authenticated Direct Boot snapshot and its exact opened artifact.
         *
         * The public snapshot intentionally carries no private manual-rule configuration. Success
         * transfers [artifact] ownership to the returned lease; null leaves it with the caller.
         */
        fun takeDirectBootOpenedOrNull(
            snapshot: DirectBootConfigurationSnapshot,
            artifact: FilterArtifact,
        ): VerifiedFilterRuntimeLease? {
            val reference = snapshot.artifact
            val identity = snapshot.state.configurationIdentity ?: return null
            val artifactIdentity = artifact.identity
            val sourceIds = artifactIdentity.selectedSources.map { it.id }
            if (
                !artifact.channelIsOpenForTesting ||
                artifact.sha256 != reference.sha256 ||
                artifactIdentity != reference.artifactIdentity ||
                artifactIdentity.configuration.generation != identity.generation ||
                artifactIdentity.configuration.fingerprint != identity.fingerprint ||
                sourceIds.size != sourceIds.toSet().size
            ) {
                return null
            }
            return VerifiedFilterRuntimeLease(
                confirmedState = null,
                configurationIdentity = identity,
                artifactDigest = reference.sha256,
                ruleCounts = artifactIdentity.ruleCounts,
                decisions = artifact.snapshot,
                closeArtifact = artifact::close,
            )
        }

        /**
         * Restores the private post-open witness and consumes [artifact] only on success.
         */
        fun takeOpenedOrNull(
            configuration: FilterLibraryDraft,
            configurationIdentity: ConfigurationIdentity,
            credentialUnlockRequired: Boolean,
            reference: FilterArtifactReference,
            artifact: FilterArtifact,
        ): VerifiedFilterRuntimeLease? {
            val verified = PersistedFilterArtifactRestorationVerifier.verifyOrNull(
                reference = reference,
                artifact = artifact,
            ) ?: return null
            val state = try {
                ConfirmedFilterState(
                    configuration = configuration,
                    configurationIdentity = configurationIdentity,
                    credentialUnlockRequired = credentialUnlockRequired,
                    verifiedArtifact = verified,
                )
            } catch (_: IllegalArgumentException) {
                return null
            }
            return takeOpenedOrNull(state, artifact)
        }

        internal fun takeOpenedOrNullForTesting(
            confirmedState: ConfirmedFilterState,
            artifact: FilterArtifact,
            decisions: DomainFilterDecisionSource = artifact.snapshot,
            closeArtifact: () -> Unit,
        ): VerifiedFilterRuntimeLease? {
            val witness = confirmedState.verifiedArtifact
            if (
                !artifact.channelIsOpenForTesting ||
                artifact.sha256 != witness.sha256 ||
                artifact.identity != witness.artifactIdentity
            ) {
                return null
            }

            val activeProfile = confirmedState.configuration.profile(
                confirmedState.configuration.activeProfileId,
            ) ?: return null
            val artifactSourceIds = artifact.identity.selectedSources.map { it.id }
            if (
                artifactSourceIds.size != artifactSourceIds.toSet().size ||
                activeProfile.enabledSourceIds != artifactSourceIds.toSet()
            ) {
                return null
            }

            return VerifiedFilterRuntimeLease(
                confirmedState = confirmedState,
                configurationIdentity = confirmedState.configurationIdentity,
                artifactDigest = witness.sha256,
                ruleCounts = witness.artifactIdentity.ruleCounts,
                decisions = decisions,
                closeArtifact = closeArtifact,
            )
        }
    }
}

private class ScopedDecisionSource(
    initialDelegate: DomainFilterDecisionSource,
) : DomainFilterDecisionSource {
    private val lock = ReentrantLock()
    private val quiescent = lock.newCondition()
    private var delegate: DomainFilterDecisionSource? = initialDelegate
    private var active = true
    private var enteredDecisions = 0

    override val blockRuleCount: Int = initialDelegate.blockRuleCount
    override val allowRuleCount: Int = initialDelegate.allowRuleCount
    override val guardrailRuleCount: Int = initialDelegate.guardrailRuleCount

    override fun decide(rawDomain: String): FilterDecision =
        decideWhileActive { it.decide(rawDomain) }

    override fun decideNormalized(normalizedDomain: String): FilterDecision =
        decideWhileActive { it.decideNormalized(normalizedDomain) }

    fun retire() {
        lock.withLock {
            active = false
            while (enteredDecisions > 0) quiescent.awaitUninterruptibly()
            delegate = null
        }
    }

    private fun decideWhileActive(
        decision: (DomainFilterDecisionSource) -> FilterDecision,
    ): FilterDecision {
        val admittedDelegate = lock.withLock {
            if (!active) {
                null
            } else {
                enteredDecisions += 1
                checkNotNull(delegate) {
                    "active scoped filter decisions must retain their mapped delegate"
                }
            }
        }
        if (admittedDelegate == null) return unavailable

        return try {
            decision(admittedDelegate)
        } finally {
            lock.withLock {
                check(enteredDecisions > 0) {
                    "scoped filter decision admission released more than once"
                }
                enteredDecisions -= 1
                if (enteredDecisions == 0) quiescent.signalAll()
            }
        }
    }

    companion object {
        private val unavailable = FilterDecision(
            FilterAction.BLOCK,
            FilterDecisionReason.PROTECTION_UNAVAILABLE,
        )
    }
}
