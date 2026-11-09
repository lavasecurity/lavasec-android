package com.lavasec.app.foundation.pause

import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import kotlin.ConsistentCopyVisibility

enum class PauseDuration(val minutes: Int) {
    FIVE_MINUTES(5),
    TEN_MINUTES(10),
    FIFTEEN_MINUTES(15),
    ;

    val durationMillis: Long get() = minutes * 60_000L

    companion object {
        /** Parses the complete, closed wire vocabulary; arbitrary minute values fail closed. */
        fun fromMinutes(minutes: Int): PauseDuration? = entries.firstOrNull { it.minutes == minutes }
    }
}

/** A pause is valid only for the exact logical protection session that created it. */
data class PauseState(
    val deadlineMillis: Long,
    val protectionSessionId: Long,
) {
    init {
        require(protectionSessionId > 0L)
    }
}

/** Exact synchronous pause input consumed by the DNS packet path. */
sealed interface RuntimePauseDirective {
    data object Filtering : RuntimePauseDirective

    data class Paused(val state: PauseState) : RuntimePauseDirective

    fun isActive(nowMillis: Long, activeProtectionSessionId: Long): Boolean = when (this) {
        Filtering -> false
        is Paused -> PausePolicy.restore(
            persistedDeadlineMillis = state.deadlineMillis,
            persistedSessionId = state.protectionSessionId,
            activeSessionId = activeProtectionSessionId,
            nowMillis = nowMillis,
        ) != null
    }
}

@ConsistentCopyVisibility
data class PausedQueryDecision internal constructor(
    val decision: FilterDecision,
    val wouldBeDecision: FilterDecision,
    val maximumResponseTtlSeconds: Long?,
) {
    init {
        // Mirror iOS `DNSQueryDispatcher.decideForwardedResponse`: only a would-be block is
        // rewritten to PAUSED_ALLOW (and capped); an ordinary paused pass keeps its real filter
        // reason (DEFAULT_ALLOW/LOCAL_ALLOWLIST) and the upstream TTL, so Top Domains and Domain
        // History still see it as a genuine pass.
        if (wouldBeDecision.action == FilterAction.BLOCK) {
            require(decision == pausedAllow) {
                "a pause-overridden block must report paused allow"
            }
            require(maximumResponseTtlSeconds == WOULD_BE_BLOCK_TTL_SECONDS) {
                "a pause-overridden block must cap the response TTL"
            }
        } else {
            require(decision == wouldBeDecision) {
                "an ordinary paused pass must retain its filter decision"
            }
            require(maximumResponseTtlSeconds == null) {
                "an ordinary paused pass has no pause TTL cap"
            }
        }
    }

    fun effectiveResponseTtlSeconds(upstreamTtlSeconds: Long): Long {
        require(upstreamTtlSeconds >= 0) { "upstream TTL must be non-negative" }
        return maximumResponseTtlSeconds?.let { minOf(upstreamTtlSeconds, it) } ?: upstreamTtlSeconds
    }

    companion object {
        private const val WOULD_BE_BLOCK_TTL_SECONDS = 1L
        private val pausedAllow = FilterDecision(FilterAction.ALLOW, FilterDecisionReason.PAUSED_ALLOW)

        internal fun from(wouldBeDecision: FilterDecision): PausedQueryDecision {
            val pauseOverridesBlock = wouldBeDecision.action == FilterAction.BLOCK
            return PausedQueryDecision(
                decision = if (pauseOverridesBlock) pausedAllow else wouldBeDecision,
                wouldBeDecision = wouldBeDecision,
                maximumResponseTtlSeconds = if (pauseOverridesBlock) {
                    WOULD_BE_BLOCK_TTL_SECONDS
                } else {
                    null
                },
            )
        }
    }
}

object PausePolicy {
    private const val PAUSE_SANITY_CAP_SLACK_MILLIS = 2L * 60_000L
    private val maximumRestorablePauseMillis =
        PauseDuration.entries.maxOf(PauseDuration::durationMillis) + PAUSE_SANITY_CAP_SLACK_MILLIS

    fun start(
        nowMillis: Long,
        duration: PauseDuration,
        protectionSessionId: Long,
    ): PauseState = PauseState(
        deadlineMillis = Math.addExact(nowMillis, duration.durationMillis),
        protectionSessionId = protectionSessionId,
    )

    fun restore(
        persistedDeadlineMillis: Long?,
        persistedSessionId: Long?,
        activeSessionId: Long,
        nowMillis: Long,
    ): PauseState? {
        val deadlineMillis = persistedDeadlineMillis ?: return null
        if (activeSessionId <= 0L || persistedSessionId != activeSessionId) return null
        if (deadlineMillis <= nowMillis) return null

        // Match iOS ProtectionPauseStore: a corrupt days-ahead deadline or backward wall-clock
        // step must fail closed instead of silently disabling filtering indefinitely. The slack
        // preserves a maximum-duration pause that was written shortly before this read.
        val maximumSaneDeadline = try {
            Math.addExact(nowMillis, maximumRestorablePauseMillis)
        } catch (_: ArithmeticException) {
            return null
        }
        if (deadlineMillis > maximumSaneDeadline) return null
        return PauseState(deadlineMillis, activeSessionId)
    }

    fun resumeNow(): PauseState? = null

    fun queryDecision(
        pauseState: PauseState?,
        nowMillis: Long,
        activeProtectionSessionId: Long,
        wouldBeDecision: FilterDecision,
    ): PausedQueryDecision? {
        val state = pauseState ?: return null
        restore(
            persistedDeadlineMillis = state.deadlineMillis,
            persistedSessionId = state.protectionSessionId,
            activeSessionId = activeProtectionSessionId,
            nowMillis = nowMillis,
        ) ?: return null
        return PausedQueryDecision.from(wouldBeDecision)
    }
}
