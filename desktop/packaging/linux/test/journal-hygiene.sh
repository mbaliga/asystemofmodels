#!/usr/bin/env bash
# journal-hygiene.sh (PLATFORM_PLAN 3, DL2; linux.md 3.2 "T17(c): no token, ledger row or prompt ever reaches the journal").
#
# EVIDENCE LABEL: CI-ONLY. It needs a real systemd journal (a hosted runner VM); it is NOT run in the build container and
# NOT DEVICE EVIDENCE. Its own logic is exercised against a stubbed journal by JournalHygieneScriptTest (LAB).
#
# What it does: sends a canary token through the owner CLI, then reads the journal of the asom unit since the start and
# counts (a) occurrences of the canary and (b) ledger-row markers. It prints exactly
#     PASS: 0 token matches, 0 ledger rows
# and exits 0 only when both counts are zero AND both positive controls held. Otherwise:
#     FAIL: <n> token matches, <m> ledger rows          (exit 1)
#     ERROR: <why the check itself could not be trusted> (exit 2)
#
# Positive controls (a grep that prints 0 is also what an unreadable journal prints, ERR-ISO-3):
#   1. a control canary written with `logger` MUST be found in the whole journal since the start, so the reader can see
#      what was written this run;
#   2. the unit's own journal since the start MUST be non-empty (systemd writes Starting/Started lines for it), so the
#      unit filter matches the right unit.
# Honest limit: as of DL2 the node has no request path that carries a prompt (`asom chat` answers NOT_IMPLEMENTED), so
# this proves the unit's output plumbing (StandardOutput=null, StandardError=null, the launcher, the JVM) and not the
# request pipeline. Re-run it unchanged when a real request path lands (DL4/DL6).
#
# Usage: journal-hygiene.sh [--unit asom.service] [--user-unit] [--canary TEXT] [--since 'YYYY-MM-DD HH:MM:SS']
# --since matters: the unit-journal positive control needs systemd's own Started line for the unit, so the window must
# open BEFORE the unit was started. Without it the window is the last 2 seconds, which is only right when the unit was
# started a moment ago (the first hosted run failed exactly here: the unit had been up for minutes).
# Environment overrides (used by the script's own test with stubs): JOURNALCTL, LOGGER, ASOM_CLI, SLEEP.
set -u

unit="asom.service"
user_flag=""
canary=""
since_arg=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --unit) unit="$2"; shift 2 ;;
    --user-unit) user_flag="--user"; shift ;;
    --canary) canary="$2"; shift 2 ;;
    --since) since_arg="$2"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument: $1"; exit 2 ;;
  esac
done

JOURNALCTL="${JOURNALCTL:-journalctl}"
LOGGER="${LOGGER:-logger}"
ASOM_CLI="${ASOM_CLI:-asom}"
SLEEP="${SLEEP:-sleep}"

stamp="$(date +%s)-$$-${RANDOM:-0}"
[ -n "$canary" ] || canary="ASOMCANARY-${stamp}-bearer-token"
control="ASOMCONTROL-${stamp}"
since="$(date -d '2 seconds ago' '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S')"
[ -z "$since_arg" ] || since="$since_arg"

# 1. Positive control: write a line that MUST be visible to the journal reader.
"$LOGGER" -t asom-hygiene-control "$control" || { echo "ERROR: could not write the control line with logger"; exit 2; }

# 2. Requests carrying the canary. Their exit status is ignored on purpose: several are NOT_IMPLEMENTED (exit 3) at DL2.
for args in "status --json" "chat --model $canary" "ledger export --note $canary"; do
  # shellcheck disable=SC2086
  "$ASOM_CLI" $args >/dev/null 2>&1 || true
done
"$SLEEP" 2

journal_all() { "$JOURNALCTL" --no-pager -o cat --since "$since" ${user_flag:+"$user_flag"} 2>/dev/null; }
journal_unit() { "$JOURNALCTL" --no-pager -o cat --since "$since" ${user_flag:+"$user_flag"} -u "$unit" 2>/dev/null; }

all_text="$(journal_all)"
unit_text="$(journal_unit)"

control_hits="$(printf '%s\n' "$all_text" | grep -c -F -- "$control")"
if [ "${control_hits:-0}" -lt 1 ]; then
  echo "ERROR: positive control failed: the control line written with logger was not found in the journal, so a clean result would prove nothing"
  exit 2
fi
unit_lines="$(printf '%s\n' "$unit_text" | grep -c .)"
if [ "${unit_lines:-0}" -lt 1 ]; then
  echo "ERROR: positive control failed: the journal of $unit since the start is empty (was the unit started? is the journal readable by this user?)"
  exit 2
fi

token_matches="$(printf '%s\n' "$unit_text" | grep -c -F -- "$canary")"
ledger_rows="$(printf '%s\n' "$unit_text" | grep -c -E '"(callerPkg|requestedModel|egress|latencyMs)"')"

if [ "${token_matches:-0}" -eq 0 ] && [ "${ledger_rows:-0}" -eq 0 ]; then
  echo "PASS: 0 token matches, 0 ledger rows"
  exit 0
fi
echo "FAIL: ${token_matches:-0} token matches, ${ledger_rows:-0} ledger rows"
exit 1
