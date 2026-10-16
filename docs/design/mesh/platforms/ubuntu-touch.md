# Platform section: Ubuntu Touch (UBports / Lomiri)

**Revision 4 (2026-10-07):** §12 amends this section; where they differ, §12 wins (`../REVISION_4.md`).

**Date:** 2026-09-30 · **Grade:** DIRECTION (an input to the roadmap v4 design session and to the phased plan; nothing here authorises execution) · **Scope:** Ubuntu Touch 24.04-1.x and 24.04-2.x on Halium phones and tablets, as distributed by UBports; 26.04-1.x ("next") only as a CI canary; 20.04 excluded · **Target directory:** `ubuntu-touch/` (AD-3).
**Reads with:** `OWNER_DIRECTIVES_2026-09-30.md` (D-A…D-F, AD-1…AD-6, treated as decided), `ASOM_MESH_DESIGN.md` r2 (§2.1 roles, §3 platform matrix, §4 T2/T9/T13/T17, §5.3–§5.4, §7.4 LP-1/LP-2, §8.2 IC-1…IC-8, §8.4 ledger, §8.6 quiescence, §9.3), `REVIEW_ROUND2.md`, and the sibling `platforms/linux.md` (whose JVM node this section reuses).
**Tags:** `[UFnn]` = verified in this session (source and date in §1.1). `[UAnn]` = assumption (§1.2). `[Fnn]`/`[Ann]` = the design brief's Appendix A/B ids; `[LFnn]`/`[LAnn]` = `linux.md` ids, inherited without re-fetching unless said. `SIGN-OFF` = needs the owner. Every performance number is an estimate unless it carries a `[UF]` tag.

**The answer in brief (tiered).**

| Tier | What an Ubuntu Touch device is in the mesh | When | Holon completeness (R2-DIRECTIVES-4) |
|---|---|---|---|
| **UT-0** | Nothing on the mesh. A click scaffold that proves the shared JVM node code runs in the Ubuntu Touch userland (CI, arm64) and under click confinement (owner device) | Now, under AD-3/AD-4; **ships nothing** | — |
| **UT-1** | A **foreground-only requester (R)** and **manifest verifier (S)** for the asom app's *own* chat screen. It borrows from paired Linux, Deck and Dell lenders (later Mac and Android). No listener, no engine, no keys, no cloud tier | After M1 (mesh-1); never blocks M1 | **Borrow-only.** It cannot serve itself: no engine and no cloud tier |
| **UT-2** (optional, unscheduled) | Adds a local CPU engine serving its own screen, a `quick`-plan benchmark producer (B) with a per-export signed file, and a **lend-screen-frontmost lender (PF)** on Wi-Fi | Revisit after M2 (UT-D7) | Whole for its own screen only |
| **Never** | An unattended lender (PA, "always-on"); a cross-app daemon for other Ubuntu Touch apps; a BYOK cloud router; a GPU or NPU engine; any device still on 20.04 | — | — |

**Honest NOs, each with its reason.**
- **No PA lending, in any store-distributable form.** In phone and tablet modes Lomiri SIGSTOPs every process in an unfocused app's cgroup [UF15][UF20], and repowerd suspends the whole system about 4 s after the display goes off [UF21]. A confined app has no policy group that lets it hold the system awake [UF13][UF21]. The only precedents for an always-on service are unconfined apps with root-installed wakelock units [UF27], which need manual review and `sudo` and defeat the watched-object rule.
- **No cross-app service for other Ubuntu Touch apps.** The node is frozen whenever another app is focused [UF15]. A loopback TCP caller also has no OS-verified identity (every app runs as the same user, and AppArmor labels do not travel over TCP) [UA18]. That is exactly the case IC-8 excludes ("no cross-app service where no OS-verified identity exists").
- **No BYOK keys.** There is no app-reachable hardware keystore and no keyring policy group [UF13], and Invariant 4 names the Android Keystore. So there is no cloud tier on Ubuntu Touch, and peers never make cloud calls for it (design §2.6).
- **No GPU or NPU inference.** Halium devices use Android vendor drivers through libhybris. No evidence was found of Vulkan or OpenCL compute reachable from a click app (§6). CPU only.
- **No QML/JavaScript-only node.** `XMLHttpRequest` cannot speak `asom-mesh/1` (length-prefixed frames over mutually authenticated, pinned TLS 1.3). Qt 5.15's `QSslSocket` cannot host a replace-PKIX verifier as the sole trust path during the handshake; Qt added interruptible verification only in Qt 6.0 [UF39] (§7.5).
- **No standalone Ubuntu Touch benchmark product.** Neither MLPerf Mobile nor MLPerf Client targets Ubuntu Touch, but directive D-C says coverage is not a differentiator. Benchmarking here is justified only when it feeds this node's own router (UT-2).
- **No 20.04 build.** UBports is reducing 20.04 updates to "only as needed" [UF06].

---

### 1. Verified platform facts (each with a source URL and date) and Assumptions

#### 1.1 Verified facts

Everything was fetched, cloned or run on **2026-09-30** unless another date is given. For git sources the commit and its date are given, because the claim is "this is what the code says at that commit". "Confidence" rates the source's quality for the exact claim.

