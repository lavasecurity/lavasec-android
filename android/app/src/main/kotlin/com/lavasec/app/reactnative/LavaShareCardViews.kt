package com.lavasec.app.reactnative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.views.view.ReactViewGroup
import com.lavasec.app.product.FilterShareCardQr
import com.lavasec.app.product.FilterShareCardQrEncoder
import java.util.WeakHashMap

/** Ordinary Fabric children own every header/text/spacing pixel. This host only captures them. */
internal class LavaShareCardSurfaceView(context: Context) : ReactViewGroup(context) {
    var token = ""
        set(value) {
            if (field == value) return
            field = value
            LavaShareCardSurfaces.refresh(this)
        }
    var payload = ""
        set(value) {
            if (field == value) return
            field = value
            LavaShareCardSurfaces.refresh(this)
        }
    var ready = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LavaShareCardSurfaces.attach(this)
    }
    override fun onDetachedFromWindow() {
        LavaShareCardSurfaces.detach(this)
        super.onDetachedFromWindow()
    }

    fun capture(token: String, payload: String, activity: LavaReactActivity): Bitmap? {
        if (!ready || this.token != token || this.payload != payload || !isAttachedToWindow ||
            width != 1080 || height != 1350 || visibility != VISIBLE ||
            (context as? ReactContext)?.currentActivity !== activity) return null
        val codes = ArrayList<LavaShareQrView>()
        fun visit(view: View) {
            if (view is LavaShareQrView) codes += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(this)
        val code = codes.singleOrNull() ?: return null
        if (!code.isReadyFor(payload)) return null
        val bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.WHITE)
            draw(Canvas(bitmap))
            if (!ready || this.token != token || this.payload != payload || !isAttachedToWindow ||
                !code.matchesCaptured(bitmap, this)) {
                bitmap.recycle(); null
            } else bitmap
        } catch (_: RuntimeException) { bitmap.recycle(); null }
    }
}

/** Weak mounted-view inventory, never a second payload, credential, or export owner. */
internal object LavaShareCardSurfaces {
    private val views = WeakHashMap<LavaShareCardSurfaceView, Unit>()

    /**
     * Consulted when a surface presents a token this registry no longer holds because its previous
     * entry was withdrawn. Returns the authoritative payload while the token is still an admitted
     * export grant, or null once the grant has been retired. A transient unmount withdraws the
     * registry entry, not the authority's grant, so a remounted surface must be re-admitted instead
     * of leaving Share enabled but refused at the capture boundary. Mirrors iOS
     * `LavaShareCardSurfaceRegistry.reauthorize`.
     */
    var reauthorize: ((String) -> String?)? = null

    fun attach(view: LavaShareCardSurfaceView) = refresh(view)

    fun detach(view: LavaShareCardSurfaceView) { views.remove(view) }

    /** Re-evaluates inventory membership after an attach or a token/payload prop update. */
    fun refresh(view: LavaShareCardSurfaceView) {
        if (view.isAttachedToWindow && admitted(view)) views[view] = Unit else views.remove(view)
    }

    private fun admitted(view: LavaShareCardSurfaceView): Boolean {
        val token = view.token
        if (token.isEmpty()) return true
        // A token-bearing surface is only admitted while the authority is configured and still
        // recognizes the token; a cleared authority (owner invalidation) must not admit.
        val authorize = reauthorize ?: return false
        return authorize(token) == view.payload
    }

    fun capture(token: String, payload: String, activity: LavaReactActivity): Bitmap? =
        views.keys.filter { it.token == token }.singleOrNull()?.capture(token, payload, activity)
}

