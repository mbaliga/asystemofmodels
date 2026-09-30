#!/usr/bin/env bash
# NOT-YET-IMPLEMENTED: PLATFORM_PLAN step MC3 (D-v2; entry criteria D21, D22, D23, D25, D27 and D28 not met: design 9.3, R3-CONFORMANCE-10, R3-CLOSURE-8). This stub does nothing and fails on purpose.
# What it will do (macos.md 10.1): CMake at the pinned llama.cpp commit, then libasom-llama-jni.dylib and the ggml dylibs, an otool -L check
# that lists only @rpath libraries and system frameworks (Metal, Foundation, Accelerate), and a sha256 line per file.
echo "NOT-YET-IMPLEMENTED: build-llama-jni.sh belongs to step MC3 of the macOS track (D-v2); it has not been built" >&2
exit 3
