#!/bin/sh
# Package pre-remove (linux.md 8.1 "stop if active; disable"). Shared by the deb (prerm: $1 = remove, upgrade, deconfigure,
# failed-upgrade) and the rpm (%preun: $1 = 0 on a real removal, 1 or more on an upgrade).
#
# Only a REAL removal acts. An upgrade must not stop the node here: postinstall.sh restarts it once, after the new files are in place.
set -e

case "${1:-}" in
  remove|0) ;;
  *) exit 0 ;;
esac

if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
  if systemctl is-active --quiet asom.service; then
    systemctl stop asom.service || echo "asom: WARNING could not stop asom.service" >&2
  fi
  systemctl disable asom.service >/dev/null 2>&1 || true
fi
exit 0
