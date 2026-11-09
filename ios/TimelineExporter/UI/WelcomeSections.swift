import SwiftUI

/// The first screen, before any file is open: what the app does and the one
/// thing to do next, then what it needs from Google and how to get the file.
/// Written for someone who has never heard of a JSON file — the App Store
/// audience. The full steps are in HelpSheet.
struct WelcomeSections: View {
    @Environment(TimelineStore.self) private var store
    @Binding var importing: Bool
    let showHelp: () -> Void

    var body: some View {
        Section {
            hero
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
        }

        Section {
            sampleButton
        } footer: {
            Text(L.md("ios_welcome_not_tracker"))
        }

        Section {
            Requirements()
        } header: {
            StepHeader(title: L.s("ios_requirements_title"))
        }

        Section {
            NumberedSteps(keys: ["ios_quick_step1", "ios_quick_step2", "ios_quick_step3_top"])
        } header: {
            StepHeader(title: L.s("ios_quick_title"))
        }
    }

    private var hero: some View {
        VStack(spacing: 14) {
            Image("BrandIcon")
                .resizable()
                .scaledToFit()
                .frame(width: 64, height: 64)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .accessibilityHidden(true)
            Text(L.s("ios_empty_title"))
                .font(.title2.bold())
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(L.s("ios_empty_body"))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let failure {
                Label {
                    Text(failure)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
                .font(.callout)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button {
                importing = true
            } label: {
                Label(L.s("pick_file_button"), systemImage: "doc.badge.plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityIdentifier("pickFile")
            Button(action: showHelp) {
                Label(L.s("ios_how_to_get_data"), systemImage: "questionmark.circle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .accessibilityIdentifier("fullGuide")
        }
        .frame(maxWidth: .infinity)
    }

    /// Why the last file did not open: the screen stays, it is needed most then.
    private var failure: String? {
        guard case .failed(let wrongFile, let errorClass, let message) = store.phase else { return nil }
        if wrongFile { return L.s("error_not_timeline") }
        return L.f("parse_error", errorClass, message ?? L.s("parse_error_no_message"))
    }

    /// Made-up history: what the app does, before the effort of an export.
    private var sampleButton: some View {
        Button {
            Task { await store.openSample() }
        } label: {
            Label {
                OptionLabel(title: L.s("ios_try_sample_button"), hint: L.s("ios_sample_hint"))
            } icon: {
                Image(systemName: "wand.and.stars")
            }
        }
        .accessibilityIdentifier("trySample")
    }
}

/// The conditions, each with a warning sign: shown on the first screen and at
/// the top of Help.
struct Requirements: View {
    var withDevice = false

    var body: some View {
        ForEach(keys, id: \.self) { key in
            Label {
                Text(L.md(key))
            } icon: {
                Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.orange)
            }
            .padding(.vertical, 2)
        }
    }

    private var keys: [String] {
        let base = ["ios_requirement_account", "ios_requirement_timeline", "ios_requirement_location"]
        return withDevice ? base + ["ios_requirement_device"] : base
    }
}

/// 1, 2, 3… beside each step. Steps may carry **bold** Markdown.
struct NumberedSteps: View {
    let keys: [String]

    var body: some View {
        ForEach(Array(keys.enumerated()), id: \.offset) { index, key in
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text("\(index + 1)")
                    .font(.callout.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 18)
                Text(L.md(key))
            }
        }
    }
}

/// Keeps the one-column Form readable on an iPad: a centred column instead of
/// lines the width of a 13" screen. Phones are narrower than the limit.
///
/// Margins inside the scroll view rather than a narrower one: the system bar
/// over a scrolled page is as wide as the scroll view, and a 720 pt one showed
/// as a hard-edged box in the middle of the window.
struct ReadableColumn: ViewModifier {
    static let maxWidth: CGFloat = 720

    @State private var width: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .contentMargins(.horizontal, max(0, (width - Self.maxWidth) / 2), for: .scrollContent)
            .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width = $0 }
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
    }
}
