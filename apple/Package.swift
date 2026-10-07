// swift-tools-version:6.0
// asom Apple lane, halves I0a and I0b (PLATFORM_PLAN.md section 6). Ships nothing. UNSIGNED, LAB.
// ES256 only. An independent Swift implementation of the LAB_SPEC.md section 4 profile; never linked into the Mac node.
import PackageDescription

let package = Package(
    name: "AsomKit",
    platforms: [.macOS(.v15), .iOS(.v17)],
    products: [
        .library(name: "AsomJSON", targets: ["AsomJSON"]),
        .library(name: "AsomDSSE", targets: ["AsomDSSE"]),
        .library(name: "AsomBenchCore", targets: ["AsomBenchCore"]),
        .library(name: "AsomManifest", targets: ["AsomManifest"]),
        .library(name: "AsomRouterCore", targets: ["AsomRouterCore"]),
        .executable(name: "asom-conformance", targets: ["asom-conformance"]),
    ],
    dependencies: [
        // Linux only. Apple builds use the system CryptoKit, so the shipped app carries no third-party crypto.
        .package(url: "https://github.com/apple/swift-crypto.git", "3.15.1"..<"5.0.0"),
    ],
    targets: [
        .target(name: "AsomJSON"),
        .target(
            name: "AsomDSSE",
            dependencies: [
                "AsomJSON",
                .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux])),
            ]
        ),
        .target(name: "AsomBenchCore", dependencies: ["AsomJSON", "AsomDSSE"]),
        .target(name: "AsomManifest", dependencies: ["AsomJSON", "AsomDSSE", "AsomBenchCore"]),
        .target(name: "AsomRouterCore", dependencies: ["AsomJSON", "AsomBenchCore"]),
        .target(name: "AsomConformanceKit", dependencies: ["AsomJSON", "AsomDSSE", "AsomBenchCore", "AsomManifest", "AsomRouterCore"]),
        .executableTarget(name: "asom-conformance", dependencies: ["AsomConformanceKit"]),
        .testTarget(name: "AsomJSONTests", dependencies: ["AsomJSON"]),
        .testTarget(name: "AsomDSSETests", dependencies: ["AsomJSON", "AsomDSSE"]),
        .testTarget(name: "AsomBenchCoreTests", dependencies: ["AsomBenchCore", "AsomJSON", "AsomDSSE"]),
        .testTarget(name: "AsomManifestTests", dependencies: ["AsomManifest", "AsomBenchCore", "AsomDSSE", "AsomJSON"]),
        .testTarget(name: "AsomRouterCoreTests", dependencies: ["AsomRouterCore", "AsomBenchCore", "AsomJSON"]),
        .testTarget(name: "AsomConformanceTests", dependencies: ["AsomConformanceKit", "AsomJSON", "AsomDSSE", "AsomBenchCore", "AsomManifest"]),
    ]
)
