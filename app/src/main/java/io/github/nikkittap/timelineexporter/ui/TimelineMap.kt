package io.github.nikkittap.timelineexporter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.parser.PlaceEntry
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
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineBlur
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.symbolSpacing
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "TimelineMap"

private const val SOURCE_TRACK = "track-source"
private const val SOURCE_GAPS = "gaps-source"
private const val SOURCE_START = "start-source"
private const val SOURCE_END = "end-source"
private const val SOURCE_PLACES = "places-source"
private const val LAYER_TRACK_GLOW = "track-glow"
private const val LAYER_TRACK = "track-line"
private const val LAYER_TRACK_ARROWS = "track-arrows"
private const val LAYER_GAPS = "gaps-line"
private const val LAYER_START_HALO = "start-halo"
private const val LAYER_START = "start-circle"
private const val LAYER_END_HALO = "end-halo"
private const val LAYER_END = "end-circle"
private const val LAYER_PLACES_HALO = "places-halo"
private const val LAYER_PLACES = "places-circle"

/** Feature property the places circle radius interpolates on. */
private const val PROPERTY_VISITS = "visits"

/** Bottom-to-top; also the order [clearOverlays] tears them down in reverse. */
private val OWNED_LAYERS = listOf(
    LAYER_TRACK_GLOW, LAYER_GAPS, LAYER_TRACK, LAYER_TRACK_ARROWS,
    LAYER_START_HALO, LAYER_START, LAYER_END_HALO, LAYER_END,
    LAYER_PLACES_HALO, LAYER_PLACES,
)
private val OWNED_SOURCES =
    listOf(SOURCE_TRACK, SOURCE_GAPS, SOURCE_START, SOURCE_END, SOURCE_PLACES)

/** Sprite id for the direction chevron repeated along the track. */
private const val IMAGE_TRACK_ARROW = "track-arrow"

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

/** Gap, in pixels, between the fitted track and the edges of the view. */
private const val FIT_PADDING_PX = 80

/**
 * How far the camera may leave the fitted view before the "back to the track"
 * button appears: two fifths of a screen width, or about half a zoom level.
 * Under that the user has nudged the map and can still see where they are;
 * over it the track can be off screen entirely with nothing saying so.
 */
private const val ADRIFT_SCREEN_FRACTION = 0.4
private const val ADRIFT_ZOOM_LEVELS = 0.45

/**
 * Above this many samples the track stops being a route and becomes a heat
 * map, and direction chevrons on it are noise: a street walked both ways two
 * hundred times has no direction to show.
 *
 * The reference export averages 86 path points per day, so this is about
 * three weeks of one person's movement — comfortably past a day out or a
 * holiday, which are the cases where "which way did I go" is a real question,
 * and well short of the year of history the accumulation rendering is for.
 */
private const val ROUTE_MODE_MAX_POINTS = 2_000

/** Screen pixels between one chevron and the next along the track. */
private const val ARROW_SPACING_PX = 96f

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
    /** Places, when the map is showing those instead of a track. */
    val placeFill: String,
)

private val LightPalette = MapPalette(
    styleAsset = "map_style_light.json",
    trackCore = "#4C3BD6",
    trackGlow = "#7C6BF2",
    gap = "#4C3BD6",
    startFill = "#0E9F86",
    endFill = "#E5533D",
    markerStroke = "#F5F4F1",
    placeFill = "#4C3BD6",
)

