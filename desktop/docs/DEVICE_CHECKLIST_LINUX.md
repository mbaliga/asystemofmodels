# Device checklist: Linux (Steam Deck and the Dell)

Source: `docs/design/mesh/platforms/linux.md` section 10.4 (the DV-D and DV-L rows are copied from it, word for word in
the command and expected columns), PLATFORM_PLAN section 3 (NDV list), plus the packaging items this track added (marked
**added by DL3**, not in the spec).

**Every item below is `NEEDS-DEVICE-VALIDATION` until the owner pastes real output.** Nothing here was run on a Steam
Deck, a Dell, real systemd, real logind, a real GPU or a real suspend. Container (LAB) and hosted-runner (CI-ONLY) results
never close an item of this list. The "expected" column is what a PASS looks like, not a result.

How to record an item: paste the command, the full output and the date next to the item (your own notes are fine), then
change its Status cell. Leave a failing or surprising result in place; a fix that hides it is worse than the result.

Commands run as the user shown. `deck` is the default SteamOS user; use your own name elsewhere.

## Steam Deck (SteamOS; USER mode only: no root-managed install survives a SteamOS update, linux.md 3.1)

| ID | Command | Expected | Assumption or hazard | Status |
|---|---|---|---|---|
| DV-D1 | during a game, over SSH: `ps -o user= -p $(pgrep -f -n steamapps)` | `deck` | LA05: every game runs as the same uid as the node, so the USER unit has no sandbox against a game | NEEDS-DEVICE-VALIDATION |
| DV-D2 | `systemctl --user start asom`; switch Game -> Desktop -> Game; `systemctl --user show asom -p ActiveEnterTimestamp` before and after | unchanged timestamp. Repeat with and without `loginctl enable-linger deck` | LA02: the user manager may restart on a mode switch | NEEDS-DEVICE-VALIDATION |
| DV-D3 | after one SteamOS update: `loginctl show-user deck -p Linger` | `Linger=yes` | LA03: linger must survive updates | NEEDS-DEVICE-VALIDATION |
| DV-D4 | docked, lending ON, Game Mode; press power; afterwards `asom status --json \| jq .diag.lastSleep` | `{"announced":true,"drainedMs":<5000}`; the Deck is **actually** asleep (fan off, SSH unreachable) | LA01 and LF05: the hazard is a Deck that never sleeps because of a block lock. A real sleep with only a delay lock, no simulated sleep. The `.diag.lastSleep` field is not implemented in this wave (the node does not yet start its control socket): the checklist run needs the DL4+ node, or read the journal-free evidence the owner has (fan, SSH) | NEEDS-DEVICE-VALIDATION |
| DV-D5 | `cat /sys/class/drm/card*/device/gpu_busy_percent`; `grep drm-engine /proc/$(pgrep -f asom-node)/fdinfo/*` | a number; `drm-engine-gfx` lines | LA04: without a per-process GPU counter the GPU contention rule is off | NEEDS-DEVICE-VALIDATION |
| DV-D6 | `asom doctor gpu` | Vulkan device `RADV VANGOGH`, heap budgets >= 6 GiB for an 8B Q4_K_M | LA06. `asom doctor gpu` does not exist yet (DL4) | NEEDS-DEVICE-VALIDATION |
| DV-D7 | Desktop Mode, idle 20 min on AC, lending OFF | records whether Plasma suspends | LA08 | NEEDS-DEVICE-VALIDATION |
| DV-D8 | `sudo nft list ruleset` | empty | LA16: the Deck ships no firewall rules | NEEDS-DEVICE-VALIDATION |
| DV-D9 | start a game while a 300-token request streams; MangoHud frame-time log | node DRAINING within 2 s; worst frame spike recorded | LA17. R3-OVERCLAIM-4: the 2 s claim starts after the contention rule trips (about 10 s of load), so a light game may never trip it | NEEDS-DEVICE-VALIDATION |
| DV-D10 | `ip -br addr show tailscale0`; after re-running the Tailscale installer, `grep TS_NO_LOGS /etc/default/tailscaled` | an address in 100.64/10; the opt-out still present | LA19 | NEEDS-DEVICE-VALIDATION |
| DV-D11 | `cat /sys/fs/cgroup$(systemctl --user show asom -p ControlGroup --value)/memory.swap.max` | `0` | LA21: `MemorySwapMax=0` needs memory delegation to the user manager | NEEDS-DEVICE-VALIDATION |

## Dell (Ubuntu or Fedora, SYSTEM mode; OS and GPU unknown until DV-L1)

