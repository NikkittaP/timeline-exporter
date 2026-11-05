package io.github.nikkittap.timelineexporter.ui

import org.junit.Assert.*
import org.junit.Test

class TapSequenceTest {
    @Test fun completesOnlyWithinWindowAndResets() {
        val taps = TapSequence(5, 3000)
        for (i in 0..3) assertFalse(taps.tap(1000L + i * 100))
        assertTrue(taps.tap(1400))
        assertFalse(taps.tap(1500))
    }
    @Test fun timeoutStartsFreshSequence() {
        val taps = TapSequence(3, 1000)
        assertFalse(taps.tap(100))
        assertFalse(taps.tap(1200))
        assertFalse(taps.tap(1300))
        assertTrue(taps.tap(1400))
    }
}
