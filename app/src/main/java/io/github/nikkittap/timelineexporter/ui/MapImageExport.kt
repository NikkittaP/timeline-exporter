package io.github.nikkittap.timelineexporter.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.parser.PathPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

private const val TAG = "MapImageExport"

/**
 * How long the options must stay still before the preview re-renders. Tapping
 * through the four shapes renders only the one you stop on: each change
 * restarts the wait, and cancels a snapshot already under way.
 */
private const val PREVIEW_DEBOUNCE_MS = 250L

/** Where the preview is, for the current shape and switches. */
private sealed interface RenderState {
    data object Empty : RenderState
    data object Rendering : RenderState
    data class Ready(val bitmap: Bitmap, val aspect: MapImageAspect, val options: MapImageOptions) : RenderState
    data object Failed : RenderState
}

/** The last thing Save or Share did, shown under the switches. */
private enum class OutputStatus {
    None, RenderingHigh, Saving, Saved, SaveFailed, ShareFailed, HighFailed, HighUnavailable;

    val busy: Boolean get() = this == RenderingHigh || this == Saving
}

/**
 * The "Export map image" screen: a live preview of the picture that will be
 * written, its shape, format and resolution, the three content switches, and
 * Save / Share.
 *
 * Full screen, over whichever map opened it, so it works the same from the
 * inline preview, from the fullscreen map and from step 4. Back closes it;
 * closing it cancels a render still in flight.
 *
 * [points] and [journeys] are the main screen's own map subject - the
 * filtered track or the trips on show - so the image is the selection the
 * user is looking at, never the whole file.
 */
@Composable
internal fun MapImageExportDialog(
    points: List<PathPoint>,
    journeys: List<List<PathPoint>>,
    placeCount: Int?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MapImageExportContent(points, journeys, placeCount, onDismiss)
    }
}

