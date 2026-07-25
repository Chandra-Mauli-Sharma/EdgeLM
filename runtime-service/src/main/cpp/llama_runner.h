#pragma once
#include <functional>
#include <string>
#include <vector>

// Week 1: real llama.cpp-backed runner. Same interface as the Phase 0
// placeholder, so nothing above the JNI line (Kotlin service/SDK/apps) changes.

namespace edgelm {

// Opaque handle. Wraps a loaded llama_model* (weights are mmap'd once by
// llama.cpp, so the shared-runtime memory property proven in Phase 0 holds).
struct Model;

struct Sink {
    std::function<void(const std::string&)> emit_chunk;   // deliver text
    std::function<bool()>                   is_cancelled; // poll for cancel
    std::function<void(int)>                on_done;      // optional: called once with token count
                                                          // when this request finishes (batched path)
};

// Load (mmap) the GGUF at `path`. Returns nullptr on failure.
Model* load_model(const char* path);

// Tokenize `prompt`, run the decode loop, stream detokenized pieces into `sink`.
// `sessionId` "" = stateless (KV wiped each call); a stable id continues that
// conversation, reusing its warm KV so prior turns aren't re-prefilled.
// Returns the number of tokens generated. Blocking; call off the main thread.
int    generate(Model* m, const std::string& sessionId, const std::string& prompt, const Sink& sink);

void   request_cancel(Model* m);
void   unload_model(Model* m);

// --- embeddings (Phase 2) ----------------------------------------------------
// Load a model in EMBEDDING mode (mean-pooled sentence embeddings, non-causal for
// BERT-style encoders). Distinct from load_model — the context is created with
// embeddings enabled. Returns nullptr on failure. Free with unload_model.
Model* load_embedding_model(const char* path);

// Embedding dimension (n_embd) of a loaded embedding model, or 0.
int    embed_dim(Model* m);

// Compute the L2-normalized embedding of [text] into [out] (resized to n_embd).
// Returns the dimension, or 0 on failure. Blocking; call off the main thread.
int    embed(Model* m, const std::string& text, std::vector<float>& out);

// Attach a small, same-tokenizer draft model to enable speculative decoding on this target.
// Returns true if the draft loaded. Safe to skip — generate() falls back to single-model.
bool   attach_draft(Model* m, const char* draftPath);

// Short label for the backend chosen at the most recent load_model(), for UI display:
// "CPU", or "GPU · <device>" (e.g. "GPU · Mali-G615 MC6"). Empty before any load.
const char* engine_label();

#ifdef EDGELM_BATCHED
class BatchedRuntime;   // batched_runner.h

// Build a persistent BatchedRuntime that SHARES [m]'s already-loaded weights + pinned
// threadpool (no second copy in RAM). The caller owns it (delete to free). This is the
// service-integration factory; the driver loop + per-request wiring live in Kotlin
// (BatchedRuntimeSession). Only compiled when -DEDGELM_BATCHED=ON.
BatchedRuntime* create_batched(Model* m, int pool_size, int n_ctx_per_seq = 512);

// Continuous-batching SMOKE TEST entry: submits every prompt on its own sequence and
// drives one loop to completion, streaming each sequence's tokens through [on_chunk]
// tagged with its index. Returns total sequences run.
int run_batched_test(Model* m, const std::vector<std::string>& prompts,
                     const std::function<void(int seq, const std::string& text)>& on_chunk,
                     int n_ctx_per_seq = 512);
#endif

} // namespace edgelm
