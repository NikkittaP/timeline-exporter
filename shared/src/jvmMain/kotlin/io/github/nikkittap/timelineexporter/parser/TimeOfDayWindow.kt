// The window lives with the model rather than with the filter because both
// sides of the app need it: `applyFilter` tests path points against it, and
// `placeEntries` tests visits against it. The model package is already where
// the shared vocabulary lives - PathPoint, Segment, MovementGroup - and
// putting it in `filter` would mean the parser importing the filter.
package io.github.nikkittap.timelineexporter.parser

import java.time.Instant
import java.time.ZoneId

/** Minutes in a day. Windows are expressed in minutes past local midnight. */
const val MINUTES_PER_DAY = 24 * 60

/**
 * A recurring window of the local clock — "07:00 to 19:00", "22:00 to 06:00" —
 * applied to every day in the export rather than to one date.
 *
 * **Local to the data, not to the phone.** A point is tested against the clock
 * that was on the wall where it was recorded, using the offset the parser
 * back-filled in 1.6.3. That is the whole reason this filter can be trusted on
 * a file that contains travel: a user asking for their mornings means the
 * mornings they lived, not 06:00–12:00 Stockholm time while they were in
 * Tashkent. Points from formats that carry no offset at all fall back to the
 * phone's zone, which is the only answer available and is what the ISO
 * timestamps in those files mean anyway.
 *
 * [startMinute] is inclusive, [endMinute] exclusive, both in `0..MINUTES_PER_DAY`.
 * `start >= end` wraps through midnight, which is not an edge case to tolerate
 * but the entire point of the "night" case: 22:00–06:00 cannot be written any
 * other way.
 */
data class TimeOfDayWindow(
    val startMinute: Int,
    val endMinute: Int,
) {
    init {
        require(startMinute in 0..MINUTES_PER_DAY) { "startMinute out of range: $startMinute" }
        require(endMinute in 0..MINUTES_PER_DAY) { "endMinute out of range: $endMinute" }
    }

    /**
     * True when the window covers the whole day and therefore constrains
     * nothing. The UI collapses this back to "no filter" rather than storing a
     * window that keeps everything and still claims to be a filter.
     */
    val isWholeDay: Boolean
        get() = startMinute == endMinute ||
            (startMinute == 0 && endMinute == MINUTES_PER_DAY)

    /** How long the window is, in minutes, counting the wrap. */
    val lengthMinutes: Int
        get() = when {
            isWholeDay -> MINUTES_PER_DAY
            endMinute > startMinute -> endMinute - startMinute
            else -> MINUTES_PER_DAY - startMinute + endMinute
        }

    /** Whether [minuteOfDay] (0 until [MINUTES_PER_DAY]) falls inside. */
    fun contains(minuteOfDay: Int): Boolean = when {
        isWholeDay -> true
        endMinute > startMinute -> minuteOfDay >= startMinute && minuteOfDay < endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    /**
     * Whether a span that starts at [minuteOfDay] and runs for
     * [spanMinutes] touches this window on any day it covers.
     *
     * This is what a *visit* is tested with, because a visit is not an
     * instant: someone at their desk from 09:00 to 18:00 was there at lunch
     * time, and a window of 12:00–13:00 should find them. Both the span and
     * the window are arcs of the same 24-hour circle, so the test is the
     * standard one for two arcs: they meet unless each starts outside the
     * other.
     */
    fun overlaps(minuteOfDay: Int, spanMinutes: Long): Boolean {
        if (isWholeDay) return true
        if (spanMinutes >= MINUTES_PER_DAY) return true
        val span = spanMinutes.coerceAtLeast(0L).toInt()
        // A zero-length visit is an instant, and an instant is only inside the
        // window if the window contains it.
        if (span == 0) return contains(minuteOfDay)
        val fromWindow = Math.floorMod(minuteOfDay - startMinute, MINUTES_PER_DAY)
        val fromSpan = Math.floorMod(startMinute - minuteOfDay, MINUTES_PER_DAY)
        return fromWindow < lengthMinutes || fromSpan < span
    }

    companion object {
        /** The window that keeps everything, i.e. no constraint at all. */
        val WholeDay = TimeOfDayWindow(0, MINUTES_PER_DAY)
    }
}

/**
 * Minutes past midnight on the clock that was in effect where [instant] was
 * recorded.
 *
 * [offsetMinutes] is the point's own UTC offset where the file states one.
 * When it does not, [fallbackZone] stands in — the phone's zone, applied at
 * that instant so a historical date gets that date's offset rather than
 * today's.
 */
fun localMinuteOfDay(instant: Instant, offsetMinutes: Int?, fallbackZone: ZoneId): Int {
    val offset = offsetMinutes ?: (fallbackZone.rules.getOffset(instant).totalSeconds / 60)
    val minutes = Math.floorDiv(instant.epochSecond, 60L) + offset
    return Math.floorMod(minutes, MINUTES_PER_DAY.toLong()).toInt()
}
