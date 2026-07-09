cmake_minimum_required(VERSION 3.22.1)
project("edgelm")

# CRITICAL for perf. Two things, both required for usable tok/s:
#  1. The `debug` APK builds native with CMAKE_BUILD_TYPE=Debug (=> -O0, ~100x slow).
#  2. GGML_NATIVE=OFF (needed for cross-compile) drops ARM dotprod/fp16, so Q4
#     matmul runs without the sdot/udot instructions (another ~5-10x slow).
# Force -O3 AND the best per-ABI SIMD target. Flags are ABI-specific: armv8.2
# dotprod+fp16 for arm64, NEON+VFPv4 for legacy 32-bit armeabi-v7a.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # armv8.2+ CPU (all modern Dimensity/Snapdragon 8xx). Drop the -march part if
    # you must support older armv8.0 devices.
    set(EDGELM_ARCH_FLAGS "-march=armv8.2-a+dotprod+fp16")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    # 32-bit ARM: enable NEON + VFPv4 (FMA). Far slower than arm64 and only for
    # legacy devices — the small models are the practical ceiling here — but it
    # compiles and runs.
    set(EDGELM_ARCH_FLAGS "-mfpu=neon-vfpv4 -mfloat-abi=softfp")
else()
    set(EDGELM_ARCH_FLAGS "")
endif()
separate_arguments(EDGELM_ARCH_LIST UNIX_COMMAND "${EDGELM_ARCH_FLAGS}")

set(CMAKE_C_FLAGS_DEBUG   "-O3 -DNDEBUG ${EDGELM_ARCH_FLAGS}" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG ${EDGELM_ARCH_FLAGS}" CACHE STRING "" FORCE)
# Belt-and-suspenders: append the flags to EVERY compile command (placed after
# AGP's -O0, so -O3 wins) — this reliably reaches ggml even if the *_FLAGS_DEBUG
# cache override above is ignored by the sub-build. Must precede add_subdirectory.
add_compile_options(-O3 ${EDGELM_ARCH_LIST})

# --- vendored llama.cpp ------------------------------------------------------
# Build llama/ggml as STATIC libs so they link into libedgelm.so — otherwise
# System.loadLibrary("edgelm") can't resolve the transitive libllama.so /
# libggml*.so and the service crashes on load with UnsatisfiedLinkError.
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
# Trim it down for a mobile build: no tests/examples/tools/server.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # NDK has no libgomp by default
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)  # cross-compiling: no -march=native

add_subdirectory(llama.cpp)

# --- our JNI shim ------------------------------------------------------------
add_library(edgelm SHARED
        edgelm_jni.cpp
        llama_runner.cpp)

find_library(log-lib log)
target_link_libraries(edgelm ${log-lib} llama)

# 16 KB page-size alignment (Android 15+/Play from Nov 1 2025).
target_link_options(edgelm PRIVATE "-Wl,-z,max-page-size=16384")
