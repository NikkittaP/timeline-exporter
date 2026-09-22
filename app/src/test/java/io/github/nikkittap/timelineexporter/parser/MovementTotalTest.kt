package io.github.nikkittap.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovementTotalTest {
    private val breakdown = listOf(
        MovementStats(MovementGroup.DRIVING, trips = 4, distanceMeters = 120_000.0),
        MovementStats(MovementGroup.WALKING, trips = 10, distanceMeters = 8_500.0),
        MovementStats(MovementGroup.CYCLING, trips = 2, distanceMeters = 14_000.0),
    )

    @Test
    fun `sums only the ticked groups`() {
        val total = breakdown.totalFor(setOf(MovementGroup.WALKING, MovementGroup.CYCLING))!!
        assertEquals(12, total.trips)
        assertEquals(22_500.0, total.distanceMeters, 0.0)
    }

    @Test
    fun `all groups ticked sums everything`() {
        val total = breakdown.totalFor(MovementGroup.entries.toSet())!!
        assertEquals(16, total.trips)
        assertEquals(142_500.0, total.distanceMeters, 0.0)
    }

    @Test
    fun `nothing ticked in the breakdown gives no total`() {
        assertNull(breakdown.totalFor(emptySet()))
        assertNull(breakdown.totalFor(setOf(MovementGroup.FLYING)))
    }
}
