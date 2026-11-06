package io.github.nikkittap.timelineexporter.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonReaderTest {

    private fun tokens(json: String): List<Pair<JsonToken, String?>> = JsonReader.of(json).use { r ->
        buildList { while (true) { val t = r.nextToken() ?: break; add(t to (if (t == JsonToken.FIELD_NAME) r.currentName() else r.valueAsString)) } }
    }

    @Test fun objectWithScalars() = assertEquals(
        listOf(JsonToken.START_OBJECT to null, JsonToken.FIELD_NAME to "a", JsonToken.VALUE_NUMBER_INT to "1",
            JsonToken.FIELD_NAME to "b", JsonToken.VALUE_STRING to "x", JsonToken.FIELD_NAME to "c", JsonToken.VALUE_NULL to null,
            JsonToken.FIELD_NAME to "d", JsonToken.VALUE_TRUE to "true", JsonToken.END_OBJECT to null),
        tokens("""{"a":1,"b":"x","c":null,"d":true}"""))

    @Test fun escapesAndUnicode() = assertEquals(
        "q\"\\/\b\u000C\n\r\t é 😀 °",
        JsonReader.of("\"q\\\"\\\\\\/\\b\\f\\n\\r\\t \\u00e9 \\ud83d\\ude00 °\"").use { it.nextToken(); it.valueAsString })

    @Test fun numbers() { JsonReader.of("[-0.5e3, 12345678901234, 1.0, -7]").use { r ->
        r.nextToken()
        r.nextToken(); assertEquals(JsonToken.VALUE_NUMBER_FLOAT, r.currentToken()); assertEquals(-500.0, r.doubleValue)
        r.nextToken(); assertEquals(JsonToken.VALUE_NUMBER_INT, r.currentToken()); assertEquals(12345678901234L, r.longValue)
        r.nextToken(); assertEquals(JsonToken.VALUE_NUMBER_FLOAT, r.currentToken())
        r.nextToken(); assertEquals(-7L, r.longValue)
    } }

    @Test fun currentNameOnValue() { JsonReader.of("""{"k":[1]}""").use { r ->
        r.nextToken(); r.nextToken(); r.nextToken()
        assertEquals(JsonToken.START_ARRAY, r.currentToken()); assertEquals("k", r.currentName())
    } }

    @Test fun skipChildren() { JsonReader.of("""{"a":{"b":[1,{"c":2}],"d":"}"},"e":3}""").use { r ->
        r.nextToken(); r.nextToken(); r.nextToken(); r.skipChildren()
        assertEquals(JsonToken.END_OBJECT, r.currentToken())
        assertEquals(JsonToken.FIELD_NAME, r.nextToken()); assertEquals("e", r.currentName())
    } }

    @Test fun bomAndWhitespace() = assertEquals(listOf(JsonToken.START_ARRAY to null, JsonToken.END_ARRAY to null), tokens("﻿ \n[ ]\t"))

    @Test fun truncatedThrows() { assertFailsWith<JsonParseException> { tokens("""{"a":[1,2""") } }
    @Test fun garbageThrows() { assertFailsWith<JsonParseException> { tokens("""{"a" 1}""") } }

    @Test fun bytesReadCountsUtf8() { JsonReader.of("[\"é\"]").use { r -> while (r.nextToken() != null) {}; assertEquals(6L, r.bytesRead) } }
}
