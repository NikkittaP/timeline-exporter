package io.github.nikkittap.timelineexporter.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.text.Html
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.parser.PathPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.OutputStream
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the map image: a real offscreen MapLibre snapshot of the exported
 * selection at the output resolution, with the branding, the statistics card,
 * the lettered start/end markers and the map attribution painted on top in
 * output pixels. Every size comes from [MapImageSpec], so one code path draws
 * all four shapes at both resolutions.
 *
 * Nothing leaves the phone. The only network traffic is the basemap tiles the
 * on-screen map loads anyway, and the track itself is a local GeoJSON source.
 */

/** The switches on the export screen. Defaults are the approved Clean Map ones. */
internal data class MapImageOptions(
    val showStats: Boolean = true,
    val showEndpoints: Boolean = true,
    val colorByDay: Boolean = true,
)

/**
 * What to draw: the exported selection exactly as the main screen holds it.
 *
 * [points] are every sample in chronological order; [journeys] the separate
 * journeys they are made of (one per selected trip in the Trips view, the
 * whole track otherwise), which is what keeps two trips from being joined by
 * a line that was never travelled.
 */
internal data class MapImageRequest(
    val points: List<PathPoint>,
    val journeys: List<List<PathPoint>>,
    /** Visited places in the same selection, or null where that is not known. */
    val placeCount: Int?,
    val fallbackZone: ZoneId,
    val options: MapImageOptions,
    val spec: MapImageSpec,
)

internal class MapImageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Longest a snapshot may take: a cold tile cache over a slow network. */
private const val SNAPSHOT_TIMEOUT_MS = 90_000L

/**
 * How far MapLibre's own rounding of the logical size may leave the snapshot
 * from the requested size. Anything further is a renderer that gave us less
 * than we asked for, and that is an error, never a quietly smaller file.
 */
private const val SIZE_TOLERANCE_PX = 2

/** The exact name on the branding: a product name, never translated. */
private const val BRAND_NAME = "Timeline Exporter"

private const val TAG = "MapImage"

/**
 * Render [request] to an opaque bitmap of exactly `spec.widthPx` x `spec.heightPx`.
 *
 * Cancelling the calling coroutine cancels the snapshot and releases it.
 * Throws [MapImageException] when there is nothing to draw, when MapLibre
 * fails, or when the device runs out of memory for this size.
 */
internal suspend fun renderMapImage(context: Context, request: MapImageRequest): Bitmap {
    val appContext = context.applicationContext
    val spec = request.spec
    val options = request.options

    // Everything proportional to the size of the track, off the main thread.
    val prepared = withContext(Dispatchers.Default) {
        val extent = trackExtent(request.points) ?: throw MapImageException("Nothing to draw")
        val camera = fitMapImageCamera(extent, spec, options.showStats)
        val stats = mapImageStats(request.points, request.journeys, request.fallbackZone, request.placeCount)
        val style = MapImageStyle.prepare(
            appContext, request.points, request.journeys, options.colorByDay, request.fallbackZone, camera.zoom,
        )
        Prepared(camera, stats, style)
    }

    val shot = withContext(Dispatchers.Main) {
        withTimeout(SNAPSHOT_TIMEOUT_MS) { snapshot(appContext, spec, prepared) }
    }

    // Painting the overlays is plain Canvas work on our own bitmap.
    return withContext(Dispatchers.Default) {
        val bitmap = try {
            toOutputBitmap(shot.bitmap, spec)
        } catch (e: OutOfMemoryError) {
            throw MapImageException("Out of memory at ${spec.widthPx}x${spec.heightPx}", e)
        }
        val canvas = Canvas(bitmap)
        val rtl = isRtl()
        val painter = OverlayPainter(context, spec, rtl)
        if (options.showEndpoints) {
            val first = request.points.first()
            val last = request.points.last()
            painter.drawEndpoints(
                canvas,
                start = projectToImage(prepared.camera, spec, first.latitude, first.longitude),
                end = projectToImage(prepared.camera, spec, last.latitude, last.longitude),
            )
        }
        val card = if (options.showStats && prepared.stats != null) painter.drawStats(canvas, prepared.stats) else null
        painter.drawAttribution(canvas, shot.attribution, showStats = card != null)
        painter.drawBranding(canvas)
        // Fully opaque, whatever the encoder: anything the style left
        // transparent becomes paper white rather than JPEG's black.
        canvas.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
        bitmap.setHasAlpha(false)
        bitmap
    }
}

