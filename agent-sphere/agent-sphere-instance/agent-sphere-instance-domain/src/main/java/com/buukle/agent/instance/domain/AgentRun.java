package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_run")
public class AgentRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long threadId;
    private String finishStatus;
    private String type;
    private String userMessage;
    private String assistantReply;
    private String reasoning;
    private String intentClassification;
    private Long taskId;
    private String status;
    /** Snapshot of session run mode at creation: WRITE / READ_ONLY. */
    private String runMode;
    /** Snapshot of the instance completion contract for this run. */
    private String completionProtocol;
    /**
     * 创建时快照：自主任务（Worker / 任务型 run）禁止向用户澄清。
     *
     * <p>必须落库（V100）。此前只写在内存的 RunVO 上，任何恢复路径
     * （claimForResume / getRun / 审批或等待恢复）从 DB 重建 run 时都会丢，
     * 导致 ContextPreparer 判定 noClarification=false：finish 不再注册、
     * ask_clarification 反而注册，STRICT_FINISH 的 worker 因此没有收口工具而空转。
     */
    private Boolean noClarification;
    /** 聊天图片附件引用 JSON（[{"fileKey","contentType"}]）；Timeline USER 行回显用。 */
    private String attachments;
    /** 命中循环次数上限被强收口（任务守卫据此判失败，避免"半成品"冒充完成）。 */
    private Boolean loopCapped;
    /** 已消耗的 agent 循环轮次；跨挂起恢复递增，使 maxLoopCount 真正成为 per-run 预算。 */
    private Integer loopCount;
    /**
     * 本 run 生效的轮次上限（V88 起固化）。
     *
     * <p>此前每次进入循环都重新解析上限，而 run 有 5 个重入入口、只有首次在任务请求线程上
     * （持有 TaskLoopLimitHolder），其余入口在调度线程上会丢失 TASK 层而回落到较小值。
     * 固化为 null 时才重新解析。
     */
    private Integer loopLimit;
    /**
     * 失败原因（V87 起）。此前 run 挂了不留任何原因，事件里的 errorMessage 被丢弃、
     * 表上又没有对应列，导致「为什么失败」事后完全无法回答。
     */
    private String errorMessage;
    /** 挂起类型（V115）：WAIT=wait 工具；LLM_RETRY=上游瞬时失败重试。 */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String waitKind;
    /** 当前已发生的 LLM 瞬时失败次数（V115），用于梯度退避；成功一轮后归零。 */
    private Integer retryAttempt;
    /** 本 run 实际使用的主模型路由（V116）；挂起恢复时用它，避免从实例重新解析导致换模型。 */
    private Long modelRouteId;
    /** 本 run 固化的思考强度（V118）。 */
    private String reasoningEffort;
    /** 本 run 固化的思考预算（Qwen thinking_budget，V118）。 */
    private Integer reasoningBudgetTokens;
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
