#!/usr/bin/env bash
# lab-packaging-check.sh: everything about the Linux packaging that can be checked WITHOUT systemd and WITHOUT a container
# engine (PLATFORM_PLAN 3 DL3). Evidence label: LAB (one container or runner, one distro). NOT DEVICE EVIDENCE, and not the
# install-matrix (containers) or systemd-vm (real systemd); those are CI-ONLY.
#
#   lab-packaging-check.sh --dist <dir with the tar.gz, deb, SHA256SUMS, install.sh, uninstall.sh> --probe <dir from build-probe.sh>
#
# Families (each prints PASS:/FAIL: lines and a count; a family that ran zero checks is a FAILURE, never a pass):
#   install-tarball   install.sh into a scratch home as an unprivileged user; selftest and probe on the INSTALLED runtime;
#                     upgrade keeps `previous`; nothing enabled or started
#   install-refusals  bad checksum, no SHA256SUMS, wrong name, 9 hostile archives: each refused, nothing installed
#   uninstall         removes what install.sh made, keeps state, edited unit kept, --purge refuses without a terminal and on
#                     a wrong phrase, and deletes only on the exact phrase typed on a pty
#   deb-layout        dpkg-deb listing and control data; the deb extracted into a scratch root; selftest and probe there
#   package-scripts   postinstall/preremove/postremove run against stub systemctl and systemd-sysusers inside a private
#                     mount namespace (needs root and unshare; ASOM_REQUIRE_UNSHARE=1 makes a skip a FAILURE)
# Exit 0 only when every family ran and nothing failed.
# shellcheck disable=SC2010,SC2088
set -u

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pkg="$(cd "$here/.." && pwd)"
dist=""
probe=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dist) dist="${2:-}"; shift 2 ;;
    --probe) probe="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1"; exit 2 ;;
  esac
done
[ -d "$dist" ] && [ -f "$probe/probe.jar" ] || { echo "ERROR: --dist <dir> and --probe <dir> are required"; exit 2; }
dist="$(cd "$dist" && pwd)"
probe="$(cd "$probe" && pwd)"
tar_gz="$(ls "$dist"/asom-desktop-*-linux-*.tar.gz | head -1)"
deb="$(ls "$dist"/*.deb | head -1)"
[ -f "$tar_gz" ] && [ -f "$deb" ] || { echo "ERROR: $dist lacks the tar.gz or the deb"; exit 2; }
arch="$(uname -m)"

base_tmp="${TMPDIR:-/tmp}"
work="$(mktemp -d "$base_tmp/asom-lab.XXXXXX")"
chmod 755 "$work"
trap 'rm -rf "$work"' EXIT
# the unprivileged user must be able to read the probe, wherever the caller built it
mkdir -p "$work/probe"; cp "$probe/probe.jar" "$probe/probe-ec.p12" "$work/probe/"; chmod -R a+rX "$work/probe"; probe="$work/probe"
# likewise the installers, the archive and the pty helper: the runner's checkout is often not traversable by uid 65534
mkdir -p "$work/dist"; cp "$dist"/install.sh "$dist"/uninstall.sh "$dist"/SHA256SUMS "$tar_gz" "$work/dist/"; chmod -R a+rX "$work/dist"
cp "$here/pty-run.py" "$work/pty-run.py"; chmod a+rx "$work/pty-run.py"
dist="$work/dist"; tar_gz="$dist/$(basename "$tar_gz")"; ptyrun="$work/pty-run.py"

pass=0; fail=0
declare -A fam_n
fam=""
family() { fam="$1"; fam_n[$fam]=0; echo "== family: $fam"; }
ok()  { echo "PASS: $1"; pass=$((pass + 1)); fam_n[$fam]=$((fam_n[$fam] + 1)); }
bad() { echo "FAIL: $1"; fail=$((fail + 1)); fam_n[$fam]=$((fam_n[$fam] + 1)); }
expect() { # expect "<description>" <command...>
  local d="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$d"; else bad "$d"; fi
}

