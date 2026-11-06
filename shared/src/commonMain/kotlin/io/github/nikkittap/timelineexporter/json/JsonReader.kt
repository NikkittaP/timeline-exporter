package io.github.nikkittap.timelineexporter.json

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.writeString

/** Token kinds, named after Jackson's `com.fasterxml.jackson.core.JsonToken`. */
enum class JsonToken {
    START_OBJECT, END_OBJECT, START_ARRAY, END_ARRAY, FIELD_NAME,
    VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT, VALUE_TRUE, VALUE_FALSE, VALUE_NULL,
}

/**
 * Malformed (or truncated) JSON; [byteOffset] is how far into the input the reader got.
 * An [IOException] (java.io.IOException on the JVM), as Jackson's JsonParseException
 * is, so callers that treat read failures and malformed files alike keep doing so.
 */
class JsonParseException(message: String, val byteOffset: Long) : IOException(message)

/**
 * A streaming, pull-style JSON tokenizer in common Kotlin — the subset of
 * Jackson's `JsonParser` that the Timeline parser uses, so the same parser
 * code can run on Kotlin/Native (iOS), where Jackson does not exist.
 *
 * Semantics follow Jackson's `JsonParser`:
 *  - [nextToken] returns the next token, or null at the end of input. Several
 *    root-level values in a row are allowed (Jackson allows it too).
 *  - [currentName] on [JsonToken.FIELD_NAME] is the name; on a value inside an
 *    object it is that value's field name; on START_OBJECT/START_ARRAY it is
 *    the name the container is the value of; on END_OBJECT/END_ARRAY it is
 *    the name the closed container was the value of; null inside arrays and
 *    at the top level.
 *  - [valueAsString]: VALUE_STRING text, the literal text of a number,
 *    "true"/"false", the name on FIELD_NAME (as Jackson's `getValueAsString`
 *    does); null for VALUE_NULL and the structural tokens.
 *
 * Differences from Jackson, deliberate:
 *  - An integer literal outside the Long range is reported as
 *    [JsonToken.VALUE_NUMBER_FLOAT] (Jackson reports VALUE_NUMBER_INT with a
 *    BigInteger value). [doubleValue] is the same either way; callers that
 *    want a Long only care about the Long range. Timeline files never
 *    contain such numbers.
 *  - [skipChildren] does not validate the content it skips beyond matching
 *    brackets (and string quoting): that is what makes it cheap. Jackson
 *    tokenizes, and so validates, skipped content.
 *  - A string that is never read is never UTF-8-decoded or validated.
 *
 * Input is UTF-8; a leading byte order mark is skipped. Bytes are read from
 * [source] through a 64 KiB buffer, and the only allocations per token are
 * the strings handed out ([currentName], [valueAsString]) — field names are
 * additionally cached, so a repeated name is the same String instance.
 */
class JsonReader(private val source: Source) : AutoCloseable {

    companion object {
        /** A reader over [text] (encoded as UTF-8). */
        fun of(text: String): JsonReader = JsonReader(Buffer().also { it.writeString(text) })

        private const val BUFFER_SIZE = 64 * 1024

        private const val CTX_OBJECT = 1
        private const val CTX_ARRAY = 2

        private const val QUOTE = 0x22        // "
        private const val BACKSLASH = 0x5C    // \
        private const val SLASH = 0x2F        // /
        private const val COMMA = 0x2C       // ,
        private const val COLON = 0x3A        // :
        private const val LBRACE = 0x7B       // {
        private const val RBRACE = 0x7D       // }
        private const val LBRACKET = 0x5B     // [
        private const val RBRACKET = 0x5D     // ]
        private const val MINUS = 0x2D        // -
        private const val PLUS = 0x2B         // +
        private const val DOT = 0x2E          // .
        private const val ZERO = 0x30
        private const val NINE = 0x39
        private const val SPACE = 0x20
        private const val TAB = 0x09
        private const val LF = 0x0A
        private const val CR = 0x0D

        private const val NAME_CACHE_SIZE = 512 // power of two
        private const val NAME_CACHE_MAX_LEN = 48

        /** Exactly representable powers of ten (the Clinger fast path). */
        private val POW10 = doubleArrayOf(
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
            1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22,
        )
    }

