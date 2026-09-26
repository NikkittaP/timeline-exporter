package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.export.trackDaySpans
import io.github.nikkittap.timelineexporter.export.trackEpochDay
import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class TrackDayRun(val date: LocalDate, val points: List<PathPoint>)

/** How many swatches the compact "Color by day" summary hints with. */
private const val LEGEND_HINT_DOTS = 5

/**
 * What the compact `Color by day` row shows in place of every date: how many
 * distinct days are coloured, the span they cover, and a small, evenly spread
 * sample of their colours — not the full set, which is the whole reason this
 * exists instead of the row it replaced.
 */
internal data class DayLegendSummary(
    val dayCount: Int,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val hintDates: List<LocalDate>,
)

/**
 * Reduce a scan of a track's local days to a [DayLegendSummary], or null for
 * no days at all. [dates] need not be sorted or de-duplicated — both happen
 * here, so a caller can hand over the raw per-sample scan.
 */
internal fun dayLegendSummary(dates: List<LocalDate>): DayLegendSummary? {
    val sorted = dates.distinct().sorted()
    if (sorted.isEmpty()) return null
    val hints = if (sorted.size <= LEGEND_HINT_DOTS) sorted else List(LEGEND_HINT_DOTS) { i ->
        sorted[i * (sorted.size - 1) / (LEGEND_HINT_DOTS - 1)]
    }
    return DayLegendSummary(sorted.size, sorted.first(), sorted.last(), hints)
}

/**
 * The summary's date span as one localized line: a single day reads as just
 * that day, a range inside one year drops the repeated year to its end, and a
 * range crossing years drops the day-of-month a reader cannot compare across
 * years anyway.
 */
internal fun formatDayRange(summary: DayLegendSummary, locale: Locale): String {
    val first = summary.firstDate
    val last = summary.lastDate
    return when {
        first == last -> first.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
        first.year == last.year -> {
            val day = DateTimeFormatter.ofPattern("MMM d", locale)
            val year = DateTimeFormatter.ofPattern("yyyy", locale)
            "${first.format(day)} – ${last.format(day)}, ${first.format(year)}"
        }
        else -> {
            val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", locale)
            "${first.format(monthYear)} – ${last.format(monthYear)}"
        }
    }
}

/** One row of the full day list the "Show all days" sheet lays out. */
internal sealed interface DayListEntry {
    data class YearHeader(val year: Int) : DayListEntry
    data class MonthHeader(val month: YearMonth) : DayListEntry
    data class Day(val date: LocalDate) : DayListEntry
}

/**
 * The full set of days, most recent first, grouped Year -> Month the way the
 * "Show all days" sheet lays them out. [dates] need not be sorted or
 * de-duplicated. A flat list of rows rather than a nested structure, because
 * that is what a single [androidx.compose.foundation.lazy.LazyColumn] needs
 * to stay lazy over a set that can run into the thousands.
 */
internal fun dayListEntries(dates: List<LocalDate>): List<DayListEntry> {
    val descending = dates.distinct().sortedDescending()
    val entries = ArrayList<DayListEntry>(descending.size + descending.size / 20 + 2)
    var lastYear: Int? = null
    var lastMonth: YearMonth? = null
    for (date in descending) {
        if (date.year != lastYear) {
            entries += DayListEntry.YearHeader(date.year)
            lastYear = date.year
            lastMonth = null
        }
        val month = YearMonth.from(date)
        if (month != lastMonth) {
            entries += DayListEntry.MonthHeader(month)
            lastMonth = month
        }
        entries += DayListEntry.Day(date)
    }
    return entries
}

/**
 * The box every sample of one calendar day fits in, and how many there were.
 *
 * Plain numbers rather than a MapLibre box, so that the part of "take me to
 * that day" which is arithmetic over the export stays arithmetic over the
 * export - testable without a map, and computable off the main thread without
 * touching anything the renderer owns.
 */
internal data class DayBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val pointCount: Int,
) {
    /**
     * True for a day with no area to frame: one sample, or several from the
     * same spot. A day spent indoors is ordinary, and it needs a zoom rather
     * than a box.
     */
    val isDegenerate: Boolean
        get() = pointCount < 2 || (minLat == maxLat && minLon == maxLon)
}

