import MapLibre
import SwiftUI
import UIKit

/// The paper plane that flies the track: a long press on the map's recenter
/// button, the twin of Android's RouteMotion and its flight in TimelineMap.kt.
/// The map frames the track, the plane follows the recorded line for six
/// seconds and lands as a small heart.
struct RouteMotion {
    struct Position {
        let coordinate: CLLocationCoordinate2D
        /// Degrees clockwise from north.
        let bearing: Double
        let alpha: Double
    }

    private struct Edge {
        let from: MapLine.Position
        let to: MapLine.Position
        let start: Double
        let length: Double
        let run: Int
    }

    private let edges: [Edge]
    private let total: Double
    /// The section flown when the whole history is too large to follow: the
    /// map frames it before the flight.
    let focus: [MapLine.Position]?
    private let runBounds: [Int: (Double, Double)]

    private init(edges: [Edge], total: Double, focus: [MapLine.Position]? = nil) {
        self.edges = edges
        self.total = total
        self.focus = focus
        var bounds: [Int: (Double, Double)] = [:]
        for e in edges {
            bounds[e.run] = (bounds[e.run]?.0 ?? e.start, e.start + e.length)
        }
        runBounds = bounds
    }

    /// Never bridges two pieces of the line, an invalid sample or a jump
    /// longer than Android's map gap.
    static func prepare(_ line: MapLine) -> RouteMotion? {
        var edges: [Edge] = []
        var total = 0.0
        var run = 0
        for piece in line.pieces {
            run += 1
            var previous: MapLine.Position?
            for i in piece.range where i < line.latitudes.count && i < line.longitudes.count {
                let p = MapLine.Position(latitude: line.latitudes[i], longitude: line.longitudes[i])
                guard valid(p) else { previous = nil; run += 1; continue }
                if let prior = previous {
                    let length = haversine(prior, p)
                    if !length.isFinite || length > gapMeters {
                        run += 1
                    } else if length > 0.01 {
                        edges.append(Edge(from: prior, to: p, start: total, length: length, run: run))
                        total += length
                    }
                }
                previous = p
            }
        }
        guard !edges.isEmpty, total.isFinite else { return nil }
        // A world-scale history shrinks a whole local route to a few points:
        // fly its longest continuous run instead.
        if total > 2_000_000 || edges.count > 15_000 {
            var lengths: [Int: Double] = [:]
            for e in edges { lengths[e.run, default: 0] += e.length }
            if let best = lengths.max(by: { $0.value < $1.value })?.key {
                var chosen: [Edge] = []
                var section = 0.0
                for e in edges where e.run == best {
                    if chosen.count >= 5_000 || section >= 1_000_000 { break }
                    chosen.append(e)
                    section += e.length
                }
                if !chosen.isEmpty, chosen.count < edges.count || total > 2_000_000 {
                    var distance = 0.0
                    let compact = chosen.map { e -> Edge in
                        defer { distance += e.length }
                        return Edge(from: e.from, to: e.to, start: distance, length: e.length, run: e.run)
                    }
                    let points = [chosen[0].from] + chosen.map(\.to)
                    return RouteMotion(edges: compact, total: distance, focus: points)
                }
            }
        }
        return RouteMotion(edges: edges, total: total)
    }

