package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.TimeOfDayWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RememberedSettingsTest {

    private val jan1 = Instant.parse("2026-01-01T00:00:00Z")
    private val jan31 = Instant.parse("2026-01-31T23:59:59Z")
    private val mar1 = Instant.parse("2026-03-01T00:00:00Z")
    private val jun30 = Instant.parse("2026-06-30T23:59:59Z")

    @Test
    fun `date range comes back when it overlaps the file`() {
        val s = RememberedSettings(dateRange = jan1..mar1)
        assertEquals(jan1..mar1, s.filterFor(jan31, jun30).dateRange)
    }

    @Test
    fun `date range touching the file at one instant still overlaps`() {
        val s = RememberedSettings(dateRange = jan1..mar1)
        assertEquals(jan1..mar1, s.filterFor(mar1, jun30).dateRange)
    }

    @Test
    fun `date range that misses the file is dropped`() {
        val s = RememberedSettings(dateRange = jan1..jan31)
        assertNull(s.filterFor(mar1, jun30).dateRange)
    }

    @Test
    fun `date range is dropped for a file without points`() {
        val s = RememberedSettings(dateRange = jan1..jan31)
        assertNull(s.filterFor(null, null).dateRange)
    }

    @Test
    fun `the rest of the filter comes back regardless of the dates`() {
        val window = TimeOfDayWindow(7 * 60, 19 * 60)
        val s = RememberedSettings(
            dateRange = jan1..jan31,
            movements = setOf(MovementGroup.WALKING),
            movingOnly = true,
            dropRepeatedPoints = true,
            timeOfDay = window,
        )
        val f = s.filterFor(mar1, jun30)
        assertEquals(setOf(MovementGroup.WALKING), f.movements)
        assertTrue(f.movingOnly)
        assertTrue(f.dropRepeatedPoints)
        assertEquals(window, f.timeOfDay)
    }

    @Test
    fun `an empty movement selection is not restored`() {
        assertNull(RememberedSettings(movements = emptySet()).filterFor(jan1, jun30).movements)
    }

    @Test
    fun `every movement group selected means no movement filter`() {
        val all = MovementGroup.entries.toSet()
        assertNull(RememberedSettings(movements = all).filterFor(jan1, jun30).movements)
    }

    @Test
    fun `defaults restore an empty filter`() {
        assertTrue(RememberedSettings().filterFor(jan1, jun30).isEmpty)
    }

    @Test
    fun `withFilter round-trips through filterFor`() {
        val filter = RememberedSettings(
            dateRange = jan1..mar1,
            movements = setOf(MovementGroup.CYCLING, MovementGroup.WALKING),
            movingOnly = true,
            timeOfDay = TimeOfDayWindow(22 * 60, 6 * 60),
        ).filterFor(jan1, jun30)
        assertEquals(filter, RememberedSettings().withFilter(filter).filterFor(jan1, jun30))
    }
}

class TimelineViewModelSettingsTest {

    private val jan1 = Instant.parse("2026-01-01T00:00:00Z")
    private val jan31 = Instant.parse("2026-01-31T23:59:59Z")

    @Test
    fun `filter changes are saved to the store`() {
        val store = SettingsStore.InMemory()
        val vm = TimelineViewModel(store)
        vm.setDateRange(jan1..jan31)
        vm.setMovingOnly(true)
        vm.setTimeOfDay(TimeOfDayWindow(8 * 60, 18 * 60))

        val saved = store.load()
        assertEquals(jan1..jan31, saved.dateRange)
        assertTrue(saved.movingOnly)
        assertEquals(TimeOfDayWindow(8 * 60, 18 * 60), saved.timeOfDay)
    }

    @Test
    fun `export choices are saved and read back by the next ViewModel`() {
        val store = SettingsStore.InMemory()
        TimelineViewModel(store).apply {
            setIncludeContainers(true)
            setShareInstead(true)
            setColorByDay(true)
        }
        val next = TimelineViewModel(store)
        assertTrue(next.includeContainers.value)
        assertTrue(next.shareInstead.value)
        assertTrue(next.colorByDay.value)
    }

    @Test
    fun `a new ViewModel does not apply the filter before a file is loaded`() {
        val store = SettingsStore.InMemory(RememberedSettings(movingOnly = true))
        assertFalse(TimelineViewModel(store).filter.value.movingOnly)
    }

    @Test
    fun `re-ticking every available group clears a remembered selection naming others`() {
        val available = setOf(MovementGroup.WALKING, MovementGroup.DRIVING)
        val vm = TimelineViewModel()
        // As if restored from a file that also had cycling.
        vm.toggleMovement(MovementGroup.DRIVING, false, available + MovementGroup.CYCLING)
        assertEquals(setOf(MovementGroup.WALKING, MovementGroup.CYCLING), vm.filter.value.movements)

        vm.toggleMovement(MovementGroup.DRIVING, true, available)
        assertNull(vm.filter.value.movements)
    }
}
