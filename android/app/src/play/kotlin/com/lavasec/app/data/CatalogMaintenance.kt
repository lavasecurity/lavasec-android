package com.lavasec.app.data

import android.content.Context
import com.lavasec.app.BuildConfig
import com.lavasec.app.MainProductViewModel
import com.lavasec.app.work.CatalogMaintenanceScheduler
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One process-owned sync authority shared by foreground refresh and durable Android workers. */
internal class CatalogMaintenance(private val context: Context, private val product: MainProductViewModel) {
    val sync: CatalogSyncOwner? by lazy {
        runCatching { CatalogSyncOwner(
            bundled = CatalogBundledArtifact.from(CatalogRepository(context.assets).loadBundled()),
            cache = CatalogManifestCache(File(context.filesDir, CatalogRuntimeCatalog.CACHE_DIRECTORY)),
            fetcher = OkHttpCatalogManifestFetcher(),
            catalogUrls = CatalogSyncEndpoints.catalogUrls(BuildConfig.BACKEND_URL),
            installer = CatalogInstaller(product::installCatalog),
        ) }.getOrNull()
    }
    suspend fun refresh(): Boolean {
        when (awaitCatalogProduct(product.state)) {
            CatalogWorkReadiness.NOT_CONFIGURED -> return true
            CatalogWorkReadiness.RETRY -> return false
            CatalogWorkReadiness.READY -> Unit
        }
        val result = sync?.refresh() ?: return false
        val accepted = result.status in setOf(CatalogRefreshStatus.UPDATED, CatalogRefreshStatus.UNCHANGED)
        if (accepted) topUp(scheduleRepair = false)
        return catalogRefreshWorkComplete(result)
    }
    suspend fun topUp(scheduleRepair: Boolean = true): Boolean {
        // Fresh installs have no confirmed filter to maintain. Stop before loading the
        // bundled cache or scheduling repair, while unavailable completed graphs retry.
        when (awaitCatalogProduct(product.state)) {
            CatalogWorkReadiness.NOT_CONFIGURED -> return true
            CatalogWorkReadiness.RETRY -> return false
            CatalogWorkReadiness.READY -> Unit
        }
        val owner = sync ?: return false
        withContext(Dispatchers.IO) { owner.loadCachedInstalled() }
        val snapshot = owner.snapshot()
        val lifetime = preparedLifetimeMillis(snapshot)
        if (lifetime == null) {
            if (scheduleRepair && shouldScheduleCatalogRepair(snapshot)) CatalogMaintenanceScheduler.repair(context)
            return true // The cache-only pass is complete; no refresh success is claimed.
        }
        withContext(Dispatchers.Default) { product.topUpPreparedFilters(System.currentTimeMillis() + lifetime) }
        return true
    }
}
