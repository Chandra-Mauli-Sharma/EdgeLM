# Phase 1 — Roadmap: from "it runs" to "it's a platform"

Phase 0 proved the one thing that had to be true: a shared runtime keeps **one
copy of the model in RAM** across apps (measured: adding a second app cost ~3.5 MB,
not ~480 MB). Phase 1 turns that proof into a real, usable runtime.

This doc sequences the Phase 1 work, what each step depends on, and how you know
it's done. Each step maps to a part of `docs/edgelm-architecture.html`.

---

## Dependency graph

```
                 ┌─────────────────────────┐
                 │ Phase 0 (DONE)          │
                 │ shared runtime + mmap   │
                 └───────────┬─────────────┘
                             │
          ┌──────────────────┼───────────────────┐
          ▼                  ▼                    ▼
  1. llama.cpp         2. OpenAI HTTP shim   (independent of each other)
     (real inference)     (compat surface)
          │                  │
          ▼                  │
  3. Vulkan GPU  ◄───────────┘  (needs real inference to measure)
          │
          ▼
  4. KV pooling + reuse   (needs real contexts to pool)
          │
          ▼
  5. Real scheduler       (needs multiple concurrent jobs to be worth it)
          │
          ▼
  6. QNN / NPU backend    (phase-split placement; needs scheduler + backends)
```

## The sequence

### 1. Real inference — `WEEK-1-LLAMACPP-INTEGRATION.md`  · arch Part 3
Swap the placeholder loop for a real `llama_decode` loop.
**Exit:** Demo A streams a coherent answer; you have a real tok/s number for a
small model (>30) and a 3B CPU baseline.
**Depends on:** Phase 0. **Blocks:** everything downstream that needs real tokens.

### 2. OpenAI HTTP shim — `PHASE1-OPENAI-HTTP-SHIM.md`  · arch Part 5
Localhost OpenAI-compatible server in the service process.
**Exit:** `curl`/OpenAI-SDK against `http://localhost:1408/v1` streams tokens.
**Depends on:** nothing (works over placeholder too) — can be done in parallel.

### 3. Vulkan GPU backend — `PHASE1-VULKAN-GPU.md`  · arch Part 3
Offload layers to the GPU; CPU fallback for bad drivers.
**Exit:** 3B tok/s beats the CPU baseline (often clears >30); logcat shows the GPU.
**Depends on:** step 1 (need real inference to offload and measure).

### 4. Context pooling + KV reuse — `PHASE1-KV-POOLING.md`  · arch Part 4
Turn the stateless per-call `llama_context` into warm, per-session state; reuse
the shared prompt prefix instead of re-prefilling it every turn.
**Exit:** second turn in a session skips prefill; measurable first-token latency
drop on multi-turn chats.
**Depends on:** step 1 (real contexts to pool).

### 5. Real scheduler — `PHASE1-SCHEDULER.md`  · arch Part 8
Replace the single-thread FIFO with priority classes + token-boundary preemption
+ aging + per-app quotas.
**Exit:** a foreground request interrupts a background one within a token or two;
no app can starve the others.
**Depends on:** step 1 (token-level control), benefits from step 4.

### 6. QNN / NPU backend + phase-split placement  · arch Parts 3, 11
Vendor Qualcomm QNN as a backend; run prefill on GPU, decode on NPU/CPU.
**Exit:** better tokens/joule and lower sustained thermals than GPU-only.
**Depends on:** steps 3 and 5 (multi-backend + scheduler to place phases).

## Suggested order for one developer

Do **2 (HTTP shim)** first for an immediate demoable win (it's independent),
then **1 (llama.cpp)** for real tokens, then **3 (Vulkan)** to hit the perf gate.
Those three close the Phase 0/Week-1 gate completely. **4 (KV)** and **5
(scheduler)** are the "make it feel like a platform" pair — do them once real
inference is stable. **6 (NPU)** is the deep-perf finale.

## Phase 1 "done" definition

A short demo + memo showing: real inference on GPU at usable tok/s; an OpenAI-SDK
app talking to the runtime unmodified; two apps sharing one warm model with a
foreground request preempting a background one; and the memory footprint still
flat as apps are added. That unlocks the developer-platform push (SDK 1.0, Hub,
Studio, CLI) — Phase 2 in the architecture roadmap.

## Not in Phase 1 (resist)
Marketplace, enterprise/fleet, cross-device mesh, vision/speech/embeddings beyond
a proof, the system-app UX. All designed in the arch doc; none needed to make the
runtime real. Breadth after depth.