# ---- run as an unprivileged user (the node and the installers refuse root) ------------------------------------------
if [ "$(id -u)" = "0" ]; then
  as_user() { setpriv --reuid=65534 --regid=65534 --clear-groups "$@"; }
  owner() { chown -R 65534:65534 "$@"; }
else
  as_user() { "$@"; }
  owner() { :; }
fi
newhome() { local h; h="$(mktemp -d "$work/home.XXXXXX")"; chmod 755 "$h"; owner "$h"; printf '%s' "$h"; }
runh() { local h="$1"; shift; as_user env HOME="$h" JAVA_TOOL_OPTIONS= PATH="$PATH" "$@"; }

# =====================================================================================================================
family install-tarball
cmp -s "$dist/install.sh" "$pkg/install.sh" && cmp -s "$dist/uninstall.sh" "$pkg/uninstall.sh" && ok "the dist installers are byte-identical to the committed install.sh and uninstall.sh" || bad "a dist installer differs from the committed one"
tar -xzOf "$tar_gz" --wildcards '*/install.sh' | cmp -s - "$pkg/install.sh" && ok "the install.sh inside the tarball is the committed one" || bad "the tarball's install.sh differs"
H="$(newhome)"
out="$(runh "$H" bash "$dist/install.sh" --archive "$tar_gz" --home "$H" 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "install.sh exits 0" || bad "install.sh exit $rc: $out"
ver_dir="$(ls "$H/.local/opt/asom" | grep -v '^current$\|^previous$' | head -1)"
[ -n "$ver_dir" ] && ok "a version directory exists: $ver_dir" || bad "no version directory"
[ "$(readlink "$H/.local/opt/asom/current")" = "$ver_dir" ] && ok "current -> $ver_dir (relative link)" || bad "current is $(readlink "$H/.local/opt/asom/current")"
[ "$(readlink "$H/.local/bin/asom")" = "$H/.local/opt/asom/current/bin/asom" ] && ok "~/.local/bin/asom links into current" || bad "bin/asom link is $(readlink "$H/.local/bin/asom")"
cmp -s "$pkg/systemd/asom-user.service" "$H/.config/systemd/user/asom.service" && ok "the user unit equals the shipped asom-user.service" || bad "user unit differs"
[ -z "$(find "$H" -name '*.wants' -o -name '*.preset' 2>/dev/null)" ] && ok "no .wants or preset anywhere: nothing enabled" || bad "found enablement artefacts"
[ ! -e "$H/.local/state" ] && [ ! -e "$H/.local/share" ] && ok "state and data directories were not created by install" || bad "install created state or data"
echo "$out" | grep -q "Nothing was enabled or started" && ok "install output says nothing was enabled or started" || bad "install output lacks the statement"
echo "$out" | grep -q "signature NOT CHECKED: no detached signature" && ok "install output says the signature was NOT checked (unsigned artefact)" || bad "install output does not disclose the unchecked signature"
echo "$out" | grep -q "sha256 of .* matches" && ok "install output says the checksum matched" || bad "install output lacks the checksum line"

img="$H/.local/opt/asom/current"
st="$(runh "$H" "$img/bin/asom-node" --mode=selftest 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "packaged selftest exits 0" || bad "selftest exit $rc"
echo "$st" | grep -Eq '^selftest: [0-9]+ ok, [0-9]+ not-yet-implemented, 0 failed$' && ok "selftest reports 0 failed" || bad "selftest summary: $(echo "$st" | tail -1)"
n_ok="$(echo "$st" | sed -n 's/^selftest: \([0-9]*\) ok.*/\1/p')"
[ "${n_ok:-0}" -gt 0 ] && ok "selftest ran $n_ok ok checks (non-vacuous)" || bad "selftest ran no ok checks"
echo "$st" | grep -q 'not-yet-implemented\] control-socket' && ok "selftest reports the control socket as not-yet-implemented (honest, not flattered)" || bad "selftest does not report control-socket as not yet implemented"
echo "$st" | sed -n '1,3p' | sed 's/^/    | /'
root_out="$("$img/bin/asom-node" --mode=selftest 2>&1)"; root_rc=$?
if [ "$(id -u)" = "0" ]; then
  [ "$root_rc" = "78" ] && echo "$root_out" | grep -q "refusing to run as root" && ok "as root the packaged node refuses (exit 78)" || bad "root run exit $root_rc"
