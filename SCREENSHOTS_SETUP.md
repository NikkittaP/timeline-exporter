# Automated Play Store screenshots (Fastlane screengrab)

This captures every store screenshot, in all 18 languages, on phone + 7"
tablet + 10" tablet, renders the framed store art from them, and uploads it to
Play — without you tapping anything. One double-click on
**`run_all_screens.bat`** does all of it, emulators included: start it and walk
away (see [The one-command run](#the-one-command-run)).

It works by letting an instrumented UI test (`ScreenshotTest.kt`) drive the app
while **screengrab** runs it once per language and grabs a PNG of each screen.
**supply** (`upload_to_play_store`) then pushes the PNGs to Play.

The language of a pass is set **inside the app**, not on the device: the test's
`applyAppLanguage()` writes the same in-app language override the language
picker writes (`LocaleManager`). screengrab does switch the device locale, but
on Android 13+ a per-app locale wins over the system one, so the device switch
alone leaves the UI in whatever the app was last left in — see
[Troubleshooting](#every-language-came-out-in-english).

The raw shots the test captures (per language):

| File                    | Screen                                            |
|-------------------------|---------------------------------------------------|
| `02_overview.png`       | Main page with the demo Timeline loaded           |
| `03_map_fullscreen.png` | Expanded world map (clean)                        |
| `05_formats.png`        | Export-format buttons (GPX / KML / GeoJSON / CSV) |
| `06_start_empty.png`    | First launch, nothing loaded                      |
| `07_movement.png`       | Movement breakdown expanded: per-type trips and distance, plus the two point filters |
| `08_places.png`         | Step 3 on the Places side: the map of visited places above the list that names them |
| `09_time_of_day.png`    | The time-of-day window dialog, a preset chosen     |
| `10_days.png`           | Expanded map, "7 days" preset, Color by day on, one day of the Alps road trip picked from the day list |

Those are **not** what Play gets. A second stage ("Aurora", `tools/store_shots/`)
renders each raw shot into framed store art — gradient background, device frame,
localized headline — and that is what lands in `fastlane/metadata/`:

| Store file         | Built from              |
|--------------------|-------------------------|
| `01_map.png`       | `03_map_fullscreen.png` |
| `02_days.png`      | `10_days.png`           |
| `03_places.png`    | `08_places.png`         |
| `04_overview.png`  | `02_overview.png`       |
| `05_timeofday.png` | `09_time_of_day.png`    |
| `06_formats.png`   | `05_formats.png`        |
| `07_movement.png`  | `07_movement.png`       |
| `08_privacy.png`   | `06_start_empty.png`    |
| feature graphic (1024x500) | `03_map_fullscreen.png` |

Play orders screenshots by file name, shows the first three without scrolling
and takes at most eight. That is the whole budget. The newest features go right
after the hero: Color by day (1.8.0), then Places (1.7.0). The date-range
calendar made way for Color by day — the date presets are already on the
overview, and the picker itself is a stock Material dialog.

### The status bar is ours, not the emulator's

Every capture has its top and bottom system bars cropped off, and a status bar
of our own drawn in place of the top one — a fixed 9:41, Wi-Fi, signal and a
full battery. That is what keeps the art free of the emulator's clock, its
SD-card and debug notification icons, and whatever a given AVD's SystemUI does
with its side padding (the Pixel_7 image collapses the left inset to ~1 dp,
which is why the clock looks jammed into the corner on screen). The bottom crop
takes the gesture pill on the phone and, on a tablet, the taskbar full of
Google's own app icons.

The heights are per slot, in raw capture pixels, in `config.json`:
`slots[].statusBarPx` and `slots[].bottomCropPx`. Change the AVD and those two
numbers may need re-measuring — the top one is roughly twice the y-centre of
the clock in a raw capture. `statusBar.enabled: false` puts the real bars back.

> Nothing here ships in the release AAB. The test deps are `androidTest`-only,
> and the FileProvider + permissions live in `src/debug`.

---

## Quick reference (the commands you actually type)

One-time, installs the renderer (Node 18+):

```bash
cd tools/store_shots && npm install && npx playwright install chromium
```

Then, from `TimelineApp/`, the whole job — all three emulators, all 18
languages, store art, and the upload to Play:

```bash
run_all_screens.bat -Upload
```

Leave out `-Upload` to capture and render without touching Play. No emulator
needs to be running beforehand, and no `adb root` by hand: the script boots,
prepares and shuts down each AVD itself. A full run with the upload took 1 h
23 min on 2026-09-23. Details in [The one-command run](#the-one-command-run).

### By hand, one lane at a time

From `TimelineApp/`, **once per emulator boot**:

```bash
adb root
```

Skipping it is the single most common way this fails — see
[Troubleshooting](#troubleshooting). Then, with the matching AVD booted:

```bash
bundle exec fastlane screens_phone       # phone AVD booted
bundle exec fastlane screens_tablet7     # 7" AVD booted
bundle exec fastlane screens_tablet10    # 10" AVD booted
bundle exec fastlane decorate_screens    # re-render everything, NO emulator needed
bundle exec fastlane upload_screens      # push to Play (internal track)
```

One device class per run, one emulator attached at a time. The three lanes
write into different Play folders and do not overwrite each other.

`screens_phone` does three things now: capture -> ingest (move the raw PNGs to
`fastlane/screenshots_raw/`) -> decorate. Because the raw shots are kept, any
copy/palette change afterwards is just `decorate_screens` — no rebuild, no
emulator, seconds instead of half an hour.

Details of the renderer (config, per-locale texts, palettes, slots) live in
[`tools/store_shots/README.md`](tools/store_shots/README.md).

---

## What I already set up in the repo

```
app/build.gradle.kts                      + screengrab / test deps
app/src/debug/AndroidManifest.xml         FileProvider + screengrab permissions
app/src/debug/res/xml/screengrab_file_paths.xml
app/src/androidTest/assets/timeline_demo.json   synthetic demo data (see below)
app/src/androidTest/.../ScreenshotTest.kt        captures the raw 02..10 shots
tools/generate_demo_timeline.py           regenerates the demo data
tools/demo_routes.json                    cached road geometry for the demo road trip
tools/store_shots/                        the "Aurora" store-art renderer (see its README)
tools/add_hero_caption.py                 DEPRECATED Pillow captioner, superseded by the above
fastlane/Appfile                          package name + key path
fastlane/Fastfile                         lanes: screens_phone / _tablet7 / _tablet10 /
                                          decorate_screens / upload_screens
fastlane/Screengrabfile                   18 locales, apk paths
fastlane/screenshots_raw/                 raw captures, kept so re-rendering needs no emulator
fastlane/screenshot_captions.txt          old captions, only used by add_hero_caption.py
run_all_screens.bat                       the one-command run (double-click)
tools/run_all_screens.ps1                 what the .bat runs: boots each AVD, runs its lane
Gemfile                                    fastlane
```

The demo data is **synthetic** — two years of an ordinary life based in Malmö,
Sweden: weekday commutes (by bike, on foot, by bus or by car — the office is
1.7 km away, so a car every day would be the odd choice, and a Timeline with
one movement type in it cannot show what the movement filter is for), lazy or
errand-y weekends, day trips over the bridge to Copenhagen by train, and about
a dozen real vacations spread out every couple of months (Stockholm, New York, Gothenburg, Rome, Prague, Dubai,
Barcelona, Tokyo, Lisbon, Vienna, Krakow). The last 7 days are a car road trip
through the Alps (Malmö → Hamburg → Zurich → Lucerne → Interlaken → Grimsel and
Oberalp passes → Innsbruck → Timmelsjoch → Bolzano/Dolomites → Brenner → Munich
→ Rostock, ferry to Trelleborg → home), one stretch per calendar day and a road
of its own for each, so coloured by day no day is painted over by another —
that week is what `10_days` shows. Flights render as dashed connectors across
the long gaps, drives as solid lines, and it keeps your real 60 MB history (and
your privacy) out of the store. Regenerate any time with:

```bash
python tools/generate_demo_timeline.py
```

The road trip follows real roads: its legs were routed once over OpenStreetMap
with the public OSRM demo server and cached in `tools/demo_routes.json`, so the
command above needs no network. Only after changing `ROAD_LEGS` re-route them:

```bash
python tools/generate_demo_timeline.py --refresh-routes
```

`ScreenshotTest.FOCUS_DAY` names the trip day `10_days` picks; move it if the
trip's dates move.

---

## One-time setup

### 1. Install Ruby + Fastlane

- Install Ruby (https://rubyinstaller.org/ on Windows — pick the "Ruby+Devkit"
  build), then from `TimelineApp/`:

```bash
gem install bundler
bundle install
```

Check it: `bundle exec fastlane --version`.

### 2. Android SDK command-line tools on PATH

screengrab uses `adb`. Make sure `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) is set
and `adb` works:

```bash
adb --version
```

On this machine the SDK is at `G:\Android\sdk` (`sdk.dir` in
`local.properties`), and `emulator` is not on PATH — use
`G:\Android\sdk\emulator\emulator.exe`. `run_all_screens.ps1` reads the SDK
path from `local.properties` itself, so it needs neither on PATH.

### 3. Play service account (only needed for the upload step)

1. Play Console → **Setup → API access**.
2. Create / link a Google Cloud project, then **Create service account** →
   in Google Cloud give it a key (JSON) → in Play Console grant it
   **Admin (or "Release to testing tracks" + "Edit store listing")** for this app.
3. Save the JSON as `fastlane/play-service-account.json` (already gitignored).
4. Verify: `bundle exec fastlane run validate_play_store_json_key`.

### 4. Create the emulators (AVDs)

In Android Studio → **Device Manager → Add a virtual device**, create three,
each with a recent system image **that has Google APIs and network** (the map
needs internet for tiles):

- **Phone** — e.g. Pixel 7 (API 34/35).
- **7" tablet** — e.g. Nexus 7 (API 34/35).
- **10" tablet** — e.g. Pixel Tablet (API 34/35).

Tip: pick images **without** the Play Store (Google APIs only) so screengrab can
set the locale freely.

---

## The one-command run

```bash
run_all_screens.bat                                  # all three slots, all 18 languages
run_all_screens.bat -Upload                          # ...then upload_screens
run_all_screens.bat -Slots phone -Locales ru-RU,ar   # quick check of one change
```

From Explorer, a double-click is the first line. The window stays open at the
end (`pause`) so the summary can be read when you come back.

For each slot in turn — `phone`, `tablet7`, `tablet10` — the script
(`tools/run_all_screens.ps1`):

1. shuts down any running emulator, so exactly one device is attached;
2. cold-boots the slot's AVD (`Pixel_7`, `7_inch_Tablet`, `Pixel_Tablet`),
   minimised but with a window: with `-no-window` nothing consumes the frames
   and the AVD stalls;
3. waits for `sys.boot_completed`, runs `adb root`, keeps the screen on
   (`svc power stayon true`) and lets SystemUI settle for 30 s;
4. runs the slot's `screens_*` lane — capture, ingest, decorate;
5. shuts the emulator down.

A lane that fails is run once more on a freshly booted emulator. After the
slots come `decorate_screens` for everything, and with `-Upload`
`upload_screens` — only if every slot went through, so a half-captured set
never reaches Play.

While it runs the PC is kept from going to sleep (the monitor may still turn
off). The whole output, with a timestamped line per step, goes to
`build/screens_logs/screens_<date>_<time>.log`, and the run ends with a table:

```
=== Done in 01:22:45 ===
  phone     OK
  tablet7   OK
  tablet10  OK
  upload    OK
  decorate  OK
```

The exit code is 0 only when every line says OK.

| Option     | Default                   | Meaning                                        |
|------------|---------------------------|------------------------------------------------|
| `-Slots`   | `phone,tablet7,tablet10`  | Which slots, comma-separated, in that order    |
| `-Locales` | all 18                    | Passed to the lanes as `locales:<value>`       |
| `-Upload`  | off                       | Run `upload_screens` after a clean run         |

A `-Locales` run leaves the other languages' raw captures in place, and the
closing `decorate_screens` re-renders their store art, so a quick check does
not strip the listing of the rest.

---

## Capturing by hand

What the script does, one step at a time. Run one device class at a time. Boot the matching emulator first (only one
device attached, or pass `--specific_device <id>`).

**First, enable adb root on the emulator** — once per boot, every time:

```bash
adb root          # prints "restarting adbd as root"
adb shell whoami  # must print: root
```

screengrab writes the PNGs into the app's private storage, and without root
they cannot be copied back off it. The run does **not** fail loudly when you
forget: the tests pass, screengrab reports the copy, and you are left with one
mangled PNG per language. See
[Troubleshooting](#the-run-succeeds-but-you-get-one-corrupt-png-per-language).

Then capture:

```bash
# Phone
bundle exec fastlane screens_phone

# 7" tablet  (boot the 7" AVD first)
bundle exec fastlane screens_tablet7

# 10" tablet (boot the 10" AVD first)
bundle exec fastlane screens_tablet10
```

To check a change without sitting through all 18 languages, name the ones you
want — the lane then captures, ingests and re-renders only those:

```bash
bundle exec fastlane screens_phone locales:ru-RU,ar
```

Each lane rebuilds the debug + test APKs, then loops through all 18 locales.
Expect ~1.2 min per locale, so a full phone run is ~25 min. The lane then
moves the raw PNGs aside and renders the store art on top of them:

```
fastlane/screenshots_raw/<locale>/<slot>/                       raw, kept for re-rendering
fastlane/metadata/android/<locale>/images/phoneScreenshots/     what Play gets
fastlane/metadata/android/<locale>/images/sevenInchScreenshots/
fastlane/metadata/android/<locale>/images/tenInchScreenshots/
```

Open a few and sanity-check the map actually rendered (needs network), the UI is
in the right language, and the count is eight per locale — not one.

Anything odd, go to [Troubleshooting](#troubleshooting).

---

## Tablets

`run_all_screens.bat` covers the tablets too. By hand it is the same three
steps as the phone, run once per device class. What differs is only
which AVD is booted and which lane you call.

| Device class | AVD here        | Lane                    | Play folder            |
|--------------|-----------------|-------------------------|------------------------|
| Phone        | `Pixel_7`       | `screens_phone`         | `phoneScreenshots`     |
| 7" tablet    | `7_inch_Tablet` | `screens_tablet7`       | `sevenInchScreenshots` |
| 10" tablet   | `Pixel_Tablet`  | `screens_tablet10`      | `tenInchScreenshots`   |

```bash
# list what you have
emulator -list-avds

# boot one (or start it from Android Studio's Device Manager)
emulator -avd 7_inch_Tablet &

# once it has booted, per boot:
adb root

bundle exec fastlane screens_tablet7
```

Points worth knowing:

- **Only one device attached at a time.** screengrab picks a device on its own;
  with a phone and a tablet both running it may grab the wrong one. Either shut
  the others down, or pass `--specific_device emulator-5556`.
- **Runs do not clobber each other.** `clear_previous_screenshots(true)` in
  `fastlane/Screengrabfile` clears only the folder for the class being captured,
  so a tablet run leaves the phone shots alone. Capturing all three is three
  separate runs, not one.
- **Tablets capture in landscape** (1920×1080 and 2560×1600 on these AVDs). The
  renderer detects orientation from the image itself, puts the headline on top
  and centres the device in what is left. Play accepts both 16:9 and 9:16, so
  there is nothing to configure.
- **Re-rendering is per slot**: `bundle exec fastlane decorate_screens
  slot:sevenInch`. Slot ids are `phone`, `sevenInch`, `tenInch` — the same names
  the lanes pass internally.
- Play requires tablet screenshots only if you declare tablet support in the
  listing, but a listing with phone-only art looks broken on a tablet.

---

## Decorating the shots ("Aurora")

The framed store art is rendered by `tools/store_shots/render.mjs`: an HTML/CSS
template drawn by headless Chromium (Playwright). Chromium does the text shaping,
so Arabic (RTL + ligatures), Hindi, Thai and CJK come out right without Pillow or
libraqm.

One-time dependency (Node 18+):

```bash
cd tools/store_shots && npm install && npx playwright install chromium
```

There is also a `bundle exec fastlane setup_store_shots` wrapper, but it drives
npx with `--prefix`, which npx accepts without actually resolving from that
folder — the two-command form above is the one to trust.

The `screens_*` lanes call it for you. To re-render **without** an emulator or a
rebuild — after a copy edit, a palette change, a template tweak:

```bash
bundle exec fastlane decorate_screens                     # all locales, all slots
bundle exec fastlane decorate_screens slot:phone
bundle exec fastlane decorate_screens locales:ru-RU,ar palette:orange
```

It reads the raw captures from `fastlane/screenshots_raw/`, so this works as long
as you have captured at least once. Headline/subtitle wording per language lives
in `tools/store_shots/locales/<play-locale>.json`; frames, palettes, slot sizes
and geometry in `tools/store_shots/config.json`. Both are documented in
[`tools/store_shots/README.md`](tools/store_shots/README.md).

> The old Pillow captioner (`tools/add_hero_caption.py`, `add_hero_captions`
> lane, `fastlane/screenshot_captions.txt`) is superseded and no longer wired
> into the capture lanes. It is kept only so older runs stay reproducible.

---

## Uploading to Play

Dry-run first (validates, changes nothing):

```bash
bundle exec fastlane upload_screens_dryrun
```

Then the real upload (pushes **only** screenshots to the **internal** track;
your listing text, release notes and AAB are left untouched):

```bash
bundle exec fastlane upload_screens
```

`run_all_screens.bat -Upload` runs the same lane at the end of a clean capture.

supply attaches the screenshots to a release on the internal track, so a build
must already be there. Both upload lanes ask Play for the newest version code
on that track (`internal_version_code` in the Fastfile) instead of naming one;
it used to be hard-coded as 18 and went stale with every upload. Check what is
there with:

```bash
bundle exec fastlane run google_play_track_version_codes track:internal
```

Open Play Console → your app → **Store listing → Phone / Tablet** and confirm the
images per language, then save/submit as usual.

---

## Troubleshooting

### `Tests failed for locale de-DE` — "No compose hierarchies found"

```
java.lang.IllegalStateException: No compose hierarchies found in the app.
```

Both tests of one language fail, often well into a run (en-US and ar went
through, then de-DE died). The emulator's screen had gone off. With the display
asleep a launched activity never resumes, so Compose has nothing to find.
`dumpsys power` shows it: `mWakefulness=Asleep`.

`ScreenshotTest.wakeDevice()` now wakes the device, dismisses the keyguard and
sets `svc power stayon true` before every test, and `run_all_screens.ps1` does
the same after boot. If it happens anyway on a hand-started AVD:

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell svc power stayon true
```

### The run "succeeds" but you get one corrupt PNG per language

Symptom, straight from a real log:

```
adb: error: failed to stat remote object '/data/data/<pkg>/app_screengrab/en-US/images/screenshots': Permission denied
$ adb shell run-as <pkg> "tar -cC .../images screenshots" | tar -xv -f- -C ...
screenshots/06_start_empty.png
tar: Skipping to next header
tar: Exiting with failure status due to previous errors
Screenshots copied to fastlane/metadata/android/en-US/images/phoneScreenshots
```

The tests pass (`OK (3 tests)`) and screengrab cheerfully reports the copy, but
each locale ends up with **one** file instead of five, and that one is corrupt.

Two things went wrong, both from the same cause — **you did not run `adb root`**:

1. `adb pull` cannot read the app's private storage, so screengrab falls back to
   `adb shell run-as … tar | tar -x`.
2. On Windows `adb shell` runs the stream through text mode, turning every `0A`
   into `0D 0A`. The tar stream is corrupted after the first member, so tar
   stops. The PNG that *did* land starts `89 50 4E 47 0D 0D 0A 1A 0D 0A` instead
   of `89 50 4E 47 0D 0A 1A 0A` — mangled, not merely truncated.

Fix, before the lane, once per emulator boot:

```bash
adb root          # prints "restarting adbd as root"
adb shell whoami  # must print: root
```

**The captures are still on the device**, so a crashed or mangled run does not
mean re-capturing. After `adb root` you can pull them by hand:

```bash
adb pull /data/data/io.github.nikkittap.timelineexporter/app_screengrab/en-US/images/screenshots .
```

> In Git Bash, prefix that with `MSYS_NO_PATHCONV=1` or the `/data/...` path is
> rewritten to a Windows path and adb reports "No such file or directory".

If `adb root` answers "adbd cannot run as root in production builds", the AVD
uses a **Google Play** system image; recreate it with **Google APIs**.

### Every language came out in English

The device locale is not what this app renders in. It carries an in-app language
setting (Settings → Language, `LocaleManager`), and from Android 13 on that is a
**per-app locale** held by the system — it overrides the system language, which
is the whole point of the feature. screengrab's `LocaleTestRule` switches the
*device* locale between passes, and the app went on ignoring it. With
`reinstall_app(false)` the app's data survives the whole run, so whatever
language it was last left in (English) was what all 18 passes captured.

The fix is in `ScreenshotTest.kt`: `applyAppLanguage()` runs before each test and
sets the app's own override to the language of the pass. The pass's locale
arrives as the `testLocale` instrumentation argument — the value straight out of
`Screengrabfile` — and is matched against the languages the app actually ships
(`@xml/locales_config`) by RFC 4647 lookup, so `en-US` → `en`, `hi-IN` → `hi`,
while `pt-BR`, `zh-CN` and `zh-TW` keep their region.

Two details worth knowing if you touch it:

- The labels the test clicks are read from a context pinned to that language,
  not from the app context. On Android 13+ `setApplicationLocales` stores the
  choice synchronously but the new configuration reaches the process a moment
  later, so lookups off the app context can still come back in the old language
  and match nothing on screen.
- The app is left in the last language of the run — nothing resets it, on
  purpose: changing a per-app locale with no activity on screen can have the
  system stop the app process, which during instrumentation is also the test
  process. Set the language by hand in the app if it bothers you.

Running the test outside screengrab (Android Studio, `connectedAndroidTest`)
passes no `testLocale`, so the app is set to "follow the system" and behaves as
before.

### `uninitialized constant TTY::Screen::Fiddle` — fastlane dies mid-run

Ruby 4.0 dropped `fiddle` from the default gems. fastlane's `tty-screen` still
requires it to measure the terminal, so **every table fastlane prints** blows up
— including the one it prints while reporting an error, which means the real
failure never reaches you. The tell-tale line appears at the very top of the
run:

```
warning: fiddle used to be loaded from the standard library, but is not part of the default gems since Ruby 4.0.0.
```

Fixed by `gem "fiddle"` in the Gemfile — run `bundle install`. More broadly,
fastlane 2.236 predates Ruby 4.0; if odd Ruby errors keep appearing, install
Ruby 3.3.x and point this project at it.

### `Process crashed (FastlanePtyError)` partway through the locales

`am instrument` returned non-zero for that locale. `pty` is not available on
Windows, so fastlane uses a `popen` fallback and reports every failure with this
one unhelpful message. With the `fiddle` fix in place fastlane can at least
print what actually failed. Check the locale's folder on the device: a partial
set (four of five) means the instrumented test itself died — usually the map
never finished loading, so raise `MAP_TILE_WAIT_MS` in `ScreenshotTest.kt` and
re-run just that language.

### `Unable to capture screenshot` — a null `Bitmap` in `captureEmptyStart`

```
java.lang.RuntimeException: Unable to capture screenshot.
Caused by: java.lang.NullPointerException: Attempt to invoke virtual method 'void android.graphics.Bitmap.recycle()' on a null object reference
```

UiAutomation gives a display capture one second and returns no bitmap past it
(logcat: `captureDisplay`, then `takeScreenshot() returned null` a second
later). The first shot of a pass is the one that misses: screengrab has just
switched the device locale, and the launcher, SystemUI and the keyboard spend
tens of seconds rebuilding for it. Waiting longer does not reliably help;
trying again does, so every shot goes through `shoot()` in `ScreenshotTest.kt`,
which retries a failed capture (`SCREENSHOT_ATTEMPTS`, `SCREENSHOT_RETRY_MS`).
A failed try leaves a 0-byte PNG on the device that the next try overwrites.

### A shot is taken at the wrong scroll position

`05_formats` showing step 2 instead of the export card, or `07_movement`
showing the top of the page: a scroll made while something is still animating
(the time-of-day dialog closing, the movement block expanding) is undone by
that animation. Those steps `settle()` before and after scrolling; raise the
waits rather than removing them. Compare file sizes of one shot across
locales in `fastlane/screenshots_raw/*/phone/` to spot the odd ones out.

### `SecurityException: … has not requested permission android.permission.DUMP`

Cosmetic. screengrab grants `DUMP` to put the status bar in demo mode. The
permission is now declared in `app/src/debug/AndroidManifest.xml`, so this
should be gone; if you see it again, that manifest was not merged into the debug
build.

### Blank map

The emulator had no internet. Cold-boot it, confirm a browser loads a page, then
raise `MAP_TILE_WAIT_MS` in `ScreenshotTest.kt`.

---

## Customising

- **Fewer languages:** trim the `locales([...])` list in `fastlane/Screengrabfile`.
- **Different / more screens:** edit `ScreenshotTest.kt` — add a
  `Screengrab.screenshot("07_xyz")` after navigating to the new state, then add a
  matching entry to `frames` in `tools/store_shots/config.json` and a text block
  to every `tools/store_shots/locales/*.json`.
- **Different wording / colours on the store art:** `tools/store_shots/locales/`
  and the `palette` key in `tools/store_shots/config.json`, then
  `bundle exec fastlane decorate_screens`.
- **Different demo trip:** edit `HOME_LATLON`, `NEARBY`, `VACATIONS`, or the
  road trip (`ROAD_LEGS` and `alps_road_trip_segments`) in
  `tools/generate_demo_timeline.py` and re-run it — with `--refresh-routes`
  if `ROAD_LEGS` changed.
- **Promote to production later:** screenshots are shared across tracks, so once
  they look right on internal they're already on the listing.
