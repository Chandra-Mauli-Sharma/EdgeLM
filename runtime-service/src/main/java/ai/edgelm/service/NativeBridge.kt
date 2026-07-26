package ai.edgelm.service

/**
 * JNI boundary to the native inference core.
 *
 * Phase 0 the native side mmaps the model file and streams placeholder tokens
 * (enough to run the shared-memory experiment). Wiring the real llama.cpp
 * decode loop behind [generate] is Week 1 of the plan and changes nothing above
 * this line — that's the point of the boundary.
 */
object NativeBridge {

    init { System.loadLibrary("edgelm") }

    /** mmap the GGUF at [path]; returns an opaque handle (0 on failure). */
    external fun loadModel(path: String): Long

    /**
     * Run generation for [handle]+[prompt], pushing chunks into [sink].
     * [sessionId] "" = stateless; a stable id continues that conversation's KV.
     * Returns the number of tokens produced. Blocking; call off the main thread.
     */
    external fun generate(handle: Long, sessionId: String, prompt: String, sink: TokenSink): Int

    /** Cooperative cancel of the in-flight generation on [handle]. */
    external fun cancel(handle: Long)

    /** Set the system prompt for [handle] (OpenAI system message). "" restores the default.
     *  Changing it re-prefills on the next generate. Serialize with generate. */
    external fun setSystemPrompt(handle: Long, system: String)

    /** Constrain the next generation to a GBNF [grammar] (guaranteed well-formed output);
     *  "" clears it. Serialize with generate. */
    external fun setGrammar(handle: Long, grammar: String)

    /** munmap + free. */
    external fun unloadModel(handle: Long)

    /** Label of the backend chosen at the last [loadModel] ("CPU" / "GPU · <device>"). */
    external fun engineLabel(): String

    /** Attach a small same-tokenizer draft model to [handle] for speculative decoding.
     *  Returns true if the draft loaded. Call after [loadModel], before generating. */
    external fun attachDraft(handle: Long, draftPath: String): Boolean

    // ---- Embeddings (Phase 2) -------------------------------------------------

    /** Load a model in embedding mode (mean-pooled). Returns a handle, or 0 on failure.
     *  Free with [unloadModel]. Distinct from a chat [loadModel] handle. */
    external fun loadEmbeddingModel(path: String): Long

    /** Embedding dimension (n_embd) of an embedding [handle], or 0. */
    external fun embedDim(handle: Long): Int

    /** L2-normalized embedding of [text], or null on failure. Blocking; call off-main. */
    external fun embed(handle: Long, text: String): FloatArray?

    // ---- Vision / multimodal (Phase 2, -DEDGELM_VISION=ON) --------------------

    /** Load an LLM + its mmproj projector as a vision model. Returns a handle, or 0. */
    external fun loadVisionModel(modelPath: String, mmprojPath: String): Long

    /** Generate a response about [image] (raw jpg/png/... bytes), streaming into [sink].
     *  Returns tokens produced, or 0 if the lib was built without -DEDGELM_VISION. Blocking. */
    external fun visionGenerate(handle: Long, prompt: String, image: ByteArray, sink: TokenSink): Int

    external fun unloadVisionModel(handle: Long)

    /** Called from C++ to deliver tokens and check for cancellation. */
    interface TokenSink {
        fun onChunk(text: String)
        fun isCancelled(): Boolean
    }

    // ---- Increment 2: continuous batching (opt-in, -DEDGELM_BATCHED=ON) --------

    /**
     * Continuous-batching test entry (see docs/PHASE1-KV-POOLING.md). Submits every
     * prompt on its own sequence over ONE shared context and drives a single batched
     * decode loop, streaming each sequence's tokens via [sink] tagged with its index.
     * Shares [handle]'s already-loaded weights — no second copy in RAM. Returns the
     * number of sequences run. Returns 0 if the native lib was built WITHOUT
     * -DEDGELM_BATCHED (default), so this is safe to call either way. Blocking.
     */
    external fun batchedRunTest(handle: Long, prompts: Array<String>, sink: BatchedSink): Int

    /** Per-sequence streaming sink for [batchedRunTest]; [seq] is the prompt index. */
    interface BatchedSink {
        fun onChunk(seq: Int, text: String)
    }

    // ---- Persistent batched runtime (service integration) ---------------------
    // All no-op / 0 unless built with -DEDGELM_BATCHED=ON. Driven by BatchedRuntimeSession.

    /** Create a persistent runtime sharing [modelHandle]'s weights. Returns 0 on failure. */
    external fun batchedCreate(modelHandle: Long, poolSize: Int, nCtxPerSeq: Int): Long

    /** Queue a generation on a sequence for (uid, sessionId). false = pool full / disabled. */
    external fun batchedSubmit(rtHandle: Long, uid: Int, sessionId: String, prompt: String,
                               sink: BatchedRequestSink): Boolean

    /** Advance one batched decode step; returns the number of still-active sequences. */
    external fun batchedStep(rtHandle: Long): Int

    /** Pause/resume a sequence (preemption): a paused seq leaves the batch, KV intact. */
    external fun batchedPause(rtHandle: Long, uid: Int, sessionId: String, paused: Boolean)

    /** Cancel a sequence; it's retired at the next step. */
    external fun batchedCancel(rtHandle: Long, uid: Int, sessionId: String)

    /** Free the runtime and its context. */
    external fun batchedDestroy(rtHandle: Long)

    /** Per-request sink for the persistent path: text chunks, then one onDone(tokenCount). */
    interface BatchedRequestSink {
        fun onChunk(text: String)
        fun onDone(tokens: Int)
        fun isCancelled(): Boolean
    }
}
