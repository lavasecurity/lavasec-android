package com.lavasec.app.reactnative

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

internal data class NativeWindowFold(val bounds: Rect, val vertical: Boolean, val separating: Boolean, val occluding: Boolean)

/** Window coordinates, including zero-width separating creases. Choose one usable pane;
 * no control or editor spans an occluding hinge. Horizontal folds retain the upright
 * top pane when usable; vertical folds choose by area, with a stable left-pane tie. */
internal fun usableWindowPane(window: Rect, folds: List<NativeWindowFold>): Rect {
    // Normalize the geometry before partitioning so a partial feature cannot make
    // equivalent platform feature lists depend on their reporting order.
    val ordered = folds.filter { it.separating || it.occluding }.sortedWith(
        compareBy<NativeWindowFold>({ !it.vertical }, { it.bounds.top }, { it.bounds.left },
            { it.bounds.bottom }, { it.bounds.right }))
    var panes = listOf(Rect(window))
    for (fold in ordered) {
        val bounds = fold.bounds
        panes = panes.flatMap { pane ->
            val intersects = bounds.bottom > pane.top && bounds.top < pane.bottom &&
                bounds.right > pane.left && bounds.left < pane.right
            if (!intersects) listOf(pane)
            else if (fold.vertical) {
                listOf(Rect(pane.left, pane.top, bounds.left.coerceIn(pane.left, pane.right), pane.bottom),
                    Rect(bounds.right.coerceIn(pane.left, pane.right), pane.top, pane.right, pane.bottom))
            } else {
                listOf(Rect(pane.left, pane.top, pane.right, bounds.top.coerceIn(pane.top, pane.bottom)),
                    Rect(pane.left, bounds.bottom.coerceIn(pane.top, pane.bottom), pane.right, pane.bottom))
            }
        }.filterNot { it.isEmpty }
    }
    fun area(pane: Rect) = pane.width().toLong() * pane.height()
    val ranked = panes.sortedWith(compareByDescending<Rect> { area(it) }.thenBy { it.top }.thenBy { it.left })
    val largest = ranked.firstOrNull() ?: return Rect(window.left, window.top, window.left, window.top)
    // Preserve the upright tabletop preference only for a substantial top pane.
    // Occlusion alone carries no posture preference; a clipped sliver never wins.
    return if (ordered.any { !it.vertical && it.separating })
        ranked.firstOrNull { it.top == window.top && area(it) * 2 >= area(largest) } ?: largest
    else largest
}

/** Fits only the React content. The native privacy cover continues to cover the whole window.
 * Content bounds are measured after system/IME resizing; this owner never applies insets. */
internal class NativeWindowViewport(
    private val content: FrameLayout,
    private val root: View,
    private val controlsPane: (Rect) -> Unit = {},
) : AutoCloseable {
    private var folds: List<NativeWindowFold> = emptyList()
    private var closed = false
    private val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
    init { content.addOnLayoutChangeListener(listener) }
    fun update(value: List<NativeWindowFold>) { if (!closed) { folds = value; apply() } }
    private fun apply() {
        if (closed || content.width <= 0 || content.height <= 0 || root.parent !== content) return
        val origin = IntArray(2); content.getLocationInWindow(origin)
        val available = Rect(origin[0], origin[1], origin[0] + content.width, origin[1] + content.height)
        val pane = usableWindowPane(available, folds)
        val left = pane.left - available.left; val top = pane.top - available.top
        val right = available.right - pane.right; val bottom = available.bottom - pane.bottom
        controlsPane(Rect(left, top, content.width - right, content.height - bottom))
        val parameters = root.layoutParams as? FrameLayout.LayoutParams ?: return
        if (parameters.leftMargin == left && parameters.topMargin == top && parameters.rightMargin == right && parameters.bottomMargin == bottom) return
        parameters.gravity = Gravity.TOP or Gravity.LEFT
        parameters.setMargins(left, top, right, bottom)
        root.layoutParams = parameters
    }
    override fun close() { closed = true; folds = emptyList(); content.removeOnLayoutChangeListener(listener) }
}
