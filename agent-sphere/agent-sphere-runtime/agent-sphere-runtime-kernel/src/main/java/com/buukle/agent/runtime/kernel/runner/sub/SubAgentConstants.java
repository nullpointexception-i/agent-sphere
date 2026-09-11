package com.buukle.agent.runtime.kernel.runner.sub;

/**
 * 统一 delegate 子 Agent 机制的常量（工具名、capabilityType、执行模式、入参 schema、输出契约）。
 *
 * <p>lane 输出契约（{@code delegate-lane/v1}）：子 Agent 的业务字段全部位于顶层，
 * 框架字段全部位于顶层 {@code _meta} 对象内，框架永不读写顶层业务键：
 * <pre>{@code
 * {"<business fields...>": "...",
 *  "_meta": {"contract":"delegate-lane/v1", "status":"OK|ERROR|UNCERTAIN",
 *            "verdict":"COMPUTED|UPSTREAM_MISSING|CLAIM_UNVERIFIABLE",
 *            "errorCategory":"...", "upstream_visible":true, "upstream_raw":"...",
 *            "retryCount":0, "reason":"..."}}
 * }</pre>
 * <p>交付门控只看 {@code _meta.status=="OK"}；无 {@code _meta} 按 UNCERTAIN 处理
 * （严格模式，不兼容“裸业务 JSON 即交付”的旧假设）。
 */
public final class SubAgentConstants {

    /** 唯一执行入口工具名（pseudo-tool）。 */
    public static final String DELEGATE_TOOL = "delegate";
    /** RuntimeTool.capabilityType 标识（区别于 builtin/mcp/cli/skill）。 */
    public static final String CAPABILITY_TYPE_SUB_AGENT = "sub_agent";

    /** 执行模式：主循环内直接执行（不隔离、不建子 run、不增 depth）。 */
    public static final String MODE_MAIN = "main";
    /** 执行模式：隔离子 Agent。 */
    public static final String MODE_SUBAGENT = "subagent";

    /** lane 输出契约版本（`_meta.contract`；未知版本判违例，防止静默漂移）。 */
    public static final String LANE_CONTRACT = "delegate-lane/v1";
    /** lane 输出顶层的框架命名空间。 */
    public static final String META_KEY = "_meta";
    /** 框架失败包裹的关联 key（顶层，便于父级对账）。 */
    public static final String META_WRAPPER_KEY = "key";
    /** 框架失败包裹的原文证据（顶层，截断）。 */
    public static final String META_WRAPPER_RAW = "raw";

    // ---- 统一聚合信封（单车道与 DAG 同形）----
    /** 聚合总览节点。 */
    public static final String ENV_OVERALL = "overall";
    public static final String ENV_TOTAL = "total";
    public static final String ENV_OK = "ok";
    public static final String ENV_FAILED = "failed";
    public static final String ENV_FAILED_KEYS = "failedKeys";
    public static final String ENV_FENCED_KEYS = "fencedKeys";
    /** lane 节点字段。 */
    public static final String LANE_RESULT = "result";
    public static final String LANE_META = "meta";
    public static final String LANE_RAW = "raw";
    public static final String LANE_FENCED = "fenced";
    public static final String LANE_DURATION_MS = "durationMs";
    public static final String LANE_RETRY_COUNT = "retryCount";
    /** 非致命告警（B1：围栏被剥离但不及改判）。 */
    public static final String META_WARNINGS = "warnings";
    public static final String WARNING_FENCED = "fenced";

    /** delegate 入参 schema。 */
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
