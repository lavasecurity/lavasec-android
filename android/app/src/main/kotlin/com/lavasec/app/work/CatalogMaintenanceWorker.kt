package com.lavasec.app.work

import android.content.Context
import android.os.UserManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.await
import com.lavasec.app.LavasecApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class CatalogMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!com.lavasec.app.edition.AppEdition.current.dynamicCatalog) return Result.success()
        if (!applicationContext.getSystemService(UserManager::class.java).isUserUnlocked) return Result.retry()
        val application = applicationContext as? LavasecApplication ?: return Result.failure()
        return try {
            val done = if (inputData.getBoolean("cachedOnly", false)) application.catalogMaintenance.topUp()
                else application.catalogMaintenance.refresh()
            if (done) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}

internal object CatalogMaintenanceScheduler {
    const val REFRESH = "lava-catalog-refresh"
    const val REPAIR = "lava-catalog-repair"
    const val TOP_UP = "lava-prepared-filter-top-up"
    val periodicPolicy = ExistingPeriodicWorkPolicy.KEEP
    val repairPolicy = ExistingWorkPolicy.KEEP
    private fun constraints(network: Boolean) = Constraints.Builder().setRequiresBatteryNotLow(true)
        .setRequiredNetworkType(if (network) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build()
    fun refreshRequest() = PeriodicWorkRequestBuilder<CatalogMaintenanceWorker>(12, TimeUnit.HOURS)
        .setInitialDelay(12, TimeUnit.HOURS).setConstraints(constraints(true))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
    fun topUpRequest() = PeriodicWorkRequestBuilder<CatalogMaintenanceWorker>(1, TimeUnit.HOURS)
        .setInitialDelay(15, TimeUnit.MINUTES).setConstraints(constraints(false))
        .setInputData(androidx.work.workDataOf("cachedOnly" to true)).build()
    fun repairRequest() = OneTimeWorkRequestBuilder<CatalogMaintenanceWorker>()
        .setConstraints(constraints(true)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
    suspend fun install(context: Context) {
        if (!com.lavasec.app.edition.AppEdition.current.dynamicCatalog) return
        val manager = CredentialWorkManager.get(context)
        manager.enqueueUniquePeriodicWork(REFRESH, periodicPolicy, refreshRequest()).await()
        manager.enqueueUniquePeriodicWork(TOP_UP, periodicPolicy, topUpRequest()).await()
    }
    /** Immediate eligibility, subject to Android constraints. Repeated repair requests never
     * replace/postpone an already pending repair or reset its retry/backoff history. */
    suspend fun repair(context: Context) {
        if (!com.lavasec.app.edition.AppEdition.current.dynamicCatalog) return
        CredentialWorkManager.get(context).enqueueUniqueWork(REPAIR, repairPolicy, repairRequest()).await()
    }
}
