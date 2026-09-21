package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * The preview map draws one line per pixel it can actually show, and the rest
 * of the track is arithmetic nobody sees. These are the rules that decide what
 * survives.
 */
class TrackDetailTest {

    private val epoch = Instant.parse("2025-06-09T00:00:00Z")

    private fun point(latitude: Double, longitude: Double, index: Int = 0) =
        PathPoint(epoch.plusSeconds(index * 60L), latitude, longitude)

    /** A straight run of [count] points from (0,0) heading east. */
    private fun straightLine(count: Int, stepDegrees: Double = 0.001) =
        List(count) { point(0.0, it * stepDegrees, it) }

    // ---- simplifyTrack ----

    @Test
    fun `a straight line collapses to its two ends`() {
        val simplified = simplifyTrack(straightLine(500), toleranceMeters = 10.0)
        assertEquals(2, simplified.size)
    }

    @Test
    fun `a detour wider than the tolerance is kept`() {
        // ~111 m north of a line that runs along the equator.
        val points = listOf(
            point(0.0, 0.0, 0),
            point(0.001, 0.005, 1),
            point(0.0, 0.01, 2),
        )
        assertEquals(3, simplifyTrack(points, toleranceMeters = 10.0).size)
    }

    @Test
    fun `a detour narrower than the tolerance is dropped`() {
        // ~1.1 m off the line, against a 10 m tolerance.
        val points = listOf(
            point(0.0, 0.0, 0),
            point(0.00001, 0.005, 1),
            point(0.0, 0.01, 2),
        )
        assertEquals(2, simplifyTrack(points, toleranceMeters = 10.0).size)
    }

