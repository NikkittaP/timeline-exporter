package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val TRACK_DAY_COLORS = listOf(
    "#0072B2", "#D55E00", "#009E73", "#CC79A7", "#7560CF", "#B58A00", "#009FB5",
)

/**
 * How many weeks of shading the seven hues cycle through. Three tiers - as
 * authored, lighter, darker - buy a repeat every three weeks instead of every
 * one, without asking a colour-blind reader to tell twenty-one hues apart.
 */
private const val TRACK_DAY_SHADES = 3

/** How far a shaded tier moves toward white or black. */
private const val TRACK_DAY_SHADE_AMOUNT = 0.30f

/**
 * The colour of one day, as a pure function of the date so that narrowing the
 * filter never repaints what stayed on screen.
 *
 * Seven hues alone would make the colour a synonym for the weekday: every
 * Monday of the year identical, and a two-week range putting two identical
 * swatches in the legend with no way to tell which line belongs to which
 * date. The hue still turns over weekly - that is what keeps neighbouring
 * days apart, which is what the eye actually needs - but each week shifts to
 * the next shade, so a colour does not come round again for three weeks.
 */
internal fun trackDayColor(date: LocalDate): String {
    val epochDay = date.toEpochDay()
    val hues = TRACK_DAY_COLORS.size.toLong()
    val hue = TRACK_DAY_COLORS[Math.floorMod(epochDay, hues).toInt()]
    val week = Math.floorDiv(epochDay, hues)
    return when (Math.floorMod(week, TRACK_DAY_SHADES.toLong()).toInt()) {
        0 -> hue
        1 -> hue.shiftedToward(0xFF)
        else -> hue.shiftedToward(0x00)
    }
}

/** Move an `#RRGGBB` string a fixed fraction of the way to white or black. */
private fun String.shiftedToward(target: Int): String {
    val rgb = substring(1).toInt(16)
    fun channel(shift: Int): Int {
        val value = (rgb shr shift) and 0xFF
        return (value + (target - value) * TRACK_DAY_SHADE_AMOUNT).toInt().coerceIn(0, 0xFF)
    }
    return "#%02X%02X%02X".format(channel(16), channel(8), channel(0))
}

/**
 * The local day a sample belongs to, counted in days since the epoch.
 *
 * Integer arithmetic rather than [java.time.Instant.atZone]: the caller runs
 * this once per edge of a track that can hold hundreds of thousands of
 * samples, and a ZonedDateTime per sample is a few million short-lived
 * objects for a number we then throw away.
 */
internal fun PathPoint.trackEpochDay(fallbackZone: ZoneId): Long {
    val offsetSeconds = tzOffsetMinutes?.times(60)
        ?: fallbackZone.rules.getOffset(timeUtc).totalSeconds
    return Math.floorDiv(timeUtc.epochSecond + offsetSeconds, SECONDS_PER_DAY)
}

internal fun PathPoint.trackDate(fallbackZone: ZoneId): LocalDate =
    LocalDate.ofEpochDay(trackEpochDay(fallbackZone))

private const val SECONDS_PER_DAY = 86_400L

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
 * Split one continuous run, after geographic gaps have already been removed.
 * Each edge belongs to its starting sample's local day. Share the boundary
 * sample so midnight never deletes an edge; do not invent a midnight GPS fix.
 */
internal fun splitTrackDays(points: List<PathPoint>, fallbackZone: ZoneId): List<TrackDayRun> {
    if (points.size < 2) return emptyList()
    val result = mutableListOf<TrackDayRun>()
    // Compare days as longs and build the LocalDate once per run, not once
    // per sample: only the boundaries ever become a date anyone reads.
    var day = points.first().trackEpochDay(fallbackZone)
    var run = mutableListOf(points.first())
    for (i in 1 until points.size) {
        val edgeDay = points[i - 1].trackEpochDay(fallbackZone)
        if (edgeDay != day) {
            result += TrackDayRun(LocalDate.ofEpochDay(day), run)
            day = edgeDay
            run = mutableListOf(points[i - 1])
        }
        run += points[i]
    }
    result += TrackDayRun(LocalDate.ofEpochDay(day), run)
    return result
}
