package com.buukle.agent.runtime.kernel.contract;

/**
 * The real outcome of one compaction attempt.
 *
 * <p>{@link #COMPLETED} means only "the summary was produced and the cursor advanced"; only in that case should the caller reassemble the history.
 * The old implementation treated "compaction was triggered" as success, so it kept retrying at the same threshold even with no history / no progress / failure.
 */
public enum CompactionOutcome {
    /** Summary persisted, cursor advanced. */
    COMPLETED,
    /** No finished history to compact (for example only the current run). */
    NO_HISTORY,
    /** There is history but nothing was written (cannot trim, or the model produced no output). */
    NO_PROGRESS,
    /** The compaction call or persistence failed. */
    FAILED
}