    @Test
    fun `the first and last point always survive`() {
        val points = straightLine(1_000)
        val simplified = simplifyTrack(points, toleranceMeters = 1_000_000.0)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun `a zero tolerance hands back the very list it was given`() {
        val points = straightLine(10)
        assertSame(points, simplifyTrack(points, toleranceMeters = 0.0))
    }

    @Test
    fun `a run too short to simplify is handed straight back`() {
        listOf(emptyList(), straightLine(1), straightLine(2)).forEach {
            assertSame(it, simplifyTrack(it, toleranceMeters = 10.0))
        }
    }

    @Test
    fun `what comes out is a subsequence of what went in`() {
        val random = Random(20260921)
        val points = List(5_000) { i ->
            point(55.0 + random.nextDouble() * 0.05, 13.0 + random.nextDouble() * 0.05, i)
        }
        val simplified = simplifyTrack(points, toleranceMeters = 50.0)

        assertTrue(simplified.size < points.size)
        var next = 0
        for (kept in simplified) {
            while (next < points.size && points[next] !== kept) next++
            assertTrue("dropped out of order: $kept", next < points.size)
            next++
        }
    }

    @Test
    fun `a full year of points simplifies without running out of stack`() {
        // Recursion is the obvious way to write Douglas-Peucker and the wrong
        // one here: a real export is 284,402 points, and a track that is one
        // long detour recurses once per point.
        val random = Random(1)
        val points = List(284_402) { i ->
            point(55.0 + i * 1e-7, 13.0 + random.nextDouble() * 0.2, i)
        }
        assertTrue(simplifyTrack(points, toleranceMeters = 100.0).size < points.size)
    }

    @Test
    fun `one ranking answers every tolerance exactly as Douglas-Peucker would`() {
        // The map thins by rank, once per track, instead of running
        // Douglas-Peucker again on every zoom step; the two must agree point
        // for point or zooming would draw a different line than it used to.
        val random = Random(7)
        var latitude = 55.0
        var longitude = 13.0
        val points = List(20_000) { i ->
            latitude += (random.nextDouble() - 0.5) * 0.002
            longitude += (random.nextDouble() - 0.5) * 0.002
            point(latitude, longitude, i)
        }
        val rank = rankTrack(points)
        for (tolerance in listOf(0.5, 3.0, 20.0, 150.0, 1_000.0, 40_000.0)) {
            val expected = referenceDouglasPeucker(points, tolerance)
            assertEquals("tolerance $tolerance", expected, keepRanked(points, rank, tolerance))
            assertEquals("tolerance $tolerance", expected.size, countRanked(rank, tolerance))
        }
    }

    /** The textbook recursive form, kept here as the definition to check against. */
    private fun referenceDouglasPeucker(points: List<PathPoint>, tolerance: Double): List<PathPoint> {
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        fun split(first: Int, last: Int) {
            if (last <= first + 1) return
            var farthest = -1
            var farthestDistance = tolerance
            for (i in first + 1 until last) {
                val distance = distanceToChord(points[i], points[first], points[last])
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthest = i
                }
            }
            if (farthest < 0) return
            keep[farthest] = true
            split(first, farthest)
            split(farthest, last)
        }
        split(0, points.size - 1)
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distanceToChord(point: PathPoint, from: PathPoint, to: PathPoint): Double {
        val metersPerLongitude = 111_320.0 * kotlin.math.cos(Math.toRadians(from.latitude))
        val px = (point.longitude - from.longitude) * metersPerLongitude
        val py = (point.latitude - from.latitude) * 111_320.0
        val sx = (to.longitude - from.longitude) * metersPerLongitude
        val sy = (to.latitude - from.latitude) * 111_320.0
        val lengthSq = sx * sx + sy * sy
        if (lengthSq == 0.0) return kotlin.math.sqrt(px * px + py * py)
        val t = ((px * sx + py * sy) / lengthSq).coerceIn(0.0, 1.0)
        val dx = px - t * sx
        val dy = py - t * sy
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // ---- choosing what to draw ----

    /** A track whose drawn point count climbs steeply with every level. */
    private val steepTrack: (Int) -> Int = { level -> minOf(1_000_000, 100 * (level + 1) * (level + 1) * (level + 1)) }

    @Test
    fun `a phone that can afford the whole track gets it at once and keeps it`() {
        val budget = 400_000
        val first = nextDrawLevel(drawn = null, needed = 0, budget = budget) { 284_402 }
        assertEquals(FULL_DETAIL_LEVEL, first)
        for (needed in 0..FULL_DETAIL_LEVEL) {
            assertEquals(first, nextDrawLevel(first, needed, budget) { 284_402 })
        }
    }

    @Test
    fun `a small zoom change inside the budget redraws nothing`() {
        val drawn = nextDrawLevel(drawn = null, needed = 11, budget = 250_000, pointsAt = steepTrack)
        assertTrue(drawn > 11)
        assertEquals(drawn, nextDrawLevel(drawn, needed = 12, budget = 250_000, pointsAt = steepTrack))
        assertEquals(drawn, nextDrawLevel(drawn, needed = 3, budget = 250_000, pointsAt = steepTrack))
    }

    @Test
    fun `zooming past what is drawn asks for more detail`() {
        assertTrue(nextDrawLevel(drawn = 5, needed = 9, budget = 10_000, pointsAt = steepTrack) >= 9)
    }

    @Test
    fun `the camera's own level is drawn even when it is over the budget`() {
        assertEquals(14, nextDrawLevel(drawn = null, needed = 14, budget = 10, pointsAt = steepTrack))
        // And kept there rather than redrawn on every idle at the same zoom.
        assertEquals(14, nextDrawLevel(drawn = 14, needed = 14, budget = 10, pointsAt = steepTrack))
    }

    @Test
    fun `pulling back from an over-budget close-up drops to what fits`() {
        val level = nextDrawLevel(drawn = 15, needed = 2, budget = 20_000, pointsAt = steepTrack)
        assertTrue(level < 15)
        assertTrue(steepTrack(level) <= 20_000)
    }

    @Test
    fun `more memory never means a smaller budget`() {
        var previous = trackPointBudget(0L, lowRamDevice = true)
        for (gigabytes in 1..16) {
            val budget = trackPointBudget(gigabytes.toLong() shl 30, lowRamDevice = false)
            assertTrue(budget >= previous)
            previous = budget
        }
        assertTrue(trackPointBudget(12L shl 30, lowRamDevice = false) >= 284_402)
    }

    // ---- the zoom ladder ----

    @Test
    fun `zooming in never asks for less detail`() {
        var previous = trackToleranceMeters(zoom = 0.0, latitude = 55.0)
        var zoom = 0.5
        while (zoom <= 22.0) {
            val tolerance = trackToleranceMeters(zoom, latitude = 55.0)
            assertTrue("zoom $zoom loosened to $tolerance from $previous", tolerance <= previous)
            previous = tolerance
            zoom += 0.5
        }
    }

    @Test
    fun `a close camera gets every point there is`() {
        assertEquals(0.0, trackToleranceMeters(zoom = 16.0, latitude = 55.0), 0.0)
        assertEquals(0.0, trackToleranceMeters(zoom = 22.0, latitude = 0.0), 0.0)
    }

    @Test
    fun `a world view simplifies to something a pixel could not show anyway`() {
        // At zoom 2 a screen pixel is kilometres wide; anything finer than
        // that is arithmetic with no picture behind it.
        assertTrue(trackToleranceMeters(zoom = 2.0, latitude = 55.0) > 500.0)
    }

    @Test
    fun `detail steps are coarse enough not to re-render on every nudge`() {
        // The level is what the render is keyed on, so a pinch that moves the
        // zoom by a fraction must not rebuild a year of geometry.
        assertEquals(trackDetailLevel(8.0), trackDetailLevel(8.4))
        assertTrue(trackDetailLevel(8.0) < trackDetailLevel(12.0))
    }

    @Test
    fun `every detail level names one tolerance`() {
        // The level is the cache key for the tolerance, so two zooms on the
        // same level have to simplify identically.
        assertEquals(
            trackToleranceMeters(8.0, latitude = 55.0),
            trackToleranceMeters(8.4, latitude = 55.0),
            0.0,
        )
    }
}
