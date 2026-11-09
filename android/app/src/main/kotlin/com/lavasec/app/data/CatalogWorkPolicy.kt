package com.lavasec.app.data

import com.lavasec.app.MainProductUiState
import com.lavasec.app.foundation.catalog.CatalogFreshnessPolicy
import com.lavasec.app.foundation.catalog.CatalogProvenance
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Bundled fallback remains usable, but cannot prove that an inactive warm artifact is fresh. */
internal fun preparedLifetimeMillis(snapshot: CatalogSyncSnapshot): Long? {
    if (snapshot.provenance == CatalogProvenance.BUNDLED || !snapshot.fresh || snapshot.refreshing) return null
    val age = snapshot.ageSeconds ?: return null
    val remaining = CatalogFreshnessPolicy.ONE_WEEK_SECONDS - age
    return remaining.takeIf { it > 0 }?.coerceAtMost(3600L)?.times(1000L)
}

/** Rejected payloads and sources needing foreground repair complete background work. */
internal fun catalogRefreshWorkComplete(result: CatalogRefreshResult): Boolean = when (result.status) {
    CatalogRefreshStatus.UPDATED, CatalogRefreshStatus.UNCHANGED,
    CatalogRefreshStatus.KEPT_INSTALLED, CatalogRefreshStatus.BUNDLED_FALLBACK,
    CatalogRefreshStatus.SUPERSEDED -> true
    CatalogRefreshStatus.INSTALL_FAILED -> result.sourceFailure != null
    CatalogRefreshStatus.OFFLINE, CatalogRefreshStatus.PERSIST_FAILED -> false
}

/** A terminal rejection returns to the periodic refresh cadence, not hourly repair enqueue. */
internal fun shouldScheduleCatalogRepair(snapshot: CatalogSyncSnapshot): Boolean =
    !snapshot.refreshing && !snapshot.sourceRepairRequired && snapshot.lastStatus != CatalogRefreshStatus.KEPT_INSTALLED &&
        snapshot.lastStatus != CatalogRefreshStatus.BUNDLED_FALLBACK

internal enum class CatalogWorkReadiness { READY, NOT_CONFIGURED, RETRY }

/** Wait for an actual credential graph; the worker uses this before touching catalog work. */
internal suspend fun awaitCatalogProduct(state: StateFlow<MainProductUiState>): CatalogWorkReadiness =
    withTimeoutOrNull(10_000L) {
        val current = state.first { candidate ->
            candidate.productAvailable || (!candidate.onboardingReconstructionInFlight &&
                candidate.onboarding?.let { !it.completed && !it.reconstructionRequired && !it.commitInFlight } == true)
        }
        if (current.productAvailable) CatalogWorkReadiness.READY else CatalogWorkReadiness.NOT_CONFIGURED
    } ?: CatalogWorkReadiness.RETRY
