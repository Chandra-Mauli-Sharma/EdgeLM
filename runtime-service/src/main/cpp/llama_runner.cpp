#include "llama_runner.h"
#include "llama.h"
#include "ggml-backend.h"   // device enumeration (diagnostic: is a Vulkan GPU present?)

#include <android/log.h>
#include <thread>
#include <vector>
#include <mutex>
#include <chrono>
#include <fstream>
#include <cstring>

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

// Backend chosen at the most recent load_model() — surfaced to the UI via engine_label().
static std::string g_engine_label;

// Count "performance" (big/prime) cores by CPU max frequency. Spreading llama.cpp threads
// onto slow little cores hurts throughput, so we run on the big-core count, not hw/2.
static int perf_core_count() {
    const unsigned n = std::thread::hardware_concurrency();
    if (n == 0) return 4;
    std::vector<long> mx(n, 0); long top = 0;
    for (unsigned i = 0; i < n; ++i) {
        std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq");
        long v = 0; if (f >> v) { mx[i] = v; if (v > top) top = v; }
    }
    if (top <= 0) return (int)std::max(1u, n / 2);              // couldn't read → heuristic
    int big = 0; for (long v : mx) if (v >= (long)(top * 0.85)) ++big;  // top tier (within 15%)
    return std::max(1, big);
}

// --- first-load backend probe -------------------------------------------------
// GPU offload WINS on some SoCs (Adreno) and LOSES on others (Mali — bandwidth-bound,
// weak driver). So instead of assuming, micro-benchmark CPU vs GPU once per model on the
// first load, cache the winner next to the model file, and reuse it on every later load.

// Return WALL-CLOCK SECONDS for a realistic small request (prefill a ~30-token prompt +
// decode a fixed number of tokens), AFTER a warmup pass to discard cold-start/allocation
// overhead. Lower is better. Measuring total time (not decode-only tok/s) is deliberate:
// it captures prefill cost, which is exactly where a weak GPU (e.g. Mali) loses. Returns a
// large sentinel on failure so that backend simply loses the comparison.
static double bench_model_secs(llama_model* model, int n_threads) {
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 512; cp.n_threads = n_threads; cp.n_threads_batch = n_threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) return 1e9;
    const llama_vocab* vocab = llama_model_get_vocab(model);
    const char* prompt =
        "Summarize this in one sentence: on-device AI runs models directly on the phone, "
        "privately and offline, shared by every app that wants intelligence.";
    const int plen = (int)std::strlen(prompt);
    const int n = -llama_tokenize(vocab, prompt, plen, nullptr, 0, true, true);
    if (n <= 0) { llama_free(ctx); return 1e9; }
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, prompt, plen, toks.data(), n, true, true) < 0) { llama_free(ctx); return 1e9; }

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    // one full prefill + decode pass (KV cleared first)
    auto pass = [&](int decode_n) -> bool {
        llama_memory_clear(llama_get_memory(ctx), /*data=*/true);
        llama_batch pb = llama_batch_get_one(toks.data(), n);
        if (llama_decode(ctx, pb)) return false;               // prefill
        for (int i = 0; i < decode_n; ++i) {
            const llama_token tok = llama_sampler_sample(smpl, ctx, -1);
            static thread_local llama_token one; one = tok;
            llama_batch b = llama_batch_get_one(&one, 1);
            if (llama_decode(ctx, b)) return false;
        }
        return true;
    };

    pass(4);                                                     // warmup — discard timing
    const auto t0 = std::chrono::steady_clock::now();
    const bool ok = pass(24);                                   // measured: prefill + 24 decode
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();

    llama_sampler_free(smpl);
    llama_free(ctx);
    return ok ? secs : 1e9;
}

// Load the model with the given offload setting, time a request, then free it.
static double bench_backend(const char* path, int n_gpu_layers, int n_threads) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers; mp.use_mmap = true;
    llama_model* m = llama_model_load_from_file(path, mp);
    if (!m) return 1e9;
    const double secs = bench_model_secs(m, n_threads);
    llama_model_free(m);
    return secs;
}

static std::string pref_path(const char* path) { return std::string(path) + ".backendpref"; }

