package com.lavasec.app.reactnative

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.common.LifecycleState
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.lavasec.app.designsystem.LavaIcons
import com.lavasec.app.designsystem.LavaTheme
import com.lavasec.app.designsystem.LavaSettingsIcons
import com.lavasec.app.designsystem.LocalLavaReducedMotion
import com.lavasec.app.foundation.preferences.GuardianLook
import com.lavasec.app.presentation.mascot.GuardianMascotAnimationPlan
import com.lavasec.app.presentation.mascot.GuardianMascotRenderer
import com.lavasec.app.presentation.mascot.GuardianMascotState
import com.lavasec.app.presentation.mascot.drawOriginalAwakeGuardianForExport
import kotlinx.coroutines.delay

/** Platform leaf only: the canonical RN tree owns layout, semantics and actions. */
internal class LavaDecorationView(context: Context) : FrameLayout(context), LifecycleEventListener {
    var guardianGestures = false
        set(value) { field = value; if (!value) guardianContact.cancel() }
    internal var guardianGestureEmitter: (String) -> Unit = { gesture -> emitGuardianGesture(gesture) }
    var staticExport by mutableStateOf(false)
    var symbol by mutableStateOf("")
    var mood by mutableStateOf("sleeping")
    var tone by mutableStateOf("green")
    var look by mutableStateOf("original")
    var colorScheme by mutableStateOf("")
    var fontPointSize by mutableStateOf(0f)
    var fontWeight by mutableStateOf("semibold")
    var revealEnabled by mutableStateOf(false)
    var revealVisible by mutableStateOf(true)
    var revealX by mutableStateOf(13f)
    var revealY by mutableStateOf(13f)
    var revealRadius by mutableStateOf(22f)
    private var attached by mutableStateOf(false)
    private var windowVisible by mutableStateOf(false)
    private var windowFocused by mutableStateOf(false)
    private var hostActive by mutableStateOf((context as? ReactContext)?.lifecycleState == LifecycleState.RESUMED)
    private val reactContext = context as? ReactContext
    private fun guardianContactEnabled() = guardianGestures && !staticExport && LavaDecorationPolicy.admitsGuardianContact(symbol) &&
        mood != "locked" && attached && windowVisible && windowFocused && hostActive
    private val guardianContact = NativeGuardianContactRecognizer(this, ::guardianContactEnabled) { guardianGestureEmitter(it) }
    private val drawing = ComposeView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setContent {
            // The leaf must observe the same OS Reduce Motion switch as the shared RN tree.
            LavaTheme(darkTheme = LavaDecorationPolicy.darkAppearance(colorScheme, isSystemInDarkTheme())) {
                val kind = LavaDecorationPolicy.drawing(symbol, mood)
                val color = decorationColor(tone, colorScheme)
                when (kind) {
                    LavaDecorationPolicy.Drawing.TRANSPARENT -> Unit
                    LavaDecorationPolicy.Drawing.CATALOG_CONTROL -> Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = NativeCatalogControlShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {}
                    LavaDecorationPolicy.Drawing.GUARDIAN -> if (staticExport) {
                        ComposeCanvas(Modifier.fillMaxSize()) { drawOriginalAwakeGuardianForExport() }
                    } else AnimatedGuardian(
                        mood = mood, look = LavaDecorationPolicy.look(look), modifier = Modifier.fillMaxSize(),
                    )
                    LavaDecorationPolicy.Drawing.BRAND -> ComposeCanvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { LavaDecorationDrawing.drawBrand(it.nativeCanvas, size.width, size.height, color.toArgb()) }
                    }
                    LavaDecorationPolicy.Drawing.RANKING -> ComposeCanvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { LavaDecorationDrawing.drawRanking(it.nativeCanvas, size.width, size.height,
                            color.toArgb(), fontPointSize.dp.toPx()) }
                    }
                    LavaDecorationPolicy.Drawing.LOCKED -> {
                        val secondary = decorationColor("secondary", colorScheme)
                        ComposeCanvas(Modifier.fillMaxSize()) {
                            drawIntoCanvas { LavaDecorationDrawing.drawLocked(it.nativeCanvas, size.width, size.height, density, secondary.toArgb()) }
                        }
                    }
                    LavaDecorationPolicy.Drawing.SYMBOL -> RevealedSymbol(
                        symbol, color, decorationColor("secondary", colorScheme), fontPointSize, fontWeight,
                        revealEnabled, revealVisible, revealX, revealY, revealRadius,
                        attached && windowVisible && windowFocused && hostActive,
                    )
                }
            }
        }
    }
    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren = false
        clipToPadding = false
        addView(drawing)
    }
    // Compose glyphs have no semantics or actions. Keep them out of hover targets so
    // standard View.onHoverEvent can identify the exposed RN ancestor for accessibility.
    // The transparent Guardian leaf owns contact only. RN receives gestures and owns actions.
    override fun onInterceptHoverEvent(event: MotionEvent): Boolean = true
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        if (guardianContactEnabled()) true else super.onInterceptTouchEvent(event)
    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (guardianContact.touch(event)) true else super.onTouchEvent(event)
    internal fun reconcileGuardianContact() { if (!guardianContactEnabled()) guardianContact.cancel() }
    @Suppress("DEPRECATION")
    private fun emitGuardianGesture(gesture: String) {
        if (reactContext?.hasActiveReactInstance() != true) return
        val data = Arguments.createMap().apply { putString("gesture", gesture) }
        reactContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "topGuardianGesture", data)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Fabric can measure a preallocated leaf before inserting it into the window.
        // Compose cannot create its window recomposer until attachment. Yoga owns
        // this leaf's size; do not let FrameLayout measure the Compose child early.
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec),
        )
        if (isAttachedToWindow) measureDrawing(measuredWidth, measuredHeight)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (isAttachedToWindow) {
            measureDrawing(right - left, bottom - top)
            drawing.layout(0, 0, right - left, bottom - top)
        }
    }
    private fun measureDrawing(width: Int, height: Int) {
        drawing.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
    private val layoutDrawing = Runnable {
        if (isAttachedToWindow) {
            measureDrawing(width, height)
            drawing.layout(0, 0, width, height)
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        windowVisible = windowVisibility == View.VISIBLE
        windowFocused = hasWindowFocus()
        hostActive = reactContext?.lifecycleState == LifecycleState.RESUMED
        reactContext?.addLifecycleEventListener(this)
        // Fabric may already have laid out this leaf while it was detached.
        // Post until the Compose child has also received its attachment callback.
        post(layoutDrawing)
    }
    override fun onDetachedFromWindow() {
        attached = false
        guardianContact.cancel()
        reactContext?.removeLifecycleEventListener(this)
        removeCallbacks(layoutDrawing)
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == View.VISIBLE
        reconcileGuardianContact()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Native consent, notification shade and external windows can interrupt a resumed host.
        windowFocused = hasWindowFocus
        reconcileGuardianContact()
    }
    override fun onHostResume() { hostActive = true }
    override fun onHostPause() { hostActive = false; guardianContact.cancel() }
    override fun onHostDestroy() { hostActive = false; guardianContact.cancel() }
}

/** Native Material owns this capsule's fill and shape. The shared search or
 * category control above it retains all semantics and interaction. No blur of
 * this leaf's own pixels can stand in for a supported backdrop material. */
internal object NativeCatalogControlShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.height / 2f)))
}

