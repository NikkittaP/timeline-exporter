package io.github.nikitapetroff.timelineexporter.parser

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
}
