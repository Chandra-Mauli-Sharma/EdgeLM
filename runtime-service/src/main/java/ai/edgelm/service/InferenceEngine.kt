package ai.edgelm.service

import ai.edgelm.runtime.ModelSpec

/**
 * One pluggable inference backend. See docs/ENGINE-ABSTRACTION.md.
 *
 * The service owns exactly one live [Session] at a time and treats it as opaque —
 * exactly as it used to treat the raw native handle. Swapping engines (llama.cpp on
 * CPU today; LiteRT-LM on GPU/NPU later) changes nothing above this seam and nothing
 * on the app-facing AIDL contract; the shared-runtime thesis lives one layer up.
 *
 * Phase A ships a single engine ([LlamaCppEngine]). Device-based engine *routing*
 * (the `canRunOn` / `supports` selection logic in the design note) arrives in Phase C.
 */
interface InferenceEngine {

    /** Stable engine id for logs / the engine label, e.g. "llama.cpp" | "litert-lm". */
    val id: String

    /** True if this engine can load [spec]'s artifact (format/quant). Used for routing. */
    fun supports(spec: ModelSpec): Boolean

    /** True if [device] meets this engine's hardware/ABI requirements. Used for routing. */
    fun canRunOn(device: DeviceProfile): Boolean

    /** The artifact (download URL + on-disk format) this engine loads for [spec], or null if
     *  it has none. Keeps model downloading in lock-step with engine routing — the same engine
     *  the registry picks to *run* a model is the one that decides which file to *fetch*. */
    fun artifactFor(spec: ModelSpec): ModelArtifact?

    /** Load a model file into a fresh session; returns null on failure. */
    fun load(path: String): Session?

    /** Backend label for [session], e.g. "CPU" or "GPU · <device>". */
    fun label(session: Session): String

    /** Attach a same-tokenizer draft for speculative decoding.
     *  Returns true if attached; false if unsupported by this engine or it failed. */
    fun attachDraft(session: Session, draftPath: String): Boolean

    /** Run one generation, streaming into [sink]; returns tokens produced.
     *  Blocking — call off the main thread. */
    fun generate(session: Session, sessionId: String, prompt: String, sink: TokenSink): Int

    /** Cooperative cancel of the in-flight generation on [session]. */
    fun cancel(session: Session)

    /** Free [session]'s native resources. */
    fun unload(session: Session)

    /** Opaque handle to one loaded model on a given engine. */
    interface Session

    /** Streaming sink + cancellation check, invoked from the engine's decode thread. */
    interface TokenSink {
        fun onChunk(text: String)
        fun isCancelled(): Boolean
    }
}

/** A downloadable model artifact: the source [url] and the on-disk [format] (also the file
 *  extension, e.g. "gguf" | "litertlm"). Different engines load different artifacts of the
 *  same logical model. */
data class ModelArtifact(val url: String, val format: String)
