import XCTest

/// The raw App Store screenshots, one fresh launch per scene and language. The
/// app puts itself in each state (ScreenshotMode); this only launches, waits
/// for the scene to settle and writes the screen as a PNG.
///
/// Driven by tools/ios/store_screenshots.sh through environment variables
/// (xcodebuild passes TEST_RUNNER_<NAME> to the runner as <NAME>):
///   SCREENSHOT_DIR       the raw root: <dir>/<locale>/<kind>/<name>.png (a
///                        host path: the simulator shares the Mac's file system)
///   SCREENSHOT_KIND      iphone or ipad
///   SCREENSHOT_LOCALES   comma list of locale=language, e.g. en-US=en,zh-CN=zh-Hans
///   SCREENSHOT_SCENES    optional, a comma list of scenes to take
///
/// Every language runs in this one test: a test run per language cost about
/// a minute and a half of xcodebuild start-up each. Failures name the locale
/// as "[de-DE] 03_map_fullscreen: ...", which the script collects.
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

    /// The map draws OpenFreeMap tiles off the network; the app reports
    /// "idle" once they are in (TimelineMapView), this is the most it waits.
    private static let mapIdleWait: TimeInterval = 20

    override func setUp() {
        continueAfterFailure = true
    }

    @MainActor
    func testCaptureStoreScreenshots() throws {
        let env = ProcessInfo.processInfo.environment
        let root = try XCTUnwrap(env["SCREENSHOT_DIR"], "SCREENSHOT_DIR is not set")
        let kind = try XCTUnwrap(env["SCREENSHOT_KIND"], "SCREENSHOT_KIND is not set")
        let locales = try XCTUnwrap(env["SCREENSHOT_LOCALES"], "SCREENSHOT_LOCALES is not set")
            .split(separator: ",")
            .compactMap { pair -> (locale: String, language: String)? in
                let parts = pair.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
                return parts.count == 2 ? (parts[0], parts[1]) : nil
            }
        XCTAssertFalse(locales.isEmpty, "SCREENSHOT_LOCALES has no locale=language pair")
        let only = Self.list(env["SCREENSHOT_SCENES"])

        for (locale, language) in locales {
            let dir = "\(root)/\(locale)/\(kind)"
            try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            for (scene, name) in Self.scenes where only?.contains(scene) ?? true {
                XCTContext.runActivity(named: "\(locale) \(name)") { _ in
                    capture(scene: scene, name: name, locale: locale, language: language, dir: dir)
                }
            }
        }
    }

    @MainActor
    private func capture(scene: String, name: String, locale: String, language: String, dir: String) {
        let tag = "[\(locale)] \(name)"
        let app = XCUIApplication()
        app.launchArguments = [
            "-screenshotScene", scene,
            "-AppleLanguages", "(\(language))",
            "-AppleLocale", Self.locale(language),
        ]
        app.launch()
        defer { app.terminate() }

        var ok = true
        switch scene {
        case "empty":
            // The welcome text fills the first screen; the toolbar is always there.
            ok = app.buttons["moreMenu"].waitForExistence(timeout: 20)
            XCTAssertTrue(ok, "\(tag): no start screen")
            sleep(1)
        case "overview":
            // The top of the loaded page: the map is below the fold, not loaded.
            ok = app.buttons["pickFile"].waitForExistence(timeout: 30)
            XCTAssertTrue(ok, "\(tag): the file never loaded")
            sleep(3)
        case "mapimage":
            ok = tap(app, "mapImage", tag: tag)
            if ok {
                // Any element type: with its overlay the picture is not always an "image".
                ok = app.descendants(matching: .any)["mapImagePreview"].waitForExistence(timeout: 60)
                XCTAssertTrue(ok, "\(tag): the preview never rendered")
                sleep(1)
            }
        default:
            let map = app.descendants(matching: .any)["map"]
            ok = map.waitForExistence(timeout: 30)
            XCTAssertTrue(ok, "\(tag): the map never appeared")
            let fullScreen = scene == "map" || scene == "days"
            if ok, fullScreen {
                ok = tap(app, "mapExpand", tag: tag)
            }
            if ok {
                if !waitForIdleMap(app, fullScreen: fullScreen) {
                    print("\(tag): the map did not report idle in \(Int(Self.mapIdleWait)) s, taking it anyway")
                }
                sleep(1)
            }
        }

        let shot = XCUIScreen.main.screenshot()
        if !ok {
            let attachment = XCTAttachment(screenshot: shot)
            attachment.name = "\(locale)-\(name)"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        let url = URL(fileURLWithPath: dir).appendingPathComponent("\(name).png")
        do {
            try shot.pngRepresentation.write(to: url)
        } catch {
            XCTFail("\(tag): could not write \(url.path): \(error)")
        }
    }

    /// Taps the button the scene scrolled to (ScreenshotMode.anchor), once
    /// it is there and enabled and the scene has had a moment to settle.
    @MainActor
    private func tap(_ app: XCUIApplication, _ id: String, tag: String) -> Bool {
        let button = app.buttons[id]
        guard button.waitForExistence(timeout: 30) else {
            XCTFail("\(tag): the button \(id) never appeared")
            return false
        }
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"),
                                                object: button)
        guard XCTWaiter().wait(for: [enabled], timeout: 20) == .completed else {
            XCTFail("\(tag): the button \(id) never became tappable")
            return false
        }
        sleep(2)
        button.tap()
        return true
    }

    /// Polls for a map whose value is "idle"; in the full-screen scenes it
    /// has to be the big one, the small map in the list goes idle first.
    @MainActor
    private func waitForIdleMap(_ app: XCUIApplication, fullScreen: Bool) -> Bool {
        let deadline = Date().addingTimeInterval(Self.mapIdleWait)
        let screen = app.windows.firstMatch.frame.height
        let maps = app.descendants(matching: .any).matching(identifier: "map")
        while Date() < deadline {
            for map in maps.allElementsBoundByIndex where map.exists {
                if map.value as? String == "idle", !fullScreen || map.frame.height > screen * 0.7 {
                    return true
                }
            }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return false
    }

    private static func list(_ value: String?) -> Set<String>? {
        guard let value else { return nil }
        let items = Set(value.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
        return items.isEmpty ? nil : items
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
