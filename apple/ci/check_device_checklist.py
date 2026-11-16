#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check that the iOS device checklist matches the spec's DV-I table (PLATFORM_PLAN.md 6, step I5).

    check_device_checklist.py IOS_MD CHECKLIST_MD

Exits non-zero when:
  * a DV-I id in the spec table is missing from the checklist, or appears in it more than once;
  * the checklist has a DV-I row id the spec does not;
  * a checklist row carries neither NEEDS-DEVICE-VALIDATION nor NEEDS-OWNER-VALIDATION;
  * the word PASS appears anywhere in the checklist (a plan document records no verdicts).
"""
import re
import sys

ROW = re.compile(r"^\|\s*(DV-I\d+)\s*\|(.*)$", re.M)
LABEL = re.compile(r"NEEDS-(DEVICE|OWNER)-VALIDATION")


def main(argv):
    if len(argv) != 3:
        sys.exit(__doc__)
    spec = open(argv[1], encoding="utf-8").read()
    doc = open(argv[2], encoding="utf-8").read()
    spec_ids = [m.group(1) for m in ROW.finditer(spec)]
    rows = {}
    problems = []
    for m in ROW.finditer(doc):
        rows.setdefault(m.group(1), []).append(m.group(0))
    for i in spec_ids:
        n = len(rows.get(i, []))
        if n != 1:
            problems.append("%s appears %d times in the checklist (want 1)" % (i, n))
    for i in rows:
        if i not in spec_ids:
            problems.append("%s is in the checklist but not in the spec" % i)
    for i, rs in rows.items():
        for r in rs:
            if not LABEL.search(r):
                problems.append("%s has no NEEDS-DEVICE-VALIDATION or NEEDS-OWNER-VALIDATION label" % i)
    if re.search(r"PASS", doc):
        problems.append("the word PASS appears in the checklist")
    if not spec_ids:
        problems.append("no DV-I ids found in the spec")
    matched = sum(1 for i in spec_ids if len(rows.get(i, [])) == 1)
    print("%d of %d ids matched" % (matched, len(spec_ids)))
    for p in problems:
        print("FAIL: " + p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
