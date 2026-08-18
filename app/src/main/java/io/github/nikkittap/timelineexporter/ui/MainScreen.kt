package io.github.nikkittap.timelineexporter.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nikkittap.timelineexporter.BuildConfig
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.export.AllExporters
import io.github.nikkittap.timelineexporter.export.CsvExporter
import io.github.nikkittap.timelineexporter.export.Exporter
import io.github.nikkittap.timelineexporter.export.GeoJsonExporter
import io.github.nikkittap.timelineexporter.export.GpxExporter
import io.github.nikkittap.timelineexporter.export.KmlExporter
import io.github.nikkittap.timelineexporter.filter.TimelineFilter
import io.github.nikkittap.timelineexporter.filter.applyFilter
import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.MovementStats
import io.github.nikkittap.timelineexporter.parser.ParsedTimeline
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.parser.TimelineFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Top-level Compose entry called from MainActivity. Hosts the Scaffold with
 * TopAppBar (app logo + Help / Support actions), owns the dialog state, and
 * delegates the main content to [MainScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineExporterApp() {
    var helpDialogOpen by remember { mutableStateOf(false) }
    var tipJarDialogOpen by remember { mutableStateOf(false) }
    var languageDialogOpen by remember { mutableStateOf(false) }
    var feedbackDialogOpen by remember { mutableStateOf(false) }
    var overflowMenuOpen by remember { mutableStateOf(false) }

    // Same activity-scoped instance MainScreen resolves to; read here only to
    // pre-fill the feedback diagnostics with what is currently loaded.
    val viewModel: TimelineViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Image(
                        painter = painterResource(R.drawable.app_logo),
                        contentDescription = stringResource(R.string.app_logo_desc),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    // Only fixed-width icon buttons live in the bar so the title
                    // is never squeezed by translated labels. Support stays
                    // visible (it's the donate entry point); Help — also
                    // reachable from the in-content "How do I get Timeline
                    // data?" link — moves into the overflow menu.
                    val cdLanguage = stringResource(R.string.language_title)
                    val cdSupport = stringResource(R.string.action_support)
                    val cdMore = stringResource(R.string.action_more)
                    IconButton(
                        onClick = { languageDialogOpen = true },
                        modifier = Modifier.semantics { contentDescription = cdLanguage },
                    ) {
                        Text("🌐", style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(
                        onClick = { tipJarDialogOpen = true },
                        modifier = Modifier.semantics { contentDescription = cdSupport },
                    ) {
                        Text("💜", style = MaterialTheme.typography.titleMedium)
                    }
                    Box {
                        IconButton(
                            onClick = { overflowMenuOpen = true },
                            modifier = Modifier.semantics { contentDescription = cdMore },
                        ) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(
                            expanded = overflowMenuOpen,
                            onDismissRequest = { overflowMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_help)) },
                                onClick = {
                                    overflowMenuOpen = false
                                    helpDialogOpen = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        MainScreen(
            modifier = Modifier.padding(innerPadding),
            onHelpClick = { helpDialogOpen = true },
            onFeedbackClick = { feedbackDialogOpen = true },
            viewModel = viewModel,
        )
    }

    if (helpDialogOpen) {
        HelpDialog(
            onDismiss = { helpDialogOpen = false },
            onFeedbackClick = {
                helpDialogOpen = false
                feedbackDialogOpen = true
            },
        )
    }
    if (tipJarDialogOpen) {
        TipJarDialog(onDismiss = { tipJarDialogOpen = false })
    }
    if (languageDialogOpen) {
        LanguageDialog(onDismiss = { languageDialogOpen = false })
    }
    if (feedbackDialogOpen) {
        val loaded = (uiState as? TimelineUiState.Loaded)?.result
        FeedbackDialog(
            feedbackContext = FeedbackContext(
                format = loaded?.format,
                pointCount = loaded?.pathPoints?.size,
            ),
            onDismiss = { feedbackDialogOpen = false },
        )
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onHelpClick: () -> Unit = {},
    onFeedbackClick: () -> Unit = {},
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

    // A successful export lands at the bottom of a four-step screen the user is
    // usually somewhere in the middle of, so both the confirmation and the
    // feedback prompt below it start off-screen. Scroll them into view.
    //
    // Keyed on exportState, which always passes through Working before the next
    // Success — so a repeated export with identical values still re-triggers.
    LaunchedEffect(exportState) {
        if (exportState !is ExportState.Success) return@LaunchedEffect
        // The prompt is composed in this same frame; maxValue only accounts for
        // it after the next layout pass, so measuring now would stop short.
        withFrameNanos { }
        scrollState.animateScrollTo(scrollState.maxValue)
        // If the content grew while the animation ran (long filename wrapping
        // onto a second line), finish the trip.
        if (scrollState.value < scrollState.maxValue) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Step 1 — file picking + load/parse status.
            StepCard(step = 1, title = stringResource(R.string.step_choose_file)) {
                Button(
                    onClick = { pickFileLauncher.launch(arrayOf("*/*")) },
                    shape = ButtonShape,
                ) {
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
                        text = if (state.wrongFile) {
                            stringResource(R.string.error_not_timeline)
                        } else {
                            stringResource(
                                R.string.parse_error,
                                state.exceptionClass,
                                state.exceptionMessage
                                    ?: stringResource(R.string.parse_error_no_message),
                            )
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is TimelineUiState.Loaded -> LoadedSummary(state.result)
                }
            }

            // Remaining steps only make sense once a file is loaded.
            val loaded = (uiState as? TimelineUiState.Loaded)?.result
            if (loaded != null) {
                val filteredPoints by remember(loaded, filter) {
                    derivedStateOf { applyFilter(loaded.pathPoints, filter, loaded.segments) }
                }
                val dataLast = loaded.pathPoints.lastOrNull()?.timeUtc

                StepCard(step = 2, title = stringResource(R.string.step_filter)) {
                    FilterContent(
                        currentRange = filter.dateRange,
                        dataLast = dataLast,
                        totalCount = loaded.pathPoints.size,
                        filteredCount = filteredPoints.size,
                        onApply = { viewModel.setDateRange(it) },
                        onClear = { viewModel.clearDateRange() },
                        onCustom = { dateDialogOpen = true },
                        parsed = loaded,
                        filter = filter,
                        onToggleMovement = { group, on, available ->
                            viewModel.toggleMovement(group, on, available)
                        },
                        onMovingOnly = { viewModel.setMovingOnly(it) },
                        onDropRepeated = { viewModel.setDropRepeatedPoints(it) },
                    )
                }

                StepCard(
                    step = 3,
                    title = stringResource(R.string.step_preview),
                    trailing = {
                        if (filteredPoints.isNotEmpty()) {
                            TextButton(onClick = { mapFullscreen = true }) {
                                Text(stringResource(R.string.preview_expand))
                            }
                        }
                    },
                ) {
                    PreviewContent(
                        points = filteredPoints,
                        inlineMapActive = !mapFullscreen,
                        mapContent = movableMap,
                    )
                }

                StepCard(step = 4, title = stringResource(R.string.step_export)) {
                    ExportContent(
                        canSave = filteredPoints.isNotEmpty(),
                        exportState = exportState,
                        onPickFormat = launchSave,
                        onFeedbackClick = onFeedbackClick,
                    )
                }
            }

            VersionFooter(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (mapFullscreen) {
            val loadedResult = (uiState as? TimelineUiState.Loaded)?.result
            if (loadedResult != null) {
                val fullscreenPoints by remember(loadedResult, filter) {
                    derivedStateOf {
                        applyFilter(loadedResult.pathPoints, filter, loadedResult.segments)
                    }
                }
                BackHandler { mapFullscreen = false }
                ExpandedMap(
                    points = fullscreenPoints,
                    onClose = { mapFullscreen = false },
                    mapContent = movableMap,
                )
            }
        }
    }

    if (dateDialogOpen) {
        val loadedResult = (uiState as? TimelineUiState.Loaded)?.result
        val dataRange = loadedResult?.let {
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

// ---------- structural building blocks ----------

/** An outlined card with a numbered badge + title header and arbitrary content. */
@Composable
private fun StepCard(
    step: Int,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StepBadge(step)
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (trailing != null) {
                    Spacer(Modifier.weight(1f))
                    trailing()
                }
            }
            content()
        }
    }
}

@Composable
private fun StepBadge(number: Int) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

// ---------- step content ----------

/** Loaded-state header: a format badge plus the points / date-range metrics. */
@Composable
private fun LoadedSummary(result: ParsedTimeline) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(
                text = stringResource(R.string.loaded_badge),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            FormatBadge(result.format)
        }

        // Date range as two short lines so it never wraps awkwardly and both
        // tiles can share one height.
        val rangeValue = if (result.pathPoints.isNotEmpty()) {
            "${formatLocalDate(result.pathPoints.first().timeUtc)}\n– ${
                formatLocalDate(result.pathPoints.last().timeUtc)
            }"
        } else {
            "—"
        }
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                label = stringResource(R.string.metric_points),
                value = formatGrouped(result.pathPoints.size),
                valueStyle = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            MetricCard(
                label = stringResource(R.string.metric_date_range),
                value = rangeValue,
                valueStyle = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = valueStyle)
        }
    }
}

@Composable
private fun FormatBadge(format: TimelineFormat) {
    val label = when (format) {
        TimelineFormat.PHONE_TAKEOUT -> stringResource(R.string.format_phone)
        TimelineFormat.PHONE_TAKEOUT_ARRAY -> stringResource(R.string.format_phone_ios)
        TimelineFormat.SEMANTIC_LOCATION_HISTORY -> stringResource(R.string.format_semantic)
        TimelineFormat.RECORDS -> stringResource(R.string.format_records)
        TimelineFormat.UNKNOWN -> stringResource(R.string.format_unknown)
    }
    Pill(
        text = label,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun Pill(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterContent(
    currentRange: ClosedRange<Instant>?,
    dataLast: Instant?,
    totalCount: Int,
    filteredCount: Int,
    onApply: (ClosedRange<Instant>) -> Unit,
    onClear: () -> Unit,
    onCustom: () -> Unit,
    parsed: ParsedTimeline,
    filter: TimelineFilter,
    onToggleMovement: (MovementGroup, Boolean, Set<MovementGroup>) -> Unit,
    onMovingOnly: (Boolean) -> Unit,
    onDropRepeated: (Boolean) -> Unit,
) {
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

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dataLast != null) {
            val last7 = lastNDaysRange(dataLast, 7)
            val last30 = lastNDaysRange(dataLast, 30)
            val last90 = lastNDaysRange(dataLast, 90)
            FilterChip(
                selected = currentRange == last7,
                onClick = { onApply(last7) },
                label = { Text(stringResource(R.string.date_preset_last_7)) },
            )
            FilterChip(
                selected = currentRange == last30,
                onClick = { onApply(last30) },
                label = { Text(stringResource(R.string.date_preset_last_30)) },
            )
            FilterChip(
                selected = currentRange == last90,
                onClick = { onApply(last90) },
                label = { Text(stringResource(R.string.date_preset_last_90)) },
            )
        }
        FilterChip(
            selected = currentRange == null,
            onClick = onClear,
            label = { Text(stringResource(R.string.date_preset_all)) },
        )
        FilterChip(
            selected = false,
            onClick = onCustom,
            label = { Text(stringResource(R.string.filter_preset_custom)) },
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

    MovementSection(
        parsed = parsed,
        filter = filter,
        onToggleMovement = onToggleMovement,
        onMovingOnly = onMovingOnly,
        onDropRepeated = onDropRepeated,
    )
}

/**
 * The movement breakdown, collapsed by default.
 *
 * Collapsed it is a single summary row, so someone who only ever exported a
 * date range sees the step they are used to plus one line. Expanded it is both
 * the statistics and the filter: the km and trip counts a user wants to look at
 * are the same rows they tick to narrow the export, so there is no separate
 * screen and no settings sitting apart from the thing they affect.
 *
 * Hidden entirely when the file carries no activity segments — older Takeout
 * formats and rawSignals-only exports have nothing to show, and an empty
 * expander is worse than no expander.
 */
@Composable
private fun MovementSection(
    parsed: ParsedTimeline,
    filter: TimelineFilter,
    onToggleMovement: (MovementGroup, Boolean, Set<MovementGroup>) -> Unit,
    onMovingOnly: (Boolean) -> Unit,
    onDropRepeated: (Boolean) -> Unit,
) {
    val breakdown = remember(parsed, filter.dateRange) {
        parsed.movementBreakdown(filter.dateRange)
    }
    if (breakdown.isEmpty()) return

    val available = remember(breakdown) { breakdown.map { it.group }.toSet() }
    val selected = filter.movements ?: available
    val places = remember(parsed, filter.dateRange) { parsed.placeCount(filter.dateRange) }
    val labels = movementLabels()

    var expanded by rememberSaveable { mutableStateOf(false) }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = movementSummaryLabel(selected, available, filter.movingOnly, places, labels),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = if (expanded) "▲" else "▼",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    if (!expanded) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        breakdown.forEach { stats ->
            MovementRow(
                stats = stats,
                label = labels[stats.group] ?: stats.group.name,
                checked = stats.group in selected,
                onCheckedChange = { onToggleMovement(stats.group, it, available) },
            )
        }

        if (places > 0) {
            Text(
                text = stringResource(R.string.filter_places_count, formatGrouped(places)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ToggleRow(
            label = stringResource(R.string.filter_moving_only),
            supporting = stringResource(R.string.filter_moving_only_hint),
            checked = filter.movingOnly,
            onCheckedChange = onMovingOnly,
        )
        ToggleRow(
            label = stringResource(R.string.filter_drop_repeated),
            supporting = stringResource(R.string.filter_drop_repeated_hint),
            checked = filter.dropRepeatedPoints,
            onCheckedChange = onDropRepeated,
        )
    }
}

/** One movement group: tick box, name, trips, and Google's own distance. */
@Composable
private fun MovementRow(
    stats: MovementStats,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.filter_trip_count, formatGrouped(stats.trips)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatKm(stats.distanceMeters),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Every movement label, resolved once.
 *
 * Spelled out rather than derived with `associateWith` or `map`: stringResource
 * is @Composable and those lambdas are not inline, so it cannot be called
 * inside them. Resolving all six up front also means callers that need a label
 * inside a non-inline lambda — joinToString, say — have a plain String to hand.
 */
@Composable
private fun movementLabels(): Map<MovementGroup, String> = mapOf(
    MovementGroup.WALKING to stringResource(R.string.movement_walking),
    MovementGroup.CYCLING to stringResource(R.string.movement_cycling),
    MovementGroup.DRIVING to stringResource(R.string.movement_driving),
    MovementGroup.TRANSIT to stringResource(R.string.movement_transit),
    MovementGroup.FLYING to stringResource(R.string.movement_flying),
    MovementGroup.OTHER to stringResource(R.string.movement_other),
)

/**
 * The one-line description shown while the section is collapsed.
 *
 * Built by joining parts rather than from one format string: which parts apply
 * depends on state, and a single template would have to spell out every
 * combination — including "0 places", which is not worth saying.
 */
@Composable
private fun movementSummaryLabel(
    selected: Set<MovementGroup>,
    available: Set<MovementGroup>,
    movingOnly: Boolean,
    places: Int,
    labels: Map<MovementGroup, String>,
): String {
    val parts = mutableListOf(
        when {
            selected.isEmpty() -> stringResource(R.string.filter_movement_none)
            selected == available -> stringResource(R.string.filter_movement_all)
            else -> selected.sortedBy { it.ordinal }
                .joinToString(", ") { labels[it] ?: it.name }
        }
    )
    if (movingOnly) parts += stringResource(R.string.filter_moving_only_short)
    if (places > 0) parts += stringResource(R.string.filter_places_count, formatGrouped(places))
    return parts.joinToString(" · ")
}

@Composable
private fun PreviewContent(
    points: List<PathPoint>,
    inlineMapActive: Boolean,
    mapContent: @Composable (List<PathPoint>, Modifier) -> Unit,
) {
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
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportContent(
    canSave: Boolean,
    exportState: ExportState,
    onPickFormat: (Exporter) -> Unit,
    onFeedbackClick: () -> Unit = {},
) {
    val enabled = canSave && exportState !is ExportState.Working
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AllExporters.forEach { exporter ->
            FilledTonalButton(
                onClick = { onPickFormat(exporter) },
                enabled = enabled,
                shape = ButtonShape,
            ) {
                Text(exporter.displayName)
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
        is ExportState.Success -> {
            Text(
                text = stringResource(
                    R.string.export_success,
                    formatGrouped(s.pointCount),
                    s.displayName ?: stringResource(R.string.export_fallback_filename),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            FeedbackPrompt(onClick = onFeedbackClick)
        }
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

/**
 * The feedback ask, shown only after a successful export — the one moment the
 * user demonstrably got what they came for.
 *
 * A tinted plaque rather than a bare text line: the first version was a muted
 * `bodySmall` link and read as a caption, which is exactly what people skip.
 * The visual weight comes from the container colour, not from a loud primary
 * button — the user has finished their task and is being asked a favour, so
 * this must not compete with the export buttons above it.
 *
 * Deliberately `tertiaryContainer` and not `secondaryContainer`: the app runs
 * with dynamic colour on, where primary and secondary are neighbouring shades
 * of the same wallpaper hue, so a secondary plaque sitting under blue export
 * buttons read as one more control. Material You derives tertiary by rotating
 * the hue, so this is the only container guaranteed to look like a different
 * kind of thing — which is the point: this is not app functionality, it's the
 * developer asking a question.
 */
@Composable
private fun FeedbackPrompt(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // The 12dp text inset is split between this Column and the button's own
        // contentPadding: the button keeps real horizontal padding (its pressed
        // state layer is a fully rounded pill, and with zero padding the first
        // and last glyphs sit outside the curve), while its label still lines up
        // with the two lines above it.
        Column(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.feedback_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                text = stringResource(R.string.feedback_prompt_body),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
            // The action inherits onTertiaryContainer from the Surface instead
            // of TextButton's default primary, which would drag the plaque back
            // to the colour of the export buttons.
            TextButton(
                onClick = onClick,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.feedback_prompt_action),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
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
private fun ExpandedMap(
    points: List<PathPoint>,
    onClose: () -> Unit,
    mapContent: @Composable (List<PathPoint>, Modifier) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // Keep the same rounded, bordered "Preview tile" framing as the inline
        // map, just enlarged with a margin around it — not edge-to-edge.
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val shape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            ) {
                mapContent(points, Modifier.fillMaxSize())
            }
            FilledTonalButton(
                onClick = onClose,
                shape = ButtonShape,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Text(stringResource(R.string.preview_close))
            }
        }
    }
}

/**
 * Small, muted app-version line shown at the very bottom of the screen.
 * Reads [BuildConfig.VERSION_NAME] so it always matches the published build.
 */
@Composable
private fun VersionFooter(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(top = 8.dp),
    )
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

// ---------- shared shapes / formatting helpers ----------

/** Slightly-rounded rectangle for filled buttons, instead of Material's pill. */
private val ButtonShape = RoundedCornerShape(8.dp)

private fun formatGrouped(n: Int): String = "%,d".format(n)

// Decimal megabytes (1 MB = 1,000,000 bytes) to match the size Android's
// own file picker reports for the same file.
private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)

/**
 * Distance for the movement breakdown.
 *
 * Whole kilometres once past 10 km — nobody reads the decimal on a 3 224 km
 * total — and one decimal below that, so a 400-metre walk does not display as
 * "0 km". Metres are never shown: the underlying figures are Google's own
 * estimates, and metre precision would imply an accuracy they do not have.
 */
private fun formatKm(meters: Double): String {
    val km = meters / 1000.0
    return if (km >= 10) "%,.0f km".format(km) else "%.1f km".format(km)
}

/**
 * "Last N days" as a real local-day [ClosedRange] of [Instant]s, anchored at
 * the last data point (not today — for historical Timeline data that's what
 * users mean). E.g. anchor 2026-05-21, N=7 -> 2026-05-15 00:00 .. 2026-05-21 23:59:59.
 */
private fun lastNDaysRange(anchor: Instant, days: Int): ClosedRange<Instant> {
    val zone = ZoneId.systemDefault()
    val endDate = LocalDate.ofInstant(anchor, zone)
    val startDate = endDate.minusDays((days - 1).toLong())
    val start = startDate.atStartOfDay(zone).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
    return start..end
}
