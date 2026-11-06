package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

class MapImageLayoutTest {
    private val spec = MapImageSpec.CleanPortrait

    private fun p(lat: Double, lon: Double, time: String = "2026-05-12T10:00:00Z") =
        PathPoint(Instant.parse(time).toKotlinInstant(), lat, lon, 0)

    /** Every point of [points] lands inside the area the route may use. */
    private fun assertInsideUsableArea(points: List<PathPoint>, showStats: Boolean, spec: MapImageSpec = this.spec) {
        val camera = fitMapImageCamera(trackExtent(points)!!, spec, showStats)
        val bottomLimit = spec.heightPx - spec.reservedBottomPx(showStats) - spec.bottomGapPx
        for (point in points) {
            val (x, y) = projectToImage(camera, spec, point.latitude, point.longitude)
            assertTrue("x=$x for $point", x >= spec.sidePaddingPx - 1 && x <= spec.widthPx - spec.sidePaddingPx + 1)
            assertTrue("y=$y for $point", y >= spec.topPaddingPx - 1 && y <= bottomLimit + 1)
        }
    }

    @Test fun `output is exactly 1080 by 1920 and the logical size multiplies back to it`() {
        assertEquals(1080, spec.widthPx)
        assertEquals(1920, spec.heightPx)
        assertEquals(1080f, spec.logicalWidth * spec.pixelRatio, 0.01f)
        assertEquals(1920f, spec.logicalHeight * spec.pixelRatio, 0.01f)
    }

    @Test fun `a city route fits above the statistics and fills one dimension`() {
        val route = listOf(p(52.37, 4.88), p(52.40, 4.90), p(52.35, 4.95), p(52.38, 4.85))
        assertInsideUsableArea(route, showStats = true)
        assertInsideUsableArea(route, showStats = false)
        val camera = fitMapImageCamera(trackExtent(route)!!, spec, true)
        val xs = route.map { projectToImage(camera, spec, it.latitude, it.longitude).first }
        val ys = route.map { projectToImage(camera, spec, it.latitude, it.longitude).second }
        val usableW = spec.widthPx - 2 * spec.sidePaddingPx
        val usableH = spec.heightPx - spec.reservedBottomPx(true) - spec.bottomGapPx - spec.topPaddingPx
        // Tight in at least one direction: fitted, not merely contained.
        assertTrue(xs.max() - xs.min() > usableW - 2 || ys.max() - ys.min() > usableH - 2)
    }

    @Test fun `hiding the statistics gives the route more room`() {
        val route = listOf(p(40.0, -3.0), p(41.5, -3.2))
        val withStats = fitMapImageCamera(trackExtent(route)!!, spec, true)
        val without = fitMapImageCamera(trackExtent(route)!!, spec, false)
        assertTrue(without.zoom > withStats.zoom)
    }

    @Test fun `a single point gets a fixed street-level zoom`() {
        val camera = fitMapImageCamera(trackExtent(listOf(p(48.85, 2.35)))!!, spec, true)
        assertEquals(MAP_IMAGE_POINT_ZOOM, camera.zoom, 0.0)
        assertInsideUsableArea(listOf(p(48.85, 2.35)), showStats = true)
    }

    @Test fun `a tiny route does not zoom in past street level`() {
        val camera = fitMapImageCamera(trackExtent(listOf(p(48.85, 2.35), p(48.85001, 2.35001)))!!, spec, true)
        assertEquals(MAP_IMAGE_MAX_ZOOM, camera.zoom, 0.0)
    }

    @Test fun `a route across the date line is framed the short way round`() {
        val route = listOf(p(-17.7, 178.0), p(-17.9, 179.9), p(-18.1, -179.5), p(-18.0, -178.4))
        val extent = trackExtent(route)!!
        assertTrue("span ${extent.lonSpan}", extent.lonSpan < 5.0)
        assertInsideUsableArea(route, showStats = true)
    }

    @Test fun `a route around half the world still fits`() {
        val route = listOf(p(55.75, 37.6), p(35.68, 139.7), p(40.7, -74.0), p(-33.9, 151.2))
        assertInsideUsableArea(route, showStats = true)
    }

    @Test fun `stats count days, skip jumps and only show places that exist`() {
        val a = listOf(
            p(52.0, 4.0, "2026-05-12T08:00:00Z"),
            p(52.009, 4.0, "2026-05-12T09:00:00Z"), // ~1 km
        )
        val b = listOf(
            p(48.0, 2.0, "2026-05-23T08:00:00Z"), // a new journey hundreds of km away
            p(48.0, 2.0135, "2026-05-23T09:00:00Z"), // ~1 km
            p(40.0, 2.0, "2026-05-23T12:00:00Z"), // a flight: a gap, not distance
        )
        val stats = mapImageStats(a + b, listOf(a, b), ZoneOffset.UTC, placeCount = 0)!!
        assertEquals(LocalDate.parse("2026-05-12"), stats.firstDate)
        assertEquals(LocalDate.parse("2026-05-23"), stats.lastDate)
        assertEquals(2, stats.dayCount)
        assertEquals(2000.0, stats.distanceMeters, 30.0)
        assertNull(stats.placeCount)
        assertNull(mapImageStats(emptyList(), emptyList(), ZoneOffset.UTC, 3))
    }

