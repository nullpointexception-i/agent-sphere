package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_llm_interaction_record")
public class AgentLlmInteractionRecord {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long sessionId;
    /** 主 Agent 问答轮次序号（= run.loop_count）；其余调用为 NULL。 */
    private Integer turnNo;
    /** 同一轮内的路由尝试序号：0=主路由，1..N=fallback。 */
    private Integer attemptNo;
    /** 同一 (run, turn, attempt) 下的调用序号：每次重试/新一轮各自一行，避免成功结果被覆盖。 */
    private Integer retryNo;
    /** 该次调用在 LLM 退避梯度上的档位（1..N，V117）；非重试轮为 NULL。 */
    private Integer retryAttempt;
    /** RUNNING / COMPLETED / FAILED。 */
    private String status;
    private String interactionType;
    private String modelName;
    private String requestBody;
    private String responseBody;
    private Integer httpStatus;
    private Integer durationMs;
    /** 供应商真实 finish_reason（stop/length/content_filter/tool_calls），截断可审计。 */
    private String finishReason;
    private String errorMessage;
    private Boolean success;
    /** 该次 LLM 调用独立的 thinking(reasoning_content) 全文；与 response_body(reply+合并) 拆分。 */
    private String reasoning;
    /** reply 正文（与 reasoning 拆分后的纯回复文本；缺省保留 response_body）。 */
    private String replyContent;
    /** 归属的子 Agent 运行（NULL=主 Agent）。 */
    private Long subAgentRunId;
    /** 归一化用量 JSON（TokenUsage，供应商无关镜像，冗余标量列供聚合）。 */
    private String usage;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cacheHitTokens;
    private Integer cacheMissTokens;
    @TableLogic
    private Boolean deleteFlag;
    private Long tenantId;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
