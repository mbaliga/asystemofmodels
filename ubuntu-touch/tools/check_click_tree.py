#!/usr/bin/env python3
"""Checks the tree a Click will contain, using plain tools only (no click-review, no device):

    check_click_tree.py <install dir> [--arch arm64|amd64] [--no-runtime] [--triplet aarch64-linux-gnu]

  <install dir> is what `cmake --install` produced with DESTDIR (Clickable's ${INSTALL_DIR}), or a Click unpacked with
  `dpkg-deb -x`. The three placeholders Clickable substitutes (@CLICK_ARCH@, @CLICK_FRAMEWORK@, @APPARMOR_POLICY@) are replaced on
  a COPY here the way Clickable does it, unless the file has no placeholder left (an already built click).
  Checks: manifest keys and hooks, the desktop file (touch app, existing icon), the AppArmor manifest (apparmor-ci/check-groups.py),
  the QML entry point, the plugin under lib/<triplet>/Asom/Bridge, and (unless --no-runtime) the runtime, jar, jvm.options and the two
  checksum lists, whose hashes are recomputed. Standard library only. Exit 0 when everything passes."""
import hashlib
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
UT = os.path.normpath(os.path.join(HERE, ".."))
FRAMEWORK = "ubuntu-touch-24.04-1.x"
POLICY = "2404.1"


