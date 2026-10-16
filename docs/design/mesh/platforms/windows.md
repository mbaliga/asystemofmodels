# Platform section: Windows 10/11 (asom-desktop node on Windows)

**Date:** 2026-09-30 · **Status:** design input for the reviser. Nothing here is approved, frozen or executable.
**Scope:** directive D-E put Windows in scope. `ASOM_MESH_DESIGN.md` r2 §3.1 still says "Windows is out of scope"; that sentence is superseded by D-E and this section is its replacement for Windows. Sequencing follows AD-1 (v2, then mesh-1 with asom-desktop) and every entry criterion of the design (§9.3), including the v4 design session (R2-CONFORMANCE-3).
**Target implementation directory:** `desktop/packaging/windows/`.
**Tags:** `[FWnn]` = verified Windows fact (§1.1, with source URL and fetch date). `[AWnn]` = assumption (§1.2). `[Fnn]`/`[Ann]` = facts and assumptions of the integrated brief. `S-Wn` = spike that settles an assumption. `NDV` = NEEDS-DEVICE-VALIDATION. `NOV` = NEEDS-OWNER-VALIDATION.

**The answer in brief.**
- **Same JVM node as Linux.** Windows runs the pure-JVM `:core:*` + `:server` + `:node-desktop` code unchanged. The only Windows-specific code is a thin platform adapter (`winplatform`, Kotlin + JNA) for keys, power, presence, paths/ACLs and firewall probing, plus packaging. No second protocol implementation, so no new conformance-drift surface beyond the adapter.
- **Roles.** Requester: **yes** (mesh-1, owner CLI). Always-on provider: **yes on AC-powered desktops**; **conditional on laptops** (AC, lid open or lid action "Do nothing"); **no during Modern Standby** (desktop apps suspended, services throttled to about 1 s per 30 s and network hidden from third-party services) [FW08][FW09]. Foreground-only provider: feasible, not recommended.
- **Hosting.** Two modes on one per-machine install. **User mode (default):** a logon-started per-user process with a tray icon. **Service mode (opt-in):** a Windows service hosted by Apache procrun in-process (`StartMode=jvm`), running as the virtual account `NT SERVICE\asom`, for "serve while logged out". `sc.exe` alone cannot host a JVM launcher [FW20]; WinSW is rejected (3.x has been alpha for years; 2.x spawns a child process) [FW24][FW25].
- **Network.** The peer listener binds only the selected overlay or confirmed-LAN address. Windows Firewall blocks inbound by default and a cancelled first-listen prompt creates **block rules that override allow rules** [FW05], so asom never listens before a narrow allow rule exists. That rule is created only by an explicit, elevated, view-first command, never by the installer.
- **Keys.** T2 = CNG Microsoft Platform Crypto Provider (TPM) ECDSA P-256, pending spike S-W1 [FW15][AW01]. T1 = CNG Microsoft Software KSP (key isolated in LSA, non-exportable by policy only) [FW16][FW44]. T0 = ACL'd PKCS#8 file, optionally DPAPI-wrapped [FW14]. Windows Hello is rejected: per-signature user gesture, RSA keys, online-account requirement [FW18].
- **Engine.** llama.cpp via the shared JNI surface: CPU and Vulkan on x64, CPU and OpenCL-Adreno on ARM64; CUDA after the licence check [FW35]. DirectML (sustained engineering) and Windows ML (ONNX artefacts; execution providers fetched by Windows Update) are rejected for mesh-1 [FW36][FW37].
- **Benchmark consequence (D-C).** MLPerf Client already covers Windows x64 and ARM64, via Windows ML, OpenVINO and llama.cpp [FW38]. **Windows benchmark coverage is not a differentiator.** The Windows bench exists only to feed the router and the signed manifest.
- **Packaging.** Temurin-21 jlink runtime → `jpackage --type app-image` → **own WiX source** → per-machine MSI with a "Background service" feature (default off). Authenticode-sign every PE file and the MSI. Distribute via GitHub Releases plus a winget manifest. There is **no in-app updater**, because it would be an automatic, unclassed egress.
- **CI.** `windows-2025` and `windows-11-arm` hosted runners can compile, unit-test (including real DPAPI/CNG-software/power-request calls), build and sign the MSI, and install/start/stop/uninstall the service in the runner session (admin, UAC off) [FW39]. TPM, GPU, sleep/lid/Modern Standby, SmartScreen/Smart App Control, Tailscale and real firewall profiles stay NDV.
- **Effort:** about **10–16 engineer-weeks** for Windows-specific work, on top of the shared D-v2 desktop node.

---

## 1. Verified platform facts and assumptions

### 1.1 Verified (fetched 2026-09-30 unless stated; "page date" = the page's own ms.date/validated date)

