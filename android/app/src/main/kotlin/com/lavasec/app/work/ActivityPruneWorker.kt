package com.lavasec.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.await
import com.lavasec.app.LavasecApplication
import com.lavasec.app.data.ActivityDatabase
import com.lavasec.app.data.ActivityRepository
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

internal enum class ActivityPruneWorkOutcome {
    SUCCESS,
    RETRY,
}

internal data class ActivityRetentionInstallationRun(
    val completion: Deferred<Unit>,
    val firstAttemptReceipt: CompletableDeferred<Unit>,
)

internal class ActivityRetentionInstallationLoop(
    private val userUnlocked: () -> Boolean,
    private val enqueueAndAwait: suspend () -> Unit,
    private val awaitRetry: suspend () -> Unit,
) {
    companion object {
        private const val MAX_ATTEMPTS = 2
    }

    private val monitor = Any()
    private var installation: ActivityRetentionInstallationRun? = null

    fun start(scope: CoroutineScope): Deferred<Unit>? =
        startWithReceipt(scope)?.completion

    fun startWithReceipt(scope: CoroutineScope): ActivityRetentionInstallationRun? {
        if (!userUnlocked()) return null
        return synchronized(monitor) {
            installation?.takeUnless { it.completion.isCompleted } ?: run {
                val firstAttemptReceipt = CompletableDeferred<Unit>()
                val completion = scope.async(
                    start = CoroutineStart.LAZY,
                ) {
                    repeat(MAX_ATTEMPTS) { attempt ->
                        try {
                            check(userUnlocked()) {
                                "User locked before retention work installation completed"
                            }
                            enqueueAndAwait()
                            if (attempt == 0) firstAttemptReceipt.complete(Unit)
                            return@async
                        } catch (cancelled: CancellationException) {
                            if (attempt == 0) {
                                firstAttemptReceipt.completeExceptionally(cancelled)
                            }
                            throw cancelled
                        } catch (failure: Exception) {
                            if (attempt == 0) {
                                firstAttemptReceipt.completeExceptionally(failure)
                            }
                            if (attempt == MAX_ATTEMPTS - 1) throw failure
                            awaitRetry()
                        }
                    }
                }
                ActivityRetentionInstallationRun(
                    completion = completion,
                    firstAttemptReceipt = firstAttemptReceipt,
                )
            }.also { created ->
                installation = created
                created.completion.invokeOnCompletion { failure ->
                    if (!created.firstAttemptReceipt.isCompleted) {
                        if (failure == null) {
                            created.firstAttemptReceipt.complete(Unit)
                        } else {
                            created.firstAttemptReceipt.completeExceptionally(failure)
                        }
                    }
                    synchronized(monitor) {
                        if (installation === created) installation = null
                    }
                }
                created.completion.start()
            }
        }
    }
}

internal suspend fun runActivityPrune(
    userUnlocked: Boolean,
    nowMillis: Long,
    prune: suspend (Long) -> Unit,
): ActivityPruneWorkOutcome {
    if (!userUnlocked) return ActivityPruneWorkOutcome.RETRY
    return try {
        require(nowMillis >= 0L)
        prune(nowMillis)
        ActivityPruneWorkOutcome.SUCCESS
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ActivityPruneWorkOutcome.RETRY
    }
}

internal fun runActivityRetentionUnlockHandoff(
    scheduleInProcess: () -> Deferred<Unit>?,
    finishPendingResult: () -> Unit,
) {
    val finished = AtomicBoolean(false)
    fun finishOnce() {
        if (finished.compareAndSet(false, true)) finishPendingResult()
    }
    var receiptObserved = false
    try {
        scheduleInProcess()?.let { firstAttemptReceipt ->
            firstAttemptReceipt.invokeOnCompletion { finishOnce() }
            receiptObserved = true
        }
    } finally {
        if (!receiptObserved) finishOnce()
    }
}

class ActivityPruneWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val userManager = applicationContext
            .getSystemService(Context.USER_SERVICE) as UserManager
        val nowMillis = System.currentTimeMillis()
        return when (
            runActivityPrune(
                userUnlocked = userManager.isUserUnlocked,
                nowMillis = nowMillis,
                prune = { capturedNow ->
                    val database = ActivityDatabase(applicationContext)
                    try {
                        ActivityRepository(database).pruneDetailedHistory(capturedNow)
                    } finally {
                        database.close()
                    }
                },
            )
        ) {
            ActivityPruneWorkOutcome.SUCCESS -> Result.success()
            ActivityPruneWorkOutcome.RETRY -> Result.retry()
        }
    }
}

internal object ActivityRetentionScheduler {
    const val UNIQUE_WORK_NAME = "lavasec-activity-retention"
    val EXISTING_WORK_POLICY = ExistingPeriodicWorkPolicy.KEEP
    private const val RETRY_DELAY_MILLIS = 30_000L

    private val applicationContext = AtomicReference<Context?>(null)
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val installationLoop = ActivityRetentionInstallationLoop(
        userUnlocked = {
            applicationContext.get()
                ?.getSystemService(Context.USER_SERVICE)
                ?.let { it as UserManager }
                ?.isUserUnlocked == true
        },
        enqueueAndAwait = {
            val context = checkNotNull(applicationContext.get())
            CredentialWorkManager.get(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    EXISTING_WORK_POLICY,
                    periodicRequest(),
                )
                .await()
        },
        awaitRetry = { delay(RETRY_DELAY_MILLIS) },
    )

    fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<ActivityPruneWorker>(
            repeatInterval = 12L,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 2L,
            flexTimeIntervalUnit = TimeUnit.HOURS,
        )
            .addTag(UNIQUE_WORK_NAME)
            .build()

    fun startIfUnlocked(context: Context): Deferred<Unit>? {
        return startRunIfUnlocked(context)?.completion
    }

    internal fun startWithFirstAttemptReceipt(context: Context): Deferred<Unit>? {
        return startRunIfUnlocked(context)?.firstAttemptReceipt
    }

    private fun startRunIfUnlocked(context: Context): ActivityRetentionInstallationRun? {
        val processContext = context.applicationContext
        val userManager = processContext
            .getSystemService(Context.USER_SERVICE) as UserManager
        if (!userManager.isUserUnlocked) return null
        applicationContext.compareAndSet(null, processContext)
        return installationLoop.startWithReceipt(processScope)
    }


}

/** Direct-Boot-safe bridge: it schedules credential work only after Android confirms unlock. */
class ActivityRetentionUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Intent.ACTION_USER_UNLOCKED) return
        (context.applicationContext as? LavasecApplication)?.handleUserUnlocked()
        val pending = goAsync()
        runActivityRetentionUnlockHandoff(
            scheduleInProcess = {
                ActivityRetentionScheduler.startWithFirstAttemptReceipt(context)
            },
            finishPendingResult = pending::finish,
        )
    }
}
