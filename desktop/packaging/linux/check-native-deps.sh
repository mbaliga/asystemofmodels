#!/usr/bin/env bash
# check-native-deps.sh: which shared libraries the app image's own ELF files ask the distro for.
#
# The package declares Depends on exactly these system libraries (nfpm.yaml: libc6, libstdc++6, libgcc-s1, zlib1g and the
# rpm equivalents). If a different JDK vendor or build starts needing another one (fontconfig, freetype, alsa, X11...),
# this fails, so the Depends line is corrected instead of the install failing on a bare server.
#
# Usage: check-native-deps.sh <app image dir>       Needs readelf (binutils).
set -euo pipefail
img="${1:-}"
[ -d "$img/lib/runtime" ] || { echo "usage: check-native-deps.sh <app image dir>" >&2; exit 2; }
command -v readelf >/dev/null 2>&1 || { echo "check-native-deps: needs readelf (binutils)" >&2; exit 2; }

allowed=" libc.so.6 libm.so.6 libdl.so.2 libpthread.so.0 librt.so.1 libz.so.1 libstdc++.so.6 libgcc_s.so.1 ld-linux-x86-64.so.2 ld-linux-aarch64.so.1 "
provided="$(find "$img" -type f -name '*.so*' -printf '%f\n' | sort -u)"
needed_all=""
n=0
while IFS= read -r f; do
  head -c4 "$f" | grep -q ELF || continue
  n=$((n + 1))
  needed_all="$needed_all
$(readelf -d "$f" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')"
done < <(find "$img" -type f \( -name '*.so' -o -name '*.so.*' -o -path '*/bin/*' \))
[ "$n" -gt 0 ] || { echo "check-native-deps: found no ELF files under $img (refusing to pass by scanning nothing)" >&2; exit 2; }

bad=0
sys=""
for lib in $(printf '%s\n' "$needed_all" | sed '/^$/d' | sort -u); do
  if printf '%s\n' "$provided" | grep -qx "$lib"; then continue; fi
  sys="$sys $lib"
  case "$allowed" in *" $lib "*) ;; *) echo "UNDECLARED system library: $lib (add it to Depends in nfpm.yaml and to this list, or fix the runtime)"; bad=1 ;; esac
done
echo "check-native-deps: scanned $n ELF files; system libraries needed:$sys"
if [ "$bad" = "0" ]; then echo "check-native-deps: OK (all within the declared Depends set)"; fi
exit "$bad"