/**
 * Write [bitmap] as [format] to [out]. The only step that differs between
 * PNG and JPEG: everything above it is one picture.
 */
internal fun encodeMapImage(bitmap: Bitmap, format: MapImageFormat, out: OutputStream): Boolean = when (format) {
    MapImageFormat.Png -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    MapImageFormat.Jpeg -> bitmap.compress(Bitmap.CompressFormat.JPEG, MapImageFormat.JPEG_QUALITY, out)
}

private class Prepared(val camera: MapImageCamera, val stats: MapImageStats?, val style: MapImageStyle)

private class Shot(val bitmap: Bitmap, val attribution: String)

/**
 * A snapshotter that leaves the picture alone. The stock one paints its own
 * logo and a small attribution line into the corner; we draw our own,
 * legible at any output size, in [OverlayPainter.drawAttribution].
 */
private class BareSnapshotter(context: Context, options: Options) : MapSnapshotter(context, options) {
    override fun addOverlay(mapSnapshot: MapSnapshot) = Unit
}

/** One snapshot, on the main thread, waited for until the style and every tile are drawn. */
private suspend fun snapshot(context: Context, spec: MapImageSpec, prepared: Prepared): Shot =
    suspendCancellableCoroutine { cont ->
        val camera = CameraPosition.Builder()
            .target(LatLng(prepared.camera.latitude, prepared.camera.longitude))
            .zoom(prepared.camera.zoom)
            .build()
        val options = MapSnapshotter.Options(spec.logicalWidth, spec.logicalHeight)
            .withPixelRatio(spec.pixelRatio)
            .withCameraPosition(camera)
            .withStyleBuilder(prepared.style.builder(spec.pixelRatio))
            .withLogo(false)
        val snapshotter = BareSnapshotter(context, options)
        cont.invokeOnCancellation {
            // Called on whatever thread cancelled us; MapLibre wants main.
            android.os.Handler(android.os.Looper.getMainLooper()).post { snapshotter.cancel() }
        }
        snapshotter.start(
            { snap ->
                val attribution = snap.attributions
                    .joinToString(" ") { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString() }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                val bitmap = snap.bitmap
                Log.d(TAG, "Snapshot ${bitmap.width}x${bitmap.height} for ${spec.widthPx}x${spec.heightPx}")
                if (cont.isActive) cont.resume(Shot(bitmap, attribution))
            },
            { error ->
                Log.w(TAG, "Snapshot failed: $error")
                if (cont.isActive) cont.resumeWithException(MapImageException("Snapshot failed: $error"))
            },
        )
    }

/**
 * The snapshot as a mutable bitmap of exactly the output size. MapLibre
 * rounds the logical size, so a ratio that does not divide the width evenly
 * can come back a pixel short; that pixel is stretched in. A snapshot further
 * off than that is refused rather than scaled up into a file that claims a
 * resolution it does not have.
 */
private fun toOutputBitmap(source: Bitmap, spec: MapImageSpec): Bitmap {
    if (abs(source.width - spec.widthPx) > SIZE_TOLERANCE_PX || abs(source.height - spec.heightPx) > SIZE_TOLERANCE_PX) {
        throw MapImageException(
            "Renderer returned ${source.width}x${source.height} for ${spec.widthPx}x${spec.heightPx}",
        )
    }
    val sized = if (source.width == spec.widthPx && source.height == spec.heightPx) source
    else source.scale(spec.widthPx, spec.heightPx)
    return if (sized.isMutable && sized.config == Bitmap.Config.ARGB_8888) sized
    else sized.copy(Bitmap.Config.ARGB_8888, true).also { if (sized !== source) sized.recycle() }
}

