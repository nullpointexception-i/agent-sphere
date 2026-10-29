package com.buukle.agent.instance.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_tool_approval")
public class AgentToolApproval {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String approvalId;
    private Long sessionId;
    private Long runId;
    private String toolCallsJson;
    private String decisionsJson;
    private String status;
    private String approvedBy;
    private String comment;
    private Long tenantId;
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
