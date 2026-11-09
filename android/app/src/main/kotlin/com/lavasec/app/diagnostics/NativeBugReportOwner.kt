package com.lavasec.app.diagnostics

import com.lavasec.app.backend.AndroidAppService
import com.lavasec.app.backend.AndroidServiceAction
import com.lavasec.app.backend.ServiceHttpFailure
import com.lavasec.app.foundation.diagnostics.ReportAttempt
import com.lavasec.app.foundation.diagnostics.ReportAttemptFailure
import com.lavasec.app.foundation.diagnostics.ReportDeliveryCompletion
import com.lavasec.app.foundation.diagnostics.ReportDeliveryState
import com.lavasec.app.foundation.diagnostics.ReportDeliveryStateMachine
import com.lavasec.app.foundation.diagnostics.ReportDeliveryTransition
import com.lavasec.app.foundation.diagnostics.ReportReviewRequest
import com.lavasec.app.foundation.feedback.FeedbackDraft
import com.lavasec.app.foundation.feedback.FeedbackTopic
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.json.JSONObject

/** Only typed, coarse facts can enter automatic diagnostics. No URLs, history, keys or account data. */
internal data class BugReportEnvironment(
    val version: String,
    val build: String,
    val androidVersion: String,
    val apiLevel: Int,
    val vpnStatus: String,
    val networkAvailable: Boolean?,
    val compiledRuleCount: Int?,
) {
    init {
        require(vpnStatus in setOf("off", "starting", "waitingForNetwork", "reconnecting", "connected", "paused", "stopping", "failed"))
        require(compiledRuleCount == null || compiledRuleCount >= 0)
    }
}

internal class BugReportReview internal constructor(
    val reportID: String,
    val preview: String,
    body: ByteArray,
) {
    private val body = body.copyOf()
    internal fun requestBytes(): ByteArray = body.copyOf()
    override fun toString() = "BugReportReview(redacted)"
}

internal enum class BugReportSendResult { SENT, FAILED, RATE_LIMITED, UNAVAILABLE }

/** Canonical report endpoint fallback, with a fresh Android single-use attestation for each attempt. */
internal class NativeBugReportService(private val endpoints: List<AndroidAppService>) {
    suspend fun submit(review: BugReportReview): BugReportSendResult {
        for (endpoint in endpoints) {
            try {
                val response = endpoint.post(AndroidServiceAction.SUBMIT_REPORT, review.requestBytes())
                val receipt = JSONObject(response.toString(Charsets.UTF_8)).opt("report_id")
                if (receipt is String && receipt.equals(review.reportID, ignoreCase = true)) return BugReportSendResult.SENT
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ServiceHttpFailure) {
                if (failure.status == 429) return BugReportSendResult.RATE_LIMITED
            } catch (_: Exception) {
                // Uncertain delivery keeps the same reviewed ID/body for manual retry.
            }
        }
        return if (endpoints.isEmpty()) BugReportSendResult.UNAVAILABLE else BugReportSendResult.FAILED
    }
}

/** Builds the reviewed capability (request bytes + preview) from the frozen identity and draft. */
internal fun interface NativeBugReportReviewFactory {
    fun create(
        reportId: String,
        draft: FeedbackDraft,
        includeDiagnostics: Boolean,
        environment: () -> BugReportEnvironment,
    ): BugReportReview
}

/**
 * Native review capability tied to one credential generation; never starts an automatic send.
 *
 * The retained-review delivery lifecycle (freeze -> explicit consent -> attempt -> matching
 * receipt, with idempotent unchanged retries, a fresh identity after a post-send edit, a terminal
 * 429, and retirement cancelling delivery) is delegated to the pure [ReportDeliveryStateMachine].
 * This owner supplies the two things the machine must not know about: the Android request body /
 * preview construction and the attested transport. The machine decides; the transport sends.
 *
 * [reviewFactory] is the seam used by pure-JVM tests to exercise the lifecycle without the
 * platform JSON body builder; production always uses the canonical native builder.
 */