private fun isRtl(): Boolean =
    TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL

/**
 * OpenStreetMap's licence asks for "© OpenStreetMap contributors". The tile
 * service's own attribution, once its HTML is flattened, can come out as a
 * bare "OpenStreetMap"; the image must not print less than the licence asks.
 */
internal fun withOsmCredit(attribution: String): String {
    if (attribution.contains("OpenStreetMap contributors")) return attribution
    val bare = Regex("""(Data from\s+)?(©\s*)?OpenStreetMap""")
    return if (bare.containsMatchIn(attribution)) bare.replaceFirst(attribution, "© OpenStreetMap contributors")
    else "$attribution © OpenStreetMap contributors".trim()
}

/** Shown when the style carries no attribution of its own - it always should. */
private const val FALLBACK_ATTRIBUTION = "© OpenMapTiles © OpenStreetMap contributors"

/**
 * The overlays, in output pixels. Every size is a design-unit number through
 * [MapImageSpec.u], not dp: the image is the same file on every phone, and
 * the same composition at every size.
 */
private class OverlayPainter(private val context: Context, private val spec: MapImageSpec, private val rtl: Boolean) {
    private val ink = "#1F1D1A".toColorInt()
    private val muted = "#6B675F".toColorInt()
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun u(v: Float) = spec.u(v)

