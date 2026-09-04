package io.github.nikkittap.timelineexporter.ui

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.nikkittap.timelineexporter.parser.PathPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleBlur
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineBlur
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "TrackMap"

private const val SOURCE_TRACK = "track-source"
private const val SOURCE_GAPS = "gaps-source"
private const val SOURCE_START = "start-source"
private const val SOURCE_END = "end-source"
private const val LAYER_TRACK_GLOW = "track-glow"
private const val LAYER_TRACK = "track-line"
private const val LAYER_GAPS = "gaps-line"
private const val LAYER_START_HALO = "start-halo"
private const val LAYER_START = "start-circle"
private const val LAYER_END_HALO = "end-halo"
private const val LAYER_END = "end-circle"

/** Bottom-to-top; also the order [clearTrackOverlays] tears them down in reverse. */
private val OWNED_LAYERS = listOf(
    LAYER_TRACK_GLOW, LAYER_GAPS, LAYER_TRACK,
    LAYER_START_HALO, LAYER_START, LAYER_END_HALO, LAYER_END,
)
private val OWNED_SOURCES = listOf(SOURCE_TRACK, SOURCE_GAPS, SOURCE_START, SOURCE_END)

/**
 * Consecutive GPS samples farther apart than this are treated as a "gap"
 * (a flight, a long drive with location off, or any other discontinuity)
 * rather than a real travelled segment. Drawing a solid line across such a
 * jump produces the long straight streaks that clutter the map. Instead we
 * break the track there and render the jump as a faint dashed connector.
 *
 * 80 km is comfortably above normal Timeline sampling density yet low enough
 * to catch short-haul flights.
 */
private const val GAP_DISTANCE_METERS = 80_000.0

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * Colors and basemap for one system theme. The two basemaps are our own
 * minimal styles in `assets/`, drawn from OpenFreeMap's OpenMapTiles vector
 * source: a near-monochrome ground whose only job is to stay out of the
 * track's way.
 */
private data class MapPalette(
    val styleAsset: String,
    /** The travelled track itself — thin, translucent, accumulating. */
    val trackCore: String,
    /** A wide, blurred wash under the track; blooms where traces pile up. */
    val trackGlow: String,
    /** Dashed connector across a discontinuity. */
    val gap: String,
    val startFill: String,
    val endFill: String,
    /** Ring around the endpoint dots — the basemap ground color. */
    val markerStroke: String,
)

private val LightPalette = MapPalette(
    styleAsset = "map_style_light.json",
    trackCore = "#4C3BD6",
    trackGlow = "#7C6BF2",
    gap = "#4C3BD6",
    startFill = "#0E9F86",
    endFill = "#E5533D",
    markerStroke = "#F5F4F1",
)

private val DarkPalette = MapPalette(
    styleAsset = "map_style_dark.json",
    trackCore = "#EAF1F8",
    trackGlow = "#4FC3E8",
    gap = "#8FA3B5",
    startFill = "#2FD3A5",
    endFill = "#FF6B57",
    markerStroke = "#191D23",
)

/**
 * How heavily to draw one trace, given how many of them there are.
 *
 * The "heat" look comes from alpha accumulation: MapLibre blends every line
 * segment in a layer against what is already in the framebuffer, so a road
 * walked once stays a faint hairline while one walked two hundred times
 * builds up to near-solid. That only works if a single pass is faint enough
 * to leave headroom — but a faint hairline is the wrong answer for a single
 * afternoon's walk, which has nothing to accumulate with. So the base weight
 * is interpolated on the log of the sample count: a small track is drawn as a
 * confident route line, a year of history as a heat map.
 */
private data class HeatWeights(
    val coreWidth: Float,
    val coreOpacity: Float,
    val glowWidth: Float,
    val glowOpacity: Float,
    val glowBlur: Float,
)

private val HEAT_ANCHORS = listOf(
    300 to HeatWeights(3.2f, 0.90f, 9.0f, 0.10f, 2.5f),
    5_000 to HeatWeights(1.8f, 0.50f, 7.0f, 0.085f, 2.5f),
    60_000 to HeatWeights(1.0f, 0.22f, 5.5f, 0.065f, 2.0f),
    400_000 to HeatWeights(0.75f, 0.12f, 4.5f, 0.05f, 2.0f),
)

