#!/usr/bin/env bash
# Generates the click's AppArmor profile with `aa-easyprof` from the PINNED UBports templates and policy groups, for the groups
# in the click's own asom.apparmor.in, then checks that the AppArmor parser accepts it (preprocess only, no kernel needed).
#
#   make-profile.sh [--policy 2404.1|2404.2] [--out FILE]
#
# The output is the profile as the phone would generate it from the same inputs. It is an APPROXIMATION of what is enforced on a
# device (the kernel differs, the abstractions differ slightly, UA19); it is CI-APPROX, NEVER DEVICE EVIDENCE.
# Needs: apparmor-utils (aa-easyprof), apparmor (apparmor_parser), python3, curl, and write access to /usr/share/apparmor/hardware
# (the profile includes it by absolute path, so the pinned hardware/ directory is copied there with sudo when it is absent).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
policy="2404.1"
out="$here/.cache/xyz.mdhv.asom.ut.profile"
while [ $# -gt 0 ]; do
  case "$1" in
    --policy) policy="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) echo "make-profile: unknown argument $1" >&2; exit 2 ;;
  esac
done
for tool in aa-easyprof apparmor_parser python3; do command -v "$tool" >/dev/null || { echo "make-profile: missing $tool (apt-get install apparmor apparmor-utils)" >&2; exit 1; }; done

pkg="xyz.mdhv.asom.ut"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$root/manifest.json.in")"
app="asom"
appid="${pkg}_${app}_${version}"
groups="$(python3 - "$root/asom.apparmor.in" "$policy" <<'E'
import json, sys
doc = json.loads(open(sys.argv[1]).read().replace("@APPARMOR_POLICY@", sys.argv[2]))
print(",".join(doc["policy_groups"]))
E
)"
python3 "$here/check-groups.py" "$root/asom.apparmor.in" --policy "$policy" --substitute

policy_dir="$("$here/fetch-policy.sh")"
if [ ! -d /usr/share/apparmor/hardware ]; then
  sudo mkdir -p /usr/share/apparmor
  sudo cp -r "$policy_dir/data/hardware" /usr/share/apparmor/hardware
fi

# AppArmor's D-Bus-safe form of the app id: every character outside [A-Za-z0-9] becomes _XX (hex of its byte).
appid_dbus="$(python3 -c 'import re,sys; print("".join(c if c.isalnum() and c.isascii() else "_%02x" % ord(c) for c in sys.argv[1]))' "$appid")"
mkdir -p "$(dirname "$out")"
aa-easyprof --templates-dir="$policy_dir/data/templates" --policy-groups-dir="$policy_dir/data/policygroups" \
  --policy-vendor=ubuntu --policy-version="$policy" -t ubuntu-sdk -p "$groups" \
  --name="$pkg" --profile-name="$appid" \
  --template-var="@{APP_PKGNAME}=$pkg" --template-var="@{APP_APPNAME}=$app" --template-var="@{APP_VERSION}=$version" \
  --template-var="@{APP_ID_DBUS}=$appid_dbus" --no-verify > "$out"
apparmor_parser -QK -p "$out" > /dev/null
echo "make-profile: $out ($(wc -l < "$out") lines), policy $policy, groups $groups, profile name $appid; the parser accepts it. CI-APPROX, NOT DEVICE EVIDENCE."