    // ---- input buffer ----
    private val buf = ByteArray(BUFFER_SIZE)
    private var pos = 0
    private var limit = 0
    /** Absolute offset of buf[0] in the input. */
    private var base = 0L
    private var eof = false
    private var started = false

    // ---- context stack: one entry per open object/array ----
    private var ctxType = IntArray(32)
    /** True once the container has a value, i.e. the next thing is ',' or the close. */
    private var ctxHasValue = BooleanArray(32)
    /** Current field name of an object context (null for arrays). */
    private var ctxName = arrayOfNulls<String>(32)
    private var depth = 0

    // ---- current token ----
    private var token: JsonToken? = null

    // VALUE_STRING: either decoded (strValue), or still raw ASCII bytes at buf[strStart, strEnd).
    private var strValue: String? = null
    private var strStart = 0
    private var strEnd = 0

    // Numbers: the literal bytes.
    private var numBuf = ByteArray(32)
    private var numLen = 0
    private var numText: String? = null

    // Scratch for decoding strings with escapes / non-ASCII.
    private var cbuf = CharArray(128)

    // Field-name cache, keyed by the name's (ASCII, escape-free) bytes.
    private val nameCacheKeys = arrayOfNulls<ByteArray>(NAME_CACHE_SIZE)
    private val nameCacheVals = arrayOfNulls<String>(NAME_CACHE_SIZE)

    /** Bytes consumed from the source so far (for progress). */
    val bytesRead: Long get() = base + pos

    /** Next token, or null at end of input. Throws [JsonParseException] on malformed input. */
    fun nextToken(): JsonToken? {
        if (!started) {
            started = true
            skipBom()
        }
        strValue = null
        strStart = 0
        strEnd = 0
        numText = null
        val t = advance()
        token = t
        return t
    }

    fun currentToken(): JsonToken? = token

    /** Field name when on FIELD_NAME, or the name of the field whose value is current; null at top level/in arrays. */
    fun currentName(): String? = when (token) {
        null -> null
        JsonToken.START_OBJECT, JsonToken.START_ARRAY -> nameAt(depth - 1)
        else -> nameAt(depth)
    }

    /** Text of VALUE_STRING, the literal of a number, "true"/"false"; null for structural tokens and VALUE_NULL. */
    val valueAsString: String?
        get() = when (token) {
            JsonToken.VALUE_STRING -> strValue ?: buf.decodeToString(strStart, strEnd).also { strValue = it }
            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> numberText()
            JsonToken.VALUE_TRUE -> "true"
            JsonToken.VALUE_FALSE -> "false"
            JsonToken.FIELD_NAME -> currentName()
            else -> null
        }

    /** VALUE_NUMBER_INT only, else throws IllegalStateException. */
    val longValue: Long
        get() {
            check(token == JsonToken.VALUE_NUMBER_INT) { "Current token ($token) is not VALUE_NUMBER_INT" }
            return parseLong() ?: error("unreachable: VALUE_NUMBER_INT outside the Long range")
        }

    /** VALUE_NUMBER_INT or VALUE_NUMBER_FLOAT, else throws IllegalStateException. */
    val doubleValue: Double
        get() = when (token) {
            JsonToken.VALUE_NUMBER_INT -> longValue.toDouble()
            JsonToken.VALUE_NUMBER_FLOAT -> fastDouble() ?: numberText().toDouble()
            else -> throw IllegalStateException("Current token ($token) is not a number")
        }

