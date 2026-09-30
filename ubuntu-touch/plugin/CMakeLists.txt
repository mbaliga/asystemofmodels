# The Asom.Bridge QML plugin: NodeProcess (the asom-ut-ctl/1 pipe to the bundled JVM), DisplayKeeper (keep-display-on, L-UT2) and
# LifecycleForwarder (Qt application state -> lifecycle frames). Qt 5.15 only (no Qt 6: ubuntu-touch.md 10.4).
find_package(Qt5 5.15 REQUIRED COMPONENTS Core Gui Qml DBus)

set(CMAKE_AUTOMOC ON)

add_library(asom_bridge MODULE
    plugin.cpp
    nodeprocess.cpp nodeprocess.h
    displaykeeper.cpp displaykeeper.h
    lifecycle.cpp lifecycle.h)
target_link_libraries(asom_bridge PRIVATE Qt5::Core Qt5::Gui Qt5::Qml Qt5::DBus)
target_compile_options(asom_bridge PRIVATE -Wall -Wextra -Werror=return-type)
set_target_properties(asom_bridge PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/qmlmodules/Asom/Bridge)
configure_file(qmldir ${CMAKE_BINARY_DIR}/qmlmodules/Asom/Bridge/qmldir COPYONLY)

# A Click puts QML plugins under lib/<arch triplet>/<module path> (ubuntu-touch.md 8.1).
if(DEFINED ENV{ARCH_TRIPLET})
    set(ASOM_UT_TRIPLET $ENV{ARCH_TRIPLET})
elseif(CMAKE_LIBRARY_ARCHITECTURE)
    set(ASOM_UT_TRIPLET ${CMAKE_LIBRARY_ARCHITECTURE})
else()
    set(ASOM_UT_TRIPLET aarch64-linux-gnu)
endif()
install(TARGETS asom_bridge LIBRARY DESTINATION lib/${ASOM_UT_TRIPLET}/Asom/Bridge)
install(FILES qmldir DESTINATION lib/${ASOM_UT_TRIPLET}/Asom/Bridge)

# Native unit tests (plain Qt, no QML): built only on request, run by ctest.
option(ASOM_UT_BUILD_TESTS "Build the plugin's Qt unit tests" OFF)
if(ASOM_UT_BUILD_TESTS)
    find_package(Qt5 5.15 REQUIRED COMPONENTS Test)
    add_executable(tst_plugin ../tests/native/tst_plugin.cpp nodeprocess.cpp nodeprocess.h displaykeeper.cpp displaykeeper.h lifecycle.cpp lifecycle.h)
    target_include_directories(tst_plugin PRIVATE .)
    target_link_libraries(tst_plugin PRIVATE Qt5::Core Qt5::Gui Qt5::Qml Qt5::DBus Qt5::Test)
    add_test(NAME tst_plugin COMMAND tst_plugin)
endif()
