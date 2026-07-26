# Phase 2 — Vision / Multimodal (design)

Image input for the runtime (arch-doc Part 12). Reuses the **multi-model residency** the
embeddings work established. This is the largest native increment in Phase 2, so it's
specified against the actual code and llama.cpp's `mtmd` API rather than written blind.

> **Status: BUILT, unbuilt/first-compile, behind `-DEDGELM_VISION=ON`.** The full path is
> wired: `vision_runner.{h,cpp}` (mtmd), JNI, `NativeBridge`, service `/v1/edge/caption`,
> a SmolVLM catalog entry (LLM + mmproj), and `edgelm caption`. The uncertain part is the
> **CMake mtmd build integration** (`add_subdirectory(tools/mtmd)` + link `mtmd`) — verify
> on device; `tools/mtmd` is normally built only under `LLAMA_BUILD_TOOLS`.

## Activation (device)

1. Build with the flag: add `arguments += "-DEDGELM_VISION=ON"` to the runtime's
   `externalNativeBuild { cmake { ... } }`, then `installDebug`. (Off → the vision JNI
   stubs return 0; `/v1/edge/caption` reports "unavailable".)
2. `edgelm pull smolvlm-500m` (fetches the LLM **and** its mmproj projector).
3. `edgelm caption path/to/photo.jpg "What is in this image?"`.

Watch logcat `edgelm-vision`: `vision model up: vision=1 ...` then `produced N tokens`.
Expect first-compile fixes around the `mtmd` build + API (it's marked experimental).

## Speech (reuses this path)

`mtmd` auto-detects audio vs image from the bytes, so `/v1/edge/transcribe` (`edgelm
transcribe <audio>`) reuses the exact same native path — it just needs an **audio-capable**
multimodal model loaded (SmolVLM is vision-only; swap in an audio-LLM). No new native code;
the endpoint + service + CLI are wired and dormant until such a model is present.

## Approach — llama.cpp `mtmd`

A vision model is two GGUFs: the **LLM** (as today) plus an **mmproj** (the CLIP/SigLIP
vision encoder + projector). Good on-device candidates: SmolVLM-2, MobileVLM,
Gemma-4-E2B-vision — small enough for a phone. llama.cpp ships the `mtmd` (multimodal)
API for exactly this:

- `mtmd_init_from_file(mmproj_path, llama_model, params)` → `mtmd_context`.
- `mtmd_bitmap_init(w, h, rgb_bytes)` → a decoded image.
- `mtmd_tokenize(ctx, chunks, text_with_<image>_marker, bitmaps)` → interleaved
  text+image chunks.
- `mtmd_encode` / `mtmd_get_output_embd` → image embeddings placed into the KV at the
  `<image>` positions; then decode continues as normal text generation.

So the pipeline is: decode image → encode to embeddings → splice into the prompt at the
image marker → generate. The text decode path (sampler, streaming, stops) is unchanged.

## Native additions (`llama_runner`)

```cpp
// Load an LLM + its mmproj projector as a vision-capable model.
Model* load_vision_model(const char* model_path, const char* mmproj_path);
// Generate from a prompt that references one image (RGB bytes), streaming text out.
int    generate_with_image(Model* m, const std::string& prompt,
                           const uint8_t* rgb, int w, int h, const Sink& sink);
```

`Model` gains an `mtmd_context*`. Image bytes arrive as decoded RGB from Kotlin (Android
`Bitmap` → `IntArray`/RGB), so no image codec in native. JNI appends `loadVisionModel` +
`generateWithImage(handle, prompt, byte[] rgb, w, h, sink)`.

## Service / HTTP integration

OpenAI already models this: a `messages[].content` **array** with
`{"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}` parts. So:

- `flattenMessages` / a new `extractImages` pulls image parts out of the content array;
  the service base64-decodes them, decodes to RGB (Android `BitmapFactory`), and calls the
  vision path with a `<image>` marker in the prompt.
- Routing: `kind == "vision"` models load via `load_vision_model` (LLM + mmproj downloaded
  together — `mmprojUrl`). A vision model resident alongside the chat model reuses the
  multi-model residency from embeddings.
- Capability: gate with `VISION` (already defined in the broker).

## Catalog

A `kind="vision"` entry carries both the LLM `url` and the `mmprojUrl` (+ sizes).
`DownloadWorker` fetches both artifacts. Hidden from the plain chat picker (like `embed`).

## Build order (device)

1. `load_vision_model` + `mtmd` init (LLM + mmproj load, log the projector dims).
2. `generate_with_image` on a fixed test image → caption. Prove encode→decode.
3. JNI + a `VisionEngine` (or a mode on `LlamaCppEngine`); `/v1/chat/completions` image
   parts → RGB → native.
4. Add a small vision model to the catalog; gate on `VISION`; wire the CLI
   (`edgelm caption <image>`).
5. Verify `mtmd` symbol names against the pinned llama.cpp (the API is newer/moving).

## Risks

- Biggest native surface in Phase 2; `mtmd` API is comparatively new — confirm signatures.
- Image preprocessing (resize/normalize) is largely handled by `mtmd`, but the RGB layout
  + dimensions must match what the projector expects.
- Memory: LLM + mmproj + image embeddings; keep to small VLMs on-device.
- The LiteRT engine path is separate (its own multimodal story) — this is the llama.cpp path.
