import SwiftUI

/// The first screen, before any file is open: what the app does, what it
/// needs from Google, and how to get the file. Written for someone who has
/// never heard of a JSON file — the App Store audience — so the conditions a
/// user can't fix from this app (a Google account, Timeline turned on) come
/// first and in bold. The full steps are in HelpSheet.
struct WelcomeSections: View {
    let showHelp: () -> Void

    var body: some View {
        Section {
            Label {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L.md("ios_welcome_body"))
                    Text(L.md("ios_welcome_not_tracker"))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "map").foregroundStyle(.tint)
            }
            .padding(.vertical, 4)
        } header: {
            Text(L.s("ios_welcome_title"))
        }

        Section {
            Requirements()
        } header: {
            Text(L.s("ios_requirements_title"))
        }

        Section {
            NumberedSteps(keys: ["ios_quick_step1", "ios_quick_step2", "ios_quick_step3"])
            Button(action: showHelp) {
                Label(L.s("ios_full_guide_button"), systemImage: "questionmark.circle")
            }
            .accessibilityIdentifier("fullGuide")
        } header: {
            Text(L.s("ios_quick_title"))
        }
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
struct ReadableColumn: ViewModifier {
    static let maxWidth: CGFloat = 720

    func body(content: Content) -> some View {
        content
            .frame(maxWidth: Self.maxWidth)
            .frame(maxWidth: .infinity)
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
    }
}
