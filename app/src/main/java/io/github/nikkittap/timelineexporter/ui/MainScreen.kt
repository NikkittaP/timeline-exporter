package io.github.nikkittap.timelineexporter.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.export.AllExporters
import io.github.nikkittap.timelineexporter.export.CsvExporter
import io.github.nikkittap.timelineexporter.export.Exporter
import io.github.nikkittap.timelineexporter.export.GeoJsonExporter
import io.github.nikkittap.timelineexporter.export.GpxExporter
import io.github.nikkittap.timelineexporter.export.KmlExporter
import io.github.nikkittap.timelineexporter.filter.TimelineFilter
import io.github.nikkittap.timelineexporter.filter.applyFilter
import io.github.nikkittap.timelineexporter.parser.ParsedTimeline
import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.Instant

/**
 * Top-level Compose entry called from MainActivity. Hosts the Scaffold with
 * TopAppBar + Help action, owns the help-dialog state, and delegates the main
 * content to [MainScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineExporterApp() {
    var helpDialogOpen by remember { mutableStateOf(false) }
    var tipJarDialogOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { tipJarDialogOpen = true }) {
                        Text(stringResource(R.string.action_support))
                    }
                    TextButton(onClick = { helpDialogOpen = true }) {
                        Text(stringResource(R.string.action_help))
                    }
                },
            )
        },
    ) { innerPadding ->
        MainScreen(
            modifier = Modifier.padding(innerPadding),
            onHelpClick = { helpDialogOpen = true },
        )
    }

    if (helpDialogOpen) {
        HelpDialog(onDismiss = { helpDialogOpen = false })
    }
    if (tipJarDialogOpen) {
        TipJarDialog(onDismiss = { tipJarDialogOpen = false })
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onHelpClick: () -> Unit = {},
    viewModel: TimelineViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()

    var dateDialogOpen by remember { mutableStateOf(false) }
    var mapInteracting by remember { mutableStateOf(false) }
    var mapFullscreen by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val movableMap = remember {
        movableContentOf<List<PathPoint>, Modifier> { pts, m ->
            TrackMap(
                points = pts,
                onInteractionChange = { mapInteracting = it },
                modifier = m,
            )
        }
    }

    // The localized track name to embed in exported files. Recomputes per
    // recomposition; the launcher callbacks below capture the current value.
    val trackName = buildTrackName(filter)

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.onFileSelected(it, context.contentResolver) }
    }

    val gpxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(GpxExporter.mimeType),
    ) { uri ->
        uri?.let {
            viewModel.onSaveDestinationSelected(it, GpxExporter, trackName, context.contentResolver)
        }
    }
    val kmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(KmlExporter.mimeType),
    ) { uri ->
        uri?.let {
            viewModel.onSaveDestinationSelected(it, KmlExporter, trackName, context.contentResolver)
        }
    }
    val geojsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(GeoJsonExporter.mimeType),
    ) { uri ->
        uri?.let {
            viewModel.onSaveDestinationSelected(it, GeoJsonExporter, trackName, context.contentResolver)
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(CsvExporter.mimeType),
    ) { uri ->
        uri?.let {
            viewModel.onSaveDestinationSelected(it, CsvExporter, trackName, context.contentResolver)
        }
    }

    val launchSave: (Exporter) -> Unit = { exporter ->
        val name = suggestFilename(filter, exporter)
        when (exporter) {
            GpxExporter -> gpxLauncher.launch(name)
            KmlExporter -> kmlLauncher.launch(name)
            GeoJsonExporter -> geojsonLauncher.launch(name)
            CsvExporter -> csvLauncher.launch(name)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState, enabled = !mapInteracting)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(onClick = { pickFileLauncher.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.pick_file_button))
            }

            when (val state = uiState) {
                TimelineUiState.Idle -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.idle_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = onHelpClick,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.idle_help_button))
                    }
                }

                is TimelineUiState.Loading -> LoadingDisplay(state.phase)

                is TimelineUiState.Error -> Text(
                    text = stringResource(
                        R.string.parse_error,
                        state.exceptionClass,
                        state.exceptionMessage
                            ?: stringResource(R.string.parse_error_no_message),
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )

                is TimelineUiState.Loaded -> {
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
                    PreviewSection(
                        points = filteredPoints,
                        inlineMapActive = !mapFullscreen,
                        onExpand = { mapFullscreen = true },
                        mapContent = movableMap,
                    )

                    HorizontalDivider()
                    ExportSection(
                        canSave = filteredPoints.isNotEmpty(),
                        exportState = exportState,
                        onPickFormat = launchSave,
                    )
                }
            }
        }

        if (mapFullscreen) {
            val loaded = (uiState as? TimelineUiState.Loaded)?.result
            if (loaded != null) {
                val fullscreenPoints by remember(loaded, filter) {
                    derivedStateOf { applyFilter(loaded.pathPoints, filter) }
                }
                BackHandler { mapFullscreen = false }
                FullscreenMap(
                    points = fullscreenPoints,
                    onClose = { mapFullscreen = false },
                    mapContent = movableMap,
                )
            }
        }
    }

    if (dateDialogOpen) {
        val loaded = (uiState as? TimelineUiState.Loaded)?.result
        val dataRange = loaded?.let {
            it.pathPoints.firstOrNull()?.timeUtc?.let { first ->
                first..it.pathPoints.last().timeUtc
            }
        }
        DateRangeDialog(
            initialRange = filter.dateRange,
            dataRange = dataRange,
            onConfirm = { range ->
                viewModel.setDateRange(range)
                dateDialogOpen = false
            },
            onDismiss = { dateDialogOpen = false },
        )
    }
}

// ---------- pure-composable helpers ----------

/**
 * Translate the semantic [LoadingPhase] into a localized title + optional
 * detail line + optional 0..1 progress for the bar. This is the ONLY
 * place loading-stage UI text lives.
 */
