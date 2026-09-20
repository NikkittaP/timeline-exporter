import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Load release signing credentials from a gitignored file at the project
// root. If the file doesn't exist (fresh checkout, CI without secrets,
// contributor with no key), the properties will be null and release builds
// will fail with a clear "Keystore file ... not found" message. Debug builds
// are unaffected (AGP provides a default debug signing config).
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "io.github.nikkittap.timelineexporter"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.nikkittap.timelineexporter"
        minSdk = 29
        targetSdk = 36

        // Bump versionCode by 1 for every AAB uploaded to Play Console
        // (including internal-testing uploads; Play won't accept a repeat).
        // Bump versionName per semver: patch for bugfixes, minor for
        // backwards-compatible features, major for breaking changes.
        //
        // History:
        //   versionCode=1, versionName="1.0.0" -> uploaded to Internal track
        //   versionCode=2, versionName="1.0.0" -> Closed (Alpha) track
        //   versionCode=3, versionName="1.1.0" -> map gap-splitting, multi-format
        //                                         parser, localization, version footer
        //   versionCode=4, versionName="1.2.0" -> language picker, polish UI,
        //                                         reject non-Timeline
        //   versionCode=5, versionName="1.2.1" -> fix in-app language switching
        //                                         on Play installs (disable AAB
        //                                         language splits)
        //   versionCode=6, versionName="1.3.0" -> streaming JSON parser (Jackson),
        //                                         large files no longer OOM,
        //                                         byte-accurate progress bar
        //   versionCode=7, versionName="1.4.0" -> Play Billing 7.1.1 -> 9.1.0
        //                                         (Google deadline 2026-08-31),
        //                                         auto service reconnection,
        //                                         unfetched-product logging
        //   versionCode=8, versionName="1.5.0" -> feedback dialog (GitHub /
        //                                         email, no telemetry),
        //                                         local time + UTC offset
        //                                         columns in CSV
        //                                         (internal testing only —
        //                                         the email intent was broken)
        //   versionCode=9, versionName="1.5.1" -> fix: mail draft opened empty
        //                                         because subject and body
        //                                         rode only in intent extras,
        //                                         which Gmail ignores; they
        //                                         now travel in the mailto URI
        //   versionCode=10, versionName="1.6.0" -> movement filtering: the
        //                                         parser now keeps visit and
        //                                         activity segments, step 2
        //                                         breaks the range down by
        //                                         movement type with trip
        //                                         counts and distances, and
        //                                         exports can drop points
        //                                         recorded while standing
        //                                         still or repeated verbatim
        //   versionCode=11, versionName="1.6.1" -> wording: step 2 is "Filter
        //                                         data", not "Filter by date",
        //                                         and the feedback ballot drops
        //                                         the movement-type filter that
        //                                         1.6.0 shipped, rewords the
        //                                         statistics item and adds
        //                                         three new candidates
        //   versionCode=12, versionName="1.6.2" -> crash hotfix: every
        //                                         LocalDate.ofInstant call
        //                                         (API 34) threw
        //                                         NoSuchMethodError on
        //                                         Android 10-13, killing the
        //                                         day chips, the date picker
        //                                         and the export filename;
        //                                         core library desugaring and
        //                                         a lint gate now stop that
        //                                         class of bug at build time.
        //                                         Also hardens the MapLibre
        //                                         teardown that was aborting
        //                                         in ~MapRenderer
        // ---- everything above is LIVE IN PRODUCTION; 1.6.2 is what users have ----
        // Below this line: not in production. 13 was never uploaded at all; 14
        // went to a testing track only, which is why 15 exists rather than the
        // ballot work landing on 14 like the five commits before it. The next
        // production changelog must therefore cover the whole span
        // 1.6.2 -> the uploaded code, not just the last versionName bump. See
        // RELEASE_NOTES.md, which accumulates those entries under "Unreleased".
        //   versionCode=13, versionName="1.6.3" -> correctness: path points
        //                                         carried the exporting
        //                                         phone's home UTC offset
        //                                         instead of the one in
        //                                         effect where they were
        //                                         recorded, so the CSV
        //                                         local-time column was wrong
        //                                         for every trip abroad.
        //                                         Also: place names from the
        //                                         older Takeout layout, and
        //                                         sending an export straight
        //                                         to another app
        //   versionCode=14, versionName="1.7.0" -> Places, a map of our own,
        //       and filtering by time of day.
        //
        //       This code kept accumulating long after the commit that named
        //       it: five more commits and the work below all landed under the
        //       same 14, because nothing in between was ever uploaded. Read
        //       this entry, not the tag, for what an AAB built at 14 contains.
        //
        //       - Places. Step 3 gains a Track / Places switch and every format
        //         writes places as well as a track: GPX waypoints, KML Point
        //         placemarks, GeoJSON Point features, a places-shaped CSV.
        //         Places are listed by visit count, with duration, last visit
        //         and address, and any of them can be given a local name that
        //         is kept on device and written into the export. Google's
        //         container places - the mall or airport a stay sits inside -
        //         are off by default. Each place carries two UTC offsets, one
        //         for the first visit and one for the last, so an aggregate
        //         spanning a DST change cannot reintroduce the bug 13 had just
        //         fixed.
        //
        //       - Step 4 says what it will write. Its heading reads "Export
        //         track" or "Export places" and a read-only plaque above the
        //         format buttons carries the kind and the count, so flipping
        //         the switch one card up is visible before the filename is. The
        //         "Open in Google Maps" link was also broken and undocumented:
        //         it passed place_id: as literal search text, and now uses the
        //         documented coordinates + query_place_id form, formatted
        //         through Locale.ROOT so a coordinate near the equator is not
        //         emitted as 1.0E-5.
        //
        //       - The map is our own. Two bundled styles in assets/, light and
        //         dark, over the same OpenMapTiles source as before but near-
        //         monochrome and 18 layers deep instead of Liberty's ~150; they
        //         follow the system theme without a download and without
        //         yanking a camera the user has panned. The track is drawn as
        //         accumulation - a hairline at low alpha over a blurred wash,
        //         its base weight interpolated on the log of the sample count -
        //         so a single walk reads as a route line and a year of history
        //         as a heat map, with direction chevrons where direction is
        //         still a real question (at most 2000 samples, zoom 13 and
        //         above). Replacing Liberty also meant restoring what it had
        //         been carrying for us: the OSM attribution, a measured scale
        //         bar, and a button back to the track once the camera has
        //         drifted off it.
        //
        //       - Places are on that map too, as dots sized by visit count,
        //         sharing one selection with the list beneath. A tap rings the
        //         dot and tints its row; a second tap opens the naming dialog.
        //         Selecting from the list moves the camera, selecting on the
        //         map does not.
        //
        //       - Filter by time of day. One more chip in step 2 opens a window
        //         that applies to every day in the range, tested against the
        //         clock where each point was recorded rather than the phone's -
        //         the offset fix in 13 paying for itself one layer up. Windows
        //         wrap through midnight (22:00-06:00), a place is kept when a
        //         visit to it overlaps the window, and the window is written
        //         into both the filename and the track name.
        //
        //       40 new strings across all 18 locales; the now-unused
        //       step_export was removed from each. New tests: PlaceEntryTest,
        //       PlacesExportTest, TimeOfDayWindowTest, TimeOfDayFilterTest.
        //
        //       Uploaded to a testing track, so 14 is spent: Play will not
        //       accept it twice, and the production upload of this span is 15.
        //   versionCode=15, versionName="1.7.0" -> the feedback ballot, re-cut.
        //       Same versionName on purpose: nothing here changes what the app
        //       does with a Timeline file, and the whole 1.6.2 -> now span is
        //       still the one release the store changelog describes. Codes 1
        //       and 2 shared 1.0.0 for the same reason.
        //
        //       The dialog was still offering two things the span had already
        //       built: feedback_option_places (7 votes, the most-requested
        //       item in the log, shipped in 14) and feedback_option_share
        //       (1 vote, shipped in 13). Both removed. feedback_option_stats
        //       loses its second half - the places list is ordered by visit
        //       count, so "most-visited places" is done and the line now asks
        //       only for "Monthly and yearly totals".
        //
        //       Four added, ordered by theme rather than by vote count so the
        //       ballot does not become its own ranking: track_per_trip (native
        //       GPX, no ZIP - splits file_per_trip into its cheap half before
        //       1.8.0 commits), place_names (measures the online-resolution
        //       question that has been declined twice from the same reference
        //       export), remember_settings (nobody asked; SharedPreferences
        //       holds only the locale and place labels today), owner (FB-014's
        //       three words of Spanish, put to a vote instead of guessed at).
        //       Eleven options now; the dialog already scrolls.
        //
        //       Also fixed: FeedbackDialog.kt carried its own hard-coded
        //       English per option and three of them paraphrased the label the
        //       ballot shows, so an incoming report could not be matched to a
        //       row of FEEDBACK.md by string equality. The outgoing text is now
        //       character-for-character the values/strings.xml label.
        //
        //       22 strings across all 18 locales: 4 added, 1 shortened,
        //       2 removed. No parser, exporter or map changes; no new tests.
        //
        //       Then, still under 15 because it is still unspent: an optional
        //       update check. Play's in-app-update API (flexible flow) asks on
        //       every launch and resume whether a newer build exists, and — if
        //       one does — a dismissible Snackbar offers to fetch it in the
        //       background; a second Snackbar offers to restart once it has
        //       landed. Neither is forced, and declining either costs nothing.
        //       Unlike the ballot re-cut, this does change what the app does,
        //       so it earns its own versionName: 1.7.1, still on versionCode
        //       15 — whoever builds this next sets the real code before
        //       uploading. AppUpdateViewModel wraps AppUpdateManager; the
        //       Activity Result launcher it needs to start the flow lives in
        //       TimelineExporterApp, alongside the Snackbar host, the same
        //       place every other launcher in this app already lives. Four new
        //       strings across all 18 locales; no parser, exporter or map
        //       changes.
        //   versionCode=15, versionName="1.7.2" -> fix the load-progress bar
        //       crawling through the first few percent of a real phone
        //       export, then finishing the rest in under a second.
        //
        //       Root-caused against Timeline.json (62 MB, one of the dev
        //       fixtures under F:/Timeline): rawSignals - the raw-ping
        //       fallback array every phone export carries after
        //       semanticSegments - starts at byte 5,164,108 of 62,884,513
        //       (8%), matching the report almost exactly. Once
        //       semanticSegments already produced path points, every one of
        //       the remaining ~57 MB of rawSignals entries costs a
        //       p.skipChildren() and nothing else - no field reads, no
        //       allocation - while the 8% before it is genuinely expensive:
        //       timestamps, coordinates, Segment/PathPoint objects. A plain
        //       bytesRead/totalBytes ratio is accurate about bytes and
        //       wrong about time, which is exactly the crawl-then-jump the
        //       report described.
        //
        //       First attempt (reverted) tried to paper over the jump with
        //       an indeterminate "Finishing up" spinner for the skip phase.
        //       Correctly labelled but not what was asked for - the bar
        //       still didn't fill evenly, just stopped admitting a number
        //       near the end. This version fixes the number itself:
        //       TimelineParser.findKeyOffsets does one cheap sequential
        //       byte scan (no JSON parsing) before the real parse to find
        //       where semanticSegments/timelineObjects/locations and
        //       rawSignals each start. TimelineViewModel.weightedProgress
        //       then maps bytes before that split into the first 90% of the
        //       bar and bytes after it into the last 10%, so the bar spends
        //       most of its range on the phase that takes most of the time.
        //       The split is only trusted when the content key comes before
        //       rawSignals - the shape every real export takes - so a file
        //       without that shape (or without rawSignals at all) falls
        //       back to the plain byte ratio, unchanged from before.
        //
        //       New tests: TimelineParserKeyOffsetsTest (the byte scan,
        //       including a needle split across its 64 KB read boundary),
        //       TimelineViewModelProgressTest (the weighting function), and
        //       a regression test pinning the real Timeline.json's split at
        //       byte 5,164,108 so this can't silently regress. Full unit
        //       suite (159 tests) green.
        //
        //       Still on versionCode 15 - unspent, not yet uploaded. No new
        //       strings this time - the previous attempt's "Finishing up"
        //       string is gone with it.
        //   versionCode=15, versionName="1.8.0" -> fullscreen map rework: the
        //       "Expand" text button is gone from the Preview header, replaced
        //       by a fullscreen/fullscreen_exit control stacked on the map
        //       itself, directly above "Fit to data" - fixing the confusion
        //       where the panel's `X` looked like it only closed the "Color by
        //       day" card when it actually closed the whole fullscreen view.
        //       The `X` is gone; the same control now opens and closes
        //       fullscreen from one spot, and Android Back/swipe-back close it
        //       too (BackHandler already did this - only the entry point was
        //       new). "Color by day"'s legend no longer lists every date in a
        //       horizontal strip that broke down past a couple of weeks: it
        //       shows a compact "N days · range" summary (a few sample
        //       swatches, a day count, a date span), and tapping it opens a
        //       Modal Bottom Sheet with the full set in a LazyColumn grouped
        //       Year -> Month, most recent first. That list is now computed
        //       from the whole track's distinct local days off the main
        //       thread, not queried from whatever the camera happens to frame
        //       each render frame - simpler, and no longer viewport-dependent,
        //       which also deleted the per-rendered-frame throttled query this
        //       screen used to run. New pure functions in TrackDays.kt
        //       (dayLegendSummary, formatDayRange, dayListEntries) carry unit
        //       tests. enableOnBackInvokedCallback turned on in the manifest
        //       for the predictive-back animation.
        //
        //       New strings: map_expand, map_exit_fullscreen,
        //       map_show_all_days, the map_day_count plurals - added to all 17
        //       locales. preview_expand and map_day_legend removed everywhere
        //       they're no longer read. Still on versionCode 15 - unspent, not
        //       yet uploaded.
        //
        //       Then the follow-up pass on the same screen, still 1.8.0 and
        //       still 15, because it finishes the feature rather than adding
        //       another. The two map controls no longer read as the same icon:
        //       fullscreen is open_in_full/close_fullscreen (diagonal arrows),
        //       and "Show the whole track" is a hand-authored route glyph
        //       instead of a second set of corner brackets. The compact legend
        //       describes the viewport again - "2 days in view", "0 days in
        //       view" when the camera is off the track - but driven from
        //       camera-idle and MapView's did-become-idle rather than the
        //       per-frame listener the last pass deleted, so a fling costs one
        //       query instead of sixty a second. The sheet gained In view /
        //       All days tabs so the complete list stays reachable. A day is
        //       now selectable from either end: tapping a date focuses the
        //       camera on everything recorded that day, tapping a line inside
        //       an 18dp box names the nearest day in a chip at the bottom of
        //       the map, and the selected day's line is drawn heavier over the
        //       rest. One selectedEpochDay backs the layers, the tap result,
        //       the legend, the sheet and the camera; filters that drop the
        //       day clear it. Both camera fits share one padded path that
        //       keeps the route clear of the legend card and the controls, and
        //       the "adrift" test that offers the way back now measures
        //       against that same padding. New in TrackDays.kt: dayBox and
        //       dayEntryIndex, both unit tested. Four new strings plus the
        //       map_day_count_in_view plurals across all 17 locales.
        //
        //   versionCode=15, versionName="1.7.0" -> uploaded to the internal
        //       testing track 2026-09-05. Every "still on versionCode 15,
        //       unspent" note above was true when written; it stopped being
        //       true here. Everything since is new relative to what testers
        //       already have installed.
        //   versionCode=16, versionName="1.8.0" -> the whole 1.7.0 -> now
        //       span for internal testing: the feedback ballot re-cut, the
        //       optional in-app update check, Color by day, the day
        //       legend/sheet/camera rework that followed it, and the four
        //       fixes traced to the same 61 MB export (the fullscreen
        //       queryRenderedFeatures crash, the zoom-dependent day count,
        //       the wide-viewport antimeridian math, and the load-progress
        //       bar crawling then jumping). See RELEASE_NOTES.md for the
        //       full breakdown; changelogs/16.txt in all 18 locales carries
        //       the store text.
        //   versionCode=17, versionName="1.8.0" -> 16's span plus the
        //       redesign: the app's own blue palette in place of wallpaper
        //       colour (dark theme matched to the dark map), four corner
        //       radii from the theme, filled step cards, vector icons in the
        //       top bar, step 1 showing the open file with points / days /
        //       distance, step 2 rebuilt around a date range field with four
        //       equal presets, and the calendar with its own header and only
        //       the file's days selectable. 16 counts as spent - bumped at the
        //       uploader's instruction - so testers get this as a new build.
        //       Same versionName: none of 1.8.0 has reached production, and
        //       the redesign ships inside it. changelogs/17.txt in all 18
        //       locales carries the store text, rewritten to fit a fifth item
        //       under Play's 500-character limit.
        //   versionCode=18, versionName="1.8.1" -> step 4 polish on top of 17
        //       (which is uploaded, so spent): the Save / Send switch stays on
        //       one line in every language (it wrapped in ru and uk, and ran
        //       into its outline in fr), with shorter ru / uk / fr labels, and
        //       the four format buttons sit in one row of equal widths instead
        //       of three plus CSV alone underneath. changelogs/18.txt in all 18
        //       locales carries 17's store text: the span it describes is the
        //       same, and a layout tidy-up is not news to a store reader.
        //   versionCode=19, versionName="1.8.2" -> the tip ask moves to the
        //       moment the app has just been useful. 18 is uploaded, so spent.
        //       From a user's second successful export onwards, the plaque
        //       under the result offers the three Play amounts at their real
        //       local prices instead of the feature-request link; the two
        //       plaques take turns rather than stacking, because two favours
        //       asked at once devalue each other. "Not now" buys a month of
        //       silence and a tip buys half a year, both held in
        //       SharedPreferences along with the count of finished exports -
        //       persistent because the second export is usually days after the
        //       first. Play Billing is warmed up one export early so the
        //       prices are on screen rather than a spinner, and a device that
        //       cannot reach Billing at all simply keeps the feedback plaque.
        //       The heart in the top bar is unchanged. No new store changelog:
        //       changelogs/19.txt would need cutting before this goes further
        //       than internal testing.
        versionCode = 19
        versionName = "1.8.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            keystoreProperties.getProperty("RELEASE_STORE_FILE")?.let { storeFile = file(it) }
            storePassword = keystoreProperties.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = keystoreProperties.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = keystoreProperties.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 strips unused code and obfuscates remaining classes/methods.
            // Reflection-using libraries (MapLibre) are protected by
            // rules in proguard-rules.pro.
            isMinifyEnabled = true
            // Strips unused resources (drawables, strings, layouts). Requires
            // isMinifyEnabled = true. Saves a few hundred KB typically.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // Google Play serves an App Bundle and, by default, splits resources by
    // language — only the device's current locale is delivered at install time.
    // The in-app language picker (LocaleManager.setApplicationLocales) then has
    // no resources to switch to, so it appears broken when installed from Play
    // even though it works in debug builds (which install all locales). Turning
    // off the language split bundles every locale into the base install.
    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Backports the java.* library APIs that Android added after our
        // minSdk. Without this, java.time methods introduced in later API
        // levels compile fine but throw NoSuchMethodError on older devices —
        // exactly how LocalDate.ofInstant (API 34) crashed 1.6.1 on Android
        // 10-13. Desugaring makes the whole java.time surface safe at
        // minSdk 29; the lint config below is the second line of defence.
        isCoreLibraryDesugaringEnabled = true
    }

    // Lint is the check that should have caught the API-34-on-minSdk-29 bug
    // before release, so make it impossible to ship past it. NewApi is
    // already a fatal-severity issue, which means lintVitalRelease runs it
    // during bundleRelease; these settings just make sure nothing quietly
    // turns that off.
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
    buildFeatures {
        compose = true
        // Generates BuildConfig (incl. VERSION_NAME) so the UI can show the
        // app version. Off by default in recent AGP.
        buildConfig = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.jackson.core)
    implementation(libs.maplibre.android.sdk)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.android.billing.ktx)
    implementation(libs.google.play.app.update.ktx)
    testImplementation(libs.junit)
    // Test-only: an independent JSON parser to validate GeoJsonExporter output.
    // The app itself writes GeoJSON by hand and reads Timeline.json with Jackson.
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // --- Screenshot automation (Fastlane screengrab). androidTest only, so
    // none of this ships in the release AAB. ---
    androidTestImplementation("tools.fastlane:screengrab:2.1.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    // Pin a consistent AndroidX Test stack. ActivityScenario (core) and
    // ActivityInvoker (monitor) must come from matching versions, otherwise
    // ActivityScenario.launch throws NoClassDefFoundError at runtime.
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:monitor:1.7.2")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}