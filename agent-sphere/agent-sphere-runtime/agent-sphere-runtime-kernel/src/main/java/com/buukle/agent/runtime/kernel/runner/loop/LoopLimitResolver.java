package com.buukle.agent.runtime.kernel.runner.loop;

/**
 * Run loop limit resolution:
 * <ul>
 *     <li>{@link #resolveMain}: main loop, priority pinned {@code PINNED} &gt; instance {@code INSTANCE} &gt;
 *         task {@code TASK} &gt; system {@code CHAT};</li>
 *     <li>{@link #resolveSub}: sub-agent loop, priority instance {@code INSTANCE} &gt; system {@code CHAT}
 *         (no task raise, and <b>no pinning needed</b>).</li>
 * </ul>
 * An instance override {@code >0} counts as configured (0 / null means unconfigured); the same applies to a pinned value ({@code loop_limit} null means not recorded).
 *
 * <p><b>Why the main loop needs PINNED but the sub loop does not</b>: a main run re-enters from several entry points — only the first time is it on the task
 * request thread (holding {@code TaskLoopLimitHolder}, able to get the TASK raise); wait resume / clarification resume /
 * reconciliation rescue all run on the scheduler thread, and without pinning the limit would fall back to the smaller CHAT value, showing up as "the same run's budget shrinking midway".
 * A sub-agent, by contrast, executes <b>once synchronously</b>: {@code SessionSubRunner} is a plain for loop and every exit path
 * calls {@code finishSubAgentRun} exactly once; there is no suspend/resume, so the limit cannot drift within one call —
 * pinning it would only add a parameter that is always null and a dead column.
 */
public final class LoopLimitResolver {

    private LoopLimitResolver() {
    }

    public record ResolvedLoopLimit(int limit, LoopLimitSource source) {
    }

    /** Main loop: the pinned value wins; otherwise resolve as instance &gt; task &gt; system. */
    public static ResolvedLoopLimit resolveMain(Integer pinnedLimit, Integer instanceLoopLimit,
                                                 Integer taskLoopLimit, int defaultLimit) {
        if (pinnedLimit != null && pinnedLimit > 0) {
            return new ResolvedLoopLimit(pinnedLimit, LoopLimitSource.PINNED);
        }
        if (instanceLoopLimit != null && instanceLoopLimit > 0) {
            return new ResolvedLoopLimit(instanceLoopLimit, LoopLimitSource.INSTANCE);
        }
        if (taskLoopLimit != null && taskLoopLimit > 0) {
            return new ResolvedLoopLimit(taskLoopLimit, LoopLimitSource.TASK);
        }
        return new ResolvedLoopLimit(defaultLimit, LoopLimitSource.CHAT);
    }

    /** Sub loop: resolve as instance &gt; system; no task raise and no pinning (no re-entry path). */
    public static ResolvedLoopLimit resolveSub(Integer instanceLoopLimit, int defaultLimit) {
        if (instanceLoopLimit != null && instanceLoopLimit > 0) {
            return new ResolvedLoopLimit(instanceLoopLimit, LoopLimitSource.INSTANCE);
        }
        return new ResolvedLoopLimit(defaultLimit, LoopLimitSource.CHAT);
    }
}
