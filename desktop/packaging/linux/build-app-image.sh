#!/usr/bin/env bash
# build-app-image.sh: the asom-desktop Linux app image (PLATFORM_PLAN 3 DL3; linux.md 7.1 and 8.1).
#
#   jlink (a JDK 21 runtime, modules from jlink-modules.txt) + jpackage --type app-image (launchers asom-node and asom)
#   + the units, sysusers.d file, polkit rule, docs and the user-local installers.
#
# Usage: build-app-image.sh --arch x86_64|aarch64 [--version <ver>] [--out <dir>] [--app-lib <dir>] [--no-gradle]
#   --arch      required. jlink and jpackage build for the JDK's own platform only, so it must equal `uname -m`
#               (aarch64 is built on an aarch64 runner; there is no cross build).
#   --version   default: NodeVersion.STRING in desktop/node-core/.../NodeEnv.kt (the single source of the version).
#   --out       default: desktop/packaging/linux/build
#   --app-lib   the node jars (default: desktop/node/build/install/asom-node/lib, produced by `:node:installDist`).
#   --no-gradle do not run Gradle; use --app-lib (or the default directory) as it is.
#   ASOM_GRADLE_FLAGS  extra flags for the Gradle call (for example --no-daemon --max-workers=2).
#
# Output (last lines):  app image: build/asom-desktop-<ver>-linux-<arch>
# NEEDS JDK 21 (jlink, jpackage, jdeps): CI uses Temurin 21 (LD-3). A different vendor is reported, not hidden.
# UNSIGNED: nothing is signed here and the result is not a release.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
# shellcheck source=modules.sh
. "$here/modules.sh"

arch=""
ver=""
out="$here/build"
app_lib="$repo/desktop/node/build/install/asom-node/lib"
run_gradle=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    --arch) arch="${2:-}"; shift 2 ;;
    --version) ver="${2:-}"; shift 2 ;;
    --out) out="${2:-}"; shift 2 ;;
    --app-lib) app_lib="${2:-}"; shift 2 ;;
    --no-gradle) run_gradle=0; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "build-app-image: unknown argument: $1" >&2; exit 2 ;;
  esac
done

die() { echo "build-app-image: $1" >&2; exit "${2:-1}"; }

[ -n "$arch" ] || die "--arch is required (x86_64 or aarch64)" 2
case "$arch" in x86_64|aarch64) ;; *) die "--arch must be x86_64 or aarch64, not '$arch'" 2 ;; esac
[ "$(uname -m)" = "$arch" ] || die "host is $(uname -m) but --arch is $arch: jlink and jpackage cannot cross-build; run on a $arch machine" 2

if [ -z "$ver" ]; then
  ver="$(sed -n 's/^[[:space:]]*const val STRING = "\([^"]*\)".*/\1/p' "$repo/desktop/node-core/src/main/kotlin/xyz/mdhv/asom/desktop/NodeEnv.kt" | head -1)"
  [ -n "$ver" ] || die "could not read NodeVersion.STRING from NodeEnv.kt; pass --version" 2
fi
case "$ver" in *[!0-9A-Za-z.+~_-]*|"") die "version '$ver' has characters that are not safe in a path or a package version" 2 ;; esac

jdk_bin=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jlink" ]; then jdk_bin="$JAVA_HOME/bin"; else
  j="$(command -v jlink || true)"; [ -n "$j" ] && jdk_bin="$(dirname "$(readlink -f "$j")")"
fi
[ -n "$jdk_bin" ] && [ -x "$jdk_bin/jpackage" ] && [ -x "$jdk_bin/jdeps" ] || die "no JDK with jlink, jpackage and jdeps found (set JAVA_HOME to a JDK 21)" 2
jdk_home="$(dirname "$jdk_bin")"
jdk_ver="$("$jdk_bin/jlink" --version 2>/dev/null | head -1)"
case "$jdk_ver" in 21|21.*) ;; *) die "JDK is '$jdk_ver'; the shipped runtime is 21 (linux.md 7.1)" 2 ;; esac
jdk_vendor="$(sed -n 's/^IMPLEMENTOR="\(.*\)"/\1/p' "$jdk_home/release" 2>/dev/null | head -1)"
[ -n "$jdk_vendor" ] || jdk_vendor="unknown"
case "$jdk_vendor" in
  *Adoptium*|*Temurin*) ;;
  *) echo "build-app-image: WARNING runtime vendor is '$jdk_vendor', not Eclipse Temurin (LD-3). This image is LAB evidence only." >&2 ;;
esac

if [ "$run_gradle" = "1" ]; then
  echo "build-app-image: ./gradlew -p desktop :node:installDist ${ASOM_GRADLE_FLAGS:-}"
  # shellcheck disable=SC2086
  (cd "$repo" && ./gradlew -p desktop :node:installDist ${ASOM_GRADLE_FLAGS:-})
fi
ls "$app_lib"/node.jar "$app_lib"/node-core.jar >/dev/null 2>&1 || die "$app_lib does not hold the node jars (run :node:installDist, or pass --app-lib)" 2

echo "build-app-image: checking jlink-modules.txt against jdeps"
JAVA_HOME="$jdk_home" "$here/check-jlink-modules.sh" --lib "$app_lib" || die "jlink-modules.txt drifted from what the jars need" 1
modules="$( { modules_section "$here/jlink-modules.txt" derived; modules_section "$here/jlink-modules.txt" added; } | LC_ALL=C sort -u | modules_join)"

name="asom-desktop-$ver-linux-$arch"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
work="$(mktemp -d "$out/.work.XXXXXX")"
trap 'rm -rf "$work"' EXIT
umask 022

