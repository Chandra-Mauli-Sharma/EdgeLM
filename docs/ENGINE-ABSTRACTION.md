# Engine abstraction — pluggable inference engines behind `IEdgeLMService`

_Design note. Goal: add a GPU/NPU engine (LiteRT-LM) alongside the current CPU engine
(llama.cpp) without disturbing EdgeLM's shared-runtime thesis or the app-facing contract._

## The thesis this protects

EdgeLM is really **two layers**:

1. **The shared runtime** — one model loaded once in the `:core` service process, served to
   many client apps over the `IEdgeLMService` Binder contract (`submit` / `cancel` /
   `prepareEngine` / `reloadModel` / `unloadModel` / `warmModels`). This is the differentiator:
   N apps, one resident model, one KV pool.
2. **The inference engine** — the thing that actually decodes tokens. Today that's llama.cpp
   reached through `NativeBridge` (`loadModel` → opaque `modelHandle: Long`, then `generate` /
   `cancel` / `unloadModel` / `engineLabel` / `attachDraft`).

The sharing lives entirely in layer 1. **The engine in layer 2 is replaceable**, and swapping it
changes nothing app-side — clients keep talking to the same AIDL. That's what makes adding
LiteRT-LM an additive move rather than a rewrite.

## Where the seam already is

`EdgeLMService` never assumes much about the engine — it holds a single opaque
`@Volatile modelHandle: Long` and funnels everything through `NativeBridge`. Every touch point is
one of six calls: `loadModel`, `generate`, `cancel`, `unloadModel`, `engineLabel`, `attachDraft`.
That is already an interface in all but name. The refactor is to make it one.

## Proposed `InferenceEngine` interface

```kotlin
/** One loaded model on one backend. Implementations own their native resources and
 *  session/KV state; the service treats a Session as an opaque token, exactly as it
 *  treats modelHandle today. */
interface InferenceEngine {
    val id: String                        // "llama.cpp" | "litert-lm"
    fun supports(spec: ModelSpec): Boolean // format/quant this engine can load
    fun canRunOn(dev: DeviceProfile): Boolean // NPU/GPU/ABI requirements met?

    fun load(path: String, spec: ModelSpec): Session?   // null = failed
    fun label(s: Session): String         // "CPU" | "GPU · <dev>" | "NPU · <dev>"
    fun attachDraft(s: Session, draftPath: String): Boolean  // no-op if unsupported

    fun generate(s: Session, sessionId: String, prompt: String, sink: TokenSink): Int
    fun cancel(s: Session)
    fun unload(s: Session)

    interface Session                     // opaque; wraps the native handle
    interface TokenSink { fun onChunk(text: String); fun isCancelled(): Boolean }
}
```

`EdgeLMService` changes only mechanically: replace the `modelHandle: Long` field with a
`Session?`, and route the six `NativeBridge.*` calls through an `engine: InferenceEngine`
chosen at load time. `AIScheduler`, `ModelStore`, notifications, idle-unload, the HTTP shim —
untouched.

### Two implementations

- **`LlamaCppEngine`** — wraps the *existing* `NativeBridge`/JNI verbatim. `Session` boxes the
  `Long` handle. GGUF models, CPU (+ the shelved Vulkan path). This is the universal fallback and
  ships first, behavior-identical to today.
- **`LiteRtEngine`** — pure Kotlin against the LiteRT-LM Android AAR (no JNI needed). `.litertlm`
  models on the portable **GPU (OpenCL)** backend — generic, with no per-SoC / NPU-vendor
  branching. `attachDraft` maps to LiteRT's own MTP/drafter support where available, else no-op.

## Device routing

Extend the calibration you already run in `prepareEngine()` (today CPU-vs-GPU) one level up to
**engine selection**, cache the winner next to the model (you already cache
`<model>.backendpref`), and surface it in the same engine-label UI.

| Device profile | Chosen engine | Rationale |
| --- | --- | --- |
| arm64 + LiteRT GPU backend initializes + model has `.litertlm` | **LiteRT-LM (GPU)** | portable OpenCL accel; the fast path |
| Everything else (long tail) | **llama.cpp (CPU)** | universal floor, any GGUF |

**Generic, not device-dependent:** no SoC/NPU-vendor detection — `DeviceProfile` carries only
`has64BitAbi`, and the LiteRT engine confirms its GPU backend at init. Selection =
`engines.filter { it.canRunOn(dev) && it.supports(spec) }`, cached. Same disabled-until-calibrated
Playground gating you already built.

