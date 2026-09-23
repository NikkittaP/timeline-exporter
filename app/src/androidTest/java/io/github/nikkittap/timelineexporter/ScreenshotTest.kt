package io.github.nikkittap.timelineexporter

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
 *   05_formats       the export-format buttons (GPX / KML / GeoJSON / CSV)
 *   06_start_empty   first launch, nothing loaded
 *   07_movement      the movement breakdown expanded (per-type trips and
 *                    distance, "only while moving", "skip repeated points")
 *   08_places        step 3 switched to Places: the map draws a dot per place
 *                    sized by its visit count, over the list that names them
 *   09_time_of_day   the time-of-day window dialog, a preset chosen
 *   10_days          the last seven days, expanded and coloured by day
 *
 * The order of the captures inside captureLoadedFlow is not cosmetic. The
 * Track / Places switch in step 3 decides what step 4 says it will export, so
 * 05_formats is taken while Track is still selected and the switch is put back
 * before the fullscreen map, which would otherwise open on the places dots.
 * 10_days narrows the dates, so it comes after everything that wants them all.
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
     * Wake the device and drop the keyguard before anything is launched.
     *
     * A full pass takes the better part of an hour, longer than the AVD's
     * screen timeout. Once the screen is off, a launched activity never
     * resumes, so Compose has no hierarchy and every test in that language
     * fails with "No compose hierarchies found" - which is how a phone pass
     * died at de-DE after en-US and ar had gone through. `stayon` keeps it
     * from happening again later in the same pass.
     */
    @Before
    fun wakeDevice() {
        for (command in listOf(
            "input keyevent KEYCODE_WAKEUP",
            "wm dismiss-keyguard",
            "svc power stayon true",
        )) {
            // executeShellCommand returns at once; reading the pipe to its end
            // waits for the command to finish.
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand(command)
                .let { ParcelFileDescriptor.AutoCloseInputStream(it) }
                .use { it.readBytes() }
        }
        Thread.sleep(WAKE_SETTLE_MS)
    }

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

        // How often shoot() tries a capture, and how long it waits in between.
        const val SCREENSHOT_ATTEMPTS = 4
        const val SCREENSHOT_RETRY_MS = 2_000L
        const val TAG = "ScreenshotTest"

        // The day 10_days picks: day 3 of the demo file's closing road trip,
        // Interlaken over the Grimsel and Oberalp passes to Innsbruck. Framed,
        // it has the Swiss, Tyrolean and Dolomite days on screen around it.
        // Moves with END_DATE / alps_road_trip_segments in
        // tools/generate_demo_timeline.py.
        val FOCUS_DAY: LocalDate = LocalDate.of(2026, 6, 28)

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

        // After waking the screen, how long we let the keyguard go away and
        // the display come up before an activity is launched on it.
        const val WAKE_SETTLE_MS = 500L

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
        shoot("06_start_empty")
    }

    @Test
    fun captureLoadedFlow() {
        val intent = Intent(appCtx, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = demoFileUri()
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<MainActivity>(intent)

        // Parse runs asynchronously; wait for step 1 to offer "Choose another
        // file", which it only does once a file has loaded.
        waitForText(R.string.file_change_button, timeoutMs = 20_000)
        // Pump the clock so the inline preview map applies its loaded style and
        // draws the route BEFORE we shoot (a bare sleep would not).
        settle(MAP_TILE_WAIT_MS)

        // 02 — the loaded overview (top of the page).
        shoot("02_overview")

        // 07 — the movement breakdown, expanded: a row per movement group with
        // its trips and distance, then the two point-level switches. Matched by
        // the row's title, read from resources, so this works in every locale
        // without hard-coding a translation.
        composeRule.onNodeWithText(str(R.string.filter_movement_title))
            .performScrollTo().performClick()
        // The block expands with an animation, and a scroll made while it is
        // still growing is undone by it: Korean came out showing the top of
        // the page. Let it open fully before scrolling.
        settle(1_500)
        // Scroll to the last switch so the whole expanded block is on screen,
        // not just the part that happened to fit below the date chips.
        composeRule.onNodeWithText(str(R.string.filter_drop_repeated)).performScrollTo()
        settle(750)
        shoot("07_movement")
        // Collapse again so the shots that follow see the page they expect.
        composeRule.onNodeWithText(str(R.string.filter_movement_title))
            .performScrollTo().performClick()
        settle(1_500)

        // 09 — the time-of-day window. The row in step 2 carries the same
        // string the dialog is titled with, so the row is matched while it is
        // still the only one on screen, and the dialog is waited for on a
        // label of its own. Cancel, not Apply: the shots that follow
        // expect the unfiltered data.
        composeRule.onNodeWithText(str(R.string.filter_time_any))
            .performScrollTo().performClick()
        waitForText(R.string.time_dialog_from, timeoutMs = 10_000)
        // The preset is matched as a chip, not as a bare label: in Hindi
        // "Day" and the "days" caption of step 1's stats are the same word,
        // and that caption, under the dialog, is the node a text match finds.
        composeRule.onNode(hasText(str(R.string.time_preset_day)) and isSelectable()).performClick()
        // The semantics say "selected" a frame or two before the chip is drawn
        // that way, and UiAutomation shoots whatever is on the glass: a Hindi
        // pass once came out with the preset still unselected.
        settle(1_000)
        shoot("09_time_of_day")
        composeRule.onNodeWithText(str(R.string.date_dialog_cancel)).performClick()
        // Closing the dialog hands focus back to the row that opened it, and
        // the page animates that row back into view. Let that finish first, or
        // it lands after the jump below and the formats shot comes out
        // showing step 2 - which it did in seven languages out of eighteen.
        settle(1_500)

        // 05 — scroll to the version footer, which sits just below the Export
        // card, so the whole card (including its bottom border and the
        // GPX/KML/GeoJSON/CSV buttons) is on screen.
        composeRule.onNodeWithText(BuildConfig.VERSION_NAME, substring = true).performScrollTo()
        settle(750)
        shoot("05_formats")

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
        shoot("08_places")
        // Back to Track before the map is expanded, or the hero shot is dots.
        composeRule.onAllNodesWithText(str(R.string.content_track)).onFirst().performClick()
        composeRule.waitForIdle()
        settle(MAP_TILE_WAIT_MS)

        // 03 — open the full-screen map. This is also the base image that
        // add_hero_caption.py turns into the captioned 01_hero. The fullscreen
        // control is an icon button on the map itself now, so both entering
        // and leaving are found by content description rather than a label.
        composeRule.onNodeWithContentDescription(str(R.string.map_expand)).performScrollTo().performClick()
        waitForDescription(R.string.map_exit_fullscreen, timeoutMs = 10_000)
        settle(MAP_TILE_WAIT_MS)
        shoot("03_map_fullscreen")
        composeRule.onNodeWithContentDescription(str(R.string.map_exit_fullscreen)).performClick()
        composeRule.waitForIdle()

        // 10 — the last week, one colour per day. The demo file ends on a
        // seven-day road trip that takes a road of its own every day, so the
        // "7 days" preset in step 2 leaves exactly that week on the map.
        // Last on purpose: it leaves a date range applied and the map
        // expanded, and every shot above wants all of the data.
        composeRule.onAllNodesWithText(quantityStr(R.plurals.map_day_count, 7)).onFirst()
            .performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(str(R.string.map_expand)).performScrollTo().performClick()
        waitForDescription(R.string.map_exit_fullscreen, timeoutMs = 10_000)
        composeRule.onNodeWithContentDescription(str(R.string.map_color_by_day)).performClick()
        // The day row arrives once the days have been counted off-thread.
        waitForDescription(R.string.map_show_all_days, timeoutMs = 10_000)
        settle(MAP_TILE_WAIT_MS)
        // The whole week is 1,500 km of motorway with the Alps squeezed into
        // its bottom corner. Pick the day over the passes from the day list
        // instead: the map frames that day, which brings the Alpine days
        // around it into view at a scale where their roads read as roads, and
        // lifts the picked line with its date under it. The list shows dates
        // the way the sheet formats them, in the app's language.
        composeRule.onNodeWithContentDescription(str(R.string.map_show_all_days)).performClick()
        val dayLabel = FOCUS_DAY.format(
            DateTimeFormatter.ofPattern("MMM d", localizedCtx.resources.configuration.locales[0])
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(dayLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(dayLabel).onFirst().performClick()
        settle(MAP_TILE_WAIT_MS)
        shoot("10_days")
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

    /**
     * [Screengrab.screenshot], tried again when the capture fails.
     *
     * UiAutomation gives the display capture one second and returns no bitmap
     * past it, which screengrab reports as "Unable to capture screenshot". The
     * first shot of a pass is the one that misses: screengrab has just
     * switched the device locale, and the launcher, SystemUI and the keyboard
     * are all rebuilding themselves for it, for tens of seconds. A second try
     * a moment later lands; a failed try leaves an empty file that the next
     * one overwrites.
     */
    private fun shoot(name: String) {
        repeat(SCREENSHOT_ATTEMPTS - 1) {
            try {
                Screengrab.screenshot(name)
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "Capture of $name failed, trying again", e)
                settle(SCREENSHOT_RETRY_MS)
            }
        }
        Screengrab.screenshot(name)
    }

    private fun quantityStr(id: Int, count: Int): String =
        localizedCtx.resources.getQuantityString(id, count, count)

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
