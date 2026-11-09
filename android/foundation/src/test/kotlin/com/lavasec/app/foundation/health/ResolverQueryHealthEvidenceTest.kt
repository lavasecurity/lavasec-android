package com.lavasec.app.foundation.health

import com.lavasec.app.foundation.dns.ResolverFailureReason
import org.junit.Assert.assertThrows
import org.junit.Test

class ResolverQueryHealthEvidenceTest {
    @Test
    fun `accepted evidence requires latency`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResolverQueryHealthEvidence(
                queryId = 1,
                primaryOutcome = PrimaryResolverOutcome.ACCEPTED,
            )
        }
    }

    @Test
    fun `only accepted evidence may carry latency`() {
        listOf(
            {
                ResolverQueryHealthEvidence(
                    queryId = 1,
                    primaryOutcome = PrimaryResolverOutcome.REJECTED,
                    latencyMillis = 1,
                )
            },
            {
                ResolverQueryHealthEvidence(
                    queryId = 2,
                    primaryOutcome = PrimaryResolverOutcome.FAILED,
                    primaryFailureReason = ResolverFailureReason.TIMEOUT,
                    latencyMillis = 1,
                )
            },
        ).forEach { construct ->
            assertThrows(IllegalArgumentException::class.java) {
                construct()
            }
        }
    }
}
