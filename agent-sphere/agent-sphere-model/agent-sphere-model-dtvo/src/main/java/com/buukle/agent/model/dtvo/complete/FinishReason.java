package com.buukle.agent.model.dtvo.complete;

/**
 * Termination reason of a streaming response (the OpenAI-compatible {@code finish_reason}).
 *
 * <p>Lives in model-dtvo: the provider side (model-service) parses and passes it through, while the
 * consumer side (SessionRunner in runtime-kernel) makes the decision. Both need it, and model-service
 * does not depend on kernel.
 *
 * <p><b>Why the distinction matters</b>: previously every stream end reported {@code stop}, so
 * {@link #LENGTH} (truncated by max_tokens) and {@link #CONTENT_FILTER} (blocked by a safety policy)
 * could not be distinguished from "the model finished on its own" — a truncated answer was treated as
 * a normal completion and the run was marked COMPLETED, a silent failure.
 */
public final class FinishReason {

    /** The model ended the turn on its own (normal completion). */
    public static final String STOP = "stop";

    /** Output was truncated by max_tokens: the content is incomplete and must not count as completion. */
    public static final String LENGTH = "length";

    /** Output was blocked by a safety policy: likewise not a completion. */
    public static final String CONTENT_FILTER = "content_filter";

    /** The turn ended with tool calls (a normal tool turn). */
    public static final String TOOL_CALLS = "tool_calls";

    private FinishReason() {
    }
}
