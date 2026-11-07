package com.buukle.agent.runtime.orchestration.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reasoning-effort resolution: config priority + dynamic default level + Qwen thinking budget.
 *
 * <p>Provider support ranges live in application.yml; this assembles the same defaults to avoid depending on Spring binding.
 */
class ReasoningEffortResolverTest {

    private ReasoningEffortResolver resolver;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        Map<String, AgentRuntimeProperties.LlmConfig.ProviderReasoningConfig> providers =
                properties.getLlm().getReasoning().getProviders();
        providers.put(ModelProviderCompany.DEEPSEEK.getValue(),
                provider("none", "low", "high", "max"));
        providers.put(ModelProviderCompany.OPENAI.getValue(),
                provider("minimal", "low", "medium", "high"));
        providers.put(ModelProviderCompany.GLM.getValue(),
                provider("low", "high", "max"));
        providers.put(ModelProviderCompany.QWEN.getValue(),
                provider("none", "low", "medium", "high", "max"));
        resolver = new ReasoningEffortResolver(properties);
    }

    private static AgentRuntimeProperties.LlmConfig.ProviderReasoningConfig provider(
            String... levels) {
        AgentRuntimeProperties.LlmConfig.ProviderReasoningConfig cfg =
                new AgentRuntimeProperties.LlmConfig.ProviderReasoningConfig();
        cfg.setSupported(true);
        cfg.setLevels(new ArrayList<>(List.of(levels)));
        return cfg;
    }

    private ModelRouteFullVO route(String company) {
        ModelRouteFullVO route = new ModelRouteFullVO();
        route.setCompany(company);
        route.setModelName("test-model");
        return route;
    }

    @Test
    void noOutputCap_selectsHighestLevel() {
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("deepseek"), null, null, null);

        assertTrue(resolved.supported());
        assertEquals("high", resolved.defaultLevel(), "自动默认封顶 high");
        assertEquals("high", resolved.selectedLevel());
        assertEquals("high", resolved.effectiveLevel());
        assertNull(resolved.answerReserveTokens());
    }

    @Test
    void dynamicDefault_picksHighestFeasibleLevel() {
        // 16000 output cap: reserve 4000 for the answer; deepseek supports [low, high, max], and neither high nor max is feasible.
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("deepseek"), null, 16000, null);

        assertTrue(resolved.supported());
        assertEquals(4000L, resolved.answerReserveTokens());
        assertEquals("low", resolved.defaultLevel());
        assertEquals("low", resolved.selectedLevel());
        assertTrue(level(resolved, "low").feasible());
        assertFalse(level(resolved, "high").feasible());
    }

    @Test
    void dynamicDefault_mediumFitsOpenAiLevels() {
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("openai"), null, 16000, null);

        assertEquals("medium", resolved.defaultLevel());
        assertTrue(level(resolved, "medium").feasible());
        assertFalse(level(resolved, "high").feasible());
    }

    @Test
    void infeasibleRequestedLevel_isKeptWithWarning() {
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("deepseek"), null, 16000, "max");

        assertEquals("max", resolved.selectedLevel(), "手选档位保留，前端只警告");
        assertFalse(level(resolved, "max").feasible());
        assertEquals("low", resolved.effectiveLevel(), "本轮实际使用最高可行档");
    }

    @Test
    void defaultIsCappedAtHighEvenWhenMaxIsFeasible() {
        // route 4: 384k nominal output, max is feasible too; the automatic default still caps at high.
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("deepseek"), null, 384_000, null);

        assertEquals("high", resolved.defaultLevel());
        assertTrue(level(resolved, "max").feasible(), "用户仍可手选 max");
        assertEquals("high", resolved.effectiveLevel());
    }

    @Test
    void explicitMax_isHonoredWhenFeasible() {
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("deepseek"), null, 384_000, "max");

        assertEquals("max", resolved.selectedLevel());
        assertEquals("max", resolved.effectiveLevel());
    }

    @Test
    void unknownRequestedLevel_fallsBackToDefault() {
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("deepseek"), null, 16000, "bogus");

        assertEquals("low", resolved.selectedLevel());
    }

    @Test
    void unsupportedProvider_returnsUnsupported() {
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("unknown-provider"), null, 16000, null);

        assertFalse(resolved.supported());
        assertTrue(resolved.levels().isEmpty());
    }

    @Test
    void routeOverride_disablesProviderSupport() {
        ModelRouteFullVO route = route("deepseek");
        route.setReasoningSupported(false);

        assertFalse(resolver.resolve(route, null, 16000, null).supported());
    }

    @Test
    void routeOverride_replacesProviderLevels() {
        ModelRouteFullVO route = route("deepseek");
        route.setReasoningLevels("[\"medium\",\"high\"]");

        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route, null, 16000, null);

        assertEquals(2, resolved.levels().size());
        assertEquals("medium", resolved.levels().get(0).value());
        assertNull(level(resolved, "low"));
    }

    @Test
    void providerConfigJson_overridesYamlThresholds() {
        // Lower the high threshold to 8000; with a 16000 cap, high becomes feasible.
        String providerConfig = "{\"reasoning\":{\"levelOverrides\":{\"high\":8000}}}";

        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("deepseek"), providerConfig, 16000, null);

        assertEquals("high", resolved.defaultLevel());
        assertTrue(level(resolved, "high").feasible());
    }

    @Test
    void routeAnswerReserveRatio_overridesGlobal() {
        ModelRouteFullVO route = route("deepseek");
        route.setReasoningAnswerReserveRatio(new java.math.BigDecimal("0.05"));

        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route, null, 16000, null);

        // max(minAnswerTokens=1024, 16000*0.05=800) = 1024
        assertEquals(1024L, resolved.answerReserveTokens());
        assertFalse(level(resolved, "high").feasible());
    }

    @Test
    void qwenLevel_mapsToThinkingBudgetWithinOutputLimit() {
        // 24000 output cap: high is feasible (16384 + 6000 ≤ 24000); budget = the level threshold 16384.
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("qwen"), null, 24_000, "high");

        assertEquals("high", resolved.selectedLevel());
        assertEquals("high", resolved.effectiveLevel());
        assertEquals(16384, resolved.thinkingBudgetTokens());
    }

    @Test
    void infeasibleHigh_downgradesPerTurnAndShrinksQwenBudget() {
        // 16000 output cap: high is infeasible → this turn drops to medium, with budget = medium threshold 8192.
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("qwen"), null, 16_000, "high");

        assertEquals("high", resolved.selectedLevel());
        assertEquals("medium", resolved.effectiveLevel());
        assertEquals(8192, resolved.thinkingBudgetTokens());
    }

    @Test
    void qwenNone_hasNoThinkingBudget() {
        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("qwen"), null, 16000, "none");

        assertEquals("none", resolved.selectedLevel());
        assertNull(resolved.thinkingBudgetTokens());
    }

    @Test
    void qwenGatewayWithoutBudget_sendsSwitchOnly() {
        String providerConfig = "{\"reasoning\":{\"thinkingBudgetSupported\":false}}";

        ReasoningEffortResolver.Resolved resolved =
                resolver.resolve(route("qwen"), providerConfig, 16000, "high");

        assertEquals("high", resolved.selectedLevel());
        assertNull(resolved.thinkingBudgetTokens());
    }

    private static ReasoningEffortResolver.LevelView level(
            ReasoningEffortResolver.Resolved resolved, String value) {
        return resolved.levels().stream()
                .filter(v -> v.value().equals(value))
                .findFirst()
                .orElse(null);
    }
}