fi
status_out="$(runh "$H" "$img/bin/asom" status --json 2>&1 | grep '^{' | head -1)"
case "$status_out" in
  '{"host":"foreground","fsm":"OFF","listeners":[],"locks":[],'*) ok "asom status --json: host foreground, fsm OFF, no listeners, no locks" ;;
  *) bad "status shape: $status_out" ;;
esac
pr="$(runh "$H" "$img/lib/runtime/bin/java" -cp "$probe/probe.jar" RuntimeProbe "$probe/probe-ec.p12" 2>&1)"; rc=$?
echo "$pr" | sed 's/^/    | /'
[ "$rc" = "0" ] && ok "runtime probe on the INSTALLED runtime: jdk.net SO_PEERCRED, ES256, TLS1.3" || bad "runtime probe exit $rc"
for c in "jdk.net SO_PEERCRED OK" "ES256 OK" "TLS1.3 handshake OK"; do
  echo "$pr" | grep -q "$c" && ok "probe line present: $c" || bad "probe line missing: $c"
done

# a second version: upgrade keeps the old one and records `previous`
mkdir -p "$work/up"
tar -xzf "$tar_gz" -C "$work/up"
top="$(ls "$work/up")"
newtop="${top/asom-desktop-*-linux-/asom-desktop-9.9.9-upgrade-linux-}"
mv "$work/up/$top" "$work/up/$newtop"
tar -czf "$work/up/$newtop.tar.gz" -C "$work/up" "$newtop"
( cd "$work/up" && sha256sum "$newtop.tar.gz" > SHA256SUMS )
chmod -R a+rX "$work/up"
out="$(runh "$H" bash "$dist/install.sh" --archive "$work/up/$newtop.tar.gz" --home "$H" 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "upgrade install exits 0" || bad "upgrade exit $rc: $out"
[ "$(readlink "$H/.local/opt/asom/current")" = "9.9.9-upgrade" ] && ok "current now points at the new version" || bad "current is $(readlink "$H/.local/opt/asom/current")"
[ "$(readlink "$H/.local/opt/asom/previous")" = "$ver_dir" ] && [ -d "$H/.local/opt/asom/$ver_dir" ] && ok "previous -> old version, old directory kept (rollback)" || bad "previous is $(readlink "$H/.local/opt/asom/previous" 2>&1)"
out="$(runh "$H" bash "$dist/install.sh" --archive "$work/up/$newtop.tar.gz" --home "$H" 2>&1)"; rc=$?
[ "$rc" != "0" ] && echo "$out" | grep -q "already exists" && ok "re-installing the same version without --force is refused" || bad "same-version reinstall exit $rc"
out="$(runh "$H" bash "$dist/install.sh" --archive "$work/up/$newtop.tar.gz" --home "$H" --force 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "--force replaces the same version" || bad "--force exit $rc: $out"

# =====================================================================================================================
family install-refusals
refuse() { # refuse "<description>" <home> <expected regex> <install.sh args...>
  local d="$1" h="$2" rx="$3"; shift 3
  local o r
  o="$(runh "$h" bash "$dist/install.sh" "$@" --home "$h" 2>&1)"; r=$?
  if [ "$r" != "0" ] && echo "$o" | grep -Eq "$rx"; then ok "$d"; else bad "$d (exit $r, output: $o)"; fi
  if [ ! -e "$h/.local/opt/asom/current" ] && [ -z "$(ls "$h/.local/opt/asom" 2>/dev/null | grep -v '^\.staging' )" ]; then ok "  ...and nothing was installed"; else bad "  ...but something was installed: $(ls -A "$h/.local/opt/asom" 2>&1)"; fi
}
mkdir -p "$work/bad"; chmod 755 "$work/bad"
base="$(basename "$tar_gz")"

