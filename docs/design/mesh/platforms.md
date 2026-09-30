# Platform counterparts and code strategy

**Section of:** asom mesh design session (2026-09-29) · **Grade:** DIRECTION (input to the roadmap §7 v4 design session; nothing here authorises execution) · **Siblings:** `trust.md` (identity, pairing, transport), `manifest.md` (signed capability manifest), `benchmark.md` (benchmark core and shells), `router.md` (mesh router), `contract.md` (contract, invariants, phasing).

This section answers four questions:

1. Which mesh roles each platform can really play, and under what constraints (§2–§4).
2. Which platform constraints bind the other sections (§5).
3. How the code is shared across platforms without Kotlin Multiplatform (§6–§7).
4. What conformance suite stops independent implementations drifting apart, and in what order the platforms should arrive (§8–§9).

Owner decisions are collected in §10, invariant impacts in §11, risks in §12. §13 lists what this section does **not** guarantee.

---

## 0. The answer in brief

- **The direction of compute flow matters more than the platform list.** Phones are the weakest, hottest and most battery-limited nodes. Desktops are the strongest and are usually plugged in. The high-value flow is therefore **phone → desktop** (a requester on the phone, a provider on the desktop). The low-value flow is desktop → phone. The platform roles and the sequencing below follow from this.
- **Linux (and the Steam Deck) and macOS are real always-on providers.** They run the existing pure-JVM `:core:*` + `:server` code as *asom-desktop*, hosted by `systemd --user` and a launchd agent respectively. **Android** is a requester, and a provider only under conditions (foreground service + charging by default). **iOS/iPadOS cannot be an always-on provider at all.** Apple states that iOS suspends backgrounded apps and has no mechanism for running a network server in the background (§1, V01). iOS is at most a *foreground-only* provider, and there is no cross-app daemon on iOS. The iOS requester is therefore an **in-app Swift SDK** that delegates routing to a paired *home node*.
- **Code strategy: recommend Option 1.** Use the JVM wherever a JVM runs (Android, Linux, macOS, Deck). Write a native **Swift** implementation only for iOS, and hold the two together with a language-neutral **conformance-vector suite** in `conformance/`. The Swift surface is kept deliberately small: the manifest, benchmark statistics and rendering, the mesh client, and later a foreground listener. It **never includes the router**, because iOS delegates routing to a home node. That keeps the fast-moving, stateful part of the system single-sourced in Kotlin. The KMP ban is kept. Relaxing it is recorded as a fallback with explicit triggers (§6.5).
- **Integer-only numbers in every signed or cross-implementation-compared JSON.** This design rule removes the hardest cross-language canonicalisation hazard (RFC 8785 number serialisation) and most scoring drift. The seed vectors computed in this session (§8.9) show real traps that bite a naive Swift port.
- **Sequencing:** S0 conformance + manifest libraries (pure JVM; buildable now with owner OK) → S1 Linux/Deck node → S2 Android as *requester* → S3 macOS node (∥ S2, if the owner has a Mac) → S4 Android as provider → S5 iOS standalone benchmark app → S6 iOS client SDK → S7 iPad/iPhone foreground provider (owner-gated). Several steps pull roadmap v2 and v4 work forward. That re-sequencing is an owner decision (OD4), not an assumption.

---

## 1. Fact base

### 1.1 Verified in this session (with sources)

IDs are cited throughout as `[Vnn]`. Confidence reflects source quality, not how important the fact is.

| ID | Fact | Source | Conf. |
|---|---|---|---|
| V01 | iOS suspends an app shortly after it moves to the background. There is no general mechanism to run code continuously in the background, or to resume in response to network or IPC requests. Running a network server in the background is not supported. The general mechanisms are silent push, `BGAppRefreshTask`, `BGProcessingTask`, `BGContinuedProcessingTask`, `UIApplication` background tasks and background `URLSession`. A force-quit blocks background launches until the user reopens the app. Apple DTS advice is to close a listener when the app becomes eligible for suspension and reopen it afterwards. | Apple Developer Forums, "iOS Background Execution Limits" (thread 685525, updated 2026-01-09); forum threads 757385 and 772637 (NWListener in background) | high |
| V02 | Local Network privacy exists on iOS/iPadOS 14+, visionOS 1+ and **macOS 15+**. On iOS the following **require** local-network access: an outgoing TCP connection, UDP send, and Bonjour register, browse or resolve. **Accepting** an incoming TCP connection does **not**. A "local network" is a broadcast-capable interface (Wi-Fi, Ethernet), **not cellular and not VPN**. `NSLocalNetworkUsageDescription` explains the access. `NSBonjourServices` must list the service types used. The multicast entitlement is iOS-only. A background iOS app with an *undetermined* state is denied silently, with no alert. On macOS, launchd **daemons**, root processes and Terminal/SSH CLI tools are exempt; launchd **agents are not**, and must be installed via `SMAppService` or declare `AssociatedBundleIdentifiers`. An Apple-issued signing identity is needed for reliable tracking. There is no API to query the permission state. | Apple TN3179 "Understanding local network privacy" (updated 2026-02-17), fetched as JSON | high |
| V03 | ATS governs the **URL Loading System** (`URLSession`). From iOS 17 / macOS 14, ATS **no longer allows connections to IP addresses by default**. `NSAllowsLocalNetworking` re-enables unqualified names, `.local` names and IP addresses. | Apple docs: `NSAppTransportSecurity`, `NSAllowsLocalNetworking` | high |
| V04 | `BGContinuedProcessingTask` (iOS/iPadOS 26) continues *user-initiated* work after the user leaves the app. Background GPU access is available only on supported devices and is queried with `BGTaskScheduler.supportedResources`. Developer reports say it is available on iPad and not on the tested iPhones. | WWDC25 session 227; Apple forums 816774, 794072, 807957 | medium |
| V05 | App Review Guidelines: **2.4.2** (no rapid battery drain or excessive heat; no "unrelated background processes, such as cryptocurrency mining"); **2.5.2** (no downloading of code that changes functionality); **2.5.4** (background services only for their intended purposes); **3.1.5(ii)** (no on-device crypto mining); **4.7** (chatbots and plug-ins allowed as non-embedded software). The fetched text has no LLM-specific rule. **5.1.2(i)** (13 Nov 2025) requires in-app disclosure and explicit permission before personal data is sent to "third-party AI". | developer.apple.com/app-store/review/guidelines; Apple Developer News; TechCrunch 2025-11-13 | high |
| V06 | App Store precedents exist. **PocketPal AI** (llama.cpp; downloads GGUF from Hugging Face; Metal on iOS) and **"Local LLM Server"** (id6757007308; iOS 26+) are both listed. The latter serves an OpenAI- and Ollama-compatible API to other devices over the LAN from iPhone, iPad or Mac, offers a "Keep Screen Awake" option and an alert "if the server gets suspended". | apps.apple.com listings; PocketPal GitHub | high (existence only) |
| V07 | The entitlement `com.apple.developer.kernel.increased-memory-limit` exists. Its practical effect under App Store distribution is reported inconsistently. | Apple entitlement doc; forum threads 770868, 704945 | medium (existence) / low (effect) |
| V08 | The Foundation Models framework (iOS/iPadOS/macOS 26) exposes the ~3B on-device Apple Intelligence model on Apple-Intelligence-capable devices only. AFM 3 (June 2026) adds a 20B sparse "Core Advanced" model. The on-device context budget is reported as 4K. | Apple newsroom 2025-09; machinelearning.apple.com (2026-06-08); createwithswift | medium |
| V09 | MLX Swift runs on iOS and macOS, and LLM support lives in `mlx-swift-lm`. MLX distributed backends: **ring** (TCP; "main purpose is Thunderbolt rings"), **JACCL** (RDMA over Thunderbolt 5, macOS 26.2+), MPI and NCCL. | github.com/ml-explore/mlx-swift; MLX distributed docs | high |
| V10 | llama.cpp backends: Metal, CUDA, HIP, Vulkan, SYCL, **OpenCL (Adreno)**, **Hexagon (Snapdragon)**, OpenVINO, BLAS, **RPC** and WebGPU, among others. llama.cpp builds as an XCFramework for Apple platforms and has an Android build guide. | github.com/ggml-org/llama.cpp README | high |
| V11 | ExecuTorch supports iOS and macOS through Core ML, MPS and XNNPACK backends, and ships as xcframeworks. | docs.pytorch.org/executorch (1.3) | high |
| V12 | Android 17: `ACCESS_LOCAL_NETWORK` (in the `NEARBY_DEVICES` group) is **mandatory for apps targeting SDK 37**. **Accepting incoming TCP requires it.** On Android 16 it is opt-in via a compat flag. The documentation does **not** say how loopback, CGNAT (100.64/10) or VPN interfaces are treated. | developer.android.com/privacy-and-security/local-network-permission; Android 17 behavior changes | high (stated parts) |
| V13 | FGS `specialUse` needs `FOREGROUND_SERVICE_SPECIAL_USE` and a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property. Its runtime prerequisites are "None", and the declared use case is reviewed only in the Play Console. `mediaProcessing` is capped at 6 h per 24 h. (A secondary source claiming `specialUse` is "reserved for system apps" is **contradicted** by the official page.) | developer.android.com FGS types | high |
| V14 | Doze begins when the device is unplugged, stationary and screen-off, and suspends network access. The documentation exempts apps with a foreground service from **App Standby**. Whether an FGS keeps network access **during Doze** is stated inconsistently across sources. | developer.android.com doze-standby | high (trigger) / low (FGS-in-Doze) |
| V15 | Android developer verification: the developer console, limited-distribution accounts (≤20 devices, no ID or fee) and a power-user "advanced flow" launched in **August 2026**. Enforcement starts **30 Sept 2026** in Brazil, Indonesia, Singapore and Thailand on certified devices, with global rollout in 2027 and later. It covers apps distributed outside Play. F-Droid publicly opposes it. | developer.android.com/developer-verification; f-droid.org open letter (2026-02-24) | high |
| V16 | **StrongBox** supports RSA-2048, AES-128/256, **ECDSA/ECDH P-256**, HMAC-SHA256 and 3DES; the page does not list Ed25519. The **Secure Enclave** supports **P-256 only** (CryptoKit `SecureEnclave.P256`). | developer.android.com keystore; Apple CryptoKit docs | high |
| V17 | `SMAppService` (macOS 13+) registers login items, agents and daemons. Each is gated by a user-approval notification and appears under System Settings → General → Login Items, where the user can revoke it. | Apple Support (deployment guide); theevilbit | high |
| V18 | A bundled JVM under the macOS hardened runtime needs `allow-jit`, `allow-unsigned-executable-memory` and `disable-library-validation`. `jpackage --mac-sign` signs the bundle with JVM-suitable defaults. | AdoptOpenJDK notarisation guide; camunda issue 54877 | medium |
| V19 | Homebrew deprecated casks that fail Gatekeeper (unsigned or un-notarised). They were disabled in the official tap on **2026-09-01**. A self-hosted tap remains possible. | Homebrew discussions 6482/7050; Workbrew "Homebrew 5.0.0" | medium-high |
| V20 | TestFlight allows up to 10,000 external testers, and builds expire after 90 days. | App Store Connect Help | high |
| V21 | Steam Deck (LCD) specs: Zen 2 4c/8t, 8 RDNA2 CUs, 4–15 W APU, 16 GB LPDDR5 at 5500 MT/s. SteamOS has a read-only root. User services under `~/.config/systemd/user` survive SteamOS updates. Tailscale's official Deck script installs a system unit that starts on boot "without needing to enter desktop mode". | steamdeck.com tech specs; theinternetvagabond.com (Syncthing on Deck); github.com/tailscale-dev/deck-tailscale | high |
| V22 | Kotlin **Swift export is Alpha** ("breaking changes are expected"). | kotlinlang.org/docs/native-swift-export.html | high |
| V23 | UniFFI (Rust → Kotlin/Swift) is used in Firefox mobile and desktop, and is pre-1.0. | github.com/mozilla/uniffi-rs; UniFFI guide | high |
| V24 | RFC 8785 serialises numbers per ECMA-262 §7.1.12.1 (the algorithm is omitted from the RFC "due to the relative complexity"), sorts properties by **UTF-16 code units**, and forbids duplicate names. RFC 7518 §3.4 encodes ES256 as a **64-octet R‖S, not DER**, and leading zero octets must not be omitted. | rfc-editor.org RFC 8785, RFC 7518 | high |
| V25 | Standard GitHub-hosted runners, **including macOS**, are free for public repositories. Larger runners are paid. | docs.github.com Actions billing; GitHub community discussion 70492 | high |
| V26 | Flatpak apps request background running and autostart through the XDG Background portal. | flatpak.github.io xdg-desktop-portal docs | high |
| V27 | JDK-4511638 (`Double.toString` shortest-decimal correctness) was fixed in **JDK 19**. The same double can therefore print differently on JDK 17 and JDK 19+ in rare cases. | bugs.openjdk.org JDK-4511638, JDK-8291475 | high |
| V28 | **Computed in this session:** the seed vectors in `mesh/conformance-examples/seed-vectors.json`. The ES256 DER form verifies under **OpenSSL 3.0.13** ("Verified OK"). The USD-rendering expectations were confirmed on **OpenJDK 21.0.10** by executing the exact `RouteRecord.formatUsd` expression. They are **not** yet confirmed on JDK 17, the CI target. | `conformance-examples/gen.py`, `UsdCheck.java`, `usdcheck.out` | high (for what was run) |

