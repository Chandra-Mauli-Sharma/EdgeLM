cmake_minimum_required(VERSION 3.22.1)
project("edgelm")

# CRITICAL for perf. Two things, both required for usable tok/s:
#  1. The `debug` APK builds native with CMAKE_BUILD_TYPE=Debug (=> -O0, ~100x slow).
#  2. GGML_NATIVE=OFF (needed for cross-compile) drops ARM dotprod/fp16, so Q4
#     matmul runs without the sdot/udot instructions (another ~5-10x slow).
# Force -O3 AND an armv8.2 target with dotprod+fp16 for the Debug config.
# NOTE: requires an armv8.2+ CPU (all Dimensity/Snapdragon 8xx do). Drop the
# -march part if you must support older armv8.0 devices.
set(CMAKE_C_FLAGS_DEBUG   "-O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
# Belt-and-suspenders: append the flags to EVERY compile command (placed after
# AGP's -O0, so -O3 wins) — this reliably reaches ggml even if the *_FLAGS_DEBUG
# cache override above is ignored by the sub-build. Must precede add_subdirectory.
add_compile_options(-O3 -march=armv8.2-a+dotprod+fp16)

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
