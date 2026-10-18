#!/usr/bin/env bash
# package-user-unit.sh <asom-user.service> <out>: the USER unit as the deb and the rpm install it (linux.md 3.1, USER row:
# the app is in /opt/asom for a package and in ~/.local/opt/asom for the tarball). It is the tarball unit with exactly two lines
# rewritten, the ExecStart and the Documentation path. Anything else in the input is copied byte for byte. It fails, and leaves
# no output file, if either line is not there to rewrite, so a reworded unit cannot silently ship the %h form system-wide.
set -euo pipefail

[ "$#" = "2" ] || { echo "usage: package-user-unit.sh <asom-user.service> <out>" >&2; exit 2; }
in="$1"
out="$2"
[ -f "$in" ] || { echo "package-user-unit: $in is not a file" >&2; exit 2; }

exec_old='ExecStart=%h/.local/opt/asom/current/bin/asom-node --mode=user'
exec_new='ExecStart=/opt/asom/current/bin/asom-node --mode=user'
doc_old='Documentation=file:%h/.local/opt/asom/current/share/doc/LINUX.md'
doc_new='Documentation=file:/opt/asom/current/share/doc/LINUX.md'

[ "$(grep -cFx -- "$exec_old" "$in")" = "1" ] || { echo "package-user-unit: $in lacks exactly one line '$exec_old'" >&2; exit 1; }
[ "$(grep -cFx -- "$doc_old" "$in")" = "1" ] || { echo "package-user-unit: $in lacks exactly one line '$doc_old'" >&2; exit 1; }

tmp="$(mktemp "$out.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    "$exec_old") printf '%s\n' "$exec_new" ;;
    "$doc_old") printf '%s\n' "$doc_new" ;;
    *) printf '%s\n' "$line" ;;
  esac
done < "$in" > "$tmp"
if grep -q '%h\|\.local/opt' "$tmp"; then echo "package-user-unit: a home-relative path is left in the output" >&2; exit 1; fi
chmod 0644 "$tmp"
mv -f "$tmp" "$out"
trap - EXIT
