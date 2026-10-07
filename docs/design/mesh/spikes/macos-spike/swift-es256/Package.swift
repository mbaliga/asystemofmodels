// swift-tools-version:5.9
// LAB SPIKE (ships nothing): proves the Apple conformance lane's DSSE/ES256 layer builds and agrees with JCA,
// on Linux via swift-crypto and on macOS via CryptoKit (same source).
import PackageDescription
let package = Package(
    name: "AsomDSSESpike",
    products: [.library(name: "AsomDSSE", targets: ["AsomDSSE"])],
    dependencies: [.package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"4.0.0")],
    targets: [
        .target(name: "AsomDSSE", dependencies: [.product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))]),
        .executableTarget(name: "es256check", dependencies: ["AsomDSSE"]),
        .testTarget(name: "AsomDSSETests", dependencies: ["AsomDSSE"]),
    ])
