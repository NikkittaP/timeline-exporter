package io.github.nikkittap.timelineexporter.json

import io.github.nikkittap.timelineexporter.parser.parseCoordinatePair
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins number parsing and byte-level reading to what the JVM does, in common
 * code so that it runs on Kotlin/Native (iosSimulatorArm64Test) too. There
 * `String.toDouble` is Kotlin/Native's own implementation, not the JDK's, and
 * it is what both JsonReader's slow path (more than 15 significant digits, or
 * a power of ten past ±22) and the parser's coordinate strings ("geo:lat,lon",
 * "lat°, lon°") go through. Every expected value is the raw IEEE bit pattern
 * `java.lang.Double.parseDouble` returned for the same text on JDK 21, so a
 * one-ulp difference on iOS fails here rather than in an export.
 */
class NativeNumberParsingTest {

    /** Inputs that take JsonReader's slow path (or sit at the edges of the double range). */
    private val edgeLiterals = listOf(
        "123456789012345.67" to 0x42DC12218377DE6BUL,
        "1e23" to 0x44B52D02C7E14AF6UL,
        "-1e23" to 0xC4B52D02C7E14AF6UL,
        "8.41e21" to 0x447C7E83209E90B2UL,
        "4.9e-324" to 0x0000000000000001UL,
        "2.2250738585072011e-308" to 0x000FFFFFFFFFFFFFUL,
        "2.2250738585072012e-308" to 0x0010000000000000UL,
        "1.7976931348623157e308" to 0x7FEFFFFFFFFFFFFFUL,
        "2e308" to 0x7FF0000000000000UL, // Infinity; the parser filters it out
        "1e-400" to 0x0000000000000000UL,
        "0.30000000000000004" to 0x3FD3333333333334UL,
        "48.858370000000001" to 0x40486DDF1172EF0BUL,
        "9007199254740993.0" to 0x4340000000000000UL,
        "0.1" to 0x3FB999999999999AUL,
    )

    /** 7-decimal coordinates, the shape of almost all real Timeline data. */
    private val coordinates = listOf(
        "48.8583701" to 0x40486DDF1249AEA0UL,
        "2.2944813" to 0x40025B19030644BEUL,
        "41.2840250" to 0x4044A45AEE631F8AUL,
        "69.2422560" to 0x40514F811F4F50A0UL,
        "-33.8567844" to 0xC040EDAB1C78DC8BUL,
        "151.2152967" to 0x4062E6E3B5E7ADFAUL,
        "40.7484405" to 0x40445FCCE5F7403EUL,
        "-73.9856644" to 0xC0527F152022B537UL,
        "35.6585805" to 0x4041D44C5DA6A444UL,
        "139.7454329" to 0x406177DA9618DB99UL,
        "-0.0000001" to 0xBE7AD7F29ABCAF48UL,
        "0.0000001" to 0x3E7AD7F29ABCAF48UL,
        "89.9999999" to 0x40567FFFFF94A036UL,
        "-179.9999999" to 0xC0667FFFFFCA501BUL,
        "51.5007292" to 0x4049C017E4F9137DUL,
        "-0.1246254" to 0xBFBFE77341403C30UL,
    )

    private fun bits(d: Double): ULong = d.toRawBits().toULong()

    private fun hex(v: ULong) = "0x" + v.toString(16).uppercase().padStart(16, '0')

    private fun assertBits(expected: ULong, actual: Double, what: String) =
        assertEquals(hex(expected), hex(bits(actual)), "$what (got $actual)")

    /** Every value of the top-level array in [json], via doubleValue. */
    private fun jsonDoubles(json: String): List<Double> = JsonReader.of(json).use { r ->
        assertEquals(JsonToken.START_ARRAY, r.nextToken())
        buildList {
            while (true) {
                val t = r.nextToken()
                if (t == JsonToken.END_ARRAY) break
                add(r.doubleValue)
            }
        }
    }

    @Test
    fun `JsonReader doubleValue matches the JVM bit for bit`() {
        val all = edgeLiterals + coordinates
        val values = jsonDoubles(all.joinToString(",", "[", "]") { it.first })
        assertEquals(all.size, values.size)
        all.zip(values).forEach { (case, v) -> assertBits(case.second, v, "JsonReader \"${case.first}\"") }
    }

    @Test
    fun `String toDouble matches the JVM bit for bit`() {
        // The parser's numeric-string path (doubleOrNull on VALUE_STRING) and parseCoordinatePair use this.
        (edgeLiterals + coordinates).forEach { (text, expected) ->
            assertBits(expected, assertNotNull(text.toDoubleOrNull(), text), "toDoubleOrNull \"$text\"")
        }
    }

    @Test
    fun `parseCoordinatePair matches the JVM bit for bit in every form`() {
        val pairs = coordinates.chunked(2) + listOf(edgeLiterals.take(2), listOf(edgeLiterals[11], edgeLiterals[10]))
        for ((lat, lon) in pairs) {
            for (text in listOf(
                "geo:${lat.first},${lon.first}",
                "${lat.first}°, ${lon.first}°",
                "${lat.first}В°, ${lon.first}В°",
                " ${lat.first} , ${lon.first} ",
            )) {
                val parsed = assertNotNull(parseCoordinatePair(text), text)
                assertBits(lat.second, parsed.first, "lat of \"$text\"")
                assertBits(lon.second, parsed.second, "lon of \"$text\"")
            }
        }
    }

    // ---- reading one byte at a time ----

