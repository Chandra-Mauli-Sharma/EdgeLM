# EdgeLM — The Android AI Runtime

A shared, on-device AI runtime for Android: install once, and any app can run local
models through a secure, OpenAI-compatible API — `EdgeLM.chat(...)` — with no cloud by default.

> **This repo is the Phase 0 spike.** Its only job is to prove the load-bearing thesis:
> a shared, out-of-process runtime can serve multiple apps while keeping **one copy of the
> model weights in RAM**. See [`docs/PHASE-0-BUILD-PLAN.md`](docs/PHASE-0-BUILD-PLAN.md).
> Design rationale for the full system is in [`docs/edgelm-architecture.html`](docs/) /
> [`docs/EdgeLM-Architecture.pdf`](docs/EdgeLM-Architecture.pdf).

## Module map

```
EdgeLM/
├── contract/         AIDL Binder contract (IEdgeLMService, ITokenCallback)
├── runtime-service/  the shared runtime — bound Service in its own :core process
│   └── src/main/cpp/ JNI + native runner (mmaps the model; llama.cpp goes here in Phase 1)
├── sdk/              thin client — EdgeLM.chat() hides Binder/mmap/llama.cpp from apps
├── demo-app-a/       first consumer app
├── demo-app-b/       second consumer app (distinct UID → proves shared memory)
├── tools/            measure_memory.sh, push_model_and_install.sh
└── docs/             architecture doc, brand book, Phase 0 plan
```

## Architecture (Phase 0 slice)

```
demo-app-a ─┐   EdgeLM.chat()   ┌──────────────── ai.edgelm.runtime:core ───────────────┐
            ├──── (sdk) ───────►│ EdgeLMService → NativeBridge (JNI) → llama_runner (C++) │
demo-app-b ─┘   Binder / AIDL   │                         mmap(model.gguf, MAP_SHARED)    │
                                └─────────────────────────────────────────────────────────┘
                          one model, mmap'd once → pages shared across both apps
```

## Getting started

Prereqs: Android Studio (Koala+), NDK + CMake, an **arm64 device** (Phase 0 is CPU-only,
real devices — add `x86_64` in `runtime-service/build.gradle.kts` for an emulator).

```bash
# 1. generate the Gradle wrapper (not committed)
gradle wrapper --gradle-version 8.7

# 2. build everything
./gradlew assembleDebug

# 3. install + push a small GGUF model, then run the memory experiment
tools/push_model_and_install.sh ~/models/llama-3.2-3b-instruct-q4.gguf
#   → open Demo A, tap Generate
tools/measure_memory.sh          # snapshot: A only
#   → open Demo B, tap Generate
tools/measure_memory.sh          # snapshot: A + B — weights should NOT double
```

The scaffold streams **placeholder tokens** end-to-end (app → SDK → Binder → Service → JNI →
mmap'd native core) so the whole pipeline and the shared-memory behaviour are testable
immediately. Wiring the real llama.cpp decode loop is Week 1 and touches only
`runtime-service/src/main/cpp/llama_runner.cpp` — nothing above the JNI line changes.

## Phase 0 success gate

Two apps, different UIDs, streaming from one runtime, with `dumpsys meminfo` showing the
weights counted **once** (shared clean pages) at **> 30 tok/s** on the target flagship.
Green gate → start Phase 1 (Vulkan/NPU backends, OpenAI HTTP shim, real scheduler, SDK 1.0).

## What's intentionally NOT here yet
GPU/NPU backends · scheduler/priorities · permission model · EdgeLM Hub · OpenAI HTTP shim ·
KV paging · quantization switching · the system app UX. All designed in the architecture doc;
none built until the gate above is green. Resist breadth.
