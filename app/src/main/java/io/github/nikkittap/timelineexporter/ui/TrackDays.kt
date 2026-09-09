package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.LocalDate
import java.time.ZoneId

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
