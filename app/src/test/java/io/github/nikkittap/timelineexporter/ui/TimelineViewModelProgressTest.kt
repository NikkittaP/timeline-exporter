package io.github.nikkittap.timelineexporter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [weightedProgress]: the fix for the load-progress bar crawling
 * through the first few real percent of a phone export and then finishing
 * the rest in under a second (see the function's doc comment for why).
 */
class TimelineViewModelProgressTest {

    private val total = 62_884_513L // Timeline.json, the reference export
    private val split = 5_164_108L // where rawSignals starts in it (~8%)

    @Test
    fun `with no split point, progress is a plain byte ratio`() {
        assertEquals(0f, weightedProgress(0, total, null), 0.0001f)
        assertEquals(0.5f, weightedProgress(total / 2, total, null), 0.001f)
        assertEquals(1f, weightedProgress(total, total, null), 0.0001f)
    }

    @Test
    fun `reaching the split point lands at the reserved share, not the raw byte fraction`() {
        // Raw byte fraction here is ~8%; the whole point of the split is that
        // the bar should already be most of the way full by the time the
        // expensive phase (before rawSignals) is done.
        val atSplit = weightedProgress(split, total, split)
        assertTrue("expected most of the bar filled by the split, was $atSplit", atSplit > 0.85f)
        assertEquals(0.9f, atSplit, 0.001f)
    }

    @Test
    fun `progress is monotonically increasing across the whole range`() {
        val samples = listOf(0L, split / 2, split, split + (total - split) / 4, total)
        val fractions = samples.map { weightedProgress(it, total, split) }
        assertTrue(
            "expected non-decreasing progress, got $fractions",
            fractions.zipWithNext().all { (a, b) -> b >= a },
        )
        assertEquals(0f, fractions.first(), 0.0001f)
        assertEquals(1f, fractions.last(), 0.0001f)
    }

    @Test
    fun `an invalid split point (past or at the end of the file) falls back to a plain byte ratio`() {
        assertEquals(0.5f, weightedProgress(total / 2, total, total), 0.001f)
        assertEquals(0.5f, weightedProgress(total / 2, total, total + 1), 0.001f)
        assertEquals(0.5f, weightedProgress(total / 2, total, 0), 0.001f)
    }
}
