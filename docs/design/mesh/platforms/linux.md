# Platform section: Linux desktop/server and Steam Deck (SteamOS)

**Date:** 2026-09-30 · **Grade:** DIRECTION (an input to the roadmap v4 design session and to D-v2; nothing here authorises execution) · **Scope:** the Linux node (a desktop or server such as "the Dell", OS assumed Ubuntu or Fedora) and the Steam Deck on SteamOS 3.x · **Target directories:** `desktop/` (shared JVM node) and `desktop/packaging/linux/`.
**Reads with:** `OWNER_DIRECTIVES_2026-09-30.md` (D-A…D-F, AD-1…AD-6, all treated as decided), `ASOM_MESH_DESIGN.md` r2 (§2 roles, §3 platform matrix, §4 T17, §7.4 LP-1/LP-2, §8.4 ledger, §8.6 quiescence, §9.3 D-v2/M1), `REVIEW_ROUND2.md`.
**Tags:** `[LFnn]` = verified in this session, source and date in §1.1. `[LAnn]` = assumption, §1.2. `[Fnn]`/`[Ann]` = the design brief's own Appendix A/B ids. `SIGN-OFF` = needs the owner. Every performance number is an estimate unless tagged `[LF]`.

**The answer in brief.**
- **The JVM node runs unchanged here.** `:core:*` and `:server` are pure JVM; the Linux node is the same code plus a thin Linux host layer (probes, systemd, logind, a Unix control socket, packaging). No KMP, no second implementation.
- **The Dell (Ubuntu/Fedora) is the best lender in the program:** a dedicated-user systemd *system* service, lending while awake, which can be kept awake by a logind block inhibitor **only if** the package ships a narrow polkit rule (the upstream default denies it to a session-less service [LF13]).
- **The Steam Deck is a lender with sharp limits.** It lends only docked, on AC, with no game running, and only while Steam lets it stay awake. **asom must never hold a sleep *block* inhibitor on SteamOS:** in Game Mode that produces a "fake sleep" (screen off, system running, reported heat) [LF05]. In Game Mode lending is **invisible on the Deck itself**, so it needs an explicit owner opt-in (LD-2).
- **Honest NOs:** no Flatpak (Flathub rejects console software [LF29]; a daemon needs systemd), no AppImage (FUSE for a long-running daemon, no gain), no TPM tier on the Deck (SteamOS blacklists the TPM module [LF03]), no in-app update checks (not a permitted egress class), no GPU performance claims from CI (hosted runners have no GPU [LF32]), no standalone Linux benchmark product (MLPerf Client already covers Ubuntu 24.04 [LF37]).

---

### 1. Verified platform facts (each with a source URL and date) and Assumptions

#### 1.1 Verified facts

All sources were fetched or searched on **2026-09-30** unless another date is given. "Source date" is the date the source itself carries. Confidence reflects the quality of the source for the exact claim, not its importance.

| ID | Fact | Source (source date) | Conf. |
|---|---|---|---|
| LF01 | Steam Deck (current spec page): 6 nm AMD APU; CPU Zen 2 4c/8t, 2.4–3.5 GHz (up to 448 GFLOPS FP32); GPU 8 RDNA 2 CUs, 1.6 GHz (1.6 TFLOPS FP32); 16 GB LPDDR5 at **6400 MT/s, quad 32-bit channels** (i.e. 102.4 GB/s peak, computed). The LCD model's 5500 MT/s (88 GB/s) comes from the earlier session's V21, not from this fetch | https://www.steamdeck.com/en/tech (undated page) | high |
| LF02 | SteamOS 3.8 (2026-06-18): Desktop Mode moved to KDE Plasma 6.4.3 with **Wayland by default**; Linux kernel **6.16**; "Updated Arch system base"; Steam Deck LCD firmware v133 adds "Preliminary support for hibernation". 3.8.15 stable followed on 2026-07-16 | https://www.gamingonlinux.com/2026/06/steamos-3-8-is-out-with-initial-steam-machine-support-desktop-mode-upgrades-new-graphics-drivers/ (2026-06-18); https://www.gamingonlinux.com/2026/07/steamos-3-8-15-brings-a-performance-fix-and-steamos-3-8-23-beta-is-out-too/ (2026-07-16). Valve's own news page returned no body to the fetch | medium-high (secondary) |
| LF03 | SteamOS: pacman-installed packages "will be removed whenever there is an operating system update"; the root filesystem is read-only (`steamos-readonly`); system extensions via `systemd-sysext`; default user `deck` (UID 1000); **the TPM kernel module is disabled by the kernel argument `module_blacklist=tpm`**; Valve issue #993 asking to move that blacklist so user changes survive updates is open with no Valve reply | https://rootpages.lukeshort.cloud/latest/unix_distributions/steamos.html ; https://github.com/ValveSoftware/SteamOS/issues/993 (opened 2023-03-07, open) | medium-high |
| LF04 | SteamOS partition layout: A/B `efi`, `rootfs` (btrfs, read-only) and **`var` (ext4, ~256 MB each)**, plus one shared `/home`; `/var` is synced between slots "under normal operation" | https://github.com/randombk/steamos-teardown/blob/master/docs/partitions.md (community teardown, via search summary) | medium |
| LF05 | **Game Mode "fake sleep":** with a `systemd-inhibit` sleep lock active, entering sleep from Game Mode turns the screen black while the system keeps running (confirmed over SSH); the reporter describes the Deck getting very hot and fan concerns; in Desktop Mode the same lock produces a visible "sleep blocked" prompt instead | https://github.com/ValveSoftware/SteamOS/issues/1615 (opened 2024-08-25, **open**) | medium-high (one reporter; unresolved) |
| LF06 | In Game Mode the **Steam client** owns idle sleep: "When plugged in, sleep after …" offers 1/5/10/15/20 minutes and **Disabled**, default 1 hour, stored in Steam's `config.vdf` (`IdleSuspendACSeconds`). A display-off low-power downloads mode (default on when plugged in) was added in a 2025-11 beta | https://steamcommunity.com/app/1675200/discussions/1/3269059787433575550/ (2022-04-23); https://www.gamingonlinux.com/2025/11/steam-deck-gets-a-new-low-power-screen-off-downloads-mode/ (2025-11-04) | medium (UI may have changed since 2022) |
| LF07 | A `systemd --user` service on the Deck "will start both in Desktop and Steam modes" | https://github.com/xbb/steamdeck-ssh-user (README, undated) | medium-low (one community README) |
| LF08 | Tailscale's official Deck installer puts binaries in `/opt/tailscale`, a system unit in `/etc/systemd/system`, "comes up automatically on boot (no need to enter desktop mode)", and **resets `/etc/systemd/system/tailscaled.service.d/override.conf` every time the script runs** | https://github.com/tailscale-dev/deck-tailscale (README) | high (for what the README says) |
| LF09 | Tailscale clients stream connection events and operational logs to `log.tailscale.com`. **Linux opt-out:** the flag `--no-logs-no-support` or `TS_NO_LOGS_NO_SUPPORT=true` in `/etc/default/tailscaled`; no opt-out documented for Android or iOS (extends [F10]) | https://tailscale.com/kb/1011/log-mesh-traffic | high |
| LF10 | **Under Headscale, clients still contact `log.tailscale.com`** (DNS and HTTPS) even with Headscale's logtail disabled, and stop only when `TS_NO_LOGS_NO_SUPPORT=true` is set on the client (Headscale 0.26.1, Debian 12) | https://github.com/juanfont/headscale/issues/2793 (opened 2025-10-13, open, fix PR #2805 referenced) | medium-high |
| LF11 | logind inhibitor locks: types `shutdown, sleep, idle, handle-power-key, handle-suspend-key, handle-hibernate-key, handle-lid-switch`; modes `block`, `delay` and `block-weak` ("no effect on operations requested by root or by the user owning the inhibitor lock"); block locks "may be overridden if the user possesses the necessary privileges"; `idle` concerns *automatic* idle action, `sleep` concerns suspend requested by unprivileged users; `PrepareForSleep(true)` announces a suspend, and delay-lock holders should finish quickly and release | https://systemd.io/INHIBITOR_LOCKS/ | high |
| LF12 | `logind.conf` defaults: `InhibitDelayMaxSec=5`; `KillUserProcesses` "Defaults to 'yes'" upstream; `UserStopDelaySec=10s`; `IdleAction=ignore`; `HandleLidSwitch=suspend`; `HandleLidSwitchExternalPower` ignored by default; `HandleLidSwitchDocked=ignore` | https://man7.org/linux/man-pages/man5/logind.conf.5.html | high (upstream; distributions may change defaults) |
| LF13 | **Upstream polkit defaults for logind** (systemd `main`, `src/login/org.freedesktop.login1.policy`): `inhibit-block-sleep` = **auth_admin_keep for "any"**, yes for inactive and active sessions; `inhibit-delay-sleep` = yes/yes/yes; `inhibit-block-idle` = yes/yes/yes; `set-self-linger` = yes/yes/yes; `suspend-ignore-inhibit` = auth_admin_keep everywhere | https://raw.githubusercontent.com/systemd/systemd/main/src/login/org.freedesktop.login1.policy (fetched and parsed 2026-09-30) | high (for upstream `main`; shipped distro policy may be older or patched) |
| LF14 | `loginctl enable-linger`: "a user manager is spawned for the user at boot and kept around after logouts. This allows users who are not logged in to run long-running services." | https://man7.org/linux/man-pages/man1/loginctl.1.html | high |
| LF15 | Since Fedora 38, GNOME suspends after 15 minutes of inactivity **even on AC** (Fedora Server excepted); the GDM login screen uses the 15-minute default regardless of user settings | https://discussion.fedoraproject.org/t/gnome-suspends-after-15-minutes-of-user-inactivity-even-on-ac-power/79801 (2023-03-22) [F21] | medium-high |
| LF16 | Firewalls: Fedora Workstation's firewalld zone `FedoraWorkstation` opens TCP/UDP 1025–65535 inbound by design; Ubuntu ships `ufw` installed but **inactive** | https://fedoramagazine.org/how-to-manage-network-services-with-firewall-cmd/ ; https://help.ubuntu.com/community/UFW (secondary summaries) | medium |
| LF17 | hwmon sysfs ABI: temperatures in **millidegree Celsius**, power in **microwatt**, devices under `/sys/class/hwmon/hwmon*` | https://docs.kernel.org/hwmon/sysfs-interface.html | high |
| LF18 | amdgpu: `gpu_busy_percent` reports GPU busy as a percentage (device-wide); hwmon `temp[1-3]_input` (m°C) with labels, `power1_average`/`power1_input` in µW that **include CPU power on APUs**, `power1_cap`, `fan1_input` | https://docs.kernel.org/gpu/amdgpu/thermal.html [F20] | high |
| LF19 | DRM client usage stats: `drm-engine-<key>: <uint> ns` per DRM file (client), exported through `show_fdinfo` | https://docs.kernel.org/gpu/drm-usage-stats.html | high |
| LF20 | PSI: `/proc/pressure/{cpu,memory,io}` with `some`/`full` lines (`avg10`, `avg60`, `avg300`, `total` in µs); per-cgroup `cpu.pressure`, `memory.pressure`, `io.pressure` under cgroup2 | https://docs.kernel.org/accounting/psi.html | high |
| LF21 | Kernel keyrings hold keys in **kernel memory**; the `logon` key type is never readable from user space; the persistent keyring is per-UID and expires on a configurable timer | https://man7.org/linux/man-pages/man7/keyrings.7.html ; https://man7.org/linux/man-pages/man7/persistent-keyring.7.html | high |
| LF22 | `systemd-creds encrypt --with-key=` one of `host`, `tpm2`, `host+tpm2`, `auto`, `null`: `host` = a root-only secret in `/var/lib/systemd/credential.secret`; `tpm2` = a key from the TPM "enabling decryption only on the original machine"; services receive credentials through `LoadCredentialEncrypted=` into `$CREDENTIALS_DIRECTORY`; **`--tpm2-pcrs` binds to no PCRs by default**; per-user credentials (`--user`, `--uid=`) were **added in systemd 256** | https://man7.org/linux/man-pages/man1/systemd-creds.1.html | high |
| LF23 | Ubuntu 26.04 LTS (released 2026-04-23) ships systemd 259 (cgroup v2 only) and kernel 7.0 | https://ubuntuhandbook.org/index.php/2026/04/ubuntu-26-04-lts-released-with-kernel-7-0-gnome-50-more/ (2026-04) | medium (secondary) |
| LF24 | TCG PC Client Platform TPM Profile: `TPM_ALG_ECDSA` and `TPM_ECC_NIST_P256` are **mandatory** for PC Client TPM 2.0 (P-384 mandatory from PTP 1.04) | https://trustedcomputinggroup.org/wp-content/uploads/PC-Client-Specific-Platform-TPM-Profile-for-TPM-2p0-v1p07_Pub.pdf (v1.07, via search summary) | medium-high |
| LF25 | JDK: `jdk.net.ExtendedSocketOptions.SO_PEERCRED` (since 16) returns `UnixDomainPrincipal(UserPrincipal user, GroupPrincipal group)` — **no pid, no numeric uid**; JEP 380 (JDK 16) adds `AF_UNIX` to `SocketChannel`/`ServerSocketChannel` only (**no datagram**); JDK 21's `ExtendedSocketOptions` has **no device-binding option** (fields: `IP_DONTFRAGMENT`, `SO_INCOMING_NAPI_ID`, `SO_PEERCRED`, `TCP_KEEPCOUNT`, `TCP_KEEPIDLE`, `TCP_KEEPINTERVAL`, `TCP_QUICKACK`) | https://docs.oracle.com/en/java/javase/17/docs/api/jdk.net/jdk/net/UnixDomainPrincipal.html ; https://openjdk.org/jeps/380 ; https://docs.oracle.com/en/java/javase/21/docs/api/jdk.net/jdk/net/ExtendedSocketOptions.html | high |
| LF26 | jpackage (JDK 17): Linux types `app-image`, `deb`, `rpm`; "Application packages must be built on the target platform"; deb needs `fakeroot`, rpm needs `rpm-build`; `--runtime-image` and `--resource-dir` exist; **no `--launcher-as-service` in 17**. JDK-8275062 later added installers that register launchers as services, which "the installer starts … and the uninstaller stops" | https://docs.oracle.com/en/java/javase/17/jpackage/packaging-overview.html ; https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html ; https://bugs.openjdk.org/browse/JDK-8275062 | high / medium (JDK-8275062 via search summary) |
| LF27 | Eclipse Temurin support: Java 17 until at least **October 2027**; Java 21 until at least **December 2029**; Java 25 until at least September 2031 | https://adoptium.net/support/ | high |
| LF28 | nFPM: "A simple deb, rpm, apk, ipk, arch linux, and msix packager written in Go … Just a single binary" (no dpkg or rpmbuild needed) | https://nfpm.goreleaser.com/ | high (licence not confirmed in the fetch) |
| LF29 | Flathub requirements: "**Console softwares will not be accepted.** Exceptions may be given to Flatpak or Flathub related tooling." | https://docs.flathub.org/docs/for-app-authors/requirements | high |
| LF30 | AppImages require FUSE; Ubuntu 22.04+ ships FUSE 3 rather than the libfuse2 many AppImages need; workaround `--appimage-extract-and-run` | https://docs.appimage.org/user-guide/troubleshooting/fuse.html | high |
| LF31 | Flatpak apps request background running and autostart through the XDG Background portal (earlier session V26) | flatpak.github.io xdg-desktop-portal docs (`platforms.md` V26) | high (not re-fetched) |
| LF32 | GitHub-hosted runners for public repos: `ubuntu-24.04`/`ubuntu-22.04` and `ubuntu-24.04-arm`/`ubuntu-22.04-arm` at 4 vCPU, 16 GB RAM, 14 GB SSD; `macos-latest` 3 vCPU (M1), 7 GB; **no GPU on standard runners**; passwordless `sudo`; each job gets a fresh VM. Labels (runner-images README): `ubuntu-26.04` and `ubuntu-26.04-arm` are **GA**; `ubuntu-latest` still → Ubuntu 24.04; `ubuntu-slim` exists | https://docs.github.com/en/actions/reference/runners/github-hosted-runners ; https://github.com/actions/runner-images | high |
| LF33 | The Ubuntu 24.04 runner image README tells users to start services with `sudo systemctl start mysql.service` (systemd manages services on the hosted VM) | https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md (via search summary) | medium-high |
| LF34 | llama.cpp's own CI (master): job `ubuntu-llvmpipe` on `ubuntu-24.04` installs `mesa-vulkan-drivers`, builds `-DGGML_VULKAN=ON` and **runs `ctest -L main` on the software Vulkan driver** with `GGML_VK_DISABLE_F16=1` and `GGML_VK_DISABLE_COOPMAT=1`; CUDA is built in `nvidia/cuda:12.6.2-devel-ubuntu24.04` (compile only, no ctest), HIP in `rocm/dev-ubuntu-22.04:6.1.2`; the release workflow builds Linux Vulkan binaries on `ubuntu-22.04` and `ubuntu-24.04-arm` with `-DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON` | https://github.com/ggml-org/llama.cpp/blob/master/.github/workflows/build-vulkan.yml , `build-cuda-ubuntu.yml`, `release.yml` (raw files downloaded and grepped 2026-09-30) | high |
| LF35 | **Community Steam Deck llama-bench row:** "AMD Custom APU 0405 (RADV VANGOGH)", Llama 2 7B Q4_0, `-ngl 100`: **pp512 144.31 ± 2.74 t/s, tg128 17.52 ± 0.15 t/s**, build `48d22e2` | https://github.com/ggml-org/llama.cpp/discussions/10879 (Vulkan scoreboard) | medium (one community submission; LCD/OLED, power limit and date not stated; read through a summariser) |
| LF36 | NVIDIA CUDA EULA: distributable on Linux include `libcudart.so`, `libcublas.so`, `libcublasLt.so` (Attachment A), on conditions including "material additional functionality", "only be accessed by your application", and distribution terms "consistent with the terms of this Agreement" | https://docs.nvidia.com/cuda/eula/index.html | high (for what the text says; no legal interpretation offered here) |
| LF37 | **MLPerf Client covers Linux.** v1.5 (2025-11-17) added "Linux (CLI, experimental)". The current benchmark page (v2.0) lists **Ubuntu Linux 24.04** among supported platforms, a "CLI-only Linux version", and models Llama 3.1 8B Instruct and Phi 4 Mini Instruct (base), Phi 4 Reasoning 14B (extended), **Qwen 3 8B** and Flux 2 Klein 4B (experimental). The Linux execution paths were **not** confirmed by any page I could fetch | https://mlcommons.org/2025/11/mlperf-client-1-5-release/ (2025-11-17); https://mlcommons.org/benchmarks/client/ | high (for what the pages say) |
| LF38 | GitHub artifact attestations: public repositories use the Sigstore Public Good Instance and write the bundle to a publicly readable transparency log; "artifact attestations are not a guarantee that an artifact is secure"; verified with `gh attestation verify` | https://docs.github.com/en/actions/concepts/security/artifact-attestations | high |
| LF39 | Linux IPv4 defaults to the **weak host model**: it "accepts any locally destined packet regardless of the network interface on which the packet was received" | https://en.wikipedia.org/wiki/Host_model | medium (secondary; consistent with `ip(7)` behaviour) |
| LF40 | `SO_BINDTODEVICE`: "If a socket is bound to an interface, only packets received from that particular interface are processed by the socket" | https://www.man7.org/linux/man-pages/man7/socket.7.html | high |
| LF41 | Steam Deck BIOS "UMA Frame Buffer Size" defaults to 1 GB, settable up to 4 GB | https://www.windowscentral.com/gaming/pc-gaming/how-to-increase-vram-on-steam-deck (secondary) | medium |
| LF42 | dbus-java (hypfvieh): MIT licence; 6.x needs Java 21, 5.x (Java 17) is in sunset support; transports include a native Unix-socket transport on JDK 16+ `UnixDomainSocketAddress` and a junixsocket transport that supports **file-descriptor passing** | https://github.com/hypfvieh/dbus-java | high |

