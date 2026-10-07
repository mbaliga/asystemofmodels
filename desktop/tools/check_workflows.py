#!/usr/bin/env python3
"""Static supply-chain checks of the mesh CI workflows (review findings EGR-3 and EGR-9). Standard library only.

    check_workflows.py [--selftest] [workflow.yml ...]      (default: the desktop-linux, lab and cleanup-artifacts
                                                              workflows of this repository)

For every file:
  * every third-party `uses:` is pinned by a full 40-hex commit SHA (a local `./` action or a `docker://...@sha256:` is fine);
  * no run: or script: body interpolates an attacker-influenceable expression (github.event.*, github.head_ref, inputs.*)
    directly; such a value must go through `env:` and be read as an environment variable;
  * every actions/checkout step sets `persist-credentials: false` (no mesh job pushes);
  * every container image named by an `image:` key is pinned by digest (`@sha256:` plus 64 hex);
  * the file declares `permissions:` at the top level (least privilege; a job may then widen its own).
Each rule counts the cases it looked at and the run fails if a rule saw none (non-vacuity). Prints one line per rule and exits
non-zero on a violation. `--selftest` plants one mutant per rule and requires every one to be caught.
"""
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
# desktop-windows.yml is deliberately absent: winplatform's WorkflowTest requires it to equal desktop/packaging/windows/ci/
# desktop-windows.yml byte for byte, and that copy belongs to the Windows track (desktop/ERRATA.md ERR-FX-4). Pass the file
# explicitly to check it.
DEFAULT = ["desktop-linux.yml", "lab.yml", "cleanup-artifacts.yml"]
HOSTILE = re.compile(r"\$\{\{\s*(github\.event\.|github\.head_ref|inputs\.)")
SHA = re.compile(r"^[0-9a-f]{40}$")


def indent_of(line):
    return len(line) - len(line.lstrip(" "))


def analyse(name, text):
    """Returns (violations, counts) for one workflow file's text."""
    bad = []
    counts = {"uses": 0, "bodies": 0, "checkouts": 0, "images": 0, "permissions": 0}
    lines = text.split("\n")

    for i, ln in enumerate(lines):
        m = re.match(r"^\s*(?:-\s+)?uses:\s*(\S+)", ln)
        if m:
            ref = m.group(1).strip("'\"")
            if ref.startswith("./"):
                continue
            counts["uses"] += 1
            if ref.startswith("docker://"):
                if not re.search(r"@sha256:[0-9a-f]{64}$", ref):
                    bad.append(f"{name}:{i + 1}: docker action not pinned by digest: {ref}")
                continue
            if "@" not in ref or not SHA.match(ref.split("@", 1)[1]):
                bad.append(f"{name}:{i + 1}: action not pinned by a full commit SHA: {ref}")

    i = 0
    while i < len(lines):
        ln = lines[i]
        m = re.match(r"^(\s*)(?:-\s+)?(run|script):\s*(.*)$", ln)
        if m:
            key_indent = len(m.group(1)) + (2 if re.match(r"^\s*-\s", ln) else 0)
            counts["bodies"] += 1
            body = [m.group(3)]
            j = i + 1
            if m.group(3).strip() in ("|", ">", "|-", ">-", "|+", ">+"):
                while j < len(lines) and (not lines[j].strip() or indent_of(lines[j]) > key_indent):
                    body.append(lines[j])
                    j += 1
            for k, b in enumerate(body):
                if HOSTILE.search(b):
                    bad.append(f"{name}:{i + 1 + k}: untrusted expression interpolated into a {m.group(2)}: body ({b.strip()[:80]})")
            i = j
            continue
        i += 1

    for i, ln in enumerate(lines):
        m = re.match(r"^(\s*)(?:-\s+)?uses:\s*actions/checkout@", ln)
        if not m:
            continue
        counts["checkouts"] += 1
        step_indent = len(m.group(1))
        j = i + 1
        block = []
        while j < len(lines) and (not lines[j].strip() or indent_of(lines[j]) > step_indent):
            block.append(lines[j])
            j += 1
        if not any(re.match(r"^\s*persist-credentials:\s*false\s*(#.*)?$", b) for b in block):
            bad.append(f"{name}:{i + 1}: actions/checkout without persist-credentials: false")

    for i, ln in enumerate(lines):
        m = re.match(r"^\s*(?:-\s+)?(?:\{.*)?\bimage:\s*(.*)$", ln)
        if not m:
            continue
        values = re.findall(r"[\"']([^\"']+)[\"']|(\$\{\{[^}]*\}\})", m.group(1))
        raw = [a or b for a, b in values]
        if not raw and m.group(1).strip():
            raw = [m.group(1).strip().rstrip(",}").strip()]
        for v in raw:
            if v.startswith("${{"):
                continue
            counts["images"] += 1
            if not re.search(r"@sha256:[0-9a-f]{64}$", v):
                bad.append(f"{name}:{i + 1}: container image not pinned by digest: {v}")

    if re.search(r"^permissions:", text, re.M):
        counts["permissions"] += 1
    else:
        bad.append(f"{name}: no top-level permissions: block")
    return bad, counts


