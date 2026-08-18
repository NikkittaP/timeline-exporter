# Release Notes — v1.5.1 (versionCode 9)

> versionCode 8 / v1.5.0 never left internal testing: its **Send an email**
> button opened a blank draft. Everything below still describes what is new
> compared to v1.4.0 — that is what a Play user will see on first install —
> with the fix recorded under **Fixed**.

## Fixed
- **The email draft arrived empty** (recipient filled, subject and body blank).
  `sendFeedbackEmail()` passed the text only as `EXTRA_SUBJECT` / `EXTRA_TEXT`
  extras alongside a bare `mailto:` URI. Gmail — and several OEM mail apps —
  compose the draft from the URI alone and discard the extras, which is why the
  recipient survived (it *is* the URI) and nothing else did. Subject and body
  now travel in the URI query string, percent-encoded with `Uri.encode`
  (`%20` for spaces, not `URLEncoder`'s `+`, and `%0A` for the newlines that
  keep the diagnostics block readable). The extras are kept as a fallback for
  clients that behave the other way round; both carry identical text, so the
  draft is the same whichever one wins.
  - `mailto:` is an **opaque** URI, so the `buildUpon().appendQueryParameter()`
    pattern used for the GitHub URL does not apply — the query is assembled by
    hand.
  - `EXTRA_EMAIL` was dropped. Under `ACTION_SENDTO` the recipient is defined
    by the URI, and a client that reads both puts the address in `To:` twice.
- GitHub was unaffected and needed no change: `ACTION_VIEW` on an `https` URL
  carries title and body as ordinary query parameters, which the browser cannot
  drop.

## Highlights
The app finally has a way to talk back. Until now a user who needed something
had exactly one channel — a public Play Store review — so requests arrived by
accident, if at all. This release adds a feedback ballot reachable from two
places, and fixes the one export complaint that didn't need a survey to
discover: CSV timestamps were UTC-only.

## Added
- **Feedback dialog.** A checklist of seven concrete candidate features (all of
  them backed by data that already exists in `Timeline.json`, so nothing on the
  list is an empty promise) plus a free-text field. Sends through **GitHub
  Issues** or **email** — the user picks; neither channel is imposed.
- **Two entry points, nothing added to the idle main screen.**
  1. A tinted plaque under a successful export — the moment the user just got
     what they came for. It started as a muted one-line link and read as a
     caption, i.e. exactly what people skip, so it now carries a title, one
     line of reasoning and a real action. Weight comes from the container
     colour, not from a primary button: the user finished their task and is
     being asked a favour, so it must not compete with the export buttons.
     The container is `tertiaryContainer`. With dynamic colour on, primary and
     secondary are neighbouring shades of the same wallpaper hue, so a
     secondary plaque under blue export buttons read as one more control;
     Material You derives tertiary by rotating the hue, which is the only
     way to signal "this isn't app functionality" without hardcoding a colour
     that would then fight the user's theme.
     The screen also auto-scrolls to the bottom when an export succeeds: both
     the "Saved N points" confirmation and the prompt under it sit below the
     fold on a four-step screen, so without it the plaque is invisible to
     anyone who didn't scroll.
  2. A button at the bottom of the help dialog — permanently available, out of
     the way.
- **Email is offered before GitHub.** Filing an issue needs a GitHub account;
  anyone without one hits a signup wall after composing their request. Mail
  works for everybody, so it leads.
- **Diagnostics are shown, not smuggled.** App version, device, Android
  version, locale, detected file format and point count are pre-filled into an
  **editable** text field. The app itself transmits nothing: both buttons hand
  a draft to the browser or the mail client, where the user can still edit or
  abandon it. This is a deliberate constraint — "100% on-device, zero
  telemetry" would be worth nothing if a feedback button quietly attached data.
- **Ballot text travels in English.** The checkbox labels are translated for
  display, but the outgoing issue/email body uses fixed English strings, so a
  request filed in Japanese or Turkish still arrives readable and
  de-duplicable.

