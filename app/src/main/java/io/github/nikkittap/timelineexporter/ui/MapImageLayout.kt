package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.toJavaInstant

/**
 * The pure half of "Export map as PNG": what the picture is, where the camera
 * goes, and what the numbers on it say. Nothing here touches MapLibre or a
 * Canvas, so all of it is checked by plain unit tests; the drawing lives in
 * MapImageRenderer.kt and the screen in MapImageExport.kt.
 */

/** The picture's shape. Pixel sizes are the Standard resolution; High doubles both. */
internal enum class MapImageAspect(val widthPx: Int, val heightPx: Int, val layout: AspectLayout) {
    /** The original Clean Map design, which every other shape is measured against. */
    Portrait(1080, 1920, AspectLayout(statsScale = 1f, compactStats = false, brandScale = 1f, sidePadding = 84f)),
    /** 4:5 leaves less height for the map, so the card is smaller. */
    SocialPost(1080, 1350, AspectLayout(statsScale = 0.86f, compactStats = false, brandScale = 0.94f, sidePadding = 76f)),
    Square(1600, 1600, AspectLayout(statsScale = 0.9f, compactStats = false, brandScale = 0.94f, sidePadding = 80f)),
    /**
     * Height is what a landscape image is short of, so the card is a compact
     * one in the bottom-start corner instead of a band across the width, with
     * the attribution beside it rather than under it.
     */
    Landscape(1920, 1080, AspectLayout(statsScale = 0.84f, compactStats = true, brandScale = 0.88f, sidePadding = 96f)),
}

/** How one [MapImageAspect] arranges its overlays, in design units (see [MapImageSpec.unit]). */
internal data class AspectLayout(
    /** Statistics card height and type, relative to the portrait card. */
    val statsScale: Float,
    /** A content-sized card in the bottom-start corner rather than a full-width band. */
    val compactStats: Boolean,
    /** Branding size, relative to the portrait one. */
    val brandScale: Float,
    /** Free space kept left and right of the route. */
    val sidePadding: Float,
)

internal enum class MapImageResolution(val factor: Int) { Standard(1), High(2) }

/** The encodings offered. The picture is the same bitmap for both; only the file differs. */
internal enum class MapImageFormat(val mimeType: String, val extension: String, val label: String) {
    Png("image/png", "png", "PNG"),
    Jpeg("image/jpeg", "jpg", "JPEG"),
    ;

    companion object {
        const val JPEG_QUALITY = 93
    }
}