    /**
     * Regression: the first sample of the test takeout, 21:58 at +02:00 on
     * 9 June, recorded in Tashkent (UTC+5). Step 2 and the file card said
     * 9 June; the image and its filename said 10 June, because they read the
     * day in the sample's own offset rather than the phone's zone.
     */
    @Test fun `dates follow the phone's zone like the rest of the app, not each sample's offset`() {
        val tashkentEvening = PathPoint(Instant.parse("2025-06-09T19:58:00Z").toKotlinInstant(), 41.28, 69.24, tzOffsetMinutes = 300)
        val later = PathPoint(Instant.parse("2026-05-21T10:00:00Z").toKotlinInstant(), 52.0, 4.0, tzOffsetMinutes = 120)
        val points = listOf(tashkentEvening, later)
        for (zoneId in listOf("UTC", "Europe/Berlin", "Europe/Moscow", "America/New_York")) {
            val zone = ZoneId.of(zoneId)
            val stats = mapImageStats(points, listOf(points), zone, null)!!
            // The same conversion step 2's period and every suggested filename use.
            assertEquals(zoneId, tashkentEvening.timeUtc.toJavaInstant().atZone(zone).toLocalDate(), stats.firstDate)
            assertEquals(zoneId, later.timeUtc.toJavaInstant().atZone(zone).toLocalDate(), stats.lastDate)
        }
        val inUtc = mapImageStats(points, listOf(points), ZoneOffset.UTC, null)!!
        assertEquals(LocalDate.parse("2025-06-09"), inUtc.firstDate)
        assertEquals("timeline-route-2025-06-09_2026-05-21.png", mapImageFilename(inUtc))
        // A phone actually set to Tashkent time sees the 10th - on screen and in the image alike.
        val inTashkent = mapImageStats(points, listOf(points), ZoneId.of("Asia/Tashkent"), null)!!
        assertEquals(LocalDate.parse("2025-06-10"), inTashkent.firstDate)
    }

    @Test fun `the day count is the app's own days figure, not a second definition`() {
        // A line from 23:30 to 00:30 Berlin time, recorded in Berlin: dates follow the
        // phone, but the count is drawnTrackDays - the file card's "days" - wherever the phone is.
        val a = PathPoint(Instant.parse("2026-05-12T21:30:00Z").toKotlinInstant(), 52.5, 13.4, tzOffsetMinutes = 120)
        val b = PathPoint(Instant.parse("2026-05-12T22:30:00Z").toKotlinInstant(), 52.51, 13.41, tzOffsetMinutes = 120)
        val c = PathPoint(Instant.parse("2026-05-14T10:00:00Z").toKotlinInstant(), 52.52, 13.42, tzOffsetMinutes = 120)
        val points = listOf(a, b, c)
        for (zone in listOf(ZoneOffset.UTC, ZoneId.of("Europe/Berlin"), ZoneId.of("Asia/Tashkent"))) {
            val stats = mapImageStats(points, listOf(points), zone, null)!!
            assertEquals(zone.id, drawnTrackDays(points, zone).size, stats.dayCount)
            assertEquals(zone.id, a.timeUtc.toJavaInstant().atZone(zone).toLocalDate(), stats.firstDate)
            assertEquals(zone.id, c.timeUtc.toJavaInstant().atZone(zone).toLocalDate(), stats.lastDate)
        }
        // 12 May (the 23:30 edge) and 13 May (the 00:30 -> 14 May edge), as recorded.
        assertEquals(2, mapImageStats(points, listOf(points), ZoneOffset.UTC, null)!!.dayCount)
    }

    @Test fun `attribution always credits OpenStreetMap contributors`() {
        assertEquals(
            "OpenFreeMap © OpenMapTiles © OpenStreetMap contributors",
            withOsmCredit("OpenFreeMap © OpenMapTiles Data from OpenStreetMap"),
        )
        assertEquals("© OpenStreetMap contributors", withOsmCredit("© OpenStreetMap contributors"))
        assertEquals("X © OpenStreetMap contributors", withOsmCredit("X"))
    }

