# PLATFORM_PLAN: directories, runtimes, CI, gates and build order per platform

**Date:** 2026-09-30 · **Companion to:** `ASOM_MESH_DESIGN.md` r3 (§3 platform matrix, §9 phases) and `LAB_SPEC.md` (the lab). **Detailed sources:** the six platform sections under `platforms/` (`linux.md`, `windows.md`, `macos.md`, `ios.md`, `ubuntu-touch.md`, `android-mesh.md`), **as amended by the design** (§12.3 lists which of their corrections were taken). Where this plan and a platform section disagree, this plan wins; each disagreement is marked **r3**.
**Authorised by:** AD-3 (placement), AD-5 (verification honesty), roadmap §14 item 7 (scaffolds gated by hosted CI, shipping nothing), and directive D-F (different agents per platform).
**Evidence labels (never dropped):** `LAB`, `CI (hosted VM) evidence`, `EMULATOR EVIDENCE`, `SIMULATOR`, `CI-APPROX — NOT DEVICE EVIDENCE`, `SIMULATED — NOT DEVICE EVIDENCE`. Device items stay `NEEDS-DEVICE-VALIDATION` (NDV); items only the owner can judge stay `NEEDS-OWNER-VALIDATION` (NOV).

---

## 0. Rules common to every platform track

| # | Rule |
|---|---|
| P1 | **Disjoint directories, one owner each.** `lab/` (the lab track), `desktop/node-core` + `desktop/node` + `desktop/packaging/linux` (the desktop-and-Linux track), `desktop/packaging/windows` (the Windows track), `desktop/packaging/macos` (the macOS track), `apple/` (the Apple track), `ubuntu-touch/` (the Ubuntu Touch track). Android work lives in the existing root modules and starts only after V1-close. Two tracks never edit the same file, except append-only `PROGRESS.md` sections and one `include` line each in `desktop/settings.gradle.kts` |
| P2 | **The root build is unchanged.** Root `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `core/`, `server/` and `.github/workflows/ci.yml` are never edited by a scaffold. Every new workflow runs `git diff --exit-code -- core server gradle settings.gradle.kts build.gradle.kts gradle.properties .github/workflows/ci.yml` |
| P3 | **Separate builds map, never include.** `desktop/` and `ubuntu-touch/jvm/` are separate Gradle builds that map the pure-JVM root projects by directory, exactly as `LAB_SPEC.md` §2.2–§2.3 does, with build directories redirected. Never `includeBuild("..")` |
| P4 | **New workflow files only:** `lab.yml`, `desktop-linux.yml`, `desktop-windows.yml`, `desktop-macos.yml`, `apple-ios.yml`, `ubuntu-touch.yml`, and after V1-close `android-mesh.yml`. Pure-JVM jobs set `ANDROID_HOME=""` and `ANDROID_SDK_ROOT=""` |
| P5 | **Scaffolds bind nothing and serve nothing.** `NoopEngine`; no listener; systemd units, services and agents ship **disabled**; no model download. Only tests bind, and only to `127.0.0.1` |
| P6 | **Nothing is released.** Every CI artefact is named or labelled `UNSIGNED — not for release`. Signing jobs run only on `main` or tags with owner-held secrets, and produce nothing public until the owner rules D22 |
| P7 | **Gate discipline** (brief §11–§12): real command output pasted into `PROGRESS.md`; `BLOCKED(<reason>)` and stop when blocked; no fabricated logs; expected outputs in this plan are what a passing run prints, not results |
| P8 | **No KMP, no Compose for Desktop, no GraalVM native-image, no `llama-server` subprocess, no in-app update check (C14), no crash data with content (C15).** One Kotlin/JVM implementation (every non-Apple platform) and one Swift implementation (iOS/iPadOS), both pinned by the lab's vectors |

---

## 1. Build order

### 1.1 Now: ships nothing; each step needs only what precedes it

| Step | Track | Directory | Starts when | Why this position |
|---|---|---|---|---|
| **S1** | lab | `lab/` | now (AD-4) | Every other track consumes its vectors (W, M, R families) and, from M1, its promoted modules. L0.1 first: it pins frozen v1 against the real `:server` |
| **S2** | desktop core + Linux | `desktop/node-core`, `desktop/node`, `desktop/packaging/linux` | after `LAB_SPEC.md` L0.1 is green (for the shared isolation mechanism and W00) | **The shared JVM node comes first**: Windows, macOS and Ubuntu Touch all plug into its `DesktopPlatform` seam, and Linux is the M1 lender platform. DL0–DL3 only |
| **S3** | Windows packaging | `desktop/packaging/windows` | after S2's DL0 (the seam and `:node-core` exist) | per-OS adapter in its own directory; W0–W2 now |
| **S4** | macOS packaging | `desktop/packaging/macos` | after S2's DL0 | per-OS adapter in its own directory; MC1–MC2 now |
| **S5** | Apple | `apple/` | after `LAB_SPEC.md` L0.2's vectors exist | the independent Swift implementation (L0.7 = macOS MC0 = iOS I0); the natural independent oracle (`LAB_SPEC.md` §4.10) |
| **S6** | Ubuntu Touch | `ubuntu-touch/` | after S2 (it maps `desktop/node-core`) and L0.1/L0.2 | UT-0: a self-test in the real arm64 userland and a reviewed click, nothing more |
| — | Android | existing root modules | **after V1-close only** | directive D-D: Android app code is untouched until v1 device validation closes. No new directory is created now |

S3, S4 and S5 may run in parallel with each other once their start conditions hold, each by its own agent. The listed order is the order in which they may *start*.

### 1.2 Later: in AD-1 order, each at its phase's entry criteria (design §9.3)

| Phase | Android | Linux / Deck | Windows | macOS | iOS / iPadOS | Ubuntu Touch |
|---|---|---|---|---|---|---|
| **V1-close** | the open v1 device checklists | — | — | — | — | — |
| **v1.1** | V11-1…V11-3 (H4, H5, H7) + H1 (D5) | — | — | — | — | — |
| **v2** | engine + benchmark shell (roadmap v2) | — | — | — | — | — |
| **D-v2** (needs D23, D25, D27, D28) | — | DL4, DL5 | W3–W7 | MC3–MC7 | — | — |
| **M1** (needs D3, D5, D8, D9, D11, D12, D19, D29) | M1-1…M1-8 (requester) | DL6 (lender), DL7 | lender only if D28 places Windows in M1 (gate 13) | lender only if D28 places a Mac in M1 (gate 14) | — | — |
| **M1b** (needs D14 part A, D15, D24, D28) | — | — | lender if not in M1 | lender if not in M1 | I1–I3 (the asom app + `RemoteMesh`) | UT-1 (UT1.1–UT1.3) |
| **v2.5, v3** | roadmap | — | — | — | — | — |
| **M2+** (needs D14 part B, D16) | L-1…L-4 (lender) | desktop local-app API (D25(b)) | same | same; MC9 daemon (optional) | I4 (iPad lender) | UT-2 only if scheduled |

---

## 2. The shared desktop node (`desktop/`)

**Runtime.** Kotlin/JVM, compiled with `--release 17`, shipped on a jlinked **Temurin 21** runtime per OS and architecture. The same jar runs on Linux, the Deck, Windows and macOS, and (as a reduced host) inside the Ubuntu Touch click.

**r3: the node is split into `node-core` + hosts** (the Ubuntu Touch section's correction, X41). The platform sections' `:node-desktop` is `:node-core` here.

```
desktop/
  README.md                          # what this is, status (scaffold: ships nothing), honest limits
  settings.gradle.kts                # maps :core:contract, :core:catalogue, :core:routing, :core:inference-api, :server
                                     #   by projectDir (LAB_SPEC.md §2.2 mechanism); includes :node-core, :node,
                                     #   :packaging:windows:winplatform, :packaging:macos:macplatform (one line per track)
  build.gradle.kts                   # plugins apply false; build-dir redirection for mapped projects; aggregate desktopTest
  gradle.properties
  node-core/                         # :node-core, host-agnostic; NO OS-specific code
    src/main/kotlin/xyz/mdhv/asom/desktop/
      Main.kt                        # asom-node entry; --mode=system|user|foreground|selftest; refuses root;
                                     #   finds the host via ServiceLoader<DesktopPlatform>; prints nothing secret (H3)
      DesktopPlatform.kt             # the seam (below)
      NodeConfig.kt                  # config JSON, integers only (C1)
      control/ControlFrames.kt       # closed command enum; request/response types (the owner CLI protocol)
      cli/AsomCli.kt                 # asom: status, watch, chat, lend on|off|--foreground, doctor, bench, ledger export
      cli/TtyConfirm.kt              # confirmations from the controlling TTY only (T17(e))
      governor/ProviderFsm.kt        # OFF/ARMED/SERVING/DRAINING; LP-0..LP-2 (LAB_SPEC.md §6.5); grace per host
      ledger/JsonlLedgerSink.kt      # append + FileChannel.force; fail-closed (design §8.4)
      engine/NoopEngine wiring       # (DL4: the v2 JNI engine surface)
    src/test/kotlin/...              # NoSecretsOnStdoutTest, FSM exhaustive test, sink SIGKILL harness
  node/                              # :node = the Linux host (linux.md §10.2, minus what moved to node-core)
  packaging/linux/                   # §3
  packaging/windows/                 # §4
  packaging/macos/                   # §5
  native/                            # llama.cpp.pin (one commit for every backend, C11) + JNI build (DL4)
