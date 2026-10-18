#!/usr/bin/env python3
"""Isolation check 4 against a PINNED base (LAB_SPEC 2.4 as amended by REVIEW_ROUND3 R3-CONFORMANCE-2), desktop copy.

This is the mechanism of lab/tools/isolation.py, copied (PLATFORM_PLAN P2/P3), with the pin in desktop/DESKTOP_BASE_SHA.

`git diff --exit-code` after a checkout compares the working tree with the index, which is always clean on a
fresh checkout, so it passes even when the change itself edits settings.gradle.kts, core/ or ci.yml. This check
instead compares every protected path, byte for byte, against the same path at the commit DESKTOP_BASE_SHA:

  * DESKTOP_BASE_SHA comes from the environment, else from desktop/DESKTOP_BASE_SHA;
  * it must exist locally and be an ancestor of HEAD, otherwise the check fails LOUDLY (a shallow clone cannot
    prove anything; use fetch-depth 0);
  * files added, removed or changed under a protected path, committed or not, tracked or not, fail the check.

What it still does not cover (stated, not hidden): a root edit that is itself part of the pinned base, and a
change to a protected path that a later, unrelated pin update blesses. The pin moves only by a reviewed edit to
desktop/DESKTOP_BASE_SHA.

Usage: isolation.py [--repo DIR] [--base SHA]      run the check
       isolation.py --selftest                     negative controls on a synthetic repository
Python 3 standard library only.
"""
import os
import re
import stat
import subprocess
import sys
import tempfile

PROTECTED = [
    "core", "server", "app", "vault", "pairing", "storage", "ledger", "client", "client-cloud", "sample-client",
    "gradle", "settings.gradle.kts", "build.gradle.kts", "gradle.properties", ".github/workflows/ci.yml",
]


class Loud(Exception):
    pass


def git(repo, *args, input_bytes=None, check=True):
    p = subprocess.run(["git", "-C", repo, *args], input=input_bytes, capture_output=True)
    if check and p.returncode != 0:
        raise Loud(f"git {' '.join(args)} failed: {p.stderr.decode(errors='replace').strip()}")
    return p


def resolve_base(repo, cli_base):
    base = cli_base or os.environ.get("DESKTOP_BASE_SHA")
    src = "--base" if cli_base else "environment DESKTOP_BASE_SHA"
    if not base:
        f = os.path.join(repo, "desktop", "DESKTOP_BASE_SHA")
        if not os.path.isfile(f):
            raise Loud("no base: set DESKTOP_BASE_SHA or provide desktop/DESKTOP_BASE_SHA")
        base = open(f, encoding="utf-8").read().strip()
        src = "desktop/DESKTOP_BASE_SHA"
    if not re.fullmatch(r"[0-9a-f]{40}", base):
        raise Loud(f"base '{base}' (from {src}) is not a full 40-hex commit id")
    if git(repo, "cat-file", "-e", f"{base}^{{commit}}", check=False).returncode != 0:
        raise Loud(f"base {base} does not exist in this clone; fetch full history (actions/checkout fetch-depth: 0)")
    if git(repo, "merge-base", "--is-ancestor", base, "HEAD", check=False).returncode != 0:
        raise Loud(f"base {base} is NOT an ancestor of HEAD: the pinned base does not describe this history")
    return base, src


def base_entries(repo, base, path):
    out = git(repo, "ls-tree", "-r", "-z", base, "--", path).stdout
    entries = {}
    for rec in out.split(b"\0"):
        if not rec:
            continue
        meta, name = rec.split(b"\t", 1)
        mode, _typ, sha = meta.decode().split()
        entries[name.decode()] = (mode, sha)
    return entries


def work_files(repo, path):
    out = git(repo, "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", path).stdout
    names = {n.decode() for n in out.split(b"\0") if n}
    return {n for n in names if os.path.lexists(os.path.join(repo, n))}


def check(repo, base, log=print):
    problems = []
    compared = 0
    for path in PROTECTED:
        want = base_entries(repo, base, path)
        have = work_files(repo, path)
        for n in sorted(set(want) - have):
            problems.append(f"{n}: present at base, missing now")
        for n in sorted(have - set(want)):
            problems.append(f"{n}: not at base, present now")
        common = sorted(set(want) & have)
        if common:
            paths = "\n".join(common).encode() + b"\n"
            hashes = git(repo, "hash-object", "--stdin-paths", input_bytes=paths).stdout.decode().split()
            for n, h in zip(common, hashes):
                mode, sha = want[n]
                full = os.path.join(repo, n)
                if mode == "120000":
                    if not os.path.islink(full):
                        problems.append(f"{n}: was a symlink at base")
                    compared += 1
                    continue
                if h != sha:
                    problems.append(f"{n}: content differs from base ({h[:12]} != {sha[:12]})")
                exec_now = bool(os.stat(full).st_mode & stat.S_IXUSR)
                if exec_now != (mode == "100755"):
                    problems.append(f"{n}: executable bit differs from base")
                compared += 1
    log(f"isolation check 4: {compared} protected files compared byte-for-byte against base {base[:12]}")
    return problems