| ID | Command | Expected | Assumption or hazard | Status |
|---|---|---|---|---|
| DV-L1 | `cat /etc/os-release; lspci \| grep -Ei 'vga\|3d'; ls /dev/tpmrm0` | the inventory for LD-5 and LD-7 | decides which distro row of the install matrix matters and whether a TPM tier exists | NEEDS-DEVICE-VALIDATION |
| DV-L2 | USER mode with a desktop session: `asom lend on`; `systemd-inhibit --list` | a `block` line, or `keep-awake: unavailable (polkit)` in `asom status` | LA23. `asom lend on` is not implemented in this wave (lending stays OFF); the inhibitor mechanism itself is exercised by `systemd-vm.sh` on a VM | NEEDS-DEVICE-VALIDATION |
| DV-L3 | SYSTEM mode with LD-6: reboot to the GDM login screen, lending ON, wait 20 min, send a request from the phone | served (GDM's 15-min suspend held off) | LF15; design M1 gate (10) with "serve while logged out". Needs the mesh listener (DL6) | NEEDS-DEVICE-VALIDATION |
| DV-L4 | `sudo systemd-creds encrypt --with-key=host+tpm2 nik.p8 nik.cred`; start the unit | the node starts; `asom status` shows `identity: sealed (host+tpm2)` | the key store is not built (DL2 declares it not-yet-implemented) | NEEDS-DEVICE-VALIDATION |
| DV-L5 | the section 6.3 workload on CPU and Vulkan (and CUDA if LD-7(b)) | measured decode and prefill replacing A12c and A12d | needs the engine (DL4); NOV as well as NDV | NEEDS-DEVICE-VALIDATION |

## Both devices

| ID | What | How | Expected | Status |
|---|---|---|---|---|
| DV-B1 | real suspend and resume | with lending ON (when it exists) press power or close the lid on AC and on battery; resume | the node drains before sleep and does not serve during the resume window; nothing stays awake because of asom | NEEDS-DEVICE-VALIDATION |
| DV-B2 | hwmon labels | `for h in /sys/class/hwmon/hwmon*; do echo "$h $(cat $h/name)"; done`; `asom doctor capture` when it exists | the sensor names the thermal probe watches exist on this machine, or the probe says NO_THERMAL_SIGNAL | NEEDS-DEVICE-VALIDATION |
| DV-B3 | thermal thresholds | run a sustained CPU load, watch the band the node reports | the provisional thresholds (D-v2 calibration, NEEDS-OWNER-VALIDATION) hold the node before the machine is uncomfortable | NEEDS-DEVICE-VALIDATION |
| DV-B4 | fan noise | listen during a sustained lend | acceptable to the owner | NEEDS-DEVICE-VALIDATION |

## Packaging items (added by DL3; not in linux.md 10.4)

CI (`package-linux`, `install-matrix`) proves the packages build and install on a hosted VM and in containers. These items
are the device side: the same files on the owner's real machines.

| ID | Device | Command | Expected | Status |
|---|---|---|---|---|
| DV-P1 | Dell | `sudo apt install ./asom-desktop_<ver>_amd64.deb` (or `sudo dnf install ./asom-desktop-<ver>.x86_64.rpm`), then `systemctl is-enabled asom; systemctl is-active asom; getent passwd asom` | `disabled`, `inactive`, a line with `/usr/sbin/nologin` (or `/sbin/nologin`). Nothing runs. Then run `/opt/asom/current/bin/asom-node --mode=selftest` as your own user: `selftest: ... 0 failed` | NEEDS-DEVICE-VALIDATION |
| DV-P2 | Dell | `sudo apt remove asom-desktop`; `ls /opt/asom /var/lib/asom` | `/opt/asom` gone; `/var/lib/asom` and user `asom` kept (ERR-DL3-5). The postremove note says so | NEEDS-DEVICE-VALIDATION |
| DV-P3 | Deck | in Desktop Mode: `bash install.sh --archive asom-desktop-<ver>-linux-x86_64.tar.gz` (with `SHA256SUMS` beside it), then `~/.local/bin/asom status --json` | install.sh prints `checks run:` with the checksum matched and the signature line honest (`NOT CHECKED` for an unsigned build); `status` prints `"host":"foreground"` or `"user"`; `systemctl --user is-enabled asom` prints `disabled` | NEEDS-DEVICE-VALIDATION |
| DV-P4 | Deck | after one SteamOS update: `ls -l ~/.local/opt/asom/current ~/.local/bin/asom ~/.config/systemd/user/asom.service` | all three still present (LF03/LF04: SteamOS updates do not touch `$HOME`) | NEEDS-DEVICE-VALIDATION |
| DV-P5 | Deck | `./uninstall.sh` (from `~/.local/opt/asom/current/`), then `./uninstall.sh --purge` in a terminal and type a wrong phrase | first run removes the install and KEEPS state; the `--purge` prompt names the ledger and the identity (re-pairing) and a wrong phrase changes nothing | NEEDS-DEVICE-VALIDATION |
| DV-P6 | either | `lib/runtime/bin/java -cp probe.jar RuntimeProbe probe-ec.p12` (from the CI artifact `asom-runtime-probe-UNSIGNED-not-for-release`) | `jdk.net SO_PEERCRED OK`, `ES256 OK`, `TLS1.3 handshake OK` on the machine's real kernel and glibc | NEEDS-DEVICE-VALIDATION |

## Not on this list, on purpose

- Anything about signatures: every package here is UNSIGNED, not for release. The owner signs `SHA256SUMS` offline
  (linux.md 8.3); that step has no automated evidence.
- SteamOS itself in CI: the install matrix uses `archlinux` as a userland proxy only. It is not SteamOS.
- Lending, serving and mesh behaviour: nothing in this wave lends or listens. Those items come with DL4 to DL6.