@Composable
fun TrackMap(
    points: List<PathPoint>,
    modifier: Modifier = Modifier,
    /**
     * Called with `true` on ACTION_DOWN and `false` on ACTION_UP/CANCEL.
     * Parent uses this to disable its own verticalScroll while the user is
     * panning/pinching the map, so the screen doesn't scroll out from
     * under their finger.
     */
    onInteractionChange: (interacting: Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val darkTheme = isSystemInDarkTheme()
    val palette = if (darkTheme) DarkPalette else LightPalette

    // Keep latest callback reference so the View's touch listener (created
    // once in `remember`) always calls the most recent caller's lambda.
    val latestInteractionCallback by rememberUpdatedState(onInteractionChange)

    // MapLibre.getInstance is idempotent — safe to call every time the
    // composable is first composed. MapView creation kicks off the native
    // GL surface; onCreate(null) means "no saved instance state to restore".
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            // Spy on touch events to tell the parent verticalScroll to back
            // off while the map is being interacted with. Returning false
            // from the listener means "I'm not consuming, continue to
            // MapView's own onTouchEvent" — pan/zoom still work normally.
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        latestInteractionCallback(true)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL ->
                        latestInteractionCallback(false)
                }
                false
            }
        }
    }

    // We capture the MapLibreMap + Style as they become available, so the
    // points-change effect below can re-render without waiting on callbacks.
    var loadedMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }

    // The camera is fitted to the data, not to the style. Remembering which
    // list we last framed keeps a theme switch (which reloads the style and
    // so re-runs the render) from yanking a camera the user has since panned.
    var lastFittedPoints by remember { mutableStateOf<List<PathPoint>?>(null) }

    // MapLibre's async callbacks (getMapAsync, setStyle) and our own render
    // effect can all fire after this composable has left composition and the
    // native map has been torn down. Touching a Style or MapLibreMap at that
    // point dereferences a freed C++ peer and aborts the process — those are
    // the MapRenderer::~MapRenderer and GeoJSONSource SIGABRTs reported
    // against 1.6.1. This flag is the single source of truth for "the native
    // map is gone"; every entry point checks it first.
    val destroyed = remember { AtomicBoolean(false) }

    // Forward Compose lifecycle events to MapView. Without this, MapLibre
    // would never know it should pause GL rendering when the screen is hidden.
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            // A late event after teardown would reach a MapView whose native
            // peer is already gone.
            if (!destroyed.get()) {
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> {}
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (destroyed.getAndSet(true)) return@onDispose

            mapView.setOnTouchListener(null)

            // Walk the MapView back down the lifecycle it was actually
            // brought up through. Composition can end while the host is
            // still RESUMED — the map scrolls out of a conditional, a dialog
            // replaces it, the user backs out mid-render — and calling
            // onDestroy() on a still-running render loop is what aborts in
            // ~MapRenderer.
            val state = lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()

            // Detach our layers and sources explicitly. MapLibre's own
            // Style.clear() only detaches the Java peers and leaves the
            // native ones to be freed underneath them (maplibre-native#3269),
            // so any GeoJsonSource wrapper that outlives this call would
            // point at freed memory.
            runCatching { loadedStyle?.let(::clearTrackOverlays) }
                .onFailure { Log.w(TAG, "Style teardown failed", it) }

            loadedStyle = null
            loadedMap = null
            mapView.onDestroy()
        }
    }

    // Kick off the async map load exactly once.
    LaunchedEffect(mapView) {
        Log.d(TAG, "Requesting MapLibreMap…")
        mapView.getMapAsync { map ->
            // MapLibre holds this callback itself and has no way to know we
            // are gone, so it can land after teardown.
            if (destroyed.get()) return@getMapAsync
            loadedMap = map
        }
    }

    // (Re)load the basemap whenever the system theme flips. The style JSON is
    // ours, bundled in assets, so this is a local parse and a tile re-request
    // rather than a style download.
    LaunchedEffect(loadedMap, palette) {
        if (destroyed.get()) return@LaunchedEffect
        val map = loadedMap ?: return@LaunchedEffect
        // The old Style's native peer goes away the moment setStyle starts;
        // drop our reference first so the render effect cannot touch it.
        loadedStyle = null
        val json = runCatching { context.readAsset(palette.styleAsset) }
            .onFailure { Log.e(TAG, "Cannot read ${palette.styleAsset}", it) }
            .getOrNull() ?: return@LaunchedEffect
        Log.d(TAG, "Loading basemap ${palette.styleAsset}")
        map.setStyle(Style.Builder().fromJson(json)) { style ->
            if (destroyed.get()) return@setStyle
            Log.d(TAG, "Style loaded (${style.layers.size} layers)")
            loadedStyle = style
        }
    }

    // Re-render the track whenever points change OR the style becomes available.
    LaunchedEffect(points, loadedStyle) {
        if (destroyed.get()) return@LaunchedEffect
        val map = loadedMap ?: return@LaunchedEffect
        val style = loadedStyle ?: return@LaunchedEffect
        // A style that is not fully loaded (a second setStyle still in
        // flight, or a map already being torn down) has no valid native peer
        // to add sources to.
        if (!style.isFullyLoaded) {
            Log.d(TAG, "Style not fully loaded — skipping render")
            return@LaunchedEffect
        }
        Log.d(TAG, "Rendering track with ${points.size} points")
        // Last-resort net: a style can go invalid between the check above and
        // the native call. Losing the overlay beats aborting the process.
        val fitCamera = lastFittedPoints !== points
        runCatching { renderTrack(map, style, points, palette, fitCamera) }
            .onFailure { Log.w(TAG, "Track render failed", it) }
            .onSuccess { if (fitCamera) lastFittedPoints = points }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun Context.readAsset(name: String): String =
    assets.open(name).use { it.readBytes().decodeToString() }

/**
 * Replace the existing track + endpoint markers on [style] with new ones
 * derived from [points]. Empty input clears the map.
 */
private fun renderTrack(
    map: MapLibreMap,
    style: Style,
    points: List<PathPoint>,
    palette: MapPalette,
    fitCamera: Boolean,
) {
    // Always tear down previous overlays first; addSource/addLayer throw on
    // collisions, and we rebuild wholesale on every change.
    clearTrackOverlays(style)

    if (points.isEmpty()) return

    // Split the ordered points into continuous runs, breaking wherever two
    // consecutive samples are too far apart to be a real travelled segment
    // (a flight, or a stretch with location services off). Each break is also
    // recorded as a "gap" connector so we can hint at it with a faint dash.
    val runs = mutableListOf<MutableList<Point>>()
    val gaps = mutableListOf<List<Point>>()
    var current = mutableListOf(points.first().toPoint())
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        if (haversineMeters(prev, cur) > GAP_DISTANCE_METERS) {
            // Close the current run and remember the jump between the two
            // endpoints, then start a fresh run at the far side.
            runs += current
            gaps += listOf(prev.toPoint(), cur.toPoint())
            current = mutableListOf(cur.toPoint())
        } else {
            current += cur.toPoint()
        }
    }
    runs += current

    // Everything we draw goes underneath the basemap's own labels, so place
    // names stay readable through a dense track. Layers inserted below the
    // same reference stack in insertion order, so this call sequence is also
    // the bottom-to-top paint order.
    val firstLabelLayer = style.layers.firstOrNull { it is SymbolLayer }?.id
    fun addBelowLabels(layer: Layer) {
        if (firstLabelLayer != null) style.addLayerBelow(layer, firstLabelLayer)
        else style.addLayer(layer)
    }

    val weights = heatWeightsFor(points.size)
    val drawableRuns = runs.filter { it.size >= 2 }

    if (drawableRuns.isNotEmpty()) {
        style.addSource(
            GeoJsonSource(
                SOURCE_TRACK,
                Feature.fromGeometry(MultiLineString.fromLngLats(drawableRuns)),
            )
        )

        // 1. Glow. Wide, blurred and almost invisible on its own; where many
        //    traces overlap the alpha stacks up and the corridor blooms.
        addBelowLabels(
            LineLayer(LAYER_TRACK_GLOW, SOURCE_TRACK).withProperties(
                lineColor(palette.trackGlow),
                lineWidth(zoomScaledWidth(weights.glowWidth)),
                lineOpacity(weights.glowOpacity),
                lineBlur(weights.glowBlur),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
    }

    // 2. Gap connectors: faint dashes across discontinuities, under the
    //    solid track so the two meet cleanly at shared endpoints.
    if (gaps.isNotEmpty()) {
        style.addSource(
            GeoJsonSource(
                SOURCE_GAPS,
                Feature.fromGeometry(MultiLineString.fromLngLats(gaps)),
            )
        )
        addBelowLabels(
            LineLayer(LAYER_GAPS, SOURCE_GAPS).withProperties(
                lineColor(palette.gap),
                lineWidth(1.2f),
                lineOpacity(0.35f),
                lineDasharray(arrayOf(1.5f, 3f)),
                lineCap(Property.LINE_CAP_BUTT),
            )
        )
    }

    // 3. The track itself: a hairline drawn at low alpha, so repeated passes
    //    over the same street accumulate into a bright, solid corridor.
    if (drawableRuns.isNotEmpty()) {
        addBelowLabels(
            LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
                lineColor(palette.trackCore),
                lineWidth(zoomScaledWidth(weights.coreWidth)),
                lineOpacity(zoomScaledOpacity(weights.coreOpacity)),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
    }

    // 4. Endpoints, on top of everything including labels — they are the one
    //    thing the user looks for by eye.
    val first = points.first()
    val last = points.last()
    style.addSource(
        GeoJsonSource(SOURCE_START, Feature.fromGeometry(first.toPoint())),
    )
    style.addLayer(endpointHalo(LAYER_START_HALO, SOURCE_START, palette.startFill))
    style.addLayer(endpointDot(LAYER_START, SOURCE_START, palette.startFill, palette.markerStroke))
    style.addSource(
        GeoJsonSource(SOURCE_END, Feature.fromGeometry(last.toPoint())),
    )
    style.addLayer(endpointHalo(LAYER_END_HALO, SOURCE_END, palette.endFill))
    style.addLayer(endpointDot(LAYER_END, SOURCE_END, palette.endFill, palette.markerStroke))

    if (!fitCamera) return

    // Camera fit. Single-point edge case can't use bounds (zero area).
    if (points.size == 1) {
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(first.latitude, first.longitude))
            .zoom(14.0)
            .build()
    } else {
        val builder = LatLngBounds.Builder()
        points.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 80))
    }
}

