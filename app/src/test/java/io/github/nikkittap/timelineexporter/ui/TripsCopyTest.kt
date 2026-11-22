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
        // The count says "trip" already: "1 trip", not "Trip — 1 trip".
        assertEquals(null, exportKindLabel(ContentKind.TRIPS))
    }

    @Test
    fun `every trip starts ticked and an untick leaves just that one out`() {
        val visible = listOf(3L, 1L, 2L)
        assertEquals(setOf(1L, 2L, 3L), markedTripKeys(visible, emptySet()))
        assertEquals(setOf(1L, 3L), markedTripKeys(visible, setOf(2L)))
        // Unticked trips outside the date range do not count against those in it.
        assertEquals(setOf(1L, 2L, 3L), markedTripKeys(visible, setOf(9L)))
        // None ticked is none, not "all": step 4 then has nothing to write.
        assertTrue(markedTripKeys(visible, setOf(1L, 2L, 3L)).isEmpty())
    }

    @Test
    fun `track and places headings do not depend on the count`() {
        assertEquals(R.string.step_export_track, exportStepTitle(ContentKind.TRACK, 1))
        assertEquals(R.string.step_export_places, exportStepTitle(ContentKind.PLACES, 1))
        assertEquals(null, exportKindLabel(ContentKind.PLACES))
        assertEquals(R.string.content_track, exportKindLabel(ContentKind.TRACK))
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
            "Trips are detected when you travel more than 50 km from home. Tap a trip to preview it on the map. " +
                "Untick trips to leave them out of the export.",
            en["trips_hint"],
        )
        assertEquals("Marked: %1\$s of %2\$s", en["trips_checked"])
        assertEquals("No trips marked for export", en["trips_checked_zero"])
        assertEquals("Show marked trips on map", en["trip_show_checked_on_map"])
        assertEquals("Export trip", en["step_export_trip"])
        assertEquals("Trip", en["content_trip"])
    }

    @Test
    fun `every locale has the trips strings and a trip-count plural with other`() {
        val keys = listOf("content_trip", "content_trips", "step_export_trip", "step_export_trips", "trips_hint",
            "trip_export_checkbox", "trips_checked", "trips_checked_zero", "trips_select_all", "trip_show_all_on_map", "trip_show_checked_on_map")
        val locales = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists() }!!
        assertEquals(18, locales.size)
        for (dir in locales) {
            val s = strings(dir)
            for (k in keys) assertTrue("${dir.name} lacks $k", !s[k].isNullOrBlank())
            // Both counts, in whatever order the language puts them.
            for (arg in listOf("%1\$s", "%2\$s")) {
                assertTrue("${dir.name} trips_checked lacks $arg", arg in s.getValue("trips_checked"))
            }
            assertTrue("${dir.name} trips_count lacks other", "other" in plurals(dir, "trips_count"))
        }
        assertTrue("one" in plurals(File(res, "values-ru"), "trips_count"))
        assertTrue("few" in plurals(File(res, "values-ru"), "trips_count"))
        assertTrue("many" in plurals(File(res, "values-ru"), "trips_count"))
    }

    @Test
    fun `a point count agrees with its number in every locale`() {
        // Was one string: "Трек — 1 точек", "11 631 точек".
        val locales = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists() }!!
        for (dir in locales) {
            assertTrue("${dir.name} points_count is still a plain string", strings(dir)["points_count"] == null)
            assertTrue("${dir.name} points_count lacks other", "other" in plurals(dir, "points_count"))
        }
        for (lang in listOf("values-ru", "values-uk", "values-pl")) {
            assertEquals(setOf("one", "few", "many", "other"), plurals(File(res, lang), "points_count"))
        }
        assertEquals(setOf("one", "other"), plurals(File(res, "values"), "points_count"))
    }

    @Test
    fun `russian copy is the reviewed wording`() {
        val ru = strings(File(res, "values-ru"))
        assertEquals("Отмечено: %1\$s из %2\$s", ru["trips_checked"])
        assertEquals("Включать ТЦ и аэропорты", ru["places_include_containers"])
        assertEquals("Будет экспортировано", ru["export_writes_label"])
    }
}
