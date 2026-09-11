package com.buukle.agent.instance.dtvo.enums;

/** 统一 Timeline 行 content 解析键（避免业务逻辑内联魔法字符串）。 */
public enum TimelineContentKey {
    TEXT("text"),
    THINKING("thinking"),
    REPLY("reply"),
    DISPLAY_NAME("displayName"),
    STATUS("status"),
    ARGS("args"),
    ARTIFACT("artifact"),
    CLARIFICATION_ID("clarificationId"),
    TITLE("title"),
    OPTIONS("options"),
    RESPONSE("response"),
    STATE("state"),
    STARTED_AT("startedAt"),
    /** 子 Agent 类型（SUBAGENT 行）。 */
    AGENT_TYPE("agentType"),
    /** 子 Agent 引用/标识（SUBAGENT 行）。 */
    AGENT_REF("agentRef"),
    DURATION_MS("durationMs"),
    MODEL_NAME("modelName"),
    /** 用户消息附图引用（[{fileKey, contentType}]，前端按 fileKey 拉字节显示）。 */
    IMAGES("images"),
    /** 用量聚合（{promptTokens, completionTokens, totalTokens, cacheHitTokens, cacheMissTokens}；assistant / run_status 行）。 */
    USAGE("usage");

    private final String code;

    TimelineContentKey(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}