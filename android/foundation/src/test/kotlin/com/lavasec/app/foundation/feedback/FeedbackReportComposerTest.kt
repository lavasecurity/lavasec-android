package com.lavasec.app.foundation.feedback

import com.lavasec.app.foundation.diagnostics.OperationalAndroidPathState
import com.lavasec.app.foundation.diagnostics.OperationalAndroidTransport
import com.lavasec.app.foundation.diagnostics.OperationalServiceState
import com.lavasec.app.foundation.diagnostics.OperationalTunnelState
import com.lavasec.app.foundation.health.ProtectionFallback
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReportComposerTest {
    @Test
    fun `diagnostics off emits only normalized reviewed fields and exact build identity`() {
        val draft = FeedbackDraft(
            topic = FeedbackTopic.WEBSITE_ACCESS,
            affectedSite = " example.com\r\n",
            details = "Could not open it.\rPlease investigate. 🔒",
            contactEmail = " person@example.com ",
        )

        val first = FeedbackReportComposer.compose(
            draft = draft,
            app = FeedbackAppBuild(version = "0.2.0", build = "42"),
            diagnostics = null,
        ) as FeedbackReportComposeResult.Composed
        val second = FeedbackReportComposer.compose(
            draft = draft,
            app = FeedbackAppBuild(version = "0.2.0", build = "42"),
            diagnostics = null,
        ) as FeedbackReportComposeResult.Composed

        val expected = """
            Lava Security Feedback
            Topic: I can't visit a website
            Affected site/domain: example.com
            Details:
            Could not open it.
            Please investigate. 🔒
            Contact email: person@example.com
            App version: 0.2.0
            Build: 42
        """.trimIndent() + "\n"
        assertEquals(expected, first.utf8.toString(StandardCharsets.UTF_8))
        assertArrayEquals(first.utf8, second.utf8)
        assertFalse(expected.contains("Optional diagnostics"))
    }

    @Test
    fun `explicit safe diagnostics render in one deterministic closed order`() {
        val result = FeedbackReportComposer.compose(
            draft = FeedbackDraft(
                topic = FeedbackTopic.VPN_OR_FILTER_ISSUE,
                details = "Protection stayed disconnected.",
            ),
            app = FeedbackAppBuild(version = "0.2.0", build = "43"),
            diagnostics = safeDiagnostics(),
        ) as FeedbackReportComposeResult.Composed

        val expected = """
            Lava Security Feedback
            Topic: VPN or filter doesn't work
            Details:
            Protection stayed disconnected.
            App version: 0.2.0
            Build: 43

            Optional diagnostics
            Schema version: 1
            Service state: running
            Tunnel state: connected
            Android transport: wifi
            Android path: validated
            Connected: true
            Network available: true
            Consecutive resolver failures: 2
            Consecutive resolver rejections: 1
            Recent response latencies (ms): 12, 34
            Active fallback: encrypted_dns
            Requires reconnect: false
        """.trimIndent() + "\n"
        assertEquals(expected, result.utf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `safe diagnostics DTO cannot carry traffic identity or raw errors`() {
        val instanceFields = FeedbackSafeDiagnostics::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapTo(linkedSetOf()) { it.name }

        assertEquals(
            linkedSetOf(
                "schemaVersion",
                "serviceState",
                "tunnelState",
                "androidTransport",
                "androidPathState",
                "isConnected",
                "networkAvailable",
                "consecutiveResolverFailures",
                "consecutiveResolverRejections",
                "recentResponseLatenciesMillis",
                "activeFallback",
                "requiresReconnect",
            ),
            instanceFields,
        )
        assertTrue(
            FeedbackSafeDiagnostics::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .none { it.type == String::class.java },
        )

        val rendered = (FeedbackReportComposer.compose(
            draft = FeedbackDraft(
                topic = FeedbackTopic.OTHER,
                details = "Visible user-authored details.",
            ),
            app = FeedbackAppBuild(version = "1", build = "2"),
            diagnostics = safeDiagnostics(),
        ) as FeedbackReportComposeResult.Composed).utf8.toString(StandardCharsets.UTF_8)
        listOf(
            "Domain:",
            "Activity:",
            "Filter:",
            "Address:",
            "SSID:",
            "Raw error:",
        ).forEach { forbidden ->
            assertFalse("$forbidden leaked into the diagnostic section", rendered.contains(forbidden))
        }
    }

    @Test
    fun `safe diagnostics owns an unmodifiable latency snapshot`() {
        val source = mutableListOf(12L, 34L)
        val diagnostics = safeDiagnostics(source)

        source += 56L
        assertEquals(listOf(12L, 34L), diagnostics.recentResponseLatenciesMillis)

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (diagnostics.recentResponseLatenciesMillis as MutableList<Long>) += 78L
        }
        assertEquals(listOf(12L, 34L), diagnostics.recentResponseLatenciesMillis)
    }

    @Test
    fun `invalid normalized draft is rejected with typed issues`() {
        val result = FeedbackReportComposer.compose(
            draft = FeedbackDraft(
                topic = FeedbackTopic.WEBSITE_ACCESS,
                affectedSite = "\u200B",
                details = "\u2066",
            ),
            app = FeedbackAppBuild(version = "1", build = "2"),
            diagnostics = null,
        )

        assertEquals(
            FeedbackReportComposeResult.Rejected(
                FeedbackReportRejection.InvalidDraft(
                    listOf(
                        FeedbackDraftIssue.AFFECTED_SITE_REQUIRED,
                        FeedbackDraftIssue.DETAILS_REQUIRED,
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun `payload above 256 KiB is rejected with its measured UTF-8 size`() {
        val result = FeedbackReportComposer.compose(
            draft = FeedbackDraft(
                topic = FeedbackTopic.OTHER,
                details = "Valid details.",
            ),
            app = FeedbackAppBuild(
                version = "x".repeat(FeedbackReportComposer.MAX_UTF8_BYTES),
                build = "1",
            ),
            diagnostics = null,
        )

        assertTrue(result is FeedbackReportComposeResult.Rejected)
        val rejection = (result as FeedbackReportComposeResult.Rejected).reason
        assertTrue(rejection is FeedbackReportRejection.TooLarge)
        rejection as FeedbackReportRejection.TooLarge
        assertEquals(262_144, rejection.maxBytes)
        assertTrue(rejection.actualBytes > rejection.maxBytes)
    }

    private fun safeDiagnostics(
        latencies: List<Long> = listOf(12L, 34L),
    ) = FeedbackSafeDiagnostics(
        schemaVersion = 1,
        serviceState = OperationalServiceState.RUNNING,
        tunnelState = OperationalTunnelState.CONNECTED,
        androidTransport = OperationalAndroidTransport.WIFI,
        androidPathState = OperationalAndroidPathState.VALIDATED,
        isConnected = true,
        networkAvailable = true,
        consecutiveResolverFailures = 2,
        consecutiveResolverRejections = 1,
        recentResponseLatenciesMillis = latencies,
        activeFallback = ProtectionFallback.ENCRYPTED_DNS,
        requiresReconnect = false,
    )
}
