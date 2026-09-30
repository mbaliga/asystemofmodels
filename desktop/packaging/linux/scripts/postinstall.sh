#!/bin/sh
# Package post-install (linux.md 8.1). Shared by the deb (postinst: $1 = configure, $2 = the previous version or empty)
# and the rpm (%post: $1 = the number of installed versions, 1 on a fresh install, 2 or more on an upgrade).
#
# It creates the service user, reloads systemd and, on an UPGRADE only, restarts a node that is ALREADY running (a stopped
# node stays stopped). It never enables a unit and never starts one: the units ship disabled, and enabling is the owner's
# explicit act. It does nothing about /var/lib/asom: the system unit's StateDirectory creates it when the owner starts it.
set -e

action=""
case "${1:-}" in
  configure) if [ -n "${2:-}" ]; then action=upgrade; else action=install; fi ;;
  1) action=install ;;
  [2-9]|[1-9][0-9]*) action=upgrade ;;
  *) exit 0 ;;
esac

if command -v systemd-sysusers >/dev/null 2>&1; then
  systemd-sysusers /usr/lib/sysusers.d/asom.conf || echo "asom: WARNING systemd-sysusers failed; the dedicated user \"asom\" was not created (only the system unit needs it)" >&2
else
  echo "asom: systemd-sysusers not found; the dedicated user \"asom\" was not created (only the system unit needs it)" >&2
fi

if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
  systemctl daemon-reload || true
  if [ "$action" = "upgrade" ]; then
    systemctl try-restart asom.service || true
  fi
fi

echo "asom: installed. The service is NOT enabled and NOT started; lending is OFF. See /opt/asom/current/share/doc/LINUX.md."
exit 0
