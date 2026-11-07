import Foundation

/// Localized strings. Keys are the Android resource names: Localizable.xcstrings
/// is generated from app/src/main/res/values*/strings.xml (plus the iOS-only
/// ios/strings) by tools/ios/strings_to_xcstrings.py, never edited by hand.
enum L {
    static func s(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    /// A string with arguments. Android's %1$s arrives as %1$@ (pass a
    /// String), %1$d as %1$lld (pass an Int).
    static func f(_ key: String, _ args: CVarArg...) -> String {
        String(format: NSLocalizedString(key, comment: ""), locale: Locale.current, arguments: args)
    }

    /// A string whose **bold** parts are Markdown: the iOS-only explanations
    /// (ios/strings) mark the words a user must not miss that way.
    static func md(_ key: String) -> AttributedString {
        let text = NSLocalizedString(key, comment: "")
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
    }

    /// A plural: the form is chosen by [count], which is also the number shown.
    static func plural(_ key: String, _ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), count)
    }
}
