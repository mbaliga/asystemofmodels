#!/usr/bin/env python3
"""Runs one scripted UI session against tests/fake_node.py and against the JVM node's `--fake-ui`, and fails on any difference in
the frames they print (or in the exit code). The QML tests use the Python fake (no JVM in the QML container); this ties it to
the JVM code that the UTC vectors pin. Usage: check_fake_nodes.py <path to asom-ut-node.jar> [java] [--limit-modules M1,M2]. Standard library only."""
import os
import subprocess
import sys
import tempfile
import threading

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
FAKE = os.path.join(ROOT, "tests", "fake_node.py")


def borrow(rid, model):
    return '{"t":"borrow","rid":"%s","model":"%s","messages":[{"role":"user","content":"hi"}],"maxTokens":8,"stream":true}' % (rid, model)


SCRIPT = [
    '{"t":"hello","v":1}',
    borrow("r0", "fake"),                                  # UI not yet active: refused
    '{"t":"peers","op":"open"}',                           # refused too
    '{"t":"lifecycle","state":"active"}',
    borrow("r1", "fake"),
    borrow("r2", "no-such-model"),
    borrow("r3", "fake-cooling"),
    borrow("r4", "local-only"),
    borrow("r5", "fake-interrupt"),
    borrow("r6", "auto"),
    '{"t":"peers","op":"open"}',
    '{"t":"peers","op":"close"}',
    '{"t":"pair","op":"begin","value":"x"}',
    '{"t":"revoke","peer":"p"}',
    '{"t":"export","kind":"ledger"}',
    '{"t":"ledger","since":0,"limit":10}',
    '{"t":"selftest"}',
    '{"t":"cancel","rid":"r1"}',
    '{"t":"lifecycle","state":"inactive"}',
    borrow("r7", "fake"),
    '{"t":"lifecycle","state":"active"}',
    borrow("r8", "fake"),
    '{"t":"shutdown"}',
]


def run(cmd):
    """One scripted session. The self-test of the JVM node runs on its own thread and its result frame may overtake or trail the frames
    of later requests on a slow machine, so the script is sent in two parts: the second only after the `selftest` frame has been seen."""
    env = dict(os.environ)
    for k in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(k, None)
    cut = SCRIPT.index('{"t":"selftest"}') + 1
    with tempfile.TemporaryDirectory() as tmp:
        env["HOME"] = tmp
        env["TMPDIR"] = tmp
        p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        lines = []
        seen = threading.Event()

        def pump():
            for raw in p.stdout:
                line = raw.decode("utf-8").rstrip("\r\n")
                lines.append(line)
                if line.startswith('{"t":"selftest"'):
                    seen.set()

        errbuf = []
        t = threading.Thread(target=pump, daemon=True)
        te = threading.Thread(target=lambda: errbuf.append(p.stderr.read()), daemon=True)
        t.start()
        te.start()
        p.stdin.write(("\n".join(SCRIPT[:cut]) + "\n").encode("utf-8"))
        p.stdin.flush()
        seen.wait(timeout=90)
        p.stdin.write(("\n".join(SCRIPT[cut:]) + "\n").encode("utf-8"))
        p.stdin.close()
        try:
            rc = p.wait(timeout=120)
        except subprocess.TimeoutExpired:
            p.kill()
            rc = -9
        t.join(timeout=10)
        te.join(timeout=10)
    return lines, (errbuf[0] if errbuf else b"").decode("utf-8"), rc


def main(argv):
    if len(argv) < 2:
        print("usage: check_fake_nodes.py <asom-ut-node.jar> [java]")
        return 2
    jar = argv[1]
    rest = [a for a in argv[2:] if a != "--limit-modules"]
    limit = argv[argv.index("--limit-modules") + 1] if "--limit-modules" in argv else None
    if limit:
        rest = [a for a in rest if a != limit]
    java = rest[0] if rest else "java"
    flags = ["-Xmx128m", "-Xss512k", "-XX:+UseSerialGC", "-Xlog:disable", "-XX:+DisplayVMOutputToStderr"] + (["--limit-modules", limit] if limit else [])
    py_out, py_err, py_rc = run([sys.executable, FAKE])
    jv_out, jv_err, jv_rc = run([java, "-Duser.name=tester"] + flags + ["-jar", jar, "--fake-ui"])
    print(f"python fake: {len(py_out)} frames, exit {py_rc}, stderr {py_err!r}")
    print(f"JVM --fake-ui: {len(jv_out)} frames, exit {jv_rc}, stderr {jv_err!r}")
    bad = []
    if len(py_out) < 25:
        bad.append(f"only {len(py_out)} frames from the Python fake: the comparison would be vacuous")
    for i, (a, b) in enumerate(zip(py_out, jv_out)):
        if a != b:
            bad.append(f"frame {i + 1} differs:\n   python: {a}\n   jvm:    {b}")
            break
    if len(py_out) != len(jv_out):
        bad.append(f"frame counts differ: python {len(py_out)}, jvm {len(jv_out)}")
    if py_rc != jv_rc:
        bad.append(f"exit codes differ: python {py_rc}, jvm {jv_rc}")
    for b in bad:
        print("VIOLATION: " + b)
    print("check_fake_nodes: " + ("FAILED" if bad else f"OK ({len(py_out)} identical frames)"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
