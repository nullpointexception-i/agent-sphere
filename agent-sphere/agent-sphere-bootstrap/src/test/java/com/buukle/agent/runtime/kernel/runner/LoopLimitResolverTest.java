package com.buukle.agent.runtime.kernel.runner;

import com.buukle.agent.runtime.kernel.runner.LoopLimitResolver.ResolvedLoopLimit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Priority guard for loop-limit resolution, focused on {@link LoopLimitSource#PINNED}.
 *
 * <p>Background: the limit was originally <b>re-resolved on every loop entry</b>, and the main run has 5 re-entry points
 * (initial startRun / wait resume / clarification resume ×2 / reconciler rescue), and only the first runs on the task request thread
 * holding {@code TaskLoopLimitHolder}. On resume, the scheduling thread cannot see the TASK layer and falls back to the smaller
 * CHAT value — in production this showed up as "the same run\'s budget shrank mid-way", and tasks were cut short by an earlier loop cap.
 */
class LoopLimitResolverTest {

    private static final int CHAT_LIMIT = 128;
    private static final int TASK_LIMIT = 2000;
    private static final int INSTANCE_LIMIT = 50;

    @Test
    void noConfig_fallsBackToChat() {
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(null, null, null, CHAT_LIMIT);

        assertEquals(CHAT_LIMIT, r.limit());
        assertEquals(LoopLimitSource.CHAT, r.source());
    }

    @Test
    void taskLimitWinsOverChat() {
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(null, null, TASK_LIMIT, CHAT_LIMIT);

        assertEquals(TASK_LIMIT, r.limit());
        assertEquals(LoopLimitSource.TASK, r.source());
    }

    @Test
    void instanceLimitWinsOverTask() {
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(null, INSTANCE_LIMIT, TASK_LIMIT, CHAT_LIMIT);

        assertEquals(INSTANCE_LIMIT, r.limit());
        assertEquals(LoopLimitSource.INSTANCE, r.source());
    }

    // ---------- PINNED（V88） ----------

    @Test
    void pinnedLimitWinsOverEverything() {
        // Core guard: on resume the budget must not drift even if instance/task/system all changed
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(
                INSTANCE_LIMIT, INSTANCE_LIMIT, TASK_LIMIT, CHAT_LIMIT);

        assertEquals(INSTANCE_LIMIT, r.limit());
        assertEquals(LoopLimitSource.PINNED, r.source());
    }

    @Test
    void pinnedLimitSurvivesMissingTaskContextOnSchedulerThread() {
        // Wait resume runs on the pending-wait-wake scheduling thread, where TaskLoopLimitHolder is null.
        // Without pinning this falls back to 128; with pinning it must still use 2000.
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(TASK_LIMIT, null, null, CHAT_LIMIT);

        assertEquals(TASK_LIMIT, r.limit(),
                "续跑时 TaskLoopLimitHolder 为 null 也必须沿用固化值，否则预算中途缩水");
    }

    @Test
    void pinnedLimitSurvivesInstanceConfigEditedMidRun() {
        // Lower the instance limit from 2000 to 5 mid-run: the same run's budget must not change
        ResolvedLoopLimit r = LoopLimitResolver.resolveMain(2000, 5, TASK_LIMIT, CHAT_LIMIT);

        assertEquals(2000, r.limit(), "运行中改实例配置不得让已固化 run 的预算漂移");
    }

    @Test
    void zeroOrNegativePinnedIsTreatedAsUnset() {
        // Legacy runs have a NULL loop_limit; 0/negative values must also be treated as unrecorded rather than zeroing the budget
        assertEquals(TASK_LIMIT,
                LoopLimitResolver.resolveMain(0, null, TASK_LIMIT, CHAT_LIMIT).limit());
        assertEquals(TASK_LIMIT,
                LoopLimitResolver.resolveMain(-1, null, TASK_LIMIT, CHAT_LIMIT).limit());
        assertEquals(LoopLimitSource.TASK,
                LoopLimitResolver.resolveMain(0, null, TASK_LIMIT, CHAT_LIMIT).source());
    }

    // ---------- Sub-loops: explicitly excluded from task limit boosts and needing no pinning ----------

    @Test
    void subLoop_neverUsesTaskLimit() {
        ResolvedLoopLimit r = LoopLimitResolver.resolveSub(null, 8);

        assertEquals(8, r.limit());
        assertEquals(LoopLimitSource.CHAT, r.source());
    }

    @Test
    void subLoop_usesInstanceOverride() {
        ResolvedLoopLimit r = LoopLimitResolver.resolveSub(INSTANCE_LIMIT, 8);

        assertEquals(INSTANCE_LIMIT, r.limit());
        assertEquals(LoopLimitSource.INSTANCE, r.source());
    }
}
