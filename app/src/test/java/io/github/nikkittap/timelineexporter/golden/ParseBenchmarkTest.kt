package io.github.nikkittap.timelineexporter.golden

import io.github.nikkittap.timelineexporter.parser.parseTimeline
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parse-speed baseline for the shared move. Skipped unless run with
 * `-Dbench=true` and the big developer-local file exists.
 */
class ParseBenchmarkTest {
    @Test
    fun `parse big timeline`() {
        val path = Path.of("F:/Timeline/Timeline_merged_big.json")
        assumeTrue("Skipped: pass -Dbench=true", System.getProperty("bench") == "true")
        assumeTrue("Skipped: $path not present", Files.exists(path))

        fun run(): Pair<Long, Int> {
            val t0 = System.nanoTime()
            val parsed = BufferedInputStream(Files.newInputStream(path)).use { parseTimeline(it) }
            return (System.nanoTime() - t0) / 1_000_000 to parsed.pathPoints.size
        }
        run() // warm-up
        val runs = List(5) { run() }
        val times = runs.map { it.first }.sorted()
        println("BENCH parse median=${times[2]} ms runs=$times points=${runs.last().second}")
    }
}
