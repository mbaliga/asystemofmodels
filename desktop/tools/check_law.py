#!/usr/bin/env python3
"""Static checks of the desktop build (PLATFORM_PLAN section 0 and 2; linux.md 3.4). Standard library only.

  * desktop/settings.gradle.kts maps exactly the five pure-JVM root projects by directory, never uses includeBuild,
    and keeps the two marked track lines for the Windows and macOS tracks;
  * :node-core depends on the mapped root projects only; :node on :node-core only (no lab, no Android module);
  * no `android.` / `com.android` / `com.google.android` import anywhere under desktop/;
  * no wildcard bind address; no listener API (the ServerSocket and Datagram families, MulticastSocket, embeddedServer,
    AsomServer, com.sun.net.httpserver) in any main source EXCEPT the single AF_UNIX control server (ControlServer.kt,
    ERR-DL2-3), which must open StandardProtocolFamily.UNIX and name no Internet socket API; tests may bind loopback;
  * no outbound network API (HTTP client, URL connection, TCP Socket, Internet-address SocketChannel) in ANY main source:
    the node dials only through the ledger-writing egress layer, which does not exist in this wave (invariant 3);
  * no bare print/println and no System.out/System.err in a main source except NodeEnv.kt (T17(c), H3);
  * no `Egress.PEER` (the frozen egress enum never grows a member here);
  * every fixture host directory carries a SYNTHETIC.txt label.
Prints one line per check and exits non-zero on a violation. `--selftest` plants one mutant per forbidden API and requires
every one to be caught, and requires the LISTENER, WILDCARD and DIAL patterns to equal those of lab/tools/check_law.py.
"""
import ast
import os
import re
import sys

DESKTOP = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
CONTROL_SERVER = "ControlServer.kt"
PEER_ENUM = "Egress" + "." + "PEER"  # built from pieces so this file does not contain the literal itself

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

MAPPED = {":core", ":core:contract", ":core:catalogue", ":core:routing", ":core:inference-api", ":server"}
ALLOWED_DEPS = {
    "node-core": {":core:contract", ":core:catalogue", ":core:routing", ":core:inference-api", ":server"},
    "node": {":node-core"},
}


def net_violations(rel, name, text):
    """The network rules for one MAIN source: no wildcard address, no listener but the AF_UNIX control server, no dial."""
    found = []
    if "0.0.0.0" in text or re.search(r'"::"', text):
        found.append(f"{rel}: names a wildcard address")
    if name == CONTROL_SERVER:
        # ERR-DL2-3: the one allowed listener, AF_UNIX only.
        if not re.search(r"ServerSocketChannel\.open\(StandardProtocolFamily\.UNIX\)", text) or re.search(
            r"InetSocketAddress|InetAddress|StandardProtocolFamily\.INET|ServerSocket\(|DatagramSocket|DatagramChannel|localhost|127\.0\.0\.1", text
        ):
            found.append(f"{rel}: the control server must open an AF_UNIX listener and name no Internet socket API")
    elif LISTENER_RE.search(text):
        found.append(f"{rel}: creates a listener in a main source (only the AF_UNIX control server may): {LISTENER_RE.search(text).group(0)}")
    if WILDCARD_RE.search(text):
        found.append(f"{rel}: names a wildcard bind address: {WILDCARD_RE.search(text).group(0)}")
    if DIAL_RE.search(text):
        found.append(f"{rel}: uses an outbound network API in a main source: {DIAL_RE.search(text).group(0)}")
    return found


def lab_pattern(lab_text, name):
    m = re.search(r"^" + name + r" = (\((?:\n[^\n]*)*?\n\)|r\"[^\n]*\")\n", lab_text, re.M)
    if not m:
        return None
    return ast.literal_eval(m.group(1))


def selftest():
    mutants = {
        "HttpServer": 'val s = HttpServer.create(InetSocketAddress("127.0.0.1", 8080), 0)',
        "AsynchronousServerSocketChannel": "val c = AsynchronousServerSocketChannel.open()",
        "DatagramChannel": "val c = DatagramChannel.open()",
        "DatagramSocket": "val c = DatagramSocket(5353)",
        "MulticastSocket": "val c = MulticastSocket(5353)",
        "ServerSocketChannel inet": "val c = ServerSocketChannel.open()",
        "wildcard ::": 'val a = InetSocketAddress("::", 5353)',
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
    for name, line in mutants.items():
        if not net_violations("node/src/main/kotlin/Mutant.kt", "Mutant.kt", line + "\n"):
            failures.append(f"mutant not caught: {name}")
    for name, line in clean.items():
        if net_violations("node/src/main/kotlin/Clean.kt", "Clean.kt", line + "\n"):
            failures.append(f"clean control flagged: {name}")
    control_ok = "val s = ServerSocketChannel.open(StandardProtocolFamily.UNIX)\n"
    if net_violations("node/src/main/kotlin/ControlServer.kt", CONTROL_SERVER, control_ok):
        failures.append("the AF_UNIX control server pattern is flagged")
    if not net_violations("node/src/main/kotlin/ControlServer.kt", CONTROL_SERVER, control_ok + mutants["HttpClient"] + "\n"):
        failures.append("a dial inside the control server is not caught")
    lab = os.path.join(DESKTOP, "..", "lab", "tools", "check_law.py")
    lab_text = open(lab, encoding="utf-8").read() if os.path.isfile(lab) else ""
    for name, value in (("LISTENER", LISTENER), ("WILDCARD", WILDCARD), ("DIAL", DIAL)):
        other = lab_pattern(lab_text, name)
        if other is None:
            failures.append(f"could not read {name} from lab/tools/check_law.py")
        elif other != value:
            failures.append(f"{name} differs from lab/tools/check_law.py: the two builds must share one pattern family")
    for f in failures:
        print("SELFTEST FAILURE: " + f)
    if failures:
        return 1
    print(f"law selftest OK: {len(mutants)} mutants caught, {len(clean)} clean controls passed, patterns equal lab/tools/check_law.py")
    return 0


def files(exts):
    for base, dirs, names in os.walk(DESKTOP):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".kotlin")]
        for n in names:
            if n.endswith(exts):
                yield os.path.join(base, n)


