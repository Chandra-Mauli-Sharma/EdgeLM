# Phase 1 — Context pooling + KV reuse

Today `generate()` creates a fresh `llama_context` per call, tokenizes the whole
templated prompt, and prefills it from scratch. Two wastes:

1. **Multi-turn chat re-prefills the entire history** every turn.
2. **Every request re-prefills the same system prompt** — identical work, repeated.

Prefill is the compute-heavy, latency-dominating phase. Eliminating repeated
prefill is the single biggest first-token-latency win on-device. This is the
memory/cache work from Part 4 of the architecture doc, at spike scale.

Two levels, smallest-win-first.

---

## Level 1 — Warm per-session context (skip re-prefilling history)

Keep a `llama_context` alive per conversation and only decode the *new* turn; the
KV cache already holds the prior turns.

### API: add an optional session id
Extend the Binder contract and SDK so a caller can tag a conversation:

```aidl
// IEdgeLMService.aidl
long submit(String model, String sessionId, String prompt, ITokenCallback callback);
```
`sessionId` empty/null = stateless one-shot (today's behavior).

### Native: a session store
Replace "context per call" with "context per session", reused across turns:

```cpp
struct Session {
    llama_context* ctx = nullptr;
    int64_t last_used_ns = 0;
    int     n_past = 0;         // tokens already in the KV cache
};

// guarded by a mutex; keyed by sessionId
std::unordered_map<std::string, Session> g_sessions;

int generate_in_session(Model* m, const std::string& sid,
                        const std::string& user_turn, const Sink& sink) {
    Session& s = get_or_create(m, sid);              // creates ctx on first use
    // Only tokenize + decode the NEW turn (+ chat-turn wrappers), not the history.
    auto toks = tokenize(vocab, wrap_turn(user_turn), /*add_special=*/ s.n_past == 0);
    llama_batch batch = llama_batch_get_one(toks.data(), toks.size());
    // ... decode loop as before; positions continue from s.n_past ...
    s.n_past += toks.size() + produced;
    s.last_used_ns = now_ns();
    return produced;
}
```

### Evict idle sessions (this is background unloading, Part 4)
Warm contexts hold KV = memory. Cap the number of live sessions and evict the
least-recently-used under pressure:

```cpp
// before creating a new session, if over budget:
evict_lru_if(g_sessions, /*max_sessions=*/ 8);
// on real memory pressure (onTrimMemory in the Service), evict harder.
```

Wire `Service.onTrimMemory()` → a native `trim_sessions()` call so the runtime
shrinks gracefully instead of getting killed by the low-memory killer.

**Exit check:** the 2nd+ turn in a session shows a clear first-token-latency drop
versus a cold one-shot with the same total context.

---

## Level 2 — Prompt-prefix cache (skip re-prefilling shared prefixes)

Many requests share a prefix (the system prompt, a RAG document). Prefill it
**once**, snapshot the KV, and restore that snapshot for new sessions instead of
recomputing it.

```cpp
// key the cache by a hash of the exact prefix text (content-addressed)
std::string key = sha1(system_prompt);

if (auto hit = g_prefix_cache.find(key); hit != end) {
    // restore KV state into the fresh sequence — no prefill
    llama_state_seq_set_data(ctx, hit->blob.data(), hit->blob.size(), /*seq=*/0);
    s.n_past = hit->n_tokens;
} else {
    prefill(ctx, system_prompt);                       // pay it once
    size_t sz = llama_state_seq_get_size(ctx, 0);
    std::vector<uint8_t> blob(sz);
    llama_state_seq_get_data(ctx, blob.data(), sz, 0); // snapshot
    g_prefix_cache[key] = { std::move(blob), n_prefix_tokens };
}
```

- **Content-addressed** so identical prefixes collapse to one snapshot.
- **Isolation (Part 7):** only cache *public* prefixes (system prompts, app-declared
  shareable context) across apps; never snapshot user content across UIDs. Namespace
  private prefixes by UID.
- Snapshots are bytes on the heap — cap total size and LRU-evict like sessions.

**Exit check:** two fresh sessions with the same system prompt — the second skips
prefill of that prefix (watch first-token latency and a cache-hit log line).

---

## The endgame (where this is heading — Part 4)

Level 1 + 2 are the pragmatic 80%. The full design in the architecture doc is
**paged KV**: allocate KV in fixed-size blocks from a shared pool, map logical
positions → physical blocks, and share identical prefix blocks **copy-on-write**
across sessions/apps (refcount, split-on-divergence). That turns "snapshot &
restore bytes" into "point at the same physical blocks until they diverge" — zero
copy, maximal sharing. Adopt it once Level 1/2 prove the latency win and you need
the memory efficiency at many concurrent sessions.

## Interactions
- **Scheduler (Part 8):** a preempted job keeps its warm session KV, so resuming
  after preemption is free — pooling and scheduling reinforce each other.
- **Memory manager (Part 4):** session eviction *is* background unloading; hook
  both to `onTrimMemory` and the same pressure signal.
- **GPU (Vulkan):** KV lives in GPU-accessible memory when offloading; the same
  pooling logic applies, but snapshots move device memory — measure the copy cost
  before caching aggressively there.

## Caveats
- `llama_state_seq_*` APIs and KV-cache helper names drift across llama.cpp
  versions — check them against your pinned commit (same rule as Week 1).
- Restored state must match model + context params (n_ctx, rope, etc.); key the
  cache on those too, or you'll restore garbage.
