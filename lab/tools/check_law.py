#!/usr/bin/env python3
"""Static checks of the lab dependency law and rules R3, R4, R5 (LAB_SPEC 0, 1.2, 1.3). Standard library only.

  * every lab module's build.gradle.kts references only the project dependencies LAB_SPEC 1.2 allows;
  * only :conformance-runner may depend on :server;
  * no `android.` / `com.android` import and no `com.google.android` reference anywhere under lab/;
  * the frozen egress enum is never given a `peer` member under its frozen name (R4);
  * no wildcard bind address; no listener is created outside the conformance harness (R5);
  * no outbound network API (HTTP client, URL connection, TCP socket, Internet-address SocketChannel) in a main source
    outside the conformance harness, which only ever talks to the loopback server it started (R5, invariant 3).
Prints one line per check and exits non-zero on a violation. `--selftest` plants one mutant per forbidden API in a synthetic
tree and requires every one to be caught, and every clean control to pass.

LISTENER, WILDCARD and DIAL below are the SAME pattern family as desktop/tools/check_law.py (a self-test there compares
them with this file), so a listener or a dial that one build rejects cannot slip into the other.
"""
import os
import re
import shutil
import sys
import tempfile

LAB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")

ALLOWED = {
    "json": set(),
    "bench-core": {":json"},
    "manifest": {":json", ":bench-core"},
    "ledger-model": {":core:contract", ":json"},
    "mesh-policy": {":ledger-model", ":json"},
    "mesh-proto": {":json", ":manifest", ":ledger-model", ":mesh-policy"},
    "mesh-router": {":core:contract", ":core:catalogue", ":core:routing", ":mesh-policy", ":json"},
    "mesh-sim": {":mesh-router", ":mesh-policy", ":ledger-model", ":core:catalogue"},
    "conformance-runner": {
        ":core:contract", ":core:catalogue", ":core:routing", ":core:inference-api", ":server", ":json", ":bench-core",
        ":manifest", ":ledger-model", ":mesh-policy", ":mesh-proto", ":mesh-router", ":mesh-sim",
    },
}
# Built from pieces so that this file does not contain the forbidden literal itself.
PEER_ENUM = "Egress" + "." + "PEER"

LISTENER = (
    r"ServerSocket|DatagramSocket|DatagramChannel|MulticastSocket|embeddedServer|AsomServer\(|HttpsServer|HttpServer"
    r"|com\.sun\.net\.httpserver"
)
WILDCARD = r"(InetSocketAddress|getByName|getAllByName|bind)\(\s*\"(::|0:0:0:0:0:0:0:0)\"|InetSocketAddress\(\s*[\w.]+\s*\)"
DIAL = (
    r"HttpClient|HttpURLConnection|HttpsURLConnection|openConnection\(|\.openStream\(|\bSocket\(|SSLSocket|\bURL\("
    r"|SocketChannel\.open\([^)]*Inet|connect\(\s*(java\.net\.)?InetSocketAddress"
)
LISTENER_RE = re.compile(LISTENER)
WILDCARD_RE = re.compile(WILDCARD)
DIAL_RE = re.compile(DIAL)

HARNESS_MAIN = os.path.join("conformance-runner", "src", "main")


def is_test(rel):
    return "/src/test/" in rel.replace(os.sep, "/")


def net_violations(rel, text):
    """The R5 network rules for one source file; `rel` is its path relative to lab/."""
    found = []
    if "0.0.0.0" in text and not rel.endswith("check_law.py"):
        found.append(f"{rel}: names the wildcard address (R5)")
    if is_test(rel):
        return found
    harness = rel.startswith(HARNESS_MAIN)
    w = WILDCARD_RE.search(text)
    if w:
        found.append(f"{rel}: names a wildcard bind address ({w.group(0)}) (R5)")
    m = LISTENER_RE.search(text)
    if m and not harness:
        found.append(f"{rel}: creates a listener outside the conformance harness or a test ({m.group(0)}) (R5)")
    d = DIAL_RE.search(text)
    if d and not harness:
        found.append(f"{rel}: uses an outbound network API ({d.group(0)}) outside the conformance harness (R5)")
    return found


def files(root, exts):
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".kotlin")]
        for n in names:
            if n.endswith(exts):
                yield os.path.join(base, n)


def check(root):
    bad = []
    for mod, allowed in ALLOWED.items():
        f = os.path.join(root, mod, "build.gradle.kts")
        text = open(f, encoding="utf-8").read()
        deps = set(re.findall(r'project\(\s*"(:[^"]+)"\s*\)', text))
        extra = deps - allowed
        if extra:
            bad.append(f"{mod}: depends on {sorted(extra)}, which LAB_SPEC 1.2 does not allow")
        if ":server" in deps and mod != "conformance-runner":
            bad.append(f"{mod}: only :conformance-runner may depend on :server")

    n = 0
    for f in files(root, (".kt", ".kts", ".java")):
        n += 1
        text = open(f, encoding="utf-8").read()
        rel = os.path.relpath(f, root)
        if re.search(r"^\s*import\s+(android|com\.android|com\.google\.android)\.", text, re.M):
            bad.append(f"{rel}: imports an Android package (R3)")
        if PEER_ENUM in text:
            bad.append(f"{rel}: uses the frozen egress enum name for a new meaning (R4)")
        bad.extend(net_violations(rel, text))
    return bad, n


