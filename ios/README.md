# Timeline Exporter for iOS

SwiftUI app on top of the Kotlin Multiplatform module `shared`
(design: `docs/superpowers/specs/2026-11-06-ios-port-design.md`).

There is no Mac in this project: the app is built and tested only in GitHub
Actions (`.github/workflows/ios.yml`). The steps the workflow runs:

1. `./gradlew :shared:assembleSharedReleaseXCFramework` builds
   `shared/build/XCFrameworks/release/Shared.xcframework`.
2. `xcodegen generate` in `ios/` turns `project.yml` into
   `TimelineExporter.xcodeproj` (generated, not committed).
3. `xcodebuild` builds the app and runs `TimelineExporterTests` on the simulator.

Source layout: `TimelineExporter/App` (SwiftUI), `TimelineExporter/Shared`
(the only Swift that imports the Kotlin framework), `TimelineExporter/Resources`,
`TimelineExporterTests`.
