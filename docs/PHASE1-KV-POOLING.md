# Phase 1 — Paged KV Cache + Continuous Batching (design)

Arch-doc **Part 4** (paged KV, COW prefix sharing) and the mechanism that unlocks the
scheduler's **token-boundary preemption** (Part 8).

> **Status: design only.** This is the single Phase-1 component that is native
> (C++/ggml) and cannot be compiled or verified in the build sandbox — it needs the
> on-device NDK toolchain against the pinned `llama.cpp` submodule. Everything below is
> specified against the *actual* current code (`llama_runner.{h,cpp}`, `edgelm_jni.cpp`,
> `NativeBridge.kt`, `LlamaCppEngine.kt`) so it's ready to implement on device.

## Where we are today

One `edgelm::Model` wraps a `llama_model` + a single `llama_context`. `generate(m,
sessionId, prompt, sink)` is **blocking and single-conversation**: a `sessionId`
continues that conversation's warm KV, `""` wipes it. Only one generation runs at a
time — which is also why the `AIScheduler` is non-preemptive.

## Key insight: don't reimplement PagedAttention — use llama.cpp's sequences

`llama.cpp` already provides the primitives; EdgeLM's job is to *drive* them as a
shared, multi-tenant pool rather than one-conversation-at-a-time:

- `llama_seq_id` — the KV cache is already partitioned by sequence id.
- `llama_batch` — decode multiple sequences' next tokens in one batched call
  (continuous batching).
- `llama_kv_self_seq_cp(ctx, src, dst, p0, p1)` — copy-on-write share a KV range from
  one sequence to another (prefix sharing).
- `llama_kv_self_seq_rm(ctx, seq, p0, p1)` — free a sequence's KV (eviction).

*(Exact symbol names track the pinned llama.cpp version — `llama_kv_self_*` vs the older
`llama_kv_cache_*`. Confirm against the submodule at build time.)*

## Design

### 1. One shared context, sized as a block pool
Create the `llama_context` with `n_seq_max = POOL_SEQS` and `n_ctx = BLOCKS *
BLOCK_TOKENS` (e.g. 16-token blocks). KV is then a shared pool many sessions draw from,
instead of each reserving worst-case contiguous context. Keep the existing Q8_0 KV +
flash-attn settings (see memory: KV traffic is the bandwidth cost).

### 2. Session → sequence registry (native)
A `SessionRegistry` maps `(callerUid, sessionId)` → `llama_seq_id`, with LRU eviction
(`llama_kv_self_seq_rm`) when the pool is full. This is Part 4's "background sessions
decay" at KV granularity, and it enforces **isolation**: every sequence is tagged with
its owner uid; user KV is never shared across uids.

### 3. COW prefix sharing (public prefixes only)
Hash the leading system-prompt prefix. If a resident sequence already prefilled an
identical **public** prefix, `llama_kv_self_seq_cp` its blocks into the new sequence
(refcount++), and diverge-on-write. This is "ten chats with the same system prompt
share those KV blocks." Per the Part 11 decision, COW is allowed for identical public
prefixes only — **never user content** — which the broker's identity tags gate.

### 4. Continuous-batching decode loop
Replace the single blocking decode with a driver that, each step, assembles a
`llama_batch` of one token per *active* sequence and calls `llama_decode` once, then
routes each sampled token back to its session's sink. Multiple apps/sessions now decode
concurrently on the shared context.

### 5. This unlocks scheduler preemption
Preemption becomes trivial and free: to pause a background sequence, **stop including
its token in the next batch** — its KV persists. To resume, include it again. The
`AIScheduler.Preemption` signal already added in Phase 1 maps directly onto
"include/exclude this seq in the next step," so a foreground request preempts a
background decode at a token boundary with zero wasted compute.

## Seam changes required

**Native (`llama_runner`)** — add a stepped, multi-sequence API alongside the existing
`generate` (keep `generate` as a thin single-seq wrapper for back-compat):
```cpp
seq_id  acquire_sequence(Model*, uint32_t uid, const std::string& sessionId,
                         const std::string& publicPrefix);   // COW-shares prefix
void    submit_prompt(Model*, seq_id, const std::string& prompt);
int     step(Model*);                       // one batched llama_decode over active seqs
void    release_sequence(Model*, seq_id);   // llama_kv_self_seq_rm
```
Sinks become per-sequence (`std::function<void(seq_id, piece)>`).

