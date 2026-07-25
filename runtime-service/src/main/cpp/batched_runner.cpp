// =============================================================================
// Paged-KV increment 2 — continuous-batching decode driver. UNBUILT (see header).
//
// Mirrors llama_runner.cpp's emission machinery (UTF-8 boundary + stop-marker
// holdback) so streamed text is identical to the single-context path, but decodes
// many sequences per step. Compile + iterate on device.
// =============================================================================
#include "batched_runner.h"

#include <android/log.h>
#include <chrono>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "edgelm-batched", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "edgelm-batched", __VA_ARGS__)

namespace edgelm {

// --- shared prompt formatting (kept in sync with llama_runner.cpp) ------------
static const std::string SYSTEM_TEXT =
    "<|im_start|>system\nYou are a concise, helpful assistant.<|im_end|>\n";
static std::string format_turn(const std::string& user) {
    return "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
}
static const std::string STOPS[] = { "<|im_end|>", "<|im_start|>", "<|endoftext|>" };

// Longest UTF-8-complete prefix of s (same as llama_runner.cpp::safe_utf8_len).
static size_t safe_utf8_len(const std::string& s) {
    const size_t n = s.size();
    for (size_t back = 0; back < 4 && back < n; ++back) {
        const size_t i = n - 1 - back;
        const unsigned char c = (unsigned char)s[i];
        if ((c & 0xC0) == 0x80) continue;
        size_t need;
        if      ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else return n;
        return (i + need <= n) ? n : i;
    }
    return n;
}

// --- lifecycle ----------------------------------------------------------------

BatchedRuntime* BatchedRuntime::create(llama_model* model, int pool_size,
                                       int n_ctx_per_seq, ggml_threadpool_t threadpool) {
    if (!model || pool_size <= 0 || n_ctx_per_seq <= 0) return nullptr;
    auto* rt = new BatchedRuntime();
    rt->model_         = model;
    rt->pool_size_     = pool_size;
    rt->n_ctx_per_seq_ = n_ctx_per_seq;
    // +1 sequence: the reserved template that holds the shared, prefilled system prefix.
    rt->template_seq_  = pool_size;                 // ids [0,pool) are real; [pool] is the template
    rt->n_ctx_         = (pool_size + 1) * n_ctx_per_seq;
    rt->vocab_         = llama_model_get_vocab(model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = rt->n_ctx_;
    cp.n_seq_max       = pool_size + 1;             // real sequences + the template
    cp.n_threads       = 4;
    cp.n_threads_batch = 4;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cp.type_k          = GGML_TYPE_Q8_0;
    cp.type_v          = GGML_TYPE_Q8_0;
    rt->ctx_ = llama_init_from_model(model, cp);
    if (!rt->ctx_) { LOGE("batched ctx init failed"); delete rt; return nullptr; }
    if (threadpool) llama_attach_threadpool(rt->ctx_, threadpool, threadpool);

    rt->mem_      = llama_get_memory(rt->ctx_);
    rt->registry_ = new SessionRegistry(rt->ctx_, pool_size);
    rt->slots_.resize(pool_size);

    // Batch capacity: one full prompt prefill + one decode token per other sequence.
    rt->batch_ = llama_batch_init(n_ctx_per_seq + pool_size, 0, 1);

    { llama_token t[4];
      if (llama_tokenize(rt->vocab_, "<|im_end|>", 10, t, 4, false, true) == 1) rt->im_end_ = t[0]; }

    // Prefill the shared system prefix ONCE into the template sequence. Real sequences then
    // COW-copy these KV cells (llama_memory_seq_cp) instead of re-prefilling the system
    // prompt — the "ten chats share one system prompt's KV" win (Part 4 / 11).
    {
        const int sn = -llama_tokenize(rt->vocab_, SYSTEM_TEXT.c_str(), (int)SYSTEM_TEXT.size(),
                                       nullptr, 0, true, true);
        if (sn > 0) {
            std::vector<llama_token> stoks(sn);
            llama_tokenize(rt->vocab_, SYSTEM_TEXT.c_str(), (int)SYSTEM_TEXT.size(),
                           stoks.data(), sn, true, true);
            rt->batch_.n_tokens = 0;
            for (int i = 0; i < sn; ++i) {
                rt->batch_.token[i]     = stoks[i];
                rt->batch_.pos[i]       = i;
                rt->batch_.n_seq_id[i]  = 1;
                rt->batch_.seq_id[i][0] = rt->template_seq_;
                rt->batch_.logits[i]    = 0;
                rt->batch_.n_tokens++;
            }
            if (llama_decode(rt->ctx_, rt->batch_) == 0) {
                rt->system_len_ = sn; rt->cow_ = true;
                LOGI("COW system prefix cached: %d tok on template seq %d", sn, rt->template_seq_);
            } else {
                LOGE("system template prefill failed — COW off, sequences self-prefill");
            }
        }
    }

    LOGI("BatchedRuntime up: pool=%d n_ctx=%d (per_seq=%d) cow=%d", pool_size, rt->n_ctx_, n_ctx_per_seq, (int)rt->cow_);
    return rt;
}

BatchedRuntime::~BatchedRuntime() {
    for (auto& s : slots_) if (s.smpl) llama_sampler_free(s.smpl);
    if (batch_.token) llama_batch_free(batch_);
    delete registry_;
    if (ctx_) llama_free(ctx_);
    // model_ is owned by the caller (shared with the single-context path).
}

llama_sampler* BatchedRuntime::make_sampler() const {
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return smpl;
}

// --- submit -------------------------------------------------------------------

bool BatchedRuntime::submit(uint32_t uid, const std::string& session_id,
                            const std::string& prompt, const Sink& sink) {
    std::lock_guard<std::mutex> lk(mu_);
    const llama_seq_id seq = registry_->acquire(uid, session_id);
    if (seq == SessionRegistry::kNone || seq >= pool_size_) { LOGE("submit: no sequence free"); return false; }

    // With COW, share the template's system KV into this sequence and prefill ONLY the turn
    // (no BOS/system). Otherwise fall back to prefilling system + turn from scratch.
    std::string text;
    int start_pos;
    bool add_special;
    if (cow_ && system_len_ > 0) {
        llama_memory_seq_cp(mem_, template_seq_, seq, /*p0=*/0, /*p1=*/system_len_);
        text = format_turn(prompt);
        start_pos = system_len_;
        add_special = false;               // system (with BOS) is already shared in
    } else {
        text = SYSTEM_TEXT + format_turn(prompt);
        start_pos = 0;
        add_special = true;
    }

    const int n = -llama_tokenize(vocab_, text.c_str(), (int)text.size(), nullptr, 0, add_special, true);
    if (n <= 0) { LOGE("submit: tokenize failed"); return false; }
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab_, text.c_str(), (int)text.size(), toks.data(), n, add_special, true) < 0) {
        LOGE("submit: tokenize failed(2)"); return false;
    }