@Composable
private fun MapImageExportContent(
    points: List<PathPoint>,
    journeys: List<List<PathPoint>>,
    placeCount: Int?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var aspect by rememberSaveable { mutableStateOf(MapImageAspect.Portrait) }
    var format by rememberSaveable { mutableStateOf(MapImageFormat.Png) }
    var resolution by rememberSaveable { mutableStateOf(MapImageResolution.Standard) }
    var showStats by rememberSaveable { mutableStateOf(true) }
    var showEndpoints by rememberSaveable { mutableStateOf(true) }
    var colorByDay by rememberSaveable { mutableStateOf(true) }
    val options = MapImageOptions(showStats, showEndpoints, colorByDay)

    // What this device can render, read once off the main thread. Until it
    // is known, High stays off rather than being offered on a guess.
    var limits by remember { mutableStateOf<MapImageDeviceLimits?>(null) }
    LaunchedEffect(Unit) { limits = withContext(Dispatchers.Default) { MapImageDeviceLimits.read(context) } }
    val highSupport = limits?.support(MapImageSpec(aspect, MapImageResolution.High))
    val highAvailable = highSupport == HighResolutionSupport.Supported
    // The size the file will really have. If High is not possible for this
    // shape the label below the preview says Standard's size, so the choice
    // never quietly shrinks.
    val effectiveResolution = if (resolution == MapImageResolution.High && highAvailable) resolution
    else MapImageResolution.Standard
    val outputSpec = MapImageSpec(aspect, effectiveResolution)

    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<RenderState>(if (points.isEmpty()) RenderState.Empty else RenderState.Rendering) }
    // The previous picture stays up, dimmed, while the next one renders - as
    // long as it is the same shape. Only this one bitmap is ever kept.
    var shown by remember { mutableStateOf<RenderState.Ready?>(null) }
    var status by remember { mutableStateOf(OutputStatus.None) }

    val zone = remember { ZoneId.systemDefault() }
    val stats = remember(points, journeys, placeCount) { mapImageStats(points, journeys, zone, placeCount) }
    val filename = mapImageFilename(stats, format)

    // The preview is always the Standard render: the same framing, type and
    // markers as High (see MapImageSpec), and it doubles as the Standard file.
    // Format and resolution therefore never re-render it.
    LaunchedEffect(points, journeys, aspect, options, attempt) {
        if (points.isEmpty()) {
            state = RenderState.Empty
            return@LaunchedEffect
        }
        state = RenderState.Rendering
        if (!status.busy) status = OutputStatus.None
        delay(PREVIEW_DEBOUNCE_MS)
        val spec = MapImageSpec(aspect, MapImageResolution.Standard)
        state = try {
            val bitmap = renderMapImage(context, MapImageRequest(points, journeys, placeCount, zone, options, spec))
            RenderState.Ready(bitmap, aspect, options).also { shown = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Map image failed", e)
            RenderState.Failed
        }
    }

    val ready = (state as? RenderState.Ready)?.takeIf { it.aspect == aspect && it.options == options }

    /**
     * The bitmap to write: the preview itself for Standard, a fresh render
     * at the full size for High. Null, with [status] saying why, when High
     * cannot be made - never a smaller picture in its place. The flag says
     * whether the caller owns the bitmap and should recycle it.
     */
    suspend fun produce(spec: MapImageSpec): Pair<Bitmap, Boolean>? {
        val preview = ready ?: return null
        if (spec.resolution == MapImageResolution.Standard) return preview.bitmap to false
        // Memory may have changed since the screen opened.
        val fresh = withContext(Dispatchers.Default) { MapImageDeviceLimits.read(context) }
        if (fresh.support(spec) != HighResolutionSupport.Supported) {
            status = OutputStatus.HighUnavailable
            return null
        }
        status = OutputStatus.RenderingHigh
        return try {
            renderMapImage(context, MapImageRequest(points, journeys, placeCount, zone, preview.options, spec)) to true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "High resolution map image failed", e)
            status = OutputStatus.HighFailed
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "High resolution map image ran out of memory", e)
            status = OutputStatus.HighFailed
            null
        }
    }

    // One launcher per format: CreateDocument fixes its MIME type up front.
    val onDocument: (Uri?, MapImageFormat) -> Unit = { uri, fileFormat ->
        if (uri != null) {
            val spec = outputSpec
            scope.launch {
                val (bitmap, owned) = produce(spec) ?: run {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    return@launch
                }
                status = OutputStatus.Saving
                val ok = saveImage(context, bitmap, fileFormat, uri)
                if (owned) bitmap.recycle()
                status = if (ok) OutputStatus.Saved else OutputStatus.SaveFailed
            }
        }
    }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MapImageFormat.Png.mimeType)) {
        onDocument(it, MapImageFormat.Png)
    }
    val saveJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MapImageFormat.Jpeg.mimeType)) {
        onDocument(it, MapImageFormat.Jpeg)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.map_image_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.preview_close))
                }
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                // The frame is the picture's exact shape, as large as fits.
                val ratio = aspect.widthPx.toFloat() / aspect.heightPx
                val (frameW, frameH) = if (maxWidth / maxHeight > ratio) maxHeight * ratio to maxHeight
                else maxWidth to maxWidth / ratio
                PreviewFrame(
                    width = frameW,
                    height = frameH,
                    state = state,
                    shown = shown?.takeIf { it.aspect == aspect }?.bitmap,
                    onRetry = { attempt++ },
                )
            }

            Text(
                text = stringResource(R.string.map_image_size, outputSpec.widthPx, outputSpec.heightPx, format.label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            val enabled = state !is RenderState.Empty && !status.busy
            AspectChooser(selected = aspect, enabled = enabled, onSelect = { aspect = it })

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val formatLabel = stringResource(R.string.map_image_format)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.weight(1f).semantics { contentDescription = formatLabel },
                ) {
                    MapImageFormat.entries.forEachIndexed { i, entry ->
                        SegmentedButton(
                            selected = format == entry,
                            onClick = { format = entry },
                            enabled = enabled,
                            shape = SegmentedButtonDefaults.itemShape(i, MapImageFormat.entries.size),
                            icon = {},
                        ) { ButtonLabel(entry.label) }
                    }
                }
                val resolutionLabel = stringResource(R.string.map_image_resolution)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.weight(1.25f).semantics { contentDescription = resolutionLabel },
                ) {
                    MapImageResolution.entries.forEachIndexed { i, entry ->
                        val text = when (entry) {
                            MapImageResolution.Standard -> stringResource(R.string.map_image_res_standard)
                            MapImageResolution.High -> stringResource(R.string.map_image_res_high)
                        }
                        SegmentedButton(
                            selected = effectiveResolution == entry,
                            onClick = { resolution = entry },
                            enabled = enabled && (entry == MapImageResolution.Standard || highAvailable),
                            shape = SegmentedButtonDefaults.itemShape(i, MapImageResolution.entries.size),
                            icon = {},
                        ) { ButtonLabel(text) }
                    }
                }
            }
            when (highSupport) {
                HighResolutionSupport.ExceedsGpu -> Note(stringResource(R.string.map_image_high_gpu))
                HighResolutionSupport.InsufficientMemory -> Note(stringResource(R.string.map_image_high_memory))
                else -> Unit
            }

            OptionRow(stringResource(R.string.map_image_show_stats), showStats, enabled) { showStats = it }
            OptionRow(stringResource(R.string.map_image_show_endpoints), showEndpoints, enabled) { showEndpoints = it }
            OptionRow(stringResource(R.string.map_color_by_day), colorByDay, enabled) { colorByDay = it }

            StatusLine(state = state, status = status)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val spec = outputSpec
                        val fileFormat = format
                        val name = filename
                        scope.launch {
                            val (bitmap, owned) = produce(spec) ?: return@launch
                            if (status == OutputStatus.RenderingHigh) status = OutputStatus.None
                            val ok = shareImage(context, bitmap, fileFormat, name)
                            if (owned) bitmap.recycle()
                            if (!ok) status = OutputStatus.ShareFailed
                        }
                    },
                    enabled = ready != null && !status.busy,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    ButtonLabel(stringResource(R.string.map_image_share))
                }
                Button(
                    onClick = {
                        when (format) {
                            MapImageFormat.Png -> savePng.launch(filename)
                            MapImageFormat.Jpeg -> saveJpeg.launch(filename)
                        }
                    },
                    enabled = ready != null && !status.busy,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    ButtonLabel(stringResource(R.string.map_image_save))
                }
            }
        }
    }
}

