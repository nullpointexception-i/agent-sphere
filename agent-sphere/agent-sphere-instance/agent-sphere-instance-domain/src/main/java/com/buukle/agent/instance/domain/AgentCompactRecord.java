package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_compact_record")
public class AgentCompactRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** NULL = session-level compaction; non-null = run-local compaction for the current run. */
    private Long runId;
    private String status;
    private String summaryBefore;
    private String summaryAfter;
    private Long tokenCount;
    private Long compactedUptoRunId;
    /** Tail messages JSON retained by run-local compaction (no reasoning; image data URLs already stripped). */
    private String tailJson;
    private String errorMessage;
    @TableLogic
    private Boolean deleteFlag;
    @TableField(fill = FieldFill.INSERT)
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
