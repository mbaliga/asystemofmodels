# Platform section: macOS (Apple silicon first; Intel noted) — asom-desktop node on macOS, and the shared `apple/` Swift package

**Date:** 2026-09-30 · **Status:** design input for the reviser. Nothing here is approved, frozen or executable.
**Scope:** directive D-A and D-E put macOS in scope. This section replaces `ASOM_MESH_DESIGN.md` r2 §3.1's macOS column and `platforms.md` §3.3 where they differ. Sequencing follows AD-1 and every entry criterion of the design (§9.3), including the v4 design session (R2-CONFORMANCE-3). Where macOS lands (D-v2 or M2) is D24; this section gives the macOS gates either way (R2-DIRECTIVES-11).
**Target implementation directories:** `desktop/packaging/macos/` (the JVM node's macOS packaging and platform adapter) and `apple/` (the shared Swift package: the Apple conformance lane, and later the iOS requester).
**Tags:** `[FMnn]` = verified macOS fact (§1.1, source URL and fetch date). `[AMnn]` = assumption (§1.2). `[Fnn]`/`[Ann]` = facts and assumptions of the integrated brief. `S-Mn` = spike that settles an assumption. `NDV` = NEEDS-DEVICE-VALIDATION. `NOV` = NEEDS-OWNER-VALIDATION. `RUN` = verified by running a command in this session (transcript path given).

**Correction to the task framing.** The session prompt says the Swift lane verifies manifests "with CryptoKit Ed25519". The design forbids that: **ES256 (ECDSA P-256) is the only signature algorithm** (constraint C2; `trust.md` §2.2 rejects Ed25519 because the Secure Enclave, StrongBox and most TPMs cannot hold it [F06]). The Swift lane uses `P256.Signing` (CryptoKit on Apple, swift-crypto on Linux). This section's spike ran exactly that (FM42).

**The answer in brief.**
- **Same JVM node as Linux and Windows.** macOS runs the pure-JVM `:core:*` + `:server` + `:node-desktop` code unchanged, compiled `--release 17`, shipped on a jlinked Temurin 21 aarch64 runtime. The macOS-specific code is (a) a small Kotlin adapter `macplatform` (pure JVM, testable in the Linux container) and (b) a small **Swift helper executable `asom-mac-helper`**, spawned by the node and spoken to over inherited pipes, that does the only things a JVM cannot: Secure Enclave signing (CryptoKit has no C or Java surface), `SMAppService` registration, IOKit sleep notifications and power assertions, and thermal / power / presence probes. **No JNA on macOS**, so no extracted native library and no new CD-D dependency.
- **Roles.** Requester: **YES** (mesh-1, owner CLI through the node). Lend while awake (PA): **YES on Apple-silicon desktops (Mac mini, Mac Studio, iMac) while a user is logged in**, and on **laptops only on AC with the lid open** (or in closed-display mode with an external display). **NO while asleep**: lid close, Apple-menu sleep and low battery are forced sleep, which no app can prevent [FM07][FM08]; Power Nap gives third-party agents no serving window [FM10][AM16]. **NO after a restart until someone unlocks FileVault** [FM12]. "Serve while logged out" exists only in an opt-in **LaunchDaemon mode** (M2, after spikes). Foreground-only lending (PF): feasible, not recommended.
- **Hosting.** Default: a **per-user LaunchAgent registered with `SMAppService.agent`** from inside the signed app bundle [FM02]. It shows in System Settings → General → Login Items, the user can switch it off there [FM03], and macOS attributes its Local Network use to the app [FM01]. Opt-in (M2): a **LaunchDaemon via `SMAppService.daemon`** running as a dedicated role user, the macOS twin of the Linux dedicated-user mode (T17a). Rejected: `jpackage --launcher-as-service` (a root daemon in `/Library/LaunchDaemons`), hand-written plists in `~/Library/LaunchAgents` (macOS 27's launchd refuses quarantined plists [FM19]), and Homebrew `brew services`.
- **Local Network privacy (macOS 15+).** The agent needs the Local Network privilege for **outgoing** LAN connections (borrowing, dialling a LAN peer); **accepting inbound connections needs none**, and **overlay (VPN) traffic is not "local network"** [FM01]. **A launchd daemon never triggers the prompt: daemons and root processes are allowed automatically** [FM01].
- **Keys.** T2 = **Secure Enclave P-256 through the Swift helper**, the wrapped key blob stored in the node's Team-ID-prefixed **app group container**, which macOS 15+ protects with SIP and macOS 27 **closes to other developer teams by default** [FM17][FM19]. T0 = PKCS#8 file in the same container (Intel without an Enclave; Enclave self-test failure; CI). **T1 (login keychain) is rejected**, and the `security` CLI route especially (FM16, AM14). Stated plainly: a Secure Enclave blob is **not bound to the app** that created it [FM15]; what protects it is the container, not the Enclave. **Same-user code that can run the node's own entitled launcher with an injected JVM option can use the key** unless the node ships the hardened launcher of §7.3 (S-M3).
- **Engine.** llama.cpp **Metal** (plus CPU/Accelerate) through the shared JNI surface, arm64 only [FM35]. MLX, Core ML and Apple Foundation Models are **not** mesh-1 backends. **MLPerf Client v1.6 already benchmarks macOS with llama.cpp-Metal and MLX and ships on the Mac App Store** [FM34], so macOS benchmark coverage is **not a differentiator**; `asom bench` on a Mac exists only to feed the signed manifest and the router.
- **Packaging.** jlink → `jpackage --type app-image` → **our own inside-out `codesign`** with per-executable minimal entitlements (hardened runtime, library validation left **on** if S-M3 passes) → **Developer ID Installer–signed `.pkg`** (root-owned install in `/Applications`) plus a signed `.dmg` → `notarytool` → `stapler` [FM24]. Distribution: GitHub Releases plus **an own Homebrew tap** (cask), official `homebrew/cask` later; **no Mac App Store; no in-app updater**.
- **CI.** `macos-latest` (macOS 26 arm64) and the `xcode-27` preview image (macOS 27.0) can compile and unit-test everything, run `swift test` (CryptoKit) and iOS-simulator builds, build the JNI dylib, run a CPU tiny-model generation, jlink/jpackage/pkgbuild, hold and release real power assertions, and run a two-node smoke and the W08 suite on the macOS JDK [FM37]. **Signing and notarisation run only on `main`/tags with the owner's Developer ID and App Store Connect API key.** Secure Enclave, Metal performance, sleep/lid/FileVault, the Local Network prompt, Tailscale and Login Items UX stay NDV.
- **Effort:** about **11–17 engineer-weeks** of macOS-specific work for the full recommendation (a minimal cut is 7–10), on top of the shared D-v2 desktop node. The integrated brief's 4–6 weeks (§9.4) did not include the helper, the hardened launcher, the key-container design or the CI pipeline (§10.4).

---

## 1. Verified platform facts and assumptions

### 1.1 Verified (fetched 2026-09-30 unless stated; "page date" = the page's own revision date where shown)

Apple developer documentation pages were fetched as their JSON data (`developer.apple.com/tutorials/data/documentation/<path>.json`) because the HTML pages render client-side; the text was extracted mechanically. Raw copies are in `platforms/macos-src/`.

| ID | Fact | Source (page date) | Conf. |
|---|---|---|---|
| FM01 | **Local Network privacy** exists on macOS 15+. "Making an outgoing TCP connection" requires it; "Listening for and accepting incoming TCP connections" does **not**; "Receiving an incoming UDP unicast" does not; all Bonjour operations and all multicast/broadcast do. A local network is one on a broadcast-capable interface: "Wi-Fi and Ethernet, but not cellular (WWAN) or VPN". "macOS automatically allows local network access by: Any daemon started by `launchd`; Any program running as root; Command-line tools run from Terminal or over SSH, including any child processes they spawn." "The exception for `launchd` daemons doesn't apply to `launchd` agents." macOS attributes a helper's access to the **responsible code** (the app). An agent not installed with `SMAppService` must set `AssociatedBundleIdentifiers`. "macOS fails to display the local network alert when a process with a very short lifespan performs a local network operation (FB16131937)." Sign with an Apple-issued identity for reliable tracking. "On macOS there's no way to reset your program's Local Network privilege to the undetermined state (FB14944392)." `AllowedEthernetLocalNetworkAddresses` / `AllowedWiFiLocalNetworkAddresses` (macOS 15.5+, set with `sudo`, **restart required**) exempt whole networks for all programs; "particularly useful for … continuous integration (CI) systems". macOS 15.1 fixed several bugs | https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy (revision 2026-02-17) | high |
| FM02 | `SMAppService` (macOS 13+) registers `LoginItems`, `LaunchAgents` and `LaunchDaemons` "as helper executables for your app"; `agent(plistName:)`: "The property list name must correspond to a property list in the calling app's `Contents/Library/LaunchAgents` directory"; `daemon(plistName:)` likewise in `Contents/Library/LaunchDaemons`. Status `requiresApproval`: registered, "but the user needs to take action in System Settings before the service is eligible to run", also returned "if the user revokes consent". `openSystemSettingsLoginItems()` opens that pane | https://developer.apple.com/documentation/servicemanagement/smappservice ; …/smappservice/agent(plistname:) ; …/daemon(plistname:) ; …/status-swift.enum/requiresapproval ; …/opensystemsettingsloginitems() | high |
| FM03 | Items registered with the framework are listed in **System Settings → General → Login Items**; an MDM Service Management payload can pre-approve them by bundle id, Team ID or label; notifications are throttled to one per 24 h; "enhanced user prompts for background task persistence appear in macOS 26 or later" | https://support.apple.com/guide/deployment/manage-login-items-background-tasks-mac-depdca572563/web (2025-12-17) | high |
| FM04 | `launchd.plist(5)`: `ProcessType` — "If left unspecified, the system will apply light resource limits to the job, throttling its CPU usage and I/O bandwidth"; `Standard` = unspecified; `Background` limits "prevent them from disrupting the user experience"; `Interactive` "run with the same resource limitations as apps, that is to say, none … should only be used if an app's ability to be responsive depends on it, and cannot be made Adaptive"; `Adaptive` moves with XPC activity. `UserName` "is only applicable for services that are loaded into the privileged system domain". `KeepAlive` `SuccessfulExit`/`Crashed`; `ThrottleInterval` default 10 s; `ExitTimeOut` (SIGTERM→SIGKILL); `LimitLoadToSessionType` (agents only); `AssociatedBundleIdentifiers` | https://keith.github.io/xcode-man-pages/launchd.plist.5.html (man page dated 2019-07-30; mirror of the Xcode man pages) | high (content) / medium (currency) |
| FM05 | Apple: "Always include the `ProcessType` key in your daemon or launch agent's `Info.plist` file"; use XPC so the system can attribute work; "On Apple silicon, a task's QoS class influences whether the system runs that task … the system is more likely to run background tasks on lower performance cores"; use `pthread_set_qos_class_self_np` | https://developer.apple.com/documentation/apple-silicon/tuning-your-code-s-performance-for-apple-silicon | high |
| FM06 | **App Nap** applies to an app that is not foreground, not visibly updating, not audible, "hasn't taken any IOKit power management or NSProcessInfo assertions", not using OpenGL; it reduces priority, throttles timers and I/O | https://developer.apple.com/library/archive/documentation/Performance/Conceptual/power_efficiency_guidelines_osx/AppNap.html (2016-09-13) | high (old page) |
| FM07 | `kIOPMAssertionTypePreventUserIdleSystemSleep`: prevents idle sleep; "The system may still sleep for lid close, Apple menu, low battery, or other sleep reasons"; "does not put the system into Dark Wake". `kIOPMAssertionTypePreventSystemSleep` prefers Dark Wake; "Assertions are just suggestions … In the case of low power or a thermal emergency, the system may sleep anyway" | https://developer.apple.com/documentation/iokit/kiopmassertiontypepreventuseridlesystemsleep ; …/kiopmassertiontypepreventsystemsleep | high |
| FM08 | `IORegisterForSystemPower`: `kIOMessageCanSystemSleep` (idle sleep) can be vetoed with `IOCancelPowerChange`; `kIOMessageSystemWillSleep` must be acknowledged with `IOAllowPowerChange` or sleep is delayed **up to 30 s**; **forced sleep (lid, Apple menu, thermal emergency, low battery) cannot be prevented, only delayed** | https://developer.apple.com/library/archive/qa/qa1340/_index.html (2014-01-13) | high |
| FM09 | `caffeinate -i` prevents idle sleep; `-s` "is valid only when system is running on AC power"; `-w pid` releases when the pid exits | https://keith.github.io/xcode-man-pages/caffeinate.8.html | high |
| FM10 | **Power Nap**: on battery it checks Mail, Calendar and iCloud; on AC it also downloads software updates and makes Time Machine backups; on by default on AC; the desktop setting "is only available on Intel-based Mac computers". No third-party serving is described | https://support.apple.com/en-au/guide/mac-help/mh40774/26/mac/26 (macOS 26 guide) | high |
| FM11 | Lid-closed use of a MacBook (closed-display mode) is documented with an external keyboard and mouse or trackpad, power ("If the external display provides power to the Mac, a separate power adapter isn't needed") and external display(s) | https://support.apple.com/en-us/117373 (2024-07-30) | high (for what it says) |
| FM12 | With FileVault on, after a restart "the data volume is locked and unavailable during and after booting, until an account has been authenticated"; from macOS 26, with Remote Login enabled, a limited pre-boot SSH accepts the password to unlock, after which "SSH (and other enabled services) are fully available" | https://keith.github.io/xcode-man-pages/apple_ssh_and_filevault.7.html ; https://support.apple.com/en-us/124963 (what's new for enterprise, macOS 26) | high |
| FM13 | **Secure Enclave** requires "a Mac with the Touch Bar and Touch ID or with an M1 or later processor"; "Works only with NIST P-256 elliptic curve keys"; "Can't encode preexisting keys". Keychain-stored Enclave keys use `kSecAttrTokenIDSecureEnclave` | https://developer.apple.com/documentation/security/protecting-keys-with-the-secure-enclave | high |
| FM14 | CryptoKit `SecureEnclave.P256.Signing.PrivateKey` and its `dataRepresentation` exist on macOS 10.15+ | https://developer.apple.com/documentation/cryptokit/secureenclave/p256/signing/privatekey ; …/datarepresentation | high |
| FM15 | Apple DTS (Quinn), May 2026, correcting his May 2025 answer: "I no longer believe that the key is tied to your App ID … I'm able to successfully pass an SE-protected key between two apps (running on iOS 26.4.2)" — a CryptoKit Enclave key's `dataRepresentation` is usable by another app on the same device | https://developer.apple.com/forums/thread/786223 | high (DTS statement; tested on iOS, not macOS) |
| FM16 | **TN3137**: macOS has a file-based keychain and the data protection keychain; the file-based one "is on the road to deprecation"; "Programs that run outside of a user context, like a `launchd` daemon, must target the file-based keychain"; data-protection access groups come from entitlements that "must be authorized by a provisioning profile" and need an app-like bundle; "Protecting a key with the Secure Enclave" (via the keychain) requires the data protection keychain; "The keychain support in the `security` command-line tool is primarily focused on the file-based keychain"; from **macOS 26.4** a keychain file may reference a protected entropy file in `/var/db/SystemKeys` | https://developer.apple.com/documentation/technotes/tn3137-on-mac-keychains (revision 2026-09-24) | high |
| FM17 | macOS 15: app group containers in `~/Library/Group Containers` are protected by SIP; an app may use one if it is from the Mac App Store, **or the group id is prefixed with its Team ID**, or a provisioning profile authorises it | https://developer.apple.com/forums/thread/756701 (DTS, June 2024, quoting the macOS 15 release notes) | high |
| FM18 | App Groups entitlement: "In macOS, you can also create app groups … using this identifier format: `<team identifier>.<group name>`. You don't need to register app groups that use this format on the Apple Developer website"; members may use Unix domain sockets and XPC named after the group | https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.application-groups | high |
| FM19 | **macOS 27 release notes**: "Accessing files in other developer teams' app data containers and app group containers no longer prompts the user for authorization; such accesses are denied by default and can be managed by the user in Privacy & Security settings. (161835690)"; "XProtect may now restrict access to app data that is commonly targeted by malicious software (178668601)"; known issue: "a process without a bundle ID cannot be granted access to specific app data containers or app group containers owned by other developer teams (184660124)"; "`launchd` no longer supports loading `launchd` property list files with the quarantine extended attribute (166415497)"; "Installer packages which specify no `hostArchitecture` will now default to arm64 (171187112)"; "All Intel-based software will no longer be compatible with macOS 28.0, excluding legacy games (176042635)" | https://developer.apple.com/documentation/macos-release-notes/macos-27-release-notes | high |
| FM20 | **macOS 27 "Golden Gate"** released 2026-09-14; 27.0.1 on 2026-09-28; runs only on Apple-silicon Macs; **macOS 26 Tahoe is the last version for Intel Macs**; macOS 27 is the last with full Rosetta 2 | https://en.wikipedia.org/wiki/MacOS_Golden_Gate ; https://www.macrumors.com/2026/09/28/apple-releases-macos-27-0-1/ | high |
| FM21 | Hardened Runtime "protects the runtime integrity of your software by preventing certain classes of exploits, like code injection, dynamically linked library (DLL) hijacking, and process memory space tampering"; entitlements go on executables only ("Shared libraries … inherit the entitlements of their host executable"); notarisation requires it | https://developer.apple.com/documentation/security/hardened-runtime | high |
| FM22 | Library validation (on by default under the hardened runtime) loads only libraries "signed by Apple or signed with the same Team ID as the main executable"; with `disable-library-validation`, "Gatekeeper runs extra security checks"; Apple: "Don't disable library validation for executables that don't host plug-ins" | https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.cs.disable-library-validation ; https://developer.apple.com/documentation/security/resolving-common-notarization-issues | high |
| FM23 | `com.apple.security.cs.allow-jit` permits `MAP_JIT` memory; `allow-unsigned-executable-memory` permits W+X memory without `MAP_JIT` and "exposes your app to common vulnerabilities" | https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.cs.allow-jit ; …/com.apple.security.cs.allow-unsigned-executable-memory | high |
| FM24 | **Notarisation** requires a Developer ID certificate (Application for code, Installer for packages), the hardened runtime, a secure timestamp (only `timestamp.apple.com`), no `get-task-allow`; `altool` was retired 2023-11-01 in favour of `notarytool`; accepted containers: UDIF disk images, signed flat packages, ZIP, including nested containers; `stapler` staples to apps, disk images and packages, **not to ZIPs or standalone binaries**; the ticket is also "publish[ed] online where Gatekeeper can find it"; `stapler` uses CloudKit | https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution ; …/customizing-the-notarization-workflow ; …/resolving-common-notarization-issues | high |
| FM25 | macOS Sequoia: "users will no longer be able to Control-click to override Gatekeeper" for software that is not signed correctly or notarised; they must go to System Settings → Privacy & Security | https://developer.apple.com/news/?id=saqachfa | high |
| FM26 | `jpackage` (JDK 25) macOS options: `--mac-package-identifier`, `--mac-sign`, `--mac-signing-keychain`, `--mac-signing-key-user-name`, `--mac-app-store`, `--mac-entitlements`, `--mac-app-category`, `--mac-dmg-content`; generic `--app-content`, `--resource-dir`, `--launcher-as-service`. The JDK 21 and 25 sources choose `sandbox.plist` (App Store) or `entitlements.plist` as default entitlements; `sandbox.plist` grants `allow-jit`, `allow-unsigned-executable-memory`, `disable-library-validation`, `allow-dyld-environment-variables`, `debugger` and `audio-input` | https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html ; https://raw.githubusercontent.com/openjdk/jdk/jdk-21-ga/src/jdk.jpackage/macosx/classes/jdk/jpackage/internal/MacAppImageBuilder.java (lines 154–166) ; …/jdk-25-ga/…/resources/sandbox.plist | high |
| FM27 | JDK 21 on macOS supports `SO_PEERCRED` on Unix-domain sockets: `peerCredentialsSupported()` returns true and the value comes from `getpeereid(fd, &uid, &gid)` (uid and gid; no pid) | https://raw.githubusercontent.com/openjdk/jdk/jdk-21-ga/src/jdk.net/macosx/classes/jdk/net/MacOSXSocketOptions.java ; …/native/libextnet/MacOSXSocketOptions.c | high |
| FM28 | **Tailscale macOS variants:** Mac App Store (App Sandbox, Network Extension); Standalone (System Extension, downloaded from Tailscale); **open-source `tailscaled`** ("uses the kernel `utun` interface, rather than the Network Extension or System Extension frameworks"; "can run before login") | https://tailscale.com/kb/1065/macos-variants (validated 2026-01-05) | high |
| FM29 | "Each Tailscale agent … streams its logs to a central log server (at `log.tailscale.com`)". On macOS `--no-logs-no-support` "works only with the command-line open source `tailscaled` variant, **not the Standalone app or the Mac App Store app**" | https://tailscale.com/kb/1011/log-mesh-traffic (validated 2026-01-05) | high (for what the page says) |
| FM30 | Headscale on macOS: any Tailscale macOS client; `tailscale login --login-server <URL>`, or the GUI Debug menu's custom login server. Nothing about logs | https://headscale.net/stable/usage/connect/apple/ | high (for what it says) |
| FM31 | **Homebrew 6.0.0** (2026-06-11): casks that fail Gatekeeper "remain on track to be disabled in September 2026"; "in September 2026, macOS Intel `x86_64` moves to Tier 3 … in September 2027, macOS Intel `x86_64` will be unsupported entirely"; "Homebrew now requires taps … to be explicitly trusted before their code is evaluated or run" | https://brew.sh/2026/06/11/homebrew-6.0.0/ | high |
| FM32 | homebrew/cask acceptance: executable artefacts "must pass Homebrew's Gatekeeper checks and must not require System Integrity Protection or Gatekeeper to be disabled or bypassed". Search results report the disabling took effect 2026-09-01 and `--no-quarantine` was removed with it | https://docs.brew.sh/Acceptable-Casks ; https://github.com/orgs/Homebrew/discussions/6482 (search summary, not fetched) | high / medium |
| FM33 | Homebrew analytics are sent "to InfluxDB over HTTPS in a detached background process" and include package and non-private GitHub tap names, OS and CPU; a notice is shown first; opt out with `brew analytics off` or `HOMEBREW_NO_ANALYTICS=1` | https://docs.brew.sh/Analytics | high |
| FM34 | **MLPerf Client v1.6** (2026-04-06): "updates to **MLX with Metal** and **llama.cpp with Metal**", "performance and compatibility on macOS and iPad"; "GUI versions … available via the App Stores for iOS and Mac and … Steam". Repository: macOS execution paths MLX and llama-cpp (Metal); Apache-2.0; "model and data files downloaded as needed" | https://mlcommons.org/2026/04/mlperf-client-v1-6/ ; https://github.com/mlcommons/mlperf_client | high |
| FM35 | llama.cpp: "On MacOS, Metal is enabled by default … To disable the Metal build at compile time use the `-DGGML_METAL=OFF`"; GPU use can be turned off at run time with `--n-gpu-layers 0`; Accelerate BLAS is enabled by default on Macs | https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md | high |
| FM36 | MLX is "an array framework for machine learning on Apple silicon" (plus Linux CUDA/CPU packages) with Python, C++, C and Swift APIs; MIT licence | https://github.com/ml-explore/mlx | high |
| FM37 | **GitHub-hosted macOS runners.** Labels for public repos include `macos-latest`, `macos-26`, `macos-15`, `macos-14`, `macos-26-intel`, `macos-15-intel` and `xcode-27` (public preview). `macos-latest` = macOS 26 **arm64** (image 20260907, macOS 26.6.2); `macos-26-intel` = x64; `xcode-27` = macOS **27.0** arm64 (image 20260921) with Xcode 27.0 default. arm64: **3 vCPU (M1), 7 GB RAM, 14 GB SSD**; Intel: 4 vCPU, 14 GB, 14 GB. Free for public repos; **passwordless sudo**; nested virtualisation unsupported. macOS 26 arm64 image: Temurin 11/17/**21 (default)**/25 (`JAVA_HOME_21_arm64` …), Xcode 26.0.1–26.6 (26.6 default), iOS 26.0–26.5 SDKs and simulators (e.g. iPhone 17), Homebrew 6.0.22, CMake 4.4.3, Gradle 9.7.1. macOS 14 images are deprecated (unsupported from 2026-11-02) | https://docs.github.com/en/actions/reference/runners/github-hosted-runners ; https://github.com/actions/runner-images (README; `images/macos/macos-26-arm64-Readme.md`, `xcode-27-arm64-Readme.md`, `macos-26-Readme.md`) | high |
| FM38 | Apple Developer Program: US$99 per membership year (fee waivers for eligible nonprofits, education, government) | https://developer.apple.com/programs/whats-included/ | high |
| FM39 | `ProcessInfo.thermalState` (macOS 10.10.3+; nominal/fair/serious/critical), `isLowPowerModeEnabled` (macOS 12+), `MTLDevice.recommendedMaxWorkingSetSize` ("how much memory … this GPU device can allocate without affecting its runtime performance", macOS 10.12+), `URLResourceKey.isExcludedFromBackupKey` (macOS 10.8+) | https://developer.apple.com/documentation/foundation/processinfo/thermalstate-swift.property ; …/islowpowermodeenabled ; https://developer.apple.com/documentation/metal/mtldevice/recommendedmaxworkingsetsize ; https://developer.apple.com/documentation/foundation/urlresourcekey/isexcludedfrombackupkey | high |
| FM40 | macOS 13 **App Management**: a notarised app may not be modified by software from another developer team unless the user allows it; a bypass was reported in 2023 | https://eclecticlight.co/2022/06/17/app-security-changes-coming-in-ventura/ ; https://lapcatsoftware.com/articles/AppManagement.html (third-party; search summaries) | medium |
| FM41 | The Application Firewall offers "Block all incoming connections", "Automatically allow built-in software …" and "Automatically allow downloaded and signed software to receive incoming connections"; the page does not state the default | https://support.apple.com/guide/security/firewall-security-in-macos-seca0e83763f/web (2021-02-18) | high (for what it says) |
| FM42 | **RUN (this session).** Swift 6.1 (Linux x86_64) with swift-crypto **3.15.1** built a one-source DSSE/ES256 module (`#if canImport(CryptoKit) … #else import Crypto`). Over the 37 M02/M03 container vectors it reproduced the JCA `VerifyDsse` column exactly for **36**; the 37th (M03-123, "Trailing data after the container JSON") was rejected at parse by Foundation's `JSONSerialization` before any signature check. `swift test`: 2/2 (PAE bytes equal `example-pae.bin`; non-canonical SPKI rejected) | `platforms/macos-spike/swift-es256/TRANSCRIPT.txt` | high (LAB; not device evidence) |
| FM43 | macOS `fsync(2)`: it "will flush all data from the host to the drive", but "the drive itself may not physically write the data … for quite some time and it may be written in an out-of-order sequence"; applications needing strict ordering "should use F_FULLFSYNC" | https://keith.github.io/xcode-man-pages/fsync.2.html | high |

### 1.2 Assumptions (not verified; each has a settling test)

| ID | Assumption | Load-bearing for | Settle by |
|---|---|---|---|
| AM01 | A CryptoKit Secure Enclave P-256 key can be created, persisted as `dataRepresentation` and used to sign from a **Developer-ID-signed helper executable with no entitlements and no provisioning profile**, in a user session, on macOS 15–27 | T2 tier (§5) | **S-M1** on the owner's Mac (NDV) |
| AM02 | GitHub's macOS VMs expose no Secure Enclave (`SecureEnclave.isAvailable == false`) | CI scope | first CI run prints it |
| AM03 | `SMAppService.agent(plistName:).register()` works when called from a secondary executable in `Contents/MacOS` of the app (not only from the main executable) | registration path (§3) | **S-M2** (fallback: register from the main launcher) |
| AM04 | Temurin 21 aarch64 HotSpot runs under the hardened runtime with **library validation on** and **only `allow-jit`** (no `allow-unsigned-executable-memory`, no `disable-library-validation`), once every dylib in the runtime is re-signed with our Team ID | C13 rating; entitlements | **S-M3** (the design's S-A3/S3), CI with Developer ID on `main` |
| AM05 | A native launcher that removes `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS` and `CLASSPATH` from its environment, `chdir`s to a fixed directory and then calls `JNI_CreateJavaVM` with a fixed option list, plus a jlink image without `java.instrument`, `jdk.attach`, `jdk.jdwp.agent` and `jdk.management.agent`, and `-XX:+DisableAttachMechanism`, prevents environment- or attach-driven code injection into the entitled node | "same-user ≠ node" (§5, §7.3) | **S-M3** + CI probe (§10.3 MC4) |
| AM06 | `ProcessType` `Standard` (or unset) measurably lowers llama.cpp decode throughput versus `Interactive` on Apple silicon | agent plist | **S-M4** on the owner's Mac |
| AM07 | Metal compute and the Secure Enclave both work from a **LaunchDaemon** running as a role user with no GUI session | daemon mode B | **S-M5** (NDV) |
| AM08 | macOS 27's cross-team container denial covers every other process, including unsigned tools and scripts started from Terminal without Full Disk Access, and does **not** block the node's own Team-ID-entitled executable when launchd starts it | key and ledger protection (§5) | **S-M6** (signed CI on `xcode-27`; device) |
| AM09 | A LAN connect blocked by Local Network privacy surfaces in the JVM as a connect failure with `EHOSTUNREACH` ("No route to host"), distinct enough to write the DIAL outcome `local-network-denied` | DIAL rows; doctor | **S-M7** (device) |
| AM10 | A JDK `ServerSocketChannel` can bind the Tailscale `utun` 100.x address with all three client variants | listener | S-M7 (device) |
| AM11 | IOPM assertions are released when the owning process exits (including `kill -9`) | fail-safe sleep policy | CI: `pmset -g assertions` before/after |
| AM12 | `IOAccelerator` `PerformanceStatistics` "Device Utilization %" is readable without root and tracks GPU load; no per-process GPU utilisation exists without root | GPU contention rule | **S-M8** (device; CI records existence) |
| AM13 | HID idle time (`IOHIDSystem` `HIDIdleTime`), screen-lock state (`CGSessionCopyCurrentDictionary`) and the console user are readable from a LaunchAgent | presence rule | S-M8 |
| AM14 | A keychain item created by `security add-generic-password` trusts `/usr/bin/security`, so any same-user process can read it silently with `security find-generic-password -w` | rejecting T1-via-CLI | CI demonstration (§10.3 MC2) |
| AM15 | The Application Firewall is off by default | doctor text | owner check |
| AM16 | A MacBook with the lid closed and no external display sleeps regardless of any third-party assertion (third-party utilities claim otherwise) | laptop role wording | device test |
| AM17 | Metal on GitHub's paravirtualised GPU either fails or reports a reduced capability family, so llama.cpp-Metal results there are not representative | CI scope | first CI run records the device name and families |
| AM18 | Setting `isExcludedFromBackupKey` on the container root excludes it from Time Machine; Migration Assistant still copies it | C7; migration guard | device |
| AM19 | `IOPlatformUUID` is stable on one Mac across OS reinstall and differs across Macs | migration guard | device |
| AM20 | Calling `SMAppService` register on a hosted runner returns `enabled` or `requiresApproval` without hanging | CI step | first CI run (non-gating) |
| AM21 | `java.awt.SystemTray` works for an `LSUIElement` companion on macOS 15–27 | watched-object UI | device (CI if a GUI session exists) |
| AM22 | llama.cpp's `GGML_METAL_EMBED_LIBRARY=ON` embeds the Metal shader library so the dylib needs no external `.metallib` at run time, and the build on Xcode 26/27 needs no separately downloaded Metal toolchain | native build | first CI build |
| AM23 | A Secure Enclave key blob stops working after an "Erase All Content and Settings" or on another Mac (no silent migration) | clone resistance claim | device |
| AM24 | The effort estimates in §10.4 | planning | revisit after M1 |
| AM25 | An Aqua-session LaunchAgent does not start after a pre-boot SSH FileVault unlock until someone logs in to the GUI (only daemons start) | mode A vs mode B restart story | device test (MC9) |
| AM26 | The VM reads `JAVA_TOOL_OPTIONS`/`_JAVA_OPTIONS` inside `JNI_CreateJavaVM` whatever launcher created it (so a stock jpackage launcher is injectable) | §7.3 problem statement | the MC4 probe shows it directly |

**Load-bearing owner inputs:** whether an **Apple-silicon Mac** exists and which (a Mac mini/Studio is a real lender; a MacBook Air is a requester that sometimes lends); its RAM (decides the §7.1 row and the largest servable model); whether the owner enrols in the Developer Program as an **individual or an organisation** (fixes the Team ID that names the group container and every signature, §11 M-D10); whether an Intel Mac must be supported (§11 M-D7).

---

## 2. Feasible mesh roles on macOS

| Role | Verdict | Honest reason |
|---|---|---|
| **R — requester** (borrow) | **YES, mesh-1** (owner CLI through the node); desktop local apps only at M2 under D25(b) + D14 part B | Outbound TLS needs no listener and no firewall change. Over the overlay it needs no Local Network privilege (VPN is not "local network"); to a LAN peer it needs the privilege once [FM01]. The CLI reaches the node over a Unix-domain socket checked with `getpeereid` [FM27]. **Holon completeness (R2-DIRECTIVES-4):** in mesh-1 a Mac node serves only its owner's CLI; there is no local-app API on desktops yet (D25). |
| **PA — lend while awake, no human present** | **YES on Apple-silicon desktops while a user is logged in** (mode A). **CONDITIONAL on laptops:** on AC, lid open or closed-display mode with an external display [FM11]. **NO while asleep, NO after a restart until FileVault is unlocked, NO while logged out** (mode A). Mode B (LaunchDaemon, M2) adds "while logged out" | Idle sleep is held off by `PreventUserIdleSystemSleep` while SERVING, which also takes the process out of App Nap [FM06][FM07]. **Forced sleep cannot be prevented** — lid close, Apple-menu Sleep, low battery, thermal emergency [FM07][FM08] — and Power Nap runs only Apple's own maintenance [FM10]. After a restart the data volume stays locked until someone authenticates [FM12], so neither an agent nor a daemon runs; macOS 26+ allows a pre-boot SSH unlock, after which daemons start and agents are expected to wait for a GUI login [AM25]. An agent lives in the user's Aqua session and ends at logout. **asom never changes `pmset` settings.** On battery, lending is off by default (as on every platform). |
| **PF — lend only while a lend screen is frontmost** | Feasible, **not recommended** | A Mac that can lend at all can do it as PA under the same governors; PF would add a window and the R2-OVERCLAIM-9 presence-law exception for little value |
| **B — benchmark producer** | YES (desktop `asom bench`, D-v2) | Only to feed the router and the signed manifest. **MLPerf Client v1.6 already measures macOS with llama.cpp-Metal and MLX and is on the Mac App Store** [FM34], so Mac coverage is not a differentiator (R2-DIRECTIVES-2, directive D-C) |
| **S — subscriber** | YES | The JVM verifier (the same one as Linux/Windows) |

**Mac classes, plainly.**

| Mac | Realistic role | Why |
|---|---|---|
| Mac mini / Mac Studio / iMac (Apple silicon), user logged in | **Best consumer lender class** (PA) | Always on AC, no lid; unified memory makes 8B–32B models practical (the §7.1 GPU row is an estimate [A12d]; measure at D-v2) |
| MacBook Pro / Air on a desk, on AC, lid open | PA while those conditions hold | Lid close or Apple-menu sleep ends lending immediately; the Air is fanless and throttles under sustained load (thermal state drives DRAINING) |
| MacBook in a bag / on battery | **Requester only** | Lending is off on battery by default; the lid is shut |
| Headless Mac mini that must survive reboots and logouts | **Mode B only** (M2, after S-M5), plus the owner's FileVault SSH-unlock routine [FM12] | An agent cannot run without a GUI login |
| Intel Mac (macOS 26 at most) | Requester only, and only if M-D7(b) | No Intel artefact is recommended (§8.6) |

### 2.1 Availability FSM conditions on macOS (the §3.2 FSM, macOS inputs)

`conditions_met` (every clause is user-overridable except those marked **hard**). All inputs come from `asom-mac-helper` events (§3.5) unless stated.

| Clause | Source | Default |
|---|---|---|
| lend toggle on | node config | OFF (**hard** default) |
| an eligible interface is up and selected | JDK `NetworkInterface` + selection record (§4.1) | — |
| AC power (laptops) | IOKit power sources: providing source is AC | required; desktops always pass |
| not Low Power Mode | `ProcessInfo.isLowPowerModeEnabled` [FM39] | required |
| thermal state `nominal` or `fair` | `ProcessInfo.thermalState` [FM39] | **hard** for `serious`/`critical` |
| not `os_sleep_imminent` | `kIOMessageSystemWillSleep` [FM08] | **hard** |
| presence rule ("yield to the local user") | HID idle ≥ 10 min, or screen locked, and the console user is the node's user [AM13] | ON. A different console user (fast user switching) counts as presence |
| GPU contention: device utilisation minus own-busy estimate below 200‰ for 60 s; drain above 400‰ for 10 s (Deck hysteresis) | `IOAccelerator` device utilisation [AM12]; own-busy = fraction of the window the engine had a Metal command buffer in flight | ON if the counter exists; otherwise off and `asom doctor` says so |
| mode A: the owning user's GUI session exists | implicit (the agent only runs then) | — |
| mode B: `serveWhileLoggedOut = true` | node config | false (M1 gate 10) |

**While SERVING** the helper holds one `kIOPMAssertionTypePreventUserIdleSystemSleep` assertion named **"asom: lending compute to your paired devices"**, visible to the user in `pmset -g assertions`, released on leaving SERVING or if the node or helper dies [AM11]. `PreventSystemSleep` is never used: it keeps a Mac in Dark Wake [FM07], which is more than lending needs. `graceMs` is 30,000 as on the other desktops, **except on `os_sleep_imminent`**: the node closes the listener at once, ends in-flight streams with `INFER_END interrupted` (the requester's `MESH_STREAM_INTERRUPTED`), writes its outcome rows (FC-5) and lets the helper acknowledge within **2 s** — the user closed the lid expecting sleep, and holding a hot laptop awake for the 30 s macOS allows [FM08] is the wrong trade. Presence hold-down (LP-2) is unchanged: SERVING is re-published no sooner than 10 min after the last presence signal. On the wire the transition shows only as `fsm` and `PEER_UNAVAILABLE` (R2-DIRECTIVES-5); the reason stays in the local ledger.

**Thermal band mapping (PROVISIONAL, for `asom.state/1`):** `nominal` → band 0; `fair` → band 1; `serious`/`critical` → band 2. `governor` = RUN at band 0, QUEUE at band 1, HOLD at band 2.

### 2.2 Store and distribution rules that bear on roles

- **Mac App Store: not used.** Its sandbox, a JIT JVM, an agent that lends to other devices and the Local Network behaviour add review and entitlement friction for no user benefit (unchanged from `platforms.md` §3.3). Nothing in the roles depends on it.
- **Developer ID + notarisation is required in practice.** Since macOS 15 an un-notarised app cannot be opened by Control-click; the user must go to Privacy & Security [FM25]. Homebrew's official tap no longer carries casks that fail Gatekeeper [FM31][FM32]. An unsigned node would also get unreliable Local Network attribution [FM01] and could not own a Team-ID group container [FM17].

---

## 3. Node hosting

### 3.1 Options considered

| Option | Verdict | Why |
|---|---|---|
| **A. Per-user LaunchAgent registered with `SMAppService.agent`** from the app bundle | **Chosen (default)** | User-approved and user-revocable in Login Items [FM02][FM03]; macOS attributes Local Network use to the app, so the prompt names "ASOM" and the choice sticks [FM01]; runs in the user's session, so the Metal device and user-session APIs are available; no admin rights; plist ships inside the signed bundle, so it cannot be quarantined or edited [FM19] |
| **B. LaunchDaemon registered with `SMAppService.daemon`, `UserName = _asom`** | **Opt-in, M2**, only after S-M5 | "Serve while logged out" and restart recovery via pre-boot SSH unlock [FM12]; exempt from Local Network privacy [FM01]; a dedicated uid keeps the node's files away from the owner's own processes (the Linux T17a rationale). Costs: admin approval, a role account created by a root install step, the file-based keychain only [FM16], and unverified Metal and Enclave access without a GUI session [AM07] |
| Root LaunchDaemon (e.g. `jpackage --launcher-as-service`) | **Rejected** | A root process parsing peer input is the highest-value target on the Mac; `jpackage` would write a plist to `/Library/LaunchDaemons` outside `SMAppService` |
| Plist copied to `~/Library/LaunchAgents` (or `brew services`) | **Rejected** | Not attributed to the app for Local Network privacy without `AssociatedBundleIdentifiers` [FM01]; a user-writable plist any same-user process can edit; macOS 27 launchd refuses a quarantined plist [FM19] |
| Login item that opens a GUI app | Rejected | App Nap and window lifecycle apply [FM06]; nothing gained |
| Node run from Terminal ("dev mode") | **Developer-only, never a product mode** | Terminal children are exempt from Local Network privacy [FM01], which would hide the product's real prompt behaviour; container access from a Terminal-started process is unverified [AM08] |

### 3.2 The bundle and the two modes (one install; exactly one lending node per Mac)

```
/Applications/ASOM.app                                   root-owned (installed by the .pkg), Developer-ID signed, notarised
└── Contents/
    ├── Info.plist                                       CFBundleIdentifier xyz.mdhv.asom.desktop; LSUIElement true;
    │                                                    LSMinimumSystemVersion 15.0; NSLocalNetworkUsageDescription (§4.2)
    ├── MacOS/
    │   ├── ASOM            companion launcher (AWT tray + small window; JVM; no group entitlement)
    │   ├── asom            CLI launcher (JVM; no group entitlement; symlinked onto PATH by the pkg or the cask)
    │   ├── asom-node       THE node: hardened native launcher hosting the JVM (§7.3); the only executable with the group entitlement
    │   └── asom-mac-helper Swift platform helper (§3.5); hardened runtime; no entitlements
    ├── Library/
    │   ├── LaunchAgents/xyz.mdhv.asom.node.plist        mode A (SMAppService.agent)
    │   └── LaunchDaemons/xyz.mdhv.asom.noded.plist      mode B (SMAppService.daemon; present from M2 only)
    ├── runtime/Contents/Home/…                          jlinked Temurin 21 aarch64, every Mach-O re-signed with our Team ID, no bin/ commands
    ├── app/                                             node-desktop.jar + deps (no native code inside any jar)
    └── Frameworks/libasom-llama-jni.dylib (+ ggml)      Metal build, Team-ID signed
```

**Mode A agent plist** (`xyz.mdhv.asom.node.plist`, normative intent):

```xml
<dict>
  <key>Label</key>                  <string>xyz.mdhv.asom.node</string>
  <key>BundleProgram</key>          <string>Contents/MacOS/asom-node</string>
  <key>ProgramArguments</key>       <array><string>asom-node</string><string>--mode=agent</string></array>
  <key>RunAtLoad</key>              <true/>
  <key>KeepAlive</key>              <dict><key>SuccessfulExit</key><false/><key>Crashed</key><true/></dict>
  <key>ThrottleInterval</key>       <integer>30</integer>
  <key>ExitTimeOut</key>            <integer>40</integer>     <!-- graceMs 30 s + ledger flush margin -->
  <key>ProcessType</key>            <string>Interactive</string> <!-- or Standard: decided by S-M4 (M-D8) -->
  <key>LimitLoadToSessionType</key> <string>Aqua</string>
  <key>StandardOutPath</key>        <string>/dev/null</string> <!-- T17(c): no token or row ever reaches a log -->
  <key>StandardErrorPath</key>      <string>/dev/null</string>
</dict>
```

- `ProcessType`: unset or `Standard` means CPU and I/O throttling [FM04], and on Apple silicon background-class work drifts to efficiency cores [FM05]. The node talks over sockets, not XPC, so `Adaptive` cannot help. S-M4 measures decode throughput under `Standard` and `Interactive`; use `Standard` unless it costs more than 5 % (M-D8).
- **One lending node per Mac.** Two logged-in users could each run an agent. The node takes an exclusive `flock` on `/var/tmp/xyz.mdhv.asom.node.lock` (created 0644 by whoever runs first; sticky directory) before entering SERVING; a second user's node stays ARMED with the local reason `another-user-node`. Borrowing is unaffected.
- **Boot start is not a separate toggle.** v1 P8's rule ("nothing runs unless the user starts it") is met by registration itself: nothing is registered at install; `asom node enable` (or the companion's button) registers the agent, which macOS announces with a notification and lists in Login Items [FM03]. Lending stays OFF until the user turns it on (`asom mesh lend on`).

**Mode B (M2, opt-in):** the same executable with `--mode=daemon`, `UserName = _asom`, state in `/Library/Application Support/xyz.mdhv.asom/` (owner `_asom`, 0700). The role account is created only when the user runs `sudo asom node daemon-install`, which prints the exact `dscl`/`sysadminctl` commands and the plist first (view-first), then registers with `SMAppService.daemon`. It is **never** created by the package. Mode B uses T0 or T2 per S-M5 and holds no keychain item. Modes A and B are mutually exclusive per Mac. Switching modes keeps the identity only if the new mode can use the existing key (a T0 file moved by the TTY-confirmed command; a T2 blob only if S-M5 shows a daemon can use it); otherwise the switch creates a new identity and the user re-pairs on each peer (M-D2).

### 3.3 Security rules for both modes (the macOS edition of T17)

| # | Rule | Mechanism |
|---|---|---|
| a | The node's key, registry, ledger, manifests and models live **only** in its protected location | mode A: `~/Library/Group Containers/<TEAMID>.xyz.mdhv.asom/` (Team-ID group, SIP-protected on macOS 15+, closed to other teams by default on macOS 27 [FM17][FM19]); mode B: `/Library/Application Support/xyz.mdhv.asom/` 0700 `_asom` |
| b | Never write tokens, keys, prompts or ledger rows to stdout/stderr, the unified log or crash files | plist routes stdout/stderr to `/dev/null`; the node logs to its own `diag/` file with the redaction law; `-XX:-CreateCoredumpOnCrash`, `-XX:ErrorFile=<container>/diag/hs_err_%p.log`; H3 stdout-capture test runs on the macOS lane too |
| c | The owner CLI talks to the node over a Unix-domain socket in the per-user temporary directory | `<per-user temp dir>/xyz.mdhv.asom/ctl.sock` (directory 0700; the node learns the directory from the helper's `paths.get`, because its own `java.io.tmpdir` is pinned inside the container; the CLI uses `$TMPDIR`, which is the same directory); `getpeereid` uid check **in both directions** [FM27]; the socket is **not** in the group container, because the CLI runs under Terminal and may be denied the container [AM08]; path length is checked against macOS's 104-byte `sun_path` limit. This is the macOS edition of CD-24: the caller is identified by uid only (`callerPkg = local-uid:<uid>`), and whether Invariant 5 governs the owner CLI is ruled in D2/D25(a), not here (R2-CONFORMANCE-6) |
| d | `pair-confirm`, `restore` and `daemon-install` need an interactive TTY confirmation | the socket cannot script them (as T17e) |
| e | The node key is excluded from backups and bound to this Mac | `isExcludedFromBackupKey` on the container root [FM39][AM18]; `node/binding.json` stores `sha256(IOPlatformUUID ‖ salt)`; on mismatch at start the node refuses to present the old identity (`NIK_MIGRATED`), marks every registry row unpaired (C7) and asks the user to re-pair [AM19] |
| f | No same-user code injection into the entitled node | hardened runtime; library validation on; no `disable-library-validation`, `allow-dyld-environment-variables`, `get-task-allow` or `debugger`; the §7.3 launcher; `-XX:+DisableAttachMechanism`; jlink without `java.instrument`, `jdk.attach`, `jdk.jdwp.agent`, `jdk.management.agent` [AM04][AM05] |
| g | Stated limit | root, any process the user granted Full Disk Access, the user approving access in Privacy & Security, and an exploit inside the node itself all defeat (a)–(f). Until S-M3 passes, the Peers tab rates a Mac node **"same-user compromise = node compromise"** (C13) |

### 3.4 Lifecycle events

| Event | Mode A (agent) | Mode B (daemon, M2) |
|---|---|---|
| Install (`.pkg` or cask) | nothing runs; nothing registered | same |
| `asom node enable` | helper calls `SMAppService.agent(…).register()` [AM03]; status `enabled` → launchd starts the node; `requiresApproval` → CLI prints "Open System Settings → General → Login Items & Extensions and allow ASOM" and calls `openSystemSettingsLoginItems()` [FM02] | `sudo asom node daemon-install` (view-first), admin approval |
| Login | launchd starts the node (`RunAtLoad`) | already running |
| User switches the item off in Login Items | launchd stops the job → SIGTERM → DRAINING, rows flushed within `ExitTimeOut`; next `asom` call reports `requiresApproval` | same, admin |
| Crash | relaunched after `ThrottleInterval` (30 s); orphaned intents reconciled (D21a crash contract) | same |
| Screen lock | node keeps running; lock is a presence signal **for** lending (the user is away) | unaffected |
| Fast user switch to another user | node keeps running in the background session; another console user = presence → DRAINING | same |
| Idle sleep while SERVING | prevented by the assertion [FM07] | same |
| Lid close / Apple-menu Sleep / low battery | `os_sleep_imminent` → DRAINING, ack within 2 s → sleep [FM08] | same |
| Wake | helper reports wake; interfaces re-enumerated; listener re-bound only if the selected interface is back; FSM re-evaluated | same |
| Logout | agent stops (session ends) | unaffected |
| Restart with FileVault | nothing runs until a GUI login | runs after unlock, including macOS 26+ pre-boot SSH unlock [FM12] |
| Upgrade (new `.pkg` or `brew upgrade --cask`) | the running node keeps the old code; `asom` detects the version skew and asks the user to run `asom node restart` (`launchctl kickstart -k gui/<uid>/xyz.mdhv.asom.node`) | `sudo asom node restart` |
| Uninstall | `asom node disable` (unregister) first; the cask's `uninstall launchctl:` does it as a fallback; the container is kept unless the user runs `asom node forget --destroy-identity` or `brew uninstall --zap` | same, plus role account removal (printed, TTY-confirmed) |

### 3.5 The Swift helper (`asom-mac-helper`)

**Why a helper and not JNA.** (`caffeinate -i -w <pid>` [FM09] could hold the sleep assertion without a helper, but sleep notifications need one anyway, so one mechanism does both.) Three of the needed APIs have no C surface a JVM can reach safely: CryptoKit's Secure Enclave keys (Swift-only), `SMAppService` (Objective-C/Swift), and `ProcessInfo` thermal/low-power state. IOKit sleep notifications need a CFRunLoop thread. Calling Objective-C through `objc_msgSend` from JNA on arm64 is possible but fragile, and JNA would add an extracted native library that library validation must also cover. One small Swift executable does all of it, is tested with `swift test` on a macOS runner, and keeps the JVM side pure Kotlin.

**Process model.** The node spawns `Contents/MacOS/asom-mac-helper serve` once at start and talks JSON Lines over the child's stdin/stdout. No socket, no XPC service, no listener: only the parent holds the pipes. On stdin EOF the helper releases its assertion and exits; if the helper dies, the node treats every probe as lost (B14 `PROBE_LOST`), leaves SERVING and restarts the helper at most once per minute.

**Security rule.** The helper has **no entitlements** and **reads no files**. Every input arrives on stdin, including the Enclave key blob for each signature. A process that spawns its own helper gets nothing without the blob, and the blob lives only in the node's protected container (§3.3a). The helper is therefore not a signing oracle for other same-user processes.

**Protocol v1** (`desktop/packaging/macos/helper-protocol/SCHEMA.md`; one JSON object per line; integers only; unknown fields rejected; vectors in `helper-protocol/vectors/*.jsonl`, consumed by both the Kotlin client tests and the Swift helper tests):

| Request (`op`) | Response / event | Notes |
|---|---|---|
| `hello {v:1}` | `{ok, helper:"<semver>", macos:"27.0.1", arch:"arm64", se:true\|false, model:"Mac14,3"}` | `se` from `SecureEnclave.isAvailable` |
| `se.create` | `{ok, blob:"<b64>", spki:"<b64 DER, 91 bytes>"}` | CryptoKit `SecureEnclave.P256.Signing.PrivateKey()`; no access-control flags (the NIK never requires user presence, `trust.md` §2.5) |
| `se.sign {blob, data}` | `{ok, sig:"<b64 64-byte r‖s>"}` | `data` ≤ 64 KiB (leaf TBS, DSSE PAE, pairing transcript); the helper hashes (CryptoKit signs data, not caller-supplied digests) |
| `se.selftest {blob}` | `{ok, verified:true}` | sign a fixed string, verify with the SPKI; run at every start |
| `power.get` | `{ok, source:"ac\|battery", charging, batteryPermille, lowPower}` | also pushed as `ev:"power"` on change |
| `thermal.get` | `{ok, state:"nominal\|fair\|serious\|critical"}` | also pushed as `ev:"thermal"` |
| `presence.get` | `{ok, hidIdleMs, screenLocked, consoleUserIsSelf}` | classified `presence` on the Kotlin side (LP-1) |
| `gpu.get` | `{ok, deviceUtilPermille}` or `{ok:false, code:"UNAVAILABLE"}` | [AM12] |
| `mem.get` | `{ok, physicalBytes, gpuRecommendedMaxWorkingSetBytes}` | feeds the RAM guard [FM39] |
| `assert.hold {reason}` / `assert.release` | `{ok}` | one assertion at most |
| event `ev:"sleep.will", token` | node replies `sleep.ack {token}` within 2 s; helper calls `IOAllowPowerChange` (timeout 2 s regardless) | `ev:"wake"` after wake |
| `svc.status\|svc.register\|svc.unregister {kind:"agent"\|"daemon"}` | `{ok, status:"notRegistered\|enabled\|requiresApproval\|notFound"}` | used by the CLI/companion, not by the running agent |
| `backup.exclude {path}` | `{ok}` | `isExcludedFromBackupKey` [FM39] |
| `platform.uuid` | `{ok, digest:"<b64 sha256(IOPlatformUUID)>"}` | the raw UUID never leaves the helper |
| `paths.get` | `{ok, userTempDir:"/var/folders/…/T/"}` | `NSTemporaryDirectory()`, the per-user directory the CLI also sees as `$TMPDIR`. The node computes its container path itself (`~/Library/Group Containers/<TEAMID>.xyz.mdhv.asom`, Team ID fixed at build time); the helper, holding no group entitlement, never touches it |

### 3.6 How the user sees that it is running (watched-object rule)

| Surface | What it shows | Who controls it |
|---|---|---|
| **System Settings → General → Login Items** | "ASOM" as an allowed background item; switching it off stops the node [FM03] | the OS; the user |
| **Registration notification** | "Background Items Added" when the agent is first registered [FM03] | the OS |
| **Menu-bar tray (companion `ASOM`)** | glyph + label, never colour alone (Invariant 6): `○ OFF` · `◐ ARMED (reason)` · `● SERVING` · `◌ DRAINING`, plus "borrowing now" / "lent to <peer alias> now"; violet `#8E7BFF` = sovereign (this Mac or a paired device), cyan `#35E0FF` = cloud, each with its word | the user starts the companion; optional login item |
| `asom status` | mode, FSM state and local reason, key tier, listener address, peers, last 10 ledger rows (metadata only) | CLI |
| `pmset -g assertions` | "asom: lending compute to your paired devices" while SERVING | the OS |
| Privacy & Security → Local Network | "ASOM" after the first LAN dial [FM01] | the OS; the user |
| Activity Monitor | `asom-node`, `asom-mac-helper` | the OS |

The tray is the Invariant 7 placeholder: AWT `SystemTray` in a separate JVM process [AM21], reading status over the control socket. A SwiftUI menu-bar item is **not** proposed until D14 part A (IC-7) is ruled.

---

## 4. Networking

### 4.1 Inbound listener

- The peer listener (port 11436, `asom-mesh/1`, IC-1) binds only addresses on the interface the user selected: the overlay's `utun` interface (a Tailscale 100.64.0.0/10 or `fd7a:115c:a1e0::/48` address) and/or a Wi-Fi/Ethernet interface (`en*`) on a network the user confirmed, at private or link-local addresses. Never `0.0.0.0`, `::`, `127.0.0.1` or a public address. The confirmation screen shows the interface name, its current addresses and the Wi-Fi network name, and refuses an interface with no private or link-local address; a phone's personal hotspot is a network the user may confirm or not, as on every platform.
- **Accepting inbound connections needs no Local Network privilege** [FM01], so a Mac that only lends on the LAN never sees the prompt; the prompt appears the first time the node **dials** a LAN address (borrowing, pairing as the dialling side, a revoke notice).
- `utun` numbering is not stable across reboots. Eligibility is recorded as (interface kind = overlay, address prefix) and re-resolved at each wake; T13's rule applies: if no kernel `utun` carries the overlay address (for example `tailscaled` in userspace-networking mode), the node reports "overlay mode unsupported: use a kernel TUN" and never binds loopback.
- The **macOS Application Firewall**, when the user has it on, may prompt for or block incoming connections; signed software can be allowed automatically if the user chose that option [FM41]. asom never calls `socketfilterfw` to change it. `asom doctor` reads `socketfilterfw --getglobalstate` and `--getappblocked <path>` and reports.

### 4.2 Local Network privacy (macOS 15+)

| Question | Answer | Source |
|---|---|---|
| Does the agent (mode A) need the privilege? | Yes, for outgoing TCP to LAN addresses only | [FM01] |
| Does a launchd **daemon** (mode B) trigger the prompt? | **No.** Daemons and root processes are allowed automatically | [FM01] |
| Is overlay traffic "local network"? | No: VPN interfaces are excluded | [FM01] |
| Does accepting inbound need it? | No | [FM01] |
| Can asom query or reset the state? | No API to reset (FB14944392); no query API is documented. The node infers denial from the connect error [AM09] and writes the DIAL outcome `local-network-denied` | [FM01] |
| Who is named in the prompt? | The app, because the agent is registered with `SMAppService` and the bundle is Developer-ID signed | [FM01][FM02] |
| Known bug to design around | No alert for a process that exits right after a denied operation (FB16131937): the node is long-lived and never exits on a dial failure | [FM01] |

`NSLocalNetworkUsageDescription` (Info.plist): *"ASOM connects to your own paired devices on this network, to borrow or lend model inference. It never discovers or contacts other devices."* The CLI shows the same sentence **before** the first LAN dial ("macOS will now ask whether ASOM may use your local network"), per T14's "request before a QR is shown" rule. `NSBonjourServices` is absent, because asom uses no Bonjour (§4.4).

### 4.3 Private overlay: Tailscale or Headscale on macOS

| Variant [FM28] | Log upload opt-out [FM29] | Runs before login | Custom control server (Headscale) [FM30] | asom verdict |
|---|---|---|---|---|
| Mac App Store (Network Extension, sandboxed) | **none** | no | yes (Debug menu) | usable; **uploads its logs to `log.tailscale.com` with no documented opt-out** — disclosed |
| Standalone (System Extension) | **none** | no | yes | same disclosure |
| **Open-source `tailscaled`** (kernel `utun`, CLI) | **`--no-logs-no-support`** | **yes** | `tailscale login --login-server <URL>` | **recommended** for a Mac lender under D8(b) (Headscale, logs off), especially with mode B |

- asom never installs, configures or starts Tailscale. `asom doctor` identifies the variant (bundle path or `tailscaled` process), reports whether `--no-logs-no-support` is on the `tailscaled` command line, and prints the D8 disclosure: the operator (or the owner's Headscale) learns the device graph; relayed traffic crosses DERP servers encrypted [F09]; the GUI variants upload client logs.
- Over the overlay no Local Network prompt appears [FM01]. That is also why a Mac on the overlay only never needs the privilege.
- Funnel-type features [F12] are named in the doctor output as "publishes a port beyond the overlay: asom never uses this; check it is off".

### 4.4 mDNS / Bonjour

Not used, and not needed: the design locates already-paired peers only from the QR, the peer's authenticated `HELLO` and the user (T12), and mDNS locate is deferred (T18, D12). If D12 ever approves it, on macOS every Bonjour operation needs the Local Network privilege and `NSBonjourServices` must list `_asom-mesh._tcp` [FM01]; the JVM has no Bonjour, so it would be one more helper operation (`dns_sd`), never a JVM multicast library.

---

## 5. Key storage tier for the node identity (NIK)

The NIK is P-256, signs only the node certificate, weekly session leaves, challenge-bound manifest presentations (at most one per 10 min per peer) and pairing transcripts (`trust.md` §2.1). Latency of tens of milliseconds per signature through the helper is therefore irrelevant. The session leaf key stays in JVM memory only (`trust.md` §2.6).

| Tier (`keyStorage`) | Where | Available on | Protects against | Does **NOT** protect against |
|---|---|---|---|---|
| **T2 `secure-enclave`** (default where available) | Key generated **inside the Secure Enclave** by `asom-mac-helper` (CryptoKit); the wrapped `dataRepresentation` blob stored at `<container>/node/nik.se` (0600); signing = node passes blob + data to the helper | Every Mac that runs macOS 27 (all Apple silicon); Intel Macs with Touch Bar and Touch ID [FM13]; pending S-M1 [AM01] | **Extraction and cloning to another machine**: the private key never exists outside the Enclave, and the blob is useless on another Mac [FM13][AM23]. Offline disk theft (with FileVault) | **Use of the key on this Mac by any process that obtains the blob**: the blob is **not** bound to asom's App ID or Team ID [FM15]. What keeps other processes from the blob is the container (§3.3a), an OS policy, not hardware. Root, Full-Disk-Access apps, a user who approves access, and code injected into the node (§7.3) can sign as the node while the Mac runs. **Attestation:** none (App Attest is rejected, `manifest.md` §5.5); peers see "hardware-backed (self-reported)". **Survival:** erase/restore or a logic-board swap destroys the key → re-pair on every peer |
| **T0 `file`** (fallback) | PKCS#8 at `<container>/node/nik.p8` (0600); optionally passphrase-wrapped with "serve while logged out" as the explicit opt-in that leaves it unwrapped (T17f) | Macs without an Enclave; Enclave self-test failure; CI; mode B until S-M5 | Other unprivileged users; on macOS 15+ generic cross-team readers (SIP-protected on 15/26, **denied by default on 27** [FM17][FM19]; whether that covers every kind of process is AM08) | Everything T2 does not, **plus extraction and silent cloning**: a copied file is the node. Migration Assistant or a restored backup copies it (hence the exclusion and the binding guard, §3.3e) |
| ~~T1 `os-keystore` (login keychain)~~ | — | — | — | **Rejected on macOS.** (1) The file-based keychain "is on the road to deprecation" [FM16]. (2) The data protection keychain needs entitlements authorised by a **provisioning profile**, an app-like bundle and a user context, so it is unavailable to mode B [FM16]. (3) The **`security` CLI route is worse than T0**: the key crosses a command line when stored, and the item then trusts `/usr/bin/security`, so any same-user process can read it silently with `find-generic-password -w` [AM14; demonstrated in CI, §10.3 MC2]. (4) The JDK `KeychainStore` returns the private key into JVM memory, adds nothing the Enclave does not, and is file-keychain only. (5) FileVault already provides at-rest protection |

**Tier selection (`NikTierSelector`):** `hello.se == true` → `se.create` → `se.selftest` → T2; otherwise T0; never silently downgrade an existing T2 identity (a missing or failing blob is `NIK_UNAVAILABLE` and needs a user decision: re-pair with a new identity). The tier is written into `HELLO` and the manifest `subject.keyStorage` as `secure-enclave` or `file`, always shown "self-reported".

**What no macOS tier guarantees.** That the software using the key is unmodified asom; that the key is used only by asom; anything about a running, unlocked, compromised Mac. `trust.md` §4.4 applies unchanged.

---

## 6. Inference backends

| Backend | mesh-1 (D-v2 macOS) | Later | Why |
|---|---|---|---|
| **llama.cpp Metal** via the shared JNI surface (arm64) | **YES** | — | Metal is llama.cpp's default on macOS [FM35]; same GGUF files and pinned commit as Android/Linux/Windows, so claims are per `fileSha256` and comparable (C11). The comparability key `(backend=metal, commit, buildFlags)` |
| **llama.cpp CPU (+ Accelerate BLAS)** | **YES** (fallback and CI) | — | Always present [FM35]; `n_gpu_layers = 0` is the CI generation test and the fallback when Metal init fails (reported as a separate backend row) |
| llama.cpp x86_64 CPU (Intel) | no artefact (M-D7) | only if M-D7(b) | macOS 27 is Apple-silicon-only and macOS 28 drops Intel software [FM19][FM20]; Homebrew drops Intel in 2027 [FM31] |
| **MLX** (Apple silicon) | **no** | a separate backend with **separate manifest rows**, only by owner decision after a measured trigger | Different weight artefacts (MLX safetensors quantisations, not the catalogue's GGUF), a second in-process engine integration against v2's single-engine rule, and MLX is Apple-only [FM36]. **Trigger:** the D-v2 owner-device benchmark shows MLX decode ≥ 1.3× llama.cpp-Metal on the same model class and the owner wants the artefact pipeline |
| **Core ML** | **rejected** | — | Converted model packages, not GGUF; no parity with other nodes |
| Apple Foundation Models | **rejected** | — | OS-versioned, not content-addressable, not servable byte-identically elsewhere (`platforms.md` V08) |
| MLX distributed / Thunderbolt rings | **rejected** | — | Sharding (parked, D10) |

**Engine rules carried over.** In-process JNI (D21a); single-flight queue; cancellation bounded by one engine call on Metal (B6: llama.cpp's abort callback is CPU-only [F18]); `n_ubatch` chosen so one Metal call is about 1 s or less, recorded in `settings.batchTokens`. **Memory guard:** fit = model bytes + KV budget + engine buffers ≤ min(physical − reserve, `recommendedMaxWorkingSetSize`) [FM39]; on unified memory the GPU working-set bound is the binding one. **Thermal signal:** `thermalState` exists on every Mac [FM39], so the `standard` plan can run (B14 satisfied); the coarse four-level signal is recorded as such. On laptops the charger rule applies.

**Benchmark baseline consequence (D-C, R2-DIRECTIVES-2, R2-OVERCLAIM-4).**
- MLPerf Client v1.6 measures LLM inference on macOS through llama.cpp-Metal and MLX, and its GUI is on the Mac App Store [FM34]. **asom builds no macOS benchmark product.** Desktop and Apple coverage is not a differentiator (directive D-C).
- `asom bench` on a Mac (the desktop CLI shell, D-v2) exists only to produce the **signed manifest** and the **router priors** for asom's own serving path (llama.cpp Metal at asom's pinned commit).
- Desktop comparability adopts **MLPerf Client's model set and metric definitions** (time to first token, tokens per second) once a spike (**S-B1d**, the desktop twin of S-B1: read `mlcommons/mlperf_client` at the v1.6 tag) pins the formulas and prompt sets. Phones keep MLPerf Mobile's (§6.1 of the design).
- Wording: *"measured with MLPerf Client's model set and metric definitions; not an MLPerf result"*. **Never "MLPerf-comparable"** (R2-OVERCLAIM-4).
- Optional owner cross-check (NOV, informational): run MLPerf Client's llama.cpp-Metal path and `asom bench` on the same Mac and model; a large gap flags a harness or build problem. MLPerf Client downloads models itself [FM34]; that is the owner's action, outside asom's ledger.

---

## 7. Runtime and code strategy

### 7.1 Recommendation

**Kotlin/JVM, the same `:node-desktop` as Linux and Windows, plus:**
1. one pure-JVM adapter module **`macplatform`** (Kotlin; no `android.*`; no JNA; compiles and runs its fakes in the Linux build container);
2. one **Swift helper executable** `asom-mac-helper` (§3.5), built only on macOS runners;
3. one **native launcher** `asom-node` (C, about 200 lines, §7.3) that hosts the JVM with a scrubbed environment.

No KMP, no Compose for Desktop, no second protocol implementation on the Mac. Code compiles with `--release 17`; the shipped runtime is a jlinked **Temurin 21 LTS aarch64** (the design's conformance lanes are JDK 17 and 21; the macOS runner images carry both [FM37]).

### 7.2 The seam (the `DesktopPlatform` interface shared with Linux and Windows)

The interface is the one the Windows section sketched (`paths`, `nikStore`, `power`, `presence`, `gpuContention`, `thermal`, `listenerGate`, `controlSocket`). macOS implements it as follows:

| Port | macOS implementation | Source of truth |
|---|---|---|
| `paths` | mode A: `~/Library/Group Containers/<TEAMID>.xyz.mdhv.asom/{node,registry,ledger,manifests,models,diag,tmp}`; mode B: `/Library/Application Support/xyz.mdhv.asom/…`; control socket in `<per-user temp dir>/xyz.mdhv.asom/` | §3.3 |
| `nikStore` | `SecureEnclaveNik` (helper `se.*`) or `FileNik` (T0); raw r‖s everywhere (C2) | §5 |
| `power` | helper `power.*`, `assert.*`, `sleep.will`/`wake` events → `PowerStatus`, `Hold`, `os_sleep_imminent` | FM07, FM08 |
| `presence` | helper `presence.get` every 10 s; all tagged `presence` (LP-1) | AM13 |
| `gpuContention` | helper `gpu.get` minus the engine's own in-flight fraction; `null` if unavailable | AM12 |
| `thermal` | helper `thermal.get` + push events; mapping in §2.1 | FM39 |
| `listenerGate` | always OPEN (no OS inbound gate for accept [FM01]); reports the Application Firewall state for the doctor only | FM41 |
| `controlSocket` | `<per-user temp dir>/xyz.mdhv.asom/ctl.sock` (from helper `paths.get`), `getpeereid` both ways | FM27 |

### 7.3 The hardened node launcher (`asom-node`)

**Problem.** A jpackage launcher passes the caller's environment into the JVM, and the VM reads `JAVA_TOOL_OPTIONS` (and `_JAVA_OPTIONS`) inside `JNI_CreateJavaVM` whoever started it [AM26]. The node's executable carries the entitlement that opens its group container. If that executable were a stock jpackage launcher, any same-user process could run it with, for example, `-Xbootclasspath/a:x.jar -Djava.system.class.loader=X` in `JAVA_TOOL_OPTIONS` and execute its own code **inside** the process that may read the Enclave blob or the T0 key. Library validation does not stop this (it is Java bytecode, not a dylib), and the attach mechanism is a second door.

**Mechanism (spec).**
1. `unsetenv` `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, `CLASSPATH`, `JAVA_HOME`; the hardened runtime already ignores `DYLD_*` without the `allow-dyld-environment-variables` entitlement, which is never granted [FM21][FM22].
2. Accept only `--mode=agent|daemon|selftest`; any other argument exits 64 before the VM exists. A **dev state** is never an argument: the node derives it when its own signature carries no Team ID (unsigned or ad-hoc CI builds), then keeps state in `~/Library/Application Support/xyz.mdhv.asom-dev/`, prints `UNSIGNED BUILD: container protection absent` in `asom status`, and pairs only with test-only peers.
3. Resolve its own path (`_NSGetExecutablePath` + `realpath`); require `libjvm.dylib` and the jars at fixed relative paths inside the same bundle (no symlink leaving it).
4. `chdir` to the state root, so no working-directory VM file can be picked up.
5. `dlopen` the bundle's `libjvm.dylib` (Team-ID signed, so library validation accepts it) and call `JNI_CreateJavaVM` on a secondary thread (8 MiB stack; the main thread parks in a run loop, as the stock launcher does) with a **fixed** option list: explicit `-Djava.class.path`, `-XX:+DisableAttachMechanism`, `-XX:-CreateCoredumpOnCrash`, `-XX:ErrorFile=<state>/diag/hs_err_%p.log`, `-Djava.awt.headless=true`, `-Djava.io.tmpdir=<state>/tmp`, `-Dfile.encoding=UTF-8`.
6. The jlinked runtime omits `java.instrument`, `jdk.attach`, `jdk.jdwp.agent` and `jdk.management.agent`, and ships no `bin/` commands (`jlink --strip-native-commands`).

**Evidence required (CI, §10.3 MC4):** the same probe, run against the stock jpackage launcher and against `asom-node`, **executes** in the first case (demonstrating the risk) and **does not** in the second.
**What it does NOT stop:** root; processes the user granted Full Disk Access; the user approving another app's access in Privacy & Security; a memory-safety bug in the node, the helper, llama.cpp or the JDK; a malicious update signed with the owner's Developer ID. The CLI (`asom`) and companion (`ASOM`) remain stock launchers **with no entitlement**, so injecting into them gains nothing the user's own shell does not already have.
The same launcher compiles for Linux; the Linux section may adopt it for the `systemd --user` mode, where it would not change T17(g) (no container isolation exists there).

### 7.4 The `apple/` Swift package and its boundary with the JVM node

**Boundary (normative intent).**

| Concern | macOS node (JVM) | `apple/` AsomKit (Swift) | `desktop/packaging/macos/helper` |
|---|---|---|---|
| Protocol, router, ledger, manifest sign and verify, bench core on a **Mac** | **the only implementation** | never linked into a Mac node | never |
| Apple framework calls for the Mac node (Enclave, `SMAppService`, IOKit, `ProcessInfo`) | — | — | **yes, and nothing else** |
| Conformance: the Apple lane (CryptoKit) | JVM lanes on the macOS JDK too | **yes**: `swift test` on macOS runners (CryptoKit) and in the Linux container (swift-crypto) over `lab/conformance/` | helper-protocol vectors only |
| Borrowing from a Mac | the owner CLI through the node | **no** (one node identity per machine; a second key on the same Mac would be a second node) | — |
| iOS / iPadOS benchmark app (M3), requester (M4), iPad lender (M5) | — | **yes**; the iOS section owns those targets | — |

**Why the helper is not in `apple/`.** It is part of the JVM node's platform adapter; keeping it under `desktop/packaging/macos/` lets the owner drop macOS by deleting one directory (as with Windows), and keeps the helper from growing into a second, unconformed protocol implementation inside the Mac node.
**Byte accounting (R2-OVERCLAIM-6).** The Mac node's peer TLS is JSSE (`SSLEngine`), the same stack as Linux and Windows, so IC-2's TLS-record-layer byte counts and the L-L15 record tap are implementable on Macs exactly as on the other desktops. The open question R2-OVERCLAIM-6 raises concerns only Network.framework, i.e. the iOS/iPadOS targets of this package.
**C10 (one harness per OS):** a Mac benchmarks only with the JVM `asom bench`; the iOS `AsomBench` app is not offered on Macs ("iPhone and iPad apps on Mac" availability off).

**Package layout** (`apple/Package.swift`, swift-tools 6.0; `platforms: [.macOS(.v15), .iOS(.v17)]`; swift-crypto only `.when(platforms: [.linux])`; shipped Apple builds use system CryptoKit and carry no third-party crypto):

| Target | Contents | Builds on Linux | Phase |
|---|---|---|---|
| `AsomJSON` | strict tokenizer (duplicates, trailing data, floats, `-0`, lone surrogates rejected), JCS integer profile with UTF-16 key order | yes | L0.7 |
| `AsomDSSE` | PAE, standard/url-safe base64, strict SPKI (T1), ES256 verify, per-export P-256 sign, DER↔raw, low-S on produce | yes | L0.7 |
| `AsomManifest` | typed decoder, the 19-step verifier + 15a–15c, audience projections (M06 incl. file), `asom.manifest-text/1` renderer (M05) | yes | L0.7 |
| `AsomBenchCore` | M04 integer derivation (for M3) | yes | L0.7/M3 |
| `AsomWire` | frame codec, pure | yes | M4 (iOS section) |
| `AsomTransport`, `AsomRequester` | Network.framework TLS, `verifyPeerChain` (swift-certificates), `RemoteMesh` | no (`#if canImport(Network)`) | M4 (iOS section) |
| `asom-conformance` (executable) | runs a vector family and prints one line per vector in the JVM runner's format, for `diff` | yes | L0.7 |

Spike FM42 is the first slice of `AsomDSSE`; the iOS spike in `platforms/ios-spike/` uses the same module name, and the reviser should merge them into this one package.
**Not in the boundary:** Foundation's `JSONSerialization` is not the strict parser (it happened to reject M03-123 for trailing data, but it is not specified to reject duplicates or to preserve number lexemes); `AsomJSON` is hand-written, as the design requires for step 11.

### 7.5 Drift cost (estimate)

- Sizes: `macplatform` about 1.5–2.5k lines of Kotlin; the helper about 1–1.5k lines of Swift; the launcher about 200 lines of C; packaging scripts about 600 lines of shell.
- Protocol, router, ledger and manifest code are shared, so the W/M/R conformance families simply run on the macOS JDK as well. Drift risk is **platform behaviour under shared code**:
  1. **Helper protocol**: its own JSON-Lines vectors, run by both the Kotlin client and the Swift helper tests.
  2. **File semantics**: APFS is case-insensitive by default, so no two stored names may differ only by case; `sun_path` is 104 bytes; `FileChannel.force` maps to `fsync`, which "will flush all data from the host to the drive" but not the drive's cache (F_FULLFSYNC does) [FM43]. The design claims durability against **process death only**, which `fsync` meets; power loss stays unclaimed.
  3. **Sockets**: IPv6 scope ids such as `%utun4` in `destAddr`; interface renumbering after wake.
  4. **Charset/locale**: explicit UTF-8 on every byte-exact path (JCS, M05 text, JSONL), as on Windows.
- **What conformance does not catch:** macOS-only branches (helper, launcher, paths, registration). Those are covered by `@EnabledOnOs(MAC)` integration tests and the device checklist.
- **The Swift lane's drift** is the iOS drift risk (K18), reduced by running it on the same vectors in CI; on the Mac it is only a cross-check, never a shipped path.

### 7.6 UI note

The tray (AWT, separate process) is the Invariant 7 placeholder. Compose for Desktop is not proposed (its tooling is usually configured through the multiplatform plugin, which the KMP ban may be read to cover). A SwiftUI menu-bar extra would be the natural macOS UI but needs D14 part A (IC-7) first.

---

## 8. Packaging, signing, distribution and updates

### 8.1 Build pipeline (macOS runner; every step scripted under `desktop/packaging/macos/scripts/`)

1. **Runtime.** `jdeps --print-module-deps --ignore-missing-deps --multi-release 21 lib/*.jar` → `$JAVA_HOME_21_arm64/bin/jlink --add-modules <list>,jdk.crypto.ec,jdk.net --strip-debug --strip-native-commands --no-header-files --no-man-pages --compress=zip-6 --output build/runtime`. A check fails the build if `java.instrument`, `jdk.attach`, `jdk.jdwp.agent` or `jdk.management.agent` is present. `java.desktop` is included only for the companion's AWT tray; the node runs with `-Djava.awt.headless=true`.
2. **Native.** `native/build-llama-jni.sh`: CMake at the pinned llama.cpp commit, `-DGGML_METAL=ON -DGGML_METAL_EMBED_LIBRARY=ON -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0` [FM35][AM22] → `libasom-llama-jni.dylib` + ggml dylibs; `otool -L` must list only `@rpath` libraries and system frameworks (Metal, Foundation, Accelerate); sha256 of each written to `native.sha256`.
3. **Helper and launcher.** `swift build -c release --arch arm64 --package-path helper`; `cmake --build launcher`.
4. **App image.** `jpackage --type app-image --name ASOM --app-version <v> --mac-package-identifier xyz.mdhv.asom.desktop --input build/lib --main-jar node-desktop.jar --main-class xyz.mdhv.asom.desktop.CompanionKt --runtime-image build/runtime --add-launcher asom=cli.properties --resource-dir resources`. Then copy `asom-node`, `asom-mac-helper`, the dylibs into `Contents/Frameworks`, and the agent plist into `Contents/Library/LaunchAgents`. **No native code inside any jar** (a check lists `*.dylib`/`*.jnilib` inside every jar and fails if any is found; notarisation would reject unsigned nested code anyway [FM24]).
5. **Sign inside-out** (`scripts/sign.sh`, never `--deep`): every dylib in `Frameworks` and `runtime`, then `asom-mac-helper` (hardened runtime, **no entitlements**), `asom` and `ASOM` (hardened runtime, `allow-jit` only), `asom-node` (hardened runtime, `allow-jit` + `com.apple.security.application-groups = [<TEAMID>.xyz.mdhv.asom]`), then the bundle. Each with `codesign --force --timestamp --options runtime --entitlements <file> -s "Developer ID Application: <owner> (<TEAMID>)"` [FM24]. `allow-unsigned-executable-memory` is added to the JVM launchers only if S-M3 shows HotSpot needs it [AM04]. `disable-library-validation`, `allow-dyld-environment-variables`, `get-task-allow`, `debugger` are never granted; jpackage's default entitlement files are never used [FM26].
6. **Verify** (`scripts/verify-signing.sh`): `codesign --verify --strict --deep -vvv ASOM.app`; `codesign -d --entitlements - <exe>` equals the expected file byte-for-byte for each of the four executables; no Mach-O without a Team ID; `codesign -dvv` shows `flags=0x10000(runtime)` and a `Timestamp=` line.
7. **Package.** `pkgbuild --component build/ASOM.app --install-location /Applications --identifier xyz.mdhv.asom.desktop --version <v> build/ASOM-component.pkg`, then `productbuild --distribution resources/distribution.xml --package-path build --sign "Developer ID Installer: <owner> (<TEAMID>)" ASOM-<v>-arm64.pkg`; `distribution.xml` declares `hostArchitectures="arm64"` (macOS 27 defaults undeclared packages to arm64 anyway [FM19]); **no pre/postinstall scripts** in mode A. DMG: `hdiutil create -volname ASOM -srcfolder build/dmg -format UDZO ASOM-<v>-arm64.dmg`, then `codesign` the DMG.
8. **Notarise and staple** (`scripts/notarize.sh`): `xcrun notarytool submit ASOM-<v>-arm64.pkg --key AuthKey.p8 --key-id <id> --issuer <uuid> --wait`, then `xcrun notarytool log <submission-id> …` (kept as a build artefact, warnings fail the job), `xcrun stapler staple` on the pkg and the dmg, `xcrun stapler validate`, `spctl -a -vvv -t install ASOM-<v>-arm64.pkg` → `accepted … source=Notarized Developer ID` [FM24].

### 8.2 Signing credentials (owner-held; CI secrets on `main` and tags only)

| Secret | Used by | Note |
|---|---|---|
| Developer ID Application certificate + private key (`.p12`, password) | `sign.sh` | imported into a temporary keychain created per job (`security create-keychain`, `import`, `set-key-partition-list`), deleted at job end |
| Developer ID Installer certificate (`.p12`) | `productbuild --sign` | same |
| App Store Connect API key (`.p8`, key id, issuer id) | `notarytool` | notarisation only; least-privilege role |
| Team ID | entitlements, group id | not secret, but fixed forever (M-D10) |

Forks and pull requests build **unsigned** (or ad-hoc-signed with `codesign -s -`) artefacts labelled `UNSIGNED — not for release`; they are never attached to a release or a cask.

### 8.3 Distribution

- **GitHub Releases:** `ASOM-<v>-arm64.pkg` (primary; root-owned install in `/Applications`), `ASOM-<v>-arm64.dmg` (drag-install; the app is then user-owned, protected from other teams' modification by App Management [FM40]), `SHA256SUMS`, and the Team ID plus certificate fingerprints in the release notes.
- **Homebrew, own tap first** (`asystemofcells/homebrew-asom`, `Casks/asom.rb`). Homebrew 6 requires users to trust a third-party tap explicitly [FM31]; the README says so. Template:

```ruby
cask "asom" do
  version "0.0.0"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  url "https://github.com/asystemofcells/asystemofmodels/releases/download/desktop-v#{version}/ASOM-#{version}-arm64.pkg"
  name "ASOM"
  desc "Sovereign model-routing node for your own paired devices"
  homepage "https://github.com/asystemofcells/asystemofmodels"
  depends_on arch: :arm64
  depends_on macos: ">= :sequoia"
  pkg "ASOM-#{version}-arm64.pkg"
  binary "/Applications/ASOM.app/Contents/MacOS/asom"
  uninstall script:   { executable: "/Applications/ASOM.app/Contents/MacOS/asom", args: ["node", "disable", "--yes"] },
            launchctl: "xyz.mdhv.asom.node",
            pkgutil:   "xyz.mdhv.asom.desktop"
  zap trash: "~/Library/Group Containers/<TEAMID>.xyz.mdhv.asom"
end
```

- **Official `homebrew/cask`** only after a notarised release exists (it must pass Gatekeeper [FM32]) and the owner wants it (notability rules apply).
- **Homebrew formula (build from source): rejected.** It cannot produce a Team-ID-signed, notarised bundle, so the node would lose the group container, Local Network attribution and a stapled ticket (standalone binaries cannot be stapled [FM24]); a hand-installed plist would hit macOS 27's quarantine rule [FM19].
- **Disclosure (not asom's egress):** Homebrew sends analytics unless the user opts out [FM33]; Gatekeeper may look up the notarisation ticket online at first launch [FM24]; `stapler` uses CloudKit when the owner staples [FM24]; macOS crash reports go to Apple only if the user opted in to sharing analytics (assumption stated, not verified here).

### 8.4 Updates

**No in-app updater.** Sparkle-style appcast checks are automatic background network events with no Invariant 3 class, exactly the Windows argument. Updates arrive only when the user runs `brew upgrade --cask asom` or installs a new pkg. The running node keeps the old code until `asom node restart`; the CLI reports the skew. Upgrades never touch the group container.

### 8.5 Uninstall

`asom node disable` unregisters the agent (the cask's `uninstall` runs it, then `launchctl` as a fallback, then removes the package receipt). The identity, registry and ledger stay unless the user runs `asom node forget --destroy-identity` (TTY-confirmed; deletes the blob or file and tells the user to revoke this Mac on every peer) or `brew uninstall --zap`. An upgrade can never silently destroy a paired identity.

### 8.6 OS floor and Intel

- **Floor: macOS 15 Sequoia, arm64 only.** macOS 15 is where Local Network privacy and SIP-protected group containers begin [FM01][FM17]; supporting 13–14 would need a second key-location story and a different Local Network narrative. The macOS 14 runner images are deprecated [FM37].
- **Intel: no artefact recommended (M-D7).** macOS 26 is the last Intel release, macOS 27 is Apple-silicon-only, macOS 28 drops Intel software, and Homebrew drops Intel in September 2027 [FM19][FM20][FM31]. If the owner needs an Intel Mac in the mesh, the fallback is an **x86_64 CPU-only requester build** compiled on `macos-26-intel` (T0 key unless the Mac has Touch ID, no Metal lending), supported until September 2027 at the latest.

---

## 9. What GitHub-hosted runners can honestly verify for macOS

| Item | `macos-latest` (macOS 26 arm64) | `xcode-27` (macOS 27.0 arm64, preview) | Linux container / `ubuntu-latest` | Remains NDV / NOV |
|---|---|---|---|---|
| Lab conformance families (W/M/R) on the macOS JDK 17 and 21 | **yes** (`JAVA_HOME_17_arm64`, `JAVA_HOME_21_arm64` [FM37]) | yes | yes (the gate lane) | — |
| Root `jvmTest` on macOS | yes (informational; Linux stays the gate) | — | yes | — |
| `macplatform` unit tests with a scripted fake helper | yes | yes | **yes** | — |
| `apple/` Swift lane: `swift build`, `swift test` (CryptoKit) | **yes** | yes | **yes** (swift-crypto; FM42 ran it) | — |
| `apple/` for iOS: `xcodebuild -scheme AsomKit -destination 'platform=iOS Simulator,name=iPhone 17' test` | **yes** (iOS 26.x simulators [FM37]) | yes (iOS 27 SDK) | no | on-device signing and Enclave (iOS section) |
| Helper: `swift test` + protocol vectors; real `thermal.get`, `power.get`, `mem.get`, `platform.uuid` | **yes** (VM values, not representative) | yes | protocol codec only | real thermal/power behaviour |
| Helper: `assert.hold` visible in `pmset -g assertions`; released after `kill -9` of the node [AM11] | **yes** | yes | — | real sleep prevention |
| Helper: `se.*` | **SKIPPED** if `se=false` [AM02] | same | — | **S-M1** on the owner's Mac |
| Helper: `presence.get`, `gpu.get` | records whether the values exist [AM12][AM13] | same | — | whether they track real use (S-M8) |
| `svc.register` (SMAppService) | recorded, **non-gating** [AM20] | same | — | Login Items UX, approval flow |
| AM14 demonstration: `security add-generic-password` then an unrelated shell reads it with `find-generic-password -w` without a prompt | **yes** (temporary keychain) | — | — | — |
| llama.cpp JNI: Metal build compiles; CPU (`n_gpu_layers=0`) tiny-GGUF generation equals the Linux lane's vector | **yes** | yes | CPU only (Linux) | Metal correctness and speed (AM17); 8B decode/prefill on the owner's Mac replacing §7.1's estimate |
| jlink + app image + pkg + dmg, **unsigned or ad-hoc** | **yes** | yes | — | — |
| **Developer ID signing, notarisation, stapling, `spctl` acceptance** | **only on `main`/tags with the owner's secrets** (§8.2) | same | — | Gatekeeper first-launch UX (NOV) |
| Library validation left on + `allow-jit` only (S-M3, AM04) | only with a Team ID (signed builds) | same | — | — |
| Env-injection probe: stock launcher executes the probe; `asom-node` does not (AM05) | **yes** (the probe needs no signing) | yes | the Linux build of the launcher | — |
| Node smoke started from the job shell (not through `SMAppService`): control socket, `asom status`, **no listening socket** until mesh listen; then exactly one on `<en0 IPv4>:11436`, never `*` or loopback (`lsof -nP -iTCP -sTCP:LISTEN`) | **yes** | yes | — | — |
| Two-node smoke on one runner (NIC IPv4 + IPv6 link-local), test-only pairing, one borrow each way, ledger row asserts (L-L14, L-L15, L-L16) | **yes** (LAB evidence; whether Local Network privacy applies to a job-shell child is recorded, not assumed) | yes | the Linux namespace suite is the M1 gate (3) | cross-machine, Wi-Fi and overlay paths |
| W08 hostile-node suite on the macOS JDK | **yes** | yes | yes | — |
| Group-container denial: an unsigned script reading the container is refused (S-M6, AM08) | prompt behaviour on 26 is not testable headless | **yes, signed builds only** | — | real user flows |
| Homebrew: `brew style` / `brew audit --cask` on the tap; `brew install --cask` of a notarised pkg (sudo available [FM37]) | **yes** (Homebrew 6.0.22) | yes | — | tap-trust UX |
| Local Network prompt, Login Items approval, sleep/lid/Power Nap/FileVault, Tailscale variants and log opt-out, Application Firewall, MacBook thermals | no | no | no | **NDV / NOV** |
| Intel x86_64 CPU build (only if M-D7(b)) | — | — | — | `macos-26-intel` compiles it and runs the CPU tiny-model test; real Intel Macs are NDV |

**Gate wording rule.** Every CI result above is recorded in `PROGRESS.md` as **"CI (hosted VM) evidence"**. The runners are 3-vCPU, 7 GB M1 VMs [FM37]; no speed, thermal or memory number from them is ever quoted. **If macOS lends in M1** (D24(b)), the M1 row gains (R2-DIRECTIVES-11): W08 on the macOS JDK; S-M1 and S-M3 outcomes recorded; a Local Network check for the agent (first LAN dial shows the ASOM prompt; denial produces a `local-network-denied` DIAL row); gate (6) idle-30-min on a Mac mini on AC; gate (10) "a Mac rebooted to the FileVault login screen accepts no offer" (trivially true in mode A: nothing runs); and gate (5) quiescence on a Mac borrower captured by a root packet capture on the Mac itself covering every `en*` and `utun*` interface (the exact `tcpdump` invocation for all interfaces on macOS is pinned in the device checklist), **never passed by a weaker method** (R2-OVERCLAIM-5).

---

## 10. Implementation scaffold plan (`desktop/packaging/macos/` and `apple/`)

**Entry rules.**
- **MC0 (the `apple/` Swift lane) is lab work** under AD-4 / D1a and D24(b): it ships nothing and may start with the lab. Its first slice already ran in this session (FM42).
- **MC1 onward is D-v2 work**: it starts only when D-v2's entry criteria hold (v2 shipped on Android; D4; the v4 design session held and recorded, R2-CONFORMANCE-3; D23 for the new module; D24(b) placing macOS in D-v2; an Apple-silicon Mac and Developer Program membership exist). Otherwise it starts at M2.
- Everything macOS-specific lives under `desktop/packaging/macos/`, so the owner can drop macOS by deleting it. Outside it the builder adds only: one `include` line in `desktop/settings.gradle.kts`; one workflow file; `.gitattributes` entries (`lab/conformance/** -text`, `desktop/**/fixtures/** -text`, `desktop/packaging/macos/helper-protocol/vectors/** -text`).

### 10.1 File tree

```
desktop/packaging/macos/
├── README.md                                    what this builds, modes, gates, owner inputs, what is NOT guaranteed
├── macplatform/                                 Gradle subproject :packaging:macos:macplatform (pure JVM, no android.*, no JNA)
│   ├── build.gradle.kts                         kotlin-jvm, --release 17; deps: :node-desktop (DesktopPlatform), kotlinx-serialization
│   └── src/
│       ├── main/kotlin/xyz/mdhv/asom/desktop/mac/
│       │   ├── MacPlatform.kt                   DesktopPlatform impl; mode = AGENT | DAEMON | DEV
│       │   ├── MacPaths.kt                      group container / daemon dirs; 0700/0600 enforcement; $TMPDIR socket dir; sun_path check
│       │   ├── MigrationGuard.kt                binding.json = sha256(platform uuid digest ‖ salt); NIK_MIGRATED
│       │   ├── NodeLock.kt                      /var/tmp/xyz.mdhv.asom.node.lock flock (one lending node per Mac)
│       │   ├── helper/HelperProcess.kt          spawn Contents/MacOS/asom-mac-helper serve; pipes; EOF/restart policy; PROBE_LOST
│       │   ├── helper/HelperProtocol.kt         strict JSON-Lines codec for protocol v1 (unknown fields rejected; integers only)
│       │   ├── keys/SecureEnclaveNik.kt         T2 via helper se.*; blob persisted by the node; start-up self-test
│       │   ├── keys/FileNik.kt                  T0 binding to MacPaths (the PKCS#8 code itself is shared in :node-desktop)
│       │   ├── keys/NikTierSelector.kt          T2 → self-test → T0 on first creation only; never silent downgrade
│       │   ├── power/MacPowerPort.kt            power/assert/sleep.will/wake → PowerStatus, Hold, os_sleep_imminent (2 s ack budget)
│       │   ├── presence/MacPresencePort.kt      hidIdle/screenLocked/consoleUserIsSelf → presence signals (LP-1)
│       │   ├── thermal/MacThermalPort.kt        thermalState → band/governor mapping (§2.1)
│       │   ├── gpu/MacGpuProbe.kt               device utilisation − own in-flight fraction → other_busy permille, or null
│       │   ├── net/InterfaceEligibility.kt      utun/en* selection; overlay prefix re-resolution on wake; LN-denied errno mapping
│       │   ├── ctl/MacControlSocket.kt          AF_UNIX + getpeereid both ways (jdk.net SO_PEERCRED)
│       │   ├── svc/ServiceRegistration.kt       enable/disable/status through helper svc.*; requiresApproval guidance
│       │   ├── tray/TrayCompanion.kt            AWT SystemTray placeholder (glyph + label states); reads status via the socket
│       │   └── doctor/MacDoctor.kt              LN hint, firewall state, pmset assertions, lid/AC, Tailscale variant + no-logs flag,
│       │                                        key tier, container protection probe, version skew, Homebrew analytics note
│       └── test/kotlin/xyz/mdhv/asom/desktop/mac/
│           ├── fakes/FakeHelper.kt              replays helper-protocol/vectors/*.jsonl (runs on any OS)
│           ├── HelperProtocolTest.kt            every vector round-trips; malformed lines rejected
│           ├── NikTierSelectorTest.kt           tier order; keyStorage labels; no silent downgrade
│           ├── SleepDrainTest.kt                sleep.will → listener closed, INFER_END interrupted, outcome rows, ack ≤ 2 s
│           ├── PresenceLawsTest.kt              W07-presence on macOS inputs (LP-1/LP-2), incl. another console user
│           ├── MigrationGuardTest.kt            binding mismatch → NIK_MIGRATED, registry rows unpaired
│           ├── StdoutSecretTest.kt              H3 on the macOS entry points
│           └── mac/                             @EnabledOnOs(MAC): HelperIT, PowerAssertionIT, ControlSocketIT, SecureEnclaveIT (skips if se=false)
├── helper/                                      Swift package (macOS only for the executable)
│   ├── Package.swift                            targets: HelperProtocol (pure Swift, builds on Linux), asom-mac-helper (executable)
│   ├── Sources/HelperProtocol/Messages.swift    Codable request/response/event types for protocol v1
│   ├── Sources/asom-mac-helper/main.swift       stdin loop; dispatch; stdout writer; exits on EOF
│   ├── Sources/asom-mac-helper/Enclave.swift    CryptoKit SecureEnclave.P256.Signing: create/sign/selftest; raw r||s; DER SPKI
│   ├── Sources/asom-mac-helper/Power.swift      IOPMAssertionCreateWithName; IORegisterForSystemPower run-loop thread; IOPowerSources
│   ├── Sources/asom-mac-helper/Probes.swift     ProcessInfo thermal/lowPower; HIDIdleTime; CGSession lock; console user; IOAccelerator; memory
│   ├── Sources/asom-mac-helper/Service.swift    SMAppService agent/daemon register/unregister/status; openSystemSettingsLoginItems
│   ├── Sources/asom-mac-helper/Backup.swift     isExcludedFromBackupKey; IOPlatformUUID digest
│   └── Tests/HelperTests/                       protocol vectors; assertion hold/release; Enclave tests skipped when unavailable
├── helper-protocol/
│   ├── SCHEMA.md                                protocol v1 (the §3.5 table, normative); versioning rule
│   └── vectors/*.jsonl                          request/response/event pairs shared by Kotlin and Swift tests
├── launcher/
│   ├── asom_launcher.c                          §7.3: env scrub, argv allow-list, realpath checks, chdir, JNI_CreateJavaVM on a thread
│   ├── CMakeLists.txt                           builds for macOS arm64 (and Linux x86_64 for the portability test)
│   └── probe/                                   Probe.java + build script for the env-injection test (test-only)
├── native/
│   ├── CMakePresets.json                        macos-arm64-metal (default); macos-x86_64-cpu (disabled unless M-D7(b))
│   └── build-llama-jni.sh                       pinned llama.cpp commit; GGML_METAL_EMBED_LIBRARY=ON; otool -L check; sha256 list
├── resources/                                   jpackage --resource-dir and bundle inputs
│   ├── Info.plist.template                      bundle id, LSUIElement, LSMinimumSystemVersion 15.0, NSLocalNetworkUsageDescription
│   ├── xyz.mdhv.asom.node.plist                 LaunchAgent (§3.2)
│   ├── xyz.mdhv.asom.noded.plist                LaunchDaemon, mode B (M2; excluded from the bundle until then)
│   ├── entitlements/node.entitlements           allow-jit; application-groups [<TEAMID>.xyz.mdhv.asom] (TEAMID substituted at sign time)
│   ├── entitlements/jvm-launcher.entitlements   allow-jit (asom, ASOM)
│   ├── entitlements/helper.entitlements         empty dict
│   ├── distribution.xml                         productbuild distribution: one choice, hostArchitectures="arm64", minimum OS 15.0
│   └── ASOM.icns
├── scripts/
│   ├── build-runtime.sh                         jdeps → jlink (Temurin 21 aarch64) --strip-native-commands; forbidden-module check
│   ├── build-app-image.sh                       jpackage --type app-image; installs launcher, helper, dylibs, agent plist; no-native-in-jar check
│   ├── sign.sh                                  inside-out codesign, per-executable entitlements, --timestamp --options runtime
│   ├── verify-signing.sh                        codesign --verify --strict; entitlement byte-compare; Team ID on every Mach-O
│   ├── build-pkg.sh                             pkgbuild/productbuild (hostArchitectures arm64; no scripts) + Installer signature
│   ├── build-dmg.sh                             hdiutil + codesign
│   ├── notarize.sh                              notarytool submit --wait; log; stapler staple/validate; spctl
│   ├── smoke-app.sh                             node from the job shell (unsigned build → dev state); status; listener checks; stop
│   ├── smoke-env-injection.sh                   probe vs stock launcher and vs asom-node
│   ├── smoke-two-node.sh                        two nodes on one runner; test-only pairing; borrow each way; row asserts
│   ├── demo-keychain-cli-weakness.sh            AM14 demonstration in a temporary keychain
│   ├── probe-container-denial.sh                S-M6 on the xcode-27 runner (signed builds only)
│   └── collect-evidence.sh                      writes the PROGRESS.md block, labelled "CI (hosted VM) evidence"
├── homebrew/
│   └── Casks/asom.rb.template                   §8.3 template (copied to the tap repo by the release job)
├── ci/
│   └── desktop-macos.yml                        canonical workflow (copied to .github/workflows/ by the builder)
└── docs/
    ├── MACOS_NODE.md                            user guide: enable, Login Items, Local Network, sleep/lid/FileVault, Tailscale variants, NOT guaranteed
    └── DEVICE_CHECKLIST_MACOS.md                every NDV/NOV item from §9 and §10.3 with exact commands and expected output

apple/                                           shared Swift package (the Apple conformance lane; later the iOS targets)
├── Package.swift                                swift-tools 6.0; macOS 15 / iOS 17; swift-crypto only for Linux
├── README.md                                    the §7.4 boundary, verbatim; "ES256 only"; "never linked into the Mac node"
├── Sources/AsomJSON/                            strict tokenizer + JCS integer profile
├── Sources/AsomDSSE/                            PAE, base64, strict SPKI, ES256 verify, per-export sign, DER↔raw, low-S
├── Sources/AsomManifest/                        typed decoder, verifier steps 1–19 + 15a–15c, projections, M05 renderer
├── Sources/AsomBenchCore/                       M04 derivation (integer, checked arithmetic)
├── Sources/asom-conformance/main.swift          prints one line per vector in the JVM runner's format
└── Tests/AsomConformanceTests/                  reads lab/conformance via ASOM_CONFORMANCE_DIR; per-family XCTest cases
```

### 10.2 Workflow sketch (`ci/desktop-macos.yml`)

```yaml
name: desktop-macos
on: { push: { branches: [main], tags: ['desktop-v*'] }, pull_request: {} }
permissions: { contents: read }
jobs:
  apple-swift-lane:                       # MC0 (lab; ships nothing)
    strategy: { matrix: { include: [ { os: macos-latest }, { os: ubuntu-latest, container: 'swift:6.1-noble' } ] } }
    runs-on: ${{ matrix.os }}
    container: ${{ matrix.container }}
    steps:
      - uses: actions/checkout@v4
      - run: swift test --package-path apple
      - run: swift run --package-path apple asom-conformance M01,M02,M03,M05,M06 lab/conformance > swift.lines
      - uses: actions/upload-artifact@v4
        with: { name: 'swift-lines-${{ matrix.os }}', path: swift.lines }
  jvm-lines:                              # the JVM runner's per-vector lines, for the diff
    runs-on: ubuntu-latest
    env: { ANDROID_HOME: '', ANDROID_SDK_ROOT: '' }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - run: ./gradlew -p lab :conformance-runner:run --args='lines M01,M02,M03,M05,M06' --quiet > jvm.lines
      - uses: actions/upload-artifact@v4
        with: { name: jvm-lines, path: jvm.lines }
  lane-diff:
    needs: [apple-swift-lane, jvm-lines]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v4
      - run: diff jvm-lines/jvm.lines swift-lines-macos-latest/swift.lines && diff jvm-lines/jvm.lines swift-lines-ubuntu-latest/swift.lines
  apple-ios-simulator:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - run: cd apple && xcodebuild -scheme AsomKit-Package -destination 'platform=iOS Simulator,name=iPhone 17' test
  macplatform:                            # MC1-MC2
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: swift test --package-path desktop/packaging/macos/helper
      - run: swift build -c release --package-path desktop/packaging/macos/helper
      - run: ./gradlew -p desktop :packaging:macos:macplatform:test --stacktrace
      - run: desktop/packaging/macos/scripts/demo-keychain-cli-weakness.sh
  package-and-smoke:                      # MC3-MC4, MC6 (unsigned)
    runs-on: macos-latest
    needs: macplatform
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: desktop/packaging/macos/native/build-llama-jni.sh
      - run: desktop/packaging/macos/scripts/build-runtime.sh && desktop/packaging/macos/scripts/build-app-image.sh
      - run: desktop/packaging/macos/scripts/smoke-app.sh
      - run: desktop/packaging/macos/scripts/smoke-env-injection.sh
      - run: desktop/packaging/macos/scripts/smoke-two-node.sh
      - uses: actions/upload-artifact@v4
        with: { name: asom-macos-arm64-UNSIGNED, path: desktop/packaging/macos/build/out/* }
  sign-notarize:                          # MC5 (owner secrets; never on forks or PRs)
    if: github.event_name == 'push' && (github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/desktop-v'))
    runs-on: macos-latest
    needs: package-and-smoke
    environment: apple-signing
    steps:
      - uses: actions/checkout@v4
      - run: desktop/packaging/macos/scripts/sign.sh && desktop/packaging/macos/scripts/verify-signing.sh
      - run: desktop/packaging/macos/scripts/build-pkg.sh && desktop/packaging/macos/scripts/build-dmg.sh
      - run: desktop/packaging/macos/scripts/notarize.sh
  macos27-probes:                         # S-M6 on macOS 27 (signed build from sign-notarize)
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: xcode-27
    needs: sign-notarize
    steps:
      - uses: actions/checkout@v4
      - run: desktop/packaging/macos/scripts/probe-container-denial.sh
```

Third-party actions are pinned by commit SHA in the real file; the Swift container image is pinned by digest. The `lines` mode of `lab/conformance-runner` (one `<id> <verdict> <code>` line per vector, sorted by id) is a small L0.1 addition that both lanes must print identically; the family list grows as the Swift lane implements more families. `sign-notarize` rebuilds from the same commit (artefact hand-off between jobs is acceptable instead, if the builder prefers).

### 10.3 Steps, gates and expected output

| Step | Deliverable | Gate (real command → expected output; pasted into `PROGRESS.md`) |
|---|---|---|
| **MC0** (lab, now under D1a + D24(b)) | `apple/` targets `AsomJSON`, `AsomDSSE`, `AsomManifest`, `AsomBenchCore`, `asom-conformance`; the three `apple-*` jobs | Linux container: `swift test --package-path apple` → `Executed N tests, with 0 failures`; `macos-latest`: same (CryptoKit path) and `xcodebuild … test` → `** TEST SUCCEEDED **`; `lane-diff` → no output, exit 0 for M01–M03, M05, M06. Vectors stay tagged **self-oracled** until an author with no access to the generator agrees (R2-OVERCLAIM-8). Already shown for the DSSE/ES256 layer: FM42 (36/37 identical to JCA; the 37th rejected at parse) |
| **MC1** | `macplatform` skeleton, `DesktopPlatform` impl, `FakeHelper`, laws tests | Linux container: `./gradlew -p desktop :packaging:macos:macplatform:test` → `BUILD SUCCESSFUL`, `mac/*IT` reported **skipped**; `macos-latest`: same command → ITs **run and pass** |
| **MC2** | helper + protocol v1 vectors + AM14 demo | `swift test --package-path desktop/packaging/macos/helper` → 0 failures; `printf '{"op":"hello","v":1}\n' \| asom-mac-helper serve` → one line with `"ok":true` and `"se":false` on the runner (AM02 recorded); `PowerAssertionIT`: `pmset -g assertions` contains `asom: lending compute to your paired devices` while held and **not** after `kill -9` of the parent (AM11); `demo-keychain-cli-weakness.sh` → `read without prompt: yes` (AM14) |
| **MC3** | native JNI build (Metal + CPU) | `build-llama-jni.sh` → `libasom-llama-jni.dylib` and ggml dylibs with sha256 lines; `otool -L` check prints `ok`; `TinyModelGenerationIT` (pinned tiny GGUF fetched by URL + sha256 at CI time, never committed; `n_gpu_layers=0`) → greedy output **equal to the Linux lane's vector**; Metal attempt logs device name and families, **informational** (AM17) |
| **MC4** | runtime, launcher, unsigned app image, node smoke, injection probe | `build-runtime.sh` → `forbidden modules: none`; `smoke-app.sh` → `asom status` prints `mode=dev state=OFF listener=none keyTier=file`; `lsof -nP -iTCP -sTCP:LISTEN -a -p <pid>` → **no rows**; after `asom mesh listen --interface en0 --confirm-lan --test-only` → exactly one row `<en0 IPv4>:11436`, never `*:11436` or `127.0.0.1`; `smoke-env-injection.sh` → `stock-launcher: PROBE-RAN` then `asom-node: PROBE-NOT-RUN` (AM05) |
| **MC5** | signing, pkg/dmg, notarisation (owner secrets) | `verify-signing.sh` → `0 findings`; `notarytool submit … --wait` → `status: Accepted`; `stapler validate` → `The validate action worked!`; `spctl -a -vvv -t install ASOM-<v>-arm64.pkg` → `accepted` with `source=Notarized Developer ID`; `asom-node --mode=selftest` on the signed build with library validation on and `allow-jit` only → `selftest ok` (S-M3/AM04), else the entitlement actually needed is recorded and M-D4 is re-put to the owner; `probe-container-denial.sh` on `xcode-27` → `Operation not permitted` for an unsigned reader and `ok` for the node (S-M6/AM08) |
| **MC6** | two-node smoke + W08 on the macOS JDK | `smoke-two-node.sh` → A→B and B→A borrows succeed; per-node ledger asserts: intent/outcome pairs, `DIAL` before SYN (L-L14), L-L15 byte sums equal to the record tap, one `CONTROL` row per control frame (L-L16); W08 suite `BUILD SUCCESSFUL`. Recorded as **LAB/CI evidence — not device evidence**, with whether Local Network privacy intervened |
| **MC7** | Homebrew tap template, docs, device checklist | `brew style --cask Casks/asom.rb` → `no offenses detected`; `brew audit --cask --strict asom` (tap trusted) → no problems; on `main` after MC5: `brew install --cask asom` → installs, `asom --version` prints `<v>`; `brew uninstall --cask asom` → `pkgutil --pkgs \| grep -c xyz.mdhv.asom` prints `0`; `DEVICE_CHECKLIST_MACOS.md` lists every NDV/NOV item of §9 with command and expected output |
| **MC8** (owner) | device validation | S-M1 (Enclave create/sign/self-test from the helper; `keyStorage=secure-enclave`; p50/p95 sign latency), S-M2, S-M4 (tok/s under `Standard` vs `Interactive`), S-M7 (Local Network prompt on first LAN dial; denial → `local-network-denied` row; `utun` bind with each Tailscale variant), S-M8 (presence and GPU counters track real use); sleep/lid/closed-display matrix (AM16); FileVault restart → node absent until login; `tailscaled --no-logs-no-support` + Headscale with a packet capture showing no `log.tailscale.com` connection; **8B decode/prefill on the owner's Mac replacing §7.1's A12d estimate**; llama-bench parity (B23); optional MLPerf Client cross-check. Each pasted as NDV/NOV evidence or left open |
| **MC9** (M2, optional) | mode B daemon | S-M5 (Metal and Enclave from a daemon, AM07); `sudo asom node daemon-install` view-first output; node serves after logout; node serves after a pre-boot SSH unlock [FM12]; no Local Network prompt in daemon mode (FM01) |

### 10.4 Effort (engineer-weeks; estimate, not measurement)

| Work | Weeks |
|---|---|
| `macplatform` (paths/container, helper client, NIK tiers, FSM inputs, control socket, interface selection, migration guard, doctor) | 2–3.5 |
| Swift helper + protocol v1 vectors | 1.5–2.5 |
| Hardened launcher + S-M3 entitlement minimisation | 1–2 |
| Native llama.cpp JNI Metal build + CI tiny-model test | 1–1.5 |
| Packaging: runtime, app image, inside-out signing, pkg/dmg, notarisation pipeline, verification | 2–3 |
| CI smokes (node, injection probe, two-node, W08 lane, container probe) | 1.5–2 |
| Tray companion placeholder | 0.5–1 |
| Homebrew tap, docs, device checklist, owner-validation support | 1–1.5 |
| **Total (macOS-specific, mode A, full recommendation)** | **≈ 11–17** |
| Minimal cut (T0 key only, stock launcher rated "same-user compromise = node compromise", helper limited to power/sleep/registration, no tray, no tap) | ≈ 7–10 |
| Mode B daemon (M2, optional) | +2–3 |
| `apple/` MC0 lane | counted in the design's L0.7 (3–5), shared with iOS |

**Assumptions behind the estimate:**
- `:node-desktop`, the JNI engine surface, the JSONL ledger, the CLI and the lab conformance suite already exist from the Linux D-v2 work (not counted);
- one engineer comfortable with Kotlin and basic Swift/C;
- the owner provides one Apple-silicon Mac and Developer Program membership; Apple's identity verification and certificate issuance are calendar time, not engineering time;
- no Mac App Store submission; no Intel artefact.

**Why this is more than the design's 4–6 weeks (§9.4, "D-v2 macOS variant").** That figure covered the Metal build, notarisation, `SMAppService` and S-A3. It did not include the helper process, the hardened launcher, the container-based key storage, the macOS CI and signing pipeline, or the tray; §2, §5 and §7.3 show why each is needed for the node to be honest about its own security. The reviser should replace 4–6 with this table (or with the minimal cut, if the owner chooses M-D3(b) and M-D4(b)).

---

## 11. Owner decisions and risks

### 11.1 Owner decisions specific to macOS

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **M-D1** | When does a Mac lend? | (a) mesh-1, as part of D-v2 under D24(b), with the macOS M1 gates of §9 (R2-DIRECTIVES-11). (b) M2. (c) requester only | **(a) if the owner has an Apple-silicon desktop (mini/Studio/iMac) with ≥ 16 GB unified memory (an 8B Q4 file is about 5 GB; 14B-class models want 24 GB or more); otherwise (b).** A MacBook alone is better treated as a requester that sometimes lends |
| **M-D2** | Hosting mode | (a) `SMAppService` LaunchAgent by default; LaunchDaemon (role user) opt-in at M2 after S-M5. (b) daemon by default. (c) agent only, ever | **(a).** Least privilege and user-visible by default; the daemon only for a headless always-on Mac, and never before S-M5 |
| **M-D3** | Node key tier | (a) Secure Enclave through the helper, blob in the Team-ID group container, T0 fallback. (b) T0 file in the container for mesh-1; Enclave at M2. (c) login keychain | **(a) if S-M1 passes inside the D-v2 window, else (b). Reject (c)** (§5) |
| **M-D4** | Launcher hardening | (a) own `asom-node` launcher (§7.3), library validation on, `allow-jit` only. (b) stock jpackage launcher; the node is rated "same-user compromise = node compromise" (C13) and the Peers tab says so | **(a) if S-M3 passes; otherwise (b), stated.** (a) costs about 1–2 weeks and is the only thing that makes the container protection mean something against a targeted same-user attacker |
| **M-D5** | Distribution | (a) notarised pkg + dmg on GitHub Releases, own Homebrew tap; official `homebrew/cask` later. (b) add the Mac App Store. (c) unsigned builds | **(a). Reject (b) and (c).** An unsigned node cannot own its container, gets unreliable Local Network attribution and cannot be opened by Control-click [FM01][FM17][FM25] |
| **M-D6** | Overlay client guidance on Macs (feeds D8) | (a) recommend open-source `tailscaled` with `--no-logs-no-support` and Headscale; state that the App Store and Standalone apps upload logs with no documented opt-out. (b) any variant, disclosed. (c) LAN-direct only on Macs | **(a)** [FM28][FM29]. It also runs before login, which mode B needs |
| **M-D7** | OS floor and Intel | (a) macOS 15+, arm64 only. (b) also an x86_64 CPU-only requester build on macOS 26, unsupported by September 2027. (c) macOS 13+ | **(a)**; (b) only if the owner names an Intel Mac that must join [FM19][FM20][FM31] |
| **M-D8** | `ProcessType` of the agent | (a) decide by S-M4: `Standard` unless it costs > 5 % decode, else `Interactive`. (b) `Interactive` always. (c) `Background` | **(a).** `Background` would push inference towards efficiency cores [FM05] |
| **M-D9** | macOS UI in mesh-1 | (a) CLI + AWT tray placeholder; SwiftUI menu bar only after D14 part A. (b) SwiftUI now (needs D14 part A early) | **(a)** |
| **M-D10** | Developer ID account type and Team ID | (a) organisation account for "asystemofcells" (a legal entity and D-U-N-S number are assumed to be required). (b) individual account in the owner's name | **Decide once, before the first signed release.** The Team ID names the group container and every signature; changing it later means a new container, a migration and re-pairing every peer. The publisher name shown by Gatekeeper is the account holder's |
| **M-D11** | Registry/contract rows this section adds (for D23) | (a) add: `macplatform` module (MOD-1 family), the helper and launcher as new artefacts, the helper protocol v1 as an internal (non-contract) interface, `keyStorage` value `secure-enclave` on desktops, the Homebrew tap as a new distribution identifier. (b) fold into existing rows | **(a)**: nothing ships on a sign-off nobody gave (D23). No app-facing contract changes |

### 11.2 Risks

| # | Risk | Severity | Mitigation | Residual (stated) |
|---|---|---|---|---|
| RM1 | A MacBook is presented as an always-on lender but sleeps on lid close, Apple-menu sleep or low battery | high | Role table says "conditional"; `os_sleep_imminent` drain with a 2 s ack; doctor shows AC/lid; Peers tab says "lends while awake" | Mid-stream sleep ends the stream (`MESH_STREAM_INTERRUPTED`) |
| RM2 | The Enclave blob is usable by any process that obtains it [FM15] | high | Blob only in the Team-ID container (SIP, macOS 27 denial) [FM17][FM19]; helper reads no files; hardened launcher (M-D4) | Root, Full-Disk-Access apps, user-granted access and exploits in the node still win |
| RM3 | Environment or attach injection into the entitled node | high | §7.3 launcher; attach disabled; instrument/attach modules removed; CI probe | Until S-M3 passes: "same-user compromise = node compromise" (C13) |
| RM4 | Notarisation or Gatekeeper rejects the bundle (nested unsigned code, entitlements, timestamp) | medium | No native code in jars; inside-out signing; `verify-signing.sh`; notary log warnings fail the job | Apple policy changes between releases |
| RM5 | Metal cannot be validated in CI (paravirtualised GPU, 3-vCPU VMs) | medium | CPU generation is the CI gate; Metal is informational; owner-device parity (B23) | A Metal-only regression reaches a release candidate; caught by the owner checklist |
| RM6 | The Local Network prompt is denied, never shown, or stuck (FB16131937, FB14944392) | medium | Long-lived agent; explanation before the first LAN dial; `local-network-denied` rows; overlay path needs no privilege | No API to reset the choice; the user must use System Settings |
| RM7 | Tailscale GUI variants upload client logs with no opt-out on macOS [FM29] | high (privacy) | M-D6: recommend `tailscaled` + no-logs + Headscale; doctor reports the variant and flag | Out of asom's control; disclosed |
| RM8 | Homebrew analytics and tap-trust friction [FM31][FM33] | low | Documented; GitHub Releases are the primary channel | Homebrew's behaviour, not asom's |
| RM9 | Apple policy churn (container rules in 27, background-item prompts in 26, keychain entropy files in 26.4) | medium | Re-verify FM01–FM25 at the start of each phase; `probe-container-denial.sh` on the newest image | A change can land mid-phase |
| RM10 | Team ID lock-in (M-D10) | medium | Decide before the first signed release | A later change forces re-pairing |
| RM11 | Intel Macs are left out | low | M-D7(b) exists as a stop-gap | Ends by September 2027 anyway |
| RM12 | After a restart with FileVault the node is down until someone logs in | medium | Disclosed; mode B + pre-boot SSH unlock for headless Macs [FM12] | Power cuts still need a human |
| RM13 | Migration Assistant or a restored backup clones a T0 identity | medium | Backup exclusion; `IOPlatformUUID` binding → `NIK_MIGRATED`; T2 blob does not work on another Mac [AM18][AM19][AM23] | A deliberate attacker can defeat the binding (they control the new Mac) |
| RM14 | Crash data carrying prompt text reaches disk or Apple | medium (Invariant 1) | `-XX:-CreateCoredumpOnCrash`; `ErrorFile` in the container; no core dumps; Apple crash sharing is the user's system setting | OS-level diagnostics are disclosed, not controlled |
| RM15 | No per-process GPU accounting, so GPU contention is estimated [AM12] | medium | Own in-flight fraction subtracted; rule disabled and disclosed when the counter is missing | Late yielding to a GPU-heavy app |
| RM16 | Throughput throttled by `ProcessType` or efficiency-core placement | medium | S-M4 measurement; M-D8 | — |
| RM17 | The Mac node and the Swift lane drift (the Mac never runs AsomKit in production, so a Swift bug is invisible on Macs) | low | Swift lane runs on macOS and Linux on every push; `lane-diff`; iOS section's own gates | Shared spec misreadings (K18) |

### 11.3 What this section does NOT guarantee

- That a Mac lends whenever it is powered: forced sleep (lid, Apple menu, low battery, thermal emergency) cannot be prevented by any app [FM07][FM08], Power Nap does not serve [FM10], and after a restart nothing runs until FileVault is unlocked [FM12].
- That the Secure Enclave key is usable only by asom: the blob is not bound to the app [FM15]; its protection is the container and the launcher, both OS policies that root, Full Disk Access, user approval or an exploit defeat.
- That the key tier, container or signature proves which software is running: peers see "self-reported"; there is no macOS attestation in this design (App Attest rejected).
- That traffic outside asom's process is ledgered: Tailscale's own logs and control traffic, Gatekeeper and notarisation lookups, XProtect, software updates, Homebrew analytics and Apple diagnostics belong to the OS or the vendor. They are disclosed, not controlled.
- Any speed: every Mac number in the design is an estimate [A12d] until the owner-device measurement at D-v2; CI runners are 3-vCPU VMs and are never quoted.