    /** The same quiet white surface under the branding, the card and the attribution. */
    private fun surface(alpha: Int, shadow: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alpha, 255, 255, 255)
        if (shadow) setShadowLayer(u(18f), 0f, u(4f), Color.argb(40, 0, 0, 0))
    }

    fun drawEndpoints(canvas: Canvas, start: Pair<Float, Float>, end: Pair<Float, Float>) {
        val radius = spec.markerRadiusPx
        var (sx, sy) = start
        var (ex, ey) = end
        // A round trip starts and ends at the same door; letting E cover S
        // would hide that it is one. Pull them apart along their own line,
        // or sideways when they coincide exactly.
        val minGap = radius * 2 + u(6f)
        val distance = hypot(ex - sx, ey - sy)
        if (distance < minGap) {
            val (ux, uy) = if (distance > 0.5f) (ex - sx) / distance to (ey - sy) / distance else 1f to 0f
            val push = (minGap - distance) / 2
            sx -= ux * push; sy -= uy * push
            ex += ux * push; ey += uy * push
        }
        drawMarker(canvas, sx, sy, radius, MapImageStyle.startColor, "S")
        drawMarker(canvas, ex, ey, radius, MapImageStyle.endColor, "E")
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, radius: Float, color: String, letter: String) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toColorInt()
            setShadowLayer(u(8f), 0f, u(3f), Color.argb(70, 0, 0, 0))
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = u(6f)
        }
        canvas.drawCircle(x, y, radius, fill)
        canvas.drawCircle(x, y, radius, ring)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = u(32f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(letter, x, y - (text.ascent() + text.descent()) / 2, text)
    }

    /**
     * The app's own launcher icon and its name, small, in the top-start
     * corner. The icon is the adaptive icon itself, background and
     * foreground layers under the system's mask, drawn as vectors at the
     * output size rather than a bitmap scaled up.
     */
    fun drawBranding(canvas: Canvas) {
        val s = spec.brandScale
        val height = spec.brandHeightPx
        val iconSize = u(48f * s)
        val inset = (height - iconSize) / 2
        val gap = u(14f * s)
        val endPad = u(24f * s)
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = u(30f * s)
            typeface = medium
        }
        val textWidth = name.measureText(BRAND_NAME)
        val width = min(inset + iconSize + gap + textWidth + endPad, spec.brandMaxWidthPx)
        val box = spec.brandBox(rtl, width)
        val rect = RectF(box.left, box.top, box.right, box.bottom)
        val corner = u(20f * s)
        canvas.drawRoundRect(rect, corner, corner, surface(245, shadow = true))

        // Start side holds the icon: left in LTR, right in RTL.
        val iconLeft = if (rtl) rect.right - inset - iconSize else rect.left + inset
        val iconTop = rect.top + inset
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.mutate()?.let { icon ->
            icon.setBounds(0, 0, iconSize.toInt(), iconSize.toInt())
            canvas.withTranslation(iconLeft, iconTop) { icon.draw(this) }
        }
        val baseline = rect.centerY() - (name.ascent() + name.descent()) / 2
        val textX = if (rtl) iconLeft - gap - textWidth else iconLeft + iconSize + gap
        canvas.drawText(BRAND_NAME, textX, baseline, name)
    }

    /**
     * A white card along the bottom: the date range on top, then up to three
     * figures. Only figures that are actually known are shown. Full width on
     * the tall shapes; on landscape a compact card no wider than its content.
     * Returns where it went.
     */
    fun drawStats(canvas: Canvas, stats: MapImageStats): ImageBox {
        val columns = buildList {
            if (stats.distanceMeters >= 50.0) {
                add(formatKm(stats.distanceMeters) to context.getString(R.string.map_image_stat_distance))
            }
            if (stats.dayCount > 0) {
                add(formatGrouped(stats.dayCount) to context.getString(R.string.map_image_stat_days))
            }
            stats.placeCount?.let {
                add(formatGrouped(it) to context.getString(R.string.map_image_stat_places))
            }
        }
        val k = spec.statsScale
        val inner = u(40f * k)
        val gap = u(56f * k)
        val align = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        val header = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = u(38f * k)
            typeface = medium
            textAlign = align
        }
        val value = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = u(54f * k)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = align
        }
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = u(29f * k)
            textAlign = align
        }
        val rangeText = formatLocalDayRange(context, stats.firstDate, stats.lastDate)
        val natural = columns.map { (number, caption) -> max(value.measureText(number), label.measureText(caption)) }
        val figuresWidth = natural.sum() + gap * (columns.size - 1)

        val maxWidth = spec.statsMaxWidthPx
        val cardWidth = if (spec.compactStats) {
            min(max(figuresWidth, header.measureText(rangeText)) + inner * 2, maxWidth)
        } else maxWidth
        val box = spec.statsBox(rtl, cardWidth)
        val card = RectF(box.left, box.top, box.right, box.bottom)
        val corner = u(32f * k)
        canvas.drawRoundRect(card, corner, corner, surface(250, shadow = true))

        val contentLeft = card.left + inner
        val contentRight = card.right - inner
        val contentWidth = contentRight - contentLeft

        val range = TextUtils.ellipsize(rangeText, header, contentWidth, TextUtils.TruncateAt.END).toString()
        val headerBaseline = card.top + inner - header.ascent()
        canvas.drawText(range, if (rtl) contentRight else contentLeft, headerBaseline, header)

        val labelBaseline = card.bottom - inner - label.descent()
        val valueBaseline = labelBaseline + label.ascent() - u(10f * k) - value.descent()

        // Each column as wide as its own text, spaced evenly; equal shares
        // (and ellipsis) only when a long translation cannot fit that way.
        val widths = if (figuresWidth <= contentWidth) natural
        else List(columns.size) { (contentWidth - gap * (columns.size - 1)) / columns.size }
        var cursor = 0f
        columns.forEachIndexed { i, (number, caption) ->
            val width = widths[i]
            val x = if (rtl) contentRight - cursor else contentLeft + cursor
            val v = TextUtils.ellipsize(number, value, width, TextUtils.TruncateAt.END).toString()
            val c = TextUtils.ellipsize(caption, label, width, TextUtils.TruncateAt.END).toString()
            canvas.drawText(v, x, valueBaseline, value)
            canvas.drawText(c, x, labelBaseline, label)
            cursor += width + gap
        }
        return box
    }

    /**
     * The map data's attribution, permanently in the image: on a small
     * translucent pill at the bottom end, shrunk to fit on one line. It is
     * the last thing at the bottom and nothing is ever drawn over it.
     */
    fun drawAttribution(canvas: Canvas, attribution: String, showStats: Boolean) {
        val text = withOsmCredit(attribution.ifBlank { FALLBACK_ATTRIBUTION })
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#4A4740".toColorInt()
            textSize = u(25f)
        }
        val padH = u(16f)
        val padV = u(8f)
        val pillHeightAt = { paint.descent() - paint.ascent() + padV * 2 }
        val (_, maxPill) = spec.attributionSlot(showStats, pillHeightAt())
        val maxWidth = maxPill - padH * 2
        while (paint.measureText(text) > maxWidth && paint.textSize > u(16f)) paint.textSize -= u(1f)
        val shown = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
        val width = paint.measureText(shown)
        val height = pillHeightAt()
        val (bottom, _) = spec.attributionSlot(showStats, height)
        val margin = spec.marginPx
        val right = if (rtl) margin + width + padH * 2 else spec.widthPx - margin
        val pill = RectF(right - width - padH * 2, bottom - height, right, bottom)
        canvas.drawRoundRect(pill, height / 2, height / 2, surface(215, shadow = false))
        canvas.drawText(shown, pill.left + padH, pill.bottom - padV - paint.descent(), paint)
    }
}

