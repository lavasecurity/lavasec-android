package com.lavasec.app.platform

class VpnConsentAttempt internal constructor()

sealed interface VpnConsentBeginResult<out Request> {
    data object Prepared : VpnConsentBeginResult<Nothing>

    data class LaunchRequired<Request>(
        val request: Request,
        val attempt: VpnConsentAttempt,
    ) : VpnConsentBeginResult<Request>

    data object AlreadyPending : VpnConsentBeginResult<Nothing>

    data object Unavailable : VpnConsentBeginResult<Nothing>
}

sealed interface VpnConsentCompletion {
    data object Prepared : VpnConsentCompletion

    data object ConsentStillRequired : VpnConsentCompletion

    data object Unavailable : VpnConsentCompletion

    data object Ignored : VpnConsentCompletion
}

sealed interface VpnConsentAbandonResult {
    data object Abandoned : VpnConsentAbandonResult

    data object Ignored : VpnConsentAbandonResult
}

class VpnConsentController<Request>(
    private val preparation: VpnPreparation<Request>,
) {
    private val lock = Any()
    private var activeAttempt: VpnConsentAttempt? = null
    private var probeInFlight = false

    fun begin(): VpnConsentBeginResult<Request> = synchronized(lock) {
        if (activeAttempt != null || probeInFlight) {
            return@synchronized VpnConsentBeginResult.AlreadyPending
        }

        when (val result = guardedProbe()) {
            VpnPreparationResult.Prepared -> VpnConsentBeginResult.Prepared
            is VpnPreparationResult.ConsentRequired -> {
                val attempt = VpnConsentAttempt()
                activeAttempt = attempt
                VpnConsentBeginResult.LaunchRequired(
                    request = result.request,
                    attempt = attempt,
                )
            }
            VpnPreparationResult.Unavailable -> VpnConsentBeginResult.Unavailable
        }
    }

    fun completeActiveResult(
        attempt: VpnConsentAttempt,
    ): VpnConsentCompletion = synchronized(lock) {
        if (activeAttempt !== attempt) {
            return@synchronized VpnConsentCompletion.Ignored
        }

        activeAttempt = null
        when (guardedProbe()) {
            VpnPreparationResult.Prepared -> VpnConsentCompletion.Prepared
            is VpnPreparationResult.ConsentRequired -> {
                VpnConsentCompletion.ConsentStillRequired
            }
            VpnPreparationResult.Unavailable -> VpnConsentCompletion.Unavailable
        }
    }

    fun abandonPending(
        attempt: VpnConsentAttempt,
    ): VpnConsentAbandonResult = synchronized(lock) {
        if (activeAttempt !== attempt) {
            return@synchronized VpnConsentAbandonResult.Ignored
        }

        activeAttempt = null
        VpnConsentAbandonResult.Abandoned
    }

    private fun guardedProbe(): VpnPreparationResult<Request> {
        check(!probeInFlight)
        probeInFlight = true
        return try {
            probe()
        } finally {
            probeInFlight = false
        }
    }

    private fun probe(): VpnPreparationResult<Request> = try {
        preparation.prepare()
    } catch (_: Exception) {
        VpnPreparationResult.Unavailable
    }
}
