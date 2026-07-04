#include "llama_runner.h"
#include "llama.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "edgelm-native", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "edgelm-native", __VA_ARGS__)

// =============================================================================
// CONTINUOUS BATCHING ENGINE (arch doc Part 8/11).
//
// One engine thread owns the llama_context. Each in-flight request occupies a
// "lane" = a KV sequence. Every decode step builds ONE llama_batch containing a
// token from every active lane, so N requests generate concurrently on the shared
// model. generate() keeps its blocking signature: it parks a Slot into a lane and
// waits on a per-slot condition until that lane completes.
//
// Notes / known limits of this first cut (expect iteration):
//  - N_LANES concurrent requests max; further submits block for a free lane.
//  - Session lanes persist KV across turns; transient (sessionId "") lanes are
//    cleared on completion. No lane eviction yet (fine for a few sessions).
//  - The system prefix is prefilled once into TEMPLATE_SEQ and copied per lane.
//  - llama.cpp batch/seq APIs drift; if a symbol is missing, check
//    examples/parallel or server in your pinned checkout.
// =============================================================================

namespace edgelm {

static const int         N_LANES      = 4;
static const int         TEMPLATE_SEQ = N_LANES;   // holds the cached system prefix
static const int         MAX_NEW      = 512;
static const int         BATCH_CAP    = 512;       // max tokens per decode step
static const std::string SYSTEM_TEXT  =
    "<|im_start|>system\nYou are a concise, helpful assistant.<|im_end|>\n";

static std::string format_turn(const std::string& user) {
    return "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
}

struct Slot {
    int             lane       = -1;
    bool            prefilling = true;
    int             produced   = 0;
    int             logit_idx  = -1;
    std::vector<llama_token> prompt;   // user-turn tokens to prefill
    llama_token     next       = 0;
    llama_sampler*  smpl       = nullptr;
    const Sink*     sink       = nullptr;
    volatile bool   cancel     = false;
    std::string     session;           // "" = transient
    // completion handshake with the blocking generate() caller
    std::mutex              cmtx;
    std::condition_variable ccv;
    bool                    done   = false;
    int                     result = 0;
    std::chrono::steady_clock::time_point t0, t_first;
};

struct Lane {
    bool        occupied = false;   // has an active slot right now
    std::string session;            // non-empty => KV for this conversation is parked here
    int         n_past   = 0;       // KV length in this lane
};

struct Model {
    llama_model*   model     = nullptr;
    llama_context* ctx       = nullptr;
    int            n_ctx     = 2048;
    int            n_threads = 4;
    int            system_len = 0;

    std::thread              engine;
    std::mutex               mtx;
    std::condition_variable  cv;         // wakes engine when work arrives / lane frees
    bool                     running = false;
    std::vector<Slot*>       active;     // slots currently decoding
    Lane                     lanes[N_LANES];
    llama_batch              batch{};
};

// ---- system prefix (prefilled once into TEMPLATE_SEQ) -----------------------
static void ensure_system(Model* m) {
    if (m->system_len > 0) return;
    const llama_vocab* vocab = llama_model_get_vocab(m->model);
    const int n = -llama_tokenize(vocab, SYSTEM_TEXT.c_str(), (int)SYSTEM_TEXT.size(),
                                  nullptr, 0, true, true);
    std::vector<llama_token> toks(n);
    llama_tokenize(vocab, SYSTEM_TEXT.c_str(), (int)SYSTEM_TEXT.size(), toks.data(), n, true, true);
    // decode into TEMPLATE_SEQ
    llama_batch b = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; ++i) {
        b.token[i] = toks[i]; b.pos[i] = i;
        b.n_seq_id[i] = 1; b.seq_id[i][0] = TEMPLATE_SEQ; b.logits[i] = false;
    }
    b.n_tokens = n;
    if (llama_decode(m->ctx, b) == 0) { m->system_len = n; LOGI("system prefix cached: %d tok", n); }
    else LOGE("system prefill failed");
    llama_batch_free(b);
}

static llama_sampler* make_sampler() {
    llama_sampler* s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(s, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(s, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(s, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(s, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return s;
}

// Pick a lane for this request (caller holds m->mtx). Returns -1 if none free.
static int alloc_lane(Model* m, const std::string& session) {
    if (!session.empty())
        for (int i = 0; i < N_LANES; ++i)
            if (m->lanes[i].session == session && !m->lanes[i].occupied) return i;   // continue
    for (int i = 0; i < N_LANES; ++i)
        if (m->lanes[i].session.empty() && !m->lanes[i].occupied) return i;          // fresh
    return -1;
}

// ---- the engine loop --------------------------------------------------------
static void engine_loop(Model* m) {
    const llama_vocab* vocab = llama_model_get_vocab(m->model);
    for (;;) {
        std::vector<Slot*> act;
        {
            std::unique_lock<std::mutex> lk(m->mtx);
  