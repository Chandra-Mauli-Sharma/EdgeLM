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

    /** munmap + free. */
    external fun unloadModel(handle: Long)

    /** Called from C++ to deliver tokens and check for cancellation. */
    interface TokenSink {
        fun onChunk(text: String)
        fun isCancelled(): Boolean
    }
}
