package io.github.nikkittap.timelineexporter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import org.maplibre.android.camera.CameraUpdate
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
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textMaxWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TimelineMap"

private const val SOURCE_TRACK = "track-source"
private const val SOURCE_GAPS = "gaps-source"
private const val SOURCE_START = "start-source"
private const val SOURCE_END = "end-source"
private const val SOURCE_PLACES = "places-source"
private const val LAYER_TRACK_GLOW = "track-glow"
private const val LAYER_TRACK = "track-line"
private const val LAYER_TRACK_ARROWS = "track-arrows"
private const val LAYER_TRACK_SELECTED = "track-selected"
private const val LAYER_GAPS = "gaps-line"
private const val LAYER_START_HALO = "start-halo"
private const val LAYER_START = "start-circle"
private const val LAYER_END_HALO = "end-halo"
private const val LAYER_END = "end-circle"
private const val LAYER_PLACES_HALO = "places-halo"
private const val LAYER_PLACES = "places-circle"
private const val SOURCE_PLACE_SELECTED = "place-selected-source"
private const val LAYER_PLACE_SELECTED_RING = "place-selected-ring"
private const val LAYER_PLACE_SELECTED_LABEL = "place-selected-label"

/** Feature property the places circle radius interpolates on. */
private const val PROPERTY_VISITS = "visits"

/**
 * Feature property carrying [PlaceEntry.key], so a tap on a dot can be
 * turned back into the row it belongs to. Without it the places layer is a
 * picture: MapLibre hands back the feature it hit, and a feature with no
 * identity answers nothing.
 */
private const val PROPERTY_KEY = "key"

/**
 * Feature properties carrying the two colours a track line is drawn in, and
 * the day it belongs to. Every track feature carries all three in both modes:
 * see the note in [prepareTrack] for why the monochrome track is data-driven
 * too rather than painted with a constant.
 */
private const val PROPERTY_CORE_COLOR = "coreColor"
private const val PROPERTY_GLOW_COLOR = "glowColor"
private const val PROPERTY_DAY_DATE = "dayDate"

/**
 * How far from a dot a tap still counts as hitting it, in dp.
 *
 * The dots are 3.5-13 px of radius; a fingertip is about 9 mm. Querying the
 * tap point alone would make the one-visit places — the ones a user most
 * needs to identify — practically untappable.
 */
private const val TAP_SLOP_DP = 18f

/**
 * Where the camera lands when a place is picked from the list. Clamped at
 * both ends: from a continent-wide view "where is that" means zooming in,
 * and from a street-level one it does not mean zooming back out to it.
 */
private const val SELECT_ZOOM_MIN = 12.0
private const val SELECT_ZOOM_MAX = 16.0
private const val SELECT_CAMERA_MS = 600

/** Font stack for the selected place's label; present in both basemaps. */
private const val FONT_LABEL = "Noto Sans Regular"

/** Bottom-to-top; also the order [clearOverlays] tears them down in reverse. */
private val OWNED_LAYERS = listOf(
    LAYER_TRACK_GLOW, LAYER_GAPS, LAYER_TRACK, LAYER_TRACK_ARROWS, LAYER_TRACK_SELECTED,
    LAYER_START_HALO, LAYER_START, LAYER_END_HALO, LAYER_END,
    LAYER_PLACES_HALO, LAYER_PLACES,
    LAYER_PLACE_SELECTED_RING, LAYER_PLACE_SELECTED_LABEL,
)
private val OWNED_SOURCES = listOf(
    SOURCE_TRACK, SOURCE_GAPS, SOURCE_START, SOURCE_END, SOURCE_PLACES,
    SOURCE_PLACE_SELECTED,
)

/** The selection overlay alone, rebuilt on its own without a full re-render. */
private val SELECTION_LAYERS = listOf(LAYER_PLACE_SELECTED_RING, LAYER_PLACE_SELECTED_LABEL)

/**
 * How much heavier and how much more opaque the selected day's line is drawn
 * than the same line unselected.
 *
 * A lift rather than a colour, because the colour is the day's identity: the
 * legend, the sheet row and the callout all name this day by that swatch, and
 * repainting the one line the user just picked would break the only mapping
 * the feature has. Weight is the free channel.
 */
private const val SELECTED_WIDTH_LIFT = 2.1f
private const val SELECTED_OPACITY = 0.95f

/**
 * Where the camera stops when it is framing one day.
 *
 * [DAY_FOCUS_ZOOM_MAX] is the ceiling for a day with a real box to fit: an
 * afternoon spent on one street would otherwise be framed at the scale of the
 * street, which tells the reader nothing about where it is.
 * [DAY_FOCUS_ZOOM_POINT] is for a day with no box at all - one sample, or
 * several from the same spot.
 */
private const val DAY_FOCUS_ZOOM_MAX = 16.0
private const val DAY_FOCUS_ZOOM_POINT = 15.0
private const val DAY_FOCUS_CAMERA_MS = 700

/**
 * Room to keep clear at the bottom of any fitted view for the map's own
 * chrome: the control stack, the scale bar, and MapLibre's attribution.
 */
private val MAP_BOTTOM_CHROME = 56.dp

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
internal const val GAP_DISTANCE_METERS = 80_000.0

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Gap, in pixels, between the fitted track and the edges of the view. */
private const val FIT_PADDING_PX = 80

/**
 * Room to leave around a fitted view, in screen pixels.
 *
 * The top is the one that moves: the `Color by day` card is painted over the
 * map and grows a row when the legend appears, and a route framed underneath
 * it is a route the user cannot see. One of these is shared by both things
 * that frame the camera - "Show the whole track" and focusing a selected day -
 * so the two cannot drift into two slightly different ideas of the edges.
 */
private data class MapInsets(val top: Int, val bottom: Int, val side: Int = FIT_PADDING_PX)

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

/** Cap on the "Show all days" sheet's height, so a year of history scrolls inside it rather than pushing it off the top of the screen. */
private val DAY_SHEET_MAX_HEIGHT = 480.dp

/**
 * How much heavier the core line is drawn when it carries a colour per day.
 * Accumulation in one colour reads as brightness; accumulation across seven
 * reads as mud, so the trace has to hold its own hue rather than stack - but
 * only by a factor, so the density tuning underneath still applies.
 */
