cmake_minimum_required(VERSION 3.22.1)

project(pojav_native LANGUAGES C)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)

find_package(bytehook REQUIRED CONFIG)

add_compile_options(-Wall -Wextra)

add_library(
    pojavexec
    SHARED
    egl_bridge.c
    ctxbridges/loader_dlopen.c
    ctxbridges/gl_bridge.c
    ctxbridges/osm_bridge.c
    ctxbridges/egl_loader.c
    ctxbridges/osmesa_loader.c
    ctxbridges/swap_interval_no_egl.c
    environ/environ.c
    jvm_hooks/emui_iterator_fix_hook.c
    jvm_hooks/java_exec_hooks.c
    jvm_hooks/lwjgl_dlopen_hook.c
    input_bridge_v3.c
    jre_launcher.c
    utils.c
    stdio_is.c
    driver_helper/nsbypass.c
)

target_include_directories(
    pojavexec
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ctxbridges
    environ
    jvm_hooks
    driver_helper
    GL
)

target_compile_options(
    pojavexec
    PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
)

target_link_libraries(
    pojavexec
    PRIVATE
    android
    dl
)

add_library(
    exithook
    SHARED
    native_hooks/exit_hook.c
    native_hooks/chmod_hook.c
)

target_include_directories(
    exithook
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
    exithook
    PRIVATE
    bytehook::bytehook
    pojavexec
    dl
)

add_library(
    pojavexec_awt
    SHARED
    awt_bridge.c
)

# This target is intentionally a placeholder so runtime can still load libawt_headless.so.
add_library(
    awt_headless
    SHARED
    awt_xawt/awt_headless_dummy.c
)

add_library(
    awt_xawt
    SHARED
    awt_xawt/xawt_fake.c
)

target_link_libraries(
    awt_xawt
    PRIVATE
    awt_headless
)
