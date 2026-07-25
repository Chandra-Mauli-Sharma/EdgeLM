#pragma once
// =============================================================================
// Paged-KV increment 2 — continuous-batching decode driver (arch doc Part 4 & 8).
//
// STATUS: UNBUILT. Written against the llama.cpp batch/memory API used in
// llama_runner.cpp (llama_batch_init, llama_decode, llama_get_logits_ith,
// llama_sampler_sample, llama_memory_seq_rm). Compile + iterate on device; confirm
// symbol signatures against the pinned submodule.
//
// Where increment 1 (session_registry.h) gave the pool + isolation, this gives the
// concurrent DECODE: many sequences prefill and generate together, one batched
// llama_decode per step. It runs alongside the confirmed single-context generate()
// (llama_runner.cpp), which stays the shipping path until this is proven on device.
//
// Concurrency model: ONE driver thread owns the context and calls step() in a loop.
// Apps submit() from any thread (thread-safe queue); tokens stream back through each
// request's Sink from the driver thread. This is the structure that makes the
// AIScheduler.Preemption seam real — a paused sequence is simply excluded from the
// next batch; its KV persists and it resumes when unpaused.
// =============================================================================
#include "llama_runner.h"     // edgelm::Sink
#include "session_registry.h"
#include "llama.h"

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace edgelm {

// A shared, multi-sequence decode engine over one llama_context.
class BatchedRuntime {
public:
    // Builds a context sized for [pool_size] concurrent sequences, each with up to
    // [n_ctx_per_seq] tokens (total n_ctx = pool_size * n_ctx_per_seq, n_seq_max =
    // pool_size). [threadpool] optional (share the pinned big-core pool). Returns
    // nullptr on failure.
    static BatchedRuntime* create(llama_model* model, int pool_size,
                                  int n_ctx_per_seq, ggml_threadpool_t threadpool);
    ~BatchedRuntime();

    // Submit a generation for (uid, sessionId). Acquires a sequence (LRU-evicting an
    // idle one if the pool is full), queues the prompt for prefill, and streams tokens
    // into [sink] from the driver thread. Returns false if no sequence is available.
    // Thread-safe.
    bool submit(uint32_t uid, const std::string& session_id,
                const std::string& prompt, const Sink& sink);

    // One scheduling step: assemble a batch (pending prefills + one decode token per
    // active, non-paused sequence), decode once, sample + stream each sequence's next
    // token, retiring finished ones. Returns the number of sequences still active
    // afterwards. Call from the single driver thread only.
    int step();

    // Convenience driver: step() until no sequences are active. (A real service loops
    // step() itself so it can interleave submit()s and honor pause/cancel.)
    void run_until_idle();

    // Cooperative preemption (Part 8): exclude/include a sequence in future batches.
    // Its KV persists while paused. Keyed by (uid, sessionId).
    void set_paused(uint32_t uid, const std::string& session_id, bool paused);

    // Cancel a specific in-flight sequence (frees its slot at the next step boundary).
    void cancel(uint32_t uid, const std::string& session_id);

    int active_count() const;

private:
    BatchedRuntime() = default;

    struct SeqState {
        llama_seq_id seq        = SessionRegistry::kNone;
        uint32_t     uid        = 0;
        std::string  session_id;
        int          pos        = 0;        // n_past for this sequence
        llama_sampler* smpl     = nullptr;
        Sink         sink;
        bool         active     = false;
        bool         paused     = false;
        bool         cancel     = false;
        bool         needs_prefill = true;
        std::vector<llama_token> prefill;   // formatted prompt tokens (system + turn)
        llama_token  next_token = 0;        // token to feed next step (post-prefill)
        // emit machinery (mirrors generate(): UTF-8 boundary + stop-marker holdback)
        std::string  decoded;
        size_t       emitted    = 0;
        int          produced   = 0;
    };

    // Append this seq's contribution to the shared batch; return its logits index, or -1.
    int stage(SeqState& s);
    // Sample + emit one token for a seq from batch logits index [lidx]; true = finished.
    bool consume(SeqState& s, int lidx);
    void retire(SeqState& s);
    llama_sampler* make_sampler() const;

    llama_model*     model_      = nullptr;
    llama_context*   ctx_        = nullptr;
    llama_memory_t   mem_        = nullptr;
    const llama_vocab* vocab_    = nullptr;
    SessionRegistry* registry_   = nullptr;
    int              pool_size_  = 0;
    int              n_ctx_      = 0;
    int              n_ctx_per_seq_ = 0;
    llama_batch      batch_{};
    llama_token      im_end_     = -1;
    // COW system-prefix sharing (increment 3): the system prompt is prefilled ONCE into a
    // reserved template sequence; each real sequence llama_memory_seq_cp's those KV cells
    // (shared, not re-prefilled). template_seq_ = pool_size_ (outside the registry's range).
    llama_seq_id     template_seq_ = SessionRegistry::kNone;
    int              system_len_   = 0;
    bool             cow_          = false;   // true once the template prefill succeeded

    std::vector<SeqState> slots_;    // indexed by llama_seq_id
    mutable std::mutex    mu_;       // guards submit() vs the driver's slot access
};

} // namespace edgelm
