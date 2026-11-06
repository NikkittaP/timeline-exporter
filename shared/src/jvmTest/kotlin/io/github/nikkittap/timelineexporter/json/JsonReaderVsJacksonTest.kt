package io.github.nikkittap.timelineexporter.json

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Differential test: walks Jackson's JsonParser and JsonReader side by side
 * over the same input and requires the same token stream — token type,
 * currentName() and valueAsString (numbers by doubleValue, ints also by
 * longValue). Malformed input must be rejected by both.
 *
 * Inputs: every inline JSON fixture of the parser tests (pulled out of their
 * sources, so new fixtures are covered automatically), some hand-written
 * edge cases, and the developer-local F:/Timeline/ *.json exports (skipped
 * when absent). Each input is walked plainly, with skipChildren() on every
 * few containers, and (small inputs) through a source that hands out one
 * byte per read, to exercise every buffer-boundary path.
 */
class JsonReaderVsJacksonTest {

    private val jackson = JsonFactory()

    private val edgeCases = listOf(
        """{}""", """[]""", """[[],{},[{}],{"a":[]}]""", """"top-level string"""", """42""", """-0.0""", """1 2 3""",
        """{"":""}""", """{"a":{"b":{"c":{"d":[1,[2,[3,[4]]]]}}},"e":null}""",
        """["\"\\\/\b\f\n\r\t", "\u00e9\u20ac\ud83d\ude00", "é€😀°В", "\ud800 lone", "a\u0000b"]""",
        """[0, -1, 1.5, -1.5e-7, 1E+2, 2e308, 1e-400, 9223372036854775807, -9223372036854775808, 123456789012345678,
           0.1, 0.30000000000000004, 41.284025, 69.242256, 412840250, -1.0e22, 1.7976931348623157e308, 4.9e-324,
           123456789012345.6, 1234567890123456.7, 0.000001234, 1e22, 1e23]""",
        """{"latLng":"41.284025°, 69.242256°","startTime":"2024-01-02T03:04:05.000+05:00","probability":0.98,"e7":412840250}""",
        """[true,false,null,{"t":true,"f":false,"n":null}]""",
        " \t\r\n { \"a\" : [ 1 , 2 ] } \n",
    )

    private val malformed = listOf(
        """{"a":[1,2""", """{"a" 1}""", """{"a":1,}""", """[1,]""", """[01]""", """[1.]""", """[-]""", """[1e]""",
        """{"a":tru}""", """{"a":nul}""", """[truex]""", """[1x]""", """{a:1}""", """["unterminated""", """["bad \x escape"]""",
        """["bad \u12G4"]""", "[\"ctrl \u0001 char\"]", """}""", """[}""", """{"a":1]""", """[1 2]""", """{"a":1 "b":2}""",
    )

    @Test
    fun `edge cases match Jackson`() {
        for (json in edgeCases) compareAllModes(json)
    }

    @Test
    fun `malformed input is rejected by both`() {
        for (json in malformed) compareAllModes(json)
    }

    @Test
    fun `parser test fixtures match Jackson`() {
        // The parser tests live in commonTest, a few (real files, XML) in jvmTest.
        val dirs = listOf("commonTest", "jvmTest").map { Path.of("src/$it/kotlin/io/github/nikkittap/timelineexporter") }
        val fixtures = dirs.flatMap { dir -> Files.walk(dir).use { s -> s.filter { it.extension == "kt" && it.parent.fileName.toString() != "json" }.toList() } }
            .flatMap { file -> RAW_STRING.findAll(file.readText()).map { it.groupValues[1].trimIndent() } }
            .filter { it.startsWith("{") || it.startsWith("[") }
        assertTrue("expected the parser tests' inline fixtures, found ${fixtures.size}", fixtures.size >= 40)
        for (json in fixtures) compareAllModes(json)
        println("JsonReaderVsJacksonTest: ${fixtures.size} inline fixtures compared")
    }

    @Test
    fun `real Timeline exports match Jackson`() {
        val dir = Path.of("F:/Timeline")
        val files = if (Files.isDirectory(dir)) dir.listDirectoryEntries("*.json").sorted() else emptyList()
        assumeTrue("Skipped: no F:/Timeline/*.json present (developer-local only)", files.isNotEmpty())
        for (file in files) {
            for (skipEvery in listOf(0, 5)) {
                val t0 = System.nanoTime()
                val tokens = BufferedInputStream(Files.newInputStream(file), 1 shl 16).use { jin ->
                    Files.newInputStream(file).asSource().buffered().use { oin ->
                        compare(file.fileName.toString(), jackson.createParser(jin), JsonReader(oin), skipEvery)
                    }
                }
                println("JsonReaderVsJacksonTest: ${file.fileName} skipEvery=$skipEvery tokens=$tokens " +
                    "in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
        }
    }

    private fun compareAllModes(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        for (skipEvery in listOf(0, 1, 3)) {
            compare(json, jackson.createParser(bytes), JsonReader(Buffer().apply { write(bytes) }), skipEvery)
            compare(json, jackson.createParser(bytes), JsonReader(oneBytePerRead(bytes)), skipEvery)
        }
    }

    /** A source whose every read returns a single byte: each token straddles buffer refills. */
    private fun oneBytePerRead(bytes: ByteArray): Source = object : RawSource {
        var i = 0
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (i >= bytes.size) return -1
            sink.writeByte(bytes[i++])
            return 1
        }
        override fun close() {}
    }.buffered()

    /**
     * Walks both in lockstep; returns the token count. With [skipEvery] > 0,
     * every [skipEvery]-th container start is skipped by both.
     */
    private fun compare(label: String, j: JsonParser, o: JsonReader, skipEvery: Int): Long {
        val what = "${label.take(80)} (skipEvery=$skipEvery)"
        var n = 0L
        var containers = 0
        j.use {
            o.use {
                while (true) {
                    val jt = try { j.nextToken() } catch (e: com.fasterxml.jackson.core.JacksonException) {
                        assertRejects(what, o)
                        return n
                    }
                    val ot = try { o.nextToken() } catch (e: JsonParseException) {
                        // Ours stopped first; Jackson must reject the input too.
                        assertJacksonRejects(what, j, e)
                        return n
                    }
                    n++
                    val at = "$what, token #$n"
                    if (jt == null || ot == null) {
                        assertEquals("$at: end of input", jt?.name, ot?.name)
                        return n
                    }
                    if (jt.name == "VALUE_NUMBER_INT" && ot == JsonToken.VALUE_NUMBER_FLOAT) {
                        // Allowed only for integers outside the Long range (see JsonReader's KDoc).
                        assertEquals("$at: INT/FLOAT mismatch", JsonParser.NumberType.BIG_INTEGER, j.numberType)
                    } else {
                        assertEquals("$at: token", jt.name, ot.name)
                    }
                    assertEquals("$at: currentName", j.currentName(), o.currentName())
                    when (ot) {
                        JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> {
                            assertEquals("$at: doubleValue", j.doubleValue, o.doubleValue, 0.0)
                            assertEquals("$at: doubleValue bits",
                                j.doubleValue.toRawBits(), o.doubleValue.toRawBits())
                            if (ot == JsonToken.VALUE_NUMBER_INT) assertEquals("$at: longValue", j.longValue, o.longValue)
                        }
                        else -> assertEquals("$at: valueAsString", j.valueAsString, o.valueAsString)
                    }
                    if (skipEvery > 0 && (ot == JsonToken.START_OBJECT || ot == JsonToken.START_ARRAY) &&
                        ++containers % skipEvery == 0
                    ) {
                        val jSkipped = try { j.skipChildren(); true } catch (e: com.fasterxml.jackson.core.JacksonException) { false }
                        val oSkipped = try { o.skipChildren(); true } catch (e: JsonParseException) { false }
                        if (!jSkipped) {
                            // Jackson validates skipped content, JsonReader only matches brackets (see its
                            // KDoc), so malformed content inside a skipped container may pass here. The
                            // skipEvery=0 walk of the same input still requires JsonReader to reject it.
                            return n
                        }
                        assertTrue("$at: Jackson skipped but JsonReader failed", oSkipped)
                        assertEquals("$at: token after skipChildren", j.currentToken().name, o.currentToken()?.name)
                        assertEquals("$at: currentName after skipChildren", j.currentName(), o.currentName())
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return n
    }

    private fun assertRejects(what: String, o: JsonReader) {
        try {
            while (o.nextToken() != null) { /* drain */ }
        } catch (e: JsonParseException) {
            return
        }
        fail("$what: Jackson rejects this input, JsonReader accepted it")
    }

    private fun assertJacksonRejects(what: String, j: JsonParser, ours: JsonParseException) {
        try {
            while (j.nextToken() != null) { /* drain */ }
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            return
        }
        fail("$what: JsonReader rejects this input (${ours.message}), Jackson accepted it")
    }

    private companion object {
        /** A Kotlin raw string literal. */
        val RAW_STRING = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
    }
}
