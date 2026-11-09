package com.lavasec.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.lavasec.app.platform.VpnPreparation
import com.lavasec.app.platform.VpnPreparationResult

internal class AndroidVpnPreparation(
    private val platformPrepare: () -> Intent?,
) : VpnPreparation<Intent> {
    constructor(context: Context) : this(
        platformPrepare = platformPrepare(context),
    )

    override fun prepare(): VpnPreparationResult<Intent> = try {
        platformPrepare()
            ?.let { VpnPreparationResult.ConsentRequired(it) }
            ?: VpnPreparationResult.Prepared
    } catch (_: Exception) {
        VpnPreparationResult.Unavailable
    }

    private companion object {
        fun platformPrepare(context: Context): () -> Intent? {
            val applicationContext = context.applicationContext
            return { VpnService.prepare(applicationContext) }
        }
    }
}
