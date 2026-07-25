# Phase 1 — Status

Sequenced against arch-doc Part 16. "Open the platform": scheduler, permission model,
KV paging, Hub v1, CLI. HTTP shim + SDK + system app already shipped in Phase 0.

| Component | State | Verifiable in sandbox? |
|-----------|-------|------------------------|
| Permission broker (Part 7) | **Implemented** (Kotlin/AIDL) | Static-reviewed; needs on-device deny-path test |
| Weighted-fair scheduler + governor (Part 8) | **Implemented** (Kotlin) | Static-reviewed; needs on-device fairness/thermal test |
| Hub v1 + `edgelm` CLI (Part 10, 13) | **Implemented** (Kotlin + bash) | Static-reviewed; needs on-device integrity + CLI test |
| Paged-KV + continuous batching (Part 4/8) | **Design only** | No — native NDK, device-only (see PHASE1-KV-POOLING.md) |

## ABI safety

- `IEdgeLMService.aidl`: only **appended** methods (`hasCapability`,
  `capabilityNeedsConsent`) — existing method order untouched → binder ABI stable.
- `NativeBridge` / the `.so`: **unchanged** in this cut (paged-KV would append JNI
  methods only).
- `ModelSpec`: new fields are all **defaulted** → existing catalog constructors compile.
- `AIScheduler.withEngine(priority, block)`: kept as a back-compat overload; the three
  internal callers (load/ensure/unload) are unchanged.

## No-regression notes

- Cross-app sharing (Phase 0, validated): demo apps rely on SDK manifest merge, which
  now also contributes `ai.edgelm.CHAT`; grant-on-first-use means they run with no new
  prompt. The single-inference hot path is unchanged.
- The permission gate runs on the Binder thread (correct UID); denials return via
  `onError`, never crash the shared service.
- Governor only clamps **background** priority; foreground/interactive are never blocked.

## Device verification checklist (build required)

1. **Broker deny path** — an app that does *not* declare `ai.edgelm.CHAT` is rejected
   with a clear `onError`; one that does declare it runs (auto-granted).
2. **Consent** — `EdgeLM.permissions().request(ctx, CAP_BACKGROUND_INFERENCE)` shows the
   consent screen; a BATCH/BACKGROUND submit before consent is denied, after is allowed.
3. **Fairness** — two apps at INTERACTIVE interleave rather than one draining first.
4. **Governor** — force thermal (`adb shell cmd thermalservice override-status 3`) and
   confirm background/HTTP requests defer while a foreground request still runs.
5. **Hub integrity** — set a wrong `sha256` on a catalog entry; the download is rejected
   and deleted, not installed. Correct hash installs and records the version.
6. **CLI** — `edgelm forward && edgelm health && edgelm run "hi" && edgelm bench 3`.
7. **Re-run the Phase 0 tok/s gate** to confirm no scheduler/broker overhead regression.

## Then: paged-KV on device

Build order in PHASE1-KV-POOLING.md. It's what turns "one generation at a time" into
concurrent multi-session decode and makes the scheduler's preemption seam live.
