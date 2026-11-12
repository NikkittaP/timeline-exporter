import SwiftUI

/// The few values the screens share, so a card, a chip and a map corner agree.
/// Colours stay semantic (system fills, the accent colour); only the place and
/// selected-place colours are fixed, because they mean the same on the map.
enum Design {
    static let cardRadius: CGFloat = 12
    static let controlRadius: CGFloat = 10
    /// Apple's minimum comfortable touch target.
    static let minTarget: CGFloat = 44
    static let mapHeightCompact: CGFloat = 340
    static let mapHeightRegular: CGFloat = 460
    static let selectedRowOpacity = 0.12
    /// The trip shown on the map. A trip marked for export has no tint: its
    /// filled mark says so, and the two states must not look alike.
    static let previewedRowOpacity = 0.16
    /// The map's floating day strip on a wide screen.
    static let dayStripMaxWidth: CGFloat = 640
    static let placeColorHex = "#D55E00"
    /// A main action's label: wide enough to read as the thing to do, never
    /// edge to edge on a phone or a long bar on an iPad.
    static let actionLabelMaxWidth: CGFloat = 280
    static let selectedPlaceHex = "#0072B2"
}

/// When the loaded page puts its map in a pane of its own beside the steps.
/// Decided on the width the window has, so a narrow Stage Manager or Split
/// View window stacks like a phone. The size class keeps out the widest
/// phones; a 13" iPad upright (1032 pt) stays stacked too.
enum PageLayout {
    /// As wide as a large phone: every row already fits that.
    static let sidebarMinWidth: CGFloat = 420
    static let sidebarMaxWidth: CGFloat = 500
    static let mapMinWidth: CGFloat = 620
    static let sidebarShare: CGFloat = 0.36

    static func isSplit(width: CGFloat, sizeClass: UserInterfaceSizeClass?) -> Bool {
        sizeClass == .regular && width >= sidebarMinWidth + mapMinWidth
    }

    static func sidebarWidth(pageWidth: CGFloat) -> CGFloat {
        min(sidebarMaxWidth, max(sidebarMinWidth, pageWidth * sidebarShare))
    }
}

extension View {
    /// The surface of a control floating over the map: Liquid Glass where the
    /// system has it, which also answers Reduce Transparency and Increase
    /// Contrast by itself, and the blur it replaces where it does not.
    @ViewBuilder
    func mapControlSurface(cornerRadius: CGFloat, interactive: Bool = false) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius)
        if #available(iOS 26, *) {
            glassEffect(Glass.regular.interactive(interactive), in: shape)
        } else {
            background(.regularMaterial, in: shape)
        }
    }
}

extension View {
    /// A main action's label at a comfortable width; the button is centred
    /// in what is left of the row.
    func actionLabelWidth() -> some View {
        frame(maxWidth: Design.actionLabelMaxWidth)
    }
}

/// A step's heading in the Form: readable as a heading, not a grey caption.
struct StepHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.headline)
            .foregroundStyle(.primary)
            .textCase(nil)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A switch's or a row's title with its explanation under it.
struct OptionLabel: View {
    let title: String
    let hint: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(hint).font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// One choice of a short row of presets.
struct PresetChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .monospacedDigit()
                .frame(minHeight: 30)
        }
        .buttonStyle(.bordered)
        .tint(selected ? Color.accentColor : Color.secondary)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A figure with its name: the file summary's Points, Days and Distance.
struct MetricTile: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.title3.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// A small picture of what the Map image makes: a route on a light map,
/// from its start dot to its pin, with the stats card along the bottom.
/// Drawn, so it follows the accent colour and the appearance.
struct MapImageThumbnail: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        Canvas { gc, size in
            let w = size.width, h = size.height
            func p(_ x: Double, _ y: Double) -> CGPoint { CGPoint(x: x * w, y: y * h) }

            // A few streets, barely there.
            var streets = Path()
            streets.move(to: p(0, 0.30)); streets.addLine(to: p(1, 0.18))
            streets.move(to: p(0.30, 0)); streets.addLine(to: p(0.42, 1))
            streets.move(to: p(0.70, 0)); streets.addLine(to: p(0.62, 1))
            streets.move(to: p(0, 0.62)); streets.addLine(to: p(1, 0.70))
            gc.stroke(streets, with: .color(.primary.opacity(0.07)), lineWidth: 2)

            var route = Path()
            route.move(to: p(0.18, 0.62))
            route.addCurve(to: p(0.50, 0.36), control1: p(0.24, 0.40), control2: p(0.40, 0.52))
            route.addCurve(to: p(0.80, 0.24), control1: p(0.60, 0.20), control2: p(0.70, 0.36))
            let halo: Color = colorScheme == .dark ? .black.opacity(0.5) : .white
            gc.stroke(route, with: .color(halo), style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
            gc.stroke(route, with: .color(.accentColor), style: StrokeStyle(lineWidth: 2.6, lineCap: .round, lineJoin: .round))

            let start = p(0.18, 0.62)
            gc.fill(Path(ellipseIn: CGRect(x: start.x - 3.5, y: start.y - 3.5, width: 7, height: 7)), with: .color(halo))
            gc.fill(Path(ellipseIn: CGRect(x: start.x - 2.2, y: start.y - 2.2, width: 4.4, height: 4.4)), with: .color(.accentColor))
            if let pin = gc.resolveSymbol(id: "pin") {
                gc.draw(pin, at: p(0.80, 0.24))
            }

            // The stats card.
            let card = Path(roundedRect: CGRect(x: 0.12 * w, y: 0.76 * h, width: 0.76 * w, height: 0.14 * h), cornerRadius: 3)
            gc.fill(card, with: .color(halo.opacity(0.9)))
            for (x, len) in [(0.18, 0.16), (0.42, 0.16), (0.66, 0.16)] {
                let bar = Path(roundedRect: CGRect(x: x * w, y: 0.815 * h, width: len * w, height: 0.03 * h), cornerRadius: 1)
                gc.fill(bar, with: .color(.accentColor.opacity(0.7)))
            }
        } symbols: {
            Image(systemName: "mappin.circle.fill")
                .font(.system(size: 13, weight: .semibold))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, Color.accentColor)
                .tag("pin")
        }
        .background(
            LinearGradient(
                colors: [Color.accentColor.opacity(0.20), Color.accentColor.opacity(0.07)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        )
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(shape)
        .overlay(shape.strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5))
    }
}
