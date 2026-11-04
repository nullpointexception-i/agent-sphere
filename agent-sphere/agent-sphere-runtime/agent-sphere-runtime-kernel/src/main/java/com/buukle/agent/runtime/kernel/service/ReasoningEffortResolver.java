package com.buukle.agent.runtime.kernel.service;

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
 *
 * <p>档位分三层：
 * <ul>
 *   <li>{@code defaultLevel}：无用户选择时的动态默认，受 {@code default-max-level} 封顶；</li>
 *   <li>{@code selectedLevel}：用户手选（即使不可行也保留，前端只警告）；</li>
 *   <li>{@code effectiveLevel}：本轮真正使用的档位——手选不可行时降到最高可行档（只降不升）。</li>
 * </ul>
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
            String effectiveLevel,
            Long effectiveMaxOutputTokens,
            Long answerReserveTokens,
            Integer thinkingBudgetTokens) {

        public static Resolved unsupported() {
            return new Resolved(false, ReasoningMode.LEVELS, List.of(),
                    null, null, null, null, null, null);
        }
    }

    /**
     * @param route                   模型路由（含 company / 路由覆盖字段）
     * @param providerConfigJson       agent_model_provider.config
     * @param effectiveMaxOutputTokens 本轮请求实际会发出的 max_tokens；null 表示未配置输出上限
     * @param requestedLevel           用户选择/run 固化档位；为空则使用动态默认
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

        String defaultLevel = pickDefault(ordered, views, levelOrder,
                global.getDefaultMaxLevel(), effectiveMaxOutputTokens);
        String selected = normalize(requestedLevel, ordered, defaultLevel);
        String effectiveLevel = pickEffective(ordered, views, selected);

        Integer thinkingBudget = null;
        if (ModelProviderCompany.QWEN.getValue().equalsIgnoreCase(company)) {
            thinkingBudget = qwenThinkingBudget(global, yamlProvider, db,
                    effectiveLevel, effectiveMaxOutputTokens, reserve);
        }

        return new Resolved(true, mode, views, defaultLevel, selected, effectiveLevel,
                effectiveMaxOutputTokens == null ? null : effectiveMaxOutputTokens.longValue(),
                effectiveMaxOutputTokens == null ? null : reserve, thinkingBudget);
    }

    /** 无用户选择时的动态默认：最高可行档，但不超过 default-max-level。 */
    private static String pickDefault(List<String> ordered, List<LevelView> views,
                                      List<String> levelOrder, String defaultMaxLevel,
                                      Integer effectiveMaxOutputTokens) {
        int capIndex = indexOfIgnoreCase(levelOrder, defaultMaxLevel);
        int visibleCap = capIndex < 0 ? ordered.size() - 1 : visibleIndexAtOrBelow(ordered, levelOrder, capIndex);
        if (visibleCap < 0) {
            visibleCap = ordered.size() - 1;
        }
        if (effectiveMaxOutputTokens == null || effectiveMaxOutputTokens <= 0) {
            return ordered.get(visibleCap);
        }
        String highestFeasible = null;
        for (int i = 0; i <= visibleCap; i++) {
            if (views.get(i).feasible()) {
                highestFeasible = views.get(i).value();
            }
        }
        return highestFeasible != null ? highestFeasible : ordered.get(0);
    }

    /** 手选档位不可行时降到最高可行档；不会升级。 */
    private static String pickEffective(List<String> ordered, List<LevelView> views, String selected) {
        int selectedIndex = indexOfIgnoreCase(ordered, selected);
        if (selectedIndex < 0) {
            selectedIndex = ordered.size() - 1;
        }
        if (views.get(selectedIndex).feasible()) {
            return views.get(selectedIndex).value();
        }
        for (int i = selectedIndex - 1; i >= 0; i--) {
            if (views.get(i).feasible()) {
                return views.get(i).value();
            }
        }
        return ordered.get(0);
    }

    private static String normalize(String requested, List<String> ordered, String defaultLevel) {
        if (requested == null || requested.isBlank()) {
            return defaultLevel;
        }
        int index = indexOfIgnoreCase(ordered, requested);
        return index < 0 ? defaultLevel : ordered.get(index);
    }

    private static int visibleIndexAtOrBelow(List<String> ordered, List<String> levelOrder, int capOrderIndex) {
        int result = -1;
        for (int i = 0; i < ordered.size(); i++) {
            int orderIndex = indexOfIgnoreCase(levelOrder, ordered.get(i));
            if (orderIndex >= 0 && orderIndex <= capOrderIndex) {
                result = i;
            }
        }
        return result;
    }

    private static int indexOfIgnoreCase(List<String> values, String target) {
        if (values == null || target == null) {
            return -1;
        }
        for (int i = 0; i < values.size(); i++) {
            if (target.equalsIgnoreCase(values.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private Integer qwenThinkingBudget(AgentRuntimeProperties.LlmConfig.ReasoningConfig global,
                                       AgentRuntimeProperties.LlmConfig.ProviderReasoningConfig yamlProvider,
                                       JsonNode db,
                                       String effectiveLevel,
                                       Integer effectiveMaxOutputTokens,
                                       long reserve) {
        if (effectiveLevel == null || ReasoningEffort.NONE.equalsIgnoreCase(effectiveLevel)) {
            return null;
        }
        boolean budgetSupported = true;
        if (db != null && db.has(PROP_THINKING_BUDGET_SUPPORTED)) {
            budgetSupported = db.path(PROP_THINKING_BUDGET_SUPPORTED).asBoolean(true);
        } else if (yamlProvider != null) {
            budgetSupported = yamlProvider.isThinkingBudgetSupported();
        }
        if (!budgetSupported) {
            return null;
        }
        Map<String, Long> thresholds = thresholds(global, yamlProvider, db);
        long raw = thresholds.getOrDefault(effectiveLevel, 0L);
        long upper = effectiveMaxOutputTokens == null || effectiveMaxOutputTokens <= 0
                ? raw : Math.max(0L, effectiveMaxOutputTokens - reserve);
        long budget = Math.min(raw, upper);
        budget = Math.max(global.getMinThinkingBudget(), budget);
        if (effectiveMaxOutputTokens != null && effectiveMaxOutputTokens > 0) {
            budget = Math.min(budget, effectiveMaxOutputTokens);
        }
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