    /** A source whose every read returns a single byte, so every token straddles a buffer refill. */
    private class OneBytePerRead(private val bytes: ByteArray) : RawSource {
        var reads = 0
        private var i = 0
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (i >= bytes.size) return -1
            reads++
            sink.writeByte(bytes[i++])
            return 1
        }
        override fun close() {}
    }

    private fun tokens(r: JsonReader): List<Pair<JsonToken, String?>> = r.use {
        buildList {
            while (true) {
                val t = r.nextToken() ?: break
                val text = when (t) {
                    JsonToken.FIELD_NAME -> r.currentName()
                    JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> r.valueAsString + "=" + hex(bits(r.doubleValue))
                    else -> r.valueAsString
                }
                add(t to text)
            }
        }
    }

    private fun bufferOf(bytes: ByteArray): Source = Buffer().apply { write(bytes) }

    // A BOM, escapes (including a surrogate pair escape), 2-, 3- and 4-byte UTF-8,
    // slow- and fast-path numbers, Long edges, literals and a container to skip.
    private val document = "\uFEFF" + """
        {"semanticSegments":[{"startTime":"2025-06-09T21:58:00.000+02:00","timelinePath":[
          {"point":"48.8583701°, 2.2944813°","time":"2025-06-09T22:00:00.000+02:00"}]},
         {"visit":{"probability":0.30000000000000004,"topCandidate":{"placeId":"ChIJ\u00e9\"q\\/","semanticType":"Ünïcödé €"}}},
         {"emoji":"😀 a 😀","esc":"tab\there\nline \ud83d\ude00 \u0041","big":123456789012345.67,"exp":1e23,
          "ints":[0,-7,12345678901234,9223372036854775807,-9223372036854775808],"lits":[true,false,null],
          "skipMe":{"a":["x\"]}","y\\"],"b":{"c":[1,{"d":"é"}]}},"after":-0.5e3}
        ],"rawSignals":[]}
    """.trimIndent()

    @Test
    fun `reading one byte per refill gives the same tokens`() {
        val bytes = document.encodeToByteArray()
        val expected = tokens(JsonReader(bufferOf(bytes)))
        val raw = OneBytePerRead(bytes)
        val actual = tokens(JsonReader(raw.buffered()))
        assertEquals(expected, actual)
        assertTrue(raw.reads >= bytes.size, "every byte came from its own read (${raw.reads} reads, ${bytes.size} bytes)")
        // Spot checks that the tokens are right, not just equal.
        assertTrue(JsonToken.VALUE_STRING to "Ünïcödé €" in actual)
        assertTrue(JsonToken.VALUE_STRING to "ChIJé\"q\\/" in actual)
        assertTrue(JsonToken.VALUE_STRING to "😀 a 😀" in actual)
        assertTrue(JsonToken.VALUE_STRING to "tab\there\nline 😀 A" in actual)
        assertTrue(JsonToken.VALUE_NUMBER_FLOAT to "1e23=0x44B52D02C7E14AF6" in actual)
        assertTrue(JsonToken.VALUE_NUMBER_INT to "-9223372036854775808=0xC3E0000000000000" in actual)
    }

    @Test
    fun `skipChildren works one byte per refill`() {
        val bytes = document.encodeToByteArray()
        JsonReader(OneBytePerRead(bytes).buffered()).use { r ->
            while (!(r.nextToken() == JsonToken.FIELD_NAME && r.currentName() == "skipMe")) Unit
            assertEquals(JsonToken.START_OBJECT, r.nextToken())
            r.skipChildren()
            assertEquals(JsonToken.END_OBJECT, r.currentToken())
            assertEquals(JsonToken.FIELD_NAME, r.nextToken())
            assertEquals("after", r.currentName())
            assertEquals(JsonToken.VALUE_NUMBER_FLOAT, r.nextToken())
            assertEquals(-500.0, r.doubleValue)
        }
    }

    // ---- invalid UTF-8 ----

    private fun stringOf(vararg body: Int): ByteArray =
        byteArrayOf(0x5B, 0x22) + ByteArray(body.size) { body[it].toByte() } + byteArrayOf(0x22, 0x5D) // ["…"]

    private val invalidUtf8 = mapOf(
        "lone continuation byte" to stringOf(0x61, 0x80, 0x62),
        "continuation byte first" to stringOf(0xBF),
        "truncated 2-byte sequence" to stringOf(0xC3),
        "truncated 3-byte sequence" to stringOf(0xE2, 0x82),
        "truncated 4-byte sequence" to stringOf(0xF0, 0x9F, 0x98),
        "truncated at end of input" to byteArrayOf(0x5B, 0x22, 0xE2.toByte(), 0x82.toByte()),
        "overlong 2-byte (C0 AF)" to stringOf(0xC0, 0xAF),
        "overlong 2-byte (C1 BF)" to stringOf(0xC1, 0xBF),
        "overlong 3-byte" to stringOf(0xE0, 0x80, 0xAF),
        "overlong 4-byte" to stringOf(0xF0, 0x80, 0x80, 0xAF),
        "above U+10FFFF" to stringOf(0xF4, 0x90, 0x80, 0x80),
        "invalid start byte F5" to stringOf(0xF5, 0x80, 0x80, 0x80),
        "invalid start byte FF" to stringOf(0xFF),
    )

    @Test
    fun `invalid UTF-8 is rejected`() {
        for ((what, bytes) in invalidUtf8) {
            assertFailsWith<JsonParseException>(what) { tokens(JsonReader(bufferOf(bytes))) }
            assertFailsWith<JsonParseException>("$what, one byte per read") {
                tokens(JsonReader(OneBytePerRead(bytes).buffered()))
            }
        }
    }
}
