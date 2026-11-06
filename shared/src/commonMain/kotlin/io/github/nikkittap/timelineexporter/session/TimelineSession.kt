package io.github.nikkittap.timelineexporter.session

import io.github.nikkittap.timelineexporter.export.CsvExporter
import io.github.nikkittap.timelineexporter.export.ExportContent
import io.github.nikkittap.timelineexporter.export.Exporter
import io.github.nikkittap.timelineexporter.export.GeoJsonExporter
import io.github.nikkittap.timelineexporter.export.GpxExporter
import io.github.nikkittap.timelineexporter.export.KmlExporter
import io.github.nikkittap.timelineexporter.export.localDate
import io.github.nikkittap.timelineexporter.filter.TimelineFilter
import io.github.nikkittap.timelineexporter.filter.applyFilter
import io.github.nikkittap.timelineexporter.parser.MINUTES_PER_DAY
import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.NotTimelineFileException
import io.github.nikkittap.timelineexporter.parser.ParsedTimeline
import io.github.nikkittap.timelineexporter.parser.ParserStage
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.parser.TimeOfDayWindow
import io.github.nikkittap.timelineexporter.parser.findKeyOffsets
import io.github.nikkittap.timelineexporter.parser.parseTimeline
import io.github.nikkittap.timelineexporter.trips.TripDetection
import io.github.nikkittap.timelineexporter.trips.detectTrips
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.datetime.toLocalDateTime

// The iOS app's door into the shared core. Swift sees Kotlin generics, sealed
// types and kotlin.time awkwardly, so everything here crosses the boundary as
// plain values: epoch milliseconds, minutes of the day, movement group names,
// format names. It lives in commonMain rather than iosMain so the JVM tests
// cover it on every push; Android does not use it (its ViewModel predates it).

/** A load in progress, as the iOS loading screen shows it. */
class LoadProgress(
    /** One of [STAGE_READING] or [STAGE_SORTING]. */
    val stage: Int,
    val bytesRead: Long,
    /** -1 when the size is unknown. */
    val totalBytes: Long,
    /** What the bar shows, 0..1, or -1 when the size is unknown. See [weightedProgress]. */
    val fraction: Double,
) {
    val isSorting: Boolean get() = stage == STAGE_SORTING

    companion object {
        const val STAGE_READING = 0
        const val STAGE_SORTING = 1
    }
}

/**
 * What the user has set, in the shapes Swift holds them in. Null means "no
 * constraint", as in [TimelineFilter].
 *
 * [rangeStartMillis]..[rangeEndMillis] is inclusive. [movements] holds
 * [MovementGroup] names; an empty set matches nothing, as in the filter.
 */
data class FilterSpec(
    val rangeStartMillis: Long? = null,
    val rangeEndMillis: Long? = null,
    val movements: Set<String>? = null,
    val movingOnly: Boolean = false,
    val dropRepeatedPoints: Boolean = false,
    val timeStartMinute: Int? = null,
    val timeEndMinute: Int? = null,
) {
    fun toFilter(): TimelineFilter {
        val start = rangeStartMillis
        val end = rangeEndMillis
        val range = if (start != null && end != null && start <= end) {
            Instant.fromEpochMilliseconds(start)..Instant.fromEpochMilliseconds(end)
        } else {
            null
        }
        val window = windowOrNull(timeStartMinute, timeEndMinute)
        return TimelineFilter(
            dateRange = range,
            movements = movements?.mapNotNull { name -> MovementGroup.entries.firstOrNull { it.name == name } }?.toSet(),
            movingOnly = movingOnly,
            dropRepeatedPoints = dropRepeatedPoints,
            timeOfDay = window,
        )
    }

    val hasRange: Boolean get() = toFilter().dateRange != null
    val hasTimeOfDay: Boolean get() = windowOrNull(timeStartMinute, timeEndMinute) != null
}

private fun windowOrNull(start: Int?, end: Int?): TimeOfDayWindow? {
    if (start == null || end == null) return null
    if (start !in 0..MINUTES_PER_DAY || end !in 0..MINUTES_PER_DAY) return null
    return TimeOfDayWindow(start, end).takeIf { !it.isWholeDay }
}

