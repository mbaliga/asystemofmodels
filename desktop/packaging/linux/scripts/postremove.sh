#!/bin/sh
# Package post-remove. Shared by the deb (postrm: $1 = remove, purge, upgrade, ...) and the rpm (%postun: $1 = 0 on a real
# removal, 1 or more on an upgrade).
#
# It reloads systemd (the unit files are gone) and says what was KEPT. It deletes nothing: neither `remove` nor `purge`
# touches /var/lib/asom (the node identity, the ledger, downloaded models) or the user `asom`. That is a deliberate
# reading of linux.md 8.1, see desktop/packaging/linux/ERRATA.md ERR-DL3-5: deleting them needs the terminal confirmation
# that names what is lost, and a maintainer script has no terminal to ask on.
set -e

case "${1:-}" in
  remove|purge|0) ;;
  *) exit 0 ;;
esac

if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
  systemctl daemon-reload || true
fi

if [ -d /var/lib/asom ]; then
  echo "asom: removed. KEPT: /var/lib/asom (node identity, ledger, models) and the system user \"asom\"."
  echo "asom: to delete them yourself, deliberately, run as root:  rm -rf /var/lib/asom   (this cannot be undone and forces re-pairing)"
fi
exit 0
