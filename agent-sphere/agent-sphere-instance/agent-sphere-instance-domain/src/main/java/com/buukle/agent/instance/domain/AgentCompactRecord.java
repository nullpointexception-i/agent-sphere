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
    /** NULL = session 级压缩；非空 = 当前 run 的 run-local 压缩。 */
    private Long runId;
    private String status;
    private String summaryBefore;
    private String summaryAfter;
    private Long tokenCount;
    private Long compactedUptoRunId;
    /** run-local 压缩保留的尾部消息 JSON（不带 reasoning；图片 data URL 已剥离）。 */
    private String tailJson;
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
