#include "llama_runner.h"
#include "llama.h"

#include <android/log.h>
#include <thread>
#include <vector>
#include <mutex>
#include <chrono>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "edgelm-native", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "edgelm-native", __VA_ARGS__)

// -----------------------------------------------------------------------------
// Single-context runtime: one warm llama_context, requests serialized by the
// caller (AIScheduler). Includes session KV reuse (multi-turn) and a prompt-prefix
// cache for the fixed system prompt. This is the CONFIRMED-WORKING engine
// (~23 tok/s, sub-second prefill, multi-turn recall).
//
// The continuous-batching (concurrent multi-sequence) engine is preserved in
// docs/wip/llama_runner_continuous_batching.cpp — resume it with a dev setup
// where it can be iterated/debugged.
// -----------------------------------------------------------------------------

namespace edgelm {

struct Model {
    llama_model*   model     = nullptr;
    llama_context* ctx       = nullptr;   // created once, kept WARM, reused per call
    int            n_ctx     = 2048;
    int            n_threads = 4;
    volatile bool  cancel    = false;
    std::string    active_session;        // conversation whose KV is currently resident
    int            session_past = 0;      // KV length for that conversation
    int            system_len   = 0;      // cached system-prefix length
    bool           system_ready = false;
};

static std::once_flag g_backend_once;
static void ensure_backend() {
    std::call_once(g_backend_once, []{ llama_backend_init(); LOGI("llama backend initialized"); });
}

Model* load_model(const char* path) {
    ensure_backend();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; mp.use_mmap = true;
    llama_model* lm = llama_model_load_from_file(path, mp);
    if (!lm) { LOGE("model load failed: %s", path); return nullptr; }

    auto* m = new Model();
    m->model = lm;
    unsigned hw = std::thread::hardware_concurrency();
    m->n_threads = (int) std::max(1u, hw ? hw / 2 : 4u);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = m->n_ctx;
    cp.n_threads       = m->n_threads;
    cp.n_threads_batch = m->n_threads;
    m->ctx = llama_init_from_model(m->model, cp);
    if (!m->ctx) { LOGE("ctx init failed"); llama_model_free(lm); delete m; return nullptr; }
    LOGI("model loaded + context warm: %s (n_ctx=%d, threads=%d)", path, m->n_ctx, m->n_threads);
    return m;
}

// Prompt-prefix cache: system preamble prefilled once, kept resident at [0, system_len).
static const std::string SYSTEM_TEXT =
    "<|im_start|>system\nYou are a concise, helpful assistant.<|im_end|>\n";

static std::string format_turn(const std::string& user) {
    return "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
}

static void ensure_system(Model* m) {
    if (m->system_ready) return;
    llama_context*     ctx   = m->ctx;
    const llama_vocab* vocab = llama_model_get_vocab(m->model);
    llama_memory_clear(llama_get_memory(ctx), /*data=*/true);
    const int n = -llama_tokenize(vocab, SYSTEM_TEXT.c_str(), (int)SYSTEM_TEXT.size(),
                                  nullptr, 0, true, true);
    std::vector<llama_token> toks(n);
    llama_tokenize(vocab, SYSTEM_TEXT.c_str(), (int)SYSTEM_TEXT.size(), toks.data(), n, true, true);
    llama_batch b = llama_batch_get_one(toks.data(), n);
    if (llama_decode(ctx, b) == 0) { m->system_len = n; m->system_ready = true;
        LOGI("system prefix cached: %d tok", n); }
    else LOGE("system prefill failed");
}

int generate(Model* m, const std::string& sessionId, const std::string& prompt, const Sink& sink) {
    if (!m || !m->model || !m->ctx) return 0;
    m->cancel = false;

    const llama_vocab* vocab = llama_model_get_vocab(m->model);
    llama_context*     ctx   = m->ctx;

    ensure_system(m);

    const bool fresh = sessionId.empty() || sessionId != m->active_session;
    if (fresh) {
        // Keep the cached system prefix; drop everything after it.
        llama_memory_seq_rm(llama_get_memory(ctx), /*seq=*/0, m->system_len, /*p1=*/-1);
        m->active_session = sessionId;
        m->session_past   = m->system_len;
    }

    const std::string text = format_turn(prompt);
    const int n_prompt = -llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                         nullptr, 0, false, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, text.c_str(), (int)text.size(),
                       tokens.data(), (int)tokens.size(), false, true) < 0) {
        LOGE("tokenize failed"); return 0;
    }

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    const int  n_ctx      = m->n_ctx;
    const int  max_tokens = 512;
    int        produced   = 0;
    int        n_pos      = m->session_past;
    char       piece[256];

    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());

    using clk = std::chrono::steady_clock;
    const auto t_start = clk::now();
    auto       t_first = t_start;

    for (int generated = 0; generated < max_tokens; ++generated) {
        if (m->cancel || (sink.is_cancelled && sink.is_cancelled())) { LOGI("cancelled @ %d", generated); break; }
        if (n_pos + batch.n_tokens > n_ctx) { LOGI("context full"); break; }
        if (llama_decode(ctx, batch)) { LOGE("llama_decode failed"); break; }
        n_pos += batch.n_tokens;
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (n > 0 && sink.emit_chunk) sink.emit_chunk(std::string(piece, n));
        ++produced;
        if (produced == 1) t_first = clk::now();
        static thread_local llama_token one;
        one   = tok;
        batch = llama_batch_get_one(&one, 1);
    }

    const auto t_end = clk::now();
    const double prefill_s = std::chrono::duration<double>(t_first - t_start).count();
    const double decode_s  = std::chrono::duration<double>(t_end - t_first).count();
    const int    dec_tok   = produced > 1 ? produced - 1 : 0;
    LOGI("perf: session='%s' fresh=%d | %zu new tok, prefill %.2fs | decode %d tok in %.2fs = %.1f tok/s | kv=%d",
         sessionId.c_str(), fresh ? 1 : 0, tokens.size(), prefill_s, dec_tok, decode_s,
         decode_s > 0 ? dec_tok / decode_s : 0.0, n_pos);

    m->session_past = n_pos;
    llama_sampler_free(smpl);   // context kept warm
    return produced;
}

void request_cancel(Model* m) { if (m) m->cancel = true; }

void unload_model(Model* m) {
    if (!m) return;
    if (m->ctx)   llama_free(m->ctx);
    if (m->model) llama_model_free(m->model);
    delete m;
}

} // namespace edgelm
