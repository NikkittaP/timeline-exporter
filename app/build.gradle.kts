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
        //       looking broken on real phone exports: it crawled through the
        //       first few percent, then rocketed to 100% in under a second.
        //       Root-caused against Timeline.json (62 MB, one of the dev
        //       fixtures under F:/Timeline): the rawSignals key that trails
        //       semanticSegments in every real export starts at byte
        //       5,164,108 of 62,884,513 - 8% in - and once semanticSegments
        //       has already produced path points, every one of the
        //       remaining ~57 MB of rawSignals entries is a
        //       p.skipChildren() with no object allocation, not a real
        //       parse. Reporting that skip on the same byte scale as the
        //       expensive stage before it is exactly the crawl-then-rocket
        //       the report described - the byte count was accurate; the
        //       assumption that a byte costs the same everywhere wasn't.
        //
        //       ParserStage gains a SkippingFallbackData case, raised
        //       instead of ExtractingSegments from the rawSignals loop
        //       whenever it is only skipping (points already came from
        //       semanticSegments). LoadingPhase.FinishingUp mirrors it on
        //       the UI side and renders as the existing indeterminate
        //       spinner (null progress), the same way SortingPoints already
        //       does - no new progress math, just admitting this phase's
        //       duration doesn't track its size. New test: skipping a
        //       redundant rawSignals tail reports SkippingFallbackData, not
        //       ExtractingSegments.
        //
        //       Still on versionCode 15 - unspent, not yet uploaded. One new
        //       string, loading_finishing, added to values/ and values-ru/
        //       only; the other 16 locales fall back to the English default
        //       until translated.
        versionCode = 15
        versionName = "1.7.2"

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