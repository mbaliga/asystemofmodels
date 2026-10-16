# NOT-YET-IMPLEMENTED: PLATFORM_PLAN step MC4 (D-v2; entry criteria D21, D22, D23, D25, D27 and D28 not met: design 9.3, R3-CONFORMANCE-10, R3-CLOSURE-8).
# Fails on purpose so that no job can mistake this for the real launcher build (macos.md 7.3, 10.1).
cmake_minimum_required(VERSION 3.25)
project(asom_launcher C)
message(FATAL_ERROR "NOT-YET-IMPLEMENTED: launcher/CMakeLists.txt belongs to step MC4 of the macOS track")
