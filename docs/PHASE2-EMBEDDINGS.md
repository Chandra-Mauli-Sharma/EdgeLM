# Phase 2 — On-device Embeddings

First Phase-2 capability (arch-doc Part 12, "Near"). Adds OpenAI-compatible
`/v1/embeddings` backed by a small on-device encoder, gated by the `EMBED` capability.
Also stands up **multi-model residency** (an embedding model resident alongside the chat
model) — the foundation vision/speech encoders will reuse.

## Pieces

- **Native** (`llama_runner.cpp`): `load_embedding_model` (context with `embeddings=true`,
  `pooling_type=MEAN`, CPU), `embed(text) -> float[]` (mean-pooled, **L2-normalized** so
  cosine == dot product), `embed_dim`. Separate from the chat `generate()` path.
- **JNI / `NativeBridge`**: `loadEmbeddingModel`, `embed` (returns `FloatArray?`), `embedDim`.
- **Model** (`ModelCatalog`): new `kind` field (`"chat"` | `"embed"`). Added
  **BGE-small-en-v1.5** (33M, 384-dim, MIT) as the default embed model. Hidden from the
  chat picker; `DownloadWorker` does NOT make an embed model the active chat model.
- **Service**: `embedHandle` — a second resident model, lazily loaded on first request
  (single-threaded, guarded by `embedLock`). `edgeEmbeddings` gates `EMBED`, embeds each
  input, returns the OpenAI response shape.
- **HTTP**: `POST /v1/embeddings` (`input` = string or array), OpenAI-compatible.
- **CLI**: `edgelm embed "text"` (both bash + ps1) → prints model, dim, first values.

## Try it

```
.\tools\edgelm.ps1 pull bge-small-en-v1.5     # ~34 MB, one-time
.\tools\edgelm.ps1 embed "the cat sat on the mat"
# -> model bge-small-en-v1.5 | dim 384 | first 5: [ ... ]
```

Two inputs return two vectors; cosine similarity (== dot product, since normalized) of
related sentences should be high. This is the primitive for **on-device RAG** — the next
increment is a vector index (a system plugin) so apps can store + search embeddings
locally.

## Notes / to verify on device

- **URL check**: confirm the BGE GGUF URL resolves without a HF token before shipping
  (same policy as the chat catalog).
- **Pooling/prefix**: mean pooling, no instruction prefix — fine for symmetric similarity;
  add `"Represent this sentence:"` for asymmetric retrieval if quality needs it.
- **Residency**: chat + embed models are both mmap'd; the embed model is tiny (~34 MB) so
  co-residency is cheap. It loads on first `/v1/embeddings` and frees on service destroy.
- First compile of the native embedding path — verify `llama_context_params.embeddings` /
  `pooling_type` and `llama_get_embeddings_seq` against the pinned llama.cpp.

## Vector index + RAG (`VectorStore`)

The embeddings primitive plus a local index = on-device semantic search / RAG (Part 12).

- **`VectorStore.kt`** (pure JVM): per-**namespace** collections (app-isolated), on-disk
  JSONL, brute-force **cosine == dot** search (embeddings are normalized). Lazily cached,
  rewritten on mutation. Fine for the thousands-of-docs scale a phone app keeps; an ANN
  index is a later optimization.
- **Service** `edgeVectors(op, body)`: `EMBED`-gated; embeds text via the resident embed
  model and delegates to the store. Ops: `upsert` (embed + store docs), `query` (embed the
  query → top-K), `delete`, `collections`.
- **HTTP**: `POST /v1/edge/vectors/{upsert,query,delete}`, `GET .../collections`.
- **CLI**: `edgelm vectors add <col> "text"`, `... search <col> "query"`, `... ls`.

```
.\tools\edgelm.ps1 vectors add notes "EdgeLM runs AI models locally on the phone"
.\tools\edgelm.ps1 vectors add notes "The Eiffel Tower is in Paris"
.\tools\edgelm.ps1 vectors search notes "private on-device inference"
# -> 0.7xx  EdgeLM runs AI models locally on the phone   (higher than the Paris line)
```

RAG loop for an app: `vectors search` to retrieve relevant docs, stuff them into the chat
prompt, `run`. All on-device — the retrieval corpus never leaves the phone, which is the
privacy story the whole runtime is built around.

## Next in Phase 2

MCP tool-calling (Responses API + plugin broker) · vision/speech encoders (reuse
multi-model residency) · QNN NPU backend · delta updates · an ANN index if corpora grow.
