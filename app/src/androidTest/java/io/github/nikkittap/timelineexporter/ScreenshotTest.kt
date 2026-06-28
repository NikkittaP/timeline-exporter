package io.github.nikkittap.timelineexporter

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import tools.fastlane.screengrab.locale.LocaleTestRule
import java.io.File

/**
 * Drives the app through six states and captures a Play-Store screenshot of
 * each, in whatever locale Fastlane screengrab is currently running.
 *
 * Captured (file name -> store screenshot):
 *   01_hero          main page with data loaded  (add the "100% on-device"
 *   02_overview      main page with data loaded
 *   03_map_fullscreen expanded world map (also the source for the hero)
 *   04_calendar      date-range picker dialog
 *   05_formats       the export-format buttons (GPX / KML / GeoJSON / CSV)
 *   06_start_empty   first launch, nothing loaded
 *
 * Note: 01_hero is NOT captured here. tools/add_hero_caption.py takes the
 * clean 03_map_fullscreen.png and overlays the localized "100% on-device"
 * caption to produce 01_hero.png (run automatically by the Fastlane lane).
 *
 * How a file gets loaded without touching the system picker: MainActivity
 * already handles an ACTION_VIEW intent that points at a content URI (its
 * "Open with .json" path). The test copies the bundled demo file into the
 * app's cache and launches the activity with such an intent.
 *
 * Locale labels are read from the app's own resources at runtime, so the
 * clicks work in every language without hard-coding any translated text.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    // targetContext = the app under test (its resources, cache, FileProvider).
    private val appCtx get() = InstrumentationRegistry.getInstrumentation().targetContext
    // context = the instrumentation APK (where androidTest/assets live).
    private val testCtx get() = InstrumentationRegistry.getInstrumentation().context

    private fun str(id: Int): String = appCtx.getString(id)

    companion object {
        const val DEMO_ASSET = "timeline_demo.json"

        // Total time we let a map settle (tiles fetched, track drawn). We don't
        // burn this in one Thread.sleep — see settle(): under Compose tests the
        // main clock only advances at sync points, so a bare sleep leaves the
        // inline map's route un-rendered until the next interaction. settle()
        // pumps waitForIdle() repeatedly so the map actually updates while we
        // wait. Raise if maps still look half-loaded.
        const val MAP_TILE_WAIT_MS = 6_000L

        // Switches the device locale for each run of the suite.
        @get:ClassRule
        @JvmStatic
        val localeTestRule = LocaleTestRule()

        @BeforeClass
        @JvmStatic
        fun beforeAll() {
            Screengrab.setDefaultScreenshotStrategy(UiAutomatorScreenshotStrategy())
        }
    }

    @Test
    fun captureEmptyStart() {
        ActivityScenario.launch(MainActivity::class.java)
        waitForText(R.string.pick_file_button)
        Screengrab.screenshot("06_start_empty")
    }

    @Test
    fun captureLoadedFlow() {
        val intent = Intent(appCtx, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = demoFileUri()
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent)

        // Parse runs asynchronously; wait for the "Loaded" badge to appear.
        waitForText(R.string.loaded_badge, timeoutMs = 20_000)
        // Pump the clock so the inline preview map applies its loaded style and
        // draws the route BEFORE we shoot (a bare sleep would not).
        settle(MAP_TILE_WAIT_MS)

        // 02 — the loaded overview (top of the page).
        Screengrab.screenshot("02_overview")

        // 05 — scroll to the version footer, which sits just below the Export
        // card, so the whole card (including its bottom border and the
        // GPX/KML/GeoJSON/CSV buttons) is on screen.
        composeRule.onNodeWithText(BuildConfig.VERSION_NAME, substring = true).performScrollTo()
        composeRule.waitForIdle()
        Screengrab.screenshot("05_formats")

        // 03 — open the full-screen map. This is also the base image that
        // add_hero_caption.py turns into the captioned 01_hero.
        composeRule.onNodeWithText(str(R.string.preview_expand)).performScrollTo().performClick()
        waitForText(R.string.preview_close, timeoutMs = 10_000)
        settle(MAP_TILE_WAIT_MS)
        Screengrab.screenshot("03_map_fullscreen")
        composeRule.onNodeWithText(str(R.string.preview_close)).performClick()
        composeRule.waitForIdle()

        // 04 — open the custom date-range (calendar) dialog.
        composeRule.onNodeWithText(str(R.string.filter_preset_custom)).performScrollTo().performClick()
        waitForText(R.string.date_dialog_apply, timeoutMs = 10_000)
        composeRule.waitForIdle()
        Screengrab.screenshot("04_calendar")
        composeRule.onNodeWithText(str(R.string.date_dialog_cancel)).performClick()
    }

    // ---- helpers ----

    /** Copy the bundled demo JSON into the app cache and expose it as a content URI. */
    private fun demoFileUri(): Uri {
        val out = File(appCtx.cacheDir, DEMO_ASSET)
        testCtx.assets.open(DEMO_ASSET).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return FileProvider.getUriForFile(
            appCtx,
            "${appCtx.packageName}.screenshot.fileprovider",
            out,
        )
    }

    private fun waitForText(stringId: Int, timeoutMs: Long = 8_000) {
        val text = str(stringId)
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Wait ~[totalMs] while repeatedly advancing the Compose clock via
     * waitForIdle(). Unlike a bare Thread.sleep this lets pending recompositions
     * (e.g. the map's "style loaded -> draw route" effect) actually run during
     * the wait, so the map is rendered by the time we screenshot it.
     */
    private fun settle(totalMs: Long, stepMs: Long = 750) {
        var elapsed = 0L
        while (elapsed < totalMs) {
            Thread.sleep(stepMs)
            composeRule.waitForIdle()
            elapsed += stepMs
        }
    }
}
