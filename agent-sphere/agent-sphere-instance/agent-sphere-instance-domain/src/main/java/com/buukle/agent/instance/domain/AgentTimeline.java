package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一 Timeline 打平展示索引（read-model）。
 * 会话级 seq（Redis INCR 分配）为排序/分页/SSE 唯一契约；正文由读取侧按 ref 解析，表内不落 payload。
 */
@Data
@TableName("agent_timeline")
public class AgentTimeline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long runId;
    private Long seq;
    /** 展示类型：user|assistant|tool|clarification|subagent|run_status|error。 */
    private String kind;
    /** 阶段/子类型：started|in_progress|succeeded|failed|answered|cancelled… */
    private String subtype;
    /** 展示状态：RUNNING|COMPLETED|FAILED|CANCELLED|PENDING|… */
    private String state;
    private Long groupId;
    /** 展示快照标签（不含正文）。 */
    private String title;
    private Long refRunId;
    private Long refInteractionId;
    private Long refToolCallId;
    private Long refSubAgentRunId;
    private Long refClarificationId;
    private String status;
    private String remark;
    @TableLogic
    private Boolean deleteFlag;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}