# Automated Play Store screenshots (Fastlane screengrab)

This captures all six store screenshots, in all 18 languages, on phone + 7"
tablet + 10" tablet, and uploads them to Play — without you tapping anything.

It works by letting an instrumented UI test (`ScreenshotTest.kt`) drive the app
while **screengrab** changes the device language for each run and grabs a PNG of
each screen. **supply** (`upload_to_play_store`) then pushes the PNGs to Play.

The six shots (per language):

| File                 | Screen                                            |
|----------------------|---------------------------------------------------|
| `01_hero.png`        | Full-screen map + **auto-added "100% on-device" caption** (generated from 03) |
| `02_overview.png`    | Main page with the demo Timeline loaded           |
| `03_map_fullscreen.png` | Expanded world map (clean)                     |
| `04_calendar.png`    | Date-range picker (calendar)                      |
| `05_formats.png`     | Export-format buttons (GPX / KML / GeoJSON / CSV) |
| `06_start_empty.png` | First launch, nothing loaded                      |

> Nothing here ships in the release AAB. The test deps are `androidTest`-only,
> and the FileProvider + permissions live in `src/debug`.

---

## What I already set up in the repo

```
app/build.gradle.kts                      + screengrab / test deps
app/src/debug/AndroidManifest.xml         FileProvider + screengrab permissions
app/src/debug/res/xml/screengrab_file_paths.xml
app/src/androidTest/assets/timeline_demo.json   synthetic demo data (see below)
app/src/androidTest/.../ScreenshotTest.kt        captures 02..06 (01_hero is generated)
tools/generate_demo_timeline.py           regenerates the demo data
tools/add_hero_caption.py                 builds 01_hero by captioning the map shot
fastlane/Appfile                          package name + key path
fastlane/Fastfile                         lanes: screens_phone / _tablet7 / _tablet10 / upload_screens
fastlane/Screengrabfile                   18 locales, apk paths
fastlane/screenshot_captions.txt          the #1 caption in 18 languages
Gemfile                                    fastlane
```

The demo data is **synthetic** — a made-up trip across 10 cities on 6 continents
(New York → London → Paris → Cairo → Dubai → Mumbai → Tokyo → Sydney → Cape Town
→ São Paulo). That gives a striking world map where the ocean hops render as
dashed "flight" connectors, and it keeps your real 60 MB history (and your
privacy) out of the store. Regenerate any time with:

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
Expect ~1–2 min per locale, so a full phone run is ~25–40 min. Results land in:

```
fastlane/metadata/android/<locale>/images/phoneScreenshots/
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

## The #1 caption ("100% on-device") — automatic

`01_hero.png` is **not** captured by the test. Instead, `tools/add_hero_caption.py`
takes the clean full-screen map (`03_map_fullscreen.png`) and overlays the
localized caption from `fastlane/screenshot_captions.txt`, writing `01_hero.png`
for the phone and both tablet sizes. The `screens_*` lanes run it automatically
after capture, so you normally don't do anything.

One-time dependency:

```bash
pip install Pillow
```

Pillow's standard Windows/macOS wheels include the **raqm** layout engine, which
the script needs to shape Arabic and Hindi correctly (and to render Arabic
right-to-left). If you ever see a "libraqm missing" warning, reinstall Pillow
from a wheel that bundles it; Latin/Cyrillic/CJK still render fine without it.

To re-run captioning by hand (e.g. after editing a caption):

```bash
python tools/add_hero_caption.py
```

Edit the wording in `fastlane/screenshot_captions.txt` (one line per language).
The script picks a font that covers each script automatically.

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
  `Screengrab.screenshot("07_xyz")` after navigating to the new state.
- **Different demo trip:** edit the `CITIES` list in
  `tools/generate_demo_timeline.py` and re-run it.
- **Promote to production later:** screenshots are shared across tracks, so once
  they look right on internal they're already on the listing.