    /** On START_OBJECT/START_ARRAY: advance to the matching END token. Otherwise no-op. Must not build strings for skipped content. */
    fun skipChildren() {
        val startToken = token
        if (startToken != JsonToken.START_OBJECT && startToken != JsonToken.START_ARRAY) return
        var level = 1
        val b = buf
        outer@ while (true) {
            if (pos >= limit && !fill()) fail("Unexpected end of input inside a skipped container")
            var i = pos
            val l = limit
            while (i < l) {
                val x = b[i++].toInt()
                if (x == QUOTE) {
                    pos = i
                    skipStringRaw() // may refill the buffer, so re-read pos/limit
                    continue@outer
                } else if (x == LBRACE || x == LBRACKET) {
                    level++
                } else if (x == RBRACE || x == RBRACKET) {
                    if (--level == 0) {
                        pos = i
                        val expected = if (ctxType[depth - 1] == CTX_OBJECT) RBRACE else RBRACKET
                        if (x != expected) fail("Mismatched close '${x.toChar()}'")
                        depth--
                        token = if (x == RBRACE) JsonToken.END_OBJECT else JsonToken.END_ARRAY
                        return
                    }
                }
            }
            pos = i
        }
    }

    override fun close() {
        source.close()
    }

    // ------------------------------------------------------------------
    // Tokenizing

    private fun advance(): JsonToken? {
        if (token == JsonToken.FIELD_NAME) {
            val c = nextNonWs()
            if (c != COLON) {
                if (c < 0) fail("Unexpected end of input after a field name")
                fail("Expected ':' after a field name, found '${c.toChar()}'")
            }
            pos++
            return readValue(nextNonWsOrFail())
        }
        if (depth == 0) {
            val c = nextNonWs()
            if (c < 0) return null
            return readValue(c)
        }
        val d = depth - 1
        var c = nextNonWsOrFail()
        if (ctxType[d] == CTX_OBJECT) {
            if (c == RBRACE) {
                pos++
                depth--
                return JsonToken.END_OBJECT
            }
            if (ctxHasValue[d]) {
                if (c != COMMA) fail("Expected ',' or '}', found '${c.toChar()}'")
                pos++
                c = nextNonWsOrFail()
            }
            if (c != QUOTE) fail("Expected a field name, found '${c.toChar()}'")
            pos++
            ctxName[d] = readName()
            ctxHasValue[d] = true
            return JsonToken.FIELD_NAME
        } else {
            if (c == RBRACKET) {
                pos++
                depth--
                return JsonToken.END_ARRAY
            }
            if (ctxHasValue[d]) {
                if (c != COMMA) fail("Expected ',' or ']', found '${c.toChar()}'")
                pos++
                c = nextNonWsOrFail()
            }
            ctxHasValue[d] = true
            return readValue(c)
        }
    }

    /** Reads the value starting with byte [c] (not yet consumed, at [pos]). */
    private fun readValue(c: Int): JsonToken = when (c) {
        LBRACE -> { pos++; push(CTX_OBJECT); JsonToken.START_OBJECT }
        LBRACKET -> { pos++; push(CTX_ARRAY); JsonToken.START_ARRAY }
        QUOTE -> { pos++; readStringValue(); JsonToken.VALUE_STRING }
        0x74 -> { readLiteral("true"); JsonToken.VALUE_TRUE }   // t
        0x66 -> { readLiteral("false"); JsonToken.VALUE_FALSE } // f
        0x6E -> { readLiteral("null"); JsonToken.VALUE_NULL }   // n
        else -> if (c == MINUS || c in ZERO..NINE) readNumber(c)
        else fail("Unexpected character '${c.toChar()}' where a value was expected")
    }

    private fun push(type: Int) {
        if (depth == ctxType.size) {
            val n = depth * 2
            ctxType = ctxType.copyOf(n)
            ctxHasValue = ctxHasValue.copyOf(n)
            ctxName = ctxName.copyOf(n)
        }
        ctxType[depth] = type
        ctxHasValue[depth] = false
        ctxName[depth] = null
        depth++
    }

