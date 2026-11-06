# iOS port — design

Date: 2026-11-06. Status: approved in conversation, awaiting review of this document.

## Goal

Ship Timeline Exporter on the App Store for iPhone and iPad with the same
features as the Android app, as a paid app ($1.99) with optional tips. The
Google Play app stays free with tips and does not change for the user.

## Decisions

| Topic | Decision |
|---|---|
| Repository | One repository (`timeline-exporter`). A separate one would turn the shared core into a published library or a submodule and every parser fix into two PRs. The repository is public, so GitHub-hosted macOS runners cost nothing. |
| Shared code | A Kotlin Multiplatform module `shared` with the parser, filter, export and trip detection. |
| iOS UI | Native SwiftUI on top of `shared`. Compose Multiplatform was rejected: the UI would feel foreign on iOS, and maplibre-compose is young. |
| Scope of 1.0 | Everything the Android app does, except Play in-app update. Intermediate builds go to TestFlight only. |
| Devices | iPhone and iPad, iOS 17+ (`@Observable`). |
| Testing | No Mac. Everything is built in GitHub Actions; the owner tests on an iPhone/iPad through TestFlight. |
| Bundle ID | `com.nikitapetrovapps.timelineexporter` (team `87H7UW6D73`). It differs from the Android `applicationId` (`io.github.nikkittap.timelineexporter`), and that does not matter: the two are unrelated. It cannot change after the first upload. |
| Price | $1.99, all features available at once. Set by hand in App Store Connect; no script touches the price. |
| Tips | Consumable IAPs `tip_small` / `tip_medium` / `tip_large` at $0.99 / $2.99 / $4.99, only in Settings → About → Support the developer. No tip prompts on iOS. |

## 1. Shared core (`shared`, KMP)

Moves to `shared/commonMain`: the packages `parser`, `filter`, `export` and
`trips` (about 5,000 lines, no `android.*` imports). These UI helpers move when iOS
needs them (stages 4–5): they use `R` and locale-aware date formatting, which must
first be split from their pure geometry and grouping logic. The
Kotlin package stays `io.github.nikkittap.timelineexporter`.

JVM-only dependencies are replaced:

| Now | Replacement |
|---|---|
| `java.time` (Instant, ZoneId, LocalDate, OffsetDateTime, DateTimeFormatter) | `kotlinx-datetime` (system time zone database on both platforms) |
| Jackson streaming (`JsonParser`, only in `TimelineParser`) | Our own streaming JSON lexer over a `kotlinx-io` `Source`. Files are never loaded whole. UTF-16 and UTF-32 input (detected by BOM or by the zero bytes at the start, as Jackson did) is transcoded to UTF-8 a chunk at a time before lexing, so a file re-saved as "Unicode" still loads. |
| `InputStream`, `BigDecimal`, `Locale` | `kotlinx-io` `Source`/`Sink`; coordinate formatting without `BigDecimal`; explicit output formatting |

There is one parser for both platforms. Changes to the Timeline format must
reach Android and iOS in one commit.

Platforms:
- Android: `app` depends on `:shared`. Only imports and date types at the
  boundary change.
- iOS: `shared` is built as `Shared.xcframework` (Kotlin/Native). A thin Swift
  adapter wraps the long operations (parse, export) in async/await with
  progress reporting.

Acceptance:
- Parser/filter/export/trips tests live in `commonTest` and pass on the JVM
  and the iOS simulator. The exceptions stay in `jvmTest`: JVM-reference
  tests (differential tests against Jackson and `java.time`/`String.format`/
  `BigDecimal`, which do not exist on iOS) and the real-file tests, which read
  developer-local exports from disk. Pure-logic tests still in `jvmTest` move
  to `commonTest` over time.
- Exports (GPX, KML, GeoJSON, CSV) of the real test files are byte-identical
  before and after the move.
- Parsing `Timeline_merged_big.json` with the new lexer is at most 20% slower
  than with Jackson on Android.
- New lexer tests cover escapes, unicode, numbers and a truncated file.

## 2. iOS app (SwiftUI)

- Project: `ios/project.yml` (XcodeGen, as in Cinelace). `.xcodeproj` is
  generated, not committed. Deployment target iOS 17, device family iPhone + iPad.
- State: an `@Observable TimelineStore`, the counterpart of `TimelineViewModel`.
  It runs `shared` off the main thread and reports progress.
