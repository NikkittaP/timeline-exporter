package io.github.nikkittap.timelineexporter.trips

import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.ParsedTimeline
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.parser.Place
import io.github.nikkittap.timelineexporter.parser.Segment
import io.github.nikkittap.timelineexporter.parser.SegmentKind
import io.github.nikkittap.timelineexporter.parser.parseTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class TripDetectorTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    // Home in Malmö; Copenhagen is ~30 km away (not a trip), Gothenburg ~230 km.
    private val home = 55.60 to 13.00
    private val near = 55.68 to 12.57
    private val far = 57.70 to 11.97

    private fun h(hours: Number) = t0.plusSeconds((hours.toDouble() * 3600).toLong())

    private fun visit(fromH: Number, toH: Number, at: Pair<Double, Double>, type: String = "UNKNOWN", id: String = "p$fromH") =
        Segment(
            kind = SegmentKind.VISIT,
            start = h(fromH),
            end = h(toH),
            place = Place(placeId = id, semanticType = type, latitude = at.first, longitude = at.second, hierarchyLevel = 0),
        )

    private fun drive(fromH: Number, toH: Number, meters: Double) =
        Segment(SegmentKind.ACTIVITY, h(fromH), h(toH), activityType = "IN_PASSENGER_VEHICLE", distanceMeters = meters)

    private fun point(atH: Number, at: Pair<Double, Double>) = PathPoint(h(atH), at.first, at.second)

    private fun timeline(segments: List<Segment>, points: List<PathPoint> = emptyList()) = ParsedTimeline(
        pathPoints = points.sortedBy { it.timeUtc },
        totalSegments = segments.size,
        pathSegments = 0,
        visitSegments = segments.count { it.kind == SegmentKind.VISIT },
        activitySegments = segments.count { it.kind == SegmentKind.ACTIVITY },
        segments = segments.sortedBy { it.start },
    )

    private fun found(t: ParsedTimeline) = (detectTrips(t, zone) as TripDetection.Found).trips

    @Test
    fun `a weekend away is one trip with its stats`() {
        val trips = found(
            timeline(
                listOf(
                    visit(0, 10, home, "HOME"),
                    drive(10, 13, 230_000.0),
                    visit(13, 40, far, id = "hotel"),
                    visit(40, 45, far, id = "museum"),
                    drive(45, 48, 231_000.0),
                    visit(48, 60, home, "HOME"),
                ),
            ),
        )
        assertEquals(1, trips.size)
        val trip = trips.single()
        assertEquals(h(10), trip.start)
        assertEquals(h(48), trip.end)
        assertEquals(2, trip.placeCount)
        assertEquals(461_000.0, trip.travelledMeters, 0.1)
        assertEquals(MovementGroup.DRIVING, trip.movements.single().first)
        assertTrue(trip.maxDistanceMeters > 200_000)
        assertFalse(trip.isDayTrip)
        assertFalse(trip.openStart || trip.openEnd)
    }

    @Test
    fun `staying within fifty kilometres is not a trip`() {
        val trips = found(timeline(listOf(visit(0, 10, home, "HOME"), visit(11, 20, near), visit(21, 30, home, "HOME"))))
        assertTrue(trips.isEmpty())
    }

    @Test
    fun `an errand shorter than three hours is dropped, a day trip is kept`() {
        val trips = found(
            timeline(
                listOf(
                    visit(0, 10, home, "HOME"),
                    visit(10.5, 11.5, far, id = "a"), // 10 → 12: two hours away
                    visit(12, 20, home, "HOME"),
                    visit(21, 26, far, id = "b"), // 20 → 27: seven hours away
                    visit(27, 40, home, "HOME"),
                ),
            ),
        )
        assertEquals(1, trips.size)
        assertTrue(trips.single().isDayTrip)
        assertEquals(h(20), trips.single().start)
    }

    @Test
    fun `a short stop at home joins two halves`() {
        val trips = found(
            timeline(
                listOf(
                    visit(0, 10, home, "HOME"),
                    visit(12, 30, far, id = "a"),
                    visit(31, 32, home, "HOME"), // an hour home, under the merge gap
                    visit(33, 50, far, id = "b"),
                    visit(52, 60, home, "HOME"),
                ),
            ),
        )
        assertEquals(1, trips.size)
        assertEquals(h(10), trips.single().start)
        assertEquals(h(52), trips.single().end)
    }

    @Test
    fun `a file that starts and ends away marks both edges open`() {
        val trips = found(
            timeline(
                listOf(
                    visit(0, 20, far, id = "a"),
                    visit(22, 40, home, "HOME"),
                    visit(42, 60, far, id = "b"),
                ),
            ),
        )
        assertEquals(2, trips.size)
        assertTrue(trips[0].openStart)
        assertFalse(trips[0].openEnd)
        assertTrue(trips[1].openEnd)
    }

    @Test
    fun `a move follows the newest home`() {
        // Living in Gothenburg from hour 100: being there is home, not away.
        val trips = found(
            timeline(
                listOf(
                    visit(0, 10, home, "HOME"),
                    visit(100, 200, far, "HOME", id = "newhome"),
                    visit(210, 230, home, id = "visit-old-town"),
                    visit(240, 260, far, "HOME", id = "newhome"),
                ),
            ),
        )
        // The move itself is not a trip: the first position in Gothenburg is
        // also the first labelled home there. The visit back to Malmö is.
        assertEquals(1, trips.size)
        assertEquals(h(200), trips.single().start)
    }

    @Test
    fun `without home labels home is where the nights are spent`() {
        val points = (0 until 10).flatMap { day ->
            listOf(point(day * 24 + 2, home), point(day * 24 + 14, home))
        } + listOf(point(4 * 24 + 8, far), point(4 * 24 + 18, far))
        val trips = found(timeline(emptyList(), points))
        assertEquals(1, trips.size)
    }

    @Test
    fun `no visits and no points means no trips, not no home`() {
        assertEquals(TripDetection.Found(emptyList()), detectTrips(timeline(emptyList()), zone))
    }

    @Test
    fun `the reference year has the eight trips measured by hand`() {
        val file = Path.of("F:/Timeline/Timeline_2.json")
        assumeTrue("Skipped: $file not present (developer-local only).", Files.exists(file))
        val parsed = Files.newInputStream(file).use { parseTimeline(it) }
        val trips = (detectTrips(parsed, ZoneId.of("Europe/Stockholm")) as TripDetection.Found).trips
        trips.forEach {
            println("${it.start} – ${it.end}  ${it.maxDistanceMeters.toInt() / 1000} km  ${it.placeCount} places  open=${it.openStart}/${it.openEnd}")
        }
        assertEquals(8, trips.size)
        assertEquals(3, trips.count { !it.isDayTrip })
        assertTrue(trips.first().openStart)
        // Norway, 19 July – 1 August 2025.
        val norway = trips.single { it.start.toString().startsWith("2025-07-19") }
        assertTrue(norway.durationSeconds > 13 * 86_400L)
    }
}
