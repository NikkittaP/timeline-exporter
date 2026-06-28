fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android build_for_screengrab

```sh
[bundle exec] fastlane android build_for_screengrab
```

Build the debug app + instrumentation APKs that screengrab drives

### android add_hero_captions

```sh
[bundle exec] fastlane android add_hero_captions
```

Overlay the localized '100% on-device' caption onto 01_hero (built from the clean 03_map_fullscreen shot). Requires Python + Pillow.

### android screens_phone

```sh
[bundle exec] fastlane android screens_phone
```

Capture screenshots on a PHONE emulator/device (run one first)

### android screens_tablet7

```sh
[bundle exec] fastlane android screens_tablet7
```

Capture screenshots on a 7-inch TABLET emulator/device

### android screens_tablet10

```sh
[bundle exec] fastlane android screens_tablet10
```

Capture screenshots on a 10-inch TABLET emulator/device

### android upload_screens

```sh
[bundle exec] fastlane android upload_screens
```

Upload ONLY screenshots to Play (internal track). Leaves your listing text, release notes and the AAB untouched.

### android upload_screens_dryrun

```sh
[bundle exec] fastlane android upload_screens_dryrun
```

Dry-run the upload (validates, changes nothing on Play)

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
