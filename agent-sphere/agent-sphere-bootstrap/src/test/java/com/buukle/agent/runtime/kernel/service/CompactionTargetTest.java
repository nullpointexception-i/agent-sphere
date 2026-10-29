package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionTargetTest {

    private static AgentRuntimeProperties.RunnerConfig.CompactionConfig config() {
        AgentRuntimeProperties.RunnerConfig.CompactionConfig c =
                new AgentRuntimeProperties.RunnerConfig.CompactionConfig();
        c.setTargetRatio(0.25);
        c.setTailRatio(0.25);
        c.setAdaptiveTargetEnabled(true);
        c.setMinTailTokens(2_000);
        return c;
    }

    private static CompactionService service() {
        return new CompactionService(null, null, null, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void largeRouteUsesFixedPlusTailWhenConfiguredTargetIsTooLow() {
        CompactionService.CompactionTarget t = service().resolveCompactionTarget(
                256_000, 230_400, 10_000, 8_000, 4_000, config());
        assertEquals(64_000, t.configuredTarget());
        assertEquals(22_000, t.fixedTokens());
        assertEquals(86_000, t.target());
        assertTrue(t.feasible());
    }

    @Test
    void smallRouteIsCappedBelowBudget() {
        CompactionService.CompactionTarget t = service().resolveCompactionTarget(
                32_000, 28_800, 10_000, 8_000, 4_000, config());
        assertEquals(27_776, t.target());
        assertTrue(t.feasible());
        assertTrue(t.target() < t.budget());
    }

    @Test
    void impossibleTargetIsReportedInfeasible() {
        CompactionService.CompactionTarget t = service().resolveCompactionTarget(
                32_000, 20_000, 10_000, 8_000, 4_000, config());
        assertFalse(t.feasible());
        assertTrue(t.target() <= t.fixedTokens() + 2_000);
    }

    @Test
    void disabledAdaptiveTargetKeepsConfiguredRatio() {
        AgentRuntimeProperties.RunnerConfig.CompactionConfig c = config();
        c.setAdaptiveTargetEnabled(false);
        CompactionService.CompactionTarget t = service().resolveCompactionTarget(
                256_000, 230_400, 10_000, 8_000, 4_000, c);
        assertEquals(64_000, t.target());
        assertTrue(t.feasible());
    }
}
