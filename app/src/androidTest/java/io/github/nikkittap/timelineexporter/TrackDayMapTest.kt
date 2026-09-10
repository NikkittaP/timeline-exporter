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
import io.github.nikkittap.timelineexporter.ui.ExpandedMap
import io.github.nikkittap.timelineexporter.ui.MapSubject
import io.github.nikkittap.timelineexporter.ui.TimelineMap
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TrackDayMapTest {
    @get:Rule val compose = createComposeRule()

    /**
     * The compact "Color by day" row now summarizes the whole track, not
     * whatever the camera happens to frame — replacing the old horizontal
     * scroller meant the legend no longer needs a viewport query at all.
     * "Show all days" opens the full, grouped list underneath it.
     */
    @Test fun showAllDaysListsEveryColouredDayRegardlessOfCamera() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val points = (0..2).flatMap { day ->
            listOf(17.9, 18.1).mapIndexed { index, lon ->
                PathPoint(
                    Instant.parse("2026-09-01T09:00:00Z").plusSeconds(day * 86400L + index * 600),
                    59.3 + day * 20, lon, 120,
                )
            }
        }
        compose.setContent { MaterialTheme { TimelineMap(MapSubject.Track(points), Modifier.fillMaxSize()) } }
        compose.onNodeWithContentDescription(context.getString(R.string.map_color_by_day)).performClick()

        val summary = compose.onNodeWithContentDescription(context.getString(R.string.map_show_all_days))
        compose.waitUntil(10_000) { runCatching { summary.fetchSemanticsNode() }.isSuccess }
        summary.performClick()

        val locale = context.resources.configuration.locales[0]
        val dayFormatter = DateTimeFormatter.ofPattern("MMM d", locale)
        (0..2).forEach { day ->
            val expected = LocalDate.parse("2026-09-01").plusDays(day.toLong()).format(dayFormatter)
            compose.onNodeWithText(expected).assertExists()
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
