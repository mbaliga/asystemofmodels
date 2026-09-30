#!/usr/bin/env python3
"""Builds deliberately malformed asom-desktop archives for lab-packaging-check.sh (standard library only).

    make-hostile-archives.py <out-dir> <arch>

Each archive has a matching line in <out-dir>/SHA256SUMS, so a refusal can only come from install.sh's own inspection of
the archive, never from the checksum. Prints one line per archive: <file>|<regex the refusal message must match>.
"""
import hashlib
import io
import os
import sys
import tarfile


def add_file(tf, name, data=b"x\n", mode=0o755):
    ti = tarfile.TarInfo(name)
    ti.size = len(data)
    ti.mode = mode
    tf.addfile(ti, io.BytesIO(data))


def add_dir(tf, name):
    ti = tarfile.TarInfo(name)
    ti.type = tarfile.DIRTYPE
    ti.mode = 0o755
    tf.addfile(ti)


def add_link(tf, name, target, hard=False):
    ti = tarfile.TarInfo(name)
    ti.type = tarfile.LNKTYPE if hard else tarfile.SYMTYPE
    ti.linkname = target
    ti.mode = 0o777
    tf.addfile(ti)


def valid_body(tf, top):
    add_dir(tf, top + "/")
    for d in ("bin", "lib", "lib/runtime", "lib/runtime/bin", "share", "share/systemd"):
        add_dir(tf, f"{top}/{d}/")
    add_file(tf, f"{top}/bin/asom-node")
    add_file(tf, f"{top}/bin/asom")
    add_file(tf, f"{top}/lib/runtime/bin/java")
    add_file(tf, f"{top}/share/systemd/asom-user.service", b"[Service]\n", 0o644)


def main():
    out, arch = sys.argv[1], sys.argv[2]
    other = "aarch64" if arch == "x86_64" else "x86_64"
    top = f"asom-desktop-9.9.9-hostile-linux-{arch}"
    os.makedirs(out, exist_ok=True)
    cases = []

    def make(fname, build, expect):
        path = os.path.join(out, fname)
        with tarfile.open(path, "w:gz", format=tarfile.GNU_FORMAT) as tf:
            build(tf)
        cases.append((fname, expect, hashlib.sha256(open(path, "rb").read()).hexdigest()))

    def dotdot(tf):
        valid_body(tf, top)
        add_file(tf, top + "/../../pwned-dotdot", b"pwned\n")

    def absolute(tf):
        valid_body(tf, top)
        add_file(tf, "/tmp/asom-pwned-absolute", b"pwned\n")

    def symlink_escape(tf):
        valid_body(tf, top)
        add_link(tf, top + "/lib/escape", "../../../../../../etc")

    def symlink_abs(tf):
        valid_body(tf, top)
        add_link(tf, top + "/lib/escape", "/etc")

    def hardlink(tf):
        valid_body(tf, top)
        add_link(tf, top + "/lib/hard", "/etc/passwd", hard=True)

    def wrong_arch(tf):
        valid_body(tf, f"asom-desktop-9.9.9-hostile-linux-{other}")

    def two_tops(tf):
        valid_body(tf, top)
        add_file(tf, "second-top/file")

    def not_asom(tf):
        add_dir(tf, top + "/")
        add_file(tf, top + "/readme.txt")

    def bad_name(tf):
        valid_body(tf, "evil-directory")

    make("hostile-dotdot.tar.gz", dotdot, "absolute path or a '..' component")
    make("hostile-absolute.tar.gz", absolute, "more than one top-level entry|absolute path or a '..' component")
    make("hostile-symlink-escape.tar.gz", symlink_escape, "symlink that leaves the image")
    make("hostile-symlink-absolute.tar.gz", symlink_abs, "absolute symlink")
    make("hostile-hardlink.tar.gz", hardlink, "hard link")
    make("hostile-wrong-arch.tar.gz", wrong_arch, "different architecture")
    make("hostile-two-tops.tar.gz", two_tops, "more than one top-level entry")
    make("hostile-not-asom.tar.gz", not_asom, "not an asom app image")
    make("hostile-bad-name.tar.gz", bad_name, "unexpected top-level directory")

    with open(os.path.join(out, "SHA256SUMS"), "w") as f:
        for fname, _, digest in cases:
            f.write(f"{digest}  {fname}\n")
    for fname, expect, _ in cases:
        print(f"{fname}|{expect}")


if __name__ == "__main__":
    main()
