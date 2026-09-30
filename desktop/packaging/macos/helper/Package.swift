// swift-tools-version: 6.0
// asom-mac-helper (PLATFORM_PLAN section 5, step MC2). Two targets, as macos.md 10.1 lists them:
//   HelperProtocol   pure Swift, no Foundation, no Apple framework: the JSON-Lines codec of helper-protocol/SCHEMA.md, the request
//                    dispatcher and the fixture machine. Builds and is TESTED ON LINUX.
//   asom-mac-helper  the executable. Every file that touches an Apple framework is inside `#if os(macOS)`, so `swift build` on
//                    Linux still succeeds (main.swift then reports "macOS only" and exits 69). The macOS half has NEVER been
//                    compiled by its author (no macOS here); the macos-latest job of desktop-macos.yml is its first compile.
import PackageDescription

let package = Package(
    name: "asom-mac-helper",
    platforms: [.macOS(.v15)],
    products: [
        .library(name: "HelperProtocol", targets: ["HelperProtocol"]),
        .executable(name: "asom-mac-helper", targets: ["asom-mac-helper"]),
    ],
    targets: [
        .target(name: "HelperProtocol"),
        .executableTarget(
            name: "asom-mac-helper",
            dependencies: ["HelperProtocol"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(name: "HelperTests", dependencies: ["HelperProtocol"]),
    ]
)