/** Known symbol roles inherit live app resources unless the shared toolbar pins appearance. */
@Composable
private fun decorationColor(tone: String, appearance: String): Color {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = remember(context, configuration, appearance) {
        if (appearance != "light" && appearance != "dark") context.resources
        else context.createConfigurationContext(Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (appearance == "dark") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }).resources
    }
    return Color(resources.getColor(LavaDecorationPolicy.colorResource(tone), context.theme))
}

/** A dim vector and a normal-colour copy clipped to its own strokes, as in the frozen iOS leaf. */
@Composable
private fun RevealedSymbol(
    symbol: String, color: Color, secondary: Color, pointSize: Float, fontWeight: String,
    enabled: Boolean, visible: Boolean, x: Float, y: Float, radius: Float, active: Boolean,
) {
    val density = LocalDensity.current
    val reduced = LocalLavaReducedMotion.current
    val destination = Offset(x, y)
    val center = remember { Animatable(destination, Offset.VectorConverter) }
    var previous by remember { mutableStateOf(LavaDecorationPolicy.Reveal(false, false, false)) }
    val next = LavaDecorationPolicy.Reveal(enabled, visible, active)
    LaunchedEffect(next, destination, radius, reduced) {
        val animate = LavaDecorationPolicy.animateReveal(previous, next, reduced)
        previous = next
        if (animate) center.animateTo(destination, tween(LavaDecorationPolicy.REVEAL_DURATION_MILLIS, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)))
        else center.snapTo(destination)
    }
    val glyph = if (pointSize > 0f) Modifier.size(pointSize.dp) else Modifier.fillMaxSize()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MaterialSymbol(symbol, if (enabled) secondary else color, glyph)
        if (enabled && visible) Box(Modifier.fillMaxSize().drawWithContent {
            drawIntoCanvas { canvas ->
                val saved = canvas.nativeCanvas.saveLayer(0f, 0f, size.width, size.height, null)
                drawContent()
                LavaDecorationDrawing.applyRevealMask(
                    canvas.nativeCanvas, size.width, size.height,
                    with(density) { center.value.x.dp.toPx() }, with(density) { center.value.y.dp.toPx() },
                    with(density) { radius.dp.toPx() }, with(density) { 5.dp.toPx() },
                )
                canvas.nativeCanvas.restoreToCount(saved)
            }
        }, contentAlignment = Alignment.Center) {
            MaterialSymbol(symbol, color, glyph)
        }
    }
}

