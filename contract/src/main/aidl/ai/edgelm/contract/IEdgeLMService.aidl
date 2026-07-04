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
     * @param prompt the user prompt
     * @param callback streaming sink
     * @return requestId used to cancel
     */
    long submit(String model, String prompt, ITokenCallback callback);

    /** Cancel an in-flight request; frees the decode loop and its KV. */
    void cancel(long requestId);

    /** Which models are currently loaded/warm in the shared runtime. */
    String[] warmModels();
}
