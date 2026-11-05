package io.github.nikkittap.timelineexporter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {

    private val notes = listOf(
        WhatsNewEntry("1.8.5", 1),
        WhatsNewEntry("1.10.0", 4),
        WhatsNewEntry("1.9.0", 2),
        WhatsNewEntry("1.9.2", 3),
    )

    private fun versions(list: List<WhatsNewEntry>) = list.map { it.version }

    @Test
    fun comparesNumerically() {
        assertTrue(WhatsNew.compare("1.10.0", "1.9.2") > 0)
        assertTrue(WhatsNew.compare("1.9", "1.9.0") == 0)
        assertTrue(WhatsNew.compare("1.8.5", "1.9.0") < 0)
    }

    @Test
    fun freshInstallShowsNothing() {
        assertEquals(emptyList<String>(), versions(WhatsNew.pending(notes, null, "1.9.2", updated = false)))
    }

    @Test
    fun updateFromBeforeTheFeatureShowsOnlyTheCurrentRelease() {
        assertEquals(listOf("1.9.2"), versions(WhatsNew.pending(notes, null, "1.9.2", updated = true)))
    }

    @Test
    fun skippedReleasesAreAllShownNewestFirst() {
        assertEquals(listOf("1.9.2", "1.9.0"), versions(WhatsNew.pending(notes, "1.8.5", "1.9.2", updated = true)))
    }

    @Test
    fun sameVersionShowsNothing() {
        assertEquals(emptyList<String>(), versions(WhatsNew.pending(notes, "1.9.2", "1.9.2", updated = true)))
    }

    @Test
    fun releaseWithoutNotesShowsNothing() {
        assertEquals(emptyList<String>(), versions(WhatsNew.pending(notes, "1.9.2", "1.9.3", updated = true)))
    }

    @Test
    fun historyStopsAtTheCurrentVersion() {
        assertEquals(listOf("1.9.2", "1.9.0", "1.8.5"), versions(WhatsNew.history(notes, "1.9.2")))
    }

    @Test
    fun generatedListIsNewestFirstAndUnique() {
        val v = versions(WHATS_NEW_NOTES)
        assertEquals(v.distinct(), v)
        assertEquals(v.sortedWith { a, b -> WhatsNew.compare(b, a) }, v)
    }
}