    SeqState& s = slots_[seq];
    if (s.smpl) llama_sampler_free(s.smpl);
    s = SeqState();                        // reset
    s.seq = seq; s.uid = uid; s.session_id = session_id;
    s.sink = sink; s.smpl = make_sampler();
    s.prefill = std::move(toks);
    s.pos = start_pos;                     // KV already holds [0, start_pos) (shared system)
    s.needs_prefill = true; s.active = true;
    LOGI("submit: uid=%u seq=%d turn_prefill=%zu tok cow=%d (pos0=%d)", uid, seq, s.prefill.size(), (int)(cow_ && system_len_ > 0), start_pos);
    return true;
}

// --- batch staging ------------------------------------------------------------

// Append [toks..toks+n) for sequence [s] at its current pos; logits only on the last
// entry (that's where the next token is sampled). Advances s.pos. Returns logits index.
int BatchedRuntime::stage(SeqState& s) {
    const llama_token* toks;
    int n;
    if (s.needs_prefill) { toks = s.prefill.data(); n = (int)s.prefill.size(); s.needs_prefill = false; }
    else                 { toks = &s.next_token;    n = 1; }

    int last = -1;
    for (int i = 0; i < n; ++i) {
        const int j = batch_.n_tokens;
        batch_.token[j]     = toks[i];
        batch_.pos[j]       = s.pos + i;
        batch_.n_seq_id[j]  = 1;
        batch_.seq_id[j][0] = s.seq;
        batch_.logits[j]    = (i == n - 1) ? 1 : 0;
        last = j;
        batch_.n_tokens++;
    }
    s.pos += n;
    return last;
}

// --- token consumption (emit + stop detection) --------------------------------

bool BatchedRuntime::consume(SeqState& s, int lidx) {
    static size_t max_stop = 0;
    if (max_stop == 0) for (const auto& x : STOPS) if (x.size() > max_stop) max_stop = x.size();

    const llama_token tok = llama_sampler_sample(s.smpl, ctx_, lidx);
    if (llama_vocab_is_eog(vocab_, tok) || tok == im_end_) {
        if (s.emitted < s.decoded.size() && s.sink.emit_chunk) s.sink.emit_chunk(s.decoded.substr(s.emitted));
        s.emitted = s.decoded.size();   // mark fully drained so retire() won't re-emit the tail
        return true;   // finished
    }
    char piece[256];
    const int np = llama_token_to_piece(vocab_, tok, piece, sizeof(piece), 0, false);
    if (np > 0) s.decoded.append(piece, np);
    s.produced++;
    s.next_token = tok;   // feed this token next step

    auto flush_upto = [&](size_t end) {
        if (end <= s.emitted) return;
        std::string chunk = s.decoded.substr(s.emitted, end - s.emitted);
        size_t safe = safe_utf8_len(chunk);
        if (safe > 0 && s.sink.emit_chunk) { s.sink.emit_chunk(chunk.substr(0, safe)); s.emitted += safe; }
    };

    size_t cut = std::string::npos;
    for (const auto& x : STOPS) {
        size_t p = s.decoded.find(x, s.emitted);
        if (p != std::string::npos && (cut == std::string::npos || p < cut)) cut = p;
    }
    if (cut != std::string::npos) {                    // stop marker appeared as text
        flush_upto(cut);
        s.emitted = s.decoded.size();                  // discard the marker + anything after; no re-emit
        return true;
    }

    size_t avail = s.decoded.size() - s.emitted;
    size_t hold  = (avail < max_stop - 1) ? avail : (max_stop - 1);
    flush_upto(s.decoded.size() - hold);
    return false;
}

