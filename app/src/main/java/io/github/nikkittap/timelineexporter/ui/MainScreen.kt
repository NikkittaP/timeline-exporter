package io.github.nikkittap.timelineexporter.ui

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nikkittap.timelineexporter.BuildConfig
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.export.AllExporters
import io.github.nikkittap.timelineexporter.export.ContentKind
import io.github.nikkittap.timelineexporter.export.CsvExporter
import io.github.nikkittap.timelineexporter.export.ExportContent
import io.github.nikkittap.timelineexporter.export.Exporter
import io.github.nikkittap.timelineexporter.export.GeoJsonExporter
import io.github.nikkittap.timelineexporter.export.GpxExporter
import io.github.nikkittap.timelineexporter.export.KmlExporter
import io.github.nikkittap.timelineexporter.filter.TimelineFilter
import io.github.nikkittap.timelineexporter.filter.applyFilter
import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.MovementStats
import io.github.nikkittap.timelineexporter.parser.totalFor
import io.github.nikkittap.timelineexporter.parser.ParsedTimeline
import io.github.nikkittap.timelineexporter.parser.PlaceEntry
import io.github.nikkittap.timelineexporter.parser.TimelineFormat
import io.github.nikkittap.timelineexporter.parser.placeEntries
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // How many exports this user has finished, and when they last declined or
    // last tipped. Owned here rather than inside MainScreen so that the tip
    // dialog above and the plaque below are reading one set of numbers; here
    // rather than in the ViewModel for the same reason the place labels are,
    // which is that reading it needs a Context.
    val tipPrompt = rememberTipPrompt()

    // Same activity-scoped instance MainScreen resolves to; read here only to
    // pre-fill the feedback diagnostics with what is currently loaded.
    val viewModel: TimelineViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Optional "a new version is available" / "restart to install it" prompt,
    // via Play's flexible in-app update flow. Never blocks anything below it.
    val appUpdateViewModel: AppUpdateViewModel = viewModel()
    val appUpdateState by appUpdateViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val updateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            // User backed out or the flow failed; nothing to recover here —
            // the Snackbar reappears next launch/resume if the update is
            // still available.
            Log.w("TimelineExporterApp", "In-app update flow returned ${result.resultCode}")
        }
    }

    // A flexible update keeps downloading while the app is backgrounded, so
    // re-check on every resume — otherwise a download that finished while the
    // user was away never surfaces the "restart" prompt.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) appUpdateViewModel.checkForUpdate()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val updateAvailableMessage = stringResource(R.string.update_available_message)
    val updateAvailableAction = stringResource(R.string.update_available_action)
    val updateDownloadedMessage = stringResource(R.string.update_downloaded_message)
    val updateDownloadedAction = stringResource(R.string.update_downloaded_action)

    LaunchedEffect(appUpdateState) {
        when (appUpdateState) {
            AppUpdateState.Available -> {
                val result = snackbarHostState.showSnackbar(
                    message = updateAvailableMessage,
                    actionLabel = updateAvailableAction,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    appUpdateViewModel.startUpdate(updateLauncher)
                } else {
                    appUpdateViewModel.dismissAvailable()
                }
            }
            AppUpdateState.Downloaded -> {
                val result = snackbarHostState.showSnackbar(
                    message = updateDownloadedMessage,
                    actionLabel = updateDownloadedAction,
                    duration = SnackbarDuration.Indefinite,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    appUpdateViewModel.completeUpdate()
                }
            }
            AppUpdateState.None -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // The bar sits on the same ground as the step cards below it
                // rather than on the card colour, so the cards read as the
                // content and the bar as the frame around it.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
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
                    // Vector icons rather than 🌐 / 💜 / ⋮: emoji draw in the
                    // emoji font's own colours and the ⋮ glyph at text weight,
                    // so the three never matched each other or the app. The
                    // heart keeps the one accent in the bar — it is the donate
                    // entry point — and the other two stay neutral.
                    IconButton(
                        onClick = { languageDialogOpen = true },
                        modifier = Modifier.semantics { contentDescription = cdLanguage },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_language),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { tipJarDialogOpen = true },
                        modifier = Modifier.semantics { contentDescription = cdSupport },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_favorite),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { overflowMenuOpen = true },
                            modifier = Modifier.semantics { contentDescription = cdMore },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
            tipPrompt = tipPrompt,
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
        // The ViewModel is hoisted out of the dialog only so that a tip made
        // here counts too. Without this, someone who tipped from the top bar
        // before their second export would still be asked again afterwards -
        // the plaque's own thank-you handler is not composed yet at that point.
        val dialogTipJar: TipJarViewModel = viewModel()
        val dialogTipState by dialogTipJar.state.collectAsStateWithLifecycle()
        LaunchedEffect(dialogTipState) {
            if (dialogTipState is TipJarState.ThankYou) tipPrompt.recordTipped()
        }
        TipJarDialog(
            onDismiss = { tipJarDialogOpen = false },
            viewModel = dialogTipJar,
        )
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
    tipPrompt: TipPromptState = rememberTipPrompt(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()

    val pendingShare by viewModel.pendingShare.collectAsStateWithLifecycle()
    val contentKind by viewModel.contentKind.collectAsStateWithLifecycle()
    val includeContainers by viewModel.includeContainers.collectAsStateWithLifecycle()

    // The user's own names for places. Lives here rather than in the ViewModel
    // because reading it needs a Context — see PlaceLabelState.
    val placeLabels = rememberPlaceLabels()

    var dateDialogOpen by remember { mutableStateOf(false) }
    var timeDialogOpen by remember { mutableStateOf(false) }
    var mapInteracting by remember { mutableStateOf(false) }
    // What the format buttons in step 4 do: write a file the user picks a home
    // for, or hand it straight to another app. Survives rotation so a chosen
    // destination is not silently reset under the user.
    var shareInstead by rememberSaveable { mutableStateOf(false) }
    var mapFullscreen by remember { mutableStateOf(false) }
    var placesFullscreen by remember { mutableStateOf(false) }
    // Non-null while the naming dialog is open, and the place it is about.
    var placeBeingNamed by remember { mutableStateOf<PlaceEntry?>(null) }
    // The one place the map and the list both point at. Held as a key rather
    // than an entry so that re-filtering — which rebuilds every PlaceEntry —
    // either keeps the selection or drops it, instead of leaving a ring around
    // a place that is no longer in the data.
    var selectedPlaceKey by rememberSaveable { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    // A successful export lands at the bottom of a four-step screen the user is
    // usually somewhere in the middle of, so both the confirmation and the
    // feedback prompt below it start off-screen. Scroll them into view.
    //
    // Keyed on exportState, which always passes through Working before the next
    // Success — so a repeated export with identical values still re-triggers.
    LaunchedEffect(exportState) {
        val finished = exportState is ExportState.Success || exportState is ExportState.Shared
        if (!finished) return@LaunchedEffect
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

    // What the user has selected, derived once at the top of the screen.
    //
    // Hoisted above the launchers on purpose: a save destination comes back
    // through a callback that fires long after the card which triggered it has
    // been recomposed, so the payload cannot be built down inside step 4. It
    // also collapses what used to be three separate derivations of the same
    // filtered list — one for the preview, one for the fullscreen map, one
    // inside the ViewModel — into the single source all three now read.
    val loadedResult = (uiState as? TimelineUiState.Loaded)?.result

    // ---- the tip ask, which can take the slot under a finished export ----
    //
    // Warmed up one export early. The plaque shows three real prices, and
    // getting them means a Play Billing connection plus a product query —
    // several hundred milliseconds that would otherwise be spent as a spinner
    // where the prices belong. Asking for the ViewModel is what starts that,
    // so it is asked for only when it is going to be used: a user whose next
    // export will not earn the ask never opens a billing connection at all.
    //
    // `|| tipAskVisible` keeps it alive while the plaque is on screen. Without
    // it, recording a tip would flip the policy to "not due" and pull the
    // ViewModel out from under the thank-you the user is still reading.
    val tipAskLikely = loadedResult != null &&
        wouldOfferTipAfterNextExport(tipPrompt.record, System.currentTimeMillis())
    var tipAskVisible by rememberSaveable { mutableStateOf(false) }
    var tipAskDeclined by rememberSaveable { mutableStateOf(false) }
    val tipJar: TipJarViewModel? =
        if (tipAskLikely || tipAskVisible) viewModel() else null
    val tipJarState = tipJar?.let { it.state.collectAsStateWithLifecycle().value }

    // Read inside the collector below, which outlives the recomposition that
    // creates the ViewModel — capturing `tipJar` directly would pin the null.
    val currentTipJar by rememberUpdatedState(tipJar)

    // Counting exports off an event rather than off exportState is what keeps
    // a rotation from counting the same export twice: a SharedFlow with no
    // replay has nothing to hand a collector that arrives afterwards.
    //
    // The decision is taken here, once, and then held. Re-deriving it on every
    // recomposition would swap the plaque out from under the user the instant
    // they declined, and would put it up with a spinner in it whenever Play
    // was still answering.
    LaunchedEffect(viewModel, tipPrompt) {
        viewModel.exportCompleted.collect {
            tipPrompt.recordSuccessfulExport()
            tipAskDeclined = false
            tipAskVisible = shouldOfferTip(tipPrompt.record, System.currentTimeMillis()) &&
                (currentTipJar?.state?.value as? TipJarState.Loaded)
                    ?.products?.isNotEmpty() == true
        }
    }

    // A tip is a tip wherever it was made — the toolbar dialog shares this
    // ViewModel — so this quiets the ask either way.
    LaunchedEffect(tipJarState) {
        if (tipJarState is TipJarState.ThankYou) tipPrompt.recordTipped()
    }

    val activity = context as? Activity
    val postExportAsk: PostExportAsk = when {
        tipAskVisible && tipJarState != null && activity != null -> PostExportAsk.Tip(
            state = tipJarState,
            onPick = { productId -> currentTipJar?.launchPurchase(activity, productId) },
            onDismiss = {
                tipPrompt.recordDeclined()
                tipAskVisible = false
                tipAskDeclined = true
            },
        )
        tipAskDeclined -> PostExportAsk.None
        else -> PostExportAsk.Feedback
    }
    val filteredPoints by remember(loadedResult, filter) {
        derivedStateOf {
            loadedResult?.let { applyFilter(it.pathPoints, filter, it.segments) } ?: emptyList()
        }
    }
    val places by remember(loadedResult, filter.dateRange, filter.timeOfDay, includeContainers) {
        derivedStateOf {
            loadedResult?.placeEntries(filter.dateRange, includeContainers, filter.timeOfDay)
                ?: emptyList()
        }
    }
    // The entry behind the selected key, and what to call it. Derived rather
    // than stored, so a change of filter — which rebuilds every PlaceEntry —
    // resolves to the new instance or to nothing at all.
    val selectedPlace = remember(places, selectedPlaceKey) {
        selectedPlaceKey?.let { key -> places.firstOrNull { it.key == key } }
    }
    val selectedPlaceName = selectedPlace?.let { placeDisplayName(it, placeLabels[it.key]) }

    /**
     * One tap selects, a second names.
     *
     * Selecting first is what makes the two halves of the step one thing: the
     * row a user taps lights up on the map, and the dot they tap lights up in
     * the list. Opening the naming dialog straight away — which is what a row
     * used to do — would mean a modal covering the map every time someone
     * wanted to know where a place was.
     */
    val onPlacePicked: (PlaceEntry?) -> Unit = { entry ->
        when {
            entry == null -> selectedPlaceKey = null
            entry.key == selectedPlaceKey -> placeBeingNamed = entry
            else -> selectedPlaceKey = entry.key
        }
    }

    val exportContent: ExportContent = when (contentKind) {
        ContentKind.TRACK -> ExportContent.Track(filteredPoints)
        ContentKind.PLACES -> ExportContent.Places(exportPlaces(places, placeLabels))
    }

    // What the map is drawing follows the Track / Places switch, so the
    // preview shows the thing step 4 is about to write rather than always the
    // track. Remembered so the instance is stable across recompositions: the
    // map re-frames its camera when the subject changes, and a new wrapper
    // every frame would re-frame every frame.
    val mapSubject = remember(contentKind, filteredPoints, places) {
        when (contentKind) {
            ContentKind.TRACK -> MapSubject.Track(filteredPoints)
            ContentKind.PLACES -> MapSubject.Places(places)
        }
    }

    // `movableContentOf` is built once by `remember`, so whatever its body
    // closes over is frozen at first composition — and `places` is a
    // `derivedStateOf` with keys, so a new file or a new filter replaces the
    // State object itself, not just its value. Routing the three moving parts
    // through `rememberUpdatedState` gives the body a holder that is created
    // once and refilled every composition, which is what keeps the map's
    // selection from silently belonging to the previously loaded file.
    val currentSelectedPlace by rememberUpdatedState(selectedPlace)
    val currentSelectedName by rememberUpdatedState(selectedPlaceName)
    val currentPlacePicked by rememberUpdatedState(onPlacePicked)
    val currentMapFullscreen by rememberUpdatedState(mapFullscreen)

    val movableMap = remember {
        movableContentOf<MapSubject, Modifier> { subject, m ->
            TimelineMap(
                subject = subject,
                fullscreen = currentMapFullscreen,
                onToggleFullscreen = { mapFullscreen = !currentMapFullscreen },
                selectedPlace = currentSelectedPlace,
                selectedPlaceName = currentSelectedName,
                onPlaceSelected = currentPlacePicked,
                onInteractionChange = { mapInteracting = it },
                modifier = m,
            )
        }
    }

    // The localized label to embed inside exported files, and the filename to
    // suggest for them. Both depend on what is being exported, so both change
    // when the user flips the Track / Places switch. They recompute per
    // recomposition; the launcher callbacks below capture the current value.
    val contentName = buildContentName(filter, contentKind)

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.onFileSelected(it, context.contentResolver) }
    }

    val gpxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(GpxExporter.mimeType),
    ) { uri ->
        uri?.let {
            viewModel.onSaveDestinationSelected(
                uri = it,
                exporter = GpxExporter,
                content = exportContent,
                name = contentName,
                contentResolver = context.contentResolver,
            )
        }
    }
    val kmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(KmlExporter.mimeType),
    ) { uri ->
        uri?.let {
            viewModel.onSaveDestinationSelected(
                uri = it,
                exporter = KmlExporter,
                content = exportContent,
                name = contentName,
                contentResolver = context.contentResolver,
            )
        }
    }
    val geojsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(GeoJsonExporter.mimeType),
    ) { uri ->
        uri?.let {
            viewModel.onSaveDestinationSelected(
                uri = it,
                exporter = GeoJsonExporter,
                content = exportContent,
                name = contentName,
                contentResolver = context.contentResolver,
            )
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(CsvExporter.mimeType),
    ) { uri ->
        uri?.let {
            viewModel.onSaveDestinationSelected(
                uri = it,
                exporter = CsvExporter,
                content = exportContent,
                name = contentName,
                contentResolver = context.contentResolver,
            )
        }
    }

    val launchSave: (Exporter) -> Unit = { exporter ->
        val name = suggestFilename(filter, exporter, contentKind)
        when (exporter) {
            GpxExporter -> gpxLauncher.launch(name)
            KmlExporter -> kmlLauncher.launch(name)
            GeoJsonExporter -> geojsonLauncher.launch(name)
            CsvExporter -> csvLauncher.launch(name)
        }
    }

    val onPickFormat: (Exporter) -> Unit = { exporter ->
        if (shareInstead) {
            viewModel.onShareRequested(
                exporter = exporter,
                content = exportContent,
                name = contentName,
                filename = suggestFilename(filter, exporter, contentKind),
                cacheDir = context.cacheDir,
            )
        } else {
            launchSave(exporter)
        }
    }

    // The ViewModel writes the file; turning it into something another app can
    // read needs a Context, so the hand-off happens here.
    //
    // No chooser title: from API 30 the system sheet ignores it for
    // ACTION_SEND, so a string set here would be translated into 18 languages
    // and never shown.
    LaunchedEffect(pendingShare) {
        val share = pendingShare ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            share.file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = share.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
        viewModel.onShareConsumed()
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
                val pickFile: () -> Unit = { pickFileLauncher.launch(arrayOf("*/*")) }

                // The filled button is the thing to do only until a file is
                // open. Once one is, it used to stay the loudest thing on the
                // screen while the step was already done; now what loaded
                // takes its place and swapping files becomes a quiet text
                // action underneath (see LoadedSummary).
                when (val state = uiState) {
                    TimelineUiState.Idle -> {
                        PickFileButton(onClick = pickFile)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    }

                    is TimelineUiState.Loading -> LoadingDisplay(state.phase)

                    is TimelineUiState.Error -> {
                        PickFileButton(onClick = pickFile)
                        Text(
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
                    }

                    is TimelineUiState.Loaded -> LoadedSummary(
                        result = state.result,
                        fileName = state.fileName,
                        onChangeFile = pickFile,
                    )
                }
            }

            // Remaining steps only make sense once a file is loaded.
            val loaded = loadedResult
            if (loaded != null) {
                val dataLast = loaded.pathPoints.lastOrNull()?.timeUtc

                StepCard(step = 2, title = stringResource(R.string.step_filter)) {
                    FilterContent(
                        currentRange = filter.dateRange,
                        dataFirst = loaded.pathPoints.firstOrNull()?.timeUtc,
                        dataLast = dataLast,
                        totalCount = loaded.pathPoints.size,
                        filteredCount = filteredPoints.size,
                        onApply = { viewModel.setDateRange(it) },
                        onClear = { viewModel.clearDateRange() },
                        onCustom = { dateDialogOpen = true },
                        onTimeOfDay = { timeDialogOpen = true },
                        parsed = loaded,
                        filter = filter,
                        includeContainers = includeContainers,
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
                ) {
                    ContentSwitch(
                        selected = contentKind,
                        enabled = exportState !is ExportState.Working,
                        onSelect = { viewModel.setContentKind(it) },
                    )
                    when (contentKind) {
                        ContentKind.TRACK -> PreviewContent(
                            subject = mapSubject,
                            inlineMapActive = !mapFullscreen,
                            mapContent = movableMap,
                        )

                        ContentKind.PLACES -> {
                            // Map first, list second. A place list is 262 rows
                            // of coordinates and counts; the one thing it
                            // cannot answer is "where is that", and until now
                            // nothing on this screen could.
                            if (!mapSubject.isEmpty) {
                                MapTile(
                                    subject = mapSubject,
                                    inlineMapActive = !mapFullscreen,
                                    mapContent = movableMap,
                                )
                            }
                            PlacesPreview(
                                entries = places,
                                labels = placeLabels,
                                dateRangeActive = filter.dateRange != null,
                                selectedKey = selectedPlaceKey,
                                onPlaceClick = onPlacePicked,
                                onShowAll = { placesFullscreen = true },
                            )
                            ToggleRow(
                                label = stringResource(R.string.places_include_containers),
                                supporting = stringResource(R.string.places_include_containers_hint),
                                checked = includeContainers,
                                onCheckedChange = { viewModel.setIncludeContainers(it) },
                            )
                        }
                    }
                }

                // The step's own title carries the Track / Places choice made
                // one card up. Someone who flips the switch and scrolls down
                // used to meet the identical word "Export" either way; now the
                // heading itself changes, before the plaque underneath spells
                // out the count.
                StepCard(
                    step = 4,
                    title = stringResource(
                        when (contentKind) {
                            ContentKind.TRACK -> R.string.step_export_track
                            ContentKind.PLACES -> R.string.step_export_places
                        }
                    ),
                ) {
                    ExportStep(
                        canSave = !exportContent.isEmpty,
                        contentKind = contentKind,
                        itemCount = exportContent.size,
                        exportState = exportState,
                        shareInstead = shareInstead,
                        onShareInsteadChange = { shareInstead = it },
                        onPickFormat = onPickFormat,
                        onFeedbackClick = onFeedbackClick,
                        postExportAsk = postExportAsk,
                    )
                }
            }

            VersionFooter(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (mapFullscreen && loadedResult != null) {
            BackHandler { mapFullscreen = false }
            ExpandedMap(
                subject = mapSubject,
                mapContent = movableMap,
            )
        }

        if (placesFullscreen && places.isNotEmpty()) {
            BackHandler { placesFullscreen = false }
            ExpandedPlaces(
                entries = places,
                labels = placeLabels,
                selectedKey = selectedPlaceKey,
                onPlaceClick = onPlacePicked,
                onClose = { placesFullscreen = false },
            )
        }
    }

    placeBeingNamed?.let { entry ->
        PlaceLabelDialog(
            entry = entry,
            currentLabel = placeLabels[entry.key],
            onSave = { label ->
                placeLabels.set(entry.key, label)
                placeBeingNamed = null
            },
            onDismiss = { placeBeingNamed = null },
        )
    }

    if (timeDialogOpen) {
        TimeOfDayDialog(
            initial = filter.timeOfDay,
            onConfirm = {
                viewModel.setTimeOfDay(it)
                timeDialogOpen = false
            },
            onDismiss = { timeDialogOpen = false },
        )
    }

    if (dateDialogOpen) {
        val dataRange = loadedResult?.let {
            it.pathPoints.firstOrNull()?.timeUtc?.let { first ->
                first..it.pathPoints.last().timeUtc
            }
        }
        DateRangeDialog(
            initialRange = filter.dateRange,
            dataRange = dataRange,
            countPoints = { range ->
                loadedResult?.let {
                    applyFilter(it.pathPoints, filter.copy(dateRange = range), it.segments).size
                } ?: 0
            },
            onConfirm = { range ->
                viewModel.setDateRange(range)
                dateDialogOpen = false
            },
            onDismiss = { dateDialogOpen = false },
        )
    }
}

// ---------- structural building blocks ----------

/**
 * One step of the flow: a numbered header and arbitrary content on a card.
 *
 * Filled, not outlined. With an outline on every card, on the map tile inside
 * it, on the chips and under the dividers, no block stood out from any other;
 * a card lifted off the tinted background by its own colour needs no line, and
 * leaves a border free to mean something where one remains (the map's edge).
 */
@Composable
private fun StepCard(
    step: Int,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** The step 1 call to action while nothing is loaded yet (or loading failed). */
@Composable
private fun PickFileButton(onClick: () -> Unit) {
    Button(onClick = onClick, shape = MaterialTheme.shapes.small) {
        Text(stringResource(R.string.pick_file_button))
    }
}

// ---------- step content ----------

/**
 * Step 1 once a file is open: which file, what kind and what span, then the
 * three numbers that describe it, then a quiet way to swap it.
 *
 * The file name leads because people keep several exports side by side
 * (Timeline_1, Timeline_merged, …) and nothing on the screen used to say
 * which one was open. The figures share one panel and one text style; the two
 * tiles this replaces set one value as a headline and the other as a small
 * title, were the heaviest thing on the screen, and carried a pink "Loaded"
 * badge for a state the rest of the screen already made obvious.
 */
@Composable
private fun LoadedSummary(result: ParsedTimeline, fileName: String?, onChangeFile: () -> Unit) {
    val formatName = formatLabel(result.format)
    val context = LocalContext.current
    val span = result.pathPoints.takeIf { it.isNotEmpty() }?.let {
        formatLocalDayRange(context, it.first().timeUtc, it.last().timeUtc)
    }
    // Days and distance both walk the whole file, so they are worked out once
    // per file and off the main thread; their cells appear when ready. Days are
    // counted the way the map's day legend counts them, so "373" here and
    // "373 days" on the map can never disagree.
    val extras by produceState<Pair<Int, Double>?>(initialValue = null, result) {
        value = withContext(Dispatchers.Default) {
            drawnTrackDays(result.pathPoints, ZoneId.systemDefault()).size to
                result.movementBreakdown().sumOf { it.distanceMeters }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_description),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = fileName ?: formatName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(formatName.takeIf { fileName != null }, span).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min).padding(vertical = 12.dp)) {
            // Weighted by how long each figure tends to be: "33,348" and
            // "14,501 km" need the room, "373" does not.
            StatCell(
                value = formatGrouped(result.pathPoints.size),
                label = stringResource(R.string.metric_points),
                modifier = Modifier.weight(1f),
            )
            val (days, meters) = extras ?: (0 to 0.0)
            if (days > 0) {
                StatDivider()
                StatCell(
                    value = formatGrouped(days),
                    label = stringResource(R.string.metric_days),
                    modifier = Modifier.weight(0.8f),
                )
            }
            if (meters > 0) {
                StatDivider()
                StatCell(
                    value = formatKm(meters),
                    label = stringResource(R.string.metric_distance),
                    modifier = Modifier.weight(1.2f),
                )
            }
        }
    }

    // Pulled left by its own inner padding so the icon lines up with the file
    // tile above instead of floating a step to the right of it.
    TextButton(
        onClick = onChangeFile,
        shape = MaterialTheme.shapes.small,
        contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
        modifier = Modifier.offset(x = (-12).dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_folder_open),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.file_change_button))
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Shrinks rather than cuts: "14.501 km" does not fit a third of a
        // 320 dp phone at full size, and a figure ending in "k…" is no figure.
        val valueStyle = MaterialTheme.typography.titleMedium
        BasicText(
            text = value,
            style = valueStyle.copy(
                fontFeatureSettings = "tnum",
                color = MaterialTheme.colorScheme.onSurface,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = valueStyle.fontSize),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatDivider() {
    VerticalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun formatLabel(format: TimelineFormat): String = when (format) {
    TimelineFormat.PHONE_TAKEOUT -> stringResource(R.string.format_phone)
    TimelineFormat.PHONE_TAKEOUT_ARRAY -> stringResource(R.string.format_phone_ios)
    TimelineFormat.SEMANTIC_LOCATION_HISTORY -> stringResource(R.string.format_semantic)
    TimelineFormat.RECORDS -> stringResource(R.string.format_records)
    TimelineFormat.UNKNOWN -> stringResource(R.string.format_unknown)
}

/**
 * Step 2: which days, which hours, which kinds of movement, and how much of
 * the file is left once all three have had their say.
 *
 * The date range is a field that shows its own value, with four equal presets
 * under it. It used to be a sentence above six chips of different widths that
 * wrapped three and three: "Custom…" never lit up even while a custom range
 * was applied, and "Time of day" shared the row though it answers a different
 * question. Now the field is the custom range (tap it for the calendar), the
 * presets are one segmented row that cannot wrap, and time of day and movement
 * are two rows of the same shape, each stating its answer under its name.
 */
@Composable
private fun FilterContent(
    currentRange: ClosedRange<Instant>?,
    dataFirst: Instant?,
    dataLast: Instant?,
    totalCount: Int,
    filteredCount: Int,
    onApply: (ClosedRange<Instant>) -> Unit,
    onClear: () -> Unit,
    onCustom: () -> Unit,
    onTimeOfDay: () -> Unit,
    parsed: ParsedTimeline,
    filter: TimelineFilter,
    includeContainers: Boolean,
    onToggleMovement: (MovementGroup, Boolean, Set<MovementGroup>) -> Unit,
    onMovingOnly: (Boolean) -> Unit,
    onDropRepeated: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // With no range applied the field shows the file's own span rather than
    // "all dates": the dates a user wants to see are the ones they would be
    // narrowing from.
    val shown = currentRange
        ?: if (dataFirst != null && dataLast != null) dataFirst..dataLast else null
    DateRangeField(
        value = shown?.let { formatLocalDayRange(context, it.start, it.endInclusive) } ?: "—",
        onClick = onCustom,
    )

    if (dataLast != null) {
        // "Last N days" counts back from the end of the file, not from today:
        // for a historical export that is what the words mean.
        val presets: List<Pair<ClosedRange<Instant>?, String>> = listOf(
            lastNDaysRange(dataLast, 7) to pluralStringResource(R.plurals.map_day_count, 7, 7),
            lastNDaysRange(dataLast, 30) to pluralStringResource(R.plurals.map_day_count, 30, 30),
            lastNDaysRange(dataLast, 90) to pluralStringResource(R.plurals.map_day_count, 90, 90),
            null to stringResource(R.string.date_preset_all_short),
        )
        ChoiceRow(
            labels = presets.map { it.second },
            // -1 while a range from the calendar is applied: none of the four
            // is true then, and the field above already says what is.
            selectedIndex = presets.indexOfFirst { it.first == currentRange },
            onSelect = { index -> presets[index].first?.let(onApply) ?: onClear() },
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    // No spacing of its own, so the two rows sit together as a list instead of
    // taking the step's gaps between them.
    Column {
        SettingRow(
            iconRes = R.drawable.ic_schedule,
            title = stringResource(R.string.filter_time_any),
            value = timeOfDayValueLabel(filter.timeOfDay),
            onClick = onTimeOfDay,
        ) {
            RowChevron()
        }
        MovementSection(
            parsed = parsed,
            filter = filter,
            includeContainers = includeContainers,
            onToggleMovement = onToggleMovement,
            onMovingOnly = onMovingOnly,
            onDropRepeated = onDropRepeated,
        )
    }

    FilterCount(filtered = filteredCount, total = totalCount)
}

/**
 * The date range as a field: what is applied (or, with nothing applied, the
 * span of the file), and the way into the calendar. It replaces both the
 * "Date range: …" sentence and the "Custom…" chip, which described one state
 * in two places and never agreed about it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeField(value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_calendar),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.metric_date_range),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = value, style = MaterialTheme.typography.bodyLarge)
            }
            RowChevron()
        }
    }
}

/**
 * The four date presets: a segmented row drawn to match Material's
 * SegmentedButton (40 dp, outline, secondaryContainer when on, 12 dp corners
 * like the Track / Places switch), but built by hand.
 *
 * Material's version could not hold these labels. It keeps 12 dp of padding
 * either side of every label and reserves room for a tick, which on a 360 dp
 * phone leaves about 50 dp of text per segment, and on a 320 dp phone 40:
 * "90 days" fit in English and became "90 T…" in German, "30 д…" in Russian.
 * Here each segment keeps 4 dp of padding, there is no tick (the filled segment
 * already says which one is on), and a label that still does not fit steps its
 * size down rather than being cut.
 *
 * Step 4's Save / Send switch uses it too, for the same reason in the other
 * direction: in Material's version "Отправить в приложение" wrapped to two
 * lines and pushed the row out of shape. Two segments have room to spare, so
 * that row asks for [labelPadding] wide enough that a long label does not run
 * into the outline.
 */
@Composable
private fun ChoiceRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
    labelPadding: Dp = 4.dp,
) {
    val shape = MaterialTheme.shapes.small
    val outline = if (enabled) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .border(1.dp, outline, shape)
            .selectableGroup(),
    ) {
        labels.forEachIndexed { index, label ->
            if (index > 0) VerticalDivider(color = outline)
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = labelPadding),
                contentAlignment = Alignment.Center,
            ) {
                val style = MaterialTheme.typography.labelLarge
                BasicText(
                    text = label,
                    style = style.copy(
                        color = when {
                            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            selected -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
                )
            }
        }
    }
}

/**
 * A row that states one filter and leads to where it is changed: an icon, what
 * the filter is, what it is set to, and a trailing affordance.
 *
 * Padded 12 dp at the sides so its icon lines up with the calendar icon inside
 * the date field above and with the tick boxes the movement row expands into.
 */
@Composable
private fun SettingRow(
    @DrawableRes iconRes: Int,
    title: String,
    value: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
private fun RowChevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * How much of the file survives the filters: the sentence it always was, plus
 * a bar. "33,348 of 33,348" and "9,748 of 33,348" look alike at a glance; a
 * full bar and a third of one do not.
 */
@Composable
private fun FilterCount(filtered: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(
                R.string.filter_filtered_count,
                formatGrouped(filtered),
                formatGrouped(total),
            ),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fraction = if (total > 0) (filtered.toFloat() / total).coerceIn(0f, 1f) else 0f
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
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
    includeContainers: Boolean,
    onToggleMovement: (MovementGroup, Boolean, Set<MovementGroup>) -> Unit,
    onMovingOnly: (Boolean) -> Unit,
    onDropRepeated: (Boolean) -> Unit,
) {
    val breakdown = remember(parsed, filter.dateRange, filter.timeOfDay) {
        parsed.movementBreakdown(filter.dateRange, filter.timeOfDay)
    }
    if (breakdown.isEmpty()) return

    val available = remember(breakdown) { breakdown.map { it.group }.toSet() }
    val selected = filter.movements ?: available
    // Counted on the same terms the Places list uses, so the "236 places" in
    // this summary and the length of that list can never disagree.
    val places = remember(parsed, filter.dateRange, filter.timeOfDay, includeContainers) {
        parsed.placeCount(filter.dateRange, includeContainers, filter.timeOfDay)
    }
    // The distance for the chosen dates, over the groups actually ticked —
    // what the export will contain. Shown in the collapsed summary so it is
    // on screen without opening the expander: the per-group rows alone left
    // users asking where the total for their dates was (FB-018).
    val total = remember(breakdown, selected) { breakdown.totalFor(selected) }
    val labels = movementLabels()

    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrowTurn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "movementArrow",
    )

    SettingRow(
        iconRes = R.drawable.ic_content_track,
        title = stringResource(R.string.filter_movement_title),
        value = movementSummaryLabel(selected, available, filter.movingOnly, total, places, labels),
        onClick = { expanded = !expanded },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_expand_more),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(arrowTurn),
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

        // One group needs no sum: its own row already is the total.
        if (total != null && breakdown.count { it.group in selected } > 1) {
            MovementTotalRow(total)
        }

        if (places > 0) {
            Text(
                text = stringResource(R.string.places_count, formatGrouped(places)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
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

/**
 * The ticked groups summed, lined up under [MovementRow]'s columns: the
 * checkbox's width left empty so "Total" sits under the group names and the
 * kilometres under theirs.
 */
@Composable
private fun MovementTotalRow(total: MovementStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(48.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.filter_movement_total),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.filter_trip_count, formatGrouped(total.trips)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatKm(total.distanceMeters),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
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
            // The same 12 dp inset as the filter rows and tick boxes above, so
            // the labels share their left edge; and a gap before the switch, so
            // a long hint wraps instead of running into it.
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
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
    total: MovementStats?,
    places: Int,
    labels: Map<MovementGroup, String>,
): String {
    val parts = mutableListOf(
        when {
            selected.isEmpty() -> stringResource(R.string.filter_movement_none)
            // Just "All": the row's title already says "Movement".
            selected == available -> stringResource(R.string.date_preset_all_short)
            // Name whichever side is shorter. Listing four of five modes spills
            // past the row's two lines in longer languages and pushes the
            // distance and places off the end.
            (available - selected).size < selected.size -> stringResource(
                R.string.filter_movement_all_except,
                (available - selected).sortedBy { it.ordinal }
                    .joinToString(", ") { labels[it] ?: it.name },
            )
            else -> selected.sortedBy { it.ordinal }
                .joinToString(", ") { labels[it] ?: it.name }
        }
    )
    total?.takeIf { it.distanceMeters > 0 }?.let { parts += formatKm(it.distanceMeters) }
    if (movingOnly) parts += stringResource(R.string.filter_moving_only_short)
    if (places > 0) parts += stringResource(R.string.places_count, formatGrouped(places))
    return parts.joinToString(" · ")
}

@Composable
private fun PreviewContent(
    subject: MapSubject,
    inlineMapActive: Boolean,
    mapContent: @Composable (MapSubject, Modifier) -> Unit,
) {
    if (subject.isEmpty) {
        Text(
            stringResource(R.string.preview_no_points),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        MapTile(subject, inlineMapActive, mapContent)
    }
}

/**
 * How tall the inline map is.
 *
 * It was 280 dp, and the `Color by day` card is about half of that once its
 * legend row appears, so most of the tile was a control panel with a strip of
 * map under it. This is that 280 dp of map plus room for the card on top of
 * it, which is what makes the preview a preview rather than a hint of one.
 * Full screen remains the way to look at a route properly; this is the size
 * at which it is worth glancing at.
 */
private val MAP_TILE_HEIGHT = 420.dp

/**
 * The bordered tile the map lives in.
 *
 * [inlineMapActive] is false while the fullscreen map is up: the map itself is
 * `movableContent` and can only be in one place at a time, so the tile stays
 * as a hole of the right size rather than collapsing the step under it.
 */
@Composable
private fun MapTile(
    subject: MapSubject,
    inlineMapActive: Boolean,
    mapContent: @Composable (MapSubject, Modifier) -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MAP_TILE_HEIGHT)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        if (inlineMapActive) {
            mapContent(subject, Modifier.fillMaxSize())
        }
    }
}

/**
 * The Track / Places switch that sits at the top of step 3 and decides what
 * step 4 writes.
 *
 * In step 3 rather than in step 4, even though it is step 4 that acts on it:
 * the choice is "what am I looking at", and the preview underneath is the
 * fastest way to tell whether it is the right one. Step 4 does not repeat the
 * control — it repeats the *answer*, in its heading and in the plaque above
 * the format buttons (see [ExportSubjectPlaque]), so the two cards can never
 * disagree about which one is live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentSwitch(
    selected: ContentKind,
    enabled: Boolean,
    onSelect: (ContentKind) -> Unit,
) {
    val kinds = ContentKind.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        kinds.forEachIndexed { index, kind ->
            SegmentedButton(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = kinds.size,
                    baseShape = MaterialTheme.shapes.small,
                ),
            ) {
                Text(
                    when (kind) {
                        ContentKind.TRACK -> stringResource(R.string.content_track)
                        ContentKind.PLACES -> stringResource(R.string.content_places)
                    }
                )
            }
        }
    }
}

/**
 * The first thing in step 4: an icon, the kind, and the count of what the
 * format buttons underneath are about to write.
 *
 * A bare "313 places" line lived here first and was not enough. The Track /
 * Places switch sits two cards up in step 3, where it reads as "what am I
 * looking at"; by the time the user has scrolled past the preview to the
 * format buttons, nothing on screen said which of the two those buttons would
 * export, and a one-line caption above four buttons is exactly the kind of
 * text people skip. Flipping the switch therefore looked like it did nothing.
 *
 * Deliberately a neutral `surfaceContainer` panel with a tinted icon, and not a
 * `secondaryContainer` one: `FilledTonalButton` — the GPX / KML / GeoJSON /
 * CSV row directly below — is `secondaryContainer` by default, so a tonal
 * plaque would be the same colour as the buttons it introduces and would read
 * as one more (broken) control. Neutral ground plus a primary-tinted icon
 * gives it the weight without the affordance.
 *
 * Read-only on purpose. The switch stays the single place the choice is made;
 * duplicating it here would put two controls on one piece of state, and the
 * plaque would then have to answer "which one is the real one?" every time
 * they are both on screen.
 */
@Composable
private fun ExportSubjectPlaque(contentKind: ContentKind, itemCount: Int) {
    val kindLabel = stringResource(
        when (contentKind) {
            ContentKind.TRACK -> R.string.content_track
            ContentKind.PLACES -> R.string.content_places
        }
    )
    val countLabel = stringResource(
        when (contentKind) {
            ContentKind.TRACK -> R.string.points_count
            ContentKind.PLACES -> R.string.places_count
        },
        formatGrouped(itemCount),
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    when (contentKind) {
                        ContentKind.TRACK -> R.drawable.ic_content_track
                        ContentKind.PLACES -> R.drawable.ic_content_places
                    }
                ),
                // The two lines next to it say the same thing in words, and a
                // screen reader announcing "route icon, this will write, track
                // — 313 points" is worse than the sentence alone.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.export_writes_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(
                        R.string.export_writes_value,
                        kindLabel,
                        countLabel,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ExportStep(
    canSave: Boolean,
    contentKind: ContentKind,
    itemCount: Int,
    exportState: ExportState,
    shareInstead: Boolean,
    onShareInsteadChange: (Boolean) -> Unit,
    onPickFormat: (Exporter) -> Unit,
    onFeedbackClick: () -> Unit = {},
    postExportAsk: PostExportAsk = PostExportAsk.Feedback,
) {
    val enabled = canSave && exportState !is ExportState.Working

    ExportSubjectPlaque(contentKind = contentKind, itemCount = itemCount)

    // Destination first, format second — one row of format buttons either way.
    // The alternative, a second row of "share as GPX / KML / …" buttons, would
    // double the controls in this step to express one binary choice.
    ChoiceRow(
        labels = listOf(
            stringResource(R.string.export_action_save),
            stringResource(R.string.export_action_share),
        ),
        selectedIndex = if (shareInstead) 1 else 0,
        onSelect = { onShareInsteadChange(it == 1) },
        enabled = enabled,
        labelPadding = 16.dp,
    )

    // One row of four equal buttons, lined up with the switch above. A FlowRow
    // of content-sized buttons put three on the first line and CSV alone on the
    // second, each a different width. Material's 24 dp side padding is what
    // pushed CSV down; 4 dp is enough here, and on a phone too narrow even for
    // that, "GeoJSON" steps its size down like the switch's labels do.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AllExporters.forEach { exporter ->
            FilledTonalButton(
                onClick = { onPickFormat(exporter) },
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                val style = MaterialTheme.typography.labelLarge
                BasicText(
                    text = exporter.displayName,
                    style = style.copy(color = LocalContentColor.current),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
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
        is ExportState.Success -> {
            Text(
                // Phrased by what was written, not by what is selected now:
                // the switch may have moved since, and "saved 2,481 places"
                // would be a plainly wrong sentence about a real file.
                text = stringResource(
                    when (s.kind) {
                        ContentKind.TRACK -> R.string.export_success
                        ContentKind.PLACES -> R.string.export_success_places
                    },
                    formatGrouped(s.itemCount),
                    s.displayName ?: stringResource(R.string.export_fallback_filename),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            PostExportPrompt(ask = postExportAsk, onFeedbackClick = onFeedbackClick)
        }
        is ExportState.Shared -> {
            // Deliberately not phrased as "sent": the share sheet is open and
            // the user may still back out of it, and the app is never told
            // which app they picked or whether they picked one.
            Text(
                text = stringResource(
                    when (s.kind) {
                        ContentKind.TRACK -> R.string.export_shared
                        ContentKind.PLACES -> R.string.export_shared_places
                    },
                    formatGrouped(s.itemCount),
                    s.formatName,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            PostExportPrompt(ask = postExportAsk, onFeedbackClick = onFeedbackClick)
        }
        ExportState.Failure.NoPoints -> Text(
            text = stringResource(
                when (contentKind) {
                    ContentKind.TRACK -> R.string.export_error_no_points
                    ContentKind.PLACES -> R.string.export_error_no_places
                }
            ),
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
 * What goes in the one slot under a finished export.
 *
 * There is exactly one slot and it holds at most one ask. Two favours stacked
 * on each other — tell me what is missing, and also, would you pay — devalue
 * each other, so they take turns: the tip plaque on the exports that earn it
 * (see [shouldOfferTip]), the feedback plaque the rest of the time.
 */
internal sealed interface PostExportAsk {
    /** The feature request. The default, and what every export showed before tips. */
    data object Feedback : PostExportAsk

    /** The tip ask, built only once Play has returned prices. */
    class Tip(
        val state: TipJarState,
        val onPick: (String) -> Unit,
        val onDismiss: () -> Unit,
    ) : PostExportAsk {
        /**
         * Play answers OK with an empty product list when the tips are not
         * configured against this package, or not sold where the user is.
         * That is not an ask, it is a question with nothing underneath it to
         * answer, so the slot goes back to the feedback plaque instead.
         */
        val hasSomethingToOffer: Boolean =
            state !is TipJarState.Loaded || state.products.isNotEmpty()
    }

    /** Nothing: the user has just said "Not now", and one no settles one export. */
    data object None : PostExportAsk
}

@Composable
internal fun PostExportPrompt(ask: PostExportAsk, onFeedbackClick: () -> Unit) {
    when {
        ask is PostExportAsk.Tip && ask.hasSomethingToOffer -> TipPrompt(ask)
        ask is PostExportAsk.None -> Unit
        else -> FeedbackPrompt(onClick = onFeedbackClick)
    }
}

/**
 * The tip ask: the same tertiary plaque as [FeedbackPrompt], in the same slot,
 * with the three amounts as buttons instead of a single link.
 *
 * The prices are not string resources. They come from Play already localized
 * and converted into the user's currency, which is also why the plaque is only
 * ever built after they have arrived — see the warm-up in `MainScreen`.
 *
 * The row borrows step 4's format-button geometry, down to the 4 dp of
 * horizontal padding and the label that steps its own size down: three prices
 * side by side on a narrow phone is the same problem as four format names, and
 * a price in rubles or yen is wider than "GPX" or "$1.00" ever gets.
 *
 * The amount buttons are `tertiary` rather than the tonal default, which is
 * `secondaryContainer` — on a tertiary plaque that would have read as a button
 * borrowed from somewhere else. The dismiss keeps the plaque's own content
 * colour, the way the feedback action does, so that saying no stays quieter
 * than saying yes without being hidden.
 */
@Composable
internal fun TipPrompt(ask: PostExportAsk.Tip) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.tip_prompt_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                text = stringResource(R.string.tip_prompt_body),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            when (val state = ask.state) {
                is TipJarState.Loaded -> {
                    TipAmountRow(products = state.products, onPick = ask.onPick)
                    TipDismissButton(onClick = ask.onDismiss)
                }
                // Play's own sheet is on screen on top of all this; the plaque
                // only has to stop offering buttons underneath it.
                TipJarState.Processing -> TipPlaqueLine(stringResource(R.string.tipjar_processing))
                TipJarState.ThankYou -> Text(
                    text = stringResource(R.string.tipjar_thank_you),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
                // Reachable only if Play fails between the plaque appearing and
                // the purchase completing. No retry offered on purpose: the
                // whole point of this placement is that the user is finished
                // and free to leave, and the toolbar heart still opens the
                // full dialog, which does have one.
                is TipJarState.Error -> {
                    TipPlaqueLine(stringResource(R.string.tipjar_error))
                    TipDismissButton(onClick = ask.onDismiss)
                }
                else -> TipDismissButton(onClick = ask.onDismiss)
            }
        }
    }
}

/** The three amounts, in Play's order, each button as wide as the next. */
@Composable
private fun TipAmountRow(products: List<TipProduct>, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        products.forEach { product ->
            // The emoji is ours and the price is Play's. A screen reader gets
            // the product's own name instead, which is the only part of this
            // that reads as words.
            val spokenLabel = "${product.name} — ${product.formattedPrice}"
            FilledTonalButton(
                onClick = { onPick(product.id) },
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = spokenLabel },
            ) {
                val style = MaterialTheme.typography.labelLarge
                BasicText(
                    text = "${product.emoji} ${product.formattedPrice}",
                    style = style.copy(color = LocalContentColor.current),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 10.sp,
                        maxFontSize = style.fontSize,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TipDismissButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.tip_prompt_dismiss),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** A status line inside the plaque, indented to sit under the two lines above. */
@Composable
private fun TipPlaqueLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/**
 * The feedback ask, shown after a successful export — the one moment the user
 * demonstrably got what they came for. Shares that slot with [TipPrompt];
 * [PostExportAsk] decides which of them any given export gets.
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
        shape = MaterialTheme.shapes.medium,
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
            progress = phase.fraction
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
internal fun ExpandedMap(
    subject: MapSubject,
    mapContent: @Composable (MapSubject, Modifier) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // Keep the same rounded, bordered "Preview tile" framing as the inline
        // map, just enlarged with a margin around it — not edge-to-edge.
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val shape = MaterialTheme.shapes.medium
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            ) {
                mapContent(subject, Modifier.fillMaxSize())
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
 * Build the human-readable label that gets embedded inside exported
 * GPX/KML/GeoJSON files — the track's `<name>`, or the document's for a places
 * export. Composed from string resources so it follows the active locale.
 *
 * The two read differently on purpose. Opened in Google Earth, one file says
 * "Google Timeline 9 Jun 2025 — 21 May 2026" and the other "Google Timeline
 * places …", which is the only thing distinguishing them once they are sitting
 * in the same folder as two orange layers.
 */
@Composable
private fun buildContentName(filter: TimelineFilter, kind: ContentKind): String {
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
    // The window is appended to the range rather than given its own slot in
    // the name template, so the 18 translations of that template did not have
    // to change and a name without a window reads exactly as it did before.
    val window = filter.timeOfDay?.takeIf { !it.isWholeDay }
    val described = if (window == null) {
        rangePart
    } else {
        stringResource(
            R.string.track_name_time_window,
            rangePart,
            formatMinuteOfDay(window.startMinute),
            formatMinuteOfDay(window.endMinute),
        )
    }
    return stringResource(
        when (kind) {
            ContentKind.TRACK -> R.string.track_name
            ContentKind.PLACES -> R.string.track_name_places
        },
        described,
    )
}

// ---------- shared shapes / formatting helpers ----------

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
    val endDate = anchor.atZone(zone).toLocalDate()
    val startDate = endDate.minusDays((days - 1).toLong())
    val start = startDate.atStartOfDay(zone).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
    return start..end
}