def selftest():
    good = (
        "name: x\npermissions: {}\njobs:\n  a:\n    runs-on: ubuntu-latest\n    steps:\n"
        "      - uses: actions/checkout@" + "a" * 40 + " # v4\n        with:\n          persist-credentials: false\n"
        "      - name: s\n        env:\n          K: ${{ github.event.inputs.keep }}\n        run: echo \"$K\"\n"
        "      - run: echo ${{ matrix.image }}\n"
    )
    mutants = {
        "tag-pinned action": good.replace("a" * 40, "v4"),
        "short-sha action": good.replace("a" * 40, "a" * 12),
        "expression in run": good.replace('echo "$K"', "echo ${{ github.event.inputs.keep }}"),
        "expression in script": good + "      - uses: actions/github-script@" + "b" * 40 + "\n        with:\n          script: |\n            const k = '${{ github.event.inputs.keep }}';\n",
        "head_ref in run": good + "      - run: |\n          echo ${{ github.head_ref }}\n",
        "checkout keeps credentials": good.replace("          persist-credentials: false\n", "          fetch-depth: 0\n"),
        "checkout without with": good.replace("        with:\n          persist-credentials: false\n", ""),
        "image by tag": good + "    strategy:\n      matrix:\n        image: [\"ubuntu:22.04\"]\n",
        "image digest wrong length": good + "    strategy:\n      matrix:\n        image: [\"ubuntu:22.04@sha256:abc\"]\n",
        "no permissions": good.replace("permissions: {}\n", ""),
    }
    controls = {
        "image by digest": good + "    strategy:\n      matrix:\n        include:\n          - { name: u, image: \"ubuntu:22.04@sha256:" + "c" * 64 + "\" }\n",
        "expression via env only": good,
    }
    failures = []
    for k, t in mutants.items():
        if not analyse("mutant.yml", t)[0]:
            failures.append(f"mutant not caught: {k}")
    for k, t in controls.items():
        found = analyse("control.yml", t)[0]
        if found:
            failures.append(f"clean control flagged: {k}: {found}")
    for f in failures:
        print("SELFTEST FAILURE: " + f)
    if failures:
        return 1
    print(f"workflow-law selftest OK: {len(mutants)} mutants caught, {len(controls)} clean controls passed")
    return 0


def main(argv):
    if "--selftest" in argv:
        return selftest()
    paths = [a for a in argv if not a.startswith("-")]
    if not paths:
        paths = [os.path.join(ROOT, ".github", "workflows", n) for n in DEFAULT]
    bad = []
    total = {}
    for p in paths:
        name = os.path.basename(p)
        v, c = analyse(name, open(p, encoding="utf-8").read())
        bad.extend(v)
        for k, n in c.items():
            total[k] = total.get(k, 0) + n
    print(f"workflow-law: {len(paths)} workflow files; third-party uses checked: {total.get('uses', 0)}; run/script bodies: "
          f"{total.get('bodies', 0)}; checkouts: {total.get('checkouts', 0)}; container images: {total.get('images', 0)}")
    for k in ("uses", "bodies", "checkouts", "images"):
        if total.get(k, 0) == 0:
            bad.append(f"rule '{k}' looked at no cases: the check would be vacuous")
    if bad:
        for b in bad:
            print("VIOLATION: " + b)
        return 1
    print("workflow-law: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
