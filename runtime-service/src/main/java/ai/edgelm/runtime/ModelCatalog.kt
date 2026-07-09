package ai.edgelm.runtime

import android.content.Context
import java.io.File

/**
 * A single downloadable model in the EdgeLM catalog.
 *
 * All entries are 4-bit (Q4_K_M) GGUFs hosted on repos that download WITHOUT a
 * Hugging Face token, with licenses clean enough to ship (Apache-2.0 / MIT /
 * Llama Community). Sizes are the actual on-disk download sizes.
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
)

/**
 * The curated model list shown in the runtime app. Small → mid, so it spans
 * "runs on anything" to "flagship only". Keep ids stable — they're filenames.
 */
object ModelCatalog {
    val models: List<ModelSpec> = listOf(
        ModelSpec(
            id = "qwen2.5-0.5b-instruct",
            name = "Qwen2.5 0.5B Instruct",
            params = "0.5B", quant = "Q4_K_M", sizeMb = 491, ctx = "32K",
            minRamMb = 1024, license = "Apache-2.0",
            blurb = "Tiny and fast — runs on virtually any phone. Great default for chat, " +
                    "summarization and simple tools. Lowest memory footprint in the catalog.",
            useCase = "Autocomplete, quick replies, text classification, and low-latency tasks on any device.",
            simpleName = "Quick Assistant",
            simpleTagline = "Fast and light. Great for quick questions and short replies — works on any phone.",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true",
        ),
        ModelSpec(
            id = "llama-3.2-1b-instruct",
            name = "Llama 3.2 1B Instruct",
            params = "1B", quant = "Q4_K_M", sizeMb = 808, ctx = "128K",
            minRamMb = 1536, license = "Llama 3.2 Community",
            blurb = "Meta's compact model with a large context window. Strong instruction " +
                    "following for its size; a good everyday balance of speed and quality.",
            useCase = "Everyday chat, summarizing long documents, and note/email drafting (128K context).",
            simpleName = "Everyday Assistant",
            simpleTagline = "A well-rounded helper for chatting, writing, and summarizing.",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true",
        ),
        ModelSpec(
            id = "qwen2.5-1.5b-instruct",
            name = "Qwen2.5 1.5B Instruct",
            params = "1.5B", quant = "Q4_K_M", sizeMb = 1120, ctx = "32K",
            minRamMb = 2048, license = "Apache-2.0",
            blurb = "Noticeably sharper reasoning and multilingual ability than the 0.5B, " +
                    "still light enough for most mid-range devices. Apache-2.0.",
            useCase = "Multilingual chat, structured/JSON output, and light reasoning where 0.5B falls short.",
            simpleName = "Smart Assistant",
            simpleTagline = "Sharper answers and better with other languages.",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
        ),
        ModelSpec(
            id = "llama-3.2-3b-instruct",
            name = "Llama 3.2 3B Instruct",
            params = "3B", quant = "Q4_K_M", sizeMb = 2020, ctx = "128K",
            minRamMb = 4096, license = "Llama 3.2 Community",
            blurb = "A capable mid-size model for higher-quality writing and reasoning. " +
                    "Best on phones with 6 GB+ RAM.",
            useCase = "High-quality writing and rewriting, RAG answers, and multi-step reasoning on 6 GB+ phones.",
            simpleName = "Pro Assistant",
            simpleTagline = "Higher-quality writing and thinking. Best on newer phones.",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf?download=true",
        ),
        ModelSpec(
            id = "phi-3.5-mini-instruct",
            name = "Phi-3.5 mini Instruct",
            params = "3.8B", quant = "Q4_K_M", sizeMb = 2390, ctx = "128K",
            minRamMb = 5120, license = "MIT",
            blurb = "Microsoft's strong small model — excellent at reasoning, code and math. " +
                    "The heaviest here; flagship phones only.",
            useCase = "Coding assistance, math, and complex reasoning where quality matters most (flagship devices).",
            simpleName = "Expert Assistant",
            simpleTagline = "Best for tricky questions, math, and coding help. Powerful phones only.",
            url = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf?download=true",
        ),
    )

    fun byId(id: String?): ModelSpec? = models.firstOrNull { it.id == id }

    /**
     * The model to suggest by default for a device with [ramMb] total RAM: the
     * largest one that comfortably fits (using ~half of RAM as a safe budget), or
     * the smallest model if nothing fits. Falls back to a balanced pick when RAM
     * is unknown.
     */
    fun recommendedFor(ramMb: Int): ModelSpec {
        if (ramMb <= 0) return byId("llama-3.2-1b-instruct") ?: models.first()
        val budget = (ramMb * 0.5).toInt()
        return models.filter { it.minRamMb <= budget }.maxByOrNull { it.minRamMb }
            ?: models.minByOrNull { it.minRamMb }!!
    }

    /** A plain-language one-word speed/capability hint for the simple UI. */
    fun hintFor(spec: ModelSpec): String = when {
        spec.sizeMb < 600 -> "Fastest"
        spec.sizeMb < 1000 -> "Fast"
        spec.sizeMb < 1500 -> "Balanced"
        spec.sizeMb < 2200 -> "Smarter"
        else -> "Most capable"
    }
}

/