// swift-tools-version:5.9
// SPIKE S-A10 (partial, Linux): swift-certificates parses a P-256 chain and verifies the leaf under the NIK.
import PackageDescription
let package = Package(
    name: "CertSpike",
    platforms: [.macOS(.v14), .iOS(.v17)],
    dependencies: [.package(url: "https://github.com/apple/swift-certificates.git", "1.0.0" ..< "2.0.0")],
    targets: [.executableTarget(name: "CertSpike",
        dependencies: [.product(name: "X509", package: "swift-certificates")])]
)