cp "$tar_gz" "$work/bad/$base"; cp "$dist/SHA256SUMS" "$work/bad/SHA256SUMS"
printf 'x' >> "$work/bad/$base"; chmod -R a+rX "$work/bad"
refuse "a corrupted archive fails the SHA-256 check" "$(newhome)" "SHA-256 mismatch" --archive "$work/bad/$base"

mkdir -p "$work/nosums"; cp "$tar_gz" "$work/nosums/$base"; chmod -R a+rX "$work/nosums"
refuse "no SHA256SUMS: refused (the installer always verifies)" "$(newhome)" "no SHA256SUMS" --archive "$work/nosums/$base"

mkdir -p "$work/othername"; cp "$tar_gz" "$work/othername/$base"; ( cd "$work/othername" && sha256sum "$base" | sed 's/  .*/  something-else.tar.gz/' > SHA256SUMS ); chmod -R a+rX "$work/othername"
refuse "the archive is not listed in SHA256SUMS: refused" "$(newhome)" "is not listed" --archive "$work/othername/$base"

mkdir -p "$work/host"
python3 "$here/make-hostile-archives.py" "$work/host" "$arch" > "$work/host/cases.txt"
chmod -R a+rX "$work/host"
n_host=0
while IFS='|' read -r f rx; do
  n_host=$((n_host + 1))
  refuse "hostile archive $f is refused" "$(newhome)" "$rx" --archive "$work/host/$f"
done < "$work/host/cases.txt"
[ "$n_host" -ge 9 ] && ok "$n_host hostile archives exercised (non-vacuous)" || bad "only $n_host hostile archives"
ls /tmp/asom-pwned-absolute "$work/pwned-dotdot" >/dev/null 2>&1 && bad "a hostile archive wrote a file outside the install" || ok "no hostile archive wrote outside the install"

if [ "$(id -u)" = "0" ]; then
  o="$(bash "$dist/install.sh" --archive "$tar_gz" --home "$work" 2>&1)"; r=$?
  [ "$r" = "2" ] && echo "$o" | grep -q "refusing to run as root" && ok "install.sh refuses to run as root" || bad "root install exit $r"
fi

# =====================================================================================================================
family uninstall
H="$(newhome)"
runh "$H" bash "$dist/install.sh" --archive "$tar_gz" --home "$H" >/dev/null 2>&1
mkdir -p "$H/.local/state/asom/ledger" "$H/.local/share/asom/identity"
echo row > "$H/.local/state/asom/ledger/ledger.jsonl"; echo key > "$H/.local/share/asom/identity/nik"
owner "$H"
out="$(runh "$H" bash "$dist/uninstall.sh" --home "$H" 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "uninstall.sh exits 0" || bad "uninstall exit $rc: $out"
[ ! -e "$H/.local/opt/asom" ] && [ ! -e "$H/.local/bin/asom" ] && [ ! -e "$H/.config/systemd/user/asom.service" ] && ok "versions, CLI link and unit are gone" || bad "something remains: $(find "$H/.local/opt" "$H/.local/bin" "$H/.config" 2>/dev/null)"
[ -f "$H/.local/state/asom/ledger/ledger.jsonl" ] && [ -f "$H/.local/share/asom/identity/nik" ] && ok "state (ledger) and data (identity) are KEPT" || bad "state or data was deleted without --purge"
echo "$out" | grep -q "kept:" && ok "output names what was kept" || bad "output does not name kept paths"

