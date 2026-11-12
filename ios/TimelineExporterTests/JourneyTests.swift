import XCTest
@testable import TimelineExporter

final class JourneyTests: XCTestCase {
    func testTapSequenceFiresOnTheLastQuickTap() {
        var taps = TapSequence(count: 5, window: 3)
        XCTAssertEqual((0..<4).map { taps.tap(now: 10 + Double($0) * 0.2) }, [false, false, false, false])
        XCTAssertTrue(taps.tap(now: 11))
        // It starts over after firing.
        XCTAssertFalse(taps.tap(now: 11.1))
    }

    func testTapSequenceStartsOverAfterTheWindow() {
        var taps = TapSequence(count: 3, window: 1)
        XCTAssertFalse(taps.tap(now: 0))
        XCTAssertFalse(taps.tap(now: 0.5))
        XCTAssertFalse(taps.tap(now: 2))
        XCTAssertFalse(taps.tap(now: 2.2))
        XCTAssertTrue(taps.tap(now: 2.4))
    }

    func testRatioShowsTwoDecimalsOrEnoughForAShortTrip() {
        let en = Locale(identifier: "en_US")
        XCTAssertEqual(Journey.ratio(40_075_000 * 1.5, locale: en), "1.5")
        XCTAssertEqual(Journey.ratio(40_075_000 * 0.25, locale: en), "0.25")
        // 2 km is 0.0000499 of the way round: the first figures show.
        XCTAssertEqual(Journey.ratio(2_000, locale: en), "0.00005")
        XCTAssertEqual(Journey.ratio(40_075_000 * 0.5, locale: Locale(identifier: "ru_RU")), "0,5")
    }
}