/** The four shapes as a row of chips, each with a small outline of its proportions. */
@Composable
private fun AspectChooser(selected: MapImageAspect, enabled: Boolean, onSelect: (MapImageAspect) -> Unit) {
    val groupLabel = stringResource(R.string.map_image_aspect)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .semantics { contentDescription = groupLabel },
    ) {
        MapImageAspect.entries.forEach { entry ->
            val (name, ratio) = when (entry) {
                MapImageAspect.Portrait -> stringResource(R.string.map_image_aspect_portrait) to "9:16"
                MapImageAspect.SocialPost -> stringResource(R.string.map_image_aspect_post) to "4:5"
                MapImageAspect.Square -> stringResource(R.string.map_image_aspect_square) to "1:1"
                MapImageAspect.Landscape -> stringResource(R.string.map_image_aspect_landscape) to "16:9"
            }
            FilterChip(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                enabled = enabled,
                label = { Text("$name · $ratio", maxLines = 1) },
                leadingIcon = { AspectGlyph(entry) },
            )
        }
    }
}

/** A small rectangle in the shape's own proportions. */
@Composable
private fun AspectGlyph(aspect: MapImageAspect) {
    val longSide = 16f
    val ratio = aspect.widthPx.toFloat() / aspect.heightPx
    val (w, h) = if (ratio < 1f) longSide * ratio to longSide else longSide to longSide / ratio
    Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(w.dp, h.dp)
                .border(1.5.dp, LocalContentColor.current, RoundedCornerShape(2.dp)),
        )
    }
}

