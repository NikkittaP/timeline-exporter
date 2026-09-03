package io.github.nikkittap.timelineexporter.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nikkittap.timelineexporter.export.Exporter
import io.github.nikkittap.timelineexporter.filter.TimelineFilter
import io.github.nikkittap.timelineexporter.filter.applyFilter
import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.NotTimelineFileException
import io.github.nikkittap.timelineexporter.parser.ParsedTimeline
import io.github.nikkittap.timelineexporter.parser.ParserStage
import io.github.nikkittap.timelineexporter.parser.parseTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId

// ---------- UI state types ----------
//
// The VM emits SEMANTIC state: "we're reading file, X of Y bytes" — not
// a pre-formatted English string. The UI layer (MainScreen) is the only
// place that turns these into localized strings via stringResource.

/** Discrete stages of the file→parsed pipeline. */
sealed interface LoadingPhase {
    data object OpeningFile : LoadingPhase
    data class ReadingFile(val bytesRead: Long, val totalBytes: Long?) : LoadingPhase
    data object DecodingJson : LoadingPhase
    data class ExtractingPoints(val done: Int, val total: Int) : LoadingPhase
    data object SortingPoints : LoadingPhase
}

sealed interface TimelineUiState {
    data object Idle : TimelineUiState
    data class Loading(val phase: LoadingPhase) : TimelineUiState
    data class Loaded(val result: ParsedTimeline) : TimelineUiState
    /**
     * Parse failed. When [wrongFile] is true the user simply picked something
     * that isn't a Timeline export (or an unreadable/binary file); the UI shows
     * a short friendly message and hides the technical detail. When false it's
     * an unexpected error and [exceptionClass]/[exceptionMessage] are shown to
     * aid bug reports. exceptionMessage is null when the exception had none.
     */
    data class Error(
        val wrongFile: Boolean,
        val exceptionClass: String,
        val exceptionMessage: String?,
    ) : TimelineUiState
}

sealed interface ExportState {
    data object Idle : ExportState
    data object Working : ExportState
    /** [displayName] may be null if the content provider didn't expose one. */
    data class Success(val pointCount: Int, val displayName: String?) : ExportState

    /**
     * The export was written to the cache and handed to the system share
     * sheet. Distinct from [Success] because nothing was saved anywhere the
     * user can go back to — telling them a file exists would be a lie — and
     * because we never learn which app they picked, or whether they picked
     * one at all.
     */
    data class Shared(val pointCount: Int, val formatName: String) : ExportState
    /** Semantic failure kind — UI composes the localized message. */
    sealed interface Failure : ExportState {
        data object NoPoints : Failure
        /**
         * Anything else. [formatName] e.g. "GPX", [exceptionClass] e.g.
         * "IOException", [exceptionMessage] e.g. "Permission denied".
         */
        data class Generic(
            val formatName: String,
            val exceptionClass: String,
            val exceptionMessage: String?,
        ) : Failure
    }
}

/**
 * An export written to the cache directory, waiting for the UI to hand it to
 * another app.
 *
 * The file has to exist on disk before it can be shared: `ACTION_SEND` passes
 * a `content://` URI, not bytes. The ViewModel writes it and stops there —
 * building the URI needs a `Context` and a FileProvider authority, which are
 * the UI layer's business.
 */
data class PendingShare(val file: File, val mimeType: String)

/** Cache subdirectory backing the FileProvider path of the same name. */
const val SHARE_CACHE_DIR = "shared"

// ---------- ViewModel ----------

class TimelineViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<TimelineUiState>(TimelineUiState.Idle)
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    private val _filter = MutableStateFlow(TimelineFilter())
    val filter: StateFlow<TimelineFilter> = _filter.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _pendingShare = MutableStateFlow<PendingShare?>(null)
    val pendingShare: StateFlow<PendingShare?> = _pendingShare.asStateFlow()

    // ----- parse pipeline -----

    fun onFileSelected(uri: Uri, contentResolver: ContentResolver) {
        _uiState.value = TimelineUiState.Loading(LoadingPhase.OpeningFile)
        _filter.value = TimelineFilter()
        _exportState.value = ExportState.Idle
        _pendingShare.value = null

        viewModelScope.launch {
            val newState = withContext(Dispatchers.IO) {
                try {
                    val totalBytes = queryFileSize(uri, contentResolver)
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open file: $uri")
                    // Stream straight from the file: never materialize the whole
                    // document as a String or object tree, so memory stays
                    // proportional to the extracted points, not to file size.
                    val result = CountingInputStream(BufferedInputStream(input)).use { stream ->
                        parseTimeline(stream) { stage ->
                            _uiState.value = TimelineUiState.Loading(
                                when (stage) {
                                    ParserStage.DecodingJson ->
                                        LoadingPhase.ReadingFile(stream.bytesRead, totalBytes)
                                    is ParserStage.ExtractingSegments ->
                                        LoadingPhase.ReadingFile(stream.bytesRead, totalBytes)
                                    ParserStage.Sorting -> LoadingPhase.SortingPoints
                                }
                            )
                        }
                    }
                    TimelineUiState.Loaded(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NotTimelineFileException) {
                    // User picked a non-Timeline file: friendly message, no detail.
                    TimelineUiState.Error(
                        wrongFile = true,
                        exceptionClass = e::class.simpleName ?: "Exception",
                        exceptionMessage = e.message,
                    )
                } catch (e: Throwable) {
                    // Catch-all incl. OutOfMemoryError so a huge/binary file can
                    // never crash the app. Malformed JSON (decode failures),
                    // out-of-memory, and read errors all land here and are shown
                    // as the friendly "wrong file" message rather than a stack
                    // trace. Anything truly unexpected keeps the technical detail.
                    val wrong = e is OutOfMemoryError ||
                        e is java.io.IOException
                    TimelineUiState.Error(
                        wrongFile = wrong,
                        exceptionClass = e::class.simpleName ?: "Throwable",
                        exceptionMessage = e.message,
                    )
                }
            }
            _uiState.value = newState
        }
    }

    // ----- filter -----

    fun setDateRange(range: ClosedRange<Instant>) {
        _filter.value = _filter.value.copy(dateRange = range)
        _exportState.value = ExportState.Idle
    }

    fun clearDateRange() {
        _filter.value = _filter.value.copy(dateRange = null)
        _exportState.value = ExportState.Idle
    }

    /**
     * Add or remove one movement group from the selection.
     *
     * null means "no constraint", and that is what the app starts from. The
     * first untick has to turn that into a concrete set, and the set it becomes
     * is every group *except* the one being unticked — otherwise unticking
     * "walking" would read as selecting only walking.
     *
     * Selecting everything collapses back to null, so a filter the user has
     * effectively cleared stops counting as one. Selecting nothing is left as
     * an empty set rather than being bounced back to null: it really does match
     * no points, the count under the chips says so, and export is disabled —
     * quietly reinterpreting it as "everything" would be worse.
     */
    fun toggleMovement(group: MovementGroup, enabled: Boolean, available: Set<MovementGroup>) {
        val current = _filter.value.movements ?: available
        val updated = if (enabled) current + group else current - group
        _filter.value = _filter.value.copy(
            movements = if (updated == available) null else updated,
        )
        _exportState.value = ExportState.Idle
    }

    fun setMovingOnly(enabled: Boolean) {
        _filter.value = _filter.value.copy(movingOnly = enabled)
        _exportState.value = ExportState.Idle
    }

    fun setDropRepeatedPoints(enabled: Boolean) {
        _filter.value = _filter.value.copy(dropRepeatedPoints = enabled)
        _exportState.value = ExportState.Idle
    }

    // ----- export -----

    /**
     * Called after the user picked a destination URI via CreateDocument.
     * [trackName] is the (already-localized) string the UI wants embedded
     * inside the exported file as the track's human label.
     */
    fun onSaveDestinationSelected(
        uri: Uri,
        exporter: Exporter,
        trackName: String,
        contentResolver: ContentResolver,
    ) {
        val loaded = _uiState.value as? TimelineUiState.Loaded ?: return
        val currentFilter = _filter.value
        val filteredPoints =
            applyFilter(loaded.result.pathPoints, currentFilter, loaded.result.segments)
        if (filteredPoints.isEmpty()) {
            _exportState.value = ExportState.Failure.NoPoints
            return
        }
        _exportState.value = ExportState.Working

        viewModelScope.launch {
            val newState = withContext(Dispatchers.IO) {
                try {
                    val content = exporter.export(filteredPoints, trackName)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("openOutputStream returned null for $uri")
                    ExportState.Success(
                        pointCount = filteredPoints.size,
                        displayName = queryDisplayName(uri, contentResolver),
                    )
                } catch (e: Exception) {
                    ExportState.Failure.Generic(
                        formatName = exporter.displayName,
                        exceptionClass = e::class.simpleName ?: "Exception",
                        exceptionMessage = e.message,
                    )
                }
            }
            _exportState.value = newState
        }
    }

    /**
     * Write the current selection to a file in the cache and offer it to
     * another app, instead of saving it somewhere the user picked.
     *
     * Requested as "send the export straight to another app instead of saving
     * a file" — the point is to skip the storage round-trip entirely when the
     * destination is a mail client or a map app, so this deliberately does not
     * go through SAF.
     *
     * [cacheDir] rather than a Context: the ViewModel needs a directory, not
     * an Android environment, and taking the narrower type keeps it testable.
     */
    fun onShareRequested(exporter: Exporter, trackName: String, cacheDir: File) {
        val loaded = _uiState.value as? TimelineUiState.Loaded ?: return
        val currentFilter = _filter.value
        val filteredPoints =
            applyFilter(loaded.result.pathPoints, currentFilter, loaded.result.segments)
        if (filteredPoints.isEmpty()) {
            _exportState.value = ExportState.Failure.NoPoints
            return
        }
        _exportState.value = ExportState.Working

        viewModelScope.launch {
            val newState = withContext(Dispatchers.IO) {
                try {
                    val dir = File(cacheDir, SHARE_CACHE_DIR)
                    dir.mkdirs()
                    // Only ever one share pending. The previous file has
                    // already been handed over, and a full Timeline export can
                    // be tens of megabytes — leaving a pile of them in the
                    // cache for the system to reap eventually is not polite.
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, suggestFilename(currentFilter, exporter))
                    file.writeText(exporter.export(filteredPoints, trackName), Charsets.UTF_8)
                    _pendingShare.value = PendingShare(file, exporter.mimeType)
                    ExportState.Shared(filteredPoints.size, exporter.displayName)
                } catch (e: Exception) {
                    ExportState.Failure.Generic(
                        formatName = exporter.displayName,
                        exceptionClass = e::class.simpleName ?: "Exception",
                        exceptionMessage = e.message,
                    )
                }
            }
            _exportState.value = newState
        }
    }

    /** Called once the UI has launched the share sheet for [pendingShare]. */
    fun onShareConsumed() {
        _pendingShare.value = null
    }
}