# an edited unit is not ours to delete
H="$(newhome)"
runh "$H" bash "$dist/install.sh" --archive "$tar_gz" --home "$H" >/dev/null 2>&1
echo "# my edit" >> "$H/.config/systemd/user/asom.service"
runh "$H" bash "$dist/uninstall.sh" --home "$H" >/dev/null 2>&1
[ -f "$H/.config/systemd/user/asom.service" ] && ok "a unit file you edited is left alone" || bad "an edited unit was deleted"

# a foreign ~/.local/bin/asom is not ours to delete
H="$(newhome)"
runh "$H" bash "$dist/install.sh" --archive "$tar_gz" --home "$H" >/dev/null 2>&1
rm "$H/.local/bin/asom"; ln -s /bin/true "$H/.local/bin/asom"; owner "$H"
runh "$H" bash "$dist/uninstall.sh" --home "$H" >/dev/null 2>&1
[ -L "$H/.local/bin/asom" ] && [ "$(readlink "$H/.local/bin/asom")" = "/bin/true" ] && ok "a ~/.local/bin/asom that is not an asom link is left alone" || bad "a foreign link was removed"

# --purge
H="$(newhome)"
runh "$H" bash "$dist/install.sh" --archive "$tar_gz" --home "$H" >/dev/null 2>&1
mkdir -p "$H/.local/state/asom" "$H/.local/share/asom"; echo row > "$H/.local/state/asom/l"; echo key > "$H/.local/share/asom/k"; owner "$H"
out="$(runh "$H" setsid -w bash "$dist/uninstall.sh" --home "$H" --purge </dev/null 2>&1)"; rc=$?
[ "$rc" != "0" ] && echo "$out" | grep -q "needs a terminal" && ok "--purge without a terminal refuses" || bad "purge without a tty: exit $rc, $out"
[ -d "$H/.local/opt/asom/current" ] && [ -f "$H/.local/state/asom/l" ] && ok "  ...and changed NOTHING (install and state intact)" || bad "  ...but something was changed"
out="$(runh "$H" python3 "$ptyrun" "yes" -- bash "$dist/uninstall.sh" --home "$H" --purge 2>&1)"
echo "$out" | grep -q "exit=1" && echo "$out" | grep -q "confirmation did not match" && ok "--purge with a wrong phrase on a terminal refuses" || bad "wrong phrase: $out"
[ -d "$H/.local/opt/asom/current" ] && [ -f "$H/.local/state/asom/l" ] && [ -f "$H/.local/share/asom/k" ] && ok "  ...and changed NOTHING" || bad "  ...but something was changed"
echo "$out" | grep -q "the ledger" && echo "$out" | grep -q "re-pairing" && ok "the prompt names what is lost (ledger, identity, re-pairing)" || bad "prompt does not name what is lost: $out"
out="$(runh "$H" python3 "$ptyrun" "delete asom state" -- bash "$dist/uninstall.sh" --home "$H" --purge 2>&1)"
echo "$out" | grep -q "exit=0" && [ ! -e "$H/.local/state/asom" ] && [ ! -e "$H/.local/share/asom" ] && [ ! -e "$H/.local/opt/asom" ] && ok "--purge with the exact phrase on a terminal deletes state, data and the install" || bad "exact phrase: $out"

# =====================================================================================================================
family deb-layout
list="$(dpkg-deb -c "$deb")"
n=$(printf '%s\n' "$list" | grep -c 'usr/lib/systemd/system/asom\.service$')
[ "$n" = "1" ] && ok "dpkg-deb -c lists usr/lib/systemd/system/asom.service exactly once" || bad "system unit listed $n times"
for p in ./usr/lib/systemd/user/asom.service ./usr/lib/sysusers.d/asom.conf ./usr/share/polkit-1/rules.d/50-asom-inhibit.rules; do
  printf '%s\n' "$list" | grep -q " $p\$" && ok "listed: $p" || bad "missing: $p"
