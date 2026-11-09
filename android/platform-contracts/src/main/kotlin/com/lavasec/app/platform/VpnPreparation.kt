package com.lavasec.app.platform

sealed interface VpnPreparationResult<out Request> {
    data object Prepared : VpnPreparationResult<Nothing>

    data class ConsentRequired<Request>(
        val request: Request,
    ) : VpnPreparationResult<Request>

    data object Unavailable : VpnPreparationResult<Nothing>
}

fun interface VpnPreparation<Request> {
    fun prepare(): VpnPreparationResult<Request>
}
