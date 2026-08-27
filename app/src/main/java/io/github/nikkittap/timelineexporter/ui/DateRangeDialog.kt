package io.github.nikkittap.timelineexporter.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.nikkittap.timelineexporter.R
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Full-screen dialog wrapping Material 3's DateRangePicker.
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
 * @param dataRange the full time span of the parsed data, if loaded. Used
 *   to populate the quick-preset chips and to pre-scroll the calendar to
 *   the relevant month.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(
    initialRange: ClosedRange<Instant>?,
    dataRange: ClosedRange<Instant>?,
    onConfirm: (ClosedRange<Instant>) -> Unit,
    onDismiss: () -> Unit,
) {
    val zoneId = ZoneId.systemDefault()

    // If user hasn't set a filter yet, default the picker to the full data
    // range so they can see what's there and narrow from it.
    val seedRange = initialRange ?: dataRange

    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = seedRange?.start?.let { instantToPickerMillis(it, zoneId) },
        initialSelectedEndDateMillis = seedRange?.endInclusive?.let { instantToPickerMillis(it, zoneId) },
        // Opens the calendar already scrolled to the relevant month.
        initialDisplayedMonthMillis = seedRange?.start?.let { instantToPickerMillis(it, zoneId) },
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = stringResource(R.string.date_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (dataRange != null) {
                    QuickPresetChips(
                        dataRange = dataRange,
                        zoneId = zoneId,
                        onSelect = { (s, e) -> pickerState.setSelection(s, e) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                DateRangePicker(
                    state = pickerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.date_dialog_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val s = pickerState.selectedStartDateMillis
                            val e = pickerState.selectedEndDateMillis
                            if (s != null && e != null) {
                                onConfirm(pickerMillisToLocalDayRange(s, e, zoneId))
                            }
                        },
                        enabled = pickerState.selectedStartDateMillis != null &&
                                pickerState.selectedEndDateMillis != null,
                    ) {
                        Text(stringResource(R.string.date_dialog_apply))
                    }
                }
            }
        }
    }
}

/**
 * Horizontally-scrolling row of "Last 7 days" / "Last 30 days" / etc. chips.
 * Each tap calls [onSelect] with a (start, end) pair in the picker's
 * UTC-midnight-millis representation, suitable for [DateRangePickerState.setSelection].
 *
 * "Last N days" is relative to the DATA's last point, not today — for
 * historical Timeline data, that's what users mean.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickPresetChips(
    dataRange: ClosedRange<Instant>,
    zoneId: ZoneId,
    onSelect: (Pair<Long, Long>) -> Unit,
) {
    // Range pairs are pure data so they're remembered; the localized
    // labels are resolved at composition time via stringResource.
    val ranges = remember(dataRange) {
        listOf(
            lastNDaysPickerRange(dataRange.endInclusive, 7, zoneId),
            lastNDaysPickerRange(dataRange.endInclusive, 30, zoneId),
            lastNDaysPickerRange(dataRange.endInclusive, 90, zoneId),
            instantToPickerMillis(dataRange.start, zoneId) to
                instantToPickerMillis(dataRange.endInclusive, zoneId),
        )
    }
    val labels = listOf(
        stringResource(R.string.date_preset_last_7),
        stringResource(R.string.date_preset_last_30),
        stringResource(R.string.date_preset_last_90),
        stringResource(R.string.date_preset_all),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        ranges.zip(labels).forEach { (range, label) ->
            AssistChip(
                onClick = { onSelect(range) },
                label = { Text(label) },
            )
        }
    }
}

// ---------- date/millis conversion helpers ----------

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
    val startDate = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val endDate = Instant.ofEpochMilli(endMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val startInstant = startDate.atStartOfDay(zoneId).toInstant()
    val endInstant = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().minusNanos(1)
    return startInstant..endInstant
}

/**
 * "Last N days" as a (start, end) pair in picker's UTC-midnight-millis
 * format, anchored at [anchorInstant] interpreted in [zoneId].
 *
 * E.g. anchor=2026-05-21, N=7 -> (2026-05-15, 2026-05-21).
 */
private fun lastNDaysPickerRange(anchorInstant: Instant, days: Int, zoneId: ZoneId): Pair<Long, Long> {
    val endDate = anchorInstant.atZone(zoneId).toLocalDate()
    val startDate = endDate.minusDays((days - 1).toLong())
    return startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to
        endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
