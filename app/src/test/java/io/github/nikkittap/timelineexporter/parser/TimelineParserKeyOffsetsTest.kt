package io.github.nikkittap.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [findKeyOffsets]: the raw-byte pre-scan that locates the
 * semanticSegments/timelineObjects/locations key and the rawSignals key
 * without JSON-parsing the file, so the load-progress bar can tell the
 * expensive part of a phone export from the cheap-to-skip tail before the
 * real parse even starts.
 */
class TimelineParserKeyOffsetsTest {

    @Test
    fun `finds both offsets in a normal phone export shape`() {
        val prefix = "{ \"semanticSegments\": [ "
        val json = prefix + "x".repeat(1000) + "], \"rawSignals\": [] }"
        val offsets = findKeyOffsets(json.byteInputStream())

        assertEquals(prefix.indexOf("\"semanticSegments\"").toLong(), offsets.firstContentKeyOffset)
        assertEquals(json.indexOf("\"rawSignals\"").toLong(), offsets.rawSignalsOffset)
        assertTrue(offsets.firstContentKeyOffset!! < offsets.rawSignalsOffset!!)
    }

    @Test
    fun `finds a needle that straddles a read-buffer boundary`() {
        // The scan reads in 64 KB chunks; pad well past that so the needle
        // itself is split across two reads, and confirm the offset still
        // comes out exact rather than being missed at the seam.
        val padding = "x".repeat(70_000)
        val json = "{ \"semanticSegments\": [$padding], \"rawSignals\": [] }"
        val offsets = findKeyOffsets(json.byteInputStream())

        assertEquals(json.indexOf("\"rawSignals\"").toLong(), offsets.rawSignalsOffset)
    }

    @Test
    fun `returns null offsets for keys that never appear`() {
        val json = "{ \"locations\": [] }"
        val offsets = findKeyOffsets(json.byteInputStream())

        assertEquals(json.indexOf("\"locations\"").toLong(), offsets.firstContentKeyOffset)
        assertNull(offsets.rawSignalsOffset)
    }

    @Test
    fun `reports rawSignals before the content key when the file is ordered that way`() {
        val json = "{ \"rawSignals\": [], \"semanticSegments\": [] }"
        val offsets = findKeyOffsets(json.byteInputStream())

        assertTrue(offsets.rawSignalsOffset!! < offsets.firstContentKeyOffset!!)
    }
}
