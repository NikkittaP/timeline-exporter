package io.github.nikitapetroff.timelineexporter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(
    initialRange: ClosedRange<Instant>?,
    onConfirm: (ClosedRange<Instant>) -> Unit,
    onDismiss: () -> Unit,
) {
    val zoneId = ZoneId.systemDefault()
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialRange?.start?.let { instantToPickerMillis(it, zoneId) },
        initialSelectedEndDateMillis = initialRange?.endInclusive?.let { instantToPickerMillis(it, zoneId) },
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = "Filter by date",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(16.dp))

                DateRangePicker(
                    state = pickerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
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
                    ) { Text("Apply") }
                }
            }
        }
    }
}

/**
 * Convert a stored Instant back to the "UTC midnight millis" the picker
 * uses for state, treating the Instant as a moment in [zoneId].
 *
 * Example: Instant 2025-05-12T05:00:00Z, zoneId Asia/Tashkent (UTC+5)
 *   -> LocalDate(2025-05-12) in that zone
 *   -> millis of 2025-05-12T00:00:00Z (UTC) = the picker's representation
 */
private fun instantToPickerMillis(instant: Instant, zoneId: ZoneId): Long {
    val localDate = LocalDate.ofInstant(instant, zoneId)
    return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/**
 * Convert two "UTC midnight millis" values from the picker into a real
 * [ClosedRange] of [Instant]s spanning those local days end-to-end.
 *
 * Example: startMillis = 2025-05-12T00:00:00Z, endMillis = 2025-05-12T00:00:00Z,
 * zoneId Asia/Tashkent
 *   -> LocalDate(2025-05-12) for both
 *   -> 2025-05-12T00:00:00+05  ..  2025-05-12T23:59:59.999999999+05
 *   -> Instant 2025-05-11T19:00:00Z  ..  Instant 2025-05-12T18:59:59.999...Z
 */
private fun pickerMillisToLocalDayRange(
    startMillis: Long,
    endMillis: Long,
    zoneId: ZoneId,
): ClosedRange<Instant> {
    val startDate = LocalDate.ofInstant(Instant.ofEpochMilli(startMillis), ZoneOffset.UTC)
    val endDate = LocalDate.ofInstant(Instant.ofEpochMilli(endMillis), ZoneOffset.UTC)
    val startInstant = startDate.atStartOfDay(zoneId).toInstant()
    val endInstant = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().minusNanos(1)
    return startInstant..endInstant
}