    func position(_ fraction: Double) -> Position? {
        guard !edges.isEmpty else { return nil }
        let distance = min(1, max(0, fraction)) * total
        var low = 0
        var high = edges.count - 1
        while low < high {
            let mid = (low + high) / 2
            if edges[mid].start + edges[mid].length < distance { low = mid + 1 } else { high = mid }
        }
        let e = edges[low]
        let t = min(1, max(0, (distance - e.start) / e.length))
        let dLon = Self.wrap(e.to.longitude - e.from.longitude)
        let lat = e.from.latitude + (e.to.latitude - e.from.latitude) * t
        let lon = Self.wrap(e.from.longitude + dLon * t)
        let dy = (e.to.latitude - e.from.latitude) * .pi / 180
        let dx = dLon * .pi / 180 * cos(lat * .pi / 180)
        let bearing = atan2(dx, dy) * 180 / .pi
        // Fades out and in where the line breaks, rather than jumping.
        let fade = total * 0.015
        let bounds = runBounds[e.run] ?? (e.start, e.start + e.length)
        let before = e.run != edges.first?.run ? distance - bounds.0 : fade
        let after = e.run != edges.last?.run ? bounds.1 - distance : fade
        let alpha = fade > 0 ? min(1, max(0, min(before, after) / fade)) : 1
        return Position(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon), bearing: bearing, alpha: alpha)
    }

    /// Android's GAP_DISTANCE_METERS.
    private static let gapMeters = 80_000.0

    private static func valid(_ p: MapLine.Position) -> Bool {
        p.latitude.isFinite && p.longitude.isFinite && abs(p.latitude) <= 90 && abs(p.longitude) <= 180
    }

    private static func wrap(_ lon: Double) -> Double {
        (lon + 540).truncatingRemainder(dividingBy: 360) - 180
    }

    private static func haversine(_ a: MapLine.Position, _ b: MapLine.Position) -> Double {
        let r = 6_371_000.0
        let lat1 = a.latitude * .pi / 180, lat2 = b.latitude * .pi / 180
        let dLat = lat2 - lat1
        let dLon = (b.longitude - a.longitude) * .pi / 180
        let h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1, sqrt(h)))
    }
}

/// The plane over the map, then the heart it lands as.
final class FlightOverlayView: UIView {
    enum Shape { case plane, heart }

    var shape = Shape.plane { didSet { setNeedsDisplay() } }
    var color: UIColor = .systemBlue { didSet { setNeedsDisplay() } }

    static let side: CGFloat = 32

    override init(frame: CGRect) {
        super.init(frame: CGRect(x: 0, y: 0, width: Self.side, height: Self.side))
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
        isAccessibilityElement = false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func draw(_ rect: CGRect) {
        let c = CGPoint(x: bounds.midX, y: bounds.midY)
        switch shape {
        case .plane:
            // Line for line the Journey plane, at Android's 28 dp.
            let s: CGFloat = 28
            let body = UIBezierPath()
            body.move(to: CGPoint(x: c.x, y: c.y - s / 2))
            body.addLine(to: CGPoint(x: c.x + s * 0.46, y: c.y + s * 0.42))
            body.addLine(to: CGPoint(x: c.x, y: c.y + s * 0.22))
            body.addLine(to: CGPoint(x: c.x - s * 0.46, y: c.y + s * 0.42))
            body.close()
            UIColor(red: 1, green: 0.976, blue: 0.941, alpha: 1).setFill()
            body.fill()
            color.setStroke()
            body.lineWidth = s * 0.06
            body.lineJoinStyle = .round
            body.stroke()
            let spine = UIBezierPath()
            spine.move(to: CGPoint(x: c.x, y: c.y - s * 0.38))
            spine.addLine(to: CGPoint(x: c.x, y: c.y + s * 0.22))
            spine.lineWidth = s * 0.04
            spine.stroke()
        case .heart:
            let u: CGFloat = 7
            let heart = UIBezierPath()
            heart.move(to: CGPoint(x: c.x, y: c.y + u))
            heart.addCurve(to: CGPoint(x: c.x, y: c.y - u),
                           controlPoint1: CGPoint(x: c.x - 2 * u, y: c.y - u),
                           controlPoint2: CGPoint(x: c.x - u, y: c.y - 2 * u))
            heart.addCurve(to: CGPoint(x: c.x, y: c.y + u),
                           controlPoint1: CGPoint(x: c.x + u, y: c.y - 2 * u),
                           controlPoint2: CGPoint(x: c.x + 2 * u, y: c.y - u))
            heart.close()
            color.setFill()
            heart.fill()
        }
    }
}

/// Runs one flight on one map: frame, fly, land, gone. Cancelled by anything
/// that moves the map or changes what it shows.
final class RouteFlight: NSObject {
    private weak var map: MLNMapView?
    private let route: RouteMotion
    private let plane = FlightOverlayView()
    private var link: CADisplayLink?
    private var started: CFTimeInterval = 0
    private var landing: DispatchWorkItem?
    private(set) var finished = false
    var onEnd: (() -> Void)?

