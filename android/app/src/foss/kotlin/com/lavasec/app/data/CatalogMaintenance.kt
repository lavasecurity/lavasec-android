package com.lavasec.app.data

import android.content.Context
import com.lavasec.app.MainProductViewModel
import com.lavasec.app.foundation.catalog.CatalogArtifact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bundled metadata only; explicit refresh re-acquires selected source bytes through the compiler. */
internal class CatalogMaintenance(private val context: Context, private val product: MainProductViewModel) {
    val sync: CatalogSyncOwner? = null
    private val bundledRefresh by lazy {
        BundledCatalogRefreshOwner(
            CatalogBundledArtifact.from(CatalogRepository(context.assets).loadBundled()),
            product::installCatalog,
        )
    }
    suspend fun refresh(): Boolean {
        if (awaitCatalogProduct(product.state) != CatalogWorkReadiness.READY) return false
        return bundledRefresh.refresh()
    }
    suspend fun topUp(scheduleRepair: Boolean = true): Boolean = true
}

/** Unchanged bundled metadata must still drive a new source compile/publication on every refresh. */
internal class BundledCatalogRefreshOwner(
    private val bundled: CatalogArtifact,
    private val install: suspend (CatalogArtifact) -> Boolean,
) {
    private val admission = Mutex()
    suspend fun refresh(): Boolean = admission.withLock {
        try { install(bundled) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
}