void BatchedRuntime::retire(SeqState& s) {
    if (s.emitted < s.decoded.size() && s.sink.emit_chunk) s.sink.emit_chunk(s.decoded.substr(s.emitted));
    if (s.sink.on_done) s.sink.on_done(s.produced);   // signal completion (frees the request's latch)
    if (s.smpl) { llama_sampler_free(s.smpl); s.smpl = nullptr; }
    registry_->release(s.uid, s.session_id);   // drops KV + returns the slot to the pool
    LOGI("retire: uid=%u seq=%d produced=%d", s.uid, s.seq, s.produced);
    s.active = false;
}

// --- one scheduling step ------------------------------------------------------

int BatchedRuntime::step() {
    std::lock_guard<std::mutex> lk(mu_);
    batch_.n_tokens = 0;

    // Retire cancelled sequences first — explicit cancel() OR the request's own
    // is_cancelled() (e.g. the SDK collector's scope was cancelled).
    for (auto& s : slots_) {
        if (!s.active) continue;
        if (s.cancel || (s.sink.is_cancelled && s.sink.is_cancelled())) retire(s);
    }

    // Stage: at most ONE prefill per step (bounds the batch), plus one decode token for
    // every active, non-paused, already-prefilled sequence.
    std::vector<std::pair<SeqState*, int>> participants;
    bool prefilled_one = false;
    for (auto& s : slots_) {
        if (!s.active || s.paused) continue;
        const int per_seq_budget = n_ctx_per_seq_;   // this seq's KV slice (system prefix is shared)
        if (s.needs_prefill) {
            if (prefilled_one) continue;                  // one prefill per step (bounds the batch)
            if (s.pos + (int)s.prefill.size() + 1 > per_seq_budget) {
                if (s.sink.emit_chunk) s.sink.emit_chunk("");   // prompt won't fit — retire cleanly
                retire(s); continue;
            }
            participants.emplace_back(&s, stage(s));
            prefilled_one = true;
        } else {
            if (s.pos + 1 > per_seq_budget) { retire(s); continue; }   // context full for this seq
            participants.emplace_back(&s, stage(s));
        }
    }

    if (batch_.n_tokens == 0) {
        int n = 0; for (auto& s : slots_) if (s.active) n++;
        return n;
    }
    if (llama_decode(ctx_, batch_)) {
        LOGE("batched llama_decode failed");
        int n = 0; for (auto& s : slots_) if (s.active) n++;   // mu_ already held — don't call active_count()
        return n;
    }

    for (auto& [s, lidx] : participants) {
        if (lidx < 0) continue;
        if (consume(*s, lidx)) retire(*s);
    }

    int n = 0; for (auto& s : slots_) if (s.active) n++;
    return n;
}

void BatchedRuntime::run_until_idle() {
    using clk = std::chrono::steady_clock;
    const auto t0 = clk::now();
    int steps = 0;
    while (step() > 0) steps++;
    const double secs = std::chrono::duration<double>(clk::now() - t0).count();
    LOGI("run_until_idle: %d steps in %.2fs", steps, secs);
}

void BatchedRuntime::set_paused(uint32_t uid, const std::string& session_id, bool paused) {
    std::lock_guard<std::mutex> lk(mu_);
    for (auto& s : slots_)
        if (s.active && s.uid == uid && s.session_id == session_id) { s.paused = paused; return; }
}

void BatchedRuntime::cancel(uint32_t uid, const std::string& session_id) {
    std::lock_guard<std::mutex> lk(mu_);
    for (auto& s : slots_)
        if (s.active && s.uid == uid && s.session_id == session_id) { s.cancel = true; return; }
}

int BatchedRuntime::active_count() const {
    std::lock_guard<std::mutex> lk(mu_);
    int n = 0; for (auto& s : slots_) if (s.active) n++;
    return n;
}

} // namespace edgelm
