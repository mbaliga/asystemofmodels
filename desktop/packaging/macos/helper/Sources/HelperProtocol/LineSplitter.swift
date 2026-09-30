// LF framing for the helper's stdin (SCHEMA section 1), pure so it is tested on Linux: a line longer than `maxLine` bytes
// is discarded up to its LF (memory stays bounded) and reported as `.overlong`; a final partial line at EOF is never reported.

public struct LineSplitter {
    public enum Item: Equatable {
        case line([UInt8])
        case overlong
    }

    private let maxLine: Int
    private var current = [UInt8]()
    private var discarding = false

    public init(maxLine: Int = ProtocolSpec.maxLineBytes) {
        self.maxLine = maxLine
    }

    /// Bytes buffered for the line in progress (never more than `maxLine`).
    public var bufferedCount: Int { current.count }

    public mutating func feed<S: Sequence>(_ bytes: S) -> [Item] where S.Element == UInt8 {
        var items = [Item]()
        for b in bytes {
            if b == 0x0A {
                items.append(discarding ? .overlong : .line(current))
                discarding = false
                current = []
            } else if !discarding {
                current.append(b)
                if current.count > maxLine {
                    discarding = true
                    current = []
                }
            }
        }
        return items
    }
}
