package com.buukle.agent.runtime.kernel.contract;

public enum TurnOutcome {
    COMPLETE,
    TOOL_CALLS,
    COMPACTED,
    /** The current run's run-local context was compacted; the caller replaces the in-memory messages and continues. */
    RUN_COMPACTED,
    ERROR,
    /** Transient LLM failure: this turn suspends and the same turn is re-run when due (V115). */
    RETRY_LATER,
    CANCELLED
}