    @Test fun `filename carries the date range, or one date for one day`() {
        val range = MapImageStats(LocalDate.parse("2026-05-12"), LocalDate.parse("2026-05-23"), 4, 1.0, null)
        assertEquals("timeline-route-2026-05-12_2026-05-23.png", mapImageFilename(range))
        assertEquals("timeline-route-2026-05-12.png", mapImageFilename(range.copy(lastDate = range.firstDate)))
        assertEquals("timeline-route.png", mapImageFilename(null))
    }

    private val allSpecs = MapImageAspect.entries.flatMap { a -> MapImageResolution.entries.map { MapImageSpec(a, it) } }

    @Test fun `every shape has its exact standard size and High doubles both sides`() {
        val expected = mapOf(
            MapImageAspect.Portrait to (1080 to 1920),
            MapImageAspect.SocialPost to (1080 to 1350),
            MapImageAspect.Square to (1600 to 1600),
            MapImageAspect.Landscape to (1920 to 1080),
        )
        for ((aspect, size) in expected) {
            val standard = MapImageSpec(aspect)
            val high = MapImageSpec(aspect, MapImageResolution.High)
            assertEquals(size, standard.widthPx to standard.heightPx)
            assertEquals(size.first * 2 to size.second * 2, high.widthPx to high.heightPx)
        }
    }

    @Test fun `the logical size multiplies back to the pixel size for every shape and resolution`() {
        for (s in allSpecs) {
            assertEquals("$s", s.widthPx.toFloat(), s.logicalWidth * s.pixelRatio, 2f)
            assertEquals("$s", s.heightPx.toFloat(), s.logicalHeight * s.pixelRatio, 2f)
            assertEquals("$s", MapImageSpec.LOGICAL_SHORT_SIDE, minOf(s.logicalWidth, s.logicalHeight))
        }
    }

    @Test fun `High frames the same map as Standard`() {
        val route = listOf(p(52.37, 4.88), p(52.40, 4.90), p(52.35, 4.95))
        for (aspect in MapImageAspect.entries) {
            val standard = MapImageSpec(aspect)
            val high = MapImageSpec(aspect, MapImageResolution.High)
            assertEquals(standard.logicalWidth, high.logicalWidth)
            assertEquals(standard.logicalHeight, high.logicalHeight)
            val a = fitMapImageCamera(trackExtent(route)!!, standard, true)
            val b = fitMapImageCamera(trackExtent(route)!!, high, true)
            assertEquals(a.zoom, b.zoom, 0.01)
        }
    }

    @Test fun `city, continental, date-line and single-point routes fit every shape`() {
        val routes = listOf(
            listOf(p(52.37, 4.88), p(52.40, 4.90), p(52.35, 4.95), p(52.38, 4.85)),
            listOf(p(55.75, 37.6), p(38.7, -9.1), p(64.1, -21.9), p(37.98, 23.7)),
            listOf(p(-17.7, 178.0), p(-17.9, 179.9), p(-18.1, -179.5), p(-18.0, -178.4)),
            listOf(p(48.85, 2.35)),
        )
        for (s in allSpecs) for (route in routes) {
            assertInsideUsableArea(route, showStats = true, spec = s)
            assertInsideUsableArea(route, showStats = false, spec = s)
        }
    }

    @Test fun `the route area clears the branding, the stats card and the attribution`() {
        for (s in allSpecs) for (rtl in listOf(false, true)) {
            val brand = s.brandBox(rtl)
            val stats = s.statsBox(rtl)
            // A marker on the topmost or lowest point still stays clear.
            assertTrue("$s", s.topPaddingPx - s.markerRadiusPx >= brand.bottom)
            val routeBottom = s.heightPx - s.reservedBottomPx(true) - s.bottomGapPx
            assertTrue("$s", routeBottom + s.markerRadiusPx <= stats.top)
            val routeBottomBare = s.heightPx - s.reservedBottomPx(false) - s.bottomGapPx
            assertTrue("$s", routeBottomBare + s.markerRadiusPx <= s.heightPx - s.attributionBandPx)
            assertFalse("$s", brand.intersects(stats))
            assertTrue("$s brand inside", brand.left >= 0 && brand.right <= s.widthPx)
            assertTrue("$s stats inside", stats.left >= 0 && stats.right <= s.widthPx && stats.bottom <= s.heightPx)
        }
    }

    @Test fun `the attribution never overlaps the branding or the stats card`() {
        for (s in allSpecs) for (rtl in listOf(false, true)) for (showStats in listOf(true, false)) {
            val pill = s.u(40f)
            val (bottom, maxWidth) = s.attributionSlot(showStats, pill)
            assertTrue("$s", maxWidth > 0)
            // Anchored at the bottom-end corner, at its longest.
            val attribution = if (rtl) ImageBox(s.marginPx, bottom - pill, s.marginPx + maxWidth, bottom)
            else ImageBox(s.widthPx - s.marginPx - maxWidth, bottom - pill, s.widthPx - s.marginPx, bottom)
            assertFalse("$s rtl=$rtl", attribution.intersects(s.brandBox(rtl)))
            if (showStats) assertFalse("$s rtl=$rtl", attribution.intersects(s.statsBox(rtl)))
            assertTrue("$s", attribution.bottom <= s.heightPx)
        }
    }