def selftest():
    mutants = {
        "HttpServer": 'val s = HttpServer.create(InetSocketAddress("127.0.0.1", 8080), 0)',
        "httpserver package": 'import com.sun.net.httpserver.Headers',
        "AsynchronousServerSocketChannel": "val c = AsynchronousServerSocketChannel.open()",
        "DatagramChannel": "val c = DatagramChannel.open()",
        "DatagramSocket": "val c = DatagramSocket(5353)",
        "MulticastSocket": "val c = MulticastSocket(5353)",
        "ServerSocketChannel inet": "val c = ServerSocketChannel.open()",
        "wildcard :: ": 'val a = InetSocketAddress("::", 5353)',
        "wildcard port-only": "val a = InetSocketAddress(9090)",
        "wildcard 0.0.0.0": 'val a = "0.0.0.0"',
        "HttpClient": "val c = java.net.http.HttpClient.newHttpClient()",
        "HttpURLConnection": 'val c = java.net.URL("https://example.invalid").openConnection()',
        "URL stream": 'val s = URL("https://example.invalid").openStream()',
        "Socket": 'val s = java.net.Socket("example.invalid", 443)',
        "SSLSocket": "val s: SSLSocket? = null",
        "SocketChannel inet": 'val c = SocketChannel.open(InetSocketAddress("example.invalid", 443))',
        "SocketChannel connect": 'c.connect(InetSocketAddress("example.invalid", 443))',
    }
    clean = {
        "unix SocketChannel": "val c = SocketChannel.open(UnixDomainSocketAddress.of(path))",
        "plain code": "fun add(a: Int, b: Int) = a + b",
        "two-arg loopback address": 'val a = InetSocketAddress("127.0.0.1", 0)',
    }
    failures = []
    n_mut = 0
    for scope, base in (("main of mesh-sim", os.path.join("mesh-sim", "src", "main", "kotlin", "Mutant.kt")),):
        for name, line in mutants.items():
            n_mut += 1
            if not net_violations(base, line + "\n"):
                failures.append(f"mutant not caught in {scope}: {name}")
    for name, line in clean.items():
        if net_violations(os.path.join("mesh-sim", "src", "main", "kotlin", "Clean.kt"), line + "\n"):
            failures.append(f"clean control flagged: {name}")
    harness = os.path.join("conformance-runner", "src", "main", "kotlin", "Harness.kt")
    if net_violations(harness, mutants["HttpClient"] + "\n"):
        failures.append("the conformance harness must be allowed its loopback HttpClient")
    if not net_violations(harness, mutants["wildcard 0.0.0.0"] + "\n"):
        failures.append("a wildcard address must be rejected even in the harness")
    if net_violations(os.path.join("mesh-sim", "src", "test", "kotlin", "T.kt"), mutants["HttpServer"] + "\n"):
        failures.append("a test source may open a listener")

    tmp = tempfile.mkdtemp(prefix="asom-lab-law-selftest-")
    try:
        for mod in ALLOWED:
            os.makedirs(os.path.join(tmp, mod))
            open(os.path.join(tmp, mod, "build.gradle.kts"), "w").write("")
        src = os.path.join(tmp, "mesh-sim", "src", "main", "kotlin")
        os.makedirs(src)
        if check(tmp)[0]:
            failures.append("the empty synthetic tree already violates the law")
        open(os.path.join(src, "Mutant.kt"), "w").write(
            'val a = HttpServer.create(InetSocketAddress("::", 0), 0)\nval b = AsynchronousServerSocketChannel.open().bind(InetSocketAddress(9090))\n'
            'val c = DatagramChannel.open().bind(InetSocketAddress("::", 5353))\n'
        )
        found, _ = check(tmp)
        if len(found) < 2:
            failures.append(f"the planted Mutant.kt was not caught by the tree scan: {found}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    for f in failures:
        print("SELFTEST FAILURE: " + f)
    if failures:
        return 1
    print(f"law selftest OK: {n_mut} mutants caught, {len(clean)} clean controls passed, harness and test scopes behave")
    return 0


def main(argv):
    if "--selftest" in argv:
        return selftest()
    bad, n = check(LAB)
    print(f"law: {len(ALLOWED)} module build files checked against the LAB_SPEC 1.2 dependency table")
    print(f"law: {n} Kotlin/Java sources scanned (no android imports, no frozen-enum reuse, no wildcard bind, listeners and outbound dials only in the harness or tests)")
    if n == 0:
        bad.append("no source files were scanned: the check would be vacuous")
    if bad:
        for b in bad:
            print("VIOLATION: " + b)
        return 1
    print("law: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