    private static let duration: CFTimeInterval = 6
    private static let landingPause: TimeInterval = 0.7

    init(map: MLNMapView, route: RouteMotion, color: UIColor) {
        self.map = map
        self.route = route
        super.init()
        plane.color = color
        plane.alpha = 0
    }

    /// [fit] frames the whole track (animated unless motion is reduced); the
    /// flight starts once the camera is there, and on the flown section of a
    /// very large history. Waits by the clock: MapLibre need not call a
    /// completion for a camera that is already where it is asked to go.
    func start(fit: (_ animated: Bool) -> Void) {
        guard let map else { return end() }
        map.addSubview(plane)
        let animated = !UIAccessibility.isReduceMotionEnabled
        fit(animated)
        after(animated ? Self.cameraTime : 0) { [weak self] in
            guard let self, let map = self.map else { return }
            guard let focus = self.route.focus, let box = Self.bounds(focus) else { return self.fly() }
            let ne = box.ne, sw = box.sw
            if ne.latitude - sw.latitude < 0.002 && ne.longitude - sw.longitude < 0.002 {
                map.setCenter(CLLocationCoordinate2D(latitude: (ne.latitude + sw.latitude) / 2,
                                                     longitude: (ne.longitude + sw.longitude) / 2),
                              zoomLevel: 14, animated: animated)
            } else {
                map.setVisibleCoordinateBounds(box, edgePadding: UIEdgeInsets(top: 60, left: 50, bottom: 70, right: 50),
                                               animated: animated, completionHandler: nil)
            }
            self.after(animated ? Self.cameraTime : 0) { [weak self] in self?.fly() }
        }
    }

    /// Runs [work] after [delay] unless the flight has ended by then.
    private func after(_ delay: TimeInterval, _ work: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, !self.finished else { return }
            work()
        }
    }

    /// How long MapLibre's animated camera change takes, and a little more.
    private static let cameraTime: TimeInterval = 0.5

    private func fly() {
        guard !finished, link == nil else { return }
        if UIAccessibility.isReduceMotionEnabled {
            land()
            return
        }
        started = CACurrentMediaTime()
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    @objc private func tick() {
        let progress = min(1, (CACurrentMediaTime() - started) / Self.duration)
        place(at: route.position(progress))
        if progress >= 1 {
            link?.invalidate()
            link = nil
            land()
        }
    }

    private func land() {
        plane.shape = .heart
        plane.transform = .identity
        place(at: route.position(1), rotate: false)
        plane.alpha = 1
        let work = DispatchWorkItem { [weak self] in self?.end() }
        landing = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.landingPause, execute: work)
    }

    private func place(at position: RouteMotion.Position?, rotate: Bool = true) {
        guard let map, let position else { return }
        let point = map.convert(position.coordinate, toPointTo: map)
        plane.center = point
        if rotate {
            plane.alpha = position.alpha
            let degrees = position.bearing - map.direction
            plane.transform = CGAffineTransform(rotationAngle: degrees * .pi / 180)
        }
    }

    func cancel() { end() }

    private func end() {
        guard !finished else { return }
        finished = true
        link?.invalidate()
        link = nil
        landing?.cancel()
        plane.removeFromSuperview()
        onEnd?()
    }

    private static func bounds(_ points: [MapLine.Position]) -> MLNCoordinateBounds? {
        guard let minLat = points.map(\.latitude).min(), let maxLat = points.map(\.latitude).max(),
              let minLon = points.map(\.longitude).min(), let maxLon = points.map(\.longitude).max() else { return nil }
        return MLNCoordinateBounds(sw: CLLocationCoordinate2D(latitude: minLat, longitude: minLon),
                                   ne: CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon))
    }
}