done
printf '%s\n' "$list" | grep -Eq ' \./usr/bin/asom -> /opt/asom/current/bin/asom$' && ok "/usr/bin/asom -> /opt/asom/current/bin/asom" || bad "/usr/bin/asom link wrong"
printf '%s\n' "$list" | grep -Eq ' \./opt/asom/current -> [^/]+$' && ok "/opt/asom/current is a relative link to the version directory" || bad "current link wrong"
printf '%s\n' "$list" | grep -Eq '\.wants|/preset|/etc/' && bad "the deb ships an enablement path or /etc file" || ok "no .wants, preset or /etc path in the deb: units ship disabled"
printf '%s\n' "$list" | grep -Ev ' root/root ' | grep -q . && bad "an entry is not root/root" || ok "every entry is owned by root/root"
printf '%s\n' "$list" | grep -E 'install\.sh|uninstall\.sh' | grep -q . && bad "the per-user installers are inside the deb" || ok "install.sh and uninstall.sh are not in the deb"
printf '%s\n' "$list" | grep -E '^-[rwx-]{2}[rwx-][rwx-]{2}w' | grep -q . && bad "a group- or world-writable file is in the deb" || ok "no group- or world-writable file in the deb"
info="$(dpkg-deb -I "$deb")"
echo "$info" | grep -q 'Depends: libc6, libstdc++6, libgcc-s1, zlib1g' && ok "Depends is exactly the libraries check-native-deps.sh allows" || bad "Depends line: $(echo "$info" | grep Depends)"
echo "$info" | grep -q 'Recommends: libvulkan1' && ok "Recommends: libvulkan1" || bad "no Recommends"
echo "$info" | grep -q 'Version: .*~' && ok "the pre-release version sorts before the release (tilde)" || bad "version has no tilde: $(echo "$info" | grep Version)"
cdir="$work/ctl"; dpkg-deb -e "$deb" "$cdir"
cmp -s "$cdir/postinst" "$pkg/scripts/postinstall.sh" && cmp -s "$cdir/prerm" "$pkg/scripts/preremove.sh" && cmp -s "$cdir/postrm" "$pkg/scripts/postremove.sh" && ok "the deb's postinst, prerm and postrm are the committed scripts, byte for byte" || bad "maintainer scripts differ from scripts/"
grep -Eq 'systemctl[[:space:]]+(--user[[:space:]]+)?(enable|start|restart|reload-or-restart|preset)|--now' "$cdir/postinst" "$cdir/prerm" "$cdir/postrm" && bad "a maintainer script enables or starts a unit" || ok "no maintainer script enables, starts or presets a unit"

root="$work/debroot"; mkdir -p "$root"; dpkg-deb -x "$deb" "$root"; chmod -R a+rX "$root"
if [ "$(id -u)" = "0" ]; then chown -R 65534:65534 "$work/debroot"; fi
H="$(newhome)"
st="$(runh "$H" "$root/opt/asom/current/bin/asom-node" --mode=selftest 2>&1)"; rc=$?
[ "$rc" = "0" ] && echo "$st" | grep -Eq '^selftest: [0-9]+ ok, [0-9]+ not-yet-implemented, 0 failed$' && ok "selftest from the extracted deb (via /opt/asom/current): 0 failed" || bad "deb selftest exit $rc: $(echo "$st" | tail -2)"
pr="$(runh "$H" "$root/opt/asom/current/lib/runtime/bin/java" -cp "$probe/probe.jar" RuntimeProbe "$probe/probe-ec.p12" 2>&1)"; rc=$?
[ "$rc" = "0" ] && ok "runtime probe from the extracted deb: OK" || bad "deb probe: $pr"
[ -L "$root/usr/bin/asom" ] && ok "usr/bin/asom is a symlink in the extracted tree" || bad "usr/bin/asom is not a symlink"
cmp -s "$root/usr/lib/systemd/system/asom.service" "$pkg/systemd/asom.service" && cmp -s "$root/usr/lib/systemd/user/asom.service" "$pkg/systemd/asom-user.service" && ok "the shipped units are byte-identical to desktop/packaging/linux/systemd/" || bad "a shipped unit differs from the committed one"

