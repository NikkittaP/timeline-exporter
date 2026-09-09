package io.github.nikkittap.timelineexporter

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import tools.fastlane.screengrab.locale.LocaleTestRule
import java.io.File
import java.util.Locale

/**
 * Drives the app through its main states and captures a screenshot of each, in
 * whatever locale Fastlane screengrab is currently running.
 *
 * The language comes from the app's own language setting, not the device's: see
 * applyAppLanguage(). screengrab switches the device locale between passes, but
 * on Android 13+ this app's per-app locale override wins over it, so without
 * that step every locale is captured in whatever language the app was last left
 * in.
 *
 * Captured (file name -> store screenshot):
 *   02_overview      main page with data loaded
 *   03_map_fullscreen expanded world map
 *   04_calendar      date-range picker dialog
 *   05_formats       the export-format buttons (GPX / KML / GeoJSON / CSV)
 *   06_start_empty   first launch, nothing loaded
 *   07_movement      the movement breakdown expanded (per-type trips and
 *                    distance, "only while moving", "skip repeated points")
 *   08_places        step 3 switched to Places: the map draws a dot per place
 *                    sized by its visit count, over the list that names them
 *   09_time_of_day   the time-of-day window dialog, a preset chosen
 *
 * The order of the captures inside captureLoadedFlow is not cosmetic. The
 * Track / Places switch in step 3 decides what step 4 says it will export, so
 * 05_formats is taken while Track is still selected and the switch is put back
 * before the fullscreen map, which would otherwise open on the places dots.
 *
 * These are raw captures, not the store art: tools/store_shots renders the
 * framed, captioned images from them. See tools/store_shots/README.md.
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

    /**
     * The app language for this pass: the screengrab locale matched against the
     * languages the app actually ships (`@xml/locales_config`) by RFC 4647
     * lookup — "en-US" -> "en", "hi-IN" -> "hi", "zh-CN" -> "zh-CN",
     * "pt-BR" -> "pt-BR". Empty means "follow the system", which is what a run
     * outside screengrab (Android Studio, `connectedAndroidTest`) gets.
     */
    private val appLanguageTag: String by lazy {
        val requested = InstrumentationRegistry.getArguments()
            .getString(TEST_LOCALE_ARG)
            ?.replace('_', '-')
            ?.takeIf { it.isNotBlank() }
            ?: return@lazy ""
        Locale.lookupTag(Locale.LanguageRange.parse(requested), shippedLanguageTags()).orEmpty()
    }

    /**
     * A context pinned to [appLanguageTag], used for every label the test looks
     * up. Reading them from [appCtx] would be a race: the system delivers the
     * new per-app configuration to the process a moment after it stores the
     * choice, so the first lookups could still come back in the previous
     * language and match no node on screen.
     */
    private val localizedCtx: Context by lazy {
        if (appLanguageTag.isEmpty()) return@lazy appCtx
        val config = Configuration(appCtx.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(appLanguageTag)))
        appCtx.createConfigurationContext(config)
    }

    private fun str(id: Int): String = localizedCtx.getString(id)

    /**
     * Put the app into this pass's language through the app's own language
     * setting, not the device's.
     *
     * screengrab switches the *device* locale between passes ([LocaleTestRule]
     * below), but this app carries an in-app language override, and on Android
     * 13+ a per-app locale wins over the system one. So the device switch never
     * reached the UI and every locale came out in whatever the app was last
     * left in — English. Setting the same override the in-app language picker
     * sets makes the two agree; on API 29-32, where the override lives in
     * SharedPreferences, it is applied by MainActivity.attachBaseContext.
     */
    @Before
    fun applyAppLanguage() {
        if (LocaleManager.getPersistedTag(appCtx) == appLanguageTag) return
        LocaleManager.setLanguage(appCtx, appLanguageTag)
        if (appLanguageTag.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // setApplicationLocales stores the choice synchronously, but the
        // configuration it produces reaches this process asynchronously. Wait
        // for it before an activity is launched, or the system recreates that
        // activity underneath the test a moment after it starts.
        val language = Locale.forLanguageTag(appLanguageTag).language
        val deadline = SystemClock.uptimeMillis() + LOCALE_APPLY_TIMEOUT_MS
        while (appCtx.resources.configuration.locales.get(0)?.language != language &&
            SystemClock.uptimeMillis() < deadline
        ) {
            Thread.sleep(100)
        }
        Thread.sleep(LOCALE_SETTLE_MS)
    }

    /** The tags in `@xml/locales_config` — the same list the picker offers. */
    private fun shippedLanguageTags(): List<String> {
        val tags = mutableListOf<String>()
        val parser = appCtx.resources.getXml(R.xml.locales_config)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_RES_NS, "name")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { tags += it }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return tags
    }

    companion object {
        const val DEMO_ASSET = "timeline_demo.json"

        // Total time we let a map settle (tiles fetched, track drawn). We don't
        // burn this in one Thread.sleep — see settle(): under Compose tests the
        // main clock only advances at sync points, so a bare sleep leaves the
        // inline map's route un-rendered until the next interaction. settle()
        // pumps waitForIdle() repeatedly so the map actually updates while we
        // wait. Raise if maps still look half-loaded.
        const val MAP_TILE_WAIT_MS = 6_000L

        // The locale of the current pass, as screengrab passes it to the
        // runner: `-e testLocale en_US`.
        const val TEST_LOCALE_ARG = "testLocale"
        const val ANDROID_RES_NS = "http://schemas.android.com/apk/res/android"

        // How long we wait for the per-app locale set on Android 13+ to reach
        // this process as a configuration change, and how long we then let it
        // settle. An empty language ("follow the system") is applied by
        // applyAppLanguage() without waiting at all.
        const val LOCALE_APPLY_TIMEOUT_MS = 5_000L
        const val LOCALE_SETTLE_MS = 400L

        // Switches the device locale for each run of the suite. The app's own
        // language is set per test in applyAppLanguage() — on Android 13+ this
        // rule alone does not change what the app renders.
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

        // 07 — the movement breakdown, expanded: a row per movement group with
        // its trips and distance, then the two point-level switches. Matched by
        // the collapsed row's own text, which starts with "All movement"
        // whenever nothing has been unticked — read from resources, so this
        // works in every locale without hard-coding a translation.
        composeRule.onNodeWithText(str(R.string.filter_movement_all), substring = true)
            .performScrollTo().performClick()
        composeRule.waitForIdle()
        // Scroll to the last switch so the whole expanded block is on screen,
        // not just the part that happened to fit below the date chips.
        composeRule.onNodeWithText(str(R.string.filter_drop_repeated)).performScrollTo()
        composeRule.waitForIdle()
        Screengrab.screenshot("07_movement")
        // Collapse again so the shots that follow see the page they expect.
        composeRule.onNodeWithText(str(R.string.filter_movement_all), substring = true)
            .performScrollTo().performClick()
        composeRule.waitForIdle()

        // 09 — the time-of-day window. Before a window is set the chip carries
        // the same string the dialog is titled with, so the chip is matched
        // while it is still the only one on screen, and the dialog is waited
        // for on a label of its own. Cancel, not Apply: the shots that follow
        // expect the unfiltered data.
        composeRule.onNodeWithText(str(R.string.filter_time_any))
            .performScrollTo().performClick()
        waitForText(R.string.time_dialog_from, timeoutMs = 10_000)
        composeRule.onNodeWithText(str(R.string.time_preset_day)).performClick()
        composeRule.waitForIdle()
        Screengrab.screenshot("09_time_of_day")
        composeRule.onNodeWithText(str(R.string.date_dialog_cancel)).performClick()
        composeRule.waitForIdle()

        // 05 — scroll to the version footer, which sits just below the Export
        // card, so the whole card (including its bottom border and the
        // GPX/KML/GeoJSON/CSV buttons) is on screen.
        composeRule.onNodeWithText(BuildConfig.VERSION_NAME, substring = true).performScrollTo()
        composeRule.waitForIdle()
        Screengrab.screenshot("05_formats")

        // 08 — step 3 on the Places side: the map above, the list below.
        // onFirst() rather than onNodeWithText: "Places" is the switch's label
        // and, in some languages, a word the step-4 heading repeats.
        composeRule.onAllNodesWithText(str(R.string.content_places)).onFirst()
            .performScrollTo().performClick()
        composeRule.waitForIdle()
        settle(MAP_TILE_WAIT_MS)
        // Frame it from the top of the step, so the switch, the map and the
        // first rows of the list are all in the shot.
        composeRule.onAllNodesWithText(str(R.string.content_places)).onFirst().performScrollTo()
        composeRule.waitForIdle()
        Screengrab.screenshot("08_places")
        // Back to Track before the map is expanded, or the hero shot is dots.
        composeRule.onAllNodesWithText(str(R.string.content_track)).onFirst().performClick()
        composeRule.waitForIdle()
        settle(MAP_TILE_WAIT_MS)

        // 03 — open the full-screen map. This is also the base image that
        // add_hero_caption.py turns into the captioned 01_hero.
        composeRule.onNodeWithText(str(R.string.preview_expand)).performScrollTo().performClick()
        // The fullscreen close action is an icon button now, so it is a
        // content description rather than a label on screen.
        waitForDescription(R.string.preview_close, timeoutMs = 10_000)
        settle(MAP_TILE_WAIT_MS)
        Screengrab.screenshot("03_map_fullscreen")
        composeRule.onNodeWithContentDescription(str(R.string.preview_close)).performClick()
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

    private fun waitForDescription(stringId: Int, timeoutMs: Long = 8_000) {
        val description = str(stringId)
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
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
