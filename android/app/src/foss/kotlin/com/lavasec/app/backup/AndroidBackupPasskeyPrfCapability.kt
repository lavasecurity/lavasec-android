package com.lavasec.app.backup

import android.content.Context
import com.lavasec.app.foundation.backup.BackupPasskeyPrfCapability

/** FOSS portable backups use password/recovery secrets; never probe any credential provider. */
internal class AndroidBackupPasskeyPrfCapability private constructor() : BackupPasskeyPrfCapability {
    override fun isAvailable() = false
    companion object {
        fun production(context: Context) = AndroidBackupPasskeyPrfCapability()
    }
}
