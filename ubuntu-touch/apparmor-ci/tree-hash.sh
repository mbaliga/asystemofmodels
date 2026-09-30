#!/usr/bin/env bash
# Prints one sha256 for a directory tree: every regular file by content and every symlink by target, over sorted relative paths.
# The UBports policy tree is mostly symlinks (policy 2404.1 points at the 20.04 files), so a hash of regular files alone would miss a
# retargeted link. Usage: tree-hash.sh <dir>
set -euo pipefail
export LC_ALL=C
dir="${1:?usage: tree-hash.sh <dir>}"
cd "$dir"
find . \( -type f -o -type l \) -print0 | sort -z | while IFS= read -r -d '' f; do
  if [ -L "$f" ]; then
    printf 'L %s %s\n' "$f" "$(readlink "$f")"
  else
    printf 'F %s %s\n' "$f" "$(sha256sum < "$f" | cut -d' ' -f1)"
  fi
done | sha256sum | cut -d' ' -f1
