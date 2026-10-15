import Foundation

/// The answer text as the claim tracker counts it (LAB_SPEC.md R4-L-19, R3-OVERCLAIM-1): NFC; Unicode `Cf` (format) and `Cc` (control)
/// characters removed; every run of space separators (`Zs`, `Zl`, `Zp`) collapsed to one U+0020; trimmed. The spec's order is read
/// literally, so a tab or a newline (both `Cc`) is removed and never collapsed to a space (apple/ERRATA.md ERR-R4B-TR-1), and the
/// result is what `outBytes` measures. The text is the whole answer: a whitespace run, a zero-width character or a combining mark may
/// straddle two chunks, so callers join the chunks before they normalise.
///
/// The general categories come from the Unicode tables of the running Swift runtime; a character assigned after those tables were
/// built is a letter or a symbol to this lane and may be a `Cf` to another (ERR-R4B-TR-2).
public enum AnswerText {
    public static func normalize(_ text: String) -> String {
        let composed = text.precomposedStringWithCanonicalMapping
        var out = String.UnicodeScalarView()
        var pendingSpace = false
        for scalar in composed.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .control, .format:
                continue
            case .spaceSeparator, .lineSeparator, .paragraphSeparator:
                pendingSpace = true
            default:
                if pendingSpace && !out.isEmpty { out.append(" ") }
                pendingSpace = false
                out.append(scalar)
            }
        }
        return String(out)
    }

    /// The UTF-8 length of the normalised text.
    public static func normalizedByteCount(_ text: String) -> Int64 {
        Int64(normalize(text).utf8.count)
    }
}
