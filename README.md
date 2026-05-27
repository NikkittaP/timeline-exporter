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

- Android 10 (API 29) or later
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
- Targets API 36 (Android 16), min API 29 (Android 10)

## Architecture

```
parser/   — pure Kotlin JSON parser, unit-tested on real 60 MB exports
filter/   — TimelineFilter spec + applyFilter pure function
export/   — Exporter strategy interface + GPX/KML/GeoJSON/CSV objects
ui/       — Compose screens, ViewModel, MapLibre wrapper, dialogs
```

Each layer is independently unit-testable. UI never touches MapLibre or
ContentResolver types directly; the ViewModel mediates.

## Attribution

- Map tiles: **OpenFreeMap** (donation-funded), based on **OpenStreetMap**
  data © OSM contributors, ODbL.
- Rendering: **MapLibre Native** (BSD-2-Clause).
- Built with: Jetpack Compose, kotlinx.serialization (both Apache 2.0).

## License

Source code is released under the MIT License — see [LICENSE](LICENSE).
