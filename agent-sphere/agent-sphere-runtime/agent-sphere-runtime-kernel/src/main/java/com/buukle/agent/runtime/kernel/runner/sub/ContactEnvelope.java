package com.buukle.agent.runtime.kernel.runner.sub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

/**
 * Structured contact envelope for sub-agents: injects the task's private inputs (args) and the dependsOn upstream results (upstream)
 * into the sub-agent as a deterministic JSON carrier, avoiding reliance on args pass-through or inlining into the goal text.
 *
 * <pre>{@code
 * {"schema":"v1-delegate-contact","laneKey":"k_base","args":{...},
 *  "upstream":{"k_dep":{...}},"upstream_visible":true,"upstream_raw":"{...}","truncated":false}
 * }</pre>
 *
 * <p>{@code upstream_visible}/{@code upstream_raw} are computed by the framework (P0 probe fields);
 * the sub-agent only needs to reference/restate them, never self-report, which removes "probe inconsistency" at the root.
 *
 * <p>Built by {@link DelegateService} and passed to {@code SessionSubRunner} via ExecBinding;
 * the latter appends it as a separate user message after rendering the goal, and {@code SubAgentPolicy}
 * may also inline it recursively into the prompt as defense in depth.
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

    /** Builds from task key/args/upstream; returns null when there is no data at all. */
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
     * Truncated copy when upstream exceeds the budget: truncates each upstream value's JSON text by key and marks it,
     * keeping the envelope itself always complete and parseable (P0 envelopes are exempt from truncation).
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
        // P0 standard probes: computed by the framework; the sub-agent only needs to reference them
        root.put(UPSTREAM_VISIBLE_KEY, !upstream.isEmpty());
        root.put(UPSTREAM_RAW_KEY, upstream.toString());
        if (truncated) {
            root.put(TRUNCATED_KEY, true);
        }
        return root;
    }

    /** Parses from JSON text; returns null when it is not an object / has no data. */
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