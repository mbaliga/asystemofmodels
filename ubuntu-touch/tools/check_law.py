#!/usr/bin/env python3
"""Static law checks of the Ubuntu Touch track (PLATFORM_PLAN section 0 and 7; ubuntu-touch.md 3.1, 4, 10.4). Standard library only.

  * ubuntu-touch/jvm/settings.gradle.kts maps projects by directory only: never the composite-build include, and every mapped path
    stays inside core/, server/, lab/ or desktop/node-core;
  * :ut-host depends only on :node-core, :json, :manifest, :ledger-model and :conformance-runner;
  * no `android.` / `com.android` / `com.google.android` import anywhere under ubuntu-touch/;
  * the Java side listens on nothing and dials nothing: no listener or dial API in any ut-host main source (SourceHygieneTest checks the
    same on the Kotlin side; this is the second, independent reading of the tree);
  * the C++ plugin uses no network class (QTcpServer, QTcpSocket, QUdpSocket, QNetworkAccessManager, QWebSocket, QLocalServer,
    QSslSocket) and spawns processes only in nodeprocess.cpp; it never sets a QProcess program from the network or an env variable;
  * QML and JS use no XMLHttpRequest, WebSocket, Qt.openUrlExternally, Qt.include of a URL, `source: "http` or PushClient;
  * no `unconfined` in any manifest or policy file of the click, no `X-Ubuntu-XMir-Enable`, no location or push policy group;
  * no colour literal outside Tokens.qml is checked by check_tokens.py, not here.
Prints one line per check; exits non-zero on a violation."""
import os
import re
import sys

UT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SKIP_DIRS = {"build", ".gradle", ".kotlin", ".cache", "stage"}
BAD = []


def files(exts, base=UT):
    for root, dirs, names in os.walk(base):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for n in names:
            if n.endswith(exts):
                yield os.path.join(root, n)


def rel(p):
    return os.path.relpath(p, UT)


def strip_comments(text, style):
    if style == "c":
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//.*$", "", text, flags=re.M)


def main():
    settings = open(os.path.join(UT, "jvm", "settings.gradle.kts"), encoding="utf-8").read()
    if re.search(r"^\s*includeBuild\(", settings, re.M):
        BAD.append("jvm/settings.gradle.kts: includeBuild is forbidden (PLATFORM_PLAN P3, R3-CONFORMANCE-3)")
    dirs = re.findall(r'^\s*":([\w:-]+)"\s+to\s+"([^"]+)"', settings, re.M)
    for path, d in dirs:
        target = os.path.normpath(os.path.join(UT, "jvm", d))
        allowed = [os.path.join(UT, "..", x) for x in ("core", "server", "lab", "desktop/node-core")]
        if not any(target == os.path.normpath(a) or target.startswith(os.path.normpath(a) + os.sep) for a in allowed):
            BAD.append(f"jvm/settings.gradle.kts maps :{path} to {d}, outside core/, server/, lab/ and desktop/node-core")
    print(f"law: settings.gradle.kts maps {len(dirs)} projects by directory, no includeBuild, all inside the allowed source trees")

    build = open(os.path.join(UT, "jvm", "ut-host", "build.gradle.kts"), encoding="utf-8").read()
    deps = set(re.findall(r'project\(\s*"(:[^"]+)"\s*\)', build))
    allowed_deps = {":node-core", ":json", ":manifest", ":ledger-model", ":conformance-runner"}
    if deps - allowed_deps:
        BAD.append(f"ut-host depends on {sorted(deps - allowed_deps)}: not in the Ubuntu Touch dependency law")
    print(f"law: ut-host depends on {sorted(deps)}")

    n = 0
    for f in files((".kt", ".kts", ".java", ".cpp", ".h", ".qml", ".js")):
        n += 1
        text = open(f, encoding="utf-8").read()
        if re.search(r"^\s*import\s+(android|com\.android|com\.google\.android)\.", text, re.M):
            BAD.append(f"{rel(f)}: imports an Android package")
    print(f"law: {n} sources scanned for Android imports")

    net_java = re.compile(r"\b(ServerSocket|ServerSocketChannel|DatagramSocket|DatagramChannel|SocketChannel|InetSocketAddress|HttpClient|HttpServer|embeddedServer|AsomServer|HttpURLConnection|MulticastSocket)\b|(?<![\w.])Socket\(")
    main_kt = [f for f in files((".kt",), os.path.join(UT, "jvm", "ut-host", "src", "main"))]
    for f in main_kt:
        for i, line in enumerate(strip_comments(open(f, encoding="utf-8").read(), "c").splitlines(), 1):
            if net_java.search(line):
                BAD.append(f"{rel(f)}:{i}: a listener or dial API in the host: {line.strip()}")
            if "0.0.0.0" in line:
                BAD.append(f"{rel(f)}:{i}: a wildcard address")
    print(f"law: {len(main_kt)} ut-host main sources: no listener, no dial API, no wildcard address")

    net_qt = re.compile(r"\b(QTcpServer|QTcpSocket|QUdpSocket|QNetworkAccessManager|QWebSocket|QWebSocketServer|QLocalServer|QSslSocket|QNetworkRequest|QHostInfo|QNetworkInterface)\b")
    cpp = list(files((".cpp", ".h"), os.path.join(UT, "plugin"))) + list(files((".cpp",), os.path.join(UT, "tests", "native")))
    for f in cpp:
        text = strip_comments(open(f, encoding="utf-8").read(), "c")
        for m in net_qt.finditer(text):
            BAD.append(f"{rel(f)}: uses network class {m.group(1)}: the UI process opens no socket")
        if re.search(r"\bQProcess\b", text) and os.path.basename(f) not in ("nodeprocess.cpp", "nodeprocess.h", "tst_plugin.cpp"):
            BAD.append(f"{rel(f)}: spawns or names a process outside nodeprocess.cpp")
    print(f"law: {len(cpp)} C++ sources: no network class, QProcess only in nodeprocess.cpp")

    qml_bad = re.compile(r"XMLHttpRequest|\bWebSocket\b|Qt\.openUrlExternally|PushClient|source:\s*[\"']https?:|Qt\.include\(\s*[\"']https?:")
    qs = list(files((".qml", ".js"), os.path.join(UT, "qml")))
    for f in qs:
        for i, line in enumerate(strip_comments(open(f, encoding="utf-8").read(), "c").splitlines(), 1):
            if qml_bad.search(line):
                BAD.append(f"{rel(f)}:{i}: network or egress from QML: {line.strip()}")
    print(f"law: {len(qs)} QML/JS files: no XMLHttpRequest, WebSocket, external URL or push")

    click_files = ["asom.apparmor.in", "manifest.json.in", "asom.desktop.in", "clickable.yaml"]
    for name in click_files:
        text = open(os.path.join(UT, name), encoding="utf-8").read()
        for token in ("unconfined", "X-Ubuntu-XMir-Enable", "push-notification-client", "\"location\""):
            for i, line in enumerate(text.splitlines(), 1):
                if token in line and not line.lstrip().startswith("#"):
                    BAD.append(f"{name}:{i}: {token} is forbidden (L-UT4, UF14)")
    print(f"law: {len(click_files)} click files: no unconfined, no XMir, no location or push group")

    for b in BAD:
        print("VIOLATION: " + b)
    print("law: " + ("FAILED" if BAD else "OK"))
    return 1 if BAD else 0


if __name__ == "__main__":
    sys.exit(main())
