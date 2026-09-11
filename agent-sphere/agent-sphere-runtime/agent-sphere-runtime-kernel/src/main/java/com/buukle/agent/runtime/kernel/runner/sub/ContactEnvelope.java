package com.buukle.agent.runtime.kernel.runner.sub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

/**
 * 子 Agent 结构化接触信封：把 task 私有入参（args）与 dependsOn 上游结果（upstream）
 * 以确定性 JSON 载体注入子 Agent，避免依赖 args 透传或 goal 文本内联。
 *
 * <pre>{@code
 * {"schema":"v1-delegate-contact","laneKey":"k_base","args":{...},
 *  "upstream":{"k_dep":{...}},"upstream_visible":true,"upstream_raw":"{...}","truncated":false}
 * }</pre>
 *
 * <p>{@code upstream_visible}/{@code upstream_raw} 由框架计算（P0 探针字段），
 * 子 Agent 只需引用/复述，无需自报，从根上消除“探针口径不一致”。
 *
 * <p>由 {@link DelegateService} 构建并经 ExecBinding 传给 {@code SessionSubRunner}，
 * 后者在渲染 goal 之后追加为一条独立 user 消息；也可由 {@code SubAgentPolicy}
 * 递归内联进 prompt 作纵深防御。
 */
public final class ContactEnvelope {

    public static final String SCHEMA = "v1-delegate-contact";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA_KEY = "schema";
    private static final String LANE_KEY = "laneKey";
    private static final String ARGS_KEY = "args";
    private static final String UPSTREAM_KEY = "upstream";
    private static final String UPSTREAM_VISIBLE_KEY = "upstream_visible";
    private static final String UPSTREAM_RAW_KEY = "upstream_raw";
    private static final String TRUNCATED_KEY = "truncated";

    private final String laneKey;
    private final JsonNode args;
    private final JsonNode upstream;
    private final boolean truncated;

    private ContactEnvelope(String laneKey, JsonNode args, JsonNode upstream, boolean truncated) {
        this.laneKey = laneKey;
        this.args = args == null || !args.isObject() ? JSON.createObjectNode() : args;
        this.upstream = upstream == null || !upstream.isObject() ? JSON.createObjectNode() : upstream;
        this.truncated = truncated;
    }

    /** 从任务 key/args/upstream 构建；无任何数据时返回 null。 */
    public static ContactEnvelope of(String laneKey, JsonNode args, JsonNode upstream) {
        ContactEnvelope env = new ContactEnvelope(laneKey,
                args == null || !args.isObject() ? JSON.createObjectNode() : args,
                upstream == null || !upstream.isObject() ? JSON.createObjectNode() : upstream,
                false);
        return env.hasData() ? env : null;
    }

    public boolean hasData() {
        return !args.isEmpty() || !upstream.isEmpty();
    }

    public JsonNode argsNode() {
        return args;
    }

    public String laneKey() {
        return laneKey;
    }

    public boolean isTruncated() {
        return truncated;
    }

    /**
     * 上游超预算时的截断副本：按 key 截断各上游 value 的 JSON 文本并标注，
     * 保证信封本身永远完整可解析（P0 信封免截断）。
     */
    public ContactEnvelope truncateUpstream(int maxBytes) {
        String raw = upstream.toString();
        if (raw.length() <= maxBytes) {
            return this;
        }
        ObjectNode cut = JSON.createObjectNode();
        int perKey = Math.max(64, maxBytes / Math.max(1, upstream.size()));
        upstream.fields().forEachRemaining(e -> {
            String v = e.getValue().toString();
            cut.put(e.getKey(), v.length() > perKey ? v.substring(0, perKey) + "...[truncated]" : v);
        });
        return new ContactEnvelope(laneKey, args, cut, true);
    }

    public String serialize() {
        return toJsonNode().toString();
    }

    private JsonNode toJsonNode() {
        ObjectNode root = JSON.createObjectNode();
        root.put(SCHEMA_KEY, SCHEMA);
        if (StringUtils.hasText(laneKey)) {
            root.put(LANE_KEY, laneKey);
        }
        root.set(ARGS_KEY, args);
        if (!upstream.isEmpty()) {
            root.set(UPSTREAM_KEY, upstream);
        }
        // P0 标准探针：由框架计算，子 Agent 引用即可
        root.put(UPSTREAM_VISIBLE_KEY, !upstream.isEmpty());
        root.put(UPSTREAM_RAW_KEY, upstream.toString());
        if (truncated) {
            root.put(TRUNCATED_KEY, true);
        }
        return root;
    }

    /** 从 JSON 文本解析；非对象/无数据返回 null。 */
    public static ContactEnvelope parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(raw.trim());
            if (node == null || !node.isObject()) {
                return null;
            }
            String laneKey = node.path(LANE_KEY).isTextual() ? node.get(LANE_KEY).asText() : null;
            JsonNode args = node.path(ARGS_KEY);
            JsonNode upstream = node.path(UPSTREAM_KEY);
            return new ContactEnvelope(laneKey,
                    args.isObject() ? args : JSON.createObjectNode(),
                    upstream.isObject() ? upstream : JSON.createObjectNode(), false);
        } catch (Exception ignored) {
            return null;
        }
    }
}