package com.lavasec.app.reactnative.components

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import com.facebook.react.ReactApplication
import com.facebook.react.interfaces.fabric.ReactSurface
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.lavasec.app.BuildConfig
import com.lavasec.app.R
import com.lavasec.app.reactnative.LavaReactActivity
import com.lavasec.app.reactnative.TEXT_SIZE_BODY_SCALES
import com.lavasec.app.settings.ProductSettingsStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A real full-size shared QA surface inside the existing admitted native page. */
internal class NativeQaComposition private constructor(
    val activity: LavaReactActivity,
    val id: String,
    private val surface: ReactSurface,
    val owner: DeviceQaPageOwner,
    parentScope: CoroutineScope,
    admitted: () -> Boolean,
    navigate: (String) -> Boolean,
    closePage: () -> Boolean,
) : AutoCloseable {
    private val job = Job(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private var closed = false
    private var started = false
    private val presentation = NativeQaPresentation(activity, owner,
        { !closed && activity.nativeHostAlive && activity.foreground && admitted() }, navigate, closePage)
    val view: View get() = checkNotNull(surface.view)
    private var confirmation: AlertDialog? = null
    private var confirming: DeviceQaPending? = null
    private var canaryJob: Job? = null
    private var canaryFlow: Any? = null
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { presentation.back() }
    }
    init {
        view.visibility = View.INVISIBLE
        presentation.changed = ::publish
        NativeQaSurfaces.register(this)
        activity.onBackPressedDispatcher.addCallback(activity, back)
    }
    fun start() {
        if (started || closed) return
        started = true
        surface.start()
        scope.launch {
            owner.state.collect {
                presentation.refresh()
                val flow = owner.canarySnapshot()
                if (canaryFlow !== flow) {
                    canaryJob?.cancel(); canaryFlow = flow
                    canaryJob = flow?.let { scope.launch { it.collect { presentation.refresh() } } }
                }
            }
        }
    }
    fun belongsTo(host: Activity?) = !closed && BuildConfig.DEBUG && activity === host && activity.nativeHostAlive && activity.foreground
    fun snapshot(): String = JSONObject().put("ownerID", id).put("revision", presentation.revision)
        .put("projection", presentation.snapshot() ?: JSONObject.NULL).toString()
    fun read(): String { presentation.refresh(); return snapshot() }
    fun command(raw: String): String { if (!presentation.send(raw)) presentation.refresh(); return snapshot() }
    fun layout(visitID: String, width: Double, height: Double) = presentation.layout(visitID, width, height)
    private fun publish() {
        if (closed) return
        // A resumed/rotated visit must acknowledge its actual newly mounted shared layout.
        view.visibility = if (presentation.ready) View.VISIBLE else View.INVISIBLE
        back.isEnabled = presentation.ready && presentation.hasDetail() && owner.state.value.pending == null
        showConfirmation()
        val context = (activity.application as ReactApplication).reactHost?.currentReactContext ?: return
        if (context.hasActiveReactInstance()) context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(EVENT, snapshot())
    }
    private fun showConfirmation() {
        val pending = owner.state.value.pending?.takeIf { belongsTo(activity) && owner.isCurrent(it.visit) }
        if (pending === confirming) return
        confirming = null; confirmation?.dismiss(); confirmation = null
        if (pending == null) return
        confirming = pending
        fun current() = !closed && confirming === pending && owner.state.value.pending === pending && owner.isCurrent(pending.visit) && belongsTo(activity)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.device_qa_apply_test_settings)
            .setMessage(R.string.device_qa_applying_settings_does_not_verify_protection_or_complete_a_network_check)
            .setPositiveButton(R.string.device_qa_apply) { _, _ -> if (current()) owner.confirm() }
            .setNegativeButton(R.string.device_qa_cancel) { _, _ -> if (current()) owner.cancelPending() }
            .setOnCancelListener { if (current()) owner.cancelPending() }.create()
        confirmation = dialog
        try { dialog.show() }
        catch (_: android.view.WindowManager.BadTokenException) { if (current()) owner.cancelPending() }
        catch (_: IllegalStateException) { if (current()) owner.cancelPending() }
    }
    override fun close() {
        if (closed) return
        closed = true; confirming = null; confirmation?.dismiss(); confirmation = null
        back.remove(); presentation.close(); NativeQaSurfaces.remove(this)
        scope.cancel(); view.visibility = View.INVISIBLE
        surface.stop()
    }
    companion object {
        const val EVENT = "lavaQaPresentation"
        fun create(activity: Activity?, owner: DeviceQaPageOwner, scope: CoroutineScope,
            admitted: () -> Boolean, navigate: (String) -> Boolean, closePage: () -> Boolean): NativeQaComposition? {
            if (!BuildConfig.DEBUG || activity !is LavaReactActivity) return null
            val id = UUID.randomUUID().toString()
            val preferences = ProductSettingsStore(activity).loadPreferences()
            val props = Bundle().apply {
                putString("ownerID", id); putString("locale", activity.resources.configuration.locales[0].toLanguageTag())
                if (!preferences.matchTextSize) putDouble("textScale", TEXT_SIZE_BODY_SCALES[preferences.textSize.index])
            }
            val surface = (activity.application as ReactApplication).reactHost?.createSurface(activity, "LavaQA", props) ?: return null
            if (surface.view == null) { surface.stop(); return null }
            return NativeQaComposition(activity, id, surface, owner, scope, admitted, navigate, closePage)
        }
    }
}

/** Main-queue registry: the UUID alone cannot rebind a surface to a different Activity. */
internal object NativeQaSurfaces {
    private val surfaces = mutableMapOf<String, NativeQaComposition>()
    fun register(value: NativeQaComposition) { check(BuildConfig.DEBUG); surfaces[value.id] = value }
    fun find(id: String, activity: Activity?): NativeQaComposition? = if (!BuildConfig.DEBUG || id.length > 128) null
        else surfaces[id]?.takeIf { it.belongsTo(activity) }
    fun remove(value: NativeQaComposition) { if (surfaces[value.id] === value) surfaces.remove(value.id) }
}
