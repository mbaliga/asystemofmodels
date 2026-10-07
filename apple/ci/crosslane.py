#!/usr/bin/env python3
"""Cross-lane check of the Swift signer (apple/crosslane): the JVM lane verifies what the Swift lane signed.

    crosslane.py jvm-lines --jvm-runner PATH --out FILE [--fixtures apple/crosslane]
    crosslane.py compare   --jvm-lines FILE --swift-lines FILE [--fixtures apple/crosslane]
    crosslane.py all       --jvm-runner PATH --swift-cli PATH [--fixtures apple/crosslane]

The fixture directory is a mini conformance directory (VERSION, manifest/M02-swift-signed.json, manifest/M03-swift-signed.json).
  jvm-lines  builds a scratch repository root `<tmp>/lab/conformance` that holds the fixture's VERSION and vector files only, runs the
             JVM lane's `lines M02,M03` on it (`-Dasom.repoRoot=<tmp>`; the lab is not edited) and writes the lines to FILE.
  compare    requires, per vector, that the JVM verdict, the Swift verdict (this lane's `lines M02,M03` over the fixture directory) and the
             verdict the fixture file records are the same line.
  all        both, running the JVM runner and the Swift CLI itself.
It exits non-zero on any difference, on a vector one side did not print, on an empty fixture, and when the fixtures do not carry both
ok and reject vectors (a check that could only ever see one outcome proves little). Evidence: LAB, oracle self, NOT DEVICE EVIDENCE.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile


def clean_env(extra=None):
    env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS" and not k.startswith("XDG_")}
    env["HOME"] = tempfile.mkdtemp(prefix="asom-crosslane-home-")
    if extra:
        env.update(extra)
    return env


def expected_lines(fixtures):
    out = {}
    mdir = os.path.join(fixtures, "manifest")
    for name in sorted(os.listdir(mdir)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(mdir, name), encoding="utf-8") as f:
            doc = json.load(f)
        for v in doc["vectors"]:
            e = v["expect"]
            out[v["id"]] = "ok" if "ok" in e else "reject " + e["reject"]
    return out


def parse_lines(text):
    lines = {}
    for raw in text.splitlines():
        if raw:
            vid, _, verdict = raw.partition(" ")
            if vid in lines:
                sys.exit("duplicate vector id " + vid)
            lines[vid] = verdict
    return lines


def run_lines(cmd, env):
    p = subprocess.run(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if p.returncode != 0:
        sys.exit("command failed (%d): %s\n%s" % (p.returncode, " ".join(cmd), p.stderr[-2000:]))
    return p.stdout


def jvm_lines_text(runner, fixtures):
    root = tempfile.mkdtemp(prefix="asom-crosslane-root-")
    try:
        conf = os.path.join(root, "lab", "conformance")
        os.makedirs(os.path.join(conf, "manifest"))
        shutil.copy(os.path.join(fixtures, "VERSION"), os.path.join(conf, "VERSION"))
        for name in os.listdir(os.path.join(fixtures, "manifest")):
            if name.endswith(".json"):
                shutil.copy(os.path.join(fixtures, "manifest", name), os.path.join(conf, "manifest", name))
        return run_lines([runner, "lines", "M02,M03"], clean_env({"JAVA_OPTS": "-Dasom.repoRoot=" + root}))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def compare(fixtures, jvm, swift):
    want = expected_lines(fixtures)
    problems = []
    if not want:
        problems.append("the fixture directory holds no vectors")
    for vid in sorted(want):
        j, s = jvm.get(vid), swift.get(vid)
        if j is None:
            problems.append("%s: the JVM lane printed nothing" % vid)
        if s is None:
            problems.append("%s: the Swift lane printed nothing" % vid)
        if j is not None and j != want[vid]:
            problems.append("%s: JVM says %r, the fixture records %r" % (vid, j, want[vid]))
        if s is not None and s != want[vid]:
            problems.append("%s: Swift says %r, the fixture records %r" % (vid, s, want[vid]))
        if j is not None and s is not None and j != s:
            problems.append("%s: JVM %r against Swift %r" % (vid, j, s))
    for vid in sorted(set(jvm) | set(swift)):
        if vid not in want:
            problems.append("%s: printed by a lane but not in the fixture files" % vid)
    oks = sum(1 for v in want.values() if v == "ok")
    rejects = len(want) - oks
    codes = sorted({v.split(" ", 1)[1] for v in want.values() if v != "ok"})
    if oks == 0 or rejects == 0:
        problems.append("the fixtures carry %d ok and %d reject vectors: both outcomes are required" % (oks, rejects))
    print("crosslane: %d vectors (%d ok, %d reject), reject codes %s" % (len(want), oks, rejects, ", ".join(codes)))
    agree = sum(1 for v in want if jvm.get(v) == swift.get(v) == want[v])
    print("crosslane: JVM, Swift and the fixture agree on %d of %d" % (agree, len(want)))
    for p in problems:
        print("PROBLEM: " + p)
    return 1 if problems else 0


def main(argv):
    args = argv[1:]
    if not args or args[0] not in ("jvm-lines", "compare", "all"):
        sys.exit(__doc__)
    mode = args.pop(0)
    opts = {"--fixtures": "apple/crosslane"}
    while args:
        flag = args.pop(0)
        if flag not in ("--jvm-runner", "--swift-cli", "--fixtures", "--out", "--jvm-lines", "--swift-lines") or not args:
            sys.exit(__doc__)
        opts[flag] = args.pop(0)
    fixtures = os.path.abspath(opts["--fixtures"])
    if mode == "jvm-lines":
        if "--jvm-runner" not in opts or "--out" not in opts:
            sys.exit(__doc__)
        text = jvm_lines_text(opts["--jvm-runner"], fixtures)
        with open(opts["--out"], "w", encoding="utf-8") as f:
            f.write(text)
        print("crosslane: JVM printed %d lines" % len(parse_lines(text)))
        return 0
    if mode == "compare":
        if "--jvm-lines" not in opts or "--swift-lines" not in opts:
            sys.exit(__doc__)
        with open(opts["--jvm-lines"], encoding="utf-8") as f:
            jvm = parse_lines(f.read())
        with open(opts["--swift-lines"], encoding="utf-8") as f:
            swift = parse_lines(f.read())
        return compare(fixtures, jvm, swift)
    if "--jvm-runner" not in opts or "--swift-cli" not in opts:
        sys.exit(__doc__)
    jvm = parse_lines(jvm_lines_text(opts["--jvm-runner"], fixtures))
    swift = parse_lines(run_lines([opts["--swift-cli"], "lines", "M02,M03"], clean_env({"ASOM_CONFORMANCE_DIR": fixtures})))
    return compare(fixtures, jvm, swift)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
