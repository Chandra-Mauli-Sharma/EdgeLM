cmake_minimum_required(VERSION 3.22.1)
project("edgelm")

# CRITICAL for perf. Two things, both required for usable tok/s:
#  1. The `debug` APK builds native with CMAKE_BUILD_TYPE=Debug (=> -O0, ~100x slow).
#  2. GGML_NATIVE=OFF (needed for cross-compile) drops ARM dotprod/fp16, so Q4
#     matmul runs without the sdot/udot instructions (another ~5-10x slow).
# Force -O3 AND the best per-ABI SIMD target. Flags are ABI-specific: armv8.2
# dotprod+fp16 for arm64, NEON+VFPv4 for legacy 32-bit armeabi-v7a.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # armv8.2+ CPU (all modern Dimensity/Snapdragon 8xx). +i8mm enables ggml's int8
    # matrix-multiply GEMM (used by Q4_0 aarch64 repacking + Q8_0); ggml dispatches it
    # at runtime via HWCAP, so CPUs without i8mm safely fall back to dotprod/NEON.
    # Drop the -march part if you must support older armv8.0 devices.
    set(EDGELM_ARCH_FLAGS "-march=armv8.2-a+dotprod+i8mm+fp16")
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

# ggml probes ARM features (dotprod/i8mm/fp16) with check_cxx_source_compiles, which uses
# CMAKE_REQUIRED_FLAGS — NOT add_compile_options. Under GGML_NATIVE=OFF those probes run
# without -march and FAIL, so ggml skips the matching CPU + KleidiAI microkernels while the
# code (built with our forced -march) still references them → undefined kai_* symbols at
# link. Feed the arch flags into the probes so they succeed and the kernels get compiled.
set(CMAKE_REQUIRED_FLAGS "${EDGELM_ARCH_FLAGS}")

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

# ARM KleidiAI microkernels: TRIED and MEASURED on-device — no benefit here, so left OFF.
#
# On Q4_0 + our -march=+i8mm build, ggml already repacks Q4_0 into the i8mm layout at load and
# dispatches its tuned GEMM at runtime, so KleidiAI is largely redundant. And decode is
# memory-bandwidth-bound, so a faster matmul kernel can't move it: measured 3B decode was
# 8.0 tok/s WITH KleidiAI vs ~7.5-8.5 without (~0% change; prefill within noise). Not worth
# KleidiAI's costs — a network FetchContent at configure time, longer builds, a larger .so.
#
# If ever revisited (e.g. a non-Q4_0 or prefill-bound path where the kernel win matters):
# enabling it requires GGML_CPU_ARM_ARCH="armv8.2-a+dotprod+i8mm+fp16" so ggml's ARCH_FLAGS
# carry the +dotprod+i8mm tags — otherwise (GGML_NATIVE=OFF + -march via add_compile_options,
# not CMAKE_C_FLAGS) ggml skips the kai_* i8mm/dotprod ukernels while kernels.cpp still
# references them → undefined symbols at link. KleidiAI is aarch64-only. See git history.
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

# --- optional Vulkan GPU backend (PHASE1-VULKAN-GPU.md) ----------------------
# OFF by default so the CPU build NEVER needs the host Vulkan SDK. ggml's Vulkan
# backend precompiles its shaders at build time with `glslc` (LunarG Vulkan SDK)
# — enabling this without glslc on PATH fails the build during shader generation.
# Once glslc is installed, enable by passing -DEDGELM_VULKAN=ON from the Gradle
# externalNativeBuild arguments (see runtime-service/build.gradle.kts). ggml links
# the device's libvulkan.so itself (present on Android API 24+); nothing else in
# the link step changes.
option(EDGELM_VULKAN "Build the ggml Vulkan GPU backend" OFF)
if(EDGELM_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    # ggml's Vulkan backend needs the HOST SPIRV-Headers / SPIRV-Tools / glslc from the
    # LunarG Vulkan SDK at configure time (to generate its compute shaders). The Android
    # NDK toolchain restricts find_package to the sysroot, so point it at the SDK and
    # allow package lookups outside the sysroot.
    if(DEFINED ENV{VULKAN_SDK})
        list(APPEND CMAKE_PREFIX_PATH "$ENV{VULKAN_SDK}")
        set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
        # ggml-vulkan.cpp uses the C++ bindings <vulkan/vulkan.hpp>, which the NDK
        # sysroot doesn't ship (it only has the C header). Add the SDK's headers to
        # the include path — headers only; linking still uses the NDK's libvulkan.so.
        include_directories("$ENV{VULKAN_SDK}/Include")
        message(STATUS "EdgeLM: using VULKAN_SDK=$ENV{VULKAN_SDK}")
    else()
        message(WARNING "EdgeLM: VULKAN_SDK env var not set — SPIRV-Headers likely won't be found")
    endif()
    message(STATUS "EdgeLM: Vulkan GPU backend ENABLED")
else()
    message(STATUS "EdgeLM: Vulkan GPU backend disabled (CPU only) — pass -DEDGELM_VULKAN=ON to enable")
endif()

add_subdirectory(llama.cpp)

# --- our JNI shim ------------------------------------------------------------
add_library(edgelm SHARED
        edgelm_jni.cpp
        llama_runner.cpp)

find_library(log-lib log)
target_link_libraries(edgelm ${log-lib} llama)

# 16 KB page-size alignment (Android 15+/Play from Nov 1 2025).
target_link_options(edgelm PRIVATE "-Wl,-z,max-page-size=16384")
