package io.github.nikkittap.timelineexporter.parser

import io.github.nikkittap.timelineexporter.filter.TimelineFilter
import io.github.nikkittap.timelineexporter.filter.applyFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
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
     * Pins the load-progress fix (weightedProgress in TimelineViewModel)
     * against the file that originally reported the bug: the bar crawled
     * through the first 5-6 MB of a 62 MB export, then jumped to done.
     * findKeyOffsets should find semanticSegments right at the start and
     * rawSignals right around where the crawl-then-jump was observed.
     */
    @Test
    fun `findKeyOffsets locates the split point that caused the progress bar bug`() {
        assumeTrue(
            "Skipped: $realFile not present (this test is developer-local only).",
            Files.exists(realFile),
        )
        val offsets = Files.newInputStream(realFile).use { findKeyOffsets(it) }

        println("firstContentKeyOffset=${offsets.firstContentKeyOffset}, rawSignalsOffset=${offsets.rawSignalsOffset}")
        assertTrue(offsets.firstContentKeyOffset != null)
        assertTrue(offsets.rawSignalsOffset != null)
        assertTrue(
            "expected the content key before rawSignals",
            offsets.firstContentKeyOffset!! < offsets.rawSignalsOffset!!,
        )
        val fileSize = Files.size(realFile)
        val splitFraction = offsets.rawSignalsOffset!!.toDouble() / fileSize
        // Reported as "slow for the first 5-6 MB of 62 MB" - roughly 8-10%.
        assertTrue("split at $splitFraction of the file", splitFraction in 0.03..0.20)
    }

    /**
     * The time-of-day window against a year of real data.
     *
     * Deliberately a partition rather than a magic number: a window and its
     * complement have to add up to every point, exactly once each. That is the
     * property half-open bounds and the midnight wrap exist to guarantee, and
     * unlike a hard-coded count it cannot be satisfied by a filter that drops
     * points on the floor.
     *
     * The counts are printed because they are the interesting part. Measured
     * before the feature was written, 14.6% of this file's points fall between
     * midnight and 06:00 — a phone at home reporting the same spot all night —
     * so the two numbers should be roughly one in seven against six in seven.
     */
    @Test
    fun `a time-of-day window and its complement cover the real file exactly once`() {
        assumeTrue(
            "Skipped: $realFile not present (this test is developer-local only).",
            Files.exists(realFile),
        )
        val result = parseTimeline(Files.readString(realFile))
        // The zone only stands in for points the file states no offset for;
        // in this export every point has one, so it should not matter — which
        // is itself worth not leaving to the machine the test runs on.
        val zone = ZoneId.of("Europe/Stockholm")

        val night = TimeOfDayWindow(0, 6 * 60)
        val rest = TimeOfDayWindow(6 * 60, 0)
        val inNight = applyFilter(result.pathPoints, TimelineFilter(timeOfDay = night), result.segments, zone)
        val inRest = applyFilter(result.pathPoints, TimelineFilter(timeOfDay = rest), result.segments, zone)

        println("00:00-06:00: ${inNight.size}, 06:00-24:00: ${inRest.size}, total ${result.pathPoints.size}")

        assertEquals(result.pathPoints.size, inNight.size + inRest.size)
        assertTrue(inNight.isNotEmpty())
        assertTrue(inRest.isNotEmpty())
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

        // 1071 visits collapse to 313 places once grouped by placeId — 262 of
        // them once Google's level-1 containers are left out, which is what
        // the Places list shows by default. 95 of the visits are containers.
        assertEquals(262, result.placeEntries().size)
        assertEquals(313, result.placeEntries(includeContainers = true).size)
        assertEquals(335, result.placeEntries().first().visits)
        assertEquals(result.placeEntries().size, result.placeCount())

        assertTrue(
            "segments must come out sorted by start time",
            result.segments.zipWithNext().all { (a, b) -> a.start <= b.start },
        )
    }
}
