package io.github.nikitapetroff.timelineexporter.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nikitapetroff.timelineexporter.export.buildGpx
import io.github.nikitapetroff.timelineexporter.filter.TimelineFilter
import io.github.nikitapetroff.timelineexporter.filter.applyFilter
import io.github.nikitapetroff.timelineexporter.parser.ParsedTimeline
import io.github.nikitapetroff.timelineexporter.parser.ParserStage
import io.github.nikitapetroff.timelineexporter.parser.parseTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ---------- UI state types ----------

sealed interface TimelineUiState {
    data object Idle : TimelineUiState
    data class Loading(
        val stageLabel: String,
        val detailLabel: String? = null,
        val progress: Float? = null,
    ) : TimelineUiState
    data class Loaded(val result: ParsedTimeline) : TimelineUiState
    data class Error(val message: String) : TimelineUiState
}

sealed interface ExportState {
    data object Idle : ExportState
    data object Working : ExportState
    data class Success(val pointCount: Int, val displayName: String) : ExportState
    data class Failed(val message: String) : ExportState
}

// ---------- ViewModel ----------

class TimelineViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<TimelineUiState>(TimelineUiState.Idle)
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow(TimelineFilter())
    val filter: StateFlow<TimelineFilter> = _filter.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    // ----- parse pipeline (unchanged from previous chunk) -----

    fun onFileSelected(uri: Uri, contentResolver: ContentResolver) {
        _uiState.value = TimelineUiState.Loading(stageLabel = "Opening file…")
        // Reset transient state belonging to the previous file.
        _filter.value = TimelineFilter()
        _exportState.value = ExportState.Idle

        viewModelScope.launch {
            val newState = withContext(Dispatchers.IO) {
                try {
                    val totalBytes = queryFileSize(uri, contentResolver)
                    val json = readUriAsString(uri, contentResolver) { bytesRead ->
                        _uiState.value = TimelineUiState.Loading(
                            stageLabel = "Reading file",
                            detailLabel = formatBytesProgress(bytesRead, totalBytes),
                            progress = totalBytes
                                ?.takeIf { it > 0 }
                                ?.let { bytesRead.toFloat() / it },
                        )
                    }
                    val result = parseTimeline(json) { stage ->
                        _uiState.value = stage.toLoadingState()
                    }
                    TimelineUiState.Loaded(result)
                } catch (e: Exception) {
                    TimelineUiState.Error(
                        "Could not parse this file as a Google Timeline export.\n" +
                                "${e::class.simpleName}: ${e.message ?: "(no message)"}"
                    )
                }
            }
            _uiState.value = newState
        }
    }

    // ----- filter -----

    fun setDateRange(range: ClosedRange<Instant>) {
        _filter.value = _filter.value.copy(dateRange = range)
        _exportState.value = ExportState.Idle // any previous save no longer reflects current filter
    }

    fun clearDateRange() {
        _filter.value = _filter.value.copy(dateRange = null)
        _exportState.value = ExportState.Idle
    }

    // ----- export -----

    /**
     * Called after the user picked a destination URI via CreateDocument.
     * Builds the GPX off-thread and writes it to [uri].
     */
    fun onSaveDestinationSelected(uri: Uri, contentResolver: ContentResolver) {
        val loaded = _uiState.value as? TimelineUiState.Loaded ?: return
        val currentFilter = _filter.value
        val filteredPoints = applyFilter(loaded.result.pathPoints, currentFilter)
        if (filteredPoints.isEmpty()) {
            _exportState.value = ExportState.Failed("No points match the current filter.")
            return
        }
        _exportState.value = ExportState.Working

        viewModelScope.launch {
            val newState = withContext(Dispatchers.IO) {
                try {
                    val gpx = buildGpx(
                        points = filteredPoints,
                        trackName = "Google Timeline ${formatRangeForName(currentFilter.dateRange)}",
                    )
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(gpx.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("Could not open destination for writing.")
                    ExportState.Success(
                        pointCount = filteredPoints.size,
                        displayName = uri.lastPathSegment ?: uri.toString(),
                    )
                } catch (e: Exception) {
                    ExportState.Failed("Could not save GPX: ${e.message ?: e::class.simpleName}")
                }
            }
            _exportState.value = newState
        }
    }
}

// ---------- private helpers ----------

private fun ParserStage.toLoadingState(): TimelineUiState.Loading = when (this) {
    ParserStage.DecodingJson -> TimelineUiState.Loading(
        stageLabel = "Decoding JSON",
        detailLabel = "Large files can take a few seconds…",
        progress = null,
    )
    is ParserStage.ExtractingSegments -> TimelineUiState.Loading(
        stageLabel = "Extracting GPS points",
        detailLabel = "${formatGrouped(done)} / ${formatGrouped(total)} segments",
        progress = if (total > 0) done.toFloat() / total else null,
    )
    ParserStage.Sorting -> TimelineUiState.Loading(
        stageLabel = "Sorting points by time",
        detailLabel = null,
        progress = null,
    )
}

private fun queryFileSize(uri: Uri, resolver: ContentResolver): Long? = try {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
    }
} catch (_: Exception) {
    null
}

private fun readUriAsString(
    uri: Uri,
    resolver: ContentResolver,
    onProgress: (bytesRead: Long) -> Unit,
): String {
    val stream = resolver.openInputStream(uri)
        ?: throw IOException("Could not open file: $uri")
    return stream.use { input ->
        val buffer = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()
        var totalRead = 0L
        var lastReportMs = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
            totalRead += n
            val now = SystemClock.uptimeMillis()
            if (now - lastReportMs >= 50L) {
                onProgress(totalRead)
                lastReportMs = now
            }
        }
        onProgress(totalRead)
        out.toString(Charsets.UTF_8)
    }
}

private fun formatBytesProgress(bytesRead: Long, totalBytes: Long?): String =
    if (totalBytes != null) "${formatMb(bytesRead)} / ${formatMb(totalBytes)}"
    else formatMb(bytesRead)

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

private fun formatGrouped(n: Int): String = "%,d".format(n)

private fun formatRangeForName(range: ClosedRange<Instant>?): String {
    if (range == null) return "(full export)"
    val z = ZoneId.systemDefault()
    val from = LocalDate.ofInstant(range.start, z)
    val to = LocalDate.ofInstant(range.endInclusive, z)
    return "$from — $to"
}

/**
 * Suggest a filename for the save dialog. Public so the screen can compute
 * it without duplicating the formatting logic.
 */
fun suggestGpxFilename(filter: TimelineFilter): String {
    val range = filter.dateRange ?: return "Timeline.gpx"
    val z = ZoneId.systemDefault()
    val from = LocalDate.ofInstant(range.start, z)
    val to = LocalDate.ofInstant(range.endInclusive, z)
    return "Timeline_${from}_to_${to}.gpx"
}
