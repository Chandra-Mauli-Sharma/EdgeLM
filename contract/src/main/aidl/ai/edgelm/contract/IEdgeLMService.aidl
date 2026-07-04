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
}
