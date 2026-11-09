package com.lavasec.app.reactnative

import android.app.AlertDialog
import android.widget.DatePicker
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.lavasec.app.data.CatalogLegalNotices
import com.lavasec.app.presentation.R
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Public offline notices and an Activity-bound, cancellable date-range presentation. */
internal class NativeReviewBridge(private val context: ReactApplicationContext) : LifecycleEventListener {
    private var pending: Promise? = null
    private var dialog: AlertDialog? = null
    private val narration = AndroidExploreNarration(context) { host ->
        val activity = host as? LavaReactActivity
        activity != null && context.currentActivity === activity && activity.foreground &&
            !activity.isFinishing && !activity.isDestroyed
    }

    fun initialize() = context.addLifecycleEventListener(this)

    fun legalNotices(): String = reviewLegalNotices(context)

    fun speakDemo(text: String, locale: String, promise: Promise) {
        // Capture before the owned main-queue hop; recreation must not rebind an old caption.
        val host = context.currentActivity as? LavaReactActivity
        if (host == null) promise.resolve(false)
        else narration.speak(text, locale, host) { completed -> promise.resolve(completed) }
    }

    fun stopDemo() = narration.stop()

    fun chooseActivityExport(canShareChart: Boolean, title: String, chart: String, diagnostics: String, logs: String, promise: Promise) {
        val host = context.currentActivity as? LavaReactActivity
        context.runOnUiQueueThread {
            if (host == null || context.currentActivity !== host || !host.foreground || host.isFinishing || host.isDestroyed || pending != null) {
                promise.resolve(null)
                return@runOnUiQueueThread
            }
            val choices = buildList {
                if (canShareChart) add("chart" to chart)
                add("diagnostics" to diagnostics)
                add("logs" to logs)
            }
            pending = promise
            val next = AlertDialog.Builder(host).setTitle(title)
                .setItems(choices.map { it.second }.toTypedArray()) { _, index -> finish(promise, choices[index].first) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish(promise, null) }
                .setOnCancelListener { finish(promise, null) }.create()
            dialog = next
            try { next.show() }
            catch (_: android.view.WindowManager.BadTokenException) { finish(promise, null) }
            catch (_: IllegalStateException) { finish(promise, null) }
        }
    }

    fun pickDates(start: Double, end: Double, promise: Promise) = context.runOnUiQueueThread {
        val activity = context.currentActivity as? LavaReactActivity
        if (activity == null || !activity.foreground || activity.isFinishing || pending != null) {
            promise.resolve(null)
            return@runOnUiQueueThread
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val minimum = today.minusMonths(24)
        val initial = runCatching {
            require(start.isFinite() && end.isFinite() && start <= end)
            val first = Instant.ofEpochSecond(start.toLong()).atZone(zone).toLocalDate()
            val last = Instant.ofEpochSecond(end.toLong()).atZone(zone).toLocalDate()
            require(first >= minimum && last <= today && first <= last)
            first to last
        }.getOrElse {
            promise.reject("activity-date-range-invalid", "Activity date range is invalid.")
            return@runOnUiQueueThread
        }
        pending = promise
        fun showDate(initialDate: LocalDate, lower: LocalDate, title: Int, accepted: (LocalDate) -> Unit) {
            if (pending !== promise) return
            val picker = DatePicker(activity).apply {
                minDate = lower.atStartOfDay(zone).toInstant().toEpochMilli()
                maxDate = today.atStartOfDay(zone).toInstant().toEpochMilli()
                init(initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth, null)
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // API 36's calendar DatePickerDialog omits even a custom dialog title. Put the
                // range stage in the visible content alongside the genuine platform picker.
                addView(TextView(activity).apply {
                    text = activity.getString(title)
                    setTextAppearance(android.R.style.TextAppearance_Material_Headline)
                    val inset = (24 * resources.displayMetrics.density).toInt()
                    setPadding(inset, inset / 2, inset, 0)
                    androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(picker, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val next = AlertDialog.Builder(activity)
                .setView(content)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    picker.clearFocus()
                    if (pending === promise) accepted(LocalDate.of(picker.year, picker.month + 1, picker.dayOfMonth))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finish(promise, null)
                }
                .setOnCancelListener { finish(promise, null) }
                .create()
            dialog = next
            try {
                next.show()
            } catch (_: android.view.WindowManager.BadTokenException) {
                finish(promise, null)
            } catch (_: IllegalStateException) {
                finish(promise, null)
            }
        }
        showDate(initial.first, minimum, R.string.activity_date_start) { first ->
            showDate(maxOf(first, initial.second), first, R.string.activity_date_end) { last ->
                val range = activityDates(first, last, today, zone)
                finish(promise, Arguments.createMap().apply {
                    putDouble("start", range.start.toDouble())
                    putDouble("end", range.end.toDouble())
                    putString("label", range.label)
                    putBoolean("includesToday", range.includesToday)
                })
            }
        }
    }

    private fun finish(expected: Promise, value: Any?) {
        if (pending !== expected) return
        pending = null
        dialog?.dismiss()
        dialog = null
        expected.resolve(value)
    }

    override fun onHostResume() = Unit
    override fun onHostPause() { narration.hostUnavailable(); pending?.let { finish(it, null) } }
    override fun onHostDestroy() { narration.hostUnavailable(); pending?.let { finish(it, null) } }

    fun invalidate() {
        narration.close()
        context.runOnUiQueueThread {
            pending?.let { finish(it, null) }
            context.removeLifecycleEventListener(this)
        }
    }
}

internal data class ReviewActivityDates(val start: Long, val end: Long, val label: String, val includesToday: Boolean)

internal fun activityDates(start: LocalDate, end: LocalDate, today: LocalDate, zone: ZoneId): ReviewActivityDates {
    require(start <= end)
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    return ReviewActivityDates(
        start.atStartOfDay(zone).toEpochSecond(),
        end.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1,
        "${start.format(format)} – ${end.format(format)}",
        today in start..end,
    )
}

internal fun reviewLegalNotices(context: android.content.Context): String {
        val sections = CatalogLegalNotices(context.assets, context::getString).buildSections()
        return JSONObject().apply {
            put("disclaimer", context.getString(R.string.legal_affiliation_disclaimer))
            put("sections", JSONArray().apply {
                sections.forEachIndexed { sectionIndex, section ->
                    put(JSONObject().apply {
                        put("title", section.title)
                        put("notices", JSONArray().apply {
                            section.notices.forEachIndexed { index, notice ->
                                put(JSONObject().apply {
                                    put("id", "legal-$sectionIndex-$index")
                                    put("displayName", notice.displayName)
                                    put("noticeText", notice.noticeText)
                                    put("plannedUse", notice.plannedUse)
                                    notice.sourceUrl?.let { put("sourceURL", it) }
                                    notice.licenseUrl?.let { put("licenseTextURL", it) }
                                })
                            }
                        })
                    })
                }
            })
        }.toString()
    }
