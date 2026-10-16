// asom-mac-helper (macos.md 3.5). `asom-mac-helper serve` reads JSON Lines requests on stdin and writes responses and events on
// stdout (helper-protocol/SCHEMA.md). Everything that needs an Apple framework is in files guarded by `#if os(macOS)`; on any
// other system this executable only says so, which keeps `swift build` green on Linux for the protocol target's sake.
#if os(macOS)
import Foundation

exit(HelperMain.run(arguments: CommandLine.arguments))
#else
#if canImport(Glibc)
import Glibc
#endif

fputs("asom-mac-helper: this executable is for macOS only\n", stderr)
exit(69)
#endif
