package com.lavasec.app.presentation.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lavasec.app.designsystem.LocalLavaSemanticColors
import com.lavasec.app.designsystem.LavaNavigationCard
import com.lavasec.app.designsystem.LavaPanel
import com.lavasec.app.designsystem.LavaSpacing
import com.lavasec.app.presentation.R
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal enum class ActivityDateEndpoint { Start, End }

internal val ActivityDatePickerDisplayedMonthMillisKey =
    SemanticsPropertyKey<Long>("ActivityDatePickerDisplayedMonthMillis")

internal data class ActivityDateDraftState(
    val draft: ActivityDateRangeDraft,
    val endpoint: ActivityDateEndpoint = ActivityDateEndpoint.Start,
)

internal data class ActivityDatePickerTransport(
    val selectedDate: LocalDate,
    val selectedDateMillis: Long,
    val displayedMonthMillis: Long,
    val focusEndpoint: ActivityDateEndpoint,
)

internal fun activityDatePickerTransport(state: ActivityDateDraftState): ActivityDatePickerTransport {
    val selectedDate = when (state.endpoint) {
        ActivityDateEndpoint.Start -> state.draft.start
        ActivityDateEndpoint.End -> state.draft.endInclusive
    }
    return ActivityDatePickerTransport(
        selectedDate = selectedDate,
        selectedDateMillis = localDateToUtcMillis(selectedDate),
        displayedMonthMillis = localDateToUtcMillis(selectedDate.withDayOfMonth(1)),
        focusEndpoint = state.endpoint,
    )
}

/** Pure date-dialog state machine. UI transport never owns local-day authority. */
internal object ActivityDateDraftReducer {
    fun select(state: ActivityDateDraftState, endpoint: ActivityDateEndpoint) = state.copy(endpoint = endpoint)

    fun choose(state: ActivityDateDraftState, date: LocalDate): ActivityDateDraftState {
        val old = state.draft
        val next = when (state.endpoint) {
            ActivityDateEndpoint.Start -> ActivityDateRangeDraft(date, maxOf(date, old.endInclusive), old.today)
            ActivityDateEndpoint.End -> ActivityDateRangeDraft(minOf(old.start, date), maxOf(old.start, date), old.today)
        }
        return ActivityDateDraftState(
            draft = next,
            endpoint = if (state.endpoint == ActivityDateEndpoint.Start) ActivityDateEndpoint.End else ActivityDateEndpoint.Start,
        )
    }

    fun reset(state: ActivityDateDraftState) = ActivityDateDraftState(
        draft = ActivityDateRangeDraft(state.draft.today, state.draft.today, state.draft.today),
        endpoint = ActivityDateEndpoint.Start,
    )
}

/** A validated, local-day draft. It deliberately has no UTC-millis authority. */
internal data class ActivityDateRangeDraft(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val today: LocalDate,
) {
    init {
        require(!start.isAfter(endInclusive))
        require(!endInclusive.isAfter(today))
        require(!start.isBefore(YearMonth.from(today).minusMonths(23).atDay(1)))
    }
}

@Composable
internal fun ActivityOverviewContent(
    state: ActivityUiState,
    onOpenTopDomains: () -> Unit,
    onOpenDomainHistory: () -> Unit,
    queryCallbacks: ActivityQueryCallbacks?,
) {
    when (val overview = state.overview) {
        ActivityContentState.Unavailable -> UnavailableOverview()
        ActivityContentState.Disabled -> OverviewMessage(
            title = stringResource(R.string.activity_disabled_title),
            detail = stringResource(R.string.activity_disabled_detail),
            tag = "activity_disabled_panel",
        )
        is ActivityContentState.Loading -> {
            val lastGood = overview.lastGood
            if (lastGood == null) {
                LoadingOverview()
            } else {
                OverviewSnapshotContent(lastGood, onOpenTopDomains, onOpenDomainHistory, queryCallbacks)
                RefreshingBanner()
            }
        }
        is ActivityContentState.Failed -> {
            val lastGood = overview.lastGood
            if (lastGood == null) {
                OverviewMessage(
                    title = stringResource(R.string.activity_failed_title),
                    detail = overview.message,
                    tag = "activity_failed_panel",
                    onRetry = queryCallbacks?.onRefreshSummary,
                )
            } else {
                OverviewSnapshotContent(lastGood, onOpenTopDomains, onOpenDomainHistory, queryCallbacks)
                OverviewMessage(
                    title = stringResource(R.string.activity_refresh_failed_title),
                    detail = overview.message,
                    tag = "activity_refresh_failed_panel",
                    onRetry = queryCallbacks?.onRefreshSummary,
                )
            }
        }
        is ActivityContentState.Ready -> OverviewSnapshotContent(
            overview.value,
            onOpenTopDomains,
            onOpenDomainHistory,
            queryCallbacks,
        )
    }
}

