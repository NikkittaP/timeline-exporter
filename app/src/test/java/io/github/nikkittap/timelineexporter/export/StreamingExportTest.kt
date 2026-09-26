package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Every format has to *stream* its output into the caller's sink rather than
 * build the whole document first and hand it over in one piece.
 *
 * This is a memory test wearing a functional disguise. A full phone export is
 * 284,000 points and produces a ~30 MB GPX; assembling that as a `String`
 * meant an 85 MB StringBuilder, a 60 MB copy of it (Java chars are two bytes)
 * and a 30 MB byte array — 175 MB of garbage for a 30 MB file, on top of the
 * ~96 MB the parsed timeline already occupies. Measured on a Pixel 7 the heap
 * sat at "0% free, 136MB/136MB" against a 192 MB limit, so a file half again
 * as large would have thrown OutOfMemoryError out of a `catch (e: Exception)`
 * that does not catch Errors — a crash, not an error message.
 *
 * Streaming cannot be asserted by looking at the finished text, so the sink
 * here watches *how* it arrives: nothing may be appended in one piece large
 * enough to be a materialized document.
 */
class StreamingExportTest {

    /** Collects the output and remembers the largest single append. */
    private class ChunkRecorder : Appendable {
        val text = StringBuilder()
        var largestChunk = 0
            private set

        override fun append(csq: CharSequence?): Appendable {
            val s = csq ?: "null"
            largestChunk = maxOf(largestChunk, s.length)
            text.append(s)
            return this
        }

        override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
            largestChunk = maxOf(largestChunk, end - start)
            text.append(csq, start, end)
            return this
        }

        override fun append(c: Char): Appendable {
            largestChunk = maxOf(largestChunk, 1)
            text.append(c)
            return this
        }
    }

    /**
     * Comfortably above any single field, header or place row a format writes,
     * and far below a document built from [TRACK_POINTS] points.
     */
    private val maxChunkChars = 4_000

    private val trackPoints = 20_000

    private fun track(): List<PathPoint> {
        val start = Instant.parse("2025-06-09T19:58:00Z")
        return List(trackPoints) { i ->
            PathPoint(
                timeUtc = start.plusSeconds(i * 60L),
                latitude = 55.5946203 + i * 1e-5,
                longitude = 13.0065502 + i * 1e-5,
            )
        }
    }

    @Test
    fun `every format streams a track instead of handing over one string`() {
        for (exporter in AllExporters) {
            val sink = ChunkRecorder()
            exporter.export(ExportContent.Track(track()), "Big trip", sink)

            assertTrue(
                "${exporter.displayName} wrote only ${sink.text.length} chars",
                sink.text.length > 100_000,
            )
            assertTrue(
                "${exporter.displayName} appended ${sink.largestChunk} chars at once",
                sink.largestChunk <= maxChunkChars,
            )
        }
    }

    @Test
    fun `every format streams a track coloured by day`() {
        for (exporter in AllExporters) {
            val sink = ChunkRecorder()
            exporter.export(ExportContent.Track(track(), colorByDay = true), "Big trip", sink)

            assertTrue(
                "${exporter.displayName} wrote only ${sink.text.length} chars",
                sink.text.length > 100_000,
            )
            assertTrue(
                "${exporter.displayName} appended ${sink.largestChunk} chars at once",
                sink.largestChunk <= maxChunkChars,
            )
        }
    }

    @Test
    fun `every format streams places instead of handing over one string`() {
        val places = List(2_000) { i -> place(name = "Place $i") }
        for (exporter in AllExporters) {
            val sink = ChunkRecorder()
            exporter.export(ExportContent.Places(places), "My places", sink)

            assertTrue(
                "${exporter.displayName} wrote only ${sink.text.length} chars",
                sink.text.length > 100_000,
            )
            assertTrue(
                "${exporter.displayName} appended ${sink.largestChunk} chars at once",
                sink.largestChunk <= maxChunkChars,
            )
        }
    }
}