private val DarkPalette = MapPalette(
    styleAsset = "map_style_dark.json",
    trackCore = "#EAF1F8",
    trackGlow = "#4FC3E8",
    gap = "#8FA3B5",
    startFill = "#2FD3A5",
    endFill = "#FF6B57",
    markerStroke = "#191D23",
    placeFill = "#4FC3E8",
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

/**
 * What the map is showing.
 *
 * The two are different pictures of the same file rather than two maps: a
 * track is a line whose weight says how often it was walked, a set of places
 * is dots whose size says how often they were visited. Everything around them
 * — the MapLibre lifecycle, the theme-driven basemap, the scale bar, the way
 * back to the data — is the same, and this is what keeps it from being written
 * twice.
 */
sealed interface MapSubject {
    data class Track(val points: List<PathPoint>) : MapSubject
    data class Places(val entries: List<PlaceEntry>) : MapSubject

    val isEmpty: Boolean
        get() = when (this) {
            is Track -> points.isEmpty()
            is Places -> entries.isEmpty()
        }
}

/**
 * Walk the subject's coordinates without building a list of them: a year of
 * history is 30,000 points, and framing the camera should not allocate a
 * parallel copy of it to find four numbers.
 */
private inline fun MapSubject.forEachCoordinate(action: (lat: Double, lon: Double) -> Unit) {
    when (this) {
        is MapSubject.Track -> points.forEach { action(it.latitude, it.longitude) }
        is MapSubject.Places -> entries.forEach { action(it.latitude, it.longitude) }
    }
}

/** Somewhere to point the camera when there is no box to fit. */
private fun MapSubject.firstCoordinate(): LatLng? = when (this) {
    is MapSubject.Track -> points.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
    is MapSubject.Places -> entries.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
}

@Composable
fun TimelineMap(
    subject: MapSubject,
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
    // Sprites are added to the style in device pixels; MapLibre has no way to
    // be told a bitmap was authored in dp.
    val density = context.resources.displayMetrics.density

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

    // The camera is fitted to the data, not to the style. Remembering what we
    // last framed keeps a theme switch (which reloads the style and so re-runs
    // the render) from yanking a camera the user has since panned.
    var lastFitted by remember { mutableStateOf<MapSubject?>(null) }

    // The box the whole subject fits in. Null when there is nothing to frame —
    // no data, or all of it in one spot — which is also the case where "back
    // to the data" has nowhere to go.
    val dataBounds = remember(subject) { subjectBounds(subject) }

    // What the last camera-idle told us: how big things are, and whether the
    // view has left the track behind. Null until the first idle, which is
    // also before there is anything worth drawing chrome for.
    var readout by remember { mutableStateOf<MapReadout?>(null) }

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
            runCatching { loadedStyle?.let(::clearOverlays) }
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

    // Scale bar and "back to the track" both describe the camera, so both are
    // recomputed when it stops moving — once per gesture, not per frame.
    DisposableEffect(loadedMap, dataBounds) {
        val map = loadedMap ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnCameraIdleListener {
            if (!destroyed.get()) {
                readout = runCatching { readCamera(map, mapView, dataBounds) }.getOrNull()
            }
        }
        map.addOnCameraIdleListener(listener)
        onDispose { runCatching { map.removeOnCameraIdleListener(listener) } }
    }

    // Re-render whenever the subject changes OR the style becomes available.
    LaunchedEffect(subject, loadedStyle) {
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
        Log.d(TAG, "Rendering $subject")
        // Last-resort net: a style can go invalid between the check above and
        // the native call. Losing the overlay beats aborting the process.
        val fitCamera = lastFitted != subject
        runCatching { renderSubject(map, style, subject, palette, dataBounds, density, fitCamera) }
            .onFailure { Log.w(TAG, "Map render failed", it) }
            .onSuccess { if (fitCamera) lastFitted = subject }

        // Seed the chrome. A map that is never touched never goes idle, so
        // waiting for the listener alone would mean no scale bar at all on a
        // track the user only looks at. The camera fit above is animated, so
        // this reading can be one frame stale; the first idle corrects it.
        withFrameNanos { }
        if (!destroyed.get()) {
            readout = runCatching { readCamera(map, mapView, dataBounds) }.getOrNull()
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        val scale = readout
        if (scale != null) {
            ScaleBar(
                metersPerPixel = scale.metersPerPixel,
                // Top-start because MapLibre puts its own logo and the
                // attribution button bottom-left, where a scale bar would
                // normally live.
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }

        if (scale?.adrift == true) {
            SmallFloatingActionButton(
                onClick = {
                    val map = loadedMap
                    val bounds = dataBounds
                    if (map != null && bounds != null && !destroyed.get()) {
                        runCatching {
                            map.animateCamera(
                                CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PX)
                            )
                        }
                    }
                },
                // Deliberately not the primary container colour: this is a
                // way back, not the thing to do on this screen.
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_fit_bounds),
                    contentDescription = stringResource(R.string.map_recenter),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun Context.readAsset(name: String): String =
    assets.open(name).use { it.readBytes().decodeToString() }

/**
 * Replace whatever this file owns on [style] with a drawing of [subject], and
 * frame it if [fitCamera] says this is new data rather than a re-render.
 */
private fun renderSubject(
    map: MapLibreMap,
    style: Style,
    subject: MapSubject,
    palette: MapPalette,
    bounds: LatLngBounds?,
    density: Float,
    fitCamera: Boolean,
) {
    // Always tear down previous overlays first; addSource/addLayer throw on
    // collisions, and we rebuild wholesale on every change.
    clearOverlays(style)

    when (subject) {
        is MapSubject.Track -> renderTrack(style, subject.points, palette, density)
        is MapSubject.Places -> renderPlaces(style, subject.entries, palette)
    }

    if (!fitCamera || subject.isEmpty) return

    // A subject with no area to frame - one point, or several in the same
    // spot - has no bounds to fit, so it gets a fixed zoom instead.
    if (bounds == null) {
        val anchor = subject.firstCoordinate() ?: return
        map.cameraPosition = CameraPosition.Builder()
            .target(anchor)
            .zoom(14.0)
            .build()
    } else {
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PX))
    }
}

/** The travelled line, its gaps, its direction and its two endpoints. */
private fun renderTrack(
    style: Style,
    points: List<PathPoint>,
    palette: MapPalette,
    density: Float,
) {
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

        // 3a. Which way. The runs are built in time order, so MapLibre's own
        //     line placement points these the way the user walked.
        //
        //     Off entirely above ROUTE_MODE_MAX_POINTS, and faded out below
        //     zoom 13: those are the two conditions under which an arrow says
        //     nothing. A year of history has no direction to show, and a
        //     country-wide view has no room to show one.
        if (points.size <= ROUTE_MODE_MAX_POINTS) {
            style.addImage(IMAGE_TRACK_ARROW, chevronBitmap(palette.trackCore, density))
            addBelowLabels(
                SymbolLayer(LAYER_TRACK_ARROWS, SOURCE_TRACK).withProperties(
                    symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                    symbolSpacing(ARROW_SPACING_PX),
                    iconImage(IMAGE_TRACK_ARROW),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    // Let MapLibre drop chevrons that would collide rather
                    // than stacking them where the track doubles back.
                    iconAllowOverlap(false),
                    iconIgnorePlacement(false),
                    iconOpacity(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(12.5, 0f),
                            Expression.stop(13.5, 0.85f),
                        )
                    ),
                )
            )
        }
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
}

/**
 * Places as dots sized by how often they were visited.
 *
 * Size rather than colour, because the first question a places map answers is
 * "which of these is my life and which is a one-off", and area reads as
 * quantity without a legend. The scale is piecewise and flattens fast: one
 * visit against 335 - the range in the reference export - is a factor of 335,
 * and a dot 335 times the area of another would be the size of a city.
 *
 * No labels. The list under the map already names every row, and 262 place
 * names would bury the map they are drawn on.
 */
private fun renderPlaces(style: Style, entries: List<PlaceEntry>, palette: MapPalette) {
    if (entries.isEmpty()) return

    // Built as GeoJSON text rather than through Feature and a properties
    // object: the values come from our own model, the app already writes
    // GeoJSON by hand elsewhere, and this needs no gson types on the classpath.
    style.addSource(GeoJsonSource(SOURCE_PLACES, placesGeoJson(entries)))

    // The halo is what keeps a cluster of overlapping dots readable: the
    // fills blend into one blob, the strokes do not.
    style.addLayer(
        CircleLayer(LAYER_PLACES_HALO, SOURCE_PLACES).withProperties(
            circleColor(palette.placeFill),
            circleOpacity(0.16f),
            circleBlur(0.6f),
            circleRadius(visitScaled(7f, 10f, 15f, 19f, 24f)),
        )
    )
    style.addLayer(
        CircleLayer(LAYER_PLACES, SOURCE_PLACES).withProperties(
            circleColor(palette.placeFill),
            circleOpacity(0.85f),
            circleRadius(visitScaled(3.5f, 5.5f, 8f, 10.5f, 13f)),
            circleStrokeColor(palette.markerStroke),
            circleStrokeWidth(1.6f),
        )
    )
}

/** Circle radius interpolated on the visit count, at 1, 5, 25, 100 and 400. */
private fun visitScaled(one: Float, five: Float, many: Float, lots: Float, most: Float): Expression =
    Expression.interpolate(
        Expression.linear(), Expression.get(PROPERTY_VISITS),
        Expression.stop(1, one),
        Expression.stop(5, five),
        Expression.stop(25, many),
        Expression.stop(100, lots),
        Expression.stop(400, most),
    )

/**
 * A FeatureCollection of the places, carrying the visit count the radius
 * interpolates on.
 *
 * Coordinates are formatted through `Locale.ROOT`: a phone set to a locale
 * with a decimal comma would otherwise emit `13,00554`, which is two numbers
 * to a JSON parser and a blank map to the user.
 */
private fun placesGeoJson(entries: List<PlaceEntry>): String = buildString {
    append("{\"type\":\"FeatureCollection\",\"features\":[")
    entries.forEachIndexed { index, entry ->
        if (index > 0) append(',')
        append("{\"type\":\"Feature\",\"properties\":{\"")
        append(PROPERTY_VISITS)
        append("\":")
        append(entry.visits)
        append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        append(String.format(Locale.ROOT, "%.6f", entry.longitude))
        append(',')
        append(String.format(Locale.ROOT, "%.6f", entry.latitude))
        append("]}}")
    }
    append("]}")
}

/**
 * The box the whole subject fits in, or null when there is nothing to frame.
 *
 * MapLibre's builder rejects fewer than two coordinates, and data that is all
 * in the same spot builds a zero-area box the camera cannot fit either. Both
 * are answered with null rather than an exception, because both are ordinary:
 * one sample, or an afternoon spent indoors.
 */
private fun subjectBounds(subject: MapSubject): LatLngBounds? {
    val anchor = subject.firstCoordinate() ?: return null
    // Min/max is tracked on our own copy of the numbers rather than read back
    // off the built box: LatLngBounds' accessors differ between MapLibre
    // versions, and the condition is about the data, not about the box.
    var minLat = anchor.latitude
    var maxLat = anchor.latitude
    var minLon = anchor.longitude
    var maxLon = anchor.longitude
    var count = 0
    val builder = LatLngBounds.Builder()
    subject.forEachCoordinate { lat, lon ->
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
        builder.include(LatLng(lat, lon))
        count++
    }
    if (count < 2) return null
    if (minLat == maxLat && minLon == maxLon) return null
    return runCatching { builder.build() }.getOrNull()
}

/**
 * Remove every layer and source this file owns from [style]. Called both
 * before a re-render (addSource/addLayer collide on existing ids) and on
 * teardown, where dropping the native sources before MapView.onDestroy() is
 * what keeps our Java GeoJsonSource wrappers from outliving their C++ peers
 * (maplibre-native#3269). Layers go first — a source with a layer still
 * attached cannot be removed.
 */
private fun clearOverlays(style: Style) {
    OWNED_LAYERS.forEach { id -> style.getLayer(id)?.let { style.removeLayer(it) } }
    OWNED_SOURCES.forEach { id -> style.getSource(id)?.let { style.removeSource(it) } }
    runCatching { style.removeImage(IMAGE_TRACK_ARROW) }
}

/**
 * The chevron repeated along the track, drawn rather than shipped as a
 * resource: it has to be the palette's track colour, and a drawable would
 * mean carrying two of them plus the tinting to pick between them.
 *
 * Pointing right (+x), which is the direction MapLibre treats as "along the
 * line" before it rotates the sprite to match.
 */
private fun chevronBitmap(colorHex: String, density: Float): Bitmap {
    val size = (13f * density).toInt().coerceAtLeast(9)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val stroke = size * 0.16f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorHex.toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // Inset by the stroke so the round caps are not clipped by the edges.
    val inset = stroke
    val path = Path().apply {
        moveTo(inset + size * 0.12f, inset)
        lineTo(size - inset, size / 2f)
        lineTo(inset + size * 0.12f, size - inset)
    }
    canvas.drawPath(path, paint)
    return bitmap
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
private fun haversineMeters(a: PathPoint, b: PathPoint): Double =
    haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)

private fun haversineMeters(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
    val lat1 = Math.toRadians(lat1Deg)
    val lat2 = Math.toRadians(lat2Deg)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(lon2Deg - lon1Deg)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
}

// ---------- On-map chrome ----------

/** What one camera-idle told us about the view. */
private data class MapReadout(
    /** Metres one screen pixel covers, measured across the middle of the view. */
    val metersPerPixel: Double,
    /** True when the camera has left the fitted track behind. */
    val adrift: Boolean,
)

/**
 * Measure the current view.
 *
 * The scale is measured rather than derived from the zoom level: the
 * relationship between zoom, screen density and metres on the ground is a
 * convention that has changed between renderers, and two points projected
 * back out of the view cannot be wrong about it. It also stays correct if the
 * camera is ever tilted, where a formula would not be.
 */
private fun readCamera(map: MapLibreMap, view: View, bounds: LatLngBounds?): MapReadout? {
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) return null

    val projection = map.projection
    val y = height / 2f
    val left = projection.fromScreenLocation(PointF(width * 0.25f, y))
    val right = projection.fromScreenLocation(PointF(width * 0.75f, y))
    val spanMeters = haversineMeters(left.latitude, left.longitude, right.latitude, right.longitude)
    if (!spanMeters.isFinite() || spanMeters <= 0.0) return null
    val metersPerPixel = spanMeters / (width * 0.5)

    return MapReadout(metersPerPixel, isAdrift(map, bounds, metersPerPixel, width))
}

/**
 * Whether the camera has wandered far enough from the fitted view to be worth
 * offering a way back.
 *
 * Compared against where the camera *would* sit if the track were fitted now,
 * rather than against where it was left after the last fit: the user can zoom
 * back out by hand, and a button offering to undo something they have already
 * undone is noise.
 */
private fun isAdrift(
    map: MapLibreMap,
    bounds: LatLngBounds?,
    metersPerPixel: Double,
    widthPx: Int,
): Boolean {
    if (bounds == null) return false
    val padding = intArrayOf(FIT_PADDING_PX, FIT_PADDING_PX, FIT_PADDING_PX, FIT_PADDING_PX)
    val fitted = map.getCameraForLatLngBounds(bounds, padding) ?: return false
    val home = fitted.target ?: return false
    val current = map.cameraPosition
    val here = current.target ?: return false
    if (abs(current.zoom - fitted.zoom) > ADRIFT_ZOOM_LEVELS) return true
    val drift = haversineMeters(here.latitude, here.longitude, home.latitude, home.longitude)
    return drift > ADRIFT_SCREEN_FRACTION * widthPx * metersPerPixel
}

/** Widest the scale bar is allowed to be drawn. */
private val SCALE_BAR_MAX_WIDTH = 96.dp

/**
 * A scale bar in the top corner of the map.
 *
 * The basemap is deliberately near-monochrome and thin on labels, which costs
 * the reader the usual cues for how big anything is: the same knot of track
 * could be a campus or a city. One bar of known length gives that back, and
 * costs a corner of the view.
 *
 * The bar is a round distance drawn at whatever width that comes to, not a
 * fixed width labelled with whatever distance it works out as — "137 m" makes
 * the reader do arithmetic that "100 m" does for them.
 */
@Composable
private fun ScaleBar(metersPerPixel: Double, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val maxMeters = with(density) { SCALE_BAR_MAX_WIDTH.toPx() } * metersPerPixel
    if (!maxMeters.isFinite() || maxMeters < 1.0) return

    val meters = niceDistance(maxMeters)
    val barWidth = with(density) { (meters / metersPerPixel).toFloat().toDp() }
    val label = if (meters >= 1000.0) {
        stringResource(R.string.map_scale_km, "%,d".format((meters / 1000.0).roundToInt()))
    } else {
        stringResource(R.string.map_scale_m, "%,d".format(meters.roundToInt()))
    }

    Surface(
        // Translucent rather than opaque: the bar has to be readable over both
        // basemaps without becoming a panel sitting on top of the map.
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(2.dp)
                    .background(LocalContentColor.current),
            )
        }
    }
}

/**
 * The largest 1, 2 or 5 times a power of ten that fits in [meters].
 *
 * Those three are what a reader can divide by in their head, which is the
 * whole job of a scale bar.
 */
private fun niceDistance(meters: Double): Double {
    val magnitude = 10.0.pow(floor(log10(meters)))
    val leading = meters / magnitude
    val step = when {
        leading >= 5.0 -> 5.0
        leading >= 2.0 -> 2.0
        else -> 1.0
    }
    return step * magnitude
}