/** An axis-aligned box in output pixels. */
internal data class ImageBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun intersects(other: ImageBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * One output image: its pixel size and where everything on it goes.
 *
 * Every size here is a design unit times [unit]. A design unit is one pixel of
 * the original 1080 px wide portrait, and [unit] grows with the image's
 * *shorter* side, so a marker, a line of type or the branding covers the same
 * share of the picture whatever its shape and resolution, and is drawn at
 * that size rather than scaled up from a small bitmap.
 *
 * MapLibre renders at [logicalWidth] x [logicalHeight] with [pixelRatio]. The
 * shorter logical side is always [LOGICAL_SHORT_SIDE], about the width of a
 * large phone, so map labels and route lines are the size people are used to
 * reading, and High is the same framing as Standard with twice the pixels.
 */
internal data class MapImageSpec(
    val aspect: MapImageAspect,
    val resolution: MapImageResolution = MapImageResolution.Standard,
) {
    val widthPx: Int get() = aspect.widthPx * resolution.factor
    val heightPx: Int get() = aspect.heightPx * resolution.factor
    private val layout: AspectLayout get() = aspect.layout

    /** Output pixels per design unit. */
    val unit: Float get() = min(widthPx, heightPx) / DESIGN_SHORT_SIDE
    val pixelRatio: Float get() = min(widthPx, heightPx) / LOGICAL_SHORT_SIDE.toFloat()
    val logicalWidth: Int get() = (widthPx / pixelRatio).roundToInt()
    val logicalHeight: Int get() = (heightPx / pixelRatio).roundToInt()

    fun u(designUnits: Float): Float = designUnits * unit

    /** Distance of every overlay from the image edge. */
    val marginPx: Float get() = u(40f)

    val brandScale: Float get() = layout.brandScale
    val brandHeightPx: Float get() = u(72f * brandScale)
    /** Widest the branding may get; it is usually much narrower. */
    val brandMaxWidthPx: Float get() = u(460f * brandScale)

    val markerRadiusPx: Float get() = u(30f)

    val statsScale: Float get() = layout.statsScale
    val compactStats: Boolean get() = layout.compactStats
    val statsCardHeightPx: Float get() = u(248f * statsScale)
    /** The strip under a full-width card that holds the attribution. */
    val attributionBandPx: Float get() = u(84f)
    /**
     * Fixed clearance between the attribution and the bottom edge: wide
     * enough that a gallery's rounded corners or a chat client's crop does not
     * clip the credit, and a fixed figure rather than whatever the band leaves
     * once the pill is centred in it.
     */
    val attributionBottomMarginPx: Float get() = u(24f)

    /** Free space kept around the route inside the map area. */
    val sidePaddingPx: Int get() = u(layout.sidePadding).roundToInt()
    /** Below the branding, with room for a marker on the topmost point. */
    val topPaddingPx: Int get() = (marginPx + brandHeightPx + u(60f)).roundToInt()
    /** Gap between the route and whatever is reserved at the bottom. */
    val bottomGapPx: Int get() = u(64f).roundToInt()

    /** Output pixels at the bottom the route must stay out of. */
    fun reservedBottomPx(showStats: Boolean): Int = when {
        !showStats -> attributionBandPx
        compactStats -> marginPx + statsCardHeightPx + u(22f)
        else -> attributionBandPx + statsCardHeightPx + u(22f)
    }.roundToInt()

    /** The branding at [width], in the top-start corner. */
    fun brandBox(rtl: Boolean, width: Float = brandMaxWidthPx): ImageBox {
        val top = marginPx
        return if (rtl) ImageBox(widthPx - marginPx - width, top, widthPx - marginPx, top + brandHeightPx)
        else ImageBox(marginPx, top, marginPx + width, top + brandHeightPx)
    }

    /** Widest the statistics card may get. A compact card shrinks to its content. */
    val statsMaxWidthPx: Float
        get() = if (compactStats) widthPx * COMPACT_STATS_SHARE else widthPx - marginPx * 2

    /** The statistics card at [width] (full width unless compact), anchored bottom-start. */
    fun statsBox(rtl: Boolean, width: Float = statsMaxWidthPx): ImageBox {
        val bottom = if (compactStats) heightPx - marginPx else heightPx - attributionBandPx
        val top = bottom - statsCardHeightPx
        return if (rtl) ImageBox(widthPx - marginPx - width, top, widthPx - marginPx, bottom)
        else ImageBox(marginPx, top, marginPx + width, bottom)
    }

    /**
     * Where the attribution may go, at the bottom-end: level with a compact
     * card's lower edge, or in its own band at the foot of the image - centred
     * there, but never closer to the edge than [attributionBottomMarginPx].
     * Returns the pill's lower edge and the longest it may be.
     */
    fun attributionSlot(showStats: Boolean, pillHeight: Float): Pair<Float, Float> {
        val besideCard = compactStats && showStats
        val maxWidth = if (besideCard) widthPx - marginPx * 2 - statsMaxWidthPx - u(24f) else widthPx - marginPx * 2
        val bottom = if (besideCard) heightPx - marginPx
        else min(heightPx - (attributionBandPx - pillHeight) / 2, heightPx - attributionBottomMarginPx)
        return bottom to maxWidth
    }

    companion object {
        /** The design every size is drawn from: the original portrait's width. */
        const val DESIGN_SHORT_SIDE = 1080f
        const val LOGICAL_SHORT_SIDE = 432

        /** A compact card never takes more than this share of the width. */
        const val COMPACT_STATS_SHARE = 0.48f

        val CleanPortrait = MapImageSpec(MapImageAspect.Portrait)
    }
}

/** Whether the High resolution of one shape can be rendered on this device. */
internal enum class HighResolutionSupport { Supported, ExceedsGpu, InsufficientMemory }

/** Bitmaps alive at once at the peak of a render: GL readback, the snapshot, our copy, the encoder's buffer. */
private const val PEAK_BITMAP_COPIES = 4L

/** Left free for the rest of the app and the system while the image renders. */
private const val MEMORY_HEADROOM_BYTES = 96L * 1024 * 1024

/**
 * Whether [spec] can be rendered here, judged before trying rather than by
 * catching the crash: the GPU must be able to hold a render target of its
 * size ([maxRenderSizePx], 0 when it could not be read, which counts as no),
 * and free memory must hold the few full-size copies a render makes.
 */
internal fun resolutionSupport(
    spec: MapImageSpec,
    maxRenderSizePx: Int,
    availableBytes: Long,
    lowRamDevice: Boolean,
): HighResolutionSupport {
    if (maxRenderSizePx < max(spec.widthPx, spec.heightPx)) return HighResolutionSupport.ExceedsGpu
    val bytes = spec.widthPx.toLong() * spec.heightPx * 4
    if (lowRamDevice || availableBytes < bytes * PEAK_BITMAP_COPIES + MEMORY_HEADROOM_BYTES) {
        return HighResolutionSupport.InsufficientMemory
    }
    return HighResolutionSupport.Supported
}

/** Where to point the camera, in MapLibre's own terms. */
internal data class MapImageCamera(val latitude: Double, val longitude: Double, val zoom: Double)

/** MapLibre's tile size: the world is this many logical pixels wide at zoom 0. */
private const val TILE_SIZE = 512.0

/** Web Mercator stops here; past it a latitude has no finite y. */
private const val MAX_MERCATOR_LATITUDE = 85.051129

/** Closest the camera goes on a route, so a short walk still shows its streets' context. */
internal const val MAP_IMAGE_MAX_ZOOM = 16.0

/** Zoom for a selection with no area at all: one sample, or all of them in one spot. */
internal const val MAP_IMAGE_POINT_ZOOM = 14.0

/**
 * The geographic box a track covers, with longitude as an arc rather than a
 * pair of edges - see [TrackWindow]. A trip across the Pacific covers 30
 * degrees from 165 E eastward, not 330 degrees from 165 W.
 *
 * The arc is the shortest one that holds every sample: the complement of the
 * widest empty stretch of longitude between them. Null for no samples.
 */
internal fun trackExtent(points: List<PathPoint>): TrackWindow? {
    if (points.isEmpty()) return null
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    val lons = DoubleArray(points.size)
    points.forEachIndexed { i, p ->
        if (p.latitude < minLat) minLat = p.latitude
        if (p.latitude > maxLat) maxLat = p.latitude
        lons[i] = wrapLongitude(p.longitude)
    }
    lons.sort()
    // The widest gap between neighbours, counting the one that wraps around
    // from the easternmost sample back to the westernmost.
    var widestGap = lons.first() + 360.0 - lons.last()
    var west = lons.first()
    for (i in 1 until lons.size) {
        val gap = lons[i] - lons[i - 1]
        if (gap > widestGap) {
            widestGap = gap
            west = lons[i]
        }
    }
    return TrackWindow(minLat, maxLat, west, (360.0 - widestGap).coerceIn(0.0, 360.0))
}

/**
 * Fit [extent] into the part of the image the route may use - below the
 * branding, above the statistics and attribution, inside the side padding -, and return the
 * camera that does it.
 *
 * Worked in Web Mercator directly rather than through MapLibre's own
 * bounds fitting, because the snapshot has to be told its camera before it
 * has a map to ask, and because the usable area is not centred: the bottom of
 * a portrait image is kept for the statistics and the attribution, so the
 * route is fitted into the rectangle above them and the camera centre is
 * shifted down to put it there. Width and height are scaled by the same zoom,
 * so nothing is ever stretched.
 */
internal fun fitMapImageCamera(extent: TrackWindow, spec: MapImageSpec, showStats: Boolean): MapImageCamera {
    val ratio = spec.pixelRatio.toDouble()
    // The usable rectangle, in logical pixels.
    val left = spec.sidePaddingPx / ratio
    val right = (spec.widthPx - spec.sidePaddingPx) / ratio
    val top = spec.topPaddingPx / ratio
    val bottom = (spec.heightPx - spec.reservedBottomPx(showStats) - spec.bottomGapPx) / ratio
    val usableWidth = right - left
    val usableHeight = bottom - top

    // The box in Mercator units, 0..1 across the world.
    val x0 = mercatorX(extent.westLon)
    val x1 = x0 + extent.lonSpan / 360.0
    val y0 = mercatorY(extent.maxLat)
    val y1 = mercatorY(extent.minLat)
    val dx = x1 - x0
    val dy = y1 - y0

    val zoom = if (dx <= 0.0 && dy <= 0.0) {
        MAP_IMAGE_POINT_ZOOM
    } else {
        val byWidth = if (dx > 0) log2(usableWidth / (dx * TILE_SIZE)) else Double.POSITIVE_INFINITY
        val byHeight = if (dy > 0) log2(usableHeight / (dy * TILE_SIZE)) else Double.POSITIVE_INFINITY
        min(byWidth, byHeight).coerceIn(0.0, MAP_IMAGE_MAX_ZOOM)
    }
    val scale = TILE_SIZE * Math.pow(2.0, zoom)

    // Put the middle of the box on the middle of the usable rectangle; the
    // camera's own centre is the middle of the whole image.
    val boxX = (x0 + x1) / 2
    val boxY = (y0 + y1) / 2
    val viewCenterX = spec.logicalWidth / 2.0
    val viewCenterY = spec.logicalHeight / 2.0
    val cameraX = boxX + (viewCenterX - (left + right) / 2) / scale
    val cameraY = (boxY + (viewCenterY - (top + bottom) / 2) / scale).coerceIn(0.0, 1.0)
    return MapImageCamera(
        latitude = latitudeOfMercatorY(cameraY),
        longitude = wrapLongitude(cameraX * 360.0 - 180.0),
        zoom = zoom,
    )
}

/**
 * Where ([latitude], [longitude]) lands in the output image, in output
 * pixels, for a snapshot taken at [camera]. The same projection MapLibre
 * renders with, so markers drawn here sit on the rendered line.
 *
 * Longitude is taken on the side of the camera it is nearest to, which is the
 * copy of the world the snapshot shows when a route crosses the date line.
 */
internal fun projectToImage(
    camera: MapImageCamera,
    spec: MapImageSpec,
    latitude: Double,
    longitude: Double,
): Pair<Float, Float> {
    val scale = TILE_SIZE * Math.pow(2.0, camera.zoom) * spec.pixelRatio
    var dLon = longitude - camera.longitude
    dLon -= 360.0 * Math.floor((dLon + 180.0) / 360.0)
    val x = spec.widthPx / 2.0 + dLon / 360.0 * scale
    val y = spec.heightPx / 2.0 + (mercatorY(latitude) - mercatorY(camera.latitude)) * scale
    return x.toFloat() to y.toFloat()
}

private fun mercatorX(longitude: Double): Double = (longitude + 180.0) / 360.0

private fun mercatorY(latitude: Double): Double {
    val lat = latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
    val s = sin(Math.toRadians(lat))
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}

private fun latitudeOfMercatorY(y: Double): Double =
    Math.toDegrees(2 * atan(exp((0.5 - y) * 2 * PI)) - PI / 2)

/**
 * What the image's statistics say about the exported selection - never about
 * the whole file.
 *
 * [distanceMeters] is the length of the line actually drawn: consecutive
 * samples inside one journey, skipping every jump the map itself shows as a
 * dashed gap (see [GAP_DISTANCE_METERS]). It is therefore a measured lower
 * bound, not Google's estimate, and the label says "tracked". Zero is shown
 * as nothing rather than "0 km".
 *
 * [placeCount] is null wherever the place list does not describe the same
 * selection the route does (the Trips view), and the image then leaves it out.
 */
internal data class MapImageStats(
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val dayCount: Int,
    val distanceMeters: Double,
    val placeCount: Int?,
)

/**
 * The first and last dates are read in [zone] - the phone's own time zone, see
 * [MapImageRequest.fallbackZone] - and never in each sample's recorded offset.
 * That is the clock step 2's period, the file card, the date filter and every
 * suggested filename use, so the image says the same dates the screen it was
 * made from does. With a recorded offset a sample at 19:58 UTC in Tashkent
 * (UTC+5) fell on the next day, and an export of "9 Jun 2025 - ..." was
 * captioned and named from the 10th.
 *
 * The day count is the app's own "days" figure, [drawnTrackDays]: the days
 * that have a line on the map, each in the time zone it was recorded in, as
 * the file card and the day colours count them. Zero hides the figure.
 */
internal fun mapImageStats(
    points: List<PathPoint>,
    journeys: List<List<PathPoint>>,
    zone: ZoneId,
    placeCount: Int?,
): MapImageStats? {
    if (points.isEmpty()) return null
    var first = Long.MAX_VALUE
    var last = Long.MIN_VALUE
    val rules = zone.rules
    for (p in points) {
        val day = Math.floorDiv(p.timeUtc.epochSeconds + rules.getOffset(p.timeUtc.toJavaInstant()).totalSeconds, SECONDS_PER_DAY)
        if (day < first) first = day
        if (day > last) last = day
    }
    var meters = 0.0
    for (journey in journeys) {
        for (i in 1 until journey.size) {
            val d = haversineMeters(journey[i - 1], journey[i])
            if (d <= GAP_DISTANCE_METERS) meters += d
        }
    }
    return MapImageStats(
        firstDate = LocalDate.ofEpochDay(first),
        lastDate = LocalDate.ofEpochDay(last),
        dayCount = drawnTrackDays(points, zone).size,
        distanceMeters = meters,
        placeCount = placeCount?.takeIf { it > 0 },
    )
}

/**
 * "timeline-route-2026-05-12_2026-05-23.png", or one date for a single day.
 * ISO dates, like every other filename the app suggests: they sort, and they
 * mean the same thing in every language.
 */
internal fun mapImageFilename(stats: MapImageStats?, format: MapImageFormat = MapImageFormat.Png): String {
    val base = when {
        stats == null -> "timeline-route"
        stats.firstDate == stats.lastDate -> "timeline-route-${stats.firstDate}"
        else -> "timeline-route-${stats.firstDate}_${stats.lastDate}"
    }
    return "$base.${format.extension}"
}

private const val SECONDS_PER_DAY = 86_400L
