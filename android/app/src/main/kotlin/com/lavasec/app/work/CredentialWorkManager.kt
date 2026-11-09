package com.lavasec.app.work

import android.content.Context
import android.os.UserManager
import androidx.work.Configuration
import androidx.work.WorkManager

/** WorkManager owns credential storage; all schedulers share this post-unlock initializer. */
internal object CredentialWorkManager {
    fun get(context: Context): WorkManager {
        val application = context.applicationContext
        check(application.getSystemService(UserManager::class.java).isUserUnlocked)
        return try {
            WorkManager.getInstance(application)
        } catch (_: IllegalStateException) {
            synchronized(this) {
                try {
                    WorkManager.getInstance(application)
                } catch (_: IllegalStateException) {
                    WorkManager.initialize(application, Configuration.Builder().build())
                    WorkManager.getInstance(application)
                }
            }
        }
    }
}
