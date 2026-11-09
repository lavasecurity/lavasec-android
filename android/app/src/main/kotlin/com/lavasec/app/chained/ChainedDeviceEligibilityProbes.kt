package com.lavasec.app.chained

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.lavasec.app.platform.ChainedDeviceMemoryReading
import com.lavasec.app.platform.ChainedMemoryKillScan
import com.lavasec.app.platform.ChainedMemoryKillScanResult
import com.lavasec.app.platform.ChainedProcessExit
import com.lavasec.app.platform.ChainedProcessExitScanPolicy

/**
 * Reads the current device's memory facts.
 *
 * A seam so the eligibility store can be exercised on the JVM/Robolectric with a deterministic
 * reading, and so the production `ActivityManager` call has one place to fail closed. A `null`
 * return means the platform could not supply a reading; the store turns that into
 * `MEMORY_UNREADABLE` rather than a guessed zero.
 */
fun interface ChainedDeviceMemoryProbe {
    fun read(): ChainedDeviceMemoryReading?
}

/**
 * Scans recent process exits for memory-related kills.
 *
 * [afterEpochMillis] is the store's consumed baseline; implementations must return only exits
 * newer than it. The result is a [ChainedMemoryKillScanResult], not a nullable, so the two
 * "no scan" cases stay distinguishable: [ChainedMemoryKillScanResult.Unsupported] is pre-API-30
 * (no exit-reason API, the policy's low-RAM fallback applies) while
 * [ChainedMemoryKillScanResult.Failed] is an API 30+ scan that threw (the store fails closed).
 */
fun interface ChainedProcessExitProbe {
    fun scanMemoryKills(afterEpochMillis: Long?): ChainedMemoryKillScanResult
}

/**
 * The production memory probe: `MemoryInfo.totalMem` is the floor term, `memoryClass` is carried
 * for diagnostics, and `isLowRamDevice` feeds the conservative pre-API-30 fallback. Any
 * `ActivityManager` failure reads as unavailable (fail closed), never as a value.
 */
internal class AndroidChainedDeviceMemoryProbe(
    private val activityManager: ActivityManager,
) : ChainedDeviceMemoryProbe {

    override fun read(): ChainedDeviceMemoryReading? = runCatching {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        ChainedDeviceMemoryReading(
            totalMemoryBytes = info.totalMem,
            memoryClassMegabytes = activityManager.memoryClass,
            isLowRamDevice = activityManager.isLowRamDevice,
        )
    }.getOrNull()

    companion object {
        fun from(context: Context): ChainedDeviceMemoryProbe =
            AndroidChainedDeviceMemoryProbe(
                context.applicationContext.getSystemService(ActivityManager::class.java),
            )
    }
}

/**
 * The production exit probe. It is itself API-level-gated: the API-30 implementation is only
 * *loaded and constructed* on API 30+, so a pre-API-30 process never links
 * `ApplicationExitInfo`. On API < 30 the probe reports
 * [ChainedMemoryKillScanResult.Unsupported], which is what selects the policy's low-RAM fallback.
 */
internal class AndroidChainedProcessExitProbe private constructor(
    private val delegate: ChainedProcessExitProbe,
) : ChainedProcessExitProbe {

    override fun scanMemoryKills(afterEpochMillis: Long?): ChainedMemoryKillScanResult =
        delegate.scanMemoryKills(afterEpochMillis)

    companion object {
        fun from(context: Context): ChainedProcessExitProbe {
            val application = context.applicationContext
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AndroidChainedProcessExitProbe(
                    Api30ChainedProcessExitProbe(
                        activityManager = application.getSystemService(ActivityManager::class.java),
                        packageName = application.packageName,
                    ),
                )
            } else {
                UnsupportedChainedProcessExitProbe
            }
        }
    }
}

/** Pre-API-30: `ApplicationExitInfo` does not exist, so exit reasons are simply unavailable. */
private object UnsupportedChainedProcessExitProbe : ChainedProcessExitProbe {
    override fun scanMemoryKills(afterEpochMillis: Long?): ChainedMemoryKillScanResult =
        ChainedMemoryKillScanResult.Unsupported
}

/**
 * API 30+ implementation over `ActivityManager.getHistoricalProcessExitReasons`.
 *
 * Only `REASON_LOW_MEMORY` counts. A crash, ANR, or user force-stop is not evidence the engine
 * exhausted memory, and treating it as one would exclude a device for an unrelated fault.
 * The complete available history is requested ([ChainedProcessExitScanPolicy.REQUEST_ALL_AVAILABLE])
 * so the exit that ended the witnessed session cannot be pushed out of a bounded newest-N window
 * by later background exits; [ChainedProcessExitScanPolicy] still fails closed if a future bounded
 * request ever comes back full.
 *
 * A throw is returned as [ChainedMemoryKillScanResult.Failed], deliberately NOT collapsed into
 * the pre-API-30 [ChainedMemoryKillScanResult.Unsupported]: the service exists here and refused,
 * which is a truthfully unknown device state the store must fail closed on rather than read as
 * "no kills".
 */
@RequiresApi(Build.VERSION_CODES.R)
private class Api30ChainedProcessExitProbe(
    private val activityManager: ActivityManager,
    private val packageName: String,
) : ChainedProcessExitProbe {

    override fun scanMemoryKills(afterEpochMillis: Long?): ChainedMemoryKillScanResult =
        runCatching {
            val exits = activityManager.getHistoricalProcessExitReasons(
                packageName,
                0,
                ChainedProcessExitScanPolicy.REQUEST_ALL_AVAILABLE,
            )
            // Carry every retained exit and its reason: the reconciler selects the FIRST exit at or
            // after the witnessed session start, and a later low-memory exit must not strike.
            ChainedProcessExitScanPolicy.classify(
                exits = exits.map {
                    ChainedProcessExit(
                        epochMillis = it.timestamp,
                        isLowMemory = it.reason == ApplicationExitInfo.REASON_LOW_MEMORY,
                    )
                },
                afterEpochMillis = afterEpochMillis,
                requestedLimit = ChainedProcessExitScanPolicy.REQUEST_ALL_AVAILABLE,
            )
        }.getOrElse { ChainedMemoryKillScanResult.Failed }
}