| ID | Fact | Source (source date) | Conf. |
|---|---|---|---|
| UF01 | **Ubuntu Touch 24.04-2.0 is the current feature release, on Ubuntu 24.04 LTS (Noble).** Released 25 July 2026 per Wikipedia; Tux Machines relays the 9to5Linux report dated 24 July 2026. Morph Browser moves to Chromium 134; Widevine installer; screenshot editor; printing. New devices: Nothing Phone (1) and Zinwa Q25 | https://en.wikipedia.org/wiki/Ubuntu_Touch ; https://news.tuxmachines.org/n/2026/07/24/Ubuntu_Touch_OTA_2_0_Officially_Released_for_Supported_Linux_Ph.shtml (2026-07-24) | medium-high (secondary; sources differ by one day) |
| UF02 | **24.04-2.0 device list (UBports blog):** Asus Zenfone Max Pro M1, F(x)tec Pro1X, Fairphone 4, Fairphone 5, JingPad A1, Lenovo Tab M10 HD 2nd Gen WiFi/LTE, OnePlus Nord N10 5G, OnePlus Nord N100, Rabbit R1, Sony Xperia X, Volla Phone, Volla Phone X, Volla Phone 22, Volla Phone X23, Volla Phone Quintus, Volla Phone Plinius, Xiaomi Poco X3 / X3 NFC, Xiaomi Redmi 9 and 9 Prime, Xiaomi Redmi Note 9, Zinwa Q25. "Ubuntu Touch 24.04-1.4 is available for all existing devices running Ubuntu Touch 24.04-1.3." **Conflict:** devices.ubuntu-touch.io says the Volla Phone 22 and Quintus are "not supported by the current Ubuntu Touch release" | https://ubports.com/blog/ubports-news-1/ubuntu-touch-24-04-2-0-and-24-04-1-4-release-4007 ; https://devices.ubuntu-touch.io/device/mimameid/ ; https://devices.ubuntu-touch.io/device/algiz/ | high for the blog text; the device site disagrees |
| UF03 | **24.04-1.0** was released on 2025-09-30. It was the first release on 24.04 LTS, brought "Qt 5.15" and experimental encryption of personal data, and requires a two-step upgrade (20.04 OTA-10 first) | https://linuxiac.com/ubuntu-touch-24-04-1-0-released-based-on-ubuntu-24-04-lts/ (via search summary); https://ubports.com/blog/ubports-news-1/ubuntu-touch-24-04-1-0-release-3973 | medium-high |
| UF04 | "Applications built against 24.04-1.x will be allowed to use APIs introduced in Qt 5.15, paving a way to Qt 6 migration in the future." The 2025 schedule ended "24 September 2025: Release version 24.04-1.0" | https://forums.ubports.com/topic/11163/ubuntu-touch-24.04-1.0-is-scheduled-to-be-released-on-24-september (2025) | high |
| UF05 | 24.04-1.3 (2026-05-07). QtWebEngine 5.15.19 is based on Chromium 87. The plan was to move Morph to Qt 6, but "many apps and system components still require Qt 5" | https://linuxiac.com/ubuntu-touch-24-04-1-3-released/ (2026-05-07) | medium-high (secondary) |
| UF06 | **20.04 is winding down:** "we're reducing the frequency of updates to Ubuntu Touch 20.04 series … It's likely that future updates to 20.04 series will occur only as needed." No end-of-life date is given | https://ubports.com/blog/ubports-news-1/ubuntu-touch-24-04-1-2-and-20-04-ota-12-release-3987 | high |
| UF07 | **UBports series registry:** `26.04-1.x` (base `resolute`, alias `next`, built from `main`), `24.04-2.x` (noble), `24.04-1.x` (noble), `focal` | https://gitlab.com/ubports/infrastructure/build-tools/-/raw/main/ut-version-info.json | high |
| UF08 | **Clickable 8.10.0** (PyPI `clickable-ut`, uploaded 2026-09-10). Changelog: 8.10.0 "Added support for ubuntu-touch-26.04-1.x framework"; 8.9.0 adds 24.04-2.x; 8.3.0 adds Noble (24.04-1.x). Framework → AppArmor policy: `24.04-1.x→2404.1`, `24.04-2.x→2404.2`, `26.04-1.x→2604.1`. **Defaults are still `default_qt = '5.12'` and `framework_base_default = '20.04'`**, so a 24.04 app must set its framework explicitly. Builders include `cmake`, `qmake`, `pure-qml-cmake`, `rust` (`cargo +<channel> install --target aarch64-unknown-linux-gnu`), `go` and `precompiled`. `clickable build` "finally runs a review"; manifest placeholders are `@CLICK_ARCH@`, `@CLICK_FRAMEWORK@` and `@APPARMOR_POLICY@` | https://pypi.org/project/clickable-ut/ ; https://gitlab.com/clickable/clickable/-/blob/542a92a599734437c3e174800dffaa9d6191e2c9/clickable/config/constants.py (commit 2026-09-10); `docs/changelog.rst`, `docs/commands.rst` at the same commit | high |
| UF09 | **Clickable CI images.** Names: `clickable/ci-ut24.04-{1,2}.x-{amd64,armhf,arm64}` and `clickable/ci-ut26.04-1.x-*`. On Docker Hub `ci-ut24.04-2.x-arm64` carries tags `latest` (2026-09-18), `8` and `8.10.0` (2026-09-11), is built for **linux/amd64** (it cross-builds for arm64) and is about 1.5 GB. The arm64-*host* development images `clickable/arm64-ut24.04-1.x-arm64` and `clickable/arm64-ut24.04-2.x-arm64` exist for **linux/arm64**; `ci-ut24.04-1.x-amd64` and `ci-ut26.04-1.x-arm64` exist too (tags `8.10.0` 2026-09-11, `latest` 2026-09-18). The default build directory is `${ROOT}/build/${ARCH_TRIPLET}/app` (`clickable/config/project.py`). The CI Dockerfile is `FROM clickable/amd64-ut$UT_VERSION-$TARGET_ARCH`, installs clickable from `ppa:bhdouglass/clickable` and sets `CLICKABLE_CONTAINER_MODE=1`, `CLICKABLE_ARCH=$TARGET_ARCH` and `CLICKABLE_NON_INTERACTIVE=1`. The Clickable CI documentation page still names only the 20.04 images (stale) | https://hub.docker.com/v2/repositories/clickable/ci-ut24.04-2.x-arm64/tags ; https://hub.docker.com/v2/repositories/clickable/arm64-ut24.04-2.x-arm64/tags ; https://gitlab.com/clickable/clickable-docker-images/-/blob/6c4503d567b4ed00dbf69e129912460554684655/ci/generic/Dockerfile (2026-08-17) | high |
| UF10 | `clickable test` defaults to `qmltestrunner` "with a virtual screen"; the rust builder's test runs `xvfb-startup <cmd>` | `docs/project-config.rst`, `docs/commands.rst`, `clickable/builders/rust.py` at commit 542a92a | high |
| UF11 | **Click AppArmor template `ubuntu-sdk` (policy 2404.2).** The click install dir `@{CLICK_DIR}/@{APP_PKGNAME}/@{APP_VERSION}/**` is `mrklix` (**bundled binaries may be executed and mmapped**). `~/.local/share/@{APP_PKGNAME}/**` is `mrwklix` (writable **and** executable). `~/.cache/<pkg>/**`, `~/.config/<pkg>/**` and `/run/user/*/<pkg>/**` are `mrwkl`. `/run/user/*/confined/<pkg>/` is the app's TMPDIR. `/proc` access is limited to a short list. **No rule for `/sys/class/thermal` or `/sys/class/power_supply`.** libhybris libraries are mappable. Policy versions shipped: `20.04`, `2404.1`, `2404.2`, `2604.1` | https://gitlab.com/ubports/development/core/apparmor-easyprof-ubuntu/-/tree/d426cf56392f84c05f4979ccdc754abf5e8d93a7/data (commit 2026-09-01), `templates/ubuntu/2404.2/ubuntu-sdk` | high (for what the policy text says) |
| UF12 | **Policy group `networking`** = `#include <abstractions/nameservice>` + DownloadManager D-Bus rules + an explicit **deny** of NetworkManager and ofono D-Bus + `network netlink dgram`. Upstream `abstractions/nameservice` grants `network inet stream`, `inet6 stream`, `inet dgram`, `inet6 dgram` and `network netlink raw`. These are coarse family/type rules: they name no address, port or operation | same repo `policygroups/ubuntu/2404.1/networking`; https://gitlab.com/apparmor/apparmor/-/blob/master/profiles/apparmor.d/abstractions/nameservice | high (text); the device's shipped abstraction may differ slightly [UA19] |
| UF13 | **Other groups.** `keep-display-on` allows only `com.canonical.Unity.Screen.{keepDisplayOn,removeDisplayOnRequest}` on the system bus. `connectivity` allows only `/com/lomiri/connectivity1/NetworkingStatus` ("coarse network connectivity information"). **No group in 2404.1/2404.2 mentions a keyring, secret service, keystore or TPM** (grep of the policy tree) | same repo, `policygroups/ubuntu/2404.1/*` | high |
| UF14 | **Store review classes (policy 2404.2).** *Common* (automated review) groups: accounts, audio, camera, connectivity, content_exchange, content_exchange_source, fm_radio, keep-display-on, location, microphone, networking, push-notification-client, sensors, usermetrics, video, webview, nfc. *Reserved* (manual review): bluetooth, calendar, contacts, debug, history, and the music, picture and video file groups. Templates: `unconfined` is **reserved** and red-flagged (`redflag_templates = ['unconfined']`). Frameworks `ubuntu-touch-24.04-1.x` and `-2.x` are "available" | https://gitlab.com/clickable/click-reviewers-tools/-/blob/9f5abad01775f020e07e41f74eb78d7bac505570/clickreviews/apparmor.py (commit 2026-09-26); `clickreviews/cr_security.py`, `clickreviews/frameworks.py` | high |
| UF15 | **Lomiri app lifecycle (the shell decides).** `Stage.qml`: an app is `RequestedRunning` iff the stage mode is `"windowed"` **or** (the stage is not `suspended` **and** the app is the focused, main-stage or side-stage app); otherwise `RequestedSuspended`. `Shell.qml` binds the Stage's `suspended: greeter.shown`. An app is exempt from the lifecycle iff `!isTouchApp` (X11/Libertine apps) **or** its short app id is in the gsettings list `com.canonical.qtmir lifecycle-exempt-appids` **or** it is temporarily awakened through ProcessControl | https://gitlab.com/ubports/development/core/lomiri/-/blob/acf48c884fee778efb2fa9ac2d09fabea152aaf9/qml/Stage/Stage.qml (commit 2026-09-24), lines ~196–208 and ~645–677; `qml/Shell.qml` ~370–391 | high (code) |
| UF16 | The default of `lifecycle-exempt-appids` is `['music.ubports']`. The schema file says "It is interpreted by Lomiri shell these days". An exempt app goes to `RunningInBackground` rather than being suspended. **No API exists for an app to add itself;** users change the list with `gsettings` in a terminal or with third-party tweak tools | https://gitlab.com/ubports/development/core/qtmir/-/blob/bc86eea99e7a5c7eae765e0222d39c7871654224/src/modules/QtMir/Application/com.canonical.qtmir.gschema.xml (commit 2026-09-26); `application.cpp` ~658–676; tweak-tool usage per https://forums.ubports.com/topic/5367/few-questions-about-ubuntu-touch | high (code); medium (tweak tool) |
| UF17 | `com.lomiri.ProcessControl.RequestWakeup(as processes, t timeSpan)` is "meant to be accessible only from privileged system processes". Lomiri also keeps the **location service's client apps** awake (`LocationWatcher`) | `lomiri` commit acf48c8, `plugins/ProcessControl/com.lomiri.ProcessControl.xml`, `LocationWatcher.cpp` | high (code) |
| UF18 | **Windowed ("desktop") mode** is chosen only when `min(width, height) > 60` grid units **and** a mouse or touchpad is connected. Phone category → `staged`; tablet → `stagedWithSideStage` | `lomiri` commit acf48c8, `qml/OrientedShell.qml` ~124–150 and ~318–338 | high (code) |
| UF19 | qtmir `Session::suspend()` applies `mir_lifecycle_state_will_suspend` and starts a **1500 ms** single-shot timer, after which the session is `Suspended` | `qtmir` commit bc86eea, `src/modules/QtMir/Application/session.cpp` ~299–314, ~564–576 | high (code) |
| UF20 | qtmir's `TaskController::suspend(appId)` calls `instance->pause()` for every instance. `lomiri-app-launch` `Base::pause()`: "Pauses this application by sending SIGSTOP to all the PIDs in the cgroup". **Child processes of an app are frozen with it** | https://gitlab.com/ubports/development/core/lomiri-app-launch/-/blob/3bce3f6ce08e0f4322c590cec5a5dcbbbd3e9405/liblomiri-app-launch/jobs-base.cpp (commit 2026-09-27) ~728–740; `qtmir` commit bc86eea, `src/modules/QtMir/Application/lal/taskcontroller.cpp` ~213–225 | high (code) |
| UF21 | **repowerd.** When the display turns off (reason not proximity) and suspend is allowed, a **4000 ms** alarm fires and then `allow_automatic_suspend`. `com.lomiri.Repowerd.requestSysState(name, state)` accepts only state 1 (active) and disallows suspend while held. **No policy group in the policy repo grants any Repowerd D-Bus access** (grep) | https://gitlab.com/ubports/development/core/repowerd/-/blob/f3bf63226cc8c348654f75af8fe4cd11ff49333f/src/core/default_state_machine.cpp (commit 2026-08-19) ~176–186, ~880–889; `src/adapters/unity_screen_service.cpp` ~130–160, ~842–862 | high (code) |
| UF22 | **OpenStore rules:** "You are only allowed to publish apps that you have permission to distribute"; "Your app can be pulled without warning at the discretion of our admins". Manual review goes through Telegram with a repository link and a reason ("needs to run a daemon"), and "Only open source applications allowed for manual review". Prohibited: stealing user data, malicious processes, and so on | https://open-store.io/submit/ | high |
| UF23 | OpenStore: "We don't track you or your device … we do not track your downloads". Device information (CPU architecture, framework versions, OS version, OS language) is "sent to the server" but "not logged or stored". Review "is far from exhaustive … there are no guarantees" | https://open-store.io/about/ | high (for the stated policy) |
| UF24 | **The OpenStore client verifies no signature or hash.** It installs through the system D-Bus `com.lomiri.click` `Install`, or falls back to `pkcon install-local --allow-untrusted <file>`; `clickinstaller.cpp` contains no hash or signature check. The `click` tool supports `debsig-verify`, gated by an `allow_unauthenticated` flag | https://gitlab.com/theopenstore/openstore-app/-/blob/bbdcca46c870a2e147528fac974fa317f57d15e6/src/clickinstaller.cpp (commit 2026-09-22); https://gitlab.com/ubports/development/core/click/-/blob/main/click_package/install.py | high (client code); whether the D-Bus installer enforces debsig is not established [UA13] |
| UF25 | **Precedent: a confined click runs llama.cpp.** UTGPT (OpenStore; published 2026-07-04, updated 2026-07-20; permissions `networking`, `content_exchange`, `content_exchange_source`; channel focal, framework `ubuntu-sdk-20.04.1`; MIT). It runs `llama-cli` as a **subprocess** via PyOtherSide/Python, from a bundled asset or a binary it downloads into its data directory. Its GitHub Actions job cross-compiles llama.cpp for `aarch64` (`-DGGML_CPU_ARM_ARCH=armv8-a`) on `ubuntu-latest` | https://open-store.io/api/v4/apps/utgpt.surajyadav ; https://github.com/suraj-yadav0/utgpt (commit fc29480, 2026-07-20): `utgpt.apparmor`, `.github/workflows/build-compat-binary.yml`, `backend/backend.py` | high (for what the listing and code show; its performance is not reported) |
| UF26 | Precedent: UBConnect (KDE Connect for Ubuntu Touch) is "highly unconfined", ships a background daemon that the app enables through systemd, and is on the OpenStore (manually reviewed) | https://forums.ubports.com/topic/11855/ubconnect-a-native-ubuntu-touch-application-for-kde-connect | medium |
| UF27 | Precedent: briglia-ut gets always-on service with "systemd user service + linger + the Ubuntu Touch kernel keep-awake unit" (a root-owned system unit). It uses the `unconfined` template and in-app `sudo -S` with the passcode | https://github.com/permaevidence/briglia-ut (README) | medium-low (one project README) |
| UF28 | **Libertine:** the default container type is `chroot` (`lxc` if the kernel allows). The rootfs lives in `~/.cache/libertine-container/<id>/rootfs/`. "Applications will not run in the background in Libertine." It uses XMir | https://docs.ubports.com/en/latest/userguide/dailyuse/libertine.html | high (docs; note UF15 shows non-touch apps are exempt from shell suspension, so this sentence is best read as "the system still suspends") |
| UF29 | snapd "will ship with our upcoming Ubuntu Touch release based on 24.04 noble by default" (2025-04-24). In 24.04-2.0, stage-1 snap interfaces are `mir`, `thumbnailer-service`, `media-hub` and `screen-inhibit-control` (2026-06-09) | https://forum.snapcraft.io/t/ubuntu-touch-snapd-enablement-update-25-04-24/46743 ; https://forum.snapcraft.io/t/snap-support-update-in-ubuntu-touch-24-04-2-0/51744 | medium-high |
| UF30 | **Overlay client.** No OpenStore Tailscale client. The community `tailscale-snap2` installs with `--devmode`; the official snap "hasn't been updated in a long while". Tailscale clients stream logs to `log.tailscale.com`; on Linux `--no-logs-no-support` or `TS_NO_LOGS_NO_SUPPORT=true` opts out; no opt-out is documented for Android or iOS (page "last validated Jan 5, 2026") | https://forums.ubports.com/topic/10559/tailscale-decentralised-vpn ; https://tailscale.com/kb/1011/log-mesh-traffic | medium-low (forum) / high (KB) |
| UF31 | Android 11 (R) kernel base config requires `CONFIG_TUN=y` (and `CONFIG_IPV6=y`) | https://android.googlesource.com/kernel/configs/+/refs/heads/main/r/android-5.4/android-base.config | high (for the base config; per-device kernels [UA05]) |
| UF32 | **Fairphone 5** on Ubuntu Touch: Qualcomm QCM6490, 1×A78 at 2.7 GHz + 3×A78 at 2.4 GHz + 4×A55 at 1.9 GHz, Adreno 643, 6 or 8 GB RAM, Halium 11, kernel 5.4.289, channel 24.04 (page shows 24.04-1.4) | https://devices.ubuntu-touch.io/device/FP5/ | high (device page) |
| UF33 | **Volla Tablet:** MediaTek Helio G99 (MT8781), 2×A76 at 2.2 GHz + 6×A55 at 2.0 GHz, Mali-G57 MC2, 12 GB LPDDR4X at 2133 MHz, Halium 13, kernel 5.10.198, 24.04 | https://devices.ubuntu-touch.io/device/mimir/ | high (device page) |
| UF34 | 24.04 **filesystem encryption** is per-file (fscrypt), not full-disk. It must be enabled per device, is experimental in 24.04-1.x, and the porting documentation says nothing of hardware-wrapped keys or a TEE | https://docs.ubports.com/en/latest/porting/configure_test_fix/Fscrypt.html ; UF03 sources | high (docs) |
| UF35 | Precedent for Java on Ubuntu Touch: a bundled OpenJDK in a click worked on amd64 (`clickable desktop`) but "closes itself immediately" on arm64 and armhf. The GUI case needed `X-Ubuntu-XMir-Enable=true` and the **unconfined** template because "x-mir-helper isn't able to launch confined". This was a Swing GUI, not a headless JVM, and the date is not shown in the fetch (a 2020-era topic id) | https://forums.ubports.com/topic/4132/getting-java-working-on-ubtouch | medium (old; different use) |
| UF36 | Temurin Linux **aarch64** JDKs are available: 17.0.20.1+1 (2026-09-01) and 21.0.12.1+1 (2026-08-21) | https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=aarch64&image_type=jdk&os=linux ; `…/latest/17/…` | high |
| UF37 | **Run in this container (not recalled):** x86_64 `jlink` 21.0.10 over the Temurin 21.0.12.1 **aarch64** `jmods`, modules `java.base,java.logging,jdk.crypto.ec,jdk.net,jdk.unsupported`, `--strip-debug --no-header-files --no-man-pages --compress=zip-9`, produced an aarch64 runtime of **39 MB** on disk and **20,520,913 bytes** as `tar.gz`. The host `objcopy` could not strip the aarch64 native symbols (three errors printed; the image was still produced). The highest glibc symbol version referenced by `bin/java`, `lib/server/libjvm.so`, `libnet.so` and `libnio.so` is **`GLIBC_2.17`** (`objdump -T`) | this session, `scratchpad/ut-research/rt-aarch64` | high (measured) |
| UF38 | GitHub runner image Ubuntu 24.04 `20260920.314.1`: Docker server 28.0.4, Rust 1.98.1, kernel 6.17.0-1022-azure. The Arm Ubuntu 24.04 image lists Docker, Java and Rust. (Hosted runner sizes, `ubuntu-24.04-arm` for public repositories, and no GPU: [LF32]) | https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md ; https://github.com/actions/partner-runner-images/blob/main/images/arm-ubuntu-24-image.md | high |
| UF39 | Qt 6 `QSslSocket::continueInterruptedHandshake()` "was introduced in Qt 6.0", together with `handshakeInterruptedOnError` (early error reporting). `peerVerifyError` is emitted only for verification errors found during the handshake | https://doc.qt.io/qt-6/qsslsocket.html | high |
| UF40 | Waydroid "comes preinstalled" on Ubuntu Touch since the Focal release; users only initialise it | forum summary: https://forums.ubports.com/topic/8807/outdated-waydroid-on-ubuntu-touch-also-for-unofficially-supported-devices | low-medium (search summary) |
| UF41 | "Ubuntu Touch doesn't have ufw enabled as it has no open ports", and ufw fails on it (missing iptables, read-only files) | https://forums.ubports.com/topic/9148/ubuntu-touch-firewall ; https://forums.ubports.com/topic/4743/error-when-enabeling-ufw (via search summary) | low |
| UF42 | AppArmor `abstractions/base` (upstream) allows reading `/proc/meminfo`, `/proc/stat`, `/proc/cpuinfo`, `/sys/devices/system/cpu/{,online,possible}` and `@{PROC}/@{pid}/{maps,auxv,status}` | https://gitlab.com/apparmor/apparmor/-/blob/master/profiles/apparmor.d/abstractions/base | high (upstream text) |

