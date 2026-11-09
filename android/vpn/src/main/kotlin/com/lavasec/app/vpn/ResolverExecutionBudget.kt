package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ResolverFailureReason
import kotlinx.coroutines.withTimeoutOrNull

/** One absolute query lifetime. Endpoints, bootstrap and retries receive the same tier deadline. */
internal object ResolverExecutionBudget {
    const val WHOLE_QUERY_NANOS = 12_000_000_000L
    const val TIER_NANOS = 1_800_000_000L
    fun whole(request: ResolverRequest, clock: MonotonicClock): ResolverRequest {
        val deadline = request.wholeQueryDeadlineNanos ?: after(clock.nowNanos(), WHOLE_QUERY_NANOS)
        return request.bounded(deadline, request.absoluteDeviceDeadlineNanos ?: deadline)
    }
    fun tier(request: ResolverRequest, clock: MonotonicClock, tier: Int = 1): ResolverRequest {
        val whole = request.wholeQueryDeadlineNanos ?: after(clock.nowNanos(), WHOLE_QUERY_NANOS)
        val deadline = request.tierDeadlines.computeIfAbsent(tier) { minOf(whole, after(clock.nowNanos(), TIER_NANOS)) }
        return request.bounded(whole, deadline)
    }
    suspend fun execute(request: ResolverRequest, clock: MonotonicClock, operation: suspend () -> ResolverResult): ResolverResult {
        val deadline = request.absoluteDeviceDeadlineNanos ?: return operation()
        val remaining = deadline - clock.nowNanos()
        if (remaining <= 0) return ResolverResult.Failure(ResolverFailureReason.TIMEOUT,
            terminal = request.wholeQueryDeadlineNanos?.let { clock.nowNanos() >= it } == true,
            local = true)
        val result = withTimeoutOrNull((remaining / 1_000_000L + if (remaining % 1_000_000L == 0L) 0 else 1).coerceAtLeast(1)) { operation() }
            ?: return ResolverResult.Failure(ResolverFailureReason.TIMEOUT,
                terminal = request.wholeQueryDeadlineNanos?.let { clock.nowNanos() >= it } == true)
        // A validated hidden answer may contain policy/negative evidence. It cannot authorize a
        // second resolver's view, even though it arrived too late for delivery to this client.
        if (result is ResolverResult.Answer && clock.nowNanos() >= deadline) {
            val meaning = DnsFallbackResponse.classify(request.query, result.dnsMessage).kind
            if (meaning in setOf(DnsFallbackResponse.Kind.MALFORMED, DnsFallbackResponse.Kind.MISMATCHED, DnsFallbackResponse.Kind.TRUNCATED))
                return ResolverResult.Failure(if (meaning == DnsFallbackResponse.Kind.MISMATCHED) ResolverFailureReason.MISMATCH_LIMIT
                    else ResolverFailureReason.INVALID_RESPONSE,
                    terminal = meaning == DnsFallbackResponse.Kind.MISMATCHED || request.wholeQueryDeadlineNanos?.let { clock.nowNanos() >= it } == true)
            return ResolverResult.Failure(ResolverFailureReason.TIMEOUT, terminal = true, peerAnswered = true)
        }
        return result
    }
    private fun after(now: Long, duration: Long) = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
}
