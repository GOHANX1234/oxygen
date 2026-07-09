cmake_minimum_required(VERSION 3.22.1)
project("oxygens_nativehook")

# Phase 0 goal (plan §6): a trivial JNI "hello world" hook compiling and linking,
# proving the NDK build pipeline works end-to-end via Codemagic.
#
# Phase 1+ (plan §4.6): this target is meant to link against a vetted, existing
# inline-hook engine (Dobby/whale-style — see inline_hook_engine.h) rather than a
# hand-rolled inline hooker. That engine is NOT vendored yet; wire it in via
# add_subdirectory()/FetchContent or a prebuilt .a/.so before Phase 1 hook work
# starts, per the plan's explicit "don't write your own inline hooker" guidance.
add_library(
        oxygens_nativehook
        SHARED
        hook_bridge.cpp
        inline_hook_engine.cpp
)

find_library(log-lib log)

target_link_libraries(
        oxygens_nativehook
        ${log-lib}
)

target_include_directories(oxygens_nativehook PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
