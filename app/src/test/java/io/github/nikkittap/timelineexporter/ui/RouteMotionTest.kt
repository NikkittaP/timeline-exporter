package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import kotlin.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RouteMotionTest {
    private fun p(lat: Double, lon: Double) = PathPoint(Instant.fromEpochSeconds(0), lat, lon)

    @Test fun emptyAndSinglePointHaveNoFlight() {
        assertNull(RouteMotion.prepare(emptyList()))
        assertNull(RouteMotion.prepare(listOf(listOf(p(0.0, 0.0)))))
    }
    @Test fun crossesAntimeridianByShortArc() {
        val route = RouteMotion.prepare(listOf(listOf(p(0.0, 179.9), p(0.0, -179.9))))!!
        assertTrue(kotlin.math.abs(route.position(0.5)!!.longitude) > 179.9)
    }
    @Test fun neverBridgesTripsOrGaps() {
        val route = RouteMotion.prepare(listOf(
            listOf(p(0.0, 0.0), p(0.0, 0.1), p(0.0, 50.0), p(0.0, 50.1)),
            listOf(p(0.0, 50.2), p(0.0, 50.3)),
        ))!!
        assertTrue(route.position(0.1)!!.longitude < 1.0)
        assertTrue(route.position(0.7)!!.longitude > 49.0)
        assertNotEquals(route.position(0.4)!!.run, route.position(0.9)!!.run)
    }
    @Test fun invalidCoordinatesBreakTheRun() {
        val route = RouteMotion.prepare(listOf(listOf(p(0.0, 0.0), p(0.0, 0.1), p(Double.NaN, 0.0), p(0.0, 0.2), p(0.0, 0.3))))!!
        assertNotEquals(route.position(0.1)!!.run, route.position(0.9)!!.run)
    }
    @Test fun largeHistoryFocusesOneContinuousRun() {
        val longRun = (0..40).map { p(0.0, it * 0.5) }
        val shortRun = listOf(p(30.0, 0.0), p(30.0, 0.2))
        val route = RouteMotion.prepare(listOf(longRun, shortRun))!!
        assertTrue(route.focusPoints!!.size < longRun.size)
        assertTrue(route.position(1.0)!!.longitude in 8.0..10.0)
    }
    @Test fun fadesAtDisconnectedRunBoundary() {
        val route = RouteMotion.prepare(listOf(listOf(p(0.0, 0.0), p(0.0, 0.1)), listOf(p(0.0, 10.0), p(0.0, 10.1))))!!
        assertTrue(route.position(0.5)!!.alpha < 0.01f)
        assertTrue(route.position(0.25)!!.alpha > 0.99f)
    }
    @Test fun preparationCanStopOnCancellation() {
        val points = (0..2048).map { p(0.0, it * 0.0001) }
        assertNull(RouteMotion.prepare(listOf(points)) { false })
    }
}