```

**The seam** (every host implements it; `ServiceLoader` picks the one on the classpath; `META-INF/services/xyz.mdhv.asom.desktop.DesktopPlatform`):

```kotlin
interface DesktopPlatform {
    val id: String                                            // "linux" | "windows" | "macos" | "ubuntu-touch"
    fun paths(mode: HostMode): NodePaths                      // private state dirs (0700/0600 or DACL), backup exclusion (C7)
    fun nikStore(paths: NodePaths): NikStore                  // reports keyStorage: tpm | os-keystore | secure-enclave | file
    fun power(): PowerPort                                    // source, charging, band, saver; hold()/release() keep-awake;
                                                              //   os_sleep_imminent / resumed events
    fun presence(): PresencePort                              // inputs tagged PRESENCE (LP-0); never serialised
    fun gpuContention(): GpuContentionPort?                   // other-busy permille, or null (rule off; asom doctor says so)
    fun thermal(): ThermalPort                                // band 0/1/2 with hysteresis
    fun listenerGate(): ListenerGate                          // Windows firewall consent gate (C16); OPEN elsewhere
    fun controlSocket(paths: NodePaths): ControlSocketServer  // SO_PEERCRED (Linux) | getpeereid (macOS) | ACL (Windows)
}
```

**Gates for the shared core** (part of DL0–DL2; §3.3):
- `./gradlew -p desktop :node-core:test` passes on JDK 17 and 21 without an Android SDK;
- `grep -c desktop settings.gradle.kts` prints `0`;
- `./gradlew -p desktop buildEnvironment | grep -c com.android` prints `0` with the SDK present;
- the root test count is unchanged.

---

## 3. Linux desktop/server and Steam Deck (`desktop/node`, `desktop/packaging/linux`)

**Source:** `platforms/linux.md` §3–§10, as amended by the design.
**r3 amendments:**
- LD-1 is decided by roadmap §14 item 7, so DL0–DL3 may start now as scaffolds;
- the D2 and D4 references are now AD-2 and AD-1;
- the standalone Linux bench CLI is dropped (LD-8, D7);
- the probe parsers have one home, `desktop/node` (X42).

**Roles and runtime.**
- **The Dell** (a system service as user `asom`) is the program's best lender (PA while awake).
- **The Deck** runs in USER mode from a `$HOME` tarball. It lends docked, on AC, with no game running; Game Mode lending only behind the D28 invisible-lending opt-in; **never on battery**; **never with a block lock**.
- Both borrow through the owner CLI.
- The runtime is the shared JVM node with a jlinked Temurin 21, x86_64 and aarch64.

**File tree** (additions to §2; condensed from `linux.md` §10.2):

```
desktop/node/                                  # :node (Linux host)
  src/main/kotlin/xyz/mdhv/asom/desktop/linux/
    LinuxPlatform.kt                           # DesktopPlatform impl (ServiceLoader)
    host/{HostMode,Paths,OsRelease,SteamOsPolicy}.kt   # Deck rules: never a block lock; 2 s game grace; battery hard NO
    control/{ControlServer,ControlClient}.kt   # AF_UNIX in $XDG_RUNTIME_DIR (0700); SO_PEERCRED both ways (a user name [LF25])
    dbus/MiniDbus.kt                           # read-only system-bus client (LD-12): SASL EXTERNAL, AddMatch, PrepareForSleep
    power/{SleepWatcher,Inhibitor}.kt          # delay lock always while SERVING; block lock only where polkit allows (D28)
    probes/{Power,Thermal,Memory,Cpu,Gpu}Probe.kt   # sysfs/procfs; amdgpu gpu_busy_percent minus own fdinfo
    net/{InterfaceSelector,LanFingerprint,FirewallDoctor}.kt   # (DL6) never applies firewall rules, prints them
  src/test/resources/fixtures/sysfs/<host>/    # deck-oled, deck-lcd, dell, ci-vm (synthetic until the owner captures real ones)
desktop/packaging/linux/
  jlink-modules.txt  build-app-image.sh  install.sh  uninstall.sh  nfpm.yaml
  systemd/asom.service  systemd/asom-user.service   # both shipped DISABLED; StandardOutput=null; LimitCORE=0; MemorySwapMax=0
  sysusers.d/asom.conf  polkit/50-asom-inhibit.rules   # the rule grants inhibit-block-sleep to user asom only (D28)
  scripts/postinstall.sh  scripts/preremove.sh         # never enable or start
  test/{distro-matrix,systemd-vm,journal-hygiene,netns-weakhost}.sh
