#!/usr/bin/env bash
# UT0.2: cross-jlink an aarch64 Temurin 21 runtime for the Ubuntu Touch click from the pinned aarch64 JDK's jmods, using the
# host's own (x86_64 or aarch64) JDK 21 jlink. Nothing is executed from the aarch64 image: it is inspected, never run.
#
#   ubuntu-touch/runtime/jlink.sh
#
# Steps (any failure exits non-zero and prints why):
#   1. read temurin.lock; download the tarball unless the cache holds a file with the pinned sha256; refuse any other file
#   2. extract only the jmods; require the host jlink to be JDK 21
#   3. jlink the modules of jlink-modules.txt; strip Java debug info and, with the cross objcopy, native debug symbols
#      (put first in PATH: see ERR-UT-JLINK-1)
#   4. assert: the resolved module set equals jlink-modules.txt; size <= 45 MB; bin/java is `ARM aarch64`;
#      no ELF file keeps .debug_* sections; the highest GLIBC symbol version any ELF file references is <= 2.17
#   5. write rt.sha256 (sha256sum manifest of every file in the image; the plugin verifies it before it spawns the JVM)
#
# Output goes to $ASOM_UT_STAGE (default ubuntu-touch/runtime/stage): rt/ and rt.sha256. UNSIGNED, not for release.
# Environment: ASOM_UT_CACHE (download cache), ASOM_UT_STAGE, ASOM_UT_OBJCOPY (default aarch64-linux-gnu-objcopy),
# ASOM_UT_OBJDUMP (default aarch64-linux-gnu-objdump), ASOM_UT_RT_MAX_MB (45), ASOM_UT_MAX_GLIBC (2.17), JAVA_HOME (optional).
set -euo pipefail
export LC_ALL=C

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lock="$here/temurin.lock"
modules_file="$here/jlink-modules.txt"
cache="${ASOM_UT_CACHE:-$here/.cache}"
stage="${ASOM_UT_STAGE:-$here/stage}"
max_mb="${ASOM_UT_RT_MAX_MB:-45}"
max_glibc="${ASOM_UT_MAX_GLIBC:-2.17}"
objcopy="${ASOM_UT_OBJCOPY:-aarch64-linux-gnu-objcopy}"
objdump="${ASOM_UT_OBJDUMP:-aarch64-linux-gnu-objdump}"

die() { printf 'jlink.sh: %s\n' "$*" >&2; exit 1; }
lockval() { grep -E "^$1=" "$lock" | head -1 | cut -d= -f2-; }

url="$(lockval url)"; want_sha="$(lockval sha256)"; release="$(lockval release)"
[ -n "$url" ] && [ -n "$want_sha" ] && [ -n "$release" ] || die "temurin.lock is incomplete"
expected_modules="$(tr -d ' \n' < "$modules_file")"
[ -n "$expected_modules" ] || die "jlink-modules.txt is empty"

for tool in tar sha256sum curl file du sort; do command -v "$tool" >/dev/null || die "missing tool: $tool"; done
command -v "$objcopy" >/dev/null || die "missing $objcopy (apt-get install binutils-aarch64-linux-gnu): the host objcopy cannot strip aarch64 objects (UF37)"
command -v "$objdump" >/dev/null || die "missing $objdump (apt-get install binutils-aarch64-linux-gnu)"
jlink="${JAVA_HOME:+$JAVA_HOME/bin/}jlink"
command -v "$jlink" >/dev/null || die "no jlink found (set JAVA_HOME to a JDK 21)"
host_major="$("$jlink" --version | grep -Eo '^[0-9]+' | head -1)"
[ "$host_major" = "21" ] || die "the host jlink is JDK $host_major; JDK 21 is required"

mkdir -p "$cache" "$stage"
tarball="$cache/$(basename "$url")"
have_sha=""
[ -f "$tarball" ] && have_sha="$(sha256sum "$tarball" | cut -d' ' -f1)"
if [ "$have_sha" != "$want_sha" ]; then
  rm -f "$tarball"
  echo "downloading $(basename "$url")"
  curl -fsSL --retry 3 --retry-delay 2 -o "$tarball.part" "$url" || { rm -f "$tarball.part"; die "download failed: $url"; }
  got_sha="$(sha256sum "$tarball.part" | cut -d' ' -f1)"
  if [ "$got_sha" != "$want_sha" ]; then
    rm -f "$tarball.part"
    die "sha256 mismatch for $url: pinned $want_sha, downloaded $got_sha"
  fi
  mv "$tarball.part" "$tarball"