### 1.2 Assumptions (not verified; each carries a validation note)

| ID | Assumption | Why it matters | How to settle |
|---|---|---|---|
| A01 | HotSpot cannot run on iOS, because third-party apps cannot JIT. The only "Java on iOS" routes are ahead-of-time (GraalVM / Gluon Substrate), and their compatibility with Kotlin coroutines, Ktor and OkHttp on iOS is unverified. | Rules out "just run the JVM" on iOS. | Spike only if OD1 is reopened. |
| A02 | iOS apps must be built and signed with Xcode on macOS. CI macOS runners can compile and run simulator tests, but device tests need owner hardware. | Apple platforms need a Mac. | Owner inventory (OD2). |
| A03 | The Apple Developer Program costs about US$99 per year and is required for TestFlight, the App Store, Developer ID and notarisation. | Cost and identity decision. | Owner. |
| A04 | Processes with an FGS proc-state keep network access during Doze (AOSP allowlists them). | Decides whether Android can be an unplugged provider. | NEEDS-DEVICE-VALIDATION (RedMagic). |
| A05 | Android 17's local-network permission does **not** cover loopback `127.0.0.1`. | **If false, v1 itself breaks at targetSdk 37.** | Emulator/device test **before** any targetSdk bump. |
| A06 | Whether Android 17 treats tailnet addresses (a VPN tun on 100.64/10) as "local network". | Decides whether a peer listener on Android needs the runtime prompt. | Device test. |
| A07 | Android allows only one active VPN, so Tailscale conflicts with other VPNs. | Availability of the overlay on phones. | Known behaviour; confirm. |
| A08 | In iPad Split View or Stage Manager, both visible apps stay unsuspended. | Could let an iPad serve while the user works beside it. | Device test. |
| A09 | iPad apps run on Apple-silicon Macs by default unless the developer opts out. | The "one harness per OS" rule (§5 C10). | Xcode setting. |
| A10 | MacBooks sleep on lid close unless clamshell conditions hold. Launch agents run only while their user is logged in. | macOS provider availability. | Device test. |
| A11 | `systemd --user` services keep running in the Deck's Game Mode. V21 proves this only for *system* units. | Deck provider availability. | Owner's Deck (trivial test). |
| A12 | Linux `SO_PEERCRED` on Unix-domain sockets yields the peer uid/pid (the constant exists in kernel headers — checked), and the JDK exposes it via `jdk.net.ExtendedSocketOptions`. The macOS analogue of `getCallingUid` is an XPC audit token plus a `SecCode` requirement check. | Local-app identity on desktop (Invariant 5 analogue). | Spike when local-app pairing on desktop is needed (not in S1–S3). |
| A13 | Swift defaults: `NumberFormatter` rounds half-even; `JSONEncoder` escapes `/` unless `.withoutEscapingSlashes` is set; `String` `<` compares by canonical equivalence rather than UTF-16 code units. | Concrete drift traps for a Swift port (§8.9). | The M01 and W01 vectors catch them either way. |
| A14 | The redistribution terms for CUDA runtime libraries. | Whether CUDA builds can ship. | Legal check (OD8). |
| A15 | TLS client authentication with a Secure Enclave key works through Network.framework (`sec_identity`/`sec_protocol_options`). | iOS mTLS with a hardware key. | Spike at S6. |
| A16 | `beginBackgroundTask` grants on the order of ~30 s, with no guarantee. | iOS drain grace. | Measure. |
| A17 | An iOS app can accept inbound connections on the tailnet (utun) address while Tailscale's packet tunnel is active and the app is in the foreground. | iOS foreground provider over the overlay. | Device test at S7. |
| A18 | No performance figures are asserted anywhere in this section. Any tok/s expectation belongs to `benchmark.md` and the roadmap §1 planning assumptions. | Anti-overclaim. | — |
| A19 | A JVM process (jpackage launcher signed with keychain entitlements) can use Secure Enclave P-256 signing through a JNI-loaded Swift/ObjC shim. `trust.md` independently rates this unverified. | The macOS node key tier. | Spike at S3; until then use a login-keychain or file key. |

---

## 2. Role taxonomy (normative definitions used below)

| Role | Definition | Hard requirements |
|---|---|---|
| **R — Requester** | Originates inference on behalf of local callers and decides placement, either itself or through its home node (H). | Outbound connections to paired peers. Ledger rows for every attempt (audit lesson). A router, or delegation to H. |
| **PA — Always-on provider** | Accepts requests from paired peers with no human at the device, subject to its own governors. | Survives screen-off, idle and user absence. Holds a listener on the overlay/LAN address (Invariant 2 amendment, roadmap §7). Off by default. |
| **PF — Foreground-only provider** | Accepts peer requests only while its UI is frontmost **and** the user has switched on "lend compute". | The listener lifecycle is bound to the UI lifecycle. The screen-idle timer is disabled while serving. |
| **H — Home node** | A PA node to which a thin requester delegates routing. | Runs the full mesh router. Is paired with the thin requester. |
| **B — Manifest producer** | Can run the benchmark and emit a signed manifest. | A local engine, the benchmark core and a signing key. |
| **S — Subscriber** | Can verify and consume manifests. | The manifest verifier (M-vectors). |

### 2.1 Provider availability state machine (identical on every platform; only the conditions differ)

```
            user_enable                    conditions_met
   OFF ───────────────────► ARMED ─────────────────────────► SERVING
    ▲                        ▲  ▲                               │
    │ user_disable           │  │ inflight==0 or grace expired  │ condition_lost(reason)
    │ (from any state; runs  │  └──────────── DRAINING ◄────────┘  or os_background (iOS)
    │  DRAINING with grace   │
    │  = 0 then → OFF)       │ os_foreground (iOS) ∧ conditions_met → SERVING
```

- **OFF:** no listener, and nothing advertised.
- **ARMED:** the user has enabled lending but a condition fails. There is no listener. The node advertises `availability=armed(reason)` to peers that ask over an outbound session it already has open.
- **SERVING:** the listener is bound to the configured overlay or LAN address, **never** `0.0.0.0` or `::`.
- **DRAINING:** the listener is **closed at once**, so new connections are refused by the kernel (per [V01] advice). In-flight requests may finish within `graceMs`. When grace expires, remaining streams are aborted with the router's typed error (defined in `router.md`/`contract.md`, not here).
- **`graceMs` per platform:**
  - Linux/Deck/macOS: 30,000 (configurable).
  - Android: 10,000.
  - iOS: `min(5,000, remaining background task time)`.
- **Transitions go to local diagnostics, not to the ledger.** The ledger records network events only, as in v1 §9 and the v2 P4 precedent for governor transitions.
- **Proposed live-state field** (owned by `router.md`): `availability ∈ {always, when-charging, foreground-only, requester-only}` plus the current FSM state and the reason code for the last `condition_lost`.

**Default `conditions_met` per platform** (each clause user-overridable except where marked hard):

| Platform | Default conditions for SERVING |
|---|---|
| Linux server/desktop | Daemon running ∧ not in a system sleep transition. On laptops: on AC. |
| Steam Deck | On AC ∧ GPU busy% attributable to other processes < 20% for 60 s (from amdgpu sysfs `gpu_busy_percent`) ∧ not suspending. An idle-sleep inhibitor is held while SERVING. |
| macOS | User session active (agent) ∧ on AC ∧ `thermalState ≤ fair` ∧ not in Low Power Mode. `PreventUserIdleSystemSleep` is asserted while SERVING. |
| Android | FGS running ∧ charging ∧ thermal headroom forecast < 0.75 ∧ battery ≥ floor (default 20%, the v2 P4 value) ∧ not in battery saver. |
| iOS / iPadOS | App frontmost ∧ lend toggle on ∧ `isIdleTimerDisabled` ∧ `thermalState ∈ {nominal, fair}` (**hard**) ∧ (charging ∨ battery ≥ 50%) ∧ not in Low Power Mode (**hard**). |

The 0.75, 20% and 50% thresholds are starting values, not measured optima. `benchmark.md` and `router.md` tune them per device from observed throttle curves.

---

## 3. Per-platform analysis

### 3.1 Linux (x86_64 and arm64; desktop, laptop, headless server — "the Dell" if it runs Linux)

- **Real roles:** R, **PA (best-suited platform)**, H, B, S.
- **Hosting:** a `systemd --user` unit, `~/.config/systemd/user/asom.service`.
  - For a headless box, the owner opts in to running without an interactive session with `loginctl enable-linger <user>`. The service is never enabled by default. Boot-start is OFF by default, mirroring v1 P8.
  - A system-wide unit is **rejected**: it needs root, conflicts with per-user keys, and gains nothing.
- **Background and power:**
  - No OS-imposed limit.
  - The daemon holds an idle-sleep inhibitor only while SERVING with work in flight. It does this by spawning `systemd-inhibit --what=idle:sleep --why="asom serving" sleep infinity` and killing it when idle; a D-Bus `Inhibit` call on `org.freedesktop.login1` is an alternative.
- **Networking and permissions:**
  - No OS-level local-network permission exists.
  - Host firewalls (ufw, firewalld) may need a rule for the overlay listener. The CLI prints the exact rule and never applies it.
  - The listener binds to the tailnet address (for example the `tailscale0` interface address) or an explicit LAN address.
- **Inference backends:**
  - Primary: llama.cpp via the **same JNI surface as Android** (roadmap v2 P1 already requires a linux-x86_64 CPU build for CI), in-process, built CPU + **Vulkan** (covers AMD, Intel and NVIDIA GPUs) [V10].
  - **CUDA** and **HIP** builds are optional artifacts, pending OD8 (redistribution, A14).
  - Rejected: an upstream `llama-server` subprocess. Reasons: (a) it adds an unledgered localhost side door that same-user processes can call around asom; (b) its llama.cpp commit would skew from Android's, making manifests from different nodes less comparable; (c) it is a second engine integration for the v2 P0 engine-conformance suite to cover.
  - What would change my mind: repeated native crashes that take the daemon down, or CUDA packaging proving impossible through JNI.
- **Keys and local identity:**
  - Node key: tier-0 file key (`0600`, optionally passphrase-wrapped). An optional TPM 2.0 P-256 key via PKCS#11 is a later tier; `trust.md` owns the tier semantics.
  - **No BYOK provider keys on non-Android nodes in S1–S3** (OD3).
  - Local-app pairing to the desktop localhost API is **not offered in S1–S3**. Only the CLI talks to the daemon, via a token file readable by the owning user. Honest limit: any process running as that user can read it (A12).
