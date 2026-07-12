#pragma once
#include <functional>
#include <string>

// Week 1: real llama.cpp-backed runner. Same interface as the Phase 0
// placeholder, so nothing above the JNI line (Kotlin service/SDK/apps) changes.

namespace edgelm {

// Opaque handle. Wraps a loaded llama_model* (weights are mmap'd once by
// llama.cpp, so the shared-runtime memory property proven in Phase 0 holds).
struct Model;

struct Sink {
    std::function<void(const std::string&)> emit_chunk;   // deliver text
    std::function<bool()>                   is_cancelled; // poll for cancel
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

// Short label for the backend chosen at the most recent load_model(), for UI display:
// "CPU", or "GPU · <device>" (e.g. "GPU · Mali-G615 MC6"). Empty before any load.
const char* engine_label();

} // namespace edgelm