/**
 * [FilterSpec] for Swift, which sees Kotlin's nullable numbers as boxed
 * objects: a flag says whether each optional part is set.
 */
fun filterSpec(
    hasRange: Boolean,
    rangeStartMillis: Long,
    rangeEndMillis: Long,
    movements: Set<String>?,
    movingOnly: Boolean,
    dropRepeatedPoints: Boolean,
    hasTimeOfDay: Boolean,
    timeStartMinute: Int,
    timeEndMinute: Int,
): FilterSpec = FilterSpec(
    rangeStartMillis = if (hasRange) rangeStartMillis else null,
    rangeEndMillis = if (hasRange) rangeEndMillis else null,
    movements = movements,
    movingOnly = movingOnly,
    dropRepeatedPoints = dropRepeatedPoints,
    timeStartMinute = if (hasTimeOfDay) timeStartMinute else null,
    timeEndMinute = if (hasTimeOfDay) timeEndMinute else null,
)

/**
 * What [openTimelineFile] produced: a session, or why there is none. A value
 * rather than an exception, so nothing depends on how Kotlin exceptions
 * reach Swift. [wrongFile] is the "not a Timeline export" case, shown
 * without technical detail; otherwise [errorClass] and [errorMessage] are
 * shown for bug reports.
 */
class OpenResult(
    val session: TimelineSession?,
    val wrongFile: Boolean,
    val errorClass: String?,
    val errorMessage: String?,
)

/** Open [path] for the iOS app; never throws. See [TimelineSession.open]. */
fun openTimelineFile(path: String, onProgress: (LoadProgress) -> Unit): OpenResult = try {
    OpenResult(TimelineSession.open(path, onProgress), false, null, null)
} catch (e: WrongFileException) {
    OpenResult(null, true, e.cause?.let { it::class.simpleName } ?: "WrongFileException", e.message)
} catch (e: Throwable) {
    // Out of memory included: a huge or binary file must not crash the app.
    OpenResult(null, e::class.simpleName == "OutOfMemoryError", e::class.simpleName ?: "Throwable", e.message)
}

/** What an export wrote: [count] items, or the error that stopped it. */
class ExportResult(val count: Int, val errorClass: String?, val errorMessage: String?) {
    val failed: Boolean get() = errorClass != null
}

/** One row of the movement breakdown: [group] is a [MovementGroup] name. */
data class MovementRow(val group: String, val trips: Int, val distanceMeters: Double)

/** Export formats, by the names the iOS buttons use. */
enum class ExportFormat(internal val exporter: Exporter) {
    GPX(GpxExporter),
    KML(KmlExporter),
    GEOJSON(GeoJsonExporter),
    CSV(CsvExporter);

    val displayName: String get() = exporter.displayName
    val fileExtension: String get() = exporter.fileExtension
    val mimeType: String get() = exporter.mimeType
}

/** Thrown by [TimelineSession.open] for a file that is not a Timeline export. */
class WrongFileException(message: String?, cause: Throwable?) : Exception(message, cause)

/**
 * One opened Timeline file. Immutable once open: the filter is passed in on
 * every call, so Swift can call it from any thread.
 */
