#!/usr/bin/env python3
"""Writes lab/conformance/router/R01, R02, R03, R05, R06 and M08 (LAB_SPEC 6.10, 6.6). R04 is normative, exists already and is never touched.

The expected values are of three kinds, each named in the vector's description:
  hand  typed by hand from the spec (R01 exclusion lists and error codes, R03 orders, the R05 table, the M08 worked numbers and adversary table);
  ref   computed by ref.py, an independent Python reading of LAB_SPEC 6.3-6.7 that never calls the Kotlin code (R02, and cross-checks of R01, R03, M08); the
        spec's own worked numbers (R02-r3-001, M08-001..004, M08-017) are asserted inside the generators;
  pin   the R06 breaker vectors write out the v1 CooldownRegistry curve; a Kotlin law test compares the pure breaker with the real registry.
Every vector is `oracle: self` (LAB_SPEC 4.10, R9). Python 3 standard library only. Run from anywhere, then run `python3 lab/tools/regen_index.py`.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
for module in ("gen_r01", "gen_r02", "gen_r03", "gen_r05", "gen_r06", "gen_m08"):
    __import__(module)
