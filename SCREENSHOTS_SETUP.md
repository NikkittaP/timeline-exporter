# Automated Play Store screenshots (Fastlane screengrab)

This captures every store screenshot, in all 18 languages, on phone + 7"
tablet + 10" tablet, renders the framed store art from them, and uploads it to
Play — without you tapping anything.

It works by letting an instrumented UI test (`ScreenshotTest.kt`) drive the app
while **screengrab** changes the device language for each run and grabs a PNG of
each screen. **supply** (`upload_to_play_store`) then pushes the PNGs to Play.

The raw shots the test captures (per language):

| File                    | Screen                                            |
|-------------------------|---------------------------------------------------|
| `02_overview.png`       | Main page with the demo Timeline loaded           |
| `03_map_fullscreen.png` | Expanded world map (clean)                        |
| `04_calendar.png`       | Date-range picker (calendar)                      |
| `05_formats.png`        | Export-format buttons (GPX / KML / GeoJSON / CSV) |
| `06_start_empty.png`    | First launch, nothing loaded                      |
| `07_movement.png`       | Movement breakdown expanded: per-type trips and distance, plus the two point filters |

Those are **not** what Play gets. A second stage ("Aurora", `tools/store_shots/`)
renders each raw shot into framed store art — gradient background, device frame,
localized headline — and that is what lands in `fastlane/metadata/`:

| Store file        | Built from              |
|-------------------|-------------------------|
| `01_map.png`      | `03_map_fullscreen.png` |
| `02_overview.png` | `02_overview.png`       |
| `03_movement.png` | `07_movement.png`       |
| `04_calendar.png` | `04_calendar.png`       |
| `05_formats.png`  | `05_formats.png`        |
| `06_privacy.png`  | `06_start_empty.png`    |
| feature graphic (1024x500) | `03_map_fullscreen.png` |

Play orders screenshots by file name and shows the first three without
scrolling, which is why the movement breakdown sits third.

> Nothing here ships in the release AAB. The test deps are `androidTest`-only,
> and the FileProvider + permissions live in `src/debug`.

---

## Quick reference (the commands you actually type)

One-time, installs the renderer (Node 18+):

```bash
cd tools/store_shots && npm install && npx playwright install chromium
```

Then, from `TimelineApp/`, **once per emulator boot**:

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
app/src/androidTest/.../ScreenshotTest.kt        captures the raw 02..06 shots
tools/generate_demo_timeline.py           regenerates the demo data
tools/store_shots/                        the "Aurora" store-art renderer (see its README)
tools/add_hero_caption.py                 DEPRECATED Pillow captioner, superseded by the above
fastlane/Appfile                          package name + key path
fastlane/Fastfile                         lanes: screens_phone / _tablet7 / _tablet10 /
                                          decorate_screens / upload_screens
fastlane/Screengrabfile                   18 locales, apk paths
fastlane/screenshots_raw/                 raw captures, kept so re-rendering needs no emulator
fastlane/screenshot_captions.txt          old captions, only used by add_hero_caption.py
Gemfile                                    fastlane
```

The demo data is **synthetic** — two years of an ordinary life based in Malmö,
Sweden: weekday commutes (by bike, on foot, by bus or by car — the office is
1.7 km away, so a car every day would be the odd choice, and a Timeline with
one movement type in it cannot show what the movement filter is for), lazy or
errand-y weekends, day trips over the bridge to Copenhagen by train, and about
a dozen real vacations spread out every couple of months (Stockholm, New York, Gothenburg, Rome, Prague, Dubai,
Barcelona, Tokyo, Lisbon, Vienna, Krakow). The last 7 days are a car road trip
through the Alps (Malmö → Hamburg → Zurich → Lucerne → Interlaken → Innsbruck
→ Bolzano/Dolomites → Munich → home). Flights render as dashed connectors
across the long gaps, drives render as solid red lines, and it keeps your real
60 MB history (and your privacy) out of the store. Regenerate any time with:

```bash
python tools/generate_demo_timeline.py
```

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

(Android Studio already installed the SDK at
`C:\Users\Nikita\AppData\Local\Android\Sdk` — point `ANDROID_HOME` there.)

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

## Capturing

Run one device class at a time. Boot the matching emulator first (only one
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

Each lane rebuilds the debug + test APKs, then loops through all 18 locales.
Expect ~1–2 min per locale, so a full phone run is ~25–40 min. The lane then
moves the raw PNGs aside and renders the store art on top of them:

```
fastlane/screenshots_raw/<locale>/<slot>/                       raw, kept for re-rendering
fastlane/metadata/android/<locale>/images/phoneScreenshots/     what Play gets
fastlane/metadata/android/<locale>/images/sevenInchScreenshots/
fastlane/metadata/android/<locale>/images/tenInchScreenshots/
```

Open a few and sanity-check the map actually rendered (needs network), the UI is
in the right language, and the count is five per locale — not one.

Anything odd, go to [Troubleshooting](#troubleshooting).

---

## Tablets

Same three steps as the phone, run once per device class. What differs is only
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

Open Play Console → your app → **Store listing → Phone / Tablet** and confirm the
images per language, then save/submit as usual.

---

## Troubleshooting

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
  `alps_road_trip_segments` route in `tools/generate_demo_timeline.py` and
  re-run it.
- **Promote to production later:** screenshots are shared across tracks, so once
  they look right on internal they're already on the listing.
