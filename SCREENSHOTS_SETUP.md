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

Those are **not** what Play gets. A second stage ("Aurora", `tools/store_shots/`)
renders each raw shot into framed store art — gradient background, device frame,
localized headline — and that is what lands in `fastlane/metadata/`:

| Store file       | Built from              |
|------------------|-------------------------|
| `01_map.png`     | `03_map_fullscreen.png` |
| `02_overview.png`| `02_overview.png`       |
| `03_calendar.png`| `04_calendar.png`       |
| `04_formats.png` | `05_formats.png`        |
| `05_privacy.png` | `06_start_empty.png`    |
| feature graphic (1024x500) | `03_map_fullscreen.png` |

> Nothing here ships in the release AAB. The test deps are `androidTest`-only,
> and the FileProvider + permissions live in `src/debug`.

---

## Quick reference (the commands you actually type)

One-time, installs the renderer (Node 18+):

```bash
cd tools/store_shots && npm install && npx playwright install chromium
```

Then, from `TimelineApp/`:

```bash
bundle exec fastlane screens_phone       # capture + decorate (boot the phone AVD first)
bundle exec fastlane decorate_screens    # re-render everything, NO emulator needed
bundle exec fastlane upload_screens      # push to Play (internal track)
```

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
Sweden: weekday commutes, lazy or errand-y weekends, the occasional day trip
over the bridge to Copenhagen, and about a dozen real vacations spread out
every couple of months (Stockholm, New York, Gothenburg, Rome, Prague, Dubai,
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

**First, enable adb root on the emulator.** screengrab saves the PNGs into the
app's private internal storage; without root, `adb pull` gets "Permission
denied" and the run-as/tar fallback is unreliable on Windows. One command per
boot fixes it:

```bash
adb root          # Google APIs emulator images allow this; prints "restarting adbd as root"
adb shell whoami  # should print: root
```

> If `adb root` says "adbd cannot run as root in production builds", your AVD
> uses a **Google Play** system image. Recreate it with a **Google APIs** image
> (Device Manager → the AVD has no Play Store icon). Root is only needed to copy
> the screenshots off the device — it doesn't affect the app itself.

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

Open a few and sanity-check the map actually rendered (needs network) and the UI
is in the right language.

> If the map is blank, the emulator had no internet — cold-boot it, confirm a
> browser loads a page, and bump `MAP_TILE_WAIT_MS` in `ScreenshotTest.kt`.

> If it says **"No screenshots were detected"** even though the tests passed
> (`OK (N tests)`), the PNGs were captured but couldn't be pulled off the
> device. Run `adb root` (see above) before the lane and re-run.

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
