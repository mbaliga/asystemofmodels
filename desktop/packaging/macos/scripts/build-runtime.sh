#!/usr/bin/env bash
# NOT-YET-IMPLEMENTED: PLATFORM_PLAN step MC4 (D-v2). This stub does nothing and fails on purpose, so that no job can mistake it for the real script.
# Entry criteria not met: D-v2 needs D21, D22, D23, D25, D27 and D28 (design 9.3; R3-CONFORMANCE-10, R3-CLOSURE-8).
# What it will do (macos.md 10.1): jdeps, then jlink of the Temurin 21 aarch64 runtime with --strip-native-commands, and the forbidden-module check (java.instrument, jdk.attach, jdk.jdwp.agent, jdk.management.agent).
echo "NOT-YET-IMPLEMENTED: build-runtime.sh belongs to step MC4 of the macOS track (D-v2); it has not been built" >&2
exit 3
