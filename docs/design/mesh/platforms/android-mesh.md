# Platform section: Android as a mesh node (delta from v1)

**Date:** 2026-09-30 · **Grade:** DIRECTION (an input to the roadmap v4 design session, to M1 and to M2; nothing here authorises execution) · **Scope:** the existing Android daemon `xyz.mdhv.asom` (v1, unvalidated on hardware) as a requester in mesh-1 and as a lender later · **Target directory:** none. **Design-only until v1 device validation closes.** Nothing below proposes touching `app/` (or any other shipped module) before then.
**Reads with:** `OWNER_DIRECTIVES_2026-09-30.md` (D-A…D-F, AD-1…AD-6, treated as decided), `ASOM_MESH_DESIGN.md` r2 (§2.1 roles, §3.1–§3.3, §4.2 T2/T5/T9/T12–T16, §5.3/§5.5, §7.4 LP-1/LP-2, §8.2 IC-1–IC-4, §8.4, §8.6, §9.2 H4/H5, §9.3 M1/M2), `REVIEW_ROUND2.md`.
**Tags:** `[AFnn]` = verified in this session (§1.1, source and date). `[AAnn]` = assumption (§1.2). `[Fnn]`/`[Ann]` = the design brief's Appendix A/B ids. `SIGN-OFF` = needs the owner. Every threshold marked PROVISIONAL is a starting value, not a measurement.