## Catalog changes

`ModelSpec` gains a few fields so one logical model can carry per-engine artifacts:

```kotlin
data class ModelSpec(
    /* ...existing... */
    val format: String = "gguf",     // "gguf" (llama.cpp) | "litertlm" (LiteRT-LM)
    val litertUrl: String? = null,   // optional .litertlm artifact
    val litertSizeMb: Int? = null,   // its download size, if different from sizeMb
)
```

(As implemented — the engine is derived from `format`/`litertUrl` via each engine's `artifactFor`,
not a separate `EngineKind` field.)

Practical note: the LiteRT fast path is **Gemma-centric** (plus some Llama/Qwen/Phi) via the
LiteRT HuggingFace Community; you won't get the full bartowski GGUF freedom there. Expect the GPU
tier to be a small curated set (a Gemma model or two), with the GGUF catalog remaining the CPU
tier. Gemma carries its own license — check before shipping.

## Migration plan (low-risk, staged)

- **Phase A — refactor only (no behavior change).** Introduce `InferenceEngine`, wrap the current
  code as `LlamaCppEngine`, swap `EdgeLMService`'s `modelHandle` for a `Session`. Ship; verify the
  CPU release is byte-for-byte behaviorally identical. Nothing app-facing moves.
- **Phase B — LiteRT-LM proof-of-concept (throwaway-ok).** Stand up `LiteRtEngine` (Kotlin AAR)
  for **one** Gemma `.litertlm` model on the portable **GPU** backend, on any GPU-capable device.
  Goal is a single number: measured decode + prefill tok/s vs the ~8 tok/s CPU baseline.
- **Phase C — routing + fallback. ✅ DONE.** Engine selection via `EngineRegistry`, driven by a
  pre-download SoC allowlist (`DeviceProfile.likelyLiteRtGpuCapable`) plus a learned, cached GPU
  verdict (`EngineProfile`, written by the first real `LiteRtEngine.load`). Download routes to the
  chosen engine's artifact (`artifactFor`); the picker hides models a device can't run
  (`ModelCatalog.visibleModels`); the one-time probe surfaces the winner in the UI as an engine
  label ("llama.cpp · CPU" / "LiteRT-LM · GPU"), and a failed probe shows a "pick another" hint.
  llama.cpp is the guaranteed fallback for every GGUF model and wherever LiteRT can't run.
- **Phase D — catalog + release. ✅ code-complete (shipping live).** Ungated `.litertlm` model in
  the catalog (Gemma 4 E2B via `litert-community`, MTP-capable so GPU speculative decoding actually
  engages; ⚠️ weights under Gemma Terms of Use — confirm before shipping); LiteRT ships live via plain `implementation` (not a
  feature module — user's call, accepting the APK-size trade); ABI handled automatically by the
  App Bundle (LiteRT arm64 libs delivered only to arm64 devices). `versionCode` bumped. Remaining
  is release *mechanics*, not code — see docs/RELEASE-CHECKLIST.md: verify AAB size vs Play limits
  (feature module is the pre-built fallback if it trips), signed `bundleRelease`, staged rollout,
  and the deferred Adreno GPU measurement as the real go/no-go.

## PoC success metric (decide before committing to C/D)

Phase B is a **go/no-go gate**. Commit to the full integration only if, on a real target NPU
device, LiteRT-LM delivers a decisive win over CPU llama.cpp on the same-size model — order-of-
magnitude on prefill and at least ~2–3× on decode (published figures suggest far more). If the
real-device delta is modest, the added engine, APK size, and Gemma lock-in aren't worth it, and
the Vulkan-yourself path stays the better bet.

## Open risks / questions

- **APK size & vendor libs.** LiteRT + NPU delegates add binary weight; you've already fought Play
  size/device-drop warnings. May need a feature-module or on-demand delegate download.
- **NPU coverage is fragmented.** Works on specific SoCs only → llama.cpp CPU fallback is
  permanent, never removed.
- **Model conversion / licensing.** `.litertlm` conversion pipeline; Gemma license terms.
- **16 KB page alignment.** Same Android 15+/Play requirement you handle for the current `.so`.
- **Shared-process semantics.** Confirm LiteRT-LM's load/weight lifecycle behaves under the
  single-service-process, many-Binder-clients model (it should — sharing is at layer 1 — but
  validate KV/session isolation across concurrent clients in Phase B).
```