def fill(text, arch):
    return text.replace("@CLICK_ARCH@", arch).replace("@CLICK_FRAMEWORK@", FRAMEWORK).replace("@APPARMOR_POLICY@", POLICY)


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    arch = "arm64"
    triplet = "aarch64-linux-gnu"
    if "--arch" in argv:
        arch = argv[argv.index("--arch") + 1]
        args = [a for a in args if a != arch]
    if "--triplet" in argv:
        triplet = argv[argv.index("--triplet") + 1]
        args = [a for a in args if a != triplet]
    if len(args) != 1:
        print(__doc__)
        return 2
    root = args[0]
    no_runtime = "--no-runtime" in argv or arch == "amd64"
    bad = []

    def need(cond, msg):
        if not cond:
            bad.append(msg)

    def read(rel):
        with open(os.path.join(root, rel), encoding="utf-8") as f:
            return f.read()

    def exists(rel):
        return os.path.exists(os.path.join(root, rel))

    for rel in ("manifest.json", "asom.apparmor", "asom.desktop", "assets/asom.svg", "qml/Main.qml"):
        need(exists(rel), f"{rel} is missing from the tree")
    if bad:
        return finish(bad)

    manifest = json.loads(fill(read("manifest.json"), arch))
    need(manifest.get("name") == "xyz.mdhv.asom.ut", f"manifest name is {manifest.get('name')!r}")
    need(re.fullmatch(r"\d+\.\d+\.\d+", str(manifest.get("version"))), f"manifest version {manifest.get('version')!r} is not x.y.z")
    need(manifest.get("architecture") == arch, f"manifest architecture is {manifest.get('architecture')!r}, expected {arch}")
    need(manifest.get("framework") == FRAMEWORK, f"manifest framework is {manifest.get('framework')!r}")
    need(re.search(r"<[^>]+@[^>]+>", str(manifest.get("maintainer", ""))), "manifest maintainer is not 'Name <address>'")
    for key in ("title", "description"):
        need(manifest.get(key), f"manifest has no {key}")
    hooks = manifest.get("hooks", {})
    need(list(hooks) == ["asom"], f"hooks {list(hooks)}: the app id is xyz.mdhv.asom.ut_asom_<version>, so the only hook is 'asom'")
    hook = hooks.get("asom", {})
    need(hook.get("apparmor") == "asom.apparmor" and hook.get("desktop") == "asom.desktop", f"hook files are {hook}")
    print(f"manifest: {manifest.get('name')} {manifest.get('version')} {manifest.get('architecture')} {manifest.get('framework')}; app id {manifest.get('name')}_asom_{manifest.get('version')}")

    desktop = dict(
        line.split("=", 1) for line in read("asom.desktop").splitlines() if "=" in line and not line.startswith("#")
    )
    need(desktop.get("Type") == "Application" and desktop.get("Terminal") == "false", "desktop file: Type/Terminal")
    need(desktop.get("X-Ubuntu-Touch") == "true", "desktop file: X-Ubuntu-Touch=true is missing (a non-touch app is exempt from Lomiri's lifecycle: L-UT4)")
    need(desktop.get("Exec", "").split()[-1:] == ["qml/Main.qml"], f"desktop Exec {desktop.get('Exec')!r} does not start qml/Main.qml")
    need(exists(desktop.get("Icon", "")), f"desktop Icon {desktop.get('Icon')!r} does not exist in the tree")
    need("X-Ubuntu-XMir-Enable" not in read("asom.desktop"), "desktop file asks for XMir: an X11 variant is forbidden (L-UT4)")
    print(f"desktop: Exec={desktop.get('Exec')} Icon={desktop.get('Icon')} X-Ubuntu-Touch={desktop.get('X-Ubuntu-Touch')}")

    tmp = os.path.join(root, ".apparmor-check.tmp")
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(fill(read("asom.apparmor"), arch))
        r = subprocess.run([sys.executable, os.path.join(UT, "apparmor-ci", "check-groups.py"), tmp, "--policy", POLICY], capture_output=True, text=True)
        need(r.returncode == 0, "check-groups.py refused the AppArmor manifest:\n" + r.stdout + r.stderr)
        print(r.stdout.strip().replace(tmp, "asom.apparmor"))
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)

    plugin = f"lib/{triplet}/Asom/Bridge"
    need(exists(plugin + "/qmldir"), f"{plugin}/qmldir is missing")
    need(any(n.startswith("libasom_bridge") for n in os.listdir(os.path.join(root, plugin))) if exists(plugin) else False, f"no libasom_bridge in {plugin}")
    for q in ("Main", "NodeModel", "StatusPage", "BorrowPage", "PeersPage", "PairPage", "LedgerPage", "StatusHeader", "Glyph"):
        need(exists(f"qml/{q}.qml"), f"qml/{q}.qml is missing")
    need(exists("qml/tokens/Tokens.qml") and exists("qml/tokens/qmldir"), "the token seam is missing")
    print(f"tree: plugin at {plugin}, {sum(1 for _ in os.scandir(os.path.join(root, 'qml')))} entries under qml/")

    if no_runtime:
        print("runtime: not checked (--no-runtime or amd64: this tree never ships)")
    else:
        for rel in ("lib/asom/rt/bin/java", "lib/asom/asom-ut-node.jar", "lib/asom/jvm.options", "lib/asom/rt.sha256", "lib/asom/jar.sha256"):
            need(exists(rel), f"{rel} is missing from the tree")
        if not bad:
            jar = open(os.path.join(root, "lib/asom/asom-ut-node.jar"), "rb").read()
            want = read("lib/asom/jar.sha256").split()[0]
            need(hashlib.sha256(jar).hexdigest() == want, "jar.sha256 does not match the jar")
            rt = os.path.join(root, "lib/asom/rt")
            listed = 0
            for line in read("lib/asom/rt.sha256").splitlines():
                m = re.fullmatch(r"([0-9a-f]{64})  \./(.+)", line)
                need(m, f"rt.sha256 line {line!r} is not 'sha256  ./path'")
                if not m:
                    continue
                path = os.path.join(rt, m.group(2))
                need(".." not in m.group(2).split("/"), f"rt.sha256 lists a path that leaves the runtime: {m.group(2)}")
                if os.path.isfile(path):
                    need(hashlib.sha256(open(path, "rb").read()).hexdigest() == m.group(1), f"rt.sha256: {m.group(2)} differs")
                    listed += 1
                else:
                    need(False, f"rt.sha256 lists {m.group(2)}, which is not in the tree")
            actual = sum(1 for base, _, names in os.walk(rt) for n in names if os.path.isfile(os.path.join(base, n)) and not os.path.islink(os.path.join(base, n)))
            need(listed == actual, f"rt.sha256 lists {listed} files but the runtime holds {actual}")
            print(f"runtime: {listed} files match rt.sha256; jar matches jar.sha256")
    return finish(bad)


def finish(bad):
    for b in bad:
        print("VIOLATION: " + b)
    print("check_click_tree: " + ("FAILED" if bad else "OK"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
