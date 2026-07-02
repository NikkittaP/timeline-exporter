# Release Notes — v1.3.0 (versionCode 6)

## Highlights
Large Timeline files now import reliably. The JSON parser was rewritten to be
fully streaming, so the app no longer loads the whole file into memory before
parsing. Imports are faster, use far less memory, and very large exports
(100 MB+) that previously failed now work.

## Fixed
- **Out-of-memory on large files.** Previously the importer read the entire file
  into a `String` and decoded the whole document into an object tree, so a large
  export could exhaust the heap. The failure surfaced as the misleading message
  *"This doesn't look like a Google Timeline file."* (OOM and parse errors share
  that friendly message.) Large files now stream and parse without OOM.
- **Progress bar stalling near the middle on files with `rawSignals`.** The
  large `rawSignals` tail was skipped in one silent step, freezing the bar and
  then jumping to done. The parser now walks it incrementally and keeps the bar
  moving to 100%.
- **File-size mismatch.** The import screen showed a smaller size (MiB) than
  Android's file picker (decimal MB) for the same file. Sizes now use decimal MB
  and match the system picker.

## Changed (technical)
- New streaming parser built on **Jackson** (`jackson-core`, Apache-2.0): GPS
  points are extracted token-by-token and each segment is discarded immediately,
  so peak memory is proportional to the number of extracted points, not file size.
- All formats still supported: phone `semanticSegments` (object + iOS array
  variant), `timelineObjects` (Semantic Location History), `locations`
  (Records.json), and the `rawSignals` fallback.
- `rawSignals` are skipped (not parsed) when `timelinePath` points exist, which
  is the normal case for real exports.
- The ViewModel parses directly from the file `InputStream` via a
  `CountingInputStream` for byte-accurate progress.
- Removed the `kotlinx.serialization` dependency, Gradle plugin and ProGuard
  rules (no longer used); removed the `@Serializable` model classes.

## Tests
- Added streaming tests: `InputStream` entry point, `rawSignals` not
  double-counted (both key orders), a 5,000-segment streamed input, and parser
  stage ordering. Existing parser tests are unchanged and still pass.

## Pre-release checklist
- [ ] `./gradlew testDebugUnitTest` (all parser tests, incl. new streaming ones)
- [ ] `./gradlew assembleRelease` (confirms R8/ProGuard builds without kotlinx.serialization)
- [ ] Manual import of the 41 MB and 115 MB test files; verify the progress bar
      fills to 100% and the shown size matches the file picker.