fi
echo "temurin: $(basename "$tarball") sha256 $want_sha (matches temurin.lock)"

work="$stage/.jdk"
rm -rf "$work" "$stage/rt" "$stage/rt.sha256"
mkdir -p "$work"
tar -xzf "$tarball" -C "$work" --wildcards '*/jmods/*' '*/release'
jmods="$(find "$work" -maxdepth 2 -type d -name jmods | head -1)"
[ -d "$jmods" ] || die "no jmods directory in the tarball"
grep -q 'aarch64' "$(dirname "$jmods")/release" || die "the pinned JDK's release file does not say aarch64"

# JDK 21's jlink IGNORES `--strip-native-debug-symbols=objcopy=<path>` (verified: pointing it at /bin/false changes nothing, the
# host objcopy in PATH is what runs, and it cannot read aarch64 objects). So the cross objcopy is put first in PATH under the name
# `objcopy`, which is the one thing the plugin does honour (ubuntu-touch/ERRATA.md ERR-UT-JLINK-1).
shim="$(mktemp -d)"
trap 'rm -rf "$shim"' EXIT
ln -s "$(command -v "$objcopy")" "$shim/objcopy"
PATH="$shim:$PATH" "$jlink" --module-path "$jmods" --add-modules "$expected_modules" --output "$stage/rt" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-9 --strip-native-debug-symbols=exclude-debuginfo-files
rm -rf "$work"

size_mb="$(du -sm "$stage/rt" | cut -f1)"
echo "rt: ${size_mb} MB"
[ "$size_mb" -le "$max_mb" ] || die "the runtime is ${size_mb} MB, over the ${max_mb} MB limit"

file_out="$(file -b "$stage/rt/bin/java")"
echo "file bin/java: $file_out"
printf '%s' "$file_out" | grep -q 'ARM aarch64' || die "bin/java is not an ARM aarch64 executable"

resolved="$(grep '^MODULES=' "$stage/rt/release" | sed 's/^MODULES="//; s/"$//' | tr ' ' '\n' | sort | paste -sd,)"
expected_sorted="$(printf '%s' "$expected_modules" | tr ',' '\n' | sort | paste -sd,)"
echo "modules: $resolved"
[ "$resolved" = "$expected_sorted" ] || die "the resolved module set differs from jlink-modules.txt (drift): resolved [$resolved], listed [$expected_sorted]"

max_seen="0"
elf_count=0
debug_files=0
while IFS= read -r -d '' f; do
  file -b "$f" | grep -q '^ELF' || continue
  elf_count=$((elf_count + 1))
  if "$objdump" -h "$f" 2>/dev/null | grep -q '\.debug_'; then debug_files=$((debug_files + 1)); fi
  v="$("$objdump" -T "$f" 2>/dev/null | grep -Eo 'GLIBC_[0-9]+(\.[0-9]+)*' | sed 's/^GLIBC_//' | sort -V | tail -1 || true)"
  [ -n "$v" ] || continue
  if [ "$(printf '%s\n%s\n' "$max_seen" "$v" | sort -V | tail -1)" = "$v" ]; then max_seen="$v"; fi
done < <(find "$stage/rt" -type f -print0)
[ "$elf_count" -gt 0 ] || die "no ELF file found in the image (the GLIBC check would be vacuous)"
echo "ELF files with .debug_* sections: $debug_files   (over $elf_count ELF files)"
[ "$debug_files" -eq 0 ] || die "$debug_files ELF files still carry debug sections"
echo "max GLIBC: $max_seen   (over $elf_count ELF files)"
[ "$(printf '%s\n%s\n' "$max_seen" "$max_glibc" | sort -V | tail -1)" = "$max_glibc" ] || die "an ELF file needs GLIBC_$max_seen, above the allowed $max_glibc"

( cd "$stage/rt" && find . -type f -print0 | sort -z | xargs -0 sha256sum ) > "$stage/rt.sha256"
echo "rt.sha256: $(wc -l < "$stage/rt.sha256") files, manifest sha256 $(sha256sum "$stage/rt.sha256" | cut -d' ' -f1)"
echo "runtime: aarch64 Temurin $release, jlinked from $expected_modules. UNSIGNED, not for release. LAB, NOT DEVICE EVIDENCE (this image was inspected, never run)."
