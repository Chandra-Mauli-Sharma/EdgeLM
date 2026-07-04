# EdgeLM — Phase 0 Build Plan (The Memory-Sharing Spike)

**Goal of Phase 0:** prove the one claim the entire company rests on — that a shared,
out-of-process runtime can serve inference to multiple apps while keeping **one copy of the
model weights in RAM**, at usable speed. Nothing else.

If this works, EdgeLM is viable and no per-app library can match it. If it doesn't, we learn
that in a month instead of a year. Everything glamorous (scheduler, Hub, plugins, multi-backend,
the system app) is deliberately **out of scope** until the gate below is green.

---

## The success gate (this is the whole point)

> **Two separate apps stream tokens from the same model, and `dumpsys meminfo` shows the
> ~2 GB of weights counted once (shared), not twice — at > 30 tokens/sec on one target flagship.**

Concretely, all four must hold:

1. Two demo apps (`demo-app-a`, `demo-app-b`), different UIDs, both generate text.
2. Both are served by a **single** `runtime-service` process (verify: one PID in `ps`).
3. `dumpsys meminfo` shows the weight region as **shared/clean pages** — total device RAM
   attributable to the model stays ~flat when the second app joins (not +2 GB).
4. Decode throughput **> 30 tok/s** for a 3B Q4 model on the chosen flagship (streaming).

Write the number down for one device. Breadth comes later.

---

## Scope

### In scope (build these)
- A bound Android **Service** hosting **llama.cpp** via JNI, loading one GGUF model with **mmap**.
- A minimal **Binder/AIDL** contract: submit a prompt, stream tokens back, cancel.
- A thin **SDK** exposing `EdgeLM.chat()` so the demo apps don't know Binder exists.
- **Two demo apps** that bind to the service and stream.
- **Measurement**: tokens/sec (in-app) and PSS/shared memory (`dumpsys`, scripted).

### Explicitly OUT of scope for Phase 0 (fake or omit)
- GPU/NPU backends — **CPU-only** for the spike (add Vulkan in Phase 1).
- The scheduler, priorities, preemption, batching — serve requests **FIFO, one at a time**.
- Permission model / consent UI — hardcode "allow" (Binder UID check stubbed).
- EdgeLM Hub, marketplace, model resolver — **ship one model in assets / adb push**.
- OpenAI-compatible HTTP shim — Phase 1 (Binder is enough to prove the thesis).
- Quantization switching, KV paging, prompt cache, speculative decode — later.
- The system app UX, DevTools, tracing UI — later.

> Rule of thumb: if a feature isn't required to observe shared memory + >30 tok/s, don't build it yet.

---

## Architecture of the spike (minimal slice of the full design)

```
demo-app-a ─┐                        ┌───────────────────────────────┐
            ├─ EdgeLM.chat() ─(SDK)─►│  runtime-service  (own process)│
demo-app-b ─┘        Binder/AIDL     │   ┌─────────────────────────┐  │
                                     │   │ EdgeLMService (Kotlin)  │  │
                                     │   │   → NativeBridge (JNI)  │  │
                                     │   │       → llama.cpp (C++) │  │
                                     │   │           mmap(model.gguf)  │
                                     │   └─────────────────────────┘  │
                                     └───────────────────────────────┘
                         one model, mmap'd once → pages shared across both callers
```

Why this proves the thesis: because the service is **one process** and the model is **mmap'd**,
both apps' requests hit the same physical pages. The OS page cache does the sharing for free —
we just have to not defeat it (no `read()` into heap, no per-request reload).

---

## Week-by-week

**Week 1 — Native core, single process, no Android UI**
- Vendor `llama.cpp` as a git submodule; build it for `arm64-v8a` via CMake/NDK.
- JNI bridge: `loadModel(path)`, `generate(prompt, callback)`, `cancel()`.
- Prove it in a one-Activity harness: load a 3B Q4 GGUF (adb-pushed), stream tokens, print tok/s.
- **Exit check:** tokens stream on-device; record baseline tok/s.

**Week 2 — Make it a Service + Binder**
- Move the native core behind a bound `Service` in its own `:process`.
- Define AIDL (`IEdgeLMService`, `ITokenCallback`), implement the Stub, stream tokens over Binder.
- One demo app binds and generates.
- **Exit check:** app and service are different PIDs; generation works across the Binder boundary.

**Week 3 — Two apps + the memory measurement**
- Second demo app (different package/UID) binds to the same service.
- Script `dumpsys meminfo <service_pid>` before/after app B joins; inspect shared vs private dirty.
- Confirm weights are shared clean pages (mmap), not duplicated.
- **Exit check:** the four gate conditions above.

**Week 4 — Harden + write up**
- Handle concurrent requests (serialize in the service — a mutex is fine for now).
- Cancellation frees the decode loop.
- Service auto-restart + rebind on death.
- Write a one-page result memo: device, model, tok/s, memory-with-1-app vs 2-apps. Decide go/no-go.

---

## How to measure memory (the number that matters)

```bash
# find the service process
adb shell ps -A | grep edgelm

# baseline with ONE app attached
adb shell dumpsys meminfo <service_pid> > mem_1app.txt

# attach the second app, then re-measure
adb shell dumpsys meminfo <service_pid> > mem_2app.txt
```

Look at the model's mapped region: it should appear under **shared clean** (mmap'd file pages),
and **total device PSS for the model should not double** when app B attaches. `tools/measure_memory.sh`
automates the diff. The failure mode to watch for: the weights showing up as **private dirty** —
that means something copied them into the heap and the sharing is broken.

---

## Risks & how we retire them early

| Risk | Why it matters | Retire it by |
|---|---|---|
| mmap sharing doesn't hold across UIDs | Kills the core thesis | Test in Week 3 first, not last |
| CPU decode < 30 tok/s for 3B | Product feels slow | Fall back to 1B Q4 for the gate; note GPU is Phase 1 upside |
| Binder overhead per token | Streaming feels laggy | Batch tokens per callback (e.g. flush every N ms), not one IPC per token |
| llama.cpp NDK build friction | Blocks Week 1 | Timebox; use a prebuilt arm64 lib if the source build stalls |
| Concurrent requests corrupt state | Two apps at once | Serialize with a mutex in Phase 0; real scheduler is Phase 1 |

---

## Definition of done for Phase 0
A short memo with a single screenshot of `dumpsys` showing shared weights, the tok/s number, and
a go/no-go. That memo unlocks Phase 1 (GPU backend, OpenAI HTTP shim, real scheduler, SDK 1.0).

See `../README.md` for the scaffold that implements this slice.
