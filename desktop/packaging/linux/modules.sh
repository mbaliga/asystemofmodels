#!/usr/bin/env bash
# Sourced by build-app-image.sh and check-jlink-modules.sh. Parses jlink-modules.txt (format documented there).
# Never run directly.

# modules_section <file> <derived|excluded|added>: the module names of one section, one per line, sorted, no comments.
modules_section() {
  awk -v want="[$2]" '
    /^\[/ { cur = $1; next }
    cur == want {
      sub(/#.*/, "")
      gsub(/[ \t\r]/, "")
      if ($0 != "") print $0
    }
  ' "$1" | LC_ALL=C sort -u
}

# modules_join: stdin lines -> one comma-separated line.
modules_join() {
  paste -sd, -
}
