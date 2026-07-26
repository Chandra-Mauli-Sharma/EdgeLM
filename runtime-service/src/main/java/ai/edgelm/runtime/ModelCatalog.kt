package ai.edgelm.runtime

import android.content.Context
import java.io.File

/**
 * A single downloadable model in the EdgeLM catalog.
 *
 * All entries are 4-bit **Q4_0** GGUFs — chosen because recent llama.cpp repacks Q4_0
 * at load into an i8mm/dotprod-optimized layout on ARM, which is materially faster on
 * phone CPUs than Q4_K_M. Hosted on repos that download WITHOUT a Hugging Face token,
 * with licenses clean enough to ship (Apache-2.0 / MIT / Llama Community). Sizes are the
 * actual on-disk download sizes.
 */
data class ModelSpec(
    val id: String,        // stable file stem, e.g. "qwen2.5-0.5b-instruct"
    val name: String,      // display name
    val params: String,    // "0.5B"
    val quant: String,     // "Q4_K_M"
    val sizeMb: Int,       // download size, MB
    val ctx: String,       // advertised context window
    val minRamMb: Int,     // rough RAM needed to actually run it
    val license: String,
    val blurb: String,
    val useCase: String,   // recommended "best for" one-liner (Advanced view)
    val simpleName: String,    // friendly, jargon-free name (Simple view)
    val simpleTagline: String, // plain-language one-liner (Simple view)
    val url: String,
    val draftId: String? = null, // optional SAME-TOKENIZER draft model id → speculative decoding
    val format: String = "gguf",   // artifact type → engine routing: "gguf" (llama.cpp) | "litertlm" (LiteRT-LM)
    val litertUrl: String? = null, // optional .litertlm artifact for the LiteRT-LM engine (Phase B+)
    val litertSizeMb: Int? = null, // download size of the .litertlm artifact, if different from sizeMb
    // --- Hub v1 (content-addressing + versioning; see Hub.kt / docs/PHASE1-HUB.md) ---
    // Expected SHA-256 of the downloaded artifact. When set, DownloadWorker refuses to
    // install a file whose hash doesn't match (tamper/corruption guard). Null = legacy
    // (unverified) entry; fill in when publishing through Hub. Per-format because the
    // gguf and .litertlm artifacts are different bytes.
    val sha256: String? = null,        // for the gguf artifact
    val litertSha256: String? = null,  // for the .litertlm artifact
    val version: Int = 1,              // model version → pin/rollback (Hub)
    val family: String? = null,        // logical family for Hub.resolve(), e.g. "llm.small"
    val kind: String = "chat",         // "chat" (generation) | "embed" (embedding encoder)
)

/**
 * The curated model list shown in the runtime app. Small → mid, so it spans
 * "runs on anything" to "flagship only". Keep ids stable — they're filenames.
 */