/** Encoder leaf only. Integer module edges plus an explicit four-module opaque white border. */
internal class LavaShareQrView(context: Context) : View(context) {
    var payload: String = ""
        set(value) { if (field != value) { field = value; qr = FilterShareCardQrEncoder.encode(value); invalidate() } }
    var moduleCount = 0
        set(value) { field = value; invalidate() }
    private var qr: FilterShareCardQr? = null
    private val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false; isFilterBitmap = false }

    fun isReadyFor(value: String): Boolean {
        val code = qr ?: return false
        return isAttachedToWindow && visibility == VISIBLE && payload == value &&
            code.size == moduleCount && width == height && width % (code.size + 8) == 0 &&
            width / (code.size + 8) >= 3
    }

    /** Verify actual exported module/quiet-zone pixels; a clipped/omitted native draw is refused. */
    fun matchesCaptured(bitmap: Bitmap, surface: ViewGroup): Boolean {
        val code = qr ?: return false
        val bounds = android.graphics.Rect(0, 0, width, height)
        surface.offsetDescendantRectToMyCoords(this, bounds)
        if (bounds.left < 0 || bounds.top < 0 || bounds.right > bitmap.width || bounds.bottom > bitmap.height ||
            bounds.width() != width || bounds.height() != height) return false
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, bounds.left, bounds.top, width, height)
        val module = width / (code.size + 8)
        for (y in 0 until height) for (x in 0 until width) {
            val mx = x / module - 4; val my = y / module - 4
            val black = mx in 0 until code.size && my in 0 until code.size && code.modules[my][mx]
            if (pixels[y * width + x] != if (black) Color.BLACK else Color.WHITE) return false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        try {
            // ReactViewGroup permits child overflow. A software export inherits the full
            // ancestor clip, so drawColor must never erase the earlier RN header/stripe.
            canvas.clipRect(0, 0, width, height)
            super.onDraw(canvas)
            canvas.drawColor(Color.WHITE)
            val code = qr ?: return
            if (code.size != moduleCount) return
            val module = minOf(width, height) / (code.size + 8)
            if (module < 3) return
            val side = (code.size + 8) * module
            val left = (width - side) / 2
            val top = (height - side) / 2
            for (y in 0 until code.size) for (x in 0 until code.size) if (code.modules[y][x]) {
                val px = left + (x + 4) * module
                val py = top + (y + 4) * module
                canvas.drawRect(px.toFloat(), py.toFloat(), (px + module).toFloat(), (py + module).toFloat(), paint)
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }
}

internal class LavaShareCardSurfaceManager : ViewGroupManager<LavaShareCardSurfaceView>() {
    override fun getName() = "LavaShareCardSurface"
    override fun createViewInstance(context: ThemedReactContext) = LavaShareCardSurfaceView(context)
    override fun needsCustomLayoutForChildren() = false
    @ReactProp(name = "token") fun token(view: LavaShareCardSurfaceView, value: String?) { view.token = value.orEmpty() }
    @ReactProp(name = "payload") fun payload(view: LavaShareCardSurfaceView, value: String?) { view.payload = value.orEmpty() }
    @ReactProp(name = "ready", defaultBoolean = false) fun ready(view: LavaShareCardSurfaceView, value: Boolean) { view.ready = value }
    override fun onDropViewInstance(view: LavaShareCardSurfaceView) {
        LavaShareCardSurfaces.detach(view)
        view.ready = false; view.token = ""; view.payload = ""
        super.onDropViewInstance(view)
    }
}

internal class LavaShareQrManager : SimpleViewManager<LavaShareQrView>() {
    override fun getName() = "LavaShareQr"
    override fun createViewInstance(context: ThemedReactContext) = LavaShareQrView(context)
    @ReactProp(name = "payload") fun payload(view: LavaShareQrView, value: String?) { view.payload = value.orEmpty() }
    @ReactProp(name = "moduleCount", defaultInt = 0) fun moduleCount(view: LavaShareQrView, value: Int) { view.moduleCount = value }
    override fun onDropViewInstance(view: LavaShareQrView) {
        view.payload = ""; view.moduleCount = 0
        super.onDropViewInstance(view)
    }
}
