package io.github.nikkittap.timelineexporter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.parser.MINUTES_PER_DAY
import io.github.nikkittap.timelineexporter.parser.TimeOfDayWindow

/**
 * How finely the window can be set. Half an hour, because the data does not
 * support finer: the median gap between two path points in the reference
 * export is six minutes, so a window edge at 08:07 would be answered by
 * whichever sample happened to land nearby. Half-hours also keep the two
 * menus to 48 rows each instead of 1440.
 */
private const val STEP_MINUTES = 30

private const val HOUR = 60

/**
 * The four windows worth a one-tap answer. They are quarters of the day rather
 * than anything cleverer — "commute" is not the same hours for two people, and
 * a preset that guesses wrong is worse than the two menus underneath.
 *
 * Evening and night both wrap through midnight, which is the case a single
 * range control could not express and the reason this dialog has two ends
 * rather than one slider.
 */
private val PRESETS = listOf(
    R.string.time_preset_morning to TimeOfDayWindow(6 * HOUR, 12 * HOUR),
    R.string.time_preset_day to TimeOfDayWindow(9 * HOUR, 18 * HOUR),
    R.string.time_preset_evening to TimeOfDayWindow(18 * HOUR, 0),
    R.string.time_preset_night to TimeOfDayWindow(22 * HOUR, 6 * HOUR),
)

/**
 * Pick a window of the local clock that applies to every day in the export.
 *
 * A dialog rather than another row of chips in step 2: the step already
 * carries a date range, its presets, a count and the movement breakdown, and
 * "which hours" is a second question about the same days rather than a fifth
 * thing to scan past. What step 2 keeps is one chip that states the answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeOfDayDialog(
    initial: TimeOfDayWindow?,
    onConfirm: (TimeOfDayWindow?) -> Unit,
    onDismiss: () -> Unit,
) {
    var startMinute by remember { mutableIntStateOf(initial?.startMinute ?: 0) }
    var endMinute by remember { mutableIntStateOf(initial?.endMinute?.let { it % MINUTES_PER_DAY } ?: 0) }

    // Equal ends mean the window covers the whole day, which is the same thing
    // as no filter — so that is what Apply sends back, rather than a window
    // that keeps everything while a chip claims something is filtered.
    val selected = if (startMinute == endMinute) null else TimeOfDayWindow(startMinute, endMinute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.time_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selected == null,
                        onClick = { startMinute = 0; endMinute = 0 },
                        label = { Text(stringResource(R.string.time_preset_any)) },
                    )
                    PRESETS.forEach { (labelRes, window) ->
                        FilterChip(
                            selected = selected == window,
                            onClick = {
                                startMinute = window.startMinute
                                endMinute = window.endMinute % MINUTES_PER_DAY
                            },
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MinuteField(
                        label = stringResource(R.string.time_dialog_from),
                        minute = startMinute,
                        onPick = { startMinute = it },
                    )
                    MinuteField(
                        label = stringResource(R.string.time_dialog_to),
                        minute = endMinute,
                        onPick = { endMinute = it },
                    )
                }

                Text(
                    text = stringResource(R.string.time_dialog_local_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.time_dialog_places_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.date_dialog_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.date_dialog_cancel))
            }
        },
    )
}

/** One end of the window: a button showing the time, and a menu of half-hours. */
@Composable
private fun MinuteField(label: String, minute: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(formatMinuteOfDay(minute))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (m in 0 until MINUTES_PER_DAY step STEP_MINUTES) {
                    DropdownMenuItem(
                        text = { Text(formatMinuteOfDay(m)) },
                        onClick = {
                            onPick(m)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * What the chip in step 2 says: the window, or the invitation to set one.
 *
 * Kept next to the dialog rather than in the step, so the two cannot end up
 * describing the same state differently.
 */
@Composable
fun timeOfDayChipLabel(window: TimeOfDayWindow?): String =
    if (window == null || window.isWholeDay) {
        stringResource(R.string.filter_time_any)
    } else {
        stringResource(
            R.string.filter_time_range,
            formatMinuteOfDay(window.startMinute),
            formatMinuteOfDay(window.endMinute),
        )
    }
