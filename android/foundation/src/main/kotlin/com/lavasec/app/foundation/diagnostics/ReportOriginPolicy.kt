package com.lavasec.app.foundation.diagnostics

/** Whether the canonical fixed-origin fallback may be attempted for a completion. */
sealed interface ReportFallbackDecision {
    /** The fallback origin is the canonical production fallback and the failure is retryable. */
    data object Allowed : ReportFallbackDecision

    /** The fallback must not be attempted; [reason] is stable and inspectable. */
    data class Forbidden(val reason: ReportFallbackReason) : ReportFallbackDecision
}

/** Why the canonical fixed-origin fallback was forbidden. */
enum class ReportFallbackReason {
    /** HTTP 429 — both hosts share the per-IP limiter; failing over bypasses or obscures it. */
    RATE_LIMITED,

    /** A receipt already confirmed the report; there is nothing to fall back for. */
    RECEIPT_RECEIVED,

    /** The primary origin is not the canonical production origin (e.g. a test/staging override). */
    NOT_PRODUCTION_ORIGIN,

    /** No fallback origin is configured. */
    NO_FALLBACK_CONFIGURED,

    /** The configured fallback origin is not the single canonical workers.dev origin. */
    NOT_CANONICAL_FALLBACK,
}

/**
 * Canonical report origins and the fixed-origin fallback decision.
 *
 * The canonical submitter POSTs to the production origin and, only on an uncertain (retryable)
 * failure, fails over to the single hardcoded workers.dev origin. Two rules are explicit here:
 *
 * - **Production-only**: the fallback exists for the production origin and nowhere else, so a
 *   test/staging override never fails over onto production infrastructure.
 * - **429 terminates**: a rate-limited attempt never falls back. Both hosts share the same per-IP
 *   limiter, and failing over would either bypass the throttle if they ever diverge or replace the
 *   friendly rate-limit copy with the fallback's raw error.
 *
 * Ports the canonical endpoint list and the `BugReportRateLimitedError` handling in
 * `LavaSecApp/DiagnosticsController.swift`, and the Android `NativeApplicationServices` gating of
 * the fallback to `BuildConfig.BACKEND_URL == https://api.lavasecurity.app/`.
 */
object ReportOriginPolicy {
    /** Canonical production report origin. */
    const val PRODUCTION_ORIGIN = "https://api.lavasecurity.app"

    /** The one canonical fixed fallback origin (Cloudflare Workers). */
    const val FALLBACK_ORIGIN = "https://lavasec-api.lavasec.workers.dev"

    /** True when [origin] is the canonical production report origin (trailing slash ignored). */
    fun isCanonicalProductionOrigin(origin: String?): Boolean =
        normalize(origin) == PRODUCTION_ORIGIN

    /** True when [origin] is the single canonical fallback origin (trailing slash ignored). */
    fun isCanonicalFallbackOrigin(origin: String?): Boolean =
        normalize(origin) == FALLBACK_ORIGIN

    /**
     * Decides whether the configured fallback may be attempted after [completion]. A rate-limited
     * or already-receipted attempt is always forbidden; an uncertain attempt is allowed only when
     * the primary is the canonical production origin and the fallback is the canonical origin.
     */
    fun decideFallback(
        primaryOrigin: String?,
        configuredFallbackOrigin: String?,
        completion: ReportDeliveryCompletion,
    ): ReportFallbackDecision {
        when (completion) {
            is ReportDeliveryCompletion.Receipt ->
                return ReportFallbackDecision.Forbidden(ReportFallbackReason.RECEIPT_RECEIVED)

            ReportDeliveryCompletion.RateLimited ->
                return ReportFallbackDecision.Forbidden(ReportFallbackReason.RATE_LIMITED)

            is ReportDeliveryCompletion.Uncertain -> Unit
        }

        if (!isCanonicalProductionOrigin(primaryOrigin)) {
            return ReportFallbackDecision.Forbidden(ReportFallbackReason.NOT_PRODUCTION_ORIGIN)
        }
        if (configuredFallbackOrigin == null) {
            return ReportFallbackDecision.Forbidden(ReportFallbackReason.NO_FALLBACK_CONFIGURED)
        }
        if (!isCanonicalFallbackOrigin(configuredFallbackOrigin)) {
            return ReportFallbackDecision.Forbidden(ReportFallbackReason.NOT_CANONICAL_FALLBACK)
        }
        return ReportFallbackDecision.Allowed
    }

    private fun normalize(origin: String?): String? =
        origin?.trim()?.trimEnd('/')?.lowercase()
}
