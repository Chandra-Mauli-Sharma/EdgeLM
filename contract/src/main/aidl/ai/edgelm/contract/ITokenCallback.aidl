package ai.edgelm.contract;

/**
 * Streaming sink for a single inference request.
 * `oneway` so the runtime never blocks on a slow client while decoding.
 */
oneway interface ITokenCallback {
    /** A chunk of generated text (one or more tokens, batched to limit IPC). */
    void onTokens(String chunk);

    /** Terminal success. tokenCount/elapsedMs let the client compute tokens/sec. */
    void onDone(int tokenCount, long elapsedMs);

    /** Terminal failure. */
    void onError(String message);
}
