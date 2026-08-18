package io.github.nikkittap.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureTimeMillis

/**
 * Smoke test against the developer's real Timeline.json export.
 * Skipped (not failed) if the file isn't present at the hard-coded path —
 * which is fine: this is a developer-only sanity check, not a CI test.
 *
 * Run via:  ./gradlew :app:testDebugUnitTest --tests "*RealFile*" -i
 * The `-i` flag prints the println output below so we can see the diagnostics.
 */
class TimelineParserRealFileTest {

    private val realFile: Path = Path.of("F:/Timeline/Timeline.json")

    @Test
    fun `parses real Timeline json if present`() {
        assumeTrue(
            "Skipped: ${realFile} not present (this test is developer-local only).",
            Files.exists(realFile),
        )

        val json = Files.readString(realFile)
        println("Loaded ${json.length / 1024} KB of JSON")

        lateinit var result: ParsedTimeline
        val ms = measureTimeMillis { result = parseTimeline(json) }

        println("Parsed in ${ms} ms")
        println("Segments: total=${result.totalSegments} " +
                "(path=${result.pathSegments}, " +
                "visit=${result.visitSegments}, " +
                "activity=${result.activitySegments})")
        println("Path points: ${result.pathPoints.size}")
        if (result.pathPoints.isNotEmpty()) {
            val first = result.pathPoints.first()
            val last  = result.pathPoints.last()
            println("First point: ${first.timeUtc}  lat=${first.latitude} lon=${first.longitude}")
            println("Last point:  ${last.timeUtc}  lat=${last.latitude} lon=${last.longitude}")
        }

        // Soft assertion — just makes sure SOMETHING was parsed. If your file
        // somehow contains zero path segments, this'll flag it.
        assert(result.totalSegments > 0) { "Expected at least one segment in real file" }
    }

    /**
     * Pins the segment extraction against a known export.
     *
     * Every figure below was measured on Timeline_2.json before the Kotlin was
     * written, so this checks the implementation against the data rather than
     * against itself. The distance total is the interesting one: 14 501.4 km is
     * Google's own sum of `distanceMeters`, and adding up haversine distances
     * between track points instead gives 14 986 km — the track is a downsampled
     * version of the trips, not a more accurate one.
     */
    @Test
    fun `segment extraction matches the measured baseline for Timeline_2 json`() {
        val file = Path.of("F:/Timeline/Timeline_2.json")
        assumeTrue(
            "Skipped: $file not present (this test is developer-local only).",
            Files.exists(file),
        )

        val result = Files.newInputStream(file).use { parseTimeline(it) }

        assertEquals(5432, result.totalSegments)
        assertEquals(3180, result.pathSegments)
        assertEquals(1175, result.activitySegments)
        assertEquals(1071, result.visitSegments)
        assertEquals(33348, result.pathPoints.size)

        assertEquals(1175, result.activities.size)
        assertEquals(1071, result.visits.size)

        val km = result.distanceByMovement().mapValues { it.value / 1000.0 }
        assertEquals(7291.8, km.getValue(MovementGroup.FLYING), 0.1)
        assertEquals(3223.7, km.getValue(MovementGroup.DRIVING), 0.1)
        assertEquals(2555.3, km.getValue(MovementGroup.TRANSIT), 0.1)
        assertEquals(1348.9, km.getValue(MovementGroup.WALKING), 0.1)
        assertEquals(76.3, km.getValue(MovementGroup.CYCLING), 0.1)
        assertEquals(14501.4, km.values.sum(), 0.2)

        // 1071 visits collapse to 313 places once grouped by placeId.
        val places = result.placesByVisitCount()
        assertEquals(313, places.size)
        assertEquals(335, places.first().second)

        assertTrue(
            "segments must come out sorted by start time",
            result.segments.zipWithNext().all { (a, b) -> a.start <= b.start },
        )
    }
}