- **Situation probes** (pure Kotlin file reads; no native code):
  - Thermal: `/sys/class/thermal/thermal_zone*/temp` and `/sys/class/hwmon/*/temp*_input`.
  - Power: `/sys/class/power_supply/*/{online,capacity,status}`.
  - Memory: `/proc/meminfo` (MemAvailable).
  - GPU busy: `/sys/class/drm/card*/device/gpu_busy_percent` (amdgpu).
  - Metered network: NetworkManager's D-Bus `Metered` property (assumption; optional).
- **Packaging and distribution:**
  - `jpackage` `.deb` and `.rpm` with a jlink'd runtime, no system JDK required.
  - A `tar.gz` with a user-local install script, the **only** option on immutable distros and the Deck.
  - Optionally a self-hosted APT/RPM repo on GitHub Releases (static; no backend).
  - **Flatpak rejected for the daemon:** background needs the portal [V26], GPU compute (especially CUDA) inside the sandbox is awkward, and systemd integration is lost. Flatpak remains acceptable for the *standalone GUI benchmark shell* if `benchmark.md` wants one.
- **Invariant notes:**
  - Inv 1: no telemetry. OpenJDK/Temurin do not phone home (assumption, standard).
  - Inv 2: v1's `127.0.0.1` listener is untouched. The peer listener is the v4-amended second listener.
  - Inv 7: the roadmap already says "CLI shell first" for asom-desktop.
- **Open validation:** firewall interaction; Vulkan driver variance (RADV vs AMDVLK vs NVIDIA). NEEDS-OWNER-VALIDATION on the Dell once its OS is known (OD8).

### 3.2 Steam Deck (SteamOS = Arch-based Linux; the project's dev host)

- **Real roles:** R, **PA when docked** (on AC, no game running), PF in practice in Desktop Mode, B, S. The Deck is a gaming device first: **a game always wins the GPU.**
- **Hosting:** the Linux artifact, installed **only** to `$HOME` (tarball + `~/.config/systemd/user` unit). The read-only root means no deb/rpm, and user units survive SteamOS updates [V21]. Running in Game Mode is assumed but unverified (A11).
- **Background and power:**
  - The Deck suspends on the power button and on idle.
  - While SERVING and on AC, the idle inhibitor (§3.1) keeps it awake. It is never held on battery by default.
  - Game detection by process name is rejected (brittle). Game contention is detected by other processes' GPU busy% (§2.1 conditions); a sustained game load drives the node to DRAINING.
- **Networking:** Tailscale's official Deck installer yields a boot-started system unit [V21], so the tailnet is available without Desktop Mode.
- **Inference backend:** llama.cpp **Vulkan** (RADV) or CPU via JNI. The 16 GB is **shared** between CPU and GPU [V21], so `benchmark.md`'s memory-headroom probe must measure it, not assume it.
- **Situation probes:** as Linux, with battery at `/sys/class/power_supply/BAT1` and APU temperature via amdgpu hwmon.
- **Why it matters beyond compute:** the Deck is where the owner develops (brief §3). A Deck node plus a second JVM node (the Dell, or a second process on the Deck) can test **every mesh protocol end-to-end with no phone and no Apple hardware**. This is the pure-JVM-first law applied to the mesh.

### 3.3 macOS (Apple silicon primary; Intel best-effort CPU-only)

- **Real roles:** R, **PA while a user session is active and the Mac is awake**, H, B, S. Apple silicon's unified memory makes it the strongest consumer provider class. Mac mini and Studio are the ideal always-on hosts; laptops are available on AC and awake only (A10).
- **Hosting:** a **launchd agent registered through `SMAppService.agent`** [V17]. This means a user approval notification, appearance in Login Items, and user-revocable at any time.
  - Chosen over a launchd **daemon** even though daemons are exempt from Local Network privacy [V02]. A daemon runs as root outside the user session, needs admin authentication, and would hold the user's node key outside the user's keychain. That privilege is not needed for a user-owned compute node.
  - Consequence: the agent **is subject to Local Network privacy on macOS 15+** [V02] for LAN-direct outbound connections. It is registered via `SMAppService` so macOS attributes it to the app bundle and shows the prompt. Traffic over the tailnet is VPN traffic, which is not "local network" [V02], so overlay-only operation needs no prompt.
- **Background and power:**
  - While SERVING and on AC, the node takes `kIOPMAssertionTypePreventUserIdleSystemSleep`, via the native shim or by spawning `caffeinate -i -w <pid>` (assumption: the stock tool).
  - App Nap does not apply to a windowless agent (assumption; verify at S3).
- **Networking:** no ATS concern, because the JVM uses its own TLS stack (OkHttp/JSSE), not the URL Loading System [V03].
- **Inference backends:**
  - Primary: **llama.cpp Metal** via the same JNI surface (a macOS-arm64 dylib built on a CI macOS runner [V25]). GGUF parity with Android and Linux keeps manifests comparable.
  - Secondary (later, optional): **MLX**. It is likely faster on some Apple-silicon workloads (assumption), but it uses different weight artefacts (MLX safetensors quantisations), so its results are **separate manifest rows, never merged with GGUF rows**.
  - Foundation Models [V08]: *not* a mesh backend. The model is OS-versioned, not content-addressable, not in the catalogue, and not servable byte-identically on other nodes. Noted, and parked.
  - MLX distributed (JACCL/ring) [V09] is sharding, which is parked per roadmap §1/§7. `router.md` owns that question.
- **Keys and identity:**
  - Target: a node key in the **Secure Enclave (P-256 only** [V16]) via a small Swift/ObjC shim in `libasom-platform.dylib` called over JNI.
  - **Unverified (A19):** Secure Enclave keys need keychain entitlements on the signed main executable (here, the jpackage launcher). `trust.md` independently flags JVM → Secure Enclave as unproven. Until an S3 spike proves it, the macOS node starts at a non-synchronisable login-keychain key or a file key (never iCloud Keychain). `trust.md` owns the tier names.
  - No BYOK in S1–S3 (OD3).
- **Situation probes** (native shim): `ProcessInfo.thermalState`, IOKit power sources (AC, battery), `host_statistics64` memory, `ProcessInfo.isLowPowerModeEnabled`, `NWPathMonitor` (`isExpensive`, `isConstrained`), and HID idle time for user-activity state.
- **Packaging and distribution:**
  - A **Developer ID-signed, notarised** `.app` (jpackage `--mac-sign`, hardened runtime plus the JVM entitlements [V18]) in a DMG.
  - A **Homebrew cask in a self-hosted tap**. The official tap now disables un-notarised casks [V19]; a notarised build could also go to the official tap.
  - **Mac App Store rejected:** its sandbox, the agent registration path and the bundled JVM add review and entitlement friction for no user benefit.
  - Needs the Apple Developer Program (OD2, A03).
- **Invariant notes:**
  - Inv 1: macOS crash reports go to Apple only if the user opts in system-wide. That is outside app control and must be disclosed; the app ships no reporting SDK.
  - Inv 6: the menu-bar or status UI (later) follows violet/cyan with shape and label redundancy.

### 3.4 Android (the existing app, `xyz.mdhv.asom`)

