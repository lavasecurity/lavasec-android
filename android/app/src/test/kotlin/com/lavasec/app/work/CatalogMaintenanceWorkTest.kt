package com.lavasec.app.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.lavasec.app.data.CatalogSyncSnapshot
import com.lavasec.app.data.CatalogRefreshResult
import com.lavasec.app.data.CatalogRefreshStatus
import com.lavasec.app.foundation.filter.CustomBlocklistSourceAcquisitionException
import com.lavasec.app.foundation.filter.CustomBlocklistSourceFailure
import com.lavasec.app.data.catalogRefreshWorkComplete
import com.lavasec.app.data.shouldScheduleCatalogRepair
import com.lavasec.app.data.preparedLifetimeMillis
import com.lavasec.app.foundation.catalog.CatalogFreshnessPolicy
import com.lavasec.app.foundation.catalog.CatalogProvenance
import java.util.concurrent.TimeUnit
import com.lavasec.app.MainProductUiState
import com.lavasec.app.onboarding.OnboardingOwnerState
import com.lavasec.app.foundation.onboarding.OnboardingState
import com.lavasec.app.data.CatalogWorkReadiness
import com.lavasec.app.data.awaitCatalogProduct
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogMaintenanceWorkTest {
    @Test fun `an abandoned fresh installation completes catalog work without waiting for onboarding`() = runTest {
        val state = MutableStateFlow(MainProductUiState(graphGeneration = 1,
            onboarding = OnboardingOwnerState(false, OnboardingState.initial())))
        assertEquals(CatalogWorkReadiness.NOT_CONFIGURED, awaitCatalogProduct(state))
        assertEquals("A fresh install must not enter retry delay", 0L, testScheduler.currentTime)
    }
    @Test fun `unknown credential availability still waits and retries`() = runTest {
        assertEquals(CatalogWorkReadiness.RETRY, awaitCatalogProduct(MutableStateFlow(MainProductUiState())))
        assertEquals(10_000L, testScheduler.currentTime)
    }
    @Test fun `an onboarding reconstruction is transient unavailability rather than a fresh installation`() = runTest {
        val state = MutableStateFlow(MainProductUiState(graphGeneration = 2,
            onboarding = OnboardingOwnerState(false, OnboardingState.initial(), reconstructionRequired = true)))
        assertEquals(CatalogWorkReadiness.RETRY, awaitCatalogProduct(state))
        assertEquals(10_000L, testScheduler.currentTime)
    }
    @Test fun `a credential graph that becomes available admits catalog work`() = runTest {
        val state = MutableStateFlow(MainProductUiState())
        val admission = async { awaitCatalogProduct(state) }
        runCurrent()
        assertFalse(admission.isCompleted)
        state.value = MainProductUiState(graphGeneration = 3, productAvailable = true,
            onboarding = OnboardingOwnerState(true, null))
        assertEquals(CatalogWorkReadiness.READY, admission.await())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `repair has immediate eligibility and keeps pending work and backoff`() {
        val repair = CatalogMaintenanceScheduler.repairRequest().workSpec
        assertEquals(0L, repair.initialDelay)
        assertEquals(NetworkType.CONNECTED, repair.constraints.requiredNetworkType)
        assertEquals(ExistingWorkPolicy.KEEP, CatalogMaintenanceScheduler.repairPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(30), repair.backoffDelayDuration)
        assertTrue(repair.constraints.requiresBatteryNotLow())
        assertNotEquals(CatalogMaintenanceScheduler.REFRESH, CatalogMaintenanceScheduler.REPAIR)
    }
    @Test fun `recurring refresh and cache-only top-up do not postpone earlier work`() {
        val refresh = CatalogMaintenanceScheduler.refreshRequest().workSpec
        val warm = CatalogMaintenanceScheduler.topUpRequest().workSpec
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, CatalogMaintenanceScheduler.periodicPolicy)
        assertEquals(TimeUnit.HOURS.toMillis(12), refresh.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(1), warm.intervalDuration)
        assertEquals(NetworkType.NOT_REQUIRED, warm.constraints.requiredNetworkType)
        assertTrue(warm.input.getBoolean("cachedOnly", false))
    }
    @Test fun `stale bundled missing or refreshing catalog requires repair before warming`() {
        val valid = CatalogSyncSnapshot(CatalogProvenance.LAST_KNOWN_GOOD, "catalog", 1, 2, 0, true, 10, false, null)
        assertEquals(3600000L, preparedLifetimeMillis(valid))
        listOf(valid.copy(provenance = CatalogProvenance.BUNDLED), valid.copy(fresh = false),
            valid.copy(refreshing = true), valid.copy(ageSeconds = null),
            valid.copy(ageSeconds = CatalogFreshnessPolicy.ONE_WEEK_SECONDS)).forEach { assertNull(preparedLifetimeMillis(it)) }
        assertEquals(1000L, preparedLifetimeMillis(valid.copy(ageSeconds = CatalogFreshnessPolicy.ONE_WEEK_SECONDS - 1)))
    }
    @Test fun `invalid rollback and superseded catalogs finish work without transient retries`() {
        listOf(CatalogRefreshStatus.UPDATED, CatalogRefreshStatus.UNCHANGED,
            CatalogRefreshStatus.KEPT_INSTALLED, CatalogRefreshStatus.BUNDLED_FALLBACK,
            CatalogRefreshStatus.SUPERSEDED).forEach { assertTrue(catalogRefreshWorkComplete(CatalogRefreshResult(it))) }
        listOf(CatalogRefreshStatus.OFFLINE, CatalogRefreshStatus.PERSIST_FAILED,
            CatalogRefreshStatus.INSTALL_FAILED).forEach { assertFalse(catalogRefreshWorkComplete(CatalogRefreshResult(it))) }
    }
    @Test fun `custom source failures complete background work until foreground source repair`() {
        val failure = CatalogRefreshResult(CatalogRefreshStatus.INSTALL_FAILED,
            sourceFailure = CustomBlocklistSourceAcquisitionException("custom", CustomBlocklistSourceFailure.STORAGE))
        assertTrue(catalogRefreshWorkComplete(failure))
        assertFalse(catalogRefreshWorkComplete(failure.copy(sourceFailure = null)))
        val stale = CatalogSyncSnapshot(CatalogProvenance.LAST_KNOWN_GOOD, "catalog", 1, 2, 0,
            false, CatalogFreshnessPolicy.ONE_WEEK_SECONDS + 1, false, failure.status,
            sourceRepairRequired = true)
        assertFalse(shouldScheduleCatalogRepair(stale))
        assertTrue(shouldScheduleCatalogRepair(stale.copy(sourceRepairRequired = false)))
    }
    @Test fun `hourly top up cannot immediately requeue a known terminal rejection`() {
        val stale = CatalogSyncSnapshot(CatalogProvenance.LAST_KNOWN_GOOD, "catalog", 1, 2, 0,
            false, CatalogFreshnessPolicy.ONE_WEEK_SECONDS + 1, false, null)
        assertTrue(shouldScheduleCatalogRepair(stale))
        assertFalse("An active refresh already owns recovery and the accepted top-up",
            shouldScheduleCatalogRepair(stale.copy(refreshing = true)))
        assertTrue(shouldScheduleCatalogRepair(stale.copy(lastStatus = CatalogRefreshStatus.OFFLINE)))
        listOf(CatalogRefreshStatus.KEPT_INSTALLED, CatalogRefreshStatus.BUNDLED_FALLBACK).forEach {
            assertFalse(shouldScheduleCatalogRepair(stale.copy(lastStatus = it)))
            assertNull(preparedLifetimeMillis(stale.copy(lastStatus = it)))
        }
    }
}
