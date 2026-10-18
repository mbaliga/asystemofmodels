# Platform section: iOS and iPadOS — the iPhone requester, the iPad foreground lender, the `AsomBench` app, and the iOS targets of the shared `apple/` Swift package

**Date:** 2026-09-30 · **Grade:** DIRECTION (an input to the reviser and to the owner's rulings D14, D15, D16, D24; nothing here authorises execution) · **Scope:** iPhone (iOS) and iPad (iPadOS) as mesh nodes, the design's phases M3 (benchmark app), M4 (requester) and M5 (iPad lender), and the Swift lane L0.7 on the iOS side.
**Target implementation directories:** `apple/` (the one shared Swift package, laid out in the macOS section §7.4; this section adds its iOS targets) and `apple/AsomBench/` (the iOS/iPadOS app).
**Reads with:** `OWNER_DIRECTIVES_2026-09-30.md` (D-A…D-F, AD-1…AD-6, all treated as decided), `ASOM_MESH_DESIGN.md` r2 (§2.1 roles, §3.1 matrix, §3.4 Swift surface, §4.2 T2/T3/T9/T13, §5.1–5.5, §7.4 LP-1/LP-2, §7.8, §8.4, §9.3 M3–M5, D14–D17, D24), `REVIEW_ROUND2.md`, and the sibling `platforms/macos.md` (which owns `apple/Package.swift`).
**Tags:** `[IFnn]` = verified in this session (§1.1, source URL, fetched 2026-09-30 unless a date is given). `[ISn]` = a spike **run** in this session (§1.2, transcript path). `[IAnn]` = assumption, not verified (§1.3). `[Fnn]`/`[Ann]` = the design brief's Appendix A/B. `[FMnn]` = a fact verified by the macOS section. `NDV` = NEEDS-DEVICE-VALIDATION. `SIGN-OFF` = needs the owner. Every performance number is an ESTIMATE unless tagged `[IF]`/`[IS]`.

**The answer in brief.**
- **No daemon, ever.** iOS suspends a backgrounded app and offers no general mechanism to run code continuously or to resume on a network request; Apple's DTS answer to "a network server that runs in the background" is "You can't" [IF01]; a listener left open while suspended accepts connections that never get a reply, so DTS says close it [IF04]. There is therefore no iOS `127.0.0.1:11435`, no cross-app asom, and no always-on role. The "node" on iOS is (a) the `AsomBench` app and (b) the `AsomKit` library inside the owner's own apps.
- **Roles, honestly.** iPhone: **R yes** (M4, foreground plus a best-effort ~30 s grace [IF02]), **B yes** (M3), **S yes**, **PF NO** (feasible in code, not honest as a product: see §2.3), **PA NO**. iPad: the same plus **PF yes, narrowly** (M5, D16): only while the lend screen is foreground-active, awake, unlocked and (recommended) charging. Neither device is a complete holon for *other* apps (R2-DIRECTIVES-4): an iPhone cannot serve another app's requests at all.
- **The 30-second grace does not help inference.** Background GPU work is not permitted without a continued-processing GPU grant, which developers report exists only on iPads [IF05][IF06]; ggml's Metal backend has been reported to **abort the whole process** when the GPU is revoked mid-compute [IF08]. Any engine (M3 benchmark, M5 lender) must stop issuing GPU work when the scene resigns active. The grace is for ledger rows and `GOAWAY`, not for tokens.
- **Local Network permission is not the obstacle.** Accepting inbound TCP needs no Local Network privilege; outgoing TCP to a LAN address does; overlay (VPN) and cellular traffic are not "local network" [IF09]. The iPhone requester needs the prompt only for LAN-direct peers; an iPad lender never needs it unless it dials.
- **No ATS exception is needed.** Peer traffic uses Network.framework, to which ATS does not apply [IF11]; provider, catalogue and model traffic are ordinary HTTPS. `Info.plist` carries no `NSAppTransportSecurity` dictionary at all, and CI lints for that.
- **Signature algorithm: ES256, resolved.** The Secure Enclave holds P-256 (and, from iOS 26, ML-KEM/ML-DSA) keys and **no Curve25519/Ed25519 key** [IF16], so ES256 stays the one manifest and node algorithm (brief §5, C2). Ed25519 is rejected; ML-DSA-65 is the parked post-quantum path. The Swift signer must normalise to low-S: swift-crypto emitted high-S in about half of 1,200 signatures [IS1].
- **App Attest and DeviceCheck: rejected.** Attestation "asks Apple" over the network and is meant for *your server* to verify [IF18]: a new egress class and an operator-server pattern, proving app integrity at attestation time, never that a benchmark was honest.
- **The benchmark is not a differentiator on Apple either.** MLPerf Client's App Store GUI already benchmarks LLMs on iPad Pro (M2 or newer, 16 GB) with llama.cpp-Metal and MLX; it does not run on iPhone [IF24][IF25]. An iPhone never lends, so an iPhone benchmark feeds **no** router. Recommendation (iOS-D1): keep M3 as an **owner-devices-only** validation app for the Swift core and the Metal engine, and do not gate M4 on it (R2-DIRECTIVES-6).
- **What CI can prove:** Linux `swift test` of the pure targets (run here [IS1][IS2]); macOS-runner `swift test` with CryptoKit; `xcodebuild test` on iPhone and iPad **simulators** (iOS 26.x, and iOS 27 on the `xcode-27` preview image [FM37]); unsigned device builds; plist, entitlement and linked-framework lints; a CPU tiny-model smoke; W08 against a hostile JVM node on loopback. **Not provable in CI:** Secure Enclave, keychain sharing across signed apps, the Local Network prompt (the simulator does not implement it [IF09]), Metal performance, background/GPU revocation, thermal behaviour, Tailscale, App Review.
- **Effort:** about **21–34 engineer-weeks** for L0.7 + M3 + M4 + M5 on iOS/iPadOS (§10.4), consistent with the design's 21–36.

---

### 1. Verified platform facts (each with a source URL and date) and Assumptions

#### 1.1 Verified facts

All sources were fetched on **2026-09-30** unless another date is given. Apple documentation pages were fetched as their DocC JSON (`https://developer.apple.com/tutorials/data/documentation/<path>.json`) and are cited by their public URL; the extracted texts are kept under `platforms/ios-src/`. "Conf." rates the source for the exact claim.

| ID | Fact | Source (source date) | Conf. |
|---|---|---|---|
| IF01 | iOS "default behaviour is to suspend your app shortly after the user has moved it to the background"; there is "no general-purpose mechanism for: Running code continuously in the background … Resuming in the background in response to a network or IPC request"; "How do I set up a network server that runs in the background?" is answered "You can't". General mechanisms: silent push, `BGAppRefreshTaskRequest`, `BGProcessingTaskRequest` ("typically delivered overnight"), `BGContinuedProcessingTask`, `UIApplication` background task, `URLSession` background session. App refresh is heuristic ("common scenarios where it won't grant you *any* background execution time"). After a force-quit iOS "sets a flag that prevents the app from being launched in the background" until the user relaunches it | https://developer.apple.com/forums/thread/685525 (Apple DTS, updated 2026-01-09) | high |
| IF02 | `UIApplication` background tasks: "On current systems you can expect about 30 seconds"; "The system does not guarantee *any* background task execution time"; `backgroundTimeRemaining` "is an estimate … avoid using as part of your app's logic"; the background "clock" starts only when the app actually moves to the background | https://developer.apple.com/forums/thread/85066 (Apple DTS, updated 2023-06-16) | high (DTS) / medium (that "about 30 s" still holds in iOS 27) |
| IF03 | `applicationDidEnterBackground(_:)` "has five seconds"; `beginBackgroundTask(withName:expirationHandler:)` requests the assertion asynchronously, may never be granted if called late, and the system kills the app if a task is not ended before time expires | https://developer.apple.com/documentation/uikit/extending-your-app-s-background-execution-time ; https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:) | high |
| IF04 | Apple DTS on listeners: "Close them when you become eligible for suspension and re-open them when that's no longer the case … if you get suspended but your connection isn't defuncted, clients will connect but not receive any response" | https://developer.apple.com/forums/thread/757385 ("Network framework and background tasks", 2024) | high |
| IF05 | `BGContinuedProcessingTask` (iOS/iPadOS 26.0): starts in the foreground, only "in response to someone's action"; progress is shown in a system Live Activity where the person can cancel; the system may terminate it under resource constraints and prioritises terminating tasks that report little progress; background **GPU** use requires the entitlement `com.apple.developer.background-tasks.continued-processing.gpu` and `BGTaskScheduler.supportedResources` containing `.gpu` ("Not all devices support background GPU use"); network and CPU-intensive work are allowed; closing the app in the app switcher cancels it without notice | https://developer.apple.com/documentation/backgroundtasks/performing-long-running-tasks-on-ios-and-ipados ; https://developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtask ; …/bgcontinuedprocessingtaskrequest/resources/gpu | high |
| IF06 | Developers report `supportedResources.contains(.gpu)` is **false on iPhone 15 Pro and iPhone 16 Pro** and true on an A16 iPad; forum answers describe background GPU as iPad-only | Apple Developer Forums (Background Tasks tag, late 2025 to early 2026), via search summary | medium |
| IF07 | iOS 27 release notes: "The system now restricts background access to the Neural Engine, similar to GPU usage restrictions"; background Neural Engine access needs the new entitlement `com.apple.developer.background-tasks.continued-processing.inference` | https://developer.apple.com/documentation/ios-ipados-release-notes/ios-ipados-27-release-notes | high |
| IF08 | ggml Metal backend on iOS: backgrounding with GPU work in flight revokes GPU access (`MTLCommandBufferErrorDomain Code=8 "accessRevoked"`), which whisper.cpp treats as fatal and **aborts the process**; reported on iOS 26.2 (iPhone 12), whisper.cpp v1.8.2; the issue was closed as not planned | https://github.com/ggml-org/whisper.cpp/issues/3531 | medium (one report; whisper.cpp, not llama.cpp, same ggml-metal backend: see IA01) |
| IF09 | TN3179 (revision 2026-02-17): a local network is "an IP network associated with a broadcast-capable network interface … Wi-Fi and Ethernet, but not cellular (WWAN) or VPN". Requires local-network access: outgoing TCP, UDP send, connecting a UDP socket, resolving `.local` names, **all** Bonjour operations. Does **not**: "Listening for and accepting incoming TCP connections", receiving unicast UDP. A backgrounded iOS app with an undetermined privilege is denied silently, not recorded. "The simulator doesn't support local network privacy." No API reports the state (FB8711182); an `NWConnection` to a LAN address waits with `currentPath.unsatisfiedReason == .localNetworkDenied`. List Bonjour types in `NSBonjourServices`. iOS 18 state-sync bug fixed in 18.6 | https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy (2026-02-17) | high |
| IF10 | `NSLocalNetworkUsageDescription` (iOS 14+): "Any app that uses the local network, directly or indirectly, should include this description"; `NSBonjourServices` (iOS 14+): the service types the app browses, e.g. `_ipp._tcp` | https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription ; …/nsbonjourservices | high |
| IF11 | "ATS doesn't apply to calls your app makes to lower-level networking interfaces like the Network framework or CFNetwork"; with ATS enabled URLSession trust "can no longer [be loosened] … but you can still tighten them"; from iOS 17 ATS no longer allows connections to IP addresses by default, `NSAllowsLocalNetworking` re-allows unqualified, `.local` and IP-address loads | https://developer.apple.com/documentation/security/preventing-insecure-network-connections ; https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking | high |
| IF12 | For apps linked on iOS 26+, the default minimum TLS version of URLSession **and Network framework** changed from 1.0 to 1.2. In iOS/iPadOS 26 "TLS-protected connections will automatically advertise support for hybrid, quantum-secure key exchange in TLS 1.3" (X25519MLKEM768). The support page does not name APIs or say anything about the server side | https://developer.apple.com/documentation/ios-ipados-release-notes/ios-ipados-26-release-notes ; https://support.apple.com/en-us/122756 (2026-06-18) | high (for what each says) |
| IF13 | Network.framework TLS controls exist (iOS 12 unless noted): `sec_protocol_options_set_tls_resumption_enabled`, `…_set_tls_tickets_enabled`, `…_set_verify_block`, `…_set_local_identity`, `…_set_peer_authentication_required` ("Clients default to true, whereas servers default to false"), `…_add_tls_application_protocol`, `…_set_min_tls_protocol_version`/`…_max_…` (iOS 13), `sec_identity_create(SecIdentity)`, `sec_protocol_metadata_access_peer_certificate_chain`, `SecTrustCopyCertificateChain` (iOS 15), `SecPKCS12Import` | https://developer.apple.com/documentation/security/sec_protocol_options_set_tls_resumption_enabled(_:_:) and sibling pages | high (existence; behaviour is spike S-A9) |
| IF14 | `NWParameters.requiredLocalEndpoint` ("A specific local IP address and port to use for connections and listeners"), `requiredInterface`, `prohibitedInterfaceTypes`, `prohibitExpensivePaths` (iOS 12); `NWListener.newConnectionLimit` (iOS 13); `NWConnection.DataTransferReport` with per-path `sentApplicationByteCount`, `receivedApplicationByteCount`, `sentTransportByteCount` ("bytes sent into the transport protocol"), `receivedTransportByteCount`, collected after `startDataTransferReport()` (iOS 13); `NWProtocolFramer.Options` adds a custom message protocol to the stack (iOS 13) | https://developer.apple.com/documentation/network/nwparameters/requiredlocalendpoint ; …/nwconnection/datatransferreport/pathreport | high |
| IF15 | Network framework's Swift-concurrency API `NetworkListener`/`NetworkConnection` exists from iOS/iPadOS 26.0 | https://developer.apple.com/documentation/network/networklistener | high |
| IF16 | CryptoKit `SecureEnclave` offers `P256` (signing, key agreement), `MLKEM768`, `MLKEM1024`, `MLDSA65`, `MLDSA87` (the ML-* types iOS 26.0+); there is **no** `SecureEnclave.Curve25519`. Apple: the Secure Enclave "Works only with NIST P-256 elliptic curve keys" (for EC), "Can't encode preexisting keys", needs A7 or later | https://developer.apple.com/documentation/cryptokit/secureenclave ; …/secureenclave/mldsa65 ; https://developer.apple.com/documentation/security/protecting-keys-with-the-secure-enclave | high |
| IF17 | `P256.Signing.ECDSASignature` has `rawRepresentation` ("A raw data representation of a P-256 digital signature", r‖s) and `derRepresentation` (iOS 13) | https://developer.apple.com/documentation/cryptokit/p256/signing/ecdsasignature | high (the 64-octet layout itself: [IS1]) |
| IF18 | App Attest: `attestKey` "Asks Apple to attest to the validity of a generated cryptographic key"; the method "accesses a remote Apple server" (`serverUnavailable`); the attestation object is sent "to your server for verification"; assertions sign later server requests and carry a counter; a receipt supports a server-to-server fraud-risk metric; `isSupported` must be checked and most extension types are unsupported. DeviceCheck `DCDevice`: a token your server combines with an Apple key to read or set **two bits per device** | https://developer.apple.com/documentation/devicecheck/dcappattestservice/attestkey(_:clientdatahash:completionhandler:) ; …/establishing-your-app-s-integrity ; …/validating-apps-that-connect-to-your-server ; …/dcdevice | high |
| IF19 | Keychain access groups share items among "apps that are delivered by a single development team", with no user interaction; groups are Team-ID-prefixed. `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`: accessible only while unlocked; items "do not migrate to a new device" | https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps ; …/ksecattraccessiblewhenunlockedthisdeviceonly | high |
| IF20 | `FileProtectionType.completeUnlessOpen`: a file opened while unlocked "may continue to [be accessed] … even if the user locks the device"; `.complete`: no read or write while locked; `isExcludedFromBackup` excludes a resource from backups | https://developer.apple.com/documentation/foundation/fileprotectiontype/completeunlessopen ; …/complete ; …/urlresourcevalues/isexcludedfrombackup | high |
| IF21 | `ProcessInfo.ThermalState`: `nominal`, `fair` ("slightly elevated"), `serious` ("high"), `critical` ("the device needs to cool down"); read `thermalState` before registering for `thermalStateDidChangeNotification`. Low Power Mode reduces CPU/GPU performance and pauses background activity. `os_proc_available_memory()` is advisory and can change at any time. The `increased-memory-limit` entitlement is "only available on some device models". `isIdleTimerDisabled` keeps the device from sleeping while set | https://developer.apple.com/documentation/foundation/processinfo/thermalstate-swift.enum ; …/islowpowermodeenabled ; …/os/os_proc_available_memory ; …/entitlements/com.apple.developer.kernel.increased-memory-limit ; …/uiapplication/isidletimerdisabled | high |
| IF22 | App Review Guidelines (Last Updated **2026-06-08**): **2.4.2** no rapid battery drain or excessive heat, no unrelated background processes; **2.5.2** no downloading or executing code that changes features; **2.5.4** background services only for their intended purposes; **2.5.14** explicit consent and a clear indication when recording user activity; **4.2.3(ii)** disclose the size of required downloads and prompt first; **5.1.2(i)** disclose and get explicit permission before sharing personal data "with third-party AI"; **2.3.1(a)** no hidden features, describe features in Review Notes. No LLM-specific rule was found in the text | https://developer.apple.com/app-store/review/guidelines/ | high |
| IF23 | Distribution: TestFlight allows up to 100 internal and 10,000 external testers; builds expire after 90 days; the first build added to a group goes to review. **"TestFlight users of your app automatically share crash reports with you, regardless of the device settings"**; App Store users share only if they opted in | https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/ ; https://developer.apple.com/documentation/xcode/acquiring-crash-reports-and-diagnostic-logs | high |
| IF24 | Device registration: up to **100 devices per product family per membership year** for development and ad hoc; free Personal Team: 3 devices, 10 App IDs, 3 apps per device, profiles expire after **7 days**, no TestFlight or App Store Connect; the Apple Developer Program costs **US$99/year** | https://developer.apple.com/help/account/devices/devices-overview/ ; https://developer.apple.com/support/compare-memberships/ ; https://developer.apple.com/programs/ | high |
| IF25 | Alternative app marketplaces: EU users on iOS 17.4 / iPadOS 18 or later; notarised apps only; the page also refers to approved marketplaces in **Japan and Brazil** | https://developer.apple.com/support/alternative-app-marketplace-in-the-eu/ | medium-high |
| IF26 | App Store submissions with Xcode 27 RC / iOS 27 SDK opened 2026-09-09; **from April 2027** iOS and iPadOS uploads must be built with the iOS & iPadOS 27 SDK or later | https://developer.apple.com/news/?id=k1mtkt1k (2026-09-09) | high |
| IF27 | GitHub runners: `macos-latest` = macOS 26 arm64; `macos-26`/`macos-15` arm64 and `-intel` variants; `macos-14` deprecated; an `xcode-27` image in **preview**; standard arm64 macOS runner = 3 vCPU (M1), 7 GB RAM, 14 GB SSD; nested virtualisation unsupported; standard runners free for public repositories. The `macos-26-arm64` image 20260907.0351.1 has macOS 26.6.2, Xcode 26.6 default (26.0.1–26.5 also installed), iOS simulator runtimes 26.0, 26.1, 26.2, 26.4 and 26.5, and simulators including "iPhone 17" and "iPad Pro 11-inch (M5)" | https://github.com/actions/runner-images (README) ; …/images/macos/macos-26-arm64-Readme.md ; https://docs.github.com/en/actions/reference/runners/github-hosted-runners | high (for those image versions) |
| IF28 | MLPerf Client v1.6 (2026-04-06): "The GUI versions of MLPerf Client are also available via the App Stores for iOS and Mac"; "MLX with Metal and llama.cpp with Metal" for "macOS and iPad systems" | https://mlcommons.org/2026/04/mlperf-client-v1-6/ (2026-04-06) [FM34] | high |
| IF29 | MLPerf Client App Store listing: **iPad only** (iPadOS 16+), "iPad Pro (M2) or newer with 16GB or more of RAM", **iPhone not compatible**; version 2.0 notes list Llama 3.1 8B and Phi 4 Mini Instruct (base) and Qwen 3 8B (experimental); "The developer does not collect any data from this app" | https://apps.apple.com/us/app/mlperf-client/id6747366479 | high (listing as fetched) |
| IF30 | MLPerf Mobile App Store listing: iPhone/iPad (iOS 13.1+); the description names image classification, language understanding and image generation, **not** LLMs; App Privacy lists data linked to the user (contact info, identifiers, diagnostics). MLPerf Mobile v6.0 (2026-06-15) lists the App Store as a distribution point and says the LLM tests run on "devices with sufficient memory via the CPU" | https://apps.apple.com/us/app/mlperf-mobile/id1629525778 ; https://mlcommons.org/2026/06/mlperf-mobile-v6/ [F40] | high (for what each says) |
| IF31 | Tailscale: "Each Tailscale agent … streams its logs to a central log server (at `log.tailscale.com`)"; opt-out documented for Windows, macOS (open-source `tailscaled` only) and Linux, **none for iOS/tvOS or Android** (page last validated 2026-01-05). The iOS app supports "Use a custom coordination server" (Headscale) | https://tailscale.com/kb/1011/log-mesh-traffic ; https://tailscale.com/kb/1507/custom-control-server | high |
| IF32 | llama.cpp ships an **XCFramework** "for iOS, visionOS, tvOS, and macOS", consumed as a SwiftPM `.binaryTarget(url:checksum:)` from release assets `llama-bNNNN-xcframework.zip`; `build-xcframework.sh` builds `ios-sim` and `ios-device` with `GGML_METAL=ON`, embedded Metal library, BLAS, static libraries, `IOS_MIN_OS_VERSION=16.4` | https://github.com/ggml-org/llama.cpp/blob/master/docs/xcframework.md ; …/build-xcframework.sh (master, 2026-09-30) | high |
| IF33 | MLX Swift runs on iOS and macOS (example chat and eval apps); `mlx-swift-lm` provides the LLM libraries; its `MLXFoundationModels` bridge needs the 27 SDKs | https://github.com/ml-explore/mlx-swift (README) ; https://github.com/ml-explore/mlx-swift-lm (README) | high |
| IF34 | **Core AI** (iOS 27.0): "Run AI models in your app on Apple silicon" across CPU, GPU and Neural Engine; models are converted to the `.aimodel` format with `coreai-torch`; language models can run through Foundation Models | https://developer.apple.com/documentation/coreai ; https://developer.apple.com/videos/play/wwdc2026/324/ | high |
| IF35 | Foundation Models: `SystemLanguageModel` (iOS 26.0) is the on-device Apple Intelligence model, availability depends on device and region; iOS 27 adds **`PrivateCloudComputeLanguageModel`**, "A variant … that runs on Private Cloud Compute", selected by one line of code | https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel ; …/privatecloudcomputelanguagemodel | high |
| IF36 | Wi-Fi Aware (iOS 26.0): "pair and connect to external devices over peer-to-peer Wi-Fi", i.e. Wi-Fi Aware certified **accessories**; iPhone 12 and later | https://developer.apple.com/documentation/wifiaware | high |
| IF37 | Export compliance: `ITSAppUsesNonExemptEncryption` = `NO` when the app uses no encryption or only exempt forms; OS-provided encryption (e.g. HTTPS via URLSession) is "typically" exempt, "proprietary" encryption is not | https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations | high |
| IF38 | `UIScreen.isCaptured` is **deprecated in iOS 27**; `UITraitCollection.sceneCaptureState` (iOS 17) replaces it. `DataScannerViewController` (VisionKit, iOS 16) scans machine-readable codes; `NWPath.UnsatisfiedReason.localNetworkDenied` exists from iOS 14.2 | https://developer.apple.com/documentation/uikit/uiscreen/iscaptured ; …/uitraitcollection/scenecapturestate ; …/visionkit/datascannerviewcontroller ; …/network/nwpath/unsatisfiedreason-swift.enum/localnetworkdenied | high |
| IF39 | Precedent (existence only): **"Local LLM Server"** on the App Store (iPhone, iPad, Mac; iOS 26+) serves an OpenAI- and Ollama-compatible API on the local network, offers "Keep Screen Awake" and "an optional alert if the server gets suspended" | https://apps.apple.com/us/app/local-llm-server/id6757007308 | high (existence; says nothing about review criteria) |
| IF40 | swift-certificates' `X509` target depends on `SwiftASN1`, `Crypto` **and `_CryptoExtras`** from swift-crypto, on every platform (range 3.12.3..<6.0.0) | `Package.swift` of swift-certificates 1.21.0 (checkout in `platforms/ios-spike/CertSpike/.build/checkouts/`) | high |
| IF41 | XcodeGen 2.46.0, MIT, Homebrew bottle available | https://formulae.brew.sh/api/formula/xcodegen.json | high |

#### 1.2 Spikes run in this session (LAB — not device evidence)

Transcript: `platforms/ios-spike/TRANSCRIPT.txt`, re-runnable with `platforms/ios-spike/run_spikes.sh` (Linux x86_64, Swift 6.1 at `/opt/swift`).

| ID | What ran | Result |
|---|---|---|
| IS1 | `AsomKitSpike` (module `AsomDSSE`): DSSE PAE, strict 91-byte P-256 SPKI (T1), ES256 verify over 64-octet r‖s, low-S normalisation; `#if canImport(CryptoKit) … #else import Crypto`. Mirrors `manifest-vectors/VerifyDsse.java` exactly (regex field pull from each raw document, key1 SPKI) | With **swift-crypto 3.15.1 and 4.5.2**: the 37 per-vector lines are **byte-identical to the JCA transcript** (`diff` empty; both sha256 `c686d67e…323a`). The Swift signer emitted **high-S in 175/400, 199/400 and 202/400** signatures across three runs, so producers must normalise; every high-S twin verified (verifiers accept high-S, as the profile requires); compressed-point and trailing-byte SPKIs rejected. (The macOS section's FM42 got 36/37 because it parsed containers with `JSONSerialization`, which rejects M03-123's trailing data first; both results agree.) |
| IS2 | `CertSpike`: swift-certificates 1.21.0 + swift-asn1 1.7.3 + swift-crypto 4.5.2 over an openssl-made P-256 node certificate and a leaf it signed | parses both; leaf verifies under the node key and not under its own; a one-byte TBS tamper fails verification; the library's SPKI serialisation equals openssl's 91 bytes. **Partial S-A10 only**: no negative-vector suite, not on iOS |
| IS3 | XcodeGen 2.46.0 built from source on Linux; `xcodegen generate` on a sketch `project.yml` (iOS app + unit tests + a local SwiftPM package) | produced a 32-object `AsomBench.xcodeproj` with `IPHONEOS_DEPLOYMENT_TARGET = 26.0`, `SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD = NO` and the package reference; quirks: `USER` must be set, and presets resolve only when run from the XcodeGen checkout. The project was **not built** (no Xcode here) |

#### 1.3 Assumptions (not verified)

| ID | Assumption | Load-bearing for | How to settle |
|---|---|---|---|
| IA01 | llama.cpp's ggml-metal behaves like whisper.cpp's [IF08]: GPU access revoked mid-compute aborts the process | M3, M5 engine lifecycle | Device spike DV-I5 (background during a Metal decode, with and without the stop-before-background rule) |
| IA02 | The iOS simulator has no Secure Enclave (`SecureEnclave.isAvailable == false`); the macOS runner VM has none either | CI key-path coverage | A CI test prints `isAvailable` on both |
| IA03 | Metal on hosted macOS runners is absent or capped (community reports of an MPS cap near 1 GB) | CI engine tests | Treat as absent: CI runs the CPU backend only |
| IA04 | A software P-256 leaf key plus certificate in the keychain yields a `SecIdentity` usable by `sec_identity_create` for NWConnection client and server auth; fallback: build an in-memory PKCS#12 and use `SecPKCS12Import` [IF13] | M4, M5 transport (S-A2) | Spike S-A2 on a device and the simulator |
| IA05 | A Secure Enclave key (a `SecKey` with `kSecAttrAccessGroup`, or a CryptoKit `dataRepresentation` blob stored in a group keychain item) is usable by every app of the owner's Team on that device and by no other app | M4 device-level pairing (§7.8, [A25]) | Spike S-A12 with two signed apps on a device |
| IA06 | Network.framework honours per connection: TLS 1.3 only, required client auth on a listener, ALPN, resumption and tickets off, and calls the verify block in both roles with the full peer chain | T2 on iOS ([A21], [A26]) | S-A9 Network.framework column; W08 in the simulator |
| IA07 | `DataTransferReport.sentTransportByteCount` counts every byte handed to TCP, i.e. TLS records including handshakes | §4.6 byte accounting | Spike: compare with a packet capture on a Mac |
| IA08 | With Tailscale's iOS packet tunnel active, a foreground app's NWListener bound to the tailnet address accepts inbound tailnet connections ([A17] of `platforms.md`) | M5 over the overlay | Device test DV-I9 |
| IA09 | The Tailscale iOS client still uploads logs when using a Headscale control server ([A10]; the Linux section found Linux clients do [LF10]) | D8 disclosure | Packet capture at the gateway with the tunnel up |
| IA10 | iOS runs one VPN tunnel at a time, so Tailscale occupies the slot | overlay availability | Known behaviour; confirm on device |
| IA11 | Required-reason API codes for `PrivacyInfo.xcprivacy`: 35F9.1 (system boot time, elapsed-time measurement), E174.1 (disk space before writing), CA92.1 (UserDefaults, app-only), C617.1 (file timestamps in the container). Recalled, not fetched (the DocC page did not expose the table) | packaging | Xcode's privacy-manifest editor at M3 |
| IA12 | Builds installed from Xcode with development provisioning do not upload crash reports to Apple or the developer (only TestFlight and consenting App Store users do [IF23]) | iOS-D5 recommendation | Owner check in Xcode Organizer after a forced crash |
| IA13 | An 8B Q4_K_M file (5.03 GB) plus a 4k KV cache fits only on 16 GB iPads (with `increased-memory-limit`), not on 8 GB devices; MLPerf Client's own 16 GB gate [IF29] is circumstantial evidence | M5 model tiers | Device test DV-I7 with `os_proc_available_memory()` |
| IA14 | In Split View / Stage Manager the lend screen is not reliably `foregroundActive`; M5 therefore serves only when it is | M5 FSM | Device test on the owner's iPad |
| IA15 | App Review would accept the M3 app and a M5 lend screen under 2.4.2 / 2.5.2 / 2.5.4; model weights count as data, not code (precedents PocketPal AI, Local LLM Server [IF39], MLPerf Client) | iOS-D5 | Only a submission settles it |
| IA16 | MLPerf Mobile's iOS build runs its LLM workload (the listing and release notes do not say) [IF30] | benchmark positioning | Owner installs it on an iPhone |
| IA17 | 8B Q4 decode on an M4/M5 iPad Pro is roughly 20–35 tok/s with llama.cpp Metal. **ESTIMATE, never shown to users** | the value of M5 | M3 on the owner's iPad (DV-I6) |
| IA18 | `_CryptoExtras` compiles BoringSSL into an iOS binary that links swift-certificates [IF40] (binary size, export-compliance answer) | M4 dependency registry | `nm` the M4 build |
| IA19 | Export compliance: TLS through Apple's stack plus ES256 authentication qualifies as exempt | `ITSAppUsesNonExemptEncryption` | Owner answers the App Store Connect questionnaire |
| IA20 | The Swift drift traps of `platforms.md` A13 (`JSONEncoder` escapes `/`; `String` ordering is canonical-equivalence, not UTF-16; `NumberFormatter` half-even) apply to the iOS SDKs too | AsomJSON | M01/W01 vectors catch them either way |

---

### 2. Feasible mesh roles on this platform

**Vocabulary** (design §2.1): **R** borrow; **PA** lend while awake with no human present; **PF** lend only while a lend screen is frontmost; **B** benchmark producer; **S** manifest subscriber and verifier.

#### 2.1 Role verdicts

| Node | R (borrow) | PA | PF | B / S | Holon completeness (R2-DIRECTIVES-4) |
|---|---|---|---|---|---|
| **iPhone** | **YES (M4)**: AsomKit inside the owner's own apps, to one home lender (D15(b)); foreground, plus a best-effort grace to finish a stream [IF02] | **NO** [IF01][IF04] | **NO** (§2.3); code-gated off by `userInterfaceIdiom` | B **yes** (M3, owner devices; feeds no router) / S **yes** (M3 verifier, M4 peer manifests) | **Borrow-only for other apps.** No cross-app daemon exists, so an iPhone never serves another app's requests; each app can only borrow (RemoteMesh) or use its own `CloudOnly` keys (D14 part B). Not an "individual" in directive B's sense unless an `Embedded` tier is scheduled (it is not) |
| **iPad** | **YES (M4)**, as the iPhone | **NO** | **YES, narrowly (M5, D16)**: lend screen `foregroundActive`, awake (`isIdleTimerDisabled`), unlocked, charging by default, thermal below `serious` (§3.4) | B **yes** (its manifest becomes a router prior for peers only from M5) / S **yes** | Lends only in front of a visible screen; still no cross-app serving |
| **iPhone/iPad as a Mac app** ("Designed for iPad" on Apple silicon) | — | — | — | **disabled** (C10: one harness per OS; the Mac benchmarks with the JVM `asom bench`; macOS section §7.4) | — |

#### 2.2 The honest reasons

- **Background execution.** iOS suspends a backgrounded app shortly after it leaves the screen; nothing resumes it on an incoming connection [IF01]. The only general mechanisms either run later at the system's discretion (refresh, processing, silent push) or are finite and user-initiated (`BGContinuedProcessingTask`, background tasks, background URLSession). **Silent push is rejected**: it needs an APNs-sending server (an operator backend) and an Apple egress class. So no mechanism can make an iPhone or iPad answer a peer while it is not on screen.
- **Listeners.** A listener must close when the app becomes eligible for suspension, or peers connect and hang [IF04]. The M5 listener therefore lives and dies with the lend screen's scene phase.
- **The GPU.** Inference runs on the GPU (Metal). Background GPU access needs a user-started continued-processing task with the GPU resource, which developers report only iPads offer [IF05][IF06]; iOS 27 restricts the Neural Engine the same way [IF07]. A GPU revoked mid-compute has aborted ggml processes [IF08][IA01]. Consequences: the 5 s `didEnterBackground` window and the ~30 s task [IF02][IF03] can flush a ledger row and send `GOAWAY`, but cannot finish an answer; `BGContinuedProcessingTask` is for finite user-started work, not for serving other devices (and using it to keep a server alive would breach guideline 2.5.4 [IF22]).
- **Networking.** Inbound TCP needs no Local Network privilege; outgoing LAN TCP does; VPN and cellular are not local network [IF09]. The permission therefore constrains only the iPhone requester's LAN-direct path, never lending.
- **Power and thermal.** `ProcessInfo.thermalState` gives four coarse states [IF21]; Low Power Mode lowers CPU and GPU performance [IF21]. Phones throttle within minutes under sustained decode (roadmap §1 planning assumption, not measured here). App Review 2.4.2 forbids excessive heat and rapid drain [IF22], which turns the design's thermal gates into review requirements.
- **Memory.** Per-app limits are device-dependent and advisory [IF21]; the increased-memory-limit entitlement exists on some models only. 8B models are plausible only on 16 GB iPads (IA13).
- **Store and distribution.** App Review is a judgement for a benchmark app and for a lend screen (2.4.2, 2.5.2, 2.5.4, 2.5.14 [IF22]); precedents exist [IF39] but decide nothing (IA15). For the owner's own devices, development provisioning avoids review entirely (§8.3).

#### 2.3 Is foreground-only lending (PF) honest? iPhone: NO. iPad: yes, narrowly.

| Factor | Effect on PF | iPhone | iPad |
|---|---|---|---|
| Local Network permission | none for accepting; the lender initiates nothing (quiescence rule, design §8.6) | not an obstacle | not an obstacle |
| The 30-second grace | useless for inference (GPU revoked on background [IF05][IF08]); covers ledger rows and `GOAWAY` only | — | — |
| Scene phase | serving requires the lend screen `foregroundActive`; any notification pull-down, call or app switch drains; background closes the listener | the owner's phone is unusable for anything else while lending | an idle iPad on a stand or in a car is a realistic lender |
| Thermal | drain at `serious`, refuse at `critical` | small thermal envelope; throttles fast (estimate) | larger envelope (estimate) |
| Memory | 8B needs about 16 GB (IA13) | current iPhones have less (not verified here) | 16 GB iPad Pro models exist (MLPerf Client's gate [IF29]) |
| Security cost | the device sits **unlocked and awake** while serving | high: a phone is carried and grabbed | real but bounded: charging, max duration, settings behind LocalAuthentication |
| Value | a peer is useful only if it beats the borrower | rarely beats the borrower | can beat a phone by a wide margin (IA17, estimate) |

**Verdict.** iPhone PF is technically buildable from the same code but **not an honest product role**: it lends only while its owner is not using it yet must be unlocked, awake and frontmost, it rarely out-performs the device that would borrow from it, and it heats the device in the hand. **NO; code-gated off.** iPad PF is honest **only with the conditions of §3.4 stated to the user**: "Your iPad lends only while this screen is open, unlocked and plugged in. Leaving this screen stops lending immediately."

**Corrections to D16's wording (design §9.3 M5).** "Re-authentication to leave" is **not implementable**: no app can block the Home gesture or the app switcher. What is implementable: (a) leaving the lend screen always stops serving (fail-safe); (b) changing lend settings or opening any other asom tab from the lend screen requires `LAContext` authentication (device owner); (c) a maximum serving duration, then re-confirmation; (d) optionally the user's own Guided Access (unverified; not relied on).

#### 2.4 What these roles do NOT guarantee

- An iPhone request is not guaranteed to finish if the user leaves the app: the grace is best-effort and unguaranteed [IF02]; a stream cut after bytes reached the app ends with the in-band `MESH_STREAM_INTERRUPTED` (CD-6b), never a silent retry.
- An iPad lender does not keep serving if anything takes the screen (a call, Siri, Control Center, an alert): it drains, and a presence-correlated `PEER_UNAVAILABLE` reaches peers (design §7.12).
- Nothing on iOS lends while locked, asleep, suspended, or after a force-quit [IF01].
- AsomKit runs inside the host app, so it cannot stop that app's other code from reading prompts (design §7.8 stated limit).

---

### 3. Node hosting

#### 3.1 What "the node" is on iOS

There is no service host. Two kinds of process play the node's parts, all signed with the owner's Team ID:

| Process | Contains | Plays | Phase |
|---|---|---|---|
| **`AsomBench` app** (bundle ID per iOS-D2; display name "asom") | the whole AsomKit, the engine package, SwiftUI placeholder UI | B, S (M3); pairing host, Peers tab, ledger viewer (M4); the lend screen on iPad (M5) | M3 → M5 |
| **The owner's other apps** | AsomKit's requester targets only (`AsomRequester`, `AsomTransport`, `AsomWire`, `AsomLedger`, `AsomDSSE`, `AsomJSON`) | R through the `RemoteMesh` tier (CD-10A) | M4 |

**Shared, Team-scoped state** (so one iPhone is one node, design §7.8):

| Item | Where | Protection |
|---|---|---|
| Node identity key (NIK) | Secure Enclave P-256; its wrapped blob (or `SecKey` reference) in a keychain item, access group `$(TeamID).xyz.mdhv.asom.node` | `WhenUnlockedThisDeviceOnly`, `kSecAttrSynchronizable = false`, access control `.privateKeyUsage` [IF16][IF19]; S-A12 decides the exact form (IA05) |
| Session leaf key + certificate (14-day) | keychain, same access group | `WhenUnlockedThisDeviceOnly`, non-sync (T9) |
| Pairing registry | app-group container `group.xyz.mdhv.asom`, `registry.json` | **single writer: the AsomBench app**; other apps read only; `completeUnlessOpen`, `isExcludedFromBackup` [IF20] |
| Per-app mesh opt-in | same container, `apps/<bundleID>.json`, written only by AsomBench's Peers tab | the toggle is enforced by AsomKit code in the owner's apps, not by the OS (stated limit, design §7.8) |
| Ledgers | same container, **one JSONL file per app**: `ledger/<bundleID>.jsonl` (no cross-process appends to one file) | `completeUnlessOpen`, `isExcludedFromBackup`; fsync per append; export only by user action |
| Models (M3, M5) | AsomBench's own `Application Support/models/<sha256>` | `isExcludedFromBackup` (size; re-downloadable) |

#### 3.2 Lifecycle, and what each part does in each state

| Scene / app state | Requester (AsomKit in a host app) | Benchmark run (M3) | Lend screen (M5, iPad) |
|---|---|---|---|
| `foregroundActive` | dials, streams | runs | SERVING if conditions hold |
| → `foregroundInactive` (Control Center, alert, call, app switcher) | continues the stream | **stops issuing engine calls** after the current one (≤ 1 s by `n_ubatch`, B6); run → ABORTING | → DRAINING at once: no new offers; the current `INFER_*` continues only while still `foregroundInactive`; presence signal for LP-2 |
| → `background` (5 s window [IF03]) | `beginBackgroundTask` was taken at request start (the clock starts only now [IF02]); stream continues on CPU/network until the task expires | engine already stopped; write partial result (B9 `backgrounded`); release | **close the listener** [IF04]; `GOAWAY` to each session; any stream still running ends `INFER_END terminal=interrupted`; lender outcome rows fsynced; GPU work must already be stopped (IA01) |
| grace expires | `CANCEL` to the lender, outcome row `interrupted`, typed error to the host app on its next run loop | — | — |
| suspended / terminated / force-quit | nothing runs; an in-flight background URLSession model download continues in the system (§3.3) | nothing | nothing |
| device locks | NIK and leaf unusable (`WhenUnlocked…`, T9): no new session; ledger file stays writable (`completeUnlessOpen`) | stop (the screen is gone) | stop (the scene backgrounds) |

#### 3.3 Start, stop, boot, sleep and lock

- **Start:** the user opens an app. **Boot:** nothing starts (no mechanism, and consistent with v1's "boot-start default OFF"). **Stop:** leaving the app, locking, or force-quit.
- **What survives suspension:** only a **background URLSession** model download, run by the system [IF01]. Ledger rule: the `download` intent row is written before the task is enqueued; the outcome row is written when the system wakes or relaunches the app for the session's events, or at the next launch, with bytes from the task's metrics; a force-quit cancels background transfers and leaves an "outcome unknown" reconciliation row at next launch (§8.4 "Does not guarantee").
- **Lock:** keys are `WhenUnlockedThisDeviceOnly`, so a locked iPhone cannot open a mesh session (T9's keyguard binding, iOS form). Files use `completeUnlessOpen` so a row begun before lock completes.

#### 3.4 The iPad lend-screen FSM (M5) and the PF presence rule

States are the design's OFF → ARMED → SERVING → DRAINING (§3.2), with iOS inputs:

| Condition for SERVING (all must hold) | Source |
|---|---|
| user tapped "Start lending" on the lend screen, and the screen is `foregroundActive` | UIScene activation state |
| device idiom is `.pad` | `UIDevice.userInterfaceIdiom` (iPhone: never) |
| `isIdleTimerDisabled = true` was set by the lend screen [IF21] | UIKit |
| charging (`UIDevice.batteryState` is `.charging` or `.full`) unless iOS-D4 allows battery ≥ 80 % | UIKit |
| `thermalState` is `nominal` or `fair` (drain at `serious`, refuse at `critical`) [IF21] | ProcessInfo |
| Low Power Mode off | ProcessInfo |
| serving duration < the cap (default 2 h, then re-confirm) | local clock |
| an eligible interface is up: the overlay's utun or a user-confirmed Wi-Fi (never cellular) | NWPathMonitor |
| the lender intent row can be written (FC-4) | AsomLedger |

**PF presence rule (answers R2-OVERCLAIM-9).** On the lend screen, being `foregroundActive` is **consent, not presence**; touches inside the lend screen are not presence. **Presence signals** are: the scene leaving `foregroundActive`, and the lend screen being dismissed. A presence signal drains at once; SERVING is published again **no sooner than 10 min after the last presence signal** and only after a new "Start lending" tap (LP-2 kept; the screen shows "lending resumes in mm:ss"). LP-1 needs this exception written in: "the lending node's own lend screen being frontmost is not a presence input".

#### 3.5 How the user sees that it is running (the watched-object rule)

- **Lending:** impossible without the full-screen lend view, which shows state (ARMED / SERVING / DRAINING and the reason), the peer node tag being served, tokens served, thermal band and power. **This is the strongest watched-object property of any platform: an iPad cannot lend invisibly.**
- **Borrowing:** each host app receives a `RouteEcho` built from the same record as its ledger row (Invariant 9; W01b in Swift): served-by (`peer:<alias>/<model>`), `egress` = reach, tier (`RemoteMesh` or `CloudOnly`). AsomKit provides the signal; the host app's UI shows it (brief §10A.6 pattern). The AsomBench app shows every app's ledger from the app-group container.
- **Benchmark:** the run screen; if iOS-D9(b) is ever chosen, the system Live Activity of `BGContinuedProcessingTask` [IF05].

---

### 4. Networking

#### 4.1 Inbound listener (M5 only; Invariant 2 as amended by IC-1)

- `NWListener` with `NWParameters(tls:tcp:)`, `requiredLocalEndpoint = <the eligible address>:11436` and `requiredInterface = <that interface>` [IF14]; `prohibitedInterfaceTypes = [.cellular]`; one listener per eligible address; **never** a wildcard, never loopback, never cellular. `newConnectionLimit` caps pending accepts [IF14].
- Eligible addresses: the overlay's assigned address on its utun interface (Tailscale `100.64.0.0/10`, `fd7a:115c:a1e0::/48`), or a Wi-Fi/Ethernet address on a network the user confirmed on this iPad (IC-1(b)); interface type from `NWPathMonitor`, never a hard-coded BSD name (TN3179 warns `en0` is not API [IF09]).
- Lifetime: opened on SERVING, closed on DRAINING's end and on every background transition [IF04]; a pairing window (≤ 120 s) may open it while the lend screen or the Peers tab is frontmost.
- **Stated exposure (T13):** while open, it hands its node certificate to anyone who can reach the address. S-A11's SNI gate has no verified Network.framework implementation (`sec_protocol_options_set_challenge_block` and the server-side SNI hooks are unexplored) [A09].
- No iPhone listener exists in any phase (PF is NO).

#### 4.2 Local Network privacy and the exact `Info.plist` keys

| Key | Value | Why |
|---|---|---|
| `NSLocalNetworkUsageDescription` | "asom connects only to devices you paired, and only when you ask it to." | outgoing LAN TCP to a paired peer (M4) and LAN pairing dials [IF09][IF10] |
| `NSBonjourServices` | **absent** | no mDNS in any scheduled phase (roadmap v4 "no mDNS"; D12 defers locate). If D12 ever approves locate: `_asom-mesh._tcp` only, browse foreground-only |
| `NSCameraUsageDescription` | "Scan the pairing code shown on your other device." | QR pairing via `DataScannerViewController` or `AVCaptureMetadataOutput` [IF38]; paste remains available |
| `NSAppTransportSecurity` | **absent** (§4.3) | — |
| `UIBackgroundModes` | **absent** (no `audio`, `voip`, `location` keep-alive tricks; 2.5.4 [IF22]) | iOS-D9(b) would add only the BGTaskScheduler registration keys that API requires |
| `ITSAppUsesNonExemptEncryption` | owner's answer (IA19) | export compliance [IF37] |
| multicast entitlement | **not requested** | only for multicast/broadcast or arbitrary Bonjour types [IF09] |

Behaviour: the prompt appears on the first LAN dial, so the requester makes that dial from the pairing screen (always foreground; a background dial with an undetermined state is silently denied and not recorded [IF09]). Denial is detected as `currentPath.unsatisfiedReason == .localNetworkDenied` [IF09] and written as the design's `DIAL` outcome `local-network-denied`; the Peers tab says "Local Network access is off for asom (Settings > Privacy & Security > Local Network)". Over the overlay no prompt appears (VPN is not local network [IF09]). **The simulator does not implement Local Network privacy [IF09]: every item here is NDV.**

#### 4.3 App Transport Security: no exception; the design's alternative stands

- **Peers:** Network.framework `NWConnection`/`NWListener` with a custom verify block; ATS does not apply [IF11]. URLSession could only *tighten* trust, never accept a pinned self-signed chain [IF11], so it is not used for peers.
- **Providers, catalogue, model mirrors:** URLSession over public HTTPS; default ATS is correct. **No** `NSAllowsArbitraryLoads`, `NSAllowsLocalNetworking` or `NSExceptionDomains`. A CI lint fails the build if `NSAppTransportSecurity` appears.
- **TLS profile (M4/M5), sketch:**

```swift
let tls = NWProtocolTLS.Options()
let sec = tls.securityProtocolOptions
sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)          // IF13; iOS 26 default min is 1.2 [IF12]
sec_protocol_options_set_max_tls_protocol_version(sec, .TLSv13)
sec_protocol_options_add_tls_application_protocol(sec, "asom-mesh/1")   // re-checked after the handshake (T2)
sec_protocol_options_set_tls_resumption_enabled(sec, false)              // T2: client never offers a PSK
sec_protocol_options_set_tls_tickets_enabled(sec, false)
sec_protocol_options_set_peer_authentication_required(sec, true)        // servers default to false [IF13]
sec_protocol_options_set_local_identity(sec, leafIdentity)               // S-A2 (IA04)
sec_protocol_options_set_verify_block(sec, { _, trust, complete in
    // The ONLY trust path (K2). SecTrustEvaluate* is forbidden. Every error path completes false.
    let der = certificateChainDER(trust)            // SecTrustCopyCertificateChain + SecCertificateCopyData [IF13]
    complete(VerifyPeerChain.verify(der, expectedPin: pin) == .accepted)  // swift-certificates (T3, IS2)
}, verifyQueue)
let params = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
params.prohibitedInterfaceTypes = [.cellular]
params.defaultProtocolStack.applicationProtocols.insert(
    NWProtocolFramer.Options(definition: AsomFramer.definition), at: 0) // asom-mesh/1 frames; framer iOS 13 [IF14]
```

  TLS 1.3 early data needs a resumption PSK, so disabling resumption also disables 0-RTT; W08 asserts it anyway (no `pre_shared_key`, no `early_data` in any ClientHello). iOS 26 clients also advertise X25519MLKEM768 [IF12]; a JSSE lender that does not support it negotiates a classical group, so post-quantum protection depends on the peer (design §4.4, still unverified for JSSE).

#### 4.4 The private overlay on iOS

- **Client:** the Tailscale iOS app, a packet-tunnel VPN; it supports a custom coordination server, i.e. Headscale [IF31]. iOS runs one VPN at a time (IA10).
- **Log upload:** Tailscale documents **no log opt-out for iOS** [IF31]; with Headscale it probably still uploads (IA09). D8's disclosure applies to every iPhone and iPad in full: "the overlay app on this device sends its own diagnostic logs to Tailscale; asom cannot switch that off."
- **Local Network:** overlay traffic is VPN, so no prompt [IF09]; the LAN-direct path (D8(b)'s equal path) needs the prompt.
- **Inbound over the overlay** (M5): IA08, device-tested.

#### 4.5 mDNS/Bonjour

Not used in any scheduled phase: no `NSBonjourServices`, no `NWBrowser`, no multicast entitlement. Locating already-paired peers uses the QR's address literals and the peer's authenticated `HELLO` (T12). If D12 ever approves locate, the iOS cost is one `NSBonjourServices` entry plus the Local Network prompt (Bonjour always needs it [IF09]), foreground-only browsing, and nothing for the overlay path (Bonjour does not cross it).

#### 4.6 Byte accounting on Network.framework (resolves R2-OVERCLAIM-6 for Apple)

Network.framework does not expose TLS record sizes before a send; the framer sits above TLS. It does expose, per connection and path, `sentApplicationByteCount` (plaintext) and `sentTransportByteCount` (bytes into TCP), after the fact [IF14]. So on Apple stacks:
1. each frame's row records its **plaintext** length, made durable before the send (P3 unchanged);
2. at session close, a `SESSION` row records the `DataTransferReport` totals, and `overheadBytes = transportBytes − Σ plaintext frame bytes` (handshake, record headers, tags, alerts);
3. L-L15 holds on Apple as "Σ rows + overheadBytes = transport bytes of the report" (IA07), not as a per-record tap.
**Wording the invariant needs (IC-2):** "bytes are counted at or below the TLS layer; the method per stack is fixed in the conformance suite", not "at the TLS record layer" (R2-OVERCLAIM-6's second option). On a crash the report is lost and only plaintext frame bytes survive, stated.

#### 4.7 Rejected transports

- **Multipeer Connectivity:** Bonjour-based discovery and its own session security; would add discovery (stop-line) and a second trust path.
- **Wi-Fi Aware:** for pairing with Wi-Fi Aware *accessories* [IF36]; not an Apple-to-Android/Linux path; reconsider only if D26's car/appliance profiles ever need it.
- **URLSession WebSocket / HTTP to peers:** ATS cannot accept pinned self-signed chains [IF11].

---

### 5. Key storage tier for the node identity

#### 5.1 The algorithm question, resolved

| Candidate | Secure Enclave (iPhone/iPad) | Everywhere else | Verdict for asom |
|---|---|---|---|
| **ECDSA P-256 / SHA-256 (ES256)** | **yes**, the only classical signing curve [IF16] | StrongBox, TEE, TPM, JCA, swift-crypto [F06] | **Kept: the only manifest and node algorithm** (brief §5.1, C2). Swift signs with `SecureEnclave.P256.Signing` (NIK) or `P256.Signing` (per-export, leaf), emits `rawRepresentation` (64-octet r‖s) and **normalises to low-S** (IS1: swift-crypto produced high-S ~50 % of the time; the Enclave is assumed no different) |
| Ed25519 | **no** `SecureEnclave.Curve25519` exists [IF16]; CryptoKit has a *software* Curve25519 only | not in StrongBox; not in most TPMs [F06] | **Rejected.** A hardware-backed NIK on iPhone would be impossible, and one algorithm must serve every node |
| ML-DSA-65 / -87 | yes, iOS/iPadOS 26+ [IF16] | not in StrongBox/TPM today; JSSE/Conscrypt TLS support absent (design §2.2) | **Parked** for a post-quantum major: a new pin `alg` and a new DSSE `payloadType`; a hybrid (ES256 + ML-DSA) would break "exactly one signature" and needs its own design |

So the brief's §5 needs **no change**; this section records the Apple evidence and adds one producer rule to the vectors: "Swift producers normalise S; a producer vector signs with a key whose first signature is high-S and expects the normalised output".

#### 5.2 Tiers on iOS

| Key | Tier (`keyStorage`) | Where | What it protects against | What it does **NOT** guarantee |
|---|---|---|---|---|
| **NIK** (M4 on iPhone and iPad; generated at first mesh enable, never earlier) | T2 `secure-enclave` (self-reported: without A2 it is treated as A0, design §5.1) | Secure Enclave P-256, `.privateKeyUsage`, `WhenUnlockedThisDeviceOnly`, Team access group [IF16][IF19] | extraction by software; migration through backups or to a new device [IF19] | **use** by any code running in one of the owner's Team apps while unlocked; a jailbroken or kernel-compromised device; that the software using it is unmodified asom; that the key is attested (App Attest rejected) |
| Fallback NIK (no Enclave: simulator and CI only, IA02) | T1 `os-keystore` | software P-256 in the keychain, same access group | offline theft of a locked device's storage | same-app code; shown as "software key" in the Peers tab of every peer |
| Session leaf (14-day) | software, `WhenUnlockedThisDeviceOnly` | keychain; in process memory during handshakes | cross-device cloning (does not migrate) | a memory-reading attacker impersonates the node until the leaf expires or the NIK is revoked (design §2.1 cost) |
| Per-export key (M3 files) | `ephemeral` | CryptoKit `P256.Signing.PrivateKey()` in memory, discarded after signing; the export history keeps only (date, file sha256, fingerprint) (design §5.4) | a stable linkable device key in shared files | anything about who made the file unless the recipient compares the fingerprint out of band (design §5.4) |
| BYOK (only if D14 part B allows a `CloudOnly` tier) | T1 per app | the host app's own keychain group, `WhenUnlockedThisDeviceOnly`, non-sync; **never** in the shared node group (brief §10A.3: keys never move between surfaces) | backup/iCloud leakage | the host app's other code |

Rules: no key is `kSecAttrSynchronizable`; no iCloud Keychain; `SecureEnclave.isAvailable` is checked and the tier recorded truthfully; the Team access group is the security boundary, and the per-app mesh toggle inside it is code-enforced, not OS-enforced (stated, design §7.8).

#### 5.3 App Attest and DeviceCheck

| | Needs the network? | Proves | Does NOT prove | Verdict |
|---|---|---|---|---|
| App Attest `attestKey` | **yes**, contacts Apple once per key [IF18] | the key is in the Enclave of a genuine Apple device, bound to the Team + bundle App ID, and the app was valid at attestation | that measurements are honest; who the user is; anything after a later compromise that keeps the key usable | **Rejected** (design D12, IC-7): a new egress class to Apple, built for an operator server that asom does not have. Peers are owner-paired, so app provenance adds little |
| App Attest assertions | no (local signing) | continued use of an attested key, with a counter | nothing without a prior attestation someone verified | rejected with the above |
| DeviceCheck `DCDevice` | yes, via a server to Apple [IF18] | two persistent bits per device | — | **Rejected**: a persistent per-device channel held by Apple for a developer, i.e. a fingerprint |

CI enforces this: the linked-framework lint fails if `DeviceCheck.framework` appears in any Mach-O.

---

### 6. Inference backends and the benchmark baseline

#### 6.1 Backends

| Backend | Formats | Use in mesh-1 | Use later | Why |
|---|---|---|---|---|
| **llama.cpp XCFramework, Metal** [IF32] | GGUF, the catalogue's files by sha256 | none (no iOS node lends or benches in mesh-1) | **M3 bench and M5 lending**, `backend = metal`, at the same pinned commit as the other Metal builds (C11) | same files and hashes as every other node, so claims are comparable per C11 |
| **llama.cpp CPU** (same XCFramework, `n_gpu_layers = 0`) | GGUF | none | M3/M5 fallback; **CI simulator smoke**; the safe choice if IA01 proves true and GPU stop-before-background is unreliable | llama.cpp's abort callback works only on CPU [F18], so cancellation is immediate |
| MLX Swift [IF33] | MLX safetensors | none | later, separate claim rows | not GGUF; different files and hashes |
| Core AI (iOS 27) [IF34] | `.aimodel` converted from PyTorch | none | later, separate rows, only if the catalogue ever carries such files | conversion breaks file-hash comparability; ANE background restricted [IF07] |
| Foundation Models `SystemLanguageModel` [IF35] | Apple's own model | none | local-only helper for AsomBench itself if ever useful; **never advertised to peers** | not a catalogue model; availability varies by device and region |
| `PrivateCloudComputeLanguageModel` [IF35] | Apple server model | **forbidden** | forbidden | a cloud egress to Apple, not a user BYOK provider; would need a new egress class. A CI symbol lint forbids it |
| Core ML / ExecuTorch | converted models | none | not planned | conversion; superseded on Apple by Core AI |

**Engine lifecycle rules (iOS-specific additions to B6/B9):** `n_ubatch` chosen so one graph compute is ≤ 1 s on the device; on `sceneWillResignActive` the engine issues no new graph compute and waits for the current one; on `didEnterBackground` it must already be idle (IA01); the RAM guard reads `os_proc_available_memory()` [IF21] before load and after context creation; `MODEL_OOM` is typed.

#### 6.2 Benchmark-baseline consequence (directive D-C)

- **Already covered by MLCommons on Apple mobile hardware:** MLPerf Client benchmarks LLMs on **iPad Pro (M2 or newer, 16 GB)** with llama.cpp-Metal and MLX, collects no data, and does not run on iPhone [IF28][IF29]. MLPerf Mobile is on the iOS App Store, but whether its LLM workload runs on iOS is not stated (IA16) and its listing declares data collection linked to the user [IF30].
- **So "iPad coverage" is not a differentiator**, as directive C already says of Apple coverage. asom's iOS benchmark exists only for the three permitted differentiators: (i) the signed, verifiable manifest; (ii) plain text rendered from the same data; (iii) results feeding a router — and (iii) applies **only when the iPad lends (M5)**. An **iPhone benchmark feeds no router ever**, because an iPhone never lends.
- **Model sets:** Q1 (Qwen3, Apache-2.0) default; L1 (the MLPerf Mobile Llama set) opt-in behind the licence screen (D18). Note the overlap: MLPerf Client v2's list includes **Llama 3.1 8B and Qwen 3 8B** [IF29], both in asom's L1/Q1. Reports say "measured with MLPerf Mobile's model set and metric definitions" only under the comparability rule, never "MLPerf-comparable" (directive D-C; R2-OVERCLAIM-4).
- **Recommendation (iOS-D1):** keep M3's *code* (it validates the Swift core, the Metal engine lifecycle and the iPad's claims that M5 needs), ship it to the owner's devices only, and do not make M4 wait for it.

---

### 7. Runtime and code strategy for iOS/iPadOS

#### 7.1 Recommendation

**Swift (Swift 6 toolchain, Swift 6 language mode for new targets), one package `apple/` shared with macOS, SwiftUI placeholder UI.** No KMP (CLAUDE.md; D17); no JVM on iOS (third-party apps cannot JIT; AOT JVM routes unverified, `platforms.md` A01); no Rust/C core (it would be a second port anyway and adds a toolchain; design Option 1). The Swift code is an **independent implementation written from the spec prose**, kept aligned by the shared vectors in `lab/conformance/` (promoted to `conformance/` under D23).

#### 7.2 The package: the macOS section's `apple/Package.swift`, plus the iOS targets

The macOS section (§7.4 there) owns `apple/Package.swift` (swift-tools 6.0; `platforms: [.macOS(.v15), .iOS(.v17)]`; swift-crypto only `.when(platforms: [.linux])`). **This section adds targets; it does not create a second package**, except for the engine (a `binaryTarget` must not enter a package that builds on Linux). Spike IS1's module name `AsomDSSE` matches the macOS layout.

| Target | Contents | Linux | Owner | Phase |
|---|---|---|---|---|
| `AsomJSON` | strict tokenizer, JCS integer profile (UTF-16 key order) | yes | macOS §7.4 | L0.7 |
| `AsomDSSE` | PAE, base64, strict SPKI (T1), ES256 verify, per-export sign, DER↔raw, **low-S on produce** (IS1) | yes | macOS §7.4 | L0.7 |
| `AsomManifest` | typed decoder, 19-step verifier + 15a–15c, projections (M06 incl. file), renderers (M05), verification contexts incl. the fingerprint comparison (design §5.4) | yes | macOS §7.4 | L0.7 |
| `AsomBenchCore` | M04 derivation, M05 bodies, run-plan interpreter, governor FSM (engine-free), consent token, executor-trace vectors | yes | macOS §7.4 (M04) + **this section** (plan/FSM) | L0.7 / M3 |
| `asom-conformance` | executable printing one line per vector in the JVM runner's format, for `diff` | yes | macOS §7.4 | L0.7 |
| **`AsomContract`** | W00 constants (header names, error and peer codes, egress values), the Swift `InferenceClient` protocol (a translation of brief §10A.1, CD-10A), `RouteEcho` | yes | **iOS** | M3 |
| **`AsomLedger`** | Swift `RouteRecord` (13 v1 fields + design §8.4 columns), JSONL sink (append, `fsync` per row, fail-closed FC-1/FC-4/FC-5), W01b projections (echo + row), view-first export bytes | yes | **iOS** | M3 |
| **`AsomPlatform`** | `NodeKeyStore` (Enclave NIK, T1 fallback), `LeafIdentity` (S-A2), per-export key, `ThermalSampler`, `PowerProbe`, `MemoryProbe`, `LocalNetworkState`, `InterfaceClassifier`, file protection and backup exclusion helpers | compiles to empty (`#if canImport(UIKit) \|\| os(macOS)`) | **iOS** | M3 / M4 |
| `AsomWire` | `asom-mesh/1` frame codec (pure), T15 hygiene | yes | macOS names it; **iOS builds it** | M4 |
| `AsomTransport` | Network.framework TLS profile (§4.3), `VerifyPeerChain` (swift-certificates, T3), framer, dial budget, `DIAL`/`SESSION` rows with `DataTransferReport` reconciliation (§4.6) | no (`#if canImport(Network)`) | **iOS** | M4 |
| `AsomRequester` | pairing (URI parse, proof, typed SAS on the scanner side, T7), registry reader, `RemoteMeshClient`, T16 body normaliser, no-silent-downgrade fallback rule, attempt loop | no | **iOS** | M4 |
| **`AsomLender`** | `LendListener`, lend FSM (§3.4), lender decision table, lender rows | no | **iOS** | M5 |
| **separate package `apple/AsomEngine/`** | `binaryTarget` llama XCFramework (pinned), `LlamaEngine` (load, tokenize, generate, cancel between graph computes), `BenchEngine` adapter, stop-before-background hook | no | **iOS** | M3 |

Third-party code: swift-crypto (Linux lane only until M4), then swift-certificates + swift-asn1 + swift-crypto (incl. `_CryptoExtras`) at M4 [IF40] — **CD-D must list swift-crypto for the iOS app from M4**, not only for the Linux lane. Build tool: XcodeGen (not shipped) [IF41][IS3]. Nothing else.

#### 7.3 Conformance families the Swift code must pass, by phase

| Phase | Families |
|---|---|
| L0.7 | M01, M02, M03, M05, M06 (+ M04 once `bench-core` is frozen); lines diffed against the JVM runner (macOS section's `lane-diff` job) |
| M3 | + M04, W00, **W01b** (Swift `RouteRecord` → echo + row), executor-trace vectors (B23), producer low-S vector (§5.1) |
| M4 | + W04 (pairing), W05 (pins, certificate templates, strict SPKI negatives), W06 (frames, T15), W07 (live-state decode), **W08 client role**, W01b-reach, `authz/classification.json` for the per-app toggle |
| M5 | + W07-presence (with the PF rule of §3.4), **W08 server role**, lender decision-table vectors, lender row vectors |
| never on iOS | R01–R06 and M08 (no router and no `ClaimTracker` on a single-provider requester). **Consequence:** R2-OVERCLAIM-1 (peer-shapeable `decodeObs`) does not affect iOS until multi-provider choice, which is itself a D17 KMP re-escalation trigger |

#### 7.4 Drift cost (estimates)

- Swift size, from the design §3.4 (estimates): M3 ~6–8k lines, M4 ~5–7k, M5 ~4–6k, of which the L0.7 core (~3–4k) is shared with the macOS lane.
- Every normative change to the manifest, ledger, frame or pairing spec costs a Swift change plus vectors. The design's D17 triggers stand: (i) multi-provider choice on iOS; (ii) Swift conformance failures found after merge in two consecutive releases; (iii) Kotlin Swift export Stable. None has fired.
- **What the vectors do not catch:** platform behaviour (scene phases, GPU revocation, keychain groups, Local Network), covered only by the device checklist (§10.5); and shared misreadings of the prose, which the "self-oracled" tag keeps visible (R2-OVERCLAIM-8). The Swift lane is written from the prose by design, which makes it the natural *independent* implementation R2-OVERCLAIM-8 asks for — provided its author has no access to the Python generator, recorded in PROGRESS.md.

#### 7.5 UI

SwiftUI, placeholder-functional, against a token seam (`Tokens/TokenSeam.swift`: violet `#8E7BFF`, cyan `#35E0FF`, always with SF Symbol shape and a text label; red/green never carry meaning, Invariant 6). This needs **D14 part A (IC-7)** before the first SwiftUI app (M3).

---

### 8. Packaging and distribution

#### 8.1 Build and project

- `apple/AsomBench/project.yml` (XcodeGen) is committed; the `.xcodeproj` is generated in CI and locally, never committed (IS3 shows it can even be generated on Linux; building needs Xcode).
- Targets: `AsomBench` (application, iOS/iPadOS; `TARGETED_DEVICE_FAMILY = 1,2`; `SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD = NO`, `SUPPORTS_XR_DESIGNED_FOR_IPHONE_IPAD = NO`, `SUPPORTS_MACCATALYST = NO` — C10), `AsomBenchTests`, `AsomBenchUITests`.
- Deployment target per iOS-D3 (recommended 26.0); SDK: Xcode 26.6 on `macos-26` today, **iOS 27 SDK mandatory for uploads from April 2027** [IF26], available on the `xcode-27` preview image [FM37].
- Entitlements (allow-list, lint-enforced): `keychain-access-groups` (`$(TeamID).xyz.mdhv.asom.node`), `com.apple.security.application-groups` (`group.xyz.mdhv.asom`, M4), `com.apple.developer.kernel.increased-memory-limit` (M3/M5, optional), and **nothing else** unless an owner decision adds it (the background-GPU entitlement only under iOS-D9(b), iPad).
- `PrivacyInfo.xcprivacy`: `NSPrivacyTracking = false`, no tracking domains, collected data types **none**, required-reason APIs declared (IA11).
- App Privacy label: "Data Not Collected". True only because no SDK is linked and nothing uploads; the P7 contribution (v2) would change it.

#### 8.2 Signing and notarisation

- iOS apps are signed with Apple Development (device builds) or Apple Distribution (TestFlight/App Store) identities; **notarisation applies only to alternative distribution** (EU, Japan, Brazil marketplaces or web distribution) [IF25].
- CI builds unsigned (`CODE_SIGNING_ALLOWED=NO`) and runs simulators, which need no signing. Signing happens on the owner's Mac (iOS-D6).

#### 8.3 Distribution channels

| Channel | Review | Automatic egress it brings | Fit |
|---|---|---|---|
| **Development provisioning** (Xcode install to registered devices; up to 100 per family per year [IF24]) | none | none known (IA12) | **Recommended for M3–M5 on the owner's devices.** Profiles last a year on a paid account; 7 days on a free Personal Team with 3 devices and 3 apps [IF24] |
| Ad hoc | none | none known | same, for installing without Xcode |
| TestFlight internal (≤ 100) / external (≤ 10,000) | first build to a group is reviewed [IF23] | **crash reports shared with the developer regardless of device settings** [IF23] | acceptable only with that disclosed (IC-7 must mention it); 90-day builds |
| App Store | full review (IA15) | crash reports only from users who opted in [IF23] | optional, earliest at M5, only if the owner wants public users |
| Alternative marketplaces / web distribution | notarisation (an Apple review) [IF25] | — | relevant only for public distribution in the EU, Japan or Brazil; irrelevant for the owner's own devices |

**Updates** reach devices by re-installing from Xcode (development), a new TestFlight build, or the App Store. **No in-app update check** (not a permitted egress class) and **no downloaded code** (2.5.2 [IF22]). Model files are data, fetched only after the consent sheet shows their size (4.2.3(ii) [IF22]), ledgered as `download`.

#### 8.4 Store-rule compliance map (for App Review Notes, 2.3.1(a))

| Guideline [IF22] | asom's answer |
|---|---|
| 2.4.2 heat/battery | charger required for sustained plans and for lending; thermal soft stop at `fair` + 120 s and hard stop at `serious` (B9); no background processing |
| 2.5.2 no downloaded code | GGUF weights are data interpreted by a bundled engine; no scripts, plug-ins or code download |
| 2.5.4 background services | none declared; the lend screen serves only in the foreground |
| 2.5.14 recording activity | the ledger records network events, not user inputs; verbose bodies (if ever on iOS) need explicit consent and a visible indicator |
| 4.2.3(ii) downloads | the consent sheet shows the exact byte count before any model download |
| 5.1.2(i) third-party AI | `CloudOnly` (if D14 part B) asks explicit permission per provider; the mesh consent names the user's own devices and says the request text is processed there |
| age-rating questionnaire | AI-generated output must be considered (secondary source, medium; answer at submission) |
| export compliance | IA19; `ITSAppUsesNonExemptEncryption` set deliberately [IF37] |

---

### 9. What GitHub Actions hosted runners can honestly verify

A new workflow `.github/workflows/apple-ios.yml` (AD-3; `ci.yml` untouched), triggered on `apple/**` and `lab/conformance/**`. The Swift-lane jobs (`apple-swift-lane`, `jvm-lines`, `lane-diff`) are the macOS section's and are **not duplicated** here.

| Job | Runner | Verifies | Does NOT verify |
|---|---|---|---|
| (macOS section) `apple-swift-lane` | `macos-26` + `swift:6.1-noble` container | pure targets on CryptoKit and swift-crypto; per-vector lines equal the JVM's | anything iOS-specific |
| `ios-package-sim` | `macos-26` | `xcodebuild -scheme AsomKit-Package -destination 'platform=iOS Simulator,name=iPhone 17,OS=26.5' test` for every package target incl. `AsomLedger`, `AsomPlatform` (simulator paths: T1 key fallback, file protection attributes set, backup exclusion set) | Enclave, keychain groups across signed apps, file protection *enforcement* (not exercised by a simulator run; assumption) |
| `ios-app-sim` | `macos-26` | `brew install xcodegen && xcodegen generate`; app + unit + UI-smoke tests on `iPhone 17` and `iPad Pro 11-inch (M5)` simulators; the UI smoke asserts no network connection is opened before consent (via a test URLProtocol and an NWConnection factory seam) | real devices, real scene-phase timings, background suspension |
| `ios-app-sim-27` | `xcode-27` (preview) | the same with the iOS 27 SDK (needed from April 2027 [IF26]); non-blocking while the image is in preview | — |
| `ios-device-build` | `macos-26` | `xcodebuild -sdk iphoneos -configuration Release CODE_SIGNING_ALLOWED=NO build` (arm64 device compile), then the lints below on the built `.app` | that it installs or runs; signing |
| `ios-lints` | `macos-26` | `plutil -lint`; no `NSAppTransportSecurity`, no `NSBonjourServices`, no `UIBackgroundModes`; entitlement allow-list; **linked-framework lint**: no `DeviceCheck`, `CloudKit`, `AdSupport`, `AppTrackingTransparency`, no `Firebase*`/`Crashlytics`, and no `PrivateCloudComputeLanguageModel` symbol | behaviour |
| `engine-sim-smoke` | `macos-26` | llama XCFramework build at the pinned commit (iOS-D7(b)) or checksum-verified download; a tiny GGUF fetched by sha256 generates 16 tokens on the **CPU** backend inside the simulator; cancel between graph computes | Metal (absent or capped on runners, IA03); speed; thermal; GPU revocation |
| `export-roundtrip` | `macos-26` → `ubuntu-24.04` | the simulator's `ci` plan (CPU, tiny model) writes an `asom.bench/1` and a signed `.asom-manifest.json`; the JVM lab verifier and `xcheck.py` verify it in another job; the report banner reads "SIMULATOR — NOT DEVICE EVIDENCE" | device numbers |
| `w08-sim` (M4, then M5) | `macos-26` | the JVM hostile reference node (lab `mesh-proto`, JDK 21) on `127.0.0.1` (test-only, lab rule 5); XCTest in the simulator dials it: every bad chain shape, missing client cert, wrong/absent ALPN, resumption and early-data attempts, REVOKED/SUSPENDED pins; asserts no `pre_shared_key` in ClientHellos and a client CertificateVerify in every session. M5 adds the server role (the simulator's listener bound to `127.0.0.1`, test builds only) | Local Network privacy (the simulator ignores it [IF09]); overlay; real Wi-Fi |
| `ledger-durability` | `macos-26` and `ubuntu-24.04` | a forked process appending through `AsomLedger` is SIGKILLed at each durability point; the rows before the kill are intact (design §8.4 honest tests) | power loss (never claimed) |

**Remains NEEDS-DEVICE-VALIDATION** (§10.5): Secure Enclave NIK and signing (IA02); keychain access group sharing between two signed apps (S-A12, IA05); `sec_identity` from the software leaf (S-A2, IA04); Network.framework knobs on hardware (IA06); the Local Network prompt, denial and re-grant; Metal throughput, thermal curve, memory limits (IA13, IA17); **background during a Metal decode** (IA01); the 30 s grace in practice; Tailscale inbound and log upload (IA08, IA09); Low Power Mode; App Review (IA15).

---

### 10. Implementation scaffold plan

#### 10.1 When any of this may start (read first)

- **I0 (the Swift lane)** is lab work: the design's L0.7 under D1a **and** D24(b) (ships nothing). It is the macOS section's MC0; this section adds only the iOS simulator job. The first slice ran here (IS1) and in the macOS section (FM42).
- **I1–I2 (M3)** need: v2 shipped; D7; D14 part A (IC-7); D24; iOS-D1, iOS-D2, iOS-D3. Under D24(b), M3 runs in parallel with D-v2, which R2-CONFORMANCE-8 says must be recorded as a departure from roadmap §0's strict sequence (an RT row ruled by D24).
- **I3 (M4)** needs: M1 shipped; D15; D2, D3, D5 ruled; D14 part B if a `CloudOnly` tier holds keys; **not** M3's store release (iOS-D1(b)); spikes S-A2, S-A9 (Network.framework column), S-A10, S-A12 passed on a device.
- **I4 (M5)** needs: M4; D16 and iOS-D4.
- A **Mac with Xcode** and the **Apple Developer Program** are needed for every device item; without them all device work is `BLOCKED(no Mac)`.

#### 10.2 File tree (additions; `apple/Package.swift` itself is the macOS section's)

```
apple/
  Package.swift                               # (macOS §7.4) + iOS targets: AsomContract, AsomLedger, AsomPlatform,
                                              #   AsomWire, AsomTransport, AsomRequester, AsomLender (table §7.2);
                                              #   swift-certificates + swift-asn1 added at M4 (CD-D, SIGN-OFF)
  Sources/AsomContract/Constants.swift        # W00: header names, error codes, egress values, peer codes (closed enums)
  Sources/AsomContract/InferenceClient.swift  # Swift translation of brief §10A.1 (chat, stream, embed, models); CD-10A
  Sources/AsomContract/RouteEcho.swift        # the echo projection of RouteRecord (Invariant 9)
  Sources/AsomLedger/RouteRecord.swift        # 13 v1 fields + design §8.4 columns; lab names until promotion (LabEgress rule)
  Sources/AsomLedger/JsonlSink.swift          # O_APPEND write + fsync per row; throws; never swallows intent-row failures
  Sources/AsomLedger/Projections.swift        # W01b: record -> (echo map, JSONL row) from one value
  Sources/AsomLedger/Export.swift             # exact bytes for the view-first share sheet
  Sources/AsomPlatform/NodeKeyStore.swift     # Enclave NIK (+T1 fallback), access group, tier reporting
  Sources/AsomPlatform/LeafIdentity.swift     # 14-day leaf; SecIdentity via keychain, PKCS#12 fallback (S-A2)
  Sources/AsomPlatform/ExportKey.swift        # per-export P256 key; fingerprint (base32 of first 128 bits of sha256(SPKI))
  Sources/AsomPlatform/ThermalSampler.swift   # thermalState + notification -> band 0/1/2 (nominal / fair / serious+)
  Sources/AsomPlatform/PowerProbe.swift       # batteryState/batteryLevel, Low Power Mode
  Sources/AsomPlatform/MemoryProbe.swift      # os_proc_available_memory, advisory only
  Sources/AsomPlatform/StorageHygiene.swift   # completeUnlessOpen + isExcludedFromBackup on every asom path
  Sources/AsomPlatform/NetworkState.swift     # NWPathMonitor interface classes; localNetworkDenied detection
  Sources/AsomWire/                           # (M4) FrameCodec.swift, Frames.swift, Hygiene.swift (T15)
  Sources/AsomTransport/                      # (M4) TLSProfile.swift (§4.3), VerifyPeerChain.swift, AsomFramer.swift,
                                              #   Dialer.swift (budgets, DIAL rows), TransferAccounting.swift (§4.6)
  Sources/AsomRequester/                      # (M4) PairingURI.swift, PairingProof.swift, SAS.swift, RegistryReader.swift,
                                              #   BodyNormaliser.swift (T16), RemoteMeshClient.swift, FallbackPolicy.swift
  Sources/AsomLender/                         # (M5) LendListener.swift, LendFSM.swift, DecisionTable.swift, LenderRows.swift
  Tests/AsomConformanceTests/                 # (macOS §7.4) + W00, W01b, W04-W08, W07-presence families as phases land
  Tests/AsomLedgerTests/  Tests/AsomPlatformTests/  Tests/AsomTransportTests/  Tests/AsomLenderTests/
  Tests/AsomTransportTests/W08ClientTests.swift   # dials the JVM hostile node on 127.0.0.1 (test-only)
  AsomEngine/                                 # SEPARATE package (binaryTarget; never built on Linux)
    Package.swift                             # .binaryTarget(path: "Artifacts/llama.xcframework") or (url:checksum:) per iOS-D7
    llama.cpp.pin                             # tag bNNNN, commit sha, xcframework zip sha256, build flags (C11)
    scripts/build-xcframework.sh              # runs upstream build-xcframework.sh ios-sim ios-device at the pinned commit
    Sources/AsomEngine/LlamaEngine.swift      # load/unload/tokenize/generate/cancel-between-computes; n_ubatch per tier
    Sources/AsomEngine/BenchEngineAdapter.swift
    Sources/AsomEngine/LifecycleGuard.swift   # stops GPU work on willResignActive; asserts idle at didEnterBackground
    Tests/AsomEngineTests/TinyModelTests.swift  # CPU backend, model from $ASOM_TINY_GGUF (sha256-checked), never committed
  AsomBench/                                  # the iOS/iPadOS app
    project.yml                               # XcodeGen spec (§10.3); the .xcodeproj is generated
    AsomBench.entitlements                    # allow-list of §8.1
    PrivacyInfo.xcprivacy                     # §8.1
    App/AsomBenchApp.swift  App/RootView.swift  # tabs: Run, Reports, Verify, Ledger; Peers (M4); Lend (M5, iPad only)
    Features/Run/ConsentSheet.swift           # exact download bytes; L1 licence screen; charger rule; "Nothing is uploaded" (B20)
    Features/Run/RunController.swift          # scene-phase aware; B9 aborts; partial finalisation
    Features/Export/ExportView.swift          # view-first bytes; fingerprint code + QR; share sheet
    Features/Verify/FileVerifyView.swift      # "signed, but the signer is unverified" until the fingerprint is compared
    Features/Ledger/LedgerView.swift          # all apps' ledgers from the app group; export
    Features/Peers/                           # (M4) PairScanView (VisionKit/AVFoundation), SASView, PeersList, AppOptIn
    Features/Lend/LendScreen.swift            # (M5, iPad) full-screen watched object; idle timer; LAContext for settings
    Tokens/TokenSeam.swift                    # violet/cyan + shape + label; placeholder (Invariants 6, 7)
    Tests/AsomBenchTests/  Tests/AsomBenchUITests/
  ci/
    lint-info-plist.sh                        # §9 ios-lints (plist keys)
    lint-entitlements.sh                      # allow-list
    lint-linked-frameworks.sh                 # otool -L / nm deny-list incl. DeviceCheck and PCC symbols
    fetch-tiny-model.sh                       # URL + sha256, CI only
    apple-ios.yml                             # canonical workflow; copied to .github/workflows/ by the builder
  docs/
    IOS.md                                    # roles and honest limits; the lend-screen promise; Tailscale log disclosure;
                                              #   TestFlight crash-report note; what the Enclave tier does not guarantee
    DEVICE_CHECKLIST_IOS.md                   # §10.5 items with exact steps and expected results
```

#### 10.3 `project.yml` (normative sketch; IS3 generated its skeleton)

```yaml
name: AsomBench
options: { bundleIdPrefix: xyz.mdhv.asom, deploymentTarget: { iOS: "26.0" } }   # iOS-D3
packages:
  AsomKit: { path: .. }                   # apple/Package.swift
  AsomEngine: { path: ../AsomEngine }
targets:
  AsomBench:
    type: application
    platform: iOS
    sources: [App, Features, Tokens]
    entitlements: { path: AsomBench.entitlements }
    dependencies:
      - { package: AsomKit, product: AsomManifest }
      - { package: AsomKit, product: AsomBenchCore }
      - { package: AsomKit, product: AsomLedger }
      - { package: AsomKit, product: AsomPlatform }
      - { package: AsomEngine, product: AsomEngine }
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: xyz.mdhv.asom        # iOS-D2
        TARGETED_DEVICE_FAMILY: "1,2"
        SUPPORTS_MACCATALYST: NO
        SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD: NO       # C10
        SUPPORTS_XR_DESIGNED_FOR_IPHONE_IPAD: NO
        GENERATE_INFOPLIST_FILE: YES
        INFOPLIST_KEY_NSLocalNetworkUsageDescription: "asom connects only to devices you paired, and only when you ask it to."
        INFOPLIST_KEY_NSCameraUsageDescription: "Scan the pairing code shown on your other device."
        SWIFT_VERSION: "6.0"
  AsomBenchTests:   { type: bundle.unit-test, platform: iOS, sources: [Tests/AsomBenchTests], dependencies: [{ target: AsomBench }] }
  AsomBenchUITests: { type: bundle.ui-testing, platform: iOS, sources: [Tests/AsomBenchUITests], dependencies: [{ target: AsomBench }] }
```

#### 10.4 Steps, gates and estimates

Gates follow brief §12: real output pasted into `PROGRESS.md`; CI-only gates labelled `CI-ONLY`; device items NDV. Expected outputs are what a passing run prints, not results.

| Step | Needs | Contents | Gate (command → expected) | Estimate (eng-weeks) |
|---|---|---|---|---|
| **I0** Swift lane (L0.7; with macOS MC0) | D1a, D24(b) | `AsomJSON`, `AsomDSSE`, `AsomManifest`, `AsomBenchCore` (M04), `asom-conformance`, conformance tests | 1. Linux container: `swift test --package-path apple` → `Executed N tests, with 0 failures`.<br>2. `swift run --package-path apple asom-conformance all lab/conformance > swift.lines; diff jvm.lines swift.lines` → no output, exit 0 (M01–M03, M05, M06).<br>3. CI-ONLY `ios-package-sim`: `xcodebuild -scheme AsomKit-Package -destination 'platform=iOS Simulator,name=iPhone 17,OS=26.5' test` → `** TEST SUCCEEDED **`.<br>4. vectors stay **self-oracled** until the Swift author, without access to the generator, is recorded in PROGRESS.md (R2-OVERCLAIM-8) | 3–5 (shared with macOS; the design's L0.7) |
| **I1** M3 foundations | v2 shipped; D7, D14A, D24; iOS-D1/2/3 | `AsomContract`, `AsomLedger`, `AsomPlatform` (bench probes), `AsomEngine` package + pin, `AsomBench` skeleton, `project.yml`, `ci/*`, `apple-ios.yml` | 1. `swift test --package-path apple --filter 'AsomLedgerTests\|AsomConformanceTests'` → 0 failures, incl. every W01b vector (the runner prints the count; a family that exercised nothing fails).<br>2. CI-ONLY `ledger-durability` → `SIGKILL at every durability point: rows intact` (count printed).<br>3. CI-ONLY `xcodegen generate --spec apple/AsomBench/project.yml` → `Created project at …`; `xcodebuild -project apple/AsomBench/AsomBench.xcodeproj -scheme AsomBench -destination 'platform=iOS Simulator,name=iPad Pro 11-inch (M5)' test` → `** TEST SUCCEEDED **`.<br>4. CI-ONLY `ios-device-build` → `** BUILD SUCCEEDED **`; `ci/lint-*.sh` → `PASS` (3 lines).<br>5. CI-ONLY `engine-sim-smoke` → `generated 16 tokens (cpu, simulator)`; `cancel latency < 1000 ms (cpu)` | 3–4 |
| **I2** M3 app | I1 | Run (quick/standard/ci plans through `BenchEngine`), consent sheets, export with per-export key and fingerprint QR, verifier view, ledger view | 1. CI-ONLY `export-roundtrip`: the simulator's `ci` plan writes a file; the JVM verifier prints `FILE: signed, signer unverified (not compared)` and, with the fingerprint, `PINNED_BY_FINGERPRINT(typed)`; `xcheck.py` → `OK`.<br>2. UI smoke: `no network before consent: PASS`.<br>3. NDV: DV-I1 … DV-I7 on the owner's iPhone and iPad (Metal run, thermal stops, background without a crash, memory tiers, llama-bench parity on a Mac within tolerance, B23) | 4–6 |
| **I3** M4 requester | M1; D15; D2/D3/D5; S-A2/S-A9/S-A10/S-A12 on device | `AsomWire`, `AsomTransport`, `AsomRequester`, Peers tab, app-group opt-in, `RemoteMesh` in one other owner app | 1. `swift test` → W04–W07 families 0 failures (Linux for `AsomWire`, simulator for the rest).<br>2. CI-ONLY `w08-sim` → `W08 client: 0 accepted bad chains; 0 ClientHellos with pre_shared_key; CertificateVerify in 100% of sessions`.<br>3. `TransferAccounting` test: `Σ frame rows + overheadBytes == DataTransferReport.sentTransportByteCount` on a scripted loopback session (CI-ONLY; IA07 on hardware is NDV).<br>4. NDV: DV-I8 … DV-I12 (iPhone streams from the Deck or Dell; the lender's ledger shows the iPhone node and no app identity; fallback only per pre-set policy; LAN prompt and denial; overlay without a prompt) | 6–10 (incl. ~1–1.5 for the four spikes) |
| **I4** M5 iPad lender | M4; D16; iOS-D4 | `AsomLender`, lend screen, PF presence rule, lender rows | 1. `swift test` → W07-presence (PF) and decision-table vectors 0 failures.<br>2. CI-ONLY `w08-sim` server role → all vectors pass; the listener refuses to bind anything but the configured address (test asserts `requiredLocalEndpoint`).<br>3. NDV: DV-I13 … DV-I17 (Android phone borrows from the iPad over LAN and overlay; background mid-stream → `GOAWAY`, both ledgers, no crash; `serious` drains; the 10-min hold-down; App Review outcome if submitted) | 4–7 |
| **I5** device-validation support | each phase | `docs/DEVICE_CHECKLIST_IOS.md`, `docs/IOS.md` | the owner's pasted outputs; items stay NDV until confirmed | 1–2 |
| **Total** | | | | **≈ 21–34** (≈ 18–29 excluding the shared I0) |

**Estimate assumptions.** One engineer fluent in Swift and Apple frameworks; an Apple-silicon Mac and the Developer Program exist; the lab vectors (L0.1/L0.2) and the frozen `bench-core` exist before I0/I1; the JVM hostile node exists (L0.5); spikes pass on the first design (a failed S-A2 or S-A12 adds 1–3 weeks); App Review cycles are not counted. These are estimates, not measurements; the design's §9.4 gives 21–36 for the same scope.

#### 10.5 Device checklist (NEEDS-DEVICE-VALIDATION; the owner pastes real outputs)

| ID | Device | Step | Expected |
|---|---|---|---|
| DV-I1 | iPhone, iPad | Settings shows asom never requested Local Network during an M3-only session | no "Local Network" entry for asom |
| DV-I2 | iPhone, iPad | M3 `quick` plan with Q1 T1 on Metal | report renders; `backend=metal`; ledger has only `download` rows (plus `catalogue` if fetched) |
| DV-I3 | iPhone | `standard` plan on charger | soft stop at `fair` + 120 s or hard stop at `serious`, logged; partial result finalised |
| DV-I4 | iPad | export a signed report; verify on the Deck with the JVM verifier, comparing the fingerprint by typing it | `PINNED_BY_FINGERPRINT(typed)` |
| DV-I5 | iPhone | during a Metal decode, swipe home; repeat 20 times | **no crash** (IA01); run aborted with `backgrounded`; each time a partial row |
| DV-I6 | iPad Pro 16 GB | 8B Q4_K_M decode/prefill, 10-min sustain | numbers recorded; they replace IA17 (never quoted as speed-ups before) |
| DV-I7 | each device | load tiers until `MODEL_OOM`; log `os_proc_available_memory()` before and after | the largest tier that fits; with and without `increased-memory-limit` |
| DV-I8 | iPhone | S-A12: two owner apps use one NIK; a third app from another Team cannot | shared use works; foreign access fails |
| DV-I9 | iPhone + Deck | pair by QR over LAN; confirm the prompt appears at the first dial; deny, then re-grant | `DIAL` outcome `local-network-denied`, then success after re-grant |
| DV-I10 | iPhone + Dell | pair and stream over Tailscale (Headscale if D8(b)) | no Local Network prompt; `peerPath=overlay` on both ledgers |
| DV-I11 | iPhone | leave the host app mid-stream | stream finishes within the grace, or `CANCEL` + `interrupted` rows on both sides; typed error in the app |
| DV-I12 | iPhone | switch the home lender off, send a request | no silent cloud call; fallback only if pre-set policy allows it, and the echo says so |
| DV-I13 | iPad + Android | Android borrows from the iPad (lend screen, charging) over LAN | served; both ledgers joined on `attemptId` |
| DV-I14 | iPad | pull down Control Center during a stream | DRAINING at once; current attempt ends per §3.2; SERVING not re-published for 10 min |
| DV-I15 | iPad | lock with the side button during a stream | listener closed; `GOAWAY`; no crash; lender rows present |
| DV-I16 | iPad | heat to `serious` with a sustained stream | drains; `PEER_UNAVAILABLE` to the requester |
| DV-I17 | iPad | packet capture at the gateway with the Tailscale tunnel up (IA09) | log.tailscale.com contacted or not: recorded for the D8 disclosure |

---

### 11. Owner decisions specific to this platform, and risks

#### 11.1 Owner decisions

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **iOS-D1** | What is the first Apple deliverable, and does M4 wait for M3? (R2-DIRECTIVES-6) | (a) As D24(b): M3 standalone benchmark app on iPhone and iPad after v2; M4 after M3 and M1. (b) M3's code after v2 as an **owner-devices-only** app (development provisioning, no public submission); **M4 gated on M1, D15 and the AsomKit gates, not on M3's release**; public release question deferred to M5. (c) No M3: the first iOS app is M4; the iPad benchmark arrives with M5 | **(b).** M3 is the cheapest place to validate the Swift core, the Metal lifecycle (IA01) and the iPad claims M5 needs, but an iPhone benchmark feeds no router and MLPerf Client already covers iPad benchmarking [IF29]; publishing it would be the me-too directive C warns against |
| **iOS-D2** | Bundle ID and app identity (permanent once registered) | (a) `xyz.mdhv.asom.bench` (AF-1's naming; misleading once the app hosts pairing and lending). (b) One app `xyz.mdhv.asom`, display name "asom", Xcode target `AsomBench` in `apple/AsomBench/`, hosting bench (M3), pairing and verifier (M4), lend screen (M5, iPad). (c) Two apps: a bench app and a separate asom host at M4 | **(b)**: one Team-signed host for the node key and registry; not a rename (the anchor is unchanged) |
| **iOS-D3** | Minimum iOS/iPadOS | (a) 26.0: TLS 1.2 floor and hybrid PQ key exchange by default [IF12], `BGContinuedProcessingTask`, `NetworkListener`. (b) 17.0: more devices, none of the above. (c) 27.0: Core AI, but excludes devices not yet updated | **(a)**, subject to the owner's device inventory (package floor stays iOS 17 per the macOS section, so the Swift lane is unaffected) |
| **iOS-D4** | iPad lend conditions (refines D16) | charging: required / battery ≥ 80 % allowed; max session: 1 h / 2 h / none; serving backend: Metal with stop-before-background / CPU only; iPhone lending: never / allowed | **charging required; 2 h cap then re-confirm; Metal with the lifecycle guard, falling back to CPU automatically if DV-I5 ever crashes; iPhone never.** Also adopt §2.3's rewording of "re-authentication to leave" |
| **iOS-D5** | Distribution channel | (a) development/ad hoc provisioning to the owner's devices; (b) TestFlight (crash reports shared automatically [IF23]); (c) App Store; (d) alternative marketplaces (EU, JP, BR only [IF25]) | **(a)** for M3–M5; (c) optional at M5; (d) not relevant for own-device use. If (b) is used, IC-7 and the about page state the crash-report sharing |
| **iOS-D6** | Signing in CI | (a) none: CI builds unsigned; the owner archives and signs on their Mac. (b) App Store Connect API key + distribution certificate as GitHub secrets for automated uploads | **(a)** (no signing secrets in a public repo's CI); revisit only with (c) of iOS-D5 |
| **iOS-D7** | llama.cpp XCFramework provenance | (a) upstream release zip pinned by SwiftPM checksum [IF32]; (b) built in CI from the pinned commit with `build-xcframework.sh`, same commit as the other Metal builds (C11); (c) vendored source | **(b)**: one fewer trusted binary, known build flags for the manifest's `buildFlags`; (a) acceptable for local development |
| **iOS-D8** | Confirm the signature algorithm | (a) ES256 only; Ed25519 rejected; ML-DSA-65 parked for a PQ major. (b) Add Ed25519 for software-only keys (per-export) | **(a)**: one verifier path everywhere; the Enclave cannot hold Ed25519 [IF16] |
| **iOS-D9** | `BGContinuedProcessingTask` use | (a) none in M3–M5. (b) Allow user-started long requests (M4.1) and benchmark continuation on iPads with the GPU resource, shown in the system Live Activity; results flagged "continued in background" | **(a)** for the MVP; (b) is honest (visible, user-started, cancellable [IF05]) but adds an entitlement and a new measurement condition |

**Dependencies on design decisions (not new here):** D14 part A (IC-7) before M3, with the Apple analogue of Invariant 8 extended to: no CloudKit/iCloud sync, no App Attest/DeviceCheck, no `PrivateCloudComputeLanguageModel`, no third-party SDKs, backup exclusion, and a disclosure of TestFlight crash sharing if TestFlight is used; D15(b); D16; D24; D8 (disclosure of the iOS Tailscale log upload).

#### 11.2 Corrections this section asks the reviser to make

1. **§3.1 matrix, iOS PF:** "not recommended" → **"NO (code-gated off); iPad only"**; add the holon-completeness note (iPhone and iPad are borrow-only for other apps) (R2-DIRECTIVES-4).
2. **§9.3 M5 / D16:** replace "re-authentication to leave" with §2.3's four implementable rules.
3. **§7.4 LP-1:** add the PF exception of §3.4 (lend screen frontmost is consent; leaving it is presence) and W07-presence PF vectors (R2-OVERCLAIM-9).
4. **IC-2 / §8.4:** make byte accounting platform-neutral and add the Network.framework method of §4.6 plus IA07 (R2-OVERCLAIM-6).
5. **Engine rules B6/B9 for iOS:** "stop issuing GPU work on resign-active; the background grace is not inference time" (IF05, IF08, IA01).
6. **`trust.md` §4.2:** `UIScreen.isCaptured` is deprecated in iOS 27; use `UITraitCollection.sceneCaptureState` [IF38].
7. **§5.1 / §5.3:** add "Secure Enclave also offers ML-DSA-65/87 on iOS 26+ (parked)" and "Swift producers must normalise S" (IS1) with a producer vector.
8. **CD-D:** swift-crypto (with `_CryptoExtras`) enters the **iOS app** at M4 through swift-certificates [IF40], not only the Linux lane; update the count.
9. **§6.0 D-iii / directive C text:** Apple mobile LLM benchmarking is covered by MLPerf Client on iPad Pro (16 GB); iPhone is not covered by MLPerf Client, but coverage is not a differentiator either way [IF29].
10. **§9.3 M4 entry:** drop "M3"; use "AsomKit core gates green (I0/I1)" (iOS-D1(b), R2-DIRECTIVES-6).
11. **Location of the Swift lane:** `apple/` at the repo root (AD-3, macOS section), not `lab/apple` (design L0.7 text); vectors are read from `lab/conformance/`.
12. **Appendix A additions:** IF01, IF04, IF05, IF08, IF09, IF11, IF12, IF16, IF18, IF23, IF29 are load-bearing for the design's Apple claims.

#### 11.3 Risk table

| # | Risk | Severity | Mitigation | Residual (stated) |
|---|---|---|---|---|
| IR1 | ggml-metal work in flight at backgrounding **aborts the app** (IA01) | high | LifecycleGuard stops GPU work on resign-active; `n_ubatch` ≤ 1 s per compute; DV-I5 ×20; automatic CPU fallback | a transition faster than one compute can still crash; each crash leaves an outcome-unknown row |
| IR2 | `verifyPeerChain` wired wrong in the Network.framework verify block (K2) | critical | one trust path; `SecTrustEvaluate*` forbidden by lint; every error path completes `false`; W08 both roles in CI | a path W08 does not exercise |
| IR3 | S-A12 (shared Enclave key) or S-A2 (leaf `sec_identity`) fails | high (blocks M4 as designed) | PKCS#12 fallback for the leaf; for S-A12, a T1 software NIK in the Team group, labelled | T1 weakens the tier to "software key" on every peer's screen |
| IR4 | No Apple-silicon Mac / no Developer Program | critical (blocks every device item) | owner inventory before D24; CI still runs simulators | nothing on-device can be claimed |
| IR5 | An unlocked, unattended iPad while lending | high (impact) | charging only, 2 h cap, `LAContext` for settings and other tabs, leaving stops serving, disclosure | anyone at the iPad can use it until they leave the lend screen |
| IR6 | App Review rejects M3 or M5 (2.4.2, 2.5.2, 2.5.4) | medium | own-device distribution (iOS-D5(a)); Review Notes per §8.4 | public availability uncertain |
| IR7 | Tailscale iOS uploads its own logs with no opt-out [IF31] (IA09) | medium-high | D8 disclosure; LAN-direct equal path | an overlay user's device graph and timings reach the overlay vendor |
| IR8 | TestFlight shares crash reports automatically [IF23] | medium | development provisioning; disclose if TestFlight is used | OS-level egress outside asom's control |
| IR9 | Swift/JVM drift (K18) | medium | shared vectors, `lane-diff`, independent authorship recorded, D17 triggers | shared misreadings of the prose |
| IR10 | Presence inference from the lend screen's FSM (K28) | medium | PF presence rule, 10-min hold-down, one decline code | a peer learns when the iPad's owner leaves the lend screen |
| IR11 | Byte accounting inexact before send on Network.framework | medium | per-frame plaintext rows + report reconciliation (§4.6) | a crash loses the transport-overhead figure for that session |
| IR12 | Memory kills (jetsam) on model load | medium | RAM guard with `os_proc_available_memory()`; tier caps from DV-I7 | limits change per OS version |
| IR13 | Xcode/SDK churn: SDK 27 required from April 2027 [IF26]; runner images lag | medium | `ios-app-sim-27` job on the `xcode-27` image; explicit `xcode-select` | preview images can break |
| IR14 | CI green read as device evidence (no Enclave, no Metal, no Local Network privacy in the simulator) | medium | every CI gate labelled CI-ONLY; §10.5 checklist; banners on simulator reports | reviewers skimming |
| IR15 | "MLPerf" naming or comparability overclaimed on an Apple report | medium | never self-stamp; the comparability rule; "Built with Llama" for L1 | users compare numbers anyway |
| IR16 | Background model download outcome rows delayed or lost (force-quit) | low | intent before enqueue; reconciliation row at next launch | "outcome unknown" is honest but uninformative |
| IR17 | Export-compliance answer wrong (IA19) | low-medium | owner answers the questionnaire; `ITSAppUsesNonExemptEncryption` set deliberately | legal interpretation |
| IR18 | Local Network denied, LAN path dead | low | `local-network-denied` state and Settings guidance; overlay path | user confusion |
