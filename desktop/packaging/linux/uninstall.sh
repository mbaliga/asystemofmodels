#!/usr/bin/env bash
# uninstall.sh: remove a user-local asom-desktop install made by install.sh (linux.md 8.1).
#
#   uninstall.sh [--home <dir>] [--purge]
#
# Stops and disables the user unit if it is active or enabled, removes the unit file (only if it is a shipped one, never
# one you edited), the ~/.local/bin/asom link (only if it points into the install) and every installed version.
#
# State and data are KEPT by default: ~/.local/state/asom (the ledger) and ~/.local/share/asom (the node identity and
# downloaded models). `--purge` deletes them after a confirmation typed on the terminal (/dev/tty, never stdin), which
# names what is lost. Without a terminal `--purge` refuses and changes NOTHING. The confirmation comes first, so a refused
# purge leaves the install as it was.
#
# Paths follow the node: $XDG_STATE_HOME and $XDG_DATA_HOME when they are absolute, else <home>/.local/state and
# <home>/.local/share. With --home <dir> (a scratch install) the XDG variables are ignored.
set -euo pipefail

home="${HOME:-}"
custom_home=0
purge=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --home) home="${2:-}"; custom_home=1; shift 2 ;;
    --purge) purge=1; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "uninstall.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done

die() { echo "uninstall.sh: $1" >&2; exit "${2:-1}"; }

[ "$(id -u)" != "0" ] || die "refusing to run as root: this removes a per-user install. Use the package manager for the .deb or .rpm." 2
[ -n "$home" ] && [ -d "$home" ] || die "home directory '$home' does not exist" 2
case "$home" in /*) ;; *) die "--home must be an absolute path" 2 ;; esac
[ "$home" != "/" ] || die "refusing --home /" 2

prefix="$home/.local/opt/asom"
bindir="$home/.local/bin"
unitdir="$home/.config/systemd/user"
if [ "$custom_home" = "1" ]; then
  state="$home/.local/state/asom"
  data="$home/.local/share/asom"
else
  case "${XDG_STATE_HOME:-}" in /*) state="$XDG_STATE_HOME/asom" ;; *) state="$home/.local/state/asom" ;; esac
  case "${XDG_DATA_HOME:-}" in /*) data="$XDG_DATA_HOME/asom" ;; *) data="$home/.local/share/asom" ;; esac
fi

# ---- the purge confirmation comes first ------------------------------------------------------------------------------
if [ "$purge" = "1" ]; then
  if ! { exec 3</dev/tty; } 2>/dev/null; then
    die "--purge needs a terminal to confirm on (/dev/tty is not available). Nothing was changed." 1
  fi
  {
    echo "--purge will DELETE, permanently:"
    echo "  $state   (the ledger: the record of every network event this node made)"
    echo "  $data    (the node identity, which forces re-pairing with every peer, and any downloaded models)"
    echo "Neither can be recovered. To go ahead type exactly:  delete asom state"
  } > /dev/tty
  printf '> ' > /dev/tty
  IFS= read -r answer <&3 || answer=""
  exec 3<&-
  [ "$answer" = "delete asom state" ] || die "confirmation did not match. Nothing was changed." 1
fi

# ---- stop and disable (only the user manager's own unit; nothing is started) --------------------------------------------
if [ "$custom_home" = "0" ] && command -v systemctl >/dev/null 2>&1 && [ -n "${XDG_RUNTIME_DIR:-}" ]; then
  if systemctl --user is-active --quiet asom.service 2>/dev/null; then
    echo "stopping the running node (systemctl --user stop asom)"
    systemctl --user stop asom.service || echo "uninstall.sh: WARNING could not stop asom.service" >&2
  fi
  if systemctl --user is-enabled --quiet asom.service 2>/dev/null; then
    echo "disabling the unit (systemctl --user disable asom)"
    systemctl --user disable asom.service >/dev/null 2>&1 || echo "uninstall.sh: WARNING could not disable asom.service" >&2
  fi
fi

removed=()
kept=()

# unit: only if it equals a shipped unit of some installed version
if [ -f "$unitdir/asom.service" ]; then
  match=0
  for shipped in "$prefix"/*/share/systemd/asom-user.service; do
    if [ -f "$shipped" ] && cmp -s "$shipped" "$unitdir/asom.service"; then match=1; break; fi
  done
  if [ "$match" = "1" ]; then rm -f "$unitdir/asom.service"; removed+=("$unitdir/asom.service")
  else kept+=("$unitdir/asom.service (differs from every shipped unit; yours, left alone)"); fi
fi
if [ -f "$unitdir/asom.service.dist" ]; then rm -f "$unitdir/asom.service.dist"; removed+=("$unitdir/asom.service.dist"); fi

# CLI link: only if it points into the install
if [ -L "$bindir/asom" ]; then
  case "$(readlink "$bindir/asom")" in
    "$prefix"/*) rm -f "$bindir/asom"; removed+=("$bindir/asom") ;;
    *) kept+=("$bindir/asom (not an asom link)") ;;
  esac
fi

# the versions
if [ -d "$prefix" ]; then
  case "$prefix" in
    */.local/opt/asom) rm -rf "$prefix"; removed+=("$prefix (every installed version, current, previous)") ;;
    *) die "refusing to delete unexpected prefix $prefix" 1 ;;
  esac
fi

if [ "$purge" = "1" ]; then
  for d in "$state" "$data"; do
    case "$d" in
      */asom) if [ -e "$d" ]; then rm -rf "$d"; removed+=("$d (PURGED)"); fi ;;
      *) die "refusing to delete unexpected path $d" 1 ;;
    esac
  done
else
  for d in "$state" "$data"; do
    if [ -e "$d" ]; then kept+=("$d (state and data: kept; --purge deletes them after a terminal confirmation)"); fi
  done
fi

if [ "$custom_home" = "0" ] && command -v systemctl >/dev/null 2>&1 && [ -n "${XDG_RUNTIME_DIR:-}" ]; then
  systemctl --user daemon-reload >/dev/null 2>&1 || true
fi

echo "asom-desktop uninstalled from $home"
for r in ${removed[@]+"${removed[@]}"}; do echo "  removed: $r"; done
for k in ${kept[@]+"${kept[@]}"}; do echo "  kept:    $k"; done
if [ "${#removed[@]}" = "0" ]; then echo "  (nothing was installed here)"; fi
