#!/usr/bin/env bash
# UT0.3 (CI only): inside an arm64 image, unpack the built click and run the bundled aarch64 runtime's `--selftest`.
#
#   docker run --rm --platform linux/arm64 -v "$PWD":/w <image@sha256:...> /w/ubuntu-touch/tests/smoke/arm64-selftest.sh /w/<click file>
#
# Asserts: exactly one stdout line, starting {"selftest":"ok", with TLSv1.3, ALPN asom-mesh/1, mutual authentication, a refused wrong
# pin, vector counts equal to the lab's own counts for M01-M03, thermalReadable and batteryReadable and rssKiB recorded, an empty
# stderr and exit 0.
#
# WHAT THIS PROVES (R3-OVERCLAIM-9): the bundled runtime STARTS and passes its self-test on an arm64 userland that is a noble-based
# UBports SDK image: glibc and ABI compatibility, the JVM flags, the jlinked module set, the vectors. It is NOT the Ubuntu Touch
# system image, NOT Halium, NOT libhybris, NOT click confinement, NOT a phone's kernel or memory. LAB / CI (hosted VM) - NOT DEVICE EVIDENCE.
set -euo pipefail
click="${1:?usage: arm64-selftest.sh <click file>}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ut="$(cd "$here/../.." && pwd)"
repo="$(cd "$ut/.." && pwd)"

if [ "$(uname -m)" != "aarch64" ]; then
  # A local dry run of THIS SCRIPT with a host-arch runtime in the click (see PROGRESS.md); never counted as the arm64 lane.
  [ "${ASOM_UT_SMOKE_DRYRUN:-}" = "1" ] || { echo "arm64-selftest: this host is $(uname -m), not aarch64" >&2; exit 1; }
  echo "arm64-selftest: DRY RUN on $(uname -m): this is NOT the arm64 lane and proves nothing about aarch64"
fi
command -v python3 >/dev/null || { echo "arm64-selftest: python3 is needed" >&2; exit 1; }
command -v dpkg-deb >/dev/null || { echo "arm64-selftest: dpkg-deb is needed" >&2; exit 1; }
echo "image: $(. /etc/os-release && echo "$PRETTY_NAME") on $(uname -m) (glibc $(ldd --version | head -1 | grep -Eo '[0-9]+\.[0-9]+$'))"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dpkg-deb -x "$click" "$work/click"
arch="$(dpkg-deb -f "$click" Architecture)"
echo "click: $(basename "$click") Architecture=$arch"
[ "$arch" = "arm64" ] || { echo "arm64-selftest: click Architecture is $arch" >&2; exit 1; }
base="$work/click"
java="$base/lib/asom/rt/bin/java"
[ -x "$java" ] || { echo "arm64-selftest: no runtime in the click" >&2; exit 1; }
file "$java" 2>/dev/null | grep -q 'ARM aarch64' || echo "arm64-selftest: (file(1) missing or the runtime is not aarch64 - the run below is the proof)"

home="$work/home"; tmp="$work/tmp"
mkdir -p "$home" "$tmp"
mapfile -t opts < <(HOME="$home" TMPDIR="$tmp" python3 "$ut/tools/expand_jvm_options.py" "$base/lib/asom/jvm.options")
echo "jvm flags: ${opts[*]}"
mkdir -p "$home/.cache/xyz.mdhv.asom.ut"

set +e
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS HOME="$home" TMPDIR="$tmp" XDG_CACHE_HOME="$home/.cache" \
  "$java" -Duser.name=smoke "${opts[@]}" -jar "$base/lib/asom/asom-ut-node.jar" --selftest > "$work/out" 2> "$work/err"
rc=$?
set -e
echo "exit: $rc"

python3 - "$work/out" "$work/err" "$rc" "$repo" <<'E'
import json, os, sys
out, err, rc, repo = open(sys.argv[1]).read(), open(sys.argv[2]).read(), int(sys.argv[3]), sys.argv[4]
lines = out.splitlines()
problems = []
if rc != 0: problems.append(f"exit code {rc}")
if err != "": problems.append(f"stderr is not empty: {err[:300]!r}")
if len(lines) != 1: problems.append(f"{len(lines)} stdout lines, expected exactly one")
doc = {}
if lines:
    if not lines[0].startswith('{"selftest":"ok"'): problems.append("the line does not start with {\"selftest\":\"ok\": " + lines[0][:300])
    try: doc = json.loads(lines[0])
    except ValueError as e: problems.append(f"not JSON: {e}")
def count(family):
    n = 0
    for d in ("json", "manifest"):
        root = os.path.join(repo, "lab", "conformance", d)
        for f in os.listdir(root):
            if f.endswith(".json"):
                j = json.load(open(os.path.join(root, f), encoding="utf-8"))
                if j.get("family") == family:
                    n += sum(1 for v in j["vectors"] if v["status"] == "normative")
    return n
if doc:
    if doc.get("tls") != "TLSv1.3": problems.append(f"tls {doc.get('tls')!r}")
    if doc.get("alpn") != "asom-mesh/1": problems.append(f"alpn {doc.get('alpn')!r}")
    if doc.get("clientAuth") is not True: problems.append("no client certificate was seen")
    if doc.get("pinMismatchRefused") is not True: problems.append("a wrong pin was not refused")
    for fam in ("M01", "M02", "M03"):
        want = count(fam)
        if want == 0: problems.append(f"the lab has no {fam} vectors: the comparison would be vacuous")
        if doc.get("vectors", {}).get(fam) != want: problems.append(f"{fam}: self-test ran {doc.get('vectors', {}).get(fam)}, the lab has {want}")
    for key in ("thermalReadable", "batteryReadable", "rssKiB"):
        if key not in doc: problems.append(f"{key} was not recorded")
    print(f"selftest: {doc.get('selftest')} tls={doc.get('tls')} alpn={doc.get('alpn')} vectors={doc.get('vectors')} "
          f"thermalReadable={doc.get('thermalReadable')} batteryReadable={doc.get('batteryReadable')} rssKiB={doc.get('rssKiB')} "
          f"osArch={doc.get('runtime', {}).get('osArch')} java={doc.get('runtime', {}).get('javaVersion')}")
    print(lines[0])
for p in problems: print("VIOLATION: " + p)
print("arm64-selftest: " + ("FAILED" if problems else "OK") + "   [LAB / CI (hosted VM) - NOT DEVICE EVIDENCE]")
sys.exit(1 if problems else 0)
E
