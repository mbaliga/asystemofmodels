#!/usr/bin/env bash
# AM14 demonstration (macos.md 5, 10.3 MC2): the `security` command-line route to the login keychain is WORSE than a 0600 file, and is
# rejected as a key tier for that reason. The claim: an item stored with `security add-generic-password` trusts /usr/bin/security itself,
# so any unrelated same-user process can read it silently with `security find-generic-password -w`, and the secret also crossed a
# command line when it was stored.
#
# It runs on macOS only, against a TEMPORARY keychain file (`security create-keychain`): the login keychain and the keychain search
# list are never touched, every command names the temporary keychain explicitly, and the file is deleted at the end.
#
# Prints, and exits 0 only for, the line `read without prompt: yes` (the reader is a separate process with an empty environment that
# did not create the item, and it got the secret back with no prompt). Exit 1: the read needed a prompt or failed (the assumption
# is then FALSE on this macOS and macos.md 5's reasoning (3) must be revisited by the owner). Exit 2: the demo could not run (no
# `security`, the keychain could not be made). Exit 3: not macOS.
#
# A control runs first: an item stored with an EMPTY trusted-application list (`-T ""`) must NOT be readable the same way, which shows
# the reader can tell a prompt from no prompt. The control is informational; its result is printed but does not decide the exit code.
#
# Test hooks (never set in CI; used only by desktop/packaging/macos/macplatform tests with a fake `security` on Linux):
#   ASOM_DEMO_SECURITY=<path>          the tool to run instead of /usr/bin/security
#   ASOM_DEMO_ALLOW_NON_DARWIN=1       do not refuse on a non-macOS system
#   ASOM_DEMO_EXTRA_ENV="A=1 B=2"      variables the reader keeps despite `env -i` (the fake needs its state directory)
#   ASOM_DEMO_TIMEOUT=<seconds>        the watchdog on each read (default 20)
set -uo pipefail

security=${ASOM_DEMO_SECURITY:-/usr/bin/security}
if [ "$(uname -s)" != "Darwin" ] && [ "${ASOM_DEMO_ALLOW_NON_DARWIN:-}" != "1" ]; then
  echo "NOT RUN: demo-keychain-cli-weakness.sh needs macOS's security tool (this is $(uname -s))"
  exit 3
fi
[ -x "$security" ] || { echo "CANNOT RUN: $security is not executable"; exit 2; }

work=$(mktemp -d "${TMPDIR:-/tmp}/asom-am14.XXXXXX") || { echo "CANNOT RUN: no temporary directory"; exit 2; }
kc="$work/asom-am14-demo.keychain-db"
pw="asom-am14-$$-$RANDOM"
# shellcheck disable=SC2329  # invoked by the EXIT trap
cleanup() {
  "$security" delete-keychain "$kc" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# a watchdog for the reader: a read that waits for a prompt in a headless job must not hang the job
with_alarm() { perl -e 'alarm shift; exec @ARGV' "$@"; }

echo "AM14 demo: a temporary keychain at $kc (the login keychain and the search list are not touched)"
"$security" create-keychain -p "$pw" "$kc" >/dev/null 2>&1 || { echo "CANNOT RUN: create-keychain failed"; exit 2; }
"$security" set-keychain-settings "$kc" >/dev/null 2>&1 || true   # no auto-lock timeout
"$security" unlock-keychain -p "$pw" "$kc" >/dev/null 2>&1 || { echo "CANNOT RUN: unlock-keychain failed"; exit 2; }

# shellcheck disable=SC2329  # exported into the watchdog shells with declare -f
read_back() {  # $1 service; prints "yes" or "no (exit N)"
  local out rc
  # shellcheck disable=SC2086  # the extra variables are a space-separated list on purpose (test hook)
  out=$(env -i HOME="${HOME:-/}" ${ASOM_DEMO_EXTRA_ENV:-} "$security" find-generic-password -a asom-am14 -s "$1" -w "$kc" 2>/dev/null </dev/null)
  rc=$?
  if [ "$rc" = 0 ] && [ "$out" = "$secret" ]; then echo "yes"; else echo "no (exit $rc)"; fi
}

secret="asom-am14-demo-value-$RANDOM$RANDOM"

# control: an item that trusts nobody
"$security" add-generic-password -a asom-am14 -s asom-am14-control -w "$secret" -T "" "$kc" >/dev/null 2>&1 \
  || { echo "CANNOT RUN: add-generic-password (control) failed"; exit 2; }
control=$(with_alarm "${ASOM_DEMO_TIMEOUT:-20}" bash -c "$(declare -f read_back); security='$security'; kc='$kc'; secret='$secret'; read_back asom-am14-control")
echo "control (item stored with -T \"\", trusts no application): read without prompt: ${control:-no (timed out)}"

# the claim: an item stored the ordinary way
echo "stored: security add-generic-password -w <secret> (the secret crossed a command line, visible in ps for that moment)"
"$security" add-generic-password -a asom-am14 -s asom-am14-service -w "$secret" "$kc" >/dev/null 2>&1 \
  || { echo "CANNOT RUN: add-generic-password failed"; exit 2; }
result=$(with_alarm "${ASOM_DEMO_TIMEOUT:-20}" bash -c "$(declare -f read_back); security='$security'; kc='$kc'; secret='$secret'; read_back asom-am14-service")
result=${result:-no (timed out)}
echo "read without prompt: $result"
case "$result" in
  yes) exit 0 ;;
  *) echo "AM14 NOT demonstrated on this macOS: the read needed a prompt or failed; the reasoning that rejects the security-CLI route must be revisited"; exit 1 ;;
esac
