import XCTest

/// The raw App Store screenshots, one fresh launch per scene. The app puts
/// itself in each state (ScreenshotMode); this only launches, waits for the
/// scene to settle and writes the screen as a PNG.
///
/// Driven by tools/ios/store_screenshots.sh through environment variables
/// (xcodebuild passes TEST_RUNNER_<NAME> to the runner as <NAME>):
///   SCREENSHOT_DIR       where <name>.png goes (a host path: the simulator
///                        shares the Mac's file system)
///   SCREENSHOT_LANGUAGE  the app's language, e.g. de or zh-Hans
///   SCREENSHOT_SCENES    optional, a comma list of scenes to take
///
/// The names are the Android captures', so tools/store_shots frames both
/// platforms from one config.
final class StoreScreenshots: XCTestCase {
    /// Scene (ScreenshotMode.Scene) -> capture name.
    private static let scenes: [(scene: String, name: String)] = [
        ("map", "03_map_fullscreen"),
        ("mapimage", "13_map_image"),
        ("trip", "11_trip"),
        ("trips", "12_trips"),
        ("days", "10_days"),
        ("places", "08_places"),
        ("formats", "05_formats"),
        ("empty", "06_start_empty"),
        ("overview", "02_overview"),
    ]

    /// The map draws OpenFreeMap tiles off the network.
    private static let mapWait: TimeInterval = 10

    override func setUp() {
        continueAfterFailure = true
    }

    @MainActor
    func testCaptureStoreScreenshots() throws {
        let env = ProcessInfo.processInfo.environment
        let dir = try XCTUnwrap(env["SCREENSHOT_DIR"], "SCREENSHOT_DIR is not set")
        let language = env["SCREENSHOT_LANGUAGE"] ?? "en"
        let only = env["SCREENSHOT_SCENES"].flatMap { list -> Set<String>? in
            let scenes = Set(list.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
            return scenes.isEmpty ? nil : scenes
        }
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)

        for (scene, name) in Self.scenes where only?.contains(scene) ?? true {
            XCTContext.runActivity(named: "\(language) \(name)") { _ in
                capture(scene: scene, name: name, language: language, dir: dir)
            }
        }
    }

    @MainActor
    private func capture(scene: String, name: String, language: String, dir: String) {
        let app = XCUIApplication()
        app.launchArguments = [
            "-screenshotScene", scene,
            "-AppleLanguages", "(\(language))",
            "-AppleLocale", Self.locale(language),
        ]
        app.launch()
        defer { app.terminate() }

        switch scene {
        case "empty":
            XCTAssertTrue(app.buttons["pickFile"].waitForExistence(timeout: 20), "\(name): no start screen")
            sleep(1)
        case "mapimage":
            let preview = app.images["mapImagePreview"]
            XCTAssertTrue(preview.waitForExistence(timeout: 90), "\(name): the preview never rendered")
            sleep(2)
        default:
            XCTAssertTrue(app.otherElements["map"].waitForExistence(timeout: 60)
                          || app.descendants(matching: .any)["map"].waitForExistence(timeout: 5),
                          "\(name): the map never appeared")
            Thread.sleep(forTimeInterval: Self.mapWait)
        }

        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let url = URL(fileURLWithPath: dir).appendingPathComponent("\(name).png")
        do {
            try shot.pngRepresentation.write(to: url)
        } catch {
            XCTFail("\(name): could not write \(url.path): \(error)")
        }
    }

    /// A region for the language, so numbers and dates read the way they do there.
    private static func locale(_ language: String) -> String {
        switch language {
        case "en": "en_US"
        case "ar": "ar_SA"
        case "pt-BR": "pt_BR"
        case "zh-Hans": "zh_CN"
        case "zh-Hant": "zh_TW"
        case "uk": "uk_UA"
        case "ja": "ja_JP"
        case "ko": "ko_KR"
        case "sv": "sv_SE"
        case "hi": "hi_IN"
        default: "\(language)_\(language.uppercased())"
        }
    }
}