@Composable
private fun LoadingDisplay(phase: LoadingPhase) {
    val label: String
    val detail: String?
    val progress: Float?
    when (phase) {
        LoadingPhase.OpeningFile -> {
            label = stringResource(R.string.loading_opening)
            detail = null
            progress = null
        }
        is LoadingPhase.ReadingFile -> {
            label = stringResource(R.string.loading_reading)
            detail = if (phase.totalBytes != null) {
                stringResource(
                    R.string.loading_reading_detail,
                    formatMb(phase.bytesRead),
                    formatMb(phase.totalBytes),
                )
            } else {
                stringResource(R.string.loading_reading_detail_unknown, formatMb(phase.bytesRead))
            }
            progress = phase.totalBytes
                ?.takeIf { it > 0 }
                ?.let { phase.bytesRead.toFloat() / it }
        }
        LoadingPhase.DecodingJson -> {
            label = stringResource(R.string.loading_decoding)
            detail = stringResource(R.string.loading_decoding_detail)
            progress = null
        }
        is LoadingPhase.ExtractingPoints -> {
            label = stringResource(R.string.loading_extracting)
            detail = stringResource(
                R.string.loading_extracting_detail,
                formatGrouped(phase.done),
                formatGrouped(phase.total),
            )
            progress = if (phase.total > 0) phase.done.toFloat() / phase.total else null
        }
        LoadingPhase.SortingPoints -> {
            label = stringResource(R.string.loading_sorting)
            detail = null
            progress = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultDisplay(result: ParsedTimeline) {
    // Compact two-line summary. The per-segment-type breakdown
    // (path / visit / activity) was useful while debugging the parser but
    // is noise for end users; can be brought back later behind an
    // expandable "Details" affordance if anyone asks.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(
                R.string.result_summary,
                formatGrouped(result.pathPoints.size),
                formatGrouped(result.totalSegments),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (result.pathPoints.isNotEmpty()) {
            val first = result.pathPoints.first()
            val last = result.pathPoints.last()
            Text(
                text = stringResource(
                    R.string.result_range,
                    formatLocalDate(first.timeUtc),
                    formatLocalDate(last.timeUtc),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Text(stringResource(R.string.filter_title), style = MaterialTheme.typography.titleMedium)
        if (currentRange == null) {
            Text(
                stringResource(R.string.filter_date_range_all),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                stringResource(
                    R.string.filter_date_range_set,
                    formatLocalDate(currentRange.start),
                    formatLocalDate(currentRange.endInclusive),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(
                R.string.filter_filtered_count,
                formatGrouped(filteredCount),
                formatGrouped(totalCount),
            ),
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
            Text(
                if (hasFilter) stringResource(R.string.filter_change_button)
                else stringResource(R.string.filter_set_button)
            )
        }
        if (hasFilter) {
            OutlinedButton(onClick = onClear) {
                Text(stringResource(R.string.filter_clear_button))
            }
        }
    }
}

@Composable
private fun PreviewSection(
    points: List<PathPoint>,
    inlineMapActive: Boolean,
    onExpand: () -> Unit,
    mapContent: @Composable (List<PathPoint>, Modifier) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.preview_title), style = MaterialTheme.typography.titleMedium)
        if (points.isEmpty()) {
            Text(
                stringResource(R.string.preview_no_points),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val shape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            ) {
                if (inlineMapActive) {
                    mapContent(points, Modifier.fillMaxSize())
                    FilledTonalButton(
                        onClick = onExpand,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    ) {
                        Text(stringResource(R.string.preview_expand))
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenMap(
    points: List<PathPoint>,
    onClose: () -> Unit,
    mapContent: @Composable (List<PathPoint>, Modifier) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            mapContent(points, Modifier.fillMaxSize())
            FilledTonalButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.preview_close))
            }
        }
    }
}

@Composable
private fun ExportSection(
    canSave: Boolean,
    exportState: ExportState,
    onPickFormat: (Exporter) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleMedium)

        Box {
            Button(
                onClick = { menuOpen = true },
                enabled = canSave && exportState !is ExportState.Working,
            ) {
                Text(stringResource(R.string.export_save_button))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                AllExporters.forEach { exporter ->
                    DropdownMenuItem(
                        text = { Text(exporter.displayName) },
                        onClick = {
                            menuOpen = false
                            onPickFormat(exporter)
                        },
                    )
                }
            }
        }

        when (val s = exportState) {
            ExportState.Idle -> { /* nothing */ }
            ExportState.Working -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.export_writing),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is ExportState.Success -> Text(
                text = stringResource(
                    R.string.export_success,
                    formatGrouped(s.pointCount),
                    s.displayName ?: stringResource(R.string.export_fallback_filename),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            ExportState.Failure.NoPoints -> Text(
                text = stringResource(R.string.export_error_no_points),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            is ExportState.Failure.Generic -> {
                val detail = s.exceptionMessage
                    ?: stringResource(R.string.parse_error_no_message)
                Text(
                    text = stringResource(
                        R.string.export_error_generic,
                        s.formatName,
                        "${s.exceptionClass}: $detail",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Build the human-readable track name that gets embedded inside exported
 * GPX/KML/GeoJSON files. Composed from string resources so it follows the
 * active locale.
 */
@Composable
private fun buildTrackName(filter: TimelineFilter): String {
    val range = filter.dateRange
    val rangePart = if (range == null) {
        stringResource(R.string.track_name_full_range)
    } else {
        stringResource(
            R.string.track_name_date_range,
            formatLocalDate(range.start),
            formatLocalDate(range.endInclusive),
        )
    }
    return stringResource(R.string.track_name, rangePart)
}

// ---------- non-Compose formatting helpers ----------

private fun formatGrouped(n: Int): String = "%,d".format(n)

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)
