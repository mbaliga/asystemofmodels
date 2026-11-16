#!/usr/bin/env python3
"""Negative controls for apple/ci/lane_diff.py: the check must FAIL on every kind of unlisted or stale difference.

    python3 apple/ci/test_lane_diff.py

Each case writes small lines files, runs the real script and checks the exit status and the named problem. A positive case (everything
listed and true) must exit 0, so the failures are not just a script that always fails. Every case counts in a total that must be non-zero.
"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "lane_diff.py")
RAN = {"cases": 0}


def run(jvm, swift, known=None, not_impl=None):
    with tempfile.TemporaryDirectory(prefix="lane-diff-test-") as d:
        paths = {}
        for name, body in (("jvm", jvm), ("swift", swift), ("known", known), ("noimpl", not_impl)):
            if body is not None:
                paths[name] = os.path.join(d, name + ".txt")
                with open(paths[name], "w", encoding="utf-8") as f:
                    f.write(body)
        cmd = [sys.executable, SCRIPT, paths["jvm"], paths["swift"]]
        if "known" in paths:
            cmd += ["--known", paths["known"]]
        if "noimpl" in paths:
            cmd += ["--not-implemented", paths["noimpl"]]
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        RAN["cases"] += 1
        return p.returncode, p.stdout + p.stderr


JVM = "M01-001 ok\nM02-101 ok\nM03-111 reject ROLLBACK\nM04-301 ok\n"
SWIFT = "M01-001 ok\nM02-101 reject DERIVATION_MISMATCH\nM03-111 reject ROLLBACK\n"
KNOWN = "M02-101 F-1 thermal-drift row flag\n"
NOIMPL = "M04-301 BLOCKED(trace)\n"


class LaneDiff(unittest.TestCase):
    def test_everything_listed_and_true_passes(self):
        rc, out = run(JVM, SWIFT, KNOWN, NOIMPL)
        self.assertEqual(rc, 0, out)
        self.assertIn("agree=2 disagree=1 (known 1) jvm-only=1 (not implemented 1)", out)

    def test_an_unlisted_disagreement_fails(self):
        rc, out = run(JVM, SWIFT, "", NOIMPL)
        self.assertEqual(rc, 1)
        self.assertIn("M02-101: disagreement not listed in the known list", out)
        self.assertIn("UNEXPLAINED", out)

    def test_a_different_reject_code_is_a_disagreement(self):
        rc, out = run("M03-111 reject ROLLBACK\n", "M03-111 reject EQUIVOCATION\n")
        self.assertEqual(rc, 1)
        self.assertIn("M03-111: disagreement not listed", out)

    def test_a_stale_known_entry_fails(self):
        rc, out = run(JVM, JVM.replace("M04-301 ok\n", ""), KNOWN, NOIMPL)
        self.assertEqual(rc, 1)
        self.assertIn("M02-101: listed as a known disagreement but the lanes agree (stale list)", out)

    def test_a_known_entry_that_neither_lane_prints_fails(self):
        rc, out = run(JVM, SWIFT, KNOWN + "M09-999 F-1 typo\n", NOIMPL)
        self.assertEqual(rc, 1)
        self.assertIn("M09-999: listed as a known disagreement but a lane did not print it", out)

    def test_a_jvm_only_vector_that_is_not_listed_fails(self):
        rc, out = run(JVM, SWIFT, KNOWN, "")
        self.assertEqual(rc, 1)
        self.assertIn("M04-301: printed by the JVM lane only and not listed as not implemented", out)

    def test_a_swift_only_vector_fails(self):
        rc, out = run(JVM, SWIFT + "M07-001 ok\n", KNOWN, NOIMPL)
        self.assertEqual(rc, 1)
        self.assertIn("M07-001: printed by the Swift lane only", out)

    def test_a_vector_listed_as_not_implemented_but_now_produced_fails(self):
        rc, out = run(JVM, SWIFT + "M04-301 ok\n", KNOWN, NOIMPL)
        self.assertEqual(rc, 1)
        self.assertIn("M04-301: listed as not implemented but the Swift lane printed it", out)

    def test_a_not_implemented_entry_the_jvm_lane_does_not_print_fails(self):
        rc, out = run(JVM, SWIFT, KNOWN, NOIMPL + "M04-777 BLOCKED(typo)\n")
        self.assertEqual(rc, 1)
        self.assertIn("M04-777: listed as not implemented but the JVM lane does not print it either", out)

    def test_an_entry_without_a_reason_fails(self):
        rc, out = run(JVM, SWIFT, "M02-101\n", NOIMPL)
        self.assertEqual(rc, 1)
        self.assertIn("M02-101: no reason given", out)
        rc, out = run(JVM, SWIFT, KNOWN, "M04-301\n")
        self.assertEqual(rc, 1)
        self.assertIn("M04-301: no reason given", out)

    def test_a_duplicate_entry_fails(self):
        rc, out = run(JVM, SWIFT, KNOWN + KNOWN, NOIMPL)
        self.assertEqual(rc, 1)
        self.assertIn("M02-101: listed twice", out)

    def test_a_duplicate_vector_id_in_a_lines_file_stops_the_run(self):
        rc, out = run("M01-001 ok\nM01-001 ok\n", "M01-001 ok\n")
        self.assertNotEqual(rc, 0)
        self.assertIn("duplicate vector id", out)

    def test_comments_and_blank_lines_in_the_lists_are_ignored(self):
        rc, out = run(JVM, SWIFT, "# why\n\n" + KNOWN + "   # indented comment\n", "# BLOCKED\n" + NOIMPL)
        self.assertEqual(rc, 0, out)

    @classmethod
    def tearDownClass(cls):
        assert RAN["cases"] > 12, "non-vacuity: the negative controls ran %d times" % RAN["cases"]
        print("lane_diff negative controls: %d runs of the real script" % RAN["cases"])


if __name__ == "__main__":
    unittest.main(verbosity=1)
