#!/usr/bin/env bash
# SYNTHETIC: a stand-in for /usr/bin/security used ONLY by DemoScriptTest on Linux. It implements just the subcommands the AM14 demo
# calls, over a state directory, and it says nothing about how the real tool behaves (that is what the macOS CI job is for).
#   FAKE_SECURITY_STATE   a directory for the fake keychains (required)
#   FAKE_SECURITY_MODE    trusting (default): an ordinary item is readable without a prompt; an item stored with -T "" is not
#                         prompting: every read needs a prompt (exit 36), as if the ordinary item did not trust the tool
#                         broken:    create-keychain fails
#                         hang:      a read never returns (for the watchdog)
set -u
state=${FAKE_SECURITY_STATE:?FAKE_SECURITY_STATE is required}
mode=${FAKE_SECURITY_MODE:-trusting}
cmd=${1:-}
shift || true
args=("$@")
last=${args[$((${#args[@]} - 1))]:-}
name=$(basename "${last:-none}")
case "$cmd" in
  create-keychain)
    [ "$mode" = broken ] && { echo "fake: create-keychain failed" >&2; exit 1; }
    : > "$state/$name"
    ;;
  set-keychain-settings|unlock-keychain)
    [ -e "$state/$name" ] || exit 1
    ;;
  add-generic-password)
    service=""; secret=""; trusted="tool"; i=0
    while [ $i -lt ${#args[@]} ]; do
      case "${args[$i]}" in
        -s) service=${args[$((i + 1))]}; i=$((i + 2)) ;;
        -w) secret=${args[$((i + 1))]}; i=$((i + 2)) ;;
        -T) [ -z "${args[$((i + 1))]}" ] && trusted="none"; i=$((i + 2)) ;;
        -a) i=$((i + 2)) ;;
        *) i=$((i + 1)) ;;
      esac
    done
    [ -e "$state/$name" ] || exit 1
    printf '%s|%s|%s\n' "$service" "$secret" "$trusted" >> "$state/$name"
    ;;
  find-generic-password)
    service=""; i=0
    while [ $i -lt ${#args[@]} ]; do
      case "${args[$i]}" in
        -s) service=${args[$((i + 1))]}; i=$((i + 2)) ;;
        -a) i=$((i + 2)) ;;
        *) i=$((i + 1)) ;;
      esac
    done
    [ "$mode" = hang ] && sleep 30
    line=$(grep -F "$service|" "$state/$name" | tail -n 1) || exit 44
    trusted=${line##*|}
    if [ "$mode" = prompting ] || [ "$trusted" = none ]; then echo "security: User interaction is not allowed." >&2; exit 36; fi
    rest=${line#*|}
    printf '%s\n' "${rest%|*}"
    ;;
  delete-keychain)
    rm -f "$state/$name"
    ;;
  *)
    echo "fake: unsupported subcommand $cmd" >&2
    exit 2
    ;;
esac