    /** Name of the innermost context of a stack [level] deep, if it is an object. */
    private fun nameAt(level: Int): String? =
        if (level <= 0 || ctxType[level - 1] != CTX_OBJECT) null else ctxName[level - 1]

    private fun readLiteral(word: String) {
        for (ch in word) {
            if (readByteOrFail() != ch.code) {
                pos--
                fail("Invalid literal, expected '$word'")
            }
        }
        checkValueEnd()
    }

    /** After a number or literal: the next byte must end the value. */
    private fun checkValueEnd() {
        val c = peekByte()
        if (c < 0 || c == SPACE || c == LF || c == CR || c == TAB ||
            c == COMMA || c == RBRACE || c == RBRACKET
        ) return
        fail("Unexpected character '${c.toChar()}' after a value")
    }

    // ---- numbers ----

    private fun appendNum(x: Int) {
        if (numLen == numBuf.size) numBuf = numBuf.copyOf(numLen * 2)
        numBuf[numLen++] = x.toByte()
    }

    private fun readNumber(first: Int): JsonToken {
        numLen = 0
        var x = first
        var isFloat = false
        if (x == MINUS) {
            appendNum(x); pos++
            x = peekByte()
        }
        var intDigits = 0
        if (x == ZERO) {
            appendNum(x); pos++; intDigits = 1
            x = peekByte()
            if (x in ZERO..NINE) fail("Leading zeroes are not allowed in a number")
        } else if (x in ZERO..NINE) {
            while (x in ZERO..NINE) {
                appendNum(x); pos++; intDigits++
                x = peekByte()
            }
        } else {
            fail("Expected a digit in a number")
        }
        if (x == DOT) {
            isFloat = true
            appendNum(x); pos++
            x = peekByte()
            if (x !in ZERO..NINE) fail("Expected a digit after the decimal point")
            while (x in ZERO..NINE) {
                appendNum(x); pos++
                x = peekByte()
            }
        }
        if (x == 0x65 || x == 0x45) { // e E
            isFloat = true
            appendNum(x); pos++
            x = peekByte()
            if (x == PLUS || x == MINUS) {
                appendNum(x); pos++
                x = peekByte()
            }
            if (x !in ZERO..NINE) fail("Expected a digit in the exponent")
            while (x in ZERO..NINE) {
                appendNum(x); pos++
                x = peekByte()
            }
        }
        checkValueEnd()
        if (isFloat) return JsonToken.VALUE_NUMBER_FLOAT
        // 18 digits always fit a Long; longer literals may not.
        if (intDigits >= 19 && parseLong() == null) return JsonToken.VALUE_NUMBER_FLOAT
        return JsonToken.VALUE_NUMBER_INT
    }

    private fun numberText(): String = numText ?: numBuf.decodeToString(0, numLen).also { numText = it }

    /** The integer literal in [numBuf] as a Long, or null if it overflows. */
    private fun parseLong(): Long? {
        var i = 0
        val neg = numBuf[0].toInt() == MINUS
        if (neg) i = 1
        val lim = if (neg) Long.MIN_VALUE else -Long.MAX_VALUE
        val multMin = lim / 10
        var acc = 0L // accumulated negatively, as Long.MIN_VALUE has no positive twin
        while (i < numLen) {
            val d = numBuf[i++].toInt() - ZERO
            if (acc < multMin) return null
            acc *= 10
            if (acc < lim + d) return null
            acc -= d
        }
        return if (neg) acc else -acc
    }

