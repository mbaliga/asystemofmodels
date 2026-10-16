# Device checklist for Ubuntu Touch (owner, NEEDS-DEVICE-VALIDATION)

Every item below is **open** until the owner runs it on a real device and writes the result here. Nothing in the repository, and
nothing a CI job prints, closes any of them. Device: a Fairphone 4 or 5, a Volla phone or a Volla Tablet on Ubuntu Touch 24.04-2.x
(24.04-1.x for DV-UT02). Source: `docs/design/mesh/platforms/ubuntu-touch.md` section 9. Result column: `open` until filled.

Setup once: install the click by `clickable install` over adb (or `pkcon install-local --allow-untrusted <file>` from Terminal or
SSH). It is UNSIGNED and not for release. For the SSH steps enable SSH under Developer Mode.

| ID | Check | Exact method | Expect | Result |
|---|---|---|---|---|
| **DV-UT01** | **S-UT1: the JVM self-test under real confinement** (`UT0.6`) | Install the click, open asom, tap **Run self-test** on the Status screen. Then over SSH: `sudo dmesg \| grep 'apparmor="DENIED"' \| grep xyz.mdhv.asom.ut` | A self-test block that begins `self-test: ok` (TLS 1.3, ALPN, vectors M01 105 / M02 18 / M03 71, ledger appends, RSS). No denial that breaks it; list the others. If it fails: `BLOCKED(S-UT1: <denial or crash>)` and UT-D2 moves to the Rust fallback | open |
| DV-UT02 | A 24.04-1.x click on a 24.04-2.x device | Install and launch on a 2.x device; `click list \| grep asom` | installs and starts (assumption UA02) | open |
| DV-UT03 | The JVM child freezes with the app | Start asom, switch to another app, then `ps -o pid,stat,cmd -C java` over SSH | state `T` within about 2 s | open |
| DV-UT04 | The state change is delivered before SIGSTOP (UA03) | Compare the node's `interrupted` state and the moment of DV-UT03 with a timestamped log (`journalctl` or the app's status line) | `lifecycle` arrives before the stop; if not, the node's watchdog covered it | open |
| DV-UT05 | `keepDisplayOn` during a stream (UA04) | With a UT-1 requester (later) and a desktop lender, stream for 3 minutes without touching the screen. UT-0 has no requester, so this item waits for UT-1 | the screen stays on; releases within about 1 s of the end | open |
| DV-UT06 | Windowed mode and the lock screen (UA17, L-UT3) | A tablet with keyboard and touchpad; lock the screen mid-session | the node treats it as locked (no new dials); see ERR-UT-FSM-5 | open |
| DV-UT07 | Quiescence | 30 minutes with asom open on Status and the overlay **off**; capture at the gateway. Positive control: open Peers, expect a SYN within 5 s | zero SYNs to peer addresses while idle. With an overlay on, an on-device capture is needed or this stays open | open (UT-1) |
| DV-UT08 | A 20-request script to a lender; both ledgers exported and joined; `ss -ltnup` on the phone | SSH | no asom socket | open (UT-1) |
| DV-UT09 | Overlay interface and `peerPath` | `ip -br addr`, row values | `tailscale0` is `overlay`, `wlan0` is `lan` | open (UT-1) |
| DV-UT10 | Metered detection | cellular data on, Wi-Fi off | no dial | open (UT-1) |
| DV-UT11 | Key-file readability from Terminal (the stated limit) | Terminal: `stat ~/.local/share/xyz.mdhv.asom.ut/identity/nik.p8` | readable by the user: the limit is real and is stated in the app | open (UT-1) |
| DV-UT12 | Firewall state | `sudo nft list ruleset; sudo iptables -S` | record it | open |
| DV-UT13 | Install-path signature enforcement | install a click with a corrupted debsig, if any | record it | open |
| DV-UT14 | Libertine spike | in a Libertine noble container: `asom-node --self-test` | record it | open |
| DV-UT15 | No peer label on loopback (UA18) | a test program: `SO_PEERSEC` on an accepted loopback socket | nothing or `unconfined` | open |
| DV-UT16 | UT-2 only: listener bound to the Wi-Fi address while frontmost | as M1's W08 | as M1 | open (UT-2) |

Also record on the first run (not gates): the size of the installed click, the JVM's start time cold and warm, the RSS the self-test
prints against the 100 to 180 MB estimate (UA11), and whether the Status screen's About block shows the two checksums.
