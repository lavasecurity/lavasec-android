package com.lavasec.app.foundation.feedback

import com.lavasec.app.foundation.diagnostics.OperationalAndroidPathState
import com.lavasec.app.foundation.diagnostics.OperationalAndroidTransport
import com.lavasec.app.foundation.diagnostics.OperationalServiceState
import com.lavasec.app.foundation.diagnostics.OperationalTunnelState
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthAccumulator
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale

data class FeedbackAppBuild(
    val version: String,
    val build: String,
)

class FeedbackSafeDiagnostics(
    schemaVersion: Int,
    serviceState: OperationalServiceState,
    tunnelState: OperationalTunnelState,
    androidTransport: OperationalAndroidTransport,
    androidPathState: OperationalAndroidPathState,
    isConnected: Boolean,
    networkAvailable: Boolean,
    consecutiveResolverFailures: Int,
    consecutiveResolverRejections: Int,
    recentResponseLatenciesMillis: List<Long>,
    activeFallback: ProtectionFallback,
    requiresReconnect: Boolean,
) {
    val schemaVersion = schemaVersion
    val serviceState = serviceState
    val tunnelState = tunnelState
    val androidTransport = androidTransport
    val androidPathState = androidPathState
    val isConnected = isConnected
    val networkAvailable = networkAvailable
    val consecutiveResolverFailures = consecutiveResolverFailures
    val consecutiveResolverRejections = consecutiveResolverRejections
    val recentResponseLatenciesMillis: List<Long> =
        Collections.unmodifiableList(ArrayList(recentResponseLatenciesMillis))
    val activeFallback = activeFallback
    val requiresReconnect = requiresReconnect

    init {
        require(schemaVersion == 1)
        require(consecutiveResolverFailures >= 0)
        require(consecutiveResolverRejections >= 0)
        require(
            recentResponseLatenciesMillis.size <=
                ProtectionHealthAccumulator.MAX_RECENT_RESPONSE_LATENCIES,
        )
        require(recentResponseLatenciesMillis.all { it >= 0L })
    }
}

sealed interface FeedbackReportRejection {
    data class InvalidDraft(
        val issues: List<FeedbackDraftIssue>,
    ) : FeedbackReportRejection

    data class TooLarge(
        val actualBytes: Int,
        val maxBytes: Int,
    ) : FeedbackReportRejection
}

sealed interface FeedbackReportComposeResult {
    class Composed internal constructor(
        bytes: ByteArray,
    ) : FeedbackReportComposeResult {
        private val bytes = bytes.copyOf()

        val utf8: ByteArray
            get() = bytes.copyOf()
    }

    data class Rejected(
        val reason: FeedbackReportRejection,
    ) : FeedbackReportComposeResult
}

object FeedbackReportComposer {
    const val MAX_UTF8_BYTES = 262_144

    fun compose(
        draft: FeedbackDraft,
        app: FeedbackAppBuild,
        diagnostics: FeedbackSafeDiagnostics?,
    ): FeedbackReportComposeResult {
        if (!draft.isReadyForReview) {
            return FeedbackReportComposeResult.Rejected(
                FeedbackReportRejection.InvalidDraft(draft.validationIssues),
            )
        }

        val text = buildString {
            appendLine("Lava Security Feedback")
            appendLine("Topic: ${requireNotNull(draft.topic).title}")
            if (draft.normalizedAffectedSite.isNotEmpty()) {
                appendLine("Affected site/domain: ${draft.normalizedAffectedSite}")
            }
            appendLine("Details:")
            appendLine(draft.normalizedDetails)
            draft.normalizedContactEmail?.let { email ->
                appendLine("Contact email: $email")
            }
            appendLine("App version: ${app.version}")
            appendLine("Build: ${app.build}")

            diagnostics?.let {
                appendLine()
                appendLine("Optional diagnostics")
                appendLine("Schema version: ${it.schemaVersion}")
                appendLine("Service state: ${it.serviceState.reportToken()}")
                appendLine("Tunnel state: ${it.tunnelState.reportToken()}")
                appendLine("Android transport: ${it.androidTransport.reportToken()}")
                appendLine("Android path: ${it.androidPathState.reportToken()}")
                appendLine("Connected: ${it.isConnected}")
                appendLine("Network available: ${it.networkAvailable}")
                appendLine(
                    "Consecutive resolver failures: ${it.consecutiveResolverFailures}",
                )
                appendLine(
                    "Consecutive resolver rejections: ${it.consecutiveResolverRejections}",
                )
                appendLine(
                    "Recent response latencies (ms): " +
                        it.recentResponseLatenciesMillis.joinToString(", "),
                )
                appendLine("Active fallback: ${it.activeFallback.reportToken()}")
                appendLine("Requires reconnect: ${it.requiresReconnect}")
            }
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_UTF8_BYTES) {
            return FeedbackReportComposeResult.Rejected(
                FeedbackReportRejection.TooLarge(
                    actualBytes = bytes.size,
                    maxBytes = MAX_UTF8_BYTES,
                ),
            )
        }
        return FeedbackReportComposeResult.Composed(bytes)
    }

    private fun Enum<*>.reportToken(): String = name.lowercase(Locale.ROOT)
}
