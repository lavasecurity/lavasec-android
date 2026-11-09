package com.lavasec.app.backend

import android.content.Context
import com.lavasec.app.diagnostics.NativeBugReportService

/** No verifier or Lava service is constructed. Protected submission fails closed to browser help. */
internal class NativeApplicationServices(context: Context) {
    val primary: AndroidAppService? = null
    val reports = NativeBugReportService(emptyList())
}
