# EdgeLM Hub — Desktop Console

A native desktop app (Electron) for the EdgeLM on-device runtime. It's a GUI over the same
control surface the `edgelm` CLI drives — the OpenAI-compatible HTTP shim inside the
runtime-service process (arch doc Parts 5, 7, 10, 13).

Six views:

- **Models** — the Hub catalog: family, size, version, installed/active/pinned state. Pull
  (durable, WorkManager-backed download) with a **live progress bar** per model, activate,
  pin/unpin (rollback).
- **Monitor** — live health and warm (resident) models (auto-refresh every 4s), plus a
  throughput history: tok/s and TTFT are measured in the renderer during streaming (each SSE
  frame is one token), plotted as a dependency-free sparkline and kept across restarts.
- **Playground** — streaming chat, the tool-using agent loop (with the three firewall consent
  flags), vision captioning of a local image, and speech transcription of an audio file **or a
  live mic recording** (captured to WAV in-app).
- **Firewall & Permissions** — the data-flow firewall: remembered per-destination egress
  policy (allow / deny / tainted), a deterministic self-test, and per-app capability grants
  (grant / deny / revoke).
- **Tools** — the app-registered webhook tools the agent can call (register / list / remove),
  with a one-click shortcut to allow egress to a tool's host. This is the platform surface:
  apps bring their own tools, the runtime brokers the call under the firewall.
- **Knowledge** — on-device embeddings, the local vector index, and retrieval-augmented
  answers: browse collections, add & embed documents, semantic search, and ask grounded
  (RAG) questions — all on the phone, no cloud. Needs the `bge-small-en-v1.5` embed model.

## How it connects

The Hub shim binds `127.0.0.1:1408` **on the phone**, and only in **DEBUG** builds. A computer
reaches it over adb, exactly like the CLI:

```
adb forward tcp:1408 tcp:1408
```

The app performs all HTTP from Electron's main process (Node `http`), so there's no CORS or
mixed-content issue — the UI only talks to the main process over IPC.

You can trigger the forward from inside the app too (the **adb forward** button in the top bar),
as long as `adb` is on your PATH. Host/port are editable in the top bar if you use a different
forward.

## Run it

Prerequisites: Node 18+ and a DEBUG EdgeLM build installed & running on a connected device
(`adb devices` shows it).

```
cd hub-desktop
npm install
npm start
```

The window opens, runs `GET /health`, and — if connected — loads the catalog. If the pill says
"no runtime", click **adb forward** (or run the adb command above) and **Reconnect**.

## Package a distributable (optional)

```
npm run dist          # electron-builder → installer in dist/
```

Produces an NSIS installer on Windows, a DMG on macOS, an AppImage on Linux.

## Endpoints used

| View | Calls |
|------|-------|
| Models | `GET /v1/edge/models`, `POST /v1/edge/pull`, `POST /v1/edge/activate`, `POST /v1/edge/pin` |
| Monitor | `GET /health` |
| Playground | `POST /v1/chat/completions` (SSE), `POST /v1/edge/agent`, `POST /v1/edge/caption`, `POST /v1/edge/transcribe` |
| Firewall | `GET/POST /v1/edge/egress[/op]`, `POST /v1/edge/agent` (`firewall_test`), `GET/POST /v1/edge/permissions[/op]` |
| Tools | `GET /v1/edge/tools`, `POST /v1/edge/tools/register`, `POST /v1/edge/tools/unregister` |
| Knowledge | `POST /v1/edge/vectors/{collections,upsert,query}`, `POST /v1/edge/rag` |
| Models (progress) | `GET /v1/edge/downloads` (WorkManager pct/read/total) |

## Theme

A light/dark toggle sits in the top bar (the sun/moon button); the choice persists across
restarts. Both themes use the same EdgeLM brand — obsidian/signal-green/volt-cyan in dark,
inverted surfaces with contrast-adjusted accents in light. The `/v1/edge/downloads` and
`/v1/edge/activate` endpoints were added to the runtime for this console.

`/v1/edge/activate` and `/v1/edge/permissions` were added to the runtime for this console (see
`EdgeLMHttpServer.kt`).

## Note vs. Tauri

Built on Electron so it runs with just Node — no Rust toolchain. A Tauri port would swap
`main.js`/`preload.js` for a Rust core + the same `renderer/` (which is plain HTML/CSS/JS with
no framework), and ship a much smaller binary. The renderer was kept framework-free to make
that port straightforward.