    @Test fun `the attribution keeps a safe margin from the bottom edge and clears the card`() {
        for (s in allSpecs) for (showStats in listOf(true, false)) {
            // The pill at its largest type (25 units plus padding), a little generously.
            val pill = s.u(48f)
            val (bottom, _) = s.attributionSlot(showStats, pill)
            assertTrue("$s bottom=$bottom", s.heightPx - bottom >= s.u(24f) - 0.01f)
            assertTrue("$s bottom margin at least 24 px", s.heightPx - bottom >= 24f)
            if (showStats && !s.compactStats) {
                assertTrue("$s pill top under the card", bottom - pill >= s.statsBox(false).bottom)
            }
        }
    }

    @Test fun `only landscape uses a compact card, narrower than half the width`() {
        for (aspect in MapImageAspect.entries) {
            val s = MapImageSpec(aspect)
            assertEquals(aspect == MapImageAspect.Landscape, s.compactStats)
            if (s.compactStats) assertTrue(s.statsMaxWidthPx <= s.widthPx / 2f)
            else assertEquals(s.widthPx - 2 * s.marginPx, s.statsMaxWidthPx, 0.01f)
        }
        // A shorter picture gets a smaller card than the portrait original.
        assertTrue(MapImageSpec(MapImageAspect.SocialPost).statsCardHeightPx < spec.statsCardHeightPx)
    }

    @Test fun `overlays scale with the resolution so High is drawn, not upscaled`() {
        for (aspect in MapImageAspect.entries) {
            val standard = MapImageSpec(aspect)
            val high = MapImageSpec(aspect, MapImageResolution.High)
            assertEquals(standard.markerRadiusPx * 2, high.markerRadiusPx, 0.01f)
            assertEquals(standard.brandHeightPx * 2, high.brandHeightPx, 0.01f)
            assertEquals(standard.statsCardHeightPx * 2, high.statsCardHeightPx, 0.01f)
        }
    }

    @Test fun `High needs a big enough GPU and enough free memory`() {
        val high = MapImageSpec(MapImageAspect.Landscape, MapImageResolution.High) // 3840 x 2160
        val plenty = 2L * 1024 * 1024 * 1024
        assertEquals(HighResolutionSupport.Supported, resolutionSupport(high, 4096, plenty, false))
        assertEquals(HighResolutionSupport.ExceedsGpu, resolutionSupport(high, 2048, plenty, false))
        assertEquals(HighResolutionSupport.ExceedsGpu, resolutionSupport(high, 0, plenty, false))
        assertEquals(HighResolutionSupport.InsufficientMemory, resolutionSupport(high, 8192, 100L * 1024 * 1024, false))
        assertEquals(HighResolutionSupport.InsufficientMemory, resolutionSupport(high, 8192, plenty, true))
        // Square High is 3200 px: fine on a 4096 GPU, not on a 3000 one.
        val square = MapImageSpec(MapImageAspect.Square, MapImageResolution.High)
        assertEquals(HighResolutionSupport.Supported, resolutionSupport(square, 4096, plenty, false))
        assertEquals(HighResolutionSupport.ExceedsGpu, resolutionSupport(square, 3000, plenty, false))
    }

    @Test fun `filename extension and MIME type follow the format`() {
        val range = MapImageStats(LocalDate.parse("2026-05-12"), LocalDate.parse("2026-05-23"), 4, 1.0, null)
        assertEquals("timeline-route-2026-05-12_2026-05-23.jpg", mapImageFilename(range, MapImageFormat.Jpeg))
        assertEquals("timeline-route.jpg", mapImageFilename(null, MapImageFormat.Jpeg))
        assertEquals("image/jpeg", MapImageFormat.Jpeg.mimeType)
        assertEquals("image/png", MapImageFormat.Png.mimeType)
    }

    @Test fun `old shared images are pruned, recent ones kept for apps still reading them`() {
        val root = kotlin.io.path.createTempDirectory("share").toFile()
        try {
            val now = 10_000_000_000L
            val old = File(root, "map-image-${now - 2 * 3_600_000}").apply { mkdirs() }
            File(old, "a.png").writeText("x")
            val recent = File(root, "map-image-${now - 60_000}").apply { mkdirs() }
            File(recent, "b.png").writeText("x")
            val gpx = File(root, "track.gpx").apply { writeText("x") }
            pruneSharedImages(root, now)
            assertFalse(old.exists())
            assertTrue(recent.exists())
            assertTrue(gpx.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
