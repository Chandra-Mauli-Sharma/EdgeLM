# Phase 1 — The AI Scheduler (token-boundary preemption)

Right now the service serializes requests on a single-thread executor: strict
FIFO, no priorities. That's correct for proving memory sharing, but it means a
long background summarization blocks the keyboard's autocomplete. This step
replaces FIFO with the OS-style scheduler from Part 8 of the architecture doc.

**The core idea:** decode is iterative, so the natural scheduling quantum is *one
token*. Between tokens a job gives up its compute permit and re-requests it;
permits go to the highest-priority waiter. A foreground request therefore
preempts a background one within a token or two — and the background job isn't
killed, it just waits (its KV stays resident). This is preemption for free,
because the model's entire state between tokens *is* the KV cache.

Copy-ready file: `docs/phase1-scheduler/AIScheduler.kt`.

---

## What you get
- **Priority classes:** FOREGROUND > INTERACTIVE > BATCH > BACKGROUND.
- **Token-boundary preemption:** higher-priority jobs jump the queue each token.
- **Aging / anti-starvation:** a waiting job's effective priority rises over time,
  so background work eventually runs even under constant foreground load.
- **Per-app admission control:** `maxPerUid` caps concurrent requests per app so
  one app can't flood the runtime.
- **`concurrency` knob:** 1 = serialize (CPU-safe); raise it with GPU/batching.

## Wiring it in — three small edits

### 1. Extend the token sink with a scheduling gate

The native decode loop must ask permission before each token. Add one method to
`NativeBridge.TokenSink` (in `NativeBridge.kt`):

```kotlin
interface TokenSink {
    fun onChunk(text: String)
    fun isCancelled(): Boolean
    fun beforeToken(): Boolean   // NEW: blocks until scheduled; false => stop
}
```

### 2. Call the gate in the native loop

In `llama_runner.cpp`, add a `before_token` to the `Sink` and call it at the top
of the generation loop:

```cpp
// in struct Sink:
std::function<bool()> before_token;   // blocks until scheduled; false => stop

// at the top of the for-loop in generate():
if (sink.before_token && !sink.before_token()) break;
```

And resolve/forward it in `edgelm_jni.cpp` (next to the existing `onChunk` /
`isCancelled` wiring):

```cpp
jmethodID beforeTok = env->GetMethodID(sinkClass, "beforeToken", "()Z");
sink.before_token = [&]() -> bool {
    return env->CallBooleanMethod(jsink, beforeTok) == JNI_TRUE;
};
```

### 3. Drive it from `EdgeLMService`

Create one scheduler, wrap each request in a `Job`, and let the sink's
`beforeToken()` yield/acquire turns:

```kotlin
private val scheduler = AIScheduler(concurrency = 1)

// inside submit(...) on the worker, before generating:
val job = AIScheduler.Job(
    id = id,
    uid = android.os.Binder.getCallingUid(),          // real per-app identity
    priority = parsePriority(/* request.edge.priority */),
)
if (!scheduler.submit(job)) { callback.onError("busy: too many requests"); return@execute }

val sink = object : NativeBridge.TokenSink {
    override fun onChunk(text: String) { runCatching { callback.onTokens(text) } }
    override fun isCancelled(): Boolean = cancelFlag.get()
    override fun beforeToken(): Boolean {
        scheduler.yieldTurn(job)          // release + re-acquire in priority order
        return !cancelFlag.get()
    }
}
try {
    val n = NativeBridge.generate(modelHandle, prompt, sink)
    callback.onDone(n, /* elapsed */ 0)
} finally {
    scheduler.finish(job)                 // always release the permit
}
```

Map the request's `edge.priority` (Part 5) to a class; default INTERACTIVE, and
FOREGROUND for whatever app currently has UI focus. The HTTP shim path can pass
BATCH by default since it has no UI.

> Because `beforeToken()` blocks inside the native call, the worker executor can
> now run **multiple** generations concurrently (bump the executor pool from 1),
> and the scheduler — not the OS thread scheduler — decides who actually decodes
> each token. That's the whole point.

## How to see it work

1. Bump the service worker pool > 1 so two requests can be in flight.
2. Start a long BACKGROUND generation in Demo B.
3. Fire a FOREGROUND request from Demo A.
4. Watch logcat timestamps: Demo A's tokens should start within a token or two,
   and Demo B's should pause (not error) and resume after A finishes.

## Limits & what's next (honest scope)
- **CPU governance only.** This schedules *turns*, not clock/thermal. The battery
  and thermal governors (Part 8) sit above this and clamp `concurrency`/backend
  choice under heat — a later add.
- **No energy budget yet.** The per-app millijoule budget from the security model
  (Part 7) layers on top of `maxPerUid`.
- **Fairness is priority + aging**, not full weighted-fair-queuing; good enough for
  the spike, formalize later.
- Pairs naturally with **KV pooling** (`PHASE1-KV-POOLING.md`): preempted jobs keep
  warm KV, so resuming them is free.
