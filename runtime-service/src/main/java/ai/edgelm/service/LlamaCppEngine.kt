package ai.edgelm.service

import ai.edgelm.runtime.ModelSpec

/**
 * EdgeLM's universal CPU engine (plus the shelved Vulkan path): llama.cpp reached
 * through [NativeBridge]. [Session] simply boxes the opaque native handle.
 *
 * This wraps the existing JNI verbatim — no behavior change vs. calling NativeBridge
 * directly — so it's the safe default and the permanent fallback for devices without a
 * supported GPU/NPU. A future LiteRtEngine implements the same [InferenceEngine].
 */
class LlamaCppEngine : InferenceEngine {

    override val id: String = "llama.cpp"

    // GGUF is the llama.cpp artifact format; the CPU engine runs on any device (universal
    // fallback). Vulkan/GPU selection within llama.cpp is decided at load time by the
    // native backend probe, not here.
    override fun supports(spec: ModelSpec): Boolean = spec.format == "gguf"
    override fun canRunOn(device: DeviceProfile): Boolean = true

    override fun artifactFor(spec: ModelSpec): ModelArtifact? =
        if (spec.url.isNotEmpty()) ModelArtifact(spec.url, "gguf") else null

    /** Boxes the native handle returned by [NativeBridge.loadModel]. */
    private class Handle(val value: Long) : InferenceEngine.Session

    private fun InferenceEngine.Session.native(): Long = (this as Handle).value

    override fun load(path: String): InferenceEngine.Session? {
        val h = NativeBridge.loadModel(path)
        return if (h != 0L) Handle(h) else null
    }

    // engineLabel() reflects the backend chosen at the last loadModel ("CPU" / "GPU · <device>").
    // Prefix the engine name so the UI clearly shows which ENGINE won (vs. "LiteRT-LM · GPU").
    override fun label(session: InferenceEngine.Session): String = "llama.cpp · " + NativeBridge.engineLabel()

    override fun attachDraft(session: InferenceEngine.Session, draftPath: String): Boolean =
        NativeBridge.attachDraft(session.native(), draftPath)

    override fun generate(
        session: InferenceEngine.Session,
        sessionId: String,
        prompt: String,
        sink: InferenceEngine.TokenSink,
    ): Int = NativeBridge.generate(
        session.native(), sessionId, prompt,
        object : NativeBridge.TokenSink {
            override fun onChunk(text: String) = sink.onChunk(text)
            override fun isCancelled(): Boolean = sink.isCancelled()
        },
    )

    override fun cancel(session: InferenceEngine.Session) =
        NativeBridge.cancel(session.native())

    override fun unload(session: InferenceEngine.Session) =
        NativeBridge.unloadModel(session.native())
}