def run(repo, cli_base):
    try:
        base, src = resolve_base(repo, cli_base)
    except Loud as e:
        print(f"isolation check 4: FAILED LOUDLY: {e}")
        return 2
    print(f"isolation check 4: base {base} (from {src}), ancestor of HEAD confirmed")
    try:
        problems = check(repo, base)
    except Loud as e:
        print(f"isolation check 4: FAILED LOUDLY: {e}")
        return 2
    if problems:
        print(f"isolation check 4: FAILED, {len(problems)} difference(s):")
        for p in problems[:50]:
            print("  " + p)
        return 1
    print("isolation check 4: OK (shipped tree byte-identical to the pinned base)")
    return 0


# ------------------------------------------------------------------ negative controls

def selftest():
    failures = []
    tmp = tempfile.mkdtemp(prefix="asom-isolation-selftest-")

    def sh(*args, check_ok=True):
        return git(tmp, "-c", "user.email=t@example.invalid", "-c", "user.name=t", "-c", "commit.gpgsign=false", *args, check=check_ok)

    def write(rel, text):
        full = os.path.join(tmp, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)

    def commit(msg):
        sh("add", "-A")
        sh("commit", "-q", "-m", msg)
        return sh("rev-parse", "HEAD").stdout.decode().strip()

    def expect(name, want_ok, base):
        try:
            b, _src = resolve_base(tmp, base)
            got_ok = not check(tmp, b, log=lambda *_: None)
        except Loud:
            got_ok = False
        status = "ok" if got_ok == want_ok else "WRONG"
        print(f"  selftest {name}: expected {'pass' if want_ok else 'fail'}, got {'pass' if got_ok else 'fail'} -> {status}")
        if got_ok != want_ok:
            failures.append(name)

    sh("init", "-q")
    for rel in ["core/contract/A.kt", "server/B.kt", "gradle/libs.versions.toml", "settings.gradle.kts",
                "build.gradle.kts", "gradle.properties", ".github/workflows/ci.yml", "app/C.kt", "desktop/DESKTOP_BASE_SHA"]:
        write(rel, f"base {rel}\n")
    base = commit("base")

    expect("unchanged tree", True, base)
    write("desktop/new.kt", "desktop only\n")
    commit("desktop-only change")
    expect("a later commit that touches only desktop/", True, base)

    write("settings.gradle.kts", "edited\n")
    expect("uncommitted edit of settings.gradle.kts", False, base)
    commit("edit settings")
    expect("COMMITTED edit of settings.gradle.kts (the case plain `git diff` after checkout misses)", False, base)
    sh("checkout", base, "--", "settings.gradle.kts")
    expect("settings.gradle.kts restored", True, base)

    write("core/contract/New.kt", "untracked\n")
    expect("untracked new file under core/", False, base)
    os.remove(os.path.join(tmp, "core/contract/New.kt"))
    os.remove(os.path.join(tmp, "server/B.kt"))
    expect("deleted file under server/", False, base)
    sh("checkout", base, "--", "server/B.kt")
    write(".github/workflows/ci.yml", "edited\n")
    commit("edit ci")
    expect("committed edit of .github/workflows/ci.yml", False, base)
    sh("checkout", base, "--", ".github/workflows/ci.yml")
    os.chmod(os.path.join(tmp, "app/C.kt"), 0o755)
    expect("executable bit flipped under app/", False, base)
    os.chmod(os.path.join(tmp, "app/C.kt"), 0o644)
    expect("executable bit restored", True, base)

    sh("checkout", "-q", "--orphan", "unrelated")
    write("other.txt", "x\n")
    sh("add", "other.txt")
    sh("commit", "-q", "-m", "unrelated root")
    expect("a base that is not an ancestor of HEAD", False, base)
    expect("a base that does not exist", False, "0" * 40)

    if failures:
        print(f"selftest FAILED: {failures}")
        return 1
    print("selftest OK: the pinned-base check fails on every tampering case and passes on the honest ones")
    return 0


def main(argv):
    if "--selftest" in argv:
        return selftest()
    repo = None
    base = None
    for i, a in enumerate(argv):
        if a == "--repo" and i + 1 < len(argv):
            repo = argv[i + 1]
        if a == "--base" and i + 1 < len(argv):
            base = argv[i + 1]
    if repo is None:
        repo = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True).stdout.strip() or "."
    return run(repo, base)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
