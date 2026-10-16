import Foundation

/// The answer text as the claim tracker counts it (LAB_SPEC.md R4-L-19, R3-OVERCLAIM-1): NFC; every invisible character removed; every run
/// of space separators (`Zs`, `Zl`, `Zp`) collapsed to one U+0020; trimmed. The spec's order is read literally, so a tab or a newline (both
/// `Cc`) is removed and never collapsed to a space (lab/ERRATA.md ERR-R4B-TR-1, apple ERR-R4B-TR-14), and the result is what `outBytes`
/// measures. The text is the whole answer: a whitespace run, an invisible character or a combining mark may straddle two chunks, so callers
/// join the chunks before they normalise.
///
/// Invisible means general category `Cc` or `Cf` (from the Unicode tables of the running Swift runtime) or a code point of `invisible`, which
/// is written out so that the verdict does not follow a runtime's tables (lab/ERRATA.md ERR-FX3-TP-1): Default_Ignorable_Code_Point of
/// Unicode 15.0 (equal to the Swift 6.1 runtime's own property, testTheWrittenOutInvisibleSetCoversDefaultIgnorableCodePoints), U+2800 BRAILLE PATTERN BLANK, and the `Cf` characters assigned after Unicode 13. What remains is visible filler, the stated
/// residual of R3-OVERCLAIM-1.
///
/// `normalizedByteCount` is what `outBytes` measures: the UTF-8 length of `normalize`, but never more than the same text without NFC (NFC can
/// expand, ERR-FX3-TP-2). The bytes-per-token table is on this basis (ERR-FX3-TP-4).
///
/// A character assigned after those tables were built is a letter or a symbol to this lane and may be a `Cf` to another (lab/ERRATA.md ERR-R4B-TR-2).
public enum AnswerText {
    /// Inclusive scalar ranges, ascending.
    public static let invisible: [ClosedRange<UInt32>] = [
        0x00AD...0x00AD, 0x034F...0x034F, 0x061C...0x061C, 0x0890...0x0891, 0x115F...0x1160, 0x17B4...0x17B5, 0x180B...0x180F, 0x200B...0x200F,
        0x202A...0x202E, 0x2060...0x206F, 0x2800...0x2800, 0x3164...0x3164, 0xFE00...0xFE0F, 0xFEFF...0xFEFF, 0xFFA0...0xFFA0, 0xFFF0...0xFFF8,
        0x13430...0x1343F, 0x1BCA0...0x1BCA3, 0x1D173...0x1D17A, 0xE0000...0xE0FFF,
    ]

    static func isInvisible(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .control, .format: return true
        default: return invisible.contains { $0.contains(scalar.value) }
        }
    }

    /// Invisible characters removed, space-separator runs collapsed, trimmed; no NFC.
    public static func strip(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        var pendingSpace = false
        for scalar in text.unicodeScalars {
            if isInvisible(scalar) { continue }
            switch scalar.properties.generalCategory {
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

    public static func normalize(_ text: String) -> String {
        strip(text.precomposedStringWithCanonicalMapping)
    }

    /// The counted length: the UTF-8 length of the normalised text, never more than that of the same text without NFC.
    public static func normalizedByteCount(_ text: String) -> Int64 {
        Int64(min(normalize(text).utf8.count, strip(text).utf8.count))
    }
}
