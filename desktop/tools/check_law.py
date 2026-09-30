#!/usr/bin/env python3
"""Static checks of the desktop build (PLATFORM_PLAN section 0 and 2; linux.md 3.4). Standard library only.

  * desktop/settings.gradle.kts maps exactly the five pure-JVM root projects by directory, never uses includeBuild,
    and keeps the two marked track lines for the Windows and macOS tracks;
  * :node-core depends on the mapped root projects only; :node on :node-core only (no lab, no Android module);
  * no `android.` / `com.android` / `com.google.android` import anywhere under desktop/;
  * no wildcard bind address; no listener API (ServerSocket, ServerSocketChannel, DatagramSocket, embeddedServer,
    AsomServer) in any main source EXCEPT the single AF_UNIX control server (ControlServer.kt, ERR-DL2-3), which must
    open StandardProtocolFamily.UNIX and name no Internet socket API; tests may bind loopback;
  * no bare print/println and no System.out/System.err in a main source except NodeEnv.kt (T17(c), H3);
  * no `Egress.PEER` (the frozen egress enum never grows a member here);
  * every fixture host directory carries a SYNTHETIC.txt label.
Prints one line per check and exits non-zero on a violation.
"""
import os
import re
import sys

DESKTOP = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
CONTROL_SERVER = "ControlServer.kt"
PEER_ENUM = "Egress" + "." + "PEER"  # built from pieces so this file does not contain the literal itself

MAPPED = {":core", ":core:contract", ":core:catalogue", ":core:routing", ":core:inference-api", ":server"}
ALLOWED_DEPS = {
    "node-core": {":core:contract", ":core:catalogue", ":core:routing", ":core:inference-api", ":server"},
    "node": {":node-core"},
}


def files(exts):
    for base, dirs, names in os.walk(DESKTOP):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".kotlin")]
        for n in names:
            if n.endswith(exts):
                yield os.path.join(base, n)


def is_test(rel):
    return "/src/test/" in rel.replace(os.sep, "/")


def main():
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
            if "0.0.0.0" in text or re.search(r'"::"', text):
                bad.append(f"{rel}: names a wildcard address")
            if os.path.basename(f) == CONTROL_SERVER:
                # ERR-DL2-3: the one allowed listener, AF_UNIX only.
                if not re.search(r"ServerSocketChannel\.open\(StandardProtocolFamily\.UNIX\)", text) or re.search(
                    r"InetSocketAddress|InetAddress|StandardProtocolFamily\.INET|ServerSocket\(|DatagramSocket|DatagramChannel|localhost|127\.0\.0\.1", text
                ):
                    bad.append(f"{rel}: the control server must open an AF_UNIX listener and name no Internet socket API")
                control_servers.append(rel)
            elif re.search(r"\b(ServerSocket|ServerSocketChannel|DatagramSocket|embeddedServer|AsomServer\()", text):
                bad.append(f"{rel}: creates a listener in a main source (only the AF_UNIX control server may)")
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
    sys.exit(main())
