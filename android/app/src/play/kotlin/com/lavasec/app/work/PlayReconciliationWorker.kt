package com.lavasec.app.work

import android.content.Context
import android.os.UserManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.await
import com.lavasec.app.BuildConfig
import com.lavasec.app.LavasecApplication
import com.lavasec.app.billing.PlayPurchaseReadStatus
import com.lavasec.app.billing.PlayPurchaseSnapshot
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PlayReconciliationOutcome { SUCCESS, RETRY }

internal suspend fun reconcilePlayPurchases(
    userUnlocked: Boolean,
    configured: Boolean,
    refresh: suspend () -> PlayPurchaseSnapshot?,
): PlayReconciliationOutcome {
    // An unconfigured internal build must not keep scheduling network retries.
    if (!configured) return PlayReconciliationOutcome.SUCCESS
    if (!userUnlocked) return PlayReconciliationOutcome.RETRY
    return try {
        val reading = refresh()
        if (reading != null && reading.status == PlayPurchaseReadStatus.CHECKED &&
            !reading.acknowledgementPending) PlayReconciliationOutcome.SUCCESS
        else PlayReconciliationOutcome.RETRY
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PlayReconciliationOutcome.RETRY
    }
}

/** No receipts in WorkData: reopen the native process owner and its encrypted cache after unlock. */
class PlayReconciliationWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val outcome = reconcilePlayPurchases(
            userUnlocked = applicationContext.getSystemService(UserManager::class.java).isUserUnlocked,
            configured = BuildConfig.PLAY_CLOUD_PROJECT_NUMBER > 0,
        ) {
            val app = applicationContext as? LavasecApplication ?: return@reconcilePlayPurchases null
            val backend = withTimeoutOrNull(30_000) { app.plusBackend.filterNotNull().first() }
                ?: return@reconcilePlayPurchases null
            withTimeoutOrNull(120_000) {
                backend.purchases.refresh()
                backend.purchases.state.value
            }
        }
        return when (outcome) {
            PlayReconciliationOutcome.SUCCESS -> Result.success()
            PlayReconciliationOutcome.RETRY -> Result.retry()
        }
    }
}

internal object PlayReconciliationScheduler {
    const val UNIQUE_WORK_NAME = "lavasec-play-reconciliation"

    fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<PlayReconciliationWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(UNIQUE_WORK_NAME)
            .build()

    suspend fun installIfConfigured(context: Context) {
        installPlayReconciliationIfConfigured(BuildConfig.PLAY_CLOUD_PROJECT_NUMBER > 0) {
            CredentialWorkManager.get(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodicRequest(),
            ).await()
        }
    }
}
