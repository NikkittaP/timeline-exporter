import SwiftUI

/// The three small surprises, the twins of the Android JourneyIllustration.kt
/// and found in the same places: a long press on the icon beside the name
/// (the snail), seven taps on the name (the postcard), five taps on the
/// Distance figure (the plane round the globe).

/// Counts quick taps: true on the [count]th tap within [window] seconds of the first.
struct TapSequence {
    let count: Int
    let window: TimeInterval
    private var first: TimeInterval = 0
    private var taps = 0

    init(count: Int, window: TimeInterval) {
        self.count = count
        self.window = window
    }

    mutating func tap(now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Bool {
        if taps == 0 || now < first || now - first > window {
            first = now
            taps = 0
        }
        taps += 1
        guard taps >= count else { return false }
        taps = 0
        return true
    }
}

enum JourneyScene: Identifiable, Equatable {
    case distance(meters: Double)
    case traveler
    case postcard

    var id: String {
        switch self {
        case .distance: "distance"
        case .traveler: "traveler"
        case .postcard: "postcard"
        }
    }

    var duration: TimeInterval {
        if case .traveler = self { return 4 }
        return 5
    }

    var message: String {
        switch self {
        case .distance(let meters): L.f("journey_distance", Journey.ratio(meters))
        case .traveler: L.s("journey_traveler")
        case .postcard: L.s("journey_postcard")
        }
    }
}

enum Journey {
    /// How many times round the Earth: two decimals, or as many as it takes
    /// for a short distance to show more than a zero.
    static func ratio(_ meters: Double, locale: Locale = .current) -> String {
        let ratio = meters / 40_075_000
        var decimals = 2
        if ratio > 0, ratio < 0.01 {
            decimals = min(12, max(2, -Int(floor(log10(ratio))) + 1))
        }
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = decimals
        return formatter.string(from: NSNumber(value: ratio)) ?? String(ratio)
    }
}

/// The scene, a short animation over a 300 × 150 drawing, and its line.
struct JourneySheet: View {
    let scene: JourneyScene
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start = Date()
    @State private var height: CGFloat = 300

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            let progress = reduceMotion ? 1 : min(1, max(0, context.date.timeIntervalSince(start) / scene.duration))
            VStack(spacing: 16) {
                Canvas { gc, size in
                    JourneyDrawing(scene: scene, progress: progress, blue: .accentColor).draw(in: &gc, size: size)
                }
                .frame(height: 150)
                .frame(maxWidth: .infinity)
                .accessibilityHidden(true)
                Text(scene.message)
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .opacity(textOpacity(progress))
            }
        }
        .padding(24)
        .padding(.top, 8)
        .frame(idealWidth: 400)
        .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) { height = $0 }
        .presentationDetents([.height(height)])
        .presentationDragIndicator(.visible)
        .modifier(FittedSheet())
        .onAppear { start = Date() }
    }

    /// The postcard's line arrives with the plane, as on Android.
    private func textOpacity(_ progress: Double) -> Double {
        guard case .postcard = scene else { return 1 }
        return min(1, max(0, (progress - 0.8) / 0.2))
    }
}

/// A small card on an iPad rather than a tall form sheet with the scene at its top.
private struct FittedSheet: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 18, *) {
            content.presentationSizing(.form.fitted(horizontal: false, vertical: true))
        } else {
            content
        }
    }
}

/// The drawings, line for line the Android Canvas code in its 300 × 150 units.
private struct JourneyDrawing {
    let scene: JourneyScene
    let progress: Double
    let blue: Color

    private var pale: Color { blue.opacity(0.16) }
    private static let paper = Color(red: 1, green: 0.976, blue: 0.941)        // #FFF9F0
    private static let snailBody = Color(red: 1, green: 0.871, blue: 0.722)    // #FFDEB8
    private static let snailShell = Color(red: 0.898, green: 0.714, blue: 0.478) // #E5B67A

    func draw(in gc: inout GraphicsContext, size: CGSize) {
        let sx = size.width / 300
        let sy = size.height / 150
        var g = gc
        if case .distance = scene {
            // The globe keeps its proportions; the other scenes stretch as on Android.
            let s = min(sx, sy)
            g.translateBy(x: (size.width - 300 * s) / 2, y: (size.height - 150 * s) / 2)
            g.scaleBy(x: s, y: s)
        } else {
            g.scaleBy(x: sx, y: sy)
        }
        switch scene {
        case .distance: drawDistance(&g)
        case .traveler:
            var line = Path()
            line.move(to: CGPoint(x: 25, y: 123))
            line.addLine(to: CGPoint(x: 275, y: 123))
            g.stroke(line, with: .color(blue.opacity(0.45)), style: StrokeStyle(lineWidth: 2, dash: [4, 7]))
            drawSnail(&g, center: CGPoint(x: 75 + 100 * progress, y: 105))
        case .postcard: drawPostcard(&g)
        }
    }

