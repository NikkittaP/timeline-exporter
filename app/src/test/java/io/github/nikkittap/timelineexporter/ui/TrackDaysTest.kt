package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class TrackDaysTest {
    private fun point(time: String, offset: Int? = 0) =
        PathPoint(Instant.parse(time), 55.0, 13.0, offset)

    @Test fun `midnight keeps every edge exactly once and shares its boundary sample`() {
        val points = listOf(
            point("2026-09-08T23:50:00Z"), point("2026-09-09T00:10:00Z"),
            point("2026-09-09T00:20:00Z"), point("2026-09-10T00:05:00Z"),
            point("2026-09-10T00:15:00Z"),
        )
        val runs = splitTrackDays(points, ZoneOffset.UTC)
        assertEquals(listOf("2026-09-08", "2026-09-09", "2026-09-10"), runs.map { it.date.toString() })
        assertEquals(points.zipWithNext(), runs.flatMap { it.points.zipWithNext() })
        assertTrue(runs.all { it.points.size >= 2 })
    }

    @Test fun `historical offset wins over current phone timezone`() {
        val sample = point("2026-09-09T22:30:00Z", 120)
        assertEquals(LocalDate.parse("2026-09-10"), sample.trackDate(ZoneId.of("America/Los_Angeles")))
        assertEquals(LocalDate.parse("2026-09-09"), sample.copy(tzOffsetMinutes = null).trackDate(ZoneId.of("America/Los_Angeles")))
    }

    @Test fun `travel back across date line can revisit an earlier date without losing edges`() {
        val points = listOf(
            point("2026-09-09T10:00:00Z", 840),
            point("2026-09-09T11:00:00Z", -600),
            point("2026-09-09T12:00:00Z", -600),
        )
        val runs = splitTrackDays(points, ZoneOffset.UTC)
        assertEquals(listOf("2026-09-10", "2026-09-09"), runs.map { it.date.toString() })
        assertEquals(points.zipWithNext(), runs.flatMap { it.points.zipWithNext() })
    }

    @Test fun `empty and isolated samples produce no lines`() {
        assertTrue(splitTrackDays(emptyList(), ZoneOffset.UTC).isEmpty())
        assertTrue(splitTrackDays(listOf(point("2026-09-09T00:00:00Z")), ZoneOffset.UTC).isEmpty())
    }

    @Test fun `colors remain stable after range changes and work before epoch`() {
        val start = LocalDate.parse("1969-12-28")
        val dates = (0L..365L).map { start.plusDays(it) }
        assertTrue(dates.zipWithNext().all { (a, b) -> trackDayColor(a) != trackDayColor(b) })
        assertEquals(dates.map(::trackDayColor).drop(15), dates.drop(15).map(::trackDayColor))
        assertNotEquals(trackDayColor(start), trackDayColor(start.plusDays(1)))
    }

    @Test fun `colour is not a synonym for the weekday`() {
        val monday = LocalDate.parse("2026-09-07")
        assertNotEquals(trackDayColor(monday), trackDayColor(monday.plusWeeks(1)))
        assertNotEquals(trackDayColor(monday), trackDayColor(monday.plusWeeks(2)))
        // Twenty-one days of distinct colours, then an exact repeat: a legend
        // covering less than three weeks can never show one swatch twice.
        val cycle = (0L until 21L).map { monday.plusDays(it) }
        assertEquals(21, cycle.map(::trackDayColor).distinct().size)
        assertEquals(cycle.map(::trackDayColor), cycle.map { trackDayColor(it.plusDays(21)) })
    }

    @Test fun `day legend summary is null for no days and de-duplicates otherwise`() {
        assertNull(dayLegendSummary(emptyList()))
        val summary = dayLegendSummary(
            listOf(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-03"), LocalDate.parse("2026-01-05"))
        )
        assertEquals(2, summary!!.dayCount)
        assertEquals(LocalDate.parse("2026-01-03"), summary.firstDate)
        assertEquals(LocalDate.parse("2026-01-05"), summary.lastDate)
    }

    @Test fun `day legend summary hints stay bounded and span the full range`() {
        val dates = (0L until 400L).map { LocalDate.parse("2025-01-01").plusDays(it) }
        val summary = dayLegendSummary(dates)!!
        assertEquals(400, summary.dayCount)
        assertTrue(summary.hintDates.size <= 5)
        assertEquals(dates.first(), summary.hintDates.first())
        assertEquals(dates.last(), summary.hintDates.last())
        assertEquals(summary.hintDates, summary.hintDates.sorted())

        val fewDates = listOf(LocalDate.parse("2026-06-10"), LocalDate.parse("2026-06-11"), LocalDate.parse("2026-06-12"))
        assertEquals(fewDates, dayLegendSummary(fewDates)!!.hintDates)
    }

    @Test fun `day range formatting collapses a single day and a shared year`() {
        val oneDay = dayLegendSummary(listOf(LocalDate.parse("2026-06-10")))!!
        assertEquals("Jun 10, 2026", formatDayRange(oneDay, Locale.US))

        val sameYear = dayLegendSummary(listOf(LocalDate.parse("2026-06-10"), LocalDate.parse("2026-06-12")))!!
        assertEquals("Jun 10 – Jun 12, 2026", formatDayRange(sameYear, Locale.US))

        val crossYear = dayLegendSummary(listOf(LocalDate.parse("2025-01-15"), LocalDate.parse("2026-09-10")))!!
        assertEquals("Jan 2025 – Sep 2026", formatDayRange(crossYear, Locale.US))
    }

    @Test fun `day list entries are grouped year, then month, most recent first`() {
        val dates = listOf(
            LocalDate.parse("2025-12-28"),
            LocalDate.parse("2026-08-29"),
            LocalDate.parse("2026-08-31"),
            LocalDate.parse("2026-09-08"),
            LocalDate.parse("2026-09-09"),
            LocalDate.parse("2026-09-09"), // duplicate sample day
        )
        val entries = dayListEntries(dates)
        assertEquals(
            listOf(
                DayListEntry.YearHeader(2026),
                DayListEntry.MonthHeader(YearMonth.of(2026, 9)),
                DayListEntry.Day(LocalDate.parse("2026-09-09")),
                DayListEntry.Day(LocalDate.parse("2026-09-08")),
                DayListEntry.MonthHeader(YearMonth.of(2026, 8)),
                DayListEntry.Day(LocalDate.parse("2026-08-31")),
                DayListEntry.Day(LocalDate.parse("2026-08-29")),
                DayListEntry.YearHeader(2025),
                DayListEntry.MonthHeader(YearMonth.of(2025, 12)),
                DayListEntry.Day(LocalDate.parse("2025-12-28")),
            ),
            entries,
        )
    }

    @Test fun `day entry index finds a date past the headers, and misses honestly`() {
        val entries = dayListEntries(
            listOf(
                LocalDate.parse("2025-12-28"),
                LocalDate.parse("2026-08-31"),
                LocalDate.parse("2026-09-09"),
            )
        )
        // Year header, month header, then the day itself.
        assertEquals(2, dayEntryIndex(entries, LocalDate.parse("2026-09-09")))
        assertEquals(entries.size - 1, dayEntryIndex(entries, LocalDate.parse("2025-12-28")))
        // A day selected on the map while the sheet lists only what is in view
        // is simply not in this list; nothing to scroll to.
        assertEquals(-1, dayEntryIndex(entries, LocalDate.parse("2026-01-01")))
        assertEquals(-1, dayEntryIndex(entries, null))
    }

    @Test fun `a day box covers every stretch of that day and nothing from its neighbours`() {
        fun at(time: String, lat: Double, lon: Double) = PathPoint(Instant.parse(time), lat, lon, 0)
        val points = listOf(
            at("2026-09-08T22:00:00Z", 10.0, 10.0),
            // Morning in one city, evening in another: one day, two stretches,
            // and the box has to hold both.
            at("2026-09-09T08:00:00Z", 59.3, 18.0),
            at("2026-09-09T09:00:00Z", 59.4, 18.2),
            at("2026-09-09T20:00:00Z", 55.6, 12.6),
            at("2026-09-10T08:00:00Z", 80.0, 80.0),
        )
        val box = dayBox(points, LocalDate.parse("2026-09-09"), ZoneOffset.UTC)!!
        assertEquals(3, box.pointCount)
        assertEquals(55.6, box.minLat, 1e-9)
        assertEquals(59.4, box.maxLat, 1e-9)
        assertEquals(12.6, box.minLon, 1e-9)
        assertEquals(18.2, box.maxLon, 1e-9)
        assertFalse(box.isDegenerate)

        assertNull(dayBox(points, LocalDate.parse("2026-09-11"), ZoneOffset.UTC))
    }

    @Test fun `a day spent in one spot has no box to fit`() {
        fun at(time: String) = PathPoint(Instant.parse(time), 59.3, 18.0, 0)
        val indoors = listOf(at("2026-09-09T08:00:00Z"), at("2026-09-09T18:00:00Z"))
        assertTrue(dayBox(indoors, LocalDate.parse("2026-09-09"), ZoneOffset.UTC)!!.isDegenerate)

        val single = listOf(PathPoint(Instant.parse("2026-09-09T08:00:00Z"), 59.3, 18.0, 0))
        assertTrue(dayBox(single, LocalDate.parse("2026-09-09"), ZoneOffset.UTC)!!.isDegenerate)
    }

    @Test fun `local day matches java time for fixed offsets and for a DST zone`() {
        val stockholm = ZoneId.of("Europe/Stockholm")
        // Straddles the March DST change, so the naive "add a constant
        // offset" shortcut would drift here if it ignored the zone rules.
        val base = Instant.parse("2026-03-28T00:00:00Z")
        for (step in 0L until 96L) {
            val at = base.plusSeconds(step * 1800L)
            assertEquals(
                at.atZone(stockholm).toLocalDate(),
                PathPoint(at, 59.3, 18.0, null).trackDate(stockholm),
            )
            assertEquals(
                at.atZone(ZoneOffset.ofTotalSeconds(-8 * 3600)).toLocalDate(),
                PathPoint(at, 59.3, 18.0, -8 * 60).trackDate(stockholm),
            )
        }
    }
    // ---------- Which days are in view ----------

    private fun at(time: String, lat: Double, lon: Double) =
        PathPoint(Instant.parse(time), lat, lon, 0)

    /** Every day, everywhere: the window a camera showing the whole earth has. */
    private val world = TrackWindow(minLat = -90.0, maxLat = 90.0, westLon = -180.0, lonSpan = 360.0)

    /** Three days in three places, each with a short line of its own. */
    private val threeDays = listOf(
        at("2026-09-09T08:00:00Z", 55.00, 13.00), at("2026-09-09T08:20:00Z", 55.02, 13.02),
        at("2026-09-10T08:00:00Z", 57.00, 15.00), at("2026-09-10T08:20:00Z", 57.02, 15.02),
        at("2026-09-11T08:00:00Z", 59.00, 17.00), at("2026-09-11T08:20:00Z", 59.02, 17.02),
    )

    @Test fun `a day whose line crosses the view counts, with both its samples off screen`() {
        val crossing = listOf(
            at("2026-09-09T08:00:00Z", 55.01, 12.95),
            at("2026-09-09T08:20:00Z", 55.01, 13.10),
        )
        val window = TrackWindow(minLat = 55.0, maxLat = 55.02, westLon = 13.0, lonSpan = 0.02)
        assertEquals(
            listOf(LocalDate.parse("2026-09-09")),
            drawnTrackDays(crossing, ZoneOffset.UTC, window),
        )
    }

    @Test fun `a view holding the whole track holds every day the track draws`() {
        val everything = drawnTrackDays(threeDays, ZoneOffset.UTC)
        assertEquals(
            listOf("2026-09-09", "2026-09-10", "2026-09-11"),
            everything.map { it.toString() },
        )
        assertEquals(everything, drawnTrackDays(threeDays, ZoneOffset.UTC, world))
        val wide = TrackWindow(minLat = 54.0, maxLat = 60.0, westLon = 12.0, lonSpan = 6.0)
        assertEquals(everything, drawnTrackDays(threeDays, ZoneOffset.UTC, wide))
    }

    @Test fun `narrowing the view can only drop days`() {
        val wide = TrackWindow(minLat = 54.0, maxLat = 60.0, westLon = 12.0, lonSpan = 6.0)
        val narrow = TrackWindow(minLat = 56.5, maxLat = 57.5, westLon = 14.5, lonSpan = 1.0)
        val inWide = drawnTrackDays(threeDays, ZoneOffset.UTC, wide)
        val inNarrow = drawnTrackDays(threeDays, ZoneOffset.UTC, narrow)
        assertEquals(listOf(LocalDate.parse("2026-09-10")), inNarrow)
        assertTrue(inWide.containsAll(inNarrow))
    }

    @Test fun `a view straddling the antimeridian is one arc, not the rest of the world`() {
        val across = listOf(
            at("2026-09-09T08:00:00Z", 60.0, 179.6),
            at("2026-09-09T08:20:00Z", 60.0, -179.6),
        )
        val elsewhere = listOf(
            at("2026-09-10T08:00:00Z", 60.0, 0.00),
            at("2026-09-10T08:20:00Z", 60.0, 0.05),
        )
        val window = TrackWindow(minLat = 59.0, maxLat = 61.0, westLon = 179.0, lonSpan = 2.0)
        assertEquals(
            listOf(LocalDate.parse("2026-09-09")),
            drawnTrackDays(across + elsewhere, ZoneOffset.UTC, window),
        )
    }

    @Test fun `a flight is a dashed connector rather than a line, and brings no day with it`() {
        val flight = listOf(
            at("2026-09-09T08:00:00Z", 55.6, 12.6),
            at("2026-09-09T14:00:00Z", 41.3, 69.2),
        )
        assertTrue(drawnTrackDays(flight, ZoneOffset.UTC).isEmpty())
        assertTrue(drawnTrackDays(flight, ZoneOffset.UTC, world).isEmpty())
    }

    @Test fun `a day left with one lone sample draws nothing and counts as nothing`() {
        val lone = listOf(at("2026-09-09T08:00:00Z", 55.0, 13.0))
        assertTrue(drawnTrackDays(lone, ZoneOffset.UTC, world).isEmpty())
    }

    @Test fun `the days in view are the days the track is split into`() {
        val points = listOf(
            at("2026-09-08T23:50:00Z", 55.00, 13.00),
            at("2026-09-09T00:10:00Z", 55.01, 13.01),
            at("2026-09-09T23:55:00Z", 55.02, 13.02),
            at("2026-09-10T00:05:00Z", 55.03, 13.03),
        )
        assertEquals(
            splitTrackDays(points, ZoneOffset.UTC).map { it.date },
            drawnTrackDays(points, ZoneOffset.UTC, world),
        )
    }
    /** Walk a view's top edge west to east and back along its bottom. */
    private fun ring(west: Double, span: Double, steps: Int = 4): List<Double> {
        val top = (0..steps).map { west + span * it / steps }
        return (top + top.reversed()).map { wrapLongitude(it) }
    }

    @Test fun `a view wider than half the world is measured the way it is drawn`() {
        val arc = longitudeArc(ring(west = -53.6, span = 183.4))
        assertEquals(-53.6, arc.west, 1e-9)
        assertEquals(183.4, arc.span, 1e-9)
    }

    @Test fun `a view straddling the antimeridian is one arc rather than two`() {
        val arc = longitudeArc(ring(west = 170.0, span = 20.0))
        assertEquals(170.0, arc.west, 1e-9)
        assertEquals(20.0, arc.span, 1e-9)
    }

    @Test fun `a view of one meridian has no width, and one of the world has all of it`() {
        assertEquals(0.0, longitudeArc(listOf(12.6, 12.6, 12.6)).span, 1e-9)
        assertEquals(360.0, longitudeArc(ring(west = -180.0, span = 400.0, steps = 8)).span, 1e-9)
    }
}