**Facts this section inherits from the design brief without re-fetching:** [F10] (Tailscale log upload), [F11] (userspace networking forwards inbound to 127.0.0.1), [F12] (Funnel), [F18] (llama.cpp abort callback CPU-only), [F20] (gpu_busy_percent device-wide), [F22] (journal readable by `systemd-journal`/`adm`/`wheel`), [F27] (whole-model placement wins), [F28] (llama.cpp RPC is insecure), [F36] (`server/Main.kt` prints the dev token and every ledger row).

#### 1.2 Assumptions (not verified)

| ID | Assumption | Load-bearing for | How to settle |
|---|---|---|---|
| LA01 | Steam's Game Mode suspend goes through logind, so a **delay** inhibitor receives `PrepareForSleep(true)` before the Deck sleeps (LF05 suggests logind is consulted, since a lock stopped the suspend) | Deck drain-before-sleep | Deck test DV-D4 (§10) |
| LA02 | A `systemd --user` service survives a Game Mode ↔ Desktop Mode switch: either the user manager outlives the switch (within `UserStopDelaySec=10s` [LF12]) or linger keeps it | Deck availability | DV-D2 |
| LA03 | `/var/lib/systemd/linger/deck` survives SteamOS updates (the `var` slots are synced [LF04]) | Deck boot-start | DV-D3, after one OS update |
| LA04 | On SteamOS 3.8 (kernel 6.16) the Van Gogh GPU exposes `gpu_busy_percent` and amdgpu `drm-engine-gfx` counters in `/proc/self/fdinfo` [A07] | game-contention governor | DV-D5 |
| LA05 | Steam and Proton games run as uid `deck` with full access to `$HOME` [A08] | the "shared-uid" label | DV-D1 (`ps -o user= -p <game pid>`) |
| LA06 | GPU-visible memory on the Deck is the UMA carve-out (1 GB default [LF41]) plus GTT (a TTM default of roughly half of RAM), enough for an 8B Q4_K_M (5.03 GB) plus KV under Vulkan | Deck 8B lending | read `VK_EXT_memory_budget` heap budgets at run time; DV-D6 |
| LA07 | Scaling LF35 by file size and parameter count gives, for Qwen3-8B Q4_K_M on the Deck under Vulkan, **decode ~10–13 tok/s and prefill ~80–120 tok/s** (see §6.3 for the arithmetic). ESTIMATE ONLY: different model, quantisation, commit, power limit, and a 7B single-run community row | §7.1 Deck row of the design | D-v2 owner benchmark (NEEDS-OWNER-VALIDATION) |
| LA08 | KDE Plasma 6 PowerDevil suspends on AC after 15 minutes by default (the MR "Suspend by default on AC profile", 2023-03-06, proposed exactly that; its merge was not confirmed), so SteamOS Desktop Mode may idle-suspend on AC | Deck Desktop Mode availability | DV-D7 |
| LA09 | `loginctl enable-linger runner` plus `XDG_RUNTIME_DIR=/run/user/$(id -u)` makes `systemctl --user` usable on a hosted Ubuntu runner | CI user-mode test | CI probe step that **fails** (never skips) if the user manager is not reachable |
| LA10 | `sudo ip netns add` and veth pairs work on hosted Ubuntu runners (the design's M1 gate (3) already relies on this) | CI multi-node and weak-host tests | first CI run |
| LA11 | `tpm2-pkcs11` + JDK `SunPKCS11` can create and use a non-exportable P-256 key for ECDSA signing. Interop issues are reported (tpm2-pkcs11 issues #156, #468, #480) | key tier T2 | spike S-L3 |
| LA12 | Ubuntu 24.04 ships systemd 255 (so no `systemd-creds --user`); Fedora ≥ 41, Ubuntu 26.04 [LF23] and SteamOS 3.8 ship ≥ 256 | key tier T1 in user mode | `systemctl --version` per distro in CI containers |
| LA13 | polkit JavaScript rules (`/usr/share/polkit-1/rules.d/*.rules`) are honoured on every target (Ubuntu ≥ 23.10, Fedora, Arch/SteamOS) | LD-6 polkit rule | CI container test on each distro |
| LA14 | Native libraries built on `ubuntu-22.04` (glibc 2.35) load on Ubuntu 22.04+, Fedora ≥ 36 and SteamOS 3.x | one binary per architecture | CI container matrix (§9) |
| LA15 | The NVIDIA proprietary driver exposes no hwmon GPU temperature; `nvidia-smi` is present wherever the driver is | NVIDIA thermal probe | Dell inventory |
| LA16 | SteamOS ships with no active host firewall | Deck listener exposure | DV-D8 (`sudo nft list ruleset`) |
| LA17 | A llama.cpp Vulkan dispatch cannot be pre-empted by a game; after a drain starts, the game can stutter for up to one graph compute | Deck game drain | DV-D9 (frame-time trace while cancelling) |
| LA18 | Comparing `UnixDomainPrincipal.user().getName()` with the expected user name is equivalent to a uid check (no two names share a uid) | control-socket identity | documented limit |
| LA19 | The deck-tailscale install uses a kernel TUN interface (`tailscale0`), not userspace networking | Deck listener on the overlay | DV-D10 (`ip -br addr show tailscale0`) |
| LA20 | MLPerf Client's Linux CLI does not run on SteamOS or use Vulkan on AMD iGPUs (neither is stated by LF37's pages) | §6.5 positioning | owner check; nothing in this plan depends on it |
| LA21 | cgroup v2 memory delegation to the user manager lets `MemorySwapMax=0` apply to a `systemd --user` unit | swap hygiene in user mode | CI user-mode probe + DV-D11 |
| LA22 | The node's JVM (heap capped at 384 MB) costs roughly 150–300 MB RSS excluding the model mapping | RAM guard reserve | measured in CI (`/proc/<pid>/status` VmRSS), reported, not gated |
| LA23 | polkit treats a process of a `systemd --user` service (which is not inside a login session) as belonging to the user's display session when one exists, so the `allow_active` default applies to it; otherwise the "any" default (auth_admin_keep) would deny the block lock [LF13] | USER-mode keep-awake | DV-L2 on the Dell; the node tries the lock and reports refusal either way |
| LA24 | `systemd-inhibit` fails non-interactively (no password prompt) when polkit would require authentication, because the node spawns it with no controlling TTY and no polkit agent | keep-awake fallback | CI test in SYSTEM mode without the LD-6 rule: expect a refusal, not a hang |
| LA25 | On Linux, `/proc/uptime` advances during suspend (boot-time clock) while the JVM's `System.nanoTime` (monotonic clock) does not, so their difference reveals a suspend the node was not told about | the no-signal sleep fallback (§3.3) | DV-D4 / DV-L3 logs (`asom status --json` `.diag.lastSleep.detectedBy`) |

---

### 2. Feasible mesh roles on this platform

**Vocabulary** (design §2.1): **R** borrow; **PA** lend while awake, no human present; **PF** lend while a lend screen/terminal is frontmost; **B** benchmark producer; **S** manifest subscriber. The design forbids the word "always-on" for any Linux role (§3.2 item 4); this section keeps that rule and says **"PA while awake"**.

#### 2.1 Role verdicts

| Node | R (borrow) | PA (lend while awake) | PF (lend while frontmost) | B / S | Holon completeness (R2-DIRECTIVES-4) |
|---|---|---|---|---|---|
| **Linux desktop or server, dedicated-user system service** (the Dell) | **Yes, owner CLI only in mesh-1** (Unix socket, D25(a)); local apps at M2 (D25(b), D14 part B) | **Yes.** The strongest case: no OS background limit; stays up across logout; can hold the machine awake while SERVING *if* the polkit rule LD-6 ships (upstream denies `inhibit-block-sleep` to session-less processes [LF13]) | Yes (`asom lend --foreground` in a terminal) | Yes / Yes | Serves its own CLI with its own engine; no local-app API until M2; **no cloud tier** (no BYOK on non-Android before D14 part B) |
| **Linux laptop, user service** | Yes, CLI | **Only on AC and awake.** Lid close suspends (`HandleLidSwitch=suspend`; with external power the lid setting is ignored by default, so the plain `HandleLidSwitch` applies [LF12]). No keep-awake on battery | Yes | Yes / Yes | as above |
| **Steam Deck, Game Mode, docked on AC** | Only via SSH (Game Mode has no terminal) | **Yes, conditionally and invisibly**: no game running; availability is controlled by **Steam's** "When plugged in, sleep after" [LF06], because asom must not block sleep on SteamOS [LF05]; lending shows nothing on the Deck's screen, so it needs the explicit opt-in LD-2 | No (nothing asom can put on screen in Game Mode) | B yes (from SSH or Desktop Mode) / S yes | Borrow via CLI only when an SSH or Desktop Mode terminal exists |
| **Steam Deck, Desktop Mode** | Yes (Konsole) | Yes while awake on AC; Plasma may idle-suspend [LA08]; asom still holds **no block lock** on SteamOS (the lock would survive a switch to Game Mode and cause LF05) | **Yes** (a Konsole window running `asom lend --foreground` is the visible lend surface) | Yes / Yes | full |
| **Steam Deck on battery** | Yes | **No** (recommended hard NO, LD-10): battery drain, heat in the hand, and a handheld is the device most likely to be picked up | No | B refused (charger required for sustained phases, as `benchmark.md` §14.4) | — |
| **Headless aarch64/x86_64 board** (D26 appliance profile) | Yes (the same JVM with lending disabled) | Technically yes; not scheduled | — | — | requester-only profile unless an engine is scheduled for it |

#### 2.2 The honest reasons

- **Background execution.** Linux imposes no app-lifecycle limit on a systemd service. The limits are power policy and session lifetime:
  - A **user** service dies with the user manager. Without linger the manager stops when the last session ends (after `UserStopDelaySec`, default 10 s [LF12]); with linger it starts at boot and survives logout [LF14].
  - A **system** service with `User=asom` has no session at all, so it is unaffected by login and logout, and runs at the login screen.
- **Sleep.** Nothing runs during suspend. Three different components can suspend a Linux machine, and asom must handle each:
  - **GNOME** (gsd-power) suspends after 15 min idle on AC on Fedora and at the GDM login screen [LF15]; a logind **block** `sleep` lock stops that, because gsd-power asks logind as an unprivileged user [LF11]. The same is assumed for Plasma [LA08].
  - **logind** itself does nothing on idle by default (`IdleAction=ignore` [LF12]); lid switches suspend laptops.
  - **Steam (Game Mode)** decides idle sleep on the Deck [LF06]; a block lock there produces a fake sleep rather than staying visibly awake [LF05].
- **Who may hold a block lock.** Upstream polkit lets processes in active or inactive sessions take `inhibit-block-sleep`, but **not "any" subject** (a system service, or a lingering user service with no session) without admin authentication [LF13]. Consequences:
  - dedicated-user mode needs a shipped polkit rule granting exactly that action to user `asom` (LD-6), or it cannot keep the machine awake;
  - lingering user mode while logged out cannot keep the machine awake; the CLI says so;
  - `inhibit-delay-sleep` is allowed for everyone [LF13], so **draining before sleep works in every mode**.
- **Networking.** No OS permission gates listening or LAN access on Linux. Host firewalls vary (§4.2), and the weak host model means binding to an overlay address does not by itself restrict which interface can reach it [LF39] (§4.1).
- **Power and thermal.**
  - The Deck is a 4–15 W APU with shared LPDDR5 (earlier session V21, [LF01]); sustained inference runs the fan and heats the handheld. Lending only docked and on AC is the default.
  - Desktops: thermal throttling is usually benign, but a lender's own user notices fan noise. The governors (§3.4) drain on heat bands and on contention.
- **Games always win on the Deck.** A game is a presence input (LP-1). The Deck drains with a **2 s grace** (not the desktop's 30 s), because a Vulkan dispatch cannot be pre-empted by a game [LA17] and every second of overlap is a visible stutter.
- **Store and distribution rules.** None gate a Linux daemon except the ones asom chooses to use. Flathub rejects console software [LF29]; SteamOS removes pacman packages on update [LF03]; so the Deck gets a `$HOME` tarball and Ubuntu/Fedora get deb/rpm (§8).
- **What "PA while awake" does NOT guarantee.**
  - It does not keep a Deck awake in Game Mode; Steam decides.
  - It does not wake a sleeping lender. Wake-on-LAN would be a new transmission mechanism and is not in this design.
  - It does not guarantee a response time: a lender drains the moment its own user needs the machine (LP-2), and the requester fails over.

---

### 3. Node hosting

#### 3.1 Three hosting modes (one binary, `asom-node --mode=…`)

| Mode | Where | Runs as | Paths | Start / stop | Boot | Survives logout | Label shown in `asom status` and to peers (self-reported) |
|---|---|---|---|---|---|---|---|
| **SYSTEM (dedicated user)**, recommended for an always-available lender where root exists (T17(a)) | Dell (Ubuntu/Fedora), any root-managed Linux | system unit `asom.service`, `User=asom`, created by `systemd-sysusers` | `/opt/asom/<ver>` (root-owned, read-only), state `/var/lib/asom` (0700 asom), control socket `/run/asom/ctl.sock` | `sudo systemctl start asom` / `stop` | **OFF by default.** Only `sudo systemctl enable asom` (explicit, printed by `asom install --system`) | yes | `host: dedicated-user` |
| **USER** (T17(b)) | Steam Deck; any workstation without root; owner preference | user unit `asom.service` in the user manager | app `~/.local/opt/asom/<ver>` (tarball) or `/opt/asom` (package); state `$XDG_STATE_HOME/asom` (default `~/.local/state/asom`); data `$XDG_DATA_HOME/asom` (models, identity); socket `$XDG_RUNTIME_DIR/asom/ctl.sock` (dir 0700) | `systemctl --user start asom` / `stop` | **OFF by default.** `systemctl --user enable asom` = start at login; start at boot additionally needs `loginctl enable-linger $USER` (allowed without admin upstream [LF13]) | only with linger | `host: shared-uid` |
| **FOREGROUND** | development; PF lending | the invoking terminal | as USER | `asom-node --foreground`; Ctrl-C drains then exits | never | no | `host: foreground` |

Nothing is enabled by any installer, in any mode, and lending is OFF even when the node runs (brief P8 "nothing runs unless the user starts it"; design T5). A running node with lending OFF binds **no TCP socket at all** in mesh-1 (the desktop has no local-app API, T17(d)) and initiates no connection unless the owner's CLI borrows (quiescence, design §8.6).

**Mode detection and refusal rules** (`host/HostMode.kt`):
- `SYSTEM` requires `uid != 0`, the user name `asom`, and `INVOCATION_ID` set (started by systemd). The node **refuses to run as root** in any mode.
- `USER` requires `XDG_RUNTIME_DIR` owned by the invoking uid with mode 0700.
- On SteamOS (`/etc/os-release` `ID=steamos`) only `USER` and `FOREGROUND` are offered (no root-managed install survives updates [LF03]).

#### 3.2 Unit files (normative content; exact files in §10)

**System unit** (`/usr/lib/systemd/system/asom.service`, shipped disabled):

```ini
[Unit]
Description=asom node (lends compute only when enabled; see `asom status`)
Documentation=file:/opt/asom/current/share/doc/LINUX.md
After=network-online.target
Wants=network-online.target

[Service]
Type=exec
User=asom
Group=asom
ExecStart=/opt/asom/current/bin/asom-node --mode=system
StateDirectory=asom
StateDirectoryMode=0700
RuntimeDirectory=asom
RuntimeDirectoryMode=0750
UMask=0077
# T17(c): no token, ledger row or prompt ever reaches the journal
StandardOutput=null
StandardError=null
# no core dumps: they could contain prompts (JVM options, incl. -XX:-CreateCoredumpOnCrash,
# -XX:ErrorFile under the state dir and -Xmx384m, are baked into the launcher by jpackage
# --java-options, so systemd never expands a % specifier inside them)
LimitCORE=0
# prompts in anonymous memory never reach swap (OOM instead; the RAM guard avoids it)
MemorySwapMax=0
# lender must not starve its own user
Nice=10
CPUWeight=20
IOSchedulingClass=idle
# sandbox
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
ProtectClock=yes
RestrictNamespaces=yes
RestrictRealtime=yes
LockPersonality=yes
CapabilityBoundingSet=
AmbientCapabilities=
SystemCallArchitectures=native
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6 AF_NETLINK
DevicePolicy=closed
DeviceAllow=char-drm rw
SupplementaryGroups=render video
TimeoutStopSec=35
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

- `Type=exec`, not `Type=notify`: `sd_notify` needs an `AF_UNIX` **datagram** socket and the JDK offers only stream Unix sockets [LF25]. Readiness is observed through the control socket instead.
- `MemoryDenyWriteExecute` is **not** set: the JIT needs writable-executable memory.
- `DeviceAllow=char-drm` gives Vulkan access to `/dev/dri/*` only. A TPM tier (T2, later) would add `/dev/tpmrm0` explicitly.
- `TimeoutStopSec=35` covers the 30 s desktop drain grace plus shutdown.

**User unit** (`asom.service` in `~/.config/systemd/user/` or `/usr/lib/systemd/user/`, shipped disabled): the same `[Service]` block minus `User`, `Group`, `StateDirectory`, `RuntimeDirectory`, `SupplementaryGroups`, `DevicePolicy`/`DeviceAllow` and the `Protect*` lines (a user manager cannot apply most of them without user namespaces), with `ExecStart=%h/.local/opt/asom/current/bin/asom-node --mode=user` (tarball) and `WantedBy=default.target`. `MemorySwapMax=0` depends on memory delegation to the user manager [LA21].

**What the units do NOT guarantee.**
- The system unit's sandbox limits what a **compromised node** can touch. It does not protect the node from root, from members of group `asom` (who may use the control socket), or from anyone who can read `/var/lib/asom` as root.
- The user unit has **no sandbox** beyond `NoNewPrivileges`: every process of the same uid can read the node's key, ledger and models, and can impersonate the node (T17(g)). On the Deck that includes every game [LA05].
- `StandardOutput=null` keeps the node's own output out of the journal. It does not stop a JVM crash log or a third-party library from writing elsewhere; `-XX:ErrorFile` pins the crash log to the private state directory, and a stdout-capture test (H3) plus a journal-grep test (§10, DL2) are the checks.
- `MemorySwapMax=0` keeps anonymous memory off swap. Model weights are file-backed and are never swapped (they are dropped and re-read). It does not stop prompts lingering in freed RAM.

#### 3.3 Lifecycle events and what survives them

The provider FSM is the design's OFF → ARMED → SERVING → DRAINING (§3.2 of the brief). The wire carries only `fsm`; the **reason stays in the local diagnostics and ledger** (r2 §7.4; R2-DIRECTIVES-5 corrects the brief's §3.2 item 1, which still says "reports availability.reason = sleeping").

| Event (Linux source) | Node reaction | Survives? |
|---|---|---|
| `PrepareForSleep(true)` from logind (needs the D-Bus reader, §7.3) | SERVING → DRAINING immediately; close the listener; abort in-flight streams **within the delay-lock budget** (`InhibitDelayMaxSec=5` [LF12]); write lender outcome rows (`terminal=interrupted`); release the delay lock | the node process: yes (frozen during suspend). Sessions: no |
| resume (`PrepareForSleep(false)`) | re-probe; ARMED → SERVING only when conditions hold. A sleep-caused drain is **not** a presence drain, so LP-2's 10-min hold-down does not apply | — |
| sleep without a signal (Steam path if [LA01] fails; D-Bus reader down) | nothing before sleep; on resume the node notices a suspend gap (the delta of `/proc/uptime`, which counts suspended time [LA25], exceeds the delta of `System.nanoTime`, which does not, by > 10 s) and closes every session; in-flight requester attempts fail over on their dial or read timeouts | ledger intents without outcomes are reconciled on the next start (D21 crash contract) |
| screen lock | nothing (a locked screen is not a presence signal for a PA lender; leaving it is) | yes |
| user logout, USER mode without linger | the user manager sends SIGTERM (`TimeoutStopSec=35`); the JVM shutdown hook drains with 30 s grace | no |
| user logout, SYSTEM mode | nothing | yes |
| Deck Game ↔ Desktop switch | USER mode: survives only if [LA02] holds; linger recommended on the Deck | depends on LA02 |
| a game starts (Deck) | presence → DRAINING with **2 s grace**; 10-min hold-down before SERVING is published again (LP-2) | yes |
| reboot | nothing starts unless the owner enabled the unit (and, in USER mode, linger) | configuration and ledger yes |
| SteamOS update | `$HOME` survives (tarball, user unit, data) [LF03, LF04]; linger file assumed to survive [LA03] | yes (verify DV-D3) |

**Keep-awake policy (the one sleep rule, design §3.2 item 2, amended for Linux):**

| Host | While SERVING with lending ON | Why |
|---|---|---|
| Ubuntu/Fedora desktop, SYSTEM mode | hold `systemd-inhibit --what=sleep --mode=block --who=asom --why="asom is lending compute to your paired devices"` **only if** the polkit rule (LD-6) is installed; otherwise it still serves, but only while the machine happens to be awake, and `asom status` shows `keep-awake: unavailable (polkit)` | upstream polkit denies block locks to session-less subjects [LF13] |
| Ubuntu/Fedora desktop, USER mode while the user has an active or inactive session | try the same block lock; if polkit refuses, serve without it and show `keep-awake: unavailable (polkit)` | allowed for session subjects [LF13], **if** polkit maps a user-service process to the user's session [LA23]; the node never assumes it, it tries and reports |
| USER mode, lingering, logged out | no block lock (denied); lends only while the machine happens to be awake | [LF13] |
| Laptop on battery | never a block lock; lending ARMED (`on-battery`) | design §2.1 |
| **SteamOS, any session** | **never a block lock. Delay lock only.** | [LF05] fake-sleep hazard; a lock taken in Desktop Mode would survive a switch into Game Mode |
| every host | a **delay** `sleep` lock whenever SERVING, released after draining | allowed for every subject [LF13] |

The node holds locks through `systemd-inhibit … sleep infinity` child processes (the lock lives as long as the child; killing it releases the lock). Pure JDK code cannot hold the lock itself: `Inhibit()` returns a Unix file descriptor, and JDK Unix sockets cannot receive descriptors [LF25]. `idle` locks are never taken, so screen blanking and locking are unaffected.

**What keep-awake does NOT guarantee.** A privileged user or a tool with `suspend-ignore-inhibit` can still suspend. A block lock costs energy for as long as lending is ON and conditions hold; `asom status` shows it, and the docs state the cost. On SteamOS the node cannot keep the Deck awake at all: the owner sets Steam's plugged-in sleep to "Disabled" if the docked Deck should keep lending [LF06].

#### 3.4 Provider governors on Linux (conditions for SERVING)

All inputs are pure file reads needing no privileges [LF17–LF20]. Sampling every 2 s; decisions use the hysteresis below. Every threshold is **PROVISIONAL** (calibrated at D-v2, NEEDS-OWNER-VALIDATION).

| Input | Source | Classified as | Rule |
|---|---|---|---|
| Power source | `/sys/class/power_supply/*/type` (`Mains`/`USB` with `online=1` → AC; `Battery` with `status`, `capacity`) — enumerate by `type`, never by name (`BAT1` on the Deck is not portable) | power | laptop/Deck: SERVING only on AC. Desktop without a battery: `ac` |
| Thermal band | hwmon `temp*_input` with labels (`amdgpu edge`, `k10temp Tctl`, `coretemp Package id 0`, `nvme Composite`) and `/sys/class/thermal/thermal_zone*/trip_point_*_{temp,type}`; NVIDIA via `nvidia-smi --query-gpu=temperature.gpu,utilization.gpu --format=csv,noheader,nounits` every 10 s if present [LA15] | thermal | band 2 (HOLD) when any watched sensor ≥ min(lowest `passive` trip − 5 °C, `crit` − 15 °C); band 1 (QUEUE) within 10 °C below that; band 0 otherwise; 3 °C hysteresis and 10 s dwell. Maps to the v2 P4 governor states and to `thermal.band` on the wire |
| Memory | `/proc/meminfo` `MemAvailable`; `/proc/pressure/memory` `full avg10`; Vulkan heap budgets from the engine [LA06] | load | the RAM guard (v2 P2) admits a model only if `modelBytes + kvBudget ≤ MemAvailable − max(1.5 GiB, 10% of RAM)`; drain when PSI memory `full avg10` > 5% for 10 s |
| CPU contention | `/proc/stat` minus own `/proc/self/stat`; `/proc/pressure/cpu` `some avg10` | presence (on Deck and desktops alike) | "others' CPU busy" > 400‰ for 10 s → DRAINING; < 200‰ for 60 s → eligible |
| GPU contention | amdgpu `gpu_busy_percent` (device-wide [LF18]) minus own busy from `/proc/self/fdinfo/*` `drm-engine-gfx` deltas [LF19]; Intel via i915/xe fdinfo (same key scheme, assumption); NVIDIA `utilization.gpu` minus nothing (no own attribution: conservative) | presence | `other_busy` > 400‰ for 10 s → DRAINING; < 200‰ for 60 s → eligible (design §3.2 item 3; unverified on SteamOS [LA04]) |
| Sleep imminent | logind `PrepareForSleep` | lifecycle | §3.3 |

- **Deck-specific:** grace on a presence drain is **2 s**; battery is a hard NO (LD-10); Game Mode lending requires the LD-2 opt-in.
- **No input-device monitoring.** The Linux lender does not read keyboard or mouse idle time or the foreground window. "Someone is using this machine heavily" is inferred only from contention, which is both less invasive and what actually hurts the local user. Light local use (a browser) is not a drain reason; `Nice=10`, `CPUWeight=20` and `IOSchedulingClass=idle` keep the CPU side polite. There is **no GPU priority control** here, so GPU-heavy local work is detected by contention and drained.
- **Laws carried over:** LP-1 (no wire field computed from presence except `fsm` and the decline decision), LP-2 (10-min hold-down after a presence drain), design §7.4. On Linux the presence inputs are exactly the two contention rows.

**What the governors do NOT guarantee.** Sensor labels vary by board, so a machine whose hottest part has no hwmon sensor can overheat without the governor seeing it (the benchmark's thermal protocol records which sensors were used). Contention thresholds are value judgements. NVIDIA GPUs get no own-versus-other attribution, so a node serving on an NVIDIA GPU may drain itself on its own load; the rule then falls back to "drain only on thermal band" for NVIDIA, stated in `asom status`.

#### 3.5 How the user sees that it is running (the watched-object rule)

| Surface | Where | What it shows |
|---|---|---|
| `asom status` (and `asom status --json`) | every mode | host mode and label; FSM state and local reason; listener addresses (or "none"); held locks (block/delay); key tier; peers and their scopes; sessions open; in-flight attempts; last 20 ledger rows (metadata only); governor readings |
| `asom watch` | every terminal | one live line: `SERVING · 1 request from <peer alias> · 11.8 tok/s · GPU 61 °C · block-lock held` |
| logind lock list | `systemd-inhibit --list`, and the "sleep is blocked by asom: asom is lending compute…" prompt that GNOME and Plasma show when the user tries to suspend | the lock and its reason, whenever a block lock is held (never on SteamOS) |
| Desktop notification (optional, off by default) | USER mode with a session bus: `notify-send` child on SERVING/DRAINING transitions | start and stop of lending |
| journal | only unit start/stop lines written by systemd itself | nothing from asom (T17(c)) |
| **Deck in Game Mode** | **nothing on the Deck's screen** | see LD-2: lending in Game Mode is visible only on the requesting device and through `asom status` over SSH or in Desktop Mode. The design's watched-object ethos is therefore met **only by the owner's opt-in**, stated in the opt-in copy: "While in Game Mode, this Deck can lend compute without showing anything on its screen." |

---

### 4. Networking

#### 4.1 Inbound listener (mesh-1 peer listener, IC-1)

- **Feasible, no OS permission.** Any unprivileged process may bind TCP 11436 on a specific local address. The listener is JSSE `SSLEngine` over a NIO `ServerSocketChannel` bound to **each selected address separately**; never `0.0.0.0`, `::` or loopback (IC-1).
- **Address selection** (`net/InterfaceSelector.kt`), by interface, never by range alone (IC-1):
  - *overlay:* the interface named by the user (default candidate `tailscale0`), taking its addresses in `100.64.0.0/10` and `fd7a:115c:a1e0::/48`;
  - *confirmed LAN:* an Ethernet or Wi-Fi interface whose **network fingerprint** matches one the user confirmed with `asom lan confirm` (TTY). Fingerprint = SHA-256 of (interface name ‖ default-gateway IPv4 from `/proc/net/route` ‖ gateway MAC from `/proc/net/arp`). Only private or link-local addresses are bound (IC-1 (b)).
  - Addresses are re-read every 5 s (the JDK has no netlink API); a vanished address closes its listener; a changed fingerprint closes the LAN listener until re-confirmed.
- **Userspace-networking detection.** If `tailscale status` reports online but no `tailscale0` interface exists, the overlay is in userspace mode, which forwards inbound connections to 127.0.0.1 [F11]; the node reports "overlay mode unsupported: use a kernel TUN" and binds nothing (T13).
- **Weak host model (new finding).** Linux accepts a packet for any local address on any interface [LF39]. A listener bound to `100.101.102.103` on `tailscale0` therefore **also accepts** a SYN for that address arriving on the Wi-Fi interface from a LAN host that routes `100.64/10` via this machine. Such a host still cannot authenticate (pinned mTLS), but it **does receive the node certificate**, which is exactly the T13 disclosure that binding to the overlay was meant to limit.
  - The JDK has no `SO_BINDTODEVICE` [LF25], which would fix this [LF40].
  - Mitigation: `asom doctor` prints (never applies) an nftables rule, and checks for it read-only when `nft` is readable:
    ```
    nft add table inet asom
    nft add chain inet asom input '{ type filter hook input priority -10; }'
    nft add rule inet asom input tcp dport 11436 iifname != "tailscale0" ip daddr 100.64.0.0/10 drop
    nft add rule inet asom input tcp dport 11436 iifname != "tailscale0" ip6 daddr fd7a:115c:a1e0::/48 drop
    ```
  - A CI test (§9, `netns-weakhost.sh`) proves both the exposure and the rule's effect in network namespaces.
  - **Stated limit:** without the rule, IC-1's "bound to the overlay interface" limits exposure by address, not by interface. The IC-1 documentation line on certificate disclosure must mention it.
- **LAN fingerprint limits:** a device that clones the gateway's IP and MAC matches the fingerprint. This only decides where the listener binds; authentication still rests on pinned keys.

#### 4.2 Local-network and firewall permissions

| Distro | Default | Effect on 11436 | What asom does |
|---|---|---|---|
| Ubuntu | `ufw` installed, inactive [LF16] | reachable on every bound address | `asom doctor` notes "no host firewall active"; if `ufw` is active, prints `sudo ufw allow in on tailscale0 to any port 11436 proto tcp` |
| Fedora Workstation | firewalld zone `FedoraWorkstation` opens 1025–65535 [LF16] | reachable on bound addresses | notes it; binding only to chosen addresses is what limits exposure |
| Fedora Server / custom firewalld | ports closed unless allowed | blocked | prints `sudo firewall-cmd --zone=<zone of the interface> --add-port=11436/tcp` (and `--permanent` variant) |
| SteamOS | no active firewall assumed [LA16] | reachable on bound addresses | notes it; verify DV-D8 |

asom **never** edits firewall rules, polkit, sysctls or routes. Outbound connections need nothing.

#### 4.3 Private overlay on Linux (Tailscale client, Headscale)

- **Availability.** Tailscale's Linux client (`tailscaled`, kernel TUN `tailscale0`) is the normal case on Ubuntu/Fedora. On the Deck the official installer puts it in `/opt/tailscale` with a boot-started system unit that needs no Desktop Mode [LF08].
- **Log upload.** Every Tailscale client streams connection events to `log.tailscale.com` [LF09]. On Linux it can be disabled with `TS_NO_LOGS_NO_SUPPORT=true` or `--no-logs-no-support` [LF09]. Under Headscale the upload **still happens unless that variable is set on each client** [LF10], which answers the Linux half of the design's [A10]. On the Deck, the installer **resets `override.conf` on every run** [LF08], so an opt-out placed there is lost whenever the owner re-runs the installer (for example after a SteamOS update); put it in `/etc/default/tailscaled` instead (then verify that file survives an update: DV-D10).
- **asom cannot verify the opt-out.** `tailscaled` runs as root, and its environment is not readable by an unprivileged node. `asom doctor overlay` therefore prints the instructions and the status "not verified by asom". The D8 disclosure must say this.
- **Relays.** Overlay traffic may cross DERP relays [F09]; that is why the egress class is `peer` with `peerPath=overlay` (D3), never `lan`.
- **Funnel.** asom never enables or depends on it [F12]; `asom doctor` warns if `tailscale funnel status` lists port 11436.

#### 4.4 mDNS / Bonjour

Avahi is common on Linux desktops (assumption; not needed). **asom does not use mDNS** in mesh-1: peers are located only from the QR, the peer's own authenticated `HELLO`, or the user (T12, T18, D12 defers mDNS locate). The node never registers or browses a service. Nothing to build.

**What networking does NOT guarantee.** Binding to chosen addresses limits who can *connect*, not who learns the node certificate (T13, plus the weak-host exposure above). The overlay operator learns the device graph (D8). A LAN or overlay observer learns timing and volume.

---

### 5. Key storage tier for the node identity

The node identity key (NIK) is ES256/P-256 (C2); it self-signs the pinned node certificate and signs 14-day session leaves (design §4.1). TLS handshakes use the **leaf** key, so the NIK signs rarely, and a slow hardware signer is acceptable. The manifest's `keyStorage` enum is closed (`strongbox|tee|secure-enclave|tpm|os-keystore|file|ephemeral|unknown`); Linux tiers map to it conservatively.

| Tier | Mechanism | `keyStorage` reported | Where offered | Guarantees | Does NOT guarantee |
|---|---|---|---|---|---|
| **T0 file** | PKCS#8 P-256 in `identity/nik.p8`, mode 0600, directory 0700; SYSTEM: `/var/lib/asom/identity/`; USER: `$XDG_DATA_HOME/asom/identity/` (outside the ledger/state directory, T17(f)) with a `CACHEDIR.TAG` so tar/borg/restic `--exclude-caches` skip it | `file` | everywhere (the only tier on the Deck) | filesystem permissions against **other** unprivileged users | anything against root, the same uid (USER mode: every game on the Deck [LA05]), backups that ignore `CACHEDIR.TAG`, or disk theft; clone detection only via T10 |
| **T0w passphrase-wrapped** | the same file encrypted with AES-256-GCM under PBKDF2-HMAC-SHA256 (JDK built-ins, ≥ 600 000 iterations, PROVISIONAL); unlocked by `asom unlock` on a TTY once per boot; "serve while logged out" requires leaving it unwrapped (T17(f)) | `file` | Linux desktops; **not practical on the Deck** (Game Mode has no TTY to unlock after a reboot) | a copied file or disk image is useless without the passphrase | anything while the node runs (the key is in process memory); a keylogger; the same uid reading memory where ptrace is permitted |
| **T1 sealed at rest** (recommended for the Dell, LD-5) | the T0 file encrypted with `systemd-creds encrypt --with-key=host+tpm2` (or `host` without a TPM) and delivered at start by `LoadCredentialEncrypted=nik:/var/lib/asom/identity/nik.cred` into `$CREDENTIALS_DIRECTORY` [LF22]; USER mode only with systemd ≥ 256 (`--user`) [LF22, LA12] | `file` (the key is still used in software; the extra property is shown locally only, never claimed on the wire) | SYSTEM mode on the Dell; USER mode on distros with systemd ≥ 256 | the ciphertext **cannot be decrypted on another machine** when TPM-bound (defeats backups, disk images, `$HOME` sync); no PCR binding by default [LF22], so firmware updates do not lock it | anything against root; the running process (plaintext in memory and in `$CREDENTIALS_DIRECTORY`); a TPM clear or motherboard swap **loses the identity** (re-pair required, stated in the docs) |
| **T2 TPM-resident** (later; spike S-L3) | a non-exportable P-256 key created in the TPM (mandatory algorithm [LF24]) through `tpm2-pkcs11`, used from JSSE/JCA via the JDK's built-in `SunPKCS11` provider; needs `/dev/tpmrm0` access (group `tss`) | `tpm` | Dell after S-L3; never the Deck ([LF03]) | the NIK itself cannot be copied off the machine (clone-resistant identity) | **use** of the key by any code running as the node user (the token PIN must be stored for unattended use, so it adds nothing against that user); the 14-day session leaves are software keys and can be stolen and used until expiry; interop is unproven [LA11]; A1 is treated exactly as A0 by every verifier (design §5.1), so peers gain nothing from the claim |
| **Kernel keyring** | — | — | **rejected as a tier** | — | it holds keys in kernel memory only [LF21], so it is lost at reboot; at most a cache for an unwrapped T0w key, which the node process holds anyway |
| **Secret Service** (GNOME Keyring, KWallet via libsecret) | — | — | **rejected** | — | needs a desktop session (absent for SYSTEM mode, lingering USER mode and Game Mode); any same-uid process can read an unlocked collection; adds a D-Bus dependency |

**Defaults.** Dell SYSTEM mode: T1 (`host+tpm2`) when `/dev/tpmrm0` exists, otherwise T1 (`host`). Linux USER mode: T0w when a TTY unlock per boot is acceptable, else T0. Deck: **T0 only**, labelled `file`, host `shared-uid`. The Peers tab on every requester shows the lender's tier as self-reported (design §4.4).

**What every tier fails to guarantee** (design §4.4 restated for Linux): a pin proves which key, not which software; a root compromise of the lender is a node compromise at every tier; hardware backing (T2) prevents extraction, not use.

---

### 6. Inference backend(s)

#### 6.1 What exists on Linux, and what the node uses

| Backend | On Linux? | mesh-1 (D-v2) | Later | Reason |
|---|---|---|---|---|
| **llama.cpp CPU** (x86_64 AVX2/AVX-512 variants, aarch64 NEON via `GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL` [LF34]) | yes | **yes** (fallback everywhere; the only path on a GPU-less Dell) | — | same engine and JNI surface as Android v2 (roadmap v2 P1 already requires a linux-x86_64 CPU build for CI) |
| **llama.cpp Vulkan** (RADV for AMD incl. the Deck, ANV for Intel, NVIDIA's proprietary Vulkan) | yes | **yes** (default where a Vulkan device with enough heap budget exists) | — | one GPU backend covers AMD, Intel and NVIDIA; llama.cpp's own CI runs its Vulkan tests on hosted runners through Mesa's software driver [LF34], so the code path can be gated in CI (performance cannot) |
| llama.cpp CUDA | yes (NVIDIA driver required) | no | per LD-7 | redistribution is permitted for `libcudart`/`libcublas*` under conditions [LF36], but the licence fit with an Apache-2.0 app is an owner/legal question; Vulkan already runs on NVIDIA |
| llama.cpp HIP/ROCm | partly | no | only with evidence | Vulkan covers AMD; ROCm's support for the Deck's Van Gogh APU is not established (assumption) |
| llama.cpp SYCL, OpenVINO, ONNX Runtime (GenAI) | yes | no | no | a second engine family breaks the comparability key `(backend, commit, buildFlags)` (C11) and doubles the engine conformance surface; OpenVINO is Intel-specific |
| MLX, Core ML, DirectML | **no** (Apple-only / Windows-only) | — | — | not applicable |
| `llama-server` subprocess, llama.cpp RPC | yes | **rejected** | — | an unledgered localhost side door (design §3.1 of `platforms.md`), and RPC is "fragile and insecure" [F28] |

- **In-process JNI, one engine owner** (roadmap v2 non-negotiables; D21(a)). A native crash takes the node down; the D21 crash contract (typed requester error, reconciliation of orphaned intents on restart) applies unchanged.
- **Cancellation.** llama.cpp's abort callback works only on CPU [F18]. On Vulkan a cancel takes effect between graph computations, so `n_ubatch` is kept small (start at 128, PROVISIONAL) to bound cancel latency; the benchmark measures it per backend (B6). This is also the Deck's game-drain latency bound [LA17].
- **Backend choice per model at load time:** Vulkan if the device reports a heap budget ≥ model bytes + KV budget + 512 MiB [LA06]; otherwise CPU. The choice is recorded in live state `engine.backend` and in the manifest.

#### 6.2 The Steam Deck: Vulkan on the RDNA2 iGPU versus CPU

**Recommendation: Vulkan (RADV) by default, CPU as automatic fallback; the D-v2 bakeoff decides (NEEDS-OWNER-VALIDATION).**
- **Prefill** is where the GPU matters: the one community row shows 144 t/s prompt processing for a 7B Q4_0 under Vulkan [LF35]; no comparable CPU figure for the Deck was found.
- **Decode** is memory-bound, and CPU and GPU share the same LPDDR5 [LF01], so decode may be similar on both. The GPU path still leaves the four CPU cores free.
- **Memory.** The GPU sees the UMA carve-out (1 GB default [LF41]) plus GTT [LA06]. The node reads Vulkan heap budgets rather than assuming 16 GB.
- **Games.** Either backend competes with a game (GPU or memory bandwidth), so the game-drain rule (§3.4) applies to both.

#### 6.3 What the Deck row of the design's §7.1 should say (arithmetic, ESTIMATE)

The design's Deck row (A12b) is disputed by R2-OVERCLAIM-3, whose recomputation from 25–40% of 1.6 TFLOPS FP32 gives prefill of only 24–39 tok/s. The community measurement [LF35] contradicts that basis:
- 144 t/s × 2 × 6.74 GFLOP per token ≈ **1.94 TFLOPS effective**, which is **above** the 1.6 TFLOPS FP32 peak [LF01]. The Vulkan kernels must therefore be using packed FP16 or integer dot-product paths, so an FP32-FLOPS basis understates prefill. (The llama-bench scoreboard model is Llama 2 7B Q4_0, 3.56 GiB ≈ 3.82 GB, 6.74 B parameters; taken as an assumption about the exact file.)
- **Decode:** 17.52 t/s × 3.82 GB ≈ 67 GB/s effective (≈ 65% of the OLED's 102.4 GB/s peak, ≈ 76% of the LCD's 88 GB/s). Qwen3-8B Q4_K_M (5.03 GB): 67 / 5.03 ≈ 13.3 t/s at the same efficiency; K-quant overhead → **~10–13 tok/s** [LA07].
- **Prefill:** 144 × 6.74 / 8.19 ≈ 118 t/s; K-quant kernels → **~80–120 tok/s** [LA07].
- **Workload of design §7.1** (500-token prompt, 300-token answer): TTFT ≈ 4.2–6.3 s (warm model); decode ≈ 23–30 s; total ≈ **27–36 s against the phone's 76.5 s ⇒ ~2.1–2.8× total, ~2.7–4× TTFT** (phone baseline [A12a]).
- **Limits, stated:** one community row; unknown LCD/OLED and TDP; a short llama-bench run is not sustained (the Deck's fan-limited sustained rate is lower, by an unknown amount); a different commit and quantisation. **The reviser should replace A12b's basis with this derivation, keep every number labelled ESTIMATE, and keep "the Peers tab must not quote a speed-up" until D-v2 measures it.**

#### 6.4 The Dell (OS and GPU unknown)

| Dell inventory | Backend | Role consequence |
|---|---|---|
| no discrete GPU | CPU | design row A12c: possibly no faster than the phone; still useful for battery/heat relief and for models the phone cannot hold |
| NVIDIA GPU | **Vulkan** (proprietary driver) in mesh-1; CUDA per LD-7 | NVIDIA thermal via `nvidia-smi` [LA15]; no own-versus-other GPU attribution (§3.4) |
| AMD or Intel GPU | Vulkan (RADV/ANV) | full governor attribution via fdinfo |

#### 6.5 Benchmark-baseline consequence (directive D-C)

- **MLPerf Mobile does not target Linux** (Android, iOS and Windows [F43]).
- **MLPerf Client does:** Ubuntu Linux 24.04, CLI only, with Llama 3.1 8B, Phi 4 Mini, Phi 4 Reasoning 14B and an experimental **Qwen 3 8B** [LF37]. Consumer LLM benchmarking on Linux desktops is therefore **already covered**. Whether it runs on SteamOS or uses Vulkan on AMD iGPUs is not stated [LA20]; that gap is too small to fund.
- **Consequences:**
  1. **No standalone Linux benchmark product** (LD-8, recommending that the design's `asom-bench` CLI be dropped for Linux). `asom bench` exists **only inside the node**, because the router needs measurements of the exact engine, commit and backend that will serve (C11). That is the only justification, and it matches directive C's "results feeding the router".
  2. **Wording.** A Qwen3-8B run may say "same model as MLPerf Client v2.0's experimental Qwen 3 8B test; not an MLPerf result" only after a methodology check of MLPerf Client's metric definitions (the Client-side analogue of S-B1; new spike S-L5). Never "MLPerf-comparable" (D-C, R2-OVERCLAIM-4).
  3. **"Desktop coverage" is not a differentiator** (D-C; R2-DIRECTIVES-2 is confirmed for Linux by LF37).
- **What asom's Linux measurement does NOT guarantee:** comparability with any MLPerf result; sustained performance from a short run; anything in a virtual machine (CI numbers are never timings, `benchmark.md` §14.5).

---

### 7. Runtime and code strategy

#### 7.1 Recommendation

**Kotlin on the JVM, the same `:core:*` and `:server` code, compiled to Java 17 bytecode, shipped with a jlink'd runtime (LD-3 recommends Temurin 21).** No KMP (CLAUDE.md). No second implementation of any spec: Linux adds only a host layer and a native build. The Swift conformance lane is irrelevant here; Linux and Android share one Kotlin implementation of every conformance family, and differ only in the TLS stack (JSSE here, Conscrypt on Android), which W08 already covers on both.

- **Why Temurin 21 at run time while building on 17:** Temurin 17 support ends no earlier than October 2027 [LF27], which is likely before D-v2 ships (AD-1 puts it after v1.1 and v2). Temurin 21 runs to at least December 2029. The design already requires the conformance families on JDK 17 **and** 21 (§3.4), and W01's double-printing difference between 17 and 19+ [F16] is exactly why: the desktop build must run the conformance runner **on the shipped runtime image**, not only on the build JDK.
- **Cost of that choice:** the packaging job needs JDK 21 (for `jlink`/`jpackage`); the local build container (JDK 17) can compile and test everything but cannot produce the shipped image. Stated in §9.

#### 7.2 What is shared and what is Linux-only

| Piece | Source | Linux-specific? | Size (estimate) |
|---|---|---|---|
| Contract, catalogue, v1 router, engine interface, `:server` pipeline and drivers | `core/*`, `server/` (mapped by directory, unchanged) | no | 0 new lines |
| Mesh protocol, verifier, router extension, ledger model, bench core | promoted from `lab/` at the owning version (D23) | no | 0 new lines here |
| JNI shim + llama.cpp pin | the **same** C++ source as Android v2 P1, built by `desktop/native/` for linux-x86_64 and linux-aarch64 | build scripts only | ~200 lines CMake/shell |
| Host layer (`desktop/node`) | new | **yes** | ~5–7k lines Kotlin + tests (estimate) |
| Packaging (`desktop/packaging/linux`) | new | yes | ~600 lines shell/YAML/unit files |

#### 7.3 The Linux host layer, concretely

- **Control channel (CD-24, T17(d)).** `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` bound to the runtime-dir path; frames are 4-byte big-endian length + UTF-8 JSON (integers only, C1), max 1 MiB; a closed enum of commands (`status`, `watch`, `chat`, `lend`, `lan-confirm`, `pair-*`, `peers`, `ledger-export`, `bench`, `unlock`, `shutdown`).
  - Peer identity: `SO_PEERCRED` → `UnixDomainPrincipal` [LF25]. SYSTEM mode accepts members of group `asom` (the owner adds themselves at install); USER mode accepts only the same user. The CLI checks the **server's** principal too (`asom` in SYSTEM mode, itself in USER mode), which is the "both directions" check of T17(d).
  - Rows for CLI-originated requests say `callerPkg = local-uid:<user name>` (the principal gives a name, not a number [LF25], [LA18]).
  - `pair-confirm`, `restore` and `lan-confirm` require a TTY confirmation read from `/dev/tty` by the CLI, never from the socket (T17(e)).
- **D-Bus, minimal and in-house (LD-12).** The only D-Bus need in mesh-1 is **one signal**, logind's `PrepareForSleep`. The node implements a ~400-line read-only client over the system bus socket `/run/dbus/system_bus_socket`: SASL `EXTERNAL` with the uid (hex of the `Uid:` line of `/proc/self/status`, since the JDK has no `getuid`), `Hello`, one `AddMatch` (`type='signal',sender='org.freedesktop.login1',interface='org.freedesktop.login1.Manager',member='PrepareForSleep',path='/org/freedesktop/login1'`), and a decoder for message headers and a single boolean body, with a 1 MiB message cap. It sends nothing else. Vectors: recorded wire bytes for both endiannesses, truncated and oversized messages.
  - **Rejected:** dbus-java (MIT, but its Java-17 line is in sunset support and the current line needs Java 21 [LF42]; a large reflection-heavy dependency needing D23 sign-off for one signal); `gdbus monitor` or `busctl monitor` subprocesses (glib tools are absent on minimal servers, and `busctl monitor` needs monitor privileges on the system bus).
  - **Failure mode:** if the reader cannot connect, the node runs without pre-sleep draining and `asom status` says "sleep not detected in advance".
- **Inhibitors.** `systemd-inhibit` child processes as in §3.3, spawned with no TTY [LA24]; the child's exit is watched, and a refused lock is a state, not an error loop.
- **Probes.** Pure parsers over `/sys` and `/proc` (§3.4), each tested against captured fixture trees. **Single home:** these parsers live in `desktop/node` only; the lab item L0.3 "Linux probe parsers over fixture files" should reference this module instead of creating a second copy (a correction for the reviser).
- **Native loader (C13).** Every `.so` under `lib/native/linux-<arch>/<backend>/` is checked against `native.sha256` inside the image before `System.load(<absolute path>)`; `LD_LIBRARY_PATH` is ignored; libraries carry `RPATH=$ORIGIN`; nothing is ever extracted to a temp directory. In SYSTEM mode the image is root-owned (`/opt/asom`), which is what C13 asks for. In a `$HOME` tarball install it is user-writable, so C13's "root-owned" property does not hold; that is part of the `shared-uid` label.
- **Ledger.** JSONL with `FileChannel.force(true)` per append, fail-closed (design §8.4); SYSTEM `/var/lib/asom/ledger/`, USER `$XDG_STATE_HOME/asom/ledger/`. Nothing in `/var` on the Deck (its `var` slot is ~256 MB [LF04]).

#### 7.4 Keeping implementations aligned

- The desktop build maps the same five pure-JVM projects by directory (the lab's §3.5 mechanism) and, after promotion, the promoted mesh modules. It runs the conformance runner (W00–W03, W01b; after promotion W04–W08, M01–M08, R01–R06) **twice: on the build JDK 17 and on the jlink'd runtime image** (`<image>/lib/runtime/bin/java -cp … ConformanceRunner`).
- W08 (the hostile-node TLS suite) runs against the **packaged** node, in both client and server roles, because a jlink module list that drops a crypto provider changes TLS behaviour without failing compilation.

#### 7.5 Rejected runtimes

| Option | Why rejected |
|---|---|
| GraalVM native-image | a third runtime for TLS and reflection-heavy libraries (kotlinx-serialization, Ktor, OkHttp); W08 and every conformance family would need another lane; the memory saving matters only for the unscheduled appliance profile (D26) |
| A Rust or Go daemon | a second implementation of every spec; the design accepts exactly one second implementation (Swift, where no JVM can run) and holds it to the vectors; a third buys nothing on a platform where the JVM runs |
| Python | same, plus packaging |
| KMP | forbidden (CLAUDE.md, D17) |

**Drift cost (estimate).** Linux adds no spec implementation. Its recurring cost is (a) the JDK skew (17 build vs 21 runtime), paid by the double conformance run; (b) the llama.cpp pin shared with Android (C11): every bump rebuilds four native variants and reruns the bench; (c) the distro matrix: about half an engineer-day per new Ubuntu, Fedora or SteamOS release to refresh the container list and fix breakage.

---

### 8. Packaging and distribution

#### 8.1 Artifacts (one app image per architecture, three wrappers)

```
asom-desktop-<ver>-linux-<arch>/            # built by jpackage --type app-image, runtime = jlink'd Temurin 21
  bin/asom-node                             # daemon launcher (JVM options baked in with --java-options)
  bin/asom                                  # CLI launcher (jpackage --add-launcher)
  lib/runtime/                              # jlink image; module list pinned in jlink-modules.txt
  lib/app/*.jar
  lib/native/linux-<arch>/{cpu,vulkan}/     # libggml*.so, libllama.so, libasom_llama_jni.so (RPATH=$ORIGIN)
  lib/native/native.sha256
  share/systemd/{asom.service,asom-user.service}
  share/sysusers.d/asom.conf
  share/polkit/50-asom-inhibit.rules
  share/doc/{LINUX.md,STEAM_DECK.md,LICENSE,NOTICE,THIRD-PARTY.txt}
  install.sh  uninstall.sh
```

| Artifact | For | Built by | Installs to | Needs root |
|---|---|---|---|---|
| `asom-desktop-<ver>-linux-x86_64.tar.gz`, `…-aarch64.tar.gz` | **Steam Deck**, immutable distros, any distro without root | `tar` of the app image | `~/.local/opt/asom/<ver>`, `~/.local/bin/asom`, `~/.config/systemd/user/asom.service` | no |
| `asom-desktop_<ver>_amd64.deb`, `_arm64.deb` | Ubuntu/Debian | **nFPM** [LF28] from the app image | `/opt/asom/<ver>`, `/usr/bin/asom`, `/usr/lib/systemd/{system,user}/`, `/usr/lib/sysusers.d/`, `/usr/share/polkit-1/rules.d/` | yes |
| `asom-desktop-<ver>.x86_64.rpm`, `.aarch64.rpm` | Fedora/RHEL-likes | nFPM | same | yes |

- **Package scripts** do `systemd-sysusers`, `systemctl daemon-reload` and, on upgrade, `systemctl try-restart asom` (restarts a running node, never starts a stopped one). **They never `enable` or `start`.** CI asserts `systemctl is-enabled asom` prints `disabled` after install.
- **Dependencies:** `Recommends: libvulkan1` (deb) / `Recommends: vulkan-loader` (rpm); the CPU backend needs nothing.
- **Removal** keeps `/var/lib/asom` (identity, ledger, models); `apt purge` or `asom uninstall --purge` deletes it after a TTY confirmation that names what is lost (the ledger and the identity, which forces re-pairing).
- **Models are never packaged.** They arrive by user action as `download` ledger rows.

#### 8.2 Rejected formats (clear NOs)

| Format | Verdict | Reason |
|---|---|---|
| **Flatpak / Flathub** | **NO** | Flathub rejects console software [LF29]; background running needs the portal [LF31]; the daemon loses systemd integration, logind locks and `/run` sockets; GPU compute inside the sandbox is extra work. Acceptable later only for a GUI, if one is ever designed (Invariant 7 sanction needed) |
| **AppImage** | **NO** | a long-running daemon would hold a FUSE mount for its lifetime, libfuse2 is missing by default on Ubuntu 22.04+ [LF30], native libraries would load from a FUSE mount (C13), and it offers nothing over the tarball |
| **Snap** | NO (not evaluated in depth; assumption) | the same sandbox, service and GPU issues as Flatpak |
| **jpackage `--type deb/rpm`** | NO, in favour of nFPM | JDK 17's jpackage has no service support [LF26]; the later service installers **start** the service on install [LF26], which violates "boot-start OFF"; shipping disabled system and user units would need template overrides; jpackage cannot cross-build (deb on Ubuntu, rpm on Fedora) while nFPM builds both from one job. jpackage is still used, for the **app image** |
| **pacman / AUR on SteamOS** | NO | packages are removed on every SteamOS update [LF03] |
| **systemd-sysext image for SteamOS** | not now | needs root and read-only toggling; a user service in `$HOME` already survives updates [LF03, LF07]; reconsider only if a SYSTEM-mode Deck is ever wanted |

#### 8.3 Signing and provenance

- `SHA256SUMS` over every artifact, **detached-signed by the owner's offline OpenPGP key** (the signature is made outside CI; the key fingerprint is published in the README). rpm packages are signed with `rpmsign` by the owner.
- **GitHub artifact attestations** for every release artifact [LF38], verifiable with `gh attestation verify --repo asystemofcells/asystemofmodels <file>`.
- `install.sh` verifies `SHA256SUMS` always, and the signature when `gpg` is present (it says which checks ran).
- **What this does NOT guarantee:** the attestation proves which workflow and commit built a file, "not … that an artifact is secure" [LF38]; the OpenPGP signature proves the owner's key signed the checksums, not that the code is safe; neither protects a `$HOME` install from later same-uid modification. Linux has no notarisation equivalent.

#### 8.4 How updates reach the device

- **No in-app update check, ever.** A version check to GitHub is not one of the permitted egress classes (Invariant 3) and would be automatic egress (Invariant 1).
- **Tarball:** the user downloads the new archive and runs `install.sh` (or `asom upgrade --from <file>`): verify, extract beside the old version, swap the `current` symlink atomically, restart the service if running. The previous version directory is kept for `asom rollback`.
- **deb/rpm:** `sudo apt install ./asom-desktop_<ver>_amd64.deb` / `sudo dnf install ./asom-desktop-<ver>.x86_64.rpm`. An optional static apt/dnf repository on GitHub Pages (LD-9) would let the OS's own updater fetch it; that traffic belongs to the package manager, not to asom, and the docs say so.
- **SteamOS updates** do not touch `$HOME` [LF03, LF04], so an OS update never updates or removes asom.
- **Version skew between peers** is handled by protocol versioning (`VERSION_UNSUPPORTED`, design §4.3); no forced upgrade exists.

---

### 9. What GitHub Actions hosted runners can honestly verify

A separate workflow, `.github/workflows/desktop-linux.yml` (AD-3: separate job; `ci.yml`'s two existing jobs untouched), triggered on changes under `desktop/**`, `core/**`, `server/**`, `gradle/**`.

| Job | Runner | Verifies | Does NOT verify |
|---|---|---|---|
| `desktop-jvm` (JDK 17 and 21 lanes) | `ubuntu-24.04`, with `ANDROID_HOME=""` | compile + unit tests of `desktop/node`; the lab-style isolation checks; root `./gradlew jvmTest` unchanged | anything native or OS-integrated |
| `desktop-jvm-arm` | `ubuntu-24.04-arm` [LF32] | the same on aarch64 | — |
| `native-linux` | `ubuntu-22.04` (x86_64, glibc baseline [LA14]); `ubuntu-24.04-arm` (aarch64) | llama.cpp + JNI shim build for `cpu` (`GGML_CPU_ALL_VARIANTS`, `GGML_BACKEND_DL`) and `vulkan`, the same flags llama.cpp's release uses [LF34] | that the binaries are fast, or that they work on a given GPU driver |
| `engine-smoke` | `ubuntu-24.04` | a tiny pinned GGUF generates through JNI on **CPU**, and on **Vulkan via Mesa's software driver** with F16 and coopmat disabled (llama.cpp's own CI pattern [LF34]); cancellation; RAM guard; single-flight queue | RADV/ANV/NVIDIA drivers, F16/coopmat paths, any timing |
| `cuda-compile` (only if LD-7(b)) | `ubuntu-24.04` in an `nvidia/cuda` devel container | that the CUDA variant compiles | that it runs (no GPU on standard runners [LF32]) |
| `package-linux` | `ubuntu-24.04` with JDK 21 | jlink image, app image, tarballs, deb/rpm via nFPM, `dpkg-deb -c` and `rpm -qlp` layout assertions, attestations on tags | signatures (owner, offline) |
| `install-matrix` | containers `ubuntu:22.04`, `ubuntu:24.04`, `ubuntu:26.04`, `fedora:latest`, `archlinux:latest` | install/uninstall; `asom --version`; `asom-node --self-test` (native load check, ES256 sign/verify, a TLS 1.3 loopback handshake with the pinned-chain verifier, all inside the packaged runtime) | systemd behaviour (plain containers have no systemd); **SteamOS itself** (Arch is a userland proxy only, not gamescope, Steam or SteamOS's logind policy) |
| `systemd-vm` | the `ubuntu-24.04` and `ubuntu-26.04` runner VMs themselves (systemd manages services there [LF33]) | `systemd-analyze verify` on both units; installed units `disabled`; SYSTEM unit starts as `asom`; `asom status` over the socket from a user in group `asom`; a request, then `journalctl -u asom` contains no token and no ledger row; `systemd-inhibit --list` shows the delay lock while SERVING; without the polkit rule the block lock is refused (LA24), with it the lock is held; the USER-mode probe (LA09) | real suspend/resume (a CI VM cannot suspend); `PrepareForSleep` from real logind (tested instead with a private `dbus-daemon` and a scripted fake logind) |
| `conformance-on-runtime` | `ubuntu-24.04` | the conformance runner on the **packaged** runtime | — |
| `mesh-netns` (M1, after promotion) | `ubuntu-24.04` with `sudo` [LA10] | the design's M1 gate (3) multi-node suite; **the weak-host test** (a LAN-side namespace reaches the overlay-bound listener without the nftables rule, and cannot with it); desktop quiescence with `tcpdump -i any` inside namespaces | overlay behaviour (no Tailscale in CI), relays, real Wi-Fi |

**Remains NEEDS-DEVICE-VALIDATION** (the owner runs §10's checklists):
- **Deck:** Game Mode user service (LF07/LA02); linger across updates (LA03); PrepareForSleep from Steam's suspend (LA01); **no fake sleep with the delay lock only** (LF05); `gpu_busy_percent` and fdinfo (LA04); Vulkan heap budget and 8B fit (LA06); the 8B decode/prefill bakeoff and sustained rate (LA07); game-launch drain timing and stutter (LA17); Tailscale TUN and log opt-out persistence (LA19, LF08); firewall state (LA16).
- **Dell:** OS, GPU, TPM and `systemd-creds` sealing; polkit behaviour for USER-mode locks (LA23); GNOME/GDM auto-suspend held off while SERVING (LF15); NVIDIA probes (LA15); CUDA if chosen; a 30-min idle test (design M1 gate 6).
- **Both:** real suspend/resume; hwmon label coverage; thermal band thresholds; fan-noise acceptability.

---

### 10. Implementation scaffold plan

#### 10.1 When any of this may start (read first)

- **D-v2 is roadmap v4's asom-desktop item.** Its entry criteria (design §9.3, as corrected by R2-CONFORMANCE-3) are: v2 shipped on Android, D4 ruled, D24 ruled, **the v4 design session held and recorded**, and D23 for CD-24. AD-1 places it after v2. AD-4 authorises only the lab.
- Steps **DL0–DL3** (build skeleton, probes and governor, host integration, packaging, all with `NoopEngine`, no listener, no mesh) touch no shipped module and ship nothing. They retire the platform risks that need calendar time on owner hardware (LF05, LF13, LA01–LA04). Starting them before D-v2's entry is an **exception** to roadmap §0 and v4's "do not cold-execute", exactly like D1a; it needs the owner ruling **LD-1**. Without it, the Linux probe parsers stay in lab L0.3 (already under D1a) and everything below waits.
- **DL4 onward** needs the v2 engine; **DL6** needs D2, D3, D5 and the promotion of the lab's mesh modules (D23).

#### 10.2 File tree

```
desktop/                                         # separate Gradle build (AD-3); the root build never references it
  README.md                                      # what the desktop node is, its status, honest limits; links to docs/
  settings.gradle.kts                            # maps ONLY :core:contract, :core:catalogue, :core:routing,
                                                 #   :core:inference-api, :server by projectDir (lab §3.5 mechanism,
                                                 #   never includeBuild("..")); later the promoted mesh modules (D23)
  build.gradle.kts                               # redirects mapped build dirs to desktop/build/mapped/<name>;
                                                 #   aggregate task desktopTest
  gradle.properties                              # JVM args for the build; nothing secret
  node/                                          # Gradle project :node  (= MOD-1 ":node-desktop")
    build.gradle.kts                             # kotlin-jvm 17 + kotlinx-serialization + application (applicationName
                                                 #   "asom-node", plus a second start script "asom" for the CLI);
                                                 #   deps: :server, :core:*; NO new third-party runtime dependency
    src/main/kotlin/xyz/mdhv/asom/desktop/
      Main.kt                                    # asom-node entry: --mode=system|user|foreground, --self-test; refuses root;
                                                 #   installs the drain shutdown hook; prints nothing secret (H3)
      NodeConfig.kt                              # config JSON (integers only, C1): lending, overlay iface, confirmed LANs,
                                                 #   gameModeLending (LD-2), keepAwake, thresholds (PROVISIONAL)
      host/HostMode.kt                           # mode detection and refusal rules (§3.1)
      host/Paths.kt                              # XDG and /var/lib/asom resolution; enforces 0700 dirs, 0600 files
      host/OsRelease.kt                          # /etc/os-release parser; isSteamOS
      host/SteamOsPolicy.kt                      # Deck rules: never a block lock, 2 s game grace, battery hard NO,
                                                 #   Game Mode lending only with the LD-2 opt-in
      control/ControlServer.kt                   # AF_UNIX stream server; SO_PEERCRED allow-list; 1 MiB frame cap
      control/ControlClient.kt                   # CLI side; checks the server principal (T17(d) both directions)
      control/ControlFrames.kt                   # closed command enum; request/response types
      cli/AsomCli.kt                             # asom: status, watch, chat, lend on|off|--foreground, lan confirm,
                                                 #   unlock, doctor, install, uninstall, upgrade, rollback, bench, ledger export
      cli/TtyConfirm.kt                          # reads confirmations from /dev/tty only (T17(e)); refuses without a TTY
      dbus/MiniDbus.kt                           # read-only system-bus client: SASL EXTERNAL, Hello, AddMatch,
                                                 #   header + boolean decoder (§7.3)
      power/SleepWatcher.kt                      # PrepareForSleep -> os_sleep_imminent / os_resumed; clock-jump fallback
      power/Inhibitor.kt                         # systemd-inhibit child processes (delay always while SERVING;
                                                 #   block only where §3.3 allows); refusal is a state
      probes/PowerProbe.kt                       # power_supply enumeration by type
      probes/ThermalProbe.kt                     # hwmon + thermal_zone trips -> band 0/1/2 with hysteresis
      probes/MemoryProbe.kt                      # MemAvailable, PSI memory
      probes/CpuProbe.kt                         # /proc/stat minus /proc/self/stat; PSI cpu
      probes/GpuProbe.kt                         # amdgpu gpu_busy_percent minus own fdinfo drm-engine-*; i915/xe fdinfo;
                                                 #   optional nvidia-smi child
      governor/Conditions.kt                     # per-host condition table (desktop, laptop, Deck)
      governor/ProviderFsm.kt                    # OFF/ARMED/SERVING/DRAINING; LP-1/LP-2; grace per host
      net/InterfaceSelector.kt                   # (DL6) overlay/LAN address selection by interface; userspace-mode detection
      net/LanFingerprint.kt                      # (DL6) /proc/net/route + /proc/net/arp fingerprint
      net/FirewallDoctor.kt                      # (DL6) read-only detection of ufw/firewalld/nft; prints rules, never applies
      ledger/JsonlLedgerSink.kt                  # append + FileChannel.force; fail-closed (design §8.4)
      engine/NativeLoader.kt                     # (DL4) sha256-checked System.load from the image only (C13)
    src/test/kotlin/...                          # unit tests; one test class per file above
    src/test/resources/fixtures/sysfs/<host>/    # captured /sys and /proc subsets per host (deck-oled, deck-lcd, dell, ci-vm);
                                                 #   synthetic until the owner captures real ones with `asom doctor capture`
    src/test/resources/dbus/                     # recorded PrepareForSleep messages (LE and BE), truncated/oversized cases
  native/                                        # (DL4)
    llama.cpp.pin                                # one commit sha, shared with Android v2 (C11)
    CMakeLists.txt                               # builds libasom_llama_jni.so from the SAME JNI source as Android v2 P1
    build-linux.sh                               # cpu (GGML_CPU_ALL_VARIANTS, GGML_BACKEND_DL) and vulkan variants per arch
  docs/
    LINUX.md                                     # modes, status, lending, keep-awake cost, firewall, overlay log opt-out
    STEAM_DECK.md                                # Game Mode rules, Steam sleep setting, linger, the invisibility statement
    DEVICE_CHECKLIST_LINUX.md                    # DV-D* (Deck) and DV-L* (Dell) with exact commands and expected results
  packaging/linux/
    jlink-modules.txt                            # module list from jdeps, committed; CI fails on drift
    build-app-image.sh                           # jlink (Temurin 21) + jpackage --type app-image + --add-launcher asom
    install.sh                                   # user-local install: verify, extract, swap `current`, write user unit (disabled)
    uninstall.sh                                 # stop, remove unit and versions; --purge after TTY confirmation
    systemd/asom.service                         # SYSTEM unit (§3.2), shipped disabled
    systemd/asom-user.service                    # USER unit, shipped disabled
    sysusers.d/asom.conf                         # u asom - "asom node" /var/lib/asom
    polkit/50-asom-inhibit.rules                 # LD-6: inhibit-block-sleep for user asom only
    nfpm.yaml                                    # deb + rpm from the app image (LD-4)
    scripts/postinstall.sh                       # systemd-sysusers; daemon-reload; try-restart on upgrade; NEVER enable/start
    scripts/preremove.sh                         # stop if active; disable
    test/distro-matrix.sh                        # container install/uninstall + --self-test per distro
    test/systemd-vm.sh                           # unit verify, disabled-after-install, start/stop, socket, lock checks
    test/journal-hygiene.sh                      # a request, then grep the journal for the token and ledger rows
    test/netns-weakhost.sh                       # (DL6) weak-host exposure with and without the nftables rule
.github/workflows/desktop-linux.yml              # the jobs of §9; ci.yml untouched
```

The polkit rule (LD-6), verbatim:

```js
// /usr/share/polkit-1/rules.d/50-asom-inhibit.rules
// Lets ONLY the dedicated `asom` service user hold a logind sleep BLOCK lock while it lends.
// Grants nothing else. Remove this file to withdraw it.
polkit.addRule(function (action, subject) {
    if (action.id == "org.freedesktop.login1.inhibit-block-sleep" && subject.user == "asom") {
        return polkit.Result.YES;
    }
});
```

#### 10.3 Steps, gates and estimates

Gates follow brief §12: the real command output is pasted into `PROGRESS.md`; CI-only gates are labelled `CI-ONLY`; device items stay `NEEDS-DEVICE-VALIDATION`. Expected outputs below are what a passing run prints; they are not results.

| Step | Needs | Contents | Gate (command → expected) | Estimate (eng-weeks) |
|---|---|---|---|---|
| **DL0** skeleton and isolation | LD-1(b) | `settings`/`build` files, `Main.kt`, `host/*`, `control/*`, `cli/AsomCli.kt` (`status` only), `NoSecretsOnStdoutTest` | 1. `./gradlew -p desktop desktopTest` → `BUILD SUCCESSFUL`, 0 failures (JDK 17 container, no SDK).<br>2. `grep -c desktop settings.gradle.kts` → `0`.<br>3. `./gradlew -p desktop buildEnvironment \| grep -c com.android` → `0` (run again in CI with the SDK present).<br>4. root `./gradlew jvmTest --rerun-tasks` → same test count as the last v1 baseline.<br>5. `git diff --exit-code -- core server gradle settings.gradle.kts build.gradle.kts .github/workflows/ci.yml` → exit 0.<br>6. `./gradlew -p desktop :node:installDist`, then `desktop/node/build/install/asom-node/bin/asom-node --foreground &` and `…/bin/asom status --json` → `{"host":"foreground","fsm":"OFF","listeners":[],"locks":[],…}` | 1–1.5 |
| **DL1** probes and governor | DL0 | `probes/*`, `governor/*`, fixtures for four hosts | 1. `./gradlew -p desktop :node:test --tests '*Probe*' --tests '*Fsm*'` → 0 failures.<br>2. the exhaustive FSM test prints `transitions exercised: 36/36` (4 states × 9 events) and **fails if any law exercised nothing**.<br>3. vectors: LP-2 hold-down (no SERVING < 600 s after a presence drain); Deck grace 2 s; NVIDIA thermal-only fallback; laptop on battery → ARMED | 1.5–2.5 |
| **DL2** host integration | DL1 | full CLI, `TtyConfirm`, `MiniDbus`, `SleepWatcher`, `Inhibitor`, units, sysusers, polkit rule, `JsonlLedgerSink` | 1. `./gradlew -p desktop :node:test` incl. `MiniDbusVectorsTest` and `SleepWatcherIT` against a private `dbus-daemon` (CI sets `ASOM_REQUIRE_DBUS=1`, so a missing binary **fails** the job).<br>2. `systemd-analyze verify desktop/packaging/linux/systemd/asom.service` → no output, exit 0; `systemd-analyze --user verify …/asom-user.service` → same.<br>3. `systemd-vm` job: `systemctl is-active asom` → `active`; `asom status --json \| jq -r .host` (as a group-`asom` user) → `dedicated-user`; `journal-hygiene.sh` → `PASS: 0 token matches, 0 ledger rows`; `systemd-inhibit --list` → a `delay` line for `asom` while SERVING (simulated conditions); without the rule `asom status` shows `keep-awake: unavailable (polkit)`, with it a `block` line (CI-ONLY).<br>4. forked-JVM SIGKILL durability test of the JSONL sink (design §8.4) | 2–3 |
| **DL3** packaging | DL2 | `jlink-modules.txt`, `build-app-image.sh`, `install.sh`, `uninstall.sh`, `nfpm.yaml`, scripts, `test/*.sh`, workflow | 1. `bash desktop/packaging/linux/build-app-image.sh --arch x86_64` → `app image: build/asom-desktop-<ver>-linux-x86_64` (CI-ONLY: needs JDK 21).<br>2. `nfpm package -f desktop/packaging/linux/nfpm.yaml -p deb` → `created package: …_amd64.deb`; `-p rpm` likewise.<br>3. `dpkg-deb -c *.deb \| grep -c usr/lib/systemd/system/asom.service` → `1`.<br>4. `distro-matrix.sh` → per distro `asom <ver> (desktop, linux-x86_64, runtime 21.x)` and `self-test: native OK (none), ES256 OK, TLS1.3 pinned handshake OK`.<br>5. after `apt install`: `systemctl is-enabled asom` → `disabled`.<br>6. `jdeps --print-module-deps` output equals `jlink-modules.txt` | 1.5–2.5 |
| **DL4** engine port | v2 shipped; D-v2 entry (incl. v4 design session); D4; LD-7 | `native/*`, `NativeLoader.kt`, wiring to the v2 engine, model manager on Linux paths, RAM guard with Vulkan budgets | 1. `native-linux` artifacts for {x86_64, aarch64} × {cpu, vulkan}.<br>2. `asom-node --self-test --engine=cpu --model=$TINY_GGUF` → `generated 16 tokens (cpu)`; `--engine=vulkan` on the CI VM → `generated 16 tokens (vulkan: llvmpipe)` (CI-ONLY, software driver).<br>3. cancel test → `cancel latency < 1000 ms (cpu)`.<br>4. RAM-guard vector → `MODEL_OOM`.<br>5. **NEEDS-OWNER-VALIDATION:** Deck and Dell 8B decode/prefill bakeoff, CPU vs Vulkan, sustained 10 min, replacing §6.3's estimate | 2.5–4 |
| **DL5** benchmark inside the node | v2 P6; `bench-core` promoted; LD-8 | `asom bench` subcommand only | `asom bench --plan ci --allow-virtual --yes=ci` → writes an `asom.bench/1` document with the banner `VIRTUALIZED — NOT DEVICE EVIDENCE`; M04/M05 vectors pass on the packaged runtime | 0.5–1 |
| **DL6** mesh-1 desktop integration | D2, D3, D5; lab mesh modules promoted (D23); M1 | `net/*`, listener wiring, `netns-weakhost.sh`, `mesh-netns` job | 1. design M1 gate (3) in network namespaces, with row counts per node incl. L-L15/L-L16.<br>2. `netns-weakhost.sh` → `without rule: connected (certificate received)` / `with rule: timeout` → `PASS`.<br>3. W08 against the packaged node in both roles → all vectors pass.<br>4. desktop quiescence in CI: CLI idle 3 min, `tcpdump -i any` in the node's namespace → `0 packets captured` on port 11436 (the 30-min run is the device gate) | 2–3 |
| **DL7** device validation support | each phase | `docs/DEVICE_CHECKLIST_LINUX.md` | the owner's pasted outputs; every item stays NEEDS-DEVICE-VALIDATION until confirmed | 0.5–1 |
| **Total** | | | | **≈ 12–19 engineer-weeks** |

**Estimate assumptions.** One engineer fluent in Kotlin and Linux; the v2 engine and its JNI exist (DL4 reuses them); the lab's mesh modules exist and are promoted (DL6); the owner runs device checks. DL0–DL5 (9–14.5) sits inside the design's §9.4 D-v2 figure for Linux/Deck (9–16), minus the standalone `asom-bench` CLI that LD-8 drops, plus the in-house D-Bus reader, the distro matrix and the Deck-specific rules. DL6 (2–3) is Linux's share of M1 (7–11 across platforms); DL7 adds 0.5–1. These are estimates, not measurements.

#### 10.4 Device checklist (exact commands; all NEEDS-DEVICE-VALIDATION)

| ID | Device | Command | Expected |
|---|---|---|---|
| DV-D1 | Deck | during a game, over SSH: `ps -o user= -p $(pgrep -f -n steamapps)` | `deck` (LA05) |
| DV-D2 | Deck | `systemctl --user start asom`; switch Game → Desktop → Game; `systemctl --user show asom -p ActiveEnterTimestamp` before and after | unchanged timestamp (LA02); repeat with and without `loginctl enable-linger deck` |
| DV-D3 | Deck | after one SteamOS update: `loginctl show-user deck -p Linger` | `Linger=yes` (LA03) |
| DV-D4 | Deck | docked, lending ON, Game Mode; press power; afterwards `asom status --json \| jq .diag.lastSleep` | `{"announced":true,"drainedMs":<5000}` (LA01); the Deck is **actually** asleep (fan off, SSH unreachable) — the LF05 hazard is absent |
| DV-D5 | Deck | `cat /sys/class/drm/card*/device/gpu_busy_percent`; `grep drm-engine /proc/$(pgrep -f asom-node)/fdinfo/*` | a number; `drm-engine-gfx` lines (LA04) |
| DV-D6 | Deck | `asom doctor gpu` | Vulkan device `RADV VANGOGH`, heap budgets ≥ 6 GiB for an 8B Q4_K_M (LA06) |
| DV-D7 | Deck | Desktop Mode, idle 20 min on AC, lending OFF | records whether Plasma suspends (LA08) |
| DV-D8 | Deck | `sudo nft list ruleset` | empty (LA16) |
| DV-D9 | Deck | start a game while a 300-token request streams; MangoHud frame-time log | node DRAINING within 2 s; worst frame spike recorded (LA17) |
| DV-D10 | Deck | `ip -br addr show tailscale0`; after re-running the Tailscale installer, `grep TS_NO_LOGS /etc/default/tailscaled` | an address in 100.64/10 (LA19); the opt-out still present |
| DV-D11 | Deck | `cat /sys/fs/cgroup$(systemctl --user show asom -p ControlGroup --value)/memory.swap.max` | `0` (LA21) |
| DV-L1 | Dell | `cat /etc/os-release; lspci \| grep -Ei 'vga\|3d'; ls /dev/tpmrm0` | inventory for LD-5, LD-7 |
| DV-L2 | Dell | USER mode with a desktop session: `asom lend on`; `systemd-inhibit --list` | a `block` line, or `keep-awake: unavailable (polkit)` in `asom status` (LA23) |
| DV-L3 | Dell | SYSTEM mode with LD-6: reboot to the GDM login screen, lending ON, wait 20 min, send a request from the phone | served (GDM's 15-min suspend held off, LF15); design M1 gate (10) with "serve while logged out" |
| DV-L4 | Dell | `sudo systemd-creds encrypt --with-key=host+tpm2 nik.p8 nik.cred`; start the unit | node starts; `asom status` shows `identity: sealed (host+tpm2)` |
| DV-L5 | Dell | the §6.3 workload on CPU and Vulkan (and CUDA if LD-7(b)) | measured decode/prefill replacing A12c/A12d |

---

### 11. Owner decisions specific to this platform, and risks

#### 11.1 Owner decisions

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **LD-1** | May `desktop/` start before D-v2's entry criteria (v2 shipped, v4 design session held)? | (a) no: probe parsers stay in lab L0.3 under D1a; nothing else until D-v2 entry · (b) DL0–DL3 now as a ship-nothing exception like D1a (`NoopEngine`, no listener, no release artifacts; CI artifacts only) · (c) everything now | **(b)**, recorded as an exception to roadmap §0 and v4's "do not cold-execute". It retires the Deck/systemd/polkit risks early on owner hardware. Engine and mesh wait regardless |
| **LD-2** | May the Deck lend in Game Mode, where nothing on its screen shows it? | (a) never: Desktop Mode foreground lending only · (b) yes, behind an explicit opt-in whose copy says "without showing anything on its screen"; docked, on AC, no game; availability controlled by Steam's sleep setting · (c) (b) plus a block lock to keep it awake | **(b)**. **(c) is rejected**: the fake-sleep hazard [LF05] |
| **LD-3** | Which Java runtime ships in the image? | (a) 17 (same as the build; support ≥ Oct 2027) · (b) 21 (≥ Dec 2029) · (c) 25 (≥ Sep 2031; no conformance lane yet) [LF27] | **(b) 21.** Build stays on 17; the conformance runner runs on the shipped image |
| **LD-4** | Tool for deb/rpm | (a) nFPM from the jpackage app image (build-time only) · (b) jpackage `--type deb/rpm` with template overrides, built on each distro · (c) hand-written `debian/` and `.spec` | **(a)**; SIGN-OFF as a build-time dependency under D23 (it is not shipped) |
| **LD-5** | NIK tier on the Dell | T0 file · T0w passphrase · **T1 systemd-creds sealed** · T2 tpm2-pkcs11 (after spike S-L3) | **T1 (`host+tpm2`)** in SYSTEM mode; T2 only if S-L3 passes and the owner wants clone resistance at the cost of re-pairing on a TPM clear. The Deck is T0 only |
| **LD-6** | Ship the polkit rule that lets user `asom` hold a sleep block lock? | (a) ship it in deb/rpm, inert until lending is ON · (b) ship it disabled (`.rules.example`) · (c) never; SYSTEM mode lends only while the machine happens to be awake | **(a)**; it grants one action to one user; the keep-awake energy cost is shown in `asom status` |
| **LD-7** | CUDA on an NVIDIA Dell | (a) none in mesh-1; Vulkan on NVIDIA · (b) a separate `asom-desktop-cuda` package **linked against the system's CUDA runtime, bundling nothing** · (c) bundle `libcudart`/`libcublas` under the EULA [LF36] | **(a) then (b)** after the Dell inventory and a measured CUDA-vs-Vulkan gap; (c) only after a legal read of the EULA's "consistent terms" condition |
| **LD-8** | Standalone `asom-bench` CLI for Linux (design AF-1, D7) | (a) drop it for Linux; `asom bench` inside the node only · (b) keep it | **(a)**: MLPerf Client already covers Ubuntu 24.04 [LF37]; directive C says coverage is not a differentiator |
| **LD-9** | Distribution channel | (a) GitHub Releases: tarballs + deb + rpm, `SHA256SUMS` signed by the owner's offline OpenPGP key, GitHub attestations · (b) (a) plus a static apt/dnf repository on GitHub Pages · (c) distro repositories | **(a)** first; (b) when there is a second user. Never an in-app update check |
| **LD-10** | Deck lending on battery | (a) hard NO · (b) off by default, overridable | **(a)** |
| **LD-11** | Weak-host exposure of an overlay-bound listener [LF39] | (a) document, print the nftables rule, check it read-only in `asom doctor`, test in CI · (b) a small JNI helper to set `SO_BINDTODEVICE` (privilege requirements unverified here) · (c) accept silently | **(a)**, and add one sentence to the IC-1 documentation line (design §8.2). Revisit (b) with evidence |
| **LD-12** | How the node learns of imminent sleep | (a) in-house read-only D-Bus reader (~400 lines, vectors) · (b) dbus-java 5.x (MIT; sunset line) as a new dependency · (c) a `gdbus monitor` child process · (d) none: no pre-sleep drain | **(a)**; (d) is the automatic fallback when (a) fails |

#### 11.2 Corrections this section asks the reviser to make in the design brief

1. §3.1 Linux/Deck columns: add the SteamOS "never a block lock" rule [LF05] and the polkit precondition for SYSTEM-mode keep-awake [LF13]; the Deck's PF role is "Desktop Mode, terminal frontmost" and PA is "Game Mode, opt-in, invisible".
2. §3.2 item 2 ("one sleep rule on every platform"): false on SteamOS; amend as §3.3 here. Item 1: the wire carries `fsm` only (R2-DIRECTIVES-5).
3. §7.1 Deck row and A12b: replace the FP32-FLOPS basis with §6.3's LF35-based derivation, all labelled ESTIMATE.
4. §0.3 / §6 "desktop coverage": confirmed not a differentiator for Linux (MLPerf Client on Ubuntu 24.04 [LF37]); AF-1's `asom-bench` CLI per LD-8.
5. IC-1 documentation: the weak-host exposure (§4.1).
6. [A10]: the Linux half is now settled: Headscale does not stop the Linux client's log upload; `TS_NO_LOGS_NO_SUPPORT=true` does [LF10]. The phone half stays open.
7. Lab L0.3's "Linux probe parsers": one home only (§7.3).
8. §3.1 key tier for Linux: add T1 (systemd-creds sealing) and state that the Deck has no TPM tier ([LF03]).

#### 11.3 Risk table

| # | Risk | Severity | Mitigation | Residual |
|---|---|---|---|---|
| LR1 | **Deck fake sleep**: a block lock in Game Mode leaves the Deck running dark and hot, possibly in a bag [LF05] | critical | `SteamOsPolicy`: never a block lock on SteamOS, in any session; unit test on the policy; DV-D4 | Steam or SteamOS behaviour changes; the owner's own tools can still take locks |
| LR2 | **Prompts or tokens leak into the journal, core dumps or swap** | high | `StandardOutput/StandardError=null`, `LimitCORE=0`, `-XX:-CreateCoredumpOnCrash`, `ErrorFile` in the state dir, `MemorySwapMax=0`; stdout-capture and journal-grep tests (DL2) | USER-mode swap hygiene depends on delegation [LA21]; freed RAM is not scrubbed |
| LR3 | **Same-uid compromise** on USER-mode nodes (every Deck game) | high | label `shared-uid` everywhere; SYSTEM mode on the Dell; T1 at rest where possible | a same-uid attacker can impersonate a USER-mode node (T17(g)) |
| LR4 | **A game stutters, or the owner's work slows, while the node lends** | high (UX) | 2 s drain on the Deck; contention thresholds; `Nice`/`CPUWeight`/`IOSchedulingClass`; small `n_ubatch` | one graph compute of overlap [LA17]; no GPU priority control on Linux |
| LR5 | **SYSTEM-mode keep-awake silently fails** (polkit denies the lock [LF13]) | medium | LD-6 rule; the node tries and reports `keep-awake: unavailable (polkit)`; DV-L3 | distro polkit policies differ from upstream |
| LR6 | **Mode switch or logout kills the USER-mode node** | medium | linger on the Deck; drain on SIGTERM; DV-D2 | depends on LA02 |
| LR7 | **Steam sleeps a docked Deck mid-request** | medium | delay lock + PrepareForSleep drain; requester failover within dial budgets; the owner sets Steam's plugged-in sleep to Disabled | if LA01 is false, no advance notice |
| LR8 | **Weak-host exposure** of the node certificate to the LAN [LF39] | medium | LD-11; CI netns test | until the owner applies the rule |
| LR9 | **Overlay log upload** continues because the opt-out was lost (Deck installer resets `override.conf` [LF08]) or never set under Headscale [LF10] | medium | `asom doctor overlay` instructions; D8 disclosure says "not verified by asom" | asom cannot check root's daemon environment |
| LR10 | **Packaging auto-starts or auto-enables the node** (violating boot-start OFF) | medium | nFPM scripts never enable/start; CI asserts `disabled` after install; jpackage service installers rejected [LF26] | a user's own automation |
| LR11 | **GPU claims without GPU evidence** | high (honesty) | CI runs Vulkan only on a software driver and asserts no timing; every Deck/Dell number labelled ESTIMATE until DV-D6/DV-L5 | — |
| LR12 | **Native ABI breakage across distros** (glibc, Vulkan loader) | medium | build on `ubuntu-22.04`; container matrix incl. `archlinux` as a SteamOS proxy; CPU fallback when Vulkan fails to load | SteamOS itself is never in CI |
| LR13 | **TPM clear or board swap loses a T1/T2 identity** | low–medium | documented; re-pair flow; T0 export is deliberately not offered | re-pairing effort |
| LR14 | **Benchmark work duplicates MLPerf Client** on Linux [LF37] | medium | LD-8: no standalone Linux bench; `asom bench` only feeds the router | users may still compare numbers |
| LR15 | **JDK 17 end of support** before D-v2 ships [LF27] | medium | LD-3 (ship 21); double conformance run | build/runtime skew bugs outside the vectors |
| LR16 | **In-house D-Bus reader bug** blocks or crashes the node | medium | read-only, one signal, 1 MiB cap, fuzzed vectors; failure degrades to "no pre-sleep drain" | a malformed but accepted message from the system bus (trusted peer) |
| LR17 | **Firewalld opens high ports** on Fedora Workstation [LF16] | low–medium | bind only chosen addresses; `asom doctor` reports the zone | exposure of those addresses on the LAN |
| LR18 | **`/var` exhaustion on the Deck** (~256 MB slots [LF04]) | low | nothing written to `/var` in USER mode | — |
| LR19 | **Invisible lending in Game Mode** weakens the watched-object ethos | medium | LD-2 opt-in with explicit copy; requester-side headers and ledgers; `asom status` over SSH | the Deck's own screen shows nothing |

**What this section as a whole does NOT guarantee.** It does not measure anything: every speed is an estimate until DV-D6/DV-L5. It does not make a Linux lender "always-on": sleep policy belongs to GNOME, Plasma, logind or Steam. It does not make a USER-mode node private from its own user's other programs. It does not verify the overlay's logging. It relies on 25 assumptions (LA01–LA25), each with the test that settles it.
