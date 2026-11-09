package com.lavasec.app.reactnative.components

import com.lavasec.app.foundation.flows.NativeFlowBuildConfiguration
import com.lavasec.app.foundation.flows.NativeFlowInventory
import com.lavasec.app.foundation.flows.NativeFlowUnavailableReason
import com.lavasec.app.foundation.flows.NativeFlowValidation

/**
 * Pure policy behind the single native flow host.
 *
 * One presentation may own the screen at a time; a launch is bound to the request, its route, the
 * credential graph and the account that initiated it. When the host resumes, a result whose graph
 * generation or account no longer matches is [LavaNativeFlowOutcome.Stale] and must never be
 * applied to the now-different owner. The public `native.flow` vocabulary and the internal
 * first-party presentations are enforced through the shared `NativeFlowInventory`, so an unknown
 * string or an internal-only route cannot construct a screen.
 */

/** Stable refusal/cancellation reasons a flow host returns to its caller. Wire-safe tokens. */
enum class LavaNativeFlowReason(val wireName: String) {
    BUSY("busy"),
    HOST_UNAVAILABLE("host-unavailable"),
    BACKGROUND("background"),
    INTERNAL_PRESENTATION("internal-presentation"),
    QA_FLOW_IN_RELEASE("qa-flow-in-release"),
    NOT_AVAILABLE_IN_BUILD("not-available-in-build"),
    UNKNOWN_FLOW("unknown-flow"),
    GRAPH_ROTATED("graph-rotated"),
    ACCOUNT_CHANGED("account-changed"),
    CANCELLED("cancelled"),
}

/**
 * One launch request. [requestId] is a monotonic identity so a late activity result can be matched
 * to the exact request; [flow] is the canonical `native.flow` name; [graphGeneration] and
 * [accountId] are captured at launch and re-checked before the result is delivered.
 */
data class LavaNativeFlowRequest(
    val requestId: Long,
    val flow: String,
    val graphGeneration: Long,
    val accountId: String?,
)

/** The typed outcome of a flow launch. */
sealed interface LavaNativeFlowOutcome {
    /** The flow completed and the result still belongs to the same graph/account. */
    data class Completed(val requestId: Long) : LavaNativeFlowOutcome

    /** The user dismissed the flow (back, outside tap, dirty-abort); nothing was applied. */
    data class Cancelled(val requestId: Long, val reason: LavaNativeFlowReason) : LavaNativeFlowOutcome

    /** The flow never launched. */
    data class Refused(val requestId: Long, val reason: LavaNativeFlowReason) : LavaNativeFlowOutcome

    /** The flow returned but the initiating graph/account is gone; the result is dropped. */
    data class Stale(val requestId: Long) : LavaNativeFlowOutcome
}

/** What a launcher reports back to the host: either the user finished/abandoned it, or it could not
 * be presented at all. */
sealed interface LavaNativeFlowLaunchResult {
    data class Launched(val completed: Boolean) : LavaNativeFlowLaunchResult

    data class Unavailable(val reason: LavaNativeFlowReason) : LavaNativeFlowLaunchResult
}

object LavaNativeFlowPolicy {
    /**
     * Whether [request] may be presented in [configuration]. `null` means launchable; otherwise the
     * typed refusal reason. Mirrors the canonical allowlist and its build gates.
     */
    fun refusal(
        request: LavaNativeFlowRequest,
        configuration: NativeFlowBuildConfiguration,
    ): LavaNativeFlowReason? = when (val validation = NativeFlowInventory.validate(request.flow, configuration)) {
        is NativeFlowValidation.Available -> null
        is NativeFlowValidation.Unavailable -> validation.reason.toFlowReason()
        NativeFlowValidation.Unknown -> LavaNativeFlowReason.UNKNOWN_FLOW
    }

    /** One active presentation: a new launch is refused while another owns the host. */
    fun admit(activeRequestId: Long?, requestId: Long): Boolean =
        activeRequestId == null || activeRequestId == requestId

    /**
     * Binds a launcher result to its request. A rotated credential graph or a different signed-in
     * account invalidates the result even when the user completed the flow.
     */
    fun bindResult(
        request: LavaNativeFlowRequest,
        result: LavaNativeFlowLaunchResult,
        currentGraphGeneration: Long?,
        currentAccountId: String?,
    ): LavaNativeFlowOutcome = when (result) {
        is LavaNativeFlowLaunchResult.Unavailable -> LavaNativeFlowOutcome.Refused(request.requestId, result.reason)
        is LavaNativeFlowLaunchResult.Launched -> when {
            currentGraphGeneration != request.graphGeneration ->
                LavaNativeFlowOutcome.Stale(request.requestId)
            currentAccountId != request.accountId ->
                LavaNativeFlowOutcome.Refused(request.requestId, LavaNativeFlowReason.ACCOUNT_CHANGED)
            result.completed -> LavaNativeFlowOutcome.Completed(request.requestId)
            else -> LavaNativeFlowOutcome.Cancelled(request.requestId, LavaNativeFlowReason.CANCELLED)
        }
    }

    private fun NativeFlowUnavailableReason.toFlowReason(): LavaNativeFlowReason = when (this) {
        NativeFlowUnavailableReason.INTERNAL_PRESENTATION -> LavaNativeFlowReason.INTERNAL_PRESENTATION
        NativeFlowUnavailableReason.QA_FLOW_IN_RELEASE -> LavaNativeFlowReason.QA_FLOW_IN_RELEASE
        NativeFlowUnavailableReason.NOT_AVAILABLE_IN_BUILD -> LavaNativeFlowReason.NOT_AVAILABLE_IN_BUILD
    }
}