// ---------- private helpers ----------

private fun queryFileSize(uri: Uri, resolver: ContentResolver): Long? = try {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
    }
} catch (_: Exception) {
    null
}

private fun queryDisplayName(uri: Uri, resolver: ContentResolver): String? = try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getString(idx) else null
    }
} catch (_: Exception) {
    null
}

/**
 * Wraps an InputStream and counts the bytes pulled through it, so the parser's
 * progress callbacks can report read progress without ever buffering the file.
 */
private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
    @Volatile
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val b = wrapped.read()
        if (b >= 0) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = wrapped.read(b, off, len)
        if (n > 0) bytesRead += n
        return n
    }

    override fun available(): Int = wrapped.available()
    override fun close() = wrapped.close()
}

/**
 * Suggest a filename for the save dialog. Filenames stay English /
 * ASCII-friendly for cross-platform compatibility — they're not
 * localised even when the rest of the UI is.
 */
fun suggestFilename(filter: TimelineFilter, exporter: Exporter): String {
    val range = filter.dateRange
        ?: return "Timeline.${exporter.fileExtension}"
    val z = ZoneId.systemDefault()
    val from = range.start.atZone(z).toLocalDate()
    val to = range.endInclusive.atZone(z).toLocalDate()
    return "Timeline_${from}_to_${to}.${exporter.fileExtension}"
}
