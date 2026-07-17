package ai.edgelm.service

import android.content.Context
import ai.edgelm.runtime.ModelSpec

/**
 * Picks the inference engine for a given model + device (docs/ENGINE-ABSTRACTION.md).
 *
 * LiteRT ships live in all builds. It's preferred where it both fits the device and has a
 * `.litertlm` artifact; [LlamaCppEngine] is the universal CPU fallback. LiteRT is kept from
 * running where it can't via the SoC allowlist + learned GPU verdict (LiteRtEngine.canRunOn) and
 * the picker filter (ModelCatalog.visibleModels).
 */
object EngineRegistry {
    private val llamaCpp = LlamaCppEngine()
    // Context-less until init(); the GPU engine needs an application Context for its cache dir.
    @Volatile private var liteRt: LiteRtEngine = LiteRtEngine()

    /** Attach an application Context so the LiteRT engine can reach a cache dir. Call once at
     *  startup (EdgeLMService.onCreate) and before enqueuing downloads. */
    fun init(context: Context) {
        liteRt = LiteRtEngine(context.applicationContext)
    }

    // Best-first: LiteRT (where usable) is preferred; llama.cpp is the universal CPU fallback.
    private fun preference(): List<InferenceEngine> = listOf(liteRt, llamaCpp)

    /** The engine to use for [spec] on [device]; never null (falls back to llama.cpp). */
    fun select(spec: ModelSpec?, device: DeviceProfile): InferenceEngine {
        if (spec == null) return llamaCpp
        return preference().firstOrNull { it.canRunOn(device) && it.supports(spec) } ?: llamaCpp
    }

    /** The always-available CPU engine, for callers that just need a default. */
    fun fallback(): InferenceEngine = llamaCpp
}
