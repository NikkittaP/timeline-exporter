---
layout: privacy
---
# Privacy Policy — Timeline Exporter

**Last updated:** 2026-11-07

This policy covers Timeline Exporter for Android and for iPhone / iPad.

## Summary

Timeline Exporter does not collect, transmit, or store any personally
identifying information on remote servers. Everything you do in the app
happens on your device.

## What the app processes

- **The Timeline file you choose** (`Timeline.json` on Android,
  `location-history.json` on iPhone). The app reads it locally to extract
  GPS points. The file is never uploaded anywhere.
- **The exported file you save.** Saved through the system file picker
  (Android's Storage Access Framework, the Files app on iOS) to the location
  you pick (local storage, iCloud Drive, Google Drive, Dropbox, etc.), or
  handed to the system share sheet. The app writes the file there and
  forgets about it.
- **Map tile requests.** To render the in-app map preview, the app fetches
  map tiles over HTTPS from `tiles.openfreemap.org` (a free, donation-funded
  OpenStreetMap tile service). These requests are standard web requests
  containing only the tile coordinates and the device's IP address as
  visible to any HTTPS server. No user account, no device ID, no Timeline
  data is sent. See [OpenFreeMap's policy](https://openfreemap.org/) for
  what they log on their end.

## What the app does NOT collect

- No analytics
- No telemetry
- No advertising IDs
- No user accounts
- No location tracking (the app reads your existing Timeline; it does not
  collect new location data)
- No crash reporting beyond what you may have enabled at the OS level

## Permissions

- **Internet** — used only to fetch map tiles from `tiles.openfreemap.org`.

On iPhone and iPad, if you choose Save Image for a map picture, iOS asks
once whether the app may add photos to your library (add only: the app
cannot see your photos).

Other than that, the app does not request location, storage, contacts,
camera, microphone, or any other permission. File picking uses the system file
picker (Android's Storage Access Framework, the Files app on iOS), which
doesn't require app-side permissions because the user explicitly grants
per-file access.

## Open source

The source code is available at
[github.com/NikkittaP/timeline-exporter](https://github.com/NikkittaP/timeline-exporter).
You can verify any of the above claims by reading it.

## Contact

For questions about this policy or the app's behavior, contact
**nikitapetrovapps@gmail.com**.

## Changes

If this policy ever changes, the new version will be published in the same
location with an updated "Last updated" date. The repository's git history
records the diff.
