package com.buukle.agent.runtime.orchestration.service;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.constant.ReasoningEffort;
import com.buukle.agent.common.constant.ReasoningMode;
import com.buukle.agent.model.dtvo.enums.ModelProviderCompany;
import com.buukle.agent.model.dtvo.vo.ModelRouteFullVO;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统一思考强度解析。
 *
 * <p>配置优先级：路由覆盖 &gt; 厂商数据库配置 &gt; yaml 厂商默认 &gt; yaml 全局默认。
 * 默认档位不写死：根据本次实际输出的 max_tokens 和档位阈值动态计算。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReasoningEffortResolver {

    /** provider.config.reasoning 的字段名。 */
    private static final String PROP_REASONING = "reasoning";
    private static final String PROP_SUPPORTED = "supported";
    private static final String PROP_MODE = "mode";
    private static final String PROP_LEVELS = "levels";
    private static final String PROP_ANSWER_RESERVE_RATIO = "answerReserveRatio";
    private static final String PROP_LEVEL_OVERRIDES = "levelOverrides";
    private static final String PROP_THINKING_BUDGET_SUPPORTED = "thinkingBudgetSupported";

    private final AgentRuntimeProperties properties;

    public record LevelView(String value, long minOutputTokens, boolean feasible) {
    }

    public record Resolved(
            boolean supported,
            String mode,
            List<LevelView> levels,
            String defaultLevel,
            String selectedLevel,
            Long effectiveMaxOutputTokens,
            Long answerReserveTokens,
            Integer thinkingBudgetTokens) {

        public static Resolved unsupported() {
            return new Resolved(false, ReasoningMode.LEVELS, List.of(), null, null, null, null, null);
        }
    }

    /**
     * @param route                   模型路由（含 company / 路由覆盖字段）
     * @param providerConfigJson       agent_model_provider.config
     * @param effectiveMaxOutputTokens 本次请求实际生效的 max_tokens；null 表示未配置输出上限
     * @param requestedLevel           用户选择；为空则使用动态默认
     */
    public Resolved resolve(ModelRouteFullVO route, String providerConfigJson,
                            Integer effectiveMaxOutputTokens, String requestedLevel) {
        if (route == null) {
            return Resolved.unsupported();
        }
        AgentRuntimeProperties.LlmConfig.ReasoningConfig global = properties.getLlm().getReasoning();
        String company = route.getCompany() == null ? "" : route.getCompany().toLowerCase(Locale.ROOT);
        AgentRuntimeProperties.LlmConfig.ProviderReasoningConfig yamlProvider =
                findProvider(global, company);
        JsonNode db = parseReasoning(providerConfigJson);

        Boolean supported = firstNonNull(route.getReasoningSupported(),
                db == null ? null : booleanOrNull(db.get(PROP_SUPPORTED)),
                yamlProvider == null ? null : yamlProvider.isSupported());
        if (supported == null || !supported) {
            return Resolved.unsupported();
        }

        String mode = firstNonBlank(route.getReasoningMode(),
                db == null ? null : textOrNull(db.get(PROP_MODE)),
                yamlProvider == null ? null : yamlProvider.getMode(),
                ReasoningMode.LEVELS);

        List<String> levels = parseLevels(route.getReasoningLevels());
        if (levels.isEmpty() && db != null) {
            levels = jsonArray(db.get(PROP_LEVELS));
        }
        if (levels.isEmpty() && yamlProvider != null && yamlProvider.getLevels() != null) {
            levels = yamlProvider.getLevels();
        }
        if (levels.isEmpty()) {
            levels = global.getDefaultLevels() == null ? List.of() : global.getDefaultLevels();
        }
        // 按全局 levelOrder 排序，去掉未知档位
        List<String> levelOrder = global.getLevelOrder() == null
                ? ReasoningEffort.ORDER : global.getLevelOrder();
        List<String> ordered = new ArrayList<>();
        for (String level : levelOrder) {
            if (levels.stream().anyMatch(l -> l.equalsIgnoreCase(level))) {
                ordered.add(level);
            }
        }
        if (ordered.isEmpty()) {
            ordered.addAll(levels);
        }
        if (ordered.isEmpty()) {
            return Resolved.unsupported();
        }

        long reserve = 0L;
        if (effectiveMaxOutputTokens != null && effectiveMaxOutputTokens > 0) {
            double ratio = firstNonNull(route.getReasoningAnswerReserveRatio() == null
                            ? null : route.getReasoningAnswerReserveRatio().doubleValue(),
                    db == null ? null : doubleOrNull(db.get(PROP_ANSWER_RESERVE_RATIO)),
                    yamlProvider == null ? null : yamlProvider.getAnswerReserveRatio(),
                    global.getAnswerReserveRatio());
            reserve = Math.max(global.getMinAnswerTokens(),
                    Math.round(effectiveMaxOutputTokens * ratio));
        }

        Map<String, Long> thresholds = thresholds(global, yamlProvider, db);
        List<LevelView> views = new ArrayList<>();
        for (String level : ordered) {
            long min = thresholds.getOrDefault(level, 0L);
            boolean feasible = effectiveMaxOutputTokens == null
                    || effectiveMaxOutputTokens <= 0
                    || min + reserve <= effectiveMaxOutputTokens;
            views.add(new LevelView(level, min, feasible));
        }

        String defaultLevel;
        if (effectiveMaxOutputTokens == null || effectiveMaxOutputTokens <= 0) {
            defaultLevel = ordered.get(ordered.size() - 1);
        } else {
            String highestFeasible = null;
            for (LevelView v : views) {
                if (v.feasible()) {
                    highestFeasible = v.value();
                }
            }
            defaultLevel = highestFeasible != null ? highestFeasible : ordered.get(0);
        }

        String selected = requestedLevel;
        if (selected == null || selected.isBlank()
                || ordered.stream().noneMatch(l -> l.equalsIgnoreCase(requestedLevel))) {
            selected = defaultLevel;
        } else {
            // 统一使用全局 levelOrder 中的大小写
            selected = ordered.stream()
                    .filter(l -> l.equalsIgnoreCase(requestedLevel))
                    .findFirst().orElse(defaultLevel);
        }

        Integer thinkingBudget = null;
        if (ModelProviderCompany.QWEN.getValue().equals(company)) {
            thinkingBudget = qwenThinkingBudget(global, yamlProvider, db,
                    selected, effectiveMaxOutputTokens, reserve);
        }

        return new Resolved(true, mode, views, defaultLevel, selected,
                effectiveMaxOutputTokens == null ? null : effectiveMaxOutputTokens.longValue(),
                effectiveMaxOutputTokens == null ? null : reserve, thinkingBudget);
    }

    private Integer qwenThinkingBudget(AgentRuntimeProperties.LlmConfig.ReasoningConfig global,
                                       AgentRuntimeProperties.LlmConfig.ProviderReasoningConfig yamlProvider,
                                       JsonNode db,
                                       String selected,
                                       Integer effectiveMaxOutputTokens,
                                       long reserve) {
        if (selected == null || ReasoningEffort.NONE.equalsIgnoreCase(selected)) {
            return null;
        }
        boolean supported = true;
        if (db != null && db.has(PROP_THINKING_BUDGET_SUPPORTED)) {
            supported = db.path(PROP_THINKING_BUDGET_SUPPORTED).asBoolean(true);
        } else if (yamlProvider != null) {
            supported = yamlProvider.isThinkingBudgetSupported();
        }
        if (!supported) {
            return null;
        }
        Map<String, Long> thresholds = thresholds(global, yamlProvider, db);
        long raw = thresholds.getOrDefault(selected, 0L);
        long upper = effectiveMaxOutputTokens == null || effectiveMaxOutputTokens <= 0
                ? raw : Math.max(0L, effectiveMaxOutputTokens - reserve);
        long budget = Math.min(raw, upper);
        budget = Math.max(global.getMinThinkingBudget(), budget);
        return budget > 0 ? (int) Math.min(Integer.MAX_VALUE, budget) : null;
    }

    /** 厂商 key 大小写不敏感（枚举值 `GLM` 与 yaml 的 `glm` 必须能对上）。 */
    private static AgentRuntimeProperties.LlmConfig.ProviderReasoningConfig findProvider(
            AgentRuntimeProperties.LlmConfig.ReasoningConfig global, String company) {
        if (global.getProviders() == null || company == null || company.isBlank()) {
            return null;
        }
        for (Map.Entry<String, AgentRuntimeProperties.LlmConfig.ProviderReasoningConfig> entry
                : global.getProviders().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(company)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, Long> thresholds(
            AgentRuntimeProperties.LlmConfig.ReasoningConfig global,
            AgentRuntimeProperties.LlmConfig.ProviderReasoningConfig yamlProvider,
            JsonNode db) {
        Map<String, Long> map = new LinkedHashMap<>();
        if (global.getLevels() != null) {
            global.getLevels().forEach((k, v) -> map.put(k.toLowerCase(Locale.ROOT), v.getMinOutputTokens()));
        }
        if (yamlProvider != null && yamlProvider.getLevelOverrides() != null) {
            yamlProvider.getLevelOverrides().forEach((k, v) -> {
                if (v != null) map.put(k.toLowerCase(Locale.ROOT), v);
            });
        }
        if (db != null && db.has(PROP_LEVEL_OVERRIDES) && db.get(PROP_LEVEL_OVERRIDES).isObject()) {
            db.get(PROP_LEVEL_OVERRIDES).fields().forEachRemaining(e -> {
                if (e.getValue().isNumber()) {
                    map.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().asLong());
                }
            });
        }
        return map;
    }

    private static JsonNode parseReasoning(String providerConfigJson) {
        if (providerConfigJson == null || providerConfigJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JsonUtils.getMapper().readTree(providerConfigJson);
            if (root == null || !root.has(PROP_REASONING) || !root.get(PROP_REASONING).isObject()) {
                return null;
            }
            return root.get(PROP_REASONING);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> parseLevels(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JsonUtils.getMapper().readTree(json);
            return jsonArray(node);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> jsonArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static Boolean booleanOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asBoolean();
    }

    private static Double doubleOrNull(JsonNode node) {
        return node == null || node.isNull() || !node.isNumber() ? null : node.asDouble();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() ? null : node.asText();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