- **Real roles:** R (today's daemon serving local AIDL-paired apps, extended with peer placement), **PA conditional** (FGS + charging by default), PF (FGS + screen on), H (possible, but a phone is a poor home node), B, S.
- **Hosting:** the existing `AsomService` FGS (`specialUse`, [V13]). The `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text is extended to mention serving paired devices. Play review of that text does not apply to sideload or F-Droid.
- **Background and power:**
  - **Doze** starts only when unplugged, stationary and screen-off [V14]. "Serve while charging" is therefore Doze-free by construction, which is why it is the default.
  - Whether an FGS keeps network access **during** Doze is unresolved (A04), so "serve unplugged" stays opt-in and NEEDS-DEVICE-VALIDATION.
  - Thermal: `PowerManager.getThermalHeadroom()` and `OnThermalStatusChangedListener`, per roadmap v2 P4.
- **Networking and permissions:**
  - **Requester role needs no listener**, only outbound TLS to paired peers. This is why Android-as-requester (S2) comes before Android-as-provider (S4).
  - The provider listener binds to the tailnet tun address. Tailscale on Android is a VPN, so it conflicts with other VPNs (A07).
  - **Android 17 / targetSdk 37:** accepting incoming TCP needs `ACCESS_LOCAL_NETWORK` [V12]. Treatment of tailnet addresses (A06) and **of loopback (A05)** is undocumented. A05 is a **v1-wide risk**: do not raise `targetSdk` to 37 until an emulator or device test shows `127.0.0.1:11435` still works for paired apps.
  - **Network Security Config:** the base config forbids cleartext except `127.0.0.1`/`localhost` (checked in `app/src/main/res/xml/network_security_config.xml`), and Invariant 2 forbids widening it. **Peer traffic therefore must be TLS at the application layer even over the tailnet** (constraint C3).
- **Inference backends** (the backend bakeoff is roadmap v2 P6 and not re-decided here):
  - llama.cpp CPU, **OpenCL (Adreno)**, **Hexagon (Snapdragon NPU)** or Vulkan [V10].
  - Alternates in the bakeoff: MLC, LiteRT-LM and ExecuTorch (+QNN) [V11].
- **Keys and identity:**
  - Node key in Android Keystore, **StrongBox P-256** when available, TEE otherwise [V16].
  - Key attestation is available (`manifest.md` owns attestation tiers, including offline chain verification without GMS, Invariant 8).
  - BYOK stays exactly as v1 §8.
- **Situation probes:** thermal headroom, `BatteryManager` (level, plugged), `ActivityManager.MemoryInfo.availMem`, `PowerManager.isInteractive` (user activity), and `ConnectivityManager` `NET_CAPABILITY_NOT_METERED`.
- **Packaging and distribution:** sideload and F-Droid (brief §14.5) are now shaped by **developer verification** [V15]. Enforcement is regional from 30 Sept 2026 and global in 2027, and it covers apps outside Play; a limited-distribution account covers ≤20 devices, and an "advanced flow" exists for power users. See OD6.

### 3.5 iOS and iPadOS

- **Real roles:**
  - **R only as an in-app SDK, foreground only.** Apps are suspended when backgrounded, and there is no background server or IPC resume [V01], so an asom *daemon* serving other iOS apps is impossible. The §10A `RemoteAsom` tier (localhost daemon) **does not exist on iOS**. The iOS tiers become `RemoteMesh` (paired home node) → `Embedded` → `CloudOnly`.
  - **B and S:** the standalone benchmark app.
  - **PF:** technically possible; owner-gated (S7).
  - **Never PA.**
- **Why delegated routing (H) instead of on-device routing:**
  - Routing locally would require pairing each iOS app with every node, polling every peer's live state from a phone (battery cost), and a Swift port of the router.
  - Delegating to one H node over the tailnet works from anywhere (Wi-Fi or cellular), keeps the router single-sourced in Kotlin, and still meets requirement 1's intent. The *requester's* router (on H) understands all devices, and the iOS app sees every decision through the echo headers and the route reason (`router.md`/`contract.md` define `X-Asom-Node` and `Route-Reason`).
  - If H is unreachable, the SDK falls back to `Embedded`, then `CloudOnly`, exactly as §10A.2 degrades.
- **Hosting:** none (in-app only). The foreground provider (S7) runs an `NWListener` only in SERVING. It is closed on `sceneDidEnterBackground` [V01], and in-flight work gets a `beginBackgroundTask` grace (A16).
  - iPadOS: Split View or Stage Manager may keep the provider unsuspended beside another app (A08).
  - `BGContinuedProcessingTask` with background GPU (iPad) [V04] suits a **user-started benchmark run** that continues after the user switches apps. It is **not** for serving, because it covers finite, user-initiated tasks only.
- **Networking and permissions:**
  - Tailnet paths are VPN traffic, not "local network" [V02], so **no Local Network prompt is needed for overlay-only operation**.
  - LAN-direct paths need `NSLocalNetworkUsageDescription`. They also need `NSBonjourServices` **only if** `trust.md` adopts Bonjour for locating paired peers (the roadmap currently says no mDNS).
  - Accepting inbound TCP does not trigger the prompt; outbound TCP to a LAN address does [V02].
  - **Transport over Network.framework, not `URLSession`** (constraint C4). ATS governs the URL Loading System and, from iOS 17, blocks IP-literal connections by default [V03]. Peers are addressed by IP with pinned self-signed certificates, which suits an `NWConnection` with a custom `sec_protocol_options` verify block.
  - Tailnet inbound for S7 is A17.
- **Thermal, memory and battery:**
  - `ProcessInfo.thermalState` gates SERVING (hard stop at `serious`).
  - Memory: `os_proc_available_memory()`; the increased-memory-limit entitlement exists [V07], but its effect is unreliable.
  - Battery and Low Power Mode are gated as in §2.1.
  - App Review **2.4.2** (heat, battery) makes these gates a review requirement as well as a safety one [V05].
- **Inference backends:**
  - Primary: **llama.cpp XCFramework (Metal)** [V10]. Same GGUF files, same catalogue sha256, same pinned llama.cpp commit, so manifests are comparable with the other nodes.
  - Optional later: MLX Swift [V09] (separate manifest rows). Core ML and ExecuTorch [V11] are available but not recommended first: they need model conversion and are not GGUF-comparable.
  - Foundation Models [V08]: available as a local-only "Apple on-device" backend for the app's own use, never advertised to peers (same reasons as §3.3).
- **App Review exposure** [V05, V06]:
  - Downloading model weights is precedented (PocketPal).
  - Foreground serving of an OpenAI-compatible API over the LAN is precedented ("Local LLM Server").
  - **Risk remains for S7.** 2.4.2 (heat) and 2.5.4 (background services only for intended purposes) are judgement calls, and one approved app is not a guarantee.
  - **5.1.2(i)** requires explicit in-app consent before personal data goes to third-party AI. This applies to the `CloudOnly` tier and aligns with the watched-object ethos.
  - Sending prompts to the *user's own* paired devices is arguably not "third-party", but the consent copy should say so anyway.
- **Keys and identity:**
  - Node key: **Secure Enclave P-256** [V16].
  - Keychain items for `CloudOnly` BYOK are `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` and **non-synchronisable** (never iCloud Keychain).
  - **Suite apps must not share keys through Keychain access groups.** That would be the programmatic key transfer that §10A.3 forbids.
- **Backup:**
  - Ledger, manifests and pairing state are marked `isExcludedFromBackup`. Otherwise iCloud Backup silently uploads them, an automatic egress to Apple that would violate Inv 1 in spirit (constraint C7).
  - Model files are excluded too (size, and they are re-downloadable).
- **Packaging and distribution:** TestFlight (10k external testers, 90-day builds [V20]), then the App Store. Both need the Apple Developer Program and a Mac (A02, A03, OD2). The iOS app **opts out of Apple-silicon Mac availability** (A09), keeping one benchmark harness per OS (C10).
- **Invariant notes:**
  - Inv 1: TestFlight and App Store crash and analytics sharing is an OS-level opt-in outside app control. Disclose it; ship no SDK.
  - Inv 7: SwiftUI is not Compose; the platform-equivalence question is OD3.
  - Inv 8 analogue: no third-party SDKs, no CloudKit, no iCloud Keychain.

---

## 4. Capability matrix

Roles: **R** requester · **PA** always-on provider · **PF** foreground-only provider · **H** home node · **B** manifest producer · **S** subscriber. "cond." means conditional (conditions in §2.1).

| | **Linux** (server/desktop) | **Steam Deck** (SteamOS) | **macOS** (Apple silicon) | **Android** (existing app) | **iPadOS** | **iOS** (iPhone) |
|---|---|---|---|---|---|---|
| **R** | yes (daemon) | yes (daemon) | yes (daemon) | yes (daemon for AIDL-paired apps) | in-app SDK, foreground, delegates routing to H | same as iPadOS |
| **PA** | **yes, best** | cond.: docked, on AC, no game | yes while a user session is active and awake | cond.: FGS + charging (unplugged = A04) | **no** [V01] | **no** [V01] |
| **PF** | n/a | yes (Desktop Mode) | n/a | yes (FGS + screen on) | yes, owner-gated S7; Split View may help (A08) | yes, owner-gated S7; low value |
| **H** | **yes** | yes when docked | yes | possible, not recommended | no | no |
| **B / S** | yes / yes | yes / yes | yes / yes | yes (v2 engine) / yes | yes (standalone app; background-GPU bench continuation [V04]) / yes | yes / yes |
| **Service host** | `systemd --user` (+ linger opt-in) | `systemd --user` in `$HOME` (A11) | launchd agent via `SMAppService` [V17] | FGS `specialUse` [V13] | none (in-app) | none (in-app) |
| **Background limit** | none | suspend on idle/power button; idle inhibitor on AC | sleep; IOPM assertion on AC; lid (A10) | Doze when unplugged + idle [V14] | suspended when backgrounded [V01] | suspended when backgrounded [V01] |
| **Network permission** | none (host firewall) | none | Local Network (macOS 15+) for LAN-direct outbound; tailnet exempt [V02] | `ACCESS_LOCAL_NETWORK` at targetSdk 37 (inbound needs it) [V12]; loopback A05 | Local Network for LAN outbound only; tailnet exempt; inbound TCP exempt [V02] | same as iPadOS |
| **Transport stack** (peer protocol per `trust.md`: TLS 1.3 mutual, pinned, framed) | JSSE `SSLEngine` + frame codec for peers; Ktor CIO stays for the loopback API | same | same | Conscrypt via the same JSSE code; NSC forces app-layer TLS (C3) | Network.framework `NWConnection`/`NWListener` + `NWProtocolFramer` (C4) | same |
| **Primary engine** | llama.cpp JNI: CPU + Vulkan (CUDA/HIP optional, OD8) | llama.cpp JNI: Vulkan (RADV) / CPU | llama.cpp JNI: Metal; MLX later (separate rows) | llama.cpp JNI: CPU / OpenCL / Hexagon / Vulkan (v2 P6 bakeoff) | llama.cpp XCFramework (Metal); MLX Swift optional | same |
| **Node key** (tiers per `trust.md`) | file (0600) → TPM P-256 later | file (TPM likely unavailable per `trust.md`) | login-keychain / file; Secure Enclave P-256 via shim only if the A19 spike passes | Keystore / StrongBox P-256 [V16] | Secure Enclave P-256 | Secure Enclave P-256 |
| **BYOK keys** | none in S1–S3 (OD3) | none in S1–S3 | none in S1–S3 | yes (v1 vault) | `CloudOnly` tier: own Keychain, ThisDeviceOnly | same |
| **Local-app identity** (Inv 5 analogue) | not offered S1–S3 (CLI token file); later `SO_PEERCRED` (A12) | same | not offered S1–S3; later XPC audit token (A12) | **AIDL `getCallingUid`** (v1) | n/a (no cross-app daemon) | n/a |
| **Situation probes** | sysfs/procfs (pure Kotlin) | sysfs incl. `gpu_busy_percent` | native shim: thermalState, IOKit, NWPathMonitor | thermal headroom, BatteryManager, availMem, metered | thermalState, battery, `os_proc_available_memory`, NWPathMonitor | same |
| **Packaging** | jpackage deb/rpm + tarball; Flatpak rejected for the daemon | tarball to `$HOME` only | notarised DMG + Homebrew cask (own tap) [V19]; no MAS | sideload + F-Droid; developer verification [V15] | TestFlight → App Store [V20] | same |
| **Toolchain / cost** | none new | none new (dev host) | Mac + Developer ID (OD2) | existing | Mac + Developer Program (OD2) | same |
| **Code** | shared JVM | shared JVM | shared JVM + tiny native shim | shared JVM + Android modules | **Swift** (`apple/AsomKit`) | **Swift** |

Not requested and out of scope: **Windows**. The JVM node would port with modest effort. Hosting would be a Windows service or a per-user scheduled task, which needs its own analysis. The roadmap's "Dell" may run Windows (OWNER-FILL, OD8).

---

## 5. Cross-cutting platform constraints that bind the sibling sections

| ID | Constraint | Driven by | Binds |
|---|---|---|---|
| **C1** | **All numbers in any signed JSON, and in any JSON compared across implementations (manifest, live state, router vectors), are integers in declared units within ±(2⁵³−1).** Examples: `decodeMilliTokPerSec`, `ttftMicros`, `bytes`, `permille`. Floats, exponents and `-0` are rejected at parse time. | RFC 8785 number serialisation is the hardest part of JCS [V24]. JDK 17 vs 19+ `Double.toString` differs [V27]. Swift rounding and escaping defaults differ (A13; demonstrated in §8.9). | `manifest.md`, `router.md` (score arithmetic in 64-bit integers with specified rounding), `benchmark.md` |
| **C2** | **ES256 (P-256) is the only signature algorithm that is hardware-backed on all three hardware families** (Secure Enclave, StrongBox [V16]; TPM 2.0 generally, assumption). Wire encoding is 64-octet R‖S [V24]. Android Keystore, JCA and `SecKeyCreateSignature` emit **DER**, so every implementation needs a tested DER↔raw codec (vectors M02/M03). | [V16], [V24] | `manifest.md`, `trust.md` |
| **C3** | **Peer traffic is TLS at the application layer, even inside WireGuard/Tailscale.** On Android, `http://` to a tailnet IP needs a Network Security Config cleartext exception, and Invariant 2 forbids one beyond localhost. Using one would be an *additional* Invariant 2 change. | Invariant 2 text; `network_security_config.xml` | `trust.md` |
| **C4** | **The iOS peer transport uses Network.framework**, not `URLSession`, to avoid ATS IP-literal and self-signed friction [V03]. | [V03] | `trust.md` |
| **C5** | **Prefer overlay paths.** On Apple platforms VPN traffic is not "local network", so no prompt is needed [V02]. On Android 17, overlay treatment is unknown (A06). LAN-direct remains possible with the platform permissions. | [V02], [V12] | `trust.md` (discovery / static registry) |
| **C6** | **No cross-app daemon on iOS.** The iOS requester is an in-app SDK, and routing is delegated to a home node. | [V01] | `router.md` (H role), `contract.md` (§10A tier table for iOS) |
| **C7** | **Backup and sync exclusion.** iOS: `isExcludedFromBackup` on ledger, manifests and pairing; Keychain items ThisDeviceOnly and non-synchronisable. macOS: node key in the Secure Enclave or a non-sync keychain item. Android: `allowBackup=false` (already set in v1). | Invariant 1 (no automatic egress) | all |
| **C8** | **Metered / expensive network is a live-state input** on every platform (`NWPathMonitor.isExpensive/isConstrained`, `NET_CAPABILITY_NOT_METERED`, NetworkManager `Metered`). A phone on cellular should not upload a 4k-token prompt to a peer without the policy allowing it. | watched-object ethos | `router.md` |
| **C9** | **Local-caller identity differs per OS** (AIDL / `SO_PEERCRED` / XPC audit token / none on iOS). The mesh never uses a *remote* caller's claim as identity; `trust.md` owns peer identity. | Invariant 5 | `trust.md`, `contract.md` |
| **C10** | **One benchmark harness per OS.** macOS benchmarks use the JVM shell. The Swift app is iOS/iPadOS only and opts out of Mac availability (A09), so two harnesses never produce competing manifests for one Mac. | Comparability | `benchmark.md` |
| **C11** | **The pinned llama.cpp commit is identical across all first-party engines**, and the manifest records the backend (`metal`, `vulkan`, `opencl`, ...), commit and build flags. Rows from different commits or backends are never averaged together. | Comparability | `benchmark.md`, `manifest.md` |

---

## 6. Code strategy

### 6.1 What each platform must implement under the recommended roles

"Shared" means one Kotlin artifact used by Android and desktop. "Port" means an independent Swift implementation pinned by conformance vectors.

| Component | Android | Linux / Deck / macOS | iOS S5 (bench app) | iOS S6 (client SDK) | iOS S7 (fg provider) |
|---|---|---|---|---|---|
| Contract constants (headers, error codes, statuses) | shared `:core:contract` | shared | port (W00) | port (W00) | port (W00) |
| JCS integer profile, manifest verify | shared `:core:mesh` (new) | shared | port (M01–M03) | port (M01–M03) | port |
| Manifest signing | Keystore adapter | file / SE-shim adapter | Secure Enclave adapter | — | SE adapter |
| Benchmark derivation + plain-text render | shared `:bench-core` (new) | shared | port (M04–M05) | — | port |
| Engine binding | JNI llama.cpp (v2 P1) | same JNI, desktop builds | llama.cpp XCFramework | (Embedded tier) | same |
| Catalogue subset, download, sha256 verify, ledger | existing Android modules | JVM (JSONL ledger, §7.1) | port (W00 + catalogue fixture) | port | port |
| **Mesh router** | shared `:core:routing` | shared | **— (none)** | **— (delegated to H)** | **— (iOS provider never routes onward)** |
| Peer client transport (framed TLS per `trust.md`) + SSE re-framing | JSSE/Conscrypt | JSSE | — | Network.framework + `NWProtocolFramer` (W03, W05, W06) | same |
| Peer listener + auth + dual-row ledger | `:server` + v4 listener | same | — | — | port (W01–W07) |
| Pairing | AIDL (local apps) + QR (peers) | QR (peers) + CLI token | — | QR scan (W04) | QR |

### 6.2 The four options, scored

| Criterion | (1) JVM + Swift port + vectors | (2) KMP for a small pure core | (3) Rust/C core + bindings | (4) iOS thin requester only |
|---|---|---|---|---|
| Respects current rules (brief §13, CLAUDE.md "no KMP") | **yes** | **no**: needs OD1 | yes literally; breaks the spirit of pure-JVM-first | **yes** |
| Reuses the audited v1 Kotlin (139 tests; 71 audit fixes) | **fully** | mostly; `java.*` usages must be rewritten (`BigDecimal` in `RouteRecord.formatUsd`, `ConcurrentHashMap` in `LatencyTracker`/`CooldownRegistry`, `MessageDigest`) | **no**: a rewrite, or a second core beside Kotlin | fully |
| Bare-JDK / Deck dev loop intact | **yes** | probably (K/N iOS targets are disabled on non-mac hosts; toolchain download behaviour is an assumption) | **no**: a Rust toolchain and per-OS native libs join the JVM test path | yes |
| iOS meets requirement 3 (standalone signed-manifest app) | yes | yes | yes | **no** (a thin client doesn't benchmark) |
| iOS meets requirement 1 (iPad/iPhone lend compute) | yes (S7) | yes | yes | **no** |
| Drift risk | medium, bounded by §8 and the small Swift surface | low for shared logic; **still needs vectors** (K/N vs JVM numeric formatting; platform crypto is per-platform anyway) | low for shared logic; bindings drift instead | low (little to drift) |
| Swift ergonomics | native | ObjC-bridged export; **Swift export is Alpha** [V22] | UniFFI pre-1.0 [V23] | native |
| Hardware keys, Network.framework, Metal | native Swift | still Swift (platform code can't be shared) | still Swift + Kotlin | native |
| Third-party subscribers ("any app or device", req. 3) | need a language-neutral spec + vectors **anyway** | same | same (or ship the Rust verifier as a library, which helps) | same |
| New toolchains | Swift/Xcode (unavoidable for any iOS app) | Kotlin/Native + Xcode | Rust + cargo-ndk + Xcode | Swift/Xcode |

### 6.3 Recommendation: Option 1, with delegated routing bounding the Swift surface

The decisive argument is about specification, not code. Requirement 3 says *any* app or device that subscribes must be able to verify the manifest. A language-neutral written spec plus golden vectors is therefore mandatory **regardless of the code strategy**. Once that spec and suite exist, a first-party Swift implementation is just the first "foreign" subscriber, held to the same bar as a third party.

KMP would reduce the Swift line count. It would not remove the spec, the vectors or the platform-specific code (Secure Enclave, Network.framework, Metal). It would cost a rule exception, a Kotlin/Native toolchain in a build that must stay bare-JDK-clean, and a Swift interop layer whose modern form is Alpha [V22].

Option 1 is affordable only because of three design choices made elsewhere in this section:

1. **Delegated routing on iOS (C6).** The router is the most frequently changing, most stateful component: v1.1 accountant, v2 governors, v2.5 semantic tiers, v4 mesh. It stays single-sourced in Kotlin. The Swift side never ports it.
2. **The integer-only rule (C1).** This removes the most expensive cross-language equivalence problems: JCS numbers, float formatting and score ties.
3. **The frozen-contract discipline** (brief §5; additive-only with owner sign-off). The parts Swift *does* port change rarely, and each change is visible and reviewed.

Option (4) is not rejected. It becomes the **first iOS increment** (S5 bench app, then S6 client SDK). But it is rejected as the *permanent* strategy, because a thin client cannot satisfy requirement 3's standalone benchmark on iOS, nor requirement 1's lend-compute on iPad.

### 6.4 Drift and maintenance cost of the winner (estimates; not measurements)

- **Swift code volume (rough estimate, including tests):**
  - S5 bench app ≈ 5k lines: catalogue subset and download/verify ~0.5k, ledger ~0.3k, llama.cpp wrapper ~0.6k, bench runner ~0.8k, M04 stats ~0.4k, M05 render ~0.3k, JCS/sign/verify ~0.6k, UI ~1.2k, plus tests.
  - S6 client SDK ≈ 3–4k.
  - S7 provider ≈ 3k.
  - Total ≈ 11–12k lines over the life of the plan.
- **Per-change cost:** every change touching W or M families lands twice (Kotlin and Swift) plus vectors. The estimate is **+30–50% effort per contract-affecting change**. Router changes cost **0** on Swift.
- **Recurring CI:** one macOS runner job (`swift test` against `conformance/`), free for this public repo [V25]. An on-device XCTest run for Secure Enclave signing needs owner hardware (NEEDS-DEVICE-VALIDATION per release).
- **Harness drift** (JVM/JNI vs Swift/C interop measuring the same llama.cpp): an **engineering parity check** at S5. Run both harnesses on one Apple-silicon Mac against the same GGUF and pinned commit, using a developer-only macOS test target of the Swift harness. Accept if every reported throughput field agrees within a tolerance that `benchmark.md` sets (a starting guess of 3% is an assumption). NEEDS-OWNER-VALIDATION.
- **Human cost:** Swift competence and a Mac are required for any iOS presence at all, whichever option is chosen (A02). That is a fixed cost of "iOS", not of Option 1.

### 6.5 What would make me change my mind

| Trigger (measurable) | Switch to | Why |
|---|---|---|
| The owner requires iOS to route across peers itself (a direct multi-peer requester, or an iOS provider that re-routes), putting the router into the Swift surface | **(2)** KMP for `:core:routing` + `:core:mesh` only | The router changes every roadmap version; porting it twice is where drift becomes chronic. |
| Swift conformance failures are found *after* merge (not by CI) in two consecutive releases, or the W/M families change more than about once per quarter | **(2)** | The vectors are not holding the line, or the spec is not slow-moving. |
| Kotlin Swift export reaches Stable [V22] and the owner lifts the ban | re-evaluate **(2)** | The main ergonomic cost disappears. |
| A first-party target with neither a JVM nor Swift appears, e.g. a browser/WASM manifest verifier for v3 web callers, or sub-512 MB embedded Linux | **(3)** Rust, scoped to verifier + canonicaliser | One small core compiled to WASM + native serves more consumers than two ports. |
| App Review rejects S7, or the owner decides iPhone/iPad lending is not worth it | freeze iOS at **(4)**'s scope (S5 + S6) | This is already the first two iOS increments; nothing is wasted. |

### 6.6 Rejected, with reasons

- **(2) now.** It needs a rule exception that the evidence does not yet justify, given the §6.3 bounding. It stays the documented fallback (OD1).
- **(3) Rust core.** It discards or duplicates the audited Kotlin core. It adds a toolchain to the bare-JDK path the brief says "must never break". The security-critical pieces (hardware keys, TLS stacks) remain per-platform anyway.
- **GraalVM / Gluon Substrate AOT to iOS (A01).** Unverified support for Kotlin coroutines, Ktor and OkHttp on iOS, with unfamiliar failure modes. It is the JVM-on-iOS route only in theory.
- **Kotlin/Native "without KMP".** It is the same toolchain and the same ban; it is not a loophole.
- **Swift on Linux for the desktop node.** It would duplicate the audited JVM server for no gain.
- **A separate Swift benchmark app for macOS.** It violates C10 (one harness per OS).

---

## 7. Module and repository layout (proposal; additive; no package renames)

### 7.1 JVM side

New modules follow brief §4 law: **pure JVM, no `android.*`**, always included in `settings.gradle.kts`, and covered by `jvmTest`.

| Module | Type | May depend on | Contents |
|---|---|---|---|
| `:core:mesh` | pure JVM | contract | Manifest DTOs, **JCS integer-profile canonicaliser**, ES256 raw↔DER codec, verifier (JCA `SHA256withECDSA`), live-state DTOs, pairing-payload codec (format from `trust.md`), and a `NodeKeySigner` interface implemented per platform. |
| `:bench-core` | pure JVM | contract, mesh, inference-api | Sample → statistic derivation (M04), plain-text renderer (M05), and the run-plan interpreter (the protocol from `benchmark.md`). |
| `:core:routing` (extended) | pure JVM | contract, catalogue, mesh | Mesh placement per `router.md`, with the simulator in its tests. |
| `:conformance` | pure JVM, **test-only** | all `:core:*`, `:bench-core`, `:server` | Loads `conformance/**` and runs every normative vector. Part of `jvmTest`. |
| `:node-desktop` | pure JVM application | `:server`, all `:core:*`, `:bench-core` | The asom-desktop shell. Contents: <br>• CLI <br>• XDG / `~/Library/Application Support` config <br>• systemd / `SMAppService` integration helpers <br>• peer listener (the v4-amended one) <br>• **JSONL append-only ledger with `FileChannel.force(true)` per row** (durable before responding — the audit lesson; export is the exact file bytes) <br>• Linux sysfs probes <br>• loader for optional native libs, with graceful `NoopEngine` when they are absent, so bare-JDK tests still pass |

- **The ledger is JSONL rather than SQLite** because it is pure JVM with no native jar, its durability semantics are explicit (`force` per append), and its export is trivially "the exact payload" (Inv 1). The ledger volume is small.
- **Native layer** (`native/`, arriving with v2 P0/P1):
  - The llama.cpp JNI library, built per `{os}-{arch}-{backend}`: linux-x86_64-{cpu,vulkan}, linux-arm64-cpu, macos-arm64-metal, android-arm64-{cpu,opencl,...}.
  - `libasom-platform` for macOS (Secure Enclave signing, thermalState, IOKit power, sleep assertion, NWPathMonitor). It is tiny and has no third-party dependencies.
  - The native libs load at runtime from a classifier jar or an install directory. **The pure-JVM build never needs them.**

### 7.2 Apple side

```
apple/
  AsomKit/                      # SwiftPM package (iOS 17+, iPadOS 17+)
    Sources/AsomContract/       # W00 constants mirrored from conformance/wire/W00-constants.json
    Sources/AsomManifest/       # JCS integer profile, ES256 raw/DER, verify, SE signer
    Sources/AsomBench/          # M04 derivation, M05 render, run-plan interpreter
    Sources/AsomEngine/         # llama.cpp XCFramework wrapper (pinned commit = C11)
    Sources/AsomMeshClient/     # InferenceClient protocol; RemoteMesh / Embedded / CloudOnly; NWConnection TLS; SSE
    Tests/ConformanceTests/     # reads ../../conformance/** ; verifies INDEX.json sha256s first
  AsomBench.xcodeproj           # the standalone iOS/iPadOS benchmark app (S5); Mac availability OFF (C10)
```

The Swift `InferenceClient` protocol mirrors the Kotlin §10A.1 surface (`chat`, `chatStream`, `embeddings`, `models`, `RequestOptions`, and a response carrying the echo headers). **It is a translation, not a new contract.** `docs/CLIENT_API.md` gains a Swift appendix at S6 (owner sign-off, since that file is contract).

### 7.3 CI (additive jobs; the existing two jobs are untouched)

| Job | Runner | Runs | Gate meaning |
|---|---|---|---|
| `jvm-tests` (existing) | ubuntu, SDK hidden | `jvmTest`, now including `:conformance` | Pure-JVM law **and** vectors on JDK 17 |
| `conformance-jdk21` | ubuntu | `:conformance:test` on the JDK the desktop jlink runtime ships | Catches [V27]-class runtime drift |
| `conformance-art` | ubuntu + Android emulator (KVM) | instrumented subset M01–M03, W05 on ART/Conscrypt | Android crypto providers ≠ desktop JCA. The emulator is not the RedMagic: StrongBox paths stay NEEDS-DEVICE-VALIDATION. |
| `apple` (from S5) | macos (standard) | `swift test` (ConformanceTests), simulator build | Swift port passes the same vectors. SE signing is device-only (simulator has no SE — assumption). |
| `xcheck-py` (optional) | ubuntu | independent Python checker for M01–M03 (like `gen.py`) | A third implementation, so the JVM is not the only oracle. |

---

## 8. The conformance suite

### 8.1 Principles

1. **The spec text is the authority; vectors are its executable form; no implementation is the oracle.** When the JVM and Swift disagree, the question is "what does the spec say?". If the spec is ambiguous, it is amended first, then the vector, then both implementations.
2. **Data only, language-neutral.** JSON files, with binary as base64url. No code in `conformance/` except an optional independent checker under `conformance/tools/`.
3. **Every family has positive and negative vectors.** "Must reject, with typed reason" is as normative as "must accept".
4. **Determinism.** Every input that could vary is supplied: clock (`nowMs`), randomness (supplied nonces), locale (none allowed), and time zone (none).
5. **Comparison is byte-exact where bytes are the contract** (canonical JSON, plain text, header values) and **typed where semantics are the contract** (error codes compared by code, never by message).

### 8.2 Location and layout

Location: **repo root `conformance/`**, a sibling of `fixtures/`. `fixtures/` holds inputs for the JVM implementation's own tests; `conformance/` is the cross-implementation normative artifact. It is also attached to GitHub Releases as `asom-conformance-<confVersion>.tar.gz` plus its sha256, a static artifact that third-party subscribers can fetch without cloning (no backend).

```
conformance/
  VERSION                         # e.g. 1.0.0  (semver, §8.7)
  INDEX.json                      # [{path, sha256, family, status}] — runners verify this first
  README.md                       # pass criteria, comparison rules, triage rule (§8.1 principle 1)
  wire/     W00-constants.json  W01-echo-headers.json  W02-error-envelopes.json
            W03-sse-framing.json  W04-pairing-payload.json  W05-key-fingerprints.json
            W06-mesh-headers.json  W07-live-state.json
  manifest/ M01-jcs.json  M02-verify-accept.json  M03-verify-reject.json
            M04-derive.json  M05-render.json  keys/TEST-ONLY-*.json
  router/   R01-hard-filter.json  R02-scoring.json  R03-ordering.json  R04-v1-pins.json
  tools/    xcheck.py (optional independent checker)
```

### 8.3 Vector envelope (JSON Schema, draft 2020-12)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "asom:conformance/vector-file/1",
  "type": "object",
  "required": ["family", "confVersion", "specRefs", "vectors"],
  "additionalProperties": false,
  "properties": {
    "family":      { "type": "string", "pattern": "^(W0[0-7]|M0[1-5]|R0[1-4])$" },
    "confVersion": { "type": "string", "pattern": "^[0-9]+\\.[0-9]+\\.[0-9]+$" },
    "specRefs":    { "type": "array", "items": { "type": "string" }, "minItems": 1 },
    "vectors": {
      "type": "array", "minItems": 1,
      "items": {
        "type": "object",
        "required": ["id", "origin", "status", "description", "input", "expect"],
        "additionalProperties": false,
        "properties": {
          "id":          { "type": "string", "pattern": "^(W|M|R)[0-9]{2}-[0-9]{3}$" },
          "origin":      { "enum": ["hand", "generated"] },
          "status":      { "enum": ["normative", "proposed", "illustrative"] },
          "description": { "type": "string", "minLength": 10 },
          "rationale":   { "type": "string" },
          "nowMs":       { "type": "integer" },
          "input":       { "type": "object" },
          "expect": {
            "oneOf": [
              { "type": "object", "required": ["ok"],     "properties": { "ok": {} } },
              { "type": "object", "required": ["reject"], "properties": { "reject": { "type": "string", "pattern": "^[A-Z][A-Z0-9_]+$" } } }
            ]
          }
        }
      }
    }
  }
}
```

- `status: proposed` marks vectors that depend on an **un-signed-off contract delta** (for example `X-Asom-Node`). Runners execute them in a non-blocking lane until owner sign-off flips them to `normative`. This keeps "proposed" from becoming "de facto frozen".
- `origin: generated` vectors must be regenerated only by an explicit task. CI fails if a generated file changes without a `VERSION` bump and a reviewed diff.

### 8.4 Families and minimum required cases

**Wire (W).** W00–W03 pin *existing* v1 behaviour and need no contract change.

| Family | What it pins | Minimum cases |
|---|---|---|
| **W00 constants** | Every `X-Asom-*` header name, every §5.6 error code with its HTTP status and OpenAI `type`, the virtual model names, default port, bind host | Exactly mirrors `ContractFreezeTest`. Proposed additions are listed with `status: proposed`. |
| **W01 echo headers** (Inv 9 law) | `RouteRecord` → header map | `Served-By` only when both provider and model are known; `Egress` always present; cost headers only when estimate **and** basis; USD rendering (HALF_UP at 8 places on the shortest decimal, strip zeros, plain string) including the §8.9 traps (`1.25e-7` → `0.00000013`, `2.5e-8` → `0.00000003`, `1.25e-9` → `0`); header names compared case-insensitively |
| **W02 error envelopes** | Typed error → HTTP status + OpenAI envelope | One per code; message text **excluded** from comparison; **no key material in any field** (the audit's x-api-key lesson as a vector) |
| **W03 SSE framing** | Byte stream → events | Split mid-field and mid-UTF-8 sequence; `\n`, `\r\n` and `\r` terminators; comment lines; multi-line `data:` joining; `[DONE]`; stream ending without a terminal blank line (reject or flush, per spec) |
| **W04 pairing payload** | QR / URI ↔ struct | Owned by `trust.md`. Unknown version, missing fingerprint, expired, and non-canonical encodings are all rejected |
| **W05 key fingerprints** | SPKI DER → fingerprint | P-256 SPKI → SHA-256 base64url (seed in §8.9); uncompressed vs compressed point input; reject non-P-256 |
| **W06 peer protocol codec** | Peer-protocol framing and message schemas (`trust.md` proposes length-prefixed frames over mutual TLS 1.3, not HTTP), plus any proposed app-facing echo additions (e.g. `X-Asom-Node`) | Frame length limits and truncation; unknown message type; version negotiation; every message schema's accept/reject pairs. Everything is `status: proposed` until contract sign-off (OD7, `contract.md`) |
| **W07 live state** | Live-state message → parsed struct + staleness class at `nowMs` | Owned by `router.md`. Clock-skew tolerance edges; unknown fields ignored or rejected per the additive rule |

**Manifest (M).**

| Family | What it pins | Minimum cases |
|---|---|---|
| **M01 JCS integer profile** | Value → canonical UTF-8 | Key order by **UTF-16 code units** (U+1F600 before U+FF21 — seed); `/` not escaped; control characters as lowercase `\u00XX`; no Unicode normalisation (NFD stays NFD); integers at ±(2⁵³−1); **reject** float, exponent, `-0`, duplicate names, lone surrogates, >2⁵³−1 |
| **M02 verify-accept** | (canonical bytes, raw sig, pinned key, `nowMs`, nonce) → ok | Leading-zero `r` kept at 32 octets (seed); high-S accepted (seed); boundary `nowMs == expiresAtMs − 1` |
| **M03 verify-reject** | … → typed reject | `SIGNATURE_INVALID` (one integer changed — seed); `SIGNATURE_ENCODING` (DER where raw is required — seed); `KEY_NOT_PINNED` (seed); `NON_INTEGER_NUMBER` (seed); `DUPLICATE_KEY` (seed); `EXPIRED`; `NOT_YET_VALID`; `NONCE_MISMATCH`; `ALG_UNSUPPORTED` (`none`, `HS256`); `SCHEMA_MAJOR_UNKNOWN`; `TRAILING_DATA`; signed bytes that are not the canonical form of the parsed object |
| **M04 derive** | Raw benchmark samples (integers: µs timestamps, token counts, temperatures) → manifest measurement fields + confidence class | Median/percentile definitions (which interpolation); outlier rule; throttle-onset detection; minimum-runs → low-confidence flag. Owned by `benchmark.md`; this section fixes that it **must** be vectorised |
| **M05 render** | Manifest fields → plain-text report, byte-exact (UTF-8, LF) | Every template branch; unit formatting; singular/plural; "unknown / not measured" wording. Guarantees the plain text and the JSON cannot diverge across implementations |

**Router (R).** These are required of every implementation that routes. Today that is the shared Kotlin only; they are also the spec pin for any future port.

| Family | What it pins | Minimum cases |
|---|---|---|
| **R01 hard filter** | Request + nodes → excluded set with reason codes | No-train; local-only; model absent; stale live state; `availability` not SERVING; metered-network policy (C8); peer trust class vs data class (`trust.md`) |
| **R02 scoring** | Per-candidate **integer** score terms + total | Every term at zero, typical and saturating values; overflow safety |
| **R03 ordering** | Ranked list | Exact ties broken by the total order (node id, then model id), as in v1's `CHEAPEST_ORDER`; determinism under input permutation |
| **R04 v1 pins** | Existing `Router` behaviour as data | The laws already encoded in `RouterTest`/`RouterPropertyTest` (filter soundness, per-policy ordering incl. unpriced/unmeasured/unranked-last, the `auto` band, determinism, total-order tie-breaks) plus `X-Asom-Fallback` restrict+order and the dedup of repeated fallback ids (`Router.plan`), expressed as concrete input/output cases. **These can be written now against frozen v1 behaviour.** |

### 8.5 Which implementation must pass what (per release)

| Implementation | W00–W03 | W04–W07 | M01–M03 | M04–M05 | R01–R04 |
|---|---|---|---|---|---|
| JVM on JDK 17 + desktop JDK (Android, desktop) | ✔ | ✔ | ✔ | ✔ | ✔ |
| ART (Android instrumented subset) | — | W05 | ✔ | — | — |
| Swift S5 (bench app) | W00 | — | ✔ | ✔ | — |
| Swift S6 (client SDK) | W00–W03 (parse side) | W04, W05, W06 | ✔ | — | — |
| Swift S7 (fg provider) | W00–W03 (generate side) | ✔ | ✔ | ✔ | — (never routes) |
| Third-party subscriber (claims "asom-manifest-compatible") | — | — | ✔ | M05 optional | — |

### 8.6 Runners

- **JVM:** `:conformance` in `jvmTest`. It runs on a bare JDK, so it works on the Deck and in the SDK-hidden CI job.
- **ART:** an instrumented test APK in the `conformance-art` job (emulator). StrongBox-backed signing is device-only and NEEDS-DEVICE-VALIDATION.
- **Swift:** `swift test` in `apple/AsomKit`, plus an on-device XCTest pass for the Secure Enclave signer before each TestFlight build (NEEDS-DEVICE-VALIDATION).
- **Every runner verifies `INDEX.json` sha256s before running.** A partial or tampered vector checkout is a failure, not a pass.

### 8.7 Versioning, triage and gates

- **Versioning.** `VERSION` is semver. **Major:** a previously valid expected output changes. **Minor:** vectors are added. **Patch:** descriptions change. Each implementation compiles in the `confVersion` it passed and reports it in its manifest's harness block (`manifest.md`) and in node capability exchange (`router.md`), so a lagging peer is visible, as a diagnostic only.
- **Triage** (§8.1 principle 1): the spec decides, the JVM does not. Hand-origin vectors encode laws; generated vectors encode bulk. A disagreement on a generated vector is presumed to be a *generator* bug until the spec says otherwise.
- **Gate text for PROGRESS.md** (brief §12 style): each platform release pastes the real runner output (`N vectors, 0 failures, confVersion X.Y.Z`) for each family it must pass per §8.5. Device-only lanes stay NEEDS-DEVICE-VALIDATION until the owner confirms.

### 8.8 Why this is the minimum

- Drop **W**, and a Swift client can mis-frame SSE or misread typed errors silently. That is the same failure class as the v1 `<queries>` defect: a tier "dead in the field" that is indistinguishable from absence.
- Drop **M01–M03**, and two honest implementations reject each other's valid manifests or, worse, accept a tampered one.
- Drop **M04–M05**, and the same device yields different numbers and prose on iOS and JVM.
- Drop **R**, and the future port (§6.5 trigger) has no target, and today's Kotlin router loses a language-neutral statement of its laws.

Everything else (performance, UI, engine numerics) is out of the suite by design; §13 covers it.

### 8.9 Seed vectors computed in this session (illustrative; not yet normative)

Files: `mesh/conformance-examples/gen.py` (generator: pure-Python P-256 + JCS integer profile), `seed-vectors.json` (output), `UsdCheck.java` + `usdcheck.out` (JVM confirmation). All keys are **TEST-ONLY**.

- **M01-001 (UTF-16 order trap).** Input keys `b, a, é, 😀, Ａ, url, ctl, nfd`.
  - Canonical: `{"a":2,"b":1,"ctl":"\u0001\n","nfd":"é","url":"https://x/y","é":3,"😀":4,"Ａ":5}`.
  - A code-point sort, the natural result of Swift `String` comparison or a naive `sorted()` in many languages, wrongly yields `...,"é":3,"Ａ":5,"😀":4}`.
  - The `nfd` value is `e`+U+0301 and must **not** be normalised.
- **Test key:** P-256 JWK `x = wCAcRYew3_8Us5ZsqSBtOOuMMdZszDoGBK8QHlJo9Co`, `y = JksSDlYCCf7hFaqCkemicNNR0bSeUVdtVuqq7Q2EWPc`. **W05 fingerprint** (SHA-256 of SPKI, base64url) = `OcEjhCYiYrqpV_-aermCh1stfS6wFdwEicGWmkHbHJ0`.
- **M02-001.** Signs `{"decodeMilliTokPerSec":12345,"expiresAtMs":1759708800000,"issuedAtMs":1759104000000,"nodeId":"n_test","schema":"asom.manifest/0-example"}`.
  - The raw 64-octet signature begins `AP5y…`: `r` has a leading zero octet that must be kept.
  - The DER form (70 bytes) verified with OpenSSL 3.0.13: "Verified OK".
- **M02-002.** The same signature in high-S form verifies. Policy: accept, and never use signature bytes as an identifier.
- **M03-001…005.** Tampered integer → `SIGNATURE_INVALID` (self-check: verify = false). DER where raw is required → `SIGNATURE_ENCODING`. Other key → `KEY_NOT_PINNED`. `12.5` → `NON_INTEGER_NUMBER`. `{"a":1,"a":1}` → `DUPLICATE_KEY`.
- **W01 USD rendering** (confirmed on OpenJDK 21.0.10):
  - `1.25e-7` → `0.00000013`. A port that rounds the *exact binary* value, or rounds half-even (Swift `NumberFormatter`'s default, A13), yields `0.00000012`.
  - `2.5e-8` → `0.00000003` (traps yield `…02`).
  - `123.456789125` → `123.45678913` (half-even yields `…12`).
  - `1.25e-9` → `0`.
  - **Still to confirm on JDK 17** (the CI target), because of [V27].
- **W03-001.** Four reads `ZGF0YTog`, `eyJhIjoxfQoK`, `OiBrZWVwLWFsaXZlCgpkYXRhOiB7ImIi`, `OjJ9DQoNCmRhdGE6IFtET05FXQoK` must produce the events `{"a":1}`, `{"b":2}` and done. The reads include a comment line, a split field and a CRLF terminator.

### 8.10 What the suite does not guarantee

- **Passing vectors proves agreement on the enumerated cases, not correctness everywhere.** The suite is a floor, not a proof.
- **It says nothing about measurement honesty**, performance, thermal safety or engine numerics. Those are physical, and are covered (partially) by the harness parity check (§6.4) and `benchmark.md`.
- **It cannot detect a shared misunderstanding** baked into the spec and into every implementation's vectors. Hand-written law vectors reviewed against the spec text reduce this risk but cannot remove it.
- **Emulator and simulator runs do not exercise StrongBox or the Secure Enclave.** Those lanes stay NEEDS-DEVICE-VALIDATION.

---

## 9. Platform sequencing

Roadmap law: versions are strictly sequential, and v1 device validation is still open (PROGRESS.md). Every stage below that pulls v2 or v4 work forward is marked **OD4** and needs owner authorisation. None of it is assumed.

| Stage | Platform / deliverable | Entry criteria | Gate (brief §12 style) | Why this position |
|---|---|---|---|---|
| **S0** (buildable now, with OD4 OK) | `conformance/` + `:conformance` with **W00–W03 and R04 pinning frozen v1 behaviour**; `:core:mesh` JCS/ES256/verify with M01–M03; `xcheck.py` | Owner OK that this is design-support work, not "starting v2/v4". **No contract change. No Android code.** | `./gradlew jvmTest` real output incl. `:conformance`; `xcheck.py` output agreeing on M01–M03 | Pure JVM on the Deck; freezes the language-neutral spec before any second implementation exists |
| **S1** | **Linux + Steam Deck node** (`:node-desktop`): PA/H/B/S, bench CLI, JSONL ledger, peer listener, llama.cpp JNI desktop builds (CPU/Vulkan) | S0; v4 design session held; Inv 2 amendment + `lan` class signed off (OD7); llama.cpp JNI surface (v2 P1) authorised for desktop first (OD4); tailnet exists | Two JVM nodes (Deck + Dell/second process) complete peer requests with **rows on both ledgers**; conformance green; Deck Game-Mode behaviour (A11) and sleep inhibition NEEDS-OWNER-VALIDATION | Zero new toolchain or fees; the dev host; always-on; proves every protocol with no phone and no Apple hardware |
| **S2** | **Android as requester** (routes AIDL-paired apps' requests to S1 providers; **no listener on the phone**) | v1 `QA_V1.md` device validation complete; S1 | RedMagic → Deck request with rows on both nodes; outbound-only verified (no socket bound beyond loopback); NEEDS-DEVICE-VALIDATION | The highest-value flow (phone → desktop), with the smallest Android change (no Inv 2 listener on the phone) |
| **S3** (∥ S2) | **macOS node**: the S1 artifact + Metal JNI dylib + `libasom-platform` + `SMAppService` agent + notarised DMG / Homebrew tap | S1; the owner has an Apple-silicon Mac + Developer ID (OD2, OD8) | Notarisation log; Local Network prompt behaviour on macOS 15+ for LAN-direct [V02]; Mac ↔ Deck mesh; NEEDS-OWNER-VALIDATION | Strongest consumer provider class; same JVM code; the delta is packaging and native glue |
| **S4** | **Android as provider** (PF/PA-when-charging) + Android standalone bench shell | v2 engine on Android (v2 P1–P4) validated on the RedMagic; A04/A06 tested; targetSdk-37 loopback check (A05) | Deck → RedMagic request; thermal DRAINING transition observed; NEEDS-DEVICE-VALIDATION | Needs the engine and governors on-device; lowest-value direction for a phone, so it comes after S2 |
| **S5** | **iOS/iPadOS standalone benchmark app** (Swift; B/S only; no mesh) | Manifest spec + M01–M05 frozen and green on the JVM; OD2 = yes; harness parity check plan | `swift test` all required families (§8.5) + on-device SE signing test; TestFlight build; harness parity on Mac within tolerance; NEEDS-DEVICE-VALIDATION | Meets requirement 3 on iOS with the least App Review risk (no serving) |
| **S6** | **iOS client SDK** (`AsomMeshClient`: RemoteMesh → Embedded → CloudOnly; delegated routing to H) | S5; S1 or S3 as home node; `CLIENT_API.md` Swift appendix signed off | iPhone app streams via the Deck/Mac H over the tailnet with no Local Network prompt (C5); H down → graceful fallback, mirroring the P6 uninstall test; NEEDS-DEVICE-VALIDATION | Requester on the platform with the least compute to offer |
| **S7** (owner-gated, OD5) | **Foreground-only provider**, **iPad first** | S6; OD5 = yes; A15/A17 spikes pass | Mac → iPad request while frontmost; background → DRAINING with the listener closed at once; thermal `serious` → DRAINING; App Review outcome recorded | Highest review risk, lowest marginal compute; last on purpose |

**Why Linux first** (not Android and not macOS):

1. It *is* the existing `:server` running on the developer's own Deck.
2. CI is Linux with a bare JDK.
3. No signing, fees, store review or OS privacy prompts.
4. It is the only platform that is always-on without conditions.
5. The v2 P1 JNI plan already requires a linux-x86_64 build.
6. Two JVM nodes exercise the entire mesh protocol, so every later platform is tested *against* a known-good reference provider.

**Why iOS last:** it has the most prerequisites (Mac, Developer Program, frozen spec, Swift port), the strictest platform limits [V01, V05], and the least compute to lend.

---

## 10. Owner decisions (only the owner can make these)

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **OD1** | Keep the KMP ban? | (a) keep; Swift port + vectors (§6.3) · (b) relax for `:core:mesh` + `:core:routing` only · (c) relax broadly | **(a)**, with (b) pre-approved as the fallback **if** a §6.5 trigger fires. The ban is the owner's own rule; the evidence does not yet justify an exception. |
| **OD2** | Commit to Apple platforms (Developer Program ≈ US$99/yr (A03), an Apple-silicon Mac, Swift skills)? | (a) yes, now · (b) macOS only (Developer ID), iOS later · (c) no Apple for now | **(b) → (a) at S5.** macOS is high-value and cheap once S1 exists; iOS needs the frozen spec first. Without a Mac, iOS is impossible regardless of code strategy (A02). |
| **OD3** | **Platform generalisation of Android-specific invariants.** Inv 4 (Android Keystore), Inv 5 (AIDL identity) and Inv 7 (Compose UI) are literally unsatisfiable on desktop and iOS. Generalising them is a **third-amendment candidate** under roadmap §13. | (a) **avoid it for S1–S4**: non-Android nodes hold no BYOK keys, offer no local-app pairing (CLI only), use CLI UI (already roadmap-sanctioned) · (b) one "platform-equivalence amendment" with an explicit table (Keychain/SE, TPM/file-key; `SO_PEERCRED`/XPC; SwiftUI against the token seam) · (c) treat as interpretation, no amendment | **(a) now, and escalate (b) before S5/S6** (the iOS `CloudOnly` Keychain vault and SwiftUI need it). **Reject (c)**: silently reinterpreting an invariant is exactly what roadmap §13 forbids. |
| **OD4** | Re-sequence the roadmap: build a "mesh track" (S0–S3) before v1.1/v2/v2.5/v3 ship, including v2 P1's JNI for desktop first? | (a) strict roadmap order (mesh work waits for v3) · (b) authorise S0 only now · (c) authorise S0–S3 as a parallel track | **(b) now, (c) after v1 device validation.** S0 changes no contract and touches no Android code. S1+ needs the v4 amendments and the v4 design session anyway. |
| **OD5** | Allow iOS/iPadOS as a foreground-only provider (S7)? | never · iPad only · iPad + iPhone | **iPad only, after S6.** RAM, Split View (A08) and background GPU for bench runs [V04] make it the only Apple mobile device where lending is plausibly worth the App Review risk [V05]. |
| **OD6** | Android distribution under developer verification [V15]. | (a) register a developer identity (ID + $25) and the signing key · (b) limited-distribution account (≤20 devices) · (c) rely on users taking the "advanced flow" | **(a) before global enforcement (2027)**, keeping F-Droid. Note that F-Droid-signed builds have their own key-registration question; confirm with F-Droid's current guidance. |
| **OD7** | Confirm that the provisional v4 egress class `lan` (roadmap §7/§8) is within the approved v4 scope and not a change to Invariant 3's "exhaustive" list requiring separate escalation. | (a) covered by the v4 direction · (b) needs explicit amendment wording | **(b)**: write the one-line Invariant 3 addition explicitly into the v4 amendment so it is not an implicit third amendment. `contract.md` owns the wording. |
| **OD8** | Inventory and backend distribution. What OS is the Dell? Does the owner have an Apple-silicon Mac? Ship CUDA/HIP builds (A14)? | — | Answer the inventory before S1 (it decides whether S1's second node is the Dell or a second Deck process). **Ship CPU + Vulkan only** until CUDA redistribution is legally cleared. |

---

## 11. Invariant impacts

| Invariant | Status | Detail |
|---|---|---|
| 1 No automatic egress | honored (with requirements) | No SDKs on any platform. **Requirements:** iCloud-backup exclusion + ThisDeviceOnly non-sync keychain (C7), or it is violated. Disclose OS-level opt-in diagnostics (Apple crash/analytics, TestFlight) as outside app control. Desktop ledger export = the exact JSONL bytes. |
| 2 Bind 127.0.0.1 only | needs-amendment (the planned v4 one) | Peer listeners on Linux, macOS, Android and iPad bind overlay/LAN addresses only. The loopback listener is unchanged. **C3 keeps the "no cleartext beyond localhost" half intact** (app-layer TLS). |
| 3 Egress classes exhaustive | needs-amendment | `lan` class (roadmap v4 provisional). Make it explicit (OD7). |
| 4 BYOK keys Android-Keystore | honored in S1–S4 by scoping; third-amendment candidate for S5+ | Non-Android nodes hold no BYOK keys until OD3(b). The iOS `CloudOnly` vault needs OD3. |
| 5 AIDL-verified pairing | honored on Android; third-amendment candidate elsewhere | No local-app pairing on desktop in S1–S4. Peer identity is `trust.md`'s. **No HTTP registration endpoint anywhere.** |
| 6 Red/green never carry meaning | honored | Applies to SwiftUI, menu-bar UI and **CLI output** (labels, never colour alone). |
| 7 Compose/Material3 placeholder UI | needs-amendment (wording) | Desktop CLI is roadmap-sanctioned. SwiftUI against the same token seam falls under OD3. |
| 8 No GMS/Firebase/Play services | honored | Apple analogue: no CloudKit, no iCloud Keychain sync, no third-party SDKs. Attestation without GMS is `manifest.md`'s. |
| 9 Echo headers + ledger from one `RouteRecord` | honored | Shared Kotlin on Android and desktop. The iOS provider (S7) ports the law and is pinned by W01. |
| CLAUDE.md "no KMP" | honored | Recommended Option 1 keeps it (OD1 fallback documented). |
| CLAUDE.md "do not start later versions" | surfaced | Every stage is OD4-gated; S0 is the only work proposed without device or contract dependencies. |
| Roadmap §11 stop-line | honored | No public listeners, no open discovery, static paired registry (`trust.md`). The platform breadth adds no relay or incentive surface. |

---

## 12. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **Android 17 local-network permission covers loopback (A05)**, breaking v1 at targetSdk 37 | critical if true (probability unknown) | Hard rule: no targetSdk 37 bump until an emulator/device test proves `127.0.0.1:11435` works for paired apps. Add it to the QA script. |
| App Review rejects the iOS provider (2.4.2 / 2.5.4) | high (for S7 only) | S5/S6 ship without serving. iPad-first. Hard thermal and Low Power gates. Precedent [V06] noted, not relied on. |
| Android developer verification blocks sideload/F-Droid installs [V15] | high (distribution) | OD6(a) before global enforcement; document the advanced flow for power users. |
| Swift port drifts from JVM | medium | Integer-only rule (C1), delegated routing (C6), §8 suite with negative vectors, independent Python checker, harness parity check, §6.5 triggers. |
| Vectors generated by the JVM enshrine JVM bugs | medium | Hand-origin law vectors; the spec-over-implementation triage rule; `xcheck.py`; JDK 17 + JDK 21 + ART lanes (V27 shows the JVM is not stable across its own runtimes). |
| Doze removes network from an unplugged Android provider (A04) | medium | Default availability is when-charging; unplugged serving is opt-in after device validation. |
| Deck contention with games, and sleep | medium | GPU-busy yield, AC-only inhibitor, availability class `when-charging` (docked). |
| macOS agents and Local Network privacy (prompt timing, short-lived process bug FB16131937 [V02]) | medium | `SMAppService` registration; the daemon does not exit on first failure (per TN3179); overlay-first (C5). |
| Apple dependency (Mac, fee, Swift skills) stalls iOS | medium | OD2; iOS is last in the sequence, so nothing earlier blocks on it. |
| Homebrew cask policy [V19] | low-medium | Notarise, or run our own tap. |
| JNI engine crash takes down the desktop daemon | medium | systemd/launchd restart choreography (mirrors the v1.1 FGS hardening); engine conformance suite (v2 P0); subprocess fallback is a documented mind-changer (§3.1). |
| Backend and commit divergence makes manifests incomparable | medium | C11: pinned commit and recorded backend; rows are never merged across backends or commits. |
| Platform breadth pressures toward open discovery or relaying (stop-line creep) | low-medium | Static paired registry only; no relaying for unpaired parties; flagged here for the critique lenses. |

---

## 13. What this section does not guarantee (anti-overclaim)

- **The capability matrix states what each platform *permits*, not what it will *deliver*.**
  - No throughput or latency is claimed for any device (A18).
  - "PA" means the OS allows an always-on listener. It does not mean the device will be awake, on AC, cool or reachable at any given moment. The live-state protocol (`router.md`) exists for exactly that.
- **"Verified" facts are verified as of 2026-09-29 against the cited pages.** Platform policies (App Review, Android 17, developer verification, Homebrew) change. Re-verify at the start of each stage, as the roadmap already requires for its planning assumptions.
- **An App Store precedent [V06] shows that one app passed review once.** It does not predict asom's outcome.
- **Hardware-backed node keys (SE, StrongBox, TPM)** keep the private key non-exportable from that chip. They do **not** prove the software using the key is unmodified, that the device is not rooted or jailbroken, or that a benchmark was honest. `manifest.md` owns attestation tiers and their limits.
- **The Linux tier-0 file key protects against other OS users only.** It does not protect against the same user's processes, root, or disk theft without full-disk encryption.
- **The conformance suite** guarantees agreement on enumerated cases only (§8.10).
- **The harness parity check** bounds harness overhead on one Mac for one model. It does not certify cross-device comparability. Thermal state, background load and OS scheduling still differ (`benchmark.md`).
- **App-layer TLS over the overlay (C3)** protects the transport between paired endpoints. It does not stop a *compromised paired peer* from reading prompts it is sent (`trust.md` data-handling policy).
- **"Serve while charging" avoids Doze by construction only for Doze's documented trigger** [V14]. OEM battery managers (RedMagic's included) may still kill or restrict the FGS. That remains NEEDS-DEVICE-VALIDATION.

---

## Appendix A — Sources consulted in this session

- Apple TN3179 Understanding local network privacy — developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy (fetched via the docs JSON endpoint; updated 2026-02-17)
- iOS Background Execution Limits — developer.apple.com/forums/thread/685525 (updated 2026-01-09); NWListener in background — developer.apple.com/forums/thread/772637, /757385
- `BGContinuedProcessingTask` — developer.apple.com/videos/play/wwdc2025/227; developer.apple.com/forums/thread/816774, /794072, /807957; developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtaskrequest
- App Review Guidelines — developer.apple.com/app-store/review/guidelines; developer.apple.com/news/?id=ey6d8onl; techcrunch.com/2025/11/13/apples-new-app-review-guidelines-clamp-down-on-apps-sharing-personal-data-with-third-party-ai
- ATS — developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity and …/nsallowslocalnetworking
- App Store listings — apps.apple.com/us/app/local-llm-server/id6757007308; apps.apple.com/sa/app/pocketpal-ai/id6502579498; github.com/a-ghorbani/pocketpal-ai
- Increased memory limit — developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.kernel.increased-memory-limit; developer.apple.com/forums/thread/770868
- Foundation Models — apple.com/newsroom/2025/09/apples-foundation-models-framework-unlocks-new-intelligent-app-experiences; machinelearning.apple.com/research/introducing-third-generation-of-apple-foundation-models
- MLX — github.com/ml-explore/mlx-swift; ml-explore.github.io/mlx/build/html/usage/distributed.html
- llama.cpp — github.com/ggml-org/llama.cpp
- ExecuTorch — docs.pytorch.org/executorch/stable/using-executorch-ios.html
- Android local network permission — developer.android.com/privacy-and-security/local-network-permission; developer.android.com/about/versions/17/behavior-changes-17
- Android FGS types — developer.android.com/develop/background-work/services/fgs/service-types; Doze — developer.android.com/training/monitoring-device-state/doze-standby
- Android developer verification — developer.android.com/developer-verification; f-droid.org/en/2026/02/24/open-letter-opposing-developer-verification.html
- Android Keystore / StrongBox — developer.android.com/privacy-and-security/keystore; CryptoKit SecureEnclave.P256 — developer.apple.com/documentation/cryptokit/secureenclave/p256
- SMAppService — support.apple.com/guide/deployment/manage-login-items-background-tasks-mac-depdca572563/web; theevilbit.github.io/posts/smappservice
- JVM notarisation — blog.adoptopenjdk.net/2020/05/a-simple-guide-to-notarizing-your-java-application; github.com/camunda/camunda/issues/54877
- Homebrew Gatekeeper policy — github.com/orgs/Homebrew/discussions/6482, /7050; workbrew.com/blog/homebrew-5-0-0
- TestFlight — developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview
- Steam Deck — steamdeck.com/en/tech/deck; theinternetvagabond.com/2022/07/04/steam_deck_syncthing.html; github.com/tailscale-dev/deck-tailscale; tailscale.com/blog/steam-deck
- Flatpak Background portal — flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Background.html
- Kotlin Swift export — kotlinlang.org/docs/native-swift-export.html; UniFFI — github.com/mozilla/uniffi-rs
- RFC 8785 — rfc-editor.org/rfc/rfc8785; RFC 7518 — rfc-editor.org/rfc/rfc7518
- GitHub Actions billing — docs.github.com/en/billing/reference/actions-runner-pricing; github.com/orgs/community/discussions/70492
- JDK-4511638 — bugs.openjdk.org/browse/JDK-4511638, JDK-8291475
- Repo files read: `CLAUDE.md`, `ASOM_BUILD_BRIEF.md`, `ASOM_ROADMAP_BRIEF.md`, `PROGRESS.md`, `core/contract/**` (RouteRecord, Capabilities, AsomHeaders, Policy, AsomErrorCode, Asom, InferenceClient), `core/routing/**` (Router, LatencyTracker, CooldownRegistry), `core/inference-api/LocalEngine.kt`, `server/**` (Main, RoutePipeline, LedgerSink, ProviderDriver, AsomServer bind), `pairing/PairingRegistry.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/network_security_config.xml`, `.github/workflows/ci.yml`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `docs/DECK_SETUP.md`, `core/contract/src/test/**/ContractFreezeTest.kt`.
