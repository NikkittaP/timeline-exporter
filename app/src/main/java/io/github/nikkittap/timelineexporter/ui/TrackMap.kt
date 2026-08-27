package io.github.nikkittap.timelineexporter.ui

import android.util.Log
import android.view.MotionEvent
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "TrackMap"

/** OpenFreeMap's "Liberty" style — free, no API key, OSM-based. */
private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val SOURCE_TRACK = "track-source"
private const val SOURCE_GAPS = "gaps-source"
private const val SOURCE_START = "start-source"
private const val SOURCE_END = "end-source"
private const val LAYER_TRACK = "track-line"
private const val LAYER_GAPS = "gaps-line"
private const val LAYER_START = "start-circle"
private const val LAYER_END = "end-circle"

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

    // Kick off async map + style load exactly once.
    LaunchedEffect(mapView) {
        Log.d(TAG, "Requesting MapLibreMap…")
        mapView.getMapAsync { map ->
            // MapLibre holds these callbacks itself and has no way to know we
            // are gone, so both can land after teardown.
            if (destroyed.get()) return@getMapAsync
            Log.d(TAG, "Map ready, loading style from $MAP_STYLE_URL")
            loadedMap = map
            map.setStyle(MAP_STYLE_URL) { style ->
                if (destroyed.get()) return@setStyle
                Log.d(TAG, "Style loaded successfully (uri=${style.uri})")
                loadedStyle = style
            }
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
        runCatching { renderTrack(map, style, points) }
            .onFailure { Log.w(TAG, "Track render failed", it) }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * Replace the existing track + endpoint markers on [style] with new ones
 * derived from [points], then fit the camera. Empty input clears the map.
 */
private fun renderTrack(map: MapLibreMap, style: Style, points: List<PathPoint>) {
    // Always tear down previous overlays first; addSource/addLayer throw on
    // collisions, and v1 just rebuilds wholesale on every change.
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

    // Gap connectors: very transparent dashed lines, drawn first so the solid
    // track sits on top of them at shared endpoints.
    if (gaps.isNotEmpty()) {
        style.addSource(
            GeoJsonSource(
                SOURCE_GAPS,
                Feature.fromGeometry(MultiLineString.fromLngLats(gaps)),
            )
        )
        style.addLayer(
            LineLayer(LAYER_GAPS, SOURCE_GAPS).withProperties(
                lineColor("#FF5722"),
                lineWidth(2.5f),
                lineOpacity(0.4f),
                lineDasharray(arrayOf(2f, 4f)),
                lineCap(Property.LINE_CAP_ROUND),
            )
        )
    }

    // Solid track: one MultiLineString made of every run with ≥2 points.
    val drawableRuns = runs.filter { it.size >= 2 }
    if (drawableRuns.isNotEmpty()) {
        style.addSource(
            GeoJsonSource(
                SOURCE_TRACK,
                Feature.fromGeometry(MultiLineString.fromLngLats(drawableRuns)),
            )
        )
        style.addLayer(
            LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
                lineColor("#FF5722"),
                lineWidth(4f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
    }

    // Start marker (green) and end marker (red).
    val first = points.first()
    val last = points.last()
    style.addSource(
        GeoJsonSource(
            SOURCE_START,
            Feature.fromGeometry(Point.fromLngLat(first.longitude, first.latitude)),
        )
    )
    style.addLayer(endpointCircle(LAYER_START, SOURCE_START, "#4CAF50"))
    style.addSource(
        GeoJsonSource(
            SOURCE_END,
            Feature.fromGeometry(Point.fromLngLat(last.longitude, last.latitude)),
        )
    )
    style.addLayer(endpointCircle(LAYER_END, SOURCE_END, "#F44336"))

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
    listOf(LAYER_GAPS, LAYER_TRACK, LAYER_START, LAYER_END).forEach { id ->
        style.getLayer(id)?.let { style.removeLayer(it) }
    }
    listOf(SOURCE_TRACK, SOURCE_GAPS, SOURCE_START, SOURCE_END).forEach { id ->
        style.getSource(id)?.let { style.removeSource(it) }
    }
}

private fun endpointCircle(layerId: String, sourceId: String, hexColor: String) =
    CircleLayer(layerId, sourceId).withProperties(
        circleColor(hexColor),
        circleRadius(8f),
        circleStrokeColor("#FFFFFF"),
        circleStrokeWidth(2f),
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
