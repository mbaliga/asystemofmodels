#!/usr/bin/env bash
# install.sh: user-local install of an asom-desktop app image tarball (linux.md 8.1 and 8.4). No root, no package manager.
#
#   install.sh --archive <asom-desktop-<ver>-linux-<arch>.tar.gz> [--sums <SHA256SUMS>] [--sig <signature>]
#              [--home <dir>] [--force]
#
# What it does, in order: verify the archive against SHA256SUMS (always; a missing or non-matching SHA256SUMS is a
# refusal); check the owner's detached signature when one is present AND gpg is installed (and say plainly when that
# check did not run); inspect the archive (one top directory, no absolute or `..` paths, right architecture); extract
# beside any older version; swap the `current` link atomically; link ~/.local/bin/asom; write the USER unit.
#
# What it never does: enable, start or restart anything, run anything from the archive, or touch state and data
# (~/.local/state/asom, ~/.local/share/asom). The unit is installed disabled. Enabling it is your explicit choice; see
# share/doc/LINUX.md inside the image. A node that is already running keeps running the old version until you restart it.
#
# Paths (HOME, or --home <dir> for a scratch install): <home>/.local/opt/asom/<ver>, <home>/.local/opt/asom/current,
# <home>/.local/opt/asom/previous (kept for `asom rollback`), <home>/.local/bin/asom, <home>/.config/systemd/user/asom.service.
#
# EVIDENCE: the artefacts this ships with are UNSIGNED, not for release. No signature check can pass on them.
set -euo pipefail

archive=""
sums=""
sig=""
home="${HOME:-}"
custom_home=0
force=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --archive) archive="${2:-}"; shift 2 ;;
    --sums) sums="${2:-}"; shift 2 ;;
    --sig) sig="${2:-}"; shift 2 ;;
    --home) home="${2:-}"; custom_home=1; shift 2 ;;
    --force) force=1; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "install.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done

die() { echo "install.sh: $1" >&2; exit "${2:-1}"; }

[ "$(id -u)" != "0" ] || die "refusing to run as root: this is the per-user installer (the node itself refuses to run as root). For a system-wide install use the .deb or .rpm." 2
[ -n "$archive" ] || die "--archive <file.tar.gz> is required" 2
[ -f "$archive" ] || die "no such archive: $archive" 2
[ -n "$home" ] && [ -d "$home" ] || die "home directory '$home' does not exist" 2
case "$home" in /*) ;; *) die "--home must be an absolute path" 2 ;; esac
for t in tar sha256sum awk mv ln rm mkdir install uname; do
  command -v "$t" >/dev/null 2>&1 || die "needs '$t' on PATH" 2
done

archive="$(cd "$(dirname "$archive")" && pwd)/$(basename "$archive")"
base="$(basename "$archive")"
[ -n "$sums" ] || sums="$(dirname "$archive")/SHA256SUMS"
[ -f "$sums" ] || die "no SHA256SUMS beside the archive ($sums). The installer always verifies; download SHA256SUMS from the same place as the archive." 1

# ---- 1. checksum (always) ---------------------------------------------------------------------------------------------
want="$(awk -v f="$base" '{ n = $2; sub(/^\*/, "", n); if (n == f) print $1 }' "$sums")"
[ -n "$want" ] || die "$base is not listed in $sums" 1
[ "$(printf '%s\n' "$want" | wc -l)" = "1" ] || die "$base is listed more than once in $sums" 1
got="$(sha256sum "$archive" | awk '{print $1}')"
[ "$got" = "$want" ] || die "SHA-256 mismatch for $base: SHA256SUMS says $want, the file is $got. Nothing was installed." 1
sha_line="sha256 of $base matches $(basename "$sums")"

# ---- 2. signature (only when there is one AND gpg is present; never claimed otherwise) --------------------------------
if [ -z "$sig" ]; then
  for c in "$sums.asc" "$sums.sig" "$sums.gpg"; do
    if [ -f "$c" ]; then sig="$c"; break; fi
  done
fi
if [ -n "$sig" ] && command -v gpg >/dev/null 2>&1; then
  gpg --verify "$sig" "$sums" >/dev/null 2>&1 || die "the signature $sig did not verify against $sums (is the owner's public key imported? its fingerprint is in the README). Nothing was installed." 1
  sig_line="signature $(basename "$sig") verified by gpg (this proves the owner's key signed the checksums, not that the code is safe)"
elif [ -n "$sig" ]; then
  sig_line="signature NOT CHECKED: gpg is not installed ($(basename "$sig") was not verified)"
else
  sig_line="signature NOT CHECKED: no detached signature was found beside SHA256SUMS"
fi

# ---- 3. inspect the archive before extracting anything ----------------------------------------------------------------
names="$(tar -tzf "$archive")" || die "$base is not a readable gzip tar archive" 1
[ -n "$names" ] || die "$base is empty" 1
top="$(printf '%s\n' "$names" | awk -F/ 'NR == 1 { print $1 }')"
printf '%s\n' "$names" | awk -F/ -v top="$top" '$1 != top { bad = 1 } END { exit bad }' || die "$base has more than one top-level entry" 1
printf '%s\n' "$names" | awk '/^\// || /(^|\/)\.\.(\/|$)/ { bad = 1 } END { exit bad }' || die "$base contains an absolute path or a '..' component" 1
tar -tvzf "$archive" | awk 'substr($0, 1, 1) == "h" { bad = 1 } END { exit bad }' || die "$base contains a hard link" 1
arch="$(uname -m)"
ver="${top#asom-desktop-}"; ver="${ver%-linux-*}"
case "$top" in
  "asom-desktop-$ver-linux-$arch") ;;
  asom-desktop-*-linux-*) die "$base is for a different architecture than this machine ($arch)" 1 ;;
  *) die "unexpected top-level directory '$top' (expected asom-desktop-<ver>-linux-$arch)" 1 ;;
