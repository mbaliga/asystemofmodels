# asom on Linux

**Status: scaffold (DL0 to DL2). It ships nothing, serves nothing and lends nothing.** `asom-node` starts OFF, binds no
socket, holds no lock and runs no engine. What exists is the host layer the later steps plug into: the owner control
socket (built, not started by the node), the mini D-Bus reader and sleep watcher, the keep-awake locks, the two systemd
units (shipped disabled), the `sysusers.d` file and the polkit rule. Authority: `docs/design/mesh/platforms/linux.md`
and `docs/design/mesh/PLATFORM_PLAN.md` section 3; deviations and open defects: [`../ERRATA.md`](../ERRATA.md).
Evidence in this repository is LAB (a container) and CI-ONLY (a hosted VM). None of it is device evidence.

## Modes

One binary, `asom-node --mode=system|user|foreground|selftest`. There is no default: a mode is always chosen on purpose.
The node refuses to run as root in every mode.

| Mode | Runs as | Paths | Start | Survives logout | `asom status` label |
|---|---|---|---|---|---|
| SYSTEM (recommended for an always-on lender) | system unit `asom.service`, user `asom` | `/opt/asom/<ver>` (root-owned), state `/var/lib/asom` (0700), socket `/run/asom/ctl.sock` | `sudo systemctl start asom` | yes | `dedicated-user` |
| USER | user unit `asom.service` (from `asom-user.service`) | `~/.local/opt/asom/<ver>`, state `$XDG_STATE_HOME/asom`, socket `$XDG_RUNTIME_DIR/asom/ctl.sock` | `systemctl --user start asom` | only with `loginctl enable-linger` | `shared-uid` |
| FOREGROUND | your terminal | as USER (falls back to a private `<state>/run` without a session) | `asom-node --foreground` | no | `foreground` |

**Nothing is enabled or started by any installer, in any mode.** Both units are shipped disabled (`systemctl is-enabled
asom` prints `disabled`). Enabling is your explicit choice: `sudo systemctl enable asom`, or `systemctl --user enable asom`
(start at login; start at boot additionally needs linger). Lending stays OFF even while the node runs.

## The units (`desktop/packaging/linux/systemd/`)

- `asom.service` (SYSTEM): `User=asom`, `Type=exec`, `StandardOutput=null`, `StandardError=null` (nothing the node prints
  reaches the journal), `LimitCORE=0` (no core dump, which could contain prompts), `MemorySwapMax=0` (anonymous memory
  never reaches swap), a sandbox (`ProtectSystem=strict`, `NoNewPrivileges`, `RestrictAddressFamilies`, ...) and
  `Nice=10`, `CPUWeight=20`, `IOSchedulingClass=idle` so a lender does not starve its own user.
- `asom-user.service` (USER): the same block minus what a user manager cannot apply. It has **no sandbox** beyond
  `NoNewPrivileges` and the seccomp-based restrictions: every process of the same uid can read the node's key, ledger
  and models, and can impersonate the node.
- `sysusers.d/asom.conf`: `u asom - "asom node" /var/lib/asom` (a system user with no login shell).
- What the SYSTEM sandbox does NOT do: it does not protect the node from root or from members of group `asom`, who may use
  the control socket.

## Talking to the node: `asom`

`asom status [--json]` is implemented. This wave it prints a **local snapshot** (`"source":"local-snapshot"`), never the
state of a running process, because the node does not yet start a control socket for the CLI to ask. Every other command
answers `NOT_IMPLEMENTED` (exit 3).

The control socket itself exists as `ControlServer` / `ControlClient` (AF_UNIX, in a 0700 directory) with `SO_PEERCRED`
in both directions:

- the server checks the peer before reading a byte: only the node's own user (USER, FOREGROUND), or the service user and
  members of group `asom` (SYSTEM), get an answer; anyone else gets one `FORBIDDEN` frame;
- the client reads the server's credentials first and refuses a server that is not `asom` (SYSTEM) or itself (USER), so an
  impostor that bound the path first receives no request;
- commands that need your confirmation (`pair-confirm`, `restore`, `lan-confirm`) are sent only after a confirmation read
  from the controlling terminal; the socket never supplies one.

It is built ahead of the owner rulings D23 and D25 and **is not started by `asom-node`**. Peer identity on Linux is a
user name (the JDK exposes names, not numbers); supplementary group membership is read from `/etc/group`, so a
membership held only in a directory service is not seen and is denied.

## Keep-awake and sleep

- While SERVING the node holds a `systemd-inhibit` **delay** `sleep` lock (allowed for every subject), released after it
  drains. `idle` locks are never taken.
- A **block** lock is tried only where the host allows it and polkit lets the process take it. Without the rule the
  node still serves, only while the machine happens to be awake, and `asom status` shows
  `keep-awake: unavailable (polkit)`. **On SteamOS a block lock is never taken** (see `STEAM_DECK.md`).
- The polkit rule (`polkit/50-asom-inhibit.rules`, install as `/usr/share/polkit-1/rules.d/50-asom-inhibit.rules`) grants
  `org.freedesktop.login1.inhibit-block-sleep` to the user `asom` and nothing else. Remove the file to withdraw it.
- **What a block lock costs:** the machine will not suspend for as long as lending is ON and conditions hold, so it draws
  power the whole time. GNOME and Plasma show "sleep is blocked by asom: asom is lending compute to your paired devices"
  when you try to suspend, and `systemd-inhibit --list` shows the lock.
- The lock is a child process on a stdin pipe; if the node dies (even by SIGKILL) the pipe closes and the lock is
  released instead of leaking.
- Sleep is detected in advance through logind's `PrepareForSleep` (a read-only D-Bus reader written in the node, no
  third-party dependency). If the bus cannot be read, `asom status` says `sleep not detected in advance` and the
  node notices a suspend afterwards from the gap between `/proc/uptime` and the monotonic clock (more than 10 s).
  Neither has been tried against a real suspend (NEEDS-DEVICE-VALIDATION).

## Journal

Nothing the node prints is meant to reach the journal (`StandardOutput=null`). `desktop/packaging/linux/test/journal-hygiene.sh`
checks this on a real journal (CI-ONLY). It cannot yet prove the request pipeline, because there is no request path that
carries a prompt at this step.

## Firewall and overlay

Not built in this wave (DL6). The node never applies a firewall rule; it will print the rules for you to apply. The
overlay network's own log opt-out (Tailscale `TS_NO_LOGS`) is your decision and is not verified to stop all log traffic.

## Testing this yourself

```sh
./gradlew -p desktop :node:test          # LAB: needs /usr/bin/dbus-daemon; ASOM_REQUIRE_DBUS=1 makes its absence a failure
sudo desktop/packaging/linux/test/systemd-vm.sh --app desktop/node/build/install/asom-node    # CI-ONLY, disposable VM, root
```

`systemd-vm.sh` changes the machine (creates user `asom`, writes under `/opt/asom`, `/usr/lib/systemd/system` and
`/usr/share/polkit-1/rules.d`). Run it only on a throwaway VM.