class TimelineSession private constructor(
    val timeline: ParsedTimeline,
    internal val zone: TimeZone,
) {
    val pointCount: Int get() = timeline.pathPoints.size
    val segmentCount: Int get() = timeline.totalSegments

    /** [io.github.nikkittap.timelineexporter.parser.TimelineFormat] name. */
    val formatName: String get() = timeline.format.name

    /** First and last point, epoch milliseconds; 0 for an empty file. */
    val firstPointMillis: Long get() = timeline.pathPoints.firstOrNull()?.timeUtc?.toEpochMilliseconds() ?: 0
    val lastPointMillis: Long get() = timeline.pathPoints.lastOrNull()?.timeUtc?.toEpochMilliseconds() ?: 0

    /** Days with at least one point, on the clock each point was recorded on. */
    val dayCount: Int by lazy {
        timeline.pathPoints.asSequence().map { localDate(it.timeUtc, it.tzOffsetMinutes, zone) }.distinct().count()
    }

    /** Google's own distance over every activity in the file, metres. */
    val totalDistanceMeters: Double by lazy { timeline.movementBreakdown().sumOf { it.distanceMeters } }

    /** Trips in the file, or null when the search failed (the rest still works). */
    val trips: TripDetection? by lazy {
        try {
            detectTrips(timeline)
        } catch (_: Exception) {
            null
        }
    }

    fun filteredPoints(spec: FilterSpec): List<PathPoint> =
        applyFilter(timeline.pathPoints, spec.toFilter(), timeline.segments, zone)

    fun filteredCount(spec: FilterSpec): Int = filteredPoints(spec).size

    /** Movement groups present in the file, in enum order. */
    fun availableMovements(): List<String> =
        timeline.movementBreakdown(fallbackZone = zone).map { it.group }.distinct().sortedBy { it.ordinal }.map { it.name }

    /** The breakdown for the date range and time of day in [spec], longest first. */
    fun movementBreakdown(spec: FilterSpec): List<MovementRow> {
        val filter = spec.toFilter()
        return timeline.movementBreakdown(filter.dateRange, filter.timeOfDay, zone)
            .map { MovementRow(it.group.name, it.trips, it.distanceMeters) }
    }

    /**
     * Write the filtered track to [path] in [format]. [name] is the label
     * inside the file. Returns the number of points written; writes nothing
     * and returns 0 when no point matches.
     */
    @Throws(Exception::class)
    fun exportTrack(spec: FilterSpec, format: ExportFormat, colorByDay: Boolean, name: String, path: String): Int {
        val points = filteredPoints(spec)
        if (points.isEmpty()) return 0
        val content = ExportContent.Track(points, colorByDay, zone)
        writeExport(format, content, name, path)
        return points.size
    }

    /** [exportTrack] for Swift: never throws. */
    fun exportTrackResult(spec: FilterSpec, format: ExportFormat, colorByDay: Boolean, name: String, path: String): ExportResult =
        try {
            ExportResult(exportTrack(spec, format, colorByDay, name, path), null, null)
        } catch (e: Throwable) {
            ExportResult(0, e::class.simpleName ?: "Throwable", e.message)
        }

    companion object {
        /**
         * Parse the file at [path]. [onProgress] is called on the calling
         * thread. Throws [WrongFileException] when the file is not a Timeline
         * export or not JSON, any other exception for a real failure.
         */
        @Throws(Exception::class)
        fun open(path: String, onProgress: (LoadProgress) -> Unit): TimelineSession =
            open(path, TimeZone.currentSystemDefault(), onProgress)

        internal fun open(path: String, zone: TimeZone, onProgress: (LoadProgress) -> Unit): TimelineSession {
            val file = Path(path)
            val total = SystemFileSystem.metadataOrNull(file)?.size?.takeIf { it >= 0 }
            val split = total?.let {
                try {
                    SystemFileSystem.source(file).buffered().use { scan ->
                        val offsets = findKeyOffsets(scan)
                        val content = offsets.firstContentKeyOffset
                        val raw = offsets.rawSignalsOffset
                        if (content != null && raw != null && content < raw) raw else null
                    }
                } catch (_: Exception) {
                    null
                }
            }
            val counting = CountingSource(SystemFileSystem.source(file))
            val parsed = try {
                parseTimeline(counting.buffered()) { stage ->
                    val read = counting.bytesRead
                    onProgress(
                        when (stage) {
                            ParserStage.Sorting -> LoadProgress(LoadProgress.STAGE_SORTING, read, total ?: -1, 1.0)
                            else -> LoadProgress(
                                LoadProgress.STAGE_READING,
                                read,
                                total ?: -1,
                                total?.let { weightedProgress(read, it, split) } ?: -1.0,
                            )
                        },
                    )
                }
            } catch (e: NotTimelineFileException) {
                throw WrongFileException(e.message, e)
            } catch (e: IOException) {
                // Malformed JSON (JsonParseException is an IOException) and read
                // errors: the user picked the wrong file far more often than the
                // parser failed, as on Android.
                throw WrongFileException(e.message, e)
            }
            return TimelineSession(parsed, zone)
        }
    }
}