/**
 * The extent of everything recorded on one calendar day, or null if nothing
 * was. Days made of several disconnected stretches are included whole - a
 * morning in one city and an evening in another give one box covering both,
 * which is what "show me that day" has to mean.
 */
internal fun dayBox(points: List<PathPoint>, date: LocalDate, zone: ZoneId): DayBox? {
    val epochDay = date.toEpochDay()
    var minLat = 0.0
    var maxLat = 0.0
    var minLon = 0.0
    var maxLon = 0.0
    var count = 0
    for (point in points) {
        if (point.trackEpochDay(zone) != epochDay) continue
        if (count == 0) {
            minLat = point.latitude
            maxLat = point.latitude
            minLon = point.longitude
            maxLon = point.longitude
        } else {
            if (point.latitude < minLat) minLat = point.latitude
            if (point.latitude > maxLat) maxLat = point.latitude
            if (point.longitude < minLon) minLon = point.longitude
            if (point.longitude > maxLon) maxLon = point.longitude
        }
        count++
    }
    return if (count == 0) null else DayBox(minLat, minLon, maxLat, maxLon, count)
}

/**
 * Where [date] sits in a list built by [dayListEntries], or -1 if it is not in
 * it — which is the ordinary case for a day selected on the map while the
 * sheet is showing only what is in view.
 *
 * A scan rather than a binary search: the rows are dates interleaved with year
 * and month headers, so the list is only sorted if you skip the headers, and a
 * few thousand comparisons once per sheet is not worth the special case.
 */
internal fun dayEntryIndex(entries: List<DayListEntry>, date: LocalDate?): Int {
    if (date == null) return -1
    return entries.indexOfFirst { it is DayListEntry.Day && it.date == date }
}

/**
 * Split one continuous run, after geographic gaps have already been removed,
 * into one piece per local day - see [trackDaySpans], which the exporters cut
 * the same track with, so a day ends at the same sample on the map and in the
 * file.
 */
internal fun splitTrackDays(points: List<PathPoint>, fallbackZone: ZoneId): List<TrackDayRun> =
    trackDaySpans(points, fallbackZone).map { span ->
        TrackDayRun(span.date, points.subList(span.first, span.last + 1))
    }

/**
 * What one camera can see, as plain numbers: a band of latitude, and an arc of
 * longitude that starts at [westLon] and runs [lonSpan] degrees east.
 *
 * An arc rather than a second edge, because a view over the Pacific has its
 * eastern edge west of its western one, and "between the two" would then name
 * the half of the world the user is not looking at.
 */
internal data class TrackWindow(
    val minLat: Double,
    val maxLat: Double,
    val westLon: Double,
    val lonSpan: Double,
) {
    /** Just the longitude half of one, as [longitudeArc] works it out. */
    data class Arc(val west: Double, val span: Double)
}

/**
 * The arc of longitude a ring of samples covers, walked in the order given.
 *
 * Steps, not a straight comparison of the extremes, and that is the whole
 * reason this exists: a phone held upright shows well over 180 degrees of
 * longitude at the zoom that frames a year of travel, and "the short way
 * round" between the two ends of a view that wide names the way round the
 * viewer is not looking - the arc comes back inside out, and everything
 * actually on screen reads as off it. Adding up steps that are each well
 * inside half a turn recovers the true span, and still folds a view
 * straddling the antimeridian into one arc rather than two halves of the
 * world. [longitudes] must therefore be neighbours on the ground as well as
 * in the list, and no two of them further apart than half a turn.
 */
internal fun longitudeArc(longitudes: List<Double>): TrackWindow.Arc {
    var running = longitudes.first()
    var west = running
    var east = running
    for (i in 1 until longitudes.size) {
        running += wrapLongitude(longitudes[i] - longitudes[i - 1])
        if (running < west) west = running
        if (running > east) east = running
    }
    return TrackWindow.Arc(wrapLongitude(west), (east - west).coerceIn(0.0, FULL_TURN))
}

