// UNVERIFIED: written on Linux, never compiled (no macOS here). The macos-latest job of desktop-macos.yml is its first compile.
#if os(macOS)
import Foundation
import HelperProtocol

/// Reads stdin in chunks and hands them to the (Linux-tested) LineSplitter. Returns nil at EOF or on a read error.
final class LineReader {
    private let fd: Int32
    private var splitter = LineSplitter()
    private var queue = [LineSplitter.Item]()
    private var eof = false

    init(fd: Int32) {
        self.fd = fd
    }

    func next() -> LineSplitter.Item? {
        while queue.isEmpty {
            if eof { return nil }
            var buf = [UInt8](repeating: 0, count: 65_536)
            let n = read(fd, &buf, buf.count)
            if n < 0 {
                if errno == EINTR { continue }
                eof = true
                return nil
            }
            if n == 0 {
                eof = true
                return nil
            }
            queue.append(contentsOf: splitter.feed(buf[0..<n]))
        }
        return queue.removeFirst()
    }
}

/// Serialises everything written to stdout: responses from the main thread and events from the IOKit thread.
final class OutputWriter {
    private let lock = NSLock()
    private var broken = false

    /// Writes `bytes` and an LF. False once stdout is closed (the node has gone).
    @discardableResult
    func write(_ bytes: [UInt8]) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if broken { return false }
        var out = bytes
        out.append(0x0A)
        var offset = 0
        while offset < out.count {
            let n = out.withUnsafeBytes { raw -> Int in
                Darwin.write(1, raw.baseAddress! + offset, out.count - offset)
            }
            if n < 0 {
                if errno == EINTR { continue }
                broken = true
                return false
            }
            offset += n
        }
        return true
    }

    func emit(_ event: Event) {
        guard let line = try? Codec.encode(event) else { return }
        write(line)
    }
}

enum HelperMain {
    static func run(arguments: [String]) -> Int32 {
        guard arguments.count == 2, arguments[1] == "serve" else {
            fputs("usage: asom-mac-helper serve\n", stderr)
            return 64
        }
        // A closed stdout is an EPIPE on write, handled in OutputWriter; the process must not die of SIGPIPE before it releases anything.
        signal(SIGPIPE, SIG_IGN)
        let writer = OutputWriter()
        let backend = MacBackend(emit: { writer.emit($0) })
        let dispatcher = Dispatcher(backend: backend)
        backend.startEvents()
        let reader = LineReader(fd: 0)
        while let item = reader.next() {
            let response: [UInt8]
            switch item {
            case .line(let bytes): response = dispatcher.handle(line: bytes)
            case .overlong: response = dispatcher.handleOverlongLine()
            }
            if !writer.write(response) { break }
        }
        // stdin EOF (the node exited or closed the pipe): release the assertion; the process then exits and IOKit would release it anyway (AM11).
        dispatcher.shutdown()
        return 0
    }
}
#endif
