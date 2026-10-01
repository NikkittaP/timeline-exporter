package io.github.nikkittap.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Step 1's distance and step 2's movement breakdown count each stretch of
 * time once. A merged file carries the overlap years' activities twice.
 */
class DistinctActivitiesTest {

    private val t0 = Instant.parse("2025-06-07T10:00:00Z")
    private fun h(hours: Double) = t0.plusSeconds((hours * 3600).toLong())

    private fun act(fromH: Double, toH: Double, type: String, meters: Double) =
        Segment(SegmentKind.ACTIVITY, h(fromH), h(toH), activityType = type, distanceMeters = meters)

    private fun timeline(segments: List<Segment>) = ParsedTimeline(
        pathPoints = emptyList(),
        totalSegments = segments.size,
        pathSegments = 0,
        visitSegments = 0,
        activitySegments = segments.size,
        segments = segments.sortedBy { it.start },
    )

    @Test
    fun `a flight recorded twice counts once in the breakdown and the total`() {
        val parsed = timeline(
            listOf(
                act(0.0, 3.8, "FLYING", 1_982_000.0),
                act(0.25, 3.6, "FLYING", 1_990_000.0), // the other export's copy
                act(4.0, 4.5, "WALKING", 1_000.0),
            ),
        )
        val flying = parsed.movementBreakdown().single { it.group == MovementGroup.FLYING }
        assertEquals(1, flying.trips)
        assertEquals(1_982_000.0, flying.distanceMeters, 0.1)
        assertEquals(1_983_000.0, parsed.distanceByMovement().values.sum(), 0.1)
    }

    @Test
    fun `activities that only touch end to end are both kept`() {
        val parsed = timeline(listOf(act(0.0, 1.0, "WALKING", 500.0), act(1.0, 2.0, "IN_BUS", 5_000.0)))
        assertEquals(2, parsed.distinctActivities.size)
        assertEquals(2, parsed.movementBreakdown().sumOf { it.trips })
    }

    @Test
    fun `a single real export loses nothing`() {
        val file = Path.of("F:/Timeline/Timeline_2.json")
        assumeTrue("Skipped: $file not present (developer-local only).", Files.exists(file))
        val parsed = Files.newInputStream(file).use { parseTimeline(it) }
        assertEquals(parsed.activities.size, parsed.distinctActivities.size)
    }

    @Test
    fun `the merged file drops exactly its duplicates`() {
        val file = Path.of("F:/Timeline/Timeline_merged_big.json")
        assumeTrue("Skipped: $file not present (developer-local only).", Files.exists(file))
        val parsed = Files.newInputStream(file).use { parseTimeline(it) }
        val before = parsed.activities.sumOf { it.distanceMeters ?: 0.0 }
        val after = parsed.movementBreakdown().sumOf { it.distanceMeters }
        println("activities ${parsed.activities.size} -> ${parsed.distinctActivities.size}, km ${before.toInt() / 1000} -> ${after.toInt() / 1000}")
        // 455, not the 517 a first count found: that count also treated a real
        // activity overlapping only the *dropped* copy as a duplicate.
        assertEquals(455, parsed.activities.size - parsed.distinctActivities.size)
    }
}
