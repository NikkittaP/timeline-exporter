package io.github.nikkittap.timelineexporter.filter

import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.parser.Place
import io.github.nikkittap.timelineexporter.parser.Segment
import io.github.nikkittap.timelineexporter.parser.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

/**
 * The filters that need the point↔segment join, plus duplicate collapsing.
 *
 * The timeline below mirrors the real shape of an export: a stay, then a walk,
 * then a stay, then a drive — with track points scattered across all four, most
 * of them recorded while standing still.
 */
class MovementFilterTest {

    private fun at(minute: Int) = Instant.parse("2025-06-01T00:00:00Z").plusSeconds(minute * 60L)

    private fun point(minute: Int, lat: Double = 1.0, lon: Double = 1.0) =
        PathPoint(at(minute), lat, lon)

    private fun visit(from: Int, to: Int, id: String) = Segment(
        kind = SegmentKind.VISIT,
        start = at(from),
        end = at(to),
        place = Place(placeId = id, semanticType = null, latitude = 1.0, longitude = 1.0),
    )

    private fun activity(from: Int, to: Int, type: String) = Segment(
        kind = SegmentKind.ACTIVITY,
        start = at(from),
        end = at(to),
        activityType = type,
    )

    private val segments = listOf(
        visit(0, 10, "home"),
        activity(10, 20, "WALKING"),
        visit(20, 30, "office"),
        activity(30, 40, "IN_PASSENGER_VEHICLE"),
    )

    private val points = listOf(
        point(1), point(5), point(9),      // at home
        point(12), point(18),              // walking
        point(22), point(28),              // at the office
        point(33), point(38),              // driving
    )

    @Test
    fun `movingOnly drops points recorded while stationary`() {
        val result = applyFilter(points, TimelineFilter(movingOnly = true), segments)
        assertEquals(listOf(at(12), at(18), at(33), at(38)), result.map { it.timeUtc })
    }

    @Test
    fun `movement filter keeps only the selected groups`() {
        val result = applyFilter(
            points,
            TimelineFilter(movements = setOf(MovementGroup.WALKING)),
            segments,
        )
        assertEquals(listOf(at(12), at(18)), result.map { it.timeUtc })
    }

    @Test
    fun `several movement groups can be selected at once`() {
        val result = applyFilter(
            points,
            TimelineFilter(movements = setOf(MovementGroup.WALKING, MovementGroup.DRIVING)),
            segments,
        )
        assertEquals(4, result.size)
    }

    @Test
    fun `an empty movement set is taken literally and matches nothing`() {
        val result = applyFilter(points, TimelineFilter(movements = emptySet()), segments)
        assertEquals(emptyList<PathPoint>(), result)
    }

    @Test
    fun `movement filters combine with the date range`() {
        val result = applyFilter(
            points,
            TimelineFilter(
                dateRange = at(0)..at(25),
                movements = setOf(MovementGroup.WALKING, MovementGroup.DRIVING),
            ),
            segments,
        )
        // The drive is after the cut-off, so only the walk survives.
        assertEquals(listOf(at(12), at(18)), result.map { it.timeUtc })
    }

    @Test
    fun `points outside every segment are kept rather than silently deleted`() {
        // A format with no segment structure, or a gap between spans: the app
        // cannot classify the point, so it must not pretend it knows.
        val orphan = point(99)
        val result = applyFilter(
            points + orphan,
            TimelineFilter(movements = setOf(MovementGroup.WALKING)),
            segments,
        )
        assertEquals(listOf(at(12), at(18), at(99)), result.map { it.timeUtc })
    }

    @Test
    fun `path segments do not shadow the activity they overlap`() {
        // A path segment spans the same wall-clock time as the walk it records.
        // Both are in the list and the path one sorts later, so a naive "last
        // segment starting before this point" lookup would land on it, find no
        // movement type, and throw away every point of the walk.
        val withPath = segments + Segment(
            kind = SegmentKind.PATH,
            start = at(11),
            end = at(19),
            points = listOf(point(12), point(18)),
        )
        val result = applyFilter(
            points,
            TimelineFilter(movements = setOf(MovementGroup.WALKING)),
            withPath.sortedBy { it.start },
        )
        assertEquals(listOf(at(12), at(18)), result.map { it.timeUtc })
    }

    @Test
    fun `a visit nested inside another visit still reads as stationary`() {
        // Google records a shop and the mall around it as two visits covering
        // the same minutes; whichever the lookup picks, the answer is a visit.
        val nested = listOf(
            visit(0, 60, "mall"),
            visit(10, 20, "shop"),
            activity(60, 70, "WALKING"),
        )
        val result = applyFilter(
            listOf(point(5), point(15), point(30), point(65)),
            TimelineFilter(movingOnly = true),
            nested,
        )
        assertEquals(listOf(at(65)), result.map { it.timeUtc })
    }

    @Test
    fun `without segments the movement filters leave the points alone`() {
        val result = applyFilter(points, TimelineFilter(movingOnly = true), emptyList())
        assertEquals(points, result)
    }

    @Test
    fun `dropRepeatedPoints collapses runs sharing the exact same coordinates`() {
        val repeated = listOf(
            point(1, 10.0, 20.0),
            point(2, 10.0, 20.0),
            point(3, 10.0, 20.0),
            point(4, 11.0, 21.0),
            point(5, 10.0, 20.0),
        )
        val result = applyFilter(repeated, TimelineFilter(dropRepeatedPoints = true))
        // First of each run survives; returning to an earlier spot is not a run.
        assertEquals(listOf(at(1), at(4), at(5)), result.map { it.timeUtc })
        assertSame(repeated[0], result[0])
    }

    @Test
    fun `dropRepeatedPoints compares against the last kept point`() {
        // With a date range removing the middle of a run, the survivors must
        // still collapse against each other rather than against a dropped point.
        val repeated = listOf(
            point(1, 10.0, 20.0),
            point(2, 10.0, 20.0),
            point(3, 10.0, 20.0),
        )
        val result = applyFilter(
            repeated,
            TimelineFilter(dateRange = at(2)..at(3), dropRepeatedPoints = true),
        )
        assertEquals(listOf(at(2)), result.map { it.timeUtc })
    }

    @Test
    fun `default filter is still a no-op returning the very same list`() {
        assertSame(points, applyFilter(points, TimelineFilter(), segments))
    }
}
