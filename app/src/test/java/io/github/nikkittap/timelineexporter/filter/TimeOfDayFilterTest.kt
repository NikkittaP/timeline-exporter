package io.github.nikkittap.timelineexporter.filter

import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.parser.TimeOfDayWindow
import io.github.nikkittap.timelineexporter.parser.parseTimeline
import io.github.nikkittap.timelineexporter.parser.placeEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The time-of-day window as the rest of the app sees it: points through
 * [applyFilter], places through `placeEntries`.
 *
 * Every test pins the fallback zone to UTC. The point of the feature is that
 * the answer does not depend on the machine it runs on, and a test that read
 * `ZoneId.systemDefault()` would pass in Stockholm and fail in CI.
 */
class TimeOfDayFilterTest {

    private val utc = ZoneId.of("UTC")

    /** A point at [hour]:00 UTC on 9 June, carrying [offsetMinutes] as its own. */
    private fun point(hour: Int, offsetMinutes: Int? = 0) = PathPoint(
        timeUtc = Instant.parse("2025-06-09T00:00:00Z").plusSeconds(hour * 3600L),
        latitude = 1.0,
        longitude = 1.0,
        tzOffsetMinutes = offsetMinutes,
    )

    private val day = (0..23).map { point(it) }

    @Test
    fun `a window keeps the hours it names`() {
        val kept = applyFilter(
            day,
            TimelineFilter(timeOfDay = TimeOfDayWindow(7 * 60, 12 * 60)),
            emptyList(),
            utc,
        )
        assertEquals(listOf(7, 8, 9, 10, 11), kept.map { it.timeUtc.atZone(utc).hour })
    }

    @Test
    fun `a window through midnight keeps both ends of the day`() {
        val kept = applyFilter(
            day,
            TimelineFilter(timeOfDay = TimeOfDayWindow(22 * 60, 6 * 60)),
            emptyList(),
            utc,
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 22, 23), kept.map { it.timeUtc.atZone(utc).hour }.sorted())
    }

    @Test
    fun `a whole-day window is not a filter at all`() {
        val spec = TimelineFilter(timeOfDay = TimeOfDayWindow.WholeDay)
        assertTrue(spec.isEmpty)
        // isEmpty means applyFilter hands the same list straight back rather
        // than copying 30,000 points to keep all of them.
        assertSame(day, applyFilter(day, spec, emptyList(), utc))
    }

    @Test
    fun `each point is judged on its own clock`() {
        // Three points at the same instant as far as the file's ISO
        // timestamps go, recorded in three different places. 04:00 UTC is
        // 06:00 in Stockholm, 09:00 in Tashkent and 21:00 in Los Angeles;
        // only the middle one is a morning.
        val points = listOf(
            point(4, offsetMinutes = 120),
            point(4, offsetMinutes = 300),
            point(4, offsetMinutes = -420),
        )
        val kept = applyFilter(
            points,
            TimelineFilter(timeOfDay = TimeOfDayWindow(7 * 60, 12 * 60)),
            emptyList(),
            utc,
        )
        assertEquals(listOf(300), kept.map { it.tzOffsetMinutes })
    }

    @Test
    fun `the window and the date range narrow independently`() {
        val twoDays = (0..47).map { point(it) }
        val secondDay = Instant.parse("2025-06-10T00:00:00Z")..Instant.parse("2025-06-10T23:59:59Z")
        val kept = applyFilter(
            twoDays,
            TimelineFilter(
                dateRange = secondDay,
                timeOfDay = TimeOfDayWindow(9 * 60, 11 * 60),
            ),
            emptyList(),
            utc,
        )
        assertEquals(2, kept.size)
        assertEquals(listOf("2025-06-10T09:00:00Z", "2025-06-10T10:00:00Z"), kept.map { it.timeUtc.toString() })
    }

    // ---------- places ----------

    private fun visit(start: String, end: String, placeId: String, offsetMinutes: Int) = """
        {
          "startTime": "$start",
          "endTime": "$end",
          "startTimeTimezoneUtcOffsetMinutes": $offsetMinutes,
          "endTimeTimezoneUtcOffsetMinutes": $offsetMinutes,
          "visit": { "topCandidate": {
            "placeId": "$placeId",
            "placeLocation": { "latLng": "1.0°, 1.0°" }
          } }
        }
    """.trimIndent()

    @Test
    fun `a place is kept when a visit to it overlaps the window`() {
        // At the office 09:00-18:00 local, and at the gym 19:00-20:00 local.
        // A lunchtime window has to find the office, whose visit starts three
        // hours before the window opens, and not the gym.
        val parsed = parseTimeline(
            """{ "semanticSegments": [
                ${visit("2025-06-09T09:00:00+02:00", "2025-06-09T18:00:00+02:00", "office", 120)},
                ${visit("2025-06-09T19:00:00+02:00", "2025-06-09T20:00:00+02:00", "gym", 120)}
            ] }"""
        )
        val lunch = TimeOfDayWindow(12 * 60, 13 * 60)
        val places = parsed.placeEntries(timeOfDay = lunch, fallbackZone = utc)
        assertEquals(listOf("office"), places.map { it.placeId })

        // And the count in step 2 has to agree with the length of that list.
        assertEquals(places.size, parsed.placeCount(timeOfDay = lunch, fallbackZone = utc))
    }

    @Test
    fun `a visit is judged on the clock where it happened`() {
        // The same nine hours of UTC, once in Stockholm and once in Tashkent.
        // 07:00-16:00 UTC is 09:00-18:00 in Stockholm and 12:00-21:00 in
        // Tashkent, so a window of 18:00-20:00 finds only the second.
        val parsed = parseTimeline(
            """{ "semanticSegments": [
                ${visit("2025-06-09T09:00:00+02:00", "2025-06-09T18:00:00+02:00", "stockholm", 120)},
                ${visit("2025-06-10T12:00:00+05:00", "2025-06-10T21:00:00+05:00", "tashkent", 300)}
            ] }"""
        )
        val evening = TimeOfDayWindow(18 * 60 + 30, 20 * 60)
        val places = parsed.placeEntries(timeOfDay = evening, fallbackZone = utc)
        assertEquals(listOf("tashkent"), places.map { it.placeId })
    }

    @Test
    fun `no window means every place, as before`() {
        val parsed = parseTimeline(
            """{ "semanticSegments": [
                ${visit("2025-06-09T09:00:00+02:00", "2025-06-09T18:00:00+02:00", "office", 120)},
                ${visit("2025-06-09T19:00:00+02:00", "2025-06-09T20:00:00+02:00", "gym", 120)}
            ] }"""
        )
        assertEquals(2, parsed.placeEntries(fallbackZone = utc).size)
        assertEquals(2, parsed.placeEntries(timeOfDay = TimeOfDayWindow.WholeDay, fallbackZone = utc).size)
    }
}