/**
 * The local days [prepareTrack] draws a line for, in order, optionally
 * narrowed to the ones with a line inside [window].
 *
 * Read off the samples rather than off the map, and that is the entire point.
 * The track reaches MapLibre as GeoJSON and is tiled per zoom level, so a day
 * spent inside one city is simplified out of the tiles as soon as the camera
 * pulls back far enough: still on screen, as one thread in a knot of lines,
 * but gone from anything the renderer can be asked about. A legend built on
 * `queryRenderedFeatures` therefore counted fewer days the further out the
 * user zoomed, which is the opposite of what "in view" means. Counting
 * samples makes the answer a function of the camera and the file alone.
 *
 * Edges rather than points, and the same edges the track is made of: a pair
 * of samples further apart than [GAP_DISTANCE_METERS] is a flight, drawn as a
 * dashed connector and not as track, and an edge belongs to its starting
 * sample's local day exactly as [splitTrackDays] assigns it. A day whose only
 * samples are isolated has no line, so it is not one of these days.
 *
 * Cheap enough to run on every camera idle - one distance and at most one
 * clip per edge, and the clip is skipped entirely once the day it would
 * answer for is already in - but it is still a pass over the whole file, so
 * callers run it off the main thread.
 */
internal fun drawnTrackDays(
    points: List<PathPoint>,
    fallbackZone: ZoneId,
    window: TrackWindow? = null,
): List<LocalDate> {
    if (points.size < 2) return emptyList()
    val days = HashSet<Long>()
    var prev = points.first()
    var prevDay = prev.trackEpochDay(fallbackZone)
    for (i in 1 until points.size) {
        val cur = points[i]
        val curDay = cur.trackEpochDay(fallbackZone)
        if (prevDay !in days &&
            haversineMeters(prev, cur) <= GAP_DISTANCE_METERS &&
            (window == null || window.crossedBy(prev, cur))
        ) {
            days += prevDay
        }
        prev = cur
        prevDay = curDay
    }
    return days.map(LocalDate::ofEpochDay).sorted()
}

/** Whether the straight edge between two samples enters this window. */
private fun TrackWindow.crossedBy(from: PathPoint, to: PathPoint): Boolean {
    // Both ends in the window's own frame, where x counts degrees east of the
    // western edge: that is what stops the antimeridian being a special case.
    val x1 = Math.floorMod(((from.longitude - westLon) * WRAP_SCALE).toLong(), FULL_TURN_SCALED)
        .toDouble() / WRAP_SCALE
    val x2 = x1 + wrapLongitude(to.longitude - from.longitude)
    // An edge lying east of the far side may still have its westward copy
    // crossing the window; both drawings are the same piece of track.
    return clips(x1, from.latitude, x2, to.latitude) ||
        clips(x1 - FULL_TURN, from.latitude, x2 - FULL_TURN, to.latitude)
}

/**
 * Liang-Barsky: whether the segment `(x1, y1) - (x2, y2)` has any part inside
 * `[0, lonSpan] x [minLat, maxLat]`. A containment test on the two ends would
 * be wrong for exactly the case the user is most likely to be looking at - a
 * road crossing the screen with both of its samples off it.
 */
private fun TrackWindow.clips(x1: Double, y1: Double, x2: Double, y2: Double): Boolean {
    var enter = 0.0
    var exit = 1.0
    val dx = x2 - x1
    val dy = y2 - y1
    val edges = arrayOf(
        -dx to (x1 - 0.0),
        dx to (lonSpan - x1),
        -dy to (y1 - minLat),
        dy to (maxLat - y1),
    )
    for ((slope, room) in edges) {
        if (slope == 0.0) {
            // Parallel to this edge: outside it means outside for good.
            if (room < 0.0) return false
            continue
        }
        val cut = room / slope
        if (slope < 0.0) {
            if (cut > exit) return false
            if (cut > enter) enter = cut
        } else {
            if (cut < enter) return false
            if (cut < exit) exit = cut
        }
    }
    return true
}

private const val FULL_TURN = 360.0
private const val HALF_TURN = 180.0

/**
 * Integer arithmetic for the one wrap that has to be exact. `x.mod(360.0)` on
 * a longitude a hair below the western edge returns 360.0 rather than 0.0 -
 * the difference is below what a double can hold - and a point at x = 360.0
 * lands outside every window narrower than the world.
 */
private const val WRAP_SCALE = 1_000_000.0
private const val FULL_TURN_SCALED = 360_000_000L

/** A difference in longitude reduced to the short way round, in (-180, 180]. */
internal fun wrapLongitude(degrees: Double): Double {
    var wrapped = degrees % FULL_TURN
    if (wrapped > HALF_TURN) wrapped -= FULL_TURN
    if (wrapped <= -HALF_TURN) wrapped += FULL_TURN
    return wrapped
}
