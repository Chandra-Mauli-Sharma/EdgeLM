# Phase B — LiteRT-LM integration guide

_How to stand up the `LiteRtEngine` behind the existing seam and get the go/no-go number.
Prerequisite reading: docs/ENGINE-ABSTRACTION.md. This phase needs the LiteRT-LM library and a
physical NPU/GPU device — it can't be done in the sandbox; run it on Windows + a real handset._

## The gate (read first)

Phase B exists to produce **one measurement**: decode + prefill tok/s for the Gemma `.litertlm`
model on LiteRT's **GPU (OpenCL)** backend — the generic, runs-on-any-capable-GPU path — versus
the ~8 tok/s CPU baseline. Commit to Phases C/D **only if** that delta is decisive (≥2–3× decode,
large prefill win). If it's modest, stop — the added AAR size + Gemma lock-in aren't worth it, and
llama.cpp CPU stays the whole story.

**Generic by design — no per-device NPU.** We deliberately target only the portable GPU backend
and one GPU-portable `.litertlm`. Per-SoC NPU delegates (the `…_sm8550` / `…_mt6989` model
variants, vendor `.so`s) can be faster still, but they're device-specific and explicitly **out of
scope** — a possible later, optional enhancement, never the gate. `DeviceProfile` carries no
SoC/vendor fields for this reason.

## Good news: no JNI needed

LiteRT-LM now exposes a **Kotlin Android API** (Google steers new projects here; the older
MediaPipe LLM Inference API is maintenance-only). So `LiteRtEngine` is implemented in **pure
Kotlin against an AAR**, not a C++/JNI shim like llama.cpp. The engine is configured through an
`EngineConfig` (model path + backend = CPU/GPU/NPU); NPU delegate libraries are located via
`context.applicationInfo.nativeLibraryDir`.

## Steps

### 1. Add the dependency
Add the LiteRT-LM Android (Kotlin) AAR to `runtime-service/build.gradle.kts` (the commented
`litertlm-android` line). Expect a meaningful APK-size bump from the LiteRT native libs — see
gating in step 6; keep it out of the base variant. For the GPU backend also declare the OpenCL
native libs in the manifest (`libOpenCL.so`, `libvndksupport.so` via `<uses-native-library>`).

### 2. Implement `LiteRtEngine` (Kotlin)
Fill in the methods currently stubbed in `LiteRtEngine.kt`, mapping the seam onto the LiteRT-LM
Kotlin API:

- `load(path)` → build the LiteRT `Engine` from `EngineConfig(modelPath = path, backend =
  Backend.GPU(), cacheDir = ctx.cacheDir.path)`; wrap engine/session in an
  `InferenceEngine.Session`. Return null on failure. (Generic GPU only — no `Backend.NPU(...)`.)
- `generate(session, sessionId, prompt, sink)` → run a streaming generation, forwarding each
  chunk to `sink.onChunk(...)` and honoring `sink.isCancelled()`; return the token count. Map
  `sessionId` onto LiteRT's conversation/session handle for multi-turn KV reuse (mirrors the
  llama.cpp path).
- `label(session)` → `"NPU · <device>"` / `"GPU · <device>"` from the chosen backend.
- `cancel` / `unload` → LiteRT's cancel + close.
- `attachDraft` → LiteRT's own multi-token/drafter support if exposed, else keep the no-op.

Nothing above the `InferenceEngine` seam changes — `EdgeLMService`, AIDL, scheduler, notifications
all stay as-is.

### 3. Make it selectable
In `LiteRtEngine.canRunOn(device)`, replace the hardcoded `false` with a **generic** check:
require `device.has64BitAbi`, then let `Engine.initialize()` with `Backend.GPU()` confirm the
GPU/OpenCL backend actually comes up; return true only if it does. No SoC/NPU-vendor branching.
If it doesn't come up, returning false keeps `EngineRegistry` on llama.cpp — the CPU floor is
never removed. Cache the result like the existing `<model>.backendpref` probe so it runs once.

### 4. Add a model to the catalog
Add one Gemma entry to `ModelCatalog` with a `litertUrl` pointing at a `.litertlm` artifact from
the LiteRT HuggingFace Community. Add a `litertSizeMb` field to `ModelSpec` (the known
per-artifact-size gap) so the download UI shows the right size — `DownloadWorker` already routes
by `artifactFor`, so once `litertUrl` is set and `canRunOn` is true, capable devices fetch and run
the `.litertlm` automatically. Mind Gemma's license.

### 5. Measure (the gate)
Install, run the Gemma model in Playground on any GPU-capable device, and read the `perf:` line
from `adb shell logcat -s edgelm-native EdgeLMService`. Record decode + prefill tok/s and compare
to the CPU baseline. This number is the go/no-go.

### 6. Gate the release (only if green)
The LiteRT AAR adds native-lib size to the APK. Ship it via a **dynamic feature module** or
on-demand download rather than the base APK, and gate on the GPU-backend probe — you've already
hit Play's APK-size and device-support warnings on the CPU build, so don't fold this into the
base variant.

## The whole "enable" surface, once B works

Turning LiteRT on for capable devices is deliberately tiny, because the plumbing already exists:

1. Implement `LiteRtEngine`'s methods (step 2).
2. Flip `LiteRtEngine.canRunOn` to the real probe (step 3).
3. Add a `litertUrl` (+ `litertSizeMb`) to one `ModelSpec` (step 4).

`EngineRegistry` routing, `DeviceProfile`, `DownloadWorker` artifact selection, and the
`EdgeLMService` per-load resolution all already flow through — no changes there.

## Risks / watch-items

- **APK size** — feature-module the AAR; the GPU backend probe decides at runtime, and llama.cpp
  CPU stays the permanent fallback wherever the GPU backend doesn't come up.
- **Model lock-in** — the fast path is Gemma-centric; the GGUF catalog remains the CPU tier.
- **Concurrent Binder clients** — validate LiteRT session/KV isolation under the shared-service
  many-clients model (EdgeLM's whole thesis) during the PoC.
- **16 KB page alignment** — same Android 15+/Play requirement you already handle for llama.cpp.

## Sources
- LiteRT-LM Android (Kotlin) getting started: https://developers.google.com/edge/litert-lm/android
- Run LLMs using LiteRT-LM on NPU: https://ai.google.dev/edge/litert/next/litert_lm_npu
- LLM Inference guide for Android (MediaPipe, now maintenance-only): https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android