    private func drawDistance(_ g: inout GraphicsContext) {
        let c = CGPoint(x: 150, y: 73)
        let globe = Path(ellipseIn: CGRect(x: 98, y: 21, width: 104, height: 104))
        g.fill(globe, with: .color(pale))
        g.stroke(globe, with: .color(blue), lineWidth: 2)
        var land = g
        land.clip(to: globe)
        land.translateBy(x: c.x, y: c.y)
        land.rotate(by: .degrees(progress * 14))
        land.translateBy(x: -c.x, y: -c.y)
        let fill = GraphicsContext.Shading.color(blue.opacity(0.42))
        land.fill(Self.polygon([(104, 48), (113, 37), (130, 33), (140, 40), (136, 46), (128, 47), (130, 53), (124, 59),
                                (127, 65), (134, 67), (140, 74), (138, 82), (132, 87), (130, 99), (125, 108), (120, 97),
                                (117, 87), (118, 77), (111, 68), (103, 61)]), with: fill)
        land.fill(Self.polygon([(151, 38), (161, 30), (180, 33), (196, 43), (201, 55), (193, 60), (184, 56), (177, 61),
                                (171, 57), (166, 62), (159, 57), (151, 59), (146, 52), (151, 47)]), with: fill)
        land.fill(Self.polygon([(149, 63), (159, 61), (170, 67), (174, 75), (168, 84), (164, 96), (158, 104), (151, 94),
                                (146, 82), (143, 72)]), with: fill)
        land.fill(Path(ellipseIn: CGRect(x: 181, y: 89, width: 13, height: 8)), with: fill)

        let orbit = Path(ellipseIn: CGRect(x: c.x - 67, y: c.y - 67, width: 134, height: 134))
        g.stroke(orbit, with: .color(blue.opacity(0.5)), style: StrokeStyle(lineWidth: 1.5, dash: [4, 6]))
        let a = progress * 2 * .pi - 1.57
        drawPlane(&g, at: CGPoint(x: c.x + 67 * cos(a), y: c.y + 67 * sin(a)), size: 17, degrees: progress * 360 + 90)
    }

    private func drawPostcard(_ g: inout GraphicsContext) {
        drawSnail(&g, center: CGPoint(x: 68, y: 111))
        var h = g
        h.translateBy(x: 40, y: 0)
        let heart = Self.heart
        let heartProgress = min(1, max(0, (progress - 0.2) / 0.8))
        h.stroke(heart.prefix(heartProgress), with: .color(blue.opacity(0.55)),
                 style: StrokeStyle(lineWidth: 2, dash: [4, 6]))
        let entry = min(1, progress / 0.2)
        let at: CGPoint
        let tangent: CGVector
        if progress < 0.2 {
            at = CGPoint(x: 30 + 120 * entry, y: 42 + 13 * entry)
            tangent = CGVector(dx: 1, dy: 0)
        } else {
            let on = heart.position(heartProgress)
            at = on.0
            tangent = on.1
        }
        let angle = atan2(tangent.dy, tangent.dx) * 180 / .pi + 90
        drawPlane(&h, at: at, size: 24, degrees: angle, expressive: true)
    }

    private func drawPlane(_ g: inout GraphicsContext, at c: CGPoint, size s: Double, degrees: Double, expressive: Bool = false) {
        var p = g
        p.translateBy(x: c.x, y: c.y)
        p.rotate(by: .degrees(degrees))
        var body = Path()
        body.move(to: CGPoint(x: 0, y: -s / 2))
        body.addLine(to: CGPoint(x: s * 0.46, y: s * 0.42))
        body.addLine(to: CGPoint(x: 0, y: s * 0.22))
        body.addLine(to: CGPoint(x: -s * 0.46, y: s * 0.42))
        body.closeSubpath()
        p.fill(body, with: .color(Self.paper))
        p.stroke(body, with: .color(blue), lineWidth: s * 0.06)
        var spine = Path()
        spine.move(to: CGPoint(x: 0, y: -s * 0.38))
        spine.addLine(to: CGPoint(x: 0, y: s * 0.22))
        p.stroke(spine, with: .color(blue), lineWidth: s * 0.04)
        if expressive {
            let r = s * 0.035
            for x in [-s * 0.12, s * 0.12] {
                p.fill(Path(ellipseIn: CGRect(x: x - r, y: s * 0.12 - r, width: 2 * r, height: 2 * r)), with: .color(blue))
            }
        }
    }

