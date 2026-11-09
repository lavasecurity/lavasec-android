package com.lavasec.app.product

import android.content.Context
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lavasec.app.R
import com.lavasec.app.foundation.chained.ChainedUpstreamConfParser
import com.lavasec.app.platform.VpnConfigurationEditorRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.CancellationException
import com.lavasec.app.platform.VpnConfigurationPick
import com.lavasec.app.platform.VpnConfigurationPickSource

/** Native name/content editor. Saved secrets are never read into this form or returned to JS.
 * Blank content retains an existing row's credential; importing a file uses the same reviewed
 * native staging path. Dismissal and activity retirement discard the sheet's supplied content.
 */
class AndroidVpnConfigurationPickSource(
    private val context: Context,
    private val registry: ActivityResultRegistry,
) : VpnConfigurationPickSource {

    override suspend fun pick(): VpnConfigurationPick = edit(VpnConfigurationEditorRequest("", false))

    override suspend fun edit(request: VpnConfigurationEditorRequest): VpnConfigurationPick = withContext<VpnConfigurationPick>(Dispatchers.Main.immediate) {
        val activity = context as? ComponentActivity ?: return@withContext VpnConfigurationPick.Unavailable
        if (activity.lifecycle.currentState == Lifecycle.State.DESTROYED) return@withContext VpnConfigurationPick.Unavailable
        val scope = CoroutineScope(currentCoroutineContext())
        suspendCancellableCoroutine<VpnConfigurationPick> { continuation ->
            val name = EditText(context).apply {
                hint = context.getString(R.string.vpn_configuration_name)
                setText(request.name)
                inputType = InputType.TYPE_CLASS_TEXT
                maxLines = 1
            }
            val content = EditText(context).apply {
                hint = context.getString(R.string.vpn_configuration_content)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setSingleLine(false)
                minLines = 6
                typeface = Typeface.MONOSPACE
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val inset = (20 * resources.displayMetrics.density).toInt()
                setPadding(inset, inset, inset, inset)
                addView(name)
                addView(content)
                if (request.exists) addView(TextView(context).apply { setText(R.string.vpn_configuration_keep_content) })
            }
            var importing: Job? = null
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.vpn_configuration_title)
                .setView(ScrollView(context).apply { addView(body) })
                .setPositiveButton(R.string.backup_save, null)
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .setNeutralButton(R.string.vpn_configuration_import, null)
                .create()
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY || event == Lifecycle.Event.ON_STOP && importing?.isActive != true) dialog.dismiss()
            }
            dialog.setOnDismissListener {
                activity.lifecycle.removeObserver(observer)
                importing?.cancel()
                name.text.clear()
                content.text.clear()
                if (continuation.isActive) continuation.resume(VpnConfigurationPick.Cancelled)
            }
            continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            activity.lifecycle.addObserver(observer)
            if (activity.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                activity.lifecycle.removeObserver(observer)
                if (continuation.isActive) continuation.resume(VpnConfigurationPick.Unavailable)
                return@suspendCancellableCoroutine
            }
            dialog.show()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = content.text.toString().trim().takeIf { it.isNotEmpty() }
                if (raw == null && !request.exists) {
                    content.error = context.getString(R.string.vpn_configuration_required)
                } else {
                    val valid = raw == null || runCatching { ChainedUpstreamConfParser.rotation(raw) }.isSuccess
                    if (!valid) content.error = context.getString(R.string.vpn_configuration_invalid)
                    else if (continuation.isActive) {
                        continuation.resume(VpnConfigurationPick.Edited(name.text.toString(), raw))
                        dialog.dismiss()
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (importing?.isActive == true) return@setOnClickListener
                importing = scope.launch {
                    when (val picked = pickDocument()) {
                        is VpnConfigurationPick.Read -> if (continuation.isActive) content.setText(picked.rawConfiguration)
                        VpnConfigurationPick.Unavailable -> if (continuation.isActive) content.error = context.getString(R.string.vpn_configuration_import_failed)
                        is VpnConfigurationPick.ImportFailed -> if (continuation.isActive) content.error = vpnConfigurationImportFailureMessage(context, picked.reason)
                        else -> Unit
                    }
                }
            }
        }
    }

    internal suspend fun pickDocument(): VpnConfigurationPick = withContext<VpnConfigurationPick>(Dispatchers.Main.immediate) {
        val activity = context as? ComponentActivity ?: return@withContext VpnConfigurationPick.Unavailable
        if (activity.lifecycle.currentState == Lifecycle.State.DESTROYED) return@withContext VpnConfigurationPick.Unavailable
        val scope = CoroutineScope(currentCoroutineContext())
        suspendCancellableCoroutine<VpnConfigurationPick> { continuation ->
            var launcher: ActivityResultLauncher<String>? = null
            var reading: Job? = null
            var disposed = false
            lateinit var observer: LifecycleEventObserver
            fun dispose() {
                if (disposed) return
                disposed = true
                activity.lifecycle.removeObserver(observer)
                launcher?.unregister()
                launcher = null
            }
            fun finish(result: VpnConfigurationPick) {
                if (disposed) return
                dispose()
                if (continuation.isActive) continuation.resume(result)
            }
            observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    reading?.cancel()
                    finish(VpnConfigurationPick.Cancelled)
                }
            }
            activity.lifecycle.addObserver(observer)
            continuation.invokeOnCancellation { activity.runOnUiThread { reading?.cancel(); dispose() } }
            if (!continuation.isActive) { dispose(); return@suspendCancellableCoroutine }
            try {
                // The non-lifecycle registry overload can register while RESUMED. A unique key
                // fences a late provider result from any replacement configuration visit.
                launcher = registry.register("lavasec.vpn.configuration.${UUID.randomUUID()}",
                    ActivityResultContracts.GetContent()) { uri ->
                    if (disposed || !continuation.isActive) return@register
                    if (uri == null) finish(VpnConfigurationPick.Cancelled)
                    else reading = scope.launch {
                        val picked = withContext(Dispatchers.IO) {
                            readVpnConfigurationDocument { context.contentResolver.openInputStream(uri) }
                        }
                        finish(picked)
                    }
                }
                launcher?.launch(DOCUMENT_MIME_TYPE)
            } catch (_: Exception) { finish(VpnConfigurationPick.Unavailable) }
        }
    }

    private companion object {
        /** WireGuard `.conf` files are commonly `text/plain` but may be `application/octet-stream`. */
        const val DOCUMENT_MIME_TYPE = "*/*"
    }
}

/** Classifies the actual bounded stream read, not document-provider size or MIME claims. */
internal fun readVpnConfigurationDocument(open: () -> InputStream?): VpnConfigurationPick = try {
    open()?.use { VpnConfigurationPick.Read(StrictWireGuardConfigurationReader.read(it)) }
        ?: VpnConfigurationPick.Unavailable
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: StrictWireGuardConfigurationReader.TooLarge) {
    VpnConfigurationPick.ImportFailed(VpnConfigurationPick.ImportFailureReason.TOO_LARGE)
} catch (_: CharacterCodingException) {
    VpnConfigurationPick.ImportFailed(VpnConfigurationPick.ImportFailureReason.NOT_TEXT)
} catch (_: Exception) {
    VpnConfigurationPick.Unavailable
}

internal fun vpnConfigurationImportFailureMessage(context: Context, reason: VpnConfigurationPick.ImportFailureReason): String =
    context.getString(when (reason) {
        VpnConfigurationPick.ImportFailureReason.TOO_LARGE -> R.string.vpn_configuration_import_too_large
        VpnConfigurationPick.ImportFailureReason.NOT_TEXT -> R.string.vpn_configuration_import_not_text
    })