object ModelCatalog {
    val models: List<ModelSpec> = listOf(
        ModelSpec(
            id = "smollm2-360m-instruct",
            name = "SmolLM2 360M Instruct",
            params = "360M", quant = "Q4_0", sizeMb = 219, ctx = "8K",
            minRamMb = 768, license = "Apache-2.0",
            blurb = "The lightest, fastest model in the catalog — clears 30+ tok/s on most " +
                    "phones. Great for instant replies, autocomplete and simple tasks; not " +
                    "meant for deep reasoning or long-form writing.",
            useCase = "Ultra-low-latency autocomplete, quick replies, and simple text tasks on any device.",
            simpleName = "Instant Assistant",
            simpleTagline = "The fastest option — instant replies for short, simple questions.",
            url = "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_0.gguf?download=true",
        ),
        ModelSpec(
            id = "qwen2.5-0.5b-instruct",
            name = "Qwen2.5 0.5B Instruct",
            params = "0.5B", quant = "Q4_0", sizeMb = 337, ctx = "32K",
            minRamMb = 1024, license = "Apache-2.0",
            blurb = "Tiny and fast — runs on virtually any phone. Great default for chat, " +
                    "summarization and simple tools. Lowest memory footprint in the catalog.",
            useCase = "Autocomplete, quick replies, text classification, and low-latency tasks on any device.",
            simpleName = "Quick Assistant",
            simpleTagline = "Fast and light. Great for quick questions and short replies — works on any phone.",
            url = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_0.gguf?download=true",
        ),
        ModelSpec(
            id = "llama-3.2-1b-instruct",
            name = "Llama 3.2 1B Instruct",
            params = "1B", quant = "Q4_0", sizeMb = 771, ctx = "128K",
            minRamMb = 1536, license = "Llama 3.2 Community",
            blurb = "Meta's compact model with a large context window. Strong instruction " +
                    "following for its size; a good everyday balance of speed and quality.",
            useCase = "Everyday chat, summarizing long documents, and note/email drafting (128K context).",
            simpleName = "Everyday Assistant",
            simpleTagline = "A well-rounded helper for chatting, writing, and summarizing.",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_0.gguf?download=true",
        ),
        ModelSpec(
            id = "qwen2.5-1.5b-instruct",
            name = "Qwen2.5 1.5B Instruct",
            params = "1.5B", quant = "Q4_0", sizeMb = 935, ctx = "32K",
            minRamMb = 2048, license = "Apache-2.0",
            blurb = "Noticeably sharper reasoning and multilingual ability than the 0.5B, " +
                    "still light enough for most mid-range devices. Apache-2.0.",
            useCase = "Multilingual chat, structured/JSON output, and light reasoning where 0.5B falls short.",
            simpleName = "Smart Assistant",
            simpleTagline = "Sharper answers and better with other languages.",
            url = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_0.gguf?download=true",
        ),
        ModelSpec(
            id = "llama-3.2-3b-instruct",
            name = "Llama 3.2 3B Instruct",
            params = "3B", quant = "Q4_0", sizeMb = 1920, ctx = "128K",
            minRamMb = 4096, license = "Llama 3.2 Community",
            blurb = "A capable mid-size model for higher-quality writing and reasoning. " +
                    "Best on phones with 6 GB+ RAM.",
            useCase = "High-quality writing and rewriting, RAG answers, and multi-step reasoning on 6 GB+ phones.",
            simpleName = "Pro Assistant",
            simpleTagline = "Higher-quality writing and thinking. Best on newer phones.",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_0.gguf?download=true",
            // Same Llama-3.2 tokenizer as the 1B → the 1B can draft for speculative decoding,
            // ~1.5-2× decode speed when the 1B is also installed. Falls back to plain single-
            // model decode when the draft isn't present.
            draftId = "llama-3.2-1b-instruct",
        ),
        ModelSpec(
            id = "phi-3.5-mini-instruct",
            name = "Phi-3.5 mini Instruct",
            params = "3.8B", quant = "Q4_0", sizeMb = 2180, ctx = "128K",
            minRamMb = 5120, license = "MIT",
            blurb = "Microsoft's strong small model — excellent at reasoning, code and math. " +
                    "The heaviest here; flagship phones only.",
            useCase = "Coding assistance, math, and complex reasoning where quality matters most (flagship devices).",
            simpleName = "Expert Assistant",
            simpleTagline = "Best for tricky questions, math, and coding help. Powerful phones only.",
            url = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_0.gguf?download=true",
        ),
        // --- Phase B (LiteRT-LM) — ACTIVE. An UNGATED, GPU-portable .litertlm from the LiteRT
        // community hub. We use **Gemma 4 E2B** rather than the older Qwen2.5-1.5B q8 build for two
        // reasons that together fix the GPU story:
        //   1) MTP — Gemma 4 ships multi-token-prediction heads, so LiteRtEngine's
        //      `enableSpeculativeDecoding = true` flag ACTUALLY engages here (it was inert on Qwen,
        //      which has no MTP). MTP accelerates GPU decode with ~zero quality loss (LiteRT-LM
        //      v0.11+). This is the real per-token speedup on top of the raw GPU backend.
        //   2) Ungated — the `litert-community/gemma-4-E2B-it-litert-lm` mirror is tagged apache-2.0
        //      and needs NO Hugging Face login, so DownloadWorker (which sends no auth token) can
        //      fetch it (the google/* Gemma repos are gated → 401). ✅ LICENSE (confirmed Jul 2026):
        //      Gemma 4 shipped under Apache-2.0 (Google Open Source Blog, "Gemma 4: Expanding the
        //      Gemmaverse with Apache 2.0", Mar 2026) — the OLD custom "Gemma Terms of Use" no longer
        //      applies to Gemma 4. So redistribution is unrestricted (standard Apache attribution).
        //      (Still good practice to spot-check the specific artifact's LICENSE file on HF.)
        // Use the GENERIC `gemma-4-E2B-it.litertlm` (portable CPU/GPU OpenCL). The SoC-suffixed
        // builds (_qualcomm_sm8750, _Google_Tensor_G5, _intel_*) target the NPU/QNN path, which our
        // engine (Backend.GPU) does not use. Must be a real .litertlm (NOT a .task, which LiteRT-LM's
        // Engine rejects with "Invalid magic number"). Routes to LiteRtEngine on 64-bit devices; if
        // the GPU backend can't initialize the model won't load (no GGUF fallback). Bigger download
        // than the Qwen build (2.59 GB vs 1.6 GB) and needs more RAM — but far stronger + multimodal,
        // and MTP keeps decode competitive. See docs/PHASE-B-LITERT-INTEGRATION.md.
        ModelSpec(
            id = "gemma-4-e2b-litert",
            name = "Gemma 4 E2B (LiteRT)",
            params = "E2B (~2B)", quant = "INT4", sizeMb = 2590, ctx = "4K",
            minRamMb = 6144, license = "Apache-2.0",
            blurb = "Google's Gemma 4 E2B, LiteRT-LM GPU build with multi-token prediction (MTP) " +
                    "speculative decoding — the fast path on Adreno-class GPUs. Stronger and newer " +
                    "than the Qwen build it replaces. Ungated download, no Hugging Face login.",
            useCase = "High-quality, hardware-accelerated on-device chat on GPU hardware with MTP decode (LiteRT-LM engine).",
            simpleName = "Turbo Assistant",
            simpleTagline = "Hardware-accelerated with multi-token prediction — fast, high-quality replies on supported phones.",
            url = "",  // no GGUF: this model runs only on the LiteRT-LM engine
            format = "litertlm",
            litertUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
            litertSizeMb = 2590,
        ),
        // --- Phase 2: on-device embeddings (kind="embed") ---------------------------------
        // BGE-small-en-v1.5: a tiny (33M) BERT-style encoder → 384-dim sentence embeddings,
        // strong for its size on retrieval. Runs on CPU in llama.cpp embedding mode (mean
        // pooling). Loaded in a SEPARATE handle from the chat model, so on-device RAG can
        // embed + chat without swapping models. ⚠️ VERIFY the URL resolves without a HF token
        // before shipping (mirrors the chat-model download policy). No instruction prefix is
        // applied — fine for symmetric similarity; add "Represent this sentence:" for asymmetric
        // retrieval if quality needs it.
        ModelSpec(
            id = "bge-small-en-v1.5",
            name = "BGE-small EN v1.5 (embeddings)",
            params = "33M", quant = "Q8_0", sizeMb = 34, ctx = "512",
            minRamMb = 256, license = "MIT",
            blurb = "Tiny on-device text-embedding model (384-dim). Powers local search, " +
                    "similarity, and retrieval-augmented generation — your text never leaves the device.",
            useCase = "On-device embeddings for semantic search and RAG (OpenAI /v1/embeddings compatible).",
            simpleName = "Search Brain",
            simpleTagline = "Understands meaning for on-device search — text stays private.",
            url = "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q8_0.gguf?download=true",
            family = "embed.small",
            kind = "embed",
        ),
    )

