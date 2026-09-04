package io.github.nikkittap.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The window arithmetic on its own: wrapping through midnight, spans that
 * touch a window without starting inside it, and whose clock a time is read
 * on.
 *
 * Wrapping is the reason this is a type rather than two Ints compared inline.
 * "Nights" is 22:00 to 06:00, which is not an interval on the number line, and
 * every one of the three predicates below gets it wrong if written naively.
 */
class TimeOfDayWindowTest {

    private fun hm(hour: Int, minute: Int = 0) = hour * 60 + minute

    // ---------- contains ----------

    @Test
    fun `a plain window keeps its own hours and nothing else`() {
        val morning = TimeOfDayWindow(hm(7), hm(12))
        assertFalse(morning.contains(hm(6, 59)))
        assertTrue(morning.contains(hm(7)))
        assertTrue(morning.contains(hm(11, 59)))
        // Half-open: the end is the first minute NOT kept, so 07:00-12:00 and
        // 12:00-17:00 tile the day instead of both claiming noon.
        assertFalse(morning.contains(hm(12)))
    }

    @Test
    fun `a window through midnight keeps both sides of it`() {
        val night = TimeOfDayWindow(hm(22), hm(6))
        assertTrue(night.contains(hm(22)))
        assertTrue(night.contains(hm(23, 59)))
        assertTrue(night.contains(hm(0)))
        assertTrue(night.contains(hm(5, 59)))
        assertFalse(night.contains(hm(6)))
        assertFalse(night.contains(hm(12)))
    }

    @Test
    fun `equal ends mean the whole day, not an empty window`() {
        val whole = TimeOfDayWindow(hm(9), hm(9))
        assertTrue(whole.isWholeDay)
        assertTrue(whole.contains(hm(3)))
        assertTrue(whole.contains(hm(21)))
        assertTrue(TimeOfDayWindow.WholeDay.isWholeDay)
    }

    @Test
    fun `length counts the wrap`() {
        assertEquals(5 * 60, TimeOfDayWindow(hm(7), hm(12)).lengthMinutes)
        assertEquals(8 * 60, TimeOfDayWindow(hm(22), hm(6)).lengthMinutes)
        assertEquals(MINUTES_PER_DAY, TimeOfDayWindow.WholeDay.lengthMinutes)
    }

    // ---------- overlaps ----------

    @Test
    fun `a visit spanning the window is kept even though it starts before it`() {
        val lunch = TimeOfDayWindow(hm(12), hm(13))
        // At a desk 09:00-18:00. The visit never starts inside the window,
        // which is exactly the case testing only its start would miss.
        assertTrue(lunch.overlaps(hm(9), 9 * 60L))
    }

    @Test
    fun `a visit entirely outside the window is dropped`() {
        val lunch = TimeOfDayWindow(hm(12), hm(13))
        assertFalse(lunch.overlaps(hm(14), 60L))
        assertFalse(lunch.overlaps(hm(9), 2 * 60L))
    }

    @Test
    fun `a visit that ends exactly when the window opens does not count`() {
        val afternoon = TimeOfDayWindow(hm(12), hm(18))
        assertFalse(afternoon.overlaps(hm(9), 3 * 60L))
    }

    @Test
    fun `a visit longer than a day touches every window`() {
        val lunch = TimeOfDayWindow(hm(12), hm(13))
        assertTrue(lunch.overlaps(hm(14), 30L * 60))
    }

    @Test
    fun `a stay overnight is found by a night window and by a morning one`() {
        val night = TimeOfDayWindow(hm(22), hm(6))
        val morning = TimeOfDayWindow(hm(7), hm(12))
        // Home from 20:00 to 08:00 the next day.
        assertTrue(night.overlaps(hm(20), 12 * 60L))
        assertTrue(morning.overlaps(hm(20), 12 * 60L))
        // Out from 12:00 to 20:00 the same day: neither.
        assertFalse(night.overlaps(hm(12), 8 * 60L))
        assertFalse(morning.overlaps(hm(12), 8 * 60L))
    }

    @Test
    fun `an instant is inside a window only where contains says so`() {
        val night = TimeOfDayWindow(hm(22), hm(6))
        assertTrue(night.overlaps(hm(23), 0L))
        assertFalse(night.overlaps(hm(9), 0L))
    }

    // ---------- which clock ----------

    @Test
    fun `a point is read on its own clock rather than the phone one`() {
        // 04:00 UTC. In Tashkent (+05:00) that is 09:00, a morning; the
        // exporting phone in Stockholm (+02:00) would call it 06:00.
        //
        // This is the bug 1.6.3 fixed showing up one layer higher: a user
        // asking for their mornings abroad has to get the mornings they lived.
        val instant = Instant.parse("2025-06-09T04:00:00Z")
        val stockholm = ZoneId.of("Europe/Stockholm")

        assertEquals(9 * 60, localMinuteOfDay(instant, 300, stockholm))
        assertEquals(6 * 60, localMinuteOfDay(instant, null, stockholm))

        val morning = TimeOfDayWindow(hm(7), hm(12))
        assertTrue(morning.contains(localMinuteOfDay(instant, 300, stockholm)))
        assertFalse(morning.contains(localMinuteOfDay(instant, null, stockholm)))
    }

    @Test
    fun `the fallback zone is applied at the point own date, not today`() {
        // Stockholm is +01:00 in January and +02:00 in July. A window filter
        // over a year would be an hour out for half of it if the offset were
        // taken once.
        val stockholm = ZoneId.of("Europe/Stockholm")
        val winter = Instant.parse("2025-01-15T10:00:00Z")
        val summer = Instant.parse("2025-07-15T10:00:00Z")
        assertEquals(11 * 60, localMinuteOfDay(winter, null, stockholm))
        assertEquals(12 * 60, localMinuteOfDay(summer, null, stockholm))
    }

    @Test
    fun `a point before midnight UTC in a western zone lands on the previous day`() {
        // 01:00 UTC at -05:00 is 20:00 the evening before. Negative minutes
        // have to wrap rather than go negative.
        val instant = Instant.parse("2025-06-09T01:00:00Z")
        assertEquals(20 * 60, localMinuteOfDay(instant, -300, ZoneId.of("UTC")))
    }
}