/**
 * Remove every layer and source this file owns from [style]. Called both
 * before a re-render (addSource/addLayer collide on existing ids) and on
 * teardown, where dropping the native sources before MapView.onDestroy() is
 * what keeps our Java GeoJsonSource wrappers from outliving their C++ peers
 * (maplibre-native#3269). Layers go first — a source with a layer still
 * attached cannot be removed.
 */
private fun clearTrackOverlays(style: Style) {
    OWNED_LAYERS.forEach { id -> style.getLayer(id)?.let { style.removeLayer(it) } }
    OWNED_SOURCES.forEach { id -> style.getSource(id)?.let { style.removeSource(it) } }
}

/**
 * Pick the base weight for one trace from the number of samples on screen,
 * interpolating on a log scale between [HEAT_ANCHORS] so the transition from
 * "one route" to "a year of routes" is gradual rather than a mode switch.
 */
private fun heatWeightsFor(pointCount: Int): HeatWeights {
    val n = pointCount.coerceAtLeast(1)
    val first = HEAT_ANCHORS.first()
    val last = HEAT_ANCHORS.last()
    if (n <= first.first) return first.second
    if (n >= last.first) return last.second
    for (i in 0 until HEAT_ANCHORS.size - 1) {
        val (lowN, low) = HEAT_ANCHORS[i]
        val (highN, high) = HEAT_ANCHORS[i + 1]
        if (n > highN) continue
        val t = ((log10(n.toDouble()) - log10(lowN.toDouble())) /
            (log10(highN.toDouble()) - log10(lowN.toDouble()))).toFloat()
        return HeatWeights(
            coreWidth = lerp(low.coreWidth, high.coreWidth, t),
            coreOpacity = lerp(low.coreOpacity, high.coreOpacity, t),
            glowWidth = lerp(low.glowWidth, high.glowWidth, t),
            glowOpacity = lerp(low.glowOpacity, high.glowOpacity, t),
            glowBlur = lerp(low.glowBlur, high.glowBlur, t),
        )
    }
    return last.second
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Line width, in pixels, as a function of zoom. A hairline that reads well
 * over a city becomes invisible at continent scale and anaemic at street
 * scale, so the base width from [heatWeightsFor] is scaled along the way —
 * with a floor at the low end, because a sub-pixel line is not "faint", it
 * is gone.
 */
private fun zoomScaledWidth(base: Float): Expression = Expression.interpolate(
    Expression.exponential(1.3f), Expression.zoom(),
    Expression.stop(2, maxOf(base * 0.6f, 0.8f)),
    Expression.stop(8, maxOf(base * 0.8f, 0.9f)),
    Expression.stop(12, base),
    Expression.stop(16, base * 1.7f),
    Expression.stop(20, base * 2.8f),
)

/**
 * Opacity as a function of zoom, and the shape here is deliberately a U.
 *
 * Accumulation only tells you something where traces actually pile up, which
 * is at city and street zoom. Zoomed out to a continent a long-haul journey
 * is a single pass with nothing to stack against, so drawing it at heat-map
 * alpha would erase the trip entirely; alpha is held high there instead. It
 * dips through zoom 10-13, where a year of local movement overlaps itself
 * hundreds of times and the gradient does the work. Past that the map is one
 * street wide, overlap is gone again, and the trace should simply be solid.
 */
private fun zoomScaledOpacity(base: Float): Expression = Expression.interpolate(
    Expression.linear(), Expression.zoom(),
    Expression.stop(2, alpha(maxOf(base, 0.45f))),
    Expression.stop(6, alpha(maxOf(base, 0.35f))),
    Expression.stop(10, alpha(base * 1.15f)),
    Expression.stop(13, alpha(base)),
    Expression.stop(16, alpha(base * 1.8f)),
    Expression.stop(19, alpha(base * 2.8f)),
)

private fun alpha(value: Float): Float = value.coerceIn(0f, 1f)

/** Soft pool of color under an endpoint dot, so it reads against a busy track. */
private fun endpointHalo(layerId: String, sourceId: String, hexColor: String) =
    CircleLayer(layerId, sourceId).withProperties(
        circleColor(hexColor),
        circleOpacity(0.18f),
        circleBlur(0.5f),
        circleRadius(
            Expression.interpolate(
                Expression.linear(), Expression.zoom(),
                Expression.stop(4, 8f),
                Expression.stop(10, 13f),
                Expression.stop(15, 18f),
            )
        ),
    )

private fun endpointDot(
    layerId: String,
    sourceId: String,
    hexColor: String,
    strokeColor: String,
) = CircleLayer(layerId, sourceId).withProperties(
    circleColor(hexColor),
    circleStrokeColor(strokeColor),
    circleStrokeWidth(2.2f),
    circleRadius(
        Expression.interpolate(
            Expression.linear(), Expression.zoom(),
            Expression.stop(4, 3.5f),
            Expression.stop(10, 5.5f),
            Expression.stop(15, 7f),
        )
    ),
)

private fun PathPoint.toPoint(): Point = Point.fromLngLat(longitude, latitude)

/**
 * Great-circle distance between two points in metres (haversine). Used only to
 * decide whether two consecutive samples are far enough apart to count as a
 * gap, so the small-angle accuracy of haversine is more than sufficient.
 */
private fun haversineMeters(a: PathPoint, b: PathPoint): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
}
