package com.buukle.agent.runtime.kernel.runner.loop;

/**
 * Source of the run loop limit:
 * <ul>
 *     <li>{@link #PINNED}: on resume, reuse the limit already pinned for this run ({@code agent_run.loop_limit}, since V88);</li>
 *     <li>{@link #INSTANCE}: instance-level {@code maxLoopCount} override (applies to both main and sub loops, highest priority);</li>
 *     <li>{@link #TASK}: raised limit for task-triggered runs (main loop only);</li>
 *     <li>{@link #CHAT}: system config fallback (main loop runner.max-loop-count, sub loop delegate.max-sub-loop-count).</li>
 * </ul>
 *
 * <p>{@link #PINNED} must come first: a run has several re-entry points (wait resume / clarification resume / reconciliation rescue all run on
 * the scheduler thread and cannot see {@code TaskLoopLimitHolder}); without pinning, the limit would shrink midway through the same run.
 */
public enum LoopLimitSource {
    PINNED,
    INSTANCE,
    TASK,
    CHAT
}