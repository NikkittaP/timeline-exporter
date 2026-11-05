package io.github.nikkittap.timelineexporter.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class JourneyNumberFormatTest {
    @Test fun formatsUsingSelectedLocale() {
        assertEquals("1.77", formatJourneyRatio(70_946_000.0, Locale.US))
        assertEquals("1,77", formatJourneyRatio(70_946_000.0, Locale.GERMANY))
    }
    @Test fun shortDistanceDoesNotRoundToZero() {
        assertNotEquals("0", formatJourneyRatio(1.0, Locale.US))
    }
}
