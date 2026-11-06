package com.buukle.agent.model.dtvo.complete;

/**
 * Transport-level statistics for one logical streaming call.
 *
 * <p>Unlike router fallback / LLM backoff attempts, these counters describe retries
 * performed inside the provider client for connection/TLS/EOF failures before any
 * chunk was received. They are persisted for latency diagnosis.
 */
public record LlmStreamStats(
        int attempts,
        int retryCount,
        boolean handshakeFailed,
        String lastErrorType,
        String lastErrorMessage
) {

    public static LlmStreamStats firstAttempt() {
        return new LlmStreamStats(1, 0, false, null, null);
    }

    public static LlmStreamStats empty() {
        return new LlmStreamStats(0, 0, false, null, null);
    }
}