/**
 * What this device can render: the largest GPU render target and the memory
 * free right now. Read once per screen, off the main thread.
 */
internal class MapImageDeviceLimits(val maxRenderSizePx: Int, val availableBytes: Long, val lowRamDevice: Boolean) {
    fun support(spec: MapImageSpec): HighResolutionSupport =
        resolutionSupport(spec, maxRenderSizePx, availableBytes, lowRamDevice)

    companion object {
        /** GPU limits do not change while the app runs. */
        @Volatile private var gpuLimit: Int? = null

        fun read(context: Context): MapImageDeviceLimits {
            val am = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val limit = gpuLimit ?: queryGpuRenderLimit().also { gpuLimit = it }
            // Bitmap pixels live in native memory since Android 8, so the
            // system's free memory is the budget, less what it keeps in reserve.
            val available = (info.availMem - info.threshold).coerceAtLeast(0L)
            return MapImageDeviceLimits(limit, available, am?.isLowRamDevice == true)
        }

        /**
         * The smallest of the GPU's texture, renderbuffer and viewport
         * limits, through a throwaway 1x1 pbuffer context. 0 when no context
         * could be made, which the caller treats as "do not risk it".
         */
        private fun queryGpuRenderLimit(): Int {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return 0
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return 0
            var context = EGL14.EGL_NO_CONTEXT
            var surface = EGL14.EGL_NO_SURFACE
            try {
                val attribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return 0
                val config = configs[0] ?: return 0
                context = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
                )
                if (context == EGL14.EGL_NO_CONTEXT) return 0
                surface = EGL14.eglCreatePbufferSurface(
                    display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
                )
                if (surface == EGL14.EGL_NO_SURFACE) return 0
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return 0
                val texture = IntArray(1)
                val renderbuffer = IntArray(1)
                val viewport = IntArray(2)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, texture, 0)
                GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, renderbuffer, 0)
                GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, viewport, 0)
                val limit = minOf(texture[0], renderbuffer[0], viewport[0], viewport[1])
                Log.d(TAG, "GPU limits: texture ${texture[0]}, renderbuffer ${renderbuffer[0]}, viewport ${viewport.toList()}")
                return limit.coerceAtLeast(0)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read GPU limits", e)
                return 0
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                // eglTerminate is deliberately not called: the display is
                // process-wide, and MapLibre's own context lives on it.
            }
        }
    }
}
