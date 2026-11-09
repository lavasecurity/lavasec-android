package com.lavasec.app.backend

import android.content.Context
import com.lavasec.app.BuildConfig
import com.lavasec.app.diagnostics.NativeBugReportService
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Process-owned, post-unlock Integrity provider shared by purchase verification and reports. */
internal class NativeApplicationServices(context: Context) {
    private val integrity = runCatching {
        NativePlayIntegrity(context.applicationContext, BuildConfig.PLAY_CLOUD_PROJECT_NUMBER)
    }.getOrNull()
    val primary = integrity?.let { provider ->
        runCatching { AndroidAppService(BuildConfig.BACKEND_URL.toHttpUrl(), provider) }.getOrNull()
    }
    val reports = NativeBugReportService(buildList {
        primary?.let(::add)
        // Canonical iOS production fallback only; a test/staging override stays on its own service.
        if (integrity != null && BuildConfig.BACKEND_URL == "https://api.lavasecurity.app/") {
            add(AndroidAppService("https://lavasec-api.lavasec.workers.dev/".toHttpUrl(), integrity))
        }
    })
}
