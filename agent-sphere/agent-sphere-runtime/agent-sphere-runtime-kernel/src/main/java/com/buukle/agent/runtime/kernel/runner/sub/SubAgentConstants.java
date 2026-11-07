package com.buukle.agent.runtime.kernel.runner.sub;

/**
 * Constants for the unified delegate sub-agent mechanism (tool name, capabilityType, execution mode, input schema, output contract).
 *
 * <p>lane output contract ({@code delegate-lane/v1}): all business fields of the sub-agent sit at the top level,
 * while all framework fields live inside the top-level {@code _meta} object; the framework never reads or writes top-level business keys:
 * <pre>{@code
 * {"<business fields...>": "...",
 *  "_meta": {"contract":"delegate-lane/v1", "status":"OK|ERROR|UNCERTAIN",
 *            "verdict":"COMPUTED|UPSTREAM_MISSING|CLAIM_UNVERIFIABLE",
 *            "errorCategory":"...", "upstream_visible":true, "upstream_raw":"...",
 *            "retryCount":0, "reason":"..."}}
 * }</pre>
 * <p>Delivery gating looks only at {@code _meta.status=="OK"}; without {@code _meta} it is treated as UNCERTAIN
 * (strict mode, incompatible with the old assumption that "bare business JSON means delivered").
 */
public final class SubAgentConstants {

    /** Name of the single execution entry tool (pseudo-tool). */
    public static final String DELEGATE_TOOL = "delegate";
    /** RuntimeTool.capabilityType marker (distinct from builtin/mcp/cli/skill). */
    public static final String CAPABILITY_TYPE_SUB_AGENT = "sub_agent";

    /** Execution mode: run directly inside the main loop (no isolation, no sub-run, no depth increase). */
    public static final String MODE_MAIN = "main";
    /** Execution mode: isolated sub-agent. */
    public static final String MODE_SUBAGENT = "subagent";

    /** lane output contract version (`_meta.contract`; an unknown version is a violation, preventing silent drift). */
    public static final String LANE_CONTRACT = "delegate-lane/v1";
    /** Framework namespace at the top level of the lane output. */
    public static final String META_KEY = "_meta";
    /** Correlation key of a framework failure wrapper (top level, for parent reconciliation). */
    public static final String META_WRAPPER_KEY = "key";
    /** Raw-text evidence of a framework failure wrapper (top level, truncated). */
    public static final String META_WRAPPER_RAW = "raw";

    // ---- Unified aggregation envelope (same shape for single lane and DAG) ----
    /** Aggregation summary node. */
    public static final String ENV_OVERALL = "overall";
    public static final String ENV_TOTAL = "total";
    public static final String ENV_OK = "ok";
    public static final String ENV_FAILED = "failed";
    public static final String ENV_FAILED_KEYS = "failedKeys";
    public static final String ENV_FENCED_KEYS = "fencedKeys";
    /** lane node fields. */
    public static final String LANE_RESULT = "result";
    public static final String LANE_META = "meta";
    public static final String LANE_RAW = "raw";
    public static final String LANE_FENCED = "fenced";
    public static final String LANE_DURATION_MS = "durationMs";
    public static final String LANE_RETRY_COUNT = "retryCount";
    /** Failure stage classification (not dispatched / upstream stranded / no output / tool error / content downgraded) for parent-side topology decisions. */
    public static final String META_FAILED_STAGE = "failedStage";
    /** List of dependency keys not delivered when an upstream is stranded. */
    public static final String META_DEPENDS_NOT_DELIVERED = "dependsOnNotDelivered";
    /** Framework content review (compared with the self-reported meta.status) to expose an OK that masks "structurally valid but empty content". */
    public static final String META_CONTENT_VERDICT = "contentVerdict";
    /** Non-fatal warning (B1: the fence was stripped but not enough to change the verdict). */
    public static final String META_WARNINGS = "warnings";
    public static final String WARNING_FENCED = "fenced";

    /** delegate input schema. */
    public static final String DELEGATE_SCHEMA = """
            {"type":"object","properties":{
              "goal":{"type":"string","description":"本次委派要达成的目标（必填）"},
              "args":{"type":"object","description":"委派任务的业务入参（可选）"},
              "agentRef":{"type":"string","description":"可选子 Agent 引用，形如 instance:<id>；其 systemPrompt+customInstructions 作为子 Agent 系统提示前缀，路由仍复用父级"},
              "mode":{"type":"string","enum":["main","subagent"],"description":"main=当前循环内直接执行（不建子 Agent）；subagent=隔离到子 Agent 执行"},
              "isolationReason":{"type":"string","description":"选择隔离的原因（mode=subagent 时建议填写）"},
              "tasks":{"type":"array","description":"可选：DAG 任务列表（要求 mode=subagent），Kahn 分层，层内并行，按 key 汇总结果","items":{"type":"object","properties":{
                "key":{"type":"string","description":"任务唯一 key，用于结果汇总与依赖引用"},
                "goal":{"type":"string","description":"该任务目标"},
                "agentRef":{"type":"string","description":"可选：该任务专属 agentRef，形如 instance:<id>"},
                "dependsOn":{"type":"array","items":{"type":"string"},"description":"依赖的上游任务 key"}
              },"required":["key","goal"]}},
              "skipPlanReason":{"type":"string","description":"未先规划而直接执行的原因（可选）"}
            },"required":["goal","mode"]}""";

    private SubAgentConstants() {
    }
}
