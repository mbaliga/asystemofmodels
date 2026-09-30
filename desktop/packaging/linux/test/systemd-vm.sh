#!/usr/bin/env bash
# systemd-vm.sh (PLATFORM_PLAN 3, DL2 gate 3; linux.md 10.3).
#
# EVIDENCE LABEL: CI-ONLY. Needs REAL systemd (PID 1), real logind and (for the block-lock cases) polkit, on a disposable
# VM: a hosted runner. It was WRITTEN in a container without systemd and has NEVER RUN there; its result is
# "CI (hosted VM) evidence", NOT DEVICE EVIDENCE, and says nothing about real suspend/resume (NEEDS-DEVICE-VALIDATION).
# It changes the machine: creates user `asom`, installs under /opt/asom, /usr/lib/systemd/system and
# /usr/share/polkit-1/rules.d. Run it only on a throwaway VM, as root.
#
# Usage: sudo systemd-vm.sh --app <dir of `:node:installDist`, e.g. desktop/node/build/install/asom-node> [--keep]
#
# What it checks (each line prints PASS: or FAIL:):
#   1  systemd-analyze verify on the installed system unit prints nothing
#   2  after install the unit is DISABLED and INACTIVE (nothing enables or starts it)
#   3  after an explicit start the unit is active, runs as user asom, holds no listening socket, has the
#      StateDirectory 0700 and RuntimeDirectory 0750, LimitCORE=0, MemorySwapMax=0, StandardOutput=null
#   4  `asom status --json --mode=system`, run as user asom under systemd-run, reports host dedicated-user.
#      This is the LOCAL SNAPSHOT (desktop/ERRATA.md ERR-CLI-1): the node does not yet start a control socket, so the
#      plan's "asom status | jq -r .host as a group-asom user" cannot be asked of the live process (BLOCKED, see ERR-DL2-4)
#   5  a DELAY lock taken as user asom shows in `systemd-inhibit --list` as delay
#   6  a BLOCK lock without the polkit rule is refused: "keep-awake: unavailable (polkit)"
#   7  with the polkit rule installed the BLOCK lock is held and listed as block; withdrawing the rule refuses it again
#   8  with --deck (the SteamOS rule) a block lock is refused by policy even WITH the polkit rule installed
#   9  journal-hygiene.sh prints "PASS: 0 token matches, 0 ledger rows"
#  10  stopping the unit leaves it inactive
set -u

app=""
keep=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --app) app="$2"; shift 2 ;;
    --keep) keep=1; shift ;;
    -h|--help) sed -n '2,27p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pkg="$(cd "$here/.." && pwd)"
[ -n "$app" ] || { echo "ERROR: --app <installDist dir> is required"; exit 2; }
app="$(cd "$app" && pwd)"

[ "$(id -u)" = "0" ] || { echo "ERROR: run as root on a disposable VM"; exit 2; }
[ -d /run/systemd/system ] || { echo "ERROR: systemd is not PID 1 here; this script is CI-ONLY (needs a real systemd)"; exit 2; }
[ -x "$app/bin/asom-node" ] && [ -x "$app/bin/asom" ] || { echo "ERROR: $app is not a :node:installDist directory"; exit 2; }
command -v java >/dev/null 2>&1 || { echo "ERROR: java is not on PATH"; exit 2; }
java_bin="$(readlink -f "$(command -v java)")"
java_home="$(dirname "$(dirname "$java_bin")")"

pass=0
fail=0
ok()   { echo "PASS: $1"; pass=$((pass + 1)); }
bad()  { echo "FAIL: $1"; fail=$((fail + 1)); }
check() { # check "<description>" <command...>
  local d="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$d"; else bad "$d"; fi
}

ver="0.0.0-ci"
dest="/opt/asom/$ver"
rule="/usr/share/polkit-1/rules.d/50-asom-inhibit.rules"

dropin="/etc/systemd/system/asom.service.d/ci-java.conf"

