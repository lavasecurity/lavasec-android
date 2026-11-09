package com.lavasec.app.account

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import java.lang.ref.WeakReference

/** Non-exported native browser lifecycle host. It never receives a token or verifier. */
class AppleOAuthBrowserActivity : Activity() {
    private val browser get() = AppleOAuthBrowserBroker.process
    private var requestId: String? = null
    private var started = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        requestId = intent.getStringExtra(REQUEST_ID)
        started = state?.getBoolean(STARTED) == true
        val id = requestId
        val host = WeakReference(this)
        if (id == null || !browser.attach(id) { host.get()?.finish() }) finish()
    }

    override fun onResume() {
        super.onResume()
        val id = requestId ?: return
        val url = browser.authorizationUrl(id)
        if (url == null) { finish(); return }
        if (started) {
            // Returning from the browser without the exact callback is a user cancellation.
            browser.cancel(id)
            finish()
            return
        }
        started = true
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            browser.unavailable(id)
            finish()
        } catch (_: SecurityException) {
            browser.unavailable(id)
            finish()
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean(STARTED, started)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        requestId?.let { id ->
            browser.detach(id)
            if (!isChangingConfigurations) browser.cancel(id)
        }
        super.onDestroy()
    }

    internal companion object {
        const val REQUEST_ID = "apple_oauth_request"
        private const val STARTED = "apple_oauth_browser_started"
    }
}

/** Exported callback only routes a matching process-owned flow; it does not initialize accounts. */
class AppleOAuthCallbackActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val callback = if (intent.action == Intent.ACTION_VIEW) intent.dataString else null
        // Do not retain the authorization code in this Activity's Intent or saved state.
        intent = Intent()
        callback?.let(AppleOAuthBrowserBroker.process::callback)
        finish()
    }
}
