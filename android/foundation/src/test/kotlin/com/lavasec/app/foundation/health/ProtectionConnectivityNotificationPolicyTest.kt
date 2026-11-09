package com.lavasec.app.foundation.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtectionConnectivityNotificationPolicyTest {
    private val now = 1_000_000L

    @Test
    fun `non actionable connectivity states remain silent`() {
        listOf(
            ProtectionHealthSeverity.HEALTHY,
            ProtectionHealthSeverity.RECOVERING,
            ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK,
            ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK,
            ProtectionHealthSeverity.NETWORK_UNAVAILABLE,
        ).forEach { severity ->
            assertNull(
                severity.name,
                ProtectionConnectivityNotificationPolicy.notification(
                    assessment = assessment(severity),
                    evidence = ProtectionConnectivityNotificationEvidence(
                        lastResolverFailureAtMillis = now - 1,
                        lastSlowResponseAtMillis = now - 1,
                    ),
                    history = ProtectionConnectivityNotificationHistory.EMPTY,
                    nowMillis = now,
                ),
            )
        }
    }

    @Test
    fun `slow and reconnect states expose their matching actionable notification`() {
        val slow = ProtectionConnectivityNotificationPolicy.notification(
            assessment = assessment(ProtectionHealthSeverity.DNS_SLOW),
            evidence = ProtectionConnectivityNotificationEvidence(lastSlowResponseAtMillis = now - 10),
            history = ProtectionConnectivityNotificationHistory.EMPTY,
            nowMillis = now,
        )
        val reconnect = ProtectionConnectivityNotificationPolicy.notification(
            assessment = assessment(ProtectionHealthSeverity.NEEDS_RECONNECT),
            evidence = ProtectionConnectivityNotificationEvidence(lastResolverFailureAtMillis = now - 5),
            history = ProtectionConnectivityNotificationHistory.EMPTY,
            nowMillis = now,
        )

        assertEquals(ProtectionConnectivityNotificationKind.DNS_SLOW, slow?.kind)
        assertEquals(ProtectionConnectivityNotificationAction.RECONNECT, slow?.action)
        assertEquals("Lava DNS is slow", slow?.title)
        assertEquals(
            "Your DNS provider is responding slowly. Tap to reconnect protection.",
            slow?.body,
        )
        assertEquals(ProtectionConnectivityNotificationKind.RECONNECT_NEEDED, reconnect?.kind)
        assertEquals(ProtectionConnectivityNotificationAction.RECONNECT, reconnect?.action)
        assertEquals("Reconnect Lava", reconnect?.title)
        assertEquals(
            "DNS is not resolving on this network. Tap to reconnect protection.",
            reconnect?.body,
        )
    }

    @Test
    fun `freshness duplicates unresolved markers and cooldown bound delivery`() {
        val eventAt = now - 100
        val candidateId = "reconnect-needed:$eventAt"
        val evidence = ProtectionConnectivityNotificationEvidence(lastResolverFailureAtMillis = eventAt)

        assertNull(
            ProtectionConnectivityNotificationPolicy.notification(
                assessment = assessment(ProtectionHealthSeverity.NEEDS_RECONNECT),
                evidence = evidence,
                history = ProtectionConnectivityNotificationHistory(lastDeliveredNotificationId = candidateId),
                nowMillis = now,
            ),
        )
        assertNull(
            ProtectionConnectivityNotificationPolicy.notification(
                assessment = assessment(ProtectionHealthSeverity.NEEDS_RECONNECT),
                evidence = evidence,
                history = ProtectionConnectivityNotificationHistory(
                    unresolvedProblemNotificationId = "dns-slow:1",
                    unresolvedProblemKind = ProtectionConnectivityNotificationKind.DNS_SLOW,
                    lastDeliveredAtMillis = now - 1,
                ),
                nowMillis = now,
            )?.takeUnless { it.supersededNotificationIds == listOf("dns-slow:1") },
        )
        assertNull(
            ProtectionConnectivityNotificationPolicy.notification(
                assessment = assessment(ProtectionHealthSeverity.DNS_SLOW),
                evidence = ProtectionConnectivityNotificationEvidence(lastSlowResponseAtMillis = eventAt),
                history = ProtectionConnectivityNotificationHistory(lastDeliveredAtMillis = now - 1),
                nowMillis = now,
            ),
        )
        assertNull(
            ProtectionConnectivityNotificationPolicy.notification(
                assessment = assessment(ProtectionHealthSeverity.NEEDS_RECONNECT),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastResolverFailureAtMillis = now -
                        ProtectionConnectivityNotificationPolicy.FRESHNESS_WINDOW_MILLIS - 1,
                ),
                history = ProtectionConnectivityNotificationHistory.EMPTY,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `future notification event timestamps are rejected`() {
        listOf(
            ProtectionHealthSeverity.DNS_SLOW to ProtectionConnectivityNotificationEvidence(
                lastSlowResponseAtMillis = now + 1,
            ),
            ProtectionHealthSeverity.NEEDS_RECONNECT to ProtectionConnectivityNotificationEvidence(
                lastResolverFailureAtMillis = now + 1,
            ),
        ).forEach { (severity, evidence) ->
            assertNull(
                ProtectionConnectivityNotificationPolicy.notification(
                    assessment = assessment(severity),
                    evidence = evidence,
                    history = ProtectionConnectivityNotificationHistory.EMPTY,
                    nowMillis = now,
                ),
            )
        }
    }

    @Test
    fun `hard reconnect escalates over slow but never downgrades an unresolved reconnect`() {
        val escalated = ProtectionConnectivityNotificationPolicy.notification(
            assessment = assessment(ProtectionHealthSeverity.NEEDS_RECONNECT),
            evidence = ProtectionConnectivityNotificationEvidence(lastResolverFailureAtMillis = now - 1),
            history = ProtectionConnectivityNotificationHistory(
                unresolvedProblemNotificationId = "dns-slow:10",
                unresolvedProblemKind = ProtectionConnectivityNotificationKind.DNS_SLOW,
                lastDeliveredAtMillis = now - 1,
            ),
            nowMillis = now,
        )
        assertEquals(listOf("dns-slow:10"), escalated?.supersededNotificationIds)

        assertNull(
            ProtectionConnectivityNotificationPolicy.notification(
                assessment = assessment(ProtectionHealthSeverity.DNS_SLOW),
                evidence = ProtectionConnectivityNotificationEvidence(lastSlowResponseAtMillis = now - 1),
                history = ProtectionConnectivityNotificationHistory(
                    unresolvedProblemNotificationId = "reconnect-needed:10",
                    unresolvedProblemKind = ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
                    lastDeliveredAtMillis = now - 1,
                ),
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `recovery clears silently only for fresh post problem primary success`() {
        val problemAt = now - 1_000
        val problemId = "reconnect-needed:$problemAt"
        val history = ProtectionConnectivityNotificationHistory(
            unresolvedProblemNotificationId = problemId,
            unresolvedProblemKind = ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
        )

        assertEquals(
            listOf(problemId),
            ProtectionConnectivityNotificationPolicy.resolvedProblemNotificationIds(
                assessment = assessment(ProtectionHealthSeverity.HEALTHY),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastPrimarySuccessAtMillis = problemAt + 1,
                    lastFallbackSuccessAtMillis = now,
                ),
                history = history,
                nowMillis = now,
            ),
        )
        assertEquals(
            emptyList<String>(),
            ProtectionConnectivityNotificationPolicy.resolvedProblemNotificationIds(
                assessment = assessment(ProtectionHealthSeverity.HEALTHY),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastPrimarySuccessAtMillis = problemAt,
                    lastFallbackSuccessAtMillis = now,
                ),
                history = history,
                nowMillis = now,
            ),
        )
        val oldProblemId = "reconnect-needed:${now -
            ProtectionConnectivityNotificationPolicy.FRESHNESS_WINDOW_MILLIS - 1}"
        val oldProblemHistory = ProtectionConnectivityNotificationHistory(
            unresolvedProblemNotificationId = oldProblemId,
            unresolvedProblemKind = ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
        )
        assertEquals(
            listOf(oldProblemId),
            ProtectionConnectivityNotificationPolicy.resolvedProblemNotificationIds(
                assessment = assessment(ProtectionHealthSeverity.HEALTHY),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastPrimarySuccessAtMillis = now -
                        ProtectionConnectivityNotificationPolicy.FRESHNESS_WINDOW_MILLIS,
                ),
                history = oldProblemHistory,
                nowMillis = now,
            ),
        )
        assertEquals(
            emptyList<String>(),
            ProtectionConnectivityNotificationPolicy.resolvedProblemNotificationIds(
                assessment = assessment(ProtectionHealthSeverity.HEALTHY),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastPrimarySuccessAtMillis = now -
                        ProtectionConnectivityNotificationPolicy.FRESHNESS_WINDOW_MILLIS - 1,
                ),
                history = oldProblemHistory,
                nowMillis = now,
            ),
        )
        assertEquals(
            emptyList<String>(),
            ProtectionConnectivityNotificationPolicy.resolvedProblemNotificationIds(
                assessment = assessment(ProtectionHealthSeverity.HEALTHY),
                evidence = ProtectionConnectivityNotificationEvidence(lastFallbackSuccessAtMillis = now),
                history = history,
                nowMillis = now,
            ),
        )
        assertEquals(
            emptyList<String>(),
            ProtectionConnectivityNotificationPolicy.resolvedProblemNotificationIds(
                assessment = assessment(ProtectionHealthSeverity.HEALTHY),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastPrimarySuccessAtMillis = now + 1,
                ),
                history = history,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `authoritative encrypted fallback coverage clears any unresolved problem before timestamp projection`() {
        listOf(
            ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
            ProtectionConnectivityNotificationKind.DNS_SLOW,
        ).forEach { kind ->
            val problemId = "${kind.wireName}:${now - 1}"
            val history = ProtectionConnectivityNotificationHistory(
                unresolvedProblemNotificationId = problemId,
                unresolvedProblemKind = kind,
            )

            assertNull(
                ProtectionConnectivityNotificationPolicy.notification(
                    assessment = assessment(ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK),
                    evidence = ProtectionConnectivityNotificationEvidence(),
                    history = history,
                    nowMillis = now,
                ),
            )
            assertEquals(
                listOf(problemId),
                ProtectionConnectivityNotificationPolicy.resolvedProblemNotificationIds(
                    assessment = assessment(ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK),
                    evidence = ProtectionConnectivityNotificationEvidence(),
                    history = history,
                    nowMillis = now,
                ),
            )
        }
    }

    @Test
    fun `encrypted fallback coverage does not expire with its observational timestamp`() {
        val problemId = "reconnect-needed:${now - 1}"
        val history = ProtectionConnectivityNotificationHistory(
            unresolvedProblemNotificationId = problemId,
            unresolvedProblemKind = ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
        )

        assertEquals(
            listOf(problemId),
            ProtectionConnectivityNotificationPolicy.resolvedProblemNotificationIds(
                assessment = assessment(ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastFallbackSuccessAtMillis = now -
                        ProtectionConnectivityNotificationPolicy.FRESHNESS_WINDOW_MILLIS - 1,
                ),
                history = history,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `encrypted fallback clear lifts cooldown only after the bounded re flap grace`() {
        val problemId = "reconnect-needed:${now - 1}"
        val history = ProtectionConnectivityNotificationHistory(
            unresolvedProblemNotificationId = problemId,
            unresolvedProblemKind = ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
            lastDeliveredAtMillis = now - 1,
        )
        val covered = assessment(ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK)
        val cooldownUntil = ProtectionConnectivityNotificationPolicy.problemDeliveryCooldownUntilAfterClear(
            assessment = covered,
            history = history,
            nowMillis = now,
        )

        assertEquals(
            now + ProtectionConnectivityNotificationPolicy.RE_FLAP_GRACE_MILLIS,
            cooldownUntil,
        )
        assertNull(
            ProtectionConnectivityNotificationPolicy.notification(
                assessment = assessment(ProtectionHealthSeverity.NEEDS_RECONNECT),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastResolverFailureAtMillis = now +
                        ProtectionConnectivityNotificationPolicy.RE_FLAP_GRACE_MILLIS - 1,
                ),
                history = ProtectionConnectivityNotificationHistory(
                    lastDeliveredAtMillis = now - 1,
                    problemDeliveryCooldownUntilMillis = cooldownUntil,
                ),
                nowMillis = now + ProtectionConnectivityNotificationPolicy.RE_FLAP_GRACE_MILLIS - 1,
            ),
        )
        assertEquals(
            ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
            ProtectionConnectivityNotificationPolicy.notification(
                assessment = assessment(ProtectionHealthSeverity.NEEDS_RECONNECT),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastResolverFailureAtMillis = now +
                        ProtectionConnectivityNotificationPolicy.RE_FLAP_GRACE_MILLIS,
                ),
                history = ProtectionConnectivityNotificationHistory(
                    lastDeliveredAtMillis = now - 1,
                    problemDeliveryCooldownUntilMillis = cooldownUntil,
                ),
                nowMillis = now + ProtectionConnectivityNotificationPolicy.RE_FLAP_GRACE_MILLIS,
            )?.kind,
        )
        assertNull(
            ProtectionConnectivityNotificationPolicy.problemDeliveryCooldownUntilAfterClear(
                assessment = assessment(ProtectionHealthSeverity.HEALTHY),
                history = history,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `encrypted fallback clear near epoch zero still grants exact re flap grace`() {
        val clearAt = 30_000L
        val history = ProtectionConnectivityNotificationHistory(
            unresolvedProblemNotificationId = "reconnect-needed:1",
            unresolvedProblemKind = ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
            lastDeliveredAtMillis = 1,
        )
        val cooldownUntil = ProtectionConnectivityNotificationPolicy.problemDeliveryCooldownUntilAfterClear(
            assessment = assessment(ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK),
            history = history,
            nowMillis = clearAt,
        )

        assertNull(
            ProtectionConnectivityNotificationPolicy.notification(
                assessment = assessment(ProtectionHealthSeverity.NEEDS_RECONNECT),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastResolverFailureAtMillis = clearAt +
                        ProtectionConnectivityNotificationPolicy.RE_FLAP_GRACE_MILLIS - 1,
                ),
                history = ProtectionConnectivityNotificationHistory(
                    lastDeliveredAtMillis = 1,
                    problemDeliveryCooldownUntilMillis = cooldownUntil,
                ),
                nowMillis = clearAt +
                    ProtectionConnectivityNotificationPolicy.RE_FLAP_GRACE_MILLIS - 1,
            ),
        )
        assertEquals(
            ProtectionConnectivityNotificationKind.RECONNECT_NEEDED,
            ProtectionConnectivityNotificationPolicy.notification(
                assessment = assessment(ProtectionHealthSeverity.NEEDS_RECONNECT),
                evidence = ProtectionConnectivityNotificationEvidence(
                    lastResolverFailureAtMillis = clearAt +
                        ProtectionConnectivityNotificationPolicy.RE_FLAP_GRACE_MILLIS,
                ),
                history = ProtectionConnectivityNotificationHistory(
                    lastDeliveredAtMillis = 1,
                    problemDeliveryCooldownUntilMillis = cooldownUntil,
                ),
                nowMillis = clearAt + ProtectionConnectivityNotificationPolicy.RE_FLAP_GRACE_MILLIS,
            )?.kind,
        )
    }

    @Test
    fun `future persisted cooldowns fail closed`() {
        val evidence = ProtectionConnectivityNotificationEvidence(lastResolverFailureAtMillis = now)

        listOf(
            ProtectionConnectivityNotificationHistory(lastDeliveredAtMillis = now + 1),
            ProtectionConnectivityNotificationHistory(
                lastDeliveredAtMillis = now -
                    ProtectionConnectivityNotificationPolicy.MINIMUM_PROBLEM_DELIVERY_INTERVAL_MILLIS,
                problemDeliveryCooldownUntilMillis = Long.MAX_VALUE,
            ),
        ).forEach { history ->
            assertNull(
                ProtectionConnectivityNotificationPolicy.notification(
                    assessment = assessment(ProtectionHealthSeverity.NEEDS_RECONNECT),
                    evidence = evidence,
                    history = history,
                    nowMillis = now,
                ),
            )
        }
    }

    private fun assessment(severity: ProtectionHealthSeverity) = ProtectionHealthAssessment(
        severity = severity,
        primaryAction = if (
            severity == ProtectionHealthSeverity.NEEDS_RECONNECT ||
            severity == ProtectionHealthSeverity.DNS_SLOW
        ) {
            ProtectionHealthAction.RECONNECT
        } else {
            ProtectionHealthAction.TURN_OFF
        },
    )
}
