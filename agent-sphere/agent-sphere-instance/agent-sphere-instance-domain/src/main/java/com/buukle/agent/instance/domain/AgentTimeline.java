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
 * Unified flattened Timeline display index (read-model).
 * Session-level seq (allocated via Redis INCR) is the single contract for ordering/paging/SSE;
 * the body is resolved on the read side by ref, and no payload is stored in the table.
 */
@Data
@TableName("agent_timeline")
public class AgentTimeline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long runId;
    private Long seq;
    /** Display type: user|assistant|tool|clarification|subagent|compaction|run_status|error. */
    private String kind;
    /** Phase/subtype: started|in_progress|succeeded|failed|answered|cancelled… */
    private String subtype;
    /** Display state: RUNNING|COMPLETED|FAILED|CANCELLED|PENDING|… */
    private String state;
    private Long groupId;
    /** Display snapshot title (no body). */
    private String title;
    private Long refRunId;
    private Long refInteractionId;
    private Long refToolCallId;
    private Long refSubAgentRunId;
    private Long refClarificationId;
    /** Tool approval record id (TOOL_APPROVAL row). */
    private Long refApprovalId;
    /** Compaction record id (COMPACTION row: body resolved from agent_compact_record.summary_after). */
    private Long refCompactId;
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