**The answer in brief.**
- **The brief's open loopback item is settled as far as source code can settle it: the Android 17 local-network permission does NOT cover `127.0.0.1`.** In the published Android 17 source (tag `android-17.0.0_r1`), the check is a per-interface prefix lookup whose entries are built only from the interfaces of real networks (Wi-Fi, cellular, VPN, Ethernet). The loopback interface never gets an entry, so loopback traffic always passes that check [AF05]. **But Android 17 adds a different loopback rule for all apps, whatever their targetSdk: cross-profile loopback is blocked** (work profile, and probably Private Space) [AF04]. The code also reserves a `USE_LOOPBACK_INTERFACE` permission bit with a TODO to check it for sender and receiver [AF05]. That is a signal of direction, and v1's frozen API is exactly the cross-app loopback pattern the 2025 localhost-tracking abuse used [AF36]. The H5 "no targetSdk 37 bump" rule stays, downgraded from "unknown, critical" to "confirm on emulator and device". A new long-horizon risk is added (R-A1).
- **Overlay peers are not "local network" under the Android 17 rules; same-subnet LAN peers are.** Android 17 marks only each interface's own on-link subnet as local. A point-to-point VPN interface marks only its own address. Tailscale gives single-IP node addresses [AF23], so a phone dialling a tailnet peer needs no `ACCESS_LOCAL_NETWORK` even at targetSdk 37. Dialling or accepting on the same Wi-Fi subnet does need it [AF02][AF05]. The public 2025 `main` branch used a broader rule that would have caught the whole `100.64.0.0/10` block [AF06], so the rule moves between releases. The design treats "permission not needed" as a fact that must be rechecked each release, never as a guarantee.
- **Doze does not cut a foreground service off, on AOSP.** Android 17 AOSP code keeps network and partial wake locks for any process at foreground-service state or higher while the device is idle [AF12]. The developer page is silent on this [AF11], and OEM power managers (the owner's RedMagic has no dontkillmyapp entry [AF35]) remain NEEDS-DEVICE-VALIDATION. **Unplugged lending is still rejected** for battery and heat, not for Doze.
- **Roles: the phone is a complete holon.** It serves its own apps alone (v1 cloud BYOK, v2 local engine) and **borrows in mesh-1** (outbound only, no listener). It **lends later** (after v3, per AD-1) in two shapes: *lend-while-charging* (screen off, an explicit opt-in, and the T9 "serve while locked" exception) and *lend-screen-frontmost*. **It is never an always-on provider.** A NO here is honest: it drains on the owner picking it up, on heat and on unplugging, and it depends on a charger.
- **The `specialUse` FGS is a sound host for the lender's listener.** It has no runtime prerequisites, no time cap, and it is not in Android 15's boot-start ban list [AF07][AF08]. The only external review is Play's, which does not apply to sideload or F-Droid. `dataSync`, `mediaProcessing` (6 h per 24 h) and `connectedDevice` (Bluetooth/USB/NFC permissions) are all wrong for this.
- **Wi-Fi "high-performance" locks no longer exist in practice.** From API 34 `WIFI_MODE_FULL_HIGH_PERF` silently becomes the low-latency lock, which works only with the screen on and the app in the foreground [AF15]. A screen-off lender gets no Wi-Fi lock at all; only the lend-screen shape benefits.
- **Thermal:** use `addThermalHeadroomListener` on API 36+ (callbacks at most every 5 s, only on changes of 0.03 or more [AF14]). Poll at most every 10 s on API 30–35 [AF13]. On API 29 (the current minSdk) only the status and its listener exist.
- **Tailscale on Android now has a log-upload switch.** tailscale-android added "Remote client logging" on 2026-04-20 (first in 1.97.331, stable from 1.98). It is **on by default and forced on when the device is MDM-managed** [AF21]. The Tailscale KB still documents no Android opt-out [AF20]. D8's disclosure changes from "no opt-out" to "off only if the user turns it off; asom cannot check".
- **Key attestation without Play services:** chain, roots, extension and RKP validity can be checked offline. Revocation cannot, because the status list is a Google URL [AF18] and fetching it is not a permitted egress class. Attestation proves where a key was generated and how the bootloader looked then, not that asom is unmodified now. A2 stays deferred (D12).
- **CI can compile, unit-test, lint and APK-check every Android module on `ubuntu-latest`,** and run a KVM emulator at API 35–37 [AF28][AF29][AF30]. That gives LNP, loopback, Doze-forcing, thermal-override and Conscrypt handshake tests as **EMULATOR EVIDENCE**. StrongBox, real attestation roots, OEM power policy, heat, radio power and the owner's network stay NEEDS-DEVICE-VALIDATION.

---

### 1. Verified platform facts (each with a source URL and date) and Assumptions

#### 1.1 Verified facts

All sources were fetched, cloned or searched on **2026-09-30** unless another date is given. "(page date)" is the date the page itself carries. AOSP facts come from reading published source at the named tag; they say what that code does, not what every shipped device does.

| ID | Fact | Source (source date) | Conf. |
|---|---|---|---|
| AF01 | Android 17 stable was released 2026-06-16; API level **37**; codename Cinnamon Bun. AOSP publishes tags `android-17.0.0_r1` (Connectivity module commit `347fbd34`, 2026-06-03) and branches `android17-release`, `android17-security-release`. The public `main` branch of the Connectivity repo was last updated 2025-03-27 | https://en.wikipedia.org/wiki/Android_17 ; `https://android.googlesource.com/platform/packages/modules/Connectivity/+refs` (listed via `?format=JSON`) | high |
| AF02 | Local network permission page (last updated 2026-07-13): Android 16 "Temporarily used `NEARBY_WIFI_DEVICES`", opt-in by `adb shell am compat enable RESTRICT_LOCAL_NETWORK <package_name>` then reboot; Android 17: `ACCESS_LOCAL_NETWORK`, "mandatory and enforced for apps targeting Android 17 or higher". Covered operations: "Making an outgoing TCP connection — yes", "Accepting an incoming TCP connection — yes", UDP send and receive, mDNS `.local` resolution, `NsdManager`. `NsdManager` offers a system picker (`DiscoveryRequest.FLAG_SHOW_PICKER`) after which the app can connect to the picked host "without ACCESS_LOCAL_NETWORK permission". Apps targeting < 37 "receive an implicit permission grant" through `INTERNET`; for them: "don't add ACCESS_LOCAL_NETWORK to your manifest or request it at runtime". Exceptions: a local DNS server on port 53; Output Switcher. **The page does not mention loopback, localhost or VPN interfaces** | https://developer.android.com/privacy-and-security/local-network-permission (2026-07-13) | high (for what the page says) |
| AF03 | Android 17 behaviour changes for apps targeting 37 (page 2026-09-16): `ACCESS_LOCAL_NETWORK` is in the `NEARBY_DEVICES` group, and "users who have already granted other `NEARBY_DEVICES` permissions aren't prompted again"; **certificate transparency is enabled by default** at targetSdk 37; **"Safer Native DCL"**: "All native files loaded using `System.load()` must be marked as read-only. Otherwise, the system throws `UnsatisfiedLinkError`"; **ECH** is used for TLS at targetSdk 37 when the networking library supports it, with a new `<domainEncryption>` element in the Network Security Configuration | https://developer.android.com/about/versions/17/behavior-changes-17 (2026-09-16) | high |
| AF04 | Android 17 behaviour changes for **all apps** (page 2026-09-16): "**Beginning with Android 17, cross-profile loopback traffic is no longer permitted by default. Loopback traffic within the same profile is not affected.** This change applies to all apps running on Android 17 or higher, regardless of what API level the app targets." Also: **app memory limits** "based on the device's total RAM", visible as exit reason `REASON_OTHER` with "MemoryLimiter:AnonSwap", adjustable with `am memory-limiter ignore/manual/status`; a per-app Keystore limit of 50,000 keys at targetSdk 37; a plan to deprecate `usesCleartextTraffic` "in a future release" in favour of the Network Security Configuration | https://developer.android.com/about/versions/17/behavior-changes-all (2026-09-16) | high |
| AF05 | **AOSP `android-17.0.0_r1`, local network protection (LNP) implementation.** `bpf/progs/netd.c`: the LNP decision is a longest-prefix lookup in `local_net_access_map` keyed by (interface index, remote IP, protocol, remote port); **"no entry" means allowed** (`if (!v || *v) return true`). `ConnectivityService.updateLocalNetworkAddresses()` fills that map only for the interfaces in a network agent's `LinkProperties` (Wi-Fi, cellular, VPN, Ethernet), so **the loopback interface (`lo`, ifindex 1) never gets an entry**. `getEffectiveLocalPrefixes()` marks each interface address's **on-link prefix** as local when it is IPv6 with a non-zero prefix length, or IPv4 inside `IPV4_LOCAL_PREFIXES` = {169.254/16, **100.64/10**, 10/8, 172.16/12, 192.168/16}, plus multicast and broadcast. Route-based rules run only behind the beta flag `lnpDeveloperOptIn` and only on links with a gatewayed route; the code states "On point-to-point links such as cellular and VPNs, assume that nothing is local except the subnets corresponding to local IP addresses". LNP is **not applied** while a global or per-network HTTP proxy is set. A **separate cross-profile loopback check** (flag `useLoopbackInterfacePermissionEnabled`) allows loopback when "`sender_uid / AID_USER_OFFSET == receiver_uid / AID_USER_OFFSET`" (same profile); a permission bit `PERMISSION_USE_LOOPBACK_INTERFACE` exists, with the comment "TODO: check loopback interface permissions for both sender and receiver" | https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-17.0.0_r1/bpf/progs/netd.c ; `…/service/src/com/android/server/ConnectivityService.java` ; `…/service/src/com/android/server/BpfNetMaps.java` ; `…/staticlibs/framework/com/android/net/module/util/NetworkStackConstants.java` (tag commit 2026-06-03) | high (for what the code at the tag does); medium (for every shipped build: OEMs and mainline updates can differ) |
| AF06 | The Connectivity **public `main` (2025-03-27)** used a broader IPv4 rule: an interface address inside a block in `IPV4_LOCAL_PREFIXES` added **the whole block** (for example all of 100.64.0.0/10) as local on that interface. The rule therefore changed between that snapshot and Android 17 r1 | `https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/service/src/com/android/server/ConnectivityService.java` (`getLocalNetworkPrefixesForAddress`) | high (for that snapshot) |
| AF07 | FGS type `specialUse`: permission `FOREGROUND_SERVICE_SPECIAL_USE`; constant `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`; **"Runtime prerequisites: None"**; "Covers any valid foreground service use cases that aren't covered by the other foreground service types"; the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text is "reviewed when you submit your app in the Google Play Console". No time limit is stated for `specialUse` | https://developer.android.com/develop/background-work/services/fgs/service-types (2026-09-21) | high |
| AF08 | Android 15: `BOOT_COMPLETED` receivers may not launch FGS types `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone` (**`specialUse` is not in the list**); `dataSync` and `mediaProcessing` may run 6 h per 24 h, then `Service.onTimeout()` | https://developer.android.com/about/versions/15/behavior-changes-15 | high |
| AF09 | Background FGS-start exemptions include `ACTION_BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`, the user interacting with the app's notification or tile, and the user turning off battery optimisation for the app | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start (2026-09-16) | high |
| AF10 | An app in the user-chosen **"Restricted"** battery state "Can't launch foreground services"; "Existing foreground services are removed from the foreground"; at targetSdk 33+ it gets no `BOOT_COMPLETED` "until the app is started for other reasons" | https://developer.android.com/topic/performance/background-optimization (2026-09-21) | high |
| AF11 | Doze triggers when the device is unplugged, stationary and screen-off; it "Suspends network access", "Ignores wake locks", defers alarms and jobs, with maintenance windows. An FGS process exempts the app from **App Standby**. A "partially exempt" (battery-optimisation allow-listed) app "can use the network and hold partial wake locks during Doze". Plugging in releases standby. **The page says nothing about FGS processes during Doze.** Play policy restricts `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to listed use cases | https://developer.android.com/training/monitoring-device-state/doze-standby (2026-08-18); raw HTML grepped to confirm the absence (a summariser had invented a sentence) | high |
| AF12 | **AOSP `android-17.0.0_r1` frameworks/base:** `NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode()` returns true for `procState <= PROCESS_STATE_BOUND_FOREGROUND_SERVICE` (network allowed in device idle and battery saver); `PowerManagerService.setWakeLockDisabledStateLocked()` disables a non-allow-listed app's partial wake lock in idle **only when** `procState > PROCESS_STATE_BOUND_FOREGROUND_SERVICE`; under **Low Power Standby** only `procState <= PROCESS_STATE_BOUND_TOP` keeps wake locks and network. So on AOSP an FGS keeps network and partial wake locks in Doze, but not in Low Power Standby | `https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/core/java/android/net/NetworkPolicyManager.java` ; `…/services/core/java/com/android/server/power/PowerManagerService.java` | high (AOSP); unknown (OEM builds) |
| AF13 | ADPF thermal guide: do not call `getThermalHeadroom()` "more than once every 10 seconds" or it returns `NaN`; `NaN` also means unsupported; 0.0 = `THERMAL_STATUS_NONE`, 1.0 = `THERMAL_STATUS_SEVERE`, and "> 1.0" is possible | https://developer.android.com/games/optimize/adpf/thermal (2026-02-26) | high |
| AF14 | API levels: `getCurrentThermalStatus`/`addThermalStatusListener` **29**; `getThermalHeadroom(forecastSeconds)` **30**; `getThermalHeadroomThresholds` **35**; `addThermalHeadroomListener` **36**; `SystemHealthManager.getCpuHeadroom`/`getGpuHeadroom` **36**. Android 17 `ThermalManagerService`: forecast range 0–60 s; headroom callbacks no more often than `HEADROOM_CALLBACK_MIN_INTERVAL_MILLIS = 5000` and only on a change of `0.03` or more; shell commands `inject-temperature`, `override-status`, `reset`, `headroom` | https://developer.android.com/reference/android/os/PowerManager ; https://developer.android.com/reference/android/os/health/SystemHealthManager ; `…/frameworks/base/+/refs/tags/android-17.0.0_r1/services/core/java/com/android/server/power/thermal/ThermalManagerService.java` | high |
| AF15 | `WIFI_MODE_FULL_HIGH_PERF`: "deprecated in API level 34 … automatically replaced with `WIFI_MODE_FULL_LOW_LATENCY` with all the restrictions documented on that lock". `WIFI_MODE_FULL_LOW_LATENCY` (API 29): "only active when the device is connected to an access point", "only active when the screen is on", "only active when the acquiring app is running in the foreground" | https://developer.android.com/reference/android/net/wifi/WifiManager | high |
| AF16 | StrongBox KeyMint (Android 9+): own CPU, secure storage, TRNG, secure timer; algorithms RSA 2048, AES 128/256, **ECDSA and ECDH P-256**, HMAC-SHA256, 3DES; "slower, more resource-constrained, and supports fewer concurrent operations"; detect with `FEATURE_STRONGBOX_KEYSTORE`; `StrongBoxUnavailableException` means fall back | https://developer.android.com/privacy-and-security/keystore (2026-03-06) | high |
| AF17 | `KeyGenParameterSpec.Builder`: `setUnlockedDeviceRequired` (API 28), "Public key operations aren't restricted … and may be performed even while the device is locked"; `setAttestationChallenge` (API 24); `setDevicePropertiesAttestationIncluded` (API 31) puts brand, device, manufacturer, model and product in the attestation. `KeyProperties.KEY_ALGORITHM_ML_DSA` (ML-DSA-65 default, and `_87`) is **API 37**; `SECURITY_LEVEL_STRONGBOX` is API 31 | https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder ; https://developer.android.com/reference/android/security/keystore/KeyProperties | high |
| AF18 | Key attestation page (2026-07-09): a new root "Key Attestation CA 1", **ECDSA P-384, active from 2026-02-01**, beside the older RSA root (serial `f92009e853b6b045`); roots published as JSON at `https://android.googleapis.com/attestation/root`; revocation status list at `https://android.googleapis.com/attestation/status` (JSON; cache per `Cache-Control`); the page advises sending the chain to "a separate trusted server" rather than validating on the device; RKP certificates must have their validity period checked; leaked factory keys are revoked "typically within several days"; RKP keys are not affected by that leak risk. It recommends the `android/keyattestation` library | https://developer.android.com/privacy-and-security/security-key-attestation (2026-07-09; read through a summariser, cross-checked against [F07]) | high (roots, URLs); medium (RKP-only scope wording) |
| AF19 | `github.com/android/keyattestation`: "A Kotlin library for verifying Android key attestation certificate chains", Apache-2.0; it can fetch the revoked-serial list **or have it built into the binary**, and accepts custom trust anchors | https://github.com/android/keyattestation (README) | high (for what the README says) |
| AF20 | Tailscale KB 1011 (validated 2026-01-05): clients stream connection events and operational logs to `log.tailscale.com`; opt-outs documented for Linux, Windows and the open-source macOS `tailscaled`; **nothing documented for Android or iOS**. KB 1315 (MDM keys, 2026-08-11): no logging policy key; `LoginURL` sets a custom control server "such as Headscale" | https://tailscale.com/kb/1011/log-mesh-traffic ; https://tailscale.com/kb/1315/mdm-keys | high |
| AF21 | **tailscale-android source** (HEAD `803d938`, 2026-09-23): commit `090afc4` (2026-04-20) "feat: support disabling remote log uploads" adds a Settings switch "Remote client logging". `getIsClientLoggingEnabled()` returns **true by default** and **forces true "when the device is managed by MDM"**. With it off, the logtail logger starts `Disabled` "so not even the internal 'logtail started' banner reaches the server". First tag containing it: `1.97.331` (2026-05-01, an unstable build); stable `1.98.x` tags contain it | https://github.com/tailscale/tailscale-android (cloned; `App.kt`, `libtailscale/tailscale.go`, `strings.xml`; `git tag --contains 090afc4`) | high (for the code); not packet-verified |
| AF22 | tailscale-android `VpnService`: adds the node's addresses with their prefix lengths (`builder.AddAddress(addr, bits)`), routes the tailnet, uses `ExcludeRoute` for local routes on API 33+, supports per-app include and exclude lists (split tunnelling), sets `setUnderlyingNetworks` and `setMetered(false)` ("Inherit the metered status from the underlying networks") | same repository, `libtailscale/net.go`, `IPNService.kt` | high |
| AF23 | Tailscale node addresses are single-IP prefixes (/32 and /128): `ipn/ipnlocal/local.go` passes `cfg.Addresses` as the router's `LocalAddrs` and tests `addr.IsSingleIP() && tsaddr.IsTailscaleIP(...)` | https://github.com/tailscale/tailscale (HEAD `f5f3260`, 2026-09-29) | medium-high (inferred from code, not observed on a device) |
| AF24 | Android developer verification: August 2026 launch of limited-distribution accounts ("up to 20 devices") and the power-user "advanced flow"; 2026-09-30 enforcement for participating stores in Brazil, Indonesia, Singapore and Thailand; "2027 and beyond" global for certified devices. The advanced flow (blog 2026-03-19): enable developer mode, confirm "you aren't being coached", restart and re-authenticate, "a one-time, one-day wait", then allow unverified installs "for 7 days or indefinitely". Neither page addresses `adb` or F-Droid | https://developer.android.com/developer-verification ; https://android-developers.googleblog.com/2026/03/android-developer-verification.html (2026-03-19) | high (for what the pages say) |
| AF25 | 16 KB pages: Android 15+ devices may use 16 KB pages; NDK r28+ aligns 16 KB by default (r27: `-Wl,-z,max-page-size=16384`); AGP ≥ 8.5.1; check with `zipalign -c -P 16 -v 4`; "Without recompiling, apps won't work on 16 KB devices in future Android releases"; 16 KB emulator images exist | https://developer.android.com/guide/practices/page-sizes (2026-09-16) | high (Play deadline wording medium) |
| AF26 | NNAPI "is deprecated … deprecated in Android 15"; Google's migration target is "TensorFlow Lite in Google Play Services" and AICore | https://developer.android.com/ndk/guides/neuralnetworks/migration-guide (2026-03-06) | high |
| AF27 | llama.cpp (master `eae11d2`, 2026-09-30): the OpenCL backend targets Adreno (8 Gen 3, 8 Elite and 8 Elite Gen 5 listed as supported on Android); the Snapdragon backend (`GGML_HEXAGON=ON` with OpenCL) builds inside Qualcomm toolchain images that bundle the Hexagon SDK; `docs/android.md` documents NDK cross-builds with `LLAMA_OPENSSL=OFF`; its Android CI cross-builds arm64 CPU on `ubuntu-24.04` | https://github.com/ggml-org/llama.cpp (`docs/backend/OPENCL.md`, `docs/backend/snapdragon/README.md`, `.github/workflows/build-android.yml`) | high |
| AF28 | Ubuntu 24.04 hosted-runner image: Android command-line tools 12.0; build-tools 34.0.0–37.0.0; platforms `android-34` … `android-37.2-beta3`; **NDK 27.3 (default), 28.2, 29.0**; `ANDROID_HOME=/usr/local/lib/android/sdk`. Announcement: "**`ubuntu-latest` label will use Ubuntu 26.04 in November 2026**" | https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md | high |
| AF29 | GitHub changelog 2024-04-02: "Actions users of our 2-vCPU GitHub-hosted Linux runners will be able to make use of hardware acceleration for Android testing", enabled by a udev rule granting `/dev/kvm` | https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/ | high |
| AF30 | Google SDK repository (`sys-img2-4.xml` files): x86_64 system images exist for API 35 and 36 as `default`, `aosp_atd`, `google_atd` and `google_apis`; **API 37.0 exists only as `google_apis` and `google_apis_ps16k`**; 37.1 and 37.2 only as `google_apis_ps16k` | `https://dl.google.com/android/repository/sys-img/{android,aosp_atd,google_atd,google_apis}/sys-img2-4.xml` | high |
| AF31 | Platform TLS: TLS 1.3 is supported and enabled by default from **API 29** for `SSLSocket`, `SSLServerSocket` and `SSLEngine`; ALPN (`SSLParameters.setApplicationProtocols`, `SSLEngine.getApplicationProtocol`) is API 29; `SSLParameters.setNamedGroups` is API 37; `setSignatureSchemes` is absent | https://developer.android.com/reference/javax/net/ssl/SSLSocket ; `…/SSLEngine` ; `…/SSLParameters` | high |
| AF32 | Wi-Fi Direct needs `NEARBY_WIFI_DEVICES` (API 33+) and location mode on for `discoverPeers()`/`discoverServices()`. Wi-Fi Aware (API 26+) needs `FEATURE_WIFI_AWARE` and `NEARBY_WIFI_DEVICES`, works without an access point, and its instant mode lasts 30 s because it "uses additional power" | https://developer.android.com/develop/connectivity/wifi/wifip2p ; https://developer.android.com/develop/connectivity/wifi/wifi-aware (both 2026-09-16) | high |
| AF33 | `WifiInfo.getSSID()` may return `UNKNOWN_SSID` and `getBSSID()` "02:00:00:00:00:00" "if the caller has insufficient permissions" | https://developer.android.com/reference/android/net/wifi/WifiInfo | high (the permission is not named there) |
| AF34 | `com.google.zxing:core` latest 3.5.4 (Maven Central metadata updated 2025-11-11); Apache-2.0; only a test dependency (JUnit) | https://repo1.maven.org/maven2/com/google/zxing/core/maven-metadata.xml ; https://github.com/zxing/zxing | high |
| AF35 | dontkillmyapp.com has **no page** for Nubia, ZTE or RedMagic (HTTP 404 for each) | https://dontkillmyapp.com/nubia (and `/zte`, `/redmagic`) | high (absence only) |
| AF36 | "Local Mess" (2025-06-03): Meta and Yandex apps listened on fixed localhost ports so their web scripts could link browsing to app identities; Google said the practice "blatantly" violated its policies; Chrome 137 shipped countermeasures | https://localmess.github.io/ ; https://www.androidpolice.com/meta-yandex-apps-de-anonymize-localhost-tracking/ | high |
| AF37 | **Repo facts** (HEAD `479f128`): `app` has `compileSdk 35`, `targetSdk 35`, `minSdk 29`; the app's own manifest declares `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` (libraries merge more, AF38); `AsomService` is `specialUse` with subtype text "Local-only (127.0.0.1) OpenAI-compatible model routing daemon serving paired apps on this device"; `allowBackup="false"` and **no** `dataExtractionRules`; the Network Security Config permits cleartext only to `127.0.0.1`/`localhost`; the vault master key is AES-256-GCM, alias `asom-master-key`, StrongBox then TEE; the Room ledger is `version = 1`, `exportSchema = false`; ledger appends run in `runCatching` and set `ledgerDegraded`; the server bind host is the constant `Asom.BIND_HOST`; the boot receiver starts the service only if `bootStartEnabled`; CI `android-apk` runs seven modules' unit tests and `assembleDebug` on `ubuntu-latest` | this repository (read, not modified) | high |
| AF38 | `androidx.work:work-runtime:2.10.1` (a dependency of `:ledger` and `:storage` via `api`) declares `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE` in its own manifest, so the **merged** v1 manifest already holds `WAKE_LOCK` and `ACCESS_NETWORK_STATE` | https://dl.google.com/android/maven2/androidx/work/work-runtime/2.10.1/work-runtime-2.10.1.aar (`AndroidManifest.xml` extracted); `ledger/build.gradle.kts`, `storage/build.gradle.kts` | high |

**Facts inherited from the design brief without re-fetching:** [F06] (StrongBox and Secure Enclave are P-256 only), [F07] (attestation: search nearest the root; RKP validity), [F09] (DERP relays), [F11] (Tailscale userspace networking forwards inbound to 127.0.0.1; desktop only here), [F16] (`Double.toString` differs across JDK 17 and 19+), [F18] (llama.cpp abort callback CPU-only), [F23] (`allowBackup=false` does not stop device-to-device transfer at API 31+), [F24] (`setUnlockedDeviceRequired` is OS-enforced), [F25] (Doze trigger), [F26] (developer verification), [F30] (Room WAL fsync), [F40] (MLPerf Mobile v6.0).

#### 1.2 Assumptions (not verified)

| ID | Assumption | Load-bearing for | How to settle |
|---|---|---|---|
| AA01 | Shipped Android 17 builds on the owner's phone (OEM build plus mainline Connectivity updates) behave like `android-17.0.0_r1`: loopback exempt from LNP; same-subnet LAN is "local"; a VPN interface marks only its own address | v1 at targetSdk 37; overlay without the permission | Emulator probe (§10, step V11-2) then device (DV-A2) |
| AA02 | The Tailscale Android app installs single-IP addresses (/32, /128) on its tun interface [AF23], so under AF05 tailnet peers are not "local network" | M1 over overlay at targetSdk 37 | `adb shell ip -br addr` on the phone (DV-A3) |
| AA03 | A future Android release may gate **same-profile** cross-app loopback (the reserved `USE_LOOPBACK_INTERFACE` bit and its TODO [AF05]). Direction only; no document announces it | v1's whole app-facing API (R-A1) | Re-read each Android beta's behaviour-change pages and the Connectivity source at each new tag |
| AA04 | The RedMagic's OEM power management neither kills a `specialUse` FGS nor cuts its network in Doze beyond AOSP [AF12] | unattended charging lender; long requester sessions | DV-A5 (30 min idle, forced Doze, then a request) |
| AA05 | An inbound unicast TCP SYN to a listening socket wakes a screen-off phone that holds no wake lock quickly enough for the dial budget (Wi-Fi wake-on-unicast; APF filters) | charging lender's idle-then-request behaviour | DV-A6 |
| AA06 | An `AndroidKeyStore` EC P-256 private key (TEE or StrongBox) can serve as the TLS 1.3 client and server key through Conscrypt via a custom `X509ExtendedKeyManager` (signature `ecdsa_secp256r1_sha256`) | the mesh transport on Android | S-A9 Conscrypt lane, instrumented test on the emulator (TEE emulated) then device (StrongBox) |
| AA07 | On Android, a fresh `SSLContext` per dial gives an empty client session cache, so no `pre_shared_key` is offered (T2); server-side ticket issuance cannot be disabled through public platform API | T2, T9 on the phone | W08 client-role assertions on ART |
| AA08 | Emulator keystores have no StrongBox and attest with a software or test root, not Google's | what CI can say about attestation | Read `KeyInfo.getSecurityLevel()` and the chain root in an emulator test; record |
| AA09 | GGUF weights loaded with `mmap` are file-backed and do not count toward the Android 17 "AnonSwap" limit [AF04]; the KV cache and compute buffers are anonymous memory and do count | lending large models (v2 engine on a lender) | `am memory-limiter status` plus a load test on the device (M2) |
| AA10 | The lending temperature and battery constants in §2.3 (enter at or below 38 °C and 50 %, drain at or above 41 °C or below 40 %) are conservative for a charging phone | M2 governors | PROVISIONAL; owner review and a RedMagic observation run |
| AA11 | Wireless charging adds enough heat that lending on it should be off by default | AN-3 | Device observation (battery temperature trace) |
| AA12 | The work profile and Android 15's Private Space are separate profiles, so AF04 blocks their apps' loopback to asom; binding asom's AIDL service across profiles already fails without cross-user permissions | v1 scope statement | Device test with a work profile (`pm create-user --profileOf 0 --managed`) |
| AA13 | The permission `WifiInfo` withholds the SSID and BSSID behind [AF33] is location (`ACCESS_FINE_LOCATION`, with location on) | "user-confirmed LAN" on a phone lender (AN-4) | Emulator test without location permission |
| AA14 | The Ubuntu 26.04 runner image (the `ubuntu-latest` default from November 2026 [AF28]) keeps the Android SDK and NDKs preinstalled | CI stability | Pin `runs-on: ubuntu-24.04` for Android jobs until a 26.04 run is green |
| AA15 | `android/keyattestation` runs on ART inside the app (and on a desktop JVM, [A23]), offline, with bundled anchors and a bundled revoked list [AF19] | A2 if ever revived | Spike only if D12's A2 deferral is reopened |
| AA16 | Android 17+ still installs apps targeting 35; no minimum-installable-targetSdk rule forces a bump soon | staying at targetSdk 35 (AN-1) | Read each release's behaviour-change page |
| AA17 | Wi-Fi power save on a screen-off lender adds at most a few hundred milliseconds to the first packet after idle, inside the 1.5 s LAN dial budget (T12) | charging lender latency | DV-A6 timing |
| AA18 | The Android emulator's `-tcpdump <file>` option captures all guest traffic, so an emulator quiescence test is possible without root | an emulator pre-check of M1 gate 5 | First CI run |
| AA19 | The owner's RedMagic uses a Snapdragon 8 Elite-class SoC (Adreno GPU, Hexagon NPU) | backend bakeoff (v2) | Owner input |

#### 1.3 How the loopback question was settled (and what is still open)

The brief carries A05 / K3 / H5: "Android 17's local-network permission might cover loopback, which would break v1 at targetSdk 37". This session read the code that implements the permission instead of relying on the developer page, which is silent [AF02].

1. **The enforcement point** is a BPF program attached to every app socket. It drops a packet when the owning UID is blocked from the local network **and** `is_local_net_access_allowed()` finds a matching "disallowed" entry for (interface, remote IP, protocol, port). **No matching entry means allowed** [AF05].
2. **Where entries come from.** `ConnectivityService` adds entries only for the interface names in a network's `LinkProperties`, whenever a network (Wi-Fi, cellular, VPN, Ethernet) connects or changes. Loopback is not a network agent's interface. **Therefore no entry ever carries the loopback interface index, and traffic on `lo` always passes the LNP check** [AF05]. This holds in both the 2025 `main` snapshot and the Android 17 r1 tag.
3. **A separate Android 17 loopback rule exists** and applies to all apps, whatever their targetSdk: loopback between **different profiles** is dropped when the flag `useLoopbackInterfacePermissionEnabled` is on; within one profile it is allowed [AF04][AF05]. The public page states it as the default behaviour [AF04].
4. **Consequences for v1.**
   - A paired app in the **same profile** keeps reaching `127.0.0.1:11435`, at any targetSdk of either app. H5's hard rule stays as a check, but its severity drops from "critical if true" to "confirm" (R-A2).
   - An app in a **work profile or Private Space** cannot reach asom's loopback server on Android 17 [AA12]. It could not pair anyway, because the AIDL bind is per profile. The README and `CLIENT_API.md` should say so (a documentation delta, no contract change).
   - **Not settled, and not settleable by reading code:** whether a later release gates same-profile loopback behind `USE_LOOPBACK_INTERFACE` [AA03]. v1's architecture is cross-app HTTP on loopback, the same shape the 2025 tracking abuse used [AF36]. That is R-A1.

---

### 2. Feasible mesh roles on this platform

**Vocabulary** (design §2.1): **R** borrow; **PA** lend with no human present; **PF** lend while a lend screen is frontmost; **B** benchmark producer; **S** manifest subscriber. **"Always-on" is not used for Android at all**: the closest role, PA-charging, is "lend while plugged in, idle and cool". Sequencing follows AD-1: the phone **borrows in mesh-1**; **Android-as-provider is a "remaining v4 item" after v2.5 and v3** (the design's M2 lender work moves there under AD-1).

#### 2.1 Role verdicts

| Role on the phone | Verdict | When (AD-1) | Conditions | Holon completeness (R2-DIRECTIVES-4) |
|---|---|---|---|---|
| **Serve own apps (whole on its own)** | **Yes** (v1 today) | v1, v2 | the existing FGS; cloud BYOK now; local engine from v2 | **Complete holon.** The only platform that serves its own apps, borrows and (later) lends |
| **R: borrow** | **YES** | **mesh-1** | Outbound only; no listener; per-app mesh toggle, default off; the FGS running (it already is while v1 serves any app); an overlay or Wi-Fi path; no metered path unless the app allows it (C8). At targetSdk 35: no new network permission. At targetSdk 37: `ACCESS_LOCAL_NETWORK` for same-subnet LAN peers only, not for overlay peers [AF05][AA01][AA02] | — |
| **PA-charging: lend while plugged in, screen off, nobody using it** | **YES, conditional and opt-in** | after v3 (AD-1) | All of §2.3 table A, including the T9 "serve while locked" opt-in, because a TEE leaf with `setUnlockedDeviceRequired(true)` cannot sign a handshake on a locked phone [AF17] | — |
| **PA unplugged** | **NO (recommended hard no)** | never | Doze no longer blocks it on AOSP [AF12], but serving from battery with the screen off drains the battery the owner will want later and heats a device in a pocket or bag. Unplugged lending is PF only | — |
| **PF: lend while the lend screen is frontmost** | **YES** | after v3 (AD-1) | §2.3 table B; screen on (`FLAG_KEEP_SCREEN_ON`); charging, or battery at or above 50 %; a maximum session length; the low-latency Wi-Fi lock works here and only here [AF15] | — |
| **B / S** | B yes (v2 engine); S yes (mesh-1 verifier) | v2 / mesh-1 | Benchmark heat tests need the charger (B-rules) | — |
| **Always-on provider** | **NO** | — | Nothing on a phone survives the owner picking it up (presence drain, LP-2), unplugging it, heat, battery saver or the "Restricted" state [AF10] | — |

#### 2.2 The honest reasons

- **Background execution.** The v1 daemon already lives in a `specialUse` FGS [AF37]. `specialUse` has no runtime prerequisites and no time limit [AF07]; `dataSync` and `mediaProcessing` would stop after 6 h in 24 h [AF08]. A peer listener inside the same FGS process needs no new component. **Limits that remain:**
  - the user's **"Restricted"** battery setting removes the FGS from the foreground and blocks new starts [AF10];
  - OEM task killers are outside AOSP [AA04], and the owner's device has no dontkillmyapp entry [AF35];
  - the process can still be killed under memory pressure. Android 17 adds per-app memory limits based on total RAM [AF04], which matters for a lender holding a multi-gigabyte model [AA09].
- **Doze and App Standby.**
  - Doze needs the phone unplugged, stationary and screen-off [AF11]. **A charging lender is never in Doze.**
  - A phone that borrows while unplugged does enter Doze. On AOSP an FGS process keeps both network access and partial wake locks while idle [AF12], so a paired app's request that reaches the daemon can still dial a peer. OEM builds are unverified [AA04].
  - **Low Power Standby** (a TV-oriented AOSP mode) keeps network only for `BOUND_TOP` and above, which an FGS is not [AF12]. If a phone enables it, lending and borrowing stop while it is active.
  - asom does **not** ask for the battery-optimisation exemption. The FGS does not need it on AOSP, and Play policy limits it [AF11]. If an OEM kills the service, the dashboard links to the system settings page (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), which needs no special permission.
- **Networking.** The requester needs no listener, so Invariant 2's app-facing text is untouched in mesh-1 (IC-1: "Outbound-only nodes … bind nothing new"). The lender's listener is IC-1's peer listener, bound to specific overlay or confirmed-LAN addresses, never loopback, a wildcard or cellular (§4.1).
- **The lock screen (T9).** Phones use a keyguard-bound TLS leaf key, so a stolen, locked phone cannot open new mesh sessions. **That same property makes a screen-off charging lender impossible** unless the owner switches on "serve while locked" for that phone. The design already allows this opt-out for charging providers only (T9); this section makes the conflict explicit and puts it in the lending consent (§2.3).
- **Power and thermal.** Sustained inference heats a phone within minutes (roadmap §1). Charging adds heat, and heat while charging wears the battery (§6.9 of the design). Lending therefore needs a charger (PA) or a short, visible session (PF), plus ceilings that drain before the OS throttles.
- **Store and distribution.** Play reviews the `specialUse` subtype text [AF07] and restricts battery exemptions [AF11]. Neither applies to sideload or F-Droid. Developer verification applies to all certified devices from 2027 [AF24] (D22).
- **What these roles do NOT guarantee.**
  - Being on a charger does not guarantee availability. The phone drains the moment its owner touches it, and stays unavailable for at least 10 minutes (LP-2).
  - The phone does not wake itself to lend. If the OS or an OEM freezes the process, the requester's dial fails over within its budget (T12).
  - Nothing here makes a phone a good lender for large models. A phone lends small models to other phones or tablets; the high-value flow stays phone → desktop (§7.1 of the design).

#### 2.3 Lending UX rules and the governor decision table (after v3; PROVISIONAL constants [AA10])

**Consent rules (normative for the Android lender):**
1. Lending is **off** by default, on two levels: the global switch ("Lend this phone's compute to my devices") and, per peer, T5's unchecked "Let this device use my compute" box. Both must be on.
2. Turning PA-charging on shows one sheet that states, in this order:
   - "Your phone will run other devices' requests while it charges with the screen off. Their text is processed on this phone; asom does not keep it";
   - "To serve while locked, this phone's mesh key will work while the phone is locked. A thief who takes it while it is running could keep using your devices until you revoke it on each of them" (T9);
   - "Charging while it works makes the phone warmer, which wears the battery faster".

   The switch stays off unless the user confirms all three.
3. PF is started only by the "Start lending" button on the lend screen, and ends when the lend screen stops being frontmost, the maximum session length expires (60 min, PROVISIONAL), or the user taps Stop.
4. **Presence (answers R2-OVERCLAIM-9 for Android):**
   - For **PA-charging**, presence inputs are: the screen becoming interactive (`PowerManager.isInteractive`), the keyguard being dismissed (`ACTION_USER_PRESENT`), and any asom activity other than the lend screen coming to the front.
   - For **PF**, the lend screen being frontmost, the screen-on state it requires, and touches inside the lend screen are **consent, not presence**. Presence is the lend screen leaving the foreground (`onStop`) or the screen turning off.
   - A presence signal drains at once. SERVING is published again no sooner than 10 minutes after the last presence signal (LP-2). PF resumes only after a new "Start lending" tap.
   - LP-1 needs this exception written in: "the lending node's own lend screen being frontmost, and the screen-on state it requires, are not presence inputs".
5. Never lend over cellular. The listener never binds a cellular interface (IC-1). A VPN whose underlying network is metered is treated as metered (the VPN inherits it [AF22]); the lender is ARMED, not SERVING, on a metered path.
6. The ongoing notification always shows the lending state (§3.3). Lending is never invisible on the device doing it.

**Table A: PA-charging governor (enter SERVING only when every row holds; drain when any drain condition fires).**

| Input | Source (API) | Enter SERVING | Drain (immediately) |
|---|---|---|---|
| Global switch, per-peer lend scope, "serve while locked" opt-in | local settings | all on | any off |
| Power | `BatteryManager` `EXTRA_PLUGGED` ∈ {AC, USB}; wireless excluded by default (AN-3) | plugged in | unplugged |
| Battery level | `EXTRA_LEVEL` | ≥ 50 % | < 40 % (hysteresis) |
| Battery temperature | `EXTRA_TEMPERATURE` (tenths of °C) | ≤ 38.0 °C | ≥ 41.0 °C |
| Thermal status | `getCurrentThermalStatus` + listener (API 29) | ≤ `LIGHT` | ≥ `MODERATE` |
| Thermal headroom | `addThermalHeadroomListener` (API 36+); otherwise `getThermalHeadroom(10)` at most every 10 s (API 30–35); absent on API 29 | ≤ 0.70 (10 s forecast) | ≥ 0.85, or `NaN` after it was supported (`PROBE_LOST`) |
| Battery saver | `PowerManager.isPowerSaveMode` | off | on |
| Background restriction | `ActivityManager.isBackgroundRestricted` | false | true (the OS removes the FGS anyway [AF10]) |
| Presence | §2.3 rule 4 | screen non-interactive ≥ 120 s **and** ≥ 10 min since the last presence signal | any presence signal |
| Path | `ConnectivityManager` network callbacks | an eligible listener address exists (§4.1) on an unmetered path | address lost, or path turns metered |
| Memory | engine RAM guard (v2 P2) + Android 17 limiter | model bytes + KV reserve fit | a `MemoryLimiter` exit was recorded in the last 24 h (`ApplicationExitInfo`), which keeps it ARMED until the user acknowledges |
| Local load | own queue (v2 P3) | a local request always preempts | the lender drains if SELF work would wait more than one engine call |

**Table B: PF (lend screen) differences from table A.** Power: plugged in **or** battery ≥ 50 % (drain < 40 %). Presence: rule 4 PF form. Screen: must be on, kept on by `FLAG_KEEP_SCREEN_ON` on the lend screen only. Wi-Fi: hold `WIFI_MODE_FULL_LOW_LATENCY` while the lend screen is frontmost [AF15]. "Serve while locked": not needed (the phone is unlocked). Session cap: 60 min, then drain and ask again.

**Drain behaviour (both):** the listener closes at once, so new connects are refused by the kernel. In-flight streams get `graceMs = 10 000` (design `platforms.md` §2.1). After that they end `INFER_END terminal=interrupted`, and the lender writes its outcome rows first (FC-5).

#### 2.4 Requester UX rules (mesh-1)

- The Hotspot tab gains a **Devices** section beside the existing Apps list. Paired nodes, their key tier ("self-reported") and per-direction scopes appear there. Apps are never mixed with devices (§5.4).
- Each paired app gets a **"May use my other devices"** switch, off by default (§8.5 of the design). Under D4(b1) it also gets **"Never use the cloud"** (the pulled-forward v2.5 column). The AIDL consent sheet gains a line naming the paired devices, so new apps opt in explicitly.
- The phone scans the lender's QR code (the lender is the listening node that opens the pairing window, T5 to T8). If scanning is not possible, the phone accepts the pairing string by paste (`trust.md` §4.9).
- The phone dials only for a real pending request, an open Peers tab, a user-started peer operation, or an attempt that is finishing (quiescence, §8.6). **A phone with the mesh on and no app using it sends nothing.**
- Every borrowed response carries `X-Asom-Egress: peer` and `X-Asom-Served-By: peer:<alias>/<model>`. The notification shows "streaming via <peer name> (self-reported)", built from the same `RouteRecord` as the ledger row (Invariant 9).

---

### 3. Node hosting

#### 3.1 One process, one FGS, three roles

| Component | Hosted in | Exists from | Notes |
|---|---|---|---|
| App-facing API (frozen §5) on `127.0.0.1:11435` | `AsomService` (`specialUse`), Ktor CIO, in-process | v1 | Unchanged by the mesh; the bind host stays a constant [AF37] |
| Requester: `MeshPipeline`, `MeshRouter`, dialer | same process, coroutines on `ServiceLocator.scope` | mesh-1 | No new service, no child process (roadmap §1 phantom-process rule) |
| Lender: peer listener on port 11436 | same FGS, same process | after v3 (AD-1) | Started and stopped by the §2.3 FSM; never a second FGS type |
| Engine (v2) | JNI, in-process | v2 | `System.loadLibrary` from `nativeLibraryDir` only; at targetSdk 37 any `System.load()` path must be read-only [AF03] |
| Node registry, ledger | Room databases in credential-encrypted storage | mesh-1 | Unreadable before the first unlock after boot; nothing mesh-related runs then (§3.2) |

**`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text** (manifest text, reviewed only by Play [AF07]; flagged for the owner, not a contract item):
- mesh-1: "Local-only (127.0.0.1) OpenAI-compatible model routing daemon serving paired apps on this device; when the user enables it, it may send those apps' requests to the user's own paired devices."
- lender: append "…and, while the user allows it, serves the user's own paired devices over a private network".

#### 3.2 Start, stop, boot, sleep, lock: what survives

| Event | Daemon (v1) | Requester (mesh-1) | Lender (after v3) |
|---|---|---|---|
| User starts (dashboard, QS tile, notification) | FGS starts | available | FSM goes OFF → ARMED; SERVING when table A or B holds |
| Boot | nothing, unless the boot toggle (default OFF) is on; `specialUse` may start from `BOOT_COMPLETED` [AF08][AF09] | as daemon | **never SERVING before the first unlock:** CE storage and the unlocked-required leaf are unavailable; a "serve while locked" leaf still needs CE storage for the registry |
| "Restricted" battery setting | FGS removed; no `BOOT_COMPLETED` at targetSdk 33+ [AF10] | stops | stops; the dashboard shows "blocked by battery setting" |
| Screen off / keyguard locks | FGS continues | continues; the dialer needs no leaf signature for an existing session, but a **new** handshake needs the leaf, which is unlocked-required on phones (T9) → while locked, only sessions opened before the lock (≤ 30 min age) can carry a borrow | PA-charging needs "serve while locked"; PF drains (presence) |
| Doze (unplugged, idle) | on AOSP an FGS keeps network and partial wake locks [AF12]; OEM unverified [AA04] | as daemon | not applicable (PA needs the charger; PF needs the screen on) |
| Low Power Standby | FGS loses network and wake locks [AF12] | stops working | drains |
| App update (`MY_PACKAGE_REPLACED`) | the process is killed; v1 does not restart it | stops | stops; restart is a user action (unchanged v1 rule) |
| Process death | `START_STICKY` restarts the FGS [AF37] | in-flight attempts end; orphaned intent rows reconciled on restart (D21 crash contract) | peers see a TCP reset; the lender reconciles its intent rows |
| Android 17 memory limiter kill | exit reason `REASON_OTHER` + "MemoryLimiter:AnonSwap" [AF04] | as process death | ARMED until the user acknowledges (table A, memory row) |

#### 3.3 How the user sees that it is running (the watched-object rule)

The single ongoing notification (channel `asom-daemon`, importance LOW, existing [AF37]) gains mesh states. Every state is a glyph plus a label, never colour (Invariant 6). Its values come from the same live state the dashboard shows.

| State | Notification text (placeholder; Hyle supplies visuals later) | Actions |
|---|---|---|
| Daemon idle, mesh off | "idle — serving on 127.0.0.1:11435" (v1) | Open |
| Borrowing | "streaming via Deck (self-reported) — peer" | Open |
| Lender ARMED | "◇ lending armed — waiting for charger" (or "…for the phone to cool", "…for 10 min without use") | Stop lending |
| Lender SERVING, idle | "◆ lending to your devices" | Stop lending |
| Lender SERVING, busy | "◆ serving a request for Dell (self-reported)" | Stop lending |
| DRAINING | "◇ lending paused: you are using the phone" (reason local only; never sent to peers, LP-1) | Stop lending |
| Verbose mode on (v1) | v1 sub-text kept | — |

- The **QS tile** keeps its v1 meaning (daemon on or off). Lending is a separate switch; it is never folded into the tile.
- The **Peers tab** shows per-peer request counts, last dial times, polling notes (design §7.4) and the lender FSM with its current reason.
- The **ledger** shows every `DIAL`, `SESSION`, `CONTROL`, attempt and manifest row (§8.4 of the design). `callerPkg` on lender-served rows is `peer:<nodeTag>`.

---

### 4. Networking

#### 4.1 Inbound listener (lender only; IC-1 peer listener)

- **Mesh-1 binds nothing new.** The phone is outbound-only, so IC-1's app-facing sentence holds unchanged, and the M1 device gate "no listening socket beyond `127.0.0.1:11435`" is checkable with `adb shell ss -ltn` (DV-A1).
- **Lender (after v3):** a plain `ServerSocketChannel` driving `SSLEngine` (platform Conscrypt) with the same frame codec the desktop uses. It is bound to **specific addresses on port 11436**, taken from `ConnectivityManager` `LinkProperties`:
  - (a) **overlay:** the address on the network with `TRANSPORT_VPN` whose interface the user selected (the tailnet tun). This is the default for a phone lender (AN-4);
  - (b) **confirmed LAN:** the Wi-Fi or Ethernet address of a network the user confirmed, only if AN-4(a) is chosen. On Android a "confirmed network" cannot be an SSID without location permission [AF33][AA13], so it is a fingerprint (gateway address, on-link prefix, DNS servers) with a stated weakness: another network with the same numbering matches it.
  - **Never** `0.0.0.0`, `::`, loopback, or an address on a `TRANSPORT_CELLULAR` network. Tailscale's userspace-networking mode, which forwards to 127.0.0.1 [F11], does not exist on Android: the Android app uses a kernel tun through `VpnService` [AF22].
- **Weak host model.** The Linux kernel under Android accepts a packet for a local address on any interface (the Linux section's LF39). So after `accept()`, the listener checks that the connection's local address and arrival network are the ones it bound for, and drops it silently before TLS if not (an `INBOUND_REFUSED` count, §8.4 of the design).
- **Certificate disclosure (T13)** is the same as on desktop: anyone who can connect sees the node certificate. The SNI-token mitigation S-A11 is unverified on Conscrypt [A09].
- **Network Security Config.** It governs platform HTTP stacks' cleartext and trust. The mesh uses raw TLS sockets with its own pinned verifier, so the NSC neither allows nor blocks it. Two Android 17 changes do not touch the mesh: certificate transparency by default at targetSdk 37 applies to the platform trust manager, which the mesh verifier replaces only on mesh sockets; provider calls keep platform trust, so CT is a gain there [AF03]. The planned `usesCleartextTraffic` deprecation [AF04] needs nothing, because v1 already uses an NSC [AF37].

#### 4.2 The local-network permission, per path (settled against AF02/AF05; device check AA01)

| Path | targetSdk 35 (today; implicit grant [AF02]) | targetSdk 37 (only after AN-1) |
|---|---|---|
| Paired app → `127.0.0.1:11435`, same profile | allowed | **allowed**: `lo` has no LNP entries [AF05] |
| Paired app → asom, **different profile** (work, Private Space) | Android 17: **blocked**, all targetSdks [AF04] | blocked |
| Phone dials a peer on the **same Wi-Fi subnet** | allowed | needs `ACCESS_LOCAL_NETWORK` (on-link prefix is local) |
| Phone dials a peer on **another RFC 1918 subnet** through the router | allowed | allowed under Android 17 r1 (only on-link prefixes are local) [AF05]; the 2025 `main` rule would have blocked it [AF06] |
| Phone dials a **tailnet** peer over the VPN interface | allowed | allowed under r1: the VPN marks only its own /32 and /128 [AF05][AF23]; check it (AA02) |
| Lender accepts from a same-subnet LAN peer | allowed | needs `ACCESS_LOCAL_NETWORK` (incoming TCP is covered [AF02]) |
| Lender accepts on the tailnet address | allowed | allowed under r1 (as above) |
| Any path while an HTTP proxy is set on the network | allowed | LNP not applied at all [AF05] |

**Design rules that follow:**
- **Keep targetSdk 35 through mesh-1** (AN-1; a build pin in brief §3, so any bump needs its own owner ruling, R2-CONFORMANCE-10).
- Build the **`LOCAL_NETWORK_DENIED`** state (T14) and the permission request flow anyway, **gated on `Build.VERSION.SDK_INT >= 37 && targetSdk >= 37`**. Do not rely on the overlay exemption: it depends on one release's rule [AF06].
- Request the permission **before** showing or scanning a pairing QR, with the rationale "asom needs this to reach your own devices on this Wi-Fi network". Users who already granted a `NEARBY_DEVICES` permission are not prompted [AF03]. That is a UX fact, not a consent asom obtained.
- The `NsdManager` system picker [AF02] would let asom reach one user-picked host without the permission. It requires mDNS advertisement by the peer, which D12 defers, so it is not used in mesh-1 (§4.6).

#### 4.3 Loopback and the frozen v1 API (the brief's open item)

- **Settled by source:** LNP never covers loopback [AF05] (§1.3). The design's H5 stays as the confirmation step: an emulator test first (§10, step V11-2), then a device test before any targetSdk 37 bump.
- **New and verified:** Android 17 blocks **cross-profile** loopback for all apps [AF04]. v1 docs must say that asom serves apps in its own profile only.
- **Watched:** the reserved `USE_LOOPBACK_INTERFACE` permission [AF05][AA03]. **Trigger for re-escalation:** any Android beta behaviour-change page, or Connectivity source at a release tag, that conditions **same-profile** cross-UID loopback on a permission or targetSdk. Then the owner decides between declaring that permission, if an app can hold it, or a contract change moving the data plane off TCP loopback. The obvious candidate is a Binder-passed socket pair (`ParcelFileDescriptor.createSocketPair()`) returned by the existing AIDL service, which keeps identity on Binder (Invariant 5). **Not designed further here:** it is a frozen-contract change.

#### 4.4 The private overlay on Android (D8)

| Item | Android status |
|---|---|
| Client | The official Tailscale app, a `VpnService` with a kernel tun [AF22]; F-Droid and Play builds exist (the repository ships both flavours; not re-verified) |
| Custom control server (Headscale) | Supported: the in-app "custom coordination server" option; MDM `LoginURL` [AF20] |
| **Log upload** | **To `log.tailscale.com` by default. Since 1.97.331/1.98 a Settings switch "Remote client logging" turns it off, including the start-up banner. It is forced on when the device is MDM-managed** [AF21]. Not in the KB [AF20]. Whether a Headscale-configured phone contacts `log.tailscale.com` with the switch off is not packet-verified (the Linux section found desktop clients do unless told not to) |
| One VPN at a time | Android runs one `VpnService` at a time (design A07; not re-verified). Non-root PCAPdroid, which is itself a `VpnService`, cannot run beside Tailscale (R2-OVERCLAIM-5) |
| Split tunnelling | Tailscale can include or exclude apps [AF22]. **If asom is excluded, asom cannot reach tailnet peers.** asom detects this by checking whether a `TRANSPORT_VPN` network is visible to its own UID, and shows "your VPN app excludes asom" |
| LAN while the overlay is up | Tailscale excludes local routes on API 33+ [AF22], so LAN-direct dials still leave through Wi-Fi. A lockdown VPN ("Block connections without VPN") drops non-VPN traffic; asom then offers only the overlay path |
| Path classification | `peerPath` is derived from the `Network` the socket is bound to (`Network.bindSocket`): `TRANSPORT_VPN` → `overlay`, `TRANSPORT_WIFI`/`ETHERNET` → `lan`. It is never inferred from the address range, because carrier NAT also uses 100.64/10 |
| Metered | The VPN inherits the underlying network's metered flag [AF22]; C8 applies |

**asom's disclosure text (pairing screen, D8):** "Your overlay app may send its own diagnostic logs to its vendor. In the Tailscale app, turn off Settings → Remote client logging (version 1.98 or later). A device managed by an organisation cannot turn it off. asom cannot check this setting." **What asom does NOT guarantee:** anything about the overlay app's own traffic, the Headscale operator's view of the device graph, or DERP relaying [F09].

#### 4.5 Wi-Fi locks, wake locks, radio power

- **No Wi-Fi lock for a screen-off lender.** `WIFI_MODE_FULL_HIGH_PERF` became the low-latency lock at API 34, and that lock is inactive with the screen off or the app in the background [AF15]. The first packet after idle may wait for the radio's power-save wake-up [AA17]. T12's LAN dial budget (1.5 s) is expected to absorb it; DV-A6 measures it.
- **PF:** hold `WIFI_MODE_FULL_LOW_LATENCY` only while the lend screen is frontmost, and release it on drain.
- **Partial wake lock:** held only while an attempt is in flight on either side (borrowing or serving), released when it ends, and **never** held by an idle SERVING lender (quiescence costs nothing when nobody is asking, G12). This needs the `WAKE_LOCK` permission (normal, install-time), which the merged v1 manifest already contains through WorkManager [AF38]. Whether an idle charging lender wakes for an incoming SYN is AA05, tested by DV-A6.
- **Why not a battery exemption:** unnecessary on AOSP for an FGS [AF12], and Play-restricted [AF11].

#### 4.6 Locating already-paired peers: NSD, Wi-Fi Direct, Wi-Fi Aware

The roadmap says "no mDNS, no open discovery", and D12 defers mDNS locate. Verdicts for Android, for **locating peers that are already paired only**:

| Mechanism | Needs | Verdict |
|---|---|---|
| Addresses from the QR, the peer's authenticated `HELLO` (≤ 4) and the user (T12) | nothing | **Use (mesh-1).** The overlay gives stable addresses, so a phone on the overlay rarely needs anything else |
| `NsdManager` mDNS with the system picker [AF02] | the peer must advertise a service; the picker is a user dialog | **Defer (D12).** It is the only Android discovery form that fits "consent before contact" (the user picks). But advertising is broadcast discovery, which the stop-line excludes until D12 approves locate, with `locSeed`-style blinded names (T18) |
| Wi-Fi Direct | `NEARBY_WIFI_DEVICES`, location mode on, a separate P2P group [AF32] | **Reject.** Android-to-Android only in practice; it forms a second network; location mode is a privacy cost; it broadcasts presence |
| Wi-Fi Aware (NAN) | `FEATURE_WIFI_AWARE`, `NEARBY_WIFI_DEVICES`, device support varies [AF32] | **Reject for mesh-1.** Broadcast discovery, device-dependent, extra power. Revisit only for D26 car or appliance profiles |

---

### 5. Key storage tier for the node identity

#### 5.1 Keys, aliases and specs

| Key | Alias (Android Keystore) | Spec | Generated | Never |
|---|---|---|---|---|
| **NIK** (node identity key) | `asom-nik-v1` | EC P-256, `PURPOSE_SIGN`, `DIGEST_SHA256`; `setIsStrongBoxBacked(true)` with `StrongBoxUnavailableException` → TEE (the vault's existing pattern [AF37]); `setAttestationChallenge("asom-nik/1")`; **`setDevicePropertiesAttestationIncluded(false)`** (AN-9) | at the first mesh enable only (X9), never in v2 | exported, backed up, used for exports (the NIK never signs a file, §5.3 of the design) |
| **Session leaf** (TLS key, 14 days) | `asom-leaf-<yyyymmdd>` | EC P-256 in the TEE (not StrongBox: one signature per handshake, and StrongBox is slower with fewer concurrent operations [AF16]); **`setUnlockedDeviceRequired(true)`** (T9) | at mesh enable and at rotation; its certificate is signed by the NIK | used for anything but TLS `CertificateVerify` |
| **"Serve while locked" leaf** | `asom-leaf-locked-<yyyymmdd>` | as above **without** `setUnlockedDeviceRequired` | only if the owner enables PA-charging lending (§2.3 rule 2); deleted when it is disabled | used while the lender is not SERVING under PA-charging |
| BYOK vault master key (v1) | `asom-master-key` | AES-256-GCM, StrongBox then TEE | v1 | shared with any mesh key; mesh code never touches it |

- The node registry (pins, state, scopes, addresses, self-reported names) lives in a **separate Room database** `mesh.db`. It holds **public** material only: SPKI pins and per-pair secrets for the SNI spike (if S-A11 lands, the pair key is a Keystore HMAC key, never in Room).
- **Backup and transfer:** Keystore keys never migrate. H4 adds `dataExtractionRules` excluding all domains from cloud backup and device-to-device transfer [F23]; the repo has none today [AF37]. A phone restored from a backup therefore comes up unpaired, and must be re-paired on every node.
- The **key tier shown to peers** is self-reported: `strongbox`, `tee` or `os-keystore`, from `KeyInfo.getSecurityLevel()` (`SECURITY_LEVEL_STRONGBOX` API 31 [AF17]; on API 29–30 `isInsideSecureHardware`). If the NIK reports software, the Peers screen and the manifest say `os-keystore (software)`.
- **ML-DSA** keys exist in the Keystore API from API 37 [AF17]. They do not change mesh-1: ES256 is the only algorithm every signer supports (design §5.1; the Secure Enclave has no ML-DSA). The registry's `alg` tag keeps the door open (trust.md §2.3).

#### 5.2 What each tier does NOT guarantee

| Tier | Guarantees | Does NOT guarantee |
|---|---|---|
| StrongBox NIK | the private key cannot be extracted, even with kernel compromise or physical side channels, within the SE's design limits [AF16] | that malware or root on the running phone cannot **use** the key; that asom is unmodified; anything once the phone is stolen **while running and unlocked** |
| TEE NIK or leaf | software cannot extract the key | protection against TEE exploits; use by code running with asom's UID or root |
| `setUnlockedDeviceRequired` leaf | a locked phone cannot open new sessions | hardware enforcement (it is OS-enforced [F24]); anything on a rooted or unlocked phone; sessions opened before the lock (bounded by the 30-min maximum session age, T9) |
| "Serve while locked" leaf | nothing beyond TEE | protection of a stolen, running, locked lender. It keeps serving and borrowing until revoked on each node. That is the opt-in's stated cost |
| Software keystore (fallback, if ever reported) | the key is stored encrypted by the OS | extraction by root; it is labelled so |

#### 5.3 Key attestation offline, without Play services (Invariant 8)

**Recommendation: keep A2 deferred (D12)**, keep the NIK attestable, and **never send the attestation chain to peers in mesh-1**: no field carries it and no verifier consumes it.

If A2 is ever revived, this is what an Android-side or desktop verifier can and cannot do **offline**:

| Check | Offline? | Notes |
|---|---|---|
| Chain signatures, CA flags, `keyCertSign`, leaf not a CA | yes | the corrected algorithm (design §5.5) |
| Root equals a pinned Google root (RSA `f92009e8…` or the P-384 "Key Attestation CA 1" from 2026-02-01) | yes, if the roots ship in the signed app release | never fetched from `android.googleapis.com/attestation/root` at run time: that would be an egress class Invariant 3 does not have [AF18] |
| The extension is found nearest the root and only there | yes | [F07] |
| `attestationChallenge == "asom-nik/1"`; `attestationSecurityLevel` (TEE or StrongBox); `attestationApplicationId` (package + signing-cert digest); `rootOfTrust` (verified-boot state, `deviceLocked`); OS and patch levels | yes | parsing only |
| RKP certificate validity periods | yes, against the verifier's clock | RKP certificates are short-lived [AF18], so an attestation captured when the NIK was generated **lapses**. A2 would be "attested, evidence valid until DATE" |
| **Revocation** | **no** | the status list is `https://android.googleapis.com/attestation/status` [AF18]. Fetching it is a new egress class. A bundled list is only as fresh as the app release (`android/keyattestation` supports a built-in list [AF19]); an owner-signed mirror (design §5.5 item 9) would still need a transport |

**What a valid attestation actually proves:** that the NIK was generated inside the device's TEE or StrongBox; that the device's bootloader state and verified-boot key were as stated **at generation time**; and that the generating app had package `xyz.mdhv.asom` with the stated signing certificate. **It does not prove:** that asom's code is unmodified now; that the device is not rooted now; that the TEE has not been compromised; or, offline, that the attestation key was not leaked before revocation [AF18]. Google's own advice is to verify on "a separate trusted server" [AF18]. In a mesh the verifier is the other node, which matches the separation, not the hardening. Play Integrity is excluded by Invariant 8; App Attest is rejected on Apple (D12).

#### 5.4 App identity (AIDL) and node identity (NIK) side by side (Amendment 3, IC-4)

| | App identity (v1, Invariant 5 as written) | Node identity (Amendment 3) |
|---|---|---|
| Who | an app on this phone | another of the owner's devices |
| Proven by | `Binder.getCallingUid()` → package + signing-cert SHA-256 via PackageManager [AF37] | the peer's NIK pin, verified by mutual TLS 1.3 on every session (T1, T2) |
| Created by | the AIDL consent sheet, launched from the client app's foreground (§5.7) | a QR pairing window the user opened on the listening node, confirmed on both screens (IC-4, T5–T8) |
| Credential | a 256-bit bearer token, stored as SHA-256 (Room `:pairing`) | none stored beyond the pin; the leaf signs handshakes |
| Reaches | the app-facing API on `127.0.0.1:11435` only | the peer protocol on port 11436 only (lender), or outbound sessions (requester) |
| Store | `:pairing` Room database (plus the per-app mesh and cloud-ban columns) | `mesh.db` `node_registry` (PAIRED, SUSPENDED, REVOKED; scopes per direction) |
| Revoked from | Hotspot → Apps | Hotspot → Devices (plus a `REVOKE_NOTICE` to the peer) |

**Rules that keep them apart (normative for the Android implementation):**
1. **No token crosses planes.** The peer listener never accepts an app bearer token. The loopback server never completes a mesh TLS handshake (it does not speak TLS).
2. **A node is never an app client.** Lender-served rows record `callerPkg = peer:<nodeTag>`; no AIDL call can be made on behalf of a peer; the AIDL surface is unchanged (no new method, D12).
3. **App identity never leaves the phone.** The requester sends no package, label or token to a peer (design §2.6); the body normaliser drops identity-bearing fields (T16). The per-app alias in `X-Asom-Served-By` is local (CD-2).
4. **Two consents, one per plane.** Pairing an app never pairs a device, and pairing a device never grants any app access to it. An app reaches a peer only when its own "May use my other devices" switch is on **and** the peer row grants `infer` in the "I borrow" direction.
5. **Revocations are independent.** Revoking an app does not touch device rows, and revoking a device does not touch app tokens. A revoked device cannot re-pair itself (M1 gate 7).
6. **Keys are separate.** The vault key, the NIK and the leaves are distinct Keystore aliases (§5.1). No mesh code path can read the BYOK vault (design §2.6: keys and key presence never travel).

---

### 6. Inference backend(s)

- **Mesh-1 needs no engine on the phone to borrow.** The phone's own engine (v2) matters only for SELF placement, and later for lending.
- **The backend is decided by roadmap v2 P6's bakeoff**, not here. Candidates on Android (JNI, in-process, `arm64-v8a`):

| Backend | Status | Verdict for the phone |
|---|---|---|
| llama.cpp CPU (arm64; KleidiAI optional) | documented NDK build [AF27] | **baseline**; the only backend whose cancel callback works mid-call [F18] |
| llama.cpp OpenCL (Adreno) | supported on 8 Gen 3, 8 Elite, 8 Elite Gen 5 [AF27] | **candidate** for the RedMagic [AA19] |
| llama.cpp Vulkan | builds; Android driver quality varies | candidate |
| llama.cpp Hexagon (Snapdragon NPU) | built with Qualcomm's toolchain images and Hexagon SDK [AF27] | candidate **after a licence check** of the Hexagon SDK redistributables (owner input; the same class of question as CUDA [A15]) |
| MLC LLM, LiteRT-LM, ExecuTorch + QNN | bakeoff alternates (roadmap v2 P6) | only without Play-services runtimes (Invariant 8) |
| NNAPI | deprecated in Android 15 [AF26] | **reject** |
| TFLite "in Google Play services" | Google's NNAPI replacement [AF26] | **reject** (Invariant 8) |
| AICore / ML Kit GenAI (Gemini Nano) | a system service serving one vendor model | **reject** as a backend: it cannot serve catalogue models, and it is the vendor analogue the roadmap differentiates from (roadmap §1) |

- **Build constraints for any native backend on Android:** 16 KB page alignment (NDK r28+ default; r27 flags) [AF25], checked in CI with `zipalign -c -P 16`; libraries only from `nativeLibraryDir`, with no extraction to temp, because "Safer Native DCL" applies at targetSdk 37 [AF03] (constraint C13); the Android 17 memory limiter counts anonymous memory (KV cache), so the RAM guard reserves for it [AA09].
- **Benchmark baseline (directive D-C):** MLPerf Mobile v6.0 already covers consumer Android devices, with Llama 3.2 1B/3B and 3.1 8B, including NPU paths on Snapdragon 8 Elite Gen 5 [F40]. asom's phone benchmark therefore measures **only asom's own serving engine**, for the router: the v2 daemon shell (B24) and, later, the standalone APK (M2 in the design). It never claims MLPerf comparability beyond "measured with MLPerf Mobile's model set and metric definitions" (D-C). **A phone's numbers feed other devices' routers only once the phone lends** (after v3). Before that they feed its own SELF estimate and governors.

---

### 7. Runtime and code strategy

**Recommendation: Kotlin on ART, reusing the pure-JVM core. No second implementation, no KMP.**

- **Shared, unchanged:** `:core:contract`, `:core:catalogue`, `:core:routing`, `:core:inference-api`, `:server`. At promotion (D23): `:core:mesh` (frame codec, pins, `verifyPeerChain`, registry FSM, `MeshRouter`, `ClaimTracker`, ledger model), promoted from the lab's `mesh-proto`, `mesh-router` and `ledger-model`.
- **Android-only host layer (new, D23 SIGN-OFF), one module `:mesh-android`** (Android library → `:core:contract`, `:core:mesh`). It holds: `NodeKeyStore` (§5.1); `ConscryptPeerTransport` (an `SSLEngine` adapter under the shared codec); `NetworkClassifier` (`peerPath`, metered state, VPN visibility); `LocalNetworkGate` (§4.2); `AndroidProbes` (`ThermalSampler` using the API 36 listener, else 10 s polling, else status only; power; presence); the peer listener (lender phase); and the node-registry Room database. QR scanning goes in its own small module `:qr` (CameraX + zxing-core [AF34]), so camera code stays out of the daemon's core paths.
- **What must not happen:** `:core:mesh` must not use JDK APIs absent on ART, because Android's `java.*` is a subset. Examples: `jdk.net.ExtendedSocketOptions.SO_PEERCRED`, `UnixDomainSocketAddress`, `java.net.http`, `SSLParameters.setSignatureSchemes` [AF31]. The desktop's Unix control socket (T17d) stays in `:node-desktop`. **Enforcement:** the ART conformance lane executes the shared code on ART (below), and Android Lint `InvalidPackage` runs on `:mesh-android`.
- **Drift and its cost.** Android and desktop share one Kotlin source, so "independent implementation" drift does not apply here. Four **platform-behaviour** drifts do, each pinned by vectors that already exist in the design:

| Drift | Vector family | Lane |
|---|---|---|
| Conscrypt vs JSSE TLS knobs: ALPN after the handshake, no PSK offered, `CertificateVerify` present, client auth required | W08 (both roles), S-A9 matrix | ART emulator (instrumented) |
| ART vs JDK 17/21 number formatting [F16] | W01, M01–M03 | ART emulator |
| Room vs JSONL ledger rows | W01b, W01b-reach, L-L5b | ART emulator + JVM |
| Android probes vs Linux probes | executor-trace vectors (fake probes), W07-presence including the PF exception | JVM (fakes) + ART |

  **Estimated cost:** 1–1.5 engineer-weeks to stand up the ART lane (M1), then about 1 day per release to keep it green. The lane also closes the design's "W01 must pass on ART before it is normative" condition (C1).

---

### 8. Packaging and distribution

- **One APK, `xyz.mdhv.asom`, as today.** Mesh code ships inside the daemon; there is no separate mesh app. The standalone benchmark APK `xyz.mdhv.asom.bench` is the design's AF-1 and is out of this section.
- **Manifest deltas (each a SIGN-OFF line, even though none is an API contract item):**

| Phase | Adds | Kind |
|---|---|---|
| v1.1 H4 | `android:dataExtractionRules` excluding all domains, for cloud backup and device transfer | manifest attribute |
| mesh-1 | `CAMERA` (runtime, asked only when the user taps "Scan") with `<uses-feature android:name="android.hardware.camera.any" android:required="false"/>`; `ACCESS_NETWORK_STATE` declared explicitly in the app manifest (already present in the merged manifest through WorkManager [AF38], so the merged set gains only `CAMERA`); the new subtype text (§3.1) | permissions |
| lender (after v3) | `WAKE_LOCK` declared explicitly (already merged through WorkManager [AF38]); `CHANGE_WIFI_STATE` is **not** needed for Wi-Fi locks, and asom does not ask for it | permission |
| targetSdk 37 (AN-1, separate ruling) | `ACCESS_LOCAL_NETWORK` (runtime; `NEARBY_DEVICES` group) [AF02][AF03] | permission |
| never | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES`, any `com.google.android.gms` or Firebase artifact | — |

- **New dependencies (CD-D, D23):** `com.google.zxing:core` 3.5.4 (Apache-2.0, no runtime deps [AF34]); AndroidX CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`). The design's CD-D counted only zxing (R2-OVERCLAIM-10); CameraX must be added. The owner may choose paste-only pairing instead (AN-5).
- **Signing and updates.**
  - The release keystore is an owner task (brief §14.5).
  - Developer verification: register before the 2027 global expansion (D22). The limited-distribution account (≤ 20 devices) and the advanced flow (a one-day wait) are fallbacks [AF24].
  - F-Droid remains the second channel. F-Droid signs with its own key unless reproducible builds are set up, so an F-Droid APK cannot update a self-signed one (general F-Droid behaviour, not re-verified here).
  - **No in-app update check:** it is not a permitted egress class (Invariant 3). Updates arrive through the store or a sideload.
- **16 KB alignment** is verified in CI for any build with native code [AF25].

---

### 9. What GitHub Actions hosted runners can honestly verify

| Check | Runner | Verifiable? | How |
|---|---|---|---|
| Compile every Android module; JVM unit tests | `ubuntu-24.04` (pin it; `ubuntu-latest` moves to 26.04 in November 2026 [AF28][AA14]) | **yes** | the existing `android-apk` job (seven modules' unit tests + `assembleDebug`) [AF37], extended with `:mesh-android:testDebugUnitTest` and `:qr:testDebugUnitTest` |
| Pure-JVM core with no SDK | `ubuntu-24.04`, `ANDROID_HOME=""` | **yes** | existing `jvm-tests`; the lab's `labTest` |
| Manifest and APK policy | `ubuntu-24.04` | **yes** | `aapt2 dump permissions` equals the allowed set (§8); `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` contains no `com.google.android.gms` or `com.google.firebase`; `zipalign -c -P 16 -v 4` |
| Room migrations (ledger v1 → v2, pairing columns, `mesh.db`) | emulator API 35 (`default` x86_64) | **yes, EMULATOR EVIDENCE** | `MigrationTestHelper`; needs `exportSchema = true` (today `false` [AF37]) |
| ART conformance lane: W01, W01b, W05, W08 (client and server roles), M01–M03, W07-presence | emulator API 35 and 36 (`aosp_atd`) | **yes, EMULATOR EVIDENCE** | instrumented tests reading `lab/conformance` vectors packaged as test assets |
| Keystore NIK and leaf generation, `KeyInfo`, `setUnlockedDeviceRequired` behaviour when locked | emulator | **partly**: TEE emulated; **no StrongBox**; attestation roots are not Google's [AA08] | instrumented; the lock is driven with `adb shell locksettings set-pin 1234` and `input keyevent KEYCODE_SLEEP` |
| LNP and loopback behaviour at targetSdk 37 | emulator **API 37.0 `google_apis` x86_64** (the only x86_64 API 37 image [AF30]) | **yes, EMULATOR EVIDENCE** of the r1 rules [AF05] | the probe test (§10, step V11-2); LAN peer = the host through `adb emu redir` (arrives from 10.0.2.2, which is on-link) |
| Cross-profile loopback block | emulator API 37 | **likely** | `pm create-user --profileOf 0 --managed` then the probe from the profile [AA12] |
| FGS network under forced Doze | emulator | **yes, AOSP behaviour only** | `adb shell dumpsys battery unplug; adb shell dumpsys deviceidle force-idle`, then a borrow; expect success [AF12] |
| Thermal governor transitions | emulator | **yes, simulated** | `adb shell cmd thermalservice override-status 2` (MODERATE) → DRAINING; `reset` [AF14] |
| Power and presence inputs | emulator | **yes, simulated** | `dumpsys battery set ac 1`, `set level 35`, `set temp 420`; `input keyevent KEYCODE_WAKEUP` |
| Quiescence (no SYN with the mesh on and no request) | emulator | **pre-check only** | emulator `-tcpdump` [AA18]; no overlay |
| 16 KB page devices | emulator `google_apis_ps16k` | **yes** | `adb shell getconf PAGE_SIZE` = 16384, then the native smoke test (v2) |
| **StrongBox; real attestation chains; OEM power policy; heat; battery wear; radio power save; the owner's Wi-Fi and overlay; Tailscale log behaviour; the owner's RedMagic at all** | — | **NO** | NEEDS-DEVICE-VALIDATION (§10.4) |

The macOS and Windows runners add nothing for Android. The Linux KVM emulator is the right lane [AF29].

---

### 10. Implementation scaffold plan

#### 10.1 When any of this may start (read first)

- **Nothing in this section may touch `app/`, `vault/`, `pairing/`, `ledger/`, `storage/`, `client*/` or `sample-client/` until v1 device validation closes** (directive D-D; PROGRESS.md P5–P8 gates still open). **No new directory is created now.**
- The only Android-relevant work that could run now is **lab (pure-JVM) code**: an LNP classifier model with vectors, under D1b with `lab/mesh-policy`. It is optional and listed in 10.3 as step L-A1.
- Order after validation: **v1.1** (H4, H5, plus the ledger schema groundwork for H1) → **v2** (engine; unchanged by this section) → **mesh-1** (requester) → v2.5 → v3 → **lender** (AD-1).

#### 10.2 File tree (post-validation; paths relative to the repo root; `*` = modified existing file)

```
settings.gradle.kts*                         # include(":mesh-android", ":qr") inside the existing hasAndroidSdk block (MOD-1, D23)
gradle/libs.versions.toml*                   # zxing-core 3.5.4, CameraX; no Conscrypt artifact (the platform's Conscrypt is used)
app/
  build.gradle.kts*                          # deps on :mesh-android, :qr; targetSdk stays 35 (AN-1)
  src/main/AndroidManifest.xml*              # v1.1: dataExtractionRules; mesh-1: ACCESS_NETWORK_STATE, CAMERA(+uses-feature
                                             #   required=false), subtype text; lender: WAKE_LOCK
  src/main/res/xml/data_extraction_rules.xml # v1.1 H4: exclude every domain from cloud-backup and device-transfer
  src/main/kotlin/xyz/mdhv/asom/
    service/AsomService.kt*                  # starts MeshHost with the server; notification states of §3.3
    ui/HotspotScreen.kt*                     # Apps | Devices sections; per-app "May use my other devices" (+ "Never use the cloud", D4(b1))
    ui/PairingActivity.kt*                   # consent sheet line naming paired devices
    ui/PeersScreen.kt                        # peers, scopes per direction, counts, key tier (self-reported), lender FSM
    ui/NodePairingActivity.kt                # scan (or paste) QR, SAS display (phone is S in mesh-1), LOCAL_NETWORK_DENIED screen
    ui/LendScreen.kt                         # lender phase: PF lend screen (FLAG_KEEP_SCREEN_ON, low-latency Wi-Fi lock, Stop)
mesh-android/                                # NEW Android library → :core:contract, :core:mesh (D23)
  build.gradle.kts
  src/main/AndroidManifest.xml               # no components; no permissions (the app declares them)
  src/main/kotlin/xyz/mdhv/asom/mesh/android/
    NodeKeyStore.kt                          # NIK + leaf aliases/specs (§5.1); tier from KeyInfo; rotation
    ConscryptPeerTransport.kt                # SSLEngine per connection, fresh SSLContext per dial (T2), X509ExtendedKeyManager over Keystore keys
    PeerDialer.kt                            # Network.bindSocket, dial budgets (T12), DIAL rows before SYN (L-L14)
    PeerListener.kt                          # lender phase: specific-address bind, post-accept interface check (§4.1)
    NetworkClassifier.kt                     # peerPath from TRANSPORT_*, metered, VPN visible to own uid, LAN fingerprint (AN-4a)
    LocalNetworkGate.kt                      # SDK>=37 && targetSdk>=37 → ACCESS_LOCAL_NETWORK state machine (T14)
    ThermalSampler.kt                        # API36 listener | API30-35 poll ≥10 s | API29 status only; NaN→PROBE_LOST
    PowerPresenceProbe.kt                    # plugged/charging split (B13), battery temp, saver, restricted, presence (§2.3 rule 4)
    LenderGovernor.kt                        # tables A/B → FSM inputs (lender phase)
    NodeRegistryDb.kt                        # Room mesh.db: node_registry, scopes, addresses; exportSchema=true
    MeshHost.kt                              # wires :core:mesh pipeline into the FGS; quiescence predicate
  src/test/kotlin/…                          # JVM unit tests with fake probes (executor-trace vectors)
  src/androidTest/kotlin/…                   # ART lane: W01/W01b/W05/W08/M01–M03/W07-presence; keystore; LNP probe
  src/androidTest/assets/conformance/        # copied from lab/conformance at build time (never hand-edited)
  schemas/                                   # Room schema JSON (exportSchema)
qr/                                          # NEW Android library: CameraX preview + zxing-core decode; paste fallback
  build.gradle.kts
  src/main/kotlin/xyz/mdhv/asom/qr/QrScanner.kt
ledger/
  build.gradle.kts*                          # exportSchema=true; schemas/ dir
  src/main/kotlin/xyz/mdhv/asom/ledger/LedgerDb.kt*        # v1→v2 migration: CD-14/CD-15 nullable columns, status 0 = intent
  src/main/kotlin/xyz/mdhv/asom/ledger/RecordMapping.kt*   # RouteRecord ↔ row incl. reach/terminal/servedClass
pairing/
  src/main/kotlin/xyz/mdhv/asom/pairing/PairingStore.kt*   # + meshAllowed, cloudBanned columns (migration)
sample-client/
  build.gradle.kts*                          # CI-only flavour "t37" (targetSdk 37) for the H5 probe; never released
  src/androidTestT37/kotlin/…/LoopbackProbeTest.kt          # H5: paired call to 127.0.0.1:11435 from a targetSdk-37 app
ci/android/
  apk-policy.sh                              # aapt2 permission allow-list, no-GMS dependency grep, zipalign -P 16
  permissions.allow                          # merged-manifest permission set of the validated v1 APK, plus each ruled addition
  enable-kvm.sh                              # the udev rule from AF29
.github/workflows/ci.yml*                    # + job "android-emulator" (ubuntu-24.04, KVM, API 35/36/37 matrix); existing jobs untouched
docs/
  CLIENT_API.md*                             # CD-DOC1/CD-DOC2 wording; "same profile only" note (§4.3)
  DEVICE_CHECKLIST_MESH_ANDROID.md           # §10.4 as a checklist
```

#### 10.3 Steps, gates and expected output

Each gate's real output is pasted into PROGRESS.md. Emulator results are labelled **EMULATOR EVIDENCE — NOT DEVICE EVIDENCE**.

| Step | Phase | What | Gate (command → expected) |
|---|---|---|---|
| L-A1 (optional) | now, D1b | `lab/mesh-policy/LnpModel.kt` + `lab/conformance/android/lnp-a17r1.json`: given interface addresses, routes and a destination, predict "LNP applies" per AF05 (and the AF06 variant, tagged) | `./gradlew -p lab labTest --tests '*LnpModel*'` → `BUILD SUCCESSFUL`, all vectors pass; the vectors are tagged **self-oracled** (R2-OVERCLAIM-8) |
| V11-1 | v1.1 H4 | `data_extraction_rules.xml` + manifest attribute | `./gradlew :app:processDebugMainManifest && grep -c dataExtractionRules app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml` → `1` (path per AGP 8.7; adjust if it moves) |
| V11-2 | v1.1 H5 | `sample-client` flavour `t37` + `LoopbackProbeTest` (pair with asom at targetSdk 35, then call `/v1/models` on 127.0.0.1); a second case in a managed profile | `./gradlew :sample-client:connectedT37DebugAndroidTest` on the API 37 `google_apis` emulator → `OK`; the profile case → connection refused or reset (AF04). Device: DV-A2 |
| V11-3 | v1.1 (H1 groundwork) | `exportSchema = true`; ledger migration 1 → 2 with nullable columns | `./gradlew :ledger:connectedDebugAndroidTest --tests '*Migration1To2*'` (API 35 emulator) → `OK (n tests)` |
| M1-1 | mesh-1 | `:mesh-android` skeleton, `NodeKeyStore`, `NetworkClassifier`, `LocalNetworkGate`, `ThermalSampler`, `PowerPresenceProbe` with fakes | `./gradlew :mesh-android:testDebugUnitTest` → `BUILD SUCCESSFUL`; executor-trace vectors pass |
| M1-2 | mesh-1 | ART conformance lane | `./gradlew :mesh-android:connectedDebugAndroidTest --tests '*Conformance*'` on API 35 and 36 → W01, W01b, W05 and M01–M03 all green; **W08 client role: 0 failures, `pre_shared_key` absent in every ClientHello, `CertificateVerify` present** |
| M1-3 | mesh-1 | Keystore tests | `…connectedDebugAndroidTest --tests '*NodeKeyStore*'` → P-256, SIGN only, non-exportable; the leaf refuses to sign while locked (a Keystore error; the exact exception class is recorded, not assumed), and the "serve while locked" leaf signs. StrongBox path: DV-A4 |
| M1-4 | mesh-1 | `ConscryptPeerDialer` against a desktop reference node | `./gradlew :mesh-android:connectedDebugAndroidTest --tests '*DialerIT*'` with the lab's JVM reference node on the runner host via `adb reverse`/`redir` → a streamed borrow; `DIAL` intent row durable before the SYN (L-L14) |
| M1-5 | mesh-1 | Pairing UI, QR (`:qr`), per-app switch, cloud-ban column, consent line | `./gradlew :qr:testDebugUnitTest :app:testDebugUnitTest` → green; zxing decodes the committed pairing-string QR fixtures |
| M1-6 | mesh-1 | Forced Doze borrow | emulator: `dumpsys battery unplug; dumpsys deviceidle force-idle`, then a paired app borrows → 200, with rows on both nodes |
| M1-7 | mesh-1 | APK policy | `ci/android/apk-policy.sh app/build/outputs/apk/debug/app-debug.apk` compares `aapt2 dump permissions` with `ci/android/permissions.allow` (generated once from the **validated v1 APK's merged manifest**, which already includes the WorkManager permissions [AF38]) → `permissions: OK (added since v1: android.permission.CAMERA)`, `gms/firebase: none`, `zipalign16: OK` |
| M1-8 | mesh-1 | Device checklist | §10.4 DV-A1…A8 → NEEDS-DEVICE-VALIDATION until the owner confirms |
| L-1 | lender | `PeerListener`, `LenderGovernor`, `LendScreen` | `…connectedDebugAndroidTest --tests '*Listener*'` (API 36/37): W08 **server role** green; bind only to the emulator's eth0/wlan0 address, never `0.0.0.0`/`lo` (`adb shell ss -ltn` shows only `127.0.0.1:11435` and `10.0.2.15:11436`) |
| L-2 | lender | Governor transitions | `cmd thermalservice override-status 2` → DRAINING within 1 s and the next offer declined `PEER_UNAVAILABLE`; `dumpsys battery unplug` → DRAINING; `input keyevent KEYCODE_WAKEUP` → DRAINING, then SERVING no sooner than 10 min (test clock) |
| L-3 | lender, targetSdk 37 only | LNP flow | on API 37 with the permission denied: a same-subnet inbound connect is dropped (`LOCAL_NETWORK_DENIED` shown); granted → accepted |
| L-4 | lender | Device checklist | DV-L1…L6 → NEEDS-DEVICE-VALIDATION |

#### 10.4 Device checklist (exact commands; all NEEDS-DEVICE-VALIDATION on the owner's RedMagic)

| ID | Check | Command / method | Pass |
|---|---|---|---|
| DV-A1 | Mesh-1 phone listens on nothing new | `adb shell ss -ltn` | only `127.0.0.1:11435` |
| DV-A2 | Loopback at targetSdk 37 on the real OS build | install the `t37` probe; run it | `/v1/models` 200 |
| DV-A3 | Tailscale tun addresses | `adb shell ip -br addr show tun0` | `100.x.y.z/32` and `fd7a:…/128` (AA02) |
| DV-A4 | NIK in StrongBox | Peers → This device | tier `strongbox` (or `tee` with the StrongBox-unavailable reason logged) |
| DV-A5 | FGS network in real Doze | unplug, screen off, wait until `dumpsys deviceidle get deep` = `IDLE`, then trigger a borrow from `:sample-client` (a debug-build test broadcast, never in release builds) | 200; rows on both nodes |
| DV-A6 | Idle-then-request timing (lender) | charging, screen off 30 min, then a request from the Deck | success within the dial budget, or a clean failover with a correct `DIAL` row |
| DV-A7 | Quiescence (M1 gate 5) | overlay **off**, LAN-only, capture at the gateway for 30 min; positive control: open Peers → SYN within 5 s | zero SYNs to peer addresses; with the overlay **on** this gate needs rooted on-device capture, otherwise it stays open (R2-OVERCLAIM-5) |
| DV-A8 | Tailscale log switch | Tailscale ≥ 1.98, switch off, capture DNS/TLS SNI for 30 min | no `log.tailscale.com` lookups; record the result either way |
| DV-L1 | Lending heat run | 20-request script from the Deck while charging; `adb shell dumpsys battery` every 30 s | drain at or before 41 °C; no `THERMAL_STATUS` ≥ SEVERE |
| DV-L2 | Presence drain | pick the phone up mid-request | DRAINING at once; no SERVING for 10 min |
| DV-L3 | Locked lender | PA-charging with "serve while locked" off, lock the phone | no new session is accepted; the notification says why |
| DV-L4 | OEM kill check | 8 h overnight lending | the FGS survives; no `ApplicationExitInfo` kill |
| DV-L5 | Memory limiter | serve an 8B Q4 model (if it fits) | `am memory-limiter status` shows no enforcement; no `MemoryLimiter` exit |
| DV-L6 | Wireless charger (if AN-3 allows) | as DV-L1 on a wireless pad | temperature trace recorded |

#### 10.5 Effort (engineer-weeks; estimates, not measurements)

| Block | Weeks | Assumptions |
|---|---|---|
| v1.1 Android items (H4, H5 probe flavour, ledger `exportSchema` + migration groundwork) | 1–2 | v1 validated; no other v1.1 rework |
| mesh-1 Android requester (`:mesh-android` requester half, `:qr`, Keystore, ART conformance lane, Room migrations, pairing and Peers UI, per-app and cloud-ban switches, device checklist) | 5–8 | `:core:mesh` already promoted and green on the JVM (lab L0.4–L0.6); one engineer who knows Kotlin and Android; the owner available for about two device sessions |
| Lender (after v3: listener, governors, lend screen, lock-screen leaf, LNP flow if targetSdk 37, W08 server role, heat and OEM runs) | 5–9 | the v2 engine exists and passes its own gates; the RedMagic available for overnight runs |
| ART lane upkeep | ~1 day per release | — |
| **Android-specific total** | **≈ 11–19** | Excludes roadmap v2, the lab, desktop and Apple work. It overlaps the design's M1 (7–11) and M2 Android-lender (4–8) estimates rather than adding to them |

---

### 11. Owner decisions specific to this platform, and risks

#### 11.1 Owner decisions

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **AN-1** | When may `targetSdk` rise above 35 (a brief §3 build pin; R2-CONFORMANCE-10)? | (a) stay at 35 through mesh-1 and the lender phase unless separately ruled; (b) bump to 37 at mesh-1; (c) bump in v1.1 | **(a).** At 35 every mesh path works without a new runtime permission [AF02]. A bump is its own RT row, preceded by V11-2 and DV-A2 |
| **AN-2** | Which lending shapes may the phone offer (after v3)? | (a) PF only; (b) PF + PA-charging, with the "serve while locked" opt-in; (c) none | **(b).** Overnight charging is where a phone's lending has value (directive B: a phone borrows from another phone), and the T9 cost is disclosed and opt-in. Take (a) if the owner rejects any serve-while-locked key |
| **AN-3** | Does wireless charging count as "plugged in" for lending? | (a) no; (b) yes; (c) yes, with a lower temperature ceiling | **(a)** by default [AA11]; revisit after DV-L6 |
| **AN-4** | Where does a phone lender listen? | (a) overlay by default, LAN only with a fingerprint confirmation (weakness stated); (b) request location permission to confirm the SSID; (c) overlay only | **(c) for the first release, then (a)**. It avoids location permission [AA13], the targetSdk 37 prompt for LAN (§4.2) and certificate exposure on shared Wi-Fi (T13). If the owner picks D8(c) (LAN only), the phone cannot lend until (a) |
| **AN-5** | QR scanning on the phone | (a) CameraX + zxing-core (two new dependencies plus `CAMERA`), with a paste fallback; (b) paste or type only | **(a)**, adding CameraX to CD-D (R2-OVERCLAIM-10) |
| **AN-6** | Loopback contingency (R-A1) | (a) watch each release, with the §4.3 trigger; (b) design a Binder socket-pair data plane now | **(a).** (b) is a frozen-contract change with no present need |
| **AN-7** | Overlay log disclosure | (a) the §4.4 text at pairing, plus DV-A8; (b) say nothing | **(a)** |
| **AN-8** | Emulator probe now (a new `lab/android-probe/` build with AGP) or in v1.1? | (a) v1.1 H5 via a `sample-client` test flavour; (b) now, as a new directory (an AD-3 extension) | **(a).** Source reading already answers the question; the emulator adds little before the device test, and (b) needs a new directory this platform's brief does not allow |
| **AN-9** | NIK attestation contents | (a) challenge only, no device properties, chain never sent in mesh-1; (b) the design's "device-properties attestation where supported" | **(a).** A2 is deferred, and device properties put brand and model into a certificate [AF17] |
| **AN-10** | Lending constants (§2.3) | approve as PROVISIONAL / change | Approve as PROVISIONAL; calibrate from DV-L1 |

#### 11.2 Corrections this section asks the reviser to make in the design brief

1. **A05 → fact (from source), K3 severity lowered.** Replace A05 with AF05's statement. Keep H5 as a confirmation gate. Add AF04 (cross-profile loopback is blocked for all apps on Android 17) and a new risk for a possible future same-profile loopback permission (R-A1).
2. **A06 → fact for Android 17 r1, with its caveat.** Overlay peers with single-IP tun addresses are not "local network"; same-subnet LAN peers are. The rule changed from the 2025 `main` snapshot (AF06). Update §3.1's Android row ("`ACCESS_LOCAL_NETWORK` … for outgoing and incoming LAN TCP; loopback and VPN undocumented") and T14.
3. **A04 → fact for AOSP (AF12)**, OEM behaviour still open (AA04). The "serve unplugged" objection becomes battery and heat, not Doze.
4. **F10 / D8 / K16:** add AF21. The Tailscale Android client has had an opt-out since 1.97.331/1.98, default on, forced on under MDM. "No documented opt-out" remains true of the KB only.
5. **B28 / X16:** use `addThermalHeadroomListener` on API 36+ (AF14); the 10 s rule applies to API 30–35; API 29 has status only.
6. **T9 vs PA-charging:** state the conflict and the "serve while locked" leaf (§2.2, §5.1) in T9 and in §2.1's PA row for Android.
7. **LP-1 PF exception (R2-OVERCLAIM-9):** the §2.3 rule 4 text.
8. **CD-D (R2-OVERCLAIM-10):** add CameraX, and list the manifest permission additions of §8 as SIGN-OFF lines.
9. **RT row for targetSdk (R2-CONFORMANCE-10):** ruled by AN-1.
10. **§3.1 matrix "Packaging" for Android:** add the 2027 developer-verification step (AF24) and "no in-app update check".
11. **C4-analogue for Android:** Conscrypt knob gaps (AA06, AA07) go into the S-A9 matrix as named rows, run on ART at M1.

#### 11.3 Risk table

| ID | Risk | Severity | Mitigation | Residual (stated) |
|---|---|---|---|---|
| R-A1 | A future Android release gates **same-profile** cross-app loopback (the reserved `USE_LOOPBACK_INTERFACE` [AF05]), breaking v1's app-facing API | critical impact, unknown likelihood | Watch each release (§4.3 trigger); AIDL control plane unchanged; contingency = a Binder socket pair (contract change, owner) | Could arrive with little notice in a beta |
| R-A2 | LNP rules differ on a shipped or OEM build, or change again (AF06 → AF05 already changed) | medium | `LocalNetworkGate` built regardless; `LOCAL_NETWORK_DENIED` state; stay at targetSdk 35 (AN-1); DV-A2/A3 | A rule change can break overlay dialling at targetSdk 37 without code changes |
| R-A3 | OEM power management kills the FGS or cuts its network (RedMagic: no public record [AF35]) | medium–high | DV-A5, DV-L4; a dashboard hint to set battery to Unrestricted; no reliance on the exemption API | Unknowable until tested; may change with OEM updates |
| R-A4 | A stolen, running, "serve while locked" lender keeps mesh access | high impact | Opt-in only, PA-charging only; 30-min session age; revoke on each node; the clone signal (T10) | Exposure lasts until revoked everywhere |
| R-A5 | Heat and battery wear from lending while charging | medium | Table A ceilings; wired charging only (AN-3); `THERMAL_STATUS` drain; DV-L1 | Wear accumulates below the ceilings |
| R-A6 | Overlay log upload stays on (default on; forced on under MDM [AF21]) | medium | §4.4 disclosure; DV-A8; LAN-direct option (D8) | asom cannot see or enforce the setting |
| R-A7 | The Android 17 memory limiter kills a lender serving a large model [AF04] | medium | RAM guard counting anonymous memory [AA09]; `ApplicationExitInfo` latch; mmap weights | Device-specific limits |
| R-A8 | Conscrypt cannot meet a TLS knob (server-side resumption, Keystore key in the key manager) [AA06][AA07] | medium | S-A9 rows on ART; W08 both roles as the gate | Server-side resumption tolerated but never exercised by a conforming client (design T2) |
| R-A9 | Developer verification blocks sideload and F-Droid installs from 2027 [AF24] | high (distribution) | D22: register the identity and key; limited-distribution fallback | Advanced-flow friction for other users |
| R-A10 | CI gives false confidence: API 37 only as a `google_apis` image [AF30], no StrongBox, no OEM, simulated thermal | medium | Every emulator result labelled EMULATOR EVIDENCE; device checklist kept open | — |
| R-A11 | Paired peers infer when the owner uses the phone (lender drains on screen-on) | medium | LP-2 hold-down; bands; one decline code; disclosed in the lending consent | Inherent (design §7.12) |
| R-A12 | Screen-off Wi-Fi power save delays the first packet [AF15] | low | Dial budgets; DV-A6 | — |
| R-A13 | Play rejects the `specialUse` text if Play is ever used [AF07] | low | Sideload and F-Droid first (brief §14.5) | — |
| R-A14 | `ubuntu-latest` becomes 26.04 in November 2026 [AF28] | low | Pin `ubuntu-24.04` for Android jobs until a 26.04 run is green [AA14] | — |

#### 11.4 What this section does NOT guarantee

- The LNP and loopback conclusions are **readings of AOSP source at `android-17.0.0_r1`**. They say nothing certain about OEM builds, mainline module updates after that tag, or Android 18. They must be re-checked on the emulator and on the device.
- The AOSP Doze conclusion (AF12) is the same kind of reading. The owner's device may differ.
- No performance or battery figure in this section is a measurement. All governor constants are PROVISIONAL.
- The Tailscale conclusions come from reading its source, not from packet captures.
- Nothing here has been compiled or run. No Android SDK exists in this environment, and the repository was not modified.
