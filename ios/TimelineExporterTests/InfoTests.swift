import XCTest
@testable import TimelineExporter

final class WhatsNewTests: XCTestCase {
    private let entries = [
        WhatsNewEntry(version: "1.0.1", key: "a"),
        WhatsNewEntry(version: "1.2.0", key: "c"),
        WhatsNewEntry(version: "1.1.0", key: "b"),
    ]

    func testComparesByNumericParts() {
        XCTAssertEqual(WhatsNew.compare("1.10.0", "1.9.2"), 1)
        XCTAssertEqual(WhatsNew.compare("1.0", "1.0.0"), 0)
        XCTAssertEqual(WhatsNew.compare("1.0.1", "1.1"), -1)
    }

    func testAFreshInstallSeesNothing() {
        XCTAssertEqual(WhatsNew.pending(entries, seen: nil, current: "1.2.0"), [])
    }

    func testAnUpdateSeesEveryReleaseSinceTheLastSeenNewestFirst() {
        let pending = WhatsNew.pending(entries, seen: "1.0.1", current: "1.2.0")
        XCTAssertEqual(pending.map(\.version), ["1.2.0", "1.1.0"])
    }

    func testNotesNewerThanTheBuildAreLeftOut() {
        XCTAssertEqual(WhatsNew.history(entries, current: "1.1.0").map(\.version), ["1.1.0", "1.0.1"])
    }
}

final class FeedbackTests: XCTestCase {
    func testTheBodyListsChoicesNotesAndDiagnostics() {
        let body = Feedback.body(selected: [2, 0], other: "  more maps \n", diagnostics: "App: 1.0.0")
        XCTAssertEqual(body, """
        Requested features:
        - A separate file per trip instead of one large file
        - Monthly and yearly totals

        Notes:
        more maps

        ---
        App: 1.0.0

        """)
    }

    func testOutgoingTextsMatchTheEnglishLabels() {
        let bundle = Bundle.main.path(forResource: "en", ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
        for option in Feedback.options {
            XCTAssertEqual(bundle.localizedString(forKey: option.key, value: nil, table: nil), option.outgoing)
        }
    }

    func testLinksKeepTheBodyIntact() throws {
        let body = "a & b + c\nd"
        let github = try XCTUnwrap(Feedback.githubURL(body: body))
        let items = URLComponents(url: github, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(items.first { $0.name == "body" }?.value, body)

        let mail = try XCTUnwrap(Feedback.mailURL(body: body))
        XCTAssertTrue(mail.absoluteString.hasPrefix("mailto:nikitapetrovapps@gmail.com?subject="))
        XCTAssertTrue(mail.absoluteString.contains("body=a%20%26%20b%20%2B%20c%0Ad"))
    }

    func testDiagnosticsNameTheFileWhenOneIsOpen() {
        let text = Feedback.diagnostics(formatName: "PHONE_TAKEOUT", pointCount: 3)
        XCTAssertTrue(text.contains("File format: PHONE_TAKEOUT"))
        XCTAssertTrue(text.hasSuffix("Points: 3"))
        XCTAssertFalse(Feedback.diagnostics(formatName: nil, pointCount: nil).contains("Points"))
    }
}
