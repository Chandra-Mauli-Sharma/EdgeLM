package ai.edgelm.contract;

import ai.edgelm.contract.ITokenCallback;

/**
 * The Phase 0 Binder contract for the shared EdgeLM runtime.
 *
 * Deliberately tiny: submit a prompt, stream tokens, cancel. No priorities,
 * no permissions, no model management yet — those arrive in Phase 1 once the
 * shared-memory thesis is proven (see docs/PHASE-0-BUILD-PLAN.md).
 */
interface IEdgeLMService {

    /**
     * Enqueue a generation request.
     * @param model  logical model id (Phase 0 ships exactly one; value is advisory)
     * @param sessionId conversation id for multi-turn KV reuse; "" = stateless one-shot.
     *                  Same id on a later call continues that conversation without
     *                  re-prefilling its history (see PHASE1-KV-POOLING.md).
     * @param prompt the user prompt (just the new turn)
     * @param priority scheduling class: 3=foreground, 2=interactive, 1=batch, 0=background.
     *                 The engine admits higher-priority requests first (with aging).
     * @param callback streaming sink
     * @return requestId used to cancel
     */
    long submit(String model, String sessionId, String prompt, int priority, ITokenCallback callback);

    /** Cancel an in-flight request; frees the decode loop and its KV. */
    void cancel(long requestId);

    /** Which models are currently loaded/warm in the shared runtime. */
    String[] warmModels();

    /** (Re)load model.gguf from the app's files dir (after it's been downloaded).
     *  Returns true if a model is now loaded. Appended at the end for ABI stability. */
    boolean reloadModel();

    /** Unload the model now to reclaim RAM (it lazily reloads on the next request).
     *  Returns true if the runtime is now idle (no model resident). Appended at the
     *  end for ABI stability — never reorder methods above this. */
    boolean unloadModel();

    /**
     * Ensure the active model is loaded and warm, running the one-time CPU-vs-GPU
     * backend probe on first load. Blocks until ready (call off the UI thread).
     * @return a short engine label for display, e.g. "CPU" or "GPU · Mali-G615 MC6",
     *         or "" if no model is available. Appended at the end for ABI stability.
     */
    String prepareEngine();
}
