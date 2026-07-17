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
        // community hub (Qwen2.5-1.5B, Apache-2.0) — ungated so the in-app download needs no HF
        // login (Gemma is gated → 401). Must be a real .litertlm (NOT a .task, which LiteRT-LM's
        // Engine rejects with "Invalid magic number"). Routes to LiteRtEngine on 64-bit devices;
        // if the GPU backend can't initialize the model won't load (no GGUF fallback for this
        // entry). See docs/PHASE-B-LITERT-INTEGRATION.md.
        ModelSpec(
            id = "qwen2.5-1.5b-litert",
            name = "Qwen2.5 1.5B (LiteRT)",
            params = "1.5B", quant = "INT8", sizeMb = 1524, ctx = "4K",
            minRamMb = 3072, license = "Apache-2.0",
            blurb = "Qwen2.5 1.5B, LiteRT-LM build with GPU acceleration — the fast path on " +
                    "devices with a capable GPU (portable OpenCL backend). Ungated download.",
            useCase = "High-throughput on-device chat on GPU hardware (LiteRT-LM engine).",
            simpleName = "Turbo Assistant",
            simpleTagline = "Hardware-accelerated on supported phones — very fast replies.",
            url = "",  // no GGUF: this model runs only on the LiteRT-LM engine
            format = "litertlm",
            litertUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
            litertSizeMb = 1524,
        ),
    )

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
