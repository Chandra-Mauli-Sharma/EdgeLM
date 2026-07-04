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
// This mirrors llama.cpp's examples/simple/simple.cpp. The llama.cpp API drifts;
// if a symbol below doesn't exist in the commit you vendored, open that example
// and copy the current call shape. Names that changed recently:
//   llama_load_model_from_file   -> llama_model_load_from_file
//   llama_new_context_with_model -> llama_init_from_model
//   llama_free_model             -> llama_model_free
//   kv-cache helpers             -> llama_get_memory() + llama_memory_clear()
// -----------------------------------------------------------------------------

namespace edgelm {

struct Model {
    llama_model*   model     = nullptr;
    llama_context* ctx       = nullptr;   // created once, kept WARM, reused per call
    int            n_ctx     = 2048;
    int            n_threads = 4;
    volatile bool  cancel    = false;
};

static std::once_flag g_backend_once;
static void ensure_backend() {
    std::call_once(g_backend_once, []{
        llama_backend_init();
        LOGI("llama backend initialized");
    });
}

Model* load_model(const char* path) {
    ensure_backend();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;          // CPU-only for now; Vulkan flips this to >0
    mp.use_mmap     = true;       // keep the shared-memory property from Phase 0

    llama_model* lm = llama_model_load_from_file(path, mp);
    if (!lm) { LOGE("llama_model_load_from_file failed: %s", path); return nullptr; }

    auto* m = new Model();
    m->model = lm;
    unsigned hw = std::thread::hardware_concurrency();
    m->n_threads = (int) std::max(1u, hw ? hw / 2 : 4u);   // big cores, roughly

    // Create the context ONCE and keep it warm. Rebuilding it per request cost
    // ~2s of graph allocation + warmup every call (the old "prefill" spike).
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = m->n_ctx;
    cp.n_threads       = m->n_threads;
    cp.n_threads_batch = m->n_threads;
    m->ctx = llama_init_from_model(m->model, cp);
    if (!m->ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(lm); delete m; return nullptr;
    }
    LOGI("model loaded + context warm: %s (n_ctx=%d, threads=%d)", path, m->n_ctx, m->n_threads);
    return m;
}

// Minimal ChatML wrapper (Qwen2.5 / many instruct models). Different families
// need different templates; for production call llama_chat_apply_template with
// the model's built-in template instead of hardcoding this.
static std::string format_prompt(const std::string& user) {
    return "<|im_start|>system\nYou are a concise, helpful assistant.<|im_end|>\n"
           "<|im_start|>user\n" + user + "<|im_end|>\n"
           "<|im_start|>assistant\n";
}

int generate(Model* m, const std::string& prompt, const Sink& sink) {
    if (!m || !m->model || !m->ctx) return 0;
    m->cancel = false;

    const llama_vocab* vocab = llama_model_get_vocab(m->model);
    llama_context*     ctx   = m->ctx;

    // Reuse the warm context: just wipe the KV from the previous request.
    // (Session-aware KV reuse across turns is the next step — PHASE1-KV-POOLING.)
    llama_memory_clear(llama_get_memory(ctx), /*data=*/true);

    // ---- tokenize the (templated) prompt ------------------------------------
    const std::string text = format_prompt(prompt);
    const int n_prompt = -llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                         nullptr, 0, /*add_special*/ true, /*parse_special*/ true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, text.c_str(), (int)text.size(),
                       tokens.data(), (int)tokens.size(), true, true) < 0) {
        LOGE("tokenize failed"); return 0;
    }

    // ---- sampler chain (per request) ----------------------------------------
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    const int  n_ctx      = m->n_ctx;
    const int  max_tokens = 512;
    int        produced   = 0;
    int        n_pos      = 0;
    char       piece[256];

    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());

    using clk = std::chrono::steady_clock;
    const auto t_start = clk::now();
    auto       t_first = t_start;

    for (int generated = 0; generated < max_tokens; ++generated) {
        if (m->cancel || (sink.is_cancelled && sink.is_cancelled())) {
            LOGI("cancelled @ %d", generated); break;
        }
        if (n_pos + batch.n_tokens > n_ctx) { LOGI("context full"); break; }

        if (llama_decode(ctx, batch)) { LOGE("llama_decode failed"); break; }
        n_pos += batch.n_tokens;

        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, /*special*/ true);
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
    LOGI("perf: %d prompt tok, prefill %.2fs | decode %d tok in %.2fs = %.1f tok/s | threads=%d",
         (int)tokens.size(), prefill_s, dec_tok, decode_s,
         decode_s > 0 ? dec_tok / decode_s : 0.0, m->n_threads);

    llama_sampler_free(smpl);   // context is kept warm — do NOT free it here
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