    /**
     * Exact double for a float literal with at most 15 significant digits and
     * a power of ten within ±22 (the result is one correctly rounded IEEE
     * operation); null when the slow path (String.toDouble) is needed.
     */
    private fun fastDouble(): Double? {
        var i = 0
        val neg = numBuf[0].toInt() == MINUS
        if (neg) i = 1
        var m = 0L
        var digits = 0
        var frac = 0
        var seenDot = false
        while (i < numLen) {
            val x = numBuf[i].toInt()
            if (x == DOT) {
                seenDot = true
            } else if (x in ZERO..NINE) {
                val d = x - ZERO
                if (m != 0L || d != 0) {
                    if (++digits > 15) return null
                    m = m * 10 + d
                }
                if (seenDot) frac++
            } else {
                break // exponent
            }
            i++
        }
        var exp = 0
        if (i < numLen) {
            i++ // e/E
            var expNeg = false
            val s = numBuf[i].toInt()
            if (s == MINUS || s == PLUS) { expNeg = s == MINUS; i++ }
            var expDigits = 0
            while (i < numLen) {
                if (++expDigits > 4) return null
                exp = exp * 10 + (numBuf[i++].toInt() - ZERO)
            }
            if (expNeg) exp = -exp
        }
        val v: Double = if (m == 0L) {
            0.0
        } else {
            val e10 = exp - frac
            when {
                e10 == 0 -> m.toDouble()
                e10 in 1..22 -> m.toDouble() * POW10[e10]
                e10 in -22..-1 -> m.toDouble() / POW10[-e10]
                else -> return null
            }
        }
        return if (neg) -v else v
    }

    // ---- strings ----

    /** Scans a string value whose opening quote is consumed. ASCII strings in the buffer are decoded lazily. */
    private fun readStringValue() {
        val b = buf
        val start = pos
        var i = start
        val l = limit
        while (i < l) {
            val x = b[i].toInt()
            if (x == QUOTE) {
                strStart = start
                strEnd = i
                pos = i + 1
                return
            }
            if (x == BACKSLASH || x < SPACE) break // x < SPACE also catches non-ASCII (negative bytes)
            i++
        }
        strValue = decodeStringSlow()
    }

    /** Reads a field name whose opening quote is consumed; short ASCII names come from a cache. */
    private fun readName(): String {
        val b = buf
        val start = pos
        var i = start
        val l = limit
        var h = 0
        while (i < l) {
            val x = b[i].toInt()
            if (x == QUOTE) {
                val len = i - start
                pos = i + 1
                if (len > NAME_CACHE_MAX_LEN) return b.decodeToString(start, i)
                val slot = (h xor (h ushr 9)) and (NAME_CACHE_SIZE - 1)
                val key = nameCacheKeys[slot]
                if (key != null && key.size == len && sameBytes(key, b, start)) return nameCacheVals[slot]!!
                val s = b.decodeToString(start, i)
                nameCacheKeys[slot] = b.copyOfRange(start, i)
                nameCacheVals[slot] = s
                return s
            }
            if (x == BACKSLASH || x < SPACE) break
            h = 31 * h + x
            i++
        }
        return decodeStringSlow()
    }

    private fun sameBytes(key: ByteArray, b: ByteArray, start: Int): Boolean {
        for (k in key.indices) if (key[k] != b[start + k]) return false
        return true
    }

    /** Full decoder: escapes, UTF-8, buffer refills. Starts at [pos] (just after the opening quote). */
    private fun decodeStringSlow(): String {
        var n = 0
        while (true) {
            val x = readByteOrFail()
            if (n + 2 > cbuf.size) cbuf = cbuf.copyOf(cbuf.size * 2)
            when {
                x == QUOTE -> return cbuf.concatToString(0, n)
                x == BACKSLASH -> {
                    cbuf[n++] = when (val e = readByteOrFail()) {
                        QUOTE, BACKSLASH, SLASH -> e.toChar()
                        0x62 -> '\b'          // b
                        0x66 -> '\u000C'      // f
                        0x6E -> '\n'          // n
                        0x72 -> '\r'          // r
                        0x74 -> '\t'          // t
                        0x75 -> {             // u
                            var v = 0
                            repeat(4) { v = (v shl 4) or hexValue(readByteOrFail()) }
                            v.toChar()
                        }
                        else -> { pos--; fail("Invalid escape '\\${e.toChar()}'") }
                    }
                }
                x < SPACE -> { pos--; fail("Unescaped control character in a string") }
                x < 0x80 -> cbuf[n++] = x.toChar()
                x < 0xC2 -> { pos--; fail("Invalid UTF-8 start byte") }
                x < 0xE0 -> cbuf[n++] = (((x and 0x1F) shl 6) or cont()).toChar()
                x < 0xF0 -> {
                    val c = ((x and 0x0F) shl 12) or (cont() shl 6) or cont()
                    if (c < 0x800) fail("Overlong UTF-8 sequence")
                    cbuf[n++] = c.toChar()
                }
                x < 0xF5 -> {
                    val cp = ((x and 0x07) shl 18) or (cont() shl 12) or (cont() shl 6) or cont()
                    if (cp < 0x10000 || cp > 0x10FFFF) fail("Invalid UTF-8 code point")
                    val v = cp - 0x10000
                    cbuf[n++] = (0xD800 + (v shr 10)).toChar()
                    cbuf[n++] = (0xDC00 + (v and 0x3FF)).toChar()
                }
                else -> { pos--; fail("Invalid UTF-8 start byte") }
            }
        }
    }