- Screens: a `NavigationStack` main screen (file, period, filters, tabs
  Track / Trips / Places). On iPad and in landscape, a `NavigationSplitView`
  with the list beside the map. Dialogs (date range, time of day, Help,
  What's new, Tip jar, Feedback) become sheets.
- Map: MapLibre Native iOS via SPM in a `UIViewRepresentable`. It uses the
  same `map_style_light.json` / `map_style_dark.json` (OpenFreeMap), copied
  from `app/src/main/assets` at build time. The iOS MapLibre version is chosen
  separately; the Android pin at 11.8 does not apply.
- Map image: `MLNMapSnapshotter` for the base map, Core Graphics for the
  overlays (track, labels, legend), geometry from the shared
  `MapImageLayout`. Saved as PNG/JPEG to Photos or Files.
- Files: import with `.fileImporter` (Files, iCloud Drive), and "Open in" /
  Share from Files. Files are read as a stream through security-scoped URLs.
  Export with `.fileExporter` / `ShareLink`, including batch trip export.
- Languages: all 18. A script in `tools/` converts `values*/strings.xml` into
  `Localizable.xcstrings`, so Android resources stay the single source of
  translations. There is no in-app language picker: the language button opens
  the system per-app language setting.
- Feedback: `MFMailComposeViewController` (mailto as a fallback) or a GitHub
  issue, with the same diagnostics as Android.
- Settings: `UserDefaults` instead of `RememberedSettings`.
- Not ported: `AppUpdateViewModel` (Play in-app update), `TipPromptPolicy` /
  `TipPromptState`.

## 3. Price and tips (StoreKit 2)

- Purchase: `Product.purchase()`. After `VerificationResult` is checked, the
  transaction is closed with `transaction.finish()`. An unfinished transaction
  (app killed mid-purchase) is picked up from `Transaction.updates` at the
  next launch.
- No Restore Purchases button: consumables are not restored.
- After a tip, a thank-you and nothing else. Tips unlock nothing.
- Products are described in the repository (`store/ios/iap/`: IDs, prices,
  texts in 18 languages, App Review screenshot) and created by `ios-iap.yml`
  with `asc_iap.py`, both taken from NEARCOIL: a read-only diff, then create
  confirmed with the diff's `plan_sha`, from `main` only. Existing products
  and prices are never changed.
- Tests: `TimelineExporter.storekit` (StoreKit Configuration) drives purchase,
  cancel, failure and unfinished-transaction tests in the CI simulator. Real
  sandbox purchases are tested through TestFlight. That needs the products in
  App Store Connect, so the Paid Apps Agreement is needed by the tips stage
  (6), not only at release.
- The App Store description and the Review Notes say that tips are optional
  and unlock nothing (guideline 3.1.1, tips).

## 4. CI and release

Runners: the standard GitHub-hosted arm64 macOS image (free for a public
repository), with the Xcode major checked; a different Xcode fails the run.
Larger runners are not used.

Workflows (`.github/workflows/`):
- `shared.yml`, Linux, every push and PR: `shared` tests on the JVM and the
  Android `assembleDebug`. Android releases are still built locally.
- `ios.yml`, manual, modes `validate` / `build` / `testflight` (NEARCOIL's
  layout). Linux preflight: secrets present, build number, `shared` tests,
  string conversion. macOS: `Shared.xcframework`, XcodeGen, `shared` tests on
  the simulator, XCTest and UI tests. `validate` builds unsigned; `build`
  produces a signed `.ipa` artifact; `testflight` uploads and waits for
  processing on Linux. `testflight` runs from `main` only.
- `ios-iap.yml`: tip products (section 3).
- `ios-screens.yml`: store screenshots.

Versions: iOS has its own `MARKETING_VERSION`, starting at 1.0.0. Build number
`YYMMDD.HHMMSS.attempt`, checked against App Store Connect (`asc_builds.py`
from NEARCOIL).

Signing is manual, from secrets. The team's Apple Distribution certificate
from NEARCOIL is reused; a new App Store provisioning profile is needed for
the bundle ID. Repository secrets: `APPLE_TEAM_ID`, `IOS_BUNDLE_ID`,
`IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64`,
`IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`, `IOS_PROVISIONING_PROFILE_BASE64`,
`APPSTORE_KEY_ID`, `APPSTORE_ISSUER_ID`, `APPSTORE_PRIVATE_KEY`.

Listing: `fastlane ios` lanes from NEARCOIL. Texts in 18 languages are built
from the Play texts in `fastlane/metadata/android`. The lanes never upload a
binary, submit for review or change the price.

Screenshots: XCUITest on the simulator with the demo data used for the Play
screenshots, iPhone 6.9" and iPad 13", 18 languages, light and dark. The
workflow keeps them as an artifact; `fastlane ios screenshots` uploads them.

One-time steps for the owner (written up step by step when reached): App ID,
provisioning profile, App Store Connect app record, GitHub secrets, Paid Apps
Agreement (by stage 6).

## 5. Testing

- `shared`: tests on the JVM (every push) and the iOS simulator; byte-identical
  exports; lexer tests; benchmark on `Timeline_merged_big.json`.
- iOS logic: XCTest for `TimelineStore`, the Swift adapter, import and export
  through temporary files, the string converter, and the StoreKit flow.
- UI: XCUITest for the main flows: open the demo file → filter → export each
  format, Trips, Places, Map image, tips. The same tests take the store
  screenshots. When a test fails, its screenshots and screen recording are
  uploaded as artifacts.
- On the device: every stage goes through TestFlight with a short checklist
  (large files: memory and time; Files/iCloud; dark theme). Crash reports come
  through TestFlight.

## Stages

Each stage gets its own plan and its own commits.

| # | Stage | Done when |
|---|---|---|
| 0 | Move to `shared` + `shared.yml` | Android 1.11 works as before; tests pass on the JVM |
| 1 | iOS skeleton: XcodeGen, `Shared.xcframework`, `ios.yml validate` | An empty app builds in CI |
| 2 | Signing + TestFlight (owner: App ID, profile, secrets) | First build on the owner's iPhone |
| 3 | Import, filters, export in 4 formats | The core of the app works on the device |
| 4 | MapLibre map + Track / Trips / Places | Feature parity except Map image |
| 5 | Map image, What's new, Help, Feedback, settings | Full feature set |
| 6 | Tips (StoreKit), `ios-iap.yml`, Paid Apps Agreement | Tips tested in sandbox |
| 7 | 18 languages, iPad layouts, screenshots, listing | Ready for review |
| 8 | Submission (by the owner) | 1.0.0 on the App Store at $1.99 |

## Risks

- The new lexer is slower than Jackson: optimise in stage 0, before any iOS work.
- MapLibre iOS with our styles: checked on a real device in stage 4, not only
  in the simulator.
- App Review: Review Notes and a demo Timeline file for the reviewer, so they
  need no data of their own.