**JNI (`NativeBridge`)** — append (never reorder) `acquireSequence`, `submitPrompt`,
`step`, `releaseSequence`. The `.so` ABI is additive.

**Kotlin (`LlamaCppEngine` / `EdgeLMService`)** — the service moves from "call
`generate` under the scheduler lock" to "register the sequence, then let a single
decode-driver thread run `step()` across all active sequences." The scheduler stops
being the *mutual-exclusion* mechanism and becomes purely the *admission + preemption*
policy it was upgraded to be.

## Risk / effort

- Largest native change in Phase 1; needs the on-device NDK build + the pinned
  llama.cpp KV API confirmed.
- Continuous batching changes the decode hot path — re-run the Phase 0 tok/s gate after,
  since batching interacts with the CPU perf levers (threadpool, Q8_0 KV) in memory.
- LiteRT engine path: LiteRT-LM manages its own KV; this design is the llama.cpp engine.
  The seam stays engine-agnostic (per-session sink), LiteRT implements it separately.

## Suggested build order on device
1. Session registry + acquire/release (no batching yet) — proves the pool + isolation.
   **DELIVERED (unbuilt):** `session_registry.h` — see below.
2. Continuous-batching `step()` loop across ≥2 sequences — proves concurrent decode.
3. COW public-prefix sharing — proves the RAM win. **DELIVERED (unbuilt):** see below.
4. Wire `AIScheduler.Preemption` to batch inclusion — proves foreground preemption.
5. Re-measure the Phase 0 tok/s gate.

## Increment 1 — `session_registry.h` (delivered, unbuilt)

A header-only `edgelm::SessionRegistry` that maps `(uid, sessionId)` → a `llama_seq_id`
from a fixed pool, with LRU eviction (`llama_memory_seq_rm`), per-uid isolation (keys are
uid-namespaced, so one app can't address another's KV), and a `cow_prefix` helper over
`llama_memory_seq_cp` for public-prefix sharing. It does **not** drive decode — that's
increment 2 — so it's isolated from the confirmed-working `generate()` path and can be
unit-tested alone.

To activate it, `load_model` must build the context with `n_seq_max = pool_size` (today
it's the default 1). Confirm the `llama_memory_*` signatures against the pinned submodule
at build time.

> Note: the older comment in `llama_runner.cpp` points at
> `docs/wip/llama_runner_continuous_batching.cpp` as a parked prototype — that file is
> **not present in the repo** (never committed / on another branch). Increment 1 was
> written fresh against the current API rather than revived from it.

## Increment 2 — `batched_runner.{h,cpp}` (delivered, unbuilt)

`edgelm::BatchedRuntime`: a continuous-batching decode driver over one multi-sequence
context, built on the increment-1 registry.

- **`create(model, pool_size, n_ctx_per_seq, threadpool)`** — builds a context with
  `n_seq_max = pool_size`, `n_ctx = pool_size * n_ctx_per_seq`, Q8_0 KV + flash-attn
  (matching the single-context path), and a shared batch.
- **`submit(uid, sessionId, prompt, sink)`** — acquires a sequence (LRU-evicts an idle
  one if full), queues the prompt for prefill. Thread-safe.
- **`step()`** — assembles one batch (≤1 prefill + one decode token per active,
  non-paused seq), one `llama_decode`, then samples/emits/retires per sequence. The
  emission machinery (UTF-8 boundary + stop-marker holdback) is copied from
  `llama_runner.cpp` so streamed text is identical.
- **`set_paused()` / `cancel()`** — the live preemption hook: a paused seq is excluded
  from the next batch, its KV persists, it resumes on unpause. This is where
  `AIScheduler.Preemption.shouldYield()` connects.

Design choices to know: ≤1 prefill per step (bounds batch size); per-seq KV budget =
`n_ctx / pool_size`; each sequence has its own sampler (independent RNG); COW
system-prefix sharing is **increment 3** (today each seq prefills system+turn itself).

### Activation — the seam is WIRED (behind a build flag)

The JNI + Kotlin path is implemented; it's gated by CMake so the default build never
compiles the unproven engine. To turn it on and smoke-test it:

1. **Build with the flag.** In `runtime-service/build.gradle.kts`, add to the
   `externalNativeBuild { cmake { arguments += … } }` block:
   `arguments += "-DEDGELM_BATCHED=ON"`. Then `.\gradlew :runtime-service:installDebug`.
   (Without the flag, `NativeBridge.batchedRunTest` returns 0 — safe no-op.)
2. **Trigger it** (debug shim must be running):
   `.\tools\edgelm.ps1 batched-test`  →  `POST /v1/edge/batched-test` → `BatchedTest.run()`.
3. **Watch it interleave:** `adb logcat -s edgelm-batched-test`. Two prompts decode on two
   sequences in one loop — `seq=0` and `seq=1` token lines should ALTERNATE, not run one
   prompt to completion then the other. That's the proof of concurrent multi-sequence decode.

What's wired: `CMakeLists.txt` (opt-in `EDGELM_BATCHED` → compiles `batched_runner.cpp` +
defines the macro) · `llama_runner::run_batched_test` (shares the loaded weights +
threadpool) · `NativeBridge.batchedRunTest` + `BatchedSink` · `BatchedTest.kt` harness ·
`/v1/edge/batched-test` endpoint + `edgelm batched-test` CLI command.

