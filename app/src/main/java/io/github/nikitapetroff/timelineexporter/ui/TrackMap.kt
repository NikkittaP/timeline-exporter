package io.github.nikitapetroff.timelineexporter.ui

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
import io.github.nikitapetroff.timelineexporter.parser.PathPoint
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
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val TAG = "TrackMap"

/** OpenFreeMap's "Liberty" style — free, no API key, OSM-based. */
private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val SOURCE_TRACK = "track-source"
private const val SOURCE_START = "start-source"
private const val SOURCE_END = "end-source"
private const val LAYER_TRACK = "track-line"
private const val LAYER_START = "start-circle"
private const val LAYER_END = "end-circle"

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

    // Forward Compose lifecycle events to MapView. Without this, MapLibre
    // would never know it should pause GL rendering when the screen is hidden.
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // Kick off async map + style load exactly once.
    LaunchedEffect(mapView) {
        Log.d(TAG, "Requesting MapLibreMap…")
        mapView.getMapAsync { map ->
            Log.d(TAG, "Map ready, loading style from $MAP_STYLE_URL")
            loadedMap = map
            map.setStyle(MAP_STYLE_URL) { style ->
                Log.d(TAG, "Style loaded successfully (uri=${style.uri})")
                loadedStyle = style
            }
        }
    }

    // Re-render the track whenever points change OR the style becomes available.
    LaunchedEffect(points, loadedStyle) {
        val map = loadedMap ?: return@LaunchedEffect
        val style = loadedStyle ?: return@LaunchedEffect
        Log.d(TAG, "Rendering track with ${points.size} points")
        renderTrack(map, style, points)
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
    listOf(LAYER_TRACK, LAYER_START, LAYER_END).forEach { id ->
        style.getLayer(id)?.let { style.removeLayer(it) }
    }
    listOf(SOURCE_TRACK, SOURCE_START, SOURCE_END).forEach { id ->
        style.getSource(id)?.let { style.removeSource(it) }
    }

    if (points.isEmpty()) return

    // Track polyline.
    val coords = points.map { Point.fromLngLat(it.longitude, it.latitude) }
    style.addSource(GeoJsonSource(SOURCE_TRACK, Feature.fromGeometry(LineString.fromLngLats(coords))))
    style.addLayer(
        LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
            lineColor("#FF5722"),
            lineWidth(4f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
    )

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

private fun endpointCircle(layerId: String, sourceId: String, hexColor: String) =
    CircleLayer(layerId, sourceId).withProperties(
        circleColor(hexColor),
        circleRadius(8f),
        circleStrokeColor("#FFFFFF"),
        circleStrokeWidth(2f),
    )
