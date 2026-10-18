# The asom node on Windows: what exists after W0 to W2 (scaffold; nothing ships)

This is a builder's note, not a user guide: there is no installer, no signed binary and no owner command that turns lending on.
The full user guide is W7 (D-v2). What exists is the host code, its tests, and the CI that will run them on Windows.

## Two hosting modes on one install (windows.md 3.2)

| | user mode (the default) | service mode (opt-in, W4/W5) |
|---|---|---|
| seam mode | `HostMode.USER` (and `FOREGROUND`) | `HostMode.SYSTEM` |
| state | `%LOCALAPPDATA%\asom\` (non-roaming) | `%ProgramData%\asom\` |
| account | the logged-in user | virtual account `NT SERVICE\asom` |
| entry | `xyz.mdhv.asom.desktop.win.WinMainKt` | procrun calling `xyz.mdhv.asom.desktop.win.ServiceEntry.start/stop` |
| identity | mutex `Global\asom-node`, shared by both modes: the second node refuses to start and says why | same |

Layout under the base: `ledger\`, `registry\`, `node\` (identity), `run\ctl.sock`, `diag\`, `config.json`. The control socket path
must fit 107 UTF-8 bytes (`sun_path`).

## What a Windows machine can and cannot do as a lender

* It can lend while awake, with nobody present, on AC power (desktops), and it holds one power request with the reason
  "asom: lending compute to your paired devices" while SERVING (`powercfg /requests` lists it).
* **It cannot lend through sleep, lid close or Modern Standby, and no application can prevent user-initiated sleep.** asom never
  changes power settings. Display-off is treated as a drain on every Windows machine (windows ERRATA WIN-SLEEP-1).
* Presence: lending needs 10 minutes without keyboard or mouse input (or a locked workstation), and never with a full-screen
  app running, plus the 10-minute hold-down: about 20 minutes after your last input. Gamepad input does not count as activity.
* On battery, or with battery saver on, it does not lend.

## The listener never starts before you have consented (C16)

Windows Firewall blocks inbound by default, and a dismissed first-listen prompt creates block rules that override allow rules.
So the node does not listen until (1) you selected an interface, (2) that interface still has the address you selected, and
(3) a firewall allow rule that asom's own command creates exists and no block rule concerns asom. `asom mesh firewall print`
(when the command exists) prints the exact `New-NetFirewallRule` text; **asom never runs it**. You read it and run it in an elevated
PowerShell, or you do not. The rules are Private-profile only; a network set to Public blocks the listener.

The rule narrows who can open a TCP connection. It is not the authorisation boundary (the pinned mTLS verifier is). Any process
running as asom's program inherits it, and a Group Policy that disables local rule merge makes it inert.

## Key tiers

See `../README.md`. The tier is chosen at the first mesh enable and never earlier; T2 (TPM) then T1 (Software KSP); T0 (file) only
with `--key-tier file`. T2 is always shown as "hardware-backed (self-reported)".

## What Tailscale does on this machine (disclosure, D8)

"Tailscale on this PC uploads its own logs to Tailscale Inc. unless you add TS_NO_LOGS_NO_SUPPORT=true to
C:\ProgramData\Tailscale\tailscaled-env.txt and restart the Tailscale service. asom cannot see or ledger that traffic." `asom doctor`
reads that file (read-only) and prints this sentence unless the line is present. Whether the setting stops every upload under
Headscale is unverified.

## Crash data

The launcher passes `-XX:-CreateCoredumpOnCrash` (a minidump would hold prompt text). Windows Error Reporting exclusions for
`asom.exe`, `asom-service.exe` and `asom-cli.exe` are written by the MSI (W4), never for `java.exe`. Nothing asom writes leaves the
machine.

## Running the tests

`./gradlew -p desktop :packaging:windows:winplatform:test` (see the README). Off Windows the integration tests are skipped and
the summary line says how many. `NOT RUN` on Windows in the container that wrote this.
