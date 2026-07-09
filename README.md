# EdgeLM — The Android AI Runtime

A shared, on-device AI runtime for Android: install once, and any app can run local
language models through a small, OpenAI-style API — `EdgeLM.chat(...)` — privately,
offline, with **one copy of the model weights in memory** shared across every app.

Think of it as "Play Services for on-device AI": apps don't ship or manage model
weights themselves — they call the shared runtime, which loads a model once and
serves everyone.

**Status:** working end-to-end and packaged as a real, installable app (**EdgeLM
Runtime**, `ai.edgelm.runtime`), release-configured for Google Play. Current version
`0.1.0`.

## What's built

- **Real on-device inference** via vendored **llama.cpp** (JNI), ~23 tok/s decode on a
  mid/flagship device, with warm-context reuse, per-session KV reuse (multi-turn), and
  a prompt-prefix cache.
- **Shared runtime service** in its own `:core` process — model mmap'd once, shared
  across apps; a second app adds negligible memory.
- **Two front doors:** a Binder/AIDL API for the native SDK, and an OpenAI-compatible
  **HTTP shim** on `127.0.0.1:1408` (debug builds only; off in release).
- **Priority scheduler** — non-preemptive admission with aging (foreground →
  background).
- **Security gate** — the service is exported but gated by
  `ai.edgelm.permission.USE_RUNTIME`; the SDK declares it so apps inherit it via
  manifest merge.
- **The app:** first-run onboarding, a **Simple/Advanced** model picker (plain-language
  by default, full detail on demand), an in-app **model catalog** with on-device
  downloads (Qwen2.5 0.5B/1.5B, Llama 3.2 1B/3B, Phi-3.5 mini), instant model
  switching, a **background download service** that survives leaving the app, an
  ongoing runtime **notification** with Free-memory / Stop actions and idle
  auto-unload, a built-in **Playground** to test the runtime, and a branded splash.
- **Release ready:** R8 + resource shrink with keep-rules for JNI/AIDL, signing from
  `keystore.properties`, and a full Play listing kit in [`docs/play/`](docs/play/).

## Module map

```
EdgeLM/
├── contract/         AIDL Binder contract (IEdgeLMService, ITokenCallback)
├── runtime-service/  the EdgeLM Runtime app + shared runtime (:core process)
│   ├── src/main/cpp/         JNI + llama_runner (llama.cpp vendored as a submodule)
│   └── src/main/java/ai/edgelm/
│       ├── service/          EdgeLMService, AIScheduler, HTTP shim, NativeBridge
│       └── runtime/          RuntimeActivity (picker), Onboarding, Playground,
│                             ModelCatalog/Store, ModelDownloadService
├── sdk/              thin client — EdgeLM.chat() hides Binder/JNI from apps
├── demo-app-a / -b/  consumer apps (distinct UIDs → prove shared memory)
├── docs/play/        Play release kit (privacy, store copy, data safety, QA, assets)
└── docs/             architecture doc, brand book, phase notes (historical)
```

## Architecture

```
any app ──┐   EdgeLM.chat()   ┌──────────── ai.edgelm.runtime:core ─────────────┐
          ├──── (sdk) ───────►│ EdgeLMService → scheduler → NativeBridge (JNI)   │
curl/     │  Binder / AIDL    │                → llama_runner (C++) → llama.cpp   │
OpenAI ───┘  127.0.0.1:1408   │        mmap(model.gguf) — one copy, shared        │
             (debug only)     └──────────────────────────────────────────────────┘
```

## Build 