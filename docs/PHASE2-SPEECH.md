# Phase 2 — On-device Speech (audio understanding)

Speech-to-text and spoken-audio Q&A, entirely on the phone, over the **same llama.cpp mtmd
path** the vision feature uses. mtmd's bitmap helper auto-detects audio (wav/mp3/flac) by magic
bytes, so an audio-capable multimodal model (LLM + an audio-encoder mmproj) plugs into the
existing pipeline — no new native decode path.

## What ships

- **Model** — `ultravox-1b` in the catalog (`kind = "audio"`): Ultravox 0.5, a Llama-3.2-1B
  paired with an Ultravox audio encoder (mmproj). 808 MB model + 1.37 GB audio projector,
  MIT-licensed, from `ggml-org/ultravox-v0_5-llama-3_2-1b-GGUF`. Needs ~3 GB RAM.
- **Capability** — a first-class `AUDIO` capability in the broker (`ai.edgelm.AUDIO`,
  grant-on-first-use like CHAT/EMBED/VISION), declared in the manifest. `/v1/edge/transcribe`
  is now gated on AUDIO (it previously borrowed VISION).
- **Loader** — `ensureAudioModelLoaded()` loads the audio model + its mmproj into a **separate**
  resident handle (`audioHandle`), independent of the vision model, through the shared
  `NativeBridge.loadVisionModel` (mtmd handles both modalities).
- **Endpoint** — `POST /v1/edge/transcribe {audio: <base64 wav/mp3/flac>, prompt?}` →
  `{text}`. Default prompt transcribes verbatim; pass a prompt to *ask about* the audio.
- **Desktop console** — a **Speech** mode in the Playground: choose an audio file, optionally
  add a prompt, get the transcript/answer.
- **CLI** — `edgelm transcribe <file> ["prompt"]` (already present).

## Try it

```
edgelm pull ultravox-1b                       # 808 MB + 1.37 GB projector
edgelm transcribe sample.wav                  # verbatim transcript
edgelm transcribe sample.wav "What is the speaker asking for?"
```

Or in the desktop Hub: Playground → Speech → choose audio → Send.

## Build requirement

The mtmd runtime is shared with vision, so this needs a **`-DEDGELM_VISION=ON`** build (the
flag enables mtmd; it covers audio too). The shipping (flag-off) build is unaffected.

## Prompt template — uses the model's own template

`vision_generate` (the shared mtmd generate loop) builds the prompt with the **model's own
chat template** via `llama_model_chat_template()` + `llama_chat_apply_template()`, wrapping the
`mtmd_default_marker()` + text as the user message. So Qwen vision models get ChatML and
Ultravox (Llama-3.2) gets the llama3 header format — automatically, from each GGUF's
`tokenizer.chat_template` metadata. If a GGUF carries no built-in template, it falls back to the
previous hardcoded ChatML wrapper, so template-less models behave exactly as before. This
applies to **both** vision and audio (one shared path). ⚠️ UNBUILT — verify on device that both
the Qwen VLM and Ultravox still format correctly after this change.

## Files

- `ModelCatalog.kt` — `ultravox-1b` spec (`kind="audio"`) + `audioModel()`.
- `CapabilityBroker.kt` — `AUDIO` capability; `AndroidManifest.xml` + `strings.xml` — permission.
- `EdgeLMService.kt` — `ensureAudioModelLoaded()` + `edgeTranscribe` (AUDIO-gated, audio handle).
- `hub-desktop/renderer/*` — Playground Speech mode.
