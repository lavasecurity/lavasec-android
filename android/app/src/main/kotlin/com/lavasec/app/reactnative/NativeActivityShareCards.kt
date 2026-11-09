package com.lavasec.app.reactnative

import com.lavasec.app.platform.LoggingSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

/** One native aggregate export capability. No caller supplies counters, a URI or an image. */
internal class NativeActivityShareCards(private val newToken: () -> String = { UUID.randomUUID().toString() }) {
    data class Request(
        val token: String, val authority: NativeQueryReadAuthority, val policy: LoggingSettings?,
        val locale: String, val zone: ZoneId, val content: JSONObject,
    )
    private var current: Request? = null
    private var revision = 0L
    @Synchronized
    fun begin(): Long { revoke(); return revision }
    @Synchronized
    fun issue(attempt: Long, authority: NativeQueryReadAuthority, policy: LoggingSettings?, locale: String,
              zone: ZoneId, command: ProductActivityCardQuery, summary: ActivityQueryResult, modules: Int,
              today: LocalDate = LocalDate.now(zone)): Request? {
        if (policy?.keepCounts != true || attempt != revision || NativeQueryReadFence(authority).rejection(authority) != null) return null
        val start = Instant.ofEpochSecond(command.start).atZone(zone).toLocalDate()
        val end = Instant.ofEpochSecond(command.end).atZone(zone).toLocalDate()
        // Native range facts determine period copy. Android's legacy Month lens is a rolling
        // thirty days; never label it "this month" unless it is actually the calendar month.
        val period = when {
            start == today && end == today -> "today"
            start == today.minusDays(6) && end == today -> "week"
            start == today.withDayOfMonth(1) && end == today -> "month"
            else -> "custom"
        }
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.forLanguageTag(locale.replace('_', '-')))
        val first = start.format(format); val last = end.format(format)
        val token = newToken()
        val content = JSONObject().put("token", token).put("payload", PAYLOAD).put("moduleCount", modules)
            .put("chart", "total").put("period", period).put("startLabel", first).put("endLabel", last)
            .put("dateLabel", if (first == last) first else "$first – $last")
            .put("allowed", summary.allowed).put("blocked", summary.blocked).put("uptime", summary.uptime)
        return Request(token, authority, policy, locale, zone, content).also { current = it }
    }
    @Synchronized
    fun accepted(token: String, authority: NativeQueryReadAuthority, policy: LoggingSettings?, locale: String, zone: ZoneId): Request? =
        current?.takeIf { policy?.keepCounts == true && it.token == token && it.policy == policy && it.locale == locale && it.zone == zone &&
            NativeQueryReadFence(it.authority).rejection(authority) == null }
    @Synchronized
    fun revoke(token: String? = null) {
        if (token == null || current?.token == token) { current = null; revision++ }
    }
    companion object { const val PAYLOAD = "https://lavasecurity.app/" }
}