/** The picture in a frame of its exact shape, with its state on top. */
@Composable
private fun PreviewFrame(width: Dp, height: Dp, state: RenderState, shown: Bitmap?, onRetry: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = if (state is RenderState.Empty) null else shown
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.map_image_preview_description),
                contentScale = ContentScale.Fit,
                alpha = if (state is RenderState.Ready) 1f else 0.45f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        when (state) {
            RenderState.Rendering -> CircularProgressIndicator()
            RenderState.Empty -> Text(
                text = stringResource(R.string.map_image_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
            RenderState.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.map_image_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.tipjar_retry)) }
            }
            is RenderState.Ready -> Unit
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OptionRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        // The row is the toggle; the switch only shows its state.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun StatusLine(state: RenderState, status: OutputStatus) {
    val (text, error) = when {
        status == OutputStatus.RenderingHigh -> stringResource(R.string.map_image_rendering_high) to false
        state is RenderState.Rendering -> stringResource(R.string.map_image_rendering) to false
        status == OutputStatus.Saving -> stringResource(R.string.map_image_saving) to false
        status == OutputStatus.Saved -> stringResource(R.string.map_image_saved) to false
        status == OutputStatus.SaveFailed -> stringResource(R.string.map_image_save_error) to true
        status == OutputStatus.ShareFailed -> stringResource(R.string.map_image_share_error) to true
        status == OutputStatus.HighFailed -> stringResource(R.string.map_image_high_error) to true
        status == OutputStatus.HighUnavailable -> stringResource(R.string.map_image_high_memory) to true
        else -> "" to false
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 20.dp)) {
        if (state is RenderState.Rendering || status.busy) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

/** One line, stepping its size down for a long translation rather than cutting a word. */
@Composable
private fun ButtonLabel(text: String) {
    val style = MaterialTheme.typography.labelLarge
    BasicText(
        text = text,
        style = style.copy(color = LocalContentColor.current),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
    )
}

/**
 * Write [bitmap] to the document the user just created. A failed write
 * deletes that document again, so a cancelled or broken save does not leave
 * an empty file behind in their Downloads.
 */
private suspend fun saveImage(context: Context, bitmap: Bitmap, format: MapImageFormat, uri: Uri): Boolean =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        try {
            val written = resolver.openOutputStream(uri, "wt")?.use { encodeMapImage(bitmap, format, it) }
            if (written != true) throw java.io.IOException("${format.label} encoder refused the bitmap")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Saving the map image failed", e)
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            false
        }
    }

/** Shared map images live in their own subfolders of the share cache, named by this prefix. */
private const val SHARE_IMAGE_DIR_PREFIX = "map-image-"

/**
 * How long a shared image is kept. The receiving app may still be reading it
 * - an upload, a slow chat client - well after the share sheet closed, so a
 * new share only clears images older than this, never the previous one.
 */
private const val SHARE_IMAGE_KEEP_MS = 60L * 60 * 1000

/**
 * Hand [bitmap] to the share sheet as a content:// URI with a one-off read
 * grant. Each share gets its own subfolder of the app's share cache, so the
 * file keeps its readable name, and stale ones are pruned by age.
 */
private suspend fun shareImage(context: Context, bitmap: Bitmap, format: MapImageFormat, filename: String): Boolean {
    val file = withContext(Dispatchers.IO) {
        try {
            val root = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
            pruneSharedImages(root, System.currentTimeMillis())
            val dir = File(root, SHARE_IMAGE_DIR_PREFIX + System.currentTimeMillis()).apply { mkdirs() }
            File(dir, filename).also { out ->
                val ok = out.outputStream().buffered().use { encodeMapImage(bitmap, format, it) }
                if (!ok) {
                    dir.deleteRecursively()
                    throw java.io.IOException("${format.label} encoder refused the bitmap")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Writing the shared map image failed", e)
            null
        }
    } ?: return false
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData too: it is what gives the share sheet its thumbnail,
            // and what the read grant travels with on newer releases.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
        true
    } catch (e: Exception) {
        Log.w(TAG, "Opening the share sheet failed", e)
        false
    }
}

/** Delete shared map images older than [SHARE_IMAGE_KEEP_MS]; younger ones may still be in use. */
internal fun pruneSharedImages(root: File, now: Long) {
    root.listFiles()?.forEach { dir ->
        if (!dir.isDirectory || !dir.name.startsWith(SHARE_IMAGE_DIR_PREFIX)) return@forEach
        val made = dir.name.removePrefix(SHARE_IMAGE_DIR_PREFIX).toLongOrNull() ?: dir.lastModified()
        if (now - made > SHARE_IMAGE_KEEP_MS) dir.deleteRecursively()
    }
}
