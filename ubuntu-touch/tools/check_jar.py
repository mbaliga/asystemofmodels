#!/usr/bin/env python3
"""Runs the SHIPPED node jar the way the phone will (same JVM flags, same module set when a limited runtime is used) and checks it:

    check_jar.py <java> <asom-ut-node.jar> [--limit-modules M1,M2,...]

  1. `--selftest`: exactly one stdout line starting {"selftest":"ok", nothing on stderr, exit 0;
  2. `--fake-ui`  against tests/fake_node.py (tools/check_fake_nodes.py): the frames are identical;
  3. `--profile=ut`: a scripted session (hello, active, borrow with no peer, peers, ledger, shutdown) prints the expected frame types
     and nothing else; the ledger file is created (mode 0600) under the private data directory.

The jar excludes the v1 server and its libraries (ubuntu-touch/jvm/ut-host/build.gradle.kts); this is what proves nothing that was
excluded is needed. LAB when run here on an x86_64 JDK; on the arm64 runner it runs the jlinked aarch64 runtime. Standard library only."""
import json
import os
import stat
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
UT_ROOT = os.path.normpath(os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
from expand_jvm_options import expand  # noqa: E402  the same expansion the plugin does (NodeProcess::expandOptions)

OPTIONS = os.path.join(UT_ROOT, "runtime", "jvm.options")


def env_for(home):
    env = {k: v for k, v in os.environ.items() if k not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
    env.update({"HOME": home, "TMPDIR": home, "XDG_CACHE_HOME": os.path.join(home, ".cache")})
    return env


def run(java, jar, limit, args, stdin=b"", user="tester"):
    with tempfile.TemporaryDirectory() as home:
        env = env_for(home)
        flags = expand(open(OPTIONS, encoding="utf-8").read(), env)
        cmd = [java, f"-Duser.name={user}"] + flags + (["--limit-modules", limit] if limit else []) + ["-jar", jar] + args
        p = subprocess.run(cmd, input=stdin, capture_output=True, env=env, timeout=180)
        ledger = os.path.join(home, ".local", "share", "xyz.mdhv.asom.ut", "ledger", "ledger.jsonl")
        mode = stat.S_IMODE(os.stat(ledger).st_mode) if os.path.exists(ledger) else None
    return p.stdout.decode("utf-8"), p.stderr.decode("utf-8"), p.returncode, mode


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    java, jar = argv[1], argv[2]
    limit = argv[argv.index("--limit-modules") + 1] if "--limit-modules" in argv else None
    bad = []

    out, err, rc, _ = run(java, jar, limit, ["--selftest"])
    lines = out.splitlines()
    ok = len(lines) == 1 and lines[0].startswith('{"selftest":"ok"') and err == "" and rc == 0
    print(f"selftest: {len(lines)} stdout line(s), stderr {err!r}, exit {rc}: {'ok' if ok else 'FAILED'}")
    if not ok:
        bad.append("--selftest: " + (lines[0][:300] if lines else "no output") + " " + err[:300])
    else:
        doc = json.loads(lines[0])
        print(f"selftest: tls {doc['tls']}, alpn {doc['alpn']}, vectors {doc['vectors']}, rssKiB {doc['rssKiB']}")

    script = "\n".join([
        '{"t":"hello","v":1}', '{"t":"lifecycle","state":"active"}',
        '{"t":"borrow","rid":"r1","model":"gpt-x","messages":[{"role":"user","content":"hi"}],"maxTokens":8,"stream":true}',
        '{"t":"peers","op":"open"}', '{"t":"ledger","since":0,"limit":10}', '{"t":"shutdown"}',
    ]) + "\n"
    out, err, rc, mode = run(java, jar, limit, ["--profile=ut"], script.encode("utf-8"))
    types = [json.loads(l)["t"] for l in out.splitlines()]
    want = ["hello_ack", "state", "error", "state", "peers", "rows"]
    ok = types == want and err == "" and rc == 0 and mode == 0o600
    print(f"--profile=ut session: frames {types}, stderr {err!r}, exit {rc}, ledger mode {oct(mode) if mode else mode}: {'ok' if ok else 'FAILED'}")
    if not ok:
        bad.append(f"--profile=ut session: frames {types} (want {want}), stderr {err[:200]!r}, exit {rc}, ledger mode {mode}")

    r = subprocess.run([sys.executable, os.path.join(HERE, "check_fake_nodes.py"), jar, java] + (["--limit-modules", limit] if limit else []), capture_output=True, text=True)
    print(r.stdout.strip())
    if r.returncode != 0:
        bad.append("check_fake_nodes.py failed")

    for b in bad:
        print("VIOLATION: " + b)
    print("check_jar: " + ("FAILED" if bad else "OK"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
