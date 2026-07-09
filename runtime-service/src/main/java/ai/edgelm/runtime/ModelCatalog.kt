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
    val useCase: String,   // recommended "best for" one-liner
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
            url = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf?download=true",
        ),
    )

    fun byId(id: String?): ModelSpec? = models.firstOrNull { it.id == id }
}

/**
 * Multi-model on-disk storage. Downloaded models live at files/models/<id>.gguf;
 * files/active_model holds the id the engine should load. Both the app process
 * (picker UI) and the :core service process share filesDir (same app UID), so the
 * service just re-reads the pointer on reloadModel().
 */
object ModelStore {
    private fun modelsDir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    private fun pointer(ctx: Context) = File(ctx.filesDir, "active_model")

    fun fileFor(ctx: Context, id: String) = File(modelsDir(ctx), "$id.gguf")
    fun isInstalled(ctx: Context, id: String) = fileFor(ctx, id).exists()

    fun installedIds(ctx: Context): Set<String> =
        modelsDir(ctx).listFiles()?.filter { it.extension == "gguf" }
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
            val f = fileFor(ctx, id)
            if (f.exists()) return f.absolutePath
        }
        val legacy = File(ctx.filesDir, "model.gguf")
        return if (legacy.exists()) legacy.absolutePath else ""
    }

    /** Remove an installed model; if it was active, clear the pointer. */
    fun remove(ctx: Context, id: String) {
        fileFor(ctx, id).delete()
        if (activeId(ctx) == id) clearActive(ctx)
    }
}