/**
 * Bar position for [bytesRead] of [totalBytes]: the expensive part of the file
 * before [splitByte] (rawSignals) gets 90% of the bar. The Android
 * TimelineViewModel.weightedProgress explains why; this is the same curve.
 */
fun weightedProgress(bytesRead: Long, totalBytes: Long, splitByte: Long?): Double {
    if (totalBytes <= 0) return 0.0
    if (splitByte == null || splitByte <= 0 || splitByte >= totalBytes) {
        return (bytesRead.toDouble() / totalBytes).coerceIn(0.0, 1.0)
    }
    val share = 0.9
    return if (bytesRead <= splitByte) {
        bytesRead.toDouble() / splitByte * share
    } else {
        share + (bytesRead - splitByte).toDouble() / (totalBytes - splitByte) * (1 - share)
    }.coerceIn(0.0, 1.0)
}

/**
 * The name offered in the save dialog, as Android's suggestFilename builds it:
 * ASCII, never localised, with the kind, the local dates of the range and the
 * time-of-day window, so two different exports never share a name.
 */
fun suggestTrackFilename(spec: FilterSpec, format: ExportFormat): String =
    suggestTrackFilename(spec, format, TimeZone.currentSystemDefault())

internal fun suggestTrackFilename(spec: FilterSpec, format: ExportFormat, zone: TimeZone): String {
    val filter = spec.toFilter()
    val range = filter.dateRange
    val dates = if (range == null) {
        ""
    } else {
        "_${range.start.toLocalDateTime(zone).date}_to_${range.endInclusive.toLocalDateTime(zone).date}"
    }
    val window = filter.timeOfDay
    val hours = if (window == null) "" else "_${clock(window.startMinute)}-${clock(window.endMinute)}"
    return "Timeline$dates$hours.${format.fileExtension}"
}

private fun clock(minuteOfDay: Int): String {
    val minute = minuteOfDay.mod(MINUTES_PER_DAY)
    val h = minute / 60
    val m = minute % 60
    return "${if (h < 10) "0" else ""}$h${if (m < 10) "0" else ""}$m"
}

internal fun writeExport(format: ExportFormat, content: ExportContent, name: String, path: String) {
    SystemFileSystem.sink(Path(path)).buffered().use { sink ->
        val out = SinkAppendable(sink)
        format.exporter.export(content, name, out)
        out.flush()
    }
}

/** Counts the bytes the parser pulls, for the progress bar. */
private class CountingSource(private val raw: RawSource) : RawSource {
    var bytesRead: Long = 0
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val n = raw.readAtMostTo(sink, byteCount)
        if (n > 0) bytesRead += n
        return n
    }

    override fun close() = raw.close()
}

/**
 * The exporters append text; files take UTF-8 bytes. Characters are gathered
 * into a small buffer so a run of one-char appends is not a write each, and a
 * surrogate pair is never split across two writes.
 */
internal class SinkAppendable(private val sink: Sink) : Appendable {
    private val pending = StringBuilder(CHUNK)

    override fun append(value: Char): Appendable {
        pending.append(value)
        if (pending.length >= CHUNK && !value.isHighSurrogate()) drain()
        return this
    }

    override fun append(value: CharSequence?): Appendable {
        pending.append(value ?: "null")
        if (pending.length >= CHUNK && pending.last().isHighSurrogate().not()) drain()
        return this
    }

    override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable {
        pending.appendRange(value ?: "null", startIndex, endIndex)
        if (pending.length >= CHUNK && pending.last().isHighSurrogate().not()) drain()
        return this
    }

    fun flush() {
        drain()
        sink.flush()
    }

    private fun drain() {
        if (pending.isEmpty()) return
        sink.writeString(pending.toString())
        pending.clear()
    }

    private companion object {
        const val CHUNK = 8 * 1024
    }
}
