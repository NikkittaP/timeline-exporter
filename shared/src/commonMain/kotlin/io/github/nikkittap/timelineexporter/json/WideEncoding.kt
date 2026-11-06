package io.github.nikkittap.timelineexporter.json

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source

/**
 * The non-UTF-8 encodings JSON may arrive in (RFC 4627 section 3), which
 * Jackson detected on its own and [JsonReader] transcodes to UTF-8 before
 * lexing. They are rare — a Timeline.json re-saved as "Unicode" by Windows
 * Notepad is UTF-16 LE with a byte order mark — so they get a separate,
 * simple layer and the UTF-8 path stays untouched.
 */
internal enum class WideEncoding(val unitBytes: Int, val bigEndian: Boolean, val label: String) {
    UTF_16BE(2, true, "UTF-16BE"),
    UTF_16LE(2, false, "UTF-16LE"),
    UTF_32BE(4, true, "UTF-32BE"),
    UTF_32LE(4, false, "UTF-32LE"),
}

/** A detected wide encoding and the length of the byte order mark in front of the text (0 if none). */
internal class DetectedEncoding(val encoding: WideEncoding, val bomBytes: Int)

/**
 * The encoding of [source] if it is UTF-16 or UTF-32, from its byte order
 * mark or, without one, from where the zero bytes fall in the first four
 * bytes: JSON text starts with an ASCII character, so `00 00 00 xx` is
 * UTF-32BE, `xx 00 00 00` UTF-32LE, `00 xx` UTF-16BE and `xx 00` UTF-16LE.
 * Null for UTF-8 (with or without a BOM) and for input too short to tell.
 * Consumes nothing from [source].
 */
internal fun detectWideEncoding(source: Source): DetectedEncoding? {
    if (!source.request(2)) return null
    source.request(4)
    val peek = source.peek()
    val b = IntArray(4) { -1 }
    var n = 0
    while (n < 4 && peek.request(1)) b[n++] = peek.readByte().toInt() and 0xFF
    val (b0, b1, b2, b3) = b
    return when {
        n == 4 && b0 == 0 && b1 == 0 && b2 == 0xFE && b3 == 0xFF -> DetectedEncoding(WideEncoding.UTF_32BE, 4)
        n == 4 && b0 == 0xFF && b1 == 0xFE && b2 == 0 && b3 == 0 -> DetectedEncoding(WideEncoding.UTF_32LE, 4)
        b0 == 0xFE && b1 == 0xFF -> DetectedEncoding(WideEncoding.UTF_16BE, 2)
        b0 == 0xFF && b1 == 0xFE -> DetectedEncoding(WideEncoding.UTF_16LE, 2)
        n == 4 && b0 == 0 && b1 == 0 && b2 == 0 && b3 != 0 -> DetectedEncoding(WideEncoding.UTF_32BE, 0)
        n == 4 && b0 != 0 && b1 == 0 && b2 == 0 && b3 == 0 -> DetectedEncoding(WideEncoding.UTF_32LE, 0)
        b0 == 0 && b1 != 0 -> DetectedEncoding(WideEncoding.UTF_16BE, 0)
        b0 != 0 && b1 == 0 -> DetectedEncoding(WideEncoding.UTF_16LE, 0)
        else -> null
    }
}

/**
 * Streams UTF-16 or UTF-32 text from [upstream] out as UTF-8, a chunk at a
 * time (never the whole file). [upstream] must be positioned after the byte
 * order mark; [startOffset] is how many bytes that was, so error offsets
 * count from the start of the file. Unpaired surrogates, code points above
 * U+10FFFF and a truncated final code unit throw [JsonParseException].
 */
internal class WideToUtf8Source(
    private val upstream: Source,
    private val encoding: WideEncoding,
    startOffset: Long,
) : RawSource {

    private companion object {
        const val CHUNK = 16 * 1024 // a multiple of 4
    }

    private val inBuf = ByteArray(CHUNK)
    private var inLen = 0
    /** Input bytes decoded so far, counting the BOM: where inBuf[0] sits in the file. */
    private var consumed = startOffset
    // A UTF-16 unit (2 bytes) becomes at most 3 UTF-8 bytes; a pair (4) or a UTF-32 unit (4) at most 4.
    private val outBuf = ByteArray(CHUNK / 2 * 3)
    private val pending = Buffer()

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0
        while (pending.exhausted()) {
            // decode() leaves at most 3 bytes behind, so there is always room.
            val n = upstream.readAtMostTo(inBuf, inLen, inBuf.size)
            if (n < 0) {
                if (inLen == 0) return -1
                fail("Truncated ${encoding.label} input", consumed)
            }
            inLen += n
            decode()
        }
        return pending.readAtMostTo(sink, byteCount)
    }

    private fun decode() {
        val u = encoding.unitBytes
        var i = 0
        var o = 0
        while (i + u <= inLen) {
            var cp = unitAt(i)
            var used = u
            if (u == 2) {
                if (cp in 0xD800..0xDBFF) {
                    if (i + 4 > inLen) break // the low surrogate has not arrived yet
                    val lo = unitAt(i + 2)
                    if (lo !in 0xDC00..0xDFFF) fail("Unpaired UTF-16 high surrogate", consumed + i)
                    cp = 0x10000 + ((cp - 0xD800) shl 10) + (lo - 0xDC00)
                    used = 4
                } else if (cp in 0xDC00..0xDFFF) {
                    fail("Unpaired UTF-16 low surrogate", consumed + i)
                }
            } else if (cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) {
                fail("Invalid UTF-32 code point", consumed + i)
            }
            o = putUtf8(cp, o)
            i += used
        }
        consumed += i
        inBuf.copyInto(inBuf, 0, i, inLen)
        inLen -= i
        pending.write(outBuf, 0, o)
    }

    /** The code unit at inBuf[i] (UTF-16: 0..0xFFFF; UTF-32: as a signed Int). */
    private fun unitAt(i: Int): Int {
        val b = inBuf
        return if (encoding.unitBytes == 2) {
            val hi = if (encoding.bigEndian) b[i] else b[i + 1]
            val lo = if (encoding.bigEndian) b[i + 1] else b[i]
            ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
        } else if (encoding.bigEndian) {
            ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
                ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        } else {
            ((b[i + 3].toInt() and 0xFF) shl 24) or ((b[i + 2].toInt() and 0xFF) shl 16) or
                ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)
        }
    }

    private fun putUtf8(cp: Int, at: Int): Int {
        val b = outBuf
        var o = at
        when {
            cp < 0x80 -> b[o++] = cp.toByte()
            cp < 0x800 -> {
                b[o++] = (0xC0 or (cp shr 6)).toByte()
                b[o++] = (0x80 or (cp and 0x3F)).toByte()
            }
            cp < 0x10000 -> {
                b[o++] = (0xE0 or (cp shr 12)).toByte()
                b[o++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                b[o++] = (0x80 or (cp and 0x3F)).toByte()
            }
            else -> {
                b[o++] = (0xF0 or (cp shr 18)).toByte()
                b[o++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                b[o++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                b[o++] = (0x80 or (cp and 0x3F)).toByte()
            }
        }
        return o
    }

    private fun fail(message: String, offset: Long): Nothing =
        throw JsonParseException("$message at input byte $offset", offset)

    override fun close() {
        upstream.close()
    }
}
