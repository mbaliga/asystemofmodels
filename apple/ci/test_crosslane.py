#!/usr/bin/env python3
"""Negative controls for apple/ci/crosslane.py `compare`: it must fail when either lane, or the fixture record, disagrees.

    python3 apple/ci/test_crosslane.py

A small fixture directory is written to a temp dir (two vectors, one ok and one reject), and `compare` is run on the real script with
lines files that are right, and with lines files that are wrong in each way. A right pair must exit 0.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "crosslane.py")
RAN = {"cases": 0}


def fixture(d, vectors):
    os.makedirs(os.path.join(d, "manifest"))
    with open(os.path.join(d, "VERSION"), "w") as f:
        f.write("0.2.0\n")
    with open(os.path.join(d, "manifest", "M02-x.json"), "w") as f:
        json.dump({"family": "M02", "confVersion": "0.2.0", "specRefs": ["x"], "vectors": vectors}, f)


OK = {"id": "M02-901", "expect": {"ok": {"pin": "PINNED"}}}
REJ = {"id": "M02-902", "expect": {"reject": "EXPIRED"}}


def compare(jvm, swift, vectors=(OK, REJ)):
    with tempfile.TemporaryDirectory(prefix="crosslane-test-") as d:
        fx = os.path.join(d, "fx")
        fixture(fx, list(vectors))
        j, s = os.path.join(d, "jvm.lines"), os.path.join(d, "swift.lines")
        with open(j, "w") as f:
            f.write(jvm)
        with open(s, "w") as f:
            f.write(swift)
        p = subprocess.run([sys.executable, SCRIPT, "compare", "--fixtures", fx, "--jvm-lines", j, "--swift-lines", s], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        RAN["cases"] += 1
        return p.returncode, p.stdout + p.stderr


GOOD = "M02-901 ok\nM02-902 reject EXPIRED\n"


class CrossLane(unittest.TestCase):
    def test_all_three_agree(self):
        rc, out = compare(GOOD, GOOD)
        self.assertEqual(rc, 0, out)
        self.assertIn("agree on 2 of 2", out)

    def test_the_jvm_lane_rejects_what_the_fixture_accepts(self):
        rc, out = compare("M02-901 reject SIGNATURE_INVALID\nM02-902 reject EXPIRED\n", GOOD)
        self.assertEqual(rc, 1)
        self.assertIn("M02-901: JVM says 'reject SIGNATURE_INVALID', the fixture records 'ok'", out)
        self.assertIn("JVM 'reject SIGNATURE_INVALID' against Swift 'ok'", out)

    def test_the_swift_lane_accepts_what_the_fixture_rejects(self):
        rc, out = compare(GOOD, "M02-901 ok\nM02-902 ok\n")
        self.assertEqual(rc, 1)
        self.assertIn("M02-902: Swift says 'ok', the fixture records 'reject EXPIRED'", out)

    def test_a_different_reject_code_fails(self):
        rc, out = compare(GOOD, "M02-901 ok\nM02-902 reject NOT_YET_VALID\n")
        self.assertEqual(rc, 1)
        self.assertIn("M02-902", out)

    def test_a_missing_line_fails(self):
        rc, out = compare("M02-901 ok\n", GOOD)
        self.assertEqual(rc, 1)
        self.assertIn("M02-902: the JVM lane printed nothing", out)
        rc, out = compare(GOOD, "M02-902 reject EXPIRED\n")
        self.assertEqual(rc, 1)
        self.assertIn("M02-901: the Swift lane printed nothing", out)

    def test_an_extra_line_fails(self):
        rc, out = compare(GOOD + "M02-999 ok\n", GOOD)
        self.assertEqual(rc, 1)
        self.assertIn("M02-999: printed by a lane but not in the fixture files", out)

    def test_a_fixture_with_only_one_outcome_fails(self):
        rc, out = compare("M02-901 ok\n", "M02-901 ok\n", vectors=(OK,))
        self.assertEqual(rc, 1)
        self.assertIn("both outcomes are required", out)

    def test_an_empty_fixture_fails(self):
        rc, out = compare("", "", vectors=())
        self.assertEqual(rc, 1)
        self.assertIn("holds no vectors", out)

    @classmethod
    def tearDownClass(cls):
        assert RAN["cases"] > 8, "non-vacuity: the negative controls ran %d times" % RAN["cases"]
        print("crosslane negative controls: %d runs of the real script" % RAN["cases"])


if __name__ == "__main__":
    unittest.main(verbosity=1)
