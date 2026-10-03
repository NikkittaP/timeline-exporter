package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The ballot's two copies of each option: the label the user reads and the
 * English line the report carries. FEEDBACK.md matches incoming reports to
 * its tally by string equality, so the two must not drift apart again, and a
 * line dropped from the ballot must be dropped everywhere.
 */
class FeedbackBallotTest {

    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun ballot(dir: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as org.w3c.dom.Element }
            .filter { it.getAttribute("name").startsWith("feedback_option_") }
            .associate { it.getAttribute("name") to it.textContent.replace("\'", "'") }
    }

    private fun resName(id: Int): String =
        R.string::class.java.fields.first { it.getInt(null) == id }.name

    @Test
    fun `each option mails its English label word for word`() {
        val en = ballot(File(res, "values"))
        for (option in FEEDBACK_OPTIONS) {
            assertEquals(en[resName(option.labelRes)], option.outgoing)
        }
        // And no label is left over without a checkbox.
        assertEquals(en.keys, FEEDBACK_OPTIONS.map { resName(it.labelRes) }.toSet())
    }

    @Test
    fun `every locale has exactly the options on the ballot`() {
        val keys = ballot(File(res, "values")).keys
        val locales = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists() }!!
        assertEquals(18, locales.size)
        for (dir in locales) {
            val labels = ballot(dir)
            assertEquals(dir.name, keys, labels.keys)
            for ((k, v) in labels) assertTrue("${dir.name} $k is blank", v.isNotBlank())
        }
    }

    @Test
    fun `what 1_9_0 ships is off the ballot`() {
        val keys = ballot(File(res, "values")).keys
        for (shipped in listOf("trip_list", "track_per_trip", "remember_settings")) {
            assertFalse(shipped, "feedback_option_$shipped" in keys)
        }
    }
}
