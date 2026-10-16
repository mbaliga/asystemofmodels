cmake_minimum_required(VERSION 3.16)
project(asom-ut LANGUAGES CXX)

# UT-0 scaffold: ships nothing (ubuntu-touch.md 10.1). Built by Clickable (builder: cmake) inside a pinned UBports image, which
# passes -DCMAKE_INSTALL_PREFIX=/ and DESTDIR=<install dir>, so the relative destinations below are the Click's tree.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
include(CTest)

add_subdirectory(plugin)

if(DEFINED ENV{ARCH_TRIPLET})
    set(ASOM_UT_TRIPLET $ENV{ARCH_TRIPLET})
elseif(CMAKE_LIBRARY_ARCHITECTURE)
    set(ASOM_UT_TRIPLET ${CMAKE_LIBRARY_ARCHITECTURE})
else()
    set(ASOM_UT_TRIPLET aarch64-linux-gnu)
endif()

# The manifest and the desktop file keep their @CLICK_ARCH@ and @CLICK_FRAMEWORK@ placeholders: Clickable replaces them in the
# install dir. They are copied, never configure_file()d (CMake would blank them). The AppArmor file is configure_file()d with
# the policy version (2404.1) because Clickable parses it as JSON before any substitution (ERR-UT-CLICK-2); the .in keeps the
# placeholder so the AppArmor approximation can test other policy versions.
install(FILES manifest.json.in DESTINATION . RENAME manifest.json)
set(APPARMOR_POLICY 2404.1)
configure_file(asom.apparmor.in ${CMAKE_CURRENT_BINARY_DIR}/asom.apparmor @ONLY)
install(FILES ${CMAKE_CURRENT_BINARY_DIR}/asom.apparmor DESTINATION .)
install(FILES asom.desktop.in DESTINATION . RENAME asom.desktop)
install(FILES assets/asom.svg DESTINATION assets)
install(DIRECTORY qml/ DESTINATION qml)
install(FILES runtime/jvm.options DESTINATION lib/asom)

# The bundled JVM runtime and node jar come from CI artefacts staged under runtime/stage (jlink.sh) and jvm/ut-host/build/libs.
# Only the arm64 click carries them: the amd64 build exists to run the QML tests and never ships (ARCH is set by Clickable).
if(NOT DEFINED ASOM_UT_STAGE)
    set(ASOM_UT_STAGE ${CMAKE_CURRENT_SOURCE_DIR}/runtime/stage)
endif()
if(NOT "$ENV{ARCH}" STREQUAL "amd64" AND NOT ASOM_UT_NO_RUNTIME)
    foreach(f rt/bin/java rt.sha256 asom-ut-node.jar jar.sha256)
        if(NOT EXISTS ${ASOM_UT_STAGE}/${f})
            message(FATAL_ERROR "runtime/stage/${f} is missing: run runtime/jlink.sh and tools/stage_node.sh first (BLOCKED, never a click without its runtime)")
        endif()
    endforeach()
    install(DIRECTORY ${ASOM_UT_STAGE}/rt DESTINATION lib/asom USE_SOURCE_PERMISSIONS)
    install(FILES ${ASOM_UT_STAGE}/rt.sha256 ${ASOM_UT_STAGE}/asom-ut-node.jar ${ASOM_UT_STAGE}/jar.sha256 DESTINATION lib/asom)
endif()
