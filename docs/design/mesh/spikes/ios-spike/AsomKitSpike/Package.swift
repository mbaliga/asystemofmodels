// swift-tools-version:5.9
// SPIKE (design session 2026-09-30): proves the AsomKit manifest-crypto layer compiles and cross-checks
// on Linux with swift-crypto, and on Apple platforms with CryptoKit, from ONE source. Ships nothing.
import PackageDescription

let package = Package(
    name: "AsomKitSpike",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "AsomDSSE", targets: ["AsomDSSE"])],
    dependencies: [
        // Linux only; Apple builds use the system CryptoKit (no third-party crypto in the shipped app).
        .package(url: "https://github.com/apple/swift-crypto.git", "4.0.0" ..< "5.0.0"),
    ],
    targets: [
        .target(name: "AsomDSSE",
                dependencies: [.product(name: "Crypto", package: "swift-crypto",
                                        condition: .when(platforms: [.linux]))]),
        .testTarget(name: "AsomDSSETests", dependencies: ["AsomDSSE"]),
    ]
)