internal class NativeBugReportOwner(
    private val capture: () -> BugReportEnvironment,
    private val send: suspend (BugReportReview) -> BugReportSendResult,
    scope: CoroutineScope,
    private val reviewFactory: NativeBugReportReviewFactory = NativeBugReportReviewFactory { reportId, draft, includeDiagnostics, environment ->
        // The environment provider must not run without opt-in, so it is passed lazily.
        buildNativeReview(reportId, draft, includeDiagnostics, environment)
    },
) : AutoCloseable {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = Any()
    private var closed = false
    private var delivery = ReportDeliveryState()
    /** The 1:1 app-side capability for the frozen [ReportDeliveryState.review]; null without one. */
    private var review: BugReportReview? = null
    private var environment: BugReportEnvironment? = null

    /**
     * Freezes (or reuses) the review capability for [draft]. An unchanged draft returns the SAME
     * [BugReportReview] object — the exact identity, body and preview the user reviewed — and a 429
     * or an uncertain completion retains it for a manual retry. A changed draft rebuilds the body;
     * the machine keeps the reviewed identity until a send has been attempted, then mints a new one.
     * Consent is never implied here.
     */
    fun prepare(draft: FeedbackDraft, includeDiagnostics: Boolean): BugReportReview? = synchronized(lock) {
        if (closed || delivery.isSending || !draft.isReadyForReview) return null
        val request = ReportReviewRequest(draft, includeDiagnostics)
        var rebuilt: BugReportReview? = null
        val next = try {
            ReportDeliveryStateMachine.prepare(delivery, request) { identity, reviewed ->
                reviewFactory.create(
                    reportId = identity.value,
                    draft = reviewed.draft,
                    includeDiagnostics = reviewed.includeDiagnostics,
                    environment = ::environmentSnapshot,
                ).also { rebuilt = it }.requestBytes()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (next.retired) {
            delivery = next
            review = null
            return null
        }
        if (next.review == null) return null
        // An unchanged request rebuilds nothing, so the previously minted capability is still the
        // one bound to the frozen review. A changed/rebuilt request carries the new capability.
        val capability = rebuilt ?: checkNotNull(review) {
            "an unchanged review must retain its prepared capability"
        }
        delivery = next
        review = capability
        capability
    }

    /** Explicit consent to deliver the currently frozen review; required before [submit]. */
    fun grantConsent(): Boolean = synchronized(lock) {
        if (closed) return false
        delivery = ReportDeliveryStateMachine.grantConsent(delivery)
        delivery.consented
    }

    /** Withdraws consent and cancels any in-flight attempt; the frozen review is retained. */
    fun revokeConsent(): Boolean = synchronized(lock) {
        if (closed) return false
        delivery = ReportDeliveryStateMachine.revokeConsent(delivery)
        !delivery.consented
    }

    fun discard() = synchronized(lock) {
        delivery = ReportDeliveryStateMachine.discard(delivery)
        review = null
        environment = null
    }

    /**
     * Sends [consentedReview] only when it is the exact frozen reviewed capability and explicit
     * consent is granted. The machine fences duplicate/late attempts; a 2xx receipt for the sent
     * identity is the only outcome that confirms delivery.
     */
    suspend fun submit(consentedReview: BugReportReview): BugReportSendResult {
        val attempt: ReportAttempt
        synchronized(lock) {
            if (closed || review !== consentedReview) return BugReportSendResult.UNAVAILABLE
            val frozen = delivery.review ?: return BugReportSendResult.UNAVAILABLE
            when (val transition = ReportDeliveryStateMachine.beginSend(delivery, frozen)) {
                is ReportDeliveryTransition.Refused -> return BugReportSendResult.UNAVAILABLE
                is ReportDeliveryTransition.Updated -> {
                    delivery = transition.state
                    attempt = checkNotNull(transition.state.activeAttempt)
                }
            }
        }
        val result = try {
            scope.async { send(consentedReview) }.await()
        } catch (cancelled: CancellationException) {
            complete(attempt, ReportDeliveryCompletion.Uncertain(ReportAttemptFailure.TRANSPORT))
            throw cancelled
        } catch (_: Exception) {
            BugReportSendResult.FAILED
        }
        complete(attempt, completionFor(result, consentedReview))
        return result
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            delivery = ReportDeliveryStateMachine.retire(delivery)
            review = null
            environment = null
        }
        scope.cancel()
    }

    private fun complete(attempt: ReportAttempt, completion: ReportDeliveryCompletion) {
        synchronized(lock) {
            delivery = ReportDeliveryStateMachine.complete(delivery, attempt, completion)
        }
    }

    private fun environmentSnapshot(): BugReportEnvironment =
        environment ?: capture().also { environment = it }

    private fun completionFor(
        result: BugReportSendResult,
        sent: BugReportReview,
    ): ReportDeliveryCompletion = when (result) {
        // The transport only reports SENT after the receipt echoed the sent report_id.
        BugReportSendResult.SENT -> ReportDeliveryCompletion.Receipt(sent.reportID)
        // 429 is terminal for this attempt; the fixed-origin fallback must not be attempted.
        BugReportSendResult.RATE_LIMITED -> ReportDeliveryCompletion.RateLimited
        BugReportSendResult.FAILED,
        BugReportSendResult.UNAVAILABLE,
        -> ReportDeliveryCompletion.Uncertain(ReportAttemptFailure.TRANSPORT)
    }
}

private fun buildNativeReview(
    reportId: String,
    draft: FeedbackDraft,
    includeDiagnostics: Boolean,
    environmentProvider: () -> BugReportEnvironment,
): BugReportReview {
    val description = buildList {
        add("Issue: ${checkNotNull(draft.topic).title}")
        draft.normalizedAffectedSite.takeIf { it.isNotEmpty() }?.let { add("Affected site/domain: $it") }
        add("Details: ${draft.normalizedDetails}")
    }.joinToString("\n")
    val body = JSONObject().put("report_id", reportId).put("include_recent_dns_events", false)
        .put("has_incident_summary", false).put("include_optional_diagnostics", includeDiagnostics)
        .put("kind", when (draft.topic) {
            FeedbackTopic.SUGGESTION -> "suggestion"
            FeedbackTopic.OTHER -> "other"
            else -> "bug"
        }).put("user_description", description)
    // Android routing requires the platform even when optional diagnostics are disabled.
    val device = JSONObject().put("platform", "android")
    body.put("device", device)
    val preview = StringBuilder(description)
    draft.normalizedContactEmail?.let {
        body.put("contact_email", it)
        preview.append("\n\nContact email: ").append(it)
    }
    if (includeDiagnostics) {
        val environment = environmentProvider()
        body.put("app", JSONObject().put("version", environment.version).put("build", environment.build))
        device.put("android_version", environment.androidVersion).put("android_api_level", environment.apiLevel)
        val vpn = JSONObject().put("status", environment.vpnStatus)
        environment.networkAvailable?.let { vpn.put("network_path_is_satisfied", it) }
        body.put("vpn", vpn)
        environment.compiledRuleCount?.let { body.put("filters", JSONObject().put("compiled_rule_count", it)) }
        preview.append("\n\nOptional diagnostics\nApp: ").append(environment.version)
            .append(" (").append(environment.build).append(")\nAndroid: ").append(environment.androidVersion)
            .append(" (API ").append(environment.apiLevel).append(")\nVPN: ").append(environment.vpnStatus)
        environment.networkAvailable?.let { preview.append("\nNetwork available: ").append(it) }
        environment.compiledRuleCount?.let { preview.append("\nCompiled filter rules: ").append(it) }
    }
    val bytes = body.toString().toByteArray(Charsets.UTF_8)
    if (bytes.size > AndroidServiceAction.SUBMIT_REPORT.maximumBodyBytes) throw IOException("Report exceeds limit")
    return BugReportReview(reportId, preview.toString(), bytes)
}
