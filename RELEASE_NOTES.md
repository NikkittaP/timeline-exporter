# Release Notes

**This file accumulates.** Every change lands in *Unreleased* as it is made;
nothing is rewritten at release time. When a build is actually published to
Play, its whole *Unreleased* section moves down into *Released* under one
heading and the store changelog is cut from that section — not from the last
`versionName` bump.

That distinction is the reason for the format. Version codes 13 and 14 were
built, tagged in `build.gradle.kts` and never uploaded, so a store changelog
written per `versionName` would have described one third of what a 1.6.2 user
is about to receive. **The store changelog covers the span from the last
*published* build to this one**, however many internal versions it contains.

Where things go at release time:

| Artefact | Path |
| --- | --- |
| Store changelog, 18 locales | `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` |
| Same text, one tagged file | `F:\Timeline\Whats_New_v<versionName>_tagged.txt` |
| The long-form notes | this file, under *Released* |

---

# Unreleased — everything since v1.8.5

## 1.9.0 — filters remembered between sessions (versionCode 24, in progress)

1.9.0 is planned around Trips. These changes come first.

- **Trips.** Step 3's switch is now Track / Trips / Places. A trip is time
  spent more than 50 km from home: a two-week holiday is one trip, not forty
  activities. It was the most-voted open request ("a list of trips with
  start, end, time and distance", 7 votes).
  - Detection is `trips/TripDetector.kt`. Home is the latest `HOME` /
    `INFERRED_HOME` visit, so a move is followed. Without labels, home is
    where the nights are spent. Gaps at home under 3 h are merged, and
    absences under 3 h are dropped. Google's own `timelineMemory` trips were
    measured and not used: their destinations are city IDs with no name or
    position in the file, a whole year of them is missing from the 13-year
    export, and May 2026 is not there at all. On Timeline_2.json the rule
    finds the 8 trips checked by hand, and 108 of Google's 114 on the
    13-year file. The 6 it misses are under 3 h.
  - The list is newest first. Multi-day trips show dates, calendar days,
    distance from home, places and km travelled; day trips get one compact
    line. Trips starting before the file, still open, or longer than 60 days
    (possibly a move) are flagged.
  - Tapping a trip shows it alone on the map. "Only this trip" sets step 2's
    dates to the trip's, so every count and export follows it.
  - Export: GPX has one `<trk>` per trip (the ballot's "each trip as a
    separate track inside one file"). KML has one Placemark per trip with a
    TimeSpan. GeoJSON has one Feature per trip with its figures. CSV has one
    row per trip. Points are the filtered track's, so the step-2 filters
    apply.
  - New strings in all 18 locales; the trip count is a plural.
  - "km travelled" is the distance the movement data accounts for:
    - Every activity overlapping the trip is counted, not only those fully
      inside. A trip starts part-way through the drive away from home, so
      the drive used to be dropped (12-19 May 2026: 568 -> 2,151 km).
    - Each stretch of time is counted once. Merged files carry many
      activities twice (517 in the 13-year file); June 2025 went from
      14,171 to 11,821 km.
    - Track that Google never classified is measured too. Sep 2013's
      ~640 km drive each way had no activities at all: 19 -> 1,557 km.
    - When that untyped part is significant, or the file cuts the trip off,
      the trip is marked `travelledEstimated`. The UI shows "≈" before the
      figure, CSV and GeoJSON have a `travelled_estimated` column, and the
      GPX/KML description says "about".
    - "km from home" is unchanged: the greatest distance from the detected
      home of any position in the trip.
  - Copy: the hint now says trips are *detected* past 50 km (the trip
    includes the way there). The selected-trip action is "Use only this
    trip". The full list's title shows the total ("Trips · 126"), and step 4
    says "Export trip" / "Trip — 1 trip" when exactly one trip is selected.

- **Filters and export choices are remembered.** On the feedback ballot as
  "Remember my filters and format for the next export" (FB-016, FB-019).
  The movement groups, *Only while moving*, *Skip repeated points*, the
  time-of-day window, the date range, *Include containers*, Save / Send and
  *Color by day* come back with the next file, after a restart too. A
  remembered date range is applied only if it overlaps the new file's
  points; a range that misses the file would open it on zero points, so it
  is dropped. The format is not remembered, because each format button
  saves straight away and nothing is selected. The Track / Places switch
  still opens on Track.
- Re-ticking every movement group this file has now clears the movement
  filter even when the remembered selection names groups this file lacks.
- AndroidX: core-ktx 1.18.0, lifecycle 2.10.0, activity-compose 1.13.0,
  core-splashscreen 1.2.0. core 1.19 and lifecycle 2.11 need compileSdk 37.
  The TipJar dialog now gets its Activity from LocalActivity instead of
  casting LocalContext.
- Memory measured against the Play thresholds that take effect in February
  2027 (2 GB foreground, 1 GB background on a 4 GB phone). Pixel_7 AVD,
  API 34, debug build, Timeline_merged_big.json (115 MB, 284,402 points),
  fresh process: peak VmHWM 268 MB, of which anonymous ~122 MB plus 18 MB
  swap. With the map on screen and Color by day on, RssAnon is 111 MB and
  swap 26 MB, and it stays there in the background. That is roughly 1/15 of
  the foreground limit, so no work is needed. GPU memory is not counted on
  the emulator.

---

# Released

## v1.8.5 (versionCode 23) — released by 1 October 2026

The *Unreleased* section from v1.8.4 up to this upload, moved here
unchanged (headings demoted one level). Store text:
`fastlane/metadata/android/<locale>/changelogs/23.txt`.

### 1.8.5 — tracks exported coloured by day (versionCode 23, 2026-09-26)

Feedback from 1.8.4 (ja-JP): the preview colours each day, but the KML came
out as one line.

- With the map's **Color by day** switch on, a KML track is one Placemark per
  day, named by date, in the preview's colour and with a TimeSpan for Google
  Earth's time slider. GeoJSON is one Feature per day with simplestyle
  `stroke`, which geojson.io, GitHub and uMap draw.
- With the switch off the file is unchanged. GPX and CSV ignore the switch,
  because GPX has no standard line colour.
- A multi-year export produces one Placemark per day (4,479 for
  Timeline_merged_big.json). Google Earth handles that; Google My Maps caps a
  layer at 2,000 features, so for My Maps export a shorter range or leave the
  switch off.

Store text: `fastlane/metadata/android/<locale>/changelogs/23.txt` (this one
line only, 18 locales) and `F:\Timeline\Whats_New_v1.8.5_tagged.txt`.

## v1.8.4 (versionCode 22) — released by 26 September 2026

The *Unreleased* section from v1.8.3 up to this upload, moved here
unchanged (headings demoted one level). Store text:
`fastlane/metadata/android/<locale>/changelogs/22.txt`.

### 1.8.4 — the distance for the chosen dates (versionCode 21, 2026-09-22)

FB-018 asked "distance of the track from the filtered dates?". The figure
existed, but only one movement group at a time, inside the collapsed
*Movement* expander; step 1's *Distance* is the whole file.

- The *Movement* summary line in step 2 now carries the total distance for the
  chosen dates, e.g. "All · 493 km · 36 places" for the last 30 days of the
  reference export. It follows the date range, the time-of-day window and the
  ticked groups, so it is the distance of what the export will contain.
- With the section open and more than one group ticked, a bold *Total* row
  under the groups sums their trips and kilometres. "Total" is translated into
  all 17 other locales.
- `MovementStats.totalFor` does the sum; it returns null rather than 0 km when
  nothing ticked has trips. Unit-tested in `MovementTotalTest`.
- When fewer groups are unticked than ticked, the summary names the unticked
  ones: "All except Flights · 2,000 km · 96 places". Listing four of five
  groups ran past the row's two lines in Russian and cut off the distance.
- The value under a step-2 row may now take three lines instead of two. With
  two groups unticked, Russian still needed the third: "Все, кроме:
  Общественный транспорт, Перелёты · 10 478 km · 25 мест".
- The groups in that summary are joined by each language's own list comma:
  "، " in Arabic and "、" in Chinese and Japanese, instead of ", " everywhere.
  The new `list_separator` string is in all 18 locales.

Store text: `fastlane/metadata/android/<locale>/changelogs/21.txt`, and
`F:\Timeline\Whats_New_v1.8.4_tagged.txt`.

#### Still 1.8.4, now versionCode 22 (2026-09-23)

A new code for the last three items above: "All except …", the third line and
the per-language list comma. The versionName stays 1.8.4, since 1.8.4 has not
reached a store reader yet. `changelogs/22.txt` is 21.txt plus one line about
"All except …", in all 18 locales; the tagged file carries the same two lines.
Longest locale is de at 312 of Play's 500 characters.

## v1.8.3 (versionCode 20) — released 22 September 2026

Everything below was the *Unreleased* section from v1.6.2 up to this upload,
moved here unchanged (headings demoted one level). The checklists and "not
uploaded yet" notes inside it describe how things stood while it was written.
Store text: `fastlane/metadata/android/<locale>/changelogs/20.txt`.

**Status update, 2026-09-12:** versionCode 15 (versionName 1.7.0) shipped to
the internal testing track on 2026-09-05, confirmed by the person doing the
upload. The "to upload" row in the table below, and every "still on
versionCode 15, unspent" note in this section, describe how things stood
*before* that upload — they are left as written, per the no-rewriting rule
above, but they no longer describe the current state. Everything from the
ballot re-cut onward in this section is new relative to what a 1.7.0 tester
already has.

### 1.8.3 — big exports, fast (still versionCode 19, 2026-09-21)

1.8.2 was never built or uploaded, so 19 is still unspent and this is the first
AAB to carry it: it contains 1.8.2's tip ask as well as everything below.

- **Exports are streamed.** Every format writes into the destination's own
  buffered writer instead of returning the whole document as a String. On a
  Pixel 7, a 284,402-point export (115 MB of Timeline JSON) went from 5.1 s and
  a full 136 MB heap to no GC at all and 69 MB, with a byte-identical file.
  One size up would have been an `OutOfMemoryError` — a crash, not the
  friendly failure the parse path gives. Pinned by `StreamingExportTest`.
- **Per-point formatting without java.text.** Timestamps and coordinates are
  written by integer arithmetic, falling back to `ISO_INSTANT` / `BigDecimal`
  whenever the fast path could differ. Same bytes as before, except that a
  coordinate below 0.001 is no longer written as `1.0E-5`, which is not a
  valid `xsd:decimal`. Pinned by `IsoInstantTest` and `CoordinateTest`.
- **The map draws what the zoom can show.** Douglas-Peucker (iterative) at a
  quarter of a pixel's ground size, taken at the track's own latitude: 844
  points at zoom 0, 190,378 at zoom 10, the exact track past zoom 15. Runs and
  day boundaries are thinned separately so no connector jumps a gap. Pinned by
  `TrackDetailTest`.

Store text: `changelogs/19.txt` in all 18 locales and
`F:\Timeline\Whats_New_v1.8.3_tagged.txt` — 18's three items plus one for
faster big exports and a sharper map. The tip ask is left out: it is not
something a store reader needs to be told. Longest locale is fr at 473 of
Play's 500 characters.

### 1.8.2 — the tip ask moves to where the app has just helped (versionCode 19, 2026-09-20)

versionCode 18 is uploaded to internal testing, so this is a new code. **No
store changelog is cut yet**: `changelogs/19.txt` still needs writing before
this goes further than internal testing.

Until now the only way to tip was the heart in the top bar — a button you press
because you already decided to, which means it is only ever found by people who
went looking. The ask now also appears where the app has demonstrably just done
something for someone: in the slot under a finished export.

- **Where it appears.** The slot under a successful export, which the
  feature-request plaque already owned. The two take turns rather than
  stacking. Two favours asked at once — tell me what is missing, and also,
  would you pay — devalue each other, and the export result would stop being
  the thing the screen is about.
- **When it appears.** From the user's second successful export onwards. The
  first export is someone finding out whether the app works at all; asking then
  is asking a stranger. The count is kept in `SharedPreferences` and is
  deliberately persistent: the second export is usually days or weeks after the
  first, and a counter living in memory would never reach two.
- **How often.** "Not now" buys a month of silence; a tip buys half a year, and
  supersedes an earlier decline. Both timestamps sit next to the count. A
  timestamp in the future — a device clock set forward and then back — reads as
  "not elapsed", so the worst a doctored clock can do is cost one ask.
- **What it offers.** The three Play amounts as three buttons, at their real
  local prices, so tipping is one tap rather than a tap into a dialog. The row
  borrows step 4's format-button geometry — equal weights, labels that step
  their own size down — because three prices side by side is the same width
  problem as four format names, and a price in rubles is wider than "GPX".
- **Where the prices come from.** Play Billing is warmed up one export early,
  the moment a file is open and the next export would earn the ask, so the
  plaque appears with prices in it instead of a spinner. A user who is not
  about to be asked never opens a billing connection at all — confirmed on the
  emulator: a cold launch logs nothing from BillingClient.
- **When it does not appear.** No Play services, billing unreachable, or an
  empty product list — which is what Play returns when the tips are not
  configured against the package or not sold in the user's country. In every
  one of those cases the slot quietly keeps the feedback plaque. That last case
  was found by the test that now pins it: a plaque with a question in it and no
  buttons underneath.
- **The top bar is unchanged.** The heart still opens the full dialog, and a
  tip made there quiets the plaque too.

Tests: `TipPromptPolicyTest` (12, JVM) covers the rules — the two-export floor,
both cooldown boundaries either side, a tip outranking an old decline, and the
clock moved backwards. `TipPromptTest` (5, instrumented) covers the plaque —
every amount offered, the right product id reported on a tap, the ask always
declinable, an empty answer not drawn at all, and a finished purchase thanked
rather than re-offered. Its expected text is read from the app's own resources:
these emulators are left in whatever locale the last screenshot run used.

### 1.8.1 — step 4 tidy-up (versionCode 18, 2026-09-14)

versionCode 17 is uploaded to internal testing, so this is a new code. The
store text is 17's, copied unchanged to all 18 `changelogs/18.txt` and to
`F:\Timeline\Whats_New_v1.8.1_tagged.txt`: the span it describes has not
changed, and nothing here is news to someone reading the listing.

- **Save / Send switch.** Drawn by the same hand-built segmented row as the
  date presets: one line, fixed height, no tick, text that shrinks instead of
  wrapping. In ru and uk "Отправить в приложение" / "Надіслати в застосунок"
  wrapped to two lines and bent the row out of shape; in fr the label ran
  into the outline. Shorter labels: ru «Поделиться», uk «Поділитися»,
  fr «Enregistrer».
- **Format buttons.** GPX / KML / GeoJSON / CSV in one row of four equal
  widths, instead of three on the first line and CSV alone under them.

Store screenshots `06_formats` and `08_privacy` (the version footer is in
both) recaptured and re-rendered for phone, 7" and 10" tablet in all 18
languages.

### Store changelog — draft for versionCode 17 (2026-09-13)

Supersedes the versionCode 16 draft below, which stays as written. 17 is the
same 1.7.0 → now span plus the redesign (next section), still versionName
1.8.0: none of it has reached production, so it ships under one name. 16 is
treated as spent — the bump to 17 was made at the uploader's instruction.

Cut to all 18 `fastlane/metadata/android/<locale>/changelogs/17.txt` and to
`F:\Timeline\Whats_New_v1.8.0_tagged.txt` (overwritten; the 16 text lives on
in the `16.txt` files). A fifth bullet on top of 16's four would have pushed
German past Play's 500-character limit — the 16 draft was already at 498 — so
all four were rewritten shorter and the redesign folded in as the first.
With the crash bullet since dropped (see below) there are three, and the
longest translation is 340 characters (fr-FR). The last bullet also stops
calling it the *export* progress bar: the fix was to the bar shown while a
file loads.

```
• Redesigned: see which file is open, simpler date filters, a new calendar, and a dark theme that matches the map.
• Color your track by day and browse days by month; tap a day to see where you were.
• Loading progress now fills evenly instead of crawling, then jumping.
```

Not in the bullets, for the same reasons as in the 16 draft: the
feedback-ballot re-cut and the update check. Also left out, unlike the 16
draft: the fullscreen crash with Color by day on and the zoom-dependent day
count. Both were bugs in Color by day itself, which is new in this release —
no published build ever had the feature, so no user ever hit either bug, and
announcing their fix would describe internal work as a change to the app.
Dropped from all 18 `17.txt` files and the tagged file on 2026-09-13.

### Pre-release checklist for versionCode 17

- [x] Bump `versionCode` to 17 (`versionName` stays 1.8.0) — 2026-09-13
- [x] Write the store changelog into all 18 `changelogs/17.txt` and
      `Whats_New_v1.8.0_tagged.txt` — 2026-09-13
- [x] `./gradlew :app:test` — 176 tests, 0 failures
- [x] `./gradlew bundleRelease` — R8 and `lintVital` green, 2026-09-13
- [ ] Run the instrumented suite (`ScreenshotTest` was updated for the new
      UI but has not been run; `TrackDayMapTest` untouched)
- [ ] Upload the AAB to the internal testing track as versionCode 17
- [ ] On a real install, look at: light and dark theme, the calendar in both,
      step 2 in a long language (German) on a narrow phone, and an Android
      10/11 device if one is available — those are the versions that used to
      get the template purple scheme
- [ ] Move this section into *Released* only once the span is promoted to
      production — internal testing is not release

### Redesign — one visual system (local development, 2026-09-13)

The screen read as parts from different kits. The causes, all fixed here:
six corner radii on one screen; three different "pick one" controls (date
chips that wrapped three and three, segmented buttons, the calendar's own
chips); colour taken from the wallpaper while the logo stayed blue, with a
pink "Loaded" badge and emoji in the top bar; step 1 still shouting "Pick
Timeline.json" after the file had loaded and never naming the file; an
outline on everything; and the stock date picker's grey header, mid-date
line breaks and clipped presets.

- **Palette.** The app's own light and dark schemes from the logo's #1976D2;
  dynamic colour is off. The dark card surface is the dark basemap's ground
  (#191D23), so the map sits inside its card at night instead of on top of
  it. Dark mode still follows the system, as the map already did. Android
  10/11 no longer fall back to the project template's purple.
- **Shape.** Four radii from the theme: 12 dp controls, 16 dp panels and the
  map, 24 dp step cards, 28 dp dialogs. Step cards are filled, not outlined;
  only the map keeps a hairline. Vector icons replace 🌐 💜 ⋮ ▼ ›.
- **Step 1.** Once loaded: the file's name, format and span; one panel with
  points, days (counted the way the map legend counts them) and distance;
  a quiet "Choose another file". The big button is only there while nothing
  is loaded.
- **Step 2.** A date range field that shows its own value and opens the
  calendar; four equal presets (7 / 30 / 90 days, All) that shrink their text
  rather than cut it in long languages; Time of day and Movement as two rows
  of the same shape; a bar under the "After filter" count.
- **Calendar.** Close, title and Apply in one bar; the selection as one
  localized range with its day and point count (the point count uses step 2's
  own filter); days outside the file can't be picked; no grey header, no
  duplicate presets, no dim bands above and below.
- **Strings.** Five new strings in all 18 locales (`file_change_button`,
  `metric_days`, `metric_distance`, `filter_movement_title`,
  `date_preset_all_short`); nine that nothing used any more removed from all
  18.

Checked on the emulator in light and dark, and in German at 320 dp wide.

### Store changelog — draft for versionCode 16 (2026-09-12)

Covers the whole 1.7.0 → now span: the feedback ballot re-cut, the optional
update check, Color by day, the day legend/sheet/camera rework, and the four
fixes traced to the 61 MB reference export (fullscreen crash, zoom-dependent
day count, wide-viewport antimeridian math, load-progress bar). Cut to all 18
`fastlane/metadata/android/<locale>/changelogs/16.txt` and to
`F:\Timeline\Whats_New_v1.8.0_tagged.txt`. Longest translation is 498
characters (de-DE); Play's limit is 500.

```
• Color your track by day, and browse every day in a scrollable, month-grouped list.
• Tap a day — on the map or in the list — to see exactly where you were.
• Fixed a crash entering fullscreen with Color by day on, and a "days in view" count that changed with zoom instead of the camera view.
• The export progress bar now fills evenly, instead of crawling through the first few percent then jumping to done.
```

Not in the bullets, deliberately: the feedback-ballot re-cut (changes the
dialog's option list, not what the app does with a file) and the update check
(only ever seen by someone who has *already* updated) — same reasoning the
14→15 draft used to drop those two.

### Pre-release checklist for versionCode 16

- [x] Bump `versionCode` to 16 (`versionName` stays 1.8.0) — 2026-09-12
- [x] Write the store changelog into all 18 `changelogs/16.txt` and
      `Whats_New_v1.8.0_tagged.txt` — 2026-09-12
- [ ] `./gradlew :app:test`
- [ ] `./gradlew bundleRelease` — R8 and `lintVital` green
- [ ] Upload the AAB to the internal testing track as versionCode 16
- [ ] Move this section into *Released* only once the span is promoted to
      production — internal testing is not release

### Color route by day (local development, 2026-09-09)

- Added an optional **Color by day** map toggle for tracks, with a horizontally
  scrollable, localized date legend. Available in the preview and fullscreen map.
- Map controls now share one compact panel: color switch and fullscreen close
  action on one row, with the date legend underneath. The scale sits above attribution.
- The legend follows the rendered routes in the current viewport after pan/zoom,
  including lines crossing the screen with both endpoint samples offscreen.
  It hides when no colored route is visible; dashed gaps do not add legend dates.
- Colors follow each sample's historical local date (device timezone is the
  fallback when the source has no offset). Seven colorblind-safe hues turn over
  weekly and shade lighter, then darker, each week, so a color does not come
  round again for 21 days and no legend shorter than three weeks shows the same
  swatch twice. The color stays stable when filtering, and switching colors
  preserves the camera.
- Line weight and opacity in color mode are lifted from the density-tuned
  values rather than replaced by constants, so a year of history stays legible
  instead of flattening into one slab.
- Existing geographic gaps remain dashed. An edge crossing midnight uses its
  starting sample's day, retaining the connection without inventing a GPS fix.
- The legend is rebuilt at most four times a second while the map is moving and
  once exactly when the camera settles, rather than once per rendered frame.
  Local days are derived by integer arithmetic, not a ZonedDateTime per sample.
- Building the overlay — splitting runs, cutting days, making the geometry —
  now happens off the main thread, so flipping the switch or loading a file no
  longer freezes the UI for the length of it. On a 200k-sample track that is
  roughly 150 ms of work moved off the main thread, leaving a few milliseconds
  of MapLibre calls on it. The previous overlay stays on screen while the new
  one is prepared.
- Turning the colour off no longer flashes the whole track black for a frame
  before it settles. Both line colours travel on the features in both modes, so
  the frame where MapLibre pairs one side's paint with the other side's data
  draws a real colour instead of falling back to the spec default.
- The render's debug log no longer stringifies the whole subject: a data class
  wrapped around every sample in the file turned a year of history into tens of
  megabytes of string on the main thread, on every render, listener or not.
- This is a map display option; export content is unchanged. Version remains
  1.7.1 / code 15 for local development; no Play upload is planned yet.
- Covers the in-app map interpretation of FB-017. Colored exported files are
  outside this change.

**In production on Play: v1.6.2 (versionCode 12), 27 August 2026.** Nothing in
this section has reached a production user. The next production changelog must
cover *all of it*, not just the newest version bump.

| Internal version | Code | Theme | Status |
| --- | --- | --- | --- |
| 1.6.3 | 13 | Correct timezones, place names, share sheet | built, never uploaded |
| 1.7.0 | 14 | Places, a map of our own, filtering by time of day | uploaded to a testing track |
| 1.7.1 | 15 | The feedback ballot, re-cut; an optional update check | to upload |

Everything after the commit that named 1.7.0 — the Maps link fix, the step-4
plaque, the basemap rewrite, the time-of-day window, places on the map — sits
under **the same versionCode 14**, because nothing in between was ever
uploaded. `app/build.gradle.kts` carries the full entry; the sections below are
grouped the way the work happened, not the way the codes ran.

**Then 14 was uploaded to a testing track, which spends it** — Play will not
take that code twice — so the ballot re-cut is 15. The ballot alone would not
have moved versionName either: it changes nothing about what the app does
with a Timeline file, the same reason codes 1 and 2 shared 1.0.0. The update
check that followed, still under the same unspent code, does change what the
app does, so it moves versionName to 1.7.1. The whole 1.6.2 → now span is
still the one release the store changelog describes; the production upload of
this span is 15, and its changelog files are named for that code.

### v1.8.0 (still versionCode 15) — the day legend, and the map that answers back

The first half of this landed with the fullscreen rework: the `Expand` text
button left the Preview header for a control on the map itself, and the
`Color by day` legend stopped being a horizontal strip of one swatch per date
— unusable past a couple of weeks — in favour of a compact summary that opens
a scrollable, Year → Month grouped sheet. What follows is the second half.

- **The two map controls no longer look alike.** Both were four corner
  brackets at 20 dp, one meaning "full screen" and the other "frame the
  track". Full screen is now diagonal expand/collapse arrows
  (`open_in_full` / `close_fullscreen`); "Show the whole track" is a route
  glyph — a line between two endpoints, which is what that button frames.
- **The compact legend follows the camera again.** Framed on the whole trip
  it counts every day; zoomed into a corner of it, it says how many days are
  in view and over what span. Panned off the track entirely it says
  "0 days in view" rather than claiming the days it can no longer show.
- **Without the cost that behaviour used to carry.** The old version ran a
  full-viewport rendered-feature query from a per-frame listener, so a fling
  over a year of history was dozens of spatial queries a second on the main
  thread. It now runs when the map settles — camera-idle for the gesture,
  map-idle for the tiles behind it — which is once per pan, not once per
  frame.
- **The sheet has two lists**, `In view` and `All days`, each labelled with
  its count, so the complete set is always one tap away no matter where the
  camera is pointed.
- **Tapping a date takes you there.** The day is selected, the sheet closes,
  and the camera eases to everything recorded on that date — all of it, for a
  day made of several disconnected stretches — with a zoom ceiling so an
  afternoon on one street is not framed at the scale of the street.
- **Tapping a track says which day it is.** The nearest day's line within a
  forgiving hit box is selected, and a small chip near the bottom of the map
  names the date in that day's own colour. No modal sheet covers the map to
  tell you about the thing you just touched; tapping the chip, or bare
  basemap, clears the selection.
- **The selected day's line is drawn heavier and more opaque** than the same
  line unselected — never recoloured, because the colour is how the legend,
  the sheet row and the chip all name that day.
- One selected day is shared by the map layers, the tap result, the legend,
  the sheet and the camera. Opening the sheet after tapping a track scrolls
  to that date and marks the row; changing the filters clears a selection the
  track no longer contains.
- Framing the camera — "Show the whole track" and focusing one day — goes
  through one padded path that keeps the route clear of the legend card, the
  control stack and the attribution, so the two cannot disagree about where
  the usable edges of the view are.

### v1.8.0 (still versionCode 15) — the day count, and the crash behind it

Two reports against the build above, both from a 61 MB phone export of a
year's travel, and both traced to the same call.

- **The day count no longer changes with the zoom.** Framed on the whole trip
  the legend said "48 days in view"; zoomed into one corner of that same trip
  it said 211. A window that shows less cannot contain more, so the number was
  never really about the window. It was read out of the rendered map, and the
  track reaches MapLibre as GeoJSON that is tiled and simplified per zoom
  level: a day spent inside one city is simplified out of the tiles as soon as
  the camera pulls back, still on screen as one thread in a knot of lines but
  gone from anything the renderer can be asked about. The count is now read
  off the samples, so it is a function of the camera and the file alone. The
  same export that reported 48 reports 365, and 377 — all of them — once the
  card is not covering the northern end of the track.
- **Full screen no longer takes the app down with it.** With `Color by day`
  on, expanding or collapsing the map killed the process every time, in
  `Renderer::queryRenderedFeatures` on MapLibre's render thread. Re-parenting
  the map between the Preview tile and the full-screen surface destroys and
  rebuilds its GL surface, and the legend's viewport query — fired by the
  camera- and map-idle listeners — landed in the middle of that. There is no
  query left to land there: what the idle listeners read now is four
  projections of the view's own edge, which touches nothing the renderer owns.
- **A view wider than half the world is measured the way it is drawn.** At the
  zoom that frames a year of travel a phone shows well over 180° of longitude,
  and asking for the short way round between the two ends of a view that wide
  names the way round the viewer is *not* looking — the window comes back
  inside out and everything on screen reads as off it. The view's edge is
  walked in quarters and the steps added up, which also folds a view
  straddling the antimeridian into one arc rather than two halves of the world.
- **"All days" lists the days the track draws a line for.** It used to list
  every date with a sample in it, including days left with nothing to draw, so
  the two counts were never quite comparable and the row could not tell when
  the whole track was in view. Both ends are one scan now, under one set of
  rules: a pair of samples further apart than the gap distance is a flight,
  drawn dashed and not as track, and an edge belongs to its starting sample's
  local day.
- A day whose line crosses the screen with both of its samples off it still
  counts — that was the one thing the rendered-feature query got right, and it
  is why the new scan clips edges against the window rather than testing where
  their endpoints fall.

### v1.8.0 (still versionCode 15) — the card stops eating the map

Follow-up to the day count above, from the same session.

- **The `Color by day` card no longer costs you days.** Its height came off
  the top of the counted window, so a route it happened to cross stopped being
  "in view" - while map showed above it and down both its sides, and the whole
  route was plainly on screen. The count describes the camera again; chrome
  painted over the map does not move the edge of the view. On the reported
  export that is the difference between 365 days and 377.
- **The card is translucent**, so a route crossing behind it stays visible
  rather than being taken on trust. Not so translucent that its two labels
  have to compete with the basemap.
- **Framing still keeps clear of it.** "Show the whole track" and focusing one
  day both still leave the card's strip empty, because where the track is
  *put* is a different question from what is on screen.
- **The inline preview map is half again as tall**, 280 dp to 420 dp. At the
  old size the card was most of the tile and the map was the strip underneath
  it; this is the old 280 dp of map with room for the card above it.

### v1.8.0 (still versionCode 15) — the card, tidied

Two things the translucency and the new height turned up.

- **The grey frame inside the card is gone.** Android draws an elevation
  shadow as a blurred silhouette behind the view, and an opaque view hides it;
  at 0.82 this one did not. The inward half of that blur read as a grey frame
  about the width of the card's own inset, with the card's middle sitting
  inside it like a second, lighter panel. The card now separates itself from
  the map with a hairline border, which has nothing to show through.
- **The day summary is two lines, always.** One line holding both the count
  and the span is a line as long as a translated plural plus a localized date
  range: it fits in English over one year, and wraps in German, in Russian, or
  as soon as the range crosses into a second year — so the card changed height
  as the user panned. Count on the first line, span on the second, each capped
  at its own line. Same height in all 17 locales, for every date, and when the
  camera is off the track and there is no span to show.

### Store changelog — draft for the next upload

Four bullets, because that is what fits above the fold. Written from the whole
span 1.6.2 → now; re-cut before upload if anything lands after this. **Cut in
all 18 locales to `fastlane/metadata/android/<locale>/changelogs/15.txt` and to
`F:\Timeline\Whats_New_v1.7.0_tagged.txt`.** Play allows 500 characters per
language; the longest translation here is 479.

The text is unchanged from the 14 cut — copied across, and `14.txt` kept beside
it as the record of what the testing-track build carried. Neither the ballot
re-cut nor the update check gets a bullet: one changes the feedback dialog,
the other only ever appears to someone who has *already* updated, and the
four bullets are already full of the things a user decides to update for.

```
• Export your places, not just your track: where you were, how often and for
  how long — in all four formats.
• Filter by time of day: only your mornings, or only your nights, on the local
  clock.
• A quieter map, where the track builds up along the streets you walk most.
• CSV local times are now correct abroad, and exports can go straight to
  another app. Still no uploads, no accounts, no ads, no tracking.
```

This is the second cut. The first was written when the span ended at 1.7.0, and
it spent three of its four bullets on the 1.6.3 correctness work; the map
rewrite, the time-of-day window and the places map all landed afterwards. The
new map earns a bullet because it is the first thing a returning user sees, and
the three 1.6.3 fixes are folded into the last one.

Not in the bullets, deliberately: the container-place default, the Maps link
fix, the export-step wording, the scale bar, the chevrons and the place
selection. They matter to someone using the app, not to someone deciding
whether to update.

### v1.6.3 (versionCode 13) — correctness

- **Local time in CSV was the exporting phone's, not the point's.** Every path
  point carried the home UTC offset, so a week in Tokyo exported with Stockholm
  timestamps — hours wrong for every trip abroad, and silently so, since the
  UTC column was right. Points now carry the offset that was in effect where
  they were recorded.
- **Place names and addresses from the older Takeout layout** are parsed
  instead of dropped.
- **Send to app.** Export no longer has to become a file first: the result can
  go straight to mail, a chat or a map through the share sheet. This was the
  third candidate on the 1.6.1 feedback ballot.
- `TimelineParserTimezoneTest` (144 lines) pins the offset behaviour.

### v1.7.0 (versionCode 14) — Places

The app could only ever export *the line between* the places someone went. A
Timeline file also records the places themselves — where, how long, how often —
and none of it could leave the app.

- **A Track / Places switch in step 3**, and step 4 writes whichever is
  selected. `ExportContent` became a sealed type so an exporter takes "what to
  write" rather than `List<PathPoint>`; one new format is still one new file.
- **All four formats write places**: GPX waypoints, KML `Point` placemarks,
  GeoJSON `Point` features, and a places-shaped CSV.
- **Places are listed by visit count**, with duration, last visit, address and
  a link that opens the place in Google Maps.
- **Local names.** Tap a place to name it; the name is yours, stored on device,
  and written into the export. Google's `INFERRED_HOME` / `INFERRED_WORK` show
  as *Home* / *Work* in the user's language, with the raw `semanticType` kept
  in the file as the machine-readable column.
- **Containers off by default.** Google also records the mall or airport a
  place sits inside, which duplicates a stay the user already has; the toggle
  is in step 3.
- **Two UTC offsets per place**, one for the first visit and one for the last:
  an aggregate spans up to a year and can straddle a DST change, and picking a
  winner would reintroduce the bug 1.6.3 just fixed.
- 26 new strings across all 18 locales. `PlaceEntryTest` and `PlacesExportTest`
  (~410 lines) cover aggregation and all four writers.

### Also in versionCode 14 — the map, time of day, places on the map

Written after the commit tagged 1.7.0, shipped in the same build.

- **The Google Maps link was undocumented and broken.** It used
  `maps/place/?q=place_id:…`, which Maps treats as literal search text: the app
  opened with `place_id:ChIJ97_m…` typed into the search box and no result.
  Now the documented Maps URLs form, coordinates plus `query_place_id`, so a
  stale ID degrades to a pin at the right spot instead of an empty search.
  Coordinates are formatted through `Locale.ROOT` with fixed decimals —
  `Double.toString` emits `1.0E-5` near the equator, which is a dead link.
- **Step 4 now says what it will export.** Flipping the Track / Places switch
  in step 3 changed nothing visible in step 4 — four identically-labelled
  format buttons either way — so the choice only surfaced afterwards, in the
  filename. Step 4's *heading* now reads "Export track" / "Export places", and
  a plaque above the format buttons carries the matching icon, the kind and
  the count ("Track — 12,481 points"). The switch is still the only control:
  the plaque is read-only, so the two cards cannot disagree about which one is
  live. The plaque is neutral `surfaceVariant` with a primary-tinted icon and
  not `secondaryContainer`, because `FilledTonalButton` — the GPX / KML row
  directly below — is `secondaryContainer`, and a tonal plaque there reads as
  one more control. Three new strings across 18 locales; the now-unused
  `step_export` was removed from all of them.
- **The map is our own basemap now, and the track is drawn as accumulation.**
  The preview used OpenFreeMap's *Liberty* style — a full-colour general
  purpose map — under a flat 4 px orange line. On a week of walking that was
  legible; on a year of history it was an orange blanket, and the ground
  underneath competed with it for every pixel. Two things changed:

  - **Two bundled styles, light and dark, in `assets/`.** Same OpenMapTiles
    vector source as before, but a near-monochrome ground whose only job is to
    stay out of the track's way: 18 layers instead of Liberty's ~150. They
    follow the system theme, and switching theme reloads the style locally
    rather than downloading one. The camera is fitted to the data, not to the
    style, so a theme flip no longer yanks a camera the user has panned.
  - **The track accumulates.** A hairline drawn at low alpha over a blurred
    wash, so a street walked once stays faint and one walked two hundred times
    builds to a solid corridor. Alpha accumulation only works if a single pass
    is faint enough to leave headroom — which is the wrong answer for a single
    afternoon's walk, with nothing to accumulate against. So the base weight
    is interpolated on the log of the sample count: a small track is drawn as
    a confident route line, a year of history as a heat map. Opacity is also a
    U over zoom: held high at continent scale, where a long-haul flight is one
    pass and heat-map alpha would erase the trip; dipping through zoom 10–13,
    where a year of local movement overlaps itself and the gradient does the
    work; solid again past 16, where the map is one street wide.
  - Track and gap layers are inserted *below* the basemap's first symbol
    layer, so place names stay readable through a dense track. The endpoint
    dots stay on top of everything — they are the one thing the user hunts for
    by eye — and each gained a soft halo so it reads against a busy track.

- **Three things the basemap swap left owing.** Replacing Liberty with our own
  style took its attribution with it, and a near-monochrome ground turns out to
  need two pieces of chrome the busy one did not.

  - **Attribution.** The style now states it on the source, in both themes. The
    tiles are OpenStreetMap data under ODbL: crediting it is a licence term,
    not a courtesy, and Liberty had been carrying it for us.
  - **A scale bar**, top-left. Few labels and no colour coding means no cue for
    how big anything is — the same knot of track could be a campus or a city.
    The bar is a round 1, 2 or 5 times a power of ten drawn at whatever width
    that comes to, rather than a fixed width labelled "137 m". The scale is
    *measured*, by projecting two screen points back out of the view, not
    derived from the zoom level: that relationship depends on screen density
    and on renderer convention, and two projected points cannot be wrong about
    it. The fullscreen map's close button moves to the top-right to make room.
  - **A way back to the track.** Panning away left no route back. A small
    button appears once the camera is two fifths of a screen, or half a zoom
    level, from where the track would be fitted — and only then, so an
    untouched map has none. It compares against where the camera *would* sit
    if the track were fitted now, not where it was left, so zooming back out by
    hand dismisses it rather than leaving a button offering to undo something
    already undone.
- **Direction chevrons, in route mode only.** The track had a start dot and an
  end dot and nothing in between saying which way it ran. Chevrons now repeat
  along the line — placed and rotated by MapLibre, which is correct because the
  runs are built in time order — under two conditions: at most 2000 samples,
  and zoom 13 or above. Both are the cases where the question exists at all. A
  year of history is a street walked both ways two hundred times, which has no
  direction to show, and a country-wide view has no room to show one. 2000
  samples is about three weeks at the reference export's 86 points per day.

- **Filter by time of day, not just by date.** Step 2 could answer *which
  days*; it could not answer *which hours of them*. One more chip in the same
  row now opens a window — 07:00 to 19:00, or 22:00 to 06:00 — that applies to
  every day in the range.

  - **On the data's clock, not the phone's.** A point is tested against the
    time that was on the wall where it was recorded, using the offset the
    parser back-fills. This is the 1.6.3 fix paying for itself one layer up:
    someone asking for their mornings while travelling gets the mornings they
    lived, not 06:00–12:00 Stockholm time in Tashkent. Formats that state no
    offset fall back to the phone's zone, applied at the point's own date so a
    year-long export is not an hour out for half of it.
  - **Windows wrap through midnight.** 22:00–06:00 is not an interval on the
    number line, and it is the one people ask for by name. That is why the
    window is a type with three predicates rather than two integers compared
    inline, and why the dialog has two ends rather than one range slider — a
    slider cannot express a window whose start is after its end.
  - **Places are kept on overlap, not on start.** A visit is a span: someone at
    their desk 09:00–18:00 was there at lunchtime, and a 12:00–13:00 window has
    to find them. The date range tests a visit's start, which is right for
    "which days"; testing the start here would answer "where did I arrive at
    lunchtime", a different and much rarer question. The movement breakdown
    follows the same rule, with the caveat that the kilometres stay the whole
    trip's — Google states one figure per activity and there is no honest way
    to apportion it across a window.
  - **The window is in the filename and in the track name**, for the same
    reason the content kind is: exporting your mornings and then your evenings
    out of one date range would otherwise write the same filename twice, and
    the second file would silently replace the first.
  - It is not a restatement of *only when moving*: 14.6% of the reference
    export's points fall between midnight and 06:00, but of the points recorded
    while actually moving, 48% land in 10:00–16:00 against 4.6% overnight. The
    two cut along different lines.
  - 11 new strings across all 18 locales. `TimeOfDayWindowTest` and
    `TimeOfDayFilterTest` (~290 lines) cover the wrap, the overlap and whose
    clock is read; the real-file test adds a partition check — a window and its
    complement must cover every point exactly once, which a magic number cannot
    prove and a filter dropping points on the floor cannot fake.

- **Places on the map.** Switching step 3 to Places replaced the map with a
  list — 262 rows of names, counts and coordinates, and no answer to *where is
  that*. The map now follows the switch: places are drawn as dots sized by how
  often each was visited, above the list rather than instead of it, with the
  same expand button the track has. Size rather than colour, because "which of
  these is my life and which is a one-off" is the first question a places map
  is asked and area answers it without a legend; the scale flattens fast, since
  one visit against 335 is a factor of 335 and a dot 335 times the area would
  be the size of a city. No labels on the dots — the list underneath already
  names every row.

  The map composable became `TimelineMap` over a `MapSubject`, because the two
  pictures share everything except what they draw: the MapLibre lifecycle and
  its teardown, the theme-driven basemap, the scale bar, the way back to the
  data. Framing the camera walks the coordinates rather than collecting them,
  so a year of history no longer allocates 30,000 pairs to find four numbers.

- **The places map and the places list are now one thing.** Putting the map
  above the list answered *where is that* in general and nothing in
  particular: the dots could not be touched, the rows could not be found on
  the map, and the two halves of step 3 described the same data without ever
  referring to each other. They now share one selection.

  - **A tap on a dot selects it**, and a tap on a row selects it. The selected
    place gets a ring on the map — outside the halo, so it reads as *this one*
    even where a dozen places overlap — and its row is tinted in the list.
    Ring rather than a recoloured dot: the dot's size already carries the
    visit count, and a second meaning on the same mark would cost the first.
  - **One tap selects, a second names.** A row used to open the naming dialog
    immediately, which meant a modal covering the map every time someone
    wanted to know where a place was. Now the first tap answers *where*, and
    the second — on the row or on the dot — opens the same dialog as before.
    The hint above the list says so, reworded in all 18 locales.
  - **Selecting from the list moves the camera; selecting on the map does
    not.** A ring around a dot the user just touched is the whole answer, and
    moving the view as well would throw away the frame they picked it from.
    The map tracks which selection it emitted itself and skips the camera for
    that one. The camera eases to the place at the current zoom clamped to
    12–16: from a continent-wide view *where is that* means zooming in, and
    from a street-level one it does not mean zooming back out.
  - **The selected place is labelled on the map** — the one place name on it,
    for the reason the dots carry none. Needs glyphs, which the basemaps fetch
    from the tile host; with no network the ring still draws and only the text
    is missing, which is the right way round.
  - **A place selected out in the tail is pinned to the front of the inline
    list**, which shows eight rows. Without that, tapping a one-visit dot drew
    a ring and changed nothing in the list — exactly the disconnection this
    was built to fix. The fullscreen list scrolls to the selection instead.
  - Under the hood: the places source carries `PlaceEntry.key` per feature, so
    a hit test resolves back to a row, and a tap queries an 18 dp box rather
    than a point — the one-visit dots are 3.5 px of radius and a fingertip is
    9 mm. Where several dots are hit, the nearest centre wins: a small dot
    sitting inside a large one is the one the user aimed at, because it is the
    one they cannot identify from the list. The selection is its own source
    and its own effect, so selecting does not rebuild 262 features and does
    not count as new data and re-frame the camera. Selection is held as a key,
    not an entry, so re-filtering either keeps it or drops it rather than
    leaving a ring on a place that is no longer in the data. No new strings —
    `place_unnamed_hint` was rewritten in place across all 18 locales.


### v1.7.0 (versionCode 15) — the feedback ballot, re-cut

The dialog is the app's only source of signal, and it had been asking for two
things it can now do. `feedback_option_places` (7 votes, the most-requested item
in the log) and `feedback_option_share` (1) are gone; `feedback_option_stats`
loses its second half — the places list is ordered by visit count, so what is
still missing is "Monthly and yearly totals", and that is all the line asks for
now. FB-012's time-of-day request shipped without ever reaching the ballot.

Four lines take their place, in a deliberate order rather than by vote count:

- **Each trip as a separate track inside one file.** `file_per_trip` has been
  collecting two different wishes. This one is native GPX (`<trk>` repeats) and
  needs no ZIP; if the votes prefer it, 1.8.0 costs a fraction of what the
  file-per-trip route does.
- **Real names for places that show only coordinates.** Online name resolution
  has been proposed and declined twice on the same argument — the top eight
  places are 55% of visits and two of them name themselves. This puts the
  question to the people holding the 262 coordinate rows instead of settling it
  from the reference export a third time.
- **Remember my filters and format for the next export.** Nobody asked;
  `SharedPreferences` holds only the locale and place labels, so every export
  re-picks the range, the movement groups and the format. Cheap enough that one
  vote would justify it.
- **Show which account the export came from.** FB-014, three words of Spanish
  with no verb. A ballot line is the cheapest way to find out whether that
  reading was right; if it stays at zero it gets dropped rather than guessed at
  again.

Eleven options is two more than 1.6.1 shipped, and the dialog scrolls. Also
fixed here: `FeedbackDialog.kt` held its own hard-coded English per option, and
three of them paraphrased the label the ballot displays — so a report could not
be matched to a row of `FEEDBACK.md` by string equality. The outgoing text is
now character-for-character the `values/strings.xml` label, from 15 on.

Twenty-two strings across all 18 locales: four added, one shortened, two
removed.

### v1.7.1 (still versionCode 15) — an optional update check

Nothing before this told a user a newer build existed short of noticing it on
the Play listing. `AppUpdateViewModel` wraps Play Core's *flexible* in-app
update flow: on launch and on every resume it asks Play whether a newer build
exists, and if one does, a dismissible Snackbar offers to fetch it in the
background — the app stays usable while it downloads. A second Snackbar, once
the download lands, offers to restart into it. Either can be waved off with no
consequence; dismissing the first stops it asking again until the app is
fully restarted, which is what "not obligatory" meant here.

- `AppUpdateManager` is created once per `AppUpdateViewModel`, an
  `AndroidViewModel` so it survives rotation the same way `TipJarViewModel`
  does. The Activity Result launcher the flow itself needs — Play's update
  screen returns through `startIntentSenderForResult` — lives in
  `TimelineExporterApp`, the same place the file-picker and every exporter's
  launcher already live, since only a composable in the Activity's own tree
  can register one.
- An `InstallStateUpdatedListener` catches the download finishing while the
  app is backgrounded; the resume check alone would miss that, since nothing
  re-runs it until the user comes back.
- The dependency is `app-update-ktx` rather than the plain `app-update`
  artifact for the Kotlin-friendlier surface (it still wraps the same
  Java Task/listener API used here, not the Flow-based one — that one models
  more states than a single dismissible Snackbar needs).
- Four new strings across all 18 locales: the two Snackbar messages and their
  two action labels. No parser, exporter or map changes.

Only testable end to end from a build actually installed *through* Play — a
`connectedAndroidTest` or `adb install` sees no update, since the flow talks
to the Play Store app on device, not this codebase. Internal-track testing is
the only way to see the Snackbar fire for real.

### Store art for this upload

Eight screenshots instead of six, and the emulator's own chrome is no longer in
any of them.

- **Two new frames.** `08_places` — step 3 on the Places side, the map of
  visited places over the list that names them — goes *second*, inside the
  three Play shows without scrolling, because it is the one thing in this span
  a 1.6.2 user does not already have. `09_time_of_day` — the window dialog with
  a preset chosen — goes fourth. `ScreenshotTest` captures both; the Track /
  Places switch is put back before the fullscreen map, which would otherwise
  open on the places dots.
- **The status bar is drawn by the renderer now.** The top and bottom system
  bars are cropped off every capture and a bar of our own — 9:41, Wi-Fi,
  signal, full battery — is drawn in place of the top one, its background
  sampled from the app's own pixels just below it so it works on a white screen
  and on the map alike. This is what removes the emulator's clock, its SD-card
  and debug notification icons, and the Pixel_7 image's collapsed left inset,
  which had the clock sitting hard against the edge while the battery floated
  28 dp off the other one. The bottom crop takes the gesture pill and, on the
  tablets, a taskbar full of Google's own app icons that had been sitting in
  the middle of the store art.
- Tablets also got their own device geometry: a phone's corner radius applied
  to a 2000 px-wide tablet is a 150 px arc, and it was swallowing the whole
  status bar.

### Pre-release checklist for this span

Places (1.7.0):

- [ ] A real takeout: the places list is plausible, ordered by visit count
- [ ] Each of GPX / KML / GeoJSON / CSV, opened in a reader that understands it
- [ ] Name a place → the name survives rotation, app restart, and appears in
      the export
- [ ] Containers on/off visibly changes the list
- [ ] A file with no places at all, and a date range that matches none
- [ ] The Maps link opens the right place, and a place with no `placeId` opens
      a pin rather than an empty search

Timezones (1.6.3):

- [ ] A trip abroad: the CSV local-time column matches the local clock there,
      not the phone's
- [ ] An export spanning a DST change
- [ ] An older `Records.json` layout keeps its place names

Map preview:

- [ ] A year of history and a single day's walk: both legible, neither a blanket
- [ ] Light and dark, and a theme flip mid-session — the style follows and the
      camera stays where the user left it
- [ ] Zoom from continent to street: the track never disappears at either end
- [ ] The attribution button credits OpenStreetMap, once, in both themes
- [ ] Airplane mode: no basemap tiles, but the track still draws
- [ ] The scale bar reads plausibly against a known distance, and updates on
      zoom; it does not collide with the fullscreen close button
- [ ] Pan away and back: the return button appears, works, and goes away again
      when the track is framed by hand
- [ ] A day's track shows chevrons pointing the way it was walked; a year of
      history shows none, at any zoom
- [ ] Flipping Track / Places redraws the map and re-frames it; flipping back
      does not leave places dots on the track
- [ ] Home is visibly the biggest dot; a place visited once is visibly small
- [ ] Expand works from the Places side, and the map survives closing it

Places selection (map ↔ list):

- [ ] Tap a dot: it rings, its row tints, and the camera does *not* move
- [ ] Tap a row: the camera eases to that dot and rings it
- [ ] Tap the same row or dot again: the naming dialog opens, on the right place
- [ ] Tap empty map: the selection clears
- [ ] Tap a one-visit dot far down the list: it appears pinned above the eight
      inline rows
- [ ] Open "show all" with something selected: the list scrolls to it
- [ ] The selected place's name is drawn under its dot, and follows a rename
      without the camera flying a second time
- [ ] Airplane mode: the ring still draws (the label may not)
- [ ] Two dots on top of each other at low zoom: the smaller one is selectable
- [ ] Select a place, then change the date range so it drops out of the data:
      no ring, no crash
- [ ] Select a place, flip to Track and back: no stray ring on the track
- [ ] Expand to fullscreen with a selection, and back: the selection survives
- [ ] Rotate with a selection: it survives

Store art:

- [ ] `screens_phone`, `screens_tablet7`, `screens_tablet10` on their AVDs, then
      the contact sheets in `tools/store_shots/preview/`
- [ ] All eight frames present in all 18 locales; no "no raw capture" warnings
- [ ] No emulator clock, notification icons or taskbar anywhere in the art
- [ ] `08_places` shows the dots *and* the first rows of the list
- [ ] The headline does not collide with the device in any language (the
      renderer warns; ar, de-DE, fr-FR and hi-IN are the tight ones)

Time of day:

- [ ] A window over a trip abroad keeps the local mornings, not the mornings on
      the phone's clock
- [ ] 22:00–06:00 keeps both ends of the night
- [ ] Setting From and To to the same time clears the filter and the chip
- [ ] The places list narrows with the window, and the count in step 2 matches
      the length of the list
- [ ] Two exports from one date range, mornings and evenings, do not overwrite
      each other
- [ ] German (long strings) and Arabic (RTL) in the dialog

Feedback ballot:

- [ ] Eleven options, no duplicates, and the dialog scrolls to the send buttons
- [ ] German (long strings) and Arabic (RTL) do not clip a label
- [ ] Tick one of the new four, send by mail: the draft carries the same English
      sentence the ballot shows

Update check (needs an internal-track install, not `adb install`):

- [ ] Install an older internal-track build, then publish this one: the
      "update available" Snackbar appears within a resume or two
- [ ] Tapping "Update" starts a background download and the app stays usable
- [ ] Backgrounding the app mid-download and returning still shows the
      "restart" Snackbar once it finishes
- [ ] Dismissing the "update available" Snackbar does not reopen it before the
      app is fully restarted
- [ ] A build already on the latest version shows neither Snackbar

Export step wording:

- [ ] Flip Track / Places and scroll to step 4: heading and plaque both follow
- [ ] The plaque does not look tappable next to the format buttons, in light
      and in dark theme
- [ ] German (long strings) and Arabic (RTL) do not clip the plaque

Always:

- [x] `./gradlew :app:test` — 143 tests, 0 failures (2026-09-05, after fixing a
      missing `placeEntries` import in `TimeOfDayFilterTest.kt` left over from
      the commit that added it — the suite would not compile until then)
- [x] `./gradlew bundleRelease` — R8 and `lintVital` green (2026-09-05)
- [x] Bump `versionCode` to 15 (`versionName` stays 1.7.0), copy the changelog
      into all 18 `changelogs/15.txt`, and bump `version_code:` in the Fastfile
      — done 2026-09-05; re-cut the text if anything lands after this
- [x] Bump `versionName` to 1.7.1 for the update check (`versionCode` stays 15
      — set the real code just before the upload build) — done 2026-09-05
- [ ] Move this section into *Released* once 15 is up

## v1.6.2 (versionCode 12) — published 27 August 2026

The crash hotfix that is currently live on Play, and the baseline every note in
*Unreleased* is measured against.

- Every `LocalDate.ofInstant` call is API 34, and threw `NoSuchMethodError` on
  Android 10–13 — killing the quick day chips, the date picker and the export
  filename on the majority of installs. Core library desugaring plus a lint
  gate now stop that class of bug at build time.
- Hardened the MapLibre teardown that was aborting in `~MapRenderer`.

Store text: `fastlane/metadata/android/<locale>/changelogs/12.txt`.

## v1.6.1 (versionCode 11) — movement filtering + wording

v1.6.0 was built and signed but never uploaded, so this file covers everything
since **v1.5.1 (versionCode 9)** — the movement filtering that made up 1.6.0,
plus the wording pass that followed the first run on a real device.

The export stopped being all-or-nothing. Until now the only question the app
asked was *when* — pick a date range, get every point Google ever recorded in
it. This release adds *what*: which kinds of movement to keep, and whether to
keep the points recorded while you were not moving at all.

### Highlights
A year of real Timeline data is mostly standing still. **71% of its points were
recorded during a visit** rather than a trip, and **29.6% repeat the previous
coordinate verbatim** — the phone reporting the same spot again while the owner
sat in an office. That is what made exports feel padded, and neither could be
filtered out before.

The same screen that now does the filtering also answers the question the first
feedback round kept asking: *how far did I actually travel, and by what?* The
breakdown and the filter are the same rows — read the kilometres, tick the
group, the export follows.

### Added
- **Movement breakdown in step 2.** One collapsed line under the date chips,
  e.g. *"All movement · 313 places"*. Someone who only ever picked a date range
  sees the step they already know plus a single extra line.
- **Per-type trips and distance.** Expanded, the section lists each movement
  group — Walking, Cycling, Driving, Public transport, Flights, Other — with
  its trip count and total distance. Several of Google's activity types fold
  into each group (running counts as walking, trains as transit).
- **Each group is a checkbox**, so "just the cycling" or "everything except
  flights" is one tap. The numbers move with the date range, so the section
  doubles as the statistics screen without being a second screen.
- **Only while moving.** Drops points recorded during a visit rather than a
  trip.
- **Skip repeated points.** Collapses runs of identical coordinates to one.
- **The parser now keeps visit and activity segments.** `visit` and `activity`
  used to be two `skipChildren()` calls, which is why four of the five things
  the first feedback report asked for had no data behind them. A `Segment`
  model now carries kind, time span, activity type, Google's `distanceMeters`,
  confidence and the matched place.

### Changed
- Nothing changes for an existing user until they touch something: both
  switches default to off and no group is preselected, so the same file and
  date range exports byte-identically to v1.5.1.
- Distances come from **Google's own `distanceMeters`**, shown as whole
  kilometres past 10 km and one decimal below. Never metres — these are
  estimates, and metre precision would imply an accuracy they do not have.
- Unticking every group leaves an **empty** selection rather than silently
  meaning "everything": it genuinely matches no points, the count under the
  chips says so, and export is already disabled at zero. Ticking every group
  collapses back to no constraint, so a cleared filter stops counting as one.
- The section **hides itself** when a file has no activity segments — the case
  for older Takeout layouts. An empty expander is worse than none.
- 15 new strings, translated across all 17 non-default locales; names and
  format placeholders verified against the default file.

### Wording (the 1.6.1 part)
Text only — no filter, exporter or parser behaviour differs from the 1.6.0
build.

- **Step 2 is now "Filter data"**, not "Filter by date". The step gained
  movement groups and two switches, so the old title described a third of what
  the card does; the new one matches the shape of its neighbours ("Choose
  file", "Preview", "Export") and survives the next filter. The custom-range
  dialog keeps **"Filter by date"** — that one really is only dates.
- **The feedback ballot was re-cut** now that there is a shipped feature to
  measure it against. *"Filter by movement type (cycling / walking / driving
  only)"* is **gone**: it is the headline above, and leaving it on the ballot
  would have collected votes for something already built.
- *"Statistics screen (km by transport type, top places)"* became **"Monthly
  and yearly totals, most-visited places"** — the per-type kilometres arrived
  with the breakdown, so the item now asks only for the half that did not.
- **Three new candidates**, each an axis the app has no answer for today:
  - *Keep only points inside an area on the map* — filtering by time and by
    movement type now exists; filtering by place does not.
  - *Send the export straight to another app instead of saving a file* — export
    goes through `CreateDocument` only, so "export, then hand it to OsmAnd" is
    two manual steps.
  - *A list of trips with start, end, time and distance* — the breakdown counts
    trips without ever showing one.
- The ballot is nine options, ordered filters → export → analysis → the rest.
  Their **outgoing English text** — what lands in the GitHub issue or the
  email, whatever the user's locale — was updated along with the labels, so
  votes stay de-duplicable.
- Five string keys touched in each of the **18** locale files: one renamed step
  title, one reworded option, three added, one deleted.

### Under the hood
- `PathPoint` instances are shared between the flat point list and the new
  segment list, so keeping segments costs one reference per point rather than a
  second copy of the track.
- Movement type and GPS track live in **different** segments: a path segment
  has points and no label, an activity segment has a label, a distance and its
  endpoints but no track. "Only the cycling" is therefore a time join, not a
  field lookup.
- `TimelineFilter` grew `movements`, `movingOnly` and `dropRepeatedPoints`, and
  `applyFilter` gained an overload taking the segment list. The old
  two-argument form behaves exactly as before.
- Movement labels resolve once into a `Map` via `movementLabels()`.
  `stringResource` cannot be called from inside a `joinToString` transform —
  that lambda is a real (non-inline) function, so its body is not composable —
  and `associateWith` / `map` would hit the same wall.
- Measured against `Timeline_2.json` (a year, 5432 segments) *before* the code
  was written, and the tests pin the figures: per-type distance from Google's
  `distanceMeters` totals 14 501.4 km.

### Fixed (test suite, not the app)
- `GeoJsonExporterTest` had **never compiled**: it parses the exporter's output
  with `kotlinx.serialization.json`, which was not a dependency in any scope
  (the app reads JSON with Jackson). Added as `testImplementation` only — it
  does not enter the AAB.
- `TimelineParserStreamingTest.streams a large input and sorts correctly` built
  its 5 000 timestamps as `minute / 60` hours, which rolls past hour 23 at
  i = 1440 and emits `"T83:20:00Z"`. The parser rightly drops those, so the
  test was measuring the drop, not the stream — it expected 5 000 points and
  got 1 440. Timestamps are now built with `Instant` arithmetic. This failure
  predates the v1.6.0 work; verified against `bc19edc`.

With both fixed, `./gradlew :app:test` is green: **89 tests, 0 failures**.

### No changes to
- Billing, the paid-export flow, or anything on the purchase path.
- The exporters themselves — GPX / KML / GeoJSON / CSV write the same bytes for
  the same points.
- The feedback dialog's mechanics — only its list of options changed. The app
  still sends nothing itself; both buttons hand a draft to the browser or the
  mail client, where the user can edit or abandon it.
- `minSdk` (29) and `targetSdk` (36).

### Expectations
`bundleRelease` is green with R8 and `lintVital` passing, and the unit suite is
green. The 1.6.0 build was also **run on a device**, where the new step behaved
as intended in ordinary use — the one thing the 1.6.0 notes could not claim.
That was a walkthrough, not the list below: the edge cases (empty selection,
older files with no activity segments, RTL, 100 MB+ inputs) have still not been
exercised, and the 1.6.1 wording has been built but not seen running.

### Pre-release checklist
- [x] `./gradlew bundleRelease` (R8 + lintVital pass; AAB signed)
- [x] `./gradlew :app:test` — 89 tests, 0 failures
- [x] Load a **real phone takeout** and confirm the breakdown appears, with
      plausible trip counts and distances
- [ ] Load an older `Records.json` / a file with no activity segments — the
      section must be absent, not empty
- [ ] Totals move when the date range changes
- [ ] Tick one group → export → the track contains only that kind of movement
- [ ] Untick every group → the count reads zero and export is disabled
- [ ] Tick every group → behaves as no filter at all
- [ ] **Only while moving** on a file with long stays: point count drops
      sharply, the remaining track still starts and ends where the trips do
- [ ] **Skip repeated points** — no duplicated consecutive coordinates in the
      output, and the timestamps stay ascending
- [ ] Both switches on, at once, with a group selected
- [ ] Export with everything off → byte-identical to a v1.5.1 export of the
      same file and range (the compatibility claim above)
- [ ] Rotate the screen with the section expanded and boxes ticked — state must
      survive
- [ ] Very large file (100 MB+): the breakdown must not stall the UI or
      re-introduce the OOM fixed in v1.3.0
- [ ] Check the section in a right-to-left locale (Arabic) and a long-string
      locale (German) for clipping, and confirm the km figures use the locale's
      decimal separator
- [ ] Dark theme
- [ ] Step 2 reads "Filter data" in a couple of locales, and the feedback
      dialog shows nine options with no movement-type item — the sent draft
      lists the English text of whatever was ticked
