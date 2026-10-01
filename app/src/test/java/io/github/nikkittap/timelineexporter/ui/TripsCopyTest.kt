package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.export.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The Trips copy: singular for one trip, and every string in every locale. */
class TripsCopyTest {

    @Test
    fun `step 4 says trip for exactly one and trips otherwise`() {
        assertEquals(R.string.step_export_trip, exportStepTitle(ContentKind.TRIPS, 1))
        assertEquals(R.string.step_export_trips, exportStepTitle(ContentKind.TRIPS, 0))
        assertEquals(R.string.step_export_trips, exportStepTitle(ContentKind.TRIPS, 2))
        assertEquals(R.string.content_trip, exportKindLabel(ContentKind.TRIPS, 1))
        assertEquals(R.string.content_trips, exportKindLabel(ContentKind.TRIPS, 126))
    }

    @Test
    fun `track and places headings do not depend on the count`() {
        assertEquals(R.string.step_export_track, exportStepTitle(ContentKind.TRACK, 1))
        assertEquals(R.string.step_export_places, exportStepTitle(ContentKind.PLACES, 1))
        assertEquals(R.string.content_places, exportKindLabel(ContentKind.PLACES, 1))
    }

    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun strings(dir: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val e = nodes.item(it) as org.w3c.dom.Element
            e.getAttribute("name") to e.textContent
        }
    }

    private fun plurals(dir: File, name: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml"))
        val nodes = doc.getElementsByTagName("plurals")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as org.w3c.dom.Element
            if (e.getAttribute("name") != name) continue
            val items = e.getElementsByTagName("item")
            return (0 until items.length).map { (items.item(it) as org.w3c.dom.Element).getAttribute("quantity") }.toSet()
        }
        return emptySet()
    }

    @Test
    fun `english copy is the reviewed wording`() {
        val en = strings(File(res, "values"))
        assertEquals(
            "Trips are detected when you travel more than 50 km from home. Tap a trip to preview it on the map.",
            en["trips_hint"],
        )
        assertEquals("Use only this trip", en["trip_only_this"])
        assertEquals("Export trip", en["step_export_trip"])
        assertEquals("Trip", en["content_trip"])
    }

    @Test
    fun `every locale has the trips strings and a trip-count plural with other`() {
        val keys = listOf("content_trip", "content_trips", "step_export_trip", "step_export_trips", "trips_hint", "trip_only_this")
        val locales = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists() }!!
        assertEquals(18, locales.size)
        for (dir in locales) {
            val s = strings(dir)
            for (k in keys) assertTrue("${dir.name} lacks $k", !s[k].isNullOrBlank())
            assertTrue("${dir.name} trips_count lacks other", "other" in plurals(dir, "trips_count"))
        }
        assertTrue("one" in plurals(File(res, "values-ru"), "trips_count"))
        assertTrue("few" in plurals(File(res, "values-ru"), "trips_count"))
        assertTrue("many" in plurals(File(res, "values-ru"), "trips_count"))
    }
}
