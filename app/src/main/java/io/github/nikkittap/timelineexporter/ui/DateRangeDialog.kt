package io.github.nikkittap.timelineexporter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.nikkittap.timelineexporter.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Full-screen dialog around Material 3's DateRangePicker, with the picker's
 * own header replaced by one of ours.
 *
 * What the stock header did, and why it is gone:
 *  - a grey title band in the picker's own container colour, the one block on
 *    the screen that looked like nothing else in the app;
 *  - a headline that set both ends with their years, which wrapped mid-date
 *    ("Jun 29, / 2026") on an ordinary phone;
 *  - a row of Last 7 / 30 / 90 / All chips repeating the presets step 2
 *    already has, clipped at the right edge.
 *
 * What replaces it: a close icon, the title and Apply in one bar, then a panel
 * with the selection as one localized range and what it holds — the number of
 * days and of points. The point count runs the same filter step 2 runs with
 * only the dates swapped (see [countPoints]), so it is the figure step 2 will
 * show after Apply rather than a second, disagreeing one.
 *
 * Dates can also be typed: a pencil in the bar swaps the calendar for the
 * picker's own two text fields, in the locale's date format, and a calendar
 * icon swaps it back. The toggle sits in the summary panel, near where the
 * stock header we dropped kept it; the top bar has no room for it, German
 * lost the end of its title. Both modes share one state, so the panel follows
 * whatever was typed as soon as it parses.
 *
 * Days outside the file cannot be picked: an export that ends on June 29 has
 * nothing to say about June 30, and letting someone select it only produced an
 * empty track.
 *
 * Conversion rules:
 *  - The picker stores each selected date as the millis at UTC midnight of
 *    that date (i.e. "May 12" -> 2025-05-12T00:00:00Z millis).
 *  - We interpret the user's selection as days in their LOCAL timezone, so
 *    "May 12 to May 19" really means "midnight May 12 local through last
 *    instant of May 19 local". The helpers below do that conversion both ways.
 *
 * @param initialRange the currently-applied filter range, if any. If null,
 *   the picker pre-selects [dataRange] so the user can see what's available.
 * @param dataRange the full time span of the parsed data, if loaded. Bounds
 *   what can be selected and pre-scrolls the calendar to the relevant month.
 * @param countPoints how many points the current filters keep for a given
 *   date range.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(
    initialRange: ClosedRange<Instant>?,
    dataRange: ClosedRange<Instant>?,
    countPoints: (ClosedRange<Instant>) -> Int,
    onConfirm: (ClosedRange<Instant>) -> Unit,
    onDismiss: () -> Unit,
) {
    val zoneId = ZoneId.systemDefault()

    // If user hasn't set a filter yet, default the picker to the full data
    // range so they can see what's there and narrow from it.
    val seedRange = initialRange ?: dataRange

    val dataDays = remember(dataRange, seedRange) {
        dataRange?.let { data ->
            val first = instantToPickerMillis(data.start, zoneId)
            val last = instantToPickerMillis(data.endInclusive, zoneId)
            // The year range has to cover the seeded selection too, or the
            // picker rejects its own initial state: "last 30 days" of a file
            // that starts on January 3 begins in the year before the file.
            val seedFirst = seedRange?.start?.let { instantToPickerMillis(it, zoneId) } ?: first
            val seedLast = seedRange?.endInclusive?.let { instantToPickerMillis(it, zoneId) } ?: last
            DataDays(
                firstMillis = first,
                lastMillis = last,
                years = pickerDate(minOf(first, seedFirst)).year..pickerDate(maxOf(last, seedLast)).year,
            )
        }
    }

    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = seedRange?.start?.let { instantToPickerMillis(it, zoneId) },
        initialSelectedEndDateMillis = seedRange?.endInclusive?.let { instantToPickerMillis(it, zoneId) },
        // Opens the calendar already scrolled to the relevant month.
        initialDisplayedMonthMillis = seedRange?.start?.let { instantToPickerMillis(it, zoneId) },
        yearRange = dataDays?.years ?: DatePickerDefaults.YearRange,
        selectableDates = dataDays ?: DatePickerDefaults.AllDates,
    )

    val start = pickerState.selectedStartDateMillis
    val end = pickerState.selectedEndDateMillis
    val selection = remember(start, end) {
        if (start != null && end != null) pickerMillisToLocalDayRange(start, end, zoneId) else null
    }
    // One pass of the step 2 filter per tap on a day. That is a few
    // milliseconds even on a year of history, and a count that lagged behind
    // the selection would be worse than none.
    val selectedPoints = remember(selection) { selection?.let(countPoints) }

    Dialog(
        onDismissRequest = onDismiss,
        // Drawn under the status and navigation bars, with the content padded
        // back out of them. Fitting the window to the bars instead left the
        // dialog's dim showing through above and below it, as two grey bands
        // framing a screen that is meant to be the whole screen.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Drawing under the bars makes their icons this window's business: left
        // alone they stay white, and vanish against the light theme's surface.
        val view = LocalView.current
        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightSurface
                isAppearanceLightNavigationBars = lightSurface
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.date_dialog_cancel),
                        )
                    }
                    Text(
                        text = stringResource(R.string.date_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp, end = 12.dp),
                    )
                    Button(
                        onClick = { selection?.let(onConfirm) },
                        enabled = selection != null,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(stringResource(R.string.date_dialog_apply))
                    }
                }

                SelectionSummary(
                    startMillis = start,
                    endMillis = end,
                    pointCount = selectedPoints,
                    typing = pickerState.displayMode == DisplayMode.Input,
                    onToggleTyping = {
                        pickerState.displayMode = if (pickerState.displayMode == DisplayMode.Input) {
                            DisplayMode.Picker
                        } else {
                            DisplayMode.Input
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                DateRangePicker(
                    state = pickerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    // Transparent, so the calendar sits on the dialog's own
                    // surface instead of a second grey panel inside it.
                    colors = DatePickerDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

/**
 * The panel above the calendar: the selection, what is in it, and the switch
 * between tapping days and typing them. The switch ends the second line, not
 * the panel: beside the range it took 48 dp from the one line that is long,
 * and Portuguese and Korean wrapped the default two-year range.
 *
 * Both lines are always there. Mid-selection, after the first tap and before
 * the second, the second line holds a non-breaking space rather than nothing,
 * because an empty Text measures shorter than a real line and the calendar
 * would jump up and back down under the user's thumb.
 */
@Composable
private fun SelectionSummary(
    startMillis: Long?,
    endMillis: Long?,
    pointCount: Int?,
    typing: Boolean,
    onToggleTyping: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val first = startMillis?.let(::pickerDate)
    val last = endMillis?.let(::pickerDate)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = when {
                    first == null -> "—"
                    last == null -> stringResource(
                        R.string.date_range_value,
                        formatLocalDayRange(context, first, first),
                        "…",
                    )
                    else -> formatLocalDayRange(context, first, last)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            val details = if (first != null && last != null) {
                val days = (ChronoUnit.DAYS.between(first, last) + 1).toInt()
                val daysText = pluralStringResource(R.plurals.map_day_count, days, days)
                if (pointCount != null) {
                    "$daysText · " + stringResource(R.string.points_count, "%,d".format(pointCount))
                } else {
                    daysText
                }
            } else {
                " "
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Laid out as an icon on a line of text, its button overhanging
                // the line, so the panel stays two lines tall.
                Box(
                    modifier = Modifier
                        .size(width = 24.dp, height = 20.dp)
                        .wrapContentSize(unbounded = true),
                ) {
                    IconButton(onClick = onToggleTyping) {
                        Icon(
                            painter = painterResource(if (typing) R.drawable.ic_calendar else R.drawable.ic_edit),
                            contentDescription = stringResource(
                                if (typing) R.string.date_dialog_pick_dates else R.string.date_dialog_type_dates,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Only the days the file covers can be picked, within the years that hold them. */
@OptIn(ExperimentalMaterial3Api::class)
private class DataDays(
    private val firstMillis: Long,
    private val lastMillis: Long,
    val years: IntRange,
) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in firstMillis..lastMillis
    override fun isSelectableYear(year: Int): Boolean = year in years
}

// ---------- date/millis conversion helpers ----------

/** The calendar date a picker millis value stands for. */
private fun pickerDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * Convert a stored Instant back to the "UTC midnight millis" the picker
 * uses for state, treating the Instant as a moment in [zoneId].
 */
private fun instantToPickerMillis(instant: Instant, zoneId: ZoneId): Long {
    val localDate = instant.atZone(zoneId).toLocalDate()
    return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/**
 * Convert two "UTC midnight millis" values from the picker into a real
 * [ClosedRange] of [Instant]s spanning those local days end-to-end.
 */
private fun pickerMillisToLocalDayRange(
    startMillis: Long,
    endMillis: Long,
    zoneId: ZoneId,
): ClosedRange<Instant> {
    val startDate = pickerDate(startMillis)
    val endDate = pickerDate(endMillis)
    val startInstant = startDate.atStartOfDay(zoneId).toInstant()
    val endInstant = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().minusNanos(1)
    return startInstant..endInstant
}