## Increment 3 — COW system-prefix sharing (delivered, unbuilt)

The RAM win to complement increment 2's throughput win. In `batched_runner.cpp`:

- `create()` reserves one extra sequence (`template_seq_ = pool_size`, so `n_seq_max =
  pool+1`) and prefills the shared system prompt into it **once**.
- `submit()` now does `llama_memory_seq_cp(template_seq_ → seq, 0, system_len_)` to share
  those KV cells, sets the sequence's start position to `system_len_`, and prefills **only
  the per-turn tokens** (no BOS/system). Fallback to full self-prefill if the template
  prefill failed (`cow_ == false`).
- On `release()`, `llama_memory_seq_rm` drops only the sequence's own reference; the shared
  system cells persist as long as the template (or another sequence) references them —
  standard unified-cache COW.

Effect: N chats with the same system prompt hold **one** copy of its KV instead of N, and
each sequence skips re-prefilling ~30 system tokens (lower ttft too). Observable in logcat:
`COW system prefix cached: <n> tok on template seq <id>`, and each `submit` shows
`turn_prefill=<small> ... cow=1` (was ~29-31 tokens of system+turn; now just the turn).

### Full service integration (WIRED, behind the same flag)

The real integration — live requests decoding concurrently — is now built:

- `NativeBridge.batchedCreate/Submit/Step/Pause/Cancel/Destroy` — the persistent runtime
  API (stubs when the flag is off).
- `BatchedRuntimeSession.kt` — owns one runtime + a single driver thread looping
  `batchedStep()`. `generate()` queues a sequence and blocks until it finishes, so the
  blocking-streaming contract is preserved while N requests share the context.
  **Preemption:** while any foreground/interactive request is active, background/batch
  sequences are `set_paused` (dropped from the batch, KV intact) and resume after — the
  concrete `Preemption` realization.
- `EdgeLMService.dispatchInference` routes Binder + HTTP requests to the batched session
  when enabled, else the default `runInference` (shipping path, untouched).
- Toggle at runtime: `POST /v1/edge/batched-mode {"on":true}` → `.\tools\edgelm.ps1
  batched-mode` (`off` to disable). Loads the model off-thread; requests use the default
  path until it's ready.

**Try it:** build with `-DEDGELM_BATCHED=ON`, `edgelm batched-mode on`, then fire two
`edgelm run "…"` (or SDK) requests at once from different shells — logcat
`edgelm-batched-svc` shows them decoding together; a foreground request pauses a
background one. `edgelm batched-mode off` returns to the serialized path.

First integration caveats (refine after it runs): tokens stream over Binder while the
driver holds the batch mutex (buffer + emit outside the lock later); enabling batched
leaves the idle single-context model resident until it auto-unloads; concurrent requests
on the *same explicit sessionId* aren't serialized. None block correctness of the common
(distinct-session) case.

Verify against the pinned llama.cpp: `n_seq_max` on `llama_context_params`, the
`llama_batch` field layout (`seq_id[j][0]`), and `llama_sampler_sample(smpl, ctx, idx)`
sampling at a batch index. This is the first compile of the batched native code — expect
to fix a symbol or two.
