# Phase 1 — Hub v1 + `edgelm` CLI

Model provisioning as a package manager, plus a scriptable CLI (arch-doc **Part 10 & 13**).

## Hub v1 (`Hub.kt`)

Sits over `ModelCatalog` (registry) and `ModelStore` (on-disk cache) and adds:

### 1. Resolve like a package manager
```kotlin
Hub.resolve(ctx, "llama-3.2-1b-instruct")   // exact id
Hub.resolve(ctx, "llama-3.2-3b-instruct@1") // id at version
Hub.resolve(ctx, "family:llm.small")        // best device-fit in a family
```
Families are the explicit `ModelSpec.family`, or derived from min-RAM when unset
(`llm.tiny` < 1.5 GB · `llm.small` < 3 GB · `llm.medium` ≥ 3 GB), so resolution works
across the current catalog without tagging every entry. `bestInFamily` returns the
largest model that fits ~half the device RAM.

### 2. Verify before you trust (content-addressing)
`ModelSpec` now carries `sha256` / `litertSha256`. On download completion,
`DownloadWorker` calls `Hub.verify(tmp, spec, format)` **before installing**:

- `Ok` — hash matches → install.
- `Mismatch` — delete the file, fail the job with an integrity error. Untrusted weights
  never load. This is Part 10's "refuse to load a tampered artifact."
- `Unverified` — no hash on record (legacy entry) → install but logged. Nothing
  regresses; fill in hashes as models are (re)published through Hub.

Hashing is streaming (1 MB chunks) so multi-GB artifacts verify in constant memory.

**Next step:** cryptographic *signing* — a Hub-signed manifest verified against a
pinned public key — layers on top of this content-addressing (not in this cut).

### 4. Delta updates (Phase 2)
A model update can ship as a small binary **delta** against the resident version instead
of a full re-download (Part 10 — "a fine-tune bump downloads MBs, not GBs").

- `ModelSpec` gains `deltaUrl` / `deltaFromVersion` / `deltaSha256`.
- `Hub.deltaAvailable(ctx, spec)` = the resident file is exactly `deltaFromVersion` and a
  delta is published. `DownloadWorker` then downloads the delta and calls
  `BinaryPatch.apply(old, delta, new)` — a streaming, constant-memory reconstruction —
  verifies the result against `deltaSha256`, and swaps it in with a backup fallback. Any
  failure falls through to a normal full download, so it's always safe.
- Format `EDLT1` (copy-from-old / add-new ops). The on-device applier is `BinaryPatch.kt`
  (unit-tested in `BinaryPatchTest`); the server-side generator is `tools/gen_delta.py`.

```
python tools/gen_delta.py old.gguf new.gguf out.delta   # prints size + deltaSha256
```

### 3. Pin & rollback
`Hub.recordInstalledVersion` stamps the installed version on a verified install.
`Hub.pin/unpin/pinnedVersion` let an app hold a known-good version;
`Hub.updateBlockedByPin` tells the UI when a catalog update would break a pin.

## `edgelm` CLI (`tools/edgelm`)

A dependency-light bash client over the runtime's loopback OpenAI shim
(127.0.0.1:1408, DEBUG builds) via `adb forward` — CI-friendly, no app UI.

```
edgelm forward             # adb port-forward tcp:1408 (once per session)
edgelm health              # liveness + warm set
edgelm models              # warm models (/v1/models)
edgelm ls                  # Hub catalog: install/active/version/family/pin state
edgelm pull <id|family:f>  # resolve + enqueue a durable, verified download
edgelm pin <id> [t|f]      # pin/unpin a model to its installed version
edgelm run "<prompt>"      # stream a completion
edgelm bench [n] [prompt]  # time n runs: ttft, end-to-end + decode tok/s
```
Both `tools/edgelm` (bash) and `tools/edgelm.ps1` (PowerShell) implement the full set.
Env: `EDGELM_HOST`, `EDGELM_PORT`, `EDGELM_MODEL`. Needs `curl` + `python3` (bash) / none
extra (ps1), and `adb` for `forward`.

### Hub control endpoints (added to the shim)
The `ls`/`pull`/`pin` commands drive new loopback endpoints on `EdgeLMHttpServer`
(debug builds), backed by `Hub` + WorkManager in the `:core` process:

| Endpoint | Does |
|----------|------|
| `GET /v1/edge/models` | catalog + installed/active/version/family/pinned |
| `POST /v1/edge/pull {"model":"<id\|family:f>"}` | `Hub.resolve` → enqueue `DownloadWorker` (verified on completion) |
| `POST /v1/edge/pin {"model":"<id>","pinned":bool}` | `Hub.pin`/`unpin` |

`bench` now reports **ttft** and **decode-only tok/s** separately (from
`edge.ttft_ms` + `edge.elapsed_ms`), so scheduler/broker/prefill overhead is isolated
from steady-state decode — the number to watch against the 16.9 tok/s baseline.

`profile`/`trace` remain out of scope (the DevTools layer, Part 13, later). The edge
endpoints are unauthenticated loopback + debug-only, consistent with the shim.

## Files
- `Hub.kt` — resolve / verify / pin-rollback.
- `ModelCatalog.kt` — `sha256`, `litertSha256`, `version`, `family` fields (defaulted).
- `DownloadWorker.kt` — integrity gate before install; records installed version.
- `tools/edgelm` — the CLI.

## Needs on-device verification
- Corrupt-file path: point a catalog `sha256` at a wrong value and confirm the download
  is rejected and deleted (not installed).
- Resolver: `Hub.resolve(ctx, "family:llm.small")` returns the expected model per device RAM.
- CLI: `edgelm forward && edgelm health && edgelm run "hi"` against a debug build.
