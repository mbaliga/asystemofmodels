#!/usr/bin/env bash
# UT0.7 (CI only, ubuntu-24.04-arm): run the bundled aarch64 runtime's `--selftest` under an AppArmor profile generated from the
# PINNED UBports `ubuntu-sdk` template and the click's own policy groups, and list every denial the kernel logged.
#
#   ASOM_UT_STAGE=<dir with rt/, asom-ut-node.jar, ...> apparmor-ci/run-approx.sh [--policy 2404.1|2404.2]
#
# CI-APPROX - NOT DEVICE EVIDENCE. The runner's kernel (6.17, upstream AppArmor) is not the Halium kernel of a phone (5.4/5.10, UBports
# patches), the abstractions differ slightly (UA19), and nothing here exercises Lomiri, libhybris or the click launcher. A clean run
# here does NOT retire risk S-UT1; only DV-UT01 on a device does. This script FAILS, never skips: no aarch64 host, no enforcing
# AppArmor, a profile that does not load, or a self-test that does not pass all end the job with a non-zero exit (UA10).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
stage="${ASOM_UT_STAGE:-$root/runtime/stage}"
policy="2404.1"
while [ $# -gt 0 ]; do
  case "$1" in
    --policy) policy="$2"; shift 2 ;;
    *) echo "run-approx: unknown argument $1" >&2; exit 2 ;;
  esac
done
say() { printf '%s\n' "$*"; }
die() { say "run-approx: FAILED: $*"; say "CI-APPROX — NOT DEVICE EVIDENCE"; exit 1; }

[ "$(uname -m)" = "aarch64" ] || die "this host is $(uname -m): the bundled runtime is aarch64 and must run natively (job: ubuntu-24.04-arm)"
for f in rt/bin/java asom-ut-node.jar; do [ -e "$stage/$f" ] || die "$stage/$f is missing"; done
command -v aa-exec >/dev/null || die "aa-exec is missing (apt-get install apparmor-utils)"
sudo aa-status --enabled >/dev/null 2>&1 || die "AppArmor is not enabled on this kernel: the approximation would be vacuous, so the job fails instead of skipping"

profile="$here/.cache/xyz.mdhv.asom.ut.$policy.profile"
"$here/make-profile.sh" --policy "$policy" --out "$profile" || die "the profile could not be generated from the pinned template"

# The UBports template refers to @{CLICK_DIR}, which Ubuntu Touch declares in its own tunables; stock Ubuntu does not, and the parser
# refuses the profile ("Found reference to variable CLICK_DIR, but is never declared"). Declare it here, as on the phone.
grep -q '^@{CLICK_DIR}' "$profile" || { printf '@{CLICK_DIR}=/opt/click.ubuntu.com\n' | cat - "$profile" > "$profile.tmp" && mv "$profile.tmp" "$profile"; }

pkg="xyz.mdhv.asom.ut"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$root/manifest.json.in")"
name="${pkg}_asom_${version}"
click_dir="/opt/click.ubuntu.com/$pkg/$version"
uid="$(id -u)"
runtime_dir="/run/user/$uid"
confined_tmp="$runtime_dir/confined/$pkg"

work="$(mktemp -d)"
cleanup() {
  sudo apparmor_parser -R "$profile" >/dev/null 2>&1 || true
  sudo rm -rf "$click_dir" "$confined_tmp" "$runtime_dir/$pkg" 2>/dev/null || true
  rm -rf "$HOME/.local/share/$pkg" "$HOME/.cache/$pkg" "$HOME/.config/$pkg" "$work"
}
trap cleanup EXIT

sudo apparmor_parser -r "$profile" || die "the kernel refused to load the profile"
say "profile loaded: $name (policy $policy)"
sudo rm -rf "$click_dir"
sudo mkdir -p "$click_dir/lib/asom"
sudo cp -a "$stage/rt" "$click_dir/lib/asom/rt"
sudo cp "$stage/asom-ut-node.jar" "$root/runtime/jvm.options" "$click_dir/lib/asom/"
sudo mkdir -p "$confined_tmp" "$runtime_dir/$pkg"
sudo chown -R "$uid" "$runtime_dir/confined" "$runtime_dir/$pkg"
sudo chmod 0700 "$confined_tmp" "$runtime_dir/$pkg"

# The profile lets the app write only under the invoking user's real home (.local/share, .cache, .config of the package), so the
# real HOME is used; the test's own files stay outside it, in $work.
home="$HOME"
mapfile -t opts < <(HOME="$home" TMPDIR="$confined_tmp" python3 "$root/tools/expand_jvm_options.py" "$root/runtime/jvm.options")

mark="$(sudo dmesg 2>/dev/null | wc -l || echo 0)"
set +e
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS HOME="$home" TMPDIR="$confined_tmp" XDG_RUNTIME_DIR="$runtime_dir" \
  XDG_CACHE_HOME="$home/.cache" aa-exec -p "$name" -- "$click_dir/lib/asom/rt/bin/java" -Duser.name=confined "${opts[@]}" \
  -jar "$click_dir/lib/asom/asom-ut-node.jar" --selftest > "$work/out" 2> "$work/err"
rc=$?
set -e

denials="$( (sudo dmesg 2>/dev/null || sudo journalctl -k -o cat 2>/dev/null || true) | tail -n +"$((mark + 1))" | grep -E 'apparmor="DENIED"' | grep -F "profile=\"$name\"" || true)"
say "self-test exit code: $rc"
say "self-test stdout: $(cat "$work/out")"
if [ -s "$work/err" ]; then say "self-test stderr: $(head -c 600 "$work/err")"; fi
if [ -n "$denials" ]; then
  say "denials ($(printf '%s\n' "$denials" | wc -l)):"
  printf '%s\n' "$denials" | sed 's/^/  /'
else
  say "denials: none logged (dmesg may be restricted on this runner: absence of a log line is not proof)"
fi
say "CI-APPROX — NOT DEVICE EVIDENCE"
[ "$rc" -eq 0 ] || die "the self-test exited $rc under the profile"
grep -q '^{"selftest":"ok"' "$work/out" || die "the self-test line is not ok"
say "run-approx: OK (the profile loaded and the self-test passed under it; see the denial list above)"