    /** The default embedding model (kind="embed"), or null if none in the catalog. */
    fun embeddingModel(): ModelSpec? = models.firstOrNull { it.kind == "embed" }

    fun byId(id: String?): ModelSpec? = models.firstOrNull { it.id == id }

    /**
     * The model to suggest by default. We recommend the **1B** — the best balance of
     * speed (snappy on-device tok/s) and quality for most phones — rather than the
     * largest that fits, because a fast first experience matters more than raw quality
     * for a default. Bigger models (3B, Phi) are one tap away in Advanced. On low-RAM
     * devices where the 1B won't fit, fall back to the largest that does (the 0.5B).
     */
    fun recommendedFor(ramMb: Int): ModelSpec {
        val oneB = byId("llama-3.2-1b-instruct") ?: models.first()
        if (ramMb <= 0) return oneB
        val budget = (ramMb * 0.5).toInt()
        return if (oneB.minRamMb <= budget) oneB
               else models.filter { it.minRamMb <= budget }.maxByOrNull { it.minRamMb }
                    ?: models.minByOrNull { it.minRamMb }!!
    }

    /** A plain-language one-word speed/capability hint for the simple UI. */
    fun hintFor(spec: ModelSpec): String = when {
        spec.sizeMb < 300 -> "Instant"
        spec.sizeMb < 600 -> "Fastest"
        spec.sizeMb < 1000 -> "Fast"
        spec.sizeMb < 1500 -> "Balanced"
        spec.sizeMb < 2200 -> "Smarter"
        else -> "Most capable"
    }

