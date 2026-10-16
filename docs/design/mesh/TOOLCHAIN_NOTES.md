# Build-environment toolchain facts (probed 2026-09-30, this container)

Verified by running commands, not recalled:

- OS: Ubuntu 24.04.4 LTS, x86_64. JDK 21.0.10 locally (CI pins JDK 17 via setup-java). `jpackage` present (21.0.10).
- Gradle wrapper 8.14.3; Kotlin 2.1.21; AGP 8.7.3; Ktor 3.1.3. NO Android SDK locally (ANDROID_HOME unset).
- Swift 6.1 (swift-6.1-RELEASE, x86_64-unknown-linux-gnu) installed at /opt/swift; use `export PATH=/opt/swift/usr/bin:$PATH`.
  SwiftPM resolves GitHub packages through the proxy: `swift-crypto` 3.x fetched and an Ed25519
  (Curve25519.Signing) sign/verify XCTest PASSED on Linux. Apple code must `#if canImport(CryptoKit) import CryptoKit #else import Crypto #endif`
  so the same source tests here (swift-crypto) and on macos-latest runners (CryptoKit). Secure Enclave APIs do not exist on Linux;
  keep them behind `#if os(iOS) || os(macOS)` and test only in CI/device.
- Docker: client present, DAEMON UNAVAILABLE. Therefore `clickable` (8.10.0, installed via pip) cannot build click packages here;
  Ubuntu Touch builds are CI-only (ubuntu-latest has Docker; use the clickable/ci images). Validate the click manifest, apparmor JSON
  and QML syntax locally with plain tools (python json, qmllint if installed — probably not).
- Rust 1.94.1 with x86_64-unknown-linux-gnu only; gcc 13.3; cmake 3.28 — a native (C/C++/Rust) core could be compiled and unit-tested
  here for Linux x86_64 only.
- Node 22, Python 3.11.
- Disk: ~30 GB free. Network: HTTPS via the agent proxy; GitHub and swift.org reachable.