cleanup() {
  systemctl stop asom >/dev/null 2>&1 || true
  rm -f "$rule"
  if [ "$keep" = "0" ]; then
    rm -f /usr/lib/systemd/system/asom.service "$dropin"
    rmdir /etc/systemd/system/asom.service.d 2>/dev/null || true
    rm -rf /opt/asom
    systemctl daemon-reload >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# ---- install (what the package will do at DL3; here by hand) --------------------------------------------------------
systemd-sysusers "$pkg/sysusers.d/asom.conf" || { echo "ERROR: systemd-sysusers failed"; exit 2; }
id asom >/dev/null 2>&1 || { echo "ERROR: user asom was not created"; exit 2; }
mkdir -p /opt/asom
rm -rf "$dest"
cp -a "$app" "$dest"
chown -R root:root "$dest"
ln -sfn "$ver" /opt/asom/current
install -m 0644 "$pkg/systemd/asom.service" /usr/lib/systemd/system/asom.service
systemctl daemon-reload

# 1
out="$(systemd-analyze verify /usr/lib/systemd/system/asom.service 2>&1)"; rc=$?
if [ "$rc" = "0" ] && [ -z "$out" ]; then ok "1 systemd-analyze verify prints nothing"; else bad "1 systemd-analyze verify: rc=$rc output: $out"; fi

# CI-only environment: the launcher needs to find this runner's JDK, which is not on systemd's PATH. The drop-in is added
# AFTER check 1, so check 1 verifies the shipped unit file untouched.
install -d /etc/systemd/system/asom.service.d
printf '[Service]\nEnvironment=JAVA_HOME=%s\n' "$java_home" > "$dropin"
systemctl daemon-reload

# 2
[ "$(systemctl is-enabled asom 2>/dev/null)" = "disabled" ] && ok "2 unit is disabled after install" || bad "2 unit is not disabled after install: $(systemctl is-enabled asom 2>&1)"
[ "$(systemctl is-active asom 2>/dev/null)" = "inactive" ] && ok "2 unit is inactive after install" || bad "2 unit is not inactive after install"

# 3
unit_start_stamp="$(date '+%Y-%m-%d %H:%M:%S')"
systemctl start asom || bad "3 systemctl start asom failed"
sleep 3
[ "$(systemctl is-active asom 2>/dev/null)" = "active" ] && ok "3 unit is active after an explicit start" || bad "3 unit is not active: $(systemctl status asom --no-pager 2>&1 | tail -5)"
pid="$(systemctl show asom -p MainPID --value)"
if [ -n "$pid" ] && [ "$pid" != "0" ] && [ "$(ps -o user= -p "$pid" | tr -d ' ')" = "asom" ]; then ok "3 the node runs as user asom (pid $pid)"; else bad "3 the node does not run as asom (pid '$pid')"; fi
listeners="$(ss -H -lntup 2>/dev/null | grep -c "pid=$pid," || true)"
unix_listeners="$(ss -H -lxp 2>/dev/null | grep -c "pid=$pid," || true)"
[ "${listeners:-0}" = "0" ] && [ "${unix_listeners:-0}" = "0" ] && ok "3 the running node holds no listening socket (TCP, UDP or Unix)" || bad "3 the node holds $listeners inet and $unix_listeners unix listeners"
[ "$(stat -c '%a %U' /var/lib/asom)" = "700 asom" ] && ok "3 /var/lib/asom is 0700 asom" || bad "3 /var/lib/asom is $(stat -c '%a %U' /var/lib/asom)"
[ "$(stat -c '%a %U' /run/asom)" = "750 asom" ] && ok "3 /run/asom is 0750 asom" || bad "3 /run/asom is $(stat -c '%a %U' /run/asom)"
show="$(systemctl show asom -p LimitCORE -p MemorySwapMax -p StandardOutput -p StandardError)"
echo "$show" | grep -qx 'LimitCORE=0' && ok "3 LimitCORE=0" || bad "3 LimitCORE: $show"
echo "$show" | grep -qx 'MemorySwapMax=0' && ok "3 MemorySwapMax=0" || bad "3 MemorySwapMax: $show"
echo "$show" | grep -qx 'StandardOutput=null' && echo "$show" | grep -qx 'StandardError=null' && ok "3 StandardOutput=null and StandardError=null" || bad "3 stdio: $show"

# 4 (local snapshot, ERR-CLI-1 / ERR-DL2-4)
snap="$(systemd-run --quiet --wait --pipe --collect --uid=asom --gid=asom --setenv=JAVA_HOME="$java_home" "$dest/bin/asom" status --json --mode=system 2>&1)"
host="$(printf '%s\n' "$snap" | grep '^{' | head -1 | sed -n 's/.*"host":"\([^"]*\)".*/\1/p')"
[ "$host" = "dedicated-user" ] && ok "4 asom status (local snapshot, as user asom under systemd) reports host=dedicated-user" || bad "4 host='$host' output: $snap"

# 5 to 8: locks, through the real Inhibitor in a diagnostic (InhibitorProbeMain), as user asom
probe() { # probe <kind> <seconds> [--deck]  -> runs in the background under systemd-run, output to $1.out
  local kind="$1" secs="$2"; shift 2
  systemd-run --quiet --wait --pipe --collect --uid=asom --gid=asom \
    "$java_bin" -cp "$dest/lib/*" xyz.mdhv.asom.desktop.linux.power.InhibitorProbeMainKt "--kind=$kind" "--seconds=$secs" "$@"
}
wait_list() { # wait_list <regex>: poll `systemd-inhibit --list` for up to 20 s
  local i
  for _ in $(seq 1 40); do
    if systemd-inhibit --list --no-pager 2>/dev/null | grep -E "$1" >/dev/null; then return 0; fi
    sleep 0.5
  done
  return 1
}

rm -f "$rule"
probe delay 12 >/tmp/asom-probe-delay.out 2>&1 &
pdelay=$!
if wait_list 'asom.*sleep.*delay|asom.*delay'; then ok "5 a delay lock held by asom is listed as delay"; else bad "5 no delay lock line for asom in: $(systemd-inhibit --list --no-pager 2>&1)"; fi
wait "$pdelay"
grep -q 'state=HELD' /tmp/asom-probe-delay.out && ok "5 the Inhibitor reported the delay lock HELD" || bad "5 probe said: $(cat /tmp/asom-probe-delay.out)"

probe block 3 >/tmp/asom-probe-block-norule.out 2>&1
if grep -q 'state=REFUSED' /tmp/asom-probe-block-norule.out && grep -q 'keep-awake: unavailable (polkit)' /tmp/asom-probe-block-norule.out; then
  ok "6 without the polkit rule a block lock is refused: keep-awake: unavailable (polkit)"
else
  bad "6 block lock without the rule: $(cat /tmp/asom-probe-block-norule.out)"
fi

install -D -m 0644 "$pkg/polkit/50-asom-inhibit.rules" "$rule"
sleep 2
probe block 12 >/tmp/asom-probe-block-rule.out 2>&1 &
pblock=$!
if wait_list 'asom.*block'; then ok "7 with the polkit rule a block lock held by asom is listed as block"; else bad "7 no block lock line for asom in: $(systemd-inhibit --list --no-pager 2>&1)"; fi
wait "$pblock"
grep -q 'state=HELD' /tmp/asom-probe-block-rule.out && ok "7 the Inhibitor reported the block lock HELD" || bad "7 probe said: $(cat /tmp/asom-probe-block-rule.out)"

probe block 3 --deck >/tmp/asom-probe-deck.out 2>&1
grep -q 'state=REFUSED' /tmp/asom-probe-deck.out && grep -q 'never taken on this host' /tmp/asom-probe-deck.out && ok "8 the SteamOS rule refuses a block lock by policy even with the polkit rule installed" || bad "8 deck rule: $(cat /tmp/asom-probe-deck.out)"

rm -f "$rule"
sleep 2
probe block 3 >/tmp/asom-probe-block-withdrawn.out 2>&1
grep -q 'state=REFUSED' /tmp/asom-probe-block-withdrawn.out && ok "7 withdrawing the polkit rule refuses the block lock again" || bad "7 after withdrawing the rule: $(cat /tmp/asom-probe-block-withdrawn.out)"

# 9
hy="$("$here/journal-hygiene.sh" --unit asom.service --since "$unit_start_stamp" --canary "ASOMCANARY-$$-vm" 2>&1)"; hrc=$?
echo "$hy" | tail -3
[ "$hrc" = "0" ] && [ "$(echo "$hy" | tail -1)" = "PASS: 0 token matches, 0 ledger rows" ] && ok "9 journal hygiene" || bad "9 journal hygiene rc=$hrc: $hy"

# 10
systemctl stop asom
state_after_stop="$(systemctl is-active asom 2>/dev/null)"
[ "$state_after_stop" = "inactive" ] && ok "10 the unit is inactive after stop" || bad "10 the unit is not inactive after stop: is-active=$state_after_stop $(systemctl show asom -p Result -p ExecMainStatus 2>/dev/null | tr '\n' ' ')"

echo "systemd-vm: $pass passed, $fail failed (CI-ONLY: real systemd and real logind on a hosted VM; NOT DEVICE EVIDENCE; real suspend/resume is NEEDS-DEVICE-VALIDATION)"
[ "$fail" = "0" ]
