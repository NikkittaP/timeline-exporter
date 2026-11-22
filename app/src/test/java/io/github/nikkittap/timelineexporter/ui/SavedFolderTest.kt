package io.github.nikkittap.timelineexporter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedFolderTest {

    private val local = "com.android.externalstorage.documents"
    private val downloads = "com.android.providers.downloads.documents"

    @Test
    fun localStorageNamesTheLastFolder() {
        assertEquals("Download", savedFolderName(local, "primary:Download/Timeline.gpx"))
        assertEquals("Trips 2026", savedFolderName(local, "primary:Documents/Trips 2026/Timeline.kml"))
        assertEquals("GPS", savedFolderName(local, "1A2B-3C4D:GPS/Timeline.gpx"))
    }

    @Test
    fun theTopOfAVolumeHasNoFolderName() {
        assertNull(savedFolderName(local, "primary:Timeline.gpx"))
        assertNull(savedFolderName(local, "primary"))
    }

    @Test
    fun downloadsProviderOnlyForRawPaths() {
        assertEquals("Download", savedFolderName(downloads, "raw:/storage/emulated/0/Download/Timeline.gpx"))
        assertNull(savedFolderName(downloads, "msf:1234"))
        assertNull(savedFolderName(downloads, "1234"))
    }

    @Test
    fun otherProvidersAndMissingIdsGiveNothing() {
        assertNull(savedFolderName("com.google.android.apps.docs.storage", "acc=1;doc=encoded=abc"))
        assertNull(savedFolderName(local, null))
        assertNull(savedFolderName(null, "primary:Download/Timeline.gpx"))
    }
}
