#!/usr/bin/env python3
"""Static checks of the lab dependency law and rules R3, R4, R5 (LAB_SPEC 0, 1.2, 1.3). Standard library only.

  * every lab module's build.gradle.kts references only the project dependencies LAB_SPEC 1.2 allows;
  * only :conformance-runner may depend on :server;
  * no `android.` / `com.android` import and no `com.google.android` reference anywhere under lab/;
  * the frozen egress enum is never given a `peer` member under its frozen name (R4);
  * no wildcard bind address; no listener is created outside the conformance harness (R5).
Prints one line per check and exits non-zero on a violation.
"""
import os
import re
import sys

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


def files(exts):
    for base, dirs, names in os.walk(LAB):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".kotlin")]
        for n in names:
            if n.endswith(exts):
                yield os.path.join(base, n)


def main():
    bad = []
    for mod, allowed in ALLOWED.items():
        f = os.path.join(LAB, mod, "build.gradle.kts")
        text = open(f, encoding="utf-8").read()
        deps = set(re.findall(r'project\(\s*"(:[^"]+)"\s*\)', text))
        extra = deps - allowed
        if extra:
            bad.append(f"{mod}: depends on {sorted(extra)}, which LAB_SPEC 1.2 does not allow")
        if ":server" in deps and mod != "conformance-runner":
            bad.append(f"{mod}: only :conformance-runner may depend on :server")
    print(f"law: {len(ALLOWED)} module build files checked against the LAB_SPEC 1.2 dependency table")

    n = 0
    for f in files((".kt", ".kts", ".java")):
        n += 1
        text = open(f, encoding="utf-8").read()
        rel = os.path.relpath(f, LAB)
        if re.search(r"^\s*import\s+(android|com\.android|com\.google\.android)\.", text, re.M):
            bad.append(f"{rel}: imports an Android package (R3)")
        if PEER_ENUM in text:
            bad.append(f"{rel}: uses the frozen egress enum name for a new meaning (R4)")
        if "0.0.0.0" in text and not rel.endswith("check_law.py"):
            bad.append(f"{rel}: names the wildcard address (R5)")
        listeners = re.findall(r"\b(ServerSocket|embeddedServer|AsomServer\()", text)
        if listeners and not rel.startswith(os.path.join("conformance-runner", "src", "main")) and "/src/test/" not in rel.replace(os.sep, "/"):
            bad.append(f"{rel}: creates a listener outside the conformance harness or a test (R5)")
    print(f"law: {n} Kotlin/Java sources scanned (no android imports, no frozen-enum reuse, no wildcard bind, listeners only in the harness or tests)")
    if bad:
        for b in bad:
            print("VIOLATION: " + b)
        return 1
    print("law: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
