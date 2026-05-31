# Privacy Policy — Timeline Exporter

**Last updated:** 2026-05-27

## Summary

Timeline Exporter does not collect, transmit, or store any personally
identifying information on remote servers. Everything you do in the app
happens on your device.

## What the app processes

- **The Timeline.json file you choose.** The app reads it locally to extract
  GPS points. The file is never uploaded anywhere.
- **The exported file you save.** Saved via Android's Storage Access
  Framework to the location you pick (local storage, Google Drive, Dropbox,
  etc.). The app writes the file there and forgets about it.
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

- **INTERNET** — used only to fetch map tiles from `tiles.openfreemap.org`.

The app does not request location, storage, contacts, camera, microphone,
or any other runtime permission. File picking uses Android's Storage
Access Framework, which doesn't require app-side permissions because the
user explicitly grants per-file access through the system file picker.

## Open source

The source code is available at
[github.com/NikkittaP/timeline-exporter](https://github.com/NikkittaP/timeline-exporter).
You can verify any of the above claims by reading it.

## Contact

For questions about this policy or the app's behavior, contact
**nikitapetroff@gmail.com**.

## Changes

If this policy ever changes, the new version will be published in the same
location with an updated "Last updated" date. The repository's git history
records the diff.
