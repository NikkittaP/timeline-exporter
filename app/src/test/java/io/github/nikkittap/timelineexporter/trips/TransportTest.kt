package io.github.nikkittap.timelineexporter.trips

import io.github.nikkittap.timelineexporter.parser.Segment
import io.github.nikkittap.timelineexporter.parser.SegmentKind
import io.github.nikkittap.timelineexporter.parser.parseTimeline
import io.github.nikkittap.timelineexporter.trips.TransportMode.BOAT
import io.github.nikkittap.timelineexporter.trips.TransportMode.CAR
import io.github.nikkittap.timelineexporter.trips.TransportMode.FLIGHT
import io.github.nikkittap.timelineexporter.trips.TransportMode.TRAIN
import io.github.nikkittap.timelineexporter.trips.TransportMode.WALK
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/** Which transport icons a trip gets, and when it gets none. */
class TransportTest {

    private val t0 = Instant.parse("2025-07-19T08:00:00Z")
    private var clock = 0L

    /** An activity of [km] taking [hours], placed after the previous one. */
    private fun leg(type: String, km: Double, hours: Double): Segment {
        val start = t0.plusSeconds(clock)
        clock += (hours * 3600).toLong() + 600
        return Segment(SegmentKind.ACTIVITY, start, start.plusSeconds((hours * 3600).toLong()), activityType = type, distanceMeters = km * 1000)
    }

    @Test
    fun `a road trip is a car`() {
        val legs = listOf(leg("IN_PASSENGER_VEHICLE", 700.0, 9.0), leg("WALKING", 30.0, 8.0), leg("IN_PASSENGER_VEHICLE", 700.0, 9.0))
        assertEquals(listOf(CAR), transportOf(legs, 700_000.0))
    }

    @Test
    fun `flights first, then the car that is a real part of the trip`() {
        val legs = listOf(
            leg("FLYING", 2400.0, 4.0), leg("IN_PASSENGER_VEHICLE", 1200.0, 20.0),
            leg("IN_BUS", 40.0, 1.0), leg("FLYING", 2400.0, 4.0),
        )
        assertEquals(listOf(FLIGHT, CAR), transportOf(legs, 2_000_000.0))
    }

    @Test
    fun `a car that flies at 600 km per hour is a flight`() {
        // The 9,174 km "car" to Florida in 2015.
        val legs = listOf(leg("IN_PASSENGER_VEHICLE", 9174.0, 13.0), leg("IN_PASSENGER_VEHICLE", 9174.0, 13.0))
        assertEquals(listOf(FLIGHT), transportOf(legs, 9_000_000.0))
    }

    @Test
    fun `walking at airliner speed is not walking`() {
        // Short and fast: too short to call a flight, too fast to be a walk.
        val legs = listOf(leg("WALKING", 100.0, 0.5), leg("IN_TRAIN", 600.0, 6.0))
        assertEquals(listOf(TRAIN), transportOf(legs, 300_000.0))
    }

    @Test
    fun `taxis and the metro below ten percent stay out, and so does walking`() {
        val legs = listOf(
            leg("FLYING", 3500.0, 5.0), leg("IN_SUBWAY", 30.0, 1.0),
            leg("WALKING", 60.0, 15.0), leg("IN_TAXI", 40.0, 1.0), leg("FLYING", 3500.0, 5.0),
        )
        assertEquals(listOf(FLIGHT), transportOf(legs, 3_400_000.0))
    }

    @Test
    fun `at most two icons`() {
        val legs = listOf(leg("FLYING", 1000.0, 2.0), leg("IN_TRAIN", 900.0, 9.0), leg("IN_FERRY", 800.0, 20.0), leg("IN_BUS", 700.0, 12.0))
        assertEquals(listOf(FLIGHT, TRAIN), transportOf(legs, 1_000_000.0))
    }

    @Test
    fun `a hiking trip shows the walker`() {
        val legs = listOf(leg("WALKING", 70.0, 20.0), leg("HIKING", 20.0, 6.0))
        assertEquals(listOf(WALK), transportOf(legs, 60_000.0))
    }

    @Test
    fun `too little typed movement to reach the place shows nothing`() {
        // September 2013: 19 km typed on a trip 642 km from home.
        val legs = listOf(leg("IN_VEHICLE", 9.4, 1.0), leg("WALKING", 1.0, 0.3))
        assertEquals(emptyList<TransportMode>(), transportOf(legs, 642_000.0))
    }

    @Test
    fun `the reference trips get the icons measured in the evaluation`() {
        val file = Path.of("F:/Timeline/Timeline_merged_big.json")
        assumeTrue("Skipped: $file not present (developer-local only).", Files.exists(file))
        val parsed = Files.newInputStream(file).use { parseTimeline(it) }
        val trips = (detectTrips(parsed, ZoneId.of("Europe/Stockholm")) as TripDetection.Found).trips
        fun at(moment: String) = trips.single { it.covers(Instant.parse(moment)) }.transport

        assertEquals(listOf(CAR), at("2025-07-25T12:00:00Z")) // Norway by car
        assertEquals(listOf(FLIGHT), at("2025-06-10T12:00:00Z")) // Tashkent
        assertEquals(listOf(TRAIN), at("2018-07-28T12:00:00Z")) // by train
        assertEquals(listOf(TRAIN, BOAT), at("2020-08-05T12:00:00Z"))
        assertEquals(listOf(FLIGHT), at("2017-06-14T12:00:00Z")) // 4,597 km of "walking"
        assertEquals(emptyList<TransportMode>(), at("2013-09-13T12:00:00Z")) // 19 km typed

        val multiDay = trips.filter { !it.isDayTrip }
        val tally = multiDay.groupingBy { it.transport }.eachCount()
        println(tally.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.value}  ${it.key}" })
    }
}
