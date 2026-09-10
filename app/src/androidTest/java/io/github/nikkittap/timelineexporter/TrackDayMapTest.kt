package io.github.nikkittap.timelineexporter

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.ui.ExpandedMap
import io.github.nikkittap.timelineexporter.ui.MapSubject
import io.github.nikkittap.timelineexporter.ui.TimelineMap
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class TrackDayMapTest {
    @get:Rule val compose = createComposeRule()

    /** Three days, each a short line of its own, well clear of the others. */
    private fun threeDays() = (0..2).flatMap { day ->
        listOf(17.9, 18.1).mapIndexed { index, lon ->
            PathPoint(
                Instant.parse("2026-09-01T09:00:00Z").plusSeconds(day * 86400L + index * 600),
                59.3 + day * 2, lon, 120,
            )
        }
    }

    /**
     * The compact "Color by day" row describes the viewport again, so the
     * sheet it opens has two lists: what the camera can see, and everything
     * the filter left in the track. "All days" is the one that must hold every
     * coloured day no matter where the camera is pointed - the direct
     * replacement for the old camera-panning test.
     */
    @Test fun allDaysListsEveryColouredDayRegardlessOfCamera() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { TimelineMap(MapSubject.Track(threeDays()), Modifier.fillMaxSize()) } }
        compose.onNodeWithContentDescription(context.getString(R.string.map_color_by_day)).performClick()

        val summary = compose.onNodeWithContentDescription(context.getString(R.string.map_show_all_days))
        compose.waitUntil(10_000) { runCatching { summary.fetchSemanticsNode() }.isSuccess }
        summary.performClick()

        compose.onNodeWithText(context.getString(R.string.map_days_tab_all, 3)).performClick()

        val locale = context.resources.configuration.locales[0]
        val dayFormatter = DateTimeFormatter.ofPattern("MMM d", locale)
        (0..2).forEach { day ->
            val expected = LocalDate.parse("2026-09-01").plusDays(day.toLong()).format(dayFormatter)
            compose.onNodeWithText(expected).assertExists()
        }
    }

    /**
     * Tapping a date is the legend half of the two-way link: it selects that
     * day, closes the sheet and names the selection on the map. Whether the
     * camera then actually flies there is MapLibre's business and needs a GL
     * surface with a loaded style; what this asserts is the state the user is
     * left looking at.
     */
    @Test fun tappingADateSelectsThatDayAndNamesItOnTheMap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { TimelineMap(MapSubject.Track(threeDays()), Modifier.fillMaxSize()) } }
        compose.onNodeWithContentDescription(context.getString(R.string.map_color_by_day)).performClick()

        val summary = compose.onNodeWithContentDescription(context.getString(R.string.map_show_all_days))
        compose.waitUntil(10_000) { runCatching { summary.fetchSemanticsNode() }.isSuccess }
        summary.performClick()
        compose.onNodeWithText(context.getString(R.string.map_days_tab_all, 3)).performClick()

        val locale = context.resources.configuration.locales[0]
        val picked = LocalDate.parse("2026-09-02")
        compose.onNodeWithText(picked.format(DateTimeFormatter.ofPattern("MMM d", locale))).performClick()

        val callout = context.getString(
            R.string.map_selected_day,
            picked.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
        )
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithContentDescription(callout).fetchSemanticsNode()
            }.isSuccess
        }
        // The sheet is gone, so the map the selection describes is visible.
        compose.onNodeWithText(context.getString(R.string.map_days_tab_all, 3)).assertDoesNotExist()

        // And tapping the callout is the way back out of the selection.
        compose.onNodeWithContentDescription(callout).performClick()
        compose.onNodeWithContentDescription(callout).assertDoesNotExist()
    }

    /**
     * The compact row follows the camera again, which is section 2 of the
     * follow-up: zoomed out over all three days it counts three, zoomed into
     * any one of them it says one day is in view.
     *
     * The camera is moved directly rather than swiped, so what is asserted is
     * what the legend says about a known viewport rather than about wherever a
     * gesture happened to land.
     */
    @Test fun theCompactLegendFollowsWhatTheCameraCanSee() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { TimelineMap(MapSubject.Track(threeDays()), Modifier.fillMaxSize()) } }
        compose.onNodeWithContentDescription(context.getString(R.string.map_color_by_day)).performClick()

        val summary = compose.onNodeWithContentDescription(context.getString(R.string.map_show_all_days))
        compose.waitUntil(10_000) { runCatching { summary.fetchSemanticsNode() }.isSuccess }

        var map: MapLibreMap? = null
        fun findMap(view: View): MapView? {
            if (view is MapView) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) findMap(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).first()
            findMap(activity.window.decorView)!!.getMapAsync { map = it }
        }
        compose.waitUntil(10_000) { map != null }

        fun expect(text: String) = compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithText(text, substring = true).fetchSemanticsNode() }.isSuccess
        }

        // Zoomed out far enough that no day is hidden behind the legend card:
        // the row keeps the plain wording, because "3 days in view" of a
        // three-day track says nothing the shorter form does not.
        compose.runOnIdle {
            map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(61.3, 18.0), 5.0))
        }
        expect(context.resources.getQuantityString(R.plurals.map_day_count, 3, 3))

        // The three days are 220 km apart, so a street-level camera over any
        // one of them can see exactly that one.
        val oneDay = context.resources.getQuantityString(R.plurals.map_day_count_in_view, 1, 1)
        (0..2).forEach { day ->
            compose.runOnIdle {
                map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(59.3 + day * 2, 18.0), 13.0))
            }
            expect(oneDay)
        }
    }

    @Test fun colorToggleAndFullscreenControlHaveSeparateTouchTargets() {
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
                movableContentOf<MapSubject, Modifier> { data, modifier ->
                    TimelineMap(
                        data,
                        modifier,
                        fullscreen = expanded.value,
                        onToggleFullscreen = { expanded.value = !expanded.value },
                    )
                }
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

        val expandButton = compose.onNodeWithContentDescription(context.getString(R.string.map_expand))
        assertTrue(
            "Color switch and the fullscreen control must have separate touch targets",
            expandButton.fetchSemanticsNode().boundsInRoot.top >= toggle.fetchSemanticsNode().boundsInRoot.bottom,
        )
        expandButton.performClick()
        toggle.assertIsOn()

        Thread.sleep(1000)
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithContentDescription(context.getString(R.string.map_exit_fullscreen)).fetchSemanticsNode()
            }.isSuccess
        }
        val expandedBitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "track-days-expanded.png").outputStream().use {
            expandedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        expandedBitmap.recycle()

        compose.onNodeWithContentDescription(context.getString(R.string.map_exit_fullscreen)).performClick()
        toggle.assertIsOn()
        toggle.performClick().assertIsOff()
        compose.onNodeWithContentDescription(context.getString(R.string.map_show_all_days)).assertDoesNotExist()
    }
}
