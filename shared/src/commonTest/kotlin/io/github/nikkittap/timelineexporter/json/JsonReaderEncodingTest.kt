package io.github.nikkittap.timelineexporter.json

import io.github.nikkittap.timelineexporter.parser.parseTimeline
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * UTF-16 and UTF-32 input, which Jackson detected on its own and JsonReader
 * transcodes to UTF-8: a Timeline.json re-saved as "Unicode" by Windows
 * Notepad must still load, with exactly the result the UTF-8 file gives.
 * The bytes are generated here rather than kept as binary fixtures.
 */
class JsonReaderEncodingTest {

    private enum class Enc(val unit: Int, val bigEndian: Boolean) {
        UTF_16BE(2, true), UTF_16LE(2, false), UTF_32BE(4, true), UTF_32LE(4, false)
    }

    /** [text] encoded as [enc], with a byte order mark in front if [bom]. */
    private fun encode(text: String, enc: Enc, bom: Boolean): ByteArray {
        val out = ArrayList<Byte>()
        fun unit(v: Int) {
            val bytes = (enc.unit - 1 downTo 0).map { (v ushr (8 * it)).toByte() }
            out.addAll(if (enc.bigEndian) bytes else bytes.reversed())
        }
        if (bom) unit(0xFEFF)
        if (enc.unit == 2) {
            text.forEach { unit(it.code) }
        } else {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.isHighSurrogate()) {
                    unit(0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00))
                    i += 2
                } else {
                    unit(c.code)
                    i++
                }
            }
        }
        return out.toByteArray()
    }

    private fun bufferOf(bytes: ByteArray): Source = Buffer().apply { write(bytes) }

    /** One byte per read, so code units and surrogate pairs straddle every refill. */
    private fun oneBytePerRead(bytes: ByteArray): Source = object : RawSource {
        var i = 0
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (i >= bytes.size) return -1
            sink.writeByte(bytes[i++])
            return 1
        }
        override fun close() {}
    }.buffered()

    // Non-ASCII in names and values, including a character outside the BMP
    // (a surrogate pair in UTF-16), as real place names and notes can be.
    private val timeline = """
        { "semanticSegments": [
            { "startTime": "2025-06-09T21:57:46.000+02:00",
              "endTime": "2025-06-10T07:03:44.000+02:00",
              "visit": {
                "hierarchyLevel": 0,
                "probability": 0.7404090762138367,
                "topCandidate": {
                  "placeId": "ChIJhaL9pVWNrjgR-ngIJC_6jrQ",
                  "semanticType": "Café Ünïcödé € 😀",
                  "probability": 0.9460377097129822,
                  "placeLocation": { "latLng": "41.2839476°, 69.2421459°" }
                }
              } },
            { "startTime": "2025-06-10T07:03:44.000+02:00",
              "endTime": "2025-06-10T08:41:07.000+02:00",
              "startTimeTimezoneUtcOffsetMinutes": 120,
              "activity": {
                "start": { "latLng": "41.2836881°, 69.2418352°" },
                "end": { "latLng": "41.622922°, 69.9346999°" },
                "distanceMeters": 73958.4609375,
                "topCandidate": { "type": "IN_PASSENGER_VEHICLE", "probability": 0.8569924831390381 }
              } },
            { "startTime": "2025-06-10T07:00:00.000+02:00",
              "endTime": "2025-06-10T09:00:00.000+02:00",
              "timelinePath": [
                { "point": "41.2836881°, 69.2418352°", "time": "2025-06-10T07:05:00.000+02:00" },
                { "point": "41.5000000°, 69.5000000°", "time": "2025-06-10T07:45:00.000+02:00" },
                { "point": "41.622922°, 69.9346999°", "time": "2025-06-10T08:40:00.000+02:00" }
              ] }
        ] }
    """.trimIndent()

    @Test
    fun `UTF-16 and UTF-32 timelines parse exactly like UTF-8`() {
        val expected = parseTimeline(timeline)
        assertEquals(3, expected.pathPoints.size)
        assertTrue(expected.visits.single().place!!.semanticType!!.endsWith("😀"))
        for (enc in Enc.entries) for (bom in listOf(true, false)) {
            val bytes = encode(timeline, enc, bom)
            assertEquals(expected, parseTimeline(bufferOf(bytes)), "$enc, bom=$bom")
            assertEquals(expected, parseTimeline(oneBytePerRead(bytes)), "$enc, bom=$bom, one byte per read")
        }
    }

    @Test
    fun `short documents are detected too`() {
        for (enc in Enc.entries) for (bom in listOf(true, false)) {
            for (json in listOf("1", "[]", "\"é\"")) {
                val tokens = JsonReader(bufferOf(encode(json, enc, bom))).use { r ->
                    buildList { while (r.nextToken() != null) add(r.valueAsString) }
                }
                val expected = JsonReader.of(json).use { r -> buildList { while (r.nextToken() != null) add(r.valueAsString) } }
                assertEquals(expected, tokens, "$enc, bom=$bom, $json")
            }
        }
    }

    @Test
    fun `UTF-8 with and without a BOM is unaffected`() {
        val expected = parseTimeline(timeline)
        val utf8 = timeline.encodeToByteArray()
        assertEquals(expected, parseTimeline(bufferOf(utf8)))
        assertEquals(expected, parseTimeline(bufferOf(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8)))
    }

    private fun utf16le(vararg units: Int): ByteArray =
        ByteArray(units.size * 2) { i -> (units[i / 2] ushr (if (i % 2 == 0) 0 else 8)).toByte() }

    private fun utf32be(vararg units: Int): ByteArray =
        ByteArray(units.size * 4) { i -> (units[i / 4] ushr (8 * (3 - i % 4))).toByte() }

    private fun readAll(bytes: ByteArray) = JsonReader(bufferOf(bytes)).use { r -> while (r.nextToken() != null) Unit }

    @Test
    fun `invalid surrogates and truncated input are rejected`() {
        val q = 0x22
        val cases = mapOf(
            "lone high surrogate" to utf16le(0xFEFF, '['.code, q, 0xD83D, 'a'.code, q, ']'.code),
            "lone low surrogate" to utf16le(0xFEFF, '['.code, q, 0xDE00, q, ']'.code),
            "high surrogate at end of input" to utf16le('['.code, q, 0xD83D),
            "reversed pair" to utf16le('['.code, q, 0xDE00, 0xD83D, q, ']'.code),
            "odd trailing byte" to utf16le('['.code, ']'.code) + byteArrayOf(0x20),
            "UTF-32 surrogate code point" to utf32be('['.code, q, 0xD800, q, ']'.code),
            "UTF-32 above U+10FFFF" to utf32be('['.code, q, 0x110000, q, ']'.code),
            "UTF-32 truncated unit" to utf32be('['.code, ']'.code) + byteArrayOf(0, 0),
        )
        for ((what, bytes) in cases) {
            assertFailsWith<JsonParseException>(what) { readAll(bytes) }
            assertFailsWith<JsonParseException>("$what, one byte per read") {
                JsonReader(oneBytePerRead(bytes)).use { r -> while (r.nextToken() != null) Unit }
            }
        }
    }
}