    /** Models to show in the picker. Hides LiteRT-only models (a .litertlm with no GGUF) on
     *  devices that can't run them — mirroring LiteRtEngine.canRunOn so we never offer (or let the
     *  user download) a model that would only fail. A LiteRT-only model is shown iff the GPU is
     *  proven-good, or not-yet-probed AND on an allowlisted (Adreno) GPU family. This is the
     *  pre-download gate: on Mali/Exynos/MediaTek the LiteRT models simply never appear. */
    fun visibleModels(ctx: Context): List<ModelSpec> {
        val device = ai.edgelm.service.DeviceProfile.current()
        // A LiteRT-only model is runnable iff this device can run it: GPU proven-good, or
        // not-yet-probed AND on an allowlisted (Adreno) GPU family. Otherwise hide it.
        val litertRunnable = when (ai.edgelm.service.EngineProfile.litertGpuUsable(ctx)) {
            true  -> true
            false -> false
            null  -> device.has64BitAbi && device.likelyLiteRtGpuCapable
        }
        return models.filter { spec ->
            if (spec.kind != "chat") return@filter false        // embed models aren't chat picks
            val litertOnly = spec.format == "litertlm" && spec.url.isEmpty()
            !litertOnly || litertRunnable
        }
    }
}

/** First-run + interface preferences. */
object Prefs {
    private const val FILE = "edgelm_prefs"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun isOnboarded(ctx: Context) = sp(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context) = sp(ctx).edit().putBoolean("onboarded", true).apply()
    fun isSimpleMode(ctx: Context) = sp(ctx).getBoolean("simple_mode", true)
    fun setSimpleMode(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("simple_mode", v).apply()
}

/**
 * Multi-model on-disk storage. Downloaded models live at files/models/<id>.gguf;
 * files/active_model holds the id the engine should load. Both the app process
 * (picker UI) and the :core service process share filesDir (same app UID), so the
 * service just re-reads the pointer on reloadModel().
 */
object ModelStore {
    // On-disk artifact formats, one file extension each. A model is stored as <id>.<format>;
    // the format is chosen per device by the engine router (see InferenceEngine.artifactFor).
    private val FORMAT_EXTS = listOf("gguf", "litertlm")

    private fun modelsDir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    private fun pointer(ctx: Context) = File(ctx.filesDir, "active_model")

    /** Path for [id] in a specific [format] (used when downloading the routed artifact). */
    fun fileFor(ctx: Context, id: String, format: String = "gguf") =
        File(modelsDir(ctx), "$id.$format")

    /** The installed artifact for [id] in whichever engine format is present, or null. */
    fun installedFile(ctx: Context, id: String): File? =
        FORMAT_EXTS.map { File(modelsDir(ctx), "$id.$it") }.firstOrNull { it.exists() }

    fun isInstalled(ctx: Context, id: String) = installedFile(ctx, id) != null

    fun installedIds(ctx: Context): Set<String> =
        modelsDir(ctx).listFiles()?.filter { it.extension in FORMAT_EXTS }
            ?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    fun activeId(ctx: Context): String? =
        pointer(ctx).takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }

    fun setActive(ctx: Context, id: String) = pointer(ctx).writeText(id)
    fun clearActive(ctx: Context) { pointer(ctx).delete() }

    /**
     * Absolute path the engine should load, or "" if nothing is installed. Falls
     * back to the legacy single-slot files/model.gguf from before the catalog.
     */
    fun activePath(ctx: Context): String {
        activeId(ctx)?.let { id ->
            installedFile(ctx, id)?.let { return it.absolutePath }
        }
        val legacy = File(ctx.filesDir, "model.gguf")
        return if (legacy.exists()) legacy.absolutePath else ""
    }

    /** Remove an installed model (any format variant); if it was active, clear the pointer. */
    fun remove(ctx: Context, id: String) {
        FORMAT_EXTS.forEach { File(modelsDir(ctx), "$id.$it").delete() }
        if (activeId(ctx) == id) clearActive(ctx)
    }
}