## Changed
- **CSV gained two columns: `time_local` and `utc_offset`.** Every Timeline
  segment carries the timezone that was in effect where the point was recorded,
  and the app was throwing it away — a user opening the CSV in Excel saw the
  UTC clock instead of the one they actually walked by. `time_local` is written
  as `YYYY-MM-DD HH:MM:SS` (the shape spreadsheets recognise without an import
  wizard); `utc_offset` keeps the row lossless as `+05:00`.
  - The columns are **appended after** `time_utc,latitude,longitude`, so any
    script that reads the first three columns by position keeps working.
  - Both cells are **left empty when the file doesn't record a timezone**
    (Records.json and other UTC-only formats). An empty cell is honest; a
    guessed one silently claims the user lives in Greenwich.
- `PathPoint` gained a nullable `tzOffsetMinutes`. The parser fills it from the
  ISO offset on the point's own timestamp, falling back to the segment's
  `startTimeTimezoneUtcOffsetMinutes`. `Z` timestamps and epoch numbers yield
  null on purpose: they state an instant in UTC and say nothing about the
  traveller's clock.
- `AndroidManifest.xml` declares `<intent>` queries for `https` and `mailto`.
  Without them, package-visibility filtering on API 30+ can make the two
  feedback intents fail even when a browser and a mail app are installed.

## No changes to
Parsing logic, filters, GPX/KML/GeoJSON output, map, billing. No new
permissions, no new dependencies, no analytics, no telemetry.

## Expectations
At ~59 MAU and a typical 3–8% response rate on this kind of prompt, this should
produce roughly **2–5 messages a month**. That is small in absolute terms and
still enough to tell "several people need this" apart from "I invented it" —
which is the whole point of shipping the channel before the features it asks
about. The frozen backlog in `docs/FEATURE_ROADMAP.md` stays frozen until the
replies arrive.

## Pre-release checklist
- [ ] `./gradlew testDebugUnitTest` — CSV exporter tests cover the new columns,
      offset formatting and the empty-cell case
- [ ] `./gradlew lintRelease` — confirms no missing translations across the 17
      localized `values-*` folders
- [ ] `./gradlew assembleRelease`
- [ ] Export a CSV from a real phone-takeout file and open it in Excel /
      LibreOffice: `time_local` must parse as a date, and must match
      `time_utc` shifted by `utc_offset`
- [ ] Export from a `Records.json` file: both new columns empty, no "1970" or
      UTC-as-local artifacts
- [ ] Tap the feedback line after an export → tick two boxes → **Open a GitHub
      issue**: the browser opens GitHub's new-issue form with the title and the
      body pre-filled, including the diagnostics block
- [ ] Same, but **Send an email**: the mail app opens with the address, subject
      **and body** filled in and nothing sent yet — this is the v1.5.1 fix, so
      check it in Gmail specifically, and in one more client if the device has
      one (the two read the intent differently)
- [ ] Email draft with a long free-text note and non-Latin characters (Cyrillic
      or Japanese): the body must arrive intact, with real line breaks rather
      than `%0A` or `+` showing through
- [ ] Edit the diagnostics field before sending — the change must reach the
      draft
- [ ] Device with no mail app configured: expect the toast, not a crash
- [ ] Rotate the screen with boxes ticked — the ballot must survive
- [ ] Export twice in a row with the same filename: the auto-scroll must fire
      both times (Success → Working → Success re-keys the effect)
- [ ] Export on a tall screen where nothing is below the fold — the scroll must
      be a no-op, not a jump
- [ ] Check the plaque in dark theme and against a few wallpapers: with dynamic
      colour on, `tertiaryContainer` is wallpaper-derived, so verify it still
      reads as a different kind of surface than the export buttons
- [ ] Check the dialog in a right-to-left locale (Arabic) and in a long-string
      locale (German) for clipping
