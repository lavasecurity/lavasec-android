package com.lavasec.app.reactnative

import android.content.Context
import android.view.View
import com.facebook.react.views.view.ReactViewGroup
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only factory product. Observe root measurements without changing RN layout or recycling. */
internal class NativeMeasuredReactView(context: Context) : ReactViewGroup(context) {
    private var traceTag = View.NO_ID
    private var history: ArrayDeque<JSONObject>? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        record("measure", MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec),
            MeasureSpec.getMode(widthMeasureSpec), MeasureSpec.getMode(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        record("layout", right - left, bottom - top, null, null)
    }

    private fun record(stage: String, requestedWidth: Int, requestedHeight: Int, widthMode: Int?, heightMode: Int?) {
        val marker = getTag(com.facebook.react.R.id.react_test_id)
        if (marker != "lava-window-viewport" && marker != "lava-render-frame") return
        if (traceTag != id) { traceTag = id; history = null }
        val rows = history ?: ArrayDeque<JSONObject>().also { history = it }
        val parentView = parent as? View
        val row = JSONObject().put("stage", stage).put("requestedWidth", requestedWidth).put("requestedHeight", requestedHeight)
            .put("widthMode", widthMode ?: JSONObject.NULL).put("heightMode", heightMode ?: JSONObject.NULL)
            .put("width", width).put("height", height).put("measuredWidth", measuredWidth).put("measuredHeight", measuredHeight)
            .put("parentClass", parentView?.javaClass?.name ?: JSONObject.NULL)
            .put("parentWidth", parentView?.width ?: JSONObject.NULL).put("parentHeight", parentView?.height ?: JSONObject.NULL)
            .put("parentMeasuredWidth", parentView?.measuredWidth ?: JSONObject.NULL)
            .put("parentMeasuredHeight", parentView?.measuredHeight ?: JSONObject.NULL)
        // Capture fixed code locations, never view contents, props or a throwable message.
        row.put("callers", JSONArray(Throwable().stackTrace.drop(2).take(12).map {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }))
        if (rows.size == 12) rows.removeFirst()
        rows.addLast(row)
    }

    fun measurementTrace(): JSONArray = JSONArray(if (traceTag == id) history?.toList().orEmpty() else emptyList<JSONObject>())
    fun clearMeasurementTrace() { history = null; traceTag = View.NO_ID }
}
