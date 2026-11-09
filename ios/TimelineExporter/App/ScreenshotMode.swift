import Foundation

/// Store screenshots: the UI test (TimelineExporterUITests/StoreScreenshots)
/// launches the app with `-screenshotScene <scene>` and the app puts itself in
/// that state, loaded from the bundled demo timeline, without a tap. Each
/// scene is a fresh launch, so no scene depends on getting back from another.
///
/// Debug builds only: `scene` is always nil in Release. The demo file itself
/// ships in both, for "Try it with sample data" (TimelineStore.openSample).
enum ScreenshotMode {
    enum Scene: String, CaseIterable {
        /// First launch, nothing loaded.
        case empty
        /// The loaded page, top.
        case overview
        /// The full-screen map, all of the history.
        case map
        /// Scrolled to the export buttons.
        case formats
        /// Places: the dots on the map over the list.
        case places
        /// Trips, the Norway holiday picked and ticked.
        case trip
        /// Trips, the list.
        case trips
        /// The last week, coloured by day, full screen.
        case days
        /// The Map image screen for the same week.
        case mapimage
        // Layout checks, not store shots: taken only when asked for or turned.
        /// Trips, one ticked for export and the one next to it on the map.
        case tripPreview
        /// Places, one picked on the map.
        case placeSelected
        /// The Map image screen; the UI test sets the 16:9 shape.
        case mapimageLandscape
    }

    static let scene: Scene? = {
        #if DEBUG
        UserDefaults.standard.string(forKey: "screenshotScene").flatMap(Scene.init(rawValue:))
        #else
        nil
        #endif
    }()

    static var isActive: Bool { scene != nil }

    /// The Form row the shot is framed on, and where it sits on screen. The
    /// full-screen map and Map image scenes scroll to the button the UI test
    /// then taps.
    static var anchor: (id: String, bottom: Bool)? {
        switch scene {
        case .formats: ("export", true)
        case .mapimage, .mapimageLandscape: ("mapImage", true)
        case .places, .trip, .trips, .map, .days, .tripPreview, .placeSelected: ("preview", false)
        default: nil
        }
    }

    /// The first day of the Norway holiday in tools/generate_demo_timeline.py
    /// (VACATIONS), the trip the Android 11_trip shot picks too.
    private static let focusTrip = DateComponents(year: 2026, month: 5, day: 12)

    /// Loads the demo and applies the scene. Called once, at launch.
    @MainActor static func prepare(_ store: TimelineStore) async {
        guard let scene, scene != .empty,
              let bundled = Bundle.main.url(forResource: "timeline_demo", withExtension: "json") else { return }
        // Shown in step 1 under the name a real export has.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Timeline.json")
        try? FileManager.default.removeItem(at: url)
        guard (try? FileManager.default.copyItem(at: bundled, to: url)) != nil else { return }
        await store.open(url: url)
        guard store.phase == .loaded, let core = store.timeline else { return }

        let cal = Calendar.current
        switch scene {
        case .places:
            store.setContentKind(.places)
        case .trips:
            store.setContentKind(.trips)
        case .trip:
            store.setContentKind(.trips)
            if let day = cal.date(from: focusTrip),
               let trip = store.tripList.trips.first(where: { cal.isDate($0.startDay, inSameDayAs: day) }) {
                store.setTrip(trip.key, checked: true)
                store.selectTrip(trip.key)
            }
        case .tripPreview:
            store.setContentKind(.trips)
            let trips = store.visibleTrips
            if let day = cal.date(from: focusTrip),
               let index = trips.firstIndex(where: { cal.isDate($0.startDay, inSameDayAs: day) }) {
                store.setTrip(trips[index].key, checked: true)
                if index + 1 < trips.count {
                    store.selectTrip(trips[index + 1].key)
                } else if index > 0 {
                    store.selectTrip(trips[index - 1].key)
                }
            }
        case .placeSelected:
            store.setContentKind(.places)
        case .days, .mapimage, .mapimageLandscape:
            // The demo ends on a seven-day road trip with a road of its own every day.
            if let last = core.lastPoint {
                store.setRange(cal.lastDays(7, endingAt: last))
            }
            store.colorByDay = true
        case .empty, .overview, .map, .formats:
            break
        }
        await store.recount()
        // The places exist only once counted.
        if scene == .placeSelected, let place = store.places.first {
            store.selectPlace(place.key)
        }
    }
}
