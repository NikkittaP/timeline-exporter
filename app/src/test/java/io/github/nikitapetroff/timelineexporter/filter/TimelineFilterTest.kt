package io.github.nikitapetroff.timelineexporter.filter

import io.github.nikitapetroff.timelineexporter.parser.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimelineFilterTest {

    private val points = listOf(
        PathPoint(Instant.parse("2025-06-01T12:00:00Z"), 1.0, 1.0),
        PathPoint(Instant.parse("2025-06-15T12:00:00Z"), 2.0, 2.0),
        PathPoint(Instant.parse("2025-06-30T12:00:00Z"), 3.0, 3.0),
    )

    @Test
    fun `empty filter returns the input unchanged`() {
        assertEquals(points, applyFilter(points, TimelineFilter()))
    }

    @Test
    fun `date range keeps only points inside the range (inclusive on both ends)`() {
        val range = Instant.parse("2025-06-15T00:00:00Z")..Instant.parse("2025-06-15T23:59:59Z")
        val result = applyFilter(points, TimelineFilter(dateRange = range))
        assertEquals(1, result.size)
        assertEquals(points[1], result.single())
    }

    @Test
    fun `range covering everything keeps all points`() {
        val range = Instant.parse("2025-01-01T00:00:00Z")..Instant.parse("2025-12-31T23:59:59Z")
        assertEquals(points, applyFilter(points, TimelineFilter(dateRange = range)))
    }

    @Test
    fun `range matching nothing returns empty list`() {
        val range = Instant.parse("2026-01-01T00:00:00Z")..Instant.parse("2026-12-31T00:00:00Z")
        assertTrue(applyFilter(points, TimelineFilter(dateRange = range)).isEmpty())
    }

    @Test
    fun `order is preserved`() {
        val range = Instant.parse("2025-06-01T00:00:00Z")..Instant.parse("2025-06-30T23:59:59Z")
        val result = applyFilter(points, TimelineFilter(dateRange = range))
        assertEquals(points, result)
    }
}