**Inherited without re-fetching:**
- [F40][F43]: MLPerf Mobile v6.0 targets Android, iOS and Windows, and depends on Firebase.
- [LF37]: MLPerf Client v2.0 lists Ubuntu Linux 24.04 as a CLI-only platform, with its execution paths unconfirmed.
- [LF09][LF10]: the Tailscale Linux log opt-out, and Headscale clients still contacting `log.tailscale.com` without it.
- [F11]: userspace networking forwards inbound connections to 127.0.0.1.
- [F27][F28]: whole-model placement wins; llama.cpp RPC is insecure.
- [LF27]: Temurin support dates.
- [LF32]: hosted runner facts.
- [LF34]: llama.cpp's Linux arm64 release flags `GGML_BACKEND_DL` and `GGML_CPU_ALL_VARIANTS`.
- [LF38]: the limits of GitHub artifact attestations.
- [F09]: overlay relays carry encrypted traffic.
- [A12a]: the flagship-phone planning figure.

#### 1.2 Assumptions (not verified)

| ID | Assumption | Load-bearing for | How to settle |
|---|---|---|---|
| UA01 | **A headless jlinked Temurin 21 aarch64 JVM starts and runs JSSE TLS 1.3 with mutual ES256 authentication inside a click under the `ubuntu-sdk` 2404.x profile on a Halium device**, with `-XX:-UsePerfData -XX:-UseContainerSupport -Djava.io.tmpdir=$TMPDIR`, and produces no AppArmor denial that breaks it. UF11 permits executing bundled binaries, UF25 shows a bundled native process running confined, and UF35 is the only Java precedent (GUI, unconfined, old) | the whole runtime choice (§7) | spike **S-UT1** (UT0.6, DV-UT01) |
| UA02 | 24.04-2.x images still provide the `ubuntu-touch-24.04-1.x` framework, so one 1.x click installs on both series | the single-build packaging plan | DV-UT02: `click framework list` on a 2.x device, or install and launch |
| UA03 | Under the 24.04 Wayland/Mir stack the app receives a Qt application-state change (Inactive or Suspended) **before** the SIGSTOP, within the 1500 ms window of UF19 | clean drain on backgrounding (§3.3) | DV-UT04: log `Qt.application.state` transitions against `ps -o stat` of the JVM child |
| UA04 | A `keepDisplayOn` request held by a confined app (group `keep-display-on`) keeps the display on and so prevents repowerd's automatic suspend (UF21) for as long as it is held | streams not dying to screen-off | DV-UT05 |
| UA05 | Ubuntu Touch device kernels have `CONFIG_TUN` (UF31 is Android's base requirement), so `tailscaled` can run in kernel-TUN mode | the overlay path | DV-UT09: `ls -l /dev/net/tun`; `ip -br link show tailscale0` |
| UA06 | The `connectivity` group's NetworkingStatus exposes a bandwidth or metered limitation, so rule C8 (no peer attempt over a metered path unless allowed) can be enforced | C8 on Ubuntu Touch | DV-UT10; fallback: treat any non-Wi-Fi interface as metered |
| UA07 | The Terminal app, `adb shell` and SSH sessions (all the same user) can read the app's `~/.local/share/<pkg>/` | the stated limit of the T0 key tier (§5) | DV-UT11: `cat` the key file's size from Terminal |
| UA08 | No host firewall is active by default on 24.04 (UF41 is low confidence) | the UT-2 listener's exposure | DV-UT12: `sudo nft list ruleset; sudo iptables -S` |
| UA09 | **Decode estimates** for Ubuntu Touch hardware (§6.3): tok/s ≈ η × bandwidth ÷ file bytes, with η = 0.4–0.6 on phone CPUs; Volla Tablet peak ≈ 17 GB/s (LPDDR4X-4266 × 32-bit); Fairphone 5 peak 25–51 GB/s (memory type and width not confirmed) | the UT-2 value judgement only | UT-2 owner-device benchmark (NEEDS-OWNER-VALIDATION) |
| UA10 | On a hosted `ubuntu-24.04-arm` runner, AppArmor enforces, `apparmor_parser -r` works with `sudo`, and a profile generated by `aa-easyprof` (from `apparmor-utils`) from the pinned UBports templates loads | the CI confinement approximation (§9) | the CI job itself; it **fails, never skips** |
| UA11 | The requester JVM's RSS is about 100–180 MB with `-Xmx128m -XX:+UseSerialGC` | RAM on 4–6 GB phones | measured in the arm64 CI lane (`VmRSS`), reported, not gated; DV-UT01 on device |
| UA12 | The OpenStore's automated review accepts an arm64 click of roughly 25–30 MB compressed (≈50 MB installed) that bundles a JRE and uses only common policy groups | store distribution | first upload (owner) |
| UA13 | The on-device install path used by the OpenStore does not enforce click signatures (UF24 shows the client passes `--allow-untrusted` in its fallback) | the update-integrity statement (§8) | read `lomiri-click` service source; DV-UT13 |
| UA14 | A Libertine noble container runs as the user without click confinement and can `apt install openjdk-21-jre-headless` from the Ubuntu archive | the device-spike escape hatch (§7.6) | DV-UT14 |
| UA15 | QR decoding of camera frames works either with zxing-core in the JVM (frames passed over the control channel) or with a C++ decoder in the plugin, within about 300 ms per frame on a Fairphone 4/5 | QR pairing (UT-D4) | UT-1 prototype |
| UA16 | A confined app can map a connected socket's local address to an interface name (`wlan0`, `tailscale0`) through Java `NetworkInterface`, since netlink is allowed (UF12) | `peerPath` truth (design §8.4) | arm64 CI lane (container) + DV-UT09 |
| UA17 | In windowed mode the app's Qt state becomes Inactive when the greeter (lock screen) is shown | lock-screen rule L-UT3 (§3.4) | DV-UT06 |
| UA18 | Loopback TCP gives the server no AppArmor label for the peer (no labelled networking on Halium kernels), and confined apps cannot connect to another app's Unix socket in its private runtime directory | the "no cross-app service" NO | DV-UT15 (`SO_PEERSEC` on an accepted loopback socket returns nothing or `unconfined`) |
| UA19 | The device's `abstractions/nameservice` and `abstractions/base` match upstream (UF12, UF42) closely enough that the node's needs (inet stream, netlink, `/proc/meminfo`) are granted | S-UT1 | DV-UT01 denials log |
| UA20 | A C++ QML plugin (Qt 5.15 `QProcess`) can spawn the bundled `java` from the click directory with stdio pipes under confinement (the `ix` rule of UF11) | the process model (§3) | DV-UT01 |

---

### 2. Feasible mesh roles on this platform

Vocabulary (design §2.1): **R** borrow; **PA** lend while awake with no human present; **PF** lend while a lend screen is frontmost; **B** benchmark producer; **S** manifest subscriber and verifier.

#### 2.1 Role verdicts

| Situation | R (borrow) | PA | PF | B / S | Holon completeness |
|---|---|---|---|---|---|
| **Phone, staged mode** (every phone) | **Yes (UT-1)**, only while the asom app is the focused app and the screen is unlocked. Only the app's **own** chat screen can borrow; other apps cannot | **No.** Unfocused apps are SIGSTOPped with their whole cgroup [UF15][UF20]; the lock screen suspends the stage [UF15]; the system suspends ~4 s after display-off [UF21] | **UT-2 only, and weak:** a dedicated lend screen, kept frontmost and unlocked, display held on with `keepDisplayOn` [UF13][UA04]. It serves only while the owner leaves the phone on its screen | B: UT-2, `quick` plan only (no thermal signal; §6.4). S: yes, UT-1 | UT-1 borrow-only; UT-2 whole for its own screen |
| **Tablet, side-stage mode** (Volla Tablet, JingPad A1, Lenovo M10) | As phone; a side-stage app also stays running [UF15] | No | UT-2: as phone | as phone | as phone |
| **Tablet or phone in windowed mode** (keyboard and touchpad attached, large screen [UF18]) | Yes | **No.** Apps are not SIGSTOPped in windowed mode, even under the lock screen [UF15], but repowerd still suspends the system after display-off [UF21]. Holding the display on indefinitely is PF, not PA | UT-2: the one realistic PF case (a docked Volla Tablet, screen on, lend window open) | as phone | as phone |
| **User-added lifecycle exemption** (`gsettings set com.canonical.qtmir lifecycle-exempt-appids …` [UF16]) | not needed | **No.** The app then runs in the background while the screen is on, but the system still suspends after display-off. asom will **not** instruct users to do this: it changes system state outside the app, and the store would not accept a click that did it itself | — | — | — |
| **Unconfined click + root keep-awake unit** (the UF27 pattern) | — | **Rejected.** Technically it gives PA. It needs the reserved `unconfined` template (manual review [UF14][UF22]), `sudo` with the device passcode and a root-owned wakelock unit. It lends while the owner cannot see it, and a battery-powered phone lending unattended is the device most likely to be in a pocket | — | — | — |
| **20.04 devices** | No build (UT-D6) | — | — | — | — |
| **Rabbit R1 on 24.04** [UF02] | UT-1 requester, the only role | No | No | No | A concrete **D26 appliance requester**: a rich-IO device the owner installed asom onto |

#### 2.2 The honest reasons

- **Background execution.** On a phone the shell, not the app, decides. Only the focused app, and the main- and side-stage apps on tablets, keep running (UF15). Everything else in the app's cgroup is SIGSTOPped 1500 ms after the shell asks it to suspend (UF19, UF20). The only exemptions:
  - apps that are not touch apps (X11/Libertine);
  - a user-edited gsettings list (UF16);
  - clients of the location service (UF17).

  **asom uses none of them.** The first means shipping an X11 app; the second means the user editing system settings; the third means requesting location permission to stay awake, which is a dishonest use of a permission.
- **System sleep.** Independently of the shell, repowerd lets the system suspend about 4 s after the display turns off (UF21). A confined app can hold only the display on (UF13), not the system. The display is the watched object, which is why PF is the ceiling.
- **Networking.** Nothing gates LAN or overlay access for a confined app with `networking` except the coarse AppArmor inet rules (UF12). There is no Android-17 or iOS style local-network prompt. So the peer plane is *possible*; the lifecycle is what limits it.
- **Power and thermal.**
  - Halium phones are CPU-only for asom (§6) and weaker than the design's flagship-Android baseline (§6.3).
  - Under confinement the app can read neither thermal zones nor battery state (UF11). The v2 governors therefore have **no thermal or battery input** on Ubuntu Touch, and a UT-2 engine can run only under time caps.
  - That alone rules out PA and the benchmark's heat plans (design B14 `NO_THERMAL_SIGNAL`).
- **Store and distribution.**
  - Everything asom needs for UT-1 (`networking`, `keep-display-on`, `camera` for QR, `content_exchange_source` for exports) is a *common* policy group, so it passes automated review (UF14).
  - Anything that would enable PA needs the reserved `unconfined` template and manual review (UF22). The design rejects that for reasons that have nothing to do with the store.
- **What a UT-1 requester does NOT guarantee.**
  - It does not keep an answer streaming if the owner switches apps or the screen locks. The stream ends as `interrupted` (§3.3).
  - It gives no speed-up the lender cannot deliver (design §7.1).
  - It gives no fallback to the cloud: there is no cloud tier on Ubuntu Touch, so when no peer can serve, the request fails with a typed error (§7.3 mapping).

---

### 3. Node hosting

#### 3.1 Process model

```
Lomiri shell ── launches ──▶ click app xyz.mdhv.asom.ut_asom_<ver>   (one systemd scope / cgroup; confined by the ubuntu-sdk 2404.x profile)
                              ├─ QML UI process (qmlscene/qml + plugin Asom.Bridge, C++, Qt 5.15)
                              │     └─ QProcess ── stdin/stdout pipes, "asom-ut-ctl/1" JSON lines ──▶
                              └─ JVM child: $CLICK/lib/asom/rt/bin/java @jvm.options -jar asom-ut-node.jar --profile=ut
                                    requester pipeline · MeshRouter · asom-mesh/1 client (JSSE SSLEngine) · JSONL ledger · manifest verifier
                                    (UT-2: + llama.cpp JNI engine, bench-core, peer listener while the lend screen is frontmost)
```

- **No listener at all in UT-0 and UT-1.** There is no app-facing HTTP API on `127.0.0.1:11435` (no other app could use it, §2.1) and no peer listener. The UI and the node talk over **anonymous pipes** that nothing else on the device can reach.
- The JVM child is in the app's cgroup, so it is **frozen and thawed with the app** (UF20). There is no loophole in which the child keeps running while the UI is frozen.
- The JVM exits on stdin EOF, so a killed UI takes the node down with it.
- The node writes **only** protocol frames to stdout and nothing identifying to stderr (the H3/T17(c) rule): no prompt text, token, key or ledger row. A capture test (UTC05) enforces it.

#### 3.2 Start, stop, boot

| Event | Behaviour |
|---|---|
| Start | Only when the owner opens the app from the launcher. The UI spawns the JVM. The node opens the ledger (fail-closed), loads the registry and identity, and dials **nothing** (quiescence, design §8.6) |
| Stop | Closing the app from the spread: the UI sends `shutdown`, and the node cancels in-flight attempts, writes outcome rows, `force`s and exits within 1 s, after which the UI kills it |
| Boot | **Never.** There is no autostart for confined clicks, and v1 P8's "boot-start default OFF" is moot |
| Update | The OpenStore replaces the click version directory. Data in `~/.local/share/xyz.mdhv.asom.ut/` survives. The JVM's CDS archive in `~/.cache/…` is regenerated when the runtime hash changes |

#### 3.3 Lifecycle FSM of the Ubuntu Touch node

| State | Entered on | The node does | Leaves on |
|---|---|---|---|
| `STARTING` | JVM spawned | open the ledger (a failure → `LEDGER_FAIL`), load keys, send `hello_ack` | ready → `IDLE` |
| `IDLE` | no sessions and no requests | nothing on the network | a local request whose `P ∋ O`, the Peers screen opened, or a user peer operation (quiescence rules 1–3) → `ACTIVE` |
| `ACTIVE` | sessions or attempts exist | runs attempts. The UI holds `keepDisplayOn` **only while an attempt is streaming** | no attempts for 5 min → close sessions → `IDLE`; lifecycle `inactive` or `suspending` → `FREEZING` |
| `FREEZING` | UI forwards Qt application state ≠ Active [UA03], or the app lost focus for 10 s (L-UT1) | sends `CANCEL` for in-flight attempts, closes sessions **without waiting for replies**, writes outcome rows (`terminal = interrupted`) and `force`s; target ≤ 1 s, inside the 1500 ms window [UF19] | done → `FROZEN` (implicit: SIGSTOP) |
| `FROZEN` | SIGSTOP | nothing (the process is stopped) | SIGCONT → `RESUMING` |
| `RESUMING` | lifecycle `active`, **or** the watchdog sees a monotonic gap > 3 s between 1 s ticks (covers UA03 failing) | treats every session as dead: never reuses a socket; writes `SESSION` close rows with `closedBy = suspend` for any not closed cleanly, and outcome rows `interrupted` for attempts still open. Nothing is retried automatically: content already left, and a retry would put the prompt on a second device without the owner asking (design §7.12) | → `IDLE` |
| `LEDGER_FAIL` | an intent or control append fails | refuses every request with `LEDGER_UNAVAILABLE` shown in the UI (FC-1) | the app restarts |

**The lender side of a frozen requester.** A lender streaming to a frozen requester sees no reads. Its per-chunk read timeout ends the attempt and writes its own outcome row (design §4.3: liveness from read timeouts, TCP keepalive and the idle close). A UT requester therefore costs a lender at most one timeout, not a leak.

#### 3.4 What survives sleep and lock (and laws)

| Situation | Node state | Evidence |
|---|---|---|
| App focused, screen on | runs | UF15 |
| Another app focused (staged mode) | frozen after ~1.5 s | UF15, UF19, UF20 |
| Lock screen shown (staged or side-stage mode) | frozen | UF15 (`suspended: greeter.shown`) |
| Lock screen shown (windowed mode) | **keeps running** unless L-UT3 stops it | UF15; UA17 |
| Display off with no `keepDisplayOn` held | whole system suspends ~4 s later | UF21 |
| Display held on by asom during a stream | runs; the display stays on | UA04 |

**Laws (new, UT profile; W-UT vectors in `ubuntu-touch/conformance/utc/`):**
- **L-UT1.** The node initiates no peer connection unless the UI has reported `active` within the last 10 s. After 10 s without `active` it enters `FREEZING`, even if the OS has not frozen it.
- **L-UT2.** `keepDisplayOn` is held only while an attempt is between `INFER_BODY` and its outcome row, and it is released within 1 s of the outcome.
- **L-UT3.** In windowed mode the node treats `inactive` as locked: no new dials, and sessions close after 10 s. This stands in for design T9's keyguard binding, which Ubuntu Touch cannot provide at the key level (§5).
- **L-UT4.** The node never asks the user to edit `lifecycle-exempt-appids`, never requests location permission and never ships an X11 variant. Those are the three lifecycle exemptions of UF15, and using any of them would be dishonest here.

#### 3.5 How the owner sees that it is running (the watched-object rule)

- **The app window is the only surface, and that is sufficient.** asom on Ubuntu Touch can never run unwatched:
  - it runs only while it is focused (or visible, in windowed mode);
  - nothing runs while it is frozen;
  - there is no background state to surface in a notification.
- **Status header** on every screen, driven by the node's `state` messages: `node: idle | active (n sessions) | interrupted`, and the lending state (UT-2) as an explicit `LENDING` banner.
  - Glyph plus label, never colour alone (Invariant 6): violet diamond "this device", cyan triangle "peer · lan" / "peer · overlay".
- **Per answer:** a provenance line **built from the same `RouteRecord` as the ledger row** (Invariant 9): `served by peer:<alias>/<model> · via lan`. Vector UTC03 asserts that the UI projection equals the row fields byte for byte.
- **Ledger tab:** rows as in design §8.4. Export is a Content Hub share of the exact JSON, shown first (Invariant 1's user export, v1 §9).
- **Does NOT guarantee:** the OS spread shows the app as open even when it is frozen, so "the app is in the spread" does not mean the node is running. The status header is authoritative only while the app is on screen.

---

### 4. Networking

#### 4.1 Inbound listener feasibility

- **UT-0 and UT-1: none, by design.** The UT node is outbound-only, like the Android phone in mesh-1 (design §8.1 row 2).
  - The UT-0 self-test performs its TLS handshake between two in-memory `SSLEngine`s, with **no socket** (§10.3).
- **UT-2 (PF lending):**
  - *Technically permitted:* the coarse `network inet stream` rule (UF12) does not restrict `bind`/`listen`.
  - *Not verified on a device* (DV-UT16).
  - It would follow IC-1 exactly:
    - bind only the Wi-Fi interface's private address, on a network the owner confirmed;
    - only while the lend screen is frontmost and SERVING;
    - never a wildcard, loopback or cellular address.
  - The listener discloses its certificate to anyone who can connect (T13). There is probably no host firewall (UF41, UA08).
  - The listener dies with the app on SIGSTOP, so the lender is visibly unavailable (`GOAWAY` sent in `FREEZING`, else the peer's read timeout).

#### 4.2 Local-network and firewall permissions

- **No local-network permission exists** on Ubuntu Touch. `networking` is a common group: one checkbox at install time, automated review (UF14).
- **What asom cannot learn under confinement:**
  - The Wi-Fi network's identity: NetworkManager is explicitly denied (UF12).
  - The "user-confirmed network" rule of IC-1(b) therefore **cannot be keyed by SSID or gateway** on Ubuntu Touch. It matters only for the UT-2 listener, whose confirmation becomes "the owner confirms on the lend screen each time it is opened" (a per-session confirmation, recorded in the ledger).
  - For the UT-1 requester this does not matter. A dial to a squatter on another network fails the pin check before any content moves; the `DIAL` row records `pin-mismatch` (design T12, §8.4).
- **Metered paths (C8):** read from the `connectivity` group [UA06]. If unavailable, any interface other than Wi-Fi or the overlay is treated as metered and not dialled.

#### 4.3 The private overlay (Headscale/Tailscale) on Ubuntu Touch

| Question | Answer |
|---|---|
| Client available? | **No OpenStore client.** Community snap in `--devmode` (needs `sudo`, off-store), official snap stale (UF30). snapd itself ships on 24.04 (UF29) |
| Kernel TUN? | Android R's base config requires `CONFIG_TUN=y` (UF31); per-device kernels are UA05 |
| Log upload | `tailscaled` on Ubuntu Touch is the **Linux** build, so the documented Linux opt-out applies: `--no-logs-no-support` or `TS_NO_LOGS_NO_SUPPORT=true` (UF30). Under Headscale the client still contacts `log.tailscale.com` unless that flag is set [LF10]. Whether the devmode snap exposes the flag is unknown |
| Userspace-networking mode | A requester in userspace mode cannot reach `100.x` peers, because app sockets bypass the tailnet unless proxied. The node detects the absence of a `tailscale*` interface and reports "overlay not usable from apps: use kernel TUN" |
| `peerPath` | From the connected socket's local interface (UA16): `tailscale0` → `overlay`, `wlan0` → `lan` |
| Recommendation (UT-D5) | **LAN-direct by default** on Ubuntu Touch. The overlay only if the owner installs `tailscaled` in kernel-TUN mode with the no-logs flag. asom detects the interface, shows "overlay: installed by you, outside asom", and discloses [F09]/[LF10] in the Peers screen. asom never installs, configures or depends on an overlay itself |

#### 4.4 mDNS/Bonjour

- Not used. The design defers mDNS (D12) and v4 says "no mDNS".
- A confined app *could* send multicast UDP (`inet dgram`, UF12), so absence is a **code law**, not an OS guarantee.
- Vector W-UT-NET1: the node opens no UDP socket other than the resolver's DNS queries made by glibc for catalogue hostnames.

#### 4.5 Egress classes the Ubuntu Touch node can produce

| Class | UT-1 | UT-2 | Notes |
|---|---|---|---|
| `peer` | yes | yes | `DIAL`, `SESSION`, `CONTROL`, attempt and manifest rows (design §8.4) |
| `catalogue` | yes | yes | the 24 h ETag refresh when the app is open (v1 §6); needed to map live-state `held` digests to model ids |
| `download` | no | yes | model files for the local engine, through the node's ledgered downloader (never the Qt DownloadManager, which would bypass the ledger) |
| `cloud` | **never** | **never** | no keys on Ubuntu Touch (§5.3) |
| `contribution` | no | no (UT-D7) | the P7 upload is Android-only in the design |

**OS traffic that is not asom's, disclosed in the About screen:**
- the OpenStore's catalogue and update checks, with device information (UF23);
- snapd refreshes, if snaps are used;
- UBports system-image OTA checks;
- any overlay client.

**Invariant 1 note:** Ubuntu Touch offers push notifications (`push-notification-client`) through a UBports-operated push server. **asom never uses them**, because a push would be a third-party egress with no class.

#### 4.6 What networking does NOT guarantee on Ubuntu Touch

- **No OS enforcement of the listener's scope.** The coarse AppArmor rules would let a modified asom bind anything; IC-1 compliance is asom's code, checked by vectors, not by the OS.
- **No SSID-bound trust.** Network confirmation is per lend session, not per network.
- **No knowledge of or control over overlay behaviour** that the owner configured outside asom.

---

### 5. Key storage tier for the node identity

#### 5.1 Tiers available

| Tier | Mechanism on Ubuntu Touch | `keyStorage` value | Recommended |
|---|---|---|---|
| Hardware (TEE, StrongBox, Secure Enclave, TPM) | **None reachable.** The Android KeyMint/Keymaster HAL exists in Halium's vendor partition but no API or policy group exposes it to clicks (UF13). A TPM does not exist on these phones | — | not available |
| Keyring or secret service | **None for clicks** (no policy group, UF13). Online Accounts (`accounts`) stores provider credentials for account plugins and is not a general key store | — | not available |
| **T0 file** | a PKCS#8 P-256 NIK in `~/.local/share/xyz.mdhv.asom.ut/identity/nik.p8`, mode 0600, written with `force`; the directory is AppArmor-private to the app (UF11) | `file` | **yes (UT-D8(a))** |
| T0p file, passphrase-wrapped | the same file wrapped with a passphrase-derived key (scrypt, then AES-256-GCM), unlocked per app start | `file` | option (UT-D8(b)) |
| Per-export ephemeral key (design §5.3) | generated in memory for one exported manifest (UT-2), then discarded | `ephemeral` | yes, for exports |

#### 5.2 What each tier does NOT guarantee

- **T0 file:**
  - It is readable by anything running as the user outside click confinement: the Terminal app, `adb shell`, SSH, unconfined OpenStore apps, a Libertine container [UA07], and anyone with root.
  - It does **not** prove the key exists on one device only (the T10 clone signal catches only concurrent use).
  - It is not bound to the lock screen: design T9's `setUnlockedDeviceRequired` has no Ubuntu Touch analogue. L-UT1/L-UT3 stand in by *behaviour*, not by key property.
  - At rest it is encrypted only if the owner enabled fscrypt user-data encryption where the device supports it (UF34). Otherwise a stolen, powered-off phone yields the key to anyone who reads the flash.
- **T0p passphrase-wrapped:** it adds protection at rest only while the node is not running. Once unlocked, the key is in JVM memory, readable by root or by a debugger.
- **Ephemeral per-export:** the same as design §5.4. It proves nothing about origin without an out-of-band fingerprint comparison.
- **Stated plainly:** an Ubuntu Touch node's pin proves "the key the owner paired", never "this phone's hardware". The peer's Peers screen shows the UT node as `keyStorage: file (self-reported)`.

#### 5.3 BYOK keys and backup

- **No provider keys on Ubuntu Touch.** Invariant 4 names the Android Keystore, and IC-8 part B (key wrapping per OS, D14) is not drafted for a platform with only a file tier. **Recommendation: do not propose it for Ubuntu Touch.** A file-wrapped BYOK vault would be the weakest vault in the product, on the device most likely to be lost.
- **Backup exclusion (C7):** Ubuntu Touch has no OS cloud backup. The owner's own copying (MTP, `adb pull`, backup apps) is invisible to asom. The About screen says that copying `~/.local/share/xyz.mdhv.asom.ut/` to another device clones the node identity, and that the right move is re-pairing, not restoring (the migrated-row rule of C7).

---

### 6. Inference backend(s)

#### 6.1 Availability

| Backend | On Ubuntu Touch (Halium, aarch64) | Verdict |
|---|---|---|
| **llama.cpp CPU (NEON / dotprod)** | Runs confined: UF25's precedent runs `llama-cli` from a click. A JNI shared library inside the click dir is `mrklix`-mappable (UF11) | **The only backend.** UT-2 only |
| llama.cpp Vulkan | Vendor Vulkan drivers are bionic-linked Android blobs. No evidence was found of Vulkan through libhybris for clicks; searches found none | No (research only) |
| llama.cpp OpenCL (Adreno) / Hexagon / MediaTek APU | Vendor SDKs are Android-only; no path through libhybris is documented | No |
| MLX, Core ML, DirectML, CUDA, Metal | Not on this platform | No |
| ONNX Runtime CPU | Exists for aarch64 Linux, but it would break the comparability key `(backend, commit, buildFlags)` and the shared engine seam | Rejected |

#### 6.2 mesh-1 versus later

- **UT-0 / UT-1 (mesh-1): no engine.** `NoopEngine` semantics, and SELF is never a candidate. The UT node's `MeshRouter.plan` is peers only. RL1's oracle "no engine and no surviving peer ⇒ exactly `Router.plan`" yields, on Ubuntu Touch, the v1 error for a key-less cloud universe (`NO_PROVIDER_KEY`), which the UI renders as "no paired device can serve this model now". The mapping is in §7.3.
- **UT-2: llama.cpp CPU via JNI in the node's JVM** (in-process, v2's non-negotiable).
  - The JNI source is the one Android v2 P1 and `desktop/native` use.
  - The build is `linux-aarch64` with `-DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON`, the flags llama.cpp's own release uses for Linux arm64 [LF34].
  - It is the same library the Linux node's aarch64 build and the D26 appliance profile need, so Ubuntu Touch adds no native variant of its own.
  - Governors run **without thermal or battery input** (UF11):
    - a wall-clock cap per request;
    - a duty-cycle limit (at most 60 s of continuous decode, then a 30 s pause);
    - "unknown" bands in live state.

    These are PROVISIONAL constants, NEEDS-DEVICE-VALIDATION.

#### 6.3 Hardware reality (every number below is an ESTIMATE, UA09)

Decode ≈ η × peak bandwidth ÷ file bytes, with η = 0.4–0.6.

| Device (verified spec) | Peak bandwidth (assumed) | Llama 3.2 1B Q8_0 (~1.3 GB) | 3B Q4_K_M (~2.0 GB) | 8B Q4_K_M (5.03 GB) | 8B fits? |
|---|---|---|---|---|---|
| Volla Tablet, Helio G99, 12 GB [UF33] | ~17 GB/s | ~5–8 tok/s | ~3.4–5 | ~1.4–2 | yes (12 GB) |
| Fairphone 5, QCM6490, 6/8 GB [UF32] | ~25–51 GB/s | ~8–23 | ~5–15 | ~2–6 | 8 GB model only, marginal next to the OS and the JVM |
| Design baseline flagship Android [A12a] | — | — | — | 5 | — |

**Consequence.** An Ubuntu Touch device is a borrower first. Even at UT-2 it would lend only small models to other small devices, and its lending value is low. That is why UT-2 is optional (UT-D7).

#### 6.4 The benchmark-baseline consequence (directive D-C)

- **MLPerf does not cover Ubuntu Touch.**
  - MLPerf Mobile targets Android, iOS and Windows [F43].
  - MLPerf Client's Linux CLI targets Ubuntu 24.04 desktops [LF37], not Halium phones.
  - Neither runs natively on Ubuntu Touch.
- **Per D-C, coverage is not a differentiator, so this gap does not justify a benchmark product.**
- **What B on Ubuntu Touch may be, at UT-2 only:**
  - The shared `bench-core` (JVM) running the **`quick` plan only.** The `standard` and sustained plans refuse with `NO_THERMAL_SIGNAL` (design B14), because confinement hides thermal zones (UF11).
  - It uses MLPerf Mobile's model set and metric definitions where the licence allows. The wording is "measured with MLPerf Mobile's model set and metric definitions", never "MLPerf-comparable" (D-C, R2-OVERCLAIM-4).
  - Results feed **this node's own** placement (D-iv) and, if PF lending is on, peers' priors.
  - Export is a per-export-key signed file through Content Hub (D-i, D-ii), view-first (IC-6).
- **No standalone Ubuntu Touch benchmark app** (the Android APK and iOS AsomBench have no analogue here).

---

### 7. Runtime and code strategy

#### 7.1 Recommendation

**Kotlin on the JVM: the same pure-JVM code as the Linux node, shipped inside the click as a jlinked Temurin 21 aarch64 runtime, with a thin QML/Lomiri UI and a small C++ QML plugin.**
- It is **conditional on spike S-UT1** (UA01).
- The fallback, if S-UT1 fails, is a Rust requester core (§7.5).
- No KMP (CLAUDE.md, D17). No `android.*` anywhere (it is pure JVM).

**Why the JVM, on a platform with no JVM.**
- **Nothing forbids it.**
  - The click template lets an app execute and mmap anything in its own install directory (UF11).
  - Bundled native executables already run confined in the store (UF25).
  - A jlinked aarch64 runtime is 39 MB on disk and 20.5 MB compressed, and needs only glibc 2.17 (UF37), against noble's glibc.
- **Zero protocol drift.** The UT node runs the *same* classes as Android and Linux:
  - the `asom-mesh/1` client on JSSE `SSLEngine`, with a fresh `SSLContext` per dial and no PSK (T2);
  - the pinned-chain verifier (T1, W08);
  - the manifest verifier (§5);
  - `MeshRouter` and `ClaimTracker`;
  - the JSONL ledger with `FileChannel.force`;
  - the record-layer byte accounting that R2-OVERCLAIM-6 worries about on Network.framework (on JSSE it is a solved problem).

  A third implementation, in Rust or C++, would have to reproduce all of that and be held to W04–W08, M01–M08 and R01–R06 in a new lane.
- **The code is not new.** It is the D26(b) "headless Linux requester profile" plus a UI. The Linux node's aarch64 build, and the appliance profile, share everything.

#### 7.2 What is shared and what is Ubuntu Touch only

| Piece | Source | UT-specific? | Size (estimate) |
|---|---|---|---|
| Contract, catalogue, v1 router (for RL1 and the error universe) | `core/*` mapped by directory (lab §3.5 mechanism) | no | 0 new lines |
| Mesh protocol, verifier, router extension, ledger model, bench core | `lab/` now; promoted modules later (D23) | no | 0 new lines |
| Host-independent node core: pipeline wiring, config, JSONL sink, requester | **`desktop/node-core`**, which requires splitting `linux.md`'s `desktop/node` into `node-core` + Linux host (a correction for the reviser, §7.7) | no | 0 new lines here |
| UT host layer (`ut-host`): stdio control channel, lifecycle FSM (§3.3), paths, self-test, UI projection | new, Kotlin | **yes** | ~1.5–2.5k lines + tests |
| QML UI (Status, Borrow, Peers, Pair, Ledger, About) against a token seam | new, QML + Lomiri.Components | yes | ~2–3k lines |
| C++ plugin `Asom.Bridge` (`NodeProcess`, `DisplayKeeper`, lifecycle forwarder) | new, Qt 5.15 | yes | ~400–700 lines |
| llama.cpp JNI (UT-2) | the same C++ as Android v2 P1; `desktop/native` builds `linux-aarch64` | build flags only | 0 |

#### 7.3 The UI ↔ node control channel `asom-ut-ctl/1`

- **Transport:** the JVM's stdin/stdout. One JSON object per line, UTF-8, the strict tokenizer of L0.2, integers only (C1), at most 1 MiB per line. It is not reachable by any other process, so it is **not a contract surface**. It is listed in D23 for transparency (§11).
- **UI → node** (closed enum):
  - `hello{v}`;
  - `lifecycle{state: active|inactive|suspending}`;
  - `borrow{rid, model, messages, maxTokens, stream}`;
  - `cancel{rid}`;
  - `peers{open|close}`;
  - `pair{begin: <qr payload>}` / `pair{confirm: <typed SAS>}` / `pair{approve}`;
  - `revoke{peer}`;
  - `ledger{since, limit}`;
  - `export{kind: ledger|manifest}`;
  - `selftest`;
  - `shutdown`.
- **Node → UI:**
  - `hello_ack{nodeTag, keyStorage}`;
  - `state{node, sessions, lending}`;
  - `chunk{rid, delta}`;
  - `end{rid, record}`, where `record` is the UI projection of the terminal `RouteRecord`;
  - `error{rid, code}`, whose codes come only from the v1 §5.6 enum plus `LEDGER_UNAVAILABLE`, `MESH_STREAM_INTERRUPTED` and `INTERRUPTED_BY_SUSPEND` (a UI-only value, never on any wire);
  - `peers{[…]}`;
  - `sas{code}`;
  - `rows{[…]}`;
  - `export_ready{bytes, sha256}`;
  - `selftest{…}`.
- **Caller identity in rows:** `callerPkg = self-ui:xyz.mdhv.asom.ut`. It is the app's own screen: no other app can reach the channel, and no identity is claimed beyond "this app's UI" (a sibling of the desktop's `local-uid:<user>`; it is ruled by the same decision R2-CONFORMANCE-6 asks for, UT-D9).
- **Error mapping for a peers-only universe** (design §7.6):
  - every peer in cooldown or back-off → `ALL_PROVIDERS_COOLING` ("all your paired devices are unavailable");
  - a model held by no paired peer → `MODEL_UNKNOWN`;
  - no paired peer and no keys → `NO_PROVIDER_KEY`, rendered "pair a device to use asom here".
- **Vectors `UTC01–UTC05`:**
  - UTC01: framing and size limits;
  - UTC02: the lifecycle FSM transitions of §3.3, including watchdog-detected resumes;
  - UTC03: the UI projection equals the ledger row (Invariant 9);
  - UTC04: error mapping;
  - UTC05: stdout and stderr hygiene (no prompt text, token, key or row outside frames).

#### 7.4 JVM runtime specifics

- **Runtime:** Temurin 21 (support to at least December 2029 [LF27]), jlinked from pinned aarch64 `jmods` (UF36), modules `java.base,java.logging,jdk.crypto.ec,jdk.unsupported`. `jdk.net` is dropped: there is no Unix socket or `SO_PEERCRED` on Ubuntu Touch. Code is compiled to Java 17 bytecode, as in the root and desktop builds.
- **Native symbol stripping:** use the cross `objcopy` (`--strip-native-debug-symbols=objcopy=/usr/bin/aarch64-linux-gnu-objcopy`), because the host `objcopy` fails on aarch64 (UF37).
- **`jvm.options`** (each flag with its reason):
  - `-XX:-UsePerfData`: no `/tmp/hsperfdata_*`, which the profile forbids;
  - `-XX:-UseContainerSupport`: skips cgroup and mountinfo reads the profile does not grant;
  - `-XX:+UseSerialGC -Xmx128m -Xss512k`: RAM on 4–6 GB phones;
  - `-Djava.io.tmpdir=${TMPDIR}`: the confined TMPDIR (UF11);
  - `-XX:SharedArchiveFile=${XDG_CACHE_HOME}/xyz.mdhv.asom.ut/cds.jsa -XX:+AutoCreateSharedArchive`: JDK 19+, faster second start.

  **No** JSSE system property is set (C12: the same process also fetches the catalogue over HTTPS). TLS 1.3 only, ALPN and the pinned verifier are set per connection with `SSLParameters` and a fresh `SSLContext` per dial (T2).
- **What the JVM choice does NOT guarantee:**
  - that S-UT1 passes (UA01);
  - a startup time: estimated 1–2 s cold and under 1 s with CDS on A76/A78 cores, unmeasured;
  - an RSS figure (UA11);
  - that the OpenStore accepts the size (UA12).

#### 7.5 Rejected runtimes (and the fallback)

| Option | Verdict | Why |
|---|---|---|
| **QML + JavaScript `XMLHttpRequest` requester** | **Rejected** | XHR speaks HTTP(S) only. It has no client certificates, no pinned-chain verifier, no ALPN control and no raw framed TLS, so it **cannot speak `asom-mesh/1`**. Making it work would need a new HTTP peer surface on every lender, which is new contract and weaker trust. No |
| Qt 5.15 C++ with `QSslSocket` | Rejected | A pinned CA list turns verification back into PKIX (hostname, EKU, path rules), not the design's `verifyPeerChain` as the sole trust path. Interruptible handshake verification arrived only in Qt 6.0 (UF39). Record-layer byte counts are not exposed per frame |
| Qt/C++ with raw OpenSSL 3 (`SSL_CTX_set_cert_verify_callback`, memory BIOs) | Rejected unless S-UT1 fails *and* Rust is refused | Feasible, but a third implementation of the frames, verifier, pairing, ledger and router in the least memory-safe option |
| **Rust core** (rustls with a custom `ServerCertVerifier`; `write_tls` gives exact record-layer bytes; Clickable's `rust` builder cross-compiles for aarch64 [UF08]) | **Fallback if S-UT1 fails** | Scope it like iOS M4: **a single home lender, no multi-provider routing** (a router in a second language is a D17 re-escalation trigger). It needs a Rust conformance lane (W04–W08 client role, M01–M03, UTC) and a hand-written strict JSON tokenizer. Cost +8–12 engineer-weeks (§10.6). It compiles and tests locally (Rust 1.94.1 is installed; toolchain notes) |
| KMP | Forbidden | CLAUDE.md, D17 |
| The Android asom APK inside Waydroid (UF40) | Rejected | It would serve only Android apps inside the Waydroid container. It binds loopback *inside* the container, so Ubuntu Touch apps cannot reach it without breaking Invariant 2, and it is frozen with Waydroid's session. That is "Android on another Android", not an Ubuntu Touch node |
| A snap with a daemon (UF29) | Not now | A strict snap daemon still cannot stop repowerd's suspend (the stage-1 interfaces include only `screen-inhibit-control`); snap confinement on Halium kernels is unverified; snaps auto-refresh from the Snap Store; install is terminal-only. Revisit if UBports ships a sanctioned background-service interface |
| Unconfined click + systemd user service + root wakelock (UF26, UF27) | Rejected | PA by stealth: `sudo`, manual review, invisible lending (§2.1) |

#### 7.6 Libertine as an escape hatch (verified constraints)

- **What it can do.** A noble Libertine container (chroot, `~/.cache/libertine-container/`, UF28) can host the **unmodified Linux JVM node and CLI** from the `desktop/` tarball, with `openjdk-21-jre-headless` from the Ubuntu archive [UA14].
- **What it cannot do:**
  - it runs only while its terminal window is up (the docs: "Applications will not run in the background in Libertine");
  - the system still suspends after display-off (UF21);
  - it runs outside click confinement [UA14];
  - it is not distributable.
- **Recommended use:** the **owner's fastest device spike**, before any click exists:
  - run `asom-node --self-test` and a JSSE handshake to a desktop node on the real phone;
  - later, `llama-bench` on the CPU (UT-2 go or no-go).
- **Never a product path.**

#### 7.7 Corrections for the reviser (cross-section)

1. **`desktop/node` should split into `desktop/node-core` (host-independent) and `desktop/node` (the Linux host layer: D-Bus, systemd, sysfs probes).**
   - Ubuntu Touch and the D26 appliance profile reuse `node-core`.
   - Without the split, the UT jar would carry dead Linux host code, or the requester would be written twice.
2. **Design §3.1 gains an Ubuntu Touch column:**
   - R: UT-1, foreground, own UI only;
   - PA: never;
   - PF: UT-2;
   - B/S: quick-only / yes;
   - service host: none (app);
   - sleep: SIGSTOP when unfocused; system suspend 4 s after display-off;
   - network permission: none;
   - peer transport: JSSE;
   - engine: llama.cpp CPU (UT-2);
   - node key tier: T0 file;
   - BYOK: none;
   - packaging: click / OpenStore;
   - code: shared JVM + QML shell.
3. **IC-7 part A (D14)** must name "QML with Lomiri.Components against the token seam", not only SwiftUI, before UT-1's UI ships (Invariant 7).
4. **R2-OVERCLAIM-9's PF exception to LP-1** is needed for the UT-2 lend screen exactly as for Android and iPad.
5. **R2-OVERCLAIM-1:** the UT requester holds no tokenizer for models it cannot run. The byte-based, end-to-end observation R2-OVERCLAIM-1 proposes is not optional for it.

---

### 8. Packaging and distribution

#### 8.1 Package format and contents

- **One arm64 click:** `xyz.mdhv.asom.ut_<version>_arm64.click`.
  - The name is a new artefact id under the anchor, not a rename (the AF-1 pattern; D23).
  - App name `asom`, so `APP_ID = xyz.mdhv.asom.ut_asom_<version>`.
  - No armhf build: few 24.04 devices, and Temurin arm32 is not worth a lane.
  - No amd64 build except for the CI/desktop test lane.
- **Framework:** `ubuntu-touch-24.04-1.x` (policy `2404.1`), so one click covers 24.04-1.x and 24.04-2.x [UA02]. Clickable defaults to 20.04 (UF08), so `clickable.yaml` sets it explicitly.
- **AppArmor manifest (UT-1):**

  ```json
  { "policy_groups": ["networking", "keep-display-on", "camera", "content_exchange_source"],
    "policy_version": 2404.1, "template": "ubuntu-sdk" }
  ```

  - Every group is *common* (UF14), so review is automated.
  - `camera` is dropped if UT-D4 chooses paste-only.
  - UT-2 adds nothing. The listener needs no group, and model import uses `content_exchange`.
- **Install tree:**

  ```
  $CLICK/manifest.json  asom.apparmor  asom.desktop  assets/asom.svg
  $CLICK/qml/…                                   # UI
  $CLICK/lib/aarch64-linux-gnu/Asom/Bridge/…     # C++ plugin + qmldir
  $CLICK/lib/asom/rt/…                           # jlinked Temurin 21 aarch64 (39 MB measured, UF37)
  $CLICK/lib/asom/asom-ut-node.jar               # node (~4–6 MB, estimate)
  $CLICK/lib/asom/jvm.options  rt.sha256  jar.sha256
  $CLICK/lib/asom/native/linux-aarch64/…         # UT-2 only: libasom_llama_jni.so + ggml variants, sha256-listed (C13)
  ```

- **Loader rule (C13):** the UI checks `rt.sha256` and `jar.sha256` before spawning the JVM, and the node checks native libraries before `System.load`.
  - The click directory is not writable by the app (the `mrklix` rule has no `w`), which is the root-owned property C13 wants.
  - The rule is broken by root, or by anyone who can replace the click.
- **Size (estimate):** about 25–30 MB compressed and about 50 MB installed for UT-1; UT-2 adds 5–15 MB for native libraries. Models go to `~/.local/share/xyz.mdhv.asom.ut/models/`, never into the click.

#### 8.2 Signing and notarisation: the honest state

- **There is no notarisation or code signing that the device checks, as far as verified.** The OpenStore client installs with no hash or signature check and falls back to `pkcon install-local --allow-untrusted` (UF24); whether the D-Bus path enforces `debsig` is UA13.
- **What "authentic asom" means here:**
  - (a) HTTPS to open-store.io, with the account that uploaded it;
  - (b) the release's SHA-256, published beside the GitHub Release;
  - (c) a GitHub artifact attestation for the click, with its limits [LF38].

  The About screen shows the installed click's own SHA-256, so the owner can compare it out of band.
- **Does NOT guarantee:** that the OpenStore, or anyone who compromises it or the owner's account, serves the same bytes to every user; that a sideloaded click is genuine unless its hash was compared.

#### 8.3 Store rules and sideloading

- **OpenStore:**
  - automated review for common groups (UF14);
  - admins may pull an app "without warning" (UF22);
  - the store tracks per-revision download counts but not users (UF23);
  - channel: noble (Clickable publishes Noble apps to "the correct channel" since 8.4.0, UF08).
- **Sideload:** the GitHub Release click, installed with `clickable install` over adb, or on the device with `pkcon install-local --allow-untrusted <file>` from Terminal or SSH.
- **Open source:** asom is Apache-2.0, which also satisfies the manual-review rule (UF22), should a reserved group ever be needed (none is planned).

#### 8.4 How updates reach the device

- **Through the OpenStore app's update list**, when the owner taps it. The OpenStore app checks revisions with the store; that traffic is the OS store's, not asom's.
- **asom never checks for updates itself.** An update check is not a permitted egress class (Invariant 3).
- **Sideload users update by hand.**
- **On an update:** the version directory changes; data and identity survive; the CDS archive is rebuilt; the ledger schema migrates append-only.
- **OS updates** (UBports OTA) can change the framework, the AppArmor policy or Qt. The CI canary on `26.04-1.x` (§9) is the early warning.

---

### 9. What GitHub Actions hosted runners can honestly verify

All jobs live in a new workflow `.github/workflows/ubuntu-touch.yml`; `ci.yml` is untouched (AD-3). Images are pinned by digest in the real file.

| Job | Runner / container | What it proves | What it cannot prove |
|---|---|---|---|
| `ut-jvm` | `ubuntu-latest`, `setup-java` 17 and 21 | the UT host layer and UTC01–UTC05 vectors; the node's conformance families on JDK 17 and 21; stdout/stderr hygiene | anything about the device |
| `ut-runtime` | `ubuntu-latest`, JDK 21 | a pinned (sha256) Temurin aarch64 `jmods` download; cross-jlink; module list equals `runtime/jlink-modules.txt`; `objdump -T` max `GLIBC_2.17` ≤ noble's 2.39 (UF37 reproduced) | that it runs on a Halium kernel |
| `ut-click` | `ubuntu-latest`, container `clickable/ci-ut24.04-1.x-arm64:8.10.0` (and `-2.x` in the matrix) | the **arm64 click builds** (cross-compiled plugin), `clickable build` runs click-review (UF08) with **zero errors**, the manifest architecture is arm64, and the AppArmor JSON uses only common groups | install on a device; runtime confinement |
| `ut-qml` | `ubuntu-latest`, container `clickable/ci-ut24.04-1.x-amd64:8.10.0` | `clickable build --arch amd64` + `clickable test` (`qmltestrunner` under a virtual screen, UF10) against a **fake node** script speaking `asom-ut-ctl/1` | Lomiri shell behaviour; real lifecycle |
| `ut-arm64-smoke` | `ubuntu-24.04-arm`, `docker run clickable/arm64-ut24.04-1.x-arm64:8.10.0` (arm64 host image, UF09) | the **bundled aarch64 runtime starts** in the real Ubuntu Touch 24.04 userland and the node's `--selftest` passes: TLS 1.3 in-memory handshake, ES256, JCS vectors, JSONL `force`; RSS reported (UA11) | Halium, libhybris, the shell, confinement |
| `ut-apparmor-approx` | `ubuntu-24.04-arm`, host kernel | **CI-APPROX only.** Generates the click profile with `aa-easyprof` from the **pinned** UBports template (commit d426cf5, UF11), loads it, and runs the self-test under `aa-exec`; denials are collected from the kernel log | the device kernel's AppArmor feature set, which differs from the runner's 6.17 kernel. **Never counted as device evidence**, and never a substitute for DV-UT01 |
| `ut-canary-2604` (non-gating) | `ubuntu-latest`, `clickable/ci-ut26.04-1.x-arm64:8.10.0` | early warning that the next series (resolute, UF07) breaks the build or review | anything shipped |
| multi-node suite (M1's) | `ubuntu-latest` netns | a **UT-profile node process** (the same jar, `--profile=ut`, x86 JVM) as a requester against desktop lenders, with M1's fault list | the UT OS layer (lifecycle, suspend) |

**NEEDS-DEVICE-VALIDATION (owner, Fairphone 4/5 or Volla on 24.04-2.x):**

| ID | Check | Exact method |
|---|---|---|
| DV-UT01 | **S-UT1:** JVM self-test under real confinement | Install the click, tap Self-test, then `sudo dmesg \| grep 'apparmor="DENIED"' \| grep xyz.mdhv.asom.ut` over SSH: expect none that break the test |
| DV-UT02 | 1.x click on a 2.x device | install and launch; `click list` |
| DV-UT03 | Lifecycle freeze of the JVM child | Switch apps, then `ps -o pid,stat,cmd -C java` shows `T` within ~2 s |
| DV-UT04 | State delivered before SIGSTOP (UA03) | node log timestamps against DV-UT03 |
| DV-UT05 | `keepDisplayOn` prevents suspend during a 3-minute stream (UA04) | stream from a desktop lender with the screen untouched |
| DV-UT06 | Windowed mode and lock screen (UA17, L-UT3) | tablet with keyboard and touchpad; lock mid-session |
| DV-UT07 | Quiescence | 30 min, app open on the Status screen, overlay **off**, capture at the gateway: zero SYNs to peer addresses. Positive control: open Peers → a SYN within 5 s (R2-OVERCLAIM-5: the overlay path needs on-device capture, else it stays open) |
| DV-UT08 | ≥ 20-request script to the Deck/Dell; both ledgers exported and joined; `ss -ltnup` on the phone shows no asom socket | SSH |
| DV-UT09 | Overlay interface and `peerPath` | `ip -br addr`, row values |
| DV-UT10 | Metered detection | cellular data on, Wi-Fi off: no dial |
| DV-UT11 | Key-file readability from Terminal (the stated limit) | Terminal: `stat` the file |
| DV-UT12 | Firewall state | `sudo nft list ruleset` |
| DV-UT13 | Install-path signature enforcement | install a click with a corrupted debsig, if any |
| DV-UT14 | Libertine spike | `asom-node --self-test` in the container |
| DV-UT15 | No peer label on loopback (UA18) | test program |
| DV-UT16 | UT-2 only: listener bound to the Wi-Fi address while frontmost; W08 server role from a hostile node on the LAN | as M1 W08 |

---

### 10. Implementation scaffold plan

#### 10.1 When any of this may start (read first)

- **UT-0** is a scaffold under **AD-3** (`ubuntu-touch/` placement) and **AD-4** (the lab exception). It **ships nothing**: no OpenStore upload and no release. It consumes only lab code authorised by D1a (L0.1 W00–W03/W01b; L0.2 M01–M03). This is an exception of the same kind as D1a, and it is recorded for ratification.
- **UT-1** is mesh-1 content for a new platform. Its entry criteria:
  - M1 green (the shared JVM requester proven on Android and desktop);
  - D2 and D3 ruled;
  - D14 part A text covering QML (§7.7 item 3);
  - the D23 rows of §11 (artefact id, bundled runtime, zxing-core, `self-ui` caller label);
  - UT-D1 to UT-D6;
  - **the v4 design session held and recorded** (R2-CONFORMANCE-3).

  It never blocks M1, and directive D-D (versions in order; nothing before v1 validation) binds it.
- **UT-2** is unscheduled (UT-D7).

#### 10.2 File tree

```
ubuntu-touch/                                   # AD-3; the root Gradle build never references it
  README.md                                     # what this is (UT-0: ships nothing), tiers UT-0/1/2, honest NOs, DV list pointer
  clickable.yaml                                # framework: ubuntu-touch-24.04-1.x (explicit; Clickable defaults to 20.04, UF08);
                                                #   builder: cmake; prebuild: stage jar + runtime from CI artifacts (fails if absent);
                                                #   install_data: stage/rt -> lib/asom/rt, stage/asom-ut-node.jar -> lib/asom;
                                                #   test: qmltestrunner -input tests/qml
  CMakeLists.txt                                # builds plugin/, installs qml/, manifest, apparmor, desktop file, assets, lib/asom/*
  manifest.json.in                              # name xyz.mdhv.asom.ut; architecture @CLICK_ARCH@; framework @CLICK_FRAMEWORK@;
                                                #   hooks.asom {apparmor, desktop}; maintainer; version
  asom.apparmor.in                              # policy_groups networking, keep-display-on, camera, content_exchange_source;
                                                #   policy_version @APPARMOR_POLICY@; template ubuntu-sdk (never unconfined)
  asom.desktop.in                               # Exec=qml qml/Main.qml (or the plugin launcher); Icon; X-Lomiri-Touch=true
  assets/asom.svg
  qml/
    Main.qml                                    # page stack; status header on every page (§3.5)
    StatusPage.qml                              # node state, self-test button (UT-0), about + installed click sha256
    BorrowPage.qml                              # (UT-1) chat screen = the only local caller; provenance line per answer
    PeersPage.qml                               # (UT-1) paired peers, self-reported names "(self-reported)", keyStorage labels
    PairPage.qml                                # (UT-1) QR scan (camera) or paste; typed SAS (T7); approve
    LedgerPage.qml                              # (UT-1) rows; export via Content Hub, exact JSON shown first
    tokens/Tokens.qml                           # token seam: violet #8E7BFF / cyan #35E0FF + glyph + label (Invariant 6/7)
  plugin/
    CMakeLists.txt  qmldir
    plugin.cpp                                  # registers Asom.Bridge 1.0
    nodeprocess.h/.cpp                          # QProcess: verifies rt.sha256/jar.sha256, spawns java @jvm.options, JSON-lines I/O,
                                                #   1 MiB line cap, kills child on app exit; exposes signals to QML
    displaykeeper.h/.cpp                        # com.canonical.Unity.Screen keepDisplayOn/removeDisplayOnRequest (UF13); L-UT2
    lifecycle.h/.cpp                            # forwards Qt::ApplicationState + focus changes as lifecycle{} lines; 1 s heartbeat
  jvm/                                          # separate Gradle build; maps projects by directory; NEVER includeBuild("..")
    settings.gradle.kts                         # maps :core:contract, :core:catalogue, :core:routing, :core:inference-api (../../core/*),
                                                #   lab modules (../../lab/*) and later ../../desktop/node-core (§7.7)
    build.gradle.kts                            # build-dir redirection; aggregate task utTest; fat jar task utNodeJar (jvmTarget 17)
    ut-host/build.gradle.kts
    ut-host/src/main/kotlin/xyz/mdhv/asom/ut/
      Main.kt                                   # --profile=ut | --selftest | --fake-ui; exits on stdin EOF; nothing secret on stderr
      ControlChannel.kt                         # asom-ut-ctl/1 codec over stdin/stdout (strict tokenizer, C1)
      NodeLifecycle.kt                          # §3.3 FSM + watchdog (monotonic gap > 3 s => RESUMING)
      UtPaths.kt                                # XDG paths under the confined dirs (UF11); 0700/0600
      SelfTest.kt                               # UT0.3: runtime facts, path probes (incl. thermal/battery readability), SHA-256,
                                                #   ES256 sign/verify, in-memory SSLEngine TLS 1.3 mutual auth (no socket),
                                                #   lab vectors W00-W03/W01b/M01-M03, 100 JSONL appends with force (timing), VmRSS
      UiProjection.kt                           # RouteRecord -> UI record (UTC03: equals the ledger row fields)
      ErrorMapping.kt                           # peers-only universe -> v1 codes (§7.3)
    ut-host/src/test/kotlin/…                   # one test class per file above
  runtime/
    temurin.lock                                # URL + sha256 of OpenJDK21U-jdk_aarch64_linux_hotspot_<ver>.tar.gz (UF36)
    jlink-modules.txt                           # java.base,java.logging,jdk.crypto.ec,jdk.unsupported (CI fails on drift)
    jlink.sh                                    # verify lock sha256 -> extract jmods -> jlink --strip-debug --no-header-files
                                                #   --no-man-pages --compress=zip-9 --strip-native-debug-symbols=objcopy=aarch64 objcopy
                                                #   -> stage/rt; writes rt.sha256; prints size and max GLIBC symbol
    jvm.options                                 # §7.4 flags
  conformance/utc/
    INDEX.json  VERSION                         # sha256 list (detects incomplete checkouts only)
    UTC01-framing.json  UTC02-lifecycle.json  UTC03-projection.json  UTC04-errors.json  UTC05-hygiene.json
  apparmor-ci/
    policy.lock                                 # apparmor-easyprof-ubuntu commit d426cf56392f84c05f4979ccdc754abf5e8d93a7
    check-groups.py                             # asserts the built apparmor JSON: common groups only, template ubuntu-sdk, never unconfined
    make-profile.sh                             # aa-easyprof --template ubuntu-sdk (from pinned repo) -p networking -p keep-display-on …
    run-approx.sh                               # stage click under /opt/click.ubuntu.com/…; apparmor_parser -r; aa-exec -p … -- java … --selftest;
                                                #   collects DENIED lines; prints "CI-APPROX — NOT DEVICE EVIDENCE"
  tests/
    qml/tst_StatusPage.qml                      # qmltestrunner; uses a fake node (Main.kt --fake-ui) via the plugin
    smoke/arm64-selftest.sh                     # extracts the click (dpkg-deb -x) inside the arm64 UT image; runs --selftest; asserts
  docs/
    UBUNTU_TOUCH.md                             # user-facing: what runs when, what leaves the phone, key-file limits, overlay disclosure
    DEVICE_CHECKLIST_UT.md                      # DV-UT01…DV-UT16 with exact commands and expected results
.github/workflows/ubuntu-touch.yml              # the jobs of §9; ci.yml untouched
```

#### 10.3 Steps and gates (real command → expected result; paste real output into `PROGRESS.md`)

| Step | Scope | Gate command | Expected result |
|---|---|---|---|
| **UT0.1** | JVM build and host tests | `./gradlew -p ubuntu-touch/jvm utTest` with `ANDROID_HOME=""` on JDK 17 **and** 21 | `BUILD SUCCESSFUL`; the test report lists UTC01–UTC05 and the ut-host classes with 0 failures; `git diff --exit-code -- core server settings.gradle.kts build.gradle.kts` is clean afterwards |
| **UT0.2** | runtime image | `ubuntu-touch/runtime/jlink.sh` (JDK 21) | prints `rt: <N> MB` with N ≤ 45; `file stage/rt/bin/java` contains `ARM aarch64`; the script prints `max GLIBC: 2.17` (UF37 measured 2.17); exit 0 |
| **UT0.3** | self-test in the real UT userland (arm64) | on `ubuntu-24.04-arm`: `docker run --rm -v "$PWD":/w clickable/arm64-ut24.04-1.x-arm64@sha256:<pinned> /w/ubuntu-touch/tests/smoke/arm64-selftest.sh` | exactly one stdout line starting `{"selftest":"ok"`, with `"tls":"TLSv1.3"`, `"alpn":"asom-mesh/1"`, vector counts equal to the lab's `labTest` counts for the same families, `"thermalReadable"` and `"batteryReadable"` recorded (expected `false` under confinement, UF11), `"rssKiB"` reported; exit 0. Labelled **LAB / CI — NOT DEVICE EVIDENCE** |
| **UT0.4** | click build and review | job container `clickable/ci-ut24.04-1.x-arm64:8.10.0`: `cd ubuntu-touch && clickable build` | exit 0; `build/aarch64-linux-gnu/app/xyz.mdhv.asom.ut_*_arm64.click` exists; the review printed by `clickable build` reports no errors; `dpkg-deb -f <click> Architecture` prints `arm64`; `python3 apparmor-ci/check-groups.py build/aarch64-linux-gnu/app/install/asom.apparmor` exits 0 (Clickable's default install dir is `${BUILD_DIR}/install`) (only the four common groups, template `ubuntu-sdk`, policy `2404.1`) |
| **UT0.5** | QML smoke | container `clickable/ci-ut24.04-1.x-amd64:8.10.0`: `clickable build --arch amd64 && clickable test` | `qmltestrunner` summary with 0 failures; exit 0 |
| **UT0.6** | **S-UT1 on a device** | owner: `clickable install` (adb), tap Self-test; then SSH `sudo dmesg \| grep 'apparmor="DENIED"'` | a self-test line identical in shape to UT0.3; no denial that breaks it (others recorded). **NEEDS-DEVICE-VALIDATION.** If it fails: `BLOCKED(S-UT1: <denial or crash>)`, and UT-D2 moves to the Rust fallback |
| UT0.7 (CI-APPROX) | confinement approximation | `ubuntu-touch/apparmor-ci/run-approx.sh` on `ubuntu-24.04-arm` | prints `CI-APPROX — NOT DEVICE EVIDENCE`, the self-test line, and the list of denials; fails if the profile fails to load or the self-test fails (UA10: fail, never skip) |
| **UT1.1** | requester (after M1) | `./gradlew -p ubuntu-touch/jvm utTest` + the promoted conformance families on the UT jar in the arm64 lane | W04–W08 (client role), M01–M03, R01–R06, W01b, UTC01–UTC05: 0 failures on JDK 17, JDK 21 and the jlinked arm64 runtime |
| UT1.2 | multi-node CI | M1 netns suite with one `--profile=ut` requester | the M1 fault list passes with the UT profile; row counts asserted on every node (L-L15, L-L16) |
| UT1.3 | device | DV-UT02–DV-UT11, DV-UT15 | as §9; NEEDS-DEVICE-VALIDATION until the owner confirms |
| UT2.x (if UT-D7) | engine, B (`quick`), PF | tiny-GGUF generation in the arm64 lane; exported manifest verifies in the JVM verifier and `xcheck.py`; DV-UT16; LP-1 PF vectors (R2-OVERCLAIM-9) | as the design's M2 gates, adapted |

#### 10.4 What the scaffold deliberately does not contain

- **No network code that runs outside tests.** The UT-0 self-test uses in-memory `SSLEngine`s only.
- **No listener, no keys, no model download.**
- **No `unconfined` template.**
- **No push notifications and no DownloadManager.**
- **No Qt 6.** Qt 6 for apps is not a framework default yet (UF05, UF08).

#### 10.5 Effort (engineer-weeks of focused work; estimates, not measurements)

| Item | Estimate | Assumptions |
|---|---|---|
| UT-0 (UT0.1–UT0.7) | **2–3.5** | lab L0.1/L0.2 exist; one engineer who knows Kotlin and basic Qt/QML; the owner runs UT0.6 on one device within a week |
| UT-1 (requester, after M1) | **5–8** | M1's JVM requester, pairing and ledger exist and are promoted; `desktop/node-core` split done (§7.7); includes QML UI (~2–3k lines), plugin, UTC vectors, device validation, first OpenStore submission; excludes store review waiting time |
| UT-2 (optional) | **6–10** | the `linux-aarch64` llama.cpp JNI build already exists (desktop/D26); includes governors without a thermal input, `quick` bench, export, lend screen, listener + W08 server role on device |
| Rust fallback (only if S-UT1 fails) | **+8–12** on UT-1 | single home lender, no router; Rust conformance lane; strict JSON/JCS, DER and verifier written from the prose |
| **Ubuntu Touch total** | **7–11.5** (UT-0 + UT-1); **13–21.5** with UT-2 | on top of the design's 72–122 mesh total, which does not include Ubuntu Touch |

---

### 11. Owner decisions specific to this platform, and risks

#### 11.1 Decisions

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **UT-D1** | What is Ubuntu Touch in the plan? | (a) UT-0 now; UT-1 requester after M1, never blocking it; UT-2 unscheduled. (b) UT-0 now, UT-1 after M2. (c) No Ubuntu Touch work until a 26.04 base ships | **(a).** D-E makes it in scope; the requester reuses mesh-1's code almost entirely |
| **UT-D2** | Runtime | (a) JVM-in-click (shared code), conditional on S-UT1. (b) Rust core, single home lender. (c) Qt/C++ + OpenSSL. (d) QML + XHR | **(a); (b) only if S-UT1 fails; reject (c) and (d)** (§7.5) |
| **UT-D3** | Distribution | (a) OpenStore, confined, common groups only, plus a GitHub Release click with published SHA-256. (b) Sideload only. (c) Unconfined with manual review | **(a).** Reject (c): it buys only the always-on lending this section rejects |
| **UT-D4** | Pairing input on the phone | (a) Camera QR scan (group `camera`; zxing-core in the JVM, already CD-D for Android) with paste fallback. (b) Paste-only (no camera group) | **(a)**, if UA15 holds in the UT-1 prototype; otherwise (b) |
| **UT-D5** | Underlay on Ubuntu Touch | (a) LAN-direct by default; overlay only if the owner installs kernel-TUN `tailscaled` with the no-logs flag, detected and disclosed. (b) Require an overlay. (c) LAN only, overlay ignored | **(a)** (no store client exists, UF30) |
| **UT-D6** | Framework and OS series | (a) One arm64 click on `ubuntu-touch-24.04-1.x`, verified on 2.x (UA02); 26.04 as a CI canary; no 20.04. (b) Build 1.x and 2.x separately. (c) Include 20.04 | **(a)** (20.04 is winding down, UF06) |
| **UT-D7** | UT-2 (local engine, `quick` bench, PF lend screen) | (a) Unscheduled; revisit after M2 with owner-device numbers from the Libertine spike. (b) Schedule with M2. (c) Never | **(a).** CPU-only phones rarely beat the lenders they would serve (§6.3) |
| **UT-D8** | Node key at rest | (a) T0 file; recommend enabling fscrypt encryption where the device supports it; the limits of §5.2 shown in the UI. (b) Passphrase-wrapped per app start | **(a)**, with (b) as a setting |
| **UT-D9** | Invariant and registry items this platform adds (SIGN-OFF, via D14, D25 and D23) | (a) IC-7 part A names QML/Lomiri; the `self-ui:xyz.mdhv.asom.ut` caller label is ruled with the desktop owner-CLI label (R2-CONFORMANCE-6); D23 rows: artefact `xyz.mdhv.asom.ut`, bundled Temurin runtime, zxing-core reuse, the internal `asom-ut-ctl/1` channel (listed, not a contract surface). (b) Rule them separately when UT-1 enters | **(a)**, so nothing ships on an unstated ruling |

#### 11.2 Risks

| Risk | Severity | Mitigation | Residual (stated) |
|---|---|---|---|
| S-UT1 fails: the JVM does not run under click confinement on Halium (UA01) | **high** | Run UT0.6 and the Libertine spike first; the CI approximation (UT0.7) surfaces denials early; the Rust fallback is scoped (§7.5) | +8–12 weeks and a third implementation if it fails |
| The lifecycle kills streams mid-answer (app switch, lock, display-off) | medium | `keepDisplayOn` during streams (L-UT2); `FREEZING` drain; `interrupted` rows; a UI warning before switching away | Answers are lost when the owner leaves the app; by design |
| UA03 false: no state change before SIGSTOP | medium | Watchdog-based `RESUMING`; lenders time out | Lender-side rows show a timeout rather than a clean `CANCEL` |
| Key file readable by Terminal, adb, unconfined apps or root; no hardware tier | **high** (inherent) | T0 label, `keyStorage: file` shown to peers; fscrypt recommended; revocation on every other node; clone signal (T10) | A thief with an unlocked or unencrypted phone can impersonate the node until revoked |
| Platform churn: 24.04-2.x → 26.04-1.x, Qt 6 migration, AppArmor policy 2604.1 | medium | Explicit framework pin; 26.04 canary job; the click-review results in CI | A future OS update can break the app before a fix ships |
| OpenStore pulls the app or rejects its size (UF22, UA12) | medium-low | GitHub Release sideload path with published SHA-256 | Sideload users update by hand |
| No on-device signature check (UF24) | medium | Published hashes; artifact attestations; About-screen self-hash | Authenticity rests on HTTPS and the store account unless the owner compares hashes |
| CI AppArmor approximation mistaken for device evidence | medium | Every output line labelled `CI-APPROX — NOT DEVICE EVIDENCE`; DV-UT01 is the gate | — |
| Overlay unusable on Ubuntu Touch (no store client, devmode snap) | medium | LAN-direct default (UT-D5); overlay detection and disclosure | Away from home, a UT requester has no peers unless the owner installs an overlay |
| Weak CPU-only hardware makes UT-2 pointless | low | UT-2 unscheduled; the peer usability gate keeps slow Ubuntu Touch lenders out of `auto` | — |
| Small user base; owner device availability | medium | UT never blocks M1; one validation device is enough | Validation depends on the owner's hardware |
| Supply chain: Temurin runtime, Clickable images, click-review in the build path | medium | Pin Temurin by sha256 and images by digest; build the jar outside the Clickable image; record image digests in `PROGRESS.md` | A compromised pinned image at pin time is not detected |
| Presence inference if UT-2 lends | low | LP-1 PF exception with W07 vectors (R2-OVERCLAIM-9); lending only with the lend screen frontmost | A paired requester can tell when the owner leaves the lend screen; by design |


---

### 12. Revision 4 amendments (normative; 2026-10-07)

**Status.** Design revision 4 (`../REVISION_4.md`) folds into this section the readings of the Ubuntu Touch track (`ubuntu-touch/ERRATA.md`; ids below are that file's). **Where this section and §1–§11 differ, this section wins**; the design and `PLATFORM_PLAN.md` still win. UT-0 ships no identity, no ledger rows in normal operation and no listener. Device items stay NEEDS-DEVICE-VALIDATION.

**R4-UT-01 (§7.3 channel; ERR-UT-CTL-1, ERR-UT-CTL-2, ERR-UT-CTL-3, ERR-UT-ERR-1).** Every frame is one JSON object whose first member is `t`; decoding is strict, closed (unknown member refused) and bounded; the 1 MiB line cap is enforced while reading. A malformed, oversize or unknown frame or a second `hello` ends the session with one fixed stderr line `asom-ut: protocol violation <REASON>` and exit 65, echoing nothing. `pair`, `revoke` and `export` in UT-0 answer `error{rid:null, code:UNSUPPORTED_BY_DRIVER}`; `hello_ack` carries `nodeTag:"none"`, `keyStorage:"unknown"`. Error precedence follows the v1 router (`local-only` → `LOCAL_ENGINE_ABSENT`; unknown concrete model → `MODEL_UNKNOWN` when a catalogue exists; no paired peer → `NO_PROVIDER_KEY`; …).

**R4-UT-02 (§3.3, §3.4 FSM and laws; ERR-UT-FSM-1, -2, -4, -5, ERR-FX-UT-3).** FREEZING and RESUMING return their work as effects; wire mapping STARTING/IDLE → `idle`, FREEZING/FROZEN/RESUMING/LEDGER_FAIL → `interrupted`. Boundaries: the UI is active while its last `active` report is at most 10,000 ms old; the watchdog fires on a gap strictly over 3,000 ms; idle close at 300,000 ms. `inactive` is treated as locked (no new dial, freeze at once), which subsumes L-UT3. Handlers never hold the session lock for long (the self-test runs on its own thread), because a long lock hold looks like a suspend. L-UT2's display hold: the UI holds the display from `borrow` to the terminal frame (a superset of the law's window); whether the channel gains a frame for the exact window is owner item **D39**.

**R4-UT-03 (§3.5 projection, ledger and status; ERR-UT-PROJ-1, ERR-FX-UT-2, ERR-FX-UT-4, R3-CONFORMANCE-1).** Projected members are the row's own (JCS-compared); only `provenance` is derived and sanitises the peer alias. The caller label `self-ui:xyz.mdhv.asom.ut` depends on D25 and appears only in throw-away self-test rows in UT-0. The node's ledger removes a torn tail at open, checks the last row, rolls back a failed append, and moves to LEDGER_FAIL on a corrupt row; queries stream, oldest first, within a 768 KiB budget. The status page says only what is true of the build (UT-0: no identity, nothing to copy).

**R4-UT-04 (§5.1 paths; ERR-UT-PATHS-1).** `~/.local/share/xyz.mdhv.asom.ut/{ledger,identity}` as siblings, cache and config likewise, all 0700; paths outside `HOME`, relative paths, `..`, symlinked application directories and directories that cannot be tightened are refused.

**R4-UT-05 (§7.4 runtime; ERR-UT-JLINK-1, ERR-UT-JLINK-2, ERR-UT-CDS-1, ERR-UT-JAR-1).** jlink ignores `objcopy=PATH`: the cross objcopy goes first in PATH. Modules are `java.base,java.logging,jdk.crypto.ec,jdk.unsupported` (no `jdk.net`: no Unix socket on UT). The CDS flags are removed (they print to stdout in a jlinked runtime); `-Xlog:disable` and `-XX:+DisplayVMOutputToStderr` keep stdout for frames only. The jar excludes the v1 server's libraries (4.2 MB).

**R4-UT-06 (§10.2 click and QML; ERR-UT-CLICK-1…4, ERR-UT-QML-1…3, ERR-UT-NET-1, ERR-FX-UT-1).** Every `Label`, `Text` and `TextEdit` sets `textFormat: Text.PlainText` itself (AutoText would fetch remote images named in peer strings with no ledger row); `AutoText`, `RichText`, `StyledText` and `MarkdownText` are forbidden in `qml/`. Both `X-Ubuntu-Touch=true` and `X-Lomiri-Touch=true` are written. The AppArmor file keeps its placeholder and CMake fills policy `2404.1`. QML CI runs under `xvfb-run` with Mesa software GL. UT-0 does no DNS and opens no socket.

**R4-UT-07 (§3.1 claims and stolen devices; R3-OVERCLAIM-9, R3-OVERCLAIM-12, ERR-UT-CI-1).** The arm64 smoke shows the runtime starts in the Clickable SDK image, not in the device userland. A Ubuntu Touch phone has **no key-level lock binding**: only the behavioural limits L-UT1 and L-UT3 apply; a stolen phone keeps its borrow scope until revoked on each lender; the key at rest is protected only if fscrypt is enabled.

**R4-UT-08 (§10 build; R3-CONFORMANCE-14, ERR-UT-MAP-1, ERR-UT-SELFTEST-1, ERR-UT-SELFTEST-2).** UT-0 maps lab modules authorised by D1a only; from UT-1 the build maps promoted modules (`PLATFORM_PLAN.md` R4-P-05). The UT-0 self-test runs M01–M03 only (no listener) and its TLS check is a JSSE behaviour test with a self-signed certificate, **not** the trust.md verifier.
