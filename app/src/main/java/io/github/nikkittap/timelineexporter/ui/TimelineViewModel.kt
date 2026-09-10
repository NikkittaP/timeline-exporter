package io.github.nikkittap.timelineexporter.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nikkittap.timelineexporter.export.ContentKind
import io.github.nikkittap.timelineexporter.export.ExportContent
import io.github.nikkittap.timelineexporter.export.Exporter
import io.github.nikkittap.timelineexporter.filter.TimelineFilter
import io.github.nikkittap.timelineexporter.parser.MINUTES_PER_DAY
import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.NotTimelineFileException
import io.github.nikkittap.timelineexporter.parser.ParsedTimeline
import io.github.nikkittap.timelineexporter.parser.ParserStage
import io.github.nikkittap.timelineexporter.parser.TimeOfDayWindow
import io.github.nikkittap.timelineexporter.parser.findKeyOffsets
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

    /**
     * [bytesRead]/[totalBytes] are the real counts, for the "X / Y MB" text.
     * [fraction] is what the bar should actually show and is deliberately a
     * separate number: see [weightedProgress] for why a straight
     * bytesRead/totalBytes ratio makes the bar crawl through the first few
     * real percent of a phone export and then finish the rest in under a
     * second.
     */
    data class ReadingFile(val bytesRead: Long, val totalBytes: Long?, val fraction: Float?) : LoadingPhase
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

    /**
     * [itemCount] counts whatever [kind] says was written — track points, or
     * places. The two need different wording ("2,481 points" vs "36 places"),
     * and only the UI can produce a translated, correctly pluralised string,
     * so the state carries the number and the kind and nothing else.
     *
     * [displayName] may be null if the content provider didn't expose one.
     */
    data class Success(
        val itemCount: Int,
        val kind: ContentKind,
        val displayName: String?,
    ) : ExportState

    /**
     * The export was written to the cache and handed to the system share
     * sheet. Distinct from [Success] because nothing was saved anywhere the
     * user can go back to — telling them a file exists would be a lie — and
     * because we never learn which app they picked, or whether they picked
     * one at all.
     */
    data class Shared(
        val itemCount: Int,
        val kind: ContentKind,
        val formatName: String,
    ) : ExportState

    /** Semantic failure kind — UI composes the localized message. */
    sealed interface Failure : ExportState {
        /** Nothing to write: no points in the track, or no places at all. */
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

    /**
     * Which of the file's contents the user is looking at, and therefore what
     * the format buttons in step 4 will write.
     *
     * ViewModel state rather than UI state because it decides what gets
     * exported, and because a rotation in the middle of reviewing a places
     * list should not bounce the user back to the track.
     */
    private val _contentKind = MutableStateFlow(ContentKind.TRACK)
    val contentKind: StateFlow<ContentKind> = _contentKind.asStateFlow()

    /**
     * Whether the places list includes Google's level-1 "container" places —
     * the mall a shop sits in, the airport around a terminal.
     *
     * Off by default: every level-1 visit in the reference export overlaps a
     * level-0 visit, so including them reports one afternoon as two stays.
     * See `placeEntries` for why it is nevertheless offered.
     */
    private val _includeContainers = MutableStateFlow(false)
    val includeContainers: StateFlow<Boolean> = _includeContainers.asStateFlow()

    // ----- parse pipeline -----

    fun onFileSelected(uri: Uri, contentResolver: ContentResolver) {
        _uiState.value = TimelineUiState.Loading(LoadingPhase.OpeningFile)
        _filter.value = TimelineFilter()
        _exportState.value = ExportState.Idle
        _pendingShare.value = null
        // A new file is a new subject: start on the track, the way step 3 has
        // always opened, rather than on a places list built from the old one.
        _contentKind.value = ContentKind.TRACK

        viewModelScope.launch {
            val newState = withContext(Dispatchers.IO) {
                try {
                    val totalBytes = queryFileSize(uri, contentResolver)
                    // One cheap sequential scan, no JSON parsing, to find where
                    // the (usually huge, usually near-free-to-skip) rawSignals
                    // fallback array starts — see weightedProgress for why the
                    // real parse below needs this before it can show a progress
                    // bar that doesn't crawl then jump. Best-effort: any failure
                    // here just means a plain byte-fraction bar, same as before.
                    val splitByte = totalBytes?.let {
                        try {
                            contentResolver.openInputStream(uri)?.use { scan ->
                                findKeyOffsets(scan).let { offsets ->
                                    val content = offsets.firstContentKeyOffset
                                    val raw = offsets.rawSignalsOffset
                                    if (content != null && raw != null && content < raw) raw else null
                                }
                            }
                        } catch (_: IOException) {
                            null
                        }
                    }
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open file: $uri")
                    // Stream straight from the file: never materialize the whole
                    // document as a String or object tree, so memory stays
                    // proportional to the extracted points, not to file size.
                    val result = CountingInputStream(BufferedInputStream(input)).use { stream ->
                        parseTimeline(stream) { stage ->
                            _uiState.value = TimelineUiState.Loading(
                                when (stage) {
                                    ParserStage.DecodingJson, is ParserStage.ExtractingSegments ->
                                        LoadingPhase.ReadingFile(
                                            bytesRead = stream.bytesRead,
                                            totalBytes = totalBytes,
                                            fraction = totalBytes?.let {
                                                weightedProgress(stream.bytesRead, it, splitByte)
                                            },
                                        )
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

    /**
     * Narrow to a window of the local clock, or clear it.
     *
     * A window covering the whole day is stored as no window at all: it keeps
     * everything, and leaving it set would light up a filter chip that is not
     * filtering.
     */
    fun setTimeOfDay(window: TimeOfDayWindow?) {
        _filter.value = _filter.value.copy(timeOfDay = window?.takeIf { !it.isWholeDay })
        _exportState.value = ExportState.Idle
    }

    // ----- content selection -----

    fun setContentKind(kind: ContentKind) {
        if (_contentKind.value == kind) return
        _contentKind.value = kind
        // The previous result described the other content ("exported 2,481
        // points"), which would now be read as describing this one.
        _exportState.value = ExportState.Idle
    }

    fun setIncludeContainers(enabled: Boolean) {
        _includeContainers.value = enabled
        _exportState.value = ExportState.Idle
    }

    // ----- export -----

    /**
     * Called after the user picked a destination URI via CreateDocument.
     *
     * [content] is built by the UI rather than recomputed here. Places carry
     * display names that only `stringResource` can produce, and having the UI
     * hand over a finished payload also removes the second, independent copy
     * of the filtering logic this method used to run — the screen already
     * derives the filtered points to show a count, and the two could disagree
     * if a filter changed between rendering and the save dialog returning.
     *
     * [name] is the (already-localized) label to embed inside the file.
     */
    fun onSaveDestinationSelected(
        uri: Uri,
        exporter: Exporter,
        content: ExportContent,
        name: String,
        contentResolver: ContentResolver,
    ) {
        if (content.isEmpty) {
            _exportState.value = ExportState.Failure.NoPoints
            return
        }
        _exportState.value = ExportState.Working

        viewModelScope.launch {
            val newState = withContext(Dispatchers.IO) {
                try {
                    val text = exporter.export(content, name)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("openOutputStream returned null for $uri")
                    ExportState.Success(
                        itemCount = content.size,
                        kind = content.kind,
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
     * Write [content] to a file in the cache and offer it to another app,
     * instead of saving it somewhere the user picked.
     *
     * Requested as "send the export straight to another app instead of saving
     * a file" — the point is to skip the storage round-trip entirely when the
     * destination is a mail client or a map app, so this deliberately does not
     * go through SAF.
     *
     * [cacheDir] rather than a Context: the ViewModel needs a directory, not
     * an Android environment, and taking the narrower type keeps it testable.
     */
    fun onShareRequested(
        exporter: Exporter,
        content: ExportContent,
        name: String,
        filename: String,
        cacheDir: File,
    ) {
        if (content.isEmpty) {
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
                    val file = File(dir, filename)
                    file.writeText(exporter.export(content, name), Charsets.UTF_8)
                    _pendingShare.value = PendingShare(file, exporter.mimeType)
                    ExportState.Shared(content.size, content.kind, exporter.displayName)
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

/**
 * Fraction of the reading phase to show as "done", instead of the plain
 * bytesRead/totalBytes ratio.
 *
 * A real phone export is two very different phases squeezed into one byte
 * range: the small semanticSegments section is fully parsed (timestamps,
 * coordinates, Segment/PathPoint allocation — expensive per byte), and the
 * much larger rawSignals tail after it is only ever skipped once
 * semanticSegments already produced points (JsonParser#skipChildren(), no
 * allocation — nearly free per byte). Measured against the app's own
 * reference export, rawSignals starts 8% of the way into a 62 MB file and
 * both the file size and that 8% are typical, not an outlier. A straight
 * byte ratio therefore spends the whole bar crawling through that first 8%
 * and finishes the remaining 92% in about a second — accurate about bytes,
 * useless as an estimate of time remaining.
 *
 * [splitByte] is the pre-scanned offset of that split (null if the file
 * doesn't have this two-speed shape at all, or the scan couldn't confirm
 * it). Bytes up to it are mapped into the first [READING_PHASE_SPLIT_SHARE]
 * of the bar, the rest into what remains — so the bar spends most of its
 * range on the phase that takes most of the time, and finishes the cheap
 * tail over a correspondingly small slice of it instead of a instant jump.
 */
private const val READING_PHASE_SPLIT_SHARE = 0.9f

internal fun weightedProgress(bytesRead: Long, totalBytes: Long, splitByte: Long?): Float {
    if (totalBytes <= 0) return 0f
    if (splitByte == null || splitByte <= 0 || splitByte >= totalBytes) {
        return (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
    }
    return if (bytesRead <= splitByte) {
        (bytesRead.toFloat() / splitByte) * READING_PHASE_SPLIT_SHARE
    } else {
        val tail = (bytesRead - splitByte).toFloat() / (totalBytes - splitByte)
        READING_PHASE_SPLIT_SHARE + tail * (1f - READING_PHASE_SPLIT_SHARE)
    }.coerceIn(0f, 1f)
}

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
 *
 * The content kind is part of the name because a user exporting both ends up
 * with two files in the same folder, and "Timeline.gpx" twice would mean the
 * second silently replaces the first.
 */
fun suggestFilename(
    filter: TimelineFilter,
    exporter: Exporter,
    kind: ContentKind = ContentKind.TRACK,
): String {
    val stem = when (kind) {
        ContentKind.TRACK -> "Timeline"
        ContentKind.PLACES -> "Timeline_places"
    }
    val range = filter.dateRange
    val dates = if (range == null) {
        ""
    } else {
        val z = ZoneId.systemDefault()
        val from = range.start.atZone(z).toLocalDate()
        val to = range.endInclusive.atZone(z).toLocalDate()
        "_${from}_to_$to"
    }
    // The time-of-day window is in the name for the same reason the content
    // kind is: someone exporting their mornings and then their evenings out of
    // the same date range would otherwise get one filename twice, and the
    // second file would silently replace the first.
    val window = filter.timeOfDay?.takeIf { !it.isWholeDay }
    val hours = if (window == null) "" else "_${clock(window.startMinute)}-${clock(window.endMinute)}"
    return "$stem$dates$hours.${exporter.fileExtension}"
}

/** A time of day as `0730`, for filenames: fixed width, ASCII, no locale. */
private fun clock(minuteOfDay: Int): String {
    val minute = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
    return "%02d%02d".format(minute / 60, minute % 60)
}