# =====================================================================================================================
family package-scripts
if [ "$(id -u)" != "0" ] || ! unshare -m true 2>/dev/null; then
  if [ "${ASOM_REQUIRE_UNSHARE:-}" = "1" ]; then bad "package-scripts needs root and unshare -m, and ASOM_REQUIRE_UNSHARE=1 forbids skipping"
  else echo "SKIPPED: package-scripts needs root and 'unshare -m' (set ASOM_REQUIRE_UNSHARE=1 to make this a failure)"; fi
else
  stubs="$work/stubs"; mkdir -p "$stubs"
  cat > "$stubs/systemctl" <<'EOF'
#!/bin/sh
echo "systemctl $*" >> "$STUB_LOG"
case "$1" in is-active) [ "${STUB_ACTIVE:-0}" = "1" ] && exit 0 || exit 3 ;; esac
exit 0
EOF
  cat > "$stubs/systemd-sysusers" <<'EOF'
#!/bin/sh
echo "systemd-sysusers $*" >> "$STUB_LOG"
exit 0
EOF
  chmod +x "$stubs"/*
  mkdir -p "$work/emptybin"
  # run_script <systemd-present 1|0> <active 1|0> <script> <args...> -> prints the stub log; leaves the exit code in $work/stub.rc
  run_script() {
    local present="$1" active="$2" script="$3"; shift 3
    : > "$work/stub.log"
    unshare -m sh -c 'mount -t tmpfs tmpfs /run/systemd && { [ "$1" = 1 ] && mkdir -p /run/systemd/system || true; }; shift; exec "$@"' sh "$present" \
      env STUB_LOG="$work/stub.log" STUB_ACTIVE="$active" PATH="$stubs:/usr/bin:/bin" sh "$script" "$@" >"$work/stub.out" 2>&1
    echo $? > "$work/stub.rc"
    cat "$work/stub.log"
  }
  has() { printf '%s\n' "$1" | grep -qx -- "$2"; }
  none() { [ -z "$1" ]; }
  PI="$pkg/scripts/postinstall.sh"; PR="$pkg/scripts/preremove.sh"; PO="$pkg/scripts/postremove.sh"

  log="$(run_script 1 0 "$PI" configure)"
  has "$log" "systemd-sysusers /usr/lib/sysusers.d/asom.conf" && has "$log" "systemctl daemon-reload" && ok "postinst configure (fresh deb): sysusers and daemon-reload" || bad "fresh deb log: $log"
  ! has "$log" "systemctl try-restart asom.service" && ok "  ...and no restart on a fresh install" || bad "fresh install restarted the unit"
  log="$(run_script 1 0 "$PI" configure 0.0.1)"
  has "$log" "systemctl try-restart asom.service" && ok "postinst configure <old> (deb upgrade): try-restart (acts only on a running node)" || bad "deb upgrade log: $log"
  log="$(run_script 1 0 "$PI" 1)"
  ! has "$log" "systemctl try-restart asom.service" && has "$log" "systemctl daemon-reload" && ok "postinst 1 (rpm fresh install): no restart" || bad "rpm fresh log: $log"
  log="$(run_script 1 0 "$PI" 2)"
  has "$log" "systemctl try-restart asom.service" && ok "postinst 2 (rpm upgrade): try-restart" || bad "rpm upgrade log: $log"
  log="$(run_script 1 0 "$PI" abort-upgrade)"
  none "$log" && [ "$(cat "$work/stub.rc")" = "0" ] && ok "postinst abort-upgrade: does nothing, exits 0" || bad "abort-upgrade log: $log"
  log="$(run_script 0 0 "$PI" configure)"
  [ "$(cat "$work/stub.rc")" = "0" ] && ! printf '%s\n' "$log" | grep -q '^systemctl' && has "$log" "systemd-sysusers /usr/lib/sysusers.d/asom.conf" && ok "postinst without a running systemd: sysusers only, no systemctl, exits 0" || bad "no-systemd log: $log rc=$(cat "$work/stub.rc")"
  : > "$work/stub.log"
  unshare -m sh -c 'mount -t tmpfs tmpfs /run/systemd && mkdir -p /run/systemd/system; exec "$@"' sh /usr/bin/env PATH="$work/emptybin" STUB_LOG="$work/stub.log" /bin/sh "$PI" configure >"$work/stub.out" 2>&1
  [ "$?" = "0" ] && grep -q "systemd-sysusers not found" "$work/stub.out" && ok "postinst without systemd-sysusers: warns and still exits 0" || bad "no-sysusers run: $(cat "$work/stub.out")"

  all=""
  for a in "configure" "configure 0.0.1" "1" "2"; do all="$all$(run_script 1 1 "$PI" $a)"$'\n'; done
  for a in remove upgrade 0 1; do all="$all$(run_script 1 1 "$PR" $a)"$'\n'; done
  for a in remove purge upgrade 0 1; do all="$all$(run_script 1 1 "$PO" $a)"$'\n'; done
  printf '%s\n' "$all" | grep -Eq '^systemctl (--user )?(enable|start|restart|reload-or-restart|preset)( |$)|--now' && bad "some script called enable, start, restart, preset or --now" || ok "no script, in any case, ever calls enable, start, restart, preset or --now"
  [ "$(printf '%s\n' "$all" | grep -c '^systemctl')" -gt 0 ] && ok "the no-enable check saw $(printf '%s\n' "$all" | grep -c '^systemctl') systemctl calls (non-vacuous)" || bad "no systemctl call was observed at all"

  log="$(run_script 1 1 "$PR" remove)"
  has "$log" "systemctl is-active --quiet asom.service" && has "$log" "systemctl stop asom.service" && has "$log" "systemctl disable asom.service" && ok "prerm remove with an active node: stop, then disable" || bad "prerm remove active: $log"
  log="$(run_script 1 0 "$PR" remove)"
  ! has "$log" "systemctl stop asom.service" && has "$log" "systemctl disable asom.service" && ok "prerm remove with an inactive node: no stop, disable" || bad "prerm remove inactive: $log"
  log="$(run_script 1 1 "$PR" upgrade)"; none "$log" && ok "prerm upgrade: does nothing (postinst restarts once)" || bad "prerm upgrade log: $log"
  log="$(run_script 1 1 "$PR" 1)"; none "$log" && ok "prerm 1 (rpm upgrade): does nothing" || bad "prerm 1 log: $log"
  log="$(run_script 1 1 "$PR" 0)"; has "$log" "systemctl stop asom.service" && ok "prerm 0 (rpm removal): stops" || bad "prerm 0 log: $log"
  for a in remove purge 0; do
    log="$(run_script 1 0 "$PO" $a)"; has "$log" "systemctl daemon-reload" && [ "$(printf '%s\n' "$log" | grep -c .)" = "1" ] && ok "postrm $a: daemon-reload only" || bad "postrm $a log: $log"
  done
  log="$(run_script 1 0 "$PO" upgrade)"; none "$log" && ok "postrm upgrade: does nothing" || bad "postrm upgrade log: $log"
fi

# =====================================================================================================================
echo
echo "== summary (evidence label: LAB; NOT the install-matrix, NOT systemd, NOT a device)"
zero=0
for f in install-tarball install-refusals uninstall deb-layout package-scripts; do
  c="${fam_n[$f]:-0}"
  echo "  family $f: $c checks"
  if [ "$c" = "0" ]; then
    if [ "$f" = "package-scripts" ] && [ "${ASOM_REQUIRE_UNSHARE:-}" != "1" ]; then echo "    (skipped, see above)"; else zero=1; echo "    FAIL: family ran zero checks"; fi
  fi
done
echo "  checks passed: $pass   failed: $fail"
if [ "$fail" = "0" ] && [ "$zero" = "0" ]; then echo "lab-packaging-check: PASS"; exit 0; fi
echo "lab-packaging-check: FAIL"
exit 1