def is_test(rel):
    return "/src/test/" in rel.replace(os.sep, "/")


def main(argv):
    if "--selftest" in argv:
        return selftest()
    bad = []
    settings = open(os.path.join(DESKTOP, "settings.gradle.kts"), encoding="utf-8").read()
    if re.search(r"^\s*includeBuild\(", settings, re.M):
        bad.append("settings.gradle.kts: includeBuild is forbidden (PLATFORM_PLAN P3, R3-CONFORMANCE-3)")
    mapped = set(re.findall(r'^\s*":([\w:-]+)"\s+to\s+"\.\./', settings, re.M))
    mapped = {":" + m for m in mapped}
    if mapped != MAPPED:
        bad.append(f"settings.gradle.kts maps {sorted(mapped)}, expected exactly {sorted(MAPPED)}")
    for marker in ("WINDOWS TRACK adds exactly one line", "MACOS TRACK adds exactly one line"):
        if marker not in settings:
            bad.append(f"settings.gradle.kts lost the marker comment '{marker}'")
    print("law: settings.gradle.kts maps the five pure-JVM root projects by directory, no includeBuild, track markers present")

    for mod, allowed in ALLOWED_DEPS.items():
        text = open(os.path.join(DESKTOP, mod, "build.gradle.kts"), encoding="utf-8").read()
        deps = set(re.findall(r'project\(\s*"(:[^"]+)"\s*\)', text))
        extra = deps - allowed
        if extra:
            bad.append(f"{mod}: depends on {sorted(extra)}, which the desktop dependency law does not allow")
    print(f"law: {len(ALLOWED_DEPS)} module build files checked against the desktop dependency table")

    n = 0
    control_servers = []
    for f in files((".kt", ".kts", ".java")):
        n += 1
        text = open(f, encoding="utf-8").read()
        rel = os.path.relpath(f, DESKTOP)
        main_src = "/src/main/" in rel.replace(os.sep, "/")
        if re.search(r"^\s*import\s+(android|com\.android|com\.google\.android)\.", text, re.M):
            bad.append(f"{rel}: imports an Android package")
        if PEER_ENUM in text:
            bad.append(f"{rel}: uses the frozen egress enum name for a new meaning")
        if main_src:
            bad.extend(net_violations(rel, os.path.basename(f), text))
            if os.path.basename(f) == CONTROL_SERVER:
                control_servers.append(rel)
            if os.path.basename(f) != "NodeEnv.kt" and (
                re.search(r"(?<![\w.])(?<!fun )(println|print)\(", text) or re.search(r"System\.(out|err)", text)
            ):
                bad.append(f"{rel}: prints outside the injected streams (T17(c))")
    print(f"law: {n} Kotlin/Java sources scanned (no android imports, no frozen-enum reuse, no wildcard bind, no listener but the AF_UNIX control server, no bare print)")
    if len(control_servers) > 1:
        bad.append(f"more than one control server source: {control_servers}")

    fx = os.path.join(DESKTOP, "node", "src", "test", "resources", "fixtures", "sysfs")
    hosts = sorted(os.listdir(fx)) if os.path.isdir(fx) else []
    for h in hosts:
        label = os.path.join(fx, h, "SYNTHETIC.txt")
        if not os.path.isfile(label) or not open(label, encoding="utf-8").read().startswith("SYNTHETIC FIXTURE"):
            bad.append(f"fixture host {h}: missing or wrong SYNTHETIC.txt label")
    if hosts != ["ci-vm", "deck-lcd", "deck-oled", "dell"]:
        bad.append(f"fixture hosts are {hosts}, expected ci-vm, dell, deck-lcd, deck-oled")
    print(f"law: {len(hosts)} fixture hosts, each labelled SYNTHETIC")

    if bad:
        for b in bad:
            print("VIOLATION: " + b)
        return 1
    print("law: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
