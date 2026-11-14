cmake_minimum_required(VERSION 3.22)
project(delta_benchmark LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(VENDOR "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/native-vendor" ABSOLUTE)
if(NOT EXISTS "${VENDOR}/pins.json")
    message(FATAL_ERROR "Run python3 tools/delta-benchmark/prepare_native.py first")
endif()
set(H "${VENDOR}/HDiffPatch/libHDiffPatch")
file(GLOB HD_SOURCES "${H}/HDiff/private_diff/*.cpp"
    "${H}/HDiff/private_diff/limit_mem_diff/*.cpp"
    "${H}/HDiff/private_diff/window_diff/*.cpp"
    "${H}/HDiff/private_diff/libdivsufsort/*.cpp")
add_library(hdiff STATIC ${HD_SOURCES} "${H}/HDiff/diff.cpp"
    "${H}/HPatchLite/hpatch_lite.c"
    "${H}/HDiff/private_diff/limit_mem_diff/adler_roll.c" "${H}/HPatch/patch.c")
target_compile_definitions(hdiff PUBLIC _IS_USED_MULTITHREAD=0)
add_library(bsdiff STATIC "${VENDOR}/bsdiff/bsdiff.c" "${VENDOR}/bsdiff/bspatch.c")
target_include_directories(bsdiff PUBLIC "${VENDOR}/bsdiff")
set(X "${VENDOR}/xdelta/xdelta3")
add_library(xdelta STATIC "${X}/xdelta3.c")
target_include_directories(xdelta PUBLIC "${X}")
target_compile_definitions(xdelta PUBLIC HAVE_CONFIG_H=0 SIZEOF_SIZE_T=8 SIZEOF_UNSIGNED_INT=4
    SIZEOF_UNSIGNED_LONG=8 SIZEOF_UNSIGNED_LONG_LONG=8 XD3_USE_LARGESIZET=1
    XD3_MAIN=0 XD3_DEBUG=0 SECONDARY_DJW=0 SECONDARY_FGK=0 SECONDARY_LZMA=0)
add_library(delta_benchmark SHARED bridge.cpp stream_bridge.cpp)
target_include_directories(delta_benchmark PRIVATE "${VENDOR}/HDiffPatch")
target_link_libraries(delta_benchmark PRIVATE bsdiff hdiff xdelta log)
foreach(engine bsdiff hdiff xdelta delta_benchmark)
    target_compile_options(${engine} PRIVATE -O3)
endforeach()