private const val COLOR_WIDTH_LIFT = 1.35f
private const val COLOR_OPACITY_LIFT = 2.2f
private const val COLOR_OPACITY_MAX = 0.85f

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
    /** Ring around the one place the list and the map both have selected. */
    val placeSelected: String,
    /** The selected place's name, drawn under its dot. */
    val placeLabelText: String,
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
    placeSelected = "#E5533D",
    placeLabelText = "#33302B",
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
    placeSelected = "#FF6B57",
    placeLabelText = "#EAF1F8",
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

    /** What to write in a log line, in place of every sample in the file. */
    val summary: String
        get() = when (this) {
            is Track -> "Track(${points.size} points)"
            is Places -> "Places(${entries.size} entries)"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineMap(
    subject: MapSubject,
    modifier: Modifier = Modifier,
    /** Whether this map is currently shown full screen — decides which of the two fullscreen-control icons is drawn. */
    fullscreen: Boolean = false,
    /** A tap on the fullscreen control, or `null` to not draw one at all. */
    onToggleFullscreen: (() -> Unit)? = null,
    /**
     * The place the screen currently has selected, if any. Drawn as a ring
     * around its dot, and the camera is eased to it when the selection came
     * from somewhere other than a tap on this map — which is what makes a row
     * in the list answer "where is that".
     */
    selectedPlace: PlaceEntry? = null,
    /** The selected place's display name, drawn under its dot. */
    selectedPlaceName: String? = null,
    /**
     * A tap on a place dot, or `null` for a tap on empty map. The caller
     * decides what selection means; this only reports what was hit.
     */
    onPlaceSelected: (PlaceEntry?) -> Unit = {},
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
    var colorByDay by rememberSaveable { mutableStateOf(false) }
    var showDayList by rememberSaveable { mutableStateOf(false) }
    val fallbackZone = remember { ZoneId.systemDefault() }
    val locale = LocalConfiguration.current.locales[0]
    val colorByDayLabel = stringResource(R.string.map_color_by_day)
    val showAllDaysLabel = stringResource(R.string.map_show_all_days)
    // Built once per locale rather than once per recomposition: the callout
    // below is recomposed on every selection change, and building a localized
    // formatter reads locale data.
    val calloutFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    }

    // One selected day, and only one. The map's own layers, the map's tap
    // handling, the compact legend, the sheet's list and the camera all read
    // this - the alternative is four ideas of "the current date" that agree
    // until they don't. Held as an epoch day because that is a day's identity
    // in TrackDays.kt already, and because it is something rememberSaveable
    // can put in a Bundle across a rotation.
    var selectedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedDay = remember(selectedEpochDay) { selectedEpochDay?.let(LocalDate::ofEpochDay) }

    // The day the camera has been asked to frame, which is deliberately not
    // the same thing as the selected day. Picking a date out of the list means
    // "take me there"; touching a line already on screen does not, and moving
    // the camera out from under that finger would throw away the view the user
    // picked it from - the same distinction `selfSelected` draws for places.
    var focusDay by remember { mutableStateOf<LocalDate?>(null) }
    // Sprites are added to the style in device pixels; MapLibre has no way to
    // be told a bitmap was authored in dp.
    val density = context.resources.displayMetrics.density

    // Every local day the track draws a line for, whether or not the camera is
    // pointed at it: the sheet's "All days" list and the fallback below both
    // describe the whole subject, so this waits on neither the style nor an
    // idle. Flipping the switch is the only thing that should make it appear,
    // and one pass over the samples is cheap arithmetic next to the GeoJSON
    // build the render effect already does off-thread.
    var trackDays by remember { mutableStateOf<List<LocalDate>>(emptyList()) }

    // The days with a line inside the current view. The same scan under the
    // same rules, one window narrower, which is what makes the two counts
    // comparable — and so what lets the row below say "all of them" when they
    // agree, instead of comparing a count of drawn days against a count of
    // dates in the file and never quite matching.
    //
    // Null and empty are different answers, and the difference is the whole
    // honesty of the row below: null is "the map has not told us yet", where
    // describing the whole track is the only thing we can say, and empty is
    // "the camera is looking at nowhere in particular", where saying six days
    // over a screen with no track on it would be a lie.
    var visibleDays by remember { mutableStateOf<List<LocalDate>?>(null) }

    // What the camera last settled on, in plain degrees. Read on the main
    // thread the moment the map stops moving; turned into days off it.
    var viewWindow by remember { mutableStateOf<TrackWindow?>(null) }

    // How tall the `Color by day` card turned out to be, in pixels. What the
    // camera fit keeps clear, so a route is never framed underneath it. The
    // day scan does not use it: see [readWindow].
    var legendHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(subject, colorByDay, fallbackZone) {
        // A new file, a new filter or a flipped switch invalidates what we
        // last knew about the view; the idle listeners below say it again.
        viewWindow = null
        visibleDays = null
        val points = (subject as? MapSubject.Track)?.points
        trackDays = if (colorByDay && points != null) {
            withContext(Dispatchers.Default) { drawnTrackDays(points, fallbackZone) }
        } else {
            emptyList()
        }
    }

    LaunchedEffect(subject, colorByDay, fallbackZone, viewWindow) {
        val window = viewWindow
        val points = (subject as? MapSubject.Track)?.points
        visibleDays = if (colorByDay && points != null && window != null) {
            withContext(Dispatchers.Default) { drawnTrackDays(points, fallbackZone, window) }
        } else {
            null
        }
    }

    // The compact row describes the viewport, and falls back to the whole
    // track only where there is no viewport answer yet.
    val viewportDays = visibleDays ?: trackDays
    val daySummary = remember(viewportDays) { dayLegendSummary(viewportDays) }
    val dayRange = remember(daySummary, locale) { daySummary?.let { formatDayRange(it, locale) } }
    // With everything on screen "18 days in view" is a distinction without a
    // difference, so the row keeps the shorter wording it had before.
    val wholeTrackInView = visibleDays.let { it == null || it.size >= trackDays.size }

    // A selected day that the filters have just removed is a selection of
    // nothing: the highlight would have no line to draw and the callout would
    // name a date the track no longer covers. Switching `Color by day` off
    // empties `trackDays` too, which is what clears the selection with it.
    LaunchedEffect(trackDays) {
        val day = selectedEpochDay ?: return@LaunchedEffect
        if (trackDays.binarySearch(LocalDate.ofEpochDay(day)) < 0) selectedEpochDay = null
    }

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

    // Bumped after every successful render. A render clears every layer this
    // file owns, the selected day's highlight included, so the highlight
    // effect keys on this to put its layer back — otherwise the day the user
    // picked would quietly go back to being drawn like every other day the
    // first time the theme flipped or the filter moved.
    var renderVersion by remember { mutableIntStateOf(0) }

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

    // The key this map's own tap last reported. A selection the user made by
    // touching a dot they can already see must not also move the camera out
    // from under their finger; one made in the list must.
    val selfSelected = remember { AtomicReference<String?>(null) }

    // What every camera fit on this map leaves clear. Recomputed rather than
    // remembered because the top of it is the legend card, which grows a row
    // the moment `Color by day` is switched on.
    val insets = MapInsets(
        top = FIT_PADDING_PX + legendHeightPx,
        bottom = FIT_PADDING_PX + (MAP_BOTTOM_CHROME.value * density).toInt(),
    )

    // Where the camera is pointed, measured off the map itself.
    //
    // Four projections and no query. What stood here was
    // `queryRenderedFeatures` over the viewport, and it was wrong twice over.
    // It could only see geometry that had survived being tiled at the current
    // zoom, so a day spent inside one city vanished from the answer as soon as
    // the camera pulled back - the legend counted fewer days the more of the
    // track was on screen. And it ran on MapLibre's render thread, which is
    // where it took the process down with it whenever the GL surface was being
    // rebuilt underneath it: exactly what re-parenting this map into and out
    // of full screen does. Both are gone with the call.
    val refreshWindow by rememberUpdatedState {
        val map = loadedMap
        viewWindow = if (destroyed.get() || map == null) {
            null
        } else {
            runCatching { readWindow(map, mapView) }.getOrNull()
        }
    }

    // A map that arrives already framed - a file loaded into a screen the user
    // is not touching - gets its first window here; after that the idles below
    // own it.
    LaunchedEffect(loadedMap) { refreshWindow() }

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

    // Scale bar, "back to the track" and the day legend all describe the
    // camera, so all three are recomputed when it stops moving — once per
    // gesture, not per frame.
    DisposableEffect(loadedMap, mapView, dataBounds, insets) {
        val map = loadedMap ?: return@DisposableEffect onDispose { }
        val cameraIdle = MapLibreMap.OnCameraIdleListener {
            if (!destroyed.get()) {
                readout = runCatching { readCamera(map, mapView, dataBounds, insets) }.getOrNull()
                refreshWindow()
            }
        }
        // Two idles, because they answer different questions. Camera-idle
        // fires when the gesture has settled, which is what makes a pan feel
        // answered. Map-idle fires when the view has actually been drawn,
        // which is what fills the legend in on a map nobody has touched -
        // where the only camera move was the fit this file performed itself,
        // and there may never be a gesture to settle.
        val mapIdle = MapView.OnDidBecomeIdleListener {
            if (!destroyed.get()) refreshWindow()
        }
        map.addOnCameraIdleListener(cameraIdle)
        mapView.addOnDidBecomeIdleListener(mapIdle)
        onDispose {
            runCatching { map.removeOnCameraIdleListener(cameraIdle) }
            runCatching { mapView.removeOnDidBecomeIdleListener(mapIdle) }
        }
    }

    // Re-render whenever the subject changes OR the style becomes available.
    LaunchedEffect(subject, loadedStyle, colorByDay, fallbackZone) {
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
        // What it is and how big, never the subject itself: MapSubject.Track
        // is a data class wrapped around every sample in the file, and its
        // generated toString turns a year of history into tens of megabytes
        // of string on the main thread - built whether or not anything is
        // listening, since a Kotlin template is evaluated before the call.
        Log.d(TAG, "Rendering ${subject.summary}")

        // Turning a year of samples into GeoJSON - splitting runs at the
        // geographic gaps, cutting those into days, building the geometry -
        // is arithmetic over our own data and touches nothing on the map. It
        // is also the expensive half of a render, long enough to be felt as a
        // stall when the colour switch is flipped, so it happens off the main
        // thread. Handing the finished collection to MapLibre costs nothing
        // by comparison: the source keeps the object and tiles it on its own
        // workers. The previous overlay stays on screen throughout, which
        // also spares the user a flash of bare basemap mid-toggle.
        val overlay = withContext(Dispatchers.Default) {
            prepareOverlay(subject, palette, colorByDay, fallbackZone)
        }

        // Then back to the main thread, and named rather than assumed. Where
        // a coroutine resumes after `withContext` is decided by the
        // interceptor the host installed on this effect: in the app that is
        // AndroidUiDispatcher and the resumption lands on the main thread,
        // but under Compose's own test harness the interceptor is not a
        // dispatcher at all and the coroutine simply carries on down the
        // worker it was already on. MapLibre rejects every call from there,
        // and `runCatching` below would swallow the refusal into a map with
        // no overlay on it.
        withContext(Dispatchers.Main.immediate) {
            // The world may have moved while we were off the main thread. A
            // style swap restarts this effect, but the coroutine only learns
            // that here.
            if (destroyed.get() || loadedStyle !== style || !style.isFullyLoaded) {
                Log.d(TAG, "Style changed while preparing — dropping this render")
                return@withContext
            }

            // Last-resort net: a style can go invalid between the check above
            // and the native call. Losing the overlay beats aborting the
            // process.
            val fitCamera = lastFitted != subject
            runCatching {
                renderSubject(map, style, subject, overlay, palette, dataBounds, insets, density, fitCamera, colorByDay)
            }
                .onFailure { Log.w(TAG, "Map render failed", it) }
                .onSuccess {
                    if (fitCamera) lastFitted = subject
                    renderVersion++
                }

            // Seed the chrome. A map that is never touched never goes idle,
            // so waiting for the listener alone would mean no scale bar at
            // all on a track the user only looks at. The camera fit above is
            // animated, so this reading can be one frame stale; the first
            // idle corrects it.
            withFrameNanos { }
            if (!destroyed.get()) {
                readout = runCatching { readCamera(map, mapView, dataBounds, insets) }.getOrNull()
                // One frame in, so this can name the view the camera fit is
                // still flying towards rather than the one it lands on. That
                // is what the idles above are for; until one of them arrives
                // the legend describes the whole track, which is the honest
                // answer for a camera that has not settled yet.
                refreshWindow()
            }
        }
    }

    // Which place a tapped dot is. Built from the subject rather than looked
    // up by scanning the list on every tap: 262 entries is nothing, but this
    // also makes "the map is showing a track" a single empty-map check.
    val placesByKey = remember(subject) {
        (subject as? MapSubject.Places)?.entries?.associateBy { it.key } ?: emptyMap()
    }
    val latestPlaceSelected by rememberUpdatedState(onPlaceSelected)

    // A tap answers "which day is this line" only while the track is coloured
    // by day, because that is the only time a line has a day to answer with.
    val trackTapsOn = colorByDay && subject is MapSubject.Track

    DisposableEffect(loadedMap, placesByKey, trackTapsOn) {
        val map = loadedMap
        if (map == null || (placesByKey.isEmpty() && !trackTapsOn)) {
            return@DisposableEffect onDispose { }
        }
        val slop = TAP_SLOP_DP * density
        val listener = MapLibreMap.OnMapClickListener { latLng ->
            if (destroyed.get()) return@OnMapClickListener false
            val tap = runCatching { map.projection.toScreenLocation(latLng) }.getOrNull()
                ?: return@OnMapClickListener false
            // The same forgiving box in both modes, and for the same reason:
            // a 3.5 px dot and a 1 px line are both far smaller than the
            // fingertip aiming at them.
            val box = RectF(tap.x - slop, tap.y - slop, tap.x + slop, tap.y + slop)

            if (placesByKey.isNotEmpty()) {
                val hit = runCatching {
                    map.queryRenderedFeatures(box, LAYER_PLACES)
                        .mapNotNull { f -> f.getStringProperty(PROPERTY_KEY)?.let(placesByKey::get) }
                        // Nearest centre wins. Where a one-visit dot sits inside a
                        // 335-visit one, the small dot is the one the user aimed
                        // at — it is the one they cannot identify from the list.
                        .minByOrNull { entry ->
                            val at = map.projection.toScreenLocation(
                                LatLng(entry.latitude, entry.longitude)
                            )
                            hypot((at.x - tap.x).toDouble(), (at.y - tap.y).toDouble())
                        }
                }.getOrNull()
                if (!destroyed.get()) {
                    selfSelected.set(hit?.key)
                    latestPlaceSelected(hit)
                }
                // Consume only a hit: a tap on empty map still clears the
                // selection, but MapLibre keeps whatever else it does with it.
                return@OnMapClickListener hit != null
            }

            val day = runCatching { nearestTrackDay(map, box, latLng) }.getOrNull()
            if (!destroyed.get()) {
                // A tap on bare basemap clears the selection, which is the
                // only way back out of it that needs no chrome at all.
                selectedEpochDay = day?.toEpochDay()
            }
            day != null
        }
        map.addOnMapClickListener(listener)
        onDispose { runCatching { map.removeOnMapClickListener(listener) } }
    }

    // Framing one day. Deliberately keyed on `focusDay` rather than on the
    // selection, so this runs for a date picked out of the list and not for a
    // line touched on the map, and clears its own request at the end so that
    // a later re-render cannot fly the camera somewhere the user did not ask
    // to go a second time.
    LaunchedEffect(focusDay, subject, loadedMap, fallbackZone) {
        val date = focusDay ?: return@LaunchedEffect
        val map = loadedMap ?: return@LaunchedEffect
        val track = subject as? MapSubject.Track
        if (track == null) {
            focusDay = null
            return@LaunchedEffect
        }
        // One pass over the samples for four numbers, off the main thread for
        // the same reason the overlay build is: a year of history is hundreds
        // of thousands of them, and this runs while a sheet is closing.
        val extent = withContext(Dispatchers.Default) {
            dayExtent(track.points, date, fallbackZone)
        }
        if (!destroyed.get() && extent != null) {
            runCatching {
                val update = extent.bounds
                    ?.let { boundsCameraUpdate(map, it, insets, DAY_FOCUS_ZOOM_MAX) }
                    ?: CameraUpdateFactory.newLatLngZoom(extent.anchor, DAY_FOCUS_ZOOM_POINT)
                map.animateCamera(update, DAY_FOCUS_CAMERA_MS)
            }.onFailure { Log.w(TAG, "Day focus camera failed", it) }
        }
        focusDay = null
    }

    // The selected day's highlight, its own layer over the track's own source:
    // no new geometry, just a filter on the day already carried by every
    // feature. Declared after the render effect and keyed on `renderVersion`
    // so a re-render, which drops every layer this file owns, is followed by
    // this putting the highlight back.
    LaunchedEffect(selectedDay, colorByDay, subject, loadedStyle, renderVersion) {
        if (destroyed.get()) return@LaunchedEffect
        val style = loadedStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        runCatching { clearDayHighlight(style) }
            .onFailure { Log.w(TAG, "Day highlight teardown failed", it) }
        val date = selectedDay ?: return@LaunchedEffect
        val track = subject as? MapSubject.Track ?: return@LaunchedEffect
        if (!colorByDay) return@LaunchedEffect
        runCatching { renderDayHighlight(style, date, track.points.size) }
            .onFailure { Log.w(TAG, "Day highlight render failed", it) }
    }

    // The selection overlay is its own source and its own effect, deliberately
    // separate from the render above: selecting a place must not rebuild 262
    // features, and must not count as new data and re-frame the camera.
    //
    // Declared after the render effect so that on a subject change — where
    // both re-run and the render clears every layer this file owns — this one
    // puts the ring back.
    LaunchedEffect(selectedPlace, selectedPlaceName, subject, loadedStyle, palette) {
        if (destroyed.get()) return@LaunchedEffect
        val map = loadedMap ?: return@LaunchedEffect
        val style = loadedStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        runCatching { clearSelection(style) }
            .onFailure { Log.w(TAG, "Selection teardown failed", it) }
        val place = selectedPlace ?: return@LaunchedEffect
        if (subject !is MapSubject.Places) return@LaunchedEffect
        runCatching { renderSelection(style, place, selectedPlaceName, palette) }
            .onFailure { Log.w(TAG, "Selection render failed", it) }

        // Only when the selection arrived from outside this map. A ring drawn
        // around a dot the user just touched is the whole answer; moving the
        // camera as well would throw away the view they picked it from.
        if (place.key != selfSelected.get()) {
            runCatching {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(place.latitude, place.longitude),
                        map.cameraPosition.zoom.coerceIn(SELECT_ZOOM_MIN, SELECT_ZOOM_MAX),
                    ),
                    SELECT_CAMERA_MS,
                )
            }.onFailure { Log.w(TAG, "Selection camera failed", it) }
            // The camera has now been where this selection asked it to go.
            // Renaming the place re-runs this effect, and flying to the same
            // spot a second time would look like a glitch.
            selfSelected.set(place.key)
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Offer the switch only where flipping it would change something.
        // "Two or more samples" is not that test: a pair of samples further
        // apart than GAP_DISTANCE_METERS - a flight with location off in
        // between - draws only the dashed connector and no track layer at
        // all, so the switch would toggle nothing and the legend would never
        // arrive. Ask instead whether any consecutive pair is close enough to
        // become a line; the answer is normally the first pair tested.
        val hasTrack = remember(subject) {
            subject is MapSubject.Track && subject.points.asSequence().zipWithNext()
                .any { (a, b) -> haversineMeters(a, b) <= GAP_DISTANCE_METERS }
        }
        // This card is now only about colouring the track — exiting fullscreen
        // moved to its own control below, always in the same spot regardless
        // of whether this card is even showing.
        if (hasTrack) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Measured outside its own padding, because the strip the
                    // camera fit has to keep clear is the whole one it sits in.
                    .onSizeChanged { legendHeightPx = it.height }
                    .padding(12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                // Translucent enough that a route crossing behind it stays
                // visible, which is what lets the day count describe the whole
                // view and still agree with what is on screen. Not so
                // translucent that a label has to compete with the basemap:
                // the surface still carries the text, the map only shows
                // through it.
                color = MaterialTheme.colorScheme.surface.copy(alpha = LEGEND_CARD_ALPHA),
                // A hairline instead of a shadow. Android draws an elevation
                // shadow as a blurred silhouette *behind* the view, which an
                // opaque view then hides; at 0.82 this one did not, and the
                // inward half of that blur read as a grey frame about the
                // width of the card's own inset - with the card's middle
                // sitting inside it like a second, lighter panel. The border
                // separates the card from the map without anything to show
                // through.
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            colorByDayLabel,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = colorByDay,
                            onCheckedChange = { colorByDay = it },
                            modifier = Modifier.semantics { contentDescription = colorByDayLabel },
                        )
                    }
                    if (colorByDay && trackDays.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        // A bounded summary rather than one swatch per day: the
                        // row this replaced put every date in a horizontal
                        // scroller, which was fine for a week and unusable for
                        // the years of history this app is built to handle.
                        // Tapping it opens the full, scrollable list instead —
                        // which is why the row stays even when the camera is
                        // looking at nothing, where it says so and remains the
                        // way to the complete list.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDayList = true }
                                .semantics { contentDescription = showAllDaysLabel }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                daySummary?.hintDates?.forEach { date ->
                                    Box(
                                        Modifier.size(8.dp)
                                            .background(Color(trackDayColor(date).toColorInt()), RoundedCornerShape(4.dp)),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            // Count over span, two lines, always reserved. One
                            // line holding both is a line whose length is the
                            // sum of a translated plural and a localized date
                            // range: it fits in English over one year and
                            // wraps in German, in Russian, or as soon as the
                            // range crosses into a second year - so the card
                            // changed height as the user panned, which is the
                            // one thing a panel pinned over a map must not do.
                            // The height is fixed explicitly rather than left
                            // to a second, blank Text to hold open - an empty
                            // string measures shorter than a real line, which
                            // pinned the count to the top of the row instead
                            // of centering it when the camera looks at
                            // nowhere in particular (0 days in view).
                            val twoLineHeight = with(LocalDensity.current) {
                                MaterialTheme.typography.labelMedium.lineHeight.toDp() +
                                    MaterialTheme.typography.labelSmall.lineHeight.toDp()
                            }
                            Column(
                                modifier = Modifier.weight(1f).height(twoLineHeight),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    pluralStringResource(
                                        if (daySummary != null && wholeTrackInView) R.plurals.map_day_count
                                        else R.plurals.map_day_count_in_view,
                                        daySummary?.dayCount ?: 0,
                                        daySummary?.dayCount ?: 0,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                dayRange?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(
                                "›",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // The whole answer to "which day is this line": the date, in that
        // day's own colour, near the map rather than over it. Deliberately not
        // a bottom sheet — a modal that covered the map every time a track was
        // touched would hide the thing the tap was asking about. Tapping it
        // clears the selection.
        val callout = selectedDay?.takeIf { colorByDay }
        if (callout != null) {
            val calloutText = remember(callout, locale) { callout.format(calloutFormatter) }
            val calloutLabel = stringResource(R.string.map_selected_day, calloutText)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Wide side margins rather than a measured position: this
                    // has to miss the control stack in one corner and the
                    // scale bar and attribution in the other.
                    .padding(start = 72.dp, end = 72.dp, bottom = 14.dp)
                    .clickable { selectedEpochDay = null }
                    .semantics { contentDescription = calloutLabel },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(10.dp)
                            .background(Color(trackDayColor(callout).toColorInt()), RoundedCornerShape(5.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(calloutText, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        val scale = readout
        if (scale != null) {
            ScaleBar(
                metersPerPixel = scale.metersPerPixel,
                // Above attribution, leaving the top edge to the unified controls.
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 40.dp),
            )
        }

        // The fullscreen toggle and "back to the track" stacked as one group
        // of map controls: same corner, same style, same touch target, so a
        // control that appears and disappears (the latter, only when the
        // camera has drifted) never shifts the other's position.
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) {
            if (onToggleFullscreen != null) {
                SmallFloatingActionButton(
                    onClick = onToggleFullscreen,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Icon(
                        painter = painterResource(if (fullscreen) R.drawable.ic_close_fullscreen else R.drawable.ic_open_in_full),
                        contentDescription = stringResource(if (fullscreen) R.string.map_exit_fullscreen else R.string.map_expand),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (scale?.adrift == true) {
                SmallFloatingActionButton(
                    onClick = {
                        val map = loadedMap
                        val bounds = dataBounds
                        if (map != null && bounds != null && !destroyed.get()) {
                            // Same padding path as focusing one day, so the
                            // two never disagree about where the edges of a
                            // usable view are. No zoom ceiling: framing all of
                            // the data means whatever the data needs.
                            runCatching {
                                map.animateCamera(boundsCameraUpdate(map, bounds, insets, maxZoom = null))
                            }.onFailure { Log.w(TAG, "Fit to data camera failed", it) }
                        }
                    },
                    // Deliberately not the primary container colour: this is a
                    // way back, not the thing to do on this screen.
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_route),
                        contentDescription = stringResource(R.string.map_recenter),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    if (showDayList && trackDays.isNotEmpty()) {
        DayLegendSheet(
            allDates = trackDays,
            visibleDates = visibleDays.orEmpty(),
            selectedDate = selectedDay,
            title = colorByDayLabel,
            locale = locale,
            onDayClick = { date ->
                selectedEpochDay = date.toEpochDay()
                focusDay = date
                showDayList = false
            },
            onDismiss = { showDayList = false },
        )
    }
}

/**
 * The full day list, in two views of the same set: the days the map can
 * currently show, and every day the filters left in the track.
 *
 * The tabs exist because the compact row above this sheet now describes the
 * viewport. Without them, zooming into a corner of a year would put the whole
 * year permanently out of reach; with them the row stays honest about what is
 * on screen and the complete list is still one tap away. Both are grouped
 * Year -> Month, most recent first, in a single LazyColumn: a year of history
 * is hundreds of entries, which is what a horizontal strip could never show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayLegendSheet(
    allDates: List<LocalDate>,
    /** Empty when the map has not answered yet, which disables the first tab. */
    visibleDates: List<LocalDate>,
    selectedDate: LocalDate?,
    title: String,
    locale: Locale,
    onDayClick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // Which tab the sheet opens on, decided once as it opens rather than kept
    // in sync with the map behind it: "In view", because that is what the row
    // the user just tapped was describing — unless the day they have selected
    // is not in view, where opening on a list that cannot show their selection
    // would be the one unhelpful choice.
    val inViewIsUseful = visibleDates.isNotEmpty() && visibleDates.size < allDates.size
    var inView by remember {
        mutableStateOf(inViewIsUseful && (selectedDate == null || selectedDate in visibleDates))
    }

    // Built only once the sheet is actually open, and only from the (already
    // distinct, already sorted) dates the caller scanned once off-thread —
    // grouping a few thousand of them into rows is arithmetic, not a query.
    val dates = if (inView) visibleDates else allDates
    val entries = remember(dates) { dayListEntries(dates) }
    val dayFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMM d", locale) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    // A day selected by touching its line on the map is somewhere in the
    // middle of a list the user has never scrolled. Put it on screen.
    LaunchedEffect(entries, selectedDate) {
        val index = dayEntryIndex(entries, selectedDate)
        if (index >= 0) runCatching { listState.scrollToItem(index) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        // The counts are the point of these labels: they are what tells the
        // reader whether the two lists differ at all, which is the whole
        // question when the map is zoomed somewhere in particular.
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            SegmentedButton(
                selected = inView,
                onClick = { inView = true },
                enabled = visibleDates.isNotEmpty(),
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.map_days_tab_in_view, visibleDates.size))
            }
            SegmentedButton(
                selected = !inView,
                onClick = { inView = false },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.map_days_tab_all, allDates.size))
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = DAY_SHEET_MAX_HEIGHT),
        ) {
            items(
                entries,
                key = { entry ->
                    when (entry) {
                        is DayListEntry.YearHeader -> "y${entry.year}"
                        is DayListEntry.MonthHeader -> "m${entry.month}"
                        is DayListEntry.Day -> entry.date.toEpochDay()
                    }
                },
            ) { entry ->
                when (entry) {
                    is DayListEntry.YearHeader -> Text(
                        entry.year.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                    is DayListEntry.MonthHeader -> Text(
                        entry.month.month.getDisplayName(TextStyle.FULL, locale),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                    )
                    is DayListEntry.Day -> {
                        val isSelected = entry.date == selectedDate
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                    else Color.Transparent
                                )
                                .clickable { onDayClick(entry.date) }
                                // Clickable already says the row can be
                                // activated; this is what says which one the
                                // map is currently showing.
                                .semantics { selected = isSelected }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Box(
                                Modifier.size(10.dp)
                                    .background(Color(trackDayColor(entry.date).toColorInt()), RoundedCornerShape(5.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(entry.date.format(dayFormatter), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun Context.readAsset(name: String): String =
    assets.open(name).use { it.readBytes().decodeToString() }

/**
 * The half of an overlay that is arithmetic over the export's own data:
 * splitting a track into continuous runs, cutting those into days, and
 * turning hundreds of thousands of samples into GeoJSON geometry.
 *
 * Separated from the drawing so it can be computed off the main thread. None
 * of it calls into MapLibre; the geometry objects are inert until a source on
 * the main thread is handed them.
 */
private sealed interface MapOverlay {
    /** Nothing to draw: an empty subject, or a track with no samples. */
    data object Empty : MapOverlay

    data class Track(
        /** Null when no run survived the gap split with two points to join. */
        val lines: FeatureCollection?,
        /** Null when nothing in the track is far enough apart to be a gap. */
        val gaps: Feature?,
        val start: Feature,
        val end: Feature,
        /** What the weights and the arrow cutoff are chosen from. */
        val pointCount: Int,
    ) : MapOverlay

    data class Places(val geoJson: String) : MapOverlay
}

/** Everything [renderSubject] will need, computed without touching the map. */
private fun prepareOverlay(
    subject: MapSubject,
    palette: MapPalette,
    colorByDay: Boolean,
    fallbackZone: ZoneId,
): MapOverlay = when (subject) {
    is MapSubject.Track -> prepareTrack(subject.points, palette, colorByDay, fallbackZone)
    is MapSubject.Places ->
        if (subject.entries.isEmpty()) MapOverlay.Empty
        else MapOverlay.Places(placesGeoJson(subject.entries))
}

/**
 * Replace whatever this file owns on [style] with [overlay], and frame it if
 * [fitCamera] says this is new data rather than a re-render.
 */
private fun renderSubject(
    map: MapLibreMap,
    style: Style,
    subject: MapSubject,
    overlay: MapOverlay,
    palette: MapPalette,
    bounds: LatLngBounds?,
    insets: MapInsets,
    density: Float,
    fitCamera: Boolean,
    colorByDay: Boolean,
) {
    // Always tear down previous overlays first; addSource/addLayer throw on
    // collisions, and we rebuild wholesale on every change.
    clearOverlays(style)

    when (overlay) {
        is MapOverlay.Track -> renderTrack(style, overlay, palette, density, colorByDay)
        is MapOverlay.Places -> renderPlaces(style, overlay.geoJson, palette)
        MapOverlay.Empty -> Unit
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
        map.animateCamera(boundsCameraUpdate(map, bounds, insets, maxZoom = null))
    }
}

/**
 * The travelled line, its gaps and its two endpoints, as GeoJSON.
 *
 * Every sample in the export passes through here, so this is the one place
 * where the cost is proportional to the size of the file rather than to the
 * size of the screen - and the reason the whole thing runs off the main
 * thread. Nothing below touches [Style].
 */
private fun prepareTrack(
    points: List<PathPoint>,
    palette: MapPalette,
    colorByDay: Boolean,
    fallbackZone: ZoneId,
): MapOverlay {
    if (points.isEmpty()) return MapOverlay.Empty

    // Split the ordered points into continuous runs, breaking wherever two
    // consecutive samples are too far apart to be a real travelled segment
    // (a flight, or a stretch with location services off). Each break is also
    // recorded as a "gap" connector so we can hint at it with a faint dash.
    val runs = mutableListOf<MutableList<PathPoint>>()
    val gaps = mutableListOf<List<Point>>()
    var current = mutableListOf(points.first())
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        if (haversineMeters(prev, cur) > GAP_DISTANCE_METERS) {
            // Close the current run and remember the jump between the two
            // endpoints, then start a fresh run at the far side.
            runs += current
            gaps += listOf(prev.toPoint(), cur.toPoint())
            current = mutableListOf(cur)
        } else {
            current += cur
        }
    }
    runs += current

    // Both colours travel on the features, in both modes, and both line
    // layers read them through the same expression. That is not decoration:
    // swapping the mode replaces the source and the layers under the same
    // ids, and for one frame MapLibre pairs one side's paint with the other
    // side's data. When only the coloured mode was data-driven, that frame
    // asked features with no dayColor for their dayColor, `to-color` failed,
    // and the whole track flashed the spec default - black - before settling.
    // Give every feature the colour it should be drawn in and the mismatched
    // frame simply draws the right colour.
    val drawableRuns = runs.filter { it.size >= 2 }
    val features = when {
        drawableRuns.isEmpty() -> null
        colorByDay -> drawableRuns.flatMap { splitTrackDays(it, fallbackZone) }.map { run ->
            val color = trackDayColor(run.date)
            Feature.fromGeometry(LineString.fromLngLats(run.points.map { it.toPoint() })).apply {
                addStringProperty(PROPERTY_CORE_COLOR, color)
                addStringProperty(PROPERTY_GLOW_COLOR, color)
                addStringProperty(PROPERTY_DAY_DATE, run.date.toString())
            }
        }
        else -> listOf(
            Feature.fromGeometry(
                MultiLineString.fromLngLats(drawableRuns.map { run -> run.map { it.toPoint() } })
            ).apply {
                addStringProperty(PROPERTY_CORE_COLOR, palette.trackCore)
                addStringProperty(PROPERTY_GLOW_COLOR, palette.trackGlow)
            }
        )
    }

    return MapOverlay.Track(
        lines = features?.let(FeatureCollection::fromFeatures),
        gaps = if (gaps.isEmpty()) null else Feature.fromGeometry(MultiLineString.fromLngLats(gaps)),
        start = Feature.fromGeometry(points.first().toPoint()),
        end = Feature.fromGeometry(points.last().toPoint()),
        pointCount = points.size,
    )
}

/** Attach the prepared track to the style: one source, four layers of paint. */
private fun renderTrack(
    style: Style,
    overlay: MapOverlay.Track,
    palette: MapPalette,
    density: Float,
    colorByDay: Boolean,
) {
    // Everything we draw goes underneath the basemap's own labels, so place
    // names stay readable through a dense track. Layers inserted below the
    // same reference stack in insertion order, so this call sequence is also
    // the bottom-to-top paint order.
    val firstLabelLayer = style.layers.firstOrNull { it is SymbolLayer }?.id
    fun addBelowLabels(layer: Layer) {
        if (firstLabelLayer != null) style.addLayerBelow(layer, firstLabelLayer)
        else style.addLayer(layer)
    }

    val weights = heatWeightsFor(overlay.pointCount)

    if (overlay.lines != null) {
        // Attached first and filled second, through the one setter whose
        // behaviour is documented; the source keeps the collection and tiles
        // it on its own workers, so this call returns immediately even for a
        // year of history.
        style.addSource(GeoJsonSource(SOURCE_TRACK).apply { setGeoJson(overlay.lines) })

        // 1. Glow. Wide, blurred and almost invisible on its own; where many
        //    traces overlap the alpha stacks up and the corridor blooms.
        addBelowLabels(
            LineLayer(LAYER_TRACK_GLOW, SOURCE_TRACK).withProperties(
                lineColor(Expression.toColor(Expression.get(PROPERTY_GLOW_COLOR))),
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
    if (overlay.gaps != null) {
        style.addSource(GeoJsonSource(SOURCE_GAPS, overlay.gaps))
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
    //
    //    Colour by day lifts both weights, because seven hues at heat-map
    //    alpha are seven greys - but it lifts them *proportionally* rather
    //    than replacing them with constants. The whole point of the
    //    [heatWeightsFor] table is that a year of history and a single walk
    //    cannot be drawn at the same weight, and that stays true in colour:
    //    pinning every track to a fat, near-opaque line turns a year of city
    //    driving into one solid slab in which no day is visible at all.
    val coreWidth = if (colorByDay) weights.coreWidth * COLOR_WIDTH_LIFT else weights.coreWidth
    val coreOpacity =
        if (colorByDay) minOf(weights.coreOpacity * COLOR_OPACITY_LIFT, COLOR_OPACITY_MAX)
        else weights.coreOpacity
    if (overlay.lines != null) {
        addBelowLabels(
            LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
                lineColor(Expression.toColor(Expression.get(PROPERTY_CORE_COLOR))),
                lineWidth(zoomScaledWidth(coreWidth)),
                lineOpacity(zoomScaledOpacity(coreOpacity)),
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
        if (overlay.pointCount <= ROUTE_MODE_MAX_POINTS) {
            style.addImage(IMAGE_TRACK_ARROW, chevronBitmap(if (colorByDay) "#FFFFFF" else palette.trackCore, density))
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
    style.addSource(GeoJsonSource(SOURCE_START, overlay.start))
    style.addLayer(endpointHalo(LAYER_START_HALO, SOURCE_START, palette.startFill))
    style.addLayer(endpointDot(LAYER_START, SOURCE_START, palette.startFill, palette.markerStroke))
    style.addSource(GeoJsonSource(SOURCE_END, overlay.end))
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
 * No labels, except on the one place that is selected — see [renderSelection].
 * The list under the map already names every row, and 262 place names would
 * bury the map they are drawn on.
 */
private fun renderPlaces(style: Style, geoJson: String, palette: MapPalette) {
    // The text itself is built in [prepareOverlay], off the main thread.
    // Attached first and filled second, so the data goes through the one
    // setter whose behaviour is documented rather than through a constructor
    // overload that has come and gone between MapLibre versions.
    val source = GeoJsonSource(SOURCE_PLACES)
    style.addSource(source)
    source.setGeoJson(geoJson)

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
        appendPlaceFeature(entry, name = null)
    }
    append("]}")
}

/** The selected place on its own, carrying the name to draw beside it. */
private fun selectedPlaceGeoJson(entry: PlaceEntry, name: String?): String = buildString {
    append("{\"type\":\"FeatureCollection\",\"features\":[")
    appendPlaceFeature(entry, name)
    append("]}")
}

/**
 * One place as a GeoJSON feature: its position, the visit count the radii
 * interpolate on, and the key a tap is resolved back through.
 */
private fun StringBuilder.appendPlaceFeature(entry: PlaceEntry, name: String?) {
    append("{\"type\":\"Feature\",\"properties\":{\"")
    append(PROPERTY_VISITS)
    append("\":")
    append(entry.visits)
    append(",\"")
    append(PROPERTY_KEY)
    append("\":\"")
    appendJsonEscaped(entry.key)
    append('"')
    if (name != null) {
        append(",\"name\":\"")
        appendJsonEscaped(name)
        append('"')
    }
    append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
    append(String.format(Locale.ROOT, "%.6f", entry.longitude))
    append(',')
    append(String.format(Locale.ROOT, "%.6f", entry.latitude))
    append("]}}")
}

/**
 * Escape a string into a JSON string body.
 *
 * A place key is a Google place ID or a rounded coordinate pair and needs
 * none of this; a user's own label is whatever they typed, quotes and
 * backslashes included, and one of those would turn the whole overlay into a
 * parse error and a blank map.
 */
private fun StringBuilder.appendJsonEscaped(value: String) {
    for (ch in value) {
        when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", ch.code))
            else -> append(ch)
        }
    }
}

/**
 * The selected place: a ring around its dot, and its name under it.
 *
 * A ring rather than a recoloured dot, because the dot's size is already
 * carrying the visit count and a second meaning on the same mark would cost
 * the first one. It is drawn outside the halo so it reads as "this one" even
 * where a dozen places overlap.
 *
 * The label is the one place name on the map, and it is here rather than on
 * every dot for the reason [renderPlaces] gives. It needs glyphs, which the
 * basemaps fetch from the tile host; with no network the ring still draws and
 * only the text is missing, which is the right way round.
 */
private fun renderSelection(
    style: Style,
    place: PlaceEntry,
    name: String?,
    palette: MapPalette,
) {
    val source = GeoJsonSource(SOURCE_PLACE_SELECTED)
    style.addSource(source)
    source.setGeoJson(selectedPlaceGeoJson(place, name))

    style.addLayer(
        CircleLayer(LAYER_PLACE_SELECTED_RING, SOURCE_PLACE_SELECTED).withProperties(
            circleColor(palette.placeSelected),
            circleOpacity(0.12f),
            circleRadius(visitScaled(9f, 12f, 17f, 22f, 27f)),
            circleStrokeColor(palette.placeSelected),
            circleStrokeWidth(2.4f),
        )
    )

    if (name.isNullOrBlank()) return
    style.addLayer(
        SymbolLayer(LAYER_PLACE_SELECTED_LABEL, SOURCE_PLACE_SELECTED).withProperties(
            textField(name),
            textFont(arrayOf(FONT_LABEL)),
            textSize(13f),
            textAnchor(Property.TEXT_ANCHOR_TOP),
            // Below the dot, in ems of the text size: the ring is above it and
            // the scale bar sits in the top corner.
            textOffset(arrayOf(0f, 1.6f)),
            // There is exactly one of these, and it is the thing the user just
            // asked to see; letting the basemap's own labels push it out would
            // make the selection look like it failed.
            textAllowOverlap(true),
            textIgnorePlacement(true),
            textMaxWidth(9f),
            textColor(palette.placeLabelText),
            textHaloColor(palette.markerStroke),
            textHaloWidth(1.4f),
        )
    )
}

/** Drop just the selection overlay, leaving the places underneath alone. */
private fun clearSelection(style: Style) {
    SELECTION_LAYERS.forEach { id -> style.getLayer(id)?.let { style.removeLayer(it) } }
    style.getSource(SOURCE_PLACE_SELECTED)?.let { style.removeSource(it) }
}

/**
 * Draw one day's track more heavily than the rest of it.
 *
 * No new source and no new geometry: the day is already a property on every
 * track feature, so this is the same source read through a filter, drawn on
 * top of the line it duplicates. [pointCount] is what keeps the emphasis
 * proportional — the weight it lifts is the weight the track is drawn at,
 * which for a year of history is a very different number than for one walk.
 */
private fun renderDayHighlight(style: Style, date: LocalDate, pointCount: Int) {
    // Nothing to sit above, so nothing to draw: the render effect has not put
    // the track back yet, and the version it bumps will bring us here again.
    val below = when {
        style.getLayer(LAYER_TRACK_ARROWS) != null -> LAYER_TRACK_ARROWS
        style.getLayer(LAYER_TRACK) != null -> LAYER_TRACK
        else -> return
    }
    val base = heatWeightsFor(pointCount).coreWidth * COLOR_WIDTH_LIFT
    val layer = LineLayer(LAYER_TRACK_SELECTED, SOURCE_TRACK).withProperties(
        lineColor(Expression.toColor(Expression.get(PROPERTY_CORE_COLOR))),
        lineWidth(zoomScaledWidth(base * SELECTED_WIDTH_LIFT)),
        lineOpacity(SELECTED_OPACITY),
        lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND),
    )
    layer.setFilter(Expression.eq(Expression.get(PROPERTY_DAY_DATE), date.toString()))
    style.addLayerAbove(layer, below)
}

/** Drop the day highlight, leaving the track underneath it alone. */
private fun clearDayHighlight(style: Style) {
    style.getLayer(LAYER_TRACK_SELECTED)?.let { style.removeLayer(it) }
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
 * What one day's geometry gives the camera: a box to fit, or — for a day with
 * one sample, or several from the same spot — nowhere to go but a point.
 */
private class DayExtent(val bounds: LatLngBounds?, val anchor: LatLng)

/**
 * [dayBox] turned into what MapLibre's camera takes.
 *
 * A scan of the samples rather than a query of the map, because the answer has
 * to be the same whether or not that day is currently on screen — which is the
 * entire point of tapping a date the camera is nowhere near.
 */
private fun dayExtent(points: List<PathPoint>, date: LocalDate, zone: ZoneId): DayExtent? {
    val box = dayBox(points, date, zone) ?: return null
    // In the degenerate case the two corners are the same coordinate, so this
    // is the day's one position rather than a corner of anything.
    val anchor = LatLng(box.minLat, box.minLon)
    if (box.isDegenerate) return DayExtent(null, anchor)
    val bounds = runCatching {
        LatLngBounds.Builder()
            .include(LatLng(box.maxLat, box.maxLon))
            .include(anchor)
            .build()
    }.getOrNull()
    return DayExtent(bounds, anchor)
}

/**
 * One way to frame a box on this map, shared by "Show the whole track" and by
 * focusing a selected day so the two cannot drift apart on where the usable
 * edges of the view are.
 *
 * [maxZoom] is the difference between them. Fitting all of the data means
 * whatever the data needs; fitting one day needs a ceiling, or an afternoon
 * spent on one street is framed at the scale of that street and says nothing
 * about where it is.
 */
private fun boundsCameraUpdate(
    map: MapLibreMap,
    bounds: LatLngBounds,
    insets: MapInsets,
    maxZoom: Double?,
): CameraUpdate {
    val padding = intArrayOf(insets.side, insets.top, insets.side, insets.bottom)
    if (maxZoom != null) {
        val fitted = map.getCameraForLatLngBounds(bounds, padding)
        if (fitted != null && fitted.zoom > maxZoom) {
            return CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(fitted).zoom(maxZoom).build()
            )
        }
    }
    return CameraUpdateFactory.newLatLngBounds(bounds, padding[0], padding[1], padding[2], padding[3])
}

/** The calendar day a rendered track feature belongs to, if it carries one. */
private fun Feature.trackDayDate(): LocalDate? =
    getStringProperty(PROPERTY_DAY_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * Which day's line the user was aiming at inside [box].
 *
 * Nearest wins rather than topmost. Where two days cross, whichever happens to
 * have been drawn last is not the one the finger was following, and at the
 * zoom levels where days overlap at all that is most taps.
 *
 * Distance is measured in degrees on our own copy of the coordinates rather
 * than by projecting each vertex back to the screen: the features MapLibre
 * hands back are clipped to the tiles under the box, so this is a short walk
 * over plain numbers, where the projection would be a native call per vertex.
 */
private fun nearestTrackDay(map: MapLibreMap, box: RectF, tap: LatLng): LocalDate? {
    // A degree of longitude is shorter than a degree of latitude everywhere
    // but the equator; without this the comparison drifts east with latitude.
    val lonScale = cos(Math.toRadians(tap.latitude))
    var best: LocalDate? = null
    var bestDistance = Double.MAX_VALUE
    for (feature in map.queryRenderedFeatures(box, LAYER_TRACK)) {
        val date = feature.trackDayDate() ?: continue
        val distance = feature.nearestSquaredDegrees(tap, lonScale) ?: continue
        if (distance < bestDistance) {
            bestDistance = distance
            best = date
        }
    }
    return best
}

/** How close this feature's nearest vertex comes to [tap], squared. */
private fun Feature.nearestSquaredDegrees(tap: LatLng, lonScale: Double): Double? {
    val coordinates = when (val geometry = geometry()) {
        is LineString -> geometry.coordinates()
        is MultiLineString -> geometry.coordinates().flatten()
        else -> null
    } ?: return null
    var best = Double.MAX_VALUE
    for (point in coordinates) {
        val dx = (point.longitude() - tap.longitude) * lonScale
        val dy = point.latitude() - tap.latitude
        val distance = dx * dx + dy * dy
        if (distance < best) best = distance
    }
    return best.takeIf { it != Double.MAX_VALUE }
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
internal fun haversineMeters(a: PathPoint, b: PathPoint): Double =
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
private fun readCamera(
    map: MapLibreMap,
    view: View,
    bounds: LatLngBounds?,
    insets: MapInsets,
): MapReadout? {
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

    return MapReadout(metersPerPixel, isAdrift(map, bounds, insets, metersPerPixel, width))
}

/**
 * The patch of world the camera is looking at, or null before the view has
 * been laid out or while a tilted camera has a corner on the horizon.
 *
 * The whole view, chrome included. The `Color by day` card used to come off
 * the top of it, on the reasoning that a day whose only line runs behind the
 * card is not a day the user can see - but the card is translucent and inset,
 * with map above it and map down both its sides, so a route it happens to
 * cross is plainly still on screen. Docking a strip of the count for it meant
 * the legend disagreed with what the user could see, which is the same
 * complaint the query this replaced produced by a different route. Chrome
 * painted over the map does not move the edge of the view. Framing the camera
 * still keeps clear of the card - see [MapInsets] - because that decides where
 * the track is *put*, which is a different question.
 *
 * The edge is walked as a loop of samples rather than jumped corner to corner,
 * because [longitudeArc] can only add up steps that are each well inside half
 * a turn, and a view this app is built for is routinely wider than that. A
 * quarter of an edge per step leaves room to spare.
 *
 * A rotated map gives the box its quad fits in rather than the quad, which can
 * call a day in view a moment before it slides in from a corner; erring that
 * way keeps the count from dropping days that are plainly on screen, which is
 * the failure worth avoiding.
 */
private fun readWindow(map: MapLibreMap, view: View): TrackWindow? {
    val width = view.width.toFloat()
    val height = view.height.toFloat()
    if (width <= 0f || height <= 0f) return null
    val projection = map.projection

    // Along the top edge, then back along the bottom, so that every pair of
    // neighbours in this list is a pair of neighbours on the ground too.
    val steps = 0..WINDOW_EDGE_STEPS
    val edge = steps.map { PointF(width * it / WINDOW_EDGE_STEPS, 0f) } +
        steps.map { PointF(width * (WINDOW_EDGE_STEPS - it) / WINDOW_EDGE_STEPS, height) }
    val seen = edge.map { projection.fromScreenLocation(it) }
    if (seen.any { !it.latitude.isFinite() || !it.longitude.isFinite() }) return null

    val arc = longitudeArc(seen.map { it.longitude })
    return TrackWindow(
        minLat = seen.minOf { it.latitude },
        maxLat = seen.maxOf { it.latitude },
        westLon = arc.west,
        lonSpan = arc.span,
    )
}

/** How many pieces each of the two horizontal edges is walked in. */
private const val WINDOW_EDGE_STEPS = 4

/** How solid the `Color by day` card sits over the map. */
private const val LEGEND_CARD_ALPHA = 0.82f

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
    insets: MapInsets,
    metersPerPixel: Double,
    widthPx: Int,
): Boolean {
    if (bounds == null) return false
    // The same padding the button itself would use. Measured against anything
    // else, the fit it performs lands somewhere this call still calls adrift,
    // and the way back offers itself again the moment it has been taken.
    val padding = intArrayOf(insets.side, insets.top, insets.side, insets.bottom)
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