/** The packaged static outlines are genuinely weight 350; never synthesize a bold contour. */
@Composable
private fun MaterialSymbol(symbol: String, color: Color, modifier: Modifier) {
    val context = LocalContext.current
    val family = MaterialSymbolGlyphs.font(symbol)
    val typeface = remember(context.assets, family) { MaterialSymbolFonts.load(context, family) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val glyph = MaterialSymbolGlyphs.glyph(symbol)
    ComposeCanvas(modifier) {
        paint.typeface = typeface
        paint.textSize = minOf(size.width, size.height)
        paint.color = color.toArgb()
        val metrics = paint.fontMetrics
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(glyph, size.width / 2f,
                size.height / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
        }
    }
}

private object MaterialSymbolFonts {
    private val fonts = mutableMapOf<String, Typeface>()
    @Synchronized fun load(context: Context, family: String): Typeface =
        fonts.getOrPut(family) { Typeface.createFromAsset(context.assets, "fonts/$family.ttf") }
}

/** Real Canvas drawing shared by the native leaf and its painted-output regressions. */
internal object LavaDecorationDrawing {
    // Frozen Shared/SoftShieldGuardian.swift LavaGuardianShieldShape, 220 x 250 design space.
    fun shieldPath(width: Float, height: Float, scale: Float = 1f): Path {
        val w = minOf(width, height * 220f / 250f)
        val h = w * 250f / 220f
        fun x(v: Float) = width / 2f + (v / 220f - 0.5f) * w * scale
        fun y(v: Float) = height / 2f + (v / 250f - 0.5f) * h * scale
        return Path().apply {
            moveTo(x(110f), y(8f))
            cubicTo(x(141f), y(8f), x(193f), y(31f), x(204f), y(50f))
            cubicTo(x(216f), y(72f), x(209f), y(166f), x(200f), y(187f))
            cubicTo(x(189f), y(211f), x(136f), y(238f), x(119f), y(245f))
            cubicTo(x(113f), y(248f), x(107f), y(248f), x(101f), y(245f))
            cubicTo(x(84f), y(238f), x(31f), y(211f), x(20f), y(187f))
            cubicTo(x(11f), y(166f), x(4f), y(72f), x(16f), y(50f))
            cubicTo(x(27f), y(31f), x(79f), y(8f), x(110f), y(8f))
            close()
        }
    }

    fun drawBrand(canvas: Canvas, width: Float, height: Float, color: Int) {
        canvas.drawPath(shieldPath(width, height), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    /** Frozen LavaTokens.swift LavaRankingGlyph: three ordered bars, not a chart or funnel. */
    fun drawRanking(canvas: Canvas, width: Float, height: Float, color: Int, pointSize: Float = 0f) {
        val size = if (pointSize > 0f) pointSize else minOf(width, height)
        val left = (width - size) / 2f + size * 0.1f
        val top = (height - size) / 2f
        val thickness = size * 0.08f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        listOf(0.8f, 0.56f, 0.32f).forEachIndexed { index, length ->
            val y = top + size * (0.2f + index * 0.3f) - thickness / 2f
            canvas.drawRoundRect(left, y, left + size * length, y + thickness,
                thickness / 2f, thickness / 2f, paint)
        }
    }

    fun drawLocked(canvas: Canvas, width: Float, height: Float, density: Float, color: Int) {
        val size = minOf(width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1.8f * density, size * 0.045f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            pathEffect = DashPathEffect(floatArrayOf(2f * density, 4f * density), 0f)
        }
        canvas.drawPath(shieldPath(width, height, 1.12f), paint)
        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        paint.typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.44f
        val metrics = paint.fontMetrics
        canvas.drawText("?", width / 2f, height / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
    }

    fun applyRevealMask(canvas: Canvas, width: Float, height: Float, x: Float, y: Float, radius: Float, feather: Float) {
        val safeRadius = maxOf(1f, radius)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, safeRadius,
                intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT),
                floatArrayOf(0f, maxOf(0f, 1f - feather / safeRadius), 1f), Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(0f, 0f, width, height, paint)
    }
}

/**
 * Minimal native mascot animation: a mood change plays the ported transition, and
 * an awake mascot blinks on [LavaDecorationPolicy.BLINK_INTERVAL_MILLIS]. Reduced
 * motion snaps straight to the stable frame. Deliberately does NOT reuse
 * [com.lavasec.app.presentation.mascot.GuardianMascotView] because that view adds
 * its own clickable semantics, which a decorative leaf must not (no extra AX stop).
 */
@Composable
private fun AnimatedGuardian(mood: String, look: GuardianLook, modifier: Modifier, staticExport: Boolean = false) {
    val state = LavaDecorationPolicy.moodState(mood)
    val reduced = LocalLavaReducedMotion.current || staticExport
    val progress = remember { Animatable(1f) }
    var plan by remember { mutableStateOf(GuardianMascotAnimationPlan.animation(state, state)) }

    LaunchedEffect(state, reduced) {
        val next = GuardianMascotAnimationPlan.animation(from = plan.endState, to = state)
        plan = next
        if (reduced) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween((next.duration * 1000.0).toInt(), easing = LinearEasing))
    }
    LaunchedEffect(state, reduced) {
        if (reduced || state != GuardianMascotState.AWAKE) return@LaunchedEffect
        while (true) {
            delay(LavaDecorationPolicy.BLINK_INTERVAL_MILLIS)
            val blink = GuardianMascotAnimationPlan.blink(on = state)
            plan = blink
            progress.snapTo(0f)
            progress.animateTo(1f, tween((blink.duration * 1000.0).toInt(), easing = LinearEasing))
        }
    }

    GuardianMascotRenderer(
        frame = plan.frame(progress.value.toDouble() * plan.duration),
        modifier = modifier,
        look = look,
    )
}

internal class LavaDecorationManager : SimpleViewManager<LavaDecorationView>() {
    override fun getName() = "LavaDecoration"
    override fun createViewInstance(context: ThemedReactContext) = LavaDecorationView(context)
    override fun onAfterUpdateTransaction(view: LavaDecorationView) {
        super.onAfterUpdateTransaction(view)
        // Base props can restore AUTO during a Fabric update; this draw-only leaf
        // never owns a semantic stop, including after a reset or native reuse.
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.reconcileGuardianContact()
    }
    @ReactProp(name = "staticExport", defaultBoolean = false) fun staticExport(view: LavaDecorationView, value: Boolean) { view.staticExport = value }
    @ReactProp(name = "guardianGestures", defaultBoolean = false) fun guardianGestures(view: LavaDecorationView, value: Boolean) { view.guardianGestures = value }
    @ReactProp(name = "symbol") fun symbol(view: LavaDecorationView, value: String?) { view.symbol = value.orEmpty() }
    @ReactProp(name = "mood") fun mood(view: LavaDecorationView, value: String?) { view.mood = value ?: "sleeping" }
    @ReactProp(name = "tone") fun tone(view: LavaDecorationView, value: String?) { view.tone = value ?: "green" }
    @ReactProp(name = "look") fun look(view: LavaDecorationView, value: String?) { view.look = value ?: "original" }
    @ReactProp(name = "colorScheme") fun colorScheme(view: LavaDecorationView, value: String?) { view.colorScheme = value.orEmpty() }
    @ReactProp(name = "fontPointSize", defaultFloat = 0f) fun fontPointSize(view: LavaDecorationView, value: Float) { view.fontPointSize = value.takeIf { it.isFinite() && it > 0f } ?: 0f }
    @ReactProp(name = "fontWeight") fun fontWeight(view: LavaDecorationView, value: String?) { view.fontWeight = value ?: "semibold" }
    @ReactProp(name = "revealEnabled", defaultBoolean = false) fun revealEnabled(view: LavaDecorationView, value: Boolean) { view.revealEnabled = value }
    @ReactProp(name = "revealVisible", defaultBoolean = true) fun revealVisible(view: LavaDecorationView, value: Boolean) { view.revealVisible = value }
    @ReactProp(name = "revealX", defaultFloat = 13f) fun revealX(view: LavaDecorationView, value: Float) { view.revealX = value.takeIf { it.isFinite() } ?: 13f }
    @ReactProp(name = "revealY", defaultFloat = 13f) fun revealY(view: LavaDecorationView, value: Float) { view.revealY = value.takeIf { it.isFinite() } ?: 13f }
    @ReactProp(name = "revealRadius", defaultFloat = 22f) fun revealRadius(view: LavaDecorationView, value: Float) { view.revealRadius = value.takeIf { it.isFinite() && it > 0f } ?: 22f }
    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> = mutableMapOf(
        "topGuardianGesture" to mapOf("registrationName" to "onGuardianGesture"),
    )
    override fun onDropViewInstance(view: LavaDecorationView) {
        view.guardianGestures = false
        super.onDropViewInstance(view)
    }
}

internal class LavaSliderManager : SimpleViewManager<SeekBar>() {
    override fun getName() = "LavaSlider"
    override fun createViewInstance(context: ThemedReactContext) = SeekBar(context).apply {
        max = 100
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) emit(view, "topValueChange", "value", progress.toDouble())
            }
            override fun onStartTrackingTouch(view: SeekBar) {
                sliderState(view).tracking = true
                emit(view, "topTrackingChange", "tracking", true)
            }
            override fun onStopTrackingTouch(view: SeekBar) {
                val state = sliderState(view)
                state.tracking = false
                // A value prop that arrived mid-drag was held, so a parent that has
                // not confirmed yet cannot yank the thumb out from under the drag.
                view.progress = state.pending.coerceIn(0, view.max)
                emit(view, "topTrackingChange", "tracking", false)
            }
        })
    }
    @ReactProp(name = "value") fun value(view: SeekBar, value: Double) {
        val state = sliderState(view)
        state.pending = value.toInt().coerceIn(0, view.max.coerceAtLeast(1))
        if (!state.tracking) view.progress = state.pending
    }
    @ReactProp(name = "maximum") fun maximum(view: SeekBar, value: Double) {
        view.max = value.toInt().coerceAtLeast(1)
        val state = sliderState(view)
        state.pending = state.pending.coerceIn(0, view.max)
        if (!state.tracking) view.progress = state.pending
    }
    @ReactProp(name = "disabled") fun disabled(view: SeekBar, value: Boolean) { view.isEnabled = !value }
    @ReactProp(name = "label") fun label(view: SeekBar, value: String?) { view.contentDescription = value }
    @ReactProp(name = "tintColor", customType = "Color") fun tintColor(view: SeekBar, color: Int?) {
        if (color == null) return
        view.progressTintList = ColorStateList.valueOf(color)
        view.thumbTintList = ColorStateList.valueOf(color)
    }
    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> = mutableMapOf(
        "topValueChange" to mapOf("registrationName" to "onValueChange"),
        "topTrackingChange" to mapOf("registrationName" to "onTrackingChange"),
    )
    @Suppress("DEPRECATION")
    private fun emit(view: SeekBar, event: String, key: String, value: Any) {
        val data = Arguments.createMap().apply {
            if (value is Boolean) putBoolean(key, value) else putDouble(key, value as Double)
        }
        (view.context as ReactContext).getJSModule(RCTEventEmitter::class.java).receiveEvent(view.id, event, data)
    }
}

/** Per-view drag bookkeeping held on the SeekBar tag; never a manager-level field. */
private class SliderState {
    var tracking = false
    var pending = 0
}

private fun sliderState(view: SeekBar): SliderState {
    val existing = view.tag as? SliderState
    if (existing != null) return existing
    return SliderState().also { view.tag = it }
}