| ID | Fact | Source (page date) | Conf. |
|---|---|---|---|
| FW01 | "Each Tailscale agent … streams its logs to a central log server (at log.tailscale.com)." On Windows the **only** opt-out is `TS_NO_LOGS_NO_SUPPORT=true` in `%ProgramData%\Tailscale\tailscaled-env.txt`; the `--no-logs-no-support` flag is not available on Windows | https://tailscale.com/kb/1011/log-mesh-traffic (validated 2026-01-05) | high |
| FW02 | Tailscale sets the **Private** network category on its Windows adapter; a request to allow Public is an open issue whose author notes that Private applies "looser" firewall rules | https://tailscale.com/kb/1643/messages-client-set-network-category-failed (2026-01-30); https://github.com/tailscale/tailscale/issues/14708 (open) | high |
| FW03 | On Windows, "When the user signs out or the device restarts, Tailscale disconnects until a user logs in again", unless "Run unattended" is set (`tailscale set --unattended=true`) | https://tailscale.com/kb/1088/run-unattended (2026-09-25) | high |
| FW04 | Headscale's Windows guide uses the official Tailscale client with `tailscale login --login-server <URL>`; it says nothing about log upload | https://headscale.net/stable/usage/connect/windows/ | high (for what it says) |
| FW05 | Windows Firewall blocks inbound by default. On an app's first listen with no allow rule, an admin user is prompted; **"If they respond No or cancel the prompt, block rules are created"** (TCP and UDP); a non-admin's prompt creates block rules whatever is chosen. **Explicit block rules take precedence over allow rules.** Application rules need the full program path (no wildcards). Microsoft recommends staging allow rules before first launch | https://learn.microsoft.com/en-us/windows/security/operating-system-security/network-security/windows-firewall/rules (2025-06-06) | high |
| FW06 | `New-NetFirewallRule` supports `-Program`, `-Service`, `-Protocol`, `-LocalPort`, `-RemoteAddress` (CIDR, ranges, keywords such as `LocalSubnet`), `-InterfaceAlias`, `-InterfaceType`, `-Profile` (Domain/Private/Public) | https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule | high |
| FW07 | `SetThreadExecutionState(ES_SYSTEM_REQUIRED)` resets the idle timer; it "**cannot be used to prevent the user from putting the computer to sleep**"; away mode is for media apps only | https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate | high |
| FW08 | `PowerSetRequest`: `SystemRequired` keeps the system out of idle sleep; `ExecutionRequired` keeps a process from PLM suspension. "On Modern Standby systems on DC power, system and execution required power requests are terminated 5 minutes after the system sleep timeout has expired." "Power requests are terminated upon user-initiated system sleep entry (power button, lid close or selecting Sleep)", except away mode on S3 | https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-powersetrequest | high |
| FW09 | Modern Standby begins when the display turns off (power button, lid close, Sleep, idle). Power requests block the NoCS phase "indefinitely on AC power, and for up to 5 minutes on DC". The Desktop Activity Moderator **suspends desktop applications**. In the resiliency phase "Session-0 services are throttled … to no more than one second of activity every 30 seconds"; "As of 24H2, additional session-0 services may be suspended". `powercfg /requests` lists holders | https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/prepare-software-for-modern-standby (updated 2024-06-25) | high |
| FW09b | Network Quiet Mode makes the network appear disconnected to third-party services during Modern Standby | Microsoft "Networking power management for Modern Standby" (seen in search results; **not fetched**) | medium |
| FW10 | `PowerRegisterSuspendResumeNotification(DEVICE_NOTIFY_CALLBACK)` (Windows 8+) delivers `PBT_APMSUSPEND`, `PBT_APMRESUMESUSPEND` and `PBT_APMRESUMEAUTOMATIC` to a callback, with no window needed | https://learn.microsoft.com/en-us/windows/win32/api/powerbase/nf-powerbase-powerregistersuspendresumenotification | high |
| FW11 | Lid close action: powercfg alias `LIDACTION`, GUID `5ca83367-6e45-459f-a27b-476b1d01c936`; 0 = Do Nothing, 1 = Sleep, 2 = Hibernate, 3 = Shut Down | https://learn.microsoft.com/en-us/windows-hardware/customize/power-settings/power-button-and-lid-settings-lid-switch-close-action | high |
| FW12 | `SYSTEM_POWER_STATUS`: `ACLineStatus` 0/1/255; `BatteryFlag` 128 = no system battery; `SystemStatusFlag` 1 = battery saver on (Windows 10+) | https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-system_power_status | high |
| FW13 | QoS: background services get "Utility" QoS (Windows 11 22H2): on battery, "most efficient CPU frequency and schedules to efficient cores". Minimised windowed apps get Low QoS. A process opts out with `SetProcessInformation(ProcessPowerThrottling)` | https://learn.microsoft.com/en-us/windows/win32/procthread/quality-of-service (2025-07-14) | high |
| FW14 | `CryptProtectData`: "Typically, only a user with the same logon credential … can decrypt"; usually the same computer, **except that a roaming profile can decrypt elsewhere**. `CRYPTPROTECT_LOCAL_MACHINE` lets "any user on the computer" decrypt. The prompt-struct flow is removed in February 2027 | https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata (2025-11-13) | high |
| FW15 | CNG KSPs: the Microsoft Software KSP supports ECDSA P-256/384/521. The Microsoft Platform Crypto Provider "utilizes the Trusted Platform Module"; Microsoft states its keys "cannot be extracted, even by malicious software" (a vendor claim); it is opened with `MS_PLATFORM_CRYPTO_PROVIDER` | https://learn.microsoft.com/en-us/windows/win32/seccertenroll/cng-key-storage-providers (2026-06-12) | high (for what it says) |
| FW16 | CNG key isolation: long-lived keys "are never present in the application process"; the Microsoft KSP runs in the LSA key-isolation service. User keys live in `%APPDATA%\Microsoft\Crypto\Keys` (**Roaming**); LocalService/NetworkService keys under `%WINDIR%\ServiceProfiles`; machine ("shared") keys under `%ALLUSERSPROFILE%\…\Crypto\Keys` | https://learn.microsoft.com/en-us/windows/win32/seccng/key-storage-and-retrieval (2024-12-17) | high |
| FW17 | `New-SelfSignedCertificate` supports `-Provider 'Microsoft Platform Crypto Provider'`, `-KeyAlgorithm ECDSA_<curve>` and `-KeyExportPolicy NonExportable` (required for PCP). The only documented PCP example uses **RSA 2048** | https://learn.microsoft.com/en-us/powershell/module/pki/new-selfsignedcertificate | high |
| FW18 | Windows Hello `KeyCredential`: "Access through these APIs does require explicit validation through a user gesture"; `RequestSignAsync` makes Windows "request the user's PIN or biometrics"; "The public key is an RSA key"; enabling Hello needs a connected Microsoft Entra ID or Microsoft account | https://learn.microsoft.com/en-us/windows/apps/develop/security/windows-hello (2026-09-27) | high |
| FW19 | SunMSCAPI reads CNG RSA and **EC** keys from `Windows-MY` and supports `SHA256withECDSA` (JDK 13; backported to 11.0.7). `Windows-MY-LOCALMACHINE` and related types were added in JDK 19 | https://bugs.openjdk.org/browse/JDK-8225688 ; https://bugs.openjdk.org/browse/JDK-8286790 | high |
| FW20 | `jpackage --launcher-as-service` (JDK 19, JDK-8275062) ships **no** service manager: "it will support the use of 3rd party service manager executables" through `--resource-dir` (`service-installer.exe`); the launcher itself cannot be the service manager. On Windows, jpackage builds `msi`, `exe` and `app-image` | https://bugs.openjdk.org/browse/JDK-8275062 ; https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html | high |
| FW21 | jpackage supports WiX v3 and, from JDK 24, WiX v4/v5 (JDK-8319457). A WiX 5 failure on JDK 24 was reported (JDK-8356592, closed as a duplicate of JDK-8347024) | https://bugs.openjdk.org/browse/JDK-8319457 ; https://bugs.openjdk.org/browse/JDK-8356592 | high / medium |
| FW22 | WiX v3 and v4 left community support on 2025-02-06: no further fixes, including security fixes | FireGiant, "WiX v3 and WiX v4 are no longer in community support" (search result; page not fetched) | medium |
| FW23 | WiX v6+ carries the Open Source Maintenance Fee: organisations generating revenue above US$10k/yr must sponsor; EULA acceptance is enforced from v7 | https://docs.firegiant.com/wix/osmf/ (search summary; page body not retrieved) | medium |
| FW24 | Apache Commons Daemon **1.6.1 (2026-06-03)**; 1.6.0 (2026-05-29) added Windows ARM64. Procrun `--StartMode=jvm` "start[s] Java in-process. Depends on jvm.dll"; static `start`/`stop` methods; `--ServiceUser`, `--Startup manual/auto/delayed`, `--StopTimeout`; parameters under `HKLM\SOFTWARE\Apache Software Foundation\ProcRun 2.0\<name>\Parameters`. The docs do not mention power events or preshutdown | https://commons.apache.org/proper/commons-daemon/changes.html ; https://commons.apache.org/proper/commons-daemon/procrun.html | high |
| FW25 | WinSW: the latest stable release is v2.12.0; 3.x exists only as pre-releases (latest v3.0.0-alpha.11); MIT licence | https://github.com/winsw/winsw/releases (the page shows no years) | high |
| FW26 | SmartScreen: unsigned → "Windows protected your PC", the user must choose "Run anyway", "Enterprise policy can prevent continuation entirely"; self-signed behaves the same. A new OV-signed file still warns until reputation accrues. **EV no longer bypasses SmartScreen** (removed 2024). Unsigned files restart reputation from zero every version. Store-distributed apps never warn. "**Smart App Control will block execution of unsigned files** unless the file has a positive reputation … checks apply to all executable files" | https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation (2026-05-04) | high |
| FW27 | Azure Artifact Signing: about US$9.99/month; organisations in the US, Canada, EU and UK; **individuals in the US and Canada only**. OV certificates cost about US$150–300/yr, with an HSM or token required since June 2023. Store MSI/EXE submissions must be signed with a Trusted-Root-Program certificate. **SignPath Foundation offers free signing for qualifying open-source projects** | https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options (2026-08-29) | high |
| FW28 | CA/B Forum ballot CSC-31: code-signing certificates issued from 2026-03-01 are valid for at most 460 days | DigiCert and GlobalSign notices (search results; not fetched) | medium |
| FW29 | Since the April 2026 cumulative update, Smart App Control can be switched back on without reinstalling Windows | trade press via search (computerworld, topedia; not fetched) | medium |
| FW30 | winget: manifest schema **1.12.0**, multi-file layout (version, installer, defaultLocale); `InstallerType` includes `msi` and `wix`; "All tools must support a silent install"; submission is a PR to `microsoft/winget-pkgs`, with automated validation (several AV engines, a Defender dynamic scan, URL reputation, HTTPS only, installer URL taken directly from the publisher's release location, install/uninstall "for both administrators and non-administrators") and moderator review | https://learn.microsoft.com/en-us/windows/package-manager/package/manifest (2026-09-14); https://learn.microsoft.com/en-us/windows/package-manager/package/repository | high |
| FW31 | The winget client has telemetry that `"telemetry": {"disable": true}` in its settings.json turns off | Microsoft `winget settings` docs (search summary) | medium |
| FW32 | Windows 10 support ended 2025-10-14; consumers "can enroll in ESU any time until the program ends on October 12, 2027" | https://support.microsoft.com/en-us/windows/deployment/updates-lifecycle/windows-10-support-has-ended-on-october-14-2025 ; https://www.microsoft.com/en-us/windows/extended-security-updates | high |
| FW33 | AF_UNIX on Windows (from build 17063): pathname sockets are secured by file and directory permissions, and **connecting requires write permission on the socket file**. No `SCM_CREDENTIALS`/`SCM_RIGHTS`, no `SOCK_DGRAM`. JEP 380: Unix-domain sockets are supported on Windows 10 / Server 2019, and peer credentials exist only "on platforms that support it" | https://devblogs.microsoft.com/commandline/af_unix-comes-to-windows/ (2017-12-19); https://openjdk.org/jeps/380 | high |
| FW34 | `WerAddExcludedApplication(exeName, bAllUsers)` removes an executable name from Windows Error Reporting; the HKLM list needs admin rights. WER settings include `ExcludedApplications`, `LocalDumps`, `Consent\DefaultConsent` | https://learn.microsoft.com/en-us/windows/win32/api/werapi/nf-werapi-weraddexcludedapplication ; https://learn.microsoft.com/en-us/windows/win32/wer/wer-settings | high |
| FW35 | llama.cpp's build guide documents Windows builds for CPU (MSVC or clang), ARM64, CUDA, Vulkan (needs the Vulkan SDK), HIP and OpenCL; it has **no DirectML or ONNX backend**. Release b11269 ships `win-cpu-x64`, `win-cpu-arm64`, `win-opencl-adreno-arm64`, `win-cuda-12.4-x64`, `win-cuda-13.4-x64`, `win-cuda-13.4-arm64`, `win-vulkan-x64`, `win-openvino-2026.4-x64`, `win-sycl-x64` and `win-rocm-10.0-x64` | https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md ; https://github.com/ggml-org/llama.cpp/releases | high |
| FW36 | "**DirectML is in sustained engineering** … new feature development has moved to Windows ML" | https://learn.microsoft.com/en-us/windows/ai/directml/dml | high |
| FW37 | Windows ML is ONNX Runtime-based. Execution providers are ones "that Windows installs and keeps up to date via Windows Update"; apps "dynamically acquire the latest execution providers". It runs on x64 and ARM64. CPU and GPU (via DirectML) work on all supported versions; the NPU and vendor GPU EPs need Windows 11 24H2 (build 26100) or later | https://learn.microsoft.com/en-us/windows/ai/new-windows-ml/overview (2026-09-01) | high |
| FW38 | MLPerf Client v1.6 (2026-04-06): "updates to **Windows ML** and **llama.cpp** … on Windows platforms"; GUI builds on the iOS/Mac App Stores and Steam. The README lists Windows x64 paths WindowsML, NativeOpenVINO, llama-cpp (CUDA) and OrtGenAI-RyzenAI; Windows ARM paths WindowsML and llama-cpp; TTFT and tokens/s metrics; Apache-2.0; models are downloaded by default | https://mlcommons.org/2026/04/mlperf-client-v1-6/ ; https://github.com/mlcommons/mlperf_client | high |
| FW39 | GitHub-hosted runners: `windows-latest` maps to `windows-2025-vs2026`; other labels are `windows-2025`, `windows-2022`, `windows-11-arm` and `windows-11-vs2026-arm`. Public repositories get 4 vCPU, 16 GB RAM and a 14 GB disk. "Windows virtual machines are configured to run as administrators with User Account Control (UAC) disabled." GPU runners are larger runners only (Team/Enterprise) | https://docs.github.com/en/actions/reference/runners/github-hosted-runners ; https://github.com/actions/runner-images | high |
| FW40 | Image `windows-2025(-vs2026)` 20260922: Temurin 8/11/**17 (default)**/21/25 with `JAVA_HOME_17_X64`, `JAVA_HOME_21_X64` and `JAVA_HOME_25_X64`; WiX 3.14.1; VS 2022 (or VS 2026 on the vs2026 image) with C++, CMake 4.4.3, Ninja and LLVM 20; **no Vulkan SDK, no CUDA**; Windows SDK 10.0.26100 (signtool); PowerShell 7.6.6 | runner-images `images/windows/Windows2025-Readme.md` and `Windows2025-VS2026-Readme.md` | high |
| FW41 | Image `windows-11-arm` 20260920: Java 21 and 23 (aarch64), VS 2022 with ARM64 tools, CMake; no WiX listed | runner-images `images/windows/Windows11-Arm64-Readme.md` | high |
| FW42 | Packet Monitor (`pktmon.exe`) is in-box from build 19041 and converts captures to pcapng | https://learn.microsoft.com/en-us/windows-server/networking/technologies/pktmon/pktmon | high |
| FW43 | JNA (Apache-2.0 or LGPL-2.1, at the user's choice) loads `jnidispatch` from `jna.boot.library.path`, then the system path; otherwise it extracts the library from the jar into a temp directory "unless `jna.nounpack=true`". The latest release is 5.19.1 (2026-06-12) | https://java-native-access.github.io/jna/5.14.0/javadoc/com/sun/jna/Native.html (loading rules); release number from a Maven-listing search | high / medium (version) |
| FW44 | Microsoft Software KSP private keys are DPAPI-protected at rest; "non-exportable" is a policy flag; the key artefacts are reachable from the SYSTEM context | https://unmitigatedrisk.com/?p=586 (third-party; search summary) | medium |

### 1.2 Assumptions (not verified; each has a settling test)

| ID | Assumption | Load-bearing for | Settle by |
|---|---|---|---|
| AW01 | The Platform Crypto Provider creates a persisted **ECDSA P-256** key on the owner's TPM 2.0 (the documented PCP example is RSA only [FW17]) | T2 tier | **S-W1** on the owner's machine (NDV) |
| AW02 | `NCryptSignHash` on a CNG ECDSA key returns the 64-byte r‖s form (the asom wire form, C2), so no DER step is needed for manifests | NIK signing code | S-W1 plus a JCA verify in CI (Software KSP) |
| AW03 | A procrun service can run as the virtual account `NT SERVICE\asom` (no password) and open a machine-scope CNG key whose DACL grants that SID | service mode | **S-W2** on a runner (Software KSP) and on the owner's machine (PCP) |
| AW04 | The JDK on Windows offers no `SO_PEERCRED` for Unix-domain sockets, so the socket file's DACL is the only local-caller check without native code | owner CLI identity (C9, CD-24) | **S-W3**: `supportedOptions()` on a Windows JDK |
| AW05 | The PDH counters `\GPU Engine(*)\Utilization Percentage` give per-process (`pid_<n>_…`) utilisation readable without admin, from a service and from a user process | GPU-contention drain rule | **S-W4** (runner: counters exist; owner GPU: NDV) |
| AW06 | `\Thermal Zone Information(*)\Temperature` exists but is often absent or static on consumer PCs; Windows has no general thermal-state API for apps | thermal band, benchmark `NO_THERMAL_SIGNAL` | **S-W5** on the owner's machines |
| AW07 | `WTSQuerySessionInformation(WTSSessionInfoEx)` gives lock state for the console session from session 0 | service-mode presence | **S-W6** |
| AW08 | `pktmon` captures traffic on the Tailscale/Wintun adapter, so the quiescence gate can be observed on a Windows borrower | M1 gate (5) on Windows | **S-W7** (NDV) |
| AW09 | Temurin's Windows DLLs and EXEs are Authenticode-signed, so a jlinked runtime stays signed; any unsigned PE files are signed by us | Smart App Control | `verify-signatures.ps1` in CI (§10) |
| AW10 | Smart App Control also blocks an **unsigned DLL** loaded by a signed EXE | signing scope | **S-W8** on a Smart App Control-enabled machine (NDV) |
| AW11 | Hosted runners expose no usable TPM to the VM | CI scope for T2 | first CI run records it |
| AW12 | There is no OS-level "local network" permission for unpackaged Win32 apps as of 2026-09; Windows Firewall is the only gate (searches found browser-level controls only) | §4 | re-check at M1 entry |
| AW13 | The Tailscale Windows adapter's interface alias is "Tailscale" | firewall rule scope | owner check (`Get-NetAdapter`) |
| AW14 | On S3 systems `PBT_APMSUSPEND` leaves only seconds for work; the exact budget is undocumented here | drain on sleep | NDV |
| AW15 | Upstream procrun binaries are not Authenticode-signed; asom signs its renamed copy | signing | inspect the release |
| AW16 | Windows Backup and OneDrive Known Folder Move do not sync `%LOCALAPPDATA%` or `%ProgramData%` by default | ledger/key backup exposure (C7) | owner check; disclosure regardless |
| AW17 | The JDK sets `SO_EXCLUSIVEADDRUSE` on Windows listening sockets, so a same-address bind by another process fails | listener squatting | S-W3 |
| AW18 | `PowerSettingRegisterNotification` (`DEVICE_NOTIFY_CALLBACK`) reports `GUID_CONSOLE_DISPLAY_STATE`, `GUID_ACDC_POWER_SOURCE` and `GUID_POWER_SAVING_STATUS` changes without a window | drain on display-off (Modern Standby) | S-W6 |
| AW19 | The runner NIC has an IPv6 link-local address in addition to its private IPv4 address, so two listeners can coexist on one runner without loopback | CI two-lender smoke | first CI run |
| AW20 | Temurin 21 on Windows defaults to UTF-8 (JEP 400, JDK 18+), while JDK 17 on Windows uses the ANSI code page (e.g. Cp1252) | conformance drift | Windows JDK 17 lane (§7) |

Load-bearing gaps for the owner: **the Dell's OS and GPU** (if the Dell runs Windows, Windows becomes a mesh-1 lender, W-D1), whether any Windows laptop is meant to lend, the owner's country (it decides the signing route, FW27), and whether the owner's use of WiX would generate revenue (FW23).

---

## 2. Feasible mesh roles on Windows

| Role | Verdict | Honest reason |
|---|---|---|
| **R — requester** (borrow) | **YES, mesh-1** (owner CLI); desktop local apps only at M2 under D25(b) + D14 part B | Outbound TLS to paired peers needs no listener, no firewall rule (outbound is allowed by default [FW05]) and no admin. The CLI reaches the node over an AF_UNIX control socket [FW33]. **Holon completeness (R2-DIRECTIVES-4):** in mesh-1 a Windows node serves only its owner's CLI, because there is no local-app API on desktops yet (D25) |
| **PA — lend while awake, no human present** | **YES on AC-powered desktops**, in either hosting mode; **CONDITIONAL on laptops** (on AC, and lid open or `LIDACTION = 0`); **NO while in Modern Standby**; **NO on battery** by default | Desktops on S3: `PowerRequestSystemRequired` keeps the machine out of idle sleep while SERVING [FW08]. Modern Standby laptops: once the display turns off by user action, power requests are terminated [FW08], desktop apps are suspended by the DAM, services are throttled to ≤ 1 s per 30 s [FW09], and the network is hidden from third-party services [FW09b]. **No asom code can lend through that, and asom never changes power settings.** On AC, a held power request keeps a Modern Standby machine in the NoCS phase through *idle* display-off [FW09]; lid close still sleeps unless the owner sets `LIDACTION = 0` [FW11]. On battery, services get Utility QoS [FW13] and lending is off by default (as on the other platforms) |
| **PF — lend only while a lend screen is frontmost** | Feasible, **not recommended** | A tray window "lend while this is open" works technically, but a Windows machine that can lend at all can do it as PA under the same governors. PF would add UI and presence-law exceptions (R2-OVERCLAIM-9) for little value |
| **B — benchmark producer** | YES (desktop `asom bench`, D-v2) | Only to feed the router and the signed manifest. **MLPerf Client already covers Windows x64 and ARM64** (Windows ML, OpenVINO, llama.cpp) [FW38], so Windows coverage is not a D-iii differentiator (R2-DIRECTIVES-2) |
| **S — subscriber** | YES | Pure JVM verifier |

### 2.1 Availability FSM conditions on Windows (the §3.2 FSM, Windows inputs)

`conditions_met` (every clause is user-overridable except those marked **hard**):

| Clause | Source | Default |
|---|---|---|
| lend toggle on | node config | OFF (**hard** default) |
| an eligible interface is up and selected | `NetworkInterface` + selection record (§4.1) | — |
| a matching **allow** firewall rule exists and **no asom-scoped block rule** exists | firewall probe (§4.2) | **hard**: otherwise state `FIREWALL_RULE_MISSING` (the Windows analogue of Android's `LOCAL_NETWORK_DENIED`) |
| AC power | `GetSystemPowerStatus`: `ACLineStatus = 1`, or `BatteryFlag = 128` (no battery) with `ACLineStatus ∈ {1, 255}` [FW12] | required |
| not battery saver | `SystemStatusFlag = 0` [FW12] | required |
| not `os_sleep_imminent` | `PBT_APMSUSPEND` [FW10]; display-off on Modern Standby machines [AW18] | **hard** |
| presence rule ("yield to the local user") | user mode: `GetLastInputInfo` idle ≥ 10 min, session locked, or no full-screen/D3D app (`SHQueryUserNotificationState`); service mode: WTS console-session state [AW07] | ON |
| GPU contention: `other_busy = total − own` below 200‰ for 60 s; drain above 400‰ for 10 s (Deck hysteresis) | PDH GPU Engine counters [AW05] | ON if the counters exist; otherwise the rule is off and `asom doctor` says so |
| a logged-in session exists, or `serveWhileLoggedOut = true` (service mode only) | WTS | `serveWhileLoggedOut = false` (M1 gate 10) |

While SERVING, the node holds one power request created with the reason string **"asom: lending compute to your paired devices"** (`PowerCreateRequest` + `PowerSetRequest(PowerRequestSystemRequired)`), cleared on leaving SERVING. `graceMs` is 30,000, as on the other desktops. Presence hold-down (LP-2) is unchanged: return to SERVING no sooner than 10 min after the last presence signal. On the wire the transition shows only as `fsm` changes and `PEER_UNAVAILABLE` (R2-DIRECTIVES-5); the reason stays in the local ledger.

### 2.2 Store and distribution rules that bear on roles

- **Microsoft Store: not used.** Its MSI/EXE route still needs a Trusted-Root-signed installer [FW27], so it saves no signing cost. Packaging the service as MSIX is a separate restricted path that this design does not need (not investigated; stated as a gap).
- winget requires silent install and uninstall for admins and non-admins, AV-clean binaries and a direct publisher URL [FW30]. None of this limits roles.

---

## 3. Node hosting

### 3.1 Options considered

| Option | Verdict | Reason |
|---|---|---|
| `sc.exe create` pointing at the jpackage launcher | **Rejected** | A service binary must speak the Service Control Manager protocol; jpackage's launcher cannot be the service manager [FW20] |
| jpackage `--launcher-as-service` + `service-installer.exe` | **Rejected** | jpackage ships no service manager, and the contract with a third-party `service-installer.exe` is outside our control [FW20]. It also couples the service to jpackage's WiX templates (FW21 shows WiX-version breakage) |
| **WinSW** v2.12.0 | Rejected | The 3.x line has been alpha for years [FW25]. 2.x runs Java as a **child process** (a second process, stop by console Ctrl+C), which fits D21's in-process rule worse |
| **Apache procrun** (Commons Daemon 1.6.1) | **Chosen for service mode** | Apache-2.0 (the same licence as asom); maintained (June 2026); native ARM64 since 1.6.0; `StartMode=jvm` loads our jlinked `jvm.dll` in-process; a static `stop()` runs the drain [FW24] |
| Per-user logon start (Task Scheduler "At log on" task) + tray | **Chosen for user mode (default)** | No admin needed to start; runs as the owner; visible in the notification area |
| NSSM | Rejected | Adds nothing over procrun; not assessed as maintained (not verified) |

### 3.2 The two modes (one install; exactly one node identity per machine)

| | **User mode (default)** | **Service mode (opt-in MSI feature "Background service", default off)** |
|---|---|---|
| Process | `C:\Program Files\asom\asom.exe` (jpackage GUI launcher, JVM in-process) running as the owner | `C:\Program Files\asom\asom-service.exe` (renamed `prunsrv.exe`) `//RS//asom`, `StartMode=jvm`, `Jvm=C:\Program Files\asom\runtime\bin\server\jvm.dll`, `StartClass=StopClass=xyz.mdhv.asom.desktop.win.ServiceEntry`, `StartMethod=start`, `StopMethod=stop`, `StopTimeout=35` |
| Account | the logged-in user | virtual account `NT SERVICE\asom` [AW03] |
| Start | `asom autostart on` creates a per-user Task Scheduler task "asom (user)" with trigger "At log on of <user>". **Default OFF** (v1 P8 boot-start parity) | `Startup=manual` by default; `asom service startup delayed` (elevated) sets delayed-auto |
| State dir | `%LOCALAPPDATA%\asom\` (non-roaming): `ledger\*.jsonl`, `registry\`, `config.json`, `run\ctl.sock`, `diag\` | `%ProgramData%\asom\`, DACL set by the MSI: SYSTEM + Administrators + `NT SERVICE\asom` full; the owner SID recorded at feature install gets **write on `run\` only** (to connect to the socket [FW33]) |
| NIK | user-scope CNG key `asom-nik-v1` (PCP if S-W1 passes, else Software KSP). Note that CNG user keys live under the **Roaming** `%APPDATA%` [FW16]; stated for domain users with roaming profiles | machine-scope CNG key `asom-nik-v1-svc` (`NCRYPT_MACHINE_KEY_FLAG`), key DACL = SYSTEM + `NT SERVICE\asom` [AW03] |
| Stops at | logoff (the session closes) | SCM stop / shutdown (`stop()` drains within `StopTimeout`) |
| Single identity | a named mutex `Global\asom-node`: whichever of the two starts second refuses to start and says why | same |
| Watched object | tray icon + `asom status` | tray companion (`asom.exe --companion`, no node inside) + `asom status` + `services.msc` |

**Why user mode is the default.** mesh-1 on Windows is mostly a requester, and a lender that yields to its present user mostly serves while the owner is logged in and away (locked). A service adds admin install, a second key scope and a larger attack surface. It is offered only for the "always-on host that lends while logged out" case: the Dell, if it runs Windows. This mirrors T17's split (T17a dedicated-user mode vs T17b `systemd --user`).

### 3.3 Security rules for both modes (the Windows edition of T17)

- **(a) No secrets or rows on stdout/stderr.** The user-mode launcher is a GUI-subsystem executable (no `--win-console`), so its stdout is discarded. The service has no `--StdOutput`/`--StdError`. The console CLI (`asom-cli.exe`) prints no tokens or rows except in `asom ledger export`, which writes a file. A CI test captures stdout/stderr during a request and asserts neither appears (H3 on Windows).
- **(b) Crash data stays local and content-free.** Pass `-XX:ErrorFile=<state>\diag\hs_err_%p.log` and **`-XX:-CreateCoredumpOnCrash`**: a JVM minidump would contain prompt text held in memory. The MSI writes `HKLM\SOFTWARE\Microsoft\Windows\Windows Error Reporting\ExcludedApplications\asom.exe`, `…\asom-service.exe` and `…\asom-cli.exe` [FW34]. The distinctive executable names matter because the list is keyed by file name; never exclude `java.exe`.
- **(c) Owner CLI over AF_UNIX** at `%LOCALAPPDATA%\asom\run\ctl.sock` (user mode) or `%ProgramData%\asom\run\ctl.sock` (service mode). **The only identity check is the socket's DACL** (connecting needs write on the socket file [FW33]); the JDK exposes no peer credentials on Windows [AW04]. Ledger rows therefore say `callerPkg = local-sid:<owner SID>(acl)`. Stated limit: any process running as the owner can connect, exactly as on Linux same-uid, **minus** the second-direction check that `SO_PEERCRED` gives the CLI on Linux. As a substitute, the CLI checks the socket file's owner SID and DACL (JDK `AclFileAttributeView`) before connecting; this is TOCTOU-prone and stated so. **This local-caller identity model must be named in the D2/D25 ruling (R2-CONFORMANCE-6).**
- **(d) `pair-confirm`, `restore` and `mesh firewall enable` need an interactive console** (`System.console() != null`). The socket cannot script them.
- **(e) Serve while logged out** is possible only in service mode and is off by default (`serveWhileLoggedOut`; M1 gate 10).
- **(f) Native code loads only from the install directory:** `-Djna.nounpack=true -Djna.noclasspath=true -Djna.boot.library.path=$APPDIR\..\native` [FW43] (C13). `C:\Program Files` is admin-write-only, which is why **no per-user (`%LOCALAPPDATA%\Programs`) install is offered**: a user-writable install would let any same-user process replace the engine DLL.
- **(g) Stated limits.** In user mode any same-user process can use the CNG key (sign), read a T0 file, and connect to the socket. In either mode an Administrator or SYSTEM can do all of that. That is the "shared-SID" tier label, the Windows twin of T17(g).

### 3.4 Lifecycle events

| Event | User mode | Service mode |
|---|---|---|
| Screen lock | keeps running; counts as "not present" for the presence rule | keeps running |
| Logoff | the process ends; a shutdown hook writes `SESSION` close rows if time allows, otherwise the join tool reports "closed by crash" (§8.4 of the brief) | keeps running; → ARMED unless `serveWhileLoggedOut` |
| Reboot | restarts at the next logon only if `autostart on` | restarts at boot only if Startup ≠ manual |
| Idle sleep (S3) | prevented while SERVING on AC [FW07][FW08] | same |
| User-initiated sleep (lid, power button, Start > Sleep) | **cannot be prevented** [FW07][FW08]; `PBT_APMSUSPEND` → DRAINING, best effort within seconds [AW14]; in-flight streams end with `MESH_STREAM_INTERRUPTED` | same |
| Modern Standby entry | display-off → DRAINING [AW18]; after the DAM phase the process is suspended [FW09] | throttled to ≤ 1 s/30 s and network-quiet [FW09][FW09b]; drains on display-off |
| Resume | re-evaluate conditions; the 10-min presence hold-down applies if resume came from user input | same |
| Engine crash (D21a) | process dies; restarts only by user action or at next logon | SCM recovery restarts it (`sc.exe failure asom reset= 86400 actions= restart/60000/restart/60000//`), into ARMED, never directly SERVING; orphaned intents are reconciled on start |

### 3.5 How the user sees that it is running (watched-object rule)

1. **Tray icon** (AWT `SystemTray`, placeholder UI, Invariant 7). The state is a glyph **and** a word, never colour alone (Invariant 6): `○ off`, `◐ armed`, `● lending`, `◌ draining`, `↗ borrowing`. Violet `#8E7BFF` / cyan `#35E0FF` are a secondary cue only. The tooltip shows the peer count and the last ledger event class.
2. `asom status` prints the mode, FSM state, bound address:port or "no listener", firewall rule state, key tier (always "(self-reported)" for T2), overlay status, and the Tailscale log opt-out check (§4.3).
3. **`powercfg /requests`** lists asom's power request while SERVING [FW09]. It is an OS-level surface that asom cannot hide.
4. `services.msc` / `Get-Service asom` (service mode); `wf.msc` shows rules named "asom peer listener (…)".
5. The ledger, via `asom ledger export` (view-first file, as in v1).

---

## 4. Networking

### 4.1 Inbound listener

- **Feasible.** A JSSE `SSLEngine` over `ServerSocketChannel` bound to a **specific** `InetSocketAddress` on the selected interface. The node **never** binds `0.0.0.0`, `::`, loopback or a public address (IC-1). The Tailscale Windows client uses a real adapter (not userspace networking), so T13's loopback-forwarding hazard [F11] does not arise with the standard client. The detector still runs.
- **Interface eligibility.**
  - `asom mesh listen --interface "<alias>"` records the interface's name, index and the addresses at selection time.
  - Overlay eligibility needs the alias the user picked (typically "Tailscale" [AW13]) **and** an address in `100.64.0.0/10` or `fd7a:115c:a1e0::/48`.
  - LAN eligibility needs a user-confirmed interface and an RFC 1918, link-local or ULA address.
  - Every (re)bind re-checks eligibility; a change → DRAINING.
  - `peerPath` comes from the accepted or connected socket's local interface, never guessed.
- **Port squatting:** another process can at most deny service, because peers pin keys (mTLS) and cannot be impersonated. `SO_EXCLUSIVEADDRUSE` behaviour is AW17.

### 4.2 Windows Firewall and the UAC/consent implication

- **The trap [FW05].** If asom listened with no allow rule, an admin user would see the "blocked some features" prompt. Dismissing it creates **block rules for `asom.exe` that override any later allow rule**. A non-admin user gets block rules whatever they choose.
- **Design rule:** the listener **never starts** until the probe finds a matching allow rule **and** no block rule scoped to asom's program path. Otherwise the state is `FIREWALL_RULE_MISSING` (or `FIREWALL_BLOCK_RULE_PRESENT`, with the rule names shown).
- **Rule creation is explicit, elevated and view-first; never done by the installer, never done silently.** `asom mesh firewall enable --interface Tailscale` prints the exact commands and, on the user's typed "yes", launches an elevated PowerShell (`Start-Process -Verb RunAs`, a UAC consent prompt, or admin credentials for a standard user) that runs exactly those commands. `asom mesh firewall print` prints them without applying (Linux parity: "host firewall rule printed, never applied" becomes "printed, applied only on explicit elevated consent"). `asom mesh firewall disable` removes them.
- **The rules (user mode; service mode swaps `-Program` for `-Service asom`):**

```powershell
# overlay path
New-NetFirewallRule -Name 'asom-peer-overlay-in' -DisplayName 'asom peer listener (overlay)' `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 11436 `
  -Program 'C:\Program Files\asom\asom.exe' `
  -InterfaceAlias 'Tailscale' -RemoteAddress 100.64.0.0/10,fd7a:115c:a1e0::/48 `
  -Profile Private -EdgeTraversalPolicy Block `
  -Description 'asom: paired devices only; created by asom mesh firewall enable on <date>'
# confirmed-LAN path (only for an interface the user confirmed)
New-NetFirewallRule -Name 'asom-peer-lan-in' -DisplayName 'asom peer listener (LAN)' `
  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 11436 `
  -Program 'C:\Program Files\asom\asom.exe' `
  -InterfaceAlias '<confirmed alias>' -RemoteAddress LocalSubnet -Profile Private -EdgeTraversalPolicy Block
```

- **Profile consequence.** Rules are Private-only. Tailscale's adapter is Private [FW02], so the overlay rule applies. A home Wi-Fi set to **Public** blocks the LAN listener; `asom doctor` says so and never changes the profile.
- **Disclosure.** Tailscale's Private category also applies *every other* Private-profile allow rule on the machine (for example, file sharing) to the tailnet [FW02]. That is outside asom, but the setup doc says so.
- **What the rule does NOT guarantee.**
  - It narrows who can **open a TCP connection**. It is not the authorisation boundary; the pinned mTLS verifier is (K2).
  - Any process running as `asom.exe` inherits it.
  - A Group Policy that disables local rule merge makes it inert [FW05].
- **Outbound (requester role):** no rule and no prompt. Windows Firewall allows outbound by default [FW05].
- **Local-network permission:** none exists for unpackaged Win32 apps [AW12]. There is no Windows analogue of Android's `ACCESS_LOCAL_NETWORK` or Apple's Local Network privacy.

### 4.3 Private overlay: Tailscale or Headscale on Windows

- **Availability.** The official Windows client runs `tailscaled` as a Windows service with a Wintun adapter. Headscale works with the same client via `tailscale login --login-server <URL>` [FW04].
- **Lending while logged out needs "Run unattended"** (`tailscale set --unattended=true`) [FW03]. Otherwise the overlay drops at logoff and reboot, and a service-mode node with `serveWhileLoggedOut` would find no eligible interface. `asom doctor` reports it.
- **Log upload (Invariant 1 disclosure, D8).** The Windows client uploads its logs to `log.tailscale.com` unless `%ProgramData%\Tailscale\tailscaled-env.txt` contains `TS_NO_LOGS_NO_SUPPORT=true`; the `--no-logs-no-support` flag does not exist on Windows [FW01].
  - `asom doctor` **reads** that file (read-only; asom never edits another product's configuration) and prints: "Tailscale on this PC uploads its own logs to Tailscale Inc. unless you add TS_NO_LOGS_NO_SUPPORT=true to C:\ProgramData\Tailscale\tailscaled-env.txt and restart the Tailscale service. asom cannot see or ledger that traffic."
  - Whether the setting stops every upload under Headscale is **unverified** (A10 extended to Windows; packet-capture test, NOV).
- **What the overlay does not guarantee:** as in §4.4 of the brief (the operator learns the device graph; DERP relays [F09]).

### 4.4 mDNS / DNS-SD

**Not used** (T18; roadmap v4 "no mDNS"). If the owner ever approves "locate already-paired peers", Windows' in-box DNS-SD APIs (`DnsServiceRegister`/`DnsServiceBrowse`) would be used. Their availability and behaviour were **not verified** in this session. **Apple Bonjour for Windows is never bundled**.

---

## 5. Key storage tier for the node identity (NIK)

The NIK is used only to sign the node certificate, the 14-day session leaves (at start and every 7 days) and mesh manifest presentations (§5.3 of the brief). TLS handshakes use the in-memory JVM leaf key, so hardware signing latency is off the request path.

| Tier (`keyStorage`) | Windows mechanism | Protects against | Does **NOT** guarantee |
|---|---|---|---|
| **T2 `tpm`** | CNG **Microsoft Platform Crypto Provider**, `NCryptCreatePersistedKey(BCRYPT_ECDSA_P256_ALGORITHM, "asom-nik-v1")`, export policy 0 (non-exportable), `NCryptSignHash` for signatures, public key via `NCryptExportKey(BCRYPT_ECCPUBLIC_BLOB)` → SPKI. User scope in user mode; machine scope with a key DACL in service mode | Key extraction by software, backups and disk images [FW15]. Cloning: the key cannot move to another machine | **Use of the key** by any code running as the owner (user mode), as `NT SERVICE\asom`, as an admin or as SYSTEM while the machine runs. Attestation: none, because TPM key attestation needs a Microsoft/manufacturer CA path and online checks, which is a new egress (rejected, like App Attest). Survival: **clearing the TPM or some firmware updates destroys the key** → the node must be re-paired everywhere. P-256 support on the owner's TPM (AW01, S-W1). Tier is always shown as "hardware-backed (self-reported)" |
| **T1 `os-keystore`** | CNG **Microsoft Software KSP**, same calls, non-exportable flag | Key bytes never enter asom's process (LSA key isolation) [FW16]. Casual copying. Offline theft of the disk **without** the user's password (DPAPI at rest [FW44]) | Same-user malware **using** the key. Admin/SYSTEM **extracting** it: "non-exportable" is a policy flag [FW44]. **Roaming profiles carry user keys to other machines** [FW16], and T10's clone signal catches only concurrent use |
| **T0 `file`** | PKCS#8 at `%LOCALAPPDATA%\asom\node\nik.p8` (or `%ProgramData%\asom\node\…`), owner-only DACL, optionally wrapped with `CryptProtectData` (user scope, **never** `CRYPTPROTECT_LOCAL_MACHINE` [FW14]) or a passphrase | Other unprivileged users | Same-user code, admin, backups, Volume Shadow Copy and System Restore snapshots; a DPAPI wrap is only as strong as the user's logon credential and roams with roaming profiles [FW14]. **Used only in CI and headless tests** |
| **Rejected: Windows Hello** (`KeyCredentialManager`) | — | — | Every signature needs a PIN or biometric gesture [FW18], which breaks unattended leaf re-minting (trust.md: "NIK never requires user authentication"). Keys are RSA [FW18], against C2 (ES256 only). It also requires a Microsoft or Entra account [FW18], an online-identity dependency (Invariant 8 spirit) |
| **Not chosen: SunMSCAPI** (`Windows-MY`) | Could *read and sign* with CNG EC keys from pure Java [FW19], but cannot create persisted keys, needs a certificate-store entry, and aliases are ambiguous | — | Kept as the **cross-check** in S-W1: sign with NCrypt, verify with JCA, then sign via SunMSCAPI and verify again |

**Selection rule (`NikTierSelector`).** At the first mesh enable (never earlier; the NIK is generated at first mesh enable only):
1. try T2;
2. on a failed create or self-test (sign and verify a 32-byte challenge) fall back to T1;
3. T0 only with `--key-tier file` (CI/headless).

The chosen tier is written to `HELLO.keyTier` and shown in the Peers tab.

**BYOK provider keys: none on Windows in mesh-1** (the design's desktop scope; Invariant 4 is untouched). A key-holding Windows tier needs D14 part B.

---

## 6. Inference backends

| Backend | mesh-1 (D-v2 Windows) | Later | Why |
|---|---|---|---|
| **llama.cpp CPU** via the shared JNI surface (x64 MSVC/clang; ARM64 clang) | **YES** | — | Same GGUF files and pinned commit as Android and Linux, so manifests are comparable (C11) and the router's claims are per file. Built and tiny-model-tested in CI on both architectures [FW35][FW40][FW41] |
| **llama.cpp Vulkan** (x64) | **YES** | — | Vendor-neutral on NVIDIA, AMD and Intel GPUs; the SDK is needed at build time only (installed in CI; not on the image [FW40]) |
| **llama.cpp OpenCL (Adreno, ARM64)** | build only; lending NDV | M2 | Snapdragon X laptops; upstream ships `win-opencl-adreno-arm64` [FW35] |
| **llama.cpp CUDA** | **off** until the licence check (A15, W-D6) | optional artefact | The largest speed-up on NVIDIA desktops (§7.1 GPU row), but redistribution terms, a large binary, and no GPU runner to test on [FW39] |
| llama.cpp HIP/ROCm, SYCL, OpenVINO | no | per owner request | Exist upstream [FW35]; each is another build and validation row |
| **DirectML** | **rejected** | — | Sustained engineering [FW36]; ONNX-graph artefacts, not catalogue GGUF, so results would need separate manifest rows and a second model pipeline |
| **ONNX Runtime / Windows ML** | **rejected for mesh-1** | a separate backend with separate manifest rows, only by owner decision | ONNX artefacts are not the catalogue's GGUF. Windows ML **acquires execution providers through Windows Update at the app's request** [FW37], an OS-mediated download asom would trigger but could not ledger (Invariant 3). NPU EPs need Windows 11 24H2+ [FW37] |
| Foundry Local | not evaluated | — | Not assessed in this session (stated) |

**Engine rules carried over.** In-process JNI only (D21a); single-flight queue; cancellation is bounded by one engine call on GPU backends (B6). On Windows the **thermal signal is weak** [AW06]:
- With no thermal zone counter, the governor uses the throughput-decline detector (B7's drift rule) and the live-state thermal band is reported as `0` with a local "no thermal source" note.
- The **benchmark `standard` and `sustained` plans refuse to start (`NO_THERMAL_SIGNAL`, B14)** on such machines.
- Only `quick` runs, and its report says why.

**Benchmark baseline consequence (D-C, R2-DIRECTIVES-2, R2-OVERCLAIM-4).**
- MLPerf Client v1.6 measures LLM inference on Windows x64 and ARM64 through Windows ML, OpenVINO, llama.cpp and Ryzen AI paths, reporting TTFT and tokens/s [FW38]. asom therefore builds **no** Windows benchmark product.
- The Windows `asom bench` shell exists only to produce the signed manifest and router priors for **asom's own serving path** (llama.cpp CPU/Vulkan).
- For desktop comparability it adopts **MLPerf Client's model set and metric definitions** (TTFT, tokens/s) alongside MLPerf Mobile's for phones, once a spike pins the formulas.
- Reports say "measured with MLPerf Client's model set and metric definitions; not an MLPerf result", **never "MLPerf-comparable"**.

---

## 7. Runtime and code strategy

**Recommendation: Kotlin/JVM, the same `:node-desktop` as Linux, plus one Windows adapter module (`winplatform`) using JNA 5.19.x. No new language, no KMP, no Swift.** Code compiles with `--release 17`. The shipped runtime is a jlinked **Temurin 21 LTS** (the design's conformance lanes are JDK 17 and 21).

**The seam** (defined once in `:node-desktop`; Linux and macOS implement the same interface; sketch):

```kotlin
interface DesktopPlatform {
    val paths: NodePaths                                   // state, run, diag, node-key dirs; applies ACLs
    fun nikStore(tier: KeyTierRequest): NikStore           // create/open/sign(rawRS)/spki/delete; reports keyStorage
    fun power(): PowerPort                                 // status(): PowerStatus; hold(reason): Hold; events(): Flow<PowerEvent>
    fun presence(): PresencePort                           // presenceSignals(): Flow<PresenceSignal> (classified 'presence', LP-1)
    fun gpuContention(): GpuContentionPort?                // null when unavailable → rule off, disclosed
    fun thermal(): ThermalPort?                            // null when no source → NO_THERMAL_SIGNAL for long plans
    fun listenerGate(iface: SelectedInterface): GateResult // Windows: firewall allow/deny probe; Linux: always OPEN + printed rule
    fun controlSocket(): ControlSocketSpec                 // path + ACL; peerIdentity = SO_PEERCRED | ACL
}
```

**Native-access choice (W-D5).**

| Option | Verdict |
|---|---|
| **JNA 5.19.x** (`jna` + `jna-platform`; Apache-2.0 option) | **Chosen.** Compiles on the JDK 17 build container; the fakes run there. jna-platform already covers DPAPI (`Crypt32Util`) and `SetThreadExecutionState` (`Kernel32`); NCrypt, PowrProf, Pdh, Wtsapi32 and Wer need small hand-written interfaces (~400 lines). `jnidispatch.dll` ships in `native\`, loaded with `jna.nounpack=true` [FW43]. **A new third-party dependency: CD-D registry row (D23)** |
| FFM (`java.lang.foreign`) | Later. Final only from JDK 22, so it needs a JDK 25 runtime and a JDK 25 compile lane that the build container lacks; revisit when the desktop runtime moves to 25 (it removes JNA and its DLL) |
| Own C JNI shim | Rejected: C to maintain and sign, and it cannot compile in the container |
| Spawning PowerShell for runtime queries | Rejected for runtime paths (latency, execution-policy variance). Allowed only for the user-visible elevated firewall command (§4.2) |

**Drift cost (estimate).**
- The Windows adapter is about 2–3.5k lines of Kotlin plus about 600 lines of WiX/PowerShell.
- Protocol, router, ledger and manifest code are **shared**, so the conformance families (W/M/R) simply run on Windows JDKs as well. The drift risk is **platform behaviour under shared code**, which the Windows lanes are there to catch:
  1. **Default charset.** JDK 17 on Windows is not UTF-8 [AW20]. Every byte-exact path (JCS, M05 text, JSONL ledger) must pass explicit `UTF_8`; a Windows JDK 17 lane catches regressions.
  2. **Line endings.** `.gitattributes` must mark `lab/conformance/** -text` (binary-exact vectors) and the desktop fixtures; otherwise `core.autocrlf` on the runner rewrites them.
  3. **File semantics.** Windows cannot rename or delete an open file, so JSONL rotation must close before renaming. `FileChannel.force` maps to `FlushFileBuffers`, and durability is claimed for process death only (as elsewhere). Paths are case-insensitive, and the AF_UNIX path must stay under 108 bytes.
  4. **Sockets.** IPv6 scope IDs (`%12`) in `destAddr`, and `SO_EXCLUSIVEADDRUSE` [AW17].
- **What conformance does not catch:** Windows-only branches in the adapter. Those are covered by `@EnabledOnOs(WINDOWS)` integration tests on real APIs (§9) and by the device checklist.

**KMP/Compose note.** A richer desktop UI (Compose for Desktop on the JVM) is not proposed. The tray is the Invariant 7 placeholder; anything more is an owner question, because Compose Desktop's tooling is often configured through the multiplatform plugin, which the KMP ban may be read to cover.

---

## 8. Packaging, signing, distribution and updates

### 8.1 Build pipeline

1. **Runtime.** `jdeps --print-module-deps --ignore-missing-deps --multi-release 21 lib\*.jar` → `jlink --add-modules <list>,jdk.crypto.ec,jdk.net,java.desktop --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output build\runtime`. `java.desktop` is only for the tray; `jdk.net` is for socket options.
2. **App image.** `jpackage --type app-image --name asom --app-version <v> --input build\lib --main-jar node-desktop.jar --main-class xyz.mdhv.asom.desktop.MainKt --runtime-image build\runtime --add-launcher asom-cli=cli.properties` (`cli.properties`: `win-console=true`) `--java-options "-Djna.nounpack=true -Djna.noclasspath=true -Djna.boot.library.path=$APPDIR\..\native -XX:-CreateCoredumpOnCrash"`. Then copy `native\` (llama JNI DLL, ggml backends, `jnidispatch.dll`) and `asom-service.exe` (procrun, renamed) into the image.
3. **Sign every PE** in the image (`signtool sign /fd sha256 /tr <RFC3161 TSA> /td sha256`). Then `verify-signatures.ps1` fails the build if any `*.exe`/`*.dll` shows `Get-AuthenticodeSignature` status ≠ `Valid` [FW26][AW09][AW10].
4. **MSI from our own WiX source** (not `jpackage --type msi`, avoiding FW20/FW21 coupling). Contents:
   - `MajorUpgrade` with a fixed UpgradeCode;
   - feature **Node** (default; files, Start-menu shortcut, WER exclusions [FW34]);
   - feature **Service** (default off; `ServiceInstall Name="asom" Start="demand" Account="NT SERVICE\asom"`, procrun `Parameters` registry values, `%ProgramData%\asom` DACL via `PermissionEx`, `ServiceControl Stop="both" Remove="uninstall"`);
   - **no firewall rules, no autostart, no Tailscale changes.**
   - Sign the MSI.
5. **ZIP (user mode only, evaluation):** the same signed app image. No service; the firewall helper still works.
6. Architectures: `x64` and `arm64` (procrun ships ARM64 since 1.6.0 [FW24]).

### 8.2 Signing (W-D4)

- Unsigned → SmartScreen interstitial; **Smart App Control blocks unsigned executables outright**; enterprise policy can block entirely [FW26].
- EV buys nothing for SmartScreen any more [FW26].
- Ranked routes:
  1. **SignPath Foundation** (free for qualifying OSS [FW27]); keys stay in SignPath's HSM and signing runs from GitHub Actions.
  2. **Azure Artifact Signing** (~US$9.99/mo; **individuals only in the US and Canada** [FW27]).
  3. **OV certificate on an HSM/token** (~US$150–300/yr; ≤ 460-day validity [FW28]; a token cannot sign from hosted CI without a cloud-HSM option).
- Whatever the route, reputation builds over weeks of downloads [FW26]. Release notes must tell early users so, and never tell users to disable Smart App Control.
- **Unsigned builds are published only as CI artefacts, never as releases or winget manifests.**

### 8.3 Distribution and updates

- **GitHub Releases**: MSI (x64, arm64), ZIP, `SHA256SUMS`, and the signer's certificate thumbprint in the release notes.
- **winget**: `asystemofcells.asom` (a new artefact identifier, AF-1 style, owner sign-off). Manifest schema 1.12.0, `InstallerType: wix`, `Scope: machine`, `InstallerSwitches.Custom: ADDLOCAL=Node`, `InstallerUrl` pointing directly at the GitHub release asset [FW30]. The service feature is not selectable through winget (`ADDLOCAL=Node,Service` is documented for manual `msiexec`). Disclosure: winget itself has client telemetry the user can disable [FW31]; that is winget's, not asom's.
- **Updates: no in-app update checker.** An automatic version check would be a background network event with no Invariant 3 class. Updates arrive only when the user runs `winget upgrade asystemofcells.asom` or installs a new MSI. The MSI major upgrade stops the service, preserves `%ProgramData%\asom` and `%LOCALAPPDATA%\asom`, and does not restart the service unless its startup type is automatic.
- **Uninstall** removes the service and the WER exclusions. `asom mesh firewall disable` is offered first; firewall rules created by the elevated helper carry the `asom-*` name prefix, and the uninstaller removes them with a scoped custom action. Node state and keys are removed only with `REMOVESTATE=1`, and the CNG key with `asom key destroy`, so that an upgrade can never silently destroy a paired identity.
- **Microsoft Store:** not used (§2.2). **Windows 10:** out of mainstream support since 2025-10-14, consumer ESU until 2027-10-12 [FW32]. Recommended support target: Windows 11 23H2+; Windows 10 22H2 best-effort until ESU ends (W-D8).

---

## 9. What GitHub-hosted runners can honestly verify

| Item | `windows-2025` (x64) | `windows-11-arm` | Remains NDV/NOV |
|---|---|---|---|
| `lab` conformance families (W/M/R) on Windows JDK 17 and 21 | **yes** (`JAVA_HOME_17_X64`, `JAVA_HOME_21_X64` [FW40]) | JDK 21 only [FW41] | — |
| `:core:*`/`:server` JVM tests (the root `jvmTest`) on Windows | yes (informational; the Linux job stays the gate) | yes | — |
| `winplatform` unit tests with fakes | yes (and in the Linux container) | yes | — |
| `winplatform` **real-API** tests: DPAPI round-trip; Software KSP ECDSA P-256 create/sign/JCA-verify/delete; `PowerCreateRequest`/`PowerSetRequest` plus a `powercfg /requests` string check (admin [FW39]); `GetSystemPowerStatus` parse; PDH counter existence; AF_UNIX socket with DACL; `WerAddExcludedApplication` in HKCU | **yes** | yes | PCP/TPM (no TPM expected [AW11]); real presence/idle; real GPU counters under load |
| llama.cpp JNI build: CPU x64, Vulkan x64 (SDK installed by a pinned step), CPU arm64, OpenCL-Adreno arm64 (compile) | **compile**; CPU tiny-GGUF generation **runs** | compile; CPU tiny-GGUF **runs** | Vulkan, CUDA and OpenCL **execution** (no GPU [FW39]) |
| jlink + app image + MSI (WiX installed by a pinned step) + ZIP | yes | yes | — |
| Signing + `verify-signatures.ps1` | yes, on `main` and tags with signing secrets; forks and PRs build unsigned | yes | SmartScreen and Smart App Control behaviour of the signed release [FW26][AW10] |
| Install smoke: `msiexec /i … ADDLOCAL=Node,Service /qn`, `Start-Service asom`, `asom status` over the control socket, stop, uninstall, check service, rules and WER keys are removed | **yes** (admin, UAC off [FW39]) | yes | real UAC prompt UX for standard users |
| Listener checks: mesh on with the runner NIC confirmed as LAN → `Get-NetTCPConnection -State Listen -LocalPort 11436` shows **only** the NIC address; never `0.0.0.0`, `::` or loopback; `FIREWALL_RULE_MISSING` before the rule exists; rule applied by the helper; `FIREWALL_BLOCK_RULE_PRESENT` when a block rule is injected | **yes** | — | Private/Public profile behaviour on real home networks |
| Two-node smoke on one runner: node A lends on the NIC's IPv4, node B lends on its IPv6 link-local [AW19]; scripted test-only pairing; one borrow each way; row counts on both ledgers (L-L15/L-L16 on JSSE) | **yes** (LAB evidence) | — | cross-machine, overlay and Wi-Fi paths |
| W08 hostile-node suite on the Windows JDK (JSSE) | **yes** (same JSSE; OS socket behaviour differs) | yes | — |
| Tailscale/Headscale, `TS_NO_LOGS_NO_SUPPORT` effect, pktmon on Wintun [AW08] | no (no tailnet in CI) | no | **NOV** |
| Sleep, resume, lid, Modern Standby, battery, battery saver, QoS on battery | no (VM) | no | **NDV** |
| Real TPM key tier (S-W1), service-account key (S-W2 with PCP) | no | no | **NDV** |
| winget manifest | schema validation against the 1.12.0 JSON schemas (any OS); `winget validate` on a Windows client when available | — | community-repo acceptance (NOV) |

**Gate wording rule.** Every CI result above is recorded in PROGRESS.md as **"CI (hosted VM) evidence"**. Device items stay `NEEDS-DEVICE-VALIDATION` until the owner pastes real output. If Windows lends in M1, the M1 row gains (R2-DIRECTIVES-11 analogue):
- W08 on the Windows JDK;
- the S-W1/S-W2 outcomes recorded;
- the firewall and Tailscale-unattended checks;
- M1 gate 5 (quiescence) on a Windows borrower. If pktmon cannot see the Wintun adapter [AW08], that item stays NDV and is **never passed by a weaker method** (R2-OVERCLAIM-5).

---

## 10. Implementation scaffold plan (`desktop/packaging/windows/`)

**Entry rule.** Step W0 is lab-only CI (AD-4/AD-5, ships nothing) and may start with the lab. W1 onward is D-v2 work: it starts only when D-v2's entry criteria hold (v2 shipped on Android; D4; the v4 design session held and recorded, R2-CONFORMANCE-3; D23 for new modules and deps). Everything Windows-specific lives under this one directory, so the owner can drop Windows by deleting it. Outside it the builder adds only:
- one `include` line in `desktop/settings.gradle.kts`;
- one workflow file;
- `.gitattributes` entries.

### 10.1 File tree

```
desktop/packaging/windows/
├── README.md                                   what this builds, modes, gates, owner inputs, what is NOT guaranteed
├── winplatform/                                Gradle subproject :packaging:windows:winplatform (pure JVM, no android.*)
│   ├── build.gradle.kts                        kotlin-jvm, --release 17; deps: :node-desktop (DesktopPlatform), jna, jna-platform (pinned 5.19.x)
│   └── src/
│       ├── main/kotlin/xyz/mdhv/asom/desktop/win/
│       │   ├── WinPlatform.kt                  DesktopPlatform impl; wires the ports below; mode = USER | SERVICE
│       │   ├── WinPaths.kt                     %LOCALAPPDATA%\asom / %ProgramData%\asom layout; DACL apply/verify (AclFileAttributeView)
│       │   ├── NodeMutex.kt                    Global\asom-node single-identity guard
│       │   ├── jna/NCrypt.kt                   NCryptOpenStorageProvider/CreatePersistedKey/SetProperty/FinalizeKey/SignHash/ExportKey/DeleteKey/FreeObject
│       │   ├── jna/PowrProf.kt                 PowerRegisterSuspendResumeNotification, PowerSettingRegisterNotification
│       │   ├── jna/Kernel32Power.kt            PowerCreateRequest/PowerSetRequest/PowerClearRequest, GetSystemPowerStatus
│       │   ├── jna/Pdh.kt                      PdhOpenQuery/AddEnglishCounter/CollectQueryData/GetFormattedCounterArray
│       │   ├── jna/Wts.kt                      WTSEnumerateSessions, WTSQuerySessionInformation(WTSSessionInfoEx)
│       │   ├── jna/UserInput.kt                GetLastInputInfo, SHQueryUserNotificationState
│       │   ├── jna/Wer.kt                      WerAddExcludedApplication (used by the tests; the MSI writes HKLM)
│       │   ├── keys/NcryptNik.kt               T2 (PCP) / T1 (Software KSP) NikStore; raw r||s; SPKI builder; key DACL (service mode)
│       │   ├── keys/FileNik.kt                 T0 PKCS#8 + optional CryptProtectData (user scope only)
│       │   ├── keys/NikTierSelector.kt         T2 → self-test → T1 → (T0 only by flag); records keyStorage
│       │   ├── power/WinPowerPort.kt           PowerStatus (source, charging, batteryBand, saver); hold("asom: lending compute to your paired devices")
│       │   ├── power/SuspendWatcher.kt         PBT_APMSUSPEND / display-off → os_sleep_imminent; resume → re-evaluate
│       │   ├── presence/WinPresencePort.kt     user mode: idle/locked/fullscreen; service mode: WTS console state; all tagged 'presence' (LP-1)
│       │   ├── gpu/GpuEngineProbe.kt           PDH GPU Engine per-pid utilisation → other_busy permille; null if counters missing
│       │   ├── thermal/ThermalZoneProbe.kt     PDH Thermal Zone Information; null if absent/static (→ NO_THERMAL_SIGNAL)
│       │   ├── net/InterfaceEligibility.kt     selected-alias + range checks; never loopback/wildcard/public; peerPath from socket
│       │   ├── net/FirewallGate.kt             read rules (netsh parse; COM INetFwPolicy2 later); allow present? block present? → GateResult
│       │   ├── net/FirewallCommand.kt          renders the exact New-NetFirewallRule text; elevated apply after typed consent
│       │   ├── ctl/WinControlSocket.kt         AF_UNIX path + DACL; peerIdentity = ACL; client-side owner/DACL precheck
│       │   ├── service/ServiceEntry.kt         procrun start(String[])/stop(String[]) static entry; drain within 30 s
│       │   ├── tray/TrayUi.kt                  AWT SystemTray placeholder; glyph+label states; companion mode reads status via socket
│       │   └── doctor/WinDoctor.kt             checks: firewall, profile, LIDACTION, Tailscale unattended + tailscaled-env.txt, key tier, thermal/GPU probes
│       └── test/kotlin/xyz/mdhv/asom/desktop/win/
│           ├── fakes/                          FakeNcrypt, FakePower, FakePresence, FakePdh, FakeFirewall (run on any OS)
│           ├── NikTierSelectorTest.kt          fallback order; keyStorage labels; never T0 without flag
│           ├── FirewallGateTest.kt             missing/allow/block/foreign-block matrices; listener never starts on MISSING
│           ├── PresenceLawsTest.kt             W07-presence on Windows inputs (LP-1/LP-2)
│           ├── StdoutSecretTest.kt             no token/row on stdout/stderr (H3)
│           └── windows/                        @EnabledOnOs(WINDOWS): DpapiIT, SoftwareKspIT, PowerRequestIT, PdhIT, AfUnixAclIT, PcpIT (skips if no TPM)
├── native/
│   ├── CMakePresets.json                       win-x64-cpu, win-x64-vulkan, win-x64-cuda (disabled), win-arm64-cpu, win-arm64-opencl
│   └── build-llama-jni.ps1                     builds the shared JNI surface at the pinned llama.cpp commit; sha256-lists outputs
├── service/
│   └── procrun-parameters.reg.template         StartMode=jvm, Jvm path, classes, StopTimeout=35, no StdOutput/StdError
├── wix/
│   ├── asom.wxs                                MajorUpgrade; features Node (default) / Service (off); ServiceInstall/Control; WER exclusions; ProgramData DACL
│   ├── AppImage.wxs                            generated file harvest of the signed app image (script output, not hand-edited)
│   └── License.rtf                             Apache-2.0 text
├── scripts/
│   ├── build-app-image.ps1                     jdeps → jlink (Temurin 21) → jpackage --type app-image → copy native\ + asom-service.exe
│   ├── sign.ps1                                SignPath/Artifact Signing/OV adapter; signs every PE then the MSI
│   ├── verify-signatures.ps1                   fails if any PE in the image or MSI payload is not Valid
│   ├── build-msi.ps1                           wix build asom.wxs AppImage.wxs -arch x64|arm64
│   ├── smoke-install.ps1                       msiexec install → service start → status → listener checks → stop → uninstall → residue checks
│   ├── smoke-two-node.ps1                      two nodes on one runner (IPv4 NIC + IPv6 link-local), scripted test pairing, borrow each way, row asserts
│   └── collect-evidence.ps1                    writes the transcript block for PROGRESS.md, labelled CI (hosted VM) evidence
├── winget/
│   └── manifests/a/asystemofcells/asom/0.0.0-template/
│       ├── asystemofcells.asom.yaml            version manifest 1.12.0
│       ├── asystemofcells.asom.installer.yaml  InstallerType wix, Scope machine, x64 + arm64, ADDLOCAL=Node
│       └── asystemofcells.asom.locale.en-US.yaml
├── ci/
│   └── desktop-windows.yml                     canonical workflow (copied to .github/workflows/ by the builder)
└── docs/
    ├── WINDOWS_NODE.md                         user guide: modes, firewall consent, Tailscale log opt-out, sleep/lid, what is NOT guaranteed
    └── DEVICE_CHECKLIST_WINDOWS.md             NDV/NOV items with the exact commands and expected outputs
```

Outside the directory (stated, minimal):
- `desktop/settings.gradle.kts`: `include(":packaging:windows:winplatform")`;
- `.github/workflows/desktop-windows.yml` (a copy of `ci/desktop-windows.yml`; the existing jobs are untouched);
- `.gitattributes`: `lab/conformance/** -text` and `desktop/**/fixtures/** -text`.

### 10.2 Workflow sketch (`ci/desktop-windows.yml`)

```yaml
name: desktop-windows
on: { push: { branches: [main] }, pull_request: {} }
permissions: { contents: read }
jobs:
  lab-windows:                      # W0: lab conformance on Windows JDKs (ships nothing)
    runs-on: windows-2025
    strategy: { matrix: { jdk: ['17', '21'] } }
    env: { ANDROID_HOME: '', ANDROID_SDK_ROOT: '' }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '${{ matrix.jdk }}' }
      - run: ./gradlew.bat -p lab labTest --stacktrace
      - run: git ls-files --eol lab/conformance | Select-String -NotMatch 'attr/-text' | Measure-Object | % { if ($_.Count -gt 0) { exit 1 } }   # every vector must carry the -text attribute
  winplatform:                      # W1-W2
    runs-on: ${{ matrix.os }}
    strategy: { matrix: { os: [windows-2025, windows-11-arm] } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: ./gradlew.bat -p desktop :packaging:windows:winplatform:test --stacktrace
  package-and-smoke:                # W3-W6 (x64 shown; arm64 mirrors it without Vulkan)
    runs-on: windows-2025
    needs: winplatform
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: pwsh desktop/packaging/windows/native/build-llama-jni.ps1 -Preset win-x64-cpu,win-x64-vulkan
      - run: pwsh desktop/packaging/windows/scripts/build-app-image.ps1
      - run: pwsh desktop/packaging/windows/scripts/build-msi.ps1 -Arch x64
      - run: pwsh desktop/packaging/windows/scripts/smoke-install.ps1
      - run: pwsh desktop/packaging/windows/scripts/smoke-two-node.ps1
      - uses: actions/upload-artifact@v4
        with: { name: asom-windows-x64-unsigned, path: desktop/packaging/windows/build/out/* }
```

The Vulkan SDK and WiX install steps are pinned by version and checksum inside the scripts (neither is preinstalled on every image [FW40][FW41]). Signing runs in a separate job on `main` and tags only.

### 10.3 Steps, gates and expected output

| Step | Deliverable | Gate (real command → expected output; pasted into PROGRESS.md) |
|---|---|---|
| **W0** (lab, now under AD-4) | `lab-windows` job + `.gitattributes` | `./gradlew.bat -p lab labTest` on JDK 17 and 21 → `BUILD SUCCESSFUL`, with the **same test count as the Linux lab lane**; the eol check prints nothing. A deliberate default-charset regression injected in a branch **fails** the JDK 17 lane (proves the lane catches AW20) |
| **W1** | `winplatform` skeleton, `DesktopPlatform` impl, fakes, JNA interfaces | Linux container: `./gradlew -p desktop :packaging:windows:winplatform:test` → `BUILD SUCCESSFUL`, Windows-only ITs reported **skipped**; `windows-2025`: same command → ITs **run and pass** (DPAPI, Software KSP, power request with `powercfg /requests` containing "asom: lending compute", PDH, AF_UNIX DACL) |
| **W2** | NIK tiers + `NikTierSelector` | `SoftwareKspIT`: 1,000 sign/verify round-trips with JCA `SHA256withECDSA` over r‖s→DER → 0 failures; `PcpIT` on the runner → `SKIPPED: no TPM` [AW11]; S-W1 on the owner machine → NDV row with `keyStorage=tpm`, sign latency p50/p95 |
| **W3** | native JNI builds | `build-llama-jni.ps1` → `asom-llama-jni.dll` plus backend DLLs for each preset with sha256 lines; `TinyModelGenerationIT` (pinned tiny GGUF fetched by URL+sha256 at CI time, never committed) → deterministic greedy output equal to the Linux lane's vector. The Vulkan build compiles; **execution is NDV** |
| **W4** | app image + MSI + signing | `build-msi.ps1` → `asom-<v>-x64.msi`; `verify-signatures.ps1` on a signed build → `0 files NotSigned/HashMismatch`; on an unsigned PR build → the job labels its artefact `UNSIGNED — not for release` |
| **W5** | service + install smoke | `smoke-install.ps1` → `Get-Service asom` Status `Running` after `Start-Service`; `asom-cli status` prints `mode=service state=OFF listener=none keyTier=os-keystore`; `Get-NetTCPConnection -State Listen -OwningProcess <pid>` → **no rows**; after `mesh listen --interface Ethernet --confirm-lan` without a rule → `FIREWALL_RULE_MISSING`; after the helper → exactly one listening row on `<NIC IPv4>:11436`; an injected block rule → `FIREWALL_BLOCK_RULE_PRESENT`; `Stop-Service` → a `SESSION` close row count equal to the open count; `msiexec /x` → service absent, `asom-*` rules absent, WER keys absent, `%ProgramData%\asom` retained |
| **W6** | two-node smoke + W08 on the Windows JDK | `smoke-two-node.ps1` → A→B and B→A borrow each succeed; per-node ledger asserts: intent/outcome pairs, `DIAL` before SYN (L-L14), L-L15 byte sums equal to the record tap; W08 suite `BUILD SUCCESSFUL`. Recorded as **LAB/CI evidence — not device evidence** |
| **W7** | winget template + docs + device checklist | JSON-schema validation of the three YAML files against manifest schemas 1.12.0 → 0 errors; `DEVICE_CHECKLIST_WINDOWS.md` lists every NDV/NOV item from §9 with command and expected output |
| **W8** (owner) | device validation | S-W1…S-W8; sleep/lid/Modern Standby matrix; a Vulkan 8B decode/prefill measurement replacing the §7.1 estimate for a Windows lender; Tailscale unattended + log opt-out packet capture; a signed release installed on a Smart App Control machine → each item pasted as NDV/NOV evidence or left open |

### 10.4 Effort (engineer-weeks; estimate, not measurement)

| Work | Weeks |
|---|---|
| `winplatform` (keys, power, presence, PDH probes, paths/ACL, control socket, firewall gate, doctor) | 3–5 |
| Service (procrun) + WiX MSI (two features, x64 + arm64) + signing pipeline | 2–3 |
| Native llama.cpp JNI Windows builds and CI (CPU/Vulkan x64, CPU/OpenCL arm64) | 1.5–3 |
| Tray placeholder UI and companion mode | 0.5–1 |
| CI smokes (install, listener, two-node, W08 lane) | 1.5–2.5 |
| winget, docs, device checklist, support for owner validation | 1–1.5 |
| **Total (Windows-specific)** | **≈ 10–16** |

**Assumptions behind the estimate:**
- `:node-desktop`, the JNI engine surface, the JSONL ledger, the CLI and the lab conformance suite already exist from the Linux D-v2 work (not counted here);
- one engineer familiar with Kotlin and basic Win32;
- the owner provides one Windows machine with a TPM 2.0 and a GPU;
- signing onboarding time (SignPath or Artifact Signing identity validation) is calendar time, not engineering time;
- no Store submission;
- CUDA excluded.

This is **not** in the brief's 72–122-week mesh total (which excluded Windows); the reviser should add it.

---

## 11. Owner decisions and risks

### 11.1 Owner decisions specific to Windows

| ID | Question | Options | Recommendation |
|---|---|---|---|
| W-D1 | Is Windows a mesh-1 **lender**, or requester-only until M2? | (a) requester-only in mesh-1; lends from M2. (b) lender in mesh-1 **if the Dell runs Windows**, adding the Windows M1 gates (§9). (c) no Windows until M2 | **(b) if the Dell runs Windows; otherwise (a).** Windows lending needs no invariant text beyond the Amendment 2/3 package; its costs are the firewall consent step and the service |
| W-D2 | Default hosting mode | (a) user mode default, service opt-in. (b) service default. (c) user mode only | **(a)**: least privilege by default; the service only for "serve while logged out" hosts |
| W-D3 | Who creates the firewall rule? | (a) the installer at install time. (b) an explicit elevated, view-first `asom mesh firewall enable`. (c) print-only | **(b)**: consent before exposure. (a) opens a port path for users who never lend; (c) invites the prompt-cancel block-rule trap [FW05] |
| W-D4 | Code-signing route | (a) SignPath Foundation (free OSS). (b) Azure Artifact Signing (US/CA individuals, or an org in US/CA/EU/UK). (c) OV certificate on HSM/token. (d) unsigned | **(a), else (b) if eligible, else (c). Never (d) for releases**: Smart App Control blocks unsigned files [FW26] |
| W-D5 | Native access layer | (a) JNA 5.19.x (new dependency, CD-D row). (b) FFM with a JDK 25 runtime. (c) own C JNI shim | **(a)** now; (b) when the desktop runtime moves to 25 |
| W-D6 | Ship CUDA builds? | (a) CPU + Vulkan only. (b) add CUDA after the licence check (A15). (c) CUDA only | **(a)** in mesh-1; (b) after the licence check and an owner GPU measurement |
| W-D7 | Installer toolchain and WiX licence | (a) own WiX v6/v7 source (OSMF applies only if the owner's use generates revenue above US$10k/yr [FW23]). (b) WiX 3.14 (preinstalled on runners, **no security fixes since 2025-02-06** [FW22]). (c) no MSI: ZIP plus a PowerShell installer | **(a)**, with the owner confirming OSMF non-applicability; (c) as the evaluation artefact only |
| W-D8 | Windows versions supported | (a) Windows 11 23H2+ supported; Windows 10 22H2 best-effort until ESU ends (2027-10-12). (b) Windows 11 only. (c) Windows 10 fully supported | **(a)** |
| W-D9 | Windows on ARM (Snapdragon X) | (a) build and CI-test in mesh-1; lending only after device validation. (b) x64 only | **(a)**: the runner exists [FW39]; procrun ships ARM64 [FW24] |
| W-D10 | winget distribution and identifier `asystemofcells.asom` | (a) yes, signed releases only. (b) GitHub Releases only | **(a)**: a new artefact identifier, not a package rename; owner sign-off like AF-1 |
| W-D11 | Local-caller identity on Windows (socket DACL only, no peer credentials) | (a) accept as the Windows edition of CD-24, named in the D2/D25 ruling. (b) require SIO_AF_UNIX_GETPEERPID via native code before M1 | **(a)**, stated as weaker than Linux `SO_PEERCRED` (R2-CONFORMANCE-6) |

### 11.2 Risks

| # | Risk | Severity | Mitigation | Residual (stated) |
|---|---|---|---|---|
| RW1 | A Windows **laptop** is presented as an always-on lender but vanishes on lid close or Modern Standby | high | Role table says "conditional"; FSM drains on `PBT_APMSUSPEND` and display-off; `asom doctor` shows `LIDACTION` and the exact powercfg command, never applied; the Peers tab says "lends while awake" | The user can still close the lid mid-stream → `MESH_STREAM_INTERRUPTED` |
| RW2 | Unsigned or low-reputation installer blocked by Smart App Control or SmartScreen; enterprise policy blocks it | high | Sign every PE and the MSI; verify in CI; warn early adopters | Reputation takes weeks [FW26]; AW10 unverified |
| RW3 | Firewall prompt dismissed → block rules that override asom's allow rule [FW05] | medium | Never listen before the rule exists; detect and name block rules; the helper can remove them with consent | GPO-managed machines may forbid local rules |
| RW4 | Tailscale uploads its logs unless the env file is set [FW01] | high (privacy) | `asom doctor` reads and reports it; setup doc; D8 disclosure | Out of asom's control; the Headscale behaviour is unverified |
| RW5 | Tailscale's Private category applies unrelated Private allow rules to the tailnet [FW02] | medium | Disclose; asom's rules are interface- and range-scoped | Other apps' exposure is not asom's to fix |
| RW6 | TPM key unsupported, locked out, or destroyed by a TPM clear or firmware update | medium | Tier fallback to T1; the self-test at creation; re-pair procedure documented | Identity loss forces re-pairing on every peer |
| RW7 | JNA extracts its DLL to temp or loads from a user-writable path (C13) | medium | `jna.nounpack` + `noclasspath` + boot path in `Program Files`; a test asserts no temp extraction | Admin-level tampering remains possible |
| RW8 | Crash data carrying prompt text leaves the machine through WER, or lies on disk in minidumps | medium (Invariant 1) | `-XX:-CreateCoredumpOnCrash`; `ErrorFile` in the state dir; WER exclusion of asom's executables [FW34] | OS-wide diagnostic data, Defender cloud protection and Windows Update are outside asom's control; disclosed |
| RW9 | Default charset or CRLF drift breaks byte-exact vectors on Windows [AW20] | medium | JDK 17 Windows lane; explicit UTF-8; `.gitattributes -text` | Code paths untested by vectors |
| RW10 | No thermal signal on most Windows PCs [AW06] | medium | Throughput-decline detector; long benchmark plans refuse (`NO_THERMAL_SIGNAL`); the thermal band is disclosed as estimated | Heat-related slowdowns are detected late |
| RW11 | Local-caller identity is ACL-only (no peer credentials) [AW04] | medium | DACL on the socket directory; client-side owner check; W-D11 names it | Same-user processes are indistinguishable (as on Linux, plus TOCTOU) |
| RW12 | Service-mode key and state ACLs are wrong (the service cannot sign, or the owner can read keys) | medium | S-W2; smoke asserts the ACLs; tier self-test at start | Administrators can always take ownership |
| RW13 | AV false positives on the JNI DLLs or procrun break winget validation [FW30] | medium | Signing; false-positive submissions; reproducible builds | Occasional delays |
| RW14 | Pressure to adopt Windows ML or DirectML for NPU speed | low | Rejected for mesh-1 (Invariant 3, artefact mismatch, sustained engineering) [FW36][FW37]; a later backend only as separate manifest rows by owner decision | NPU performance forgone |
| RW15 | Windows 10 users after ESU | low | Best-effort only; installer warns on Windows 10 | — |
| RW16 | Estimates and Windows-lender speed | medium | Nothing quoted until an owner-device measurement (D-v2 gate); CPU-only Windows desktops may be no faster than the phone (§7.1) | — |

### 11.3 What this section does NOT guarantee

- That a Windows machine lends whenever it is powered: sleep, lid close and Modern Standby end lending, and **no application can prevent user-initiated sleep** [FW07][FW08].
- That the firewall rule, the key tier or the service account make a compromised Windows machine safe. They narrow exposure; an admin, SYSTEM, or same-user malware can use the node's identity.
- That traffic outside asom's process is ledgered. Tailscale logs, winget telemetry, Windows diagnostics, Windows Update, SmartScreen and Defender cloud lookups are the OS's or the vendor's, and are disclosed, not controlled.
- That hosted-CI results describe a real device. They are VM evidence; every device behaviour listed in §9 stays NDV until the owner runs it.
