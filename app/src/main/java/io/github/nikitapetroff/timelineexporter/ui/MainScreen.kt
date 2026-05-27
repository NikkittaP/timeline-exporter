package io.github.nikitapetroff.timelineexporter.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nikitapetroff.timelineexporter.filter.applyFilter
import io.github.nikitapetroff.timelineexporter.parser.ParsedTimeline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()

    var dateDialogOpen by remember { mutableStateOf(false) }

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.onFileSelected(it, context.contentResolver) }
    }

    val saveGpxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        uri?.let { viewModel.onSaveDestinationSelected(it, context.contentResolver) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(onClick = { pickFileLauncher.launch(arrayOf("*/*")) }) {
            Text("Pick Timeline.json")
        }

        when (val state = uiState) {
            TimelineUiState.Idle -> Text(
                "Pick a Google Maps Timeline JSON file to parse.",
                style = MaterialTheme.typography.bodyMedium,
            )

            is TimelineUiState.Loading -> LoadingDisplay(state)

            is TimelineUiState.Error -> Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            is TimelineUiState.Loaded -> {
                // Compute filtered points ONCE per (data, filter) change; share
                // with both the filter readout, the map, and the save button.
                val filteredPoints by remember(state.result, filter) {
                    derivedStateOf { applyFilter(state.result.pathPoints, filter) }
                }

                ResultDisplay(state.result)

                HorizontalDivider()
                FilterSection(
                    totalCount = state.result.pathPoints.size,
                    filteredCount = filteredPoints.size,
                    currentRange = filter.dateRange,
                    onOpenDateDialog = { dateDialogOpen = true },
                    onClearRange = { viewModel.clearDateRange() },
                )

                HorizontalDivider()
                PreviewSection(points = filteredPoints)

                HorizontalDivider()
                ExportSection(
                    canSave = filteredPoints.isNotEmpty(),
                    exportState = exportState,
                    onSave = { saveGpxLauncher.launch(suggestGpxFilename(filter)) },
                )
            }
        }
    }

    if (dateDialogOpen) {
        val loaded = (uiState as? TimelineUiState.Loaded)?.result
        val initial = filter.dateRange ?: loaded?.let {
            it.pathPoints.firstOrNull()?.timeUtc?.let { first ->
                first..it.pathPoints.last().timeUtc
            }
        }
        DateRangeDialog(
            initialRange = initial,
            onConfirm = { range ->
                viewModel.setDateRange(range)
                dateDialogOpen = false
            },
            onDismiss = { dateDialogOpen = false },
        )
    }
}

@Composable
private fun LoadingDisplay(state: TimelineUiState.Loading) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = state.stageLabel, style = MaterialTheme.typography.titleMedium)
        if (state.detailLabel != null) {
            Text(
                text = state.detailLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val fraction = state.progress
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultDisplay(result: ParsedTimeline) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Parsed successfully", style = MaterialTheme.typography.titleMedium)
        Text("Total segments: ${result.totalSegments}")
        Text("  • Path: ${result.pathSegments}")
        Text("  • Visit: ${result.visitSegments}")
        Text("  • Activity: ${result.activitySegments}")
        Text("Path points: ${result.pathPoints.size}")
        if (result.pathPoints.isNotEmpty()) {
            val first = result.pathPoints.first()
            val last = result.pathPoints.last()
            Spacer(modifier = Modifier.size(8.dp))
            Text("First: ${first.timeUtc}")
            Text("Last:  ${last.timeUtc}")
        }
    }
}

@Composable
private fun FilterSection(
    totalCount: Int,
    filteredCount: Int,
    currentRange: ClosedRange<Instant>?,
    onOpenDateDialog: () -> Unit,
    onClearRange: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Filter", style = MaterialTheme.typography.titleMedium)
        if (currentRange == null) {
            Text("Date range: all dates", style = MaterialTheme.typography.bodyMedium)
        } else {
            val z = ZoneId.systemDefault()
            val from = LocalDate.ofInstant(currentRange.start, z)
            val to = LocalDate.ofInstant(currentRange.endInclusive, z)
            Text("Date range: $from — $to", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "After filter: ${"%,d".format(filteredCount)} of ${"%,d".format(totalCount)} points",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterActionsRow(
            hasFilter = currentRange != null,
            onPick = onOpenDateDialog,
            onClear = onClearRange,
        )
    }
}

@Composable
private fun FilterActionsRow(hasFilter: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPick) {
            Text(if (hasFilter) "Change date range" else "Set date range")
        }
        if (hasFilter) {
            OutlinedButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun PreviewSection(points: List<io.github.nikitapetroff.timelineexporter.parser.PathPoint>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Preview", style = MaterialTheme.typography.titleMedium)
        if (points.isEmpty()) {
            Text(
                "No points match the current filter — nothing to preview.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val shape = RoundedCornerShape(8.dp)
            TrackMap(
                points = points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            )
        }
    }
}

@Composable
private fun ExportSection(
    canSave: Boolean,
    exportState: ExportState,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Export", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = onSave,
            enabled = canSave && exportState !is ExportState.Working,
        ) {
            Text("Save as GPX…")
        }
        when (val s = exportState) {
            ExportState.Idle -> { /* nothing */ }
            ExportState.Working -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Writing GPX…", style = MaterialTheme.typography.bodySmall)
            }
            is ExportState.Success -> Text(
                text = "Saved ${"%,d".format(s.pointCount)} points to ${s.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is ExportState.Failed -> Text(
                text = s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
