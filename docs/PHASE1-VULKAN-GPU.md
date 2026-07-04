# Phase 1 — Vulkan GPU backend

CPU decode gets a small model over the >30 tok/s gate, but a 3B sits under it.
Vulkan offloads the matmul-heavy work to the phone GPU (Adreno / Mali / Xclipse)
via ggml's Vulkan backend — the portable path that works across vendors without a
per-SoC SDK. This is the "performance ceiling" lever from Part 3 of the arch doc.

Prereqs: you've completed the Week 1 llama.cpp integration (vendored submodule +
real `llama_runner.cpp`). This guide only changes the **build flags** and **one
line** of the runner.

---

## Step 1 — Host toolchain: a GLSL compiler

ggml's Vulkan backend precompiles its compute shaders at build time using a host
tool (`vulkan-shaders-gen`) that needs **`glslc`** on your PATH. Install the
**LunarG Vulkan SDK** (Windows) and confirm:

```powershell
glslc --version
```

If `glslc` isn't found, the Vulkan build fails while generating shaders — that's
the #1 setup snag, so verify this first.

## Step 2 — Turn on the Vulkan backend in CMake

In `runtime-service/src/main/cpp/CMakeLists.txt`, add **before** `add_subdirectory(llama.cpp)`:

```cmake
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
```

Nothing else in the link step changes — ggml pulls in the device's `libvulkan.so`
(present on Android API 24+) itself. Keep `-DANDROID_STL=c++_static` and the
16 KB alignment flag.

## Step 3 — Offload layers to the GPU (one line)

In `llama_runner.cpp`, `load_model()`:

```cpp
mp.n_gpu_layers = 99;   // was 0 (CPU). 99 = "offload everything that fits".
```

Tune later: fewer layers if the model doesn't fit GPU-accessible memory; more is
faster until you run out of room.

## Step 4 — Add a CPU fallback (drivers vary a lot)

Mobile Vulkan drivers are uneven. Make GPU an attempt, not an assumption — retry
on CPU if GPU load fails, so a bad driver degrades to slower instead of dead:

```cpp
Model* load_model(const char* path) {
    ensure_backend();
    auto try_load = [&](int gpu_layers) -> llama_model* {
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = gpu_layers;
        mp.use_mmap     = true;
        return llama_model_load_from_file(path, mp);
    };

    llama_model* lm = try_load(99);            // GPU first
    if (!lm) { LOGE("GPU load failed; falling back to CPU"); lm = try_load(0); }
    if (!lm) { LOGE("model load failed: %s", path); return nullptr; }

    auto* m = new Model();
    m->model = lm;
    unsigned hw = std::thread::hardware_concurrency();
    m->n_threads = (int) std::max(1u, hw ? hw / 2 : 4u);
    return m;
}
```

## Step 5 — Build, install, verify the GPU is actually used

First Vulkan build is slow (shader generation). Then:

```powershell
.\gradlew.bat :runtime-service:installDebug
adb shell logcat -s edgelm-native ggml
```

In logcat you want a ggml line naming your GPU, e.g. `ggml_vulkan: Found GPU:
Adreno (TM) 7xx`. If you see it fall back to CPU, check `glslc`, the driver, and
that the device reports Vulkan 1.1+.

## Step 6 — Re-measure the gate

Run the same 3B prompt CPU vs GPU and compare tokens/sec in the app:

| Path | 3B Q4 tok/s (rough) | Notes |
|---|---|---|
| CPU (Week 1 baseline) | ~5–15 | below the gate |
| Vulkan GPU | ~15–40+ | device/driver dependent; often clears >30 |

Record both numbers on your target device — that CPU→GPU delta is exactly the
Phase 1 result that justifies the backend.

---

## Memory: how this interacts with the shared-runtime thesis

- On mobile the GPU uses **unified system memory**, but offloaded weights are
  copied into **GPU-accessible buffers** — these are *not* the mmap'd, file-backed
  clean pages you measured in Phase 0. So the "480 MB Private Clean" picture shifts
  to device/driver allocations when you offload.
- **Cross-app sharing still holds**, and that's what matters: the single service
  owns the one GPU context and one set of weight buffers; apps remain thin Binder
  clients and still don't duplicate the model. The thesis ("N apps, one copy")
  survives — the copy just lives in GPU memory instead of the page cache.
- Re-run `tools/measure_memory.ps1` with two apps after enabling GPU to confirm the
  service footprint still doesn't scale with app count.

## Caveats & tuning

- **Driver variance is real.** Some Mali/older Adreno drivers are slow or buggy in
  Vulkan compute; the CPU fallback in Step 4 keeps you shipping.
- **Thermals.** GPU decode is faster but hotter; sustained runs throttle. This is
  precisely what the thermal/energy governors (Part 8) manage at scale — for now,
  benchmark in short bursts and watch for throttling skewing your tok/s.
- **Prefill vs decode.** GPU helps prefill most (parallel matmuls). The arch doc's
  "phase-split placement" (prefill on GPU, decode on NPU/CPU) is the next
  optimization once QNN/NPU lands.
- **APK size / first run.** Vulkan shaders add to build time and the `.so`; first
  model load may compile/upload shaders (one-time).

## Where this sits on the roadmap

You now have: shared runtime (proven) → real inference (Week 1) → OpenAI HTTP shim
→ GPU acceleration. Remaining Phase 1 shortlist from the architecture doc:
**context pooling + KV reuse**, then the **real scheduler** (priority, token-boundary
preemption, battery/thermal governors), then **QNN/NPU** for the phase-split win.
