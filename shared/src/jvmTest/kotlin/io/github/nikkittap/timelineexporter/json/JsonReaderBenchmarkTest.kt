package io.github.nikkittap.timelineexporter.json

import com.fasterxml.jackson.core.JsonFactory
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tokenizer speed, JsonReader vs Jackson, over the big developer-local file:
 * every token, every string value decoded, every number read as a double.
 * Skipped unless run with `-Dbench=true` and the file exists.
 */
class JsonReaderBenchmarkTest {
    private val path = Path.of("F:/Timeline/Timeline_merged_big.json")

    @Test
    fun `tokenize big timeline`() {
        assumeTrue("Skipped: pass -Dbench=true", System.getProperty("bench") == "true")
        assumeTrue("Skipped: $path not present", Files.exists(path))
        val factory = JsonFactory()

        fun jackson(): Long {
            var sink = 0L
            BufferedInputStream(Files.newInputStream(path)).use { input ->
                factory.createParser(input).use { p ->
                    while (true) {
                        val t = p.nextToken() ?: break
                        sink += when (t) {
                            com.fasterxml.jackson.core.JsonToken.VALUE_STRING -> p.valueAsString.length.toLong()
                            com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT,
                            com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_FLOAT -> p.doubleValue.toLong()
                            com.fasterxml.jackson.core.JsonToken.FIELD_NAME -> p.currentName().length.toLong()
                            else -> 1L
                        }
                    }
                }
            }
            return sink
        }

        fun ours(): Long {
            var sink = 0L
            JsonReader(Files.newInputStream(path).asSource().buffered()).use { r ->
                while (true) {
                    val t = r.nextToken() ?: break
                    sink += when (t) {
                        JsonToken.VALUE_STRING -> r.valueAsString!!.length.toLong()
                        JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> r.doubleValue.toLong()
                        JsonToken.FIELD_NAME -> r.currentName()!!.length.toLong()
                        else -> 1L
                    }
                }
            }
            return sink
        }

        fun median(f: () -> Long): Pair<Long, Long> {
            val sink = f() // warm-up
            val times = List(5) {
                val t0 = System.nanoTime(); f(); (System.nanoTime() - t0) / 1_000_000
            }.sorted()
            return times[2] to sink
        }

        val (j, js) = median(::jackson)
        val (o, os) = median(::ours)
        println("BENCH tokenize jackson median=$j ms, JsonReader median=$o ms (ratio ${"%.2f".format(o.toDouble() / j)}), sinks $js/$os")
    }
}
