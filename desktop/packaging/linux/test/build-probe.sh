#!/usr/bin/env bash
# build-probe.sh: compile test/probe/RuntimeProbe.java and make its throwaway EC test key (PKCS12, password `changeit`,
# CN=localhost, valid 2 days, regenerated every run, protects nothing). Needs a JDK (javac, jar, keytool) on the BUILD
# machine only; the probe then runs on the shipped runtime, which has no compiler.
#
# Usage: build-probe.sh <out-dir>     -> <out-dir>/probe.jar and <out-dir>/probe-ec.p12
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${1:-}"
[ -n "$out" ] || { echo "usage: build-probe.sh <out-dir>" >&2; exit 2; }
for t in javac jar keytool; do command -v "$t" >/dev/null 2>&1 || { echo "build-probe: needs '$t' (a JDK) on PATH" >&2; exit 2; }; done
mkdir -p "$out"
out="$(cd "$out" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
javac --release 17 -d "$work/classes" "$here/probe/RuntimeProbe.java"
jar --create --file "$out/probe.jar" --main-class RuntimeProbe -C "$work/classes" .
rm -f "$out/probe-ec.p12"
keytool -genkeypair -alias probe -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
  -dname "CN=localhost" -ext "san=dns:localhost" -validity 2 \
  -storetype PKCS12 -keystore "$out/probe-ec.p12" -storepass changeit -keypass changeit >/dev/null 2>&1
echo "probe built: $out/probe.jar $out/probe-ec.p12"
