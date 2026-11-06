package io.github.nikkittap.timelineexporter.json

import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The size limits that stand in for Jackson's StreamReadConstraints. On
 * Kotlin/Native an out-of-memory error cannot be caught, so a crafted or
 * corrupt file must fail with JsonParseException before arrays grow.
 */
class JsonReaderLimitsTest {

    private fun readAll(r: JsonReader) = r.use { while (it.nextToken() != null) it.valueAsString }

    @Test
    fun `nesting up to the limit is fine and one deeper fails`() {
        val ok = "[".repeat(JsonReader.MAX_DEPTH) + "]".repeat(JsonReader.MAX_DEPTH)
        readAll(JsonReader.of(ok))
        val objects = "{\"a\":".repeat(JsonReader.MAX_DEPTH - 1) + "{}" + "}".repeat(JsonReader.MAX_DEPTH - 1)
        readAll(JsonReader.of(objects))

        val tooDeep = "[".repeat(JsonReader.MAX_DEPTH + 1) + "]".repeat(JsonReader.MAX_DEPTH + 1)
        val e = assertFailsWith<JsonParseException> { readAll(JsonReader.of(tooDeep)) }
        assertTrue("Nesting" in e.message!!, e.message)
        // Millions deep, as a crafted file would be: fails at the limit, not out of memory.
        assertFailsWith<JsonParseException> { readAll(JsonReader.of("[".repeat(2_000_000))) }
    }

    @Test
    fun `number text up to the limit is fine and one longer fails`() {
        val n = JsonReader.MAX_NUMBER_LENGTH
        JsonReader.of("[" + "1".repeat(n) + "]").use { r ->
            r.nextToken(); r.nextToken()
            assertEquals(n, r.valueAsString!!.length)
        }
        JsonReader.of("[0." + "5".repeat(n - 2) + "]").use { r ->
            r.nextToken(); r.nextToken()
            assertEquals(0.5555555555555556, r.doubleValue)
        }
        for (tooLong in listOf("1".repeat(n + 1), "-" + "1".repeat(n), "0." + "5".repeat(n), "1e" + "0".repeat(n))) {
            val e = assertFailsWith<JsonParseException>(tooLong.take(5)) { readAll(JsonReader.of("[$tooLong]")) }
            assertTrue("Number" in e.message!!, e.message)
        }
        assertFailsWith<JsonParseException> { readAll(JsonReader.of("[" + "9".repeat(1_000_000) + "]")) }
    }

    private fun reader(json: String, maxStringLength: Int) =
        JsonReader(Buffer().apply { writeString(json) }, maxStringLength)

    @Test
    fun `strings up to the limit are fine and one longer fails`() {
        val limit = 300 // past the 128-char scratch buffer, so it has to grow first
        val bodies = listOf(
            "a".repeat(limit),                  // fast path: plain ASCII in the buffer
            "é".repeat(limit),                  // slow path: non-ASCII
            "\\n".repeat(limit),                // slow path: escapes
            "a".repeat(limit - 2) + "😀",       // a surrogate pair counts as two chars
        )
        for (body in bodies) {
            val what = body.take(4)
            reader("[\"$body\"]", limit).use { r ->
                r.nextToken(); r.nextToken()
                assertEquals(limit, r.valueAsString!!.length, what)
            }
            reader("{\"$body\":1}", limit).use { r ->
                r.nextToken(); r.nextToken()
                assertEquals(limit, r.currentName()!!.length, what)
            }
            val longer = body + "x"
            val e = assertFailsWith<JsonParseException>(what) { readAll(reader("[\"$longer\"]", limit)) }
            assertTrue("String" in e.message!!, e.message)
            assertFailsWith<JsonParseException>("$what as a name") { readAll(reader("{\"$longer\":1}", limit)) }
        }
        // A short limit applies before the scratch buffer first grows, too.
        assertFailsWith<JsonParseException> { readAll(reader("[\"" + "é".repeat(11) + "\"]", 10)) }
        // A huge string fails without being held whole.
        assertFailsWith<JsonParseException> { readAll(reader("[\"" + "é".repeat(200_000) + "\"]", 1000)) }
    }

    @Test
    fun `the default limits are Jackson's`() {
        assertEquals(1000, JsonReader.MAX_DEPTH)
        assertEquals(1000, JsonReader.MAX_NUMBER_LENGTH)
        assertEquals(20_000_000, JsonReader.MAX_STRING_LENGTH)
    }
}
