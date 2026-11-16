#!/usr/bin/env python3
"""The reproducible gate summary of the proto-integration track (LAB_SPEC 7.4, 7.6, 8.1, work item L0.5).

  ./gradlew -p lab :mesh-proto:test --rerun-tasks        # JDK 21, then again with JAVA_HOME=/opt/jdks/jdk-17
  python3 lab/mesh-proto/tools/integration/summary.py    # prints the summary lines and checks them

`mesh-proto/build.gradle.kts` does not show test output, so the lines `ProtoIntegrationGateTest` prints with println are read from the Gradle test report
(`build/test-results/test/TEST-*.xml`, element system-out). The script fails (exit 1) when a required line is missing or has a zero where the spec wants a count above zero:

  L-L15 MEASURED sessions: <n> (n > 0), mismatches: 0
  L-L16 per-frame-type counts: {every listed type present and non-zero}
  W08-over-tls: accepted bad chains: 0; ClientHellos with pre_shared_key: 0; sessions with client CertificateVerify: <k>/<k>
  W08-frames-tls: ... refusal kinds exercised: <x>/<x> ... frames after control failure: 0

Evidence label for everything it prints: LAB, oracle: self, NOT DEVICE EVIDENCE.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", ".."))
RESULTS = os.path.join(ROOT, "lab", "mesh-proto", "build", "test-results", "test")
PREFIXES = (
    "== proto integration gate", "calibration:", "runs:", "L-L15", "L-L16", "structural laws", "LP-1", "L-L13", "L-L14", "W08-frames-tls", "W08-handshake-session", "W08-over-tls", "evidence:",
)
L16 = ["HELLO", "HELLO_ACK", "STATE_REQ", "STATE", "MANIFEST_REQ", "MANIFEST", "GOAWAY", "ERROR", "REVOKE_NOTICE", "PAIR_HELLO", "PAIR_CHALLENGE", "PAIR_DECISION", "PAIR_COMMIT", "PAIR_COMMIT_ACK", "EXT_IGNORED"]


def load():
    files = glob.glob(os.path.join(RESULTS, "TEST-*ProtoIntegrationGateTest.xml"))
    if not files:
        sys.exit("no test report for ProtoIntegrationGateTest under %s (run the Gradle test task first)" % RESULTS)
    root = ET.parse(files[0]).getroot()
    out = root.find("system-out")
    text = out.text if out is not None and out.text else ""
    return root, text.splitlines()


def main():
    root, lines = load()
    tests, failures, errors = int(root.get("tests")), int(root.get("failures")), int(root.get("errors"))
    shown = [l for l in lines if l.startswith(PREFIXES) or l.startswith("W08-case")]
    for l in shown:
        if not l.startswith("W08-case"):
            print(l)
    cases = [l for l in shown if l.startswith("W08-case")]
    print("W08-case lines: %d (one per hostile handshake case; see the test report for the list)" % len(cases))
    problems = []
    if failures or errors:
        problems.append("the gate class has %d failure(s) and %d error(s)" % (failures, errors))
    full = "\n".join(lines)
    m = re.search(r"L-L15 MEASURED sessions: (\d+) \(n > 0\), mismatches: (\d+)", full)
    if not m or int(m.group(1)) == 0 or int(m.group(2)) != 0:
        problems.append("L-L15 line missing, zero MEASURED sessions, or mismatches: %s" % (m.group(0) if m else None))
    m = re.search(r"L-L16 per-frame-type counts: \{(.*?)\}", full)
    if not m:
        problems.append("L-L16 per-frame-type counts line missing")
    else:
        counts = dict(kv.split("=") for kv in m.group(1).split(", "))
        zero = [t for t in L16 if int(counts.get(t, "0")) == 0]
        if zero:
            problems.append("L-L16 never exercised: %s" % zero)
    m = re.search(r"W08-over-tls: accepted bad chains: (\d+); ClientHellos with pre_shared_key: (\d+); sessions with client CertificateVerify: (\d+)/(\d+)", full)
    if not m or m.group(1) != "0" or m.group(2) != "0" or m.group(3) != m.group(4) or int(m.group(4)) == 0:
        problems.append("W08-over-tls line missing or not 0 / 0 / k/k with k > 0: %s" % (m.group(0) if m else None))
    m = re.search(r"W08-frames-tls: hostile cases: (\d+); refusal kinds exercised: (\d+)/(\d+);.*frames after control failure: (\d+);", full)
    if not m or int(m.group(1)) == 0 or m.group(2) != m.group(3) or m.group(4) != "0":
        problems.append("W08-frames-tls line missing, zero cases, an unexercised refusal kind, or frames after a control failure: %s" % (m.group(0) if m else None))
    if "partial run:" in full:
        problems.append("the gate class ran only partly (a filtered run): its non-vacuity assertions were not made")
    print("gate class: %d tests, %d failures, %d errors" % (tests, failures, errors))
    if problems:
        for p in problems:
            print("PROBLEM:", p)
        return 1
    print("summary OK (LAB, oracle: self, NOT DEVICE EVIDENCE)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
