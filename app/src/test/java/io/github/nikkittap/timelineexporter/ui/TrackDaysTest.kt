package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

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
}