desktop/docs/{LINUX,STEAM_DECK,DEVICE_CHECKLIST_LINUX}.md
.github/workflows/desktop-linux.yml
```

**CI (`desktop-linux.yml`; triggers `desktop/**`, `core/**`, `server/**`, `gradle/**`):**

| Job | Runner | Commands (core) | Proves | Does not prove |
|---|---|---|---|---|
| `desktop-jvm` (JDK 17, 21) | `ubuntu-24.04`, `ANDROID_HOME=""` | `./gradlew -p desktop desktopTest`; the isolation checks | compile + unit tests; root untouched | anything OS-integrated |
| `desktop-jvm-arm` | `ubuntu-24.04-arm` | same | aarch64 | — |
| `native-linux` (DL4) | `ubuntu-22.04` (glibc baseline), `ubuntu-24.04-arm` | `desktop/native/build-linux.sh` (CPU `GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL`, Vulkan) | the JNI builds | speed; drivers |
| `engine-smoke` (DL4) | `ubuntu-24.04` | `asom-node --self-test --engine=cpu --model=$TINY_GGUF`; Vulkan on Mesa's software driver | generation, cancel, RAM guard | GPU paths, timing |
| `package-linux` | `ubuntu-24.04`, JDK 21 | `build-app-image.sh`; `nfpm package -p deb`/`-p rpm`; `dpkg-deb -c`, `rpm -qlp` layout checks | packaging | signatures (owner, offline) |
| `install-matrix` | containers `ubuntu:22.04`, `ubuntu:24.04`, `ubuntu:26.04`, `fedora:latest`, `archlinux:latest` | `distro-matrix.sh` | install, uninstall, `--self-test` in the packaged runtime | systemd; **SteamOS itself** (Arch is a userland proxy only) |
| `systemd-vm` | the runner VM (`ubuntu-24.04`, `ubuntu-26.04`) | `systemd-analyze verify`; `systemd-vm.sh`; `journal-hygiene.sh` | units disabled after install; the service runs as `asom`; the journal holds no token or ledger row; the delay lock while SERVING; the block lock refused without the polkit rule | real suspend/resume; real logind |
| `mesh-netns` (M1, after promotion) | `ubuntu-24.04` with `sudo` | the design's M1 gate 3 suite; `netns-weakhost.sh` | the multi-node faults, row counts (L-L15, L-L16), weak-host exposure (C18), desktop quiescence | overlay, relays, Wi-Fi |

**Gates** (full expected output in `linux.md` §10.3; the essentials):

| Step | When | Gate (command → expected) | Estimate |
|---|---|---|---|
| **DL0** skeleton + isolation | now | `./gradlew -p desktop desktopTest` → `BUILD SUCCESSFUL`, 0 failures (JDK 17 container, no SDK); `grep -c desktop settings.gradle.kts` → `0`; `./gradlew -p desktop buildEnvironment \| grep -c com.android` → `0` with the SDK present; root test count unchanged; `git diff --exit-code …` → exit 0; `asom status --json` → `{"host":"foreground","fsm":"OFF","listeners":[],"locks":[],…}` | 1–1.5 |
| **DL1** probes + governor | now | `./gradlew -p desktop :node:test --tests '*Probe*' --tests '*Fsm*'` → 0 failures; the exhaustive FSM test prints `transitions exercised: 36/36` and fails if a law exercised nothing; LP-2 and Deck-grace vectors pass | 1.5–2.5 |
| **DL2** host integration | now | `MiniDbusVectorsTest`, `SleepWatcherIT` against a private `dbus-daemon` (`ASOM_REQUIRE_DBUS=1`: a missing binary fails the job); `systemd-analyze verify …/asom.service` → no output; `journal-hygiene.sh` → `PASS: 0 token matches, 0 ledger rows` (CI-ONLY); forked-JVM SIGKILL test of the JSONL sink | 2–3 |
| **DL3** packaging | now | `build-app-image.sh --arch x86_64` → `app image: build/asom-desktop-<ver>-linux-x86_64`; `nfpm package … -p deb` → `created package: …_amd64.deb`; `distro-matrix.sh` → `self-test: native OK (none), ES256 OK, TLS1.3 pinned handshake OK` per distro; after `apt install`, `systemctl is-enabled asom` → `disabled` | 1.5–2.5 |
| **DL4** engine port | D-v2 | JNI artefacts for {x86_64, aarch64} × {cpu, vulkan}; `generated 16 tokens (cpu)`; `cancel latency < 1000 ms (cpu)`; `MODEL_OOM` vector; **NOV: Deck and Dell 8B decode/prefill, CPU vs Vulkan, 10-min sustained, replacing design §7.1's estimates** | 2.5–4 |
| **DL5** bench inside the node | D-v2 (after v2 P6) | `asom bench --plan ci --allow-virtual --yes=ci` → an `asom.bench/1` document with the banner `VIRTUALIZED — NOT DEVICE EVIDENCE`; M04/M05 vectors on the packaged runtime | 0.5–1 |
| **DL6** mesh-1 integration | M1 | M1 gate 3 in network namespaces with row counts on every node; `netns-weakhost.sh` → `without rule: connected … / with rule: timeout → PASS`; W08 against the packaged node in both roles; desktop quiescence: `0 packets captured` on port 11436 over 3 min (the 30-min run is the device gate) | 2–3 |
| **DL7** device-validation support | each phase | `desktop/docs/DEVICE_CHECKLIST_LINUX.md`; the owner's pasted outputs | 0.5–1 |

**NDV (the owner runs these; exact commands in `linux.md` §10.4):**
- **Deck:**
  - DV-D1: Steam games run as `deck`;
  - DV-D2: the Game Mode user service across mode switches;
  - DV-D3: linger survives updates;
  - DV-D4: **a real sleep from Game Mode with only a delay lock, no fake sleep** [LF05];
  - DV-D5: GPU counters;
  - DV-D6: the Vulkan heap budget for 8B;
  - DV-D7: Plasma idle suspend;
  - DV-D8: firewall state;
  - DV-D9: game-start drain ≤ 2 s and stutter;
  - DV-D10: Tailscale TUN and log opt-out persistence;
  - DV-D11: `swap.max = 0`.
- **Dell:**
  - DV-L1: inventory (OS, GPU, TPM);
  - DV-L2: USER-mode polkit;
  - DV-L3: a lender at the GDM login screen serves only with "serve while logged out" (M1 gate 10);
  - DV-L4: `systemd-creds` sealing;
  - DV-L5: 8B decode/prefill.
- **Both:** real suspend/resume; hwmon labels; thermal thresholds; fan noise.

**Estimate:** 12–19 engineer-weeks (DL0–DL7). The design's §9.4 counts DL0–DL5 (9–14.5) under D-v2 and DL6 (2–3) inside M1.

---

## 4. Windows 10/11 (`desktop/packaging/windows`)

**Source:** `platforms/windows.md` §3–§10.
**r3 amendments:**
- `:node-desktop` is `:node-core`;
- the Windows items of the platform engineering package are ruled together as D27;
- whether Windows lends in M1 is D28, and if it does, M1 gate 13 applies;
- the owner CLI's ACL-only identity (W-D11) is D25(a).

**Roles and runtime.**
- The owner CLI borrows (M1 or M1b).
- PA on AC desktops (M1 only if the Dell runs Windows); laptops conditional; **never in Modern Standby**.
- User mode (a logon task) by default; service mode opt-in through Apache procrun in-process (`StartMode=jvm`) as `NT SERVICE\asom`.
- Kotlin + JNA 5.19.x adapter `winplatform`; jlinked Temurin 21 (x64, arm64).
- Keys: T2 CNG Platform Crypto Provider (TPM, P-256 pending S-W1), T1 Software KSP; T0 file for CI only.
- The listener never starts before a consented, elevated allow rule exists (C16).

**File tree:** `windows.md` §10.1, verbatim, with `:node-desktop` read as `:node-core`:

```
desktop/packaging/windows/
  README.md
  winplatform/                      # :packaging:windows:winplatform (pure JVM; JNA)
    src/main/kotlin/xyz/mdhv/asom/desktop/win/
      WinPlatform.kt WinPaths.kt NodeMutex.kt
      jna/{NCrypt,PowrProf,Kernel32Power,Pdh,Wts,UserInput,Wer}.kt
      keys/{NcryptNik,FileNik,NikTierSelector}.kt
      power/{WinPowerPort,SuspendWatcher}.kt  presence/WinPresencePort.kt  gpu/GpuEngineProbe.kt
      thermal/ThermalZoneProbe.kt  net/{InterfaceEligibility,FirewallGate,FirewallCommand}.kt
      ctl/WinControlSocket.kt  service/ServiceEntry.kt  tray/TrayUi.kt (placeholder, after D14 part A)  doctor/WinDoctor.kt
    src/test/kotlin/.../{fakes/, NikTierSelectorTest, FirewallGateTest, PresenceLawsTest, StdoutSecretTest,
                         windows/ (@EnabledOnOs(WINDOWS): DpapiIT, SoftwareKspIT, PowerRequestIT, PdhIT, AfUnixAclIT, PcpIT)}
  native/{CMakePresets.json, build-llama-jni.ps1}
  service/procrun-parameters.reg.template
  wix/{asom.wxs, AppImage.wxs, License.rtf}
  scripts/{build-app-image,sign,verify-signatures,build-msi,smoke-install,smoke-two-node,collect-evidence}.ps1
  winget/manifests/a/asystemofcells/asom/0.0.0-template/*.yaml
  ci/desktop-windows.yml            # canonical copy of .github/workflows/desktop-windows.yml
  docs/{WINDOWS_NODE,DEVICE_CHECKLIST_WINDOWS}.md
```

Outside the directory: one `include(":packaging:windows:winplatform")` line in `desktop/settings.gradle.kts`, the workflow file, and `.gitattributes` lines.

**CI (`desktop-windows.yml`):**

| Job | Runner | Commands | Proves | Stays NDV/NOV |
|---|---|---|---|---|
| `lab-windows` (W0; JDK 17, 21) | `windows-2025` | `./gradlew.bat -p lab labTest --stacktrace`; an eol check that every file under `lab/conformance` carries `-text` | the lab on Windows JDKs: charset and CRLF traps (C17) | — |
| `winplatform` (W1–W2) | `windows-2025`, `windows-11-arm` | `./gradlew.bat -p desktop :packaging:windows:winplatform:test` | real DPAPI, Software KSP P-256, power request (`powercfg /requests`), PDH, AF_UNIX DACL | TPM (S-W1), real presence and GPU |
| `package-and-smoke` (W3–W6) | `windows-2025` | `build-llama-jni.ps1`; `build-app-image.ps1`; `build-msi.ps1 -Arch x64`; `smoke-install.ps1`; `smoke-two-node.ps1` | JNI builds (CPU runs; Vulkan compiles only), MSI, service lifecycle, listener and firewall gate, a two-node borrow each way with row asserts, W08 on the Windows JDK | GPU execution, SmartScreen / Smart App Control, real firewall profiles, Tailscale |
| `sign` | `windows-2025`, `main`/tags only | `sign.ps1`; `verify-signatures.ps1` | every PE and the MSI signed | reputation behaviour (NOV) |

**Gates** (`windows.md` §10.3, essentials):

| Step | When | Gate | Estimate |
|---|---|---|---|
| **W0** | now | `./gradlew.bat -p lab labTest` on JDK 17 and 21 → `BUILD SUCCESSFUL` with **the same test count as the Linux lab lane**; the eol check prints nothing; an injected default-charset regression on a branch **fails** the JDK 17 lane | (in the lab's estimate) |
| **W1** | now | the Linux container runs `…:winplatform:test` → `BUILD SUCCESSFUL`, Windows ITs **skipped**; `windows-2025` runs the same → ITs **run and pass** (`powercfg /requests` contains `asom: lending compute`) | 3–5 (with W2) |
| **W2** | now | `SoftwareKspIT`: 1,000 sign/verify round trips → 0 failures; `PcpIT` → `SKIPPED: no TPM`; S-W1 on the owner's machine → NDV | (above) |
| **W3** | D-v2 | `asom-llama-jni.dll` + backend DLLs with sha256 lines; `TinyModelGenerationIT` output equal to the Linux lane's vector; Vulkan compiles, **execution NDV** | 1.5–3 |
| **W4** | D-v2 | `build-msi.ps1` → `asom-<v>-x64.msi`; signed builds: `0 files NotSigned/HashMismatch`; unsigned PR builds labelled `UNSIGNED — not for release` | 2–3 (with W5) |
| **W5** | D-v2 | `smoke-install.ps1`: service `Running`; `asom-cli status` → `mode=service state=OFF listener=none keyTier=os-keystore`; no listening rows; `FIREWALL_RULE_MISSING` before consent; exactly one row `<NIC IPv4>:11436` after; `FIREWALL_BLOCK_RULE_PRESENT` when a block rule is injected; clean uninstall | (above) |
| **W6** | D-v2 / M1 | `smoke-two-node.ps1`: A→B and B→A borrows; L-L14, L-L15, L-L16 row asserts; W08 green. Labelled **LAB/CI evidence — not device evidence** | 1.5–2.5 |
| **W7** | D-v2 | winget YAML valid against schema 1.12.0; the device checklist lists every NDV/NOV item | 1–1.5 |
| **W8** (owner) | D-v2 / M1 | S-W1…S-W8; the sleep/lid/Modern Standby matrix; Vulkan 8B measurement; Tailscale unattended mode and log opt-out capture; a signed release under Smart App Control | NDV/NOV |

**If Windows lends in M1** (D28), M1 gate 13 applies:
- W08 on the Windows JDK;
- the S-W1 and S-W2 outcomes recorded;
- the firewall-consent and Tailscale-unattended checks;
- quiescence (gate 5) on a Windows borrower by pktmon on the Wintun adapter, **or left open**. It is never passed by a weaker method.

**NDV/NOV:**
- the TPM key tier (S-W1) and the service-account key (S-W2);
- sleep, lid and Modern Standby;
- battery saver;
- real GPU counters and execution;
- SmartScreen and Smart App Control;
- Tailscale and its `TS_NO_LOGS_NO_SUPPORT` effect;
- real firewall profiles;
- winget acceptance.

**Estimate:** 10–16 engineer-weeks (Windows-specific; additive to the Linux work; `windows.md` §10.4).

---

## 5. macOS (`desktop/packaging/macos`)

**Source:** `platforms/macos.md` §3–§10.
**r3 amendments:**
- ES256, not Ed25519 (C2);
- the helper lives under `desktop/packaging/macos`, not `apple/`;
- r2's 4–6-week estimate is replaced;
- **the Swift conformance lane (MC0) moves to the Apple track (§6)**, with its jobs in `apple-ios.yml`, not `desktop-macos.yml`;
- whether a Mac lends in M1 is D28, and if it does, gate 14 applies.

**Roles and runtime.**
- The owner CLI borrows.
- PA on Apple-silicon desktops while logged in; laptops only on AC with the lid open; never asleep; never after a FileVault restart until unlock.
- An `SMAppService` LaunchAgent by default; an opt-in LaunchDaemon only at M2 after S-M5.
- The shared JVM node, plus three Mac-only pieces:
  1. `macplatform` (pure Kotlin, tested on Linux with a fake helper);
  2. `asom-mac-helper` (Swift; the Secure Enclave via CryptoKit, `SMAppService`, IOKit sleep and assertions, probes; JSON-Lines over inherited pipes);
  3. the hardened C launcher `asom-node` (environment scrubbed, fixed argv, the attach mechanism disabled).
- Jlinked Temurin 21 aarch64 without `java.instrument`, `jdk.attach`, `jdk.jdwp.agent` or `jdk.management.agent`.
- Keys: T2 Secure Enclave P-256 through the helper, with the blob in the Team-ID group container (pending S-M1); T0 file fallback; the login keychain is rejected.

**File tree:** `macos.md` §10.1, with `:node-desktop` read as `:node-core`, and with the `apple/` subtree owned by §6:

```
desktop/packaging/macos/
  README.md
  macplatform/            # :packaging:macos:macplatform: MacPlatform, MacPaths, MigrationGuard, NodeLock, helper/{HelperProcess,
                          #   HelperProtocol}, keys/{SecureEnclaveNik,FileNik,NikTierSelector}, power/, presence/, thermal/, gpu/,
                          #   net/InterfaceEligibility, ctl/MacControlSocket (getpeereid both ways), svc/ServiceRegistration,
                          #   tray/TrayCompanion (placeholder, after D14 part A), doctor/MacDoctor; tests incl. FakeHelper and mac/ ITs
  helper/                 # Swift package: HelperProtocol (builds on Linux) + asom-mac-helper (macOS only)
  helper-protocol/        # SCHEMA.md + vectors/*.jsonl shared by the Kotlin and Swift tests
  launcher/               # asom_launcher.c, CMakeLists.txt, probe/ (env-injection test)
  native/                 # CMakePresets.json (macos-arm64-metal), build-llama-jni.sh
  resources/              # Info.plist.template, LaunchAgent/LaunchDaemon plists, entitlements (node: allow-jit + app group;
                          #   helper: empty), distribution.xml, ASOM.icns
  scripts/                # build-runtime, build-app-image, sign, verify-signing, build-pkg, build-dmg, notarize, smoke-app,
                          #   smoke-env-injection, smoke-two-node, demo-keychain-cli-weakness, probe-container-denial, collect-evidence
  homebrew/Casks/asom.rb.template
  ci/desktop-macos.yml
  docs/{MACOS_NODE,DEVICE_CHECKLIST_MACOS}.md
```

**CI (`desktop-macos.yml`):**

| Job | Runner | Commands | Proves | Stays NDV/NOV |
|---|---|---|---|---|
| `macplatform` (MC1–MC2) | `macos-latest` (macOS 26, M1 VM) | `swift test --package-path desktop/packaging/macos/helper`; `./gradlew -p desktop :packaging:macos:macplatform:test`; `demo-keychain-cli-weakness.sh` | helper protocol; `pmset -g assertions` shows the assertion while held and not after `kill -9`; the keychain CLI weakness demonstrated | Secure Enclave (`se=false` on runners), real presence and thermal |
| `package-and-smoke` (MC3–MC4, MC6) | `macos-latest` | `build-llama-jni.sh`; `build-runtime.sh`; `build-app-image.sh`; `smoke-app.sh`; `smoke-env-injection.sh`; `smoke-two-node.sh` | Metal build + CPU tiny-model equal to the Linux vector; `forbidden modules: none`; no listener until mesh listen, then exactly one on `<en0 IPv4>:11436`; the stock launcher `PROBE-RAN` vs `asom-node` `PROBE-NOT-RUN`; two-node borrows with L-L14, L-L15, L-L16; W08 | Metal speed, Local Network prompt, sleep/lid/FileVault |
| `sign-notarize` (MC5) | `macos-latest`, `main`/tags, environment `apple-signing` | `sign.sh`; `verify-signing.sh`; `build-pkg.sh`; `build-dmg.sh`; `notarize.sh` | `status: Accepted`; `stapler validate` works; `spctl … accepted`; the S-M3 selftest with library validation on and `allow-jit` only | Gatekeeper first-launch UX (NOV) |
| `macos27-probes` | `xcode-27`, `main` | `probe-container-denial.sh` | an unsigned reader is refused the group container (S-M6) | real user flows |

**Gates** (`macos.md` §10.3, essentials):

| Step | When | Gate | Estimate |
|---|---|---|---|
| **MC1** | now | Linux container: `./gradlew -p desktop :packaging:macos:macplatform:test` → `BUILD SUCCESSFUL`, `mac/*IT` skipped; `macos-latest`: ITs run and pass | 2–3.5 (`macplatform` in total) |
| **MC2** | now | `swift test --package-path desktop/packaging/macos/helper` → 0 failures; `printf '{"op":"hello","v":1}\n' \| asom-mac-helper serve` → `"ok":true`, `"se":false` on the runner; assertion held, then released after `kill -9`; `read without prompt: yes` (the keychain weakness, AM14) | 1.5–2.5 |
| **MC3** | D-v2 | `libasom-llama-jni.dylib` with sha256 lines; `otool -L` check `ok`; CPU tiny-model equal to the Linux vector; Metal informational | 1–1.5 |
| **MC4** | D-v2 | `forbidden modules: none`; `mode=dev state=OFF listener=none keyTier=file`; no listening rows, then exactly one; `stock-launcher: PROBE-RAN` / `asom-node: PROBE-NOT-RUN` | 1–2 (launcher) + part of packaging |
| **MC5** | D-v2 (owner secrets) | `0 findings`; `status: Accepted`; `accepted … source=Notarized Developer ID`; `selftest ok` with library validation on (else the needed entitlement is recorded and M-D4 re-put to the owner) | 2–3 (packaging) |
| **MC6** | D-v2 / M1 | two-node borrows with row asserts; W08 green; **LAB/CI evidence — not device evidence**, recording whether Local Network privacy intervened | 1.5–2 (smokes) |
| **MC7** | D-v2 | `brew style --cask` → `no offenses detected`; install and uninstall via the tap leave no `xyz.mdhv.asom` receipt; the device checklist is complete | 1–1.5 |
| **MC8** (owner) | D-v2 / M1 | S-M1, S-M2, S-M4, S-M7, S-M8; the sleep/lid matrix; FileVault restart; `tailscaled --no-logs-no-support` + Headscale capture; **8B on the owner's Mac replacing design §7.1's A12d estimate** | NDV/NOV |
| **MC9** (M2, optional) | M2 | S-M5 (Metal and the Enclave from a daemon); serving after logout | +2–3 |

**If a Mac lends in M1** (D28), M1 gate 14 applies:
- W08 on the macOS JDK;
- the S-M1 and S-M3 outcomes recorded;
- the Local Network check (the first LAN dial shows the prompt; a denial writes a `local-network-denied` DIAL row);
- idle 30 min on AC (gate 6) on a Mac mini;
- gate 10, which is trivially true in agent mode;
- quiescence by a root capture on the Mac covering every `en*` and `utun*` interface, **or left open**.

**NDV/NOV:**
- the Secure Enclave from the helper (S-M1);
- library validation with the hardened launcher (S-M3);
- Metal correctness and speed;
- the Local Network prompt and Login Items approval;
- sleep, lid, Power Nap and FileVault;
- the Tailscale variants and their log upload;
- the Application Firewall;
- MacBook thermals.

**Estimate:** 11–17 engineer-weeks (mode A, the full recommendation); a minimal cut of 7–10 (T0 key only, a stock launcher rated "same-user compromise = node compromise"); +2–3 for the optional mode-B daemon. MC0 is counted in the Apple track.

---

## 6. Apple: the Swift lane and iOS/iPadOS (`apple/`)

**Source:** `platforms/ios.md` §3–§10 and `macos.md` §7.4.
**r3 amendments:**
- **D24(a) is the recommendation:**
  - one app, `xyz.mdhv.asom` (display name "asom"), hosts the benchmark, pairing, the verifier and (iPad, after v3) the lend screen;
  - **there is no separate M3 benchmark phase**, and M4 does not wait for it;
  - the iOS section's `AsomBench/` directory is therefore `apple/AsomApp/`, target `asom`.
- The Swift-lane jobs live in `apple-ios.yml`.
- ES256 only; Swift producers normalise to low-S (C2).
- App Attest and DeviceCheck are rejected.
- swift-certificates and swift-asn1 enter at M4 (CD-D, D23).

**Roles and runtime.**
- **iPhone:** a requester (M4, placed by D24), the benchmark on the owner's devices only (it feeds no router), a manifest subscriber. **Never** PA or PF.
- **iPad:** the same, plus PF after v3 (M5, D16).
- Both are **borrow-only holons** (no cross-app daemon on iOS).
- Swift 6: an independent implementation written from the spec prose and pinned by the lab's vectors; never linked into the Mac node.
- Network.framework with one verify block running `verifyPeerChain` (C4).
- The Secure Enclave NIK in a Team keychain access group (pending S-A12).
- Byte accounting: plaintext per frame plus a `DataTransferReport` reconciliation at session close (IC-2, [IA07]).

**File tree** (the Swift lane is `macos.md` §7.4's layout; the iOS targets are `ios.md` §10.2's, renamed per r3):

```
apple/
  Package.swift                      # swift-tools 6.0; platforms macOS 15 / iOS 17; swift-crypto only .when(platforms: [.linux])
  README.md                          # the boundary: "ES256 only"; "never linked into the Mac node"
  Sources/AsomJSON/                  # strict tokenizer + JCS integer profile (LAB_SPEC.md §4.2), hand-written (not JSONSerialization)
  Sources/AsomDSSE/                  # PAE, strict base64, strict 91-byte SPKI, ES256 verify, per-export sign, DER<->raw, low-S
  Sources/AsomManifest/              # typed decoder, the r3 verifier (LAB_SPEC.md §4.6), projections, the M05 renderer
  Sources/AsomBenchCore/             # M04 derivation (checked integer arithmetic)
  Sources/asom-conformance/main.swift  # prints "<id> ok" | "<id> reject <CODE>" per vector (LAB_SPEC.md §3.3 lines format)
  Tests/AsomConformanceTests/        # reads lab/conformance via ASOM_CONFORMANCE_DIR
  # M1b (M4), per ios.md §10.2:
  Sources/AsomContract/ Sources/AsomLedger/ Sources/AsomPlatform/ Sources/AsomWire/ Sources/AsomTransport/ Sources/AsomRequester/
  # M2+ (M5): Sources/AsomLender/
  AsomEngine/                        # SEPARATE package (binaryTarget llama.xcframework; never built on Linux); llama.cpp.pin
  AsomApp/                           # r3: the one asom app (ios.md's AsomBench/): project.yml (XcodeGen), entitlements,
                                     #   PrivacyInfo.xcprivacy, App/, Features/{Run,Export,Verify,Ledger,Peers,Lend}, Tokens/TokenSeam.swift
  ci/{lint-info-plist,lint-entitlements,lint-linked-frameworks,fetch-tiny-model}.sh  apple-ios.yml
  docs/{IOS,DEVICE_CHECKLIST_IOS}.md
```

**CI (`apple-ios.yml`; triggers `apple/**`, `lab/conformance/**`):**

| Job | Runner | Commands | Proves | Does not prove |
|---|---|---|---|---|
| `apple-swift-lane` | `macos-latest` and `ubuntu-latest` in container `swift:6.1-noble` (pinned by digest) | `swift test --package-path apple`; `swift run --package-path apple asom-conformance M01,M02,M03,M05,M06 lab/conformance > swift.lines` | the pure targets on CryptoKit and on swift-crypto | anything iOS-specific |
| `jvm-lines` | `ubuntu-latest`, JDK 17, `ANDROID_HOME=""` | `./gradlew -p lab :conformance-runner:run --args='lines M01,M02,M03,M05,M06' --quiet > jvm.lines` | the JVM verdicts | — |
| `lane-diff` | `ubuntu-latest` | `diff jvm.lines swift-lines-macos/swift.lines && diff jvm.lines swift-lines-linux/swift.lines` | per-vector agreement of two implementations | independence (only the recorded authorship does, `LAB_SPEC.md` §4.10) |
| `ios-package-sim` | `macos-latest` | `xcodebuild -scheme AsomKit-Package -destination 'platform=iOS Simulator,name=iPhone 17' test` | package targets in the simulator | Enclave; file-protection enforcement |
| `ios-app-sim` (M1b) | `macos-latest` | `xcodegen generate --spec apple/AsomApp/project.yml`; `xcodebuild … test` on the iPhone 17 and iPad Pro 11-inch (M5) simulators | the app and UI smoke ("no network before consent") | real devices, background suspension |
| `ios-device-build` + `ios-lints` (M1b) | `macos-latest` | `xcodebuild -sdk iphoneos -configuration Release CODE_SIGNING_ALLOWED=NO build`; the three lints | an arm64 device compile; no `NSBonjourServices`, no `UIBackgroundModes`, no `DeviceCheck`/`CloudKit`/`Firebase*` | install, signing |
| `engine-sim-smoke` (M1b) | `macos-latest` | the XCFramework built at the pin; a tiny GGUF by sha256 | `generated 16 tokens (cpu, simulator)` | Metal, speed, GPU revocation |
| `export-roundtrip` (M1b) | `macos-latest` → `ubuntu-latest` | the simulator's `ci` plan writes a signed file; the JVM lab verifier and `xcheck.py` check it | `FILE: signed, signer unverified (not compared)` and, with the fingerprint, `PINNED_BY_FINGERPRINT(typed)` | device numbers |
| `w08-sim` (M1b; M2+ server role) | `macos-latest` | XCTest dials the JVM hostile node on `127.0.0.1` | `0 accepted bad chains; 0 ClientHellos with pre_shared_key; CertificateVerify in 100% of sessions` | Local Network privacy (the simulator ignores it) |
| `ledger-durability` | `macos-latest`, `ubuntu-latest` | a forked process is SIGKILLed at each durability point | rows intact | power loss (never claimed) |

**Gates:**

| Step | When | Gate | Estimate |
|---|---|---|---|
| **I0 = MC0 = L0.7** (the Swift lane) | now (roadmap §14 item 7; ships nothing) | Linux container: `swift test --package-path apple` → `Executed N tests, with 0 failures`; `macos-latest` the same on CryptoKit; `lane-diff` → no output, exit 0 for M01–M03, M05, M06; `ios-package-sim` → `** TEST SUCCEEDED **`. The vectors stay **self-oracled** until `PROGRESS.md` records that this lane's author had no access to the generator source. Already shown for the DSSE/ES256 layer by spike IS1: 37 per-vector lines identical to the JCA transcript with swift-crypto 3.15.1 and 4.5.2 (on the r0 vectors; r3 regenerates them) | 3–5 (shared with macOS) |
| **I1** foundations | M1b (D14 part A, D15, D24; iOS-D3) | `swift test --filter 'AsomLedgerTests\|AsomConformanceTests'` → 0 failures incl. every W01b vector; `ledger-durability` → `rows intact`; `xcodegen` + simulator tests → `** TEST SUCCEEDED **`; `ios-device-build` → `** BUILD SUCCEEDED **`; lints `PASS`; `engine-sim-smoke` → `generated 16 tokens (cpu, simulator)` | 3–4 |
| **I2** app (bench, export, verifier, ledger) | M1b | `export-roundtrip` as above; UI smoke `no network before consent: PASS`; NDV DV-I1…DV-I7 | 4–6 |
| **I3** requester (M4) | M1b, after M1; S-A2, S-A9 (Network.framework column), S-A10, S-A12 on a device | W04–W07 families 0 failures; `w08-sim` client role; `TransferAccounting`: `Σ frame rows + overheadBytes == DataTransferReport.sentTransportByteCount` on a scripted loopback session (CI-ONLY; hardware is NDV); NDV DV-I8…DV-I12 | 6–10 |
| **I4** iPad lender (M5) | M2+ (D16) | W07p PF vectors and decision-table vectors 0 failures; `w08-sim` server role; NDV DV-I13…DV-I17 | 4–7 |
| **I5** device-validation support | each phase | `docs/DEVICE_CHECKLIST_IOS.md` | 1–2 |

**NDV** (`ios.md` §10.5): the Secure Enclave NIK and signing; keychain access-group sharing between two signed apps (S-A12); `sec_identity` from the software leaf (S-A2); Network.framework knobs on hardware; the Local Network prompt, denial and re-grant; Metal throughput and thermal behaviour; **backgrounding during a Metal decode (no crash)**; the ~30 s grace in practice; Tailscale inbound and its log upload (none documented as switchable [IF31]); Low Power Mode; App Review. **iPad lending as a lender (M5)** stays NDV until DV-I13…DV-I17 pass.

**Estimate:** 21–34 engineer-weeks, or 18–29 without the Swift lane shared with macOS. The design's §9.4 counts I1–I3 (13–20) in M1b and I4 (4–7) in M2+.

---

## 7. Ubuntu Touch (`ubuntu-touch/`)

**Source:** `platforms/ubuntu-touch.md` §3–§10.
**r3 amendments:**
- UT-0 is authorised by roadmap §14 item 7 (ships nothing);
- UT-1's placement is D24 (M1b);
- the `self-ui:` caller label is D25(a);
- QML needs D14 part A (IC-7 names QML/Lomiri);
- the PF exception applies to UT-2 (LAB_SPEC §6.5);
- quiescence (DV-UT07) passes by a gateway capture only with the overlay off.

**Roles and runtime.**
- **A foreground-only requester and verifier (UT-1):** its own chat screen is its only caller; no BYOK keys, no cloud tier; **never PA, never a cross-app service** (Lomiri freezes unfocused apps, and repowerd suspends about 4 s after display-off).
- **Runtime:** the shared JVM code inside the click as a jlinked Temurin 21 aarch64 runtime (39 MB measured [UF37]), driven by a thin QML/Lomiri UI through a C++ `QProcess` bridge over a private stdio channel `asom-ut-ctl/1`.
- **Conditional on the on-device spike S-UT1.** If it fails, a Rust single-home-lender core is the fallback (+8–12 weeks).
- **Keys:** a T0 file in the app-private directory.

**File tree** (`ubuntu-touch.md` §10.2; `jvm/` maps `../../desktop/node-core` after §2's split):

```
ubuntu-touch/
  README.md  clickable.yaml (framework ubuntu-touch-24.04-1.x)  CMakeLists.txt  manifest.json.in (xyz.mdhv.asom.ut)
  asom.apparmor.in                 # networking, keep-display-on, camera, content_exchange_source; template ubuntu-sdk; never unconfined
  asom.desktop.in  assets/asom.svg
  qml/{Main,StatusPage,BorrowPage,PeersPage,PairPage,LedgerPage}.qml  qml/tokens/Tokens.qml
  plugin/{plugin.cpp,nodeprocess.*,displaykeeper.*,lifecycle.*}      # QProcess bridge; 1 MiB line cap; sha256-checked runtime and jar
  jvm/settings.gradle.kts          # maps ../../core/*, ../../lab/* and ../../desktop/node-core by projectDir; NEVER includeBuild("..")
  jvm/build.gradle.kts             # build-dir redirection; utTest; utNodeJar
  jvm/ut-host/src/main/kotlin/xyz/mdhv/asom/ut/{Main,ControlChannel,NodeLifecycle,UtPaths,SelfTest,UiProjection,ErrorMapping}.kt
  runtime/{temurin.lock,jlink-modules.txt,jlink.sh,jvm.options}
  conformance/utc/{INDEX.json,VERSION,UTC01..UTC05}.json
  apparmor-ci/{policy.lock,check-groups.py,make-profile.sh,run-approx.sh}
  tests/qml/tst_StatusPage.qml  tests/smoke/arm64-selftest.sh
  docs/{UBUNTU_TOUCH,DEVICE_CHECKLIST_UT}.md
.github/workflows/ubuntu-touch.yml
```

**CI (`ubuntu-touch.yml`; images pinned by digest):**

| Job | Runner / container | Commands | Proves | Cannot prove |
|---|---|---|---|---|
| `ut-jvm` (JDK 17, 21) | `ubuntu-latest` | `./gradlew -p ubuntu-touch/jvm utTest` with `ANDROID_HOME=""` | the UT host and UTC01–UTC05; stdout hygiene | the device |
| `ut-runtime` | `ubuntu-latest`, JDK 21 | `ubuntu-touch/runtime/jlink.sh` | a cross-jlinked aarch64 runtime ≤ 45 MB with max `GLIBC_2.17` | Halium |
| `ut-click` | `clickable/ci-ut24.04-1.x-arm64:8.10.0` | `cd ubuntu-touch && clickable build`; `check-groups.py` | the arm64 click builds; click-review reports no errors; common policy groups only | install, runtime confinement |
| `ut-qml` | `clickable/ci-ut24.04-1.x-amd64:8.10.0` | `clickable build --arch amd64 && clickable test` | QML against a fake node | the Lomiri shell |
| `ut-arm64-smoke` | `ubuntu-24.04-arm` + `clickable/arm64-ut24.04-1.x-arm64` | `tests/smoke/arm64-selftest.sh` | **the bundled runtime starts in the real UT 24.04 userland**; the self-test (TLS 1.3 in memory, ES256, JCS, JSONL `force`) passes | Halium, libhybris, confinement |
| `ut-apparmor-approx` | `ubuntu-24.04-arm` | `apparmor-ci/run-approx.sh` | **CI-APPROX — NOT DEVICE EVIDENCE**: the pinned template loads and the self-test runs under `aa-exec` | the device kernel's AppArmor |
| `ut-canary-2604` (non-gating) | `clickable/ci-ut26.04-1.x-arm64` | `clickable build` | early warning for the next series | anything shipped |

**Gates** (`ubuntu-touch.md` §10.3):

| Step | When | Gate | Estimate |
|---|---|---|---|
| **UT0.1** | now (after S2) | `./gradlew -p ubuntu-touch/jvm utTest` on JDK 17 and 21 → `BUILD SUCCESSFUL`, UTC01–UTC05 and the host classes with 0 failures; `git diff --exit-code -- core server settings.gradle.kts build.gradle.kts` clean | UT-0 in total: 2–3.5 |
| **UT0.2** | now | `jlink.sh` → `rt: <N> MB` (N ≤ 45); `ARM aarch64`; `max GLIBC: 2.17` | (above) |
| **UT0.3** | now | the arm64 smoke → exactly one line `{"selftest":"ok",…,"tls":"TLSv1.3","alpn":"asom-mesh/1",…}` with vector counts equal to the lab's; labelled **LAB / CI — NOT DEVICE EVIDENCE** | (above) |
| **UT0.4** | now | `clickable build` → exit 0; `…_arm64.click` exists; no review errors; `Architecture: arm64`; `check-groups.py` exit 0 | (above) |
| **UT0.5** | now | `clickable test` → 0 failures | (above) |
| **UT0.6** (owner) | when a device exists | **S-UT1:** install; tap Self-test; `sudo dmesg \| grep 'apparmor="DENIED"'` → no denial that breaks it. **NDV.** On failure: `BLOCKED(S-UT1: …)`, and UT-D2 moves to the Rust fallback | — |
| **UT0.7** | now | `run-approx.sh` → `CI-APPROX — NOT DEVICE EVIDENCE`, the self-test line, and the denial list; it fails (never skips) if the profile does not load | — |
| **UT1.1** | M1b | `utTest` + the promoted families on the UT jar in the arm64 lane: W04–W08 (client role), M01–M03, R01–R06, W01b, UTC01–UTC05: 0 failures on JDK 17, JDK 21 and the jlinked arm64 runtime | UT-1: 5–8 |
| **UT1.2** | M1b | the M1 netns suite with one `--profile=ut` requester: the fault list passes; row counts per node (L-L15, L-L16) | (above) |
| **UT1.3** | M1b | DV-UT02–DV-UT11, DV-UT15: NDV until the owner confirms | (above) |

**NDV:**
- DV-UT01: S-UT1 under real confinement;
- DV-UT02: a 1.x click on a 2.x device;
- DV-UT03, DV-UT04: the JVM child freezes on app switch, and state is delivered before `SIGSTOP`;
- DV-UT05: `keepDisplayOn` during a stream;
- DV-UT06: windowed mode and the lock screen;
- **DV-UT07: quiescence, 30 min with the overlay off and a gateway capture; with the overlay on it needs an on-device capture or stays open**;
- DV-UT08: a 20-request script with joined ledgers and no listening socket;
- DV-UT09–DV-UT16 as in `ubuntu-touch.md` §9.

**Estimate:** 7–11.5 engineer-weeks (UT-0 + UT-1); 13–21.5 with the unscheduled UT-2; +8–12 if S-UT1 fails.

---

## 8. Android (existing modules; nothing until V1-close)

**Source:** `platforms/android-mesh.md` §9–§10.
**r3 amendments:**
- **the emulator lane is a new workflow file `.github/workflows/android-mesh.yml`, not a job added to `ci.yml`** (AD-3 and P4; `android-mesh.md` §10.2 proposed editing `ci.yml`);
- targetSdk stays 35 unless D29 rules otherwise;
- CameraX and the `CAMERA` permission are SIGN-OFF items (CD-D, AP-1, D23);
- the cloud-ban switch is AD-1's column, not r2's D4(b1).

**Nothing in this section may touch `app/`, `vault/`, `pairing/`, `ledger/`, `storage/`, `client*/` or `sample-client/` until v1 device validation closes** (directive D-D). No new directory is created now. The only Android-relevant work now is optional lab code: an LNP classifier model with vectors in `lab/mesh-policy` (step L-A1, self-oracled).

**Roles and runtime.**
- The Android phone is the one full holon: it serves its own apps (v1/v2), borrows in M1 (outbound only), and lends after v3 in two opt-in shapes: the lend screen (PF), and charging with "serve while locked" (PA-charging, D16).
- Kotlin on ART; the shared pure-JVM core, promoted as `:core:mesh`.
- New Android modules after validation: `:mesh-android` (Keystore NIK and leaf, the Conscrypt transport with a fresh `SSLContext` per dial, the dialer with `DIAL` rows before the SYN, the listener (lender phase), network classifier, local-network gate, probes, governor, Room `mesh.db`) and `:qr` (CameraX + zxing-core, paste fallback).

**File tree after validation:** `android-mesh.md` §10.2, with the r3 workflow correction:

```
settings.gradle.kts*             # include(":mesh-android", ":qr") INSIDE the existing hasAndroidSdk block (MOD-1, D23; the only root edit, at M1)
gradle/libs.versions.toml*       # zxing-core 3.5.4, CameraX (D23)
app/*                            # AndroidManifest additions (v1.1 dataExtractionRules; M1 CAMERA with uses-feature required=false);
                                 #   Hotspot "Apps | Devices", per-app "May use my other devices" + "Never use the cloud" (AD-1 column);
                                 #   PeersScreen, NodePairingActivity; LendScreen (lender phase)
mesh-android/                    # NEW (M1)
qr/                              # NEW (M1)
ledger/*                         # exportSchema = true; migration v1 -> v2 (CD-14/CD-15 nullable columns; status 0 = intent)
pairing/*                        # meshAllowed, cloudBanned columns
sample-client/*                  # CI-only flavour "t37" (targetSdk 37) for the H5 loopback probe; never released
ci/android/{apk-policy.sh,permissions.allow,enable-kvm.sh}
.github/workflows/android-mesh.yml   # r3: NEW file (ubuntu-24.04, KVM, API 35/36/37 matrix); ci.yml untouched
docs/CLIENT_API.md*  docs/DEVICE_CHECKLIST_MESH_ANDROID.md
```

**Root-file note.** Adding `include(":mesh-android", ":qr")` inside the existing `hasAndroidSdk` block at M1 is a root change made at the owning version's entry, with the D23 sign-off (MOD-1). It is not a scaffold edit. The pure-JVM `jvmTest` set is extended explicitly when `:core:mesh` is promoted.

**CI (`android-mesh.yml`, after V1-close):**

| Check | Runner | Verifiable? | How |
|---|---|---|---|
| unit tests incl. `:mesh-android`, `:qr` | `ubuntu-24.04` (pinned) | yes | `./gradlew :mesh-android:testDebugUnitTest :qr:testDebugUnitTest` |
| APK policy | `ubuntu-24.04` | yes | `ci/android/apk-policy.sh app/build/outputs/apk/debug/app-debug.apk` → `permissions: OK (added since v1: android.permission.CAMERA)`, `gms/firebase: none`, `zipalign16: OK` |
| Room migrations | emulator API 35 | **EMULATOR EVIDENCE** | `./gradlew :ledger:connectedDebugAndroidTest --tests '*Migration1To2*'` |
| ART conformance lane (W01, W01b, W05, W08 both roles, M01–M03, W07p) | emulator API 35, 36 | **EMULATOR EVIDENCE** | `./gradlew :mesh-android:connectedDebugAndroidTest --tests '*Conformance*'` → green; W08: `pre_shared_key` absent, `CertificateVerify` present |
| Keystore NIK and leaf | emulator | partly (TEE emulated; no StrongBox) | `--tests '*NodeKeyStore*'`; the leaf refuses to sign while locked; the "serve while locked" leaf signs |
| loopback at targetSdk 37 (H5) | emulator API 37 `google_apis` | **EMULATOR EVIDENCE** | `./gradlew :sample-client:connectedT37DebugAndroidTest` → `OK`; the managed-profile case is refused |
| FGS network under forced Doze | emulator | AOSP behaviour only | `dumpsys battery unplug; dumpsys deviceidle force-idle`, then a borrow → 200 with rows on both nodes |
| thermal and power transitions (lender) | emulator | simulated | `cmd thermalservice override-status 2` → DRAINING; `KEYCODE_WAKEUP` → DRAINING, then SERVING no sooner than 10 min (test clock) |
| StrongBox, attestation roots, OEM power policy (RedMagic), heat, radio, overlay, Tailscale's log switch | — | **no** | NDV |

**Gates:**

| Step | Phase | Gate | Estimate |
|---|---|---|---|
| L-A1 (optional) | now, lab | `./gradlew -p lab labTest --tests '*LnpModel*'` → `BUILD SUCCESSFUL`; self-oracled | (lab) |
| V11-1 | v1.1 (H4) | `./gradlew :app:processDebugMainManifest && grep -c dataExtractionRules <merged manifest>` → `1` | v1.1: 1–2 |
| V11-2 | v1.1 (H5) | `:sample-client:connectedT37DebugAndroidTest` on API 37 → `OK`; the profile case → refused; device: DV-A2 | (above) |
| V11-3 | v1.1 (H7) | `:ledger:connectedDebugAndroidTest --tests '*Migration1To2*'` → `OK (n tests)` | (above) |
| M1-1…M1-7 | M1 | unit tests; the ART conformance lane; Keystore tests; `DialerIT` against the lab's JVM reference node via `adb reverse` (a `DIAL` intent durable before the SYN); QR fixtures decode; the forced-Doze borrow; APK policy | M1 requester: 5–8 |
| M1-8 | M1 | DV-A1…DV-A8: NDV | (above) |
| L-1…L-4 | M2+ (after v3) | W08 server role on API 36/37; `adb shell ss -ltn` shows only `127.0.0.1:11435` and `10.0.2.15:11436`; governor transitions; the LNP flow at targetSdk 37 only if D29 bumps it; DV-L1…DV-L6 | lender: 5–9 |

**NDV (the owner's RedMagic):**
- DV-A1: the phone listens on nothing new in M1 (`adb shell ss -ltn` → only `127.0.0.1:11435`);
- DV-A2: loopback at targetSdk 37;
- DV-A3: Tailscale tun addresses;
- DV-A4: the NIK in StrongBox;
- DV-A5: FGS network in real Doze;
- DV-A6: idle then request;
- **DV-A7: quiescence (M1 gate 5): overlay off with a gateway capture and its positive control; with the overlay on, only a rooted on-device capture passes it, otherwise it stays open**;
- DV-A8: Tailscale's log switch;
- DV-L1…DV-L6 (lender heat run, presence drain, locked lender, OEM kill check, memory limiter, wireless charger).

**Estimate:** 11–19 engineer-weeks Android-specific (v1.1 items 1–2; M1 requester 5–8; lender 5–9). These overlap the design's M1 and M2+ rows rather than adding to them.

---

## 9. Long-horizon holons (D26): nothing scheduled

- **Car head units and Wi-Fi appliances.** They are possible only as borrow-only requester profiles: the Linux jar with lending off (a D26(b) option) or the Ubuntu Touch app (a Rabbit R1 running Ubuntu Touch 24.04 is one concrete example [UF02]).
- **Nothing is built** unless the owner rules D26(b) or D26(c).

---

## 10. CI summary

| Workflow | Track | Triggers | Gating jobs | Runners |
|---|---|---|---|---|
| `ci.yml` (existing, untouched) | v1 | push to main, PRs | `jvm-tests`, `android-apk` | `ubuntu-latest` |
| `lab.yml` | lab | push to main, PRs | `lab-tests` (JDK 17, 21), `lab-isolation-with-sdk`, `root-unchanged` | `ubuntu-latest` |
| `desktop-linux.yml` | desktop + Linux | `desktop/**`, `core/**`, `server/**`, `gradle/**` | `desktop-jvm`, `desktop-jvm-arm`, `package-linux`, `install-matrix`, `systemd-vm` (+ `native-linux`, `engine-smoke` from DL4; `mesh-netns` from M1) | `ubuntu-24.04`, `ubuntu-24.04-arm`, `ubuntu-22.04`, distro containers |
| `desktop-windows.yml` | Windows | `desktop/**`, `lab/**` | `lab-windows`, `winplatform` (+ `package-and-smoke` from D-v2) | `windows-2025`, `windows-11-arm` |
| `desktop-macos.yml` | macOS | `desktop/**` | `macplatform` (+ `package-and-smoke` from D-v2; `sign-notarize` on `main`/tags with secrets) | `macos-latest`, `xcode-27` |
| `apple-ios.yml` | Apple | `apple/**`, `lab/conformance/**` | `apple-swift-lane`, `jvm-lines`, `lane-diff`, `ios-package-sim` (+ app, device-build, lint, engine, export, W08, durability jobs at M1b) | `macos-latest`, `ubuntu-latest` + `swift:6.1-noble` |
| `ubuntu-touch.yml` | Ubuntu Touch | `ubuntu-touch/**`, `desktop/node-core/**`, `lab/**` | `ut-jvm`, `ut-runtime`, `ut-click`, `ut-qml`, `ut-arm64-smoke`, `ut-apparmor-approx` (CI-APPROX) | `ubuntu-latest`, `ubuntu-24.04-arm`, Clickable containers |
| `android-mesh.yml` (after V1-close) | Android | `app/**`, `mesh-android/**`, `qr/**`, `ledger/**`, `pairing/**`, `sample-client/**` | unit, APK policy, emulator lanes (API 35/36/37) | `ubuntu-24.04` + KVM |

- **Every non-Android workflow** runs the `git diff --exit-code` check of P2 and pins third-party actions by commit SHA.
- **Nothing from a hosted runner is quoted as a speed, thermal or memory figure.** The runners are VMs: for example, `macos-latest` is a 3-vCPU, 7 GB M1 VM.

---

## 11. Effort (engineer-weeks; estimates, not measurements)

| Track | Now (ships nothing) | Later (by phase) | Source |
|---|---|---|---|
| Lab (L0.1–L0.6) | 16–25 | — | design §9.4 |
| Apple Swift lane (L0.7 = MC0 = I0) | 3–5 | — | `macos.md`, `ios.md` |
| Desktop core + Linux/Deck | DL0–DL3: 6–9.5 | DL4–DL5 (D-v2) 3–5; DL6 (M1) 2–3; DL7 0.5–1 | `linux.md` §10.3 (total 12–19) |
| Windows | W0–W2: part of 10–16 | W3–W8 (D-v2/M1 or M1b) | `windows.md` §10.4 (10–16) |
| macOS | MC1–MC2: part of 11–17 | MC3–MC8 (D-v2/M1 or M1b); MC9 +2–3 (M2) | `macos.md` §10.4 (11–17; minimal cut 7–10) |
| iOS/iPadOS | — | I1–I3 (M1b) 13–20; I4 (M2+) 4–7; I5 1–2 | `ios.md` §10.4 (21–34 incl. I0) |
| Ubuntu Touch | UT-0: 2–3.5 | UT-1 (M1b) 5–8; UT-2 unscheduled 6–10; Rust fallback +8–12 if S-UT1 fails | `ubuntu-touch.md` §10.5 |
| Android | — | v1.1 1–2; M1 requester 5–8; lender (M2+) 5–9 | `android-mesh.md` §10.5 (11–19) |

The design's §9.4 sums the mesh and platform work, by phase, to **≈ 92–150 engineer-weeks** (excluding roadmap v1.1/v2/v2.5/v3 themselves, UT-2, the Rust fallback and D26). The per-track figures above overlap its rows; they are not added to it. All assume one engineer per track who knows its stack, the owner's hardware for device sessions, and spikes passing on the first design.

---

## 12. Agents per track (directive D-F)

| Agent | Owns | Starts | Hands off to |
|---|---|---|---|
| Lab | `lab/`, `lab.yml` | now | everyone (vectors); Android and desktop at promotion (D23) |
| Desktop + Linux | `desktop/` except `packaging/windows` and `packaging/macos`; `desktop-linux.yml` | after L0.1 | Windows, macOS, Ubuntu Touch (the `:node-core` seam) |
| Windows | `desktop/packaging/windows`, `desktop-windows.yml` | after DL0 | owner (W8 device items) |
| macOS | `desktop/packaging/macos`, `desktop-macos.yml` | after DL0 | owner (MC8), Apple agent (none: the helper is separate) |
| Apple | `apple/`, `apple-ios.yml` | after L0.2 vectors exist | owner (DV-I*); the lab (independent oracle record, `LAB_SPEC.md` §4.10) |
| Ubuntu Touch | `ubuntu-touch/`, `ubuntu-touch.yml` | after DL0 and L0.1/L0.2 | owner (S-UT1, DV-UT*) |
| Android | existing Android modules, `android-mesh.yml` | **after V1-close** | owner (DV-A*, DV-L*) |
| Reviewer | reads every track's `PROGRESS.md` entries; checks labels, isolation checks and oracle tags | continuous | owner |

**Coordination rules:**
- each agent appends only its own `PROGRESS.md` section;
- a change another track needs goes through the owning agent (for example, a new `DesktopPlatform` port goes through the desktop agent);
- a conflict with the design stops the agent with `BLOCKED(spec conflict: <where>)`.