@Composable
private fun UnavailableOverview() {
    LavaPanel(modifier = Modifier.testTag("activity_unavailable_panel")) {
        Text(stringResource(R.string.activity_live_unavailable))
        Text(stringResource(R.string.activity_live_unavailable_detail))
    }
}

@Composable
private fun LoadingOverview() {
    LavaPanel(modifier = Modifier.testTag("activity_initial_loading_panel")) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(stringResource(R.string.activity_loading))
    }
}

@Composable
private fun RefreshingBanner() {
    LavaPanel(modifier = Modifier.testTag("activity_refreshing_panel")) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.activity_refreshing))
    }
}

@Composable
private fun OverviewMessage(
    title: String,
    detail: String,
    tag: String,
    onRetry: (() -> Unit)? = null,
) {
    LavaPanel(modifier = Modifier.testTag(tag)) {
        Text(title)
        Text(detail)
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("activity_retry_button"),
            ) { Text(stringResource(R.string.activity_retry)) }
        }
    }
}

@Composable
private fun OverviewSnapshotContent(
    snapshot: ActivityOverviewSnapshot,
    onOpenTopDomains: () -> Unit,
    onOpenDomainHistory: () -> Unit,
    queryCallbacks: ActivityQueryCallbacks?,
) {
    if (queryCallbacks != null) {
        ActivityDateControl(snapshot.dateRange, queryCallbacks.onDateRangeSelected)
        Button(
            onClick = queryCallbacks.onRefreshSummary,
            modifier = Modifier.testTag("activity_refresh_button"),
        ) { Text(stringResource(R.string.activity_refresh)) }
    } else {
        Text(
            text = formatActivityDateRange(snapshot.dateRange, stringResource(R.string.activity_today)),
            modifier = Modifier.testTag("activity_date_range_read_only"),
        )
    }
    // The date-control information hierarchy (iOS DiagnosticsDateControls.swift parity):
    // the control carries the current range, and this quiet line discloses the fixed
    // 24-month window and the device timezone the picker and lens both honor. It is
    // copy, not a second policy — the picker bounds remain the single source of truth.
    Text(
        text = stringResource(
            R.string.activity_date_scope_detail,
            snapshot.dateRange.zoneId,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("activity_date_scope_detail"),
    )

    val counts = snapshot.counts
    if (counts == null) {
        OverviewMessage(
            title = stringResource(R.string.activity_counts_unavailable_title),
            detail = stringResource(R.string.activity_counts_unavailable_detail),
            tag = "activity_counts_unavailable_panel",
        )
    } else {
        CountsPanel(counts, canAdjustDates = queryCallbacks != null)
    }

    UptimePanel(snapshot.localProtectionUptimeMillis)
    LavaNavigationCard(
        title = stringResource(R.string.activity_top_domains_title),
        summary = stringResource(R.string.activity_top_domains_summary),
        modifier = Modifier.testTag("activity_open_top_domains"),
        onClick = onOpenTopDomains,
    )
    LavaNavigationCard(
        title = stringResource(R.string.activity_domain_history_title),
        summary = stringResource(R.string.activity_domain_history_summary),
        modifier = Modifier.testTag("activity_open_domain_history"),
        onClick = onOpenDomainHistory,
    )
    Text(stringResource(R.string.activity_privacy_detail))
    Text(
        text = stringResource(R.string.activity_clear_scope_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("activity_clear_scope"),
    )
}

@Composable
private fun CountsPanel(counts: ActivityCountSnapshot, canAdjustDates: Boolean) {
    LavaPanel(modifier = Modifier.testTag("activity_counts_panel")) {
        Text(stringResource(R.string.activity_total, formatActivityCount(counts.total)))
        ActivityFlowBar(counts)
        Text(stringResource(R.string.activity_allowed, formatActivityCount(BigInteger.valueOf(counts.allowed)), formatActivityPercentage(counts.allowedShare)))
        Text(stringResource(R.string.activity_blocked, formatActivityCount(BigInteger.valueOf(counts.blocked)), formatActivityPercentage(counts.blockedShare)))
        if (counts.total == BigInteger.ZERO) {
            // The empty-state explanation: an honest zero for the selected range, plus the
            // actionable hint only when the date control is actually present.
            Text(stringResource(R.string.activity_empty))
            if (canAdjustDates) {
                Text(
                    text = stringResource(R.string.activity_empty_adjust_dates),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("activity_empty_adjust_dates"),
                )
            }
        }
    }
}

@Composable
private fun ActivityFlowBar(counts: ActivityCountSnapshot) {
    val total = counts.total
    val allowedLabel = formatActivityCount(BigInteger.valueOf(counts.allowed))
    val blockedLabel = formatActivityCount(BigInteger.valueOf(counts.blocked))
    val flowDescription = stringResource(R.string.activity_flow_bar_description, allowedLabel, blockedLabel)
    val modifier = Modifier
        .fillMaxWidth()
        .height(12.dp)
        .semantics {
            contentDescription = flowDescription
        }
        .testTag("activity_flow_bar")
    val semanticColors = LocalLavaSemanticColors.current
    val track = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .testTag("activity_flow_track")
    if (total == BigInteger.ZERO) {
        Box(modifier) { Box(track) }
        return
    }
    BoxWithConstraints(modifier) {
        Box(track)
        val hasAllowed = counts.allowed > 0L
        val hasBlocked = counts.blocked > 0L
        val geometry = flowBarGeometry(counts.allowed, counts.blocked, total, maxWidth)
        Row(Modifier.fillMaxWidth()) {
            if (hasAllowed) {
                Box(
                    Modifier
                        .width(geometry.allowedWidth)
                        .height(12.dp)
                        .background(semanticColors.safe)
                        .testTag("activity_flow_allowed_segment"),
                )
            }
            if (geometry.gapWidth > 0.dp) Spacer(Modifier.width(geometry.gapWidth))
            if (hasBlocked) {
                Box(
                    Modifier
                        .width(geometry.blockedWidth)
                        .height(12.dp)
                        .background(semanticColors.danger)
                        .testTag("activity_flow_blocked_segment"),
                )
            }
        }
    }
}

internal data class ActivityFlowGeometry(
    val allowedWidth: androidx.compose.ui.unit.Dp,
    val blockedWidth: androidx.compose.ui.unit.Dp,
    val gapWidth: androidx.compose.ui.unit.Dp,
)

internal fun flowBarGeometry(
    allowed: Long,
    blocked: Long,
    total: BigInteger,
    maxWidth: androidx.compose.ui.unit.Dp,
): ActivityFlowGeometry {
    if (total == BigInteger.ZERO) return ActivityFlowGeometry(0.dp, 0.dp, 0.dp)
    if (blocked == 0L) return ActivityFlowGeometry(maxWidth, 0.dp, 0.dp)
    if (allowed == 0L) return ActivityFlowGeometry(0.dp, maxWidth, 0.dp)
    val gap = 3.dp
    val available = (maxWidth - gap).coerceAtLeast(0.dp)
    val fraction = BigDecimal.valueOf(blocked)
        .divide(BigDecimal(total), 8, RoundingMode.HALF_UP)
        .toFloat()
    val blockedWidth = maxOf(10.dp, available * fraction)
        .coerceAtMost((available - 1.dp).coerceAtLeast(0.dp))
    return ActivityFlowGeometry(available - blockedWidth, blockedWidth, gap)
}

@Composable
private fun UptimePanel(uptimeMillis: Long?) {
    LavaPanel(modifier = Modifier.testTag("activity_uptime_panel")) {
        if (uptimeMillis == null) Text(stringResource(R.string.activity_uptime_unavailable))
        else ActivityUptimeText(uptimeMillis)
    }
}

@Composable
private fun ActivityUptimeText(millis: Long) {
    val localizedParts = mutableListOf<String>()
    for (part in activityUptimeParts(millis)) {
        val resource = when (part.unit) {
            ActivityUptimeUnit.Day -> R.plurals.activity_uptime_days
            ActivityUptimeUnit.Hour -> R.plurals.activity_uptime_hours
            ActivityUptimeUnit.Minute -> R.plurals.activity_uptime_minutes
        }
        localizedParts += androidx.compose.ui.res.pluralStringResource(
            resource,
            part.value.toInt(),
            formatActivityCount(BigInteger.valueOf(part.value)),
        )
    }
    Text(stringResource(R.string.activity_uptime_protected_locally, localizedParts.joinToString(" ")))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityDateControl(
    range: ActivityDateRangeUi,
    onDateRangeSelected: (LocalDate, LocalDate) -> Unit,
) {
    var showingDialog by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showingDialog = true },
        modifier = Modifier.testTag("activity_date_range_control"),
    ) { Text(stringResource(R.string.activity_change_dates, formatActivityDateRange(range, stringResource(R.string.activity_today)))) }
    if (!showingDialog) return

    var dateState by remember(range) {
        mutableStateOf(ActivityDateDraftState(ActivityDateRangeDraft(range.start, range.endInclusive, range.today)))
    }
    var pickerSyncRevision by remember(range) { mutableIntStateOf(0) }
    val startFocus = remember { FocusRequester() }
    val endFocus = remember { FocusRequester() }
    val draft = dateState.draft
    val transport = activityDatePickerTransport(dateState)
    val editingStart = transport.focusEndpoint == ActivityDateEndpoint.Start
    val selectedDate = transport.selectedDate
    val pickerMinimumDate = YearMonth.from(range.today).minusMonths(23).atDay(1)
    val pickerMaximumDate = range.today
    val pickerYearRange = pickerMinimumDate.year..pickerMaximumDate.year
    val pickerState = key(pickerMinimumDate, pickerMaximumDate) {
        rememberDatePickerState(
            initialSelectedDateMillis = transport.selectedDateMillis,
            yearRange = pickerYearRange,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = utcMillisToLocalDate(utcTimeMillis)
                    return date >= pickerMinimumDate && date <= pickerMaximumDate
                }
            },
        )
    }
    LaunchedEffect(transport, pickerSyncRevision) {
        if (pickerState.selectedDateMillis != transport.selectedDateMillis) {
            pickerState.selectedDateMillis = transport.selectedDateMillis
        }
        if (pickerState.displayedMonthMillis != transport.displayedMonthMillis) {
            pickerState.displayedMonthMillis = transport.displayedMonthMillis
        }
    }
    LaunchedEffect(transport.focusEndpoint) {
        if (editingStart) startFocus.requestFocus() else endFocus.requestFocus()
    }
    LaunchedEffect(pickerState.selectedDateMillis) {
        val selected = pickerState.selectedDateMillis?.let(::utcMillisToLocalDate) ?: return@LaunchedEffect
        if (selected == selectedDate) return@LaunchedEffect
        dateState = ActivityDateDraftReducer.choose(dateState, selected)
    }
    AlertDialog(
        onDismissRequest = { showingDialog = false },
        title = { Text(stringResource(R.string.activity_date_dialog_title)) },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("activity_date_dialog_scroll"),
                verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm),
            ) {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(LavaSpacing.sm)) {
                    OutlinedButton(
                        onClick = {
                            dateState = ActivityDateDraftReducer.select(dateState, ActivityDateEndpoint.Start)
                            pickerSyncRevision += 1
                        },
                        modifier = Modifier
                            .focusRequester(startFocus)
                            .semantics { selected = editingStart }
                            .testTag("activity_date_start"),
                    ) { Text(stringResource(R.string.activity_date_start) + ": " + formatActivityDateRange(ActivityDateRangeUi(draft.start, draft.start, range.today, range.zoneId), stringResource(R.string.activity_today))) }
                    OutlinedButton(
                        onClick = {
                            dateState = ActivityDateDraftReducer.select(dateState, ActivityDateEndpoint.End)
                            pickerSyncRevision += 1
                        },
                        modifier = Modifier
                            .focusRequester(endFocus)
                            .semantics { selected = !editingStart }
                            .testTag("activity_date_end"),
                    ) { Text(stringResource(R.string.activity_date_end) + ": " + formatActivityDateRange(ActivityDateRangeUi(draft.endInclusive, draft.endInclusive, range.today, range.zoneId), stringResource(R.string.activity_today))) }
                }
                DatePicker(
                    state = pickerState,
                    modifier = Modifier
                        .testTag("activity_date_picker")
                        .semantics {
                            this[ActivityDatePickerDisplayedMonthMillisKey] = pickerState.displayedMonthMillis
                        },
                )
                OutlinedButton(
                    onClick = {
                        dateState = ActivityDateDraftReducer.reset(dateState)
                        pickerSyncRevision += 1
                    },
                    modifier = Modifier.testTag("activity_date_reset"),
                ) { Text(stringResource(R.string.activity_reset_dates)) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDateRangeSelected(draft.start, draft.endInclusive)
                    showingDialog = false
                },
                modifier = Modifier.testTag("activity_date_show"),
            ) { Text(stringResource(R.string.activity_show)) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { showingDialog = false },
                modifier = Modifier.testTag("activity_date_close"),
            ) { Text(stringResource(R.string.activity_close)) }
        },
    )
}

