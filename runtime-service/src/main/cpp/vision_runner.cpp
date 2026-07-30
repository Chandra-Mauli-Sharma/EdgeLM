// =============================================================================
// Vision / multimodal runner using llama.cpp mtmd. UNBUILT (-DEDGELM_VISION=ON).
// =============================================================================
#include "vision_runner.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <android/log.h>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "edgelm-vision", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "edgelm-vision", __VA_ARGS__)

namespace edgelm {

struct VisionModel {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    mtmd_context*  mctx  = nullptr;   // the mmproj vision encoder/projector
    int            n_threads = 4;
};

VisionModel* load_vision_model(const char* model_path, const char* mmproj_path) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; mp.use_mmap = true;   // small VLMs on CPU
    llama_model* lm = llama_model_load_from_file(model_path, mp);
    if (!lm) { LOGE("vision: LLM load failed: %s", model_path); return nullptr; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 4096; cp.n_threads = 4; cp.n_threads_batch = 4;
    llama_context* ctx = llama_init_from_model(lm, cp);
    if (!ctx) { LOGE("vision: ctx init failed"); llama_model_free(lm); return nullptr; }

    mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu = false; mparams.n_threads = 4; mparams.print_timings = false;
    mtmd_context* mctx = mtmd_init_from_file(mmproj_path, lm, mparams);
    if (!mctx) { LOGE("vision: mtmd_init failed: %s", mmproj_path); llama_free(ctx); llama_model_free(lm); return nullptr; }

    LOGI("vision model up: vision=%d audio=%d", (int)mtmd_support_vision(mctx), (int)mtmd_support_audio(mctx));
    return new VisionModel{ lm, ctx, mctx, 4 };
}

int vision_generate(VisionModel* m, const std::string& prompt,
                    const uint8_t* image, int image_len, const Sink& sink) {
    if (!m || !m->mctx) return 0;
    const llama_vocab* vocab = llama_model_get_vocab(m->model);

    // Decode the image FILE bytes (jpg/png/...) into an mtmd bitmap (stb_image under the hood).
    auto wrap = mtmd_helper_bitmap_init_from_buf(m->mctx, image, (size_t)image_len, /*placeholder=*/false);
    if (!wrap.bitmap) { LOGE("vision: image decode failed"); return 0; }

    // Build the prompt with the MODEL'S OWN chat template, so each model gets the right wrapper:
    // Qwen vision → ChatML, Llama-3.2/Ultravox audio → the llama3 header format. The media
    // marker lives inside the user message content. Falls back to ChatML if the model carries
    // no built-in template (preserves the previous behaviour for template-less GGUFs).
    const std::string user_content = std::string(mtmd_default_marker()) + "\n" + prompt;
    std::string full;
    const char* tmpl = llama_model_chat_template(m->model, nullptr);
    if (tmpl) {
        llama_chat_message msg{ "user", user_content.c_str() };
        std::vector<char> buf(user_content.size() * 2 + 256);
        int32_t n = llama_chat_apply_template(tmpl, &msg, 1, /*add_ass=*/true, buf.data(), (int32_t)buf.size());
        if (n > (int32_t)buf.size()) {           // buffer too small → grow and retry (per API contract)
            buf.resize(n);
            n = llama_chat_apply_template(tmpl, &msg, 1, /*add_ass=*/true, buf.data(), n);
        }
        if (n > 0) full.assign(buf.data(), (size_t)n);
    }
    if (full.empty()) {   // no built-in template (or apply failed) → ChatML fallback (Qwen-style)
        full = std::string("<|im_start|>user\n") + user_content + "<|im_end|>\n<|im_start|>assistant\n";
    }
    LOGI("vision: chat template=%s", tmpl ? "model-builtin" : "chatml-fallback");
    mtmd_input_text itext{ full.c_str(), /*add_special=*/true, /*parse_special=*/true };

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    const mtmd_bitmap* bmps[1] = { wrap.bitmap };
    if (mtmd_tokenize(m->mctx, chunks, &itext, bmps, 1) != 0) {
        LOGE("vision: tokenize failed");
        mtmd_input_chunks_free(chunks); mtmd_bitmap_free(wrap.bitmap); return 0;
    }

    // Prefill prompt + encoded image into the context (helper runs encode + decode).
    llama_pos new_past = 0;
    if (mtmd_helper_eval_chunks(m->mctx, m->ctx, chunks, /*n_past=*/0, /*seq_id=*/0,
                                /*n_batch=*/512, /*logits_last=*/true, &new_past) != 0) {
        LOGE("vision: eval_chunks failed");
        mtmd_input_chunks_free(chunks); mtmd_bitmap_free(wrap.bitmap); return 0;
    }

    // Standard decode loop for the text response.
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_token im_end = -1;
    { llama_token t[4]; if (llama_tokenize(vocab, "<|im_end|>", 10, t, 4, false, true) == 1) im_end = t[0]; }

    int produced = 0; char piece[256];
    const int max_tokens = 256;
    for (int i = 0; i < max_tokens; ++i) {
        if (sink.is_cancelled && sink.is_cancelled()) break;
        llama_token tok = llama_sampler_sample(smpl, m->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok) || tok == im_end) break;
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0 && sink.emit_chunk) sink.emit_chunk(std::string(piece, (size_t)n));
        ++produced;
        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(m->ctx, b)) { LOGE("vision: decode failed"); break; }
    }

    llama_sampler_free(smpl);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(wrap.bitmap);
    LOGI("vision: produced %d tokens", produced);
    return produced;
}

void unload_vision_model(VisionModel* m) {
    if (!m) return;
    if (m->mctx)  mtmd_free(m->mctx);
    if (m->ctx)   llama_free(m->ctx);
    if (m->model) llama_model_free(m->model);
    delete m;
}

} // namespace edgelm