echo "build-app-image: jlink --add-modules $modules"
"$jdk_bin/jlink" --add-modules "$modules" --output "$work/runtime" \
  --strip-debug --no-header-files --no-man-pages --compress zip-6

mkdir -p "$work/input"
cp "$app_lib"/*.jar "$work/input/"

# JVM options are baked into the launcher (linux.md 3.2) so that systemd never expands a specifier inside them.
# -XX:ErrorFile: the crash log goes to the null device. linux.md 3.2 asks for a path under the state dir, but the state dir
# differs per mode (SYSTEM /var/lib/asom, USER $XDG_STATE_HOME/asom) and a jpackage launcher expands no environment
# variable; a wrong path would make the JVM fall back to the working directory or /tmp, where an hs_err file (environment,
# registers, stack fragments) would sit world-readable. See desktop/packaging/linux/ERRATA.md ERR-DL3-3.
jvm_opts=(-Dfile.encoding=UTF-8 -XX:-CreateCoredumpOnCrash -XX:ErrorFile=/dev/null -Xmx384m)
jp_opts=()
for o in "${jvm_opts[@]}"; do jp_opts+=(--java-options "$o"); done

cat > "$work/asom-cli.properties" <<EOF
main-jar=node.jar
main-class=xyz.mdhv.asom.desktop.cli.AsomCliMainKt
description=asom owner CLI
EOF

echo "build-app-image: jpackage --type app-image"
"$jdk_bin/jpackage" --type app-image --name asom-node \
  --input "$work/input" --main-jar node.jar --main-class xyz.mdhv.asom.desktop.MainKt \
  --runtime-image "$work/runtime" --dest "$work/dest" \
  --app-version "$ver" --vendor "asom (UNSIGNED, not for release)" \
  --description "asom node: sovereign model routing; lends compute only when the owner turns it on" \
  --add-launcher "asom=$work/asom-cli.properties" \
  "${jp_opts[@]}"

img="$work/dest/asom-node"
[ -x "$img/bin/asom-node" ] && [ -x "$img/bin/asom" ] || die "jpackage did not produce bin/asom-node and bin/asom under $img" 1
[ -d "$img/lib/runtime" ] || die "jpackage did not embed the runtime" 1

share="$img/share"
mkdir -p "$share/systemd" "$share/sysusers.d" "$share/polkit" "$share/doc"
install -m 0644 "$here/systemd/asom.service" "$share/systemd/asom.service"
install -m 0644 "$here/systemd/asom-user.service" "$share/systemd/asom-user.service"
install -m 0644 "$here/sysusers.d/asom.conf" "$share/sysusers.d/asom.conf"
install -m 0644 "$here/polkit/50-asom-inhibit.rules" "$share/polkit/50-asom-inhibit.rules"
for d in LINUX.md STEAM_DECK.md; do
  if [ -f "$repo/desktop/docs/$d" ]; then install -m 0644 "$repo/desktop/docs/$d" "$share/doc/$d"; fi
done
install -m 0644 "$repo/LICENSE" "$share/doc/LICENSE"
install -m 0755 "$here/install.sh" "$img/install.sh"
install -m 0755 "$here/uninstall.sh" "$img/uninstall.sh"

{
  echo "asom-desktop $ver (linux-$arch): UNSIGNED, NOT FOR RELEASE."
  echo
  echo "This image was built by desktop/packaging/linux/build-app-image.sh. It is not signed, not attested and not"
  echo "reproducibility-checked. The bundled Java runtime is a jlink image of the JDK named in BUILD-INFO.txt; its own"
  echo "licence texts are under lib/runtime/legal/. asom is Apache-2.0 (LICENSE)."
} > "$share/doc/NOTICE"

{
  echo "Bundled jars (lib/app). This is an inventory with checksums, NOT a licence audit."
  echo
  ( cd "$img/lib/app" && for j in *.jar; do printf '%s  %s\n' "$(sha256sum "$j" | cut -d' ' -f1)" "$j"; done )
  echo
  echo "Bundled Java runtime: $jdk_vendor $jdk_ver (jlink image; licences in lib/runtime/legal/)."
} > "$share/doc/THIRD-PARTY.txt"

{
  echo "version:        $ver"
  echo "arch:           $arch"
  echo "runtime:        $jdk_vendor $jdk_ver"
  echo "jlink modules:  $modules"
  echo "jvm options:    ${jvm_opts[*]}"
  echo "signed:         no (UNSIGNED, not for release)"
  echo "built by:       desktop/packaging/linux/build-app-image.sh"
} > "$share/doc/BUILD-INFO.txt"

# The runtime must really carry what jlink-modules.txt promises (a list that compiles is not a list that runs).
listed="$("$img/lib/runtime/bin/java" --list-modules | cut -d@ -f1)"
for m in $(modules_section "$here/jlink-modules.txt" derived) $(modules_section "$here/jlink-modules.txt" added); do
  printf '%s\n' "$listed" | grep -qx "$m" || die "the runtime image lacks module $m" 1
done
for m in $(modules_section "$here/jlink-modules.txt" excluded); do
  printf '%s\n' "$listed" | grep -qx "$m" && die "the runtime image contains excluded module $m" 1
done

rm -rf "${out:?}/$name"
mv "$img" "$out/$name"
"$here/check-native-deps.sh" "$out/$name" || die "the image needs a system library the package does not declare" 1
echo "build-app-image: runtime $jdk_vendor $jdk_ver, $(printf '%s\n' "$listed" | wc -l) modules in the image, $(du -sh "$out/$name" | cut -f1) on disk"
banner="$("$out/$name/bin/asom-node" --version)"
echo "build-app-image: launcher says: $banner"
echo "app image path: $out/$name"
case "$out" in
  "$here/build") echo "app image: build/$name" ;;
  *) echo "app image: $out/$name" ;;
esac
