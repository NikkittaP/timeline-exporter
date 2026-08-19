# Release Notes — v1.6.1 (versionCode 11)

v1.6.0 was built and signed but never uploaded, so this file covers everything
since **v1.5.1 (versionCode 9)** — the movement filtering that made up 1.6.0,
plus the wording pass that followed the first run on a real device.

The export stopped being all-or-nothing. Until now the only question the app
asked was *when* — pick a date range, get every point Google ever recorded in
it. This release adds *what*: which kinds of movement to keep, and whether to
keep the points recorded while you were not moving at all.

## Highlights
A year of real Timeline data is mostly standing still. **71% of its points were
recorded during a visit** rather than a trip, and **29.6% repeat the previous
coordinate verbatim** — the phone reporting the same spot again while the owner
sat in an office. That is what made exports feel padded, and neither could be
filtered out before.

The same screen that now does the filtering also answers the question the first
feedback round kept asking: *how far did I actually travel, and by what?* The
breakdown and the filter are the same rows — read the kilometres, tick the
group, the export follows.

## Added
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

## Changed
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

## Wording (the 1.6.1 part)
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

## Under the hood
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

## Fixed (test suite, not the app)
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

## No changes to
- Billing, the paid-export flow, or anything on the purchase path.
- The exporters themselves — GPX / KML / GeoJSON / CSV write the same bytes for
  the same points.
- The feedback dialog's mechanics — only its list of options changed. The app
  still sends nothing itself; both buttons hand a draft to the browser or the
  mail client, where the user can edit or abandon it.
- `minSdk` (29) and `targetSdk` (36).

## Expectations
`bundleRelease` is green with R8 and `lintVital` passing, and the unit suite is
green. The 1.6.0 build was also **run on a device**, where the new step behaved
as intended in ordinary use — the one thing the 1.6.0 notes could not claim.
That was a walkthrough, not the list below: the edge cases (empty selection,
older files with no activity segments, RTL, 100 MB+ inputs) have still not been
exercised, and the 1.6.1 wording has been built but not seen running.

## Pre-release checklist
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
