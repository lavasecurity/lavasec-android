package com.lavasec.app.billing

import com.lavasec.app.foundation.subscription.EntitlementApplicationPolicy
import com.lavasec.app.foundation.subscription.EntitlementReadingConfidence
import com.lavasec.app.foundation.subscription.FeatureLimitAdmission
import com.lavasec.app.foundation.subscription.FeatureLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** The device's persisted store entitlement, independent of optional account sign-in. */
internal interface PlusPlanPersistence {
    /** Missing is Free. An unreadable record must throw, never masquerade as missing. */
    suspend fun load(): Boolean
    /** Return only after durable publication. An uncertain write must throw and be retryable. */
    suspend fun save(hasPlus: Boolean)
}

/** Construct only after native purchase verification; never accept this value from RN. */
internal data class VerifiedPlusReading(
    val active: Boolean,
    val expiresAt: Instant? = null,
    val confidence: EntitlementReadingConfidence,
)

internal data class NativePlusState(
    val available: Boolean = false,
    val hasPlus: Boolean = false,
    val expiresAt: Instant? = null,
    val admission: FeatureLimitAdmission? = null,
    val localCapabilities: Boolean = false,
) {
    val localFeaturesAvailable: Boolean get() = hasPlus || localCapabilities
    val limits: FeatureLimits get() = when {
        localCapabilities -> FeatureLimits.FOSS
        hasPlus -> FeatureLimits.PLUS
        else -> FeatureLimits.FREE
    }
}

/**
 * Entitlement application from iOS LavaSecurityPlusController, behind Android durable storage.
 * No account/session input, wall-clock expiry timer, or blanket filter reload: StoreKit likewise
 * owns the device entitlement independently of Supabase. Only a verified, confirmed negative
 * changes a persisted Plus flag to Free. Failures do not become negative readings.
 *
 * Purchase/query ordering belongs to the native store adapter. It must serialize its reads and
 * updates before calling this owner; this mutex serializes persistence and publication themselves.
 */
internal class NativePlusEntitlementOwner(
    private val persistence: PlusPlanPersistence,
    private val localCapabilities: Boolean = false,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(NativePlusState())
    val state: StateFlow<NativePlusState> = mutableState.asStateFlow()
    private var previousReading: VerifiedPlusReading? = null

    suspend fun initialize(): Boolean = mutex.withLock {
        if (mutableState.value.available) return@withLock true
        if (localCapabilities) {
            mutableState.value = NativePlusState(
                available = true, localCapabilities = true,
                admission = FeatureLimitAdmission(FeatureLimits.FOSS),
            )
            return@withLock true
        }
        try {
            val hasPlus = persistence.load()
            mutableState.value = NativePlusState(
                available = true, hasPlus = hasPlus,
                expiresAt = if (hasPlus) mutableState.value.expiresAt else null,
                admission = FeatureLimitAdmission(if (hasPlus) FeatureLimits.PLUS else FeatureLimits.FREE),
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Bind an authoritative filter publication or runtime lease to the current durable tier.
     * A concurrent entitlement change waits for this operation; callers must not re-enter this
     * owner from the block. Uncertain persistence refuses admission until initialize reloads disk.
     */
    suspend fun <T> withAvailableLimits(block: suspend (FeatureLimits) -> T): T? = mutex.withLock {
        val current = mutableState.value
        if (!current.available) return@withLock null
        block(current.limits)
    }

    /** Capture before validating the edit, then carry the exact witness through compilation. */
    fun captureAdmission(): FeatureLimitAdmission? = state.value.let {
        if (it.available) it.admission else null
    }

    /** No network or compilation here: fence only the final authoritative publication. */
    suspend fun <T : Any> withAdmission(
        admission: FeatureLimitAdmission,
        block: suspend () -> T,
    ): T? = mutex.withLock {
        val current = mutableState.value
        if (!current.available || current.admission !== admission) return@withLock null
        block()
    }

    /** Returns false on unavailable/failed storage. A store must retry rather than acknowledge. */
    suspend fun apply(reading: VerifiedPlusReading): Boolean = mutex.withLock {
        // FOSS never accepts paid readings; a decorative status cannot become a receipt.
        if (localCapabilities) return@withLock false
        val current = mutableState.value
        if (!current.available) return@withLock false
        val previous = previousReading
        if (previous != null && !EntitlementApplicationPolicy.shouldDeliverReading(
                valueChanged = reading.active != previous.active || reading.expiresAt != previous.expiresAt,
                newConfidence = reading.confidence,
                previousConfidence = previous.confidence,
            )
        ) return@withLock true

        val resolved = EntitlementApplicationPolicy.resolvedHasLavaSecurityPlus(
            computed = reading.active,
            persisted = current.hasPlus,
            confidence = reading.confidence,
        )
        val expiry = when {
            reading.active -> reading.expiresAt
            !resolved -> null
            else -> current.expiresAt
        }
        try {
            if (resolved != current.hasPlus) persistence.save(resolved)
        } catch (cancelled: CancellationException) {
            mutableState.value = current.copy(available = false, admission = null)
            previousReading = null
            throw cancelled
        } catch (_: Exception) {
            // Publication may have committed before its read-back failed. Recover from disk before
            // the next mutation; otherwise a later no-op lapse could leave a durable grant behind.
            mutableState.value = current.copy(available = false, admission = null)
            previousReading = null
            return@withLock false
        }
        mutableState.value = NativePlusState(
            available = true,
            hasPlus = resolved,
            expiresAt = expiry,
            admission = if (resolved == current.hasPlus) current.admission else
                FeatureLimitAdmission(if (resolved) FeatureLimits.PLUS else FeatureLimits.FREE),
        )
        previousReading = reading
        true
    }
}