    private func drawSnail(_ g: inout GraphicsContext, center c: CGPoint) {
        func pt(_ dx: Double, _ dy: Double) -> CGPoint { CGPoint(x: c.x + dx, y: c.y + dy) }
        func dot(_ dx: Double, _ dy: Double, _ r: Double) {
            g.fill(Path(ellipseIn: CGRect(x: c.x + dx - r, y: c.y + dy - r, width: 2 * r, height: 2 * r)), with: .color(blue))
        }
        func line(_ a: CGPoint, _ b: CGPoint, _ w: Double) {
            var l = Path()
            l.move(to: a)
            l.addLine(to: b)
            g.stroke(l, with: .color(blue), lineWidth: w)
        }
        var body = Path()
        body.move(to: pt(-35, 16))
        body.addQuadCurve(to: pt(34, 5), control: pt(25, 25))
        body.addLine(to: pt(32, -25))
        body.addQuadCurve(to: pt(18, -26), control: pt(28, -38))
        body.addLine(to: pt(14, 3))
        body.closeSubpath()
        g.fill(body, with: .color(Self.snailBody))
        g.stroke(body, with: .color(blue), lineWidth: 2.5)

        let shell = Path(ellipseIn: CGRect(x: c.x - 8 - 24, y: c.y - 4 - 24, width: 48, height: 48))
        g.fill(shell, with: .color(Self.snailShell))
        g.stroke(shell, with: .color(blue), lineWidth: 2.5)
        var spiral = Path()
        spiral.addArc(center: pt(-8, -4), radius: 13, startAngle: .degrees(45), endAngle: .degrees(325), clockwise: false)
        g.stroke(spiral, with: .color(blue), lineWidth: 2)

        // The little backpack with its map.
        let pack = Path(roundedRect: CGRect(x: c.x - 26, y: c.y - 38, width: 34, height: 20), cornerRadius: 6)
        g.fill(pack, with: .color(pale))
        g.stroke(pack, with: .color(blue), lineWidth: 2)
        line(pt(-23, -29), pt(5, -29), 1.5)
        g.stroke(Path(roundedRect: CGRect(x: c.x - 12, y: c.y - 31, width: 5, height: 5), cornerRadius: 1),
                 with: .color(blue), lineWidth: 1.5)

        line(pt(21, -28), pt(17, -43), 2)
        line(pt(29, -29), pt(34, -43), 2)
        dot(17, -43, 2)
        dot(34, -43, 2)
        dot(26, -20, 1.8)
    }

    private static func polygon(_ points: [(Double, Double)]) -> Path {
        var p = Path()
        p.addLines(points.map { CGPoint(x: $0.0, y: $0.1) })
        p.closeSubpath()
        return p
    }

    /// The heart the plane flies, as a polyline so a point and a direction
    /// can be found at any share of its length.
    private static let heart: Polyline = {
        var points: [CGPoint] = [CGPoint(x: 150, y: 55)]
        func cubic(_ p0: CGPoint, _ p1: CGPoint, _ p2: CGPoint, _ p3: CGPoint) {
            for i in 1...48 {
                let t = Double(i) / 48, u = 1 - t
                let a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t
                points.append(CGPoint(x: a * p0.x + b * p1.x + c * p2.x + d * p3.x,
                                      y: a * p0.y + b * p1.y + c * p2.y + d * p3.y))
            }
        }
        cubic(CGPoint(x: 150, y: 55), CGPoint(x: 122, y: 24), CGPoint(x: 85, y: 55), CGPoint(x: 120, y: 93))
        points.append(CGPoint(x: 150, y: 118))
        points.append(CGPoint(x: 180, y: 93))
        cubic(CGPoint(x: 180, y: 93), CGPoint(x: 215, y: 55), CGPoint(x: 178, y: 24), CGPoint(x: 150, y: 55))
        return Polyline(points)
    }()
}

private struct Polyline {
    let points: [CGPoint]
    /// Distance from the start to each point.
    let lengths: [Double]

    init(_ points: [CGPoint]) {
        self.points = points
        var total = 0.0
        var lengths = [0.0]
        for i in 1..<points.count {
            total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            lengths.append(total)
        }
        self.lengths = lengths
    }

    var length: Double { lengths.last ?? 0 }

    /// The segment the target distance falls in and how far along it.
    private func locate(_ fraction: Double) -> (index: Int, t: Double) {
        let target = length * min(1, max(0, fraction))
        var i = 1
        while i < points.count - 1 && lengths[i] < target { i += 1 }
        let span = lengths[i] - lengths[i - 1]
        return (i, span > 0 ? (target - lengths[i - 1]) / span : 0)
    }

    func prefix(_ fraction: Double) -> Path {
        var path = Path()
        guard fraction > 0 else { return path }
        let (i, t) = locate(fraction)
        path.addLines(Array(points[..<i]))
        path.addLine(to: Self.lerp(points[i - 1], points[i], t))
        return path
    }

    func position(_ fraction: Double) -> (CGPoint, CGVector) {
        let (i, t) = locate(fraction)
        let a = points[i - 1], b = points[i]
        return (Self.lerp(a, b, t), CGVector(dx: b.x - a.x, dy: b.y - a.y))
    }

    private static func lerp(_ a: CGPoint, _ b: CGPoint, _ t: Double) -> CGPoint {
        CGPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t)
    }
}
