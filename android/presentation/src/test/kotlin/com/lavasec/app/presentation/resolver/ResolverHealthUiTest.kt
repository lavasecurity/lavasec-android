package com.lavasec.app.presentation.resolver

import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ProtectionHealthAssessment
import com.lavasec.app.foundation.health.ProtectionHealthEvidence
import com.lavasec.app.foundation.health.ProtectionHealthPolicy
import com.lavasec.app.foundation.health.ProtectionHealthSeverity
import com.lavasec.app.presentation.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResolverHealthUiTest {
    @Test
    fun `every runtime severity maps to its exact typed resolver health state`() {
        assertEquals(
            ResolverHealthUi.HEALTHY,
            ResolverHealthUi.from(assessment(ProtectionHealthSeverity.HEALTHY)),
        )
        assertEquals(
            ResolverHealthUi.RECOVERING,
            ResolverHealthUi.from(assessment(ProtectionHealthSeverity.RECOVERING)),
        )
        assertEquals(
            ResolverHealthUi.DEVICE_FALLBACK,
            ResolverHealthUi.from(assessment(ProtectionHealthSeverity.USING_DEVICE_DNS_FALLBACK)),
        )
        assertEquals(
            ResolverHealthUi.ENCRYPTED_FALLBACK,
            ResolverHealthUi.from(assessment(ProtectionHealthSeverity.USING_ENCRYPTED_FALLBACK)),
        )
        assertEquals(
            ResolverHealthUi.SLOW,
            ResolverHealthUi.from(assessment(ProtectionHealthSeverity.DNS_SLOW)),
        )
        assertEquals(
            ResolverHealthUi.UNREACHABLE,
            ResolverHealthUi.from(assessment(ProtectionHealthSeverity.NETWORK_UNAVAILABLE)),
        )
        assertEquals(
            ResolverHealthUi.NEEDS_RECONNECT,
            ResolverHealthUi.from(assessment(ProtectionHealthSeverity.NEEDS_RECONNECT)),
        )
    }

    @Test
    fun `resolver health states derive from the exact runtime health evidence`() {
        // The existing wedge-recovery evidence pipeline drives every state: this
        // pins the derivation so health evidence can never silently change shape.
        assertEquals(
            ResolverHealthUi.HEALTHY,
            ResolverHealthUi.from(ProtectionHealthPolicy.assess(ProtectionHealthEvidence(isConnected = true))),
        )
        assertEquals(
            ResolverHealthUi.UNREACHABLE,
            ResolverHealthUi.from(
                ProtectionHealthPolicy.assess(
                    ProtectionHealthEvidence(isConnected = true, networkAvailable = false),
                ),
            ),
        )
        assertEquals(
            ResolverHealthUi.RECOVERING,
            ResolverHealthUi.from(
                ProtectionHealthPolicy.assess(
                    ProtectionHealthEvidence(isConnected = true, consecutiveResolverFailures = 2),
                ),
            ),
        )
        assertEquals(
            ResolverHealthUi.NEEDS_RECONNECT,
            ResolverHealthUi.from(
                ProtectionHealthPolicy.assess(
                    ProtectionHealthEvidence(isConnected = true, consecutiveResolverFailures = 3),
                ),
            ),
        )
        assertEquals(
            ResolverHealthUi.NEEDS_RECONNECT,
            ResolverHealthUi.from(
                ProtectionHealthPolicy.assess(
                    ProtectionHealthEvidence(isConnected = true, requiresReconnect = true),
                ),
            ),
        )
        assertEquals(
            ResolverHealthUi.DEVICE_FALLBACK,
            ResolverHealthUi.from(
                ProtectionHealthPolicy.assess(
                    ProtectionHealthEvidence(
                        isConnected = true,
                        activeFallback = ProtectionFallback.DEVICE_DNS,
                    ),
                ),
            ),
        )
        assertEquals(
            ResolverHealthUi.ENCRYPTED_FALLBACK,
            ResolverHealthUi.from(
                ProtectionHealthPolicy.assess(
                    ProtectionHealthEvidence(
                        isConnected = true,
                        activeFallback = ProtectionFallback.ENCRYPTED_DNS,
                    ),
                ),
            ),
        )
        assertEquals(
            ResolverHealthUi.SLOW,
            ResolverHealthUi.from(
                ProtectionHealthPolicy.assess(
                    ProtectionHealthEvidence(
                        isConnected = true,
                        recentResponseLatenciesMillis = listOf(2_600L, 2_700L, 2_800L),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `every health state carries a distinct title and detail pair`() {
        val pairs = ResolverHealthUi.entries.map { it to (it.titleResource to it.detailResource) }
        assertEquals(ResolverHealthUi.entries.size, pairs.map { it.second }.distinct().size)
    }

    @Test
    fun `slow messaging never claims a reconnect repairs the resolver`() {
        // The slow copy offers the two remedies the runtime policy pairs with
        // slowness (reconnect or switch) without claiming either repairs the
        // resolver; it is also deliberately NOT the needs-reconnect copy, so
        // the four problem states stay separately actionable.
        assertEquals(R.string.settings_resolver_health_slow_detail, ResolverHealthUi.SLOW.detailResource)
        assertNotEquals(
            ResolverHealthUi.SLOW.detailResource,
            ResolverHealthUi.NEEDS_RECONNECT.detailResource,
        )
        assertNotEquals(
            ResolverHealthUi.SLOW.detailResource,
            ResolverHealthUi.RECOVERING.detailResource,
        )
        assertNotEquals(
            ResolverHealthUi.SLOW.detailResource,
            ResolverHealthUi.UNREACHABLE.detailResource,
        )
    }

    private fun assessment(severity: ProtectionHealthSeverity) =
        ProtectionHealthAssessment(
            severity,
            com.lavasec.app.foundation.health.ProtectionHealthAction.TURN_OFF,
        )
}
