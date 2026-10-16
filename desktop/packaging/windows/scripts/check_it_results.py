#!/usr/bin/env python3
"""Reads the JUnit XML of `:packaging:windows:winplatform:test` and judges the Windows-only integration tests. Standard library only.

  check_it_results.py --expect windows <results-dir>   on a Windows runner: the integration tests must have RUN and passed.
  check_it_results.py --expect skipped <results-dir>   anywhere else: every one of them must have been SKIPPED, none passed.

The integration tests are the classes in the package `xyz.mdhv.asom.desktop.win.windows`. A skipped test proves nothing, so
on Windows a skip is a failure, with one exception the plan names: `PcpIT` (the TPM tier, which a hosted VM cannot answer,
AW11). Elsewhere a PASSED integration test is a failure: it would mean the OS guard did not work. Both modes fail on a count
below MIN_TESTS, so a filter that selected nothing cannot pass.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

PACKAGE = "xyz.mdhv.asom.desktop.win.windows."
MIN_TESTS = 20
MAY_SKIP_ON_WINDOWS = {"PcpIT"}


def load(directory):
    tests = []
    for f in sorted(glob.glob(os.path.join(directory, "TEST-*.xml"))):
        for tc in ET.parse(f).getroot().iter("testcase"):
            cls = tc.get("classname", "")
            if not cls.startswith(PACKAGE):
                continue
            state = "passed"
            note = ""
            if tc.find("failure") is not None or tc.find("error") is not None:
                state = "failed"
                e = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
                note = (e.get("message") or "")[:200]
            elif tc.find("skipped") is not None:
                state = "skipped"
                note = tc.find("skipped").get("message") or ""
            tests.append((cls[len(PACKAGE):], tc.get("name"), state, note))
    return tests


def main(argv):
    if len(argv) != 3 or argv[0] != "--expect" or argv[1] not in ("windows", "skipped"):
        print(__doc__)
        return 2
    mode, directory = argv[1], argv[2]
    tests = load(directory)
    passed = [t for t in tests if t[2] == "passed"]
    skipped = [t for t in tests if t[2] == "skipped"]
    failed = [t for t in tests if t[2] == "failed"]
    print(f"integration tests: {len(tests)} found, {len(passed)} passed, {len(skipped)} skipped, {len(failed)} failed (expecting: {mode})")
    for cls, name, state, note in tests:
        if state == "skipped":
            print(f"  SKIPPED {cls}: {name} {('- ' + note) if note else ''}")
    bad = []
    if len(tests) < MIN_TESTS:
        bad.append(f"only {len(tests)} integration tests found, expected at least {MIN_TESTS}")
    bad += [f"FAILED {c}: {n}: {note}" for c, n, s, note in failed]
    if mode == "windows":
        bad += [f"SKIPPED on Windows (not allowed): {c}: {n} {note}" for c, n, s, note in skipped if c not in MAY_SKIP_ON_WINDOWS]
        if not passed:
            bad.append("no integration test passed")
    else:
        bad += [f"PASSED off Windows (the OS guard failed): {c}: {n}" for c, n, s, note in passed]
    if bad:
        for b in bad:
            print("VIOLATION: " + b)
        return 1
    print("integration tests: OK for mode " + mode)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