// Return the n_gpu_layers to use for this model: cached decision if present, otherwise
// probe CPU vs GPU (lower total time wins) and remember the winner.
static int decide_gpu_layers(const char* path, bool has_gpu, int n_threads) {
    {   // cached?
        std::ifstream f(pref_path(path)); int v;
        if (f && (f >> v)) { LOGI("backend pref (cached): n_gpu_layers=%d", v); return v; }
    }
    int chosen = 0;
    if (!has_gpu) {
        LOGI("no GPU offload available — using CPU");
    } else {
        LOGI("probing CPU vs GPU (one-time, first load of this model)…");
        const double gpu = bench_backend(path, 99, n_threads);  // seconds, lower is better
        const double cpu = bench_backend(path, 0,  n_threads);
        chosen = (gpu < cpu * 0.95) ? 99 : 0;   // pick GPU only if it's clearly FASTER
        LOGI("backend probe: gpu=%.2fs | cpu=%.2fs -> %s", gpu, cpu, chosen ? "GPU" : "CPU");
    }
    { std::ofstream f(pref_path(path)); if (f) f << chosen; }   // best-effort cache
    return chosen;
}

Model* load_model(const char* path) {
    ensure_backend();

    // Enumerate ggml backend devices (also confirms the Vulkan backend registered), and
    // remember the first non-CPU (GPU/accel) device's description for the engine label.
    std::string gpu_desc;
    {
        const size_t nd = ggml_backend_dev_count();
        LOGI("ggml devices: %zu | gpu_offload_supported=%d", nd, (int)llama_supports_gpu_offload());
        for (size_t i = 0; i < nd; ++i) {
            ggml_backend_dev_t d = ggml_backend_dev_get(i);
            const int type = (int)ggml_backend_dev_type(d);   // 0 = CPU, non-zero = GPU/accel
            LOGI("  dev[%zu]: name=%s type=%d desc=%s", i,
                 ggml_backend_dev_name(d), type, ggml_backend_dev_description(d));
            if (gpu_desc.empty() && type != 0) gpu_desc = ggml_backend_dev_description(d);
        }
    }

    // Choose the backend by benchmark (cached), not by assumption: GPU can be slower
    // than the CPU on some SoCs. When no GPU is compiled in, has_gpu is false and this
    // resolves to CPU with no probing cost.
    const int n_threads = perf_core_count();
    LOGI("threads=%d (performance cores)", n_threads);
    const bool has_gpu  = llama_supports_gpu_offload();
    const int gpu_layers = decide_gpu_layers(path, has_gpu, n_threads);

    auto try_load = [&](int gl) -> llama_model* {
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = gl;
        mp.use_mmap     = true;
        return llama_model_load_from_file(path, mp);
    };

    llama_model* lm = try_load(gpu_layers);
    if (!lm && gpu_layers != 0) { LOGE("chosen backend load failed; falling back to CPU"); lm = try_load(0); }
    if (!lm) { LOGE("model load failed: %s", path); return nullptr; }

    auto* m = new Model();
    m->model     = lm;
    m->n_threads = n_threads;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = m->n_ctx;
    cp.n_threads       = m->n_threads;
    cp.n_threads_batch = m->n_threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;   // faster attention + less KV memory
    m->ctx = llama_init_from_model(m->model, cp);
    if (!m->ctx) { LOGE("ctx init failed"); llama_model_free(lm); delete m; return nullptr; }
    g_engine_label = (gpu_layers != 0 && !gpu_desc.empty()) ? ("GPU · " + gpu_desc) : "CPU";
    LOGI("model loaded + context warm: %s (n_ctx=%d, threads=%d, engine=%s)",
         path, m->n_ctx, m->n_threads, g_engine_label.c_str());
    return m;
}

const char* engine_label() { return g_engine_label.c_str(); }

// Prompt-prefix cache: system preamble prefilled once, kept resident at [0, system_len).
static const std::string SYSTEM_TEXT =
    "<|im_start|>system\nYou are a concise, helpful assistant.<|im_end|>\n";

static std::string format_turn(const std::string& user) {
    return "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
}