    private fun cont(): Int {
        val y = readByteOrFail()
        if ((y and 0xC0) != 0x80) { pos--; fail("Invalid UTF-8 continuation byte") }
        return y and 0x3F
    }

    private fun hexValue(x: Int): Int = when (x) {
        in 0x30..0x39 -> x - 0x30
        in 0x41..0x46 -> x - 0x41 + 10
        in 0x61..0x66 -> x - 0x61 + 10
        else -> { pos--; fail("Invalid hex digit in a \\u escape") }
    }

    /**
     * Skips a string whose opening quote is consumed, without decoding it. An
     * escape is skipped as the backslash plus the next byte (the rest of a
     * \u escape is four hex digits, none of them a quote).
     */
    private fun skipStringRaw() {
        val b = buf
        outer@ while (true) {
            if (pos >= limit && !fill()) fail("Unexpected end of input inside a string")
            var i = pos
            val l = limit
            while (i < l) {
                val x = b[i].toInt()
                if (x == QUOTE) {
                    pos = i + 1
                    return
                }
                if (x == BACKSLASH) {
                    if (i + 1 < l) {
                        i += 2
                        continue
                    }
                    // The escaped byte is at the start of the next buffer.
                    pos = l
                    readByteOrFail()
                    continue@outer
                }
                i++
            }
            pos = i
        }
    }

    // ---- bytes ----

    private fun skipBom() {
        if (peekByte() == 0xEF) {
            pos++
            if (readByteOrFail() != 0xBB || readByteOrFail() != 0xBF) fail("Invalid byte order mark")
        }
    }

    /** Refills the (fully consumed) buffer; false at end of input. */
    private fun fill(): Boolean {
        if (eof) return false
        base += limit
        pos = 0
        limit = 0
        while (true) {
            val n = source.readAtMostTo(buf, 0, buf.size)
            if (n < 0) { eof = true; return false }
            if (n > 0) { limit = n; return true }
        }
    }

    /** Next byte (0..255) without consuming it, or -1 at end of input. */
    private fun peekByte(): Int {
        if (pos >= limit && !fill()) return -1
        return buf[pos].toInt() and 0xFF
    }

    private fun readByteOrFail(): Int {
        if (pos >= limit && !fill()) fail("Unexpected end of input")
        return buf[pos++].toInt() and 0xFF
    }

    /** Skips whitespace; the next byte (not consumed), or -1 at end of input. */
    private fun nextNonWs(): Int {
        while (true) {
            if (pos >= limit && !fill()) return -1
            val x = buf[pos].toInt()
            if (x == SPACE || x == LF || x == CR || x == TAB) pos++ else return x and 0xFF
        }
    }

    private fun nextNonWsOrFail(): Int {
        val c = nextNonWs()
        if (c < 0) fail("Unexpected end of input")
        return c
    }

    private fun fail(message: String): Nothing =
        throw JsonParseException("$message at byte $bytesRead", bytesRead)
}
