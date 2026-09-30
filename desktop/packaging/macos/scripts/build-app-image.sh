#!/usr/bin/env bash
# NOT-YET-IMPLEMENTED: PLATFORM_PLAN step MC4 (D-v2). This stub does nothing and fails on purpose, so that no job can mistake it for the real script.
# Entry criteria not met: D-v2 needs D21, D22, D23, D25, D27 and D28 (design 9.3; R3-CONFORMANCE-10, R3-CLOSURE-8).
# What it will do (macos.md 10.1): jpackage --type app-image, then the launcher, the helper, the dylibs and the agent plist into the bundle, and the no-native-code-in-any-jar check.
echo "NOT-YET-IMPLEMENTED: build-app-image.sh belongs to step MC4 of the macOS track (D-v2); it has not been built" >&2
exit 3