// A single glyph (emoji, CJK, accented letter) can span several tokens, so one
// token's piece is often a *partial* UTF-8 sequence. Converting that partial byte
// run to a Java string yields replacement chars ("?"). Return the byte length of the
// longest prefix of `s` that ends on a complete UTF-8 character, so we can emit that
// and hold the incomplete tail until the next token completes it.
static size_t safe_utf8_len(const std::string& s) {
    const size_t n = s.size();
    // Scan back over at most 3 continuation bytes to find the last lead byte.
    for (size_t back = 0; back < 4 && back < n; ++back) {
        const size_t i = n - 1 - back;
        const unsigned char c = (unsigned char)s[i];
        if ((c & 0xC0) == 0x80) continue;            // continuation byte, keep scanning
        size_t need;                                  // bytes this character needs
        if      ((c & 0x80) == 0x00) need = 1;        // 0xxxxxxx  ASCII
        else if ((c & 0xE0) == 0xC0) need = 2;        // 110xxxxx
        else if ((c & 0xF0) == 0xE0) need = 3;        // 1110xxxx
        else if ((c & 0xF8) == 0xF0) need = 4;        // 11110xxx
        else                         return n;        // invalid lead: don't stall, flush it
        return (i + need <= n) ? n : i;               // complete → all safe; else split before it
    }
    return n;   // no lead byte in the last 4 bytes (all ASCII/continuation): safe as-is
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

    // Some Qwen GGUFs mark only <|endoftext|> as EOG, so the assistant's <|im_end|>
    // slips through llama_vocab_is_eog and the model role-plays another turn. Treat
    // <|im_end|> as an explicit stop token.
    llama_token im_end = -1;
    { llama_token t[4]; if (llama_tokenize(vocab, "<|im_end|>", 10, t, 4, false, true) == 1) im_end = t[0]; }

    // Text-level stop markers. Belt-and-suspenders for the token check above: some
    // GGUF tokenizations emit the chat template as *literal text* (normal tokens), so
    // matching the <|im_end|> token id can't catch it. We scan the decoded text and
    // stop at the first marker — and hold back a short tail each step so a marker split
    // across tokens never reaches the UI before we can see it.
    static const std::string STOPS[] = { "<|im_end|>", "<|im_start|>", "<|endoftext|>" };
    size_t max_stop = 0;
    for (const auto& s : STOPS) if (s.size() > max_stop) max_stop = s.size();

    std::string decoded;   // full text decoded this turn
    size_t      emitted = 0;   // bytes already sent to the sink

    // Emit decoded[emitted, end), trimmed to a UTF-8 boundary so we never split a glyph.
    auto flush_upto = [&](size_t end) {
        if (end <= emitted) return;
        std::string chunk = decoded.substr(emitted, end - emitted);
        size_t safe = safe_utf8_len(chunk);
        if (safe > 0 && sink.emit_chunk) { sink.emit_chunk(chunk.substr(0, safe)); emitted += safe; }
    };

    bool hit_stop = false;
    for (int generated = 0; generated < max_tokens; ++generated) {
        if (m->cancel || (sink.is_cancelled && sink.is_cancelled())) { LOGI("cancelled @ %d", generated); break; }
        if (n_pos + batch.n_tokens > n_ctx) { LOGI("context full"); break; }
        if (llama_decode(ctx, batch)) { LOGE("llama_decode failed"); break; }
        n_pos += batch.n_tokens;
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok) || tok == im_end) break;   // clean stop token
        // special=false: never render a control token (e.g. a stray <|im_start|>) as text.
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) decoded.append(piece, n);
        ++produced;
        if (produced == 1) t_first = clk::now();

        // A stop marker fully appeared as text → emit up to it and stop.
        size_t cut = std::string::npos;
        for (const auto& s : STOPS) {
            size_t p = decoded.find(s, emitted);
            if (p != std::string::npos && (cut == std::string::npos || p < cut)) cut = p;
        }
        if (cut != std::string::npos) { flush_upto(cut); hit_stop = true; break; }

        // Otherwise flush all but a short tail that could be the start of a marker.
        size_t avail = decoded.size() - emitted;
        size_t hold  = (avail < max_stop - 1) ? avail : (max_stop - 1);
        flush_upto(decoded.size() - hold);

        static thread_local llama_token one;
        one   = tok;
        batch = llama_batch_get_one(&one, 1);
    }
    // Clean stop (EOG / im_end / context / cancel): no marker present, flush the rest.
    if (!hit_stop && emitted < decoded.size() && sink.emit_chunk)
        sink.emit_chunk(decoded.substr(emitted));

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