internal fun localDateToUtcMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun utcMillisToLocalDate(millis: Long): LocalDate =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

internal fun formatActivityPercentage(value: ActivityPercentageUi): String = when (value) {
    ActivityPercentageUi.Zero -> "0%"
    ActivityPercentageUi.LessThanOne -> "<1%"
    is ActivityPercentageUi.Rounded -> "${value.wholePercent}%"
    ActivityPercentageUi.GreaterThan99 -> ">99%"
    ActivityPercentageUi.OneHundred -> "100%"
}

internal fun formatActivityCount(value: BigInteger, locale: Locale = Locale.getDefault()): String =
    java.text.NumberFormat.getIntegerInstance(locale).format(value)

internal enum class ActivityUptimeUnit { Day, Hour, Minute }
internal data class ActivityUptimePart(val unit: ActivityUptimeUnit, val value: Long)

internal fun activityUptimeParts(millis: Long): List<ActivityUptimePart> {
    require(millis >= 0L)
    var remainingMinutes = millis / 60_000L
    val days = remainingMinutes / (24L * 60L)
    remainingMinutes %= 24L * 60L
    val hours = remainingMinutes / 60L
    val minutes = remainingMinutes % 60L
    val units = buildList {
        if (days > 0L) add(ActivityUptimePart(ActivityUptimeUnit.Day, days))
        if (hours > 0L) add(ActivityUptimePart(ActivityUptimeUnit.Hour, hours))
        if (minutes > 0L || isEmpty()) add(ActivityUptimePart(ActivityUptimeUnit.Minute, minutes))
    }
    return units.take(2)
}

internal fun formatActivityDateRange(
    range: ActivityDateRangeUi,
    todayLabel: String = "Today",
    locale: Locale = Locale.getDefault(),
): String {
    if (range.start == range.today && range.endInclusive == range.today) return todayLabel
    val days = java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1L
    val monthDay = DateTimeFormatter.ofPattern("MMM d", locale)
    val monthDayYear = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
    val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", locale)
    val single = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    return when {
        days > 120L -> "${range.start.format(monthYear)} – ${range.endInclusive.format(monthYear)}"
        range.start == range.endInclusive && range.start.year == range.today.year -> range.start.format(monthDay)
        range.start == range.endInclusive -> range.start.format(monthDayYear)
        range.start.year == range.endInclusive.year && range.start.month == range.endInclusive.month ->
            "${range.start.format(monthDay)}–${range.endInclusive.dayOfMonth}"
        else -> "${range.start.format(if (range.start.year == range.today.year) monthDay else monthDayYear)} – ${range.endInclusive.format(if (range.endInclusive.year == range.today.year) monthDay else monthDayYear)}"
    }
}

/** A failure state is never translated into optimistic success copy. */
internal fun domainActionFailureCopy(state: DomainActionUiState.ApplyFailed): String = state.message
