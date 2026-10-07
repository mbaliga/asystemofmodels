#!/usr/bin/env bash
# build-packages.sh: the distributable files from an app image (linux.md 8.1): the .tar.gz for the per-user route, the .deb
# and the .rpm (nfpm), the two installers and SHA256SUMS over all of them.
#
#   build-packages.sh --image <asom-desktop-<ver>-linux-<arch> dir> [--out <dir>] [--formats tar,deb,rpm] [--nfpm <binary>]
#
#   --out      default: <image dir>/../dist
#   --formats  default: tar,deb,rpm
#   --nfpm     default: the pinned, checksum-verified download (fetch-nfpm.sh). An nfpm on PATH is NOT used unless named here.
#
# UNSIGNED: nothing is signed, attested or notarised here. SHA256SUMS is not a signature; the owner signs it offline,
# outside CI (linux.md 8.3). Every output dir gets UNSIGNED-NOT-FOR-RELEASE.txt.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
image=""
out=""
formats="tar,deb,rpm"
nfpm_bin=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --image) image="${2:-}"; shift 2 ;;
    --out) out="${2:-}"; shift 2 ;;
    --formats) formats="${2:-}"; shift 2 ;;
    --nfpm) nfpm_bin="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "build-packages: unknown argument: $1" >&2; exit 2 ;;
  esac
done
die() { echo "build-packages: $1" >&2; exit "${2:-1}"; }

[ -d "$image/lib/runtime" ] && [ -x "$image/bin/asom-node" ] || die "--image must be an app image made by build-app-image.sh" 2
image="$(cd "$image" && pwd)"
name="$(basename "$image")"
case "$name" in asom-desktop-*-linux-x86_64|asom-desktop-*-linux-aarch64) ;; *) die "unexpected image directory name '$name'" 2 ;; esac
arch="${name##*-linux-}"
ver="${name#asom-desktop-}"; ver="${ver%-linux-*}"
case "$arch" in x86_64) deb_arch=amd64 ;; aarch64) deb_arch=arm64 ;; esac
[ -n "$out" ] || out="$(dirname "$image")/dist"
mkdir -p "$out"
out="$(cd "$out" && pwd)"

want() { case ",$formats," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

made=()
if want tar; then
  rm -f "$out"/*.tar.gz
  tar -czf "$out/$name.tar.gz" --owner=0 --group=0 --numeric-owner --sort=name -C "$(dirname "$image")" "$name"
  made+=("$name.tar.gz")
  cp "$here/install.sh" "$here/uninstall.sh" "$out/"
  made+=("install.sh" "uninstall.sh")
fi

if want deb || want rpm; then
  if [ -z "$nfpm_bin" ]; then
    nfpm_bin="$("$here/fetch-nfpm.sh" | tail -1)"
    echo "build-packages: nfpm route: pinned download, checksum-verified by fetch-nfpm.sh"
  else
    echo "build-packages: nfpm route: --nfpm given explicitly, NOT verified by this script"
  fi
  [ -x "$nfpm_bin" ] || die "nfpm not found at '$nfpm_bin'" 2
  echo "build-packages: nfpm $("$nfpm_bin" --version | sed -n 's/^GitVersion: *//p') at $nfpm_bin"

  stage="$(mktemp -d "$out/.stage.XXXXXX")"
  trap 'rm -rf "$stage"' EXIT
  cp -a "$image" "$stage/$name"
  rm -f "$stage/$name/install.sh" "$stage/$name/uninstall.sh"
  export ASOM_VERSION="$ver" ASOM_ARCH="$deb_arch" ASOM_STAGE="$stage/$name" ASOM_LINUX_DIR="$here"
  # nfpm 2.41 does not expand ${VAR} in contents src/dst, so every variable is resolved here, before nfpm sees the file.
  yaml="$(cat "$here/nfpm.yaml")"
  for v in ASOM_VERSION ASOM_ARCH ASOM_STAGE ASOM_LINUX_DIR; do yaml="${yaml//\$\{$v\}/${!v}}"; done
  case "$yaml" in *'${'*) die "nfpm.yaml still holds an unresolved \${...} after substitution" 1 ;; esac
  printf '%s\n' "$yaml" > "$stage/nfpm.resolved.yaml"
  for f in deb rpm; do
    want "$f" || continue
    rm -f "$out"/*."$f"
    "$nfpm_bin" package -f "$stage/nfpm.resolved.yaml" -p "$f" -t "$out"
    pkg="$(ls "$out"/*."$f")"
    [ "$(printf '%s\n' "$pkg" | wc -l)" = "1" ] || die "expected one .$f in $out, found: $pkg" 1
    made+=("$(basename "$pkg")")
  done
fi

cat > "$out/UNSIGNED-NOT-FOR-RELEASE.txt" <<EOF
These files were built by desktop/packaging/linux/build-packages.sh. They are UNSIGNED, not attested and not for release.
SHA256SUMS lists checksums only: it is not a signature. The signing key is the owner's, offline (linux.md 8.3).
EOF

( cd "$out" && sha256sum "${made[@]}" > SHA256SUMS )
echo "build-packages: wrote into $out:"
( cd "$out" && ls -l "${made[@]}" SHA256SUMS | awk '{print "  " $5 "\t" $9}' )
