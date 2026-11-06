# Timeline Exporter

An Android app for parsing, filtering, and exporting Google Maps Timeline data
to standard GPS formats — GPX, KML, GeoJSON, CSV.

Since late 2024 Google stores Maps Timeline on-device only, with no cloud
backup and no Google Takeout export. This app gives you a phone-side tool to
slice that data by date and feed it to anything that reads GPS tracks
(Strava, Garmin, Komoot, OsmAnd, Google Earth, QGIS, your own scripts, …).

## Features

- Parses Google's `semanticSegments` JSON format (timelinePath, visit,
  activity) — handles degree-symbol coordinate strings and timezone-offset
  timestamps
- Date-range filter with quick-select chips (Last 7 / 30 / 90 days, All data)
- Live map preview of the filtered track via MapLibre + OpenStreetMap tiles
- Export to **GPX**, **KML**, **GeoJSON**, or **CSV**
- Receives shared `.json` files from the Android share sheet — no need to
  use the in-app file picker every time
- 100% on-device: no analytics, no telemetry, no ads, no servers other than
  map-tile fetches to OpenFreeMap

## Requirements

- Android 8.0 (API 26) or later
- A `Timeline.json` exported from Google Maps. In Android Settings:
  **Location → Location services → Timeline → Export Timeline data**.
  The app's in-screen Help dialog also walks you through this.

## Build

- Android Studio Panda 4 (2025.3.4) or later
- Clone, open the project, let Gradle sync, hit ▶ Run
- First Gradle sync downloads MapLibre native libs (~6 MB) and Compose
  artifacts — give it a few minutes on a fresh machine

## Tech stack

- **Kotlin 2.2** + **Jetpack Compose** (Material 3)
- **kotlinx.serialization** for the JSON parser
- **MapLibre Native Android 11.8** for the map preview, with vector tiles
  from [OpenFreeMap](https://openfreemap.org) (Liberty style)
- Targets API 36 (Android 16), min API 26 (Android 8.0)

### Why MapLibre 11.8 and not 13.x

MapLibre 13.7.1 was tried in November 2026 and rolled back. On a Xiaomi
Mi 5s (Android 8.0, Adreno 530, GPU driver from April 2018), 13.x does not
draw the track at all. You see only the dashed gap lines and the endpoint
dots. Logcat shows over a hundred `Adreno: Shader compilation failed`
lines and no crash. The same phone draws the track with 11.8.0, and newer
GPUs (OnePlus 7 Pro, Pixel 7 AVD) draw it with either version. Old Adreno
drivers also shipped on Android 10 phones, and 13.x gave the app nothing
visible: the map teardown bug maplibre-native#4622 that `textureMode`
works around is still open there, and the AAB grows by 3.3 MB. So 11.8 is
pinned in `gradle/libs.versions.toml`. Before bumping it, check the map
on an old-driver Adreno phone and grep logcat for
`Shader compilation failed`.

## Architecture

```
parser/   — pure Kotlin JSON parser, unit-tested on real 60 MB exports
filter/   — TimelineFilter spec + applyFilter pure function
export/   — Exporter strategy interface + GPX/KML/GeoJSON/CSV objects
ui/       — Compose screens, ViewModel, MapLibre wrapper, dialogs
```

Each layer is independently unit-testable. UI never touches MapLibre or
ContentResolver types directly; the ViewModel mediates.

The `parser`, `filter`, `export` and `trips` layers live in the Kotlin
Multiplatform module `shared` (JVM plus iOS targets), which the Android app
depends on; the `shared` workflow runs its tests on the JVM and on the iOS
simulator.

## Attribution

- Map tiles: **OpenFreeMap** (donation-funded), based on **OpenStreetMap**
  data © OSM contributors, ODbL.
- Rendering: **MapLibre Native** (BSD-2-Clause).
- Built with: Jetpack Compose, kotlinx.serialization (both Apache 2.0).

## License

Source code is released under the MIT License — see [LICENSE](LICENSE).