esac
case "$ver" in ""|*[!0-9A-Za-z.+~_-]*) die "unsafe version string '$ver' in the archive name" 1 ;; esac

# ---- 4. extract to a staging directory, then verify what came out -----------------------------------------------------
prefix="$home/.local/opt/asom"
bindir="$home/.local/bin"
unitdir="$home/.config/systemd/user"
mkdir -p "$prefix" "$bindir" "$unitdir"
umask 022
stage="$prefix/.staging.$$"
rm -rf "$stage"
mkdir "$stage"
cleanup() { rm -rf "$stage" "$prefix/current.tmp.$$" "$prefix/previous.tmp.$$"; }
trap cleanup EXIT
tar -xzf "$archive" -C "$stage" --no-same-owner
tree="$stage/$top"
for f in bin/asom-node bin/asom lib/runtime/bin/java share/systemd/asom-user.service; do
  [ -e "$tree/$f" ] || die "the archive lacks $f: not an asom app image" 1
done
stage_real="$(cd "$stage" && pwd -P)"
while IFS= read -r l; do
  t="$(readlink "$l")"
  case "$t" in /*) die "the archive holds an absolute symlink ($l -> $t)" 1 ;; esac
  r="$(realpath -m -- "$(dirname "$l")/$t")"
  case "$r" in "$stage_real/$top"|"$stage_real/$top"/*) ;; *) die "the archive holds a symlink that leaves the image ($l -> $t)" 1 ;; esac
done < <(find "$tree" -type l)

# ---- 5. place it, swap `current` atomically ---------------------------------------------------------------------------
dest="$prefix/$ver"
if [ -e "$dest" ]; then
  [ "$force" = "1" ] || die "$dest already exists. Run uninstall.sh first, or pass --force to replace this version." 1
  rm -rf "$dest"
fi
mv "$tree" "$dest"

prev=""
if [ -L "$prefix/current" ]; then prev="$(readlink "$prefix/current")"; fi
ln -s "$ver" "$prefix/current.tmp.$$"
mv -T "$prefix/current.tmp.$$" "$prefix/current"
if [ -n "$prev" ] && [ "$prev" != "$ver" ] && [ -d "$prefix/$prev" ]; then
  ln -s "$prev" "$prefix/previous.tmp.$$"
  mv -T "$prefix/previous.tmp.$$" "$prefix/previous"
fi

# ---- 6. the CLI link and the (disabled) USER unit ---------------------------------------------------------------------
link_note=""
if [ -L "$bindir/asom" ] && case "$(readlink "$bindir/asom")" in "$prefix"/*) true ;; *) false ;; esac; then
  ln -sfn "$prefix/current/bin/asom" "$bindir/asom"
elif [ ! -e "$bindir/asom" ] && [ ! -L "$bindir/asom" ]; then
  ln -s "$prefix/current/bin/asom" "$bindir/asom"
else
  link_note="left $bindir/asom alone (it exists and is not an asom link); run $prefix/current/bin/asom instead"
fi

unit_note=""
shipped="$dest/share/systemd/asom-user.service"
if [ ! -e "$unitdir/asom.service" ]; then
  install -m 0644 "$shipped" "$unitdir/asom.service"
  unit_note="wrote $unitdir/asom.service (disabled)"
elif cmp -s "$shipped" "$unitdir/asom.service"; then
  unit_note="$unitdir/asom.service is already the shipped unit (state unchanged)"
else
  install -m 0644 "$shipped" "$unitdir/asom.service.dist"
  unit_note="$unitdir/asom.service differs from the shipped unit and was NOT overwritten; the shipped one is beside it as asom.service.dist"
fi

if [ "$custom_home" = "0" ] && command -v systemctl >/dev/null 2>&1 && [ -n "${XDG_RUNTIME_DIR:-}" ]; then
  systemctl --user daemon-reload >/dev/null 2>&1 || true
fi

echo "asom-desktop $ver installed under $prefix (UNSIGNED artefact, not for release)"
echo "checks run:"
echo "  - $sha_line"
echo "  - $sig_line"
echo "  - archive layout: one top directory, no absolute or '..' paths, no symlink leaving the image, architecture $arch"
echo "current -> $ver${prev:+ (previous -> $prev, kept for rollback)}"
[ -z "$link_note" ] || echo "note: $link_note"
echo "note: $unit_note"
echo "Nothing was enabled or started, and state and data were not touched. Lending is OFF until you turn it on."
echo "Next: read $prefix/current/share/doc/LINUX.md, then $prefix/current/bin/asom status."
if [ "$custom_home" = "0" ] && command -v systemctl >/dev/null 2>&1 && systemctl --user is-active --quiet asom.service 2>/dev/null; then
  echo "note: a node is running and keeps using the old version until you restart it yourself: systemctl --user try-restart asom"
fi
