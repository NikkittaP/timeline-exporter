package io.github.nikkittap.timelineexporter

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.ui.MapSubject
import io.github.nikkittap.timelineexporter.ui.TimelineMap
import io.github.nikkittap.timelineexporter.ui.ExpandedMap
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.camera.CameraUpdateFactory

class TrackDayMapTest {
    @get:Rule val compose = createComposeRule()

    @Test fun legendFollowsVisibleLinesIncludingEdgesWithBothSamplesOffscreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val points = (0..2).flatMap { day ->
            listOf(17.9, 18.1).mapIndexed { index, lon ->
                PathPoint(Instant.parse("2026-09-01T09:00:00Z").plusSeconds(day * 86400L + index * 600),
                    59.3 + day * 2, lon, 120)
            }
        }
        compose.setContent { MaterialTheme { TimelineMap(MapSubject.Track(points), Modifier.fillMaxSize()) } }
        compose.onNodeWithContentDescription(context.getString(R.string.map_color_by_day)).performClick()
        var map: MapLibreMap? = null
        fun findMap(view: View): MapView? {
            if (view is MapView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                findMap(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            findMap(activity.window.decorView)!!.getMapAsync { map = it }
        }
        compose.waitUntil(10_000) { map != null }
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(context.resources.configuration.locales[0])
        val legend = compose.onNodeWithContentDescription(context.getString(R.string.map_day_legend))
        fun expectDate(day: Int) {
            compose.runOnIdle { map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(59.3 + day * 2, 18.0), 16.0)) }
            val expected = LocalDate.parse("2026-09-01").plusDays(day.toLong()).format(formatter)
            try {
                compose.waitUntil(10_000) {
                    runCatching { legend.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == expected }.getOrDefault(false)
                }
            } catch (error: Throwable) {
                var detail = ""
                compose.runOnIdle {
                    detail = "camera=${map!!.cameraPosition}; features=" + map!!.queryRenderedFeatures(
                        android.graphics.RectF(0f, 0f, 1080f, 2400f), "track-line"
                    ).map { it.getStringProperty("dayDate") }.distinct()
                }
                throw AssertionError("Expected $expected; $detail; legend=" + runCatching {
                    legend.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                }.getOrNull(), error)
            }
        }
        expectDate(0)
        expectDate(1)
        compose.runOnIdle { map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(61.0, 20.0), 16.0)) }
        compose.waitUntil(10_000) { runCatching { legend.fetchSemanticsNode() }.isFailure }
        expectDate(2)
    }

    @Test fun colorToggleAndLegend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val points = (0..5).flatMap { day ->
            (0..20).map { step ->
                PathPoint(
                    Instant.parse("2026-09-01T09:00:00Z").plusSeconds(day * 86400L + step * 60L),
                    59.32 + day * 0.004 + kotlin.math.sin(step / 4.0) * 0.003,
                    18.03 + step * 0.002,
                    120,
                )
            }
        }
        val expanded = mutableStateOf(false)
        val subject = MapSubject.Track(points)
        compose.setContent {
            val map = remember {
                movableContentOf<MapSubject, Modifier> { data, modifier -> TimelineMap(data, modifier, onClose = if (expanded.value) ({ expanded.value = false }) else null) }
            }
            MaterialTheme {
                if (expanded.value) {
                    ExpandedMap(subject, mapContent = map)
                } else {
                    map(subject, Modifier.fillMaxSize())
                }
            }
        }
        val toggle = compose.onNodeWithContentDescription(context.getString(R.string.map_color_by_day))
        toggle.assertIsOff().performClick().assertIsOn()

        // The map loads its bundled style asynchronously outside Compose idling.
        Thread.sleep(4000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "track-days.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        compose.runOnIdle { expanded.value = true }
        toggle.assertIsOn()
        val close = compose.onNodeWithContentDescription(context.getString(R.string.preview_close))
        assertTrue(
            "Close and the color switch must have separate touch targets",
            close.fetchSemanticsNode().boundsInRoot.left >= toggle.fetchSemanticsNode().boundsInRoot.right,
        )
        Thread.sleep(1000)
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithContentDescription(context.getString(R.string.map_day_legend)).fetchSemanticsNode()
            }.isSuccess
        }
        val expandedBitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "track-days-expanded.png").outputStream().use {
            expandedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        expandedBitmap.recycle()
        close.performClick()
        toggle.assertIsOn()
        toggle.performClick().assertIsOff()
        compose.onNodeWithContentDescription(context.getString(R.string.map_day_legend)).assertDoesNotExist()
    }
}
