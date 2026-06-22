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
    private String status;
    private String summaryBefore;
    private String summaryAfter;
    private Long tokenCount;
    private Long compactedUptoRunId;
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
