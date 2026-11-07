package com.buukle.agent.runtime.kernel.runner.compaction;

import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.contract.CompactionOutcome;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-run compaction bookkeeping: retry budget, consecutive-failure disable switch and
 * run-local counters.
 *
 * <p>Extracted so the loop only asks "should history be reassembled?" and never owns the
 * compaction policy itself.
 */
@Slf4j
public final class CompactionTracker {

    private int retries;
    private int consecutiveFailures;
    private int runAttempts;
    private int runCount;
    private int lastRunLoop = -1000;
    private boolean disabled;
    private boolean skip;

    public CompactionTracker(int runCount) {
        this.runCount = runCount;
    }

    /** @return true when history should be reassembled; false when the turn must be sent as-is. */
    public boolean onCompacted(CompactionOutcome outcome, Long runId) {
        if (outcome != CompactionOutcome.COMPLETED) {
            retries++;
            consecutiveFailures++;
            if (consecutiveFailures >= RunnerConstants.MAX_CONSECUTIVE_COMPACTION_FAILURES) {
                disabled = true;
            }
            log.warn("Compaction did not make progress (outcome={}, attempts={}, consecutiveFailures={}, disabled={}) for run {}; sending oversized context",
                    outcome, retries, consecutiveFailures, disabled, runId);
            skip = true;
            return false;
        }
        consecutiveFailures = 0;
        return true;
    }

    public void onRunCompactedWithoutMessages(Long runId) {
        consecutiveFailures++;
        if (consecutiveFailures >= RunnerConstants.MAX_CONSECUTIVE_COMPACTION_FAILURES) {
            disabled = true;
        }
        log.warn("Run compaction returned no rebuilt messages for run {} (consecutiveFailures={}, disabled={}); sending as-is",
                runId, consecutiveFailures, disabled);
        skip = true;
    }

    public void onRunCompacted(int loopCount, Long runId, int messageCount) {
        consecutiveFailures = 0;
        runAttempts++;
        runCount++;
        lastRunLoop = loopCount;
        log.info("Run compaction applied for run {} (attempt {}, runCount {}): messages={}",
                runId, runAttempts, runCount, messageCount);
    }

    public void resetForToolLoop() {
        skip = disabled;
        retries = 0;
    }

    public int retries() {
        return retries;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public int runAttempts() {
        return runAttempts;
    }

    public int runCount() {
        return runCount;
    }

    public int lastRunLoop() {
        return lastRunLoop;
    }

    public boolean disabled() {
        return disabled;
    }

    public boolean skip() {
        return skip;
    }
}
