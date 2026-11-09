package com.lavasec.app.product.activity

import com.lavasec.app.foundation.config.ProtectionLevel
import com.lavasec.app.foundation.filter.FilterProfileDraft
import com.lavasec.app.product.DomainActionOwner
import com.lavasec.app.product.NativeDomainActionHost
import com.lavasec.app.product.Revisioned
import com.lavasec.app.product.StablePage
import com.lavasec.app.product.domain.ActiveFilterAuthority
import com.lavasec.app.product.domain.ActiveFilterAuthorityPort
import com.lavasec.app.product.domain.DomainActionApplyResult
import com.lavasec.app.product.domain.DomainActionAuthenticationPolicy
import com.lavasec.app.product.domain.DomainActionAuthenticationResult
import com.lavasec.app.product.domain.DomainActionAuthenticator
import com.lavasec.app.product.domain.DomainActionMutationPort
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityOwnerTest {
    @Test
    fun `revision signals drive exact machine and mapped disabled state`() = runTest {
        val port = DisabledPort()
        val domain = DomainActionOwner(host(), this, UnconfinedTestDispatcher(testScheduler))
        val owner = ActivityOwner(
            port = port,
            initialDateLens = todayLens(),
            domainActions = domain,
            scope = this,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        port.signals.emit(ProductActivityRevisionSignal(1L, policyRevision = 1L))
        advanceUntilIdle()

        assertEquals(1, port.summaryRevisionReads)
        assertEquals(1, port.domainRevisionReads)
        assertEquals(1, port.networkRevisionReads)
        assertTrue(owner.state.value.overview is
            com.lavasec.app.presentation.activity.ActivityContentState.Disabled)
        assertTrue(owner.state.value.domainHistory is
            com.lavasec.app.presentation.activity.ActivityContentState.Disabled)
        owner.close()
        domain.close()
    }

    @Test
    fun `closed owner rejects commands and freezes publication`() = runTest {
        val port = DisabledPort()
        val domain = DomainActionOwner(host(), this, UnconfinedTestDispatcher(testScheduler))
        val owner = ActivityOwner(
            port = port,
            initialDateLens = todayLens(),
            domainActions = domain,
            scope = this,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        port.signals.emit(ProductActivityRevisionSignal(1L))
        advanceUntilIdle()
        val frozen = owner.state.value
        val reads = port.summaryRevisionReads
        owner.close()

        assertFalse(owner.setTopDomainSearch("later"))
        assertFalse(owner.refreshSummary())
        port.signals.emit(ProductActivityRevisionSignal(2L))
        advanceUntilIdle()
        assertEquals(reads, port.summaryRevisionReads)
        assertEquals(frozen, owner.state.value)
        domain.close()
    }

    private fun todayLens(): ActivityDateLens {
        val day = LocalDate.of(2026, 8, 2)
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return ActivityDateLens(start, start, "UTC", day)
    }

    private fun host(): NativeDomainActionHost {
        val profile = FilterProfileDraft(
            id = "profile",
            name = "Profile",
            protectionLevel = ProtectionLevel.BALANCED,
            enabledSourceIds = emptySet(),
        )
        return NativeDomainActionHost(
            authenticationPolicy = DomainActionAuthenticationPolicy.SKIPPED,
            authenticator = DomainActionAuthenticator {
                DomainActionAuthenticationResult.Authorized
            },
            authority = ActiveFilterAuthorityPort {
                ActiveFilterAuthority(0L, profile, hasUnsavedChanges = false)
            },
            mutation = DomainActionMutationPort { _, _ -> DomainActionApplyResult.Applied(1L) },
        )
    }
}

private class DisabledPort : ProductActivityPort {
    val signals = MutableSharedFlow<ProductActivityRevisionSignal>(extraBufferCapacity = 1)
    override val revisionSignals: Flow<ProductActivityRevisionSignal> = signals
    var summaryRevisionReads = 0
    var domainRevisionReads = 0
    var networkRevisionReads = 0

    override suspend fun summaryCountRevision(): Long = 0L.also { summaryRevisionReads += 1 }

    override suspend fun domainLogRevision(): Long = 0L.also { domainRevisionReads += 1 }

    override suspend fun networkRevision(): Long = 0L.also { networkRevisionReads += 1 }

    override suspend fun loadSummary(
        query: ActivitySummaryQuery,
        topDomainsLimit: Int,
    ): Revisioned<ActivitySummaryRead> = Revisioned(
        0L,
        ActivitySummaryRead(
            query = query,
            topDomainsDomainRevision = 0L,
            topDomainsPolicyRevision = 1L,
            topDomainsAvailability = DomainHistoryAvailability.DISABLED,
            result = ProductReadResult.Disabled,
        ),
    )

    override suspend fun loadDomainHistory(
        request: DomainHistoryPageRequest,
    ): Revisioned<DomainHistoryRead> = Revisioned(
        0L,
        DomainHistoryRead(
            query = request.query,
            policyRevision = 1L,
            availability = DomainHistoryAvailability.DISABLED,
            result = ProductReadResult.Disabled,
        ),
    )

    override suspend fun loadNetworkActivity(
        request: NetworkActivityPageRequest,
    ): Revisioned<ProductReadResult<StablePage<NetworkActivityRow>>> =
        Revisioned(0L, ProductReadResult.Disabled)
}
