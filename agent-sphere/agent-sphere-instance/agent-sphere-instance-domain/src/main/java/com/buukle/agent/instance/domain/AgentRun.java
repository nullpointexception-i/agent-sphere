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